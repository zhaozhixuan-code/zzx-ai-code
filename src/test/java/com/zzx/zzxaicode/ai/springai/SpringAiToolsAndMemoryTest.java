package com.zzx.zzxaicode.ai.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.zzxaicode.ai.common.ProjectFileOperations;
import com.zzx.zzxaicode.config.springai.SpringAiProperties;
import com.zzx.zzxaicode.model.po.ChatHistory;
import com.zzx.zzxaicode.service.ChatHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用独立文件目录和内存 Redis 替身，验证工具边界及模型消息持久化。 */
class SpringAiToolsAndMemoryTest {
    /** 文件读写改和单文件删除可用，跨应用、绝对路径、通配符和重要文件删除拒绝。 */
    @Test
    void toolsOperateOnlyWithinBoundApplication() throws Exception {
        Path root = Files.createTempDirectory(Path.of("target"), "ai-tools-");
        var files = new ProjectFileOperations(root);
        var tools = new SpringAiToolCallbacks(new ObjectMapper(), files).forApp(11);
        assertTrue(tools.get("writeFile").call("{\"relativeFilePath\":\"src/demo.txt\",\"content\":\"before\"}").contains("成功"));
        assertEquals("before", tools.get("readFile").call("{\"relativeFilePath\":\"src/demo.txt\"}"));
        assertTrue(tools.get("modifyFile").call("{\"relativeFilePath\":\"src/demo.txt\",\"oldContent\":\"before\",\"newContent\":\"after\"}").contains("成功"));
        assertEquals("after", Files.readString(root.resolve("vue_project_11/src/demo.txt")));
        assertTrue(tools.get("readDir").call("{\"relativeDirPath\":\"\"}").contains("demo.txt"));
        assertTrue(tools.get("readFile").call("{\"relativeFilePath\":\"../vue_project_12/secret.txt\"}").startsWith("错误"));
        assertTrue(tools.get("readFile").call("{\"relativeFilePath\":\"C:/outside.txt\"}").startsWith("错误"));
        assertTrue(tools.get("writeFile").call("{\"relativeFilePath\":\"x.txt\",\"content\":\"x\",\"appId\":12}").startsWith("错误"));
        assertTrue(tools.get("deleteFile").call("{\"relativeFilePath\":\"src/*.txt\"}").startsWith("错误"));
        assertTrue(tools.get("writeFile").call("{\"relativeFilePath\":\"package.json\",\"content\":\"{}\"}").contains("成功"));
        assertTrue(tools.get("deleteFile").call("{\"relativeFilePath\":\"package.json\"}").startsWith("错误"));
        assertTrue(Files.exists(root.resolve("vue_project_11/package.json")));
        assertTrue(tools.get("deleteFile").call("{\"relativeFilePath\":\"src/demo.txt\"}").contains("成功"));
        assertFalse(Files.exists(root.resolve("vue_project_11/src/demo.txt")));
        assertTrue(tools.get("readFile").call("not-json").startsWith("错误"));
        assertTrue(tools.get("readFile").call("{}").startsWith("错误"));
    }

    /** 历史回填去掉已保存的本轮输入，缓存命中不再重复拼接 MySQL。 */
    @Test
    void historyFallbackDeduplicatesCurrentInputAndKeepsApplicationIsolation() {
        var fixture = new MemoryFixture();
        when(fixture.history.listRecentMessages(11L, 10)).thenReturn(List.of(
                message("user", "old"), message("ai", "answer"), message("user", "current")));
        List<Message> initial = fixture.repository.load(11, "current");
        assertEquals(List.of("old", "answer", "current"), initial.stream().map(Message::getText).toList());
        initial.add(new AssistantMessage("done"));
        fixture.repository.save(11, initial);
        List<Message> next = fixture.repository.load(11, "next");
        assertEquals(5, next.size());
        assertEquals("next", next.getLast().getText());
        verify(fixture.history, times(1)).listRecentMessages(11L, 10);
        when(fixture.history.listRecentMessages(12L, 10)).thenReturn(List.of());
        assertEquals(List.of("app B"), fixture.repository.load(12, "app B").stream().map(Message::getText).toList());
        fixture.cache.clear();
        fixture.repository.load(11, "current");
        verify(fixture.history, times(2)).listRecentMessages(11L, 10);
        assertEquals(Duration.ofHours(1), fixture.ttl.get());
    }

    /** 工具调用和结果可还原，窗口裁剪从完整用户轮次开始。 */
    @Test
    void toolMessagesSurviveCacheAndWindowTrimming() {
        var fixture = new MemoryFixture();
        fixture.settings.setMemoryMessages(4);
        var call = new AssistantMessage.ToolCall("call1", "function", "readFile", "{\"relativeFilePath\":\"a.txt\"}");
        List<Message> messages = new ArrayList<>(List.of(new UserMessage("old"), new AssistantMessage("old answer"),
                new UserMessage("current"), AssistantMessage.builder().content("").toolCalls(List.of(call)).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call1", "readFile", "data"))).build(),
                new AssistantMessage("done")));
        fixture.repository.save(11, messages);
        List<Message> loaded = fixture.repository.load(11, "next");
        assertEquals(5, loaded.size());
        assertEquals("current", loaded.getFirst().getText());
        assertEquals("call1", ((AssistantMessage) loaded.get(1)).getToolCalls().getFirst().id());
        assertEquals("data", ((ToolResponseMessage) loaded.get(2)).getResponses().getFirst().responseData());
    }

    /**
     * 创建用户可见历史实体。
     * @param role user 或 ai
     * @param text 历史正文
     * @return 不访问持久层的实体
     */
    private static ChatHistory message(String role, String text) {
        return ChatHistory.builder().messageType(role).message(text).build();
    }

    /** Redis 行为替身保留键与 TTL，不访问真实 Redis 或数据库。 */
    static final class MemoryFixture {
        final Map<String, String> cache = new HashMap<>();
        final AtomicReference<Duration> ttl = new AtomicReference<>();
        final ChatHistoryService history = mock(ChatHistoryService.class);
        final SpringAiProperties settings = new SpringAiProperties();
        final SpringAiConversationRepository repository;

        /** 构造键隔离的内存缓存替身。 */
        @SuppressWarnings("unchecked")
        MemoryFixture() {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenAnswer(invocation -> cache.get(invocation.getArgument(0)));
            doAnswer(invocation -> {
                cache.put(invocation.getArgument(0), invocation.getArgument(1));
                ttl.set(invocation.getArgument(2));
                return null;
            }).when(values).set(anyString(), anyString(), any(Duration.class));
            repository = new SpringAiConversationRepository(redis, new ObjectMapper(), history, settings);
        }
    }
}
