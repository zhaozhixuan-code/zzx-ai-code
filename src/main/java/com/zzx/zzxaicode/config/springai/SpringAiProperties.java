package com.zzx.zzxaicode.config.springai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** OpenAI 兼容连接配置，角色差异通过配置表达，不绑定服务商 SDK。 */
@Data
@ConfigurationProperties(prefix = "app.ai")
public class SpringAiProperties {
    private Duration timeout = Duration.ofSeconds(180);
    private Duration memoryTtl = Duration.ofHours(1);
    private int historyMessages = 10;
    private int memoryMessages = 50;
    private int maxToolRounds = 50;
    private Role generation = new Role();
    private Role routing = new Role();
    private Role vue = new Role();

    /** 空的角色属性继承 spring.ai.openai 的通用连接属性。 */
    @Data
    public static class Role {
        private String baseUrl;
        private String apiKey;
        private String model;
        private String completionsPath;
        private Double temperature;
        private Integer maxTokens;
        private String reasoningEffort;
        private Map<String, String> headers = new HashMap<>();
        private Map<String, Object> extraBody = new HashMap<>();
    }
}
