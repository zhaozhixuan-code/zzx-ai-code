package com.zzx.zzxaicode.ai.springai;

import com.zzx.zzxaicode.constants.AppConstant;
import com.zzx.zzxaicode.controller.StaticResourceController;
import com.zzx.zzxaicode.core.builder.VueProjectBuilder;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import com.zzx.zzxaicode.model.po.App;
import com.zzx.zzxaicode.model.po.User;
import com.zzx.zzxaicode.service.impl.AppServiceImpl;
import com.zzx.zzxaicode.service.impl.ProjectDownloadServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerMapping;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实文件与旧预览/下载/部署实现回归；数据库更新、构建和截图外部能力使用替身。 */
class SpringAiDeliveryBoundaryTest {
    /** 普通模式按原目录预览与复制，Vue 预览/部署只使用 dist，源码 ZIP 排除构建产物和依赖。 */
    @Test
    void allModesKeepPreviewDownloadAndLocalDeploymentPaths() throws Exception {
        for (var type : CodeGenTypeEnum.values()) {
            long id = System.nanoTime();
            String folder = type.getValue() + "_" + id;
            String sourcePath = AppConstant.CODE_OUTPUT_ROOT_DIR + java.io.File.separator + folder;
            Path source = Path.of(sourcePath);
            Files.createDirectories(source);
            Files.writeString(source.resolve("index.html"), "<h1>source</h1>");
            Files.writeString(source.resolve("style.css"), "h1{color:blue}");
            Files.writeString(source.resolve("script.js"), "let n=0");
            Files.createDirectories(source.resolve("node_modules"));
            Files.writeString(source.resolve("node_modules/ignored.js"), "dependency");
            Files.writeString(source.resolve(".env"), "TEST_ONLY=true");
            if (type == CodeGenTypeEnum.VUE_PROJECT) {
                Files.createDirectories(source.resolve("dist/assets"));
                Files.writeString(source.resolve("dist/index.html"), "<h1>built</h1>");
                Files.writeString(source.resolve("dist/assets/built.js"), "let built=true");
            }
            String resourcePath = type == CodeGenTypeEnum.VUE_PROJECT ? "/dist/index.html" : "/";
            var request = new MockHttpServletRequest("GET", "/api/static/" + folder + resourcePath);
            request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/static/" + folder + resourcePath);
            var response = new StaticResourceController().serveStaticResource(folder, request);
            assertEquals(200, response.getStatusCode().value());
            assertEquals("text/html; charset=UTF-8", response.getHeaders().getFirst("Content-Type"));
            assertEquals(type == CodeGenTypeEnum.VUE_PROJECT ? "<h1>built</h1>" : "<h1>source</h1>",
                    response.getBody().getContentAsString(StandardCharsets.UTF_8));

            var zipResponse = new MockHttpServletResponse();
            new ProjectDownloadServiceImpl().downloadProjectAsZip(source.toString(), "test", zipResponse);
            var zipEntries = new HashMap<String, String>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(zipResponse.getContentAsByteArray()))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    zipEntries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertEquals("<h1>source</h1>", zipEntries.get("index.html"));
            assertFalse(zipEntries.keySet().stream().anyMatch(name -> name.startsWith("node_modules")
                    || name.startsWith("dist") || name.equals(".env")));

            var service = spy(new AppServiceImpl());
            var app = new App();
            app.setId(id);
            app.setUserId(1L);
            app.setCodeGenType(type.getValue());
            app.setDeployKey("migration-test-" + id);
            var owner = new User();
            owner.setId(1L);
            doReturn(app).when(service).getById(id);
            doReturn(true).when(service).updateById(any(App.class));
            doNothing().when(service).generateAppScreenshot(anyLong(), anyString());
            var builder = mock(VueProjectBuilder.class);
            when(builder.buildProject(sourcePath)).thenReturn(true);
            ReflectionTestUtils.setField(service, "vueProjectBuilder", builder);
            assertEquals(AppConstant.CODE_DEPLOY_HOST + "/" + app.getDeployKey() + "/", service.deployApp(id, owner));
            Path deployed = Path.of(AppConstant.CODE_DEPLOY_ROOT_DIR, app.getDeployKey());
            assertEquals(type == CodeGenTypeEnum.VUE_PROJECT ? "<h1>built</h1>" : "<h1>source</h1>",
                    Files.readString(deployed.resolve("index.html")));
            if (type == CodeGenTypeEnum.VUE_PROJECT) {
                verify(builder).buildProject(sourcePath);
                assertTrue(Files.isRegularFile(deployed.resolve("assets/built.js")));
                assertFalse(Files.exists(deployed.resolve("node_modules")));
            } else {
                verifyNoInteractions(builder);
                assertEquals("let n=0", Files.readString(deployed.resolve("script.js")));
            }
        }
    }
}
