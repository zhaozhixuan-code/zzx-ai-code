package com.zzx.zzxaicode.quality.model;

/** 单个质量检查项的结果；ERROR、TIMEOUT 和 SKIPPED 都不能视为通过。 */
public enum QualityCheckItemStatus {
    PASSED,
    FAILED,
    ERROR,
    TIMEOUT,
    SKIPPED
}
