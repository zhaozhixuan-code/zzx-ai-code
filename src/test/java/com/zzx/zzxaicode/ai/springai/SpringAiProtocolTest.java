package com.zzx.zzxaicode.ai.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zzx.zzxaicode.config.springai.OpenAiConnectionProperties;
import com.zzx.zzxaicode.config.springai.SpringAiModelConfig;
import com.zzx.zzxaicode.config.springai.SpringAiProperties;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅在本机回环模拟 OpenAI 协议，不向外部模型发送请求或使用真实密钥。 */
class SpringAiProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 默认路径、角色路径、请求头、模型选项和同步响应均使用配置值。 */
    @Test
    void syncAndStreamSupportConfiguredEndpointsAndHeaders() throws Exception {
        try (var server = new StubServer((request, index) -> request.path.equals("/custom/chat")
                ? textStream("<h1>stream</h1>", "stop") : "{\"id\":\"sync\",\"model\":\"fake\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"sync ok\"},\"finish_reason\":\"stop\"}]}")) {
            var settings = new SpringAiProperties();
            var connection = connection(server.url());
            settings.getGeneration().setHeaders(Map.of("X-Test-Role", "generation"));
            ChatModel model = model(connection, settings);
            assertEquals("sync ok", model.call(new Prompt("hi")).getResult().getOutput().getText());
            Request sync = server.requests.getFirst();
            assertEquals("/v1/chat/completions", sync.path);
            assertEquals("generation", sync.header);
            assertEquals("fake-model", sync.body.get("model").asText());
            assertEquals(1234, sync.body.get("max_tokens").asInt());
            assertEquals(0.3, sync.body.get("temperature").asDouble());
            settings.getGeneration().setCompletionsPath("/custom/chat");
            ChatModel custom = model(connection, settings);
            assertEquals("<h1>stream</h1>", custom.stream(new Prompt("hi")).map(response ->
                    response.getResult() == null || response.getResult().getOutput().getText() == null ? ""
                            : response.getResult().getOutput().getText()).collectList().map(parts -> String.join("", parts))
                    .block(Duration.ofSeconds(5)));
        }
    }

    /** 工具参数分片由 Spring AI 聚合，项目层保留请求/执行事件，第二轮提交完整工具协议。 */
    @Test
    void streamingToolCallExecutesThenContinuesWithPairedMessages() throws Exception {
        try (var server = new StubServer((request, index) -> index == 0 ? toolStream() : textStream("完成", "stop"))) {
            var settings = new SpringAiProperties();
            ChatModel model = model(connection(server.url()), settings);
            var repository = mock(SpringAiConversationRepository.class);
            when(repository.load(11, "创建页面")).thenReturn(new ArrayList<>(List.of(new UserMessage("创建页面"))));
            var callbacks = mock(SpringAiToolCallbacks.class);
            var callback = mock(org.springframework.ai.tool.ToolCallback.class);
            when(callback.getToolDefinition()).thenReturn(new org.springframework.ai.tool.definition.DefaultToolDefinition(
                    "writeFile", "写入文件", "{\"type\":\"object\",\"properties\":{\"relativeFilePath\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}}}"));
            when(callback.call(anyString())).thenReturn("文件写入成功: src/demo.txt");
            when(callbacks.forApp(11)).thenReturn(Map.of("writeFile", callback));
            var generator = new SpringAiCodeGenerator(model, model, new SpringAiPromptLoader(), repository, callbacks, settings);
            List<String> events = generator.stream(11, "创建页面", CodeGenTypeEnum.VUE_PROJECT).collectList().block(Duration.ofSeconds(8));
            assertNotNull(events);
            assertTrue(events.stream().anyMatch(event -> event.contains("tool_request")));
            assertTrue(events.stream().anyMatch(event -> event.contains("tool_executed")));
            assertTrue(events.stream().anyMatch(event -> event.contains("完成")));
            verify(callback).call("{\"relativeFilePath\":\"src/demo.txt\",\"content\":\"hello\"}");
            JsonNode messages = server.requests.get(1).body.get("messages");
            assertEquals("assistant", messages.get(2).get("role").asText());
            assertEquals("call_1", messages.get(2).get("tool_calls").get(0).get("id").asText());
            assertEquals("tool", messages.get(3).get("role").asText());
            assertEquals("call_1", messages.get(3).get("tool_call_id").asText());
            verify(repository).save(eq(11L), anyList());
        }
    }

    /** HTTP 401/429、模型 length 截断及缺失结束标记均不能保存为成功上下文。 */
    @Test
    void errorsAndIncompleteStreamsDoNotSaveSuccessfulTurn() throws Exception {
        for (String mode : List.of("401", "429", "length", "disconnect")) {
            try (var server = new StubServer((request, index) -> mode.matches("\\d+") ? mode
                    : textStream("partial", "disconnect".equals(mode) ? null : "length"))) {
                var repository = mock(SpringAiConversationRepository.class);
                when(repository.load(11, "创建页面")).thenReturn(new ArrayList<>(List.of(new UserMessage("创建页面"))));
                var settings = new SpringAiProperties();
                ChatModel model = model(connection(server.url()), settings);
                var generator = new SpringAiCodeGenerator(model, model, new SpringAiPromptLoader(), repository,
                        mock(SpringAiToolCallbacks.class), settings);
                assertThrows(RuntimeException.class, () -> generator.stream(11, "创建页面", CodeGenTypeEnum.HTML)
                        .collectList().block(Duration.ofSeconds(6)), mode);
                verify(repository, never()).save(anyLong(), anyList());
                assertEquals(1, server.requests.size(), "错误响应不应被静默重试");
            }
        }
    }

    /** 模型不返回数据时超时，订阅取消后同一应用能够再次生成。 */
    @Test
    void timeoutReleasesApplicationGenerationSlot() {
        var repository = mock(SpringAiConversationRepository.class);
        when(repository.load(11, "创建页面")).thenAnswer(invocation -> new ArrayList<>(List.of(new UserMessage("创建页面"))));
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.never());
        var settings = new SpringAiProperties();
        settings.setTimeout(Duration.ofMillis(80));
        var generator = new SpringAiCodeGenerator(model, model, new SpringAiPromptLoader(), repository,
                mock(SpringAiToolCallbacks.class), settings);
        assertThrows(RuntimeException.class, () -> generator.stream(11, "创建页面", CodeGenTypeEnum.HTML)
                .collectList().block(Duration.ofSeconds(2)));
        assertThrows(RuntimeException.class, () -> generator.stream(11, "创建页面", CodeGenTypeEnum.HTML)
                .collectList().block(Duration.ofSeconds(2)));
        verify(repository, times(2)).load(11, "创建页面");
        verify(repository, never()).save(anyLong(), anyList());
    }

    /**
     * 创建只访问回环服务器的配置。
     * @param url 回环测试端点
     * @return 不含真实密钥的配置
     */
    private static OpenAiConnectionProperties connection(String url) {
        var connection = new OpenAiConnectionProperties();
        connection.setBaseUrl(url);
        connection.setApiKey("local-test-key");
        connection.getChat().getOptions().setModel("fake-model");
        connection.getChat().getOptions().setTemperature(0.3);
        connection.getChat().getOptions().setMaxTokens(1234);
        connection.getChat().getOptions().setStreamUsage(false);
        return connection;
    }

    /**
     * 使用生产配置工厂创建测试模型。
     * @param connection 回环连接配置
     * @param settings 角色覆盖
     * @return Spring AI 模型
     */
    private static ChatModel model(OpenAiConnectionProperties connection, SpringAiProperties settings) {
        return new SpringAiModelConfig().springAiGenerationModel(connection, settings, ObservationRegistry.create());
    }

    /**
     * 构造两个文本 SSE 帧，可选择是否有结束标记。
     * @param text 文本片段
     * @param finish stop/length 或 null
     * @return OpenAI SSE 响应
     */
    private static String textStream(String text, String finish) {
        try {
            return frame(Map.of("role", "assistant", "content", text), null)
                    + (finish == null ? "" : frame(Map.of(), finish)) + "data: [DONE]\n\n";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** @return 工具名称、参数拆为两段的 SSE 响应。 */
    private static String toolStream() {
        try {
            return frame(Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "call_1", "type", "function",
                    "function", Map.of("name", "writeFile", "arguments", "{\"relativeFilePath\":\"src/demo.txt\",")))), null)
                    + frame(Map.of("tool_calls", List.of(Map.of("index", 0,
                    "function", Map.of("arguments", "\"content\":\"hello\"}")))), null)
                    + frame(Map.of(), "tool_calls") + "data: [DONE]\n\n";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 构造兼容的单个 SSE 帧。
     * @param delta 响应增量
     * @param finish 可空结束原因
     * @return SSE 数据行
     */
    private static String frame(Map<String, Object> delta, String finish) throws Exception {
        Map<String, Object> choice = new java.util.LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finish);
        return "data: " + JSON.writeValueAsString(Map.of("id", "fake", "model", "fake-model", "created", 1,
                "object", "chat.completion.chunk", "choices", List.of(choice))) + "\n\n";
    }

    /** 回环服务器收到的请求。 */
    private record Request(String path, String header, JsonNode body) { }

    /** 每个测试独立的回环服务器，关闭时只停止自身监听。 */
    private static final class StubServer implements AutoCloseable {
        final HttpServer server;
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger counter = new AtomicInteger();

        /**
         * 按收到的请求返回模拟响应。
         * @param response 响应正文函数，401/429 字符串表示对应错误
         */
        StubServer(java.util.function.BiFunction<Request, Integer, String> response) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                Request request = new Request(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("X-Test-Role"),
                        JSON.readTree(exchange.getRequestBody()));
                requests.add(request);
                String body = response.apply(request, counter.getAndIncrement());
                int status = body.matches("401|429") ? Integer.parseInt(body) : 200;
                if (status != 200) {
                    body = "{\"error\":{\"message\":\"local failure\"}}";
                }
                exchange.getResponseHeaders().set("Content-Type", request.body.path("stream").asBoolean()
                        && status == 200 ? "text/event-stream" : "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        /** @return 仅本机访问的随机端口地址。 */
        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /** 停止当前测试服务器。 */
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
