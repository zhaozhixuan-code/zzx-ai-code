package com.zzx.zzxaicode.quality.check;

import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.quality.model.*;
import com.zzx.zzxaicode.quality.workspace.QualityWorkspaceService;
import com.zzx.zzxaicode.utils.ScreenshotDriverFactory;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 对唯一候选做受控首屏检查，捕获导航前安装的异常监听、关键资源失败与可见空白。 */
@Component
@lombok.extern.slf4j.Slf4j
public class BrowserQualityChecker {
    private final ScreenshotDriverFactory factory;
    /** @param factory 创建隔离浏览器会话，不复用用户标签页 */
    public BrowserQualityChecker(ScreenshotDriverFactory factory) { this.factory=factory; }

    /**
     * 构建失败时不启动浏览器；成功候选加入必选 browser 结果，退出必须回收真实会话。
     * @param workspace 服务端任务目录 @param build 本轮构建报告 @param timeout 阶段剩余预算
     * @param cancelled 共享取消标志 @param safeToRelease 会话退出不明时置 false，保留应用资格
     * @return 包含构建和浏览器结果的同轮次报告，无法启动归类环境异常
     */
    public QualityReport check(QualityWorkspaceService.Workspace workspace,QualityReport build,Duration timeout,
            AtomicBoolean cancelled,AtomicBoolean safeToRelease) throws IOException,InterruptedException {
        if(!build.isPassed()) return build;
        Instant start=Instant.now(); Instant deadline=start.plus(timeout);
        String logRef="quality-browser-"+build.checkRound()+".log";
        ChromeDriver driver=null;
        QualityCheckItem item;
        try(CandidatePreviewServer server=new CandidatePreviewServer(workspace.source().resolve(build.artifactId()))) {
            if(cancelled.get()) throw new InterruptedException("浏览器检查已取消");
            driver=factory.createQualityDriver(server.origin(),timeout);
            driver.manage().timeouts().pageLoadTimeout(timeout).scriptTimeout(timeout);
            driver.executeCdpCommand("Network.enable",Map.of());
            driver.executeCdpCommand("Network.setCacheDisabled",Map.of("cacheDisabled",true));
            // 在导航之前安装监听，不能等待页面打开后才捕获首屏异常；列表有界避免错误风暴撑满报告。
            driver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument",Map.of("source",captureScript()));
            driver.get(server.origin()+"/index.html");
            Instant settle=Instant.now().plusMillis(1500);
            while(Instant.now().isBefore(settle)) {
                if(cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("浏览器检查已取消");
                if(Instant.now().isAfter(deadline)) throw new org.openqa.selenium.TimeoutException("浏览器阶段超时");
                Thread.sleep(100);
            }
            @SuppressWarnings("unchecked") List<String> errors=(List<String>)driver.executeScript("return window.__qualityErrors || ['首屏采集器不可用']");
            List<String> diagnostics=new ArrayList<>(errors);
            server.missingCriticalResources().forEach(path->diagnostics.add("本地关键资源缺失："+path));
            boolean visible=Boolean.TRUE.equals(driver.executeScript("return !!document.body && (document.body.innerText.trim().length>0 || [...document.querySelectorAll('img,svg,canvas,video')].some(e=>e.getBoundingClientRect().width>0 && e.getBoundingClientRect().height>0))"));
            if(!visible) diagnostics.add("首屏无可见文本或图形内容，疑似空白；仅检查当前入口，不代表所有交互均已验证");
            if(!driver.getCurrentUrl().startsWith(server.origin()+"/")) diagnostics.add("首屏离开本轮候选入口");
            byte[] screenshot=driver.getScreenshotAs(OutputType.BYTES);
            if(screenshot.length<=5*1024*1024) Files.write(workspace.source().resolve("quality-browser-"+build.checkRound()+".png"),screenshot);
            String evidence=String.join("\n",diagnostics).replace(server.origin(),"[preview]");
            Files.writeString(workspace.source().resolve(logRef),evidence.isBlank()?"受控首屏检查通过；外部网络已阻断，未验证后续交互":evidence);
            List<QualityProblem> problems=diagnostics.isEmpty()?List.of():List.of(new QualityProblem(QualityProblemCategory.RUNTIME,true,null,null,null,
                    evidence.substring(0,Math.min(4000,evidence.length())),logRef));
            item=new QualityCheckItem("browser",true,problems.isEmpty()?QualityCheckItemStatus.PASSED:QualityCheckItemStatus.FAILED,
                    Duration.between(start,Instant.now()).toMillis(),problems);
        } catch(org.openqa.selenium.TimeoutException error) {
            log.warn("首屏检查超时，taskId={}",workspace.taskId(),error);
            item=environment(start,QualityCheckItemStatus.TIMEOUT,"浏览器首屏检查超时",logRef);
        } catch(WebDriverException | BusinessException error) {
            log.warn("浏览器检查环境异常，taskId={}",workspace.taskId(),error);
            item=environment(start,QualityCheckItemStatus.ERROR,"浏览器环境无法启动或执行检查",logRef);
        } finally {
            if(driver!=null) {
                try { driver.quit(); }
                catch(WebDriverException error) { safeToRelease.set(false); }
            }
        }
        List<QualityCheckItem> items=new ArrayList<>(build.items());items.add(item);
        Path log=workspace.source().resolve(logRef);
        if(!Files.exists(log)) Files.writeString(log,item.problems().getFirst().summary());
        return new QualityReport(build.taskId(),build.appId(),build.checkRound(),build.startedAt(),Instant.now(),items,build.artifactId());
    }
    /** @param start 阶段开始 @param status 实际检查异常 @param message 无敏感信息的原因 @param ref 日志名 @return 不触发源码修复的环境项 */
    private QualityCheckItem environment(Instant start,QualityCheckItemStatus status,String message,String ref) {
        return new QualityCheckItem("browser",true,status,Duration.between(start,Instant.now()).toMillis(),
                List.of(new QualityProblem(QualityProblemCategory.ENVIRONMENT,false,null,null,null,message,ref)));
    }
    /** @return 导航前执行的有界采集器，覆盖未捕获异常、Promise 拒绝及 console.error */
    private String captureScript() {
        return "(()=>{const errors=[];Object.defineProperty(window,'__qualityErrors',{value:errors});"
                +"const add=x=>{if(errors.length<100)errors.push(String(x).slice(0,1000));};"
                +"addEventListener('error',e=>{if(e.message)add(e.message+' '+(e.filename||'')+':'+e.lineno);},true);"
                +"addEventListener('unhandledrejection',e=>add('Unhandled rejection: '+e.reason));"
                +"const original=console.error;console.error=(...args)=>{add(args.join(' '));original.apply(console,args);};})();";
    }
}
