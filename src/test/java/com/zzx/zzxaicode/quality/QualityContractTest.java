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

    /** 未结束的检查和只有可选项的报告不能误判成功。 */
    @Test
    void shouldRejectIncompleteOrOptionalOnlyReport() {
        QualityReport passed = report(QualityCheckItemStatus.PASSED);
        assertFalse(new QualityReport(passed.taskId(), passed.appId(), 0,
                passed.startedAt(), null, passed.items(), null).isPassed());
        QualityCheckItem optional = new QualityCheckItem("optional", false,
                QualityCheckItemStatus.SKIPPED, 0, List.of());
        assertFalse(new QualityReport(passed.taskId(), passed.appId(), 0,
                passed.startedAt(), passed.finishedAt(), List.of(optional), null).isPassed());
    }

    /** 真实 Spring 属性绑定和初始化回调应校验预算，无需外部数据库或模型。 */
    @Test
    void shouldBindPropertiesAndValidateAtStartup() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(QualityConfig.class)
                .withPropertyValues("app.quality.max-repair-attempts=0", "app.quality.build-timeout=20s")
                .run(context -> {
                    org.junit.jupiter.api.Assertions.assertNull(context.getStartupFailure());
                    var settings = context.getBean(QualityCheckProperties.class);
                    org.junit.jupiter.api.Assertions.assertEquals(0, settings.getMaxRepairAttempts());
                    org.junit.jupiter.api.Assertions.assertEquals(Duration.ofSeconds(20), settings.getBuildTimeout());
                });
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(QualityConfig.class)
                .withPropertyValues("app.quality.max-repair-attempts=-1")
                .run(context -> org.junit.jupiter.api.Assertions.assertNotNull(context.getStartupFailure()));
    }

    /** 仅注册质量配置，防止测试启动主应用和外部连接。 */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(QualityCheckProperties.class)
    static class QualityConfig { }

    /** Linux 目录身份可配置，但容器不能以 root 用户或 root 组执行工程。 */
    @Test
    void shouldAllowOnlyNonRootBuildIdentity() {
        QualityCheckProperties settings = new QualityCheckProperties();
        settings.setBuildUser("1001:1002");
        assertDoesNotThrow(settings::validate);
        for (String identity : List.of("0:1000", "1000:0", "root", "-1:1000", "1000:1000 --privileged")) {
            settings.setBuildUser(identity);
            assertThrows(IllegalArgumentException.class, settings::validate);
        }
    }

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
