package com.zzx.zzxaicode.ai.langchain;

import com.zzx.zzxaicode.model.enums.ChatHistoryMessageTypeEnum;
import com.zzx.zzxaicode.model.po.ChatHistory;
import com.zzx.zzxaicode.service.ChatHistoryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.stereotype.Component;

/** 保留学习实现的历史转换，将 LangChain4j 类型限定在旧适配包。 */
@Component
public class LegacyChatHistoryMemoryAdapter {
    private final ChatHistoryService historyService;

    /**
     * 注入框架无关历史服务。
     * @param historyService 应用历史查询服务
     */
    public LegacyChatHistoryMemoryAdapter(ChatHistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * 将可见消息回填到旧记忆窗口。
     * @param appId 应用 ID，学习默认实例允许 0
     * @param memory 旧模型记忆
     * @param maxCount 回填条数上限
     */
    public void load(long appId, MessageWindowChatMemory memory, int maxCount) {
        if (appId <= 0) {
            return;
        }
        var history = historyService.listRecentMessages(appId, maxCount);
        if (!history.isEmpty()) {
            memory.clear();
        }
        for (ChatHistory item : history) {
            if (ChatHistoryMessageTypeEnum.USER.getValue().equals(item.getMessageType())) {
                memory.add(UserMessage.from(item.getMessage()));
            } else if (ChatHistoryMessageTypeEnum.AI.getValue().equals(item.getMessageType())) {
                memory.add(AiMessage.from(item.getMessage()));
            }
        }
    }
}
