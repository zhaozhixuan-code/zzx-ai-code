package com.zzx.zzxaicode.quality.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * 一次检查轮次的结构化报告。
 *
 * @param taskId 服务端任务标识
 * @param appId 应用标识
 * @param checkRound 检查轮次，首次检查固定为 0
 * @param startedAt 检查开始时间
 * @param finishedAt 检查结束时间，可空表示尚未结束
 * @param items 检查项结果
 * @param artifactId 本轮候选产物引用，可空
 */
public record QualityReport(
        String taskId,
        long appId,
        int checkRound,
        Instant startedAt,
        Instant finishedAt,
        List<QualityCheckItem> items,
        String artifactId) implements Serializable {

    private static final long serialVersionUID = 1L;

    public QualityReport {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (appId <= 0) {
            throw new IllegalArgumentException("appId 必须大于 0");
        }
        if (checkRound < 0) {
            throw new IllegalArgumentException("checkRound 不能小于 0");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt 不能为空");
        }
        if (finishedAt != null && finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt 不能早于 startedAt");
        }
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 检查必须结束且包含必选项，所有必选项明确 PASSED 才能判定本轮通过。 */
    public boolean isPassed() {
        return finishedAt != null && items.stream().anyMatch(QualityCheckItem::required)
                && items.stream().allMatch(QualityCheckItem::isPassingRequirement);
    }
}
