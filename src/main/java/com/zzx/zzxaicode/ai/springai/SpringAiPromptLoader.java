package com.zzx.zzxaicode.ai.springai;

import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 以原文读取既有提示词，避免模板引擎解析示例代码中的大括号。 */
@Component
public class SpringAiPromptLoader {
    /**
     * 获取生成类型对应的系统提示词。
     * @param type 支持的代码生成类型
     * @return UTF-8 提示词原文
     */
    public String forType(CodeGenTypeEnum type) {
        return load(switch (type) {
            case HTML -> "codegen-html-system-prompt.txt";
            case MULTI_FILE -> "codegen-multi-file-system-prompt.txt";
            case VUE_PROJECT -> "codegen-vue-project-system-prompt.txt";
        });
    }

    /**
     * 读取固定资源名，不接受用户路径。
     * @param fileName prompt 目录内的固定文件名
     * @return 提示词原文
     * @throws BusinessException 资源不存在或不可读取
     */
    public String load(String fileName) {
        try {
            return new ClassPathResource("prompt/" + fileName).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统提示词读取失败");
        }
    }
}
