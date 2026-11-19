package com.zzx.zzxaicode.quality.model;

import java.io.Serializable;
import java.util.List;

/**
 * 一轮自动修复的审计记录；不保存模型连接、线程或工具实例等执行资源。
 *
 * @param attempt 修复次数，从 1 开始
 * @param checkRound 修复前对应的检查轮次，从 0 开始
 * @param changed 是否产生有效源码变更
 * @param changedFiles 本轮修改的相对文件路径
 * @param beforeDiagnosticFingerprint 修复前诊断指纹，可空
 * @param afterDiagnosticFingerprint 修复后诊断指纹，可空
 * @param summary 修复结果摘要
 */
public record RepairRecord(
        int attempt,
        int checkRound,
        boolean changed,
        List<String> changedFiles,
        String beforeDiagnosticFingerprint,
        String afterDiagnosticFingerprint,
        String summary) implements Serializable {

    private static final long serialVersionUID = 1L;

    public RepairRecord {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 必须从 1 开始");
        }
        if (checkRound < 0) {
            throw new IllegalArgumentException("checkRound 不能小于 0");
        }
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        changedFiles.forEach(file -> validateRelativePath(file, "changedFiles"));
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary 不能为空");
        }
    }

    private static void validateRelativePath(String value, String field) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.startsWith("\\")
                || value.matches("^[A-Za-z]:[\\\\/].*")
                || value.equals("..") || value.startsWith("../") || value.startsWith("..\\")
                || value.contains("/../") || value.contains("\\..\\")) {
            throw new IllegalArgumentException(field + " 必须是工作区内的相对路径");
        }
    }
}
