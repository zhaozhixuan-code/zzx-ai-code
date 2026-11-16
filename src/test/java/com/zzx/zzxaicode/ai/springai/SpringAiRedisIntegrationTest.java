package com.zzx.zzxaicode.ai.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.zzxaicode.config.springai.SpringAiProperties;
import com.zzx.zzxaicode.model.po.ChatHistory;
import com.zzx.zzxaicode.service.ChatHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式指定 Redis 可执行文件后启动无持久化的临时实例，不接触项目已有 Redis。 */
@EnabledIfSystemProperty(named = "migration.redis.executable", matches = ".+")
class SpringAiRedisIntegrationTest {
    /** 验证实际序列化、应用键隔离、TTL 自然过期及过期后的可见历史回填。 */
    @Test
    void realRedisRestoresToolMessagesAndExpiresToHistoryFallback() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        Path directory = Files.createTempDirectory(Path.of("target"), "migration-redis-");
        Process process = new ProcessBuilder(System.getProperty("migration.redis.executable"), "--bind", "127.0.0.1",
                "--port", String.valueOf(port), "--save", "", "--appendonly", "no", "--dir", directory.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        JedisConnectionFactory factory = null;
        try {
            awaitRedis(process, port);
            factory = new JedisConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port));
            factory.afterPropertiesSet();
            factory.start();
            var redis = new StringRedisTemplate(factory);
            var history = mock(ChatHistoryService.class);
            when(history.listRecentMessages(11L, 10)).thenReturn(List.of(
                    ChatHistory.builder().messageType("user").message("question").build(),
                    ChatHistory.builder().messageType("ai").message("visible answer").build()));
            when(history.listRecentMessages(12L, 10)).thenReturn(List.of());
            var properties = new SpringAiProperties();
            properties.setMemoryTtl(Duration.ofSeconds(2));
            var repository = new SpringAiConversationRepository(redis, new ObjectMapper(), history, properties);
            var call = new AssistantMessage.ToolCall("call1", "function", "readFile", "{\"relativeFilePath\":\"src/App.vue\"}");
            var messages = new ArrayList<Message>(List.of(new UserMessage("question"),
                    AssistantMessage.builder().content("").toolCalls(List.of(call)).build(),
                    ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call1", "readFile", "content"))).build(),
                    new AssistantMessage("answer")));
            repository.save(11, messages);
            long ttl = redis.getExpire("springai:conversation:11", TimeUnit.MILLISECONDS);
            assertTrue(ttl > 0 && ttl <= 2000);
            List<Message> loaded = repository.load(11, "next");
            assertEquals(5, loaded.size());
            assertEquals(call, ((AssistantMessage) loaded.get(1)).getToolCalls().getFirst());
            assertEquals("content", ((ToolResponseMessage) loaded.get(2)).getResponses().getFirst().responseData());
            verifyNoInteractions(history);
            assertEquals(List.of("B"), repository.load(12, "B").stream().map(Message::getText).toList());
            repository.save(12, List.of(new UserMessage("B"), new AssistantMessage("answer B")));
            assertNotEquals(redis.opsForValue().get("springai:conversation:11"), redis.opsForValue().get("springai:conversation:12"));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (Boolean.TRUE.equals(redis.hasKey("springai:conversation:11")) && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertFalse(Boolean.TRUE.equals(redis.hasKey("springai:conversation:11")));
            assertEquals(List.of("question", "visible answer", "next"),
                    repository.load(11, "next").stream().map(Message::getText).toList());
            verify(history).listRecentMessages(11L, 10);
        } finally {
            if (factory != null) {
                factory.destroy();
            }
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 等待本用例启动的 Redis 接受连接，进程失败或五秒未就绪时直接使测试失败。
     * @param process 本用例独占的进程
     * @param port 仅绑定回环地址的临时端口
     * @throws Exception Redis 无法启动或等待被中断
     */
    private void awaitRedis(Process process, int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                return;
            } catch (java.io.IOException e) {
                Thread.sleep(25);
            }
        }
        fail("临时 Redis 未能启动");
    }
}
