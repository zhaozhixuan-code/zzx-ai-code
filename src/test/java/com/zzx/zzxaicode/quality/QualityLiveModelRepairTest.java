package com.zzx.zzxaicode.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.zzxaicode.ai.common.ProjectFileOperations;
import com.zzx.zzxaicode.ai.springai.*;
import com.zzx.zzxaicode.config.*;
import com.zzx.zzxaicode.config.springai.*;
import com.zzx.zzxaicode.quality.check.BrowserQualityChecker;
import com.zzx.zzxaicode.quality.model.*;
import com.zzx.zzxaicode.quality.workspace.QualityWorkspaceService;
import com.zzx.zzxaicode.utils.ScreenshotDriverFactory;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式启用真实模型和 Chrome；只测试固定静态资产的运行修复，不冒充 Vue 容器构建验收。 */
@EnabledIfSystemProperty(named="quality.live-model",matches="true")
class QualityLiveModelRepairTest {
    @TempDir Path root;
    /** 固定首屏异常经真实工具修改后通过真实浏览器；不启动应用、不读写用户数据库和 Redis。 */
    @Test void realModelRepairsBrowserEvidenceThroughRestrictedTools() throws Exception {
        StandardEnvironment env=new StandardEnvironment();
        var loader=new YamlPropertySourceLoader();
        for(String path:List.of("src/main/resources/application.yml","src/main/resources/application-local.yml")) {
            if(Files.isRegularFile(Path.of(path))) for(var source:loader.load(path,new FileSystemResource(path))) env.getPropertySources().addFirst(source);
        }
        var connection=Binder.get(env).bind("spring.ai.openai",Bindable.of(OpenAiConnectionProperties.class)).get();
        var settings=Binder.get(env).bind("app.ai",Bindable.of(SpringAiProperties.class)).orElse(new SpringAiProperties());
        Assumptions.assumeTrue(connection.getApiKey()!=null && !connection.getApiKey().contains("${"),"没有已配置模型密钥，未调用真实模型");
        settings.setTimeout(Duration.ofSeconds(90));
        var model=new SpringAiModelConfig().springAiVueModel(connection,settings,ObservationRegistry.NOOP);
        var memory=mock(SpringAiConversationRepository.class);
        when(memory.load(anyLong(),anyString())).thenAnswer(c->new ArrayList<Message>(List.of(new UserMessage(c.getArgument(1,String.class)))));
        var generator=new SpringAiCodeGenerator(model,model,new SpringAiPromptLoader(),memory,
                new SpringAiToolCallbacks(new ObjectMapper(),new ProjectFileOperations(root)),settings);
        Files.createDirectories(root.resolve("public"));
        String html="<!doctype html><meta charset='UTF-8'><h1>计数器</h1><button id='count'>0</button><script src='./app.js'></script>";
        Files.writeString(root.resolve("public/app.js"),"throw new Error('early-fixture-error');\nlet count=0;document.getElementById('count').addEventListener('click',()=>{document.getElementById('count').textContent=String(++count)});");
        var workspace=new QualityWorkspaceService.Workspace(1,UUID.randomUUID().toString(),root);
        var browserProperties=new ScreenshotProperties();browserProperties.setAutoDownload(false);
        var checker=new BrowserQualityChecker(new ScreenshotDriverFactory(browserProperties));
        var failed=checker.check(workspace,candidate(workspace,0,html),Duration.ofSeconds(20),new AtomicBoolean(),new AtomicBoolean(true));
        assertFalse(failed.isPassed());assertTrue(failed.items().getLast().problems().getFirst().summary().contains("early-fixture-error"));
        var support=new QualityRepairSupport();var before=support.snapshot(root);
        String requirement="保留已有计数器标题和按钮点击加一行为。这里只修复 public/app.js 的首屏异常，不创建新工程，不改变 HTML、构建配置或其他文件。";
        var session=generator.openWorkspaceSession(1,"2",requirement,root);
        try { session.stream(support.prompt(requirement,failed),10,Duration.ofMinutes(2)).blockLast(Duration.ofMinutes(2).plusSeconds(5)); }
        finally { session.cancelAndAwaitFiles(); }
        var audit=support.audit(1,failed,before,support.snapshot(root));
        assertEquals(List.of("public/app.js"),audit.changedFiles());
        var passed=checker.check(workspace,candidate(workspace,1,html),Duration.ofSeconds(20),new AtomicBoolean(),new AtomicBoolean(true));
        assertTrue(passed.isPassed(),passed.toString());
        verify(memory,never()).save(anyLong(),anyList());
    }
    /** @param workspace 测试工作区 @param round 轮次 @param html 固定页面 @return 受控资产复制报告，非 npm 构建报告 */
    private QualityReport candidate(QualityWorkspaceService.Workspace workspace,int round,String html) throws Exception {
        String artifact="quality-dist-"+round;Path dist=root.resolve(artifact);Files.createDirectories(dist);
        Files.writeString(dist.resolve("index.html"),html);Files.copy(root.resolve("public/app.js"),dist.resolve("app.js"));
        Instant now=Instant.now();return new QualityReport(workspace.taskId(),1,round,now,now,
                List.of(new QualityCheckItem("controlled-fixture-copy",true,QualityCheckItemStatus.PASSED,0,List.of())),artifact);
    }
}
