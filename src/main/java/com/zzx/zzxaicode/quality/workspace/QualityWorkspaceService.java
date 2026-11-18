package com.zzx.zzxaicode.quality.workspace;

import com.zzx.zzxaicode.constants.AppConstant;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.UUID;

/** 任务源码及产物隔离；失败目录保留，预览只解析原子替换的成功指针。 */
@Component
public class QualityWorkspaceService {
    private static final Set<String> EXCLUDED = Set.of("node_modules", "dist", ".git", ".quality", ".env");
    private final Path output;

    /** 使用既有预览根目录，内部任务目录不直接作为公开静态资源。 */
    public QualityWorkspaceService() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR));
    }

    /** @param output 测试或服务器指定的应用输出根目录 */
    public QualityWorkspaceService(Path output) {
        this.output = output.toAbsolutePath().normalize();
    }

    /**
     * 从上一成功源码准备任务，跳过依赖、旧产物和环境文件，拒绝文件系统链接。
     * @param appId 已获得独占资格的应用
     * @param taskId 服务端生成的 UUID
     * @return 本轮独立工作区
     */
    public Workspace prepare(long appId, String taskId) throws IOException {
        Workspace workspace = workspace(appId, taskId);
        Path previous = publishedSource(appId);
        requireNoLink(workspace.source());
        Files.createDirectories(workspace.source());
        if (Files.isDirectory(previous)) {
            Files.walkFileTree(previous, new SimpleFileVisitor<>() {
                /** 拷贝源目录结构；不进入依赖、产物或链接目录。 */
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(previous) && (EXCLUDED.contains(dir.getFileName().toString())
                            || dir.getFileName().toString().startsWith("quality-"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    requireNoLink(dir);
                    Files.createDirectories(workspace.source().resolve(previous.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                /** 拷贝普通源码文件，异常明确中止准备，不发布部分拷贝。 */
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    requireNoLink(file);
                    if (attrs.isRegularFile() && !EXCLUDED.contains(file.getFileName().toString())
                            && !file.getFileName().toString().startsWith(".env")
                            && !file.getFileName().toString().startsWith("quality-")) {
                        Files.copy(file, workspace.source().resolve(previous.relativize(file)));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return workspace;
    }

    /**
     * 返回当前确认源码；尚无质量任务成功时读取原有工程作为迁移输入。
     * @param appId 应用 ID
     * @return 当前可读工程路径
     */
    public Path publishedSource(long appId) throws IOException {
        Path pointer = appRoot(appId).resolve("published");
        if (Files.isRegularFile(pointer)) {
            requireNoLink(pointer);
            String id = Files.readString(pointer).trim();
            if (!id.isEmpty()) return workspace(appId, id).source();
        }
        return output.resolve("vue_project_" + appId);
    }

    /**
     * 原子切换成功产物指针；调用者先保存报告，再调用此方法，失败不能宣告成功。
     * @param workspace 已通过检查的工作区
     * @return 上一指针值，供后续终态持久化失败时补偿；空串表示旧工程
     */
    public String publish(Workspace workspace) throws IOException {
        if (!Files.isRegularFile(workspace.source().resolve("dist/index.html"))) {
            throw new IOException("本轮构建产物缺失");
        }
        Path pointer = appRoot(workspace.appId()).resolve("published");
        String previous = Files.isRegularFile(pointer) ? Files.readString(pointer) : "";
        setPublished(workspace.appId(), workspace.taskId());
        return previous;
    }

    /**
     * 写入或补偿成功指针；不覆盖源码，不删除目录，不接受客户端给出的路径。
     * @param appId 应用 ID
     * @param taskId 服务端 UUID 或空串（回到原有工程）
     */
    public void setPublished(long appId, String taskId) throws IOException {
        if (!taskId.isEmpty()) UUID.fromString(taskId);
        Path root = appRoot(appId);
        Files.createDirectories(root);
        requireNoLink(root);
        Path temp = root.resolve("pointer-" + UUID.randomUUID() + ".tmp");
        Files.writeString(temp, taskId, StandardOpenOption.CREATE_NEW);
        // 不支持原子替换的文件系统明确失败，避免读到半写入指针。
        Files.move(temp, root.resolve("published"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** @param appId 应用 ID @param taskId 服务端 UUID @return 确定的任务工作区 */
    public Workspace workspace(long appId, String taskId) {
        if (!UUID.fromString(taskId).toString().equals(taskId)) throw new IllegalArgumentException("任务标识无效");
        return new Workspace(appId, taskId, appRoot(appId).resolve("tasks").resolve(taskId).resolve("source"));
    }

    /** @param appId 正整数应用 ID @return 内部应用目录 */
    private Path appRoot(long appId) {
        if (appId <= 0) throw new IllegalArgumentException("应用标识无效");
        return output.resolve(".quality/apps/" + appId);
    }

    /** @param path 已存在或将创建的路径 @throws IOException 任一级祖先为链接或特殊文件 */
    public static void requireNoLink(Path path) throws IOException {
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor) || (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther())) {
                throw new IOException("工作区不允许链接路径");
            }
        }
    }

    /** @param appId 应用 ID @param taskId 任务 UUID @param source 服务端源码根目录 */
    public record Workspace(long appId, String taskId, Path source) { }
}
