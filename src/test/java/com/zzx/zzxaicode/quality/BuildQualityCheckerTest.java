package com.zzx.zzxaicode.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.zzxaicode.quality.check.*;
import com.zzx.zzxaicode.quality.config.QualityCheckProperties;
import com.zzx.zzxaicode.quality.model.*;
import com.zzx.zzxaicode.quality.workspace.QualityWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 不执行生成项目：用受控构建结果验证分类和产物真实性，用真实 Java 子进程验证回收。 */
class BuildQualityCheckerTest {
    @TempDir Path root;

    /** 退出码零也必须存在本轮资源；旧 dist 不作为本轮成功证据。 */
    @Test void rejectsFalseSuccessAndClassifiesEnvironment() throws Exception {
        var runner = mock(ControlledProcessRunner.class);
        when(runner.run(anyList(), any(), any(), any())).thenReturn(new ControlledProcessRunner.Result(0,false,1,"ok"));
        Files.writeString(root.resolve("package.json"), "{}");
        Files.writeString(root.resolve("index.html"), "<div>source</div>");
        Files.createDirectories(root.resolve("dist"));
        Files.writeString(root.resolve("dist/index.html"), "old");
        var workspace = new QualityWorkspaceService.Workspace(1, UUID.randomUUID().toString(), root);
        var checker = new BuildQualityChecker(runner, new QualityCheckProperties(), new ObjectMapper());
        var report = checker.check(workspace,0,new AtomicBoolean(),new AtomicBoolean(true));
        assertFalse(report.isPassed());
        assertEquals(QualityProblemCategory.MISSING_FILE,report.items().getFirst().problems().getFirst().category());
        when(runner.run(anyList(), any(), any(), any())).thenReturn(new ControlledProcessRunner.Result(125,false,1,"Docker unavailable"));
        assertEquals(QualityProblemCategory.ENVIRONMENT,checker.check(workspace,1,new AtomicBoolean(),new AtomicBoolean(true))
                .items().getFirst().problems().getFirst().category());
        Path artifact = root.resolve("quality-dist-2");
        Files.createDirectories(artifact);
        Files.writeString(artifact.resolve("index.html"), "<script src='./missing.js'></script>");
        assertFalse(checker.validArtifact(artifact));
        Files.writeString(artifact.resolve("missing.js"), "console.log('ok')");
        assertTrue(checker.validArtifact(artifact));
    }

    /** 实际输出超过管道容量仍持续排空；超时强制停止独立 Java 进程。 */
    @Test void drainsFloodAndStopsOnTimeoutAndCancellation() throws Exception {
        var runner = new ControlledProcessRunner();
        Path fixture = root.resolve("ProcessFixture.java");
        Files.writeString(fixture,"class ProcessFixture { public static void main(String[] a) throws Exception { if(a[0].equals(\"flood\")) { for(int i=0;i<20000;i++) System.out.println(\"12345678901234567890\"); } else { Thread.sleep(30000); } } }");
        String java = Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        var flood=runner.run(List.of(java,fixture.toString(),"flood"),root,Duration.ofSeconds(15),new AtomicBoolean());
        assertEquals(0,flood.exitCode());
        assertTrue(flood.output().length()<=65536);
        var timeout=runner.run(List.of(java,fixture.toString(),"wait"),root,Duration.ofMillis(500),new AtomicBoolean());
        assertTrue(timeout.timedOut());
        assertThrows(InterruptedException.class,()->runner.run(List.of(java,fixture.toString(),"wait"),root,Duration.ofSeconds(5),new AtomicBoolean(true)));
    }
}
