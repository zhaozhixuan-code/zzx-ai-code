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

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 配置流式聊天模型
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.streaming-chat-model")
@Data
public class StreamingChatModelConfig {

    /**
     * AI 大模型监听器
     */
    @Resource
    private AiModelMonitorListener aiModelMonitorListener;

    private String baseUrl;

    private String apiKey;

    private String modelName;

    /**
     * 推理强度；DeepSeek 使用 none 关闭当前适配尚未支持的思考模式。
     */
    private String reasoningEffort;

    /**
     * 自定义请求头，用于通过 Accept-Encoding 避免旧版 Brotli 解码器截断响应。
     */
    private Map<String, String> customHeaders;

    private Integer maxTokens;

    private Double temperature;

    private boolean logRequests;

    private boolean logResponses;


    /**
     * 创建用于 HTML 和多文件代码生成的流式聊天模型。
     *
     * @return 携带推理强度配置和监控监听器的独立模型实例
     */
    @Bean
    @Scope("prototype")
    public StreamingChatModel streamingChatModelPrototype() {
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
