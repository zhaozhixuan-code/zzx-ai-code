package com.zzx.zzxaicode.utils;

import com.zzx.zzxaicode.config.ScreenshotProperties;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用独立浏览器替身验证失败恢复、并发隔离和资源边界，不启动 Spring、网络或 COS。 */
class WebScreenshotUtilsTest {
    @TempDir
    Path directory;

    /** 初始化失败应仅影响本次请求；后续请求仍能创建浏览器并保存有效 JPEG。 */
    @Test
    void initializationFailureDoesNotPoisonLaterRequests() throws Exception {
        var factory = mock(ScreenshotDriverFactory.class);
        var driver = driver(Color.RED);
        when(factory.createDriver()).thenThrow(new BusinessException(ErrorCode.SYSTEM_ERROR, "下载超时"))
                .thenReturn(driver);
        var screenshots = new WebScreenshotUtils(factory, properties());
        assertThrows(BusinessException.class, () -> screenshots.saveWebPageScreenshot("http://local/first"));
        assertImage(screenshots.saveWebPageScreenshot("http://local/second"), Color.RED);
        verify(factory, times(2)).createDriver();
        verify(driver).quit();
    }

    /** 会话丢失后先关闭旧会话，再创建新浏览器；不能继续复用已经失效的对象。 */
    @Test
    void lostSessionRetriesWithAnotherBrowser() throws Exception {
        var factory = mock(ScreenshotDriverFactory.class);
        var broken = driver(Color.RED);
        var healthy = driver(Color.BLUE);
        doThrow(new NoSuchSessionException("session gone")).when(broken).get(anyString());
        when(factory.createDriver()).thenReturn(broken, healthy);
        var screenshots = new WebScreenshotUtils(factory, properties());
        assertImage(screenshots.saveWebPageScreenshot("http://local/retry"), Color.BLUE);
        var order = inOrder(factory, broken, healthy);
        order.verify(factory).createDriver();
        order.verify(broken).quit();
        order.verify(factory).createDriver();
        order.verify(healthy).quit();
    }

    /** 导航持续超时只允许两次尝试，两个会话都应退出，不能生成伪成功截图。 */
    @Test
    void navigationTimeoutHasOneRetryAndAlwaysQuits() throws Exception {
        var factory = mock(ScreenshotDriverFactory.class);
        var first = driver(Color.RED);
        var second = driver(Color.BLUE);
        doThrow(new TimeoutException("navigation timeout")).when(first).get(anyString());
        doThrow(new TimeoutException("navigation timeout")).when(second).get(anyString());
        when(factory.createDriver()).thenReturn(first, second);
        assertThrows(BusinessException.class, () ->
                new WebScreenshotUtils(factory, properties()).saveWebPageScreenshot("http://local/timeout"));
        verify(factory, times(2)).createDriver();
        verify(first).quit();
        verify(second).quit();
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    /** 图片或字体未加载完属于资源等待超时，应截图当前内容，不重复启动浏览器。 */
    @Test
    void slowResourcesStillProduceScreenshot() throws Exception {
        var factory = mock(ScreenshotDriverFactory.class);
        var browser = driver(Color.GREEN);
        when(((JavascriptExecutor) browser).executeScript(startsWith("return Array.from"))).thenReturn(false);
        when(factory.createDriver()).thenReturn(browser);
        var config = properties();
        config.setRenderTimeout(Duration.ofMillis(10));
        assertImage(new WebScreenshotUtils(factory, config).saveWebPageScreenshot("http://local/slow"), Color.GREEN);
        verify(factory).createDriver();
        verify(browser).quit();
    }

    /** 两个实际重叠的任务必须独占会话和文件，输出颜色对应各自页面，不能相互覆盖。 */
    @Test
    void concurrentPagesHaveIndependentSessionsAndFiles() throws Exception {
        var factory = mock(ScreenshotDriverFactory.class);
        var first = driver(Color.RED);
        var second = driver(Color.BLUE);
        var bothNavigating = new CountDownLatch(2);
        for (var browser : new WebDriver[]{first, second}) {
            doAnswer(call -> {
                bothNavigating.countDown();
                assertTrue(bothNavigating.await(3, TimeUnit.SECONDS));
                return null;
            }).when(browser).get(anyString());
        }
        when(factory.createDriver()).thenReturn(first, second);
        var config = properties();
        config.setMaxConcurrent(2);
        var screenshots = new WebScreenshotUtils(factory, config);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = workers.submit(() -> screenshots.saveWebPageScreenshot("http://local/a"));
            var b = workers.submit(() -> screenshots.saveWebPageScreenshot("http://local/b"));
            Path pathA = Path.of(a.get(5, TimeUnit.SECONDS));
            Path pathB = Path.of(b.get(5, TimeUnit.SECONDS));
            assertNotEquals(pathA, pathB);
            int rgbA = ImageIO.read(pathA.toFile()).getRGB(2, 2);
            int rgbB = ImageIO.read(pathB.toFile()).getRGB(2, 2);
            assertTrue(Math.abs(new Color(rgbA).getRed() - new Color(rgbB).getRed()) > 200);
        }
        verify(first).quit();
        verify(second).quit();
    }

