package com.zzx.zzxaicode.manager;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.zzx.zzxaicode.config.CosClientConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * COS 对象存储管理器
 */
@Component
@Slf4j
public class CosManager {

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private COSClient cosClient;

    /**
     * 上传对象
     *
     * @param key  唯一键
     * @param file 文件
     * @return 上传结果
     */
    public PutObjectResult putObjectResult(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key, file);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 文件上传到 COS 并返回访问 URL
     *
     * @param key  COS 对象键（完整路径）
     * @param file file 要上传的文件
     * @return 文件访问的 URL，失败返回 null
     */
    public String uploadFile(String key, File file) {
        PutObjectResult result = putObjectResult(key, file);
        if (result != null) {
            // 构建访问的url
            String url = String.format("%s/%s", cosClientConfig.getHost(), key);
            log.info("上传成功，访问的url为：{}", url);
            return url;
        } else {
            log.error("文件上传COS失败");
            return null;
        }
    }

}
