package com.zzx.zzxaicode.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.zzxaicode.quality.model.*;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 同一事务保存任务投影与轮次；终态采用 RUNNING 条件更新，拒绝迟到进度覆盖。 */
@Repository
public class JdbcQualityTaskStore implements QualityTaskStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final String instanceId;
    @Value("${app.quality.enabled:true}") private boolean enabled=true;

    /** @param jdbc 数据访问 @param manager 事务管理器 @param json Java 时间序列化器 @param instanceId 单实例稳定标识 */
    public JdbcQualityTaskStore(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,
            @Value("${app.quality.instance-id:local}") String instanceId) {
        this.jdbc=jdbc; this.transactions=new TransactionTemplate(manager); this.json=json; this.instanceId=instanceId;
    }

    /** @param task 新任务；只有服务端 UUID 可作为标识，重复插入明确失败 */
    @Override public void create(QualityTaskSnapshot task) {
        jdbc.update("INSERT INTO quality_task(task_id,app_id,user_id,instance_id,status,started_at,payload,isDelete) VALUES(?,?,?,?,?,?,?,0)",
                task.taskId(),task.appId(),task.userId(),instanceId,task.status().name(),Timestamp.from(task.startedAt()),encode(task));
    }

    /** @param task 顺序执行的最新投影；任务与所有轮次在一个本地事务提交 */
    @Override public void save(QualityTaskSnapshot task) {
        transactions.executeWithoutResult(tx->{
            int count=jdbc.update("UPDATE quality_task SET status=?,payload=? WHERE task_id=? AND instance_id=? AND status='RUNNING' AND isDelete=0",
                    task.status().name(),encode(task),task.taskId(),instanceId);
            if(count!=1) {
                if(task.equals(find(task.taskId()))) return;
                throw new IllegalStateException("质量任务已结束或不存在，拒绝覆盖");
            }
            for(QualityReport report:task.reports()) {
                int updated=jdbc.update("UPDATE quality_round SET payload=? WHERE task_id=? AND check_round=? AND isDelete=0",
                        encode(report),task.taskId(),report.checkRound());
                if(updated==0) jdbc.update("INSERT INTO quality_round(task_id,check_round,payload,isDelete) VALUES(?,?,?,0)",
                        task.taskId(),report.checkRound(),encode(report));
            }
        });
    }

    /** @param appId 已鉴权应用 @return 最近任务，未执行时返回 null */
    @Override public QualityTaskSnapshot latest(long appId) {
        List<String> rows=jdbc.queryForList("SELECT payload FROM quality_task WHERE app_id=? AND isDelete=0 ORDER BY started_at DESC,task_id DESC LIMIT 1",String.class,appId);
        return rows.isEmpty()?null:decode(rows.getFirst());
    }

    /** @param taskId 服务端任务 UUID @return 对应报告；调用者仍须验证应用归属 */
    @Override public QualityTaskSnapshot find(String taskId) {
        List<String> rows=jdbc.queryForList("SELECT payload FROM quality_task WHERE task_id=? AND isDelete=0",String.class,taskId);
        return rows.isEmpty()?null:decode(rows.getFirst());
    }

    /** 仅标记本实例上次未结束任务，不重放模型、文件工具或构建；部署要求同一 instance-id 单进程运行。 */
    @PostConstruct
    public void interruptUnfinished() {
        if(!enabled) return;
        var pending=jdbc.queryForList("SELECT payload FROM quality_task WHERE instance_id=? AND status='RUNNING' AND isDelete=0",String.class,instanceId);
        for(String payload:pending) {
            QualityTaskSnapshot old=decode(payload);
            save(new QualityTaskSnapshot(old.taskId(),old.appId(),old.userId(),QualityTaskStatus.INTERRUPTED,
                    QualityTaskStage.INTERRUPTED,old.repairAttempts(),old.maxRepairAttempts(),old.startedAt(),Instant.now(),
                    "服务已重启，任务中断；未自动恢复执行",null,old.reports(),old.repairs()));
        }
    }

    /** @param value 业务数据 @return JSON，序列化失败必须阻止成功确认 */
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JsonProcessingException error) { throw new IllegalStateException("质量报告序列化失败",error); }
    }
    /** @param value 已存储 JSON @return 业务投影，不接受模型提供的多态类信息 */
    private QualityTaskSnapshot decode(String value) {
        try { return json.readValue(value,QualityTaskSnapshot.class); }
        catch(JsonProcessingException error) { throw new IllegalStateException("质量报告读取失败",error); }
    }
}
