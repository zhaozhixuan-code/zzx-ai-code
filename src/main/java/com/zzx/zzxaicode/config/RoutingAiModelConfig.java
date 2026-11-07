package com.zzx.zzxaicode.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.Map;

/**
 * 智能路由模型配置类
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.routing-chat-model")
@Data
public class RoutingAiModelConfig {

    private String baseUrl;

    private String apiKey;

    private String modelName;

    /**
     * 推理强度；DeepSeek 使用 none 关闭思考模式，避免路由的 token 额度被思考内容占用。
     */
    private String reasoningEffort;

    /**
     * 自定义请求头，用于通过 Accept-Encoding 避免旧版 Brotli 解码器截断响应。
     */
    private Map<String, String> customHeaders;

    private Integer maxTokens;

    private Double temperature;

    private Boolean logRequests = false;

    private Boolean logResponses = false;

    /**
     * 创建用于路由判断的ChatModel
     *
     * @return 按当前路由配置创建的聊天模型
     */
    @Bean
    @Scope("prototype")
    public ChatModel routingChatModelPrototype() {
        return OpenAiChatModel.builder()
                .defaultRequestParameters(OpenAiChatRequestParameters.builder()
                        .reasoningEffort(reasoningEffort)
                        .build())
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .customHeaders(customHeaders)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }
}
