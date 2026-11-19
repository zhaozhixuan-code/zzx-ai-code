package com.zzx.zzxaicode.quality;
import com.zzx.zzxaicode.quality.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 有效变更必须来自源码；日志和构建输出不能假装修复。 */
class QualityRepairSupportTest {
    @TempDir Path root;
    /** 验证无进展、修改清单、指令证据和配置保护边界。 */
    @Test void auditsOnlySourceChanges() throws Exception {
        var support=new QualityRepairSupport();
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.vue"),"broken");
        var before=support.snapshot(root);
        Instant now=Instant.now();
        var report=new QualityReport("task",1,0,now,now,List.of(new QualityCheckItem("build",true,
                QualityCheckItemStatus.FAILED,1,List.of(new QualityProblem(QualityProblemCategory.SYNTAX,true,
                "src/App.vue",1,null,"Unexpected token","build.log")))),null);
        Files.writeString(root.resolve("quality-build.log"),"different");
        assertFalse(support.audit(1,report,before,support.snapshot(root)).changed());
        Files.writeString(root.resolve("src/App.vue"),"<template>hello</template>");
        assertEquals(List.of("src/App.vue"),support.audit(1,report,before,support.snapshot(root)).changedFiles());
        assertTrue(support.prompt("原始功能",report).contains("Unexpected token"));
        assertTrue(support.prompt("原始功能",report).contains("原始功能"));
    }
}
