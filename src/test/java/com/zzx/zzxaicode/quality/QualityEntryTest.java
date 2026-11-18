package com.zzx.zzxaicode.quality;
import com.zzx.zzxaicode.controller.*;
import com.zzx.zzxaicode.core.handler.JsonMessageStreamHandler;
import com.zzx.zzxaicode.model.po.User;
import com.zzx.zzxaicode.quality.workspace.*;
import com.zzx.zzxaicode.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerMapping;
import reactor.core.publisher.Flux;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际 SSE 适配及静态文件边界，质量事件不混入正文，未发布任务不可访问。 */
class QualityEntryTest {
    @TempDir Path root;
    /** 质量事件独立命名；失败不 done，成功事件先于 done。 */
    @Test void separatesQualityEventsFromChatAndSseTerminal() {
        String quality=QualityWorkflowService.EVENT_PREFIX+"{\"taskId\":\"test\",\"status\":\"SUCCEEDED\"}";
        var history=mock(ChatHistoryService.class); User user=new User(); user.setId(2L);
        assertEquals(quality,new JsonMessageStreamHandler().handle(Flux.just(quality),history,1,user).blockLast());
        verify(history).addChatMessage(1L,"","ai",2L);
        var apps=mock(AppService.class); var users=mock(UserService.class); var request=new MockHttpServletRequest();
        when(users.getLoginUser(request)).thenReturn(user);
        var controller=new AppController();ReflectionTestUtils.setField(controller,"appService",apps);ReflectionTestUtils.setField(controller,"userService",users);
        when(apps.chatToGenCode(1L,"page",user)).thenReturn(Flux.just(quality));
        var events=controller.chatToGenCode(1L,"page",request).collectList().block();
        assertEquals("quality",events.getFirst().event());assertEquals("done",events.getLast().event());
        when(apps.chatToGenCode(1L,"page",user)).thenReturn(Flux.concat(Flux.just(quality),Flux.error(new IllegalStateException("failed"))));
        java.util.List<String> names=new java.util.ArrayList<>();
        assertThrows(IllegalStateException.class,()->controller.chatToGenCode(1L,"page",request).doOnNext(e->names.add(e.event())).blockLast());
        assertFalse(names.contains("done"));
    }
    /** 发布指针才使候选可见；内部根路径、路径穿越及源码不向公开静态接口开放。 */
    @Test void servesOnlyPublishedVueDist() throws Exception {
        var workspaces=new QualityWorkspaceService(root);
        var workspace=workspaces.prepare(1,UUID.randomUUID().toString());
        Files.createDirectories(workspace.source().resolve("dist"));Files.writeString(workspace.source().resolve("dist/index.html"),"published");
        var controller=new StaticResourceController(workspaces);
        assertEquals(404,serve(controller,"vue_project_1","/dist/index.html").getStatusCode().value());
        workspaces.publish(workspace);
        assertEquals("published",serve(controller,"vue_project_1","/dist/index.html").getBody().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(404,serve(controller,"vue_project_1","/package.json").getStatusCode().value());
        assertEquals(404,serve(controller,"vue_project_1","/dist/../../secret").getStatusCode().value());
        assertEquals(404,serve(controller,".quality","/apps/1/published").getStatusCode().value());
    }
    /** @param controller 静态接口 @param key 应用标识 @param path 请求路径 @return 资源响应 */
    private org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> serve(StaticResourceController controller,String key,String path) {
        var request=new MockHttpServletRequest("GET","/api/static/"+key+path);
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE,"/static/"+key+path);
        return controller.serveStaticResource(key,request);
    }
}
