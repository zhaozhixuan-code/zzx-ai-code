package com.zzx.zzxaicode.quality.model;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 一轮检查中的单项结果。
 *
 * @param name 服务端定义的检查项名称
 * @param required 是否为成功判定的必选项
 * @param status 检查项状态，只有 PASSED 才表示该项通过
 * @param durationMillis 检查耗时，单位为毫秒
 * @param problems 本项产生的诊断问题
 */
public record QualityCheckItem(
        String name,
        boolean required,
        QualityCheckItemStatus status,
        long durationMillis,
        List<QualityProblem> problems) implements Serializable {

    private static final long serialVersionUID = 1L;

    public QualityCheckItem {
        name = requireText(name, "name");
        status = Objects.requireNonNull(status, "status 不能为空");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis 不能小于 0");
        }
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** 必选项只有明确 PASSED 才能参与任务成功判定。 */
    public boolean isPassingRequirement() {
        return !required || status == QualityCheckItemStatus.PASSED;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
