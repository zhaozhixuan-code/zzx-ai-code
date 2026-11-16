package com.zzx.zzxaicode.ai.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 将 Spring AI 模型消息完整保存到独立的 MySQL 表，并实现官方 ChatMemoryRepository 契约。
 * 用户可见的 chat_history 不包含工具调用协议，因此不能作为本仓储的事实来源。
 */
@Repository
public class SpringAiJdbcChatMemoryRepository implements ChatMemoryRepository {
    private static final String CONVERSATION_PREFIX = "app:";
    private static final String FIND_CONVERSATIONS = """
            SELECT DISTINCT conversation_id
            FROM spring_ai_chat_message
            ORDER BY conversation_id
            """;
    private static final String FIND_MESSAGES = """
            SELECT message_type, message_data
            FROM spring_ai_chat_message
            WHERE conversation_id = ?
            ORDER BY sequence_no
            """;
    private static final String DELETE_MESSAGES = "DELETE FROM spring_ai_chat_message WHERE conversation_id = ?";
    private static final String INSERT_MESSAGE = """
            INSERT INTO spring_ai_chat_message
                (conversation_id, app_id, sequence_no, message_type, message_data)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 注入 JDBC 访问器和 JSON 转换器。
     * @param jdbcTemplate 访问 Spring AI 消息表的 JDBC 模板
     * @param objectMapper 项目统一 JSON 转换器
     */
    public SpringAiJdbcChatMemoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询已持久化的会话 ID。
     * @return 按会话 ID 排序且不重复的会话列表
     */
    @Override
    public List<String> findConversationIds() {
        return jdbcTemplate.queryForList(FIND_CONVERSATIONS, String.class);
    }

    /**
     * 按稳定序号恢复一整个会话，确保 assistant 工具调用和 tool 结果成对返回。
     * @param conversationId Spring AI 会话 ID，应用入口使用 app:&lt;appId&gt; 格式
     * @return 可直接交给 ChatModel 的消息列表
     */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        return jdbcTemplate.query(FIND_MESSAGES, (resultSet, rowNum) -> readMessage(resultSet), conversationId);
    }

    /**
     * 原子替换会话窗口。MessageWindowChatMemory 会先完成窗口裁剪，再把完整窗口传入此方法。
     * @param conversationId Spring AI 会话 ID
     * @param messages 已按模型协议排序的窗口消息
     */
    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        jdbcTemplate.update(DELETE_MESSAGES, conversationId);
        Long appId = parseAppId(conversationId);
        for (int index = 0; index < messages.size(); index++) {
            PersistedMessage stored = PersistedMessage.from(messages.get(index));
            jdbcTemplate.update(INSERT_MESSAGE, conversationId, appId, index,
                    stored.messageType(), writeJson(stored));
        }
    }

    /**
     * 删除会话的全部模型消息，供 ChatMemory.clear 调用。
     * @param conversationId Spring AI 会话 ID
     */
    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update(DELETE_MESSAGES, conversationId);
    }

    /**
     * 从数据库 JSON 恢复 Spring AI 消息，并拒绝未知消息类型，避免静默丢失上下文。
     * @param resultSet 当前消息行
     * @return 可发送给模型的 Spring AI 消息
     * @throws SQLException 无法读取消息列时抛出
     */
    private Message readMessage(ResultSet resultSet) throws SQLException {
        try {
            PersistedMessage stored = objectMapper.readValue(resultSet.getString("message_data"),
                    new TypeReference<>() { });
            return stored.toMessage();
        } catch (Exception exception) {
            throw new IllegalStateException("Spring AI 模型消息恢复失败", exception);
        }
    }

    /**
     * 序列化前面已经校验过结构的消息记录。
     * @param stored 待写入的消息记录
     * @return JSON 文本
     */
    private String writeJson(PersistedMessage stored) {
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (Exception exception) {
            throw new IllegalStateException("Spring AI 模型消息序列化失败", exception);
        }
    }

    /**
     * 从应用会话 ID 提取索引字段；非应用型会话仍可持久化，索引字段保持为空。
     * @param conversationId Spring AI 会话 ID
     * @return 应用 ID，无法解析时返回 null
     */
    private Long parseAppId(String conversationId) {
        if (conversationId == null || !conversationId.startsWith(CONVERSATION_PREFIX)) {
            return null;
        }
        try {
            return Long.valueOf(conversationId.substring(CONVERSATION_PREFIX.length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 数据库中的稳定消息载荷，完整覆盖当前生成器使用的文本、metadata 和工具协议。 */
    record PersistedMessage(String messageType, String text, Map<String, Object> metadata,
                            List<AssistantMessage.ToolCall> toolCalls,
                            List<ToolResponseMessage.ToolResponse> toolResponses) {
        /**
         * 将 Spring AI 消息转换为可稳定序列化的记录。
         * @param message 原始 Spring AI 消息
         * @return 不依赖框架多态序列化的消息记录
         */
        static PersistedMessage from(Message message) {
            List<AssistantMessage.ToolCall> calls = message instanceof AssistantMessage assistant
                    ? assistant.getToolCalls() : List.of();
            List<ToolResponseMessage.ToolResponse> responses = message instanceof ToolResponseMessage tool
                    ? tool.getResponses() : List.of();
            return new PersistedMessage(message.getMessageType().getValue(), message.getText(), message.getMetadata(),
                    calls, responses);
        }

        /**
         * 显式恢复四种当前主链路支持的消息类型。
         * @return Spring AI 消息实例
         */
        Message toMessage() {
            Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;
            List<AssistantMessage.ToolCall> safeCalls = toolCalls == null ? List.of() : toolCalls;
            List<ToolResponseMessage.ToolResponse> safeResponses = toolResponses == null ? List.of() : toolResponses;
            return switch (messageType) {
                case "user" -> UserMessage.builder().text(text).metadata(safeMetadata).build();
                case "assistant" -> AssistantMessage.builder().content(text).properties(safeMetadata)
                        .toolCalls(safeCalls).build();
                case "system" -> SystemMessage.builder().text(text).metadata(safeMetadata).build();
                case "tool" -> ToolResponseMessage.builder().responses(safeResponses).metadata(safeMetadata).build();
                default -> throw new IllegalStateException("不支持的 Spring AI 消息类型: " + messageType);
            };
        }
    }
}
