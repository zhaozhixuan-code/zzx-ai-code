package com.zzx.zzxaicode.utils;

import com.sun.net.httpserver.HttpServer;
import com.zzx.zzxaicode.config.ScreenshotProperties;
import com.zzx.zzxaicode.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 显式开启的真实浏览器验证；只使用本机页面和预装驱动，不下载、不访问 COS 或数据库。 */
@EnabledIfSystemProperty(named = "screenshot.browser-test", matches = "true")
class WebScreenshotBrowserTest {
    @TempDir
    Path directory;

    /** 并发截取两个延迟挂载的页面，并验证 Chrome 输出 JPEG 的实际像素与页面对应。 */
    @Test
    void realChromeRendersDelayedPagesIndependently() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String color = exchange.getRequestURI().getPath().contains("red") ? "rgb(255,0,0)" : "rgb(0,0,255)";
            byte[] page = ("<!doctype html><html><body style='margin:0;background:white'>"
                    + "<script>setTimeout(()=>{document.body.style.background='" + color
                    + "';document.body.textContent='rendered'},200)</script></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            try (var output = exchange.getResponseBody()) {
                output.write(page);
            }
        });
        server.start();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var config = properties();
            config.setMaxConcurrent(2);
            config.setSettleDelay(Duration.ofMillis(600));
            var screenshots = new WebScreenshotUtils(new ScreenshotDriverFactory(config), config);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var red = workers.submit(() -> screenshots.saveWebPageScreenshot(base + "/red"));
            var blue = workers.submit(() -> screenshots.saveWebPageScreenshot(base + "/blue"));
            String redFile = red.get(45, TimeUnit.SECONDS);
            String blueFile = blue.get(45, TimeUnit.SECONDS);
            assertNotEquals(redFile, blueFile);
            assertColor(redFile, Color.RED);
            assertColor(blueFile, Color.BLUE);
        } finally {
            server.stop(0);
        }
    }

    /** 第一次因浏览器路径错误失败后，修正配置即可再次截图，不需要重启 JVM。 */
    @Test
    void realChromeCanRecoverAfterInitializationFailure() throws Exception {
        var config = properties();
        String actualBrowser = config.getBrowserBinary();
        config.setBrowserBinary(directory.resolve("missing-browser").toString());
        var screenshots = new WebScreenshotUtils(new ScreenshotDriverFactory(config), config);
        assertThrows(BusinessException.class, () -> screenshots.saveWebPageScreenshot("about:blank"));
        config.setBrowserBinary(actualBrowser);
        assertColor(screenshots.saveWebPageScreenshot(
                "data:text/html,<html><body style='background:rgb(0,0,255)'>recovered</body></html>"), Color.BLUE);
    }

    /** 图片响应一直挂起时，EAGER 导航和资源等待上限应让截图在响应完成前成功。 */
    @Test
    void realChromeCapturesPageWithStalledImage() throws Exception {
        var imageRequested = new CountDownLatch(1);
        var releaseImage = new CountDownLatch(1);
        var imageFinished = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(requests);
            server.createContext("/slow-image", exchange -> {
                imageRequested.countDown();
                try {
                    releaseImage.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    imageFinished.set(true);
                    exchange.close();
                }
            });
            server.createContext("/", exchange -> {
                byte[] page = "<html><body style='background:rgb(0,0,255)'><img src='/slow-image'></body></html>"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, page.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(page);
                }
            });
            server.start();
            try {
                var config = properties();
                config.setSettleDelay(Duration.ZERO);
                config.setRenderTimeout(Duration.ofMillis(100));
                var screenshots = new WebScreenshotUtils(new ScreenshotDriverFactory(config), config);
                String file = screenshots.saveWebPageScreenshot("http://127.0.0.1:" + server.getAddress().getPort());
                assertTrue(imageRequested.await(1, TimeUnit.SECONDS));
                assertFalse(imageFinished.get(), "截图不应等待挂起的图片请求完成");
                assertColor(file, Color.BLUE);
            } finally {
                releaseImage.countDown();
                server.stop(0);
            }
        }
    }

    /**
     * @return 只使用显式提供的本地驱动的配置，缺少系统属性时直接失败而非尝试联网
     */
    private ScreenshotProperties properties() {
        var config = new ScreenshotProperties();
        config.setDriverPath(System.getProperty("screenshot.test.driver"));
        assertNotNull(config.getDriverPath(), "请显式设置 -Dscreenshot.test.driver=本地驱动完整路径");
        config.setBrowserBinary(System.getProperty("screenshot.test.browser"));
        config.setAutoDownload(false);
        config.setOutputDirectory(directory);
        config.setSettleDelay(Duration.ofMillis(300));
        return config;
    }

    /** @param file 实际截图文件；@param expected 页面背景颜色，允许 JPEG 压缩误差 */
    private void assertColor(String file, Color expected) throws Exception {
        var image = ImageIO.read(Path.of(file).toFile());
        assertNotNull(image);
        var pixel = new Color(image.getRGB(40, 40));
        assertTrue(Math.abs(pixel.getRed() - expected.getRed()) < 10, "red=" + pixel);
        assertTrue(Math.abs(pixel.getGreen() - expected.getGreen()) < 10, "green=" + pixel);
        assertTrue(Math.abs(pixel.getBlue() - expected.getBlue()) < 10, "blue=" + pixel);
    }
}
