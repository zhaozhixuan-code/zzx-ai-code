package com.zzx.zzxaicode.quality.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Graph 与业务报告共用的任务数据契约。
 *
 * <p>该类型只包含可序列化业务数据；Service、进程、浏览器、SSE 连接和模型客户端不属于状态。</p>
 *
 * @param taskId 服务端任务标识
 * @param appId 应用标识
 * @param userId 用户标识
 * @param originalRequirement 原始需求
 * @param generationType 生成类型文本，编排层负责约束为 vue_project
 * @param workspaceId 服务端工作区引用
 * @param artifactId 已确认产物引用，可空
 * @param stage 当前阶段
 * @param finalStatus 业务终态，运行中为 RUNNING
 * @param checkRound 最新检查轮次，首次检查为 0
 * @param repairAttempts 已启动的修复次数
 * @param maxRepairAttempts 本任务允许启动的修复次数
 * @param deadline 整体截止时间
 * @param qualityReport 最新检查报告，可空
 * @param repairRecords 历史修复记录，按执行顺序追加
 */
public record QualityTaskState(
        String taskId,
        long appId,
        long userId,
        String originalRequirement,
        String generationType,
        String workspaceId,
        String artifactId,
        QualityTaskStage stage,
        QualityTaskStatus finalStatus,
        int checkRound,
        int repairAttempts,
        int maxRepairAttempts,
        Instant deadline,
        QualityReport qualityReport,
        List<RepairRecord> repairRecords) implements Serializable {

    private static final long serialVersionUID = 1L;

    public QualityTaskState {
        requireText(taskId, "taskId");
        if (appId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("appId 和 userId 必须大于 0");
        }
        requireText(originalRequirement, "originalRequirement");
        requireText(generationType, "generationType");
        requireText(workspaceId, "workspaceId");
        if (stage == null || finalStatus == null || deadline == null) {
            throw new IllegalArgumentException("stage、finalStatus 和 deadline 不能为空");
        }
        if (checkRound < 0 || repairAttempts < 0 || maxRepairAttempts < 0) {
            throw new IllegalArgumentException("轮次和修复次数不能小于 0");
        }
        if (repairAttempts > maxRepairAttempts) {
            throw new IllegalArgumentException("repairAttempts 不能超过 maxRepairAttempts");
        }
        repairRecords = repairRecords == null ? List.of() : List.copyOf(repairRecords);
    }

    /**
     * 追加一条修复记录；相同记录重复回调时保持原列表，避免历史被重复追加。
     *
     * @param record 当前修复轮次记录，不可为 null
     * @return 包含新增记录的新状态；若记录已存在则返回内容不变的新状态
     */
    public QualityTaskState appendRepairRecord(RepairRecord record) {
        Objects.requireNonNull(record, "record 不能为空");
        if (repairRecords.contains(record)) {
            return this;
        }
        List<RepairRecord> appended = new ArrayList<>(repairRecords);
        appended.add(record);
        return new QualityTaskState(
                taskId,
                appId,
                userId,
                originalRequirement,
                generationType,
                workspaceId,
                artifactId,
                stage,
                finalStatus,
                checkRound,
                repairAttempts,
                maxRepairAttempts,
                deadline,
                qualityReport,
                appended);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
