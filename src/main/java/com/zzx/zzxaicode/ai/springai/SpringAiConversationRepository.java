package com.zzx.zzxaicode.ai.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
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

/** Redis 保存 Spring AI 模型消息，可见历史只在缓存缺失时作为纯文本回填。 */
@Component
public class SpringAiConversationRepository {
    /** 与其他模型框架的 Redis 键分离，后缀只使用服务端校验的 appId。 */
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
     * @throws BusinessException Redis 消息 JSON 无法解析或包含不支持的角色
     */
    public List<Message> load(long appId, String currentMessage) {
        List<Message> messages = new ArrayList<>();
        String cached = redis.opsForValue().get(PREFIX + appId);
        if (cached != null) {
            // 命中时只使用模型消息，不能再追加包含同一轮文本的可见历史。
            try {
                for (StoredMessage item : mapper.readValue(cached, new TypeReference<List<StoredMessage>>() { })) {
                    messages.add(fromStored(item));
                }
            } catch (JsonProcessingException | IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "模型上下文读取失败");
            }
        } else {
            // 缓存过期后只有可见文本可回填，先保留时间顺序，再去掉入口刚写入的本轮输入。
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
     * 仅在一轮成功时保存完整消息；一次扫描确定用户轮次边界，避免孤立 tool result。
     * @param appId 应用 ID
     * @param messages 不包含系统提示词的完整对话
     * @throws BusinessException 消息无法序列化为 Redis 载荷
     */
    public void save(long appId, List<Message> messages) {
        int start = 0;
        int maxMessages = properties.getMemoryMessages();
        // 只在后续 user 处推进起点；达到窗口目标即停止，不反复复制剩余消息。
        for (int i = 1; i < messages.size() && messages.size() - start > maxMessages; i++) {
            if (messages.get(i) instanceof UserMessage) {
                start = i;
            }
        }
        // 若最后一轮本身超限，就整轮保留；下一轮完成后才有安全淘汰它的 user 边界。
        try {
            List<StoredMessage> stored = messages.subList(start, messages.size()).stream().map(this::toStored).toList();
            redis.opsForValue().set(PREFIX + appId, mapper.writeValueAsString(stored), properties.getMemoryTtl());
        } catch (JsonProcessingException e) {
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

    /**
     * Redis 文本与工具协议载荷，仅由服务端写入；系统提示词在每次请求时单独提供。
     * @param role user、assistant 或 tool
     * @param text 消息正文；工具响应的实际结果保存在 responses 中
     * @param calls assistant 声明的工具调用，其他角色为空列表
     * @param responses 工具调用结果，ID 必须对应先前 assistant 的调用，其他角色为空列表
     */
    public record StoredMessage(String role, String text, List<AssistantMessage.ToolCall> calls,
                                List<ToolResponseMessage.ToolResponse> responses) { }
}
