package com.zzx.zzxaicode.quality;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.zzxaicode.ai.springai.SpringAiCodeGenerator;
import com.zzx.zzxaicode.exception.*;
import com.zzx.zzxaicode.quality.check.BuildQualityChecker;
import com.zzx.zzxaicode.quality.config.QualityCheckProperties;
import com.zzx.zzxaicode.quality.graph.*;
import com.zzx.zzxaicode.quality.model.*;
import com.zzx.zzxaicode.quality.workspace.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.zzx.zzxaicode.quality.graph.QualityGraphStateContract.*;

/** Vue 生成的实际 Graph 闭环；每个订阅独占工作区、模型会话、预算与应用租约。 */
@Service
public class QualityWorkflowService {
    public static final String EVENT_PREFIX="\u001equality:";
    private final SpringAiCodeGenerator generator;
    private final QualityWorkspaceService workspaces;
    private final QualityAppLock locks;
    private final BuildQualityChecker checker;
    private final QualityRepairSupport repair;
    private final QualityCheckProperties settings;
    private final QualityTaskStore store;
    private final ObjectMapper json;

    /** 注入模型、隔离、检查和报告能力；构造时不启动任何任务或外部进程。 */
    public QualityWorkflowService(SpringAiCodeGenerator generator,QualityWorkspaceService workspaces,QualityAppLock locks,
            BuildQualityChecker checker,QualityRepairSupport repair,QualityCheckProperties settings,QualityTaskStore store,ObjectMapper json) {
        this.generator=generator; this.workspaces=workspaces; this.locks=locks; this.checker=checker;
        this.repair=repair; this.settings=settings; this.store=store; this.json=json;
    }

    /**
     * 订阅时启动一次任务；取消先停止实际文件/构建操作，再释放租约，不重试整个生成链路。
     * @param appId 已鉴权应用 @param userId 已鉴权用户 @param requirement 原始需求
     * @return 实时模型事件与带保留前缀的质量快照；仅成功任务正常完成
     */
    public Flux<String> stream(long appId,long userId,String requirement) {
        return Flux.usingWhen(Mono.fromCallable(()->open(appId,userId,requirement)).subscribeOn(Schedulers.boundedElastic()),
                run->GraphEventBridge.stream(run::graph)
                        .takeUntilOther(Mono.delay(settings.getOverallTimeout()).flatMap(n->Mono.error(new java.util.concurrent.TimeoutException("任务达到整体期限"))))
                        .onErrorResume(error->Mono.fromRunnable(()->run.fail(error)).subscribeOn(Schedulers.boundedElastic())
                                .thenMany(Flux.concat(Flux.just(run.event()),Flux.error(new BusinessException(ErrorCode.OPERATION_ERROR,run.message))))),
                run->cleanup(run,false), (run,error)->cleanup(run,false),run->cleanup(run,true));
    }

