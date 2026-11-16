package com.zzx.zzxaicode.config.springai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/** 主业务通用连接，使用 Spring AI OpenAI 属性路径，密钥仅从外部配置读取。 */
@Data
@ConfigurationProperties(prefix = "spring.ai.openai")
public class OpenAiConnectionProperties {
    /** 兼容服务的基础地址，具体补全路径由 chat.completionsPath 提供。 */
    private String baseUrl;
    /** 从环境变量或本地安全配置注入，不写入提示词。 */
    private String apiKey;
    /** 三个模型角色共享的聊天端点及默认选项。 */
    private Chat chat = new Chat();

    /** 聊天端点及模型选项。 */
    @Data
    public static class Chat {
        /** 相对于基础地址的聊天补全路径。 */
        private String completionsPath = "/v1/chat/completions";
        /** 角色未单独指定时使用的模型选项。 */
        private Options options = new Options();
    }

    /** 未提供 usage 的端点可以关闭 streamUsage，缺失数据不填零。 */
    @Data
    public static class Options {
        /** 兼容端点识别的模型 ID。 */
        private String model;
        /** 默认采样温度，可由生成、路由或 Vue 角色分别覆盖。 */
        private Double temperature = 0.7;
        /** 单次响应的默认输出 token 上限。 */
        private Integer maxTokens = 8192;
        /** 请求流式 usage 统计；不支持该参数的端点应关闭。 */
        private boolean streamUsage = true;
        /** 端点支持的推理强度参数，原样交给 Spring AI 请求选项。 */
        private String reasoningEffort;
        /** 各角色共享的 HTTP 请求头，角色同名头优先。 */
        private Map<String, String> httpHeaders = new HashMap<>();
        /** 兼容端点的附加请求体字段，角色同名字段优先。 */
        private Map<String, Object> extraBody = new HashMap<>();
    }
}
