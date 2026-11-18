package com.zzx.zzxaicode.quality.model;

/** 诊断问题分类，用于区分源码错误和检查环境异常。 */
public enum QualityProblemCategory {
    SYNTAX,
    IMPORT,
    MISSING_FILE,
    BUILD,
    RUNTIME,
    ENVIRONMENT,
    UNKNOWN
}
