package com.zzx.zzxaicode.utils;

import com.zzx.zzxaicode.config.ScreenshotProperties;
import com.zzx.zzxaicode.exception.BusinessException;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.github.bonigarcia.wdm.config.WebDriverManagerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 本地配置优先级与离线分支验证；禁止测试触发真实驱动下载。 */
class ScreenshotDriverFactoryTest {
    @TempDir
    Path directory;

    /** 显式驱动应在关闭下载时直接可用，即使 JVM 属性错误也不应访问 WebDriverManager。 */
    @Test
    void configuredDriverBypassesNetworkAndOverridesSystemProperty() throws Exception {
        Path executable = Files.writeString(directory.resolve("chromedriver.exe"), "test fixture");
        assertTrue(executable.toFile().setExecutable(true));
        var config = new ScreenshotProperties();
        config.setDriverPath(executable.toString());
        config.setAutoDownload(false);
        String previous = System.getProperty("webdriver.chrome.driver");
        try (var managers = mockStatic(WebDriverManager.class)) {
            System.setProperty("webdriver.chrome.driver", directory.resolve("missing.exe").toString());
            assertEquals(executable.toAbsolutePath().toFile(), new ScreenshotDriverFactory(config).resolveDriverExecutable());
            managers.verifyNoInteractions();
        } finally {
            if (previous == null) System.clearProperty("webdriver.chrome.driver");
            else System.setProperty("webdriver.chrome.driver", previous);
        }
    }

    /** 显式缺失路径必须明确报错；不能静默下载并掩盖服务器部署配置错误。 */
    @Test
    void missingExplicitDriverFailsWithoutDownload() {
        var config = new ScreenshotProperties();
        config.setDriverPath(directory.resolve("missing.exe").toString());
        try (var managers = mockStatic(WebDriverManager.class)) {
            var error = assertThrows(BusinessException.class, () ->
                    new ScreenshotDriverFactory(config).resolveDriverExecutable());
            assertTrue(error.getMessage().contains("不存在或没有执行权限"));
            managers.verifyNoInteractions();
        }
    }

    /** 浏览器路径错误应在解析驱动前拒绝，避免尝试下载与错误配置无关的新驱动。 */
    @Test
    void missingBrowserFailsBeforeDriverResolution() {
        var config = new ScreenshotProperties();
        config.setBrowserBinary(directory.resolve("missing-browser").toString());
        try (var managers = mockStatic(WebDriverManager.class)) {
            assertThrows(BusinessException.class, () -> new ScreenshotDriverFactory(config).createDriver());
            managers.verifyNoInteractions();
        }
    }

    /** 重复截图兼容原有 webdriver.chrome.driver JVM 参数，且不自动下载。 */
    @Test
    void existingSystemPropertyRemainsSupported() throws Exception {
        Path executable = Files.writeString(directory.resolve("existing-driver.exe"), "test fixture");
        assertTrue(executable.toFile().setExecutable(true));
        String previous = System.getProperty("webdriver.chrome.driver");
        try (var managers = mockStatic(WebDriverManager.class)) {
            System.setProperty("webdriver.chrome.driver", executable.toString());
            assertEquals(executable.toAbsolutePath().toFile(),
                    new ScreenshotDriverFactory(new ScreenshotProperties()).resolveDriverExecutable());
            managers.verifyNoInteractions();
        } finally {
            if (previous == null) System.clearProperty("webdriver.chrome.driver");
            else System.setProperty("webdriver.chrome.driver", previous);
        }
    }

    /** 下载超时不写入不可恢复的静态状态；每次解析都带超时并关闭递归回退，下一次仍可成功。 */
    @Test
    void downloadTimeoutIsBoundedAndNextResolutionCanRecover() throws Exception {
        Path executable = Files.writeString(directory.resolve("downloaded-driver.exe"), "test fixture");
        assertTrue(executable.toFile().setExecutable(true));
        var config = new ScreenshotProperties();
        var factory = spy(new ScreenshotDriverFactory(config));
        doReturn(null).when(factory).findDriverOnPath();
        var manager = mock(WebDriverManager.class, RETURNS_SELF);
        when(manager.getDownloadedDriverPath()).thenReturn(executable.toString());
        doThrow(new WebDriverManagerException("Read timed out")).doNothing().when(manager).setup();
        String previous = System.getProperty("webdriver.chrome.driver");
        try (var managers = mockStatic(WebDriverManager.class)) {
            System.clearProperty("webdriver.chrome.driver");
            managers.when(WebDriverManager::chromedriver).thenReturn(manager);
            assertThrows(BusinessException.class, factory::resolveDriverExecutable);
            assertEquals(executable.toAbsolutePath().toFile(), factory.resolveDriverExecutable());
            verify(manager, times(2)).timeout(15);
            verify(manager, times(2)).avoidFallback();
            verify(manager, times(2)).avoidExport();
            verify(manager, times(2)).setup();
        } finally {
            if (previous == null) System.clearProperty("webdriver.chrome.driver");
            else System.setProperty("webdriver.chrome.driver", previous);
        }
    }

    /** 离线模式没有本地驱动时直接报错，不能调用 WebDriverManager 或 Selenium Manager 下载。 */
    @Test
    void offlineModeWithoutLocalDriverFailsImmediately() {
        var config = new ScreenshotProperties();
        config.setAutoDownload(false);
        var factory = spy(new ScreenshotDriverFactory(config));
        doReturn(null).when(factory).findDriverOnPath();
        String previous = System.getProperty("webdriver.chrome.driver");
        try (var managers = mockStatic(WebDriverManager.class)) {
            System.clearProperty("webdriver.chrome.driver");
            var failure = assertThrows(BusinessException.class, factory::resolveDriverExecutable);
            assertTrue(failure.getMessage().contains("已关闭自动下载"));
            managers.verifyNoInteractions();
        } finally {
            if (previous == null) System.clearProperty("webdriver.chrome.driver");
            else System.setProperty("webdriver.chrome.driver", previous);
        }
    }

    /** 无效配置启动时即被拒绝，避免零并发或无期限等待；零前端等待是有效边界。 */
    @Test
    void invalidConcurrencyAndTimeoutsAreRejected() {
        var config = new ScreenshotProperties();
        config.setMaxConcurrent(0);
        assertThrows(IllegalArgumentException.class, config::validate);
        config.setMaxConcurrent(1);
        config.setDriverTimeout(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, config::validate);
        config.setDriverTimeout(Duration.ofSeconds(15));
        config.setQueueTimeout(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, config::validate);
        config.setQueueTimeout(Duration.ofSeconds(1));
        config.setSettleDelay(Duration.ZERO);
        assertDoesNotThrow(config::validate);
    }
}
