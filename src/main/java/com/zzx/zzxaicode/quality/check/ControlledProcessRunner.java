package com.zzx.zzxaicode.quality.check;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 持续读取输出的受控进程执行器；命令仅由平台构造，输出保留末尾 64KiB。 */
@Component
public class ControlledProcessRunner {
    /**
     * 执行确定的参数列表，不拼接 shell 命令；超时或取消时终止实际进程树。
     * @param command 平台固定的命令及参数
     * @param directory 工作目录
     * @param timeout 此次调用最长持续时间
     * @param cancelled 任务取消标志
     * @return 退出码、耗时、是否超时及有界输出
     */
    public Result run(List<String> command, Path directory, Duration timeout, AtomicBoolean cancelled)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        if (cancelled.get()) throw new InterruptedException("检查已取消");
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        StringBuilder tail = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> drain(process, tail));
        boolean timedOut = false;
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("检查已取消");
                if (Duration.ofNanos(System.nanoTime() - start).compareTo(timeout) >= 0) {
                    timedOut = true;
                    break;
                }
            }
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
            if (process.isAlive()) {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("进程未能停止");
            }
            reader.join(5000);
            if (reader.isAlive()) process.getInputStream().close();
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        synchronized (tail) {
            return new Result(process.isAlive() ? -1 : process.exitValue(), timedOut,
                    Duration.ofNanos(System.nanoTime() - start).toMillis(), tail.toString());
        }
    }

    /** @param process 当前子进程 @param tail 固定上限的日志尾部 */
    private void drain(Process process, StringBuilder tail) {
        try (var input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int size;
            while ((size = input.read(buffer)) >= 0) {
                synchronized (tail) {
                    tail.append(buffer, 0, size);
                    if (tail.length() > 65536) tail.delete(0, tail.length() - 65536);
                }
            }
        } catch (IOException e) {
            synchronized (tail) { tail.append("\n[输出读取结束]"); }
        }
    }

    /** @param exitCode 实际退出码 @param timedOut 是否超时 @param durationMillis 耗时 @param output 有界日志 */
    public record Result(int exitCode, boolean timedOut, long durationMillis, String output) { }
}
