package com.zzx.zzxaicode.quality;
import com.zzx.zzxaicode.config.ScreenshotProperties;
import com.zzx.zzxaicode.quality.check.*;
import com.zzx.zzxaicode.quality.model.*;
import com.zzx.zzxaicode.quality.workspace.QualityWorkspaceService;
import com.zzx.zzxaicode.utils.ScreenshotDriverFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 loopback 代理与可选真实 Chrome，覆盖早期脚本异常、资源缺失、空白及环境异常。 */
class BrowserQualityCheckerTest {
    @TempDir Path root;
    /** 代理只服务当前候选 origin；越界、私网地址与任意主机不会被转发。 */
    @Test void localProxyRejectsForeignTargetsAndTraversal() throws Exception {
        Files.writeString(root.resolve("index.html"),"fixture");
        try(var server=new CandidatePreviewServer(root)) {
            URI origin=URI.create(server.origin());
            var client=HttpClient.newBuilder().proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1",origin.getPort()))).build();
            assertEquals(200,client.send(HttpRequest.newBuilder(URI.create(server.origin()+"/index.html")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403,client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:9/private")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403,client.send(HttpRequest.newBuilder(URI.create(server.origin()+"/../secret")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    /** 无法创建浏览器属于环境故障，不能驱动模型改源码。 */
    @Test void browserStartupFailureIsEnvironment() throws Exception {
        var factory=mock(ScreenshotDriverFactory.class);
        when(factory.createQualityDriver(anyString(),any())).thenThrow(new org.openqa.selenium.WebDriverException("unavailable"));
        var report=new BrowserQualityChecker(factory).check(workspace(),build(),Duration.ofSeconds(10),new AtomicBoolean(),new AtomicBoolean(true));
        assertFalse(report.isPassed());assertEquals(QualityProblemCategory.ENVIRONMENT,report.items().getLast().problems().getFirst().category());
    }
    /** 安装本地匹配驱动时显式启用；每个场景独立创建并回收真实 Chrome。 */
    @Test @EnabledIfSystemProperty(named="quality.live-browser",matches="true")
    void realChromeDetectsEarlyErrorsResourcesAndBlank() throws Exception {
        var props=new ScreenshotProperties();props.setAutoDownload(false);
        var checker=new BrowserQualityChecker(new ScreenshotDriverFactory(props));
        Files.createDirectories(root.resolve("quality-dist-0"));
        for(String page:List.of("<h1>正常页面</h1>","<h1>错误页面</h1><script>throw new Error('early-fixture-error')</script>",
                "<h1>资源错误</h1><script src='missing.js'></script>","<div id='app'></div>")) {
            Files.writeString(root.resolve("quality-dist-0/index.html"),page);
            AtomicBoolean safe=new AtomicBoolean(true);
            var report=checker.check(workspace(),build(),Duration.ofSeconds(20),new AtomicBoolean(),safe);
            assertEquals(page.equals("<h1>正常页面</h1>"),report.isPassed(),report.toString());
            assertTrue(safe.get(),"浏览器应已回收");
            assertTrue(Files.isRegularFile(root.resolve("quality-browser-0.png")));
        }
    }
    /** @return 独立受控 fixture 工作区 */
    private QualityWorkspaceService.Workspace workspace() { return new QualityWorkspaceService.Workspace(1,UUID.randomUUID().toString(),root); }
    /** @return 已通过构建的受控候选报告，真实浏览器测试不冒充真实构建 */
    private QualityReport build() { Instant now=Instant.now();return new QualityReport("fixture",1,0,now,now,List.of(new QualityCheckItem("build",true,QualityCheckItemStatus.PASSED,1,List.of())),"quality-dist-0"); }
}
