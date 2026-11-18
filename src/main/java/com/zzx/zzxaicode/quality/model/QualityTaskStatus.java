package com.zzx.zzxaicode.quality.model;

/** 质量任务的业务终态；RUNNING 表示尚未产生终态。 */
public enum QualityTaskStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}
