package com.zzx.zzxaicode.service.impl;

import com.qcloud.cos.exception.CosClientException;
import com.zzx.zzxaicode.manager.CosManager;
import com.zzx.zzxaicode.utils.WebScreenshotUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证上传成功及失败时的文件所有权，不连接真实 COS。 */
class ScreenshotServiceImplTest {
    @TempDir
    Path directory;

    /** 上传成功后只删除本次图片，共享目录中其他任务的文件必须保留。 */
    @Test
    void successfulUploadDeletesOnlyOwnedImage() throws Exception {
        Path owned = Files.writeString(directory.resolve("owned.jpg"), "fixture");
        Path other = Files.writeString(directory.resolve("other.jpg"), "another task");
        var storage = mock(CosManager.class);
        when(storage.uploadFile(anyString(), eq(owned.toFile()))).thenReturn("https://cos.example/cover.jpg");
        assertEquals("https://cos.example/cover.jpg", service(owned, storage)
                .generateAndUploadScreenshot("http://local/app"));
        assertFalse(Files.exists(owned));
        assertEquals("another task", Files.readString(other));
        verify(storage).uploadFile(startsWith("screenshot/"), any(File.class));
    }

    /** COS 抛出异常也必须清理本次图片，原始异常向上传播，不误删旁边的文件。 */
    @Test
    void failedUploadStillDeletesOnlyOwnedImage() throws Exception {
        Path owned = Files.writeString(directory.resolve("owned.jpg"), "fixture");
        Path other = Files.writeString(directory.resolve("other.jpg"), "another task");
        var storage = mock(CosManager.class);
        var failure = new CosClientException("upload failed");
        when(storage.uploadFile(anyString(), any(File.class))).thenThrow(failure);
        assertSame(failure, assertThrows(CosClientException.class, () ->
                service(owned, storage).generateAndUploadScreenshot("http://local/app")));
        assertFalse(Files.exists(owned));
        assertTrue(Files.exists(other));
    }

    /** @param image 本次截图路径；@param storage COS 替身；@return 注入隔离依赖的截图服务 */
    private ScreenshotServiceImpl service(Path image, CosManager storage) {
        var renderer = mock(WebScreenshotUtils.class);
        when(renderer.saveWebPageScreenshot(anyString())).thenReturn(image.toString());
        var service = new ScreenshotServiceImpl();
        ReflectionTestUtils.setField(service, "webScreenshotUtils", renderer);
        ReflectionTestUtils.setField(service, "cosManager", storage);
        return service;
    }
}