    /** @param run 单次资源 @param cancelled 是否客户端取消 @return 完成资源回收后才结束的信号 */
    private Mono<Void> cleanup(Run run,boolean cancelled) {
        return Mono.fromRunnable(()->run.close(cancelled)).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /** @param appId 应用 @param userId 用户 @param requirement 原始需求 @return 已保存初始报告的独占执行资源 */
    private Run open(long appId,long userId,String requirement) throws IOException {
        if(!settings.isEnabled()) throw new BusinessException(ErrorCode.OPERATION_ERROR,"质量检查已停用，Vue 生成暂不可用");
        if(appId<=0 || userId<=0) throw new BusinessException(ErrorCode.PARAMS_ERROR,"任务归属无效");
        var lease=locks.acquire(appId);
        try {
            var workspace=workspaces.prepare(appId,UUID.randomUUID().toString());
            var session=generator.openWorkspaceSession(appId,String.valueOf(userId),requirement,workspace.source());
            Run run=new Run(workspace,userId,requirement,session,lease);
            store.create(run.snapshot());
            return run;
        } catch(IOException | RuntimeException error) { lease.close(); throw error; }
    }

    /** 执行资源及用于报告回收的最新投影；图内计数与路由由节点返回的状态增量驱动。 */
    private final class Run {
        final QualityWorkspaceService.Workspace workspace;
        final long userId;
        final String requirement;
        final SpringAiCodeGenerator.WorkspaceSession session;
        final QualityAppLock.Lease lease;
        final Instant started=Instant.now();
        final Instant deadline=started.plus(settings.getOverallTimeout());
        final AtomicBoolean cancelled=new AtomicBoolean();
        final AtomicBoolean safeToRelease=new AtomicBoolean(true);
        final Object gate=new Object();
        final List<QualityReport> reports=new ArrayList<>();
        final List<RepairRecord> repairs=new ArrayList<>();
        QualityTaskStatus status=QualityTaskStatus.RUNNING;
        QualityTaskStage stage=QualityTaskStage.CREATED;
        Instant finished;
        int attempts;
        String message="等待生成";
        String artifact;

        /** @param workspace 独立目录 @param userId 用户 @param requirement 需求 @param session 私有模型会话 @param lease 应用资格 */
        Run(QualityWorkspaceService.Workspace workspace,long userId,String requirement,
                SpringAiCodeGenerator.WorkspaceSession session,QualityAppLock.Lease lease) {
            this.workspace=workspace; this.userId=userId; this.requirement=requirement; this.session=session; this.lease=lease;
        }

        /** @param events 实时事件出口 @return 实际 Graph 执行，失败节点保留 error 语义 */
        Flux<?> graph(GraphEventBridge.Execution events) {
            try {
                return new StateGraph(QualityGraphStateContract.keyStrategyFactory())
                        .addNode("generate",state->events.forward(Flux.defer(()->{
                            progress(QualityTaskStage.GENERATING,"正在生成",events);
                            return session.stream(null,settings.getMaxModelToolRounds(),remaining(settings.getModelTimeout()));
                        }),Map.of(REPAIR_ATTEMPTS,0)))
                        .addNode("check",state->events.complete(Mono.fromCallable(()->{
                            synchronized(gate) {
                                ensureRunning();
                                attempts=state.value(REPAIR_ATTEMPTS,0);
                                progress(QualityTaskStage.CHECKING,"正在检查第 "+(attempts+1)+" 轮",events);
                                QualityReport report=checker.check(workspace,attempts,cancelled,safeToRelease);
                                ensureRunning(); reports.add(report); store.save(snapshot()); events.emit(event());
                                return Map.<String,Object>of(QUALITY_REPORT,report,CHECK_ROUND,attempts);
                            }
                        }).subscribeOn(Schedulers.boundedElastic())))
                        .addConditionalEdges("check",edge_async(this::route),Map.of("success","success","repair","repair","failure","failure"))
                        .addNode("repair",state->events.complete(Mono.defer(()->{
                            QualityReport report=state.value(QUALITY_REPORT).map(QualityReport.class::cast).orElseThrow();
                            attempts=state.value(REPAIR_ATTEMPTS,0)+1;
                            progress(QualityTaskStage.REPAIRING,"正在定向修复，第 "+attempts+" 次",events);
                            try {
                                Map<String,String> before=repair.snapshot(workspace.source());
                                return session.stream(repair.prompt(requirement,report),settings.getMaxModelToolRounds(),remaining(settings.getModelTimeout()))
                                        .doOnNext(events::emit).then(Mono.fromCallable(()->{
                                            synchronized(gate) {
                                                ensureRunning();
                                                RepairRecord record=repair.audit(attempts,report,before,repair.snapshot(workspace.source()));
                                                repairs.add(record); store.save(snapshot()); events.emit(event());
                                                if(!record.changed()) throw new BusinessException(ErrorCode.OPERATION_ERROR,"修复未产生有效源码变更，已提前结束");
                                                return Map.<String,Object>of(REPAIR_ATTEMPTS,attempts,REPAIR_RECORDS,List.of(record));
                                            }
                                        }));
                            } catch(IOException error) { return Mono.error(error); }
                        }).subscribeOn(Schedulers.boundedElastic())))
                        .addNode("success",state->events.complete(Mono.fromCallable(()->{
                            synchronized(gate) {
                                QualityReport report=state.value(QUALITY_REPORT).map(QualityReport.class::cast).orElseThrow();
                                confirm(report,events);
                                return Map.<String,Object>of(FINAL_STATUS,QualityTaskStatus.SUCCEEDED);
                            }
                        }).subscribeOn(Schedulers.boundedElastic())))
                        .addNode("failure",state->java.util.concurrent.CompletableFuture.failedFuture(
                                new BusinessException(ErrorCode.OPERATION_ERROR,message)))
                        .addEdge(StateGraph.START,"generate").addEdge("generate","check")
                        .addEdge("repair","check").addEdge("success",StateGraph.END).addEdge("failure",StateGraph.END)
                        .compile(CompileConfig.builder().recursionLimit(Math.addExact(Math.multiplyExact(settings.getMaxRepairAttempts(),3),12)).build())
                        .stream(Map.of(TASK_ID,workspace.taskId(),APP_ID,workspace.appId(),USER_ID,userId,
                                ORIGINAL_REQUIREMENT,requirement,MAX_REPAIR_ATTEMPTS,settings.getMaxRepairAttempts(),DEADLINE,deadline));
            } catch(GraphStateException error) { return Flux.error(error); }
        }

        /** @param state 检查节点返回的实际状态 @return 通过、修复或失败，修复预算独立于模型工具循环 */
        String route(OverAllState state) {
            QualityReport report=state.value(QUALITY_REPORT).map(QualityReport.class::cast).orElseThrow();
            if(report.isPassed()) return "success";
            var problems=report.items().stream().filter(i->i.required() && i.status()!=QualityCheckItemStatus.PASSED)
                    .flatMap(i->i.problems().stream()).toList();
            message=problems.isEmpty()?"检查未通过":problems.getFirst().summary();
            if(problems.isEmpty() || problems.stream().anyMatch(p->!p.repairable() || p.category()==QualityProblemCategory.ENVIRONMENT)) return "failure";
            if(state.value(REPAIR_ATTEMPTS,0)>=settings.getMaxRepairAttempts()) { message="已达到修复上限："+message; return "failure"; }
            if(reports.size()>1 && repair.diagnosticFingerprint(report).equals(repair.diagnosticFingerprint(reports.get(reports.size()-2)))) {
                message="相同诊断再次出现，未取得可验证进展："+message; return "failure";
            }
            return "repair";
        }

        /** @param report 全部必选项通过的报告 @param events 事件出口；报告与指针失败时补偿旧预览 */
        void confirm(QualityReport report,GraphEventBridge.Execution events) throws IOException {
            ensureRunning(); progress(QualityTaskStage.CONFIRMING_ARTIFACT,"检查通过，正在确认产物",events);
            Files.move(workspace.source().resolve(report.artifactId()),workspace.source().resolve("dist"),StandardCopyOption.ATOMIC_MOVE);
            ensureRunning();
            String previous=workspaces.publish(workspace);
            try {
                ensureRunning(); session.save();
                artifact=report.artifactId(); stage=QualityTaskStage.SUCCEEDED; status=QualityTaskStatus.SUCCEEDED;
                finished=Instant.now(); message="质量检查通过";
                store.save(snapshot());
            } catch(RuntimeException error) {
                status=QualityTaskStatus.RUNNING; finished=null; artifact=null;
                workspaces.setPublished(workspace.appId(),previous);
                try { session.restorePreviousContext(); }
                catch(RuntimeException restoreError) { safeToRelease.set(false); error.addSuppressed(restoreError); }
                throw error;
            }
            events.emit(event());
        }

        /** @param next 下一阶段 @param text 进度描述 @param events 实时出口；先持久化再通知页面 */
        void progress(QualityTaskStage next,String text,GraphEventBridge.Execution events) {
            synchronized(gate) { ensureRunning(); stage=next; message=text; store.save(snapshot()); events.emit(event()); }
        }

        /** @param error 真实异常；只暴露业务原因，底层异常不带出主机路径或凭据 */
        void fail(Throwable error) {
            cancelled.set(true); session.cancelAndAwaitFiles();
            synchronized(gate) {
                if(status!=QualityTaskStatus.RUNNING) return;
                status=QualityTaskStatus.FAILED; stage=QualityTaskStage.FAILED; finished=Instant.now();
                Throwable cause=reactor.core.Exceptions.unwrap(error);
                while(cause.getCause()!=null && !(cause instanceof BusinessException)) cause=cause.getCause();
                message=cause instanceof BusinessException?cause.getMessage():cause instanceof java.util.concurrent.TimeoutException?"任务执行超时":"任务执行异常，请查看服务端日志";
                store.save(snapshot());
            }
        }

        /** @param clientCancelled 客户端断开；清理真实执行后才允许另一次写入 */
        void close(boolean clientCancelled) {
            cancelled.set(true); session.cancelAndAwaitFiles();
            synchronized(gate) {
                try {
                    if(status==QualityTaskStatus.RUNNING) {
                        status=clientCancelled?QualityTaskStatus.CANCELLED:QualityTaskStatus.FAILED;
                        stage=clientCancelled?QualityTaskStage.CANCELLED:QualityTaskStage.FAILED;
                        finished=Instant.now(); message=clientCancelled?"客户端已取消":"任务未完成"; store.save(snapshot());
                    }
                } finally { if(safeToRelease.get()) lease.close(); }
            }
        }

        /** @return 无执行资源的完整、不可变业务快照 */
        QualityTaskSnapshot snapshot() {
            return new QualityTaskSnapshot(workspace.taskId(),workspace.appId(),userId,status,stage,attempts,
                    settings.getMaxRepairAttempts(),started,finished,message,artifact,List.copyOf(reports),List.copyOf(repairs));
        }
        /** @return 页面可识别的质量事件，不与模型正文混淆 */
        String event() {
            try { return EVENT_PREFIX+json.writeValueAsString(snapshot()); }
            catch(JsonProcessingException error) { throw new IllegalStateException("质量事件序列化失败",error); }
        }
        /** 取消和整体期限检查必须在每次有副作用的业务动作前执行。 */
        void ensureRunning() {
            if(cancelled.get() || Instant.now().isAfter(deadline)) throw new BusinessException(ErrorCode.OPERATION_ERROR,"任务已取消或超过期限");
        }
        /** @param stageTimeout 单阶段预算 @return 不超过剩余整体期限的阶段时长 */
        Duration remaining(Duration stageTimeout) {
            ensureRunning(); Duration left=Duration.between(Instant.now(),deadline);
            return stageTimeout.compareTo(left)<0?stageTimeout:left;
        }
    }
}
