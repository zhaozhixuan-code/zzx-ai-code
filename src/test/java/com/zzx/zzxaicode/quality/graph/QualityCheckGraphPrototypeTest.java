package com.zzx.zzxaicode.quality.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S01 最小 Graph 原型的状态隔离和有界路由验证。 */
class QualityCheckGraphPrototypeTest {

    /** 验证首次检查失败后只修复一次，再次检查通过并结束。 */
    @Test
    void shouldRepairOnceAndFinishSuccessfully() {
        QualityCheckGraphPrototype.PrototypeResult result =
                QualityCheckGraphPrototype.run("task-a");

        assertEquals("task-a", result.taskId());
        assertTrue(result.checkPassed());
        assertEquals(1, result.repairAttempts());
        assertEquals(2, result.checkRuns());
        assertEquals("SUCCEEDED", result.finalStatus());
    }

    /** 验证两次执行的任务标识和计数互不共享。 */
    @Test
    void shouldKeepTaskStateIsolated() {
        QualityCheckGraphPrototype.PrototypeResult first =
                QualityCheckGraphPrototype.run("task-a");
        QualityCheckGraphPrototype.PrototypeResult second =
                QualityCheckGraphPrototype.run("task-b");

        assertEquals("task-a", first.taskId());
        assertEquals("task-b", second.taskId());
        assertEquals(first.repairAttempts(), second.repairAttempts());
        assertEquals(first.checkRuns(), second.checkRuns());
    }
}
