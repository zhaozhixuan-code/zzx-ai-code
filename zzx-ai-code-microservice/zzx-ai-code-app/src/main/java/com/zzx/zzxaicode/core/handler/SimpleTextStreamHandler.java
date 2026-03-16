package com.zzx.zzxaicode.core.handler;

import com.zzx.zzxaicode.model.enums.ChatHistoryMessageTypeEnum;
import com.zzx.zzxaicode.model.po.User;
import com.zzx.zzxaicode.service.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * 简单文本流处理器
 * 处理 HTML 和 MULTI_FILE 类型的流式响应
 */

@Slf4j
public class SimpleTextStreamHandler {

    /**
     * 处理 HTML 和 MULTI_FILE 类型的流式响应
     *
     * @param originFlux         原始流
     * @param chatHistoryService 对话历史服务
     * @param appId              应用 ID
     * @param loginUser          登录用户
     * @return 处理后的流
     */
    public Flux<String> handle(Flux<String> originFlux,
                               ChatHistoryService chatHistoryService,
                               long appId,
                               User loginUser) {
        // 收集 AI 相应内容并在完成后添加到对话历史
        StringBuilder aiResultBuilder = new StringBuilder();
        return originFlux.map(chunk -> {
            // 收集 AI 信息
            aiResultBuilder.append(chunk);
            return chunk;
        }).doOnComplete(() -> {
            // 流式响应完成后，添加 AI 信息到历史对话中
            String aiResponse = aiResultBuilder.toString();
            chatHistoryService.addChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
        }).doOnError(throwable -> {
            String aiResponse = aiResultBuilder.toString();
            // 添加错误信息到对话历史
            String errorMessage = "AI 回复失败" + throwable.getMessage() + aiResponse;
            chatHistoryService.addChatMessage(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
        });

    }
}
