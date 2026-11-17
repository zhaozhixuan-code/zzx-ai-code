package com.zzx.zzxaicode.utils;

import com.zzx.zzxaicode.config.ScreenshotProperties;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 有界并发的网页截图组件；不共享浏览器会话，初始化失败不会污染后续任务。 */
@Component
@Slf4j
public class WebScreenshotUtils {
    private final ScreenshotDriverFactory driverFactory;
    private final ScreenshotProperties properties;
    private final Semaphore slots;

    /**
     * @param driverFactory 每次调用创建独立浏览器的工厂
     * @param properties 截图等待、并发及文件目录配置
     */
    public WebScreenshotUtils(ScreenshotDriverFactory driverFactory, ScreenshotProperties properties) {
        this.driverFactory = driverFactory;
        this.properties = properties;
        this.slots = new Semaphore(properties.getMaxConcurrent(), true);
    }

    /**
     * 在有界并发名额内完成网页截图，仅对已经建立的浏览器会话异常重试一次。
     * @param webUrl 需要截图的完整网页地址
     * @return 本次任务独占的 JPEG 文件路径，成功后由上传服务清理
     * @throws BusinessException 截图失败、等待名额超时或任务被中断
     */
    public String saveWebPageScreenshot(String webUrl) {
        if (webUrl == null || webUrl.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "网页地址不能为空");
        }
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire(properties.getQueueTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "截图任务繁忙，等待浏览器名额超时");
            }
            for (int attempt = 1; attempt <= 2; attempt++) {
                WebDriver driver = null;
                try {
                    driver = driverFactory.createDriver();
                    driver.manage().timeouts().pageLoadTimeout(properties.getPageLoadTimeout());
                    driver.get(webUrl);
                    waitForPageLoad(driver);
                    String path = saveCompressedImage(((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
                    log.info("截图保存成功，网页地址={}, 文件={}", webUrl, path);
                    return path;
                } catch (WebDriverException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "截图任务被中断");
                    }
                    if (driver == null || attempt == 2) {
                        log.error("网页截图失败，网页地址={}, 尝试次数={}；请核对浏览器/驱动版本及 Linux 运行依赖",
                                webUrl, attempt, e);
                        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "浏览器截图失败，请查看截图日志");
                    }
                    log.warn("截图会话异常，将关闭浏览器后重试一次，网页地址={}", webUrl, e);
                } finally {
                    if (driver != null) {
                        try {
                            driver.quit();
                        } catch (WebDriverException e) {
                            // 清理异常只记录，不能覆盖已生成的图片或原始失败原因。
                            log.warn("关闭截图浏览器失败，网页地址={}", webUrl, e);
                        }
                    }
                }
            }
            throw new IllegalStateException("截图重试次数已耗尽");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "截图任务被中断");
        } finally {
            if (acquired) {
                slots.release();
            }
        }
    }

    /**
     * 等待 DOM 可访问，再给前端挂载时间；图片或字体长期未完成时保留已渲染内容。
     * @param driver 当前任务独占的浏览器
     * @throws InterruptedException 等待期间收到取消信号，向上传递以停止任务
     */
    private void waitForPageLoad(WebDriver driver) throws InterruptedException {
        WebDriverWait wait = new WebDriverWait(driver, properties.getRenderTimeout());
        wait.until(current -> Boolean.TRUE.equals(((JavascriptExecutor) current).executeScript(
                "return document.readyState !== 'loading' && document.body !== null")));
        Thread.sleep(properties.getSettleDelay().toMillis());
        try {
            wait.until(current -> Boolean.TRUE.equals(((JavascriptExecutor) current).executeScript(
                    "return Array.from(document.images).every(img => img.complete)"
                            + " && (!document.fonts || document.fonts.status === 'loaded')")));
        } catch (TimeoutException e) {
            log.warn("图片或字体等待超时，截取当前已渲染页面");
        }
    }

    /**
     * 在内存中把 PNG 转成 JPEG，仅落盘一个独占文件，避免失败时遗留原始 PNG 或临时目录。
     * @param png 浏览器返回的 PNG 字节
     * @return 压缩文件的绝对路径；失败时清理本方法创建的单个文件
     */
    private String saveCompressedImage(byte[] png) {
        Path output = null;
        try {
            var image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) {
                throw new IOException("浏览器返回的截图无法解码");
            }
            Files.createDirectories(properties.getOutputDirectory());
            output = Files.createTempFile(properties.getOutputDirectory(), "screenshot-", ".jpg");
            // JPEG 不支持透明通道，先合成白底 RGB，避免透明 PNG 引发颜色空间不兼容。
            var rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = rgb.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try (var stream = ImageIO.createImageOutputStream(output.toFile())) {
                writer.setOutput(stream);
                var parameters = writer.getDefaultWriteParam();
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(0.3f);
                writer.write(null, new IIOImage(rgb, null, null), parameters);
            } finally {
                writer.dispose();
            }
            return output.toAbsolutePath().toString();
        } catch (IOException e) {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException cleanupError) {
                    e.addSuppressed(cleanupError);
                }
            }
            log.error("保存压缩截图失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存压缩截图失败");
        }
    }
}
