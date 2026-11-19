package com.zzx.zzxaicode.quality.model;

/** 质量任务当前执行阶段；阶段值用于进度展示，不代表任务已经成功。 */
public enum QualityTaskStage {
    CREATED,
    GENERATING,
    CHECKING,
    REPAIRING,
    CONFIRMING_ARTIFACT,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}
