package com.zzx.zzxaicode.quality.graph;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;

import java.util.HashMap;
import java.util.Map;

/**
 * S02 Graph 状态键与合并策略契约。
 *
 * <p>标量状态采用覆盖策略；修复记录采用追加策略。每次调用都返回新的策略 Map，避免不同任务共享可变状态配置。</p>
 */
public final class QualityGraphStateContract {

    public static final String TASK_ID = "taskId";
    public static final String APP_ID = "appId";
    public static final String USER_ID = "userId";
    public static final String ORIGINAL_REQUIREMENT = "originalRequirement";
    public static final String GENERATION_TYPE = "generationType";
    public static final String WORKSPACE_ID = "workspaceId";
    public static final String ARTIFACT_ID = "artifactId";
    public static final String STAGE = "stage";
    public static final String FINAL_STATUS = "finalStatus";
    public static final String CHECK_PASSED = "checkPassed";
    public static final String CHECK_ROUND = "checkRound";
    public static final String CHECK_RUNS = "checkRuns";
    public static final String REPAIR_ATTEMPTS = "repairAttempts";
    public static final String MAX_REPAIR_ATTEMPTS = "maxRepairAttempts";
    public static final String DEADLINE = "deadline";
    public static final String QUALITY_REPORT = "qualityReport";
    public static final String REPAIR_RECORDS = "repairRecords";

    private QualityGraphStateContract() {
    }

    /**
     * 创建 Graph 状态键策略。
     *
     * @return 本任务使用的键策略工厂
     */
    public static KeyStrategyFactory keyStrategyFactory() {
        Map<String, KeyStrategy> replaceKeys = new HashMap<>();
        replaceKeys.put(TASK_ID, new ReplaceStrategy());
        replaceKeys.put(APP_ID, new ReplaceStrategy());
        replaceKeys.put(USER_ID, new ReplaceStrategy());
        replaceKeys.put(ORIGINAL_REQUIREMENT, new ReplaceStrategy());
        replaceKeys.put(GENERATION_TYPE, new ReplaceStrategy());
        replaceKeys.put(WORKSPACE_ID, new ReplaceStrategy());
        replaceKeys.put(ARTIFACT_ID, new ReplaceStrategy());
        replaceKeys.put(STAGE, new ReplaceStrategy());
        replaceKeys.put(FINAL_STATUS, new ReplaceStrategy());
        replaceKeys.put(CHECK_PASSED, new ReplaceStrategy());
        replaceKeys.put(CHECK_ROUND, new ReplaceStrategy());
        replaceKeys.put(CHECK_RUNS, new ReplaceStrategy());
        replaceKeys.put(REPAIR_ATTEMPTS, new ReplaceStrategy());
        replaceKeys.put(MAX_REPAIR_ATTEMPTS, new ReplaceStrategy());
        replaceKeys.put(DEADLINE, new ReplaceStrategy());
        replaceKeys.put(QUALITY_REPORT, new ReplaceStrategy());
        Map<String, KeyStrategy> strategies = new HashMap<>(replaceKeys);
        // 节点仅返回本轮增量；重放同一增量不能重复计入历史。
        strategies.put(REPAIR_RECORDS, new AppendStrategy(false));
        return () -> new HashMap<>(strategies);
    }
}
