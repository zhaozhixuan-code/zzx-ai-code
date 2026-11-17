package com.zzx.zzxaicode.utils;

import com.zzx.zzxaicode.config.ScreenshotProperties;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

/** 仅启动截图相关 Spring Bean，验证配置绑定与依赖注入，不启动数据库、Redis 或浏览器。 */
class ScreenshotConfigurationTest {
    /** Spring 正确绑定离线配置，组件创建期间即使浏览器路径不存在也不进行驱动初始化。 */
    @Test
    void screenshotBeansBindConfigurationWithoutStartingBrowser() {
        try (var managers = mockStatic(WebDriverManager.class)) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                    .withUserConfiguration(ScreenshotProperties.class, ScreenshotDriverFactory.class,
                            WebScreenshotUtils.class)
                    .withPropertyValues("screenshot.auto-download=false", "screenshot.driver-timeout=8s",
                            "screenshot.browser-binary=/not-installed/chromium", "screenshot.max-concurrent=2")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var config = context.getBean(ScreenshotProperties.class);
                        assertFalse(config.isAutoDownload());
                        assertEquals(Duration.ofSeconds(8), config.getDriverTimeout());
                        assertEquals(2, config.getMaxConcurrent());
                    });
            managers.verifyNoInteractions();
        }
    }
}
