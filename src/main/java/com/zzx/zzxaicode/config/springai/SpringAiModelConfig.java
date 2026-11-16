package com.zzx.zzxaicode.config.springai;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/** 三个模型角色共享通用连接，允许单独覆盖 endpoint/key/model/path。 */
@Configuration
@EnableConfigurationProperties({SpringAiProperties.class, OpenAiConnectionProperties.class})
public class SpringAiModelConfig {
    /**
     * 创建普通生成模型。
     * @param connection 通用 OpenAI 兼容连接配置
     * @param settings 角色及超时配置
     * @param registry Spring AI Observation 注册表
     * @return 普通生成角色模型
     */
    @Bean
    public ChatModel springAiGenerationModel(OpenAiConnectionProperties connection, SpringAiProperties settings,
                                             ObservationRegistry registry) {
        return createModel(connection, settings, settings.getGeneration(), registry);
    }

    /**
     * 创建应用类型路由模型。
     * @param connection 通用连接配置
     * @param settings 路由角色配置
     * @param registry 监控注册表
     * @return 路由模型
     */
    @Bean
    public ChatModel springAiRoutingModel(OpenAiConnectionProperties connection, SpringAiProperties settings,
                                          ObservationRegistry registry) {
        return createModel(connection, settings, settings.getRouting(), registry);
    }

    /**
     * 创建 Vue 工具调用模型。
     * @param connection 通用连接配置
     * @param settings Vue 角色配置
     * @param registry 监控注册表
     * @return 支持工具声明的兼容模型
     */
    @Bean
    public ChatModel springAiVueModel(OpenAiConnectionProperties connection, SpringAiProperties settings,
                                      ObservationRegistry registry) {
        return createModel(connection, settings, settings.getVue(), registry);
    }

    /**
     * 合并角色覆盖并创建线程安全模型；不自动重试已产生部分输出的请求。
     * @param connection 通用配置
     * @param settings 超时配置
     * @param role 当前角色覆盖
     * @param registry Observation 注册表
     * @return 兼容协议模型
     */
    private ChatModel createModel(OpenAiConnectionProperties connection, SpringAiProperties settings,
                                  SpringAiProperties.Role role, ObservationRegistry registry) {
        var defaults = connection.getChat().getOptions();
        var headers = new LinkedMultiValueMap<String, String>();
        // 在副本上应用角色覆盖，避免一个角色的设置改变其他模型共享的通用配置。
        Map<String, String> configuredHeaders = new HashMap<>(defaults.getHttpHeaders());
        configuredHeaders.putAll(role.getHeaders());
        configuredHeaders.forEach(headers::set);
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(settings.getTimeout());
        requestFactory.setReadTimeout(settings.getTimeout());
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(orDefault(role.getBaseUrl(), connection.getBaseUrl()))
                .apiKey(orDefault(role.getApiKey(), connection.getApiKey()))
                .completionsPath(orDefault(role.getCompletionsPath(), connection.getChat().getCompletionsPath()))
                .headers(headers)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder())
                .build();
        Map<String, Object> extra = new HashMap<>(defaults.getExtraBody());
        extra.putAll(role.getExtraBody());
        var options = OpenAiChatOptions.builder()
                .model(orDefault(role.getModel(), defaults.getModel()))
                .temperature(role.getTemperature() == null ? defaults.getTemperature() : role.getTemperature())
                .maxTokens(role.getMaxTokens() == null ? defaults.getMaxTokens() : role.getMaxTokens())
                .streamUsage(defaults.isStreamUsage())
                .reasoningEffort(orDefault(role.getReasoningEffort(), defaults.getReasoningEffort()))
                .extraBody(extra)
                // 工具由生成器顺序执行，才能在执行前后分别发送项目的工具事件。
                .internalToolExecutionEnabled(false)
                .build();
        // 不重试已输出部分内容的调用，避免前端内容和文件工具副作用被重复执行。
        return OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
                .observationRegistry(registry).retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }

    /**
     * 角色未设置字符串时使用通用属性。
     * @param override 角色覆盖，可空
     * @param fallback 通用属性，可空
     * @return 非空覆盖或通用属性
     */
    private String orDefault(String override, String fallback) {
        return StringUtils.hasText(override) ? override : fallback;
    }
}
