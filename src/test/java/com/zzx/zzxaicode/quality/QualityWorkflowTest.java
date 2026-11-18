package com.zzx.zzxaicode.quality;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.zzxaicode.ai.springai.SpringAiCodeGenerator;
import com.zzx.zzxaicode.quality.check.BuildQualityChecker;
import com.zzx.zzxaicode.quality.check.BrowserQualityChecker;
import com.zzx.zzxaicode.quality.config.QualityCheckProperties;
import com.zzx.zzxaicode.quality.model.*;
import com.zzx.zzxaicode.quality.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实际 SAA Graph，模型和构建受控；覆盖业务预算和失败终态，不能替代真实模型/构建验收。 */
class QualityWorkflowTest {
    @TempDir Path root;
    /** 默认两次修复恰好三次检查；只有通过报告和发布后正常结束。 */
    @Test void repairsTwiceThenPublishes() throws Exception {
        Fixture f=new Fixture(2,2,false);
        f.service.stream(1,2,"计数器").blockLast(Duration.ofSeconds(10));
        assertEquals(3,f.checks.get()); assertEquals(2,f.repairs.get());
        assertEquals(QualityTaskStatus.SUCCEEDED,f.saved.getLast().status());
        assertTrue(Files.isRegularFile(f.workspaces.publishedSource(1).resolve("dist/index.html")));
        verify(f.session).save();
    }
    /** 零次预算不修复；环境错误不会进入模型修复；用完预算不再复检。 */
    @Test void rejectsEnvironmentAndBudgetExhaustion() throws Exception {
        for(int budget:List.of(0,2)) {
            Fixture f=new Fixture(budget,99,false);
            assertThrows(RuntimeException.class,()->f.service.stream(1,2,"计数器").blockLast(Duration.ofSeconds(10)));
            assertEquals(budget+1,f.checks.get()); assertEquals(budget,f.repairs.get());
            assertEquals(QualityTaskStatus.FAILED,f.saved.getLast().status());
            verify(f.session,never()).save();
        }
        Fixture env=new Fixture(2,99,true);
        assertThrows(RuntimeException.class,()->env.service.stream(1,2,"计数器").blockLast(Duration.ofSeconds(10)));
        assertEquals(0,env.repairs.get()); assertEquals(1,env.checks.get());
    }

    /** 客户端断开停止模型，等待文件门回收后才释放同一应用的资格。 */
    @Test void cancellationStopsModelAndReleasesLease() throws Exception {
        Fixture f=new Fixture(2,0,false);
        AtomicBoolean modelCancelled=new AtomicBoolean();
        when(f.session.stream(any(),anyInt(),any())).thenReturn(Flux.<String>never().doOnCancel(()->modelCancelled.set(true)));
        var subscription=f.service.stream(1,2,"计数器").subscribe();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(()->!f.saved.isEmpty());
        subscription.dispose();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(()->modelCancelled.get()
                && f.saved.getLast().status()==QualityTaskStatus.CANCELLED);
        verify(f.session).cancelAndAwaitFiles();
        assertEquals(0,f.checks.get());
    }

    /** 首屏代码错误进入同一个修复预算；下一轮必须重新构建并重新检查页面。 */
    @Test void browserFailureRoutesBackThroughBuildAndRepair() throws Exception {
        Fixture f=new Fixture(2,0,false); AtomicInteger runs=new AtomicInteger();
        when(f.browser.check(any(),any(),any(),any(),any())).thenAnswer(call->{
            QualityReport build=call.getArgument(1);
            if(runs.getAndIncrement()>0) return build;
            var items=new ArrayList<>(build.items());
            items.add(new QualityCheckItem("browser",true,QualityCheckItemStatus.FAILED,1,List.of(new QualityProblem(
                    QualityProblemCategory.RUNTIME,true,null,null,null,"early-error","browser.log"))));
            return new QualityReport(build.taskId(),build.appId(),build.checkRound(),build.startedAt(),Instant.now(),items,build.artifactId());
        });
        f.service.stream(1,2,"计数器").blockLast(Duration.ofSeconds(10));
        assertEquals(2,f.checks.get());assertEquals(1,f.repairs.get());assertEquals(2,runs.get());
    }

