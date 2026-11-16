package com.zzx.zzxaicode.ai.springai;

import com.zzx.zzxaicode.ai.common.CodeGenTypeRouter;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.monitor.MonitorContextHolder;
import org.springframework.ai.openai.OpenAiChatOptions;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 按固定枚举名解析应用类型，非法路由拒绝创建应用。 */
@Service
public class SpringAiCodeGenTypeRouter implements CodeGenTypeRouter {
    private final ChatModel model;
    private final SpringAiPromptLoader prompts;

    /**
     * 注入专用路由模型及提示词。
     * @param model 路由角色模型
     * @param prompts 原提示词加载器
     */
    public SpringAiCodeGenTypeRouter(@Qualifier("springAiRoutingModel") ChatModel model,
                                     SpringAiPromptLoader prompts) {
        this.model = model;
        this.prompts = prompts;
    }

    /**
     * 同步获取分类结果；请求连接及读取超时由路由模型的 HTTP 客户端配置控制。
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
        // 创建应用只需要一个最终分类，无需流式收集后再次阻塞等待。
        var response = model.call(new Prompt(List.of(
                new SystemMessage(prompts.load("codegen-routing-system-prompt.txt")
                        + "\n只返回 HTML、MULTI_FILE、VUE_PROJECT 之一，不添加解释。"),
                new UserMessage(userPrompt)), options));
        return parse(response.getResult() == null ? null : response.getResult().getOutput().getText());
    }

    /**
     * 只解析提示词声明的枚举名，忽略首尾空白和大小写。
     * @param output 模型分类文本，可空
     * @return 原项目枚举
     * @throws BusinessException 输出不是单一有效分类
     */
    static CodeGenTypeEnum parse(String output) {
        String value = output == null ? "" : output.trim();
        for (CodeGenTypeEnum type : CodeGenTypeEnum.values()) {
            if (type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型未返回有效的代码生成类型，请重试");
    }
}
