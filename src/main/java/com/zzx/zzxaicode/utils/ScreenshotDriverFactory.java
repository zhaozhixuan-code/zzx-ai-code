package com.zzx.zzxaicode.utils;

import com.zzx.zzxaicode.config.ScreenshotProperties;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.github.bonigarcia.wdm.config.WebDriverManagerException;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.http.ClientConfig;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/** 创建独立的本地浏览器会话；显式指定驱动服务，避免 Selenium 再触发另一套驱动下载。 */
@Component
@Slf4j
public class ScreenshotDriverFactory {
    private final ScreenshotProperties properties;

    /** 注入服务器配置，构造阶段不启动浏览器或访问驱动下载站点。
     * @param properties 当前服务器的浏览器、驱动及超时配置
     */
    public ScreenshotDriverFactory(ScreenshotProperties properties) {
        this.properties = properties;
    }

    /** 创建一个独立会话，创建失败时停止驱动进程。
     * @return 新建会话；调用方必须在 finally 中 quit
     */
    public WebDriver createDriver() {
        // 先检查显式浏览器路径，避免错误配置触发无意义的驱动版本探测和下载。
        File browser = properties.getBrowserBinary() == null || properties.getBrowserBinary().isBlank()
                ? null : requireExecutable(properties.getBrowserBinary(), "浏览器");
        File executable = resolveDriverExecutable();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--disable-gpu", "--disable-dev-shm-usage",
                "--window-size=1600,900", "--disable-extensions");
        if (properties.isNoSandbox()) {
            options.addArguments("--no-sandbox");
        }
        if (browser != null) {
            options.setBinary(browser.getAbsolutePath());
        }
        // DOM 就绪后即返回，外部资源的慢请求交给后续有界渲染等待处理；保留浏览器真实 UA。
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        ChromeDriverService service = new ChromeDriverService.Builder()
                .usingDriverExecutable(executable).usingAnyFreePort()
                .withTimeout(properties.getDriverTimeout()).build();
        Duration commandTimeout = properties.getPageLoadTimeout().compareTo(properties.getRenderTimeout()) > 0
                ? properties.getPageLoadTimeout() : properties.getRenderTimeout();
        if (commandTimeout.compareTo(properties.getDriverTimeout()) < 0) {
            commandTimeout = properties.getDriverTimeout();
        }
        ClientConfig client = ClientConfig.defaultConfig()
                .connectionTimeout(properties.getDriverTimeout()).readTimeout(commandTimeout.plusSeconds(5));
        try {
            log.info("启动截图浏览器，driver={}, browser={}", executable, browser == null ? "自动发现 Chrome" : browser);
            return new ChromeDriver(service, options, client);
        } catch (WebDriverException e) {
            // 会话创建失败时尚未把 driver 交给调用方，必须在此停止已启动的驱动服务。
            service.stop();
            throw e;
        }
    }

    /**
     * 按配置、JVM 属性、PATH、自动解析的顺序选择驱动；显式错误路径不静默改用其他版本。
     * 同步限制仅覆盖驱动解析，避免多个任务同时下载或写入同一份缓存。
     * @return 当前进程可执行的驱动文件
     */
    synchronized File resolveDriverExecutable() {
        String configured = properties.getDriverPath();
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("webdriver.chrome.driver");
        }
        if (configured != null && !configured.isBlank()) {
            return requireExecutable(configured, "ChromeDriver");
        }
        File pathDriver = findDriverOnPath();
        if (pathDriver != null) {
            return pathDriver;
        }
        if (!properties.isAutoDownload()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "未找到本地 ChromeDriver，请配置 screenshot.driver-path 或 PATH；当前已关闭自动下载");
        }
        WebDriverManager manager = WebDriverManager.chromedriver().avoidExport().avoidFallback()
                .timeout(Math.toIntExact(properties.getDriverTimeout().toSeconds()));
        if (properties.getBrowserBinary() != null && !properties.getBrowserBinary().isBlank()) {
            manager.browserBinary(properties.getBrowserBinary());
        }
        try {
            manager.setup();
            return requireExecutable(manager.getDownloadedDriverPath(), "ChromeDriver");
        } catch (WebDriverManagerException e) {
            log.error("ChromeDriver 自动解析或下载失败，Linux 可预装匹配驱动并配置 screenshot.driver-path", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "ChromeDriver 自动解析或下载失败，请配置本地驱动");
        }
    }

    /** @return PATH 中首个可执行的 ChromeDriver；未安装时返回 null 交给下载开关决定后续行为 */
    File findDriverOnPath() {
        String searchPath = System.getenv("PATH");
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "chromedriver.exe" : "chromedriver";
        if (searchPath != null) {
            for (String directory : searchPath.split(File.pathSeparator)) {
                if (!directory.isBlank()) {
                    Path candidate = Path.of(directory.replace("\"", ""), name);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        return candidate.toAbsolutePath().toFile();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 校验可执行文件，Linux 上同时检查执行权限。
     * @param value 完整文件路径，不接受目录
     * @param type 用于错误提示的组件名称
     * @return 经过校验的绝对路径文件
     */
    private File requireExecutable(String value, String type) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, type + " 路径为空");
        }
        Path path = Path.of(value).toAbsolutePath();
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, type + " 不存在或没有执行权限：" + path);
        }
        return path.toFile();
    }
}
