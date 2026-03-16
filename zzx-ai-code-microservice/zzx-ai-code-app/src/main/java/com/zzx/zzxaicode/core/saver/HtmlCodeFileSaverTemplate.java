package com.zzx.zzxaicode.core.saver;

import cn.hutool.core.util.StrUtil;
import com.zzx.zzxaicode.ai.model.HtmlCodeResult;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;

/**
 * HTML代码保存
 */
public class HtmlCodeFileSaverTemplate extends CodeFileSaverTemplate<HtmlCodeResult> {

    /**
     * 获得业务类型
     *
     * @return
     */
    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.HTML;
    }

    /**
     * 保存文件
     *
     * @param baseDirPath 基础目录路径
     * @param result      代码对象
     */
    @Override
    protected void saveFile(String baseDirPath, HtmlCodeResult result) {
        writeToFile(baseDirPath, "index.html", result.getHtmlCode());
    }


    /**
     * 重写校验参数方法
     *
     * @param result
     */
    @Override
    protected void validateInput(HtmlCodeResult result) {
        super.validateInput(result);
        String htmlCode = result.getHtmlCode();
        if (StrUtil.isEmpty(htmlCode)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "html 参数为空");
        }
    }
}
