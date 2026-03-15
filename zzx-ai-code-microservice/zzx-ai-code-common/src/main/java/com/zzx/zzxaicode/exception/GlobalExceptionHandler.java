package com.zzx.zzxaicode.exception;

import cn.hutool.json.JSONUtil;
import com.zzx.zzxaicode.common.BaseResponse;
import com.zzx.zzxaicode.common.ResultUtils;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Hidden
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException", e);
        // 尝试处理 SSE 请求
        if (handleSseError(e.getCode(), e.getMessage())) {
            return null;
        }
        // 对于普通请求，返回标准 JSON 响应
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

/*      *//**
     * 处理 GuardrailException LangChain4j 的护轨错误
     * @param e
     * @return
     *//*
    @ExceptionHandler(GuardrailException.class)
    public BaseResponse<?> guardrailExceptionHandler(GuardrailException e) {
        log.error("GuardrailException", e);

        // 从 GuardrailException 消息中提取原始错误信息
        // 格式："The guardrail xxx failed with this message: 实际错误消息"
        String errorMessage = extractOriginalMessage(e.getMessage());

        // 尝试处理 SSE 请求
        if (handleSseError(ErrorCode.PARAMS_ERROR.getCode(), errorMessage)) {
            return null;
        }
        // 对于普通请求，返回标准 JSON 响应
        return ResultUtils.error(ErrorCode.PARAMS_ERROR.getCode(), errorMessage);
    } */

    /**
     * 从 GuardrailException 消息中提取原始错误信息
     * @param message GuardrailException 的原始消息
     * @return 提取后的原始错误信息
     */
    private String extractOriginalMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "输入验证失败";
        }

        // 使用正则表达式匹配 "failed with this message: " 后面的内容
        Pattern pattern = Pattern.compile("failed with this message:\\s*(.+)$");
        Matcher matcher = pattern.matcher(message);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 如果匹配失败，返回完整消息（去掉类名部分）
        if (message.contains(":")) {
            int lastColonIndex = message.lastIndexOf(":");
            return message.substring(lastColonIndex + 1).trim();
        }

        return message;
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        // 尝试处理 SSE 请求
        if (handleSseError(ErrorCode.SYSTEM_ERROR.getCode(), "系统错误")) {
            return null;
        }
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }

    /**
     * 处理SSE请求的错误响应
     *
     * @param errorCode 错误码
     * @param errorMessage 错误信息
     * @return true表示是SSE请求并已处理，false表示不是SSE请求
     */
    private boolean handleSseError(int errorCode, String errorMessage) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return false;
        }
        HttpServletRequest request = attributes.getRequest();
        HttpServletResponse response = attributes.getResponse();
        // 判断是否是SSE请求（通过Accept头或URL路径）
        String accept = request.getHeader("Accept");
        String uri = request.getRequestURI();
        if ((accept != null && accept.contains("text/event-stream")) ||
                uri.contains("/chat/gen/code")) {
            try {
                // 设置SSE响应头
                response.setContentType("text/event-stream");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-cache");
                response.setHeader("Connection", "keep-alive");
                // 构造错误消息的SSE格式
                Map<String, Object> errorData = Map.of(
                        "error", true,
                        "code", errorCode,
                        "message", errorMessage
                );
                String errorJson = JSONUtil.toJsonStr(errorData);
                // 发送业务错误事件（避免与标准error事件冲突）
                String sseData = "event: business-error\ndata: " + errorJson + "\n\n";
                response.getWriter().write(sseData);
                response.getWriter().flush();
                // 发送结束事件
                response.getWriter().write("event: done\ndata: {}\n\n");
                response.getWriter().flush();
                // 表示已处理SSE请求
                return true;
            } catch (IOException ioException) {
                log.error("Failed to write SSE error response", ioException);
                // 即使写入失败，也表示这是SSE请求
                return true;
            }
        }
        return false;
    }
}
