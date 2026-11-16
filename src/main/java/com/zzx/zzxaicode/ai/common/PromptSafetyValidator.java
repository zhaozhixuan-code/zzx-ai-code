package com.zzx.zzxaicode.ai.common;

import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 框架无关输入校验，保持原护栏的长度、敏感词及注入拒绝规则。 */
public final class PromptSafetyValidator {
    private static final List<String> WORDS = List.of("忽略之前的指令", "ignore previous instructions", "ignore above",
            "破解", "hack", "绕过", "bypass", "越狱", "jailbreak");
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(?:previous|above|all)\\s+(?:instructions?|commands?|prompts?)"),
            Pattern.compile("(?i)(?:forget|disregard)\\s+(?:everything|all)\\s+(?:above|before)"),
            Pattern.compile("(?i)(?:pretend|act|behave)\\s+(?:as|like)\\s+(?:if|you\\s+are)"),
            Pattern.compile("(?i)system\\s*:\\s*you\\s+are"),
            Pattern.compile("(?i)new\\s+(?:instructions?|commands?|prompts?)\\s*:"));

    /** 禁止实例化无状态校验器。 */
    private PromptSafetyValidator() { }

    /**
     * 在发出模型请求前校验本轮用户输入。
     * @param input 用户输入，不能为空或超过 1000 字符
     * @throws BusinessException 空白、过长、敏感词或注入模式命中时拒绝
     */
    public static void validate(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "输入内容不能为空");
        }
        if (input.length() > 1000) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "输入内容过长，不要超过 1000 字");
        }
        String lower = input.toLowerCase(Locale.ROOT);
        if (WORDS.stream().anyMatch(lower::contains)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "输入包含不当内容，请修改后重试");
        }
        if (PATTERNS.stream().anyMatch(pattern -> pattern.matcher(input).find())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "检测到恶意输入，请求被拒绝");
        }
    }
}
