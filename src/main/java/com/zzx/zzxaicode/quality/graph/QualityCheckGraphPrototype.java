package com.zzx.zzxaicode.quality.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 质量检查闭环的 S01 最小 Graph 原型。
 *
 * <p>该原型只验证 Graph 的状态更新、条件路由和有界循环，不接入模型、文件系统或真实构建器。
 * 后续业务节点应在此 API 验证基础上接入，不能把本类中的假检查结果当作产品质量结论。</p>
 */
public final class QualityCheckGraphPrototype {

    /** Graph 状态中的服务端任务标识，用于验证不同任务的状态不会互相覆盖。 */
    static final String TASK_ID = "taskId";
    /** Graph 状态中的检查是否通过标识。 */
    static final String CHECK_PASSED = "checkPassed";
    /** Graph 状态中的已启动修复次数。 */
    static final String REPAIR_ATTEMPTS = "repairAttempts";
    /** Graph 状态中的检查次数。 */
    static final String CHECK_RUNS = "checkRuns";
    /** Graph 状态中的当前阶段。 */
    static final String STAGE = "stage";
    /** Graph 状态中的业务终态。 */
    static final String FINAL_STATUS = "finalStatus";

    private QualityCheckGraphPrototype() {
    }

    /**
     * 执行一次隔离的假质量检查闭环。
     *
     * @param taskId 服务端任务标识，不能为空或空白
     * @return 该任务的最终状态、检查次数和修复次数
     * @throws IllegalArgumentException 当任务标识为空时抛出
     * @throws IllegalStateException 当 Graph 构建或执行未产生最终状态时抛出
     */
    public static PrototypeResult run(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }

        try {
            CompiledGraph graph = createGraph();
            Optional<OverAllState> result = graph.invoke(Map.of(
                    TASK_ID, taskId,
                    CHECK_PASSED, false,
                    REPAIR_ATTEMPTS, 0,
                    CHECK_RUNS, 0,
                    STAGE, "INITIALIZED"));
            OverAllState state = result.orElseThrow(() ->
                    new IllegalStateException("Graph 未返回最终状态"));
            return new PrototypeResult(
                    state.value(TASK_ID, ""),
                    state.value(CHECK_PASSED, false),
                    state.value(REPAIR_ATTEMPTS, 0),
                    state.value(CHECK_RUNS, 0),
                    state.value(STAGE, "UNKNOWN"),
                    state.value(FINAL_STATUS, "UNKNOWN"));
        } catch (GraphStateException e) {
            throw new IllegalStateException("质量检查 Graph 创建失败", e);
        }
    }

    /**
     * 创建“生成 → 检查 →（修复一次或成功）→ 结束”的最小状态图。
     *
     * @return 已编译、可独立执行的 Graph
     * @throws GraphStateException 当节点或边定义不符合 Graph 约束时抛出
     */
    static CompiledGraph createGraph() throws GraphStateException {
        return new StateGraph(createKeyStrategyFactory())
                .addNode("generate", node_async(state -> Map.of(
                        STAGE, "GENERATED")))
                .addNode("check", node_async(state -> {
                    int checkRuns = state.value(CHECK_RUNS, 0) + 1;
                    int repairAttempts = state.value(REPAIR_ATTEMPTS, 0);
                    // 假检查只在修复节点完成后通过，用于稳定验证条件边和循环上限。
                    return Map.of(
                            CHECK_RUNS, checkRuns,
                            CHECK_PASSED, repairAttempts > 0,
                            STAGE, "CHECKED");
                }))
                .addNode("repair", node_async(state -> {
                    int repairAttempts = state.value(REPAIR_ATTEMPTS, 0) + 1;
                    return Map.of(
                            REPAIR_ATTEMPTS, repairAttempts,
                            STAGE, "REPAIRED");
                }))
                .addNode("success", node_async(state -> Map.of(
                        FINAL_STATUS, "SUCCEEDED",
                        STAGE, "SUCCEEDED")))
                .addEdge(START, "generate")
                .addEdge("generate", "check")
                .addConditionalEdges("check", edge_async(state ->
                                state.value(CHECK_PASSED, false) ? "success" : "repair"),
                        Map.of("repair", "repair", "success", "success"))
                .addEdge("repair", "check")
                .addEdge("success", END)
                .compile();
    }

    /**
     * 为原型状态定义替换策略。
     *
     * <p>每个任务执行时使用自己的初始状态；节点只返回增量，后续正式状态契约可据此扩展检查报告和修复记录。</p>
     *
     * @return Graph 使用的状态键策略工厂
     */
    private static KeyStrategyFactory createKeyStrategyFactory() {
        Map<String, KeyStrategy> strategies = new HashMap<>();
        strategies.put(TASK_ID, new ReplaceStrategy());
        strategies.put(CHECK_PASSED, new ReplaceStrategy());
        strategies.put(REPAIR_ATTEMPTS, new ReplaceStrategy());
        strategies.put(CHECK_RUNS, new ReplaceStrategy());
        strategies.put(STAGE, new ReplaceStrategy());
        strategies.put(FINAL_STATUS, new ReplaceStrategy());
        return () -> new HashMap<>(strategies);
    }

    /**
     * S01 原型执行结果，不携带执行资源或可变 Graph 对象。
     *
     * @param taskId 任务标识
     * @param checkPassed 最后一次检查是否通过
     * @param repairAttempts 已启动的修复次数
     * @param checkRuns 已执行的检查次数
     * @param stage 最终阶段
     * @param finalStatus 业务终态
     */
    public record PrototypeResult(
            String taskId,
            boolean checkPassed,
            int repairAttempts,
            int checkRuns,
            String stage,
            String finalStatus) {
    }
}
