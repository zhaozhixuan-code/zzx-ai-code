package com.zzx.zzxaicode.ai.springai;

import com.zzx.zzxaicode.ai.common.CodeGenTypeRouter;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.config.springai.SpringAiProperties;
import com.zzx.zzxaicode.monitor.MonitorContextHolder;
import org.springframework.ai.openai.OpenAiChatOptions;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.List;
import java.util.Map;

/** 只接受枚举名或现有枚举值，非法路由拒绝创建应用。 */
@Service
public class SpringAiCodeGenTypeRouter implements CodeGenTypeRouter {
    private final ChatModel model;
    private final SpringAiPromptLoader prompts;
    private final SpringAiProperties properties;

    /**
     * 注入专用路由模型及提示词。
     * @param model 路由角色模型
     * @param prompts 原提示词加载器
     * @param properties 请求超时配置
     */
    public SpringAiCodeGenTypeRouter(@Qualifier("springAiRoutingModel") ChatModel model,
                                     SpringAiPromptLoader prompts, SpringAiProperties properties) {
        this.model = model;
        this.prompts = prompts;
        this.properties = properties;
    }

    /**
     * 使用原路由提示词分类。
     * @param userPrompt 已由应用创建入口校验的需求文本
     * @return 支持的代码生成类型
     * @throws BusinessException 模型空结果或非法分类
     */
    @Override
    public CodeGenTypeEnum routeCodeGenType(String userPrompt) {
        var monitor = MonitorContextHolder.getContext();
        var options = OpenAiChatOptions.builder().toolContext(Map.of(
                "userId", monitor == null || monitor.getUserId() == null ? "unknown" : monitor.getUserId(),
                "appId", "routing")).build();
        // 路由只用于新应用分类，不加载应用记忆；所有文本片段收齐后只解析一次枚举。
        var response = model.stream(new Prompt(List.of(
                new SystemMessage(prompts.load("codegen-routing-system-prompt.txt")
                        + "\n只返回 HTML、MULTI_FILE、VUE_PROJECT 之一，不添加解释。"),
                new UserMessage(userPrompt)), options))
                .timeout(properties.getTimeout())
                .map(item -> item.getResult() == null || item.getResult().getOutput().getText() == null
                        ? "" : item.getResult().getOutput().getText())
                .collectList().map(parts -> String.join("", parts)).block(properties.getTimeout());
        return parse(response);
    }

    /**
     * 严格解析模型分类结果，兼容 JSON 字符串外层引号。
     * @param output 模型分类文本，可空
     * @return 原项目枚举
     * @throws BusinessException 输出不是单一有效分类
     */
    static CodeGenTypeEnum parse(String output) {
        String value = output == null ? "" : output.trim();
        // 允许模型返回单个 JSON 字符串，但解释性文本或多个候选仍会被下方枚举匹配拒绝。
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        for (CodeGenTypeEnum type : CodeGenTypeEnum.values()) {
            if (type.name().equals(value.toUpperCase(Locale.ROOT)) || type.getValue().equals(value)) {
                return type;
            }
        }
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型未返回有效的代码生成类型，请重试");
    }
}
