package com.zzx.zzxaicode.core.parser;

import cn.hutool.json.JSONUtil;
import com.zzx.zzxaicode.ai.common.model.HtmlCodeResult;
import com.zzx.zzxaicode.ai.common.model.MultiFileCodeResult;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;

/** 接收文本代码块或结构化 JSON，并在保存前校验页面必需内容。 */
public final class GeneratedCodeResultParser {
    /** 只提供无状态解析方法。 */
    private GeneratedCodeResultParser() { }

    /**
     * 解析普通模式输出，缺少 HTML 时禁止将空结果保存为成功。
     * @param text 完整模型输出
     * @param type HTML 或多文件类型
     * @return 公共代码结果 DTO；CSS/JS 为可选字段
     * @throws BusinessException JSON 无效或必需 HTML 为空
     */
    public static Object parse(String text, CodeGenTypeEnum type) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型未返回可保存的代码");
        }
        String source = text.trim();
        if (source.startsWith("```json") && source.endsWith("```")) {
            source = source.substring(7, source.length() - 3).trim();
        }
        Object parsed;
        try {
            parsed = source.startsWith("{") ? switch (type) {
                case HTML -> JSONUtil.toBean(source, HtmlCodeResult.class);
                case MULTI_FILE -> JSONUtil.toBean(source, MultiFileCodeResult.class);
                default -> throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的解析类型");
            } : CodeParserExecutor.executeParser(source, type);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型代码结果解析失败");
        }
        String html = parsed instanceof HtmlCodeResult result ? result.getHtmlCode()
                : parsed instanceof MultiFileCodeResult result ? result.getHtmlCode() : null;
        if (html == null || html.isBlank() || !html.contains("<")) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型代码缺少有效 HTML 内容");
        }
        return parsed;
    }
}
