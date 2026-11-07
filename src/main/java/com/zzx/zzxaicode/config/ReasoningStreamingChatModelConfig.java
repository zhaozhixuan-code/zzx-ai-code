package com.zzx.zzxaicode.config;

import com.zzx.zzxaicode.monitor.AiModelMonitorListener;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.List;
import java.util.Map;

/**
 * 推理模型配置类
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.reasoning-streaming-chat-model")
@Data
public class ReasoningStreamingChatModelConfig {

    /**
     * AI 大模型监听器
     */
    @Resource
    private AiModelMonitorListener aiModelMonitorListener;

    private String baseUrl;

    private String apiKey;

    private String modelName;

    /**
     * 推理强度；当前适配不回传 reasoning_content，DeepSeek 工具调用需配置为 none。
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
     * 创建用于 Vue 工程工具调用的流式聊天模型。
     *
     * @return 携带推理强度配置和监控监听器的独立模型实例
     */
    @Bean
    @Scope("prototype")
    public StreamingChatModel reasoningStreamingChatModelPrototype() {
        return OpenAiStreamingChatModel.builder()
                .defaultRequestParameters(OpenAiChatRequestParameters.builder()
                        .reasoningEffort(reasoningEffort)
                        .build())
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .customHeaders(customHeaders)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(aiModelMonitorListener))
                .build();
    }
}
