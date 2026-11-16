package com.zzx.zzxaicode.ai.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.zzxaicode.config.springai.SpringAiProperties;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.model.enums.ChatHistoryMessageTypeEnum;
import com.zzx.zzxaicode.model.po.ChatHistory;
import com.zzx.zzxaicode.service.ChatHistoryService;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Redis 保存原始模型消息，可见历史只在缓存缺失时作为纯文本回填。 */
@Component
public class SpringAiConversationRepository {
    private static final String PREFIX = "springai:conversation:";
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ChatHistoryService history;
    private final SpringAiProperties properties;

    /**
     * 注入历史及模型上下文存储。
     * @param redis 专用键前缀的字符串缓存
     * @param mapper JSON 序列化器
     * @param history 用户可见历史服务
     * @param properties TTL 和消息窗口
     */
    public SpringAiConversationRepository(StringRedisTemplate redis, ObjectMapper mapper,
                                          ChatHistoryService history, SpringAiProperties properties) {
        this.redis = redis;
        this.mapper = mapper;
        this.history = history;
        this.properties = properties;
    }

    /**
     * 加载模型上下文并追加本轮输入，系统提示词由调用方单独提供。
     * @param appId 服务端应用 ID
     * @param currentMessage 本轮用户输入，已在可见历史中保存
     * @return 独立可修改的消息列表，不同时合并 Redis 和 MySQL 历史
     */
    public List<Message> load(long appId, String currentMessage) {
        List<Message> messages = new ArrayList<>();
        String cached = redis.opsForValue().get(PREFIX + appId);
        if (cached != null) {
            try {
                for (StoredMessage item : mapper.readValue(cached, new TypeReference<List<StoredMessage>>() { })) {
                    messages.add(fromStored(item));
                }
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "模型上下文读取失败");
            }
        } else {
            List<ChatHistory> recent = new ArrayList<>(history.listRecentMessages(appId, properties.getHistoryMessages()));
            // 应用入口先保存本轮输入，缓存回填时仅去掉最后这条，随后统一追加一次。
            if (!recent.isEmpty()) {
                ChatHistory last = recent.getLast();
                if (ChatHistoryMessageTypeEnum.USER.getValue().equals(last.getMessageType())
                        && currentMessage.equals(last.getMessage())) {
                    recent.removeLast();
                }
            }
            for (ChatHistory item : recent) {
                if (ChatHistoryMessageTypeEnum.USER.getValue().equals(item.getMessageType())) {
                    messages.add(new UserMessage(item.getMessage()));
                } else if (ChatHistoryMessageTypeEnum.AI.getValue().equals(item.getMessageType())) {
                    // 旧历史仅有展示摘要，作为普通 assistant 文本使用，不捏造 tool_call_id。
                    messages.add(new AssistantMessage(item.getMessage()));
                }
            }
        }
        // 失败请求可能只留下用户消息，下轮不能把最后一个历史 user 当作当前输入。
        messages.add(new UserMessage(currentMessage));
        return messages;
    }

    /**
     * 仅在一轮成功时保存完整消息；裁剪到用户轮次边界，避免孤立 tool result。
     * @param appId 应用 ID
     * @param messages 不包含系统提示词的完整对话
     */
    public void save(long appId, List<Message> messages) {
        List<Message> window = new ArrayList<>(messages);
        while (window.size() > properties.getMemoryMessages()) {
            int nextUser = -1;
            for (int i = 1; i < window.size(); i++) {
                if (window.get(i) instanceof UserMessage) {
                    nextUser = i;
                    break;
                }
            }
            // 单个工具轮次过长时保持完整，下一轮才淘汰，不能切断协议配对。
            if (nextUser < 0) {
                break;
            }
            window = new ArrayList<>(window.subList(nextUser, window.size()));
        }
        try {
            List<StoredMessage> stored = window.stream().map(this::toStored).toList();
            redis.opsForValue().set(PREFIX + appId, mapper.writeValueAsString(stored), properties.getMemoryTtl());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "模型上下文保存失败");
        }
    }

    /**
     * 将 Spring AI 类型转换为稳定的缓存载荷。
     * @param message 模型消息
     * @return 不含框架多态元数据的缓存记录
     */
    private StoredMessage toStored(Message message) {
        return new StoredMessage(message.getMessageType().getValue(), message.getText(),
                message instanceof AssistantMessage assistant ? assistant.getToolCalls() : List.of(),
                message instanceof ToolResponseMessage tool ? tool.getResponses() : List.of());
    }

    /**
     * 还原缓存中的模型消息。
     * @param stored 已保存的角色、文本和工具协议
     * @return 模型可接受的消息
     */
    private Message fromStored(StoredMessage stored) {
        return switch (stored.role()) {
            case "user" -> new UserMessage(stored.text());
            case "assistant" -> AssistantMessage.builder().content(stored.text()).toolCalls(stored.calls()).build();
            case "tool" -> ToolResponseMessage.builder().responses(stored.responses()).build();
            default -> throw new IllegalArgumentException("不支持的上下文角色");
        };
    }

    /** 缓存仅由服务端写入，不接受模型指定角色或应用键。 */
    public record StoredMessage(String role, String text, List<AssistantMessage.ToolCall> calls,
                                List<ToolResponseMessage.ToolResponse> responses) { }
}
