package com.zzx.zzxaicode.quality;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.zzxaicode.quality.check.*;
import com.zzx.zzxaicode.quality.config.QualityCheckProperties;
import com.zzx.zzxaicode.quality.model.*;
import com.zzx.zzxaicode.quality.workspace.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** 显式启用的真实容器验证；Docker 不可用必须标记跳过，不以模拟结果代替。 */
@EnabledIfSystemProperty(named="quality.live-docker",matches="true")
class QualityLiveBuildTest {
    @TempDir Path root;
    /** 固定依赖的正常 Vue 工程通过；真实语法/导入故障产生可修复诊断且不会复用旧产物。 */
    @Test void buildsAndDiagnosesActualVueFixture() throws Exception {
        var settings=new QualityCheckProperties();var runner=new ControlledProcessRunner();
        var checker=new BuildQualityChecker(runner,settings,new ObjectMapper());
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("package.json"),"{\"type\":\"module\",\"dependencies\":{\"vue\":\"3.5.17\",\"vite\":\"7.0.4\",\"@vitejs/plugin-vue\":\"6.0.0\"}}");
        Files.writeString(root.resolve("index.html"),"<div id='app'></div><script type='module' src='/src/main.js'></script>");
        Files.writeString(root.resolve("vite.config.js"),"import {defineConfig} from 'vite';import vue from '@vitejs/plugin-vue';export default defineConfig({plugins:[vue()]});");
        Files.writeString(root.resolve("src/main.js"),"import {createApp} from 'vue';import App from './App.vue';createApp(App).mount('#app');");
        Files.writeString(root.resolve("src/App.vue"),"<template><h1>计数器</h1></template>");
        var workspace=new QualityWorkspaceService.Workspace(1,UUID.randomUUID().toString(),root);
        var available=runner.run(List.of("docker","info","--format","{{.ServerVersion}}"),root,Duration.ofSeconds(15),new AtomicBoolean());
        if(available.exitCode()!=0 || available.timedOut()) {
            var unavailable=checker.check(workspace,0,new AtomicBoolean(),new AtomicBoolean(true));
            assertFalse(unavailable.isPassed());
            assertEquals(QualityProblemCategory.ENVIRONMENT,unavailable.items().getFirst().problems().getFirst().category());
            Assumptions.assumeTrue(false,"Docker 守护进程不可用；已实测环境错误分类，未执行真实构建验收");
        }
        assertTrue(checker.check(workspace,0,new AtomicBoolean(),new AtomicBoolean(true)).isPassed());
        Files.writeString(root.resolve("src/App.vue"),"<template><h1>未闭合</template>");
        var syntax=checker.check(workspace,1,new AtomicBoolean(),new AtomicBoolean(true));
        assertFalse(syntax.isPassed());assertTrue(syntax.items().getFirst().problems().getFirst().repairable());
        Files.writeString(root.resolve("src/App.vue"),"<script setup>import Missing from './Missing.vue'</script><template><Missing/></template>");
        var missing=checker.check(workspace,2,new AtomicBoolean(),new AtomicBoolean(true));
        assertFalse(missing.isPassed());assertTrue(missing.items().getFirst().problems().getFirst().summary().contains("Missing"));
    }
}
