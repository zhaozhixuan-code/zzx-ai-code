package com.zzx.zzxaicode.quality;

import com.zzx.zzxaicode.quality.config.QualityCheckProperties;
import com.zzx.zzxaicode.quality.model.QualityCheckItem;
import com.zzx.zzxaicode.quality.model.QualityCheckItemStatus;
import com.zzx.zzxaicode.quality.model.QualityReport;
import com.zzx.zzxaicode.quality.model.QualityProblem;
import com.zzx.zzxaicode.quality.model.QualityProblemCategory;
import com.zzx.zzxaicode.quality.model.QualityTaskStage;
import com.zzx.zzxaicode.quality.model.QualityTaskState;
import com.zzx.zzxaicode.quality.model.QualityTaskStatus;
import com.zzx.zzxaicode.quality.model.RepairRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** S02 状态、报告和配置契约的边界验证。 */
class QualityContractTest {

    /** 必选项为 SKIPPED 或 ERROR 时，报告不能判定通过。 */
    @Test
    void shouldRequireExplicitPassedStatusForRequiredItems() {
        QualityReport skipped = report(QualityCheckItemStatus.SKIPPED);
        QualityReport error = report(QualityCheckItemStatus.ERROR);

        assertFalse(skipped.isPassed());
        assertFalse(error.isPassed());
    }

    /** 报告及其诊断项只包含可序列化业务数据，不携带执行资源。 */
    @Test
    void shouldSerializeReport() {
        QualityReport report = report(QualityCheckItemStatus.PASSED);

        assertDoesNotThrow(() -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(report);
            }
        });
    }

    /** 零次修复是合法配置，负数修复预算和非正超时必须被拒绝。 */
    @Test
    void shouldValidateRepairAndTimeoutBudget() {
        QualityCheckProperties valid = new QualityCheckProperties();
        valid.setMaxRepairAttempts(0);
        assertDoesNotThrow(valid::validate);

        QualityCheckProperties negativeRepair = new QualityCheckProperties();
        negativeRepair.setMaxRepairAttempts(-1);
        assertThrows(IllegalArgumentException.class, negativeRepair::validate);

        QualityCheckProperties invalidTimeout = new QualityCheckProperties();
        invalidTimeout.setOverallTimeout(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, invalidTimeout::validate);
    }

    /** 重复终态回调不会重复增加同一轮修复历史。 */
    @Test
    void shouldAvoidDuplicateRepairHistory() {
        QualityTaskState state = new QualityTaskState(
                "task-a",
                1,
                2,
                "创建一个页面",
                "vue_project",
                "workspace-a",
                null,
                QualityTaskStage.CHECKING,
                QualityTaskStatus.RUNNING,
                0,
                1,
                2,
                Instant.parse("2026-11-18T00:10:00Z"),
                null,
                List.of());
        RepairRecord record = new RepairRecord(
                1, 0, true, List.of("src/main.ts"), "before", "after", "已修改");

        QualityTaskState once = state.appendRepairRecord(record);
        QualityTaskState twice = once.appendRepairRecord(record);

        org.junit.jupiter.api.Assertions.assertEquals(1, once.repairRecords().size());
        org.junit.jupiter.api.Assertions.assertEquals(once.repairRecords(), twice.repairRecords());
    }

    /** 诊断和修复记录不能携带工作区外的绝对路径或路径穿越片段。 */
    @Test
    void shouldRejectNonRelativeEvidencePaths() {
        assertThrows(IllegalArgumentException.class, () -> new QualityProblem(
                QualityProblemCategory.BUILD,
                true,
                "../outside.js",
                null,
                null,
                "路径越界",
                null));
        assertThrows(IllegalArgumentException.class, () -> new RepairRecord(
                1,
                0,
                true,
                List.of("C:\\outside.js"),
                null,
                null,
                "路径越界"));
    }

    private QualityReport report(QualityCheckItemStatus status) {
        QualityProblem problem = new QualityProblem(
                QualityProblemCategory.BUILD,
                status != QualityCheckItemStatus.PASSED,
                "src/main.ts",
                1,
                1,
                "示例诊断",
                "build-log-0");
        QualityCheckItem item = new QualityCheckItem(
                "build",
                true,
                status,
                10,
                status == QualityCheckItemStatus.PASSED ? List.of() : List.of(problem));
        return new QualityReport(
                "task-a",
                1,
                0,
                Instant.parse("2026-11-18T00:00:00Z"),
                Instant.parse("2026-11-18T00:00:01Z"),
                List.of(item),
                "artifact-0");
    }
}