    /** 名额耗尽必须有界失败；超时请求不能错误释放别的任务持有的名额。 */
    @Test
    void queueTimeoutDoesNotLeakOrOverReleasePermit() throws Exception {
        var factory = mock(ScreenshotDriverFactory.class);
        var browser = driver(Color.RED);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return null;
        }).when(browser).get(anyString());
        when(factory.createDriver()).thenReturn(browser);
        var config = properties();
        config.setQueueTimeout(Duration.ofMillis(30));
        var screenshots = new WebScreenshotUtils(factory, config);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> screenshots.saveWebPageScreenshot("http://local/held"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertThrows(BusinessException.class, () -> screenshots.saveWebPageScreenshot("http://local/queued"));
                assertThrows(BusinessException.class, () -> screenshots.saveWebPageScreenshot("http://local/queued-again"));
                verify(factory).createDriver();
            } finally {
                release.countDown();
            }
            assertImage(first.get(5, TimeUnit.SECONDS), Color.RED);
        }
        assertImage(screenshots.saveWebPageScreenshot("http://local/after"), Color.RED);
        verify(factory, times(2)).createDriver();
    }

    /** 被中断的任务保留中断标记且不启动浏览器。 */
    @Test
    void interruptedTaskStopsWithoutOpeningBrowser() throws Exception {
        var factory = mock(ScreenshotDriverFactory.class);
        var screenshots = new WebScreenshotUtils(factory, properties());
        var preserved = new AtomicBoolean();
        Thread worker = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            assertThrows(BusinessException.class, () -> screenshots.saveWebPageScreenshot("http://local/interrupted"));
            preserved.set(Thread.currentThread().isInterrupted());
        });
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertTrue(preserved.get());
        verifyNoInteractions(factory);
    }

    /** 非法图片不重试浏览器，不留磁盘垃圾；清理会话失败也不能覆盖已成功的截图结果。 */
    @Test
    void invalidImageQuitsAndNextRequestRecoversEvenWhenQuitFails() throws Exception {
        var factory = mock(ScreenshotDriverFactory.class);
        var broken = driver(Color.RED);
        when(((TakesScreenshot) broken).getScreenshotAs(OutputType.BYTES)).thenReturn(new byte[]{1, 2, 3});
        var healthy = driver(Color.BLUE);
        doThrow(new NoSuchSessionException("closed already")).when(healthy).quit();
        when(factory.createDriver()).thenReturn(broken, healthy);
        var screenshots = new WebScreenshotUtils(factory, properties());
        assertThrows(BusinessException.class, () -> screenshots.saveWebPageScreenshot("http://local/bad-image"));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
        assertImage(screenshots.saveWebPageScreenshot("http://local/healthy"), Color.BLUE);
        verify(broken).quit();
        verify(healthy).quit();
    }

    /** 空地址在获得名额或启动浏览器之前拒绝。 */
    @Test
    void blankUrlDoesNotStartBrowser() {
        var factory = mock(ScreenshotDriverFactory.class);
        var screenshots = new WebScreenshotUtils(factory, properties());
        assertThrows(BusinessException.class, () -> screenshots.saveWebPageScreenshot(" "));
        assertThrows(BusinessException.class, () -> screenshots.saveWebPageScreenshot(null));
        verifyNoInteractions(factory);
    }

    /** @return 使用测试专属目录且无需真实等待的配置 */
    private ScreenshotProperties properties() {
        var config = new ScreenshotProperties();
        config.setOutputDirectory(directory);
        config.setSettleDelay(Duration.ZERO);
        return config;
    }

    /** @param color 截图颜色；@return 同时支持脚本、超时及截图 API 的独立浏览器替身 */
    private WebDriver driver(Color color) throws Exception {
        var browser = mock(WebDriver.class, withSettings().extraInterfaces(JavascriptExecutor.class, TakesScreenshot.class));
        var options = mock(WebDriver.Options.class);
        var timeouts = mock(WebDriver.Timeouts.class);
        when(browser.manage()).thenReturn(options);
        when(options.timeouts()).thenReturn(timeouts);
        when(((JavascriptExecutor) browser).executeScript(anyString())).thenReturn(true);
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) image.setRGB(x, y, color.getRGB());
        }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        when(((TakesScreenshot) browser).getScreenshotAs(OutputType.BYTES)).thenReturn(bytes.toByteArray());
        return browser;
    }

    /** @param path JPEG 路径；@param expected 预期纯色，容许有损压缩的少量误差 */
    private void assertImage(String path, Color expected) throws Exception {
        var image = ImageIO.read(Path.of(path).toFile());
        assertNotNull(image);
        var actual = new Color(image.getRGB(2, 2));
        assertTrue(Math.abs(actual.getRed() - expected.getRed()) < 10);
        assertTrue(Math.abs(actual.getBlue() - expected.getBlue()) < 10);
        assertTrue(Math.abs(actual.getGreen() - expected.getGreen()) < 10);
        assertTrue(path.endsWith(".jpg"));
    }
}
