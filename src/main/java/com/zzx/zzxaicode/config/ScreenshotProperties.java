package com.zzx.zzxaicode.config;

import com.zzx.zzxaicode.constants.AppConstant;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.nio.file.Path;
import java.time.Duration;

/** 截图运行配置；生产环境可指定预装的 Chrome/Chromium 与 ChromeDriver，避免运行时下载。 */
@Data
@Component
@ConfigurationProperties(prefix = "screenshot")
public class ScreenshotProperties {
    /** 浏览器完整路径；空值使用 ChromeDriver 的浏览器发现机制，Chromium 可显式指定。 */
    private String browserBinary;
    /** 驱动完整路径；优先于 webdriver.chrome.driver 系统属性及 PATH。 */
    private String driverPath;
    /** 找不到本地驱动时是否允许 WebDriverManager 解析和下载；离线服务器应关闭。 */
    private boolean autoDownload = true;
    /** 单次驱动网络连接/读取及驱动进程启动等待，并非整个截图任务的总超时。 */
    private Duration driverTimeout = Duration.ofSeconds(15);
    /** 浏览器导航至 DOM 就绪的最长等待。 */
    private Duration pageLoadTimeout = Duration.ofSeconds(30);
    /** DOM、图片及字体就绪检查各自的最长等待；资源超时后截取已渲染内容。 */
    private Duration renderTimeout = Duration.ofSeconds(15);
    /** DOM 就绪后留给 Vue 挂载和异步内容展示的等待时间，可设为零。 */
    private Duration settleDelay = Duration.ofSeconds(2);
    /** 等待空闲浏览器名额的最长时间，超时明确失败，避免无期限排队。 */
    private Duration queueTimeout = Duration.ofSeconds(60);
    /** 同时运行的浏览器任务上限，默认单任务以控制服务器内存占用。 */
    private int maxConcurrent = 1;
    /** 延续原启动行为；Linux 使用具备沙盒权限的普通用户运行时可以关闭。 */
    private boolean noSandbox = true;
    /** 压缩截图的保存目录，应用运行用户必须具有写权限。 */
    private Path outputDirectory = Path.of(AppConstant.IMAGE_ROOT_PATH);

    /** 启动时拒绝无效的时长或并发配置，避免到截图任务中才暴露配置错误。 */
    @PostConstruct
    public void validate() {
        Assert.isTrue(maxConcurrent > 0, "screenshot.max-concurrent 必须大于 0");
        Assert.isTrue(driverTimeout != null && driverTimeout.toSeconds() > 0,
                "screenshot.driver-timeout 必须至少为 1 秒");
        Assert.isTrue(pageLoadTimeout != null && pageLoadTimeout.toMillis() > 0,
                "screenshot.page-load-timeout 必须大于 0");
        Assert.isTrue(renderTimeout != null && renderTimeout.toMillis() > 0,
                "screenshot.render-timeout 必须大于 0");
        Assert.isTrue(queueTimeout != null && queueTimeout.toMillis() > 0,
                "screenshot.queue-timeout 必须大于 0");
        Assert.isTrue(settleDelay != null && !settleDelay.isNegative(),
                "screenshot.settle-delay 不能小于 0");
        Assert.notNull(outputDirectory, "screenshot.output-directory 不能为空");
    }
}
