package com.zzx.zzxaicode.core.saver;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 代码文件保存模板类
 *
 * @param <T> HtmlCodeResult 或 MultiFileCodeResult
 */
public abstract class CodeFileSaverTemplate<T> {

    /**
     * 文件保存根目录
     * 默认保存到当前项目目录下的tmp/code_output子目录
     */
    private static final String FILE_SAVE_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_output";


    /**
     * 保存代码流程
     *
     * @param result 代码结果对象
     * @return 保存后的目录File对象
     */
    public File saveCode(T result) {
        // 1. 输入校验
        this.validateInput(result);
        // 2. 构建唯一目录
        String baseDirPath = buildUniqueDir();
        // 3. 把代码保存到文件(实现交给子类)
        saveFile(baseDirPath, result);
        // 4. 返回保存目录的File对象
        return new File(baseDirPath);
    }

    /**
     * 输入校验(可由子类覆盖)
     *
     * @param result
     */
    protected void validateInput(T result) {
        if (result == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数错误");
        }
    }


    /**
     * 构建唯一目录
     * 通过业务类型+时间戳+雪花ID创建唯一目录名，避免重复
     *
     * @return 唯一目录路径，格式为: tmp/code_output/biz_type/日期_雪花ID
     */
    protected String buildUniqueDir() {
        // 获取当前日期
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        // 获得业务类型
        String bizType = getCodeType().getValue();
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
    protected void writeToFile(String dirPath, String fileName, String content) {
        // 拼接完整的文件路径
        String filePath = dirPath + File.separator + fileName;
        // 使用UTF-8编码将内容写入文件
        FileUtil.writeString(content, filePath, StandardCharsets.UTF_8);
    }

    /**
     * 获得业务类型
     *
     * @return 业务类型枚举
     */
    protected abstract CodeGenTypeEnum getCodeType();


    /**
     * 保存文件
     *
     * @param baseDirPath 基础目录路径
     * @param result      代码对象
     */
    protected abstract void saveFile(String baseDirPath, T result);

}
