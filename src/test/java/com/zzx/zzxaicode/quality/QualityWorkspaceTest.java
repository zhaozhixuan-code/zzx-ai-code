package com.zzx.zzxaicode.quality;

import com.zzx.zzxaicode.ai.common.ProjectFileOperations;
import com.zzx.zzxaicode.quality.workspace.QualityAppLock;
import com.zzx.zzxaicode.quality.workspace.QualityWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 工作区隔离、原子发布和应用资格的实际文件验证。 */
class QualityWorkspaceTest {
    @TempDir Path root;

    /** 失败候选不覆盖上一预览；确认后才切换指针，补偿恢复原指针。 */
    @Test
    void preservesPublishedArtifactUntilConfirmation() throws Exception {
        var service = new QualityWorkspaceService(root);
        Path old = root.resolve("vue_project_1/dist");
        Files.createDirectories(old);
        Files.writeString(old.resolve("index.html"), "old");
        var task = service.prepare(1, UUID.randomUUID().toString());
        assertFalse(Files.exists(task.source().resolve("dist")));
        assertThrows(java.io.IOException.class, () -> service.publish(task));
        assertEquals("old", Files.readString(service.publishedSource(1).resolve("dist/index.html")));
        Files.createDirectories(task.source().resolve("dist"));
        Files.writeString(task.source().resolve("dist/index.html"), "new");
        String previous = service.publish(task);
        assertEquals(task.source(), service.publishedSource(1));
        service.setPublished(1, previous);
        assertEquals("old", Files.readString(service.publishedSource(1).resolve("dist/index.html")));
    }

    /** 模型越界路径及修复对构建配置的修改被服务端拒绝。 */
    @Test
    void restrictsTaskFileTools() {
        var files = new ProjectFileOperations(root);
        Path workspace = root.resolve("task/source");
        assertTrue(files.executeInWorkspace(workspace, "writeFile",
                Map.of("relativeFilePath", "../outside", "content", "bad"), false).startsWith("错误"));
        assertTrue(files.executeInWorkspace(workspace, "writeFile",
                Map.of("relativeFilePath", "package.json", "content", "{}"), true).startsWith("错误"));
        assertTrue(files.executeInWorkspace(workspace, "writeFile",
                Map.of("relativeFilePath", "src/main.ts", "content", "console.log('ok')"), true).contains("成功"));
    }

    /** 修复在 npm 安装后仍只列出源码，单个过大文件不能撑满模型工具上下文。 */
    @Test void boundsToolContextAfterDependencyInstall() throws Exception {
        var files=new ProjectFileOperations(root);
        Files.createDirectories(root.resolve("node_modules"));Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("node_modules/ignored.js"),"dependency");
        Files.writeString(root.resolve("src/App.vue"),"source");Files.writeString(root.resolve("quality-build-0.log"),"diagnostics");
        String listing=files.executeInWorkspace(root,"readDir",Map.of("relativeDirPath",""),true);
        assertTrue(listing.contains("App.vue"));assertFalse(listing.contains("ignored.js"));assertFalse(listing.contains("quality-build"));
        Files.writeString(root.resolve("src/large.js"),"a".repeat(256*1024+1));
        assertTrue(files.executeInWorkspace(root,"readFile",Map.of("relativeFilePath","src/large.js"),true).startsWith("错误"));
    }

    /** 失败竞争及重复关闭旧租约都不能释放后续任务持有的资格。 */
    @Test
    void onlyOwnerCanReleaseLease() {
        var lock = new QualityAppLock();
        var first = lock.acquire(1);
        assertThrows(RuntimeException.class, () -> lock.acquire(1));
        try (var otherApp = lock.acquire(2)) { assertNotNull(otherApp); }
        first.close();
        try (var second = lock.acquire(1)) {
            first.close();
            assertThrows(RuntimeException.class, () -> lock.acquire(1));
        }
    }
}
