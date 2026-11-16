package com.zzx.zzxaicode.ai.springai;

import com.zzx.zzxaicode.ai.common.AiCodeGenerator;
import com.zzx.zzxaicode.constants.AppConstant;
import com.zzx.zzxaicode.controller.WorkflowSseController;
import com.zzx.zzxaicode.core.AiCodeGeneratorFacade;
import com.zzx.zzxaicode.core.builder.VueProjectBuilder;
import com.zzx.zzxaicode.core.handler.JsonMessageStreamHandler;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import com.zzx.zzxaicode.model.po.User;
import com.zzx.zzxaicode.monitor.AiModelMetricsCollector;
import com.zzx.zzxaicode.service.ChatHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证业务保存/构建、项目事件、监控及学习接口停用的集成边界。 */
class SpringAiIntegrationBoundaryTest {
    /** 普通生成正常保存，错误或缺失字段不得产生成功保存结果。 */
    @Test
    void facadeSavesHtmlAndMultiFilesAndRejectsInvalidOutput() throws Exception {
        var generator = mock(AiCodeGenerator.class);
        var facade = new AiCodeGeneratorFacade(generator, mock(VueProjectBuilder.class));
        long appId = System.nanoTime();
        when(generator.stream(appId, "page", CodeGenTypeEnum.HTML)).thenReturn(Flux.just("```html\n", "<h1>ok</h1>\n```"));
        facade.generateAndSaveCodeStream("page", CodeGenTypeEnum.HTML, appId).blockLast(Duration.ofSeconds(3));
        Path htmlFile = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "html_" + appId, "index.html");
        assertEquals("<h1>ok</h1>", Files.readString(htmlFile));
        when(generator.stream(appId, "multi", CodeGenTypeEnum.MULTI_FILE)).thenReturn(Flux.just(
                "```html\n<main>multi</main>\n```\n```css\nmain{}\n```\n```js\nconsole.log(1)\n```"));
        facade.generateAndSaveCodeStream("multi", CodeGenTypeEnum.MULTI_FILE, appId).blockLast(Duration.ofSeconds(3));
        Path multiDir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "multi-file_" + appId);
        assertEquals("main{}", Files.readString(multiDir.resolve("style.css")));
        assertEquals("console.log(1)", Files.readString(multiDir.resolve("script.js")));
        long invalidId = appId + 1;
        when(generator.stream(invalidId, "invalid", CodeGenTypeEnum.MULTI_FILE)).thenReturn(Flux.just("{}"));
        assertThrows(BusinessException.class, () -> facade.generateAndSaveCodeStream("invalid", CodeGenTypeEnum.MULTI_FILE, invalidId)
                .blockLast(Duration.ofSeconds(3)));
        assertFalse(Files.exists(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "multi-file_" + invalidId)));
        when(generator.stream(invalidId, "missingFiles", CodeGenTypeEnum.MULTI_FILE)).thenReturn(Flux.just(
                "```html\n<html><link href=\"style.css\"><script src=\"script.js\"></script></html>\n```"));
        assertThrows(BusinessException.class, () -> facade.generateAndSaveCodeStream("missingFiles", CodeGenTypeEnum.MULTI_FILE, invalidId)
                .blockLast(Duration.ofSeconds(3)));
        assertFalse(Files.exists(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "multi-file_" + invalidId)));
        when(generator.stream(invalidId, "failure", CodeGenTypeEnum.HTML)).thenReturn(Flux.concat(
                Flux.just("<h1>partial"), Flux.error(new IllegalStateException("disconnected"))));
        assertThrows(IllegalStateException.class, () -> facade.generateAndSaveCodeStream("failure", CodeGenTypeEnum.HTML, invalidId)
                .blockLast(Duration.ofSeconds(3)));
        assertFalse(Files.exists(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "html_" + invalidId)));
    }

    /** Vue 必须在构建完成后结束，构建失败以错误信号传播。 */
    @Test
    void vueBuildFailureDoesNotCompleteSuccessfully() {
        var generator = mock(AiCodeGenerator.class);
        var builder = mock(VueProjectBuilder.class);
        var facade = new AiCodeGeneratorFacade(generator, builder);
        when(generator.stream(11, "vue", CodeGenTypeEnum.VUE_PROJECT)).thenReturn(Flux.just("event"));
        when(builder.buildProject(anyString())).thenReturn(false);
        assertThrows(BusinessException.class, () -> facade.generateAndSaveCodeStream("vue", CodeGenTypeEnum.VUE_PROJECT, 11L)
                .blockLast(Duration.ofSeconds(3)));
        verify(builder).buildProject(AppConstant.CODE_OUTPUT_ROOT_DIR + java.io.File.separator + "vue_project_11");
        when(builder.buildProject(anyString())).thenReturn(true);
        assertDoesNotThrow(() -> facade.generateAndSaveCodeStream("vue", CodeGenTypeEnum.VUE_PROJECT, 11L)
                .blockLast(Duration.ofSeconds(3)));
    }

    /** 工具失败文本不能被渲染为成功写入的代码块。 */
    @Test
    void eventDisplayShowsRealToolFailure() {
        var history = mock(ChatHistoryService.class);
        var user = new User();
        user.setId(1L);
        var events = Flux.just("{\"type\":\"tool_request\",\"id\":\"1\",\"name\":\"writeFile\"}",
                "{\"type\":\"tool_executed\",\"id\":\"1\",\"name\":\"writeFile\",\"arguments\":\"{\\\"relativeFilePath\\\":\\\"a.txt\\\",\\\"content\\\":\\\"x\\\"}\",\"result\":\"错误：拒绝写入\"}");
        String text = String.join("", new JsonMessageStreamHandler().handle(events, history, 11, user).collectList().block());
        assertTrue(text.contains("[工具失败]"));
        assertFalse(text.contains("```"));
        verify(history).addChatMessage(eq(11L), contains("错误：拒绝写入"), eq("ai"), eq(1L));
    }

    /** 不同模型轮次可复用调用 ID，展示层必须逐次显示生成器已经去重后的工具事件。 */
    @Test
    void eventDisplayDoesNotSuppressToolRequestsFromLaterRounds() {
        var history = mock(ChatHistoryService.class);
        var user = new User();
        user.setId(1L);
        String request = "{\"type\":\"tool_request\",\"id\":\"call_1\",\"name\":\"readFile\"}";
        String executed = "{\"type\":\"tool_executed\",\"id\":\"call_1\",\"name\":\"readFile\",\"arguments\":\"{\\\"relativeFilePath\\\":\\\"a.txt\\\"}\",\"result\":\"文件内容\"}";
        List<String> parts = new JsonMessageStreamHandler().handle(Flux.just(request, executed, request, executed),
                history, 11, user).collectList().block(Duration.ofSeconds(3));
        assertNotNull(parts);
        assertEquals(2, parts.stream().filter(part -> part.contains("[选择工具]")).count());
        assertEquals(2, parts.stream().filter(part -> part.contains("[工具调用]")).count());
    }

    /** 工具已返回参数解析失败时，展示层直接显示错误，不再次解析模型给出的无效 JSON。 */
    @Test
    void malformedToolArgumentsRemainVisibleAsToolFailure() {
        var history = mock(ChatHistoryService.class);
        var user = new User();
        user.setId(1L);
        String event = "{\"type\":\"tool_executed\",\"id\":\"call_1\",\"name\":\"writeFile\",\"arguments\":\"not-json\",\"result\":\"错误：工具参数必须是有效 JSON 对象\"}";
        List<String> parts = new JsonMessageStreamHandler().handle(Flux.just(event), history, 11, user)
                .collectList().block(Duration.ofSeconds(3));
        assertNotNull(parts);
        assertTrue(String.join("", parts).contains("[工具失败]"));
        verify(history).addChatMessage(eq(11L), contains("错误：工具参数必须是有效 JSON 对象"), eq("ai"), eq(1L));
    }

    /** 监控保留模型/用户/应用标签，只记录服务端原始 usage 中实际存在的字段。 */
    @Test
    void observationsKeepTagsWithoutInventingMissingTokenUsage() {
        var metrics = mock(AiModelMetricsCollector.class);
        var handler = new SpringAiModelObservationHandler(metrics);
        var options = OpenAiChatOptions.builder().model("fake").toolContext(Map.of("appId", "11", "userId", "1")).build();
        var context = ChatModelObservationContext.builder().prompt(new Prompt("hello", options)).provider("openai").build();
        handler.onStart(context);
        context.setResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
        handler.onStop(context);
        verify(metrics).recordRequest("1", "11", "fake", "success");
        verify(metrics, never()).recordTokenUsage(anyString(), anyString(), anyString(), anyString(), anyLong());
        clearInvocations(metrics);
        var nativeUsage = new OpenAiApi.Usage(null, 2, null);
        context.setResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("done"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(2, null, null, nativeUsage)).build()));
        handler.onStop(context);
        verify(metrics).recordTokenUsage("1", "11", "fake", "input", 2L);
        verify(metrics, never()).recordTokenUsage(anyString(), anyString(), anyString(), eq("output"), anyLong());
        verify(metrics, never()).recordTokenUsage(anyString(), anyString(), anyString(), eq("total"), anyLong());
    }

    /** 保留方法及工作流实现，演示方法不再映射为 HTTP 接口。 */
    @Test
    void workflowMethodsHaveNoHttpMapping() {
        for (var method : WorkflowSseController.class.getDeclaredMethods()) {
            assertNull(method.getAnnotation(GetMapping.class));
            assertNull(method.getAnnotation(PostMapping.class));
        }
        assertEquals(3, WorkflowSseController.class.getDeclaredMethods().length);
    }
}