    /** 模型没有有效写入时提前停止；终态存储失败时补偿预览和模型上下文。 */
    @Test void noProgressStopsAndConfirmationFailureRestoresPreview() throws Exception {
        Fixture unchanged=new Fixture(2,99,false);
        when(unchanged.session.stream(notNull(),anyInt(),any())).thenReturn(Flux.just("无修改"));
        assertThrows(RuntimeException.class,()->unchanged.service.stream(1,2,"计数器").blockLast(Duration.ofSeconds(10)));
        assertEquals(1,unchanged.checks.get());
        assertFalse(unchanged.saved.getLast().repairs().getFirst().changed());
        Fixture failedStore=new Fixture(2,0,false);
        Path old=failedStore.workspaces.publishedSource(1).resolve("dist");Files.createDirectories(old);Files.writeString(old.resolve("index.html"),"old");
        doAnswer(call->{ QualityTaskSnapshot t=call.getArgument(0);if(t.status()==QualityTaskStatus.SUCCEEDED) throw new IllegalStateException("store unavailable");failedStore.saved.add(t);return null; }).when(failedStore.store).save(any());
        assertThrows(RuntimeException.class,()->failedStore.service.stream(1,2,"计数器").blockLast(Duration.ofSeconds(10)));
        assertEquals("old",Files.readString(failedStore.workspaces.publishedSource(1).resolve("dist/index.html")));
        verify(failedStore.session).restorePreviousContext();
    }

    /** 测试依赖及结果集合，每个实例有独立应用根。 */
    class Fixture {
        final QualityWorkspaceService workspaces;
        final SpringAiCodeGenerator.WorkspaceSession session=mock(SpringAiCodeGenerator.WorkspaceSession.class);
        final AtomicInteger checks=new AtomicInteger(),repairs=new AtomicInteger();
        final List<QualityTaskSnapshot> saved=new CopyOnWriteArrayList<>();
        final QualityTaskStore store=mock(QualityTaskStore.class);
        final BrowserQualityChecker browser=mock(BrowserQualityChecker.class);
        final QualityWorkflowService service;
        /** @param budget 修复预算 @param failures 先失败的检查数 @param environment 是否模拟环境错误 */
        Fixture(int budget,int failures,boolean environment) throws Exception {
            workspaces=new QualityWorkspaceService(root.resolve(UUID.randomUUID().toString()));
            var generator=mock(SpringAiCodeGenerator.class);
            AtomicReference<Path> source=new AtomicReference<>();
            when(generator.openWorkspaceSession(anyLong(),anyString(),anyString(),any())).thenAnswer(call->{source.set(call.getArgument(3));return session;});
            when(session.stream(any(),anyInt(),any())).thenAnswer(call->Flux.defer(()->{
                try {
                    Files.createDirectories(source.get().resolve("src"));
                    int count=call.getArgument(0)==null?0:repairs.incrementAndGet();
                    Files.writeString(source.get().resolve("src/App.vue"),"<template>版本"+count+"</template>");
                    return Flux.just("模型事件");
                } catch(java.io.IOException e) { return Flux.error(e); }
            }));
            var checker=mock(BuildQualityChecker.class);
            when(checker.check(any(),anyInt(),any(),any())).thenAnswer(call->{
                var ws=(QualityWorkspaceService.Workspace)call.getArgument(0);
                int round=call.getArgument(1); boolean pass=checks.getAndIncrement()>=failures;
                String artifact="quality-dist-"+round;
                if(pass) { Files.createDirectories(ws.source().resolve(artifact)); Files.writeString(ws.source().resolve(artifact+"/index.html"),"ok"); }
                var problems=pass?List.<QualityProblem>of():List.of(new QualityProblem(environment?QualityProblemCategory.ENVIRONMENT:QualityProblemCategory.SYNTAX,!environment,null,null,null,"诊断"+round,"build.log"));
                Instant now=Instant.now();
                return new QualityReport(ws.taskId(),ws.appId(),round,now,now,List.of(new QualityCheckItem("build",true,pass?QualityCheckItemStatus.PASSED:QualityCheckItemStatus.FAILED,1,problems)),pass?artifact:null);
            });
            doAnswer(c->{saved.add(c.getArgument(0));return null;}).when(store).save(any());
            QualityCheckProperties settings=new QualityCheckProperties(); settings.setMaxRepairAttempts(budget);
            when(browser.check(any(),any(),any(),any(),any())).thenAnswer(call->call.getArgument(1));
            service=new QualityWorkflowService(generator,workspaces,new QualityAppLock(),checker,browser,new QualityRepairSupport(),settings,store,new ObjectMapper().findAndRegisterModules());
        }
    }
}
