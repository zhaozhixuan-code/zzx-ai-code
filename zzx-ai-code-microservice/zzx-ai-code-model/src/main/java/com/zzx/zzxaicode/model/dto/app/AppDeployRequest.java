package com.zzx.zzxaicode.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用部署请求
 */
@Data
public class AppDeployRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用 ID
     */
    private Long appId;

}
