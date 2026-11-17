package com.zzx.zzxaicode.core.handler;

import com.zzx.zzxaicode.model.enums.ChatHistoryMessageTypeEnum;
import com.zzx.zzxaicode.model.po.User;
import com.zzx.zzxaicode.service.ChatHistoryService;
import reactor.core.publisher.Flux;

/**
 * 处理 HTML 和 MULTI_FILE 类型的文本流，并在每次订阅完成后记录可见回复。
 */
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
        return Flux.defer(() -> {
            // 收集器属于单次订阅，重复订阅不会把上次回复拼进当前历史。
            StringBuilder aiResultBuilder = new StringBuilder();
            return originFlux.doOnNext(aiResultBuilder::append)
                    .doOnComplete(() -> chatHistoryService.addChatMessage(appId, aiResultBuilder.toString(),
                            ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId()))
                    .doOnError(throwable -> chatHistoryService.addChatMessage(appId,
                            "AI 回复失败：" + throwable.getMessage() + aiResultBuilder,
                            ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId()));
        });

    }
}
