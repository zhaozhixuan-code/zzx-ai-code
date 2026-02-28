package com.zzx.zzxaicode.utils;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Slf4j
class WebScreenshotUtilsTest {

    @Test
    void saveWebPageScreenshot() {

        String testUrl = "https://www.bilibili.com";
        String webPageScreenshot = WebScreenshotUtils.saveWebPageScreenshot(testUrl);
        log.info("截图保存成功，保存路径为：{}", webPageScreenshot);
        assertNotNull(webPageScreenshot);
    }
}