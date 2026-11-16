package com.zzx.zzxaicode.config.springai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/** 主业务通用连接，使用 Spring AI OpenAI 属性路径，密钥仅从外部配置读取。 */
@Data
@ConfigurationProperties(prefix = "spring.ai.openai")
public class OpenAiConnectionProperties {
    private String baseUrl;
    private String apiKey;
    private Chat chat = new Chat();

    /** 聊天端点及模型选项。 */
    @Data
    public static class Chat {
        private String completionsPath = "/v1/chat/completions";
        private Options options = new Options();
    }

    /** 未提供 usage 的端点可以关闭 streamUsage，缺失数据不填零。 */
    @Data
    public static class Options {
        private String model;
        private Double temperature = 0.7;
        private Integer maxTokens = 8192;
        private boolean streamUsage = true;
        private String reasoningEffort;
        private Map<String, String> httpHeaders = new HashMap<>();
        private Map<String, Object> extraBody = new HashMap<>();
    }
}
