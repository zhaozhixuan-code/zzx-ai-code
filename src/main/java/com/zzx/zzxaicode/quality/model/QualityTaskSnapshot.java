package com.zzx.zzxaicode.quality.model;

import java.time.Instant;
import java.util.List;

/**
 * 报告查询及 SSE 的业务快照；不包含工作区绝对路径、连接或 Graph 对象。
 * @param taskId 任务 UUID @param appId 应用 @param userId 归属用户 @param status 运行/终态
 * @param stage 当前阶段 @param repairAttempts 已启动修复数 @param maxRepairAttempts 修复上限
 * @param startedAt 开始时间 @param finishedAt 终态时间，运行中为空 @param message 用户可见原因
 * @param artifactId 通过检查的候选引用 @param reports 全部检查轮次 @param repairs 全部修复记录
 */
public record QualityTaskSnapshot(String taskId,long appId,long userId,QualityTaskStatus status,
        QualityTaskStage stage,int repairAttempts,int maxRepairAttempts,Instant startedAt,Instant finishedAt,
        String message,String artifactId,List<QualityReport> reports,List<RepairRecord> repairs) {
    public QualityTaskSnapshot { reports=List.copyOf(reports); repairs=List.copyOf(repairs); }
}
