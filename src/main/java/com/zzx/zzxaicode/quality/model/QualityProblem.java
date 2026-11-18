package com.zzx.zzxaicode.quality.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * 可审计的质量问题诊断。
 *
 * @param category 问题分类；环境异常不能直接交给源码修复节点
 * @param repairable 是否允许进入自动修复
 * @param file 相对工作区文件路径，可空
 * @param line 行号，无法定位时为 null
 * @param column 列号，无法定位时为 null
 * @param summary 面向用户的诊断摘要
 * @param logRef 日志或证据引用，可空；不得写入无关绝对路径
 */
public record QualityProblem(
        QualityProblemCategory category,
        boolean repairable,
        String file,
        Integer line,
        Integer column,
        String summary,
        String logRef) implements Serializable {

    /** Java 序列化版本，报告可能被 Graph 检查点或业务存储序列化。 */
    private static final long serialVersionUID = 1L;

    public QualityProblem {
        category = Objects.requireNonNull(category, "category 不能为空");
        summary = requireText(summary, "summary");
        validateRelativePath(file, "file");
        if (line != null && line < 1) {
            throw new IllegalArgumentException("line 必须大于 0");
        }
        if (column != null && column < 1) {
            throw new IllegalArgumentException("column 必须大于 0");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static void validateRelativePath(String value, String field) {
        if (value == null) {
            return;
        }
        if (value.isBlank() || value.startsWith("/") || value.startsWith("\\")
                || value.matches("^[A-Za-z]:[\\\\/].*")
                || value.equals("..") || value.startsWith("../") || value.startsWith("..\\")
                || value.contains("/../") || value.contains("\\..\\")) {
            throw new IllegalArgumentException(field + " 必须是工作区内的相对路径");
        }
    }
}
