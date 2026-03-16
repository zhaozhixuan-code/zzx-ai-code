package com.zzx.zzxaicode.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.exception.ThrowUtils;
import com.zzx.zzxaicode.manager.CosManager;
import com.zzx.zzxaicode.service.ScreenshotService;
import com.zzx.zzxaicode.utils.WebScreenshotUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 截图服务类
 */
@Service
@Slf4j
public class ScreenshotServiceImpl implements ScreenshotService {

    @Resource
    private CosManager cosManager;


    /**
     * 生成截图并上传
     *
     * @param webUrl 网页地址
     * @return 截图上传后的地址
     */
    @Override
    public String generateAndUploadScreenshot(String webUrl) {
        // 1. 校验参数
        ThrowUtils.throwIf(StrUtil.isBlank(webUrl), ErrorCode.PARAMS_ERROR, "网页地址不能为空");
        log.info("开始生成截图，网页地址:{}", webUrl);
        // 2. 本地截图
        String localScreenshotPath = WebScreenshotUtils.saveWebPageScreenshot(webUrl);
        ThrowUtils.throwIf(localScreenshotPath == null, ErrorCode.SYSTEM_ERROR, "生成截图失败");
        try {
            // 3. 上传文件到 COS
            String cosUrl = uploadScreenshotToCos(localScreenshotPath);
            ThrowUtils.throwIf(StrUtil.isBlank(cosUrl), ErrorCode.OPERATION_ERROR, "上传文件失败");
            log.info("截图上传成功，访问的URL为：{}", cosUrl);
            return cosUrl;
        } finally {
            // 4. 清理本地文件
            cleanupLocalFile(localScreenshotPath);
        }
    }

    /**
     * 上传截图文件到 COS
     *
     * @param localScreenshotPath 本地截图文件路径
     * @return 文件访问的 URL，失败返回 null
     */
    private String uploadScreenshotToCos(String localScreenshotPath) {
        if (StrUtil.isBlank(localScreenshotPath)) {
            return null;
        }
        File screenshotFile = new File(localScreenshotPath);
        if (!screenshotFile.exists()) {
            log.error("截图文件不存在:{}", localScreenshotPath);
            return null;
        }
        // 生成 COS 对象键
        String fileName = UUID.randomUUID().toString().substring(0, 8) + "compressed.jpg";
        String cosKey = generateScreenShotKey(fileName);
        // 上传文件
        return cosManager.uploadFile(cosKey, screenshotFile);
    }

    /**
     * 生成截图的 COS 对象键
     * 格式 /screenshot/2025/01/01/fileName
     *
     * @param fileName 文件名
     * @return
     */
    private String generateScreenShotKey(String fileName) {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return String.format("screenshot/%s/%s", datePath, fileName);
    }

    /**
     * 清理本地文件
     *
     * @param localFilePath 本地文件路径
     */
    private void cleanupLocalFile(String localFilePath) {
        File file = new File(localFilePath);
        if (file.exists()) {
            File parentFile = file.getParentFile();
            FileUtil.del(parentFile);
            log.info("清理本地文件成功:{}", localFilePath);
        }
    }
}
