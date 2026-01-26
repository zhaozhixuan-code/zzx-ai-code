package com.zzx.zzxaicode.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.zzx.zzxaicode.ai.model.HtmlCodeResult;
import com.zzx.zzxaicode.ai.model.MultiFileCodeResult;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 代码保存器
 * 用于将AI生成的代码保存到本地文件系统
 */
@Deprecated
public class CodeFileSaver {

    /**
     * 文件保存根目录
     * 默认保存到当前项目目录下的tmp/code_output子目录
     */
    private static final String FILE_SAVE_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_output";

    /**
     * 保存 HTML 模式生成的代码
     * 创建唯一目录并将HTML代码保存为index.html文件
     *
     * @param htmlCodeResult HTML 模式生成的代码结果对象
     * @return 保存后的目录File对象
     */
    public static File saveHtmlCodeResult(HtmlCodeResult htmlCodeResult) {
        // 构建基于HTML类型的唯一目录 tmp/code_output/biz_type_时间戳_雪花ID
        String buildUniqueDir = buildUniqueDir(CodeGenTypeEnum.HTML.getValue());
        // 将HTML代码内容写入index.html文件
        writeToFile(buildUniqueDir, "index.html", htmlCodeResult.getHtmlCode());
        // 返回保存目录的File对象
        return new File(buildUniqueDir);
    }

    /**
     * 保存多文件模式生成的代码
     * 创建唯一目录并将生成的文件保存到该目录下
     *
     * @param multiFileCodeResult 多文件模式生成的代码结果对象
     * @return 保存后的目录File对象
     */
    public static File saveMultiFileCodeResult(MultiFileCodeResult multiFileCodeResult) {
        String buildUniqueDir = buildUniqueDir(CodeGenTypeEnum.MULTI_FILE.getValue());
        writeToFile(buildUniqueDir, "index.html", multiFileCodeResult.getHtmlCode());
        writeToFile(buildUniqueDir, "style.css", multiFileCodeResult.getCssCode());
        writeToFile(buildUniqueDir, "script.js", multiFileCodeResult.getJsCode());
        return new File(buildUniqueDir);
    }


    /**
     * 构建唯一目录
     * 通过业务类型+时间戳+雪花ID创建唯一目录名，避免重复
     *
     * @param bizType 业务类型，用于区分不同类型的代码生成
     * @return 唯一目录路径，格式为: tmp/code_output/biz_type/日期_雪花ID
     */
    private static String buildUniqueDir(String bizType) {
        // 获取当前日期
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        // 组合业务类型、当前时间戳和雪花算法生成的唯一ID作为目录名
        String uniqueDirName = bizType + File.separator + date + "_" + IdUtil.getSnowflakeNextIdStr();
        // 拼接完整目录路径
        String dirPath = FILE_SAVE_ROOT_DIR + File.separator + uniqueDirName;
        // 创建目录（如果不存在则自动创建）
        FileUtil.mkdir(dirPath);
        return dirPath;
    }


    /**
     * 保存单个文件
     * 将指定内容写入指定路径的文件
     *
     * @param dirPath  目录路径
     * @param fileName 文件名称
     * @param content  文件内容（字符串格式）
     */
    private static void writeToFile(String dirPath, String fileName, String content) {
        // 拼接完整的文件路径
        String filePath = dirPath + File.separator + fileName;
        // 使用UTF-8编码将内容写入文件
        FileUtil.writeString(content, filePath, StandardCharsets.UTF_8);
    }
}
