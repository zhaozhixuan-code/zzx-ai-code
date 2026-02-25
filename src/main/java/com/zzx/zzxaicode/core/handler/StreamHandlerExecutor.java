package com.zzx.zzxaicode.core.handler;

import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import com.zzx.zzxaicode.model.po.User;
import com.zzx.zzxaicode.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 流处理器执行器
 * 根据代码生成类型创建格式的流处理器：
 * 1．传统的Flux<String>流(HTML、MULTI_FILE）->SimpleTextStreamHandler
 * 2.TokenStream格式的复杂流（VUE_PROJECT）->JsonMessageStreamHandler
 */
@Slf4j
@Component
public class StreamHandlerExecutor {

    @Resource
    private JsonMessageStreamHandler jsonMessageStreamHandler;

    /**
     * 创建流处理器并处理聊天历史记录
     *
     * @param originFlux         原始流
     * @param chatHistoryService 对话历史服务
     * @param appId              应用 ID
     * @param longUser           登录用户
     * @param codeGenType        代码生成类型
     * @return 处理后的流
     */
    public Flux<String> doExecute(Flux<String> originFlux,
                                  ChatHistoryService chatHistoryService,
                                  long appId, User longUser, CodeGenTypeEnum codeGenType) {
        return switch (codeGenType) {
            // 简单文本处理器不需要依赖注入
            case HTML, MULTI_FILE ->
                    new SimpleTextStreamHandler().handle(originFlux, chatHistoryService, appId, longUser);

            case VUE_PROJECT ->
                // 复杂流处理器需要依赖注入
                    jsonMessageStreamHandler.handle(originFlux, chatHistoryService, appId, longUser);
        };
    }
}
