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
    /** 模型请求超时；流式调用还以该时长限制相邻响应片段之间的等待。 */
    private Duration timeout = Duration.ofSeconds(180);
    /** 完整模型上下文的 Redis 生存时间，每次成功保存后重新计时。 */
    private Duration memoryTtl = Duration.ofHours(1);
    /** Redis 未命中时从可见历史回填的最大消息条数，包含入口已保存的本轮输入。 */
    private int historyMessages = 10;
    /** 模型记忆窗口的目标条数；单个用户工具轮次超过此值时仍保留整个轮次。 */
    private int memoryMessages = 50;
    /** Vue 模型请求轮数上限，包含工具执行后的最终文本续答请求。 */
    private int maxToolRounds = 50;
    /** HTML、多文件生成角色的配置覆盖。 */
    private Role generation = new Role();
    /** 应用创建时选择生成类型的角色配置覆盖。 */
    private Role routing = new Role();
    /** Vue 文件工具生成角色的配置覆盖。 */
    private Role vue = new Role();

    /** 空的角色属性继承 spring.ai.openai 的通用连接属性。 */
    @Data
    public static class Role {
        /** OpenAI 兼容服务的基础地址，空值继承通用连接配置。 */
        private String baseUrl;
        /** 外部注入的角色密钥，空值继承通用密钥。 */
        private String apiKey;
        /** 服务端识别的模型 ID，空值继承通用模型。 */
        private String model;
        /** 聊天补全接口路径，空值继承通用路径。 */
        private String completionsPath;
        /** 采样温度；null 表示继承通用值，0 是有效覆盖值。 */
        private Double temperature;
        /** 单次模型输出 token 上限，null 表示继承通用值。 */
        private Integer maxTokens;
        /** 端点支持的推理强度参数，空值继承通用值。 */
        private String reasoningEffort;
        /** 追加到通用请求头，同名键由角色值覆盖。 */
        private Map<String, String> headers = new HashMap<>();
        /** 追加到通用请求体的扩展字段，同名键由角色值覆盖。 */
        private Map<String, Object> extraBody = new HashMap<>();
    }
}
