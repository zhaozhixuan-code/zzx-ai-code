package com.zzx.zzxaicode.quality.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.Duration;

/**
 * 质量检查闭环的预算配置。
 *
 * <p>修复次数、模型工具轮数和各阶段超时分别计量，避免把模型内部循环误当成业务修复次数。
 * 配置在应用启动时校验，失败应尽早暴露而不是运行到质量任务时才失败。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.quality")
public class QualityCheckProperties {

    /** 是否启用 Vue 工程质量闭环；HTML 和多文件模式不由该配置切换。 */
    private boolean enabled = true;
    /** 单个任务最多启动的源码修复次数，0 表示只检查不修复。 */
    private int maxRepairAttempts = 2;
    /** 单轮模型调用允许的工具循环次数。 */
    private int maxModelToolRounds = 50;
    /** 单个质量任务的整体截止时间。 */
    private Duration overallTimeout = Duration.ofMinutes(30);
    /** 生成或修复模型阶段的单次超时。 */
    private Duration modelTimeout = Duration.ofMinutes(3);
    /** 构建检查阶段的单次超时。 */
    private Duration buildTimeout = Duration.ofMinutes(10);
    /** 浏览器运行检查阶段的单次超时。 */
    private Duration browserTimeout = Duration.ofMinutes(2);

    /** 校验质量任务预算，阻止负数、零超时及整体期限不足以覆盖任一阶段。 */
    @PostConstruct
    public void validate() {
        Assert.isTrue(maxRepairAttempts >= 0,
                "app.quality.max-repair-attempts 不能小于 0");
        Assert.isTrue(maxModelToolRounds > 0,
                "app.quality.max-model-tool-rounds 必须大于 0");
        requirePositive(overallTimeout, "app.quality.overall-timeout");
        requirePositive(modelTimeout, "app.quality.model-timeout");
        requirePositive(buildTimeout, "app.quality.build-timeout");
        requirePositive(browserTimeout, "app.quality.browser-timeout");
        Assert.isTrue(overallTimeout.compareTo(modelTimeout) >= 0,
                "app.quality.overall-timeout 不能小于 model-timeout");
        Assert.isTrue(overallTimeout.compareTo(buildTimeout) >= 0,
                "app.quality.overall-timeout 不能小于 build-timeout");
        Assert.isTrue(overallTimeout.compareTo(browserTimeout) >= 0,
                "app.quality.overall-timeout 不能小于 browser-timeout");
    }

    private void requirePositive(Duration value, String name) {
        Assert.notNull(value, name + " 不能为空");
        Assert.isTrue(!value.isNegative() && !value.isZero(), name + " 必须大于 0");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRepairAttempts() {
        return maxRepairAttempts;
    }

    public void setMaxRepairAttempts(int maxRepairAttempts) {
        this.maxRepairAttempts = maxRepairAttempts;
    }

    public int getMaxModelToolRounds() {
        return maxModelToolRounds;
    }

    public void setMaxModelToolRounds(int maxModelToolRounds) {
        this.maxModelToolRounds = maxModelToolRounds;
    }

    public Duration getOverallTimeout() {
        return overallTimeout;
    }

    public void setOverallTimeout(Duration overallTimeout) {
        this.overallTimeout = overallTimeout;
    }

    public Duration getModelTimeout() {
        return modelTimeout;
    }

    public void setModelTimeout(Duration modelTimeout) {
        this.modelTimeout = modelTimeout;
    }

    public Duration getBuildTimeout() {
        return buildTimeout;
    }

    public void setBuildTimeout(Duration buildTimeout) {
        this.buildTimeout = buildTimeout;
    }

    public Duration getBrowserTimeout() {
        return browserTimeout;
    }

    public void setBrowserTimeout(Duration browserTimeout) {
        this.browserTimeout = browserTimeout;
    }
}
