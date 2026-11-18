package com.zzx.zzxaicode.quality.check;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.zzxaicode.quality.config.QualityCheckProperties;
import com.zzx.zzxaicode.quality.model.*;
import com.zzx.zzxaicode.quality.workspace.QualityWorkspaceService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** 真实 Vue 构建检查；生成代码只在有资源限制的 Docker 中执行，不回退到宿主机。 */
@Component
public class BuildQualityChecker {
    private final ControlledProcessRunner runner;
    private final QualityCheckProperties settings;
    private final ObjectMapper json;

    /** @param runner 有界进程执行器 @param settings 检查预算 @param json 工程配置解析器 */
    public BuildQualityChecker(ControlledProcessRunner runner, QualityCheckProperties settings, ObjectMapper json) {
        this.runner = runner;
        this.settings = settings;
        this.json = json;
    }

    /**
     * 安装时禁用生命周期脚本，构建时断网；执行平台固定 Vite CLI，不信任 package.json 的 build 脚本。
     * @param workspace 服务端任务根目录
     * @param round 首次为 0，每次修复后加 1
     * @param cancelled 可跨线程设置的任务取消标志
     * @param safeToRelease 清理失败时置为 false，调用者不得释放应用写入资格
     * @return 本轮真实报告；环境异常与代码失败分开分类
     */
    public QualityReport check(QualityWorkspaceService.Workspace workspace, int round,
                               AtomicBoolean cancelled, AtomicBoolean safeToRelease) throws IOException, InterruptedException {
        Instant start = Instant.now();
        Path source = workspace.source();
        QualityWorkspaceService.requireNoLink(source);
        if (!Files.isRegularFile(source.resolve("package.json")) || !Files.isRegularFile(source.resolve("index.html"))) {
            return report(workspace, round, start, QualityCheckItemStatus.FAILED,
                    QualityProblemCategory.MISSING_FILE, Files.isRegularFile(source.resolve("package.json")),
                    "工程缺少 package.json 或 index.html", "structure", null);
        }
        try { json.readTree(Files.readString(source.resolve("package.json"))); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return report(workspace, round, start, QualityCheckItemStatus.FAILED,
                    QualityProblemCategory.BUILD, false, "package.json 格式无效；受限修复不允许改变依赖配置", "structure", null);
        }
        String outDir = "quality-dist-" + round;
        if (Files.exists(source.resolve(outDir))) throw new IOException("本轮产物目录已存在，拒绝复用旧产物");
        String container = "quality-" + workspace.taskId() + "-" + round;
        boolean started = false;
        try {
            var available = runner.run(List.of("docker", "info", "--format", "{{.ServerVersion}}"), source,
                    Duration.ofSeconds(15), cancelled);
            if (available.timedOut() || available.exitCode() != 0) {
                return report(workspace, round, start, QualityCheckItemStatus.ERROR,
                        QualityProblemCategory.ENVIRONMENT, false, "Docker 守护进程不可用", "environment", null);
            }
            started = true;
            Duration timeout = settings.getBuildTimeout();
            var install = runner.run(command(container + "-install", source, true, "npm", "install", "--ignore-scripts",
                    "--no-audit", "--no-fund"), source, timeout, cancelled);
            Files.writeString(source.resolve("quality-install-" + round + ".log"), sanitize(install.output(), source));
            if (install.exitCode() != 0 || install.timedOut()) {
                return report(workspace, round, start, install.timedOut() ? QualityCheckItemStatus.TIMEOUT : QualityCheckItemStatus.ERROR,
                        QualityProblemCategory.ENVIRONMENT, false, "依赖安装失败，检查环境或依赖配置，未调用源码修复",
                        "quality-install-" + round + ".log", null);
            }
            Duration remaining = timeout.minus(Duration.between(start, Instant.now()));
            if (remaining.isNegative() || remaining.isZero()) throw new InterruptedException("构建阶段期限已到");
            var build = runner.run(command(container + "-build", source, false, "node", "node_modules/vite/bin/vite.js",
                    "build", "--base=./", "--outDir", outDir), source, remaining, cancelled);
            String log = sanitize(build.output(), source);
            String logRef = "quality-build-" + round + ".log";
            Files.writeString(source.resolve(logRef), log);
            if (build.timedOut()) return report(workspace, round, start, QualityCheckItemStatus.TIMEOUT,
                    QualityProblemCategory.ENVIRONMENT, false, "构建超时", logRef, null);
            if (build.exitCode() != 0) {
                // Docker/Node 启动失败并不构成可修复的源代码证据。
                boolean code = build.exitCode() != 125 && build.exitCode() != 126 && build.exitCode() != 127
                        && !log.contains("Cannot find module '/workspace/node_modules/vite/");
                return report(workspace, round, start, code ? QualityCheckItemStatus.FAILED : QualityCheckItemStatus.ERROR,
                        code ? QualityProblemCategory.BUILD : QualityProblemCategory.ENVIRONMENT, code,
                        code ? tail(log, 4000) : "构建执行环境不可用", logRef, null);
            }
            Path artifact = source.resolve(outDir);
            if (!validArtifact(artifact)) return report(workspace, round, start, QualityCheckItemStatus.FAILED,
                    QualityProblemCategory.MISSING_FILE, true, "构建退出成功但缺少有效入口或本地引用资源", logRef, null);
            return report(workspace, round, start, QualityCheckItemStatus.PASSED, null, false, "构建通过", logRef, outDir);
        } catch (IOException e) {
            return report(workspace, round, start, QualityCheckItemStatus.ERROR,
                    QualityProblemCategory.ENVIRONMENT, false, "Docker 构建环境不可用，未调用源码修复", "environment", null);
        } finally {
            if (started) {
                // 中断标志先清除以完成容器回收；回收后恢复标志，不把容器遗留当成已停止。
                boolean interrupted = Thread.interrupted();
                try {
                    for (String suffix : List.of("-install", "-build")) {
                        var cleanup = runner.run(List.of("docker", "rm", "-f", container + suffix), source,
                                Duration.ofSeconds(15), new AtomicBoolean());
                        if (cleanup.timedOut() || (cleanup.exitCode() != 0 && !cleanup.output().contains("No such container"))) {
                            // 守护进程失联时无法证明容器已停止，保留应用锁，禁止另一个任务并发写入。
                            safeToRelease.set(false);
                        }
                    }
                } catch (IOException | InterruptedException e) {
                    safeToRelease.set(false);
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
    }

    /**
     * 容器只挂载当前源码；安装可访问网络但禁止依赖脚本，构建断网且禁特权、限内存/CPU/PID。
     * @param name 服务端容器名 @param source 挂载目录 @param install 安装阶段开关 @param args 固定命令
     * @return 不经过 shell 的 Docker 参数
     */
    List<String> command(String name, Path source, boolean install, String... args) {
        List<String> result = new ArrayList<>(List.of("docker", "run", "--rm", "--pull=never", "--name", name,
                "--memory=768m", "--memory-swap=768m", "--cpus=1", "--pids-limit=128", "--cap-drop=ALL",
                "--security-opt=no-new-privileges", "--read-only", "--tmpfs", "/tmp:rw,nosuid,size=256m",
                "--network=" + (install ? settings.getInstallNetwork() : "none"), "--user=1000:1000",
                "--env", "npm_config_cache=/tmp/npm", "--mount", "type=bind,source=" + source + ",target=/workspace",
                "--workdir=/workspace", settings.getBuildImage()));
        result.addAll(List.of(args));
        return result;
    }

    /** @param artifact 本轮唯一产物目录 @return 入口及本地 src/href 引用是否存在且不越界 */
    public boolean validArtifact(Path artifact) throws IOException {
        artifact = artifact.toAbsolutePath().normalize();
        Path index = artifact.resolve("index.html");
        if (!Files.isRegularFile(index)) return false;
        QualityWorkspaceService.requireNoLink(index);
        var matcher = Pattern.compile("(?:src|href)=[\"']([^\"']+)[\"']").matcher(Files.readString(index));
        while (matcher.find()) {
            String ref = matcher.group(1).split("[?#]", 2)[0];
            if (ref.isBlank() || ref.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*") || ref.startsWith("//")) continue;
            Path target = artifact.resolve(ref.startsWith("/") ? ref.substring(1) : ref).normalize();
            if (!target.startsWith(artifact) || !Files.isRegularFile(target)) return false;
            QualityWorkspaceService.requireNoLink(target);
        }
        return true;
    }

    /** @return 日志安全摘要，隐藏当前宿主机工作目录 */
    private String sanitize(String log, Path source) { return log.replace(source.toString(), "[workspace]"); }

    /** @param text 原始诊断 @param max 最大字符数 @return 非空、有界诊断摘要 */
    private String tail(String text, int max) {
        if (text.isBlank()) return "构建命令失败，无诊断输出";
        return text.substring(Math.max(0, text.length() - max));
    }

    /** 将实际检查结果转换成框架无关业务报告，不伪造文件或行号。 */
    private QualityReport report(QualityWorkspaceService.Workspace workspace, int round, Instant start,
                                 QualityCheckItemStatus status, QualityProblemCategory category, boolean repairable,
                                 String summary, String logRef, String artifact) {
        List<QualityProblem> problems = category == null ? List.of() : List.of(
                new QualityProblem(category, repairable, null, null, null, summary, logRef));
        return new QualityReport(workspace.taskId(), workspace.appId(), round, start, Instant.now(),
                List.of(new QualityCheckItem("build", true, status, Duration.between(start, Instant.now()).toMillis(), problems)), artifact);
    }
}
