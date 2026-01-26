package com.zzx.zzxaicode.core.saver;

import com.zzx.zzxaicode.ai.model.HtmlCodeResult;
import com.zzx.zzxaicode.ai.model.MultiFileCodeResult;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;

import java.io.File;

/**
 * 代码保存执行器
 * 根据代码生成类型执行对应的代码保存器
 */
public class CodeFileSaverExecutor {

    private static final CodeFileSaverTemplate<HtmlCodeResult> htmlCodeFileSaver = new HtmlCodeFileSaverTemplate();

    private static final CodeFileSaverTemplate<MultiFileCodeResult> multiFileCodeFileSaver = new MultiFileCodeFileSaverTemplate();

    /**
     * 执行代码保存器
     *
     * @param codeResult  代码结果
     * @param codeGenType 生成类型
     * @return 保存的文件
     */
    public static File executeSaver(Object codeResult, CodeGenTypeEnum codeGenType) {
        return switch (codeGenType) {
            case HTML -> htmlCodeFileSaver.saveCode((HtmlCodeResult) codeResult);

            case MULTI_FILE -> multiFileCodeFileSaver.saveCode((MultiFileCodeResult) codeResult);

            default -> throw new RuntimeException("不支持的生成类型");
        };

    }
}
