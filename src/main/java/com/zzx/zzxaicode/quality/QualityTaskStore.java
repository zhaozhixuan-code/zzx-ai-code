package com.zzx.zzxaicode.quality;
import com.zzx.zzxaicode.quality.model.QualityTaskSnapshot;
/** 业务报告存储边界，不承担 Graph 检查点或模型消息持久化。 */
public interface QualityTaskStore {
    /** @param task 服务端新任务；重复标识必须拒绝 */
    void create(QualityTaskSnapshot task);
    /** @param task 更新快照；已终态任务不得被进度覆盖，失败须抛异常 */
    void save(QualityTaskSnapshot task);
    /** @param appId 已鉴权应用 @return 最近任务；从未执行返回 null */
    QualityTaskSnapshot latest(long appId);
    /** @param taskId 服务端任务标识 @return 对应报告；不存在返回 null */
    QualityTaskSnapshot find(String taskId);
}
