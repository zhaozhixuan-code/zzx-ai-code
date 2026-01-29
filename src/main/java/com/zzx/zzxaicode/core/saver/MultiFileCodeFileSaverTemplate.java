package com.zzx.zzxaicode.core.saver;

import cn.hutool.core.util.StrUtil;
import com.zzx.zzxaicode.ai.model.HtmlCodeResult;
import com.zzx.zzxaicode.ai.model.MultiFileCodeResult;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;

/**
 * 多文件代码保存
 */
public class MultiFileCodeFileSaverTemplate extends CodeFileSaverTemplate<MultiFileCodeResult> {

    /**
     * 获得业务类型
     *
     * @return
     */
    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.MULTI_FILE;
    }

    /**
     * 保存文件
     *
     * @param baseDirPath 基础目录路径
     * @param result      代码对象
     */
    @Override
    protected void saveFile(String baseDirPath, MultiFileCodeResult result) {
        String htmlCode = result.getHtmlCode();
        String cssCode = result.getCssCode();
        String jsCode = result.getJsCode();

        // 保存 html 文件
        if (StrUtil.isNotEmpty(htmlCode)) {
            writeToFile(baseDirPath, "index.html", htmlCode);
        }
        // 保存 css 文件
        if (StrUtil.isNotEmpty(cssCode)) {
            writeToFile(baseDirPath, "style.css", cssCode);
        }
        // 保存 js 文件
        if (StrUtil.isNotEmpty(jsCode)) {
            writeToFile(baseDirPath, "script.js", jsCode);
        }
    }

    /**
     * 重写校验参数方法
     *
     * @param result
     */
    @Override
    protected void validateInput(MultiFileCodeResult result) {
        super.validateInput(result);
        // html 参数必须不能为空,
        // 修改html可以为空
        // String htmlCode = result.getHtmlCode();
        // if (StrUtil.isEmpty(htmlCode)) {
        //     throw new BusinessException(ErrorCode.PARAMS_ERROR, "html 参数为空");
        // }
    }
}
