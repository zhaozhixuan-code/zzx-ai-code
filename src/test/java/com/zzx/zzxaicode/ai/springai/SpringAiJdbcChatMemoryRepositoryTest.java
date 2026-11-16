package com.zzx.zzxaicode.ai.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用 H2 验证官方 ChatMemoryRepository 的完整消息保存、恢复、替换和清理语义。 */
class SpringAiJdbcChatMemoryRepositoryTest {
    private JdbcTemplate jdbcTemplate;
    private SpringAiJdbcChatMemoryRepository repository;

    /** 每个用例使用独立内存表，避免依赖外部数据库。 */
    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:spring_ai_memory;DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS spring_ai_chat_message");
        jdbcTemplate.execute("""
                CREATE TABLE spring_ai_chat_message (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    conversation_id VARCHAR(128) NOT NULL,
                    app_id BIGINT NULL,
                    sequence_no INT NOT NULL,
                    message_type VARCHAR(32) NOT NULL,
                    message_data CLOB NOT NULL,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    UNIQUE (conversation_id, sequence_no)
                )
                """);
        repository = new SpringAiJdbcChatMemoryRepository(jdbcTemplate, new ObjectMapper());
    }

    /** 工具调用与结果的 ID、名称、参数、返回值及 metadata 都能跨数据库往返。 */
    @Test
    void storesAndRestoresCompleteToolProtocol() {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1", "function", "readFile", "{\"relativeFilePath\":\"src/App.vue\"}");
        List<Message> messages = List.of(
                UserMessage.builder().text("读取首页").metadata(Map.of("trace", "t-1")).build(),
                AssistantMessage.builder().content("").properties(Map.of("finishReason", "TOOL_CALLS"))
                        .toolCalls(List.of(call)).build(),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("call-1", "readFile", "<template/>"))).build(),
                new AssistantMessage("已读取"));

        repository.saveAll("app:11", messages);

        List<Message> restored = repository.findByConversationId("app:11");
        assertEquals(messages.size(), restored.size());
        assertEquals("t-1", restored.getFirst().getMetadata().get("trace"));
        AssistantMessage.ToolCall restoredCall = ((AssistantMessage) restored.get(1)).getToolCalls().getFirst();
        assertEquals(call, restoredCall);
        ToolResponseMessage.ToolResponse restoredResponse =
                ((ToolResponseMessage) restored.get(2)).getResponses().getFirst();
        assertEquals("call-1", restoredResponse.id());
        assertEquals("<template/>", restoredResponse.responseData());
        assertEquals(11L, jdbcTemplate.queryForObject(
                "SELECT app_id FROM spring_ai_chat_message WHERE conversation_id = 'app:11' LIMIT 1", Long.class));
    }

    /** saveAll 替换窗口，clear 删除会话，不能遗留旧序号或孤立工具消息。 */
    @Test
    void replacesWindowAndClearsConversation() {
        repository.saveAll("app:11", List.of(new UserMessage("old"), new AssistantMessage("answer")));
        repository.saveAll("app:11", List.of(new UserMessage("new")));

        assertEquals(List.of("new"), repository.findByConversationId("app:11").stream().map(Message::getText).toList());
        assertTrue(repository.findConversationIds().contains("app:11"));
        repository.deleteByConversationId("app:11");
        assertTrue(repository.findByConversationId("app:11").isEmpty());
        assertTrue(repository.findConversationIds().isEmpty());
    }
}
