package com.zzx.zzxaicode.core.parser;

import com.zzx.zzxaicode.ai.common.model.HtmlCodeResult;
import com.zzx.zzxaicode.ai.common.model.MultiFileCodeResult;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;

import java.util.regex.Pattern;

/** 解析提示词约定的代码块，并在保存前校验页面必需内容。 */
public final class GeneratedCodeResultParser {
    private static final Pattern RESOURCE_TAG = Pattern.compile("<(link|script)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOURCE_ATTRIBUTE = Pattern.compile(
            "\\s(href|src)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);
    /** 只提供无状态解析方法。 */
    private GeneratedCodeResultParser() { }

    /**
     * 解析普通模式输出，缺少 HTML 时禁止将空结果保存为成功。
     * @param text 完整模型输出
     * @param type HTML 或多文件类型
     * @return 公共代码结果 DTO；CSS/JS 为可选字段
     * @throws BusinessException 输出为空、必需 HTML 无效或引用的本地 CSS/JS 内容缺失
     */
    public static Object parse(String text, CodeGenTypeEnum type) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型未返回可保存的代码");
        }
        Object parsed = CodeParserExecutor.executeParser(text.trim(), type);
        String html = parsed instanceof HtmlCodeResult result ? result.getHtmlCode()
                : parsed instanceof MultiFileCodeResult result ? result.getHtmlCode() : null;
        // HTML 解析器允许直接返回页面原文；正文必须以标签开头，避免 JSON 或解释文字被保存成页面。
        if (html == null || !html.stripLeading().startsWith("<")) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型代码缺少有效 HTML 内容");
        }
        if (parsed instanceof MultiFileCodeResult result) {
            validateReferencedFiles(result);
        }
        return parsed;
    }

    /**
     * 检查三文件协议中被 HTML 引用的固定资源，避免页面已保存却缺少样式或交互脚本。
     * @param result 当前完整多文件结果；未引用的 CSS/JS 仍可为空
     * @throws BusinessException 引用了 style.css/script.js，但当前结果未提供对应非空内容
     */
    private static void validateReferencedFiles(MultiFileCodeResult result) {
        // 注释中的示例标签不是实际依赖，外部 URL 也不属于当前三文件保存协议。
        String html = result.getHtmlCode().replaceAll("(?s)<!--.*?-->", "");
        var tags = RESOURCE_TAG.matcher(html);
        while (tags.find()) {
            var attributes = RESOURCE_ATTRIBUTE.matcher(tags.group());
            while (attributes.find()) {
                String value = attributes.group(2) != null ? attributes.group(2)
                        : attributes.group(3) != null ? attributes.group(3) : attributes.group(4);
                // 本地固定资源带查询串、片段或 ./ 前缀时仍对应同一个待保存文件。
                String path = value.split("[?#]", 2)[0];
                if (path.startsWith("./")) {
                    path = path.substring(2);
                }
                boolean missingCss = "link".equalsIgnoreCase(tags.group(1)) && "href".equalsIgnoreCase(attributes.group(1))
                        && "style.css".equals(path) && (result.getCssCode() == null || result.getCssCode().isBlank());
                boolean missingJs = "script".equalsIgnoreCase(tags.group(1)) && "src".equalsIgnoreCase(attributes.group(1))
                        && "script.js".equals(path) && (result.getJsCode() == null || result.getJsCode().isBlank());
                if (missingCss || missingJs) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型代码引用了未提供的文件：" + path);
                }
            }
        }
    }
}
