package com.zzx.zzxaicode.service;

/**
 * 截图服务
 */
public interface ScreenshotService {


    /**
     * 通用的截图服务，可以得到访问地址
     *
     * @param webUrl 截图网页的url
     * @return
     */
    String generateAndUploadScreenshot(String webUrl);
}
