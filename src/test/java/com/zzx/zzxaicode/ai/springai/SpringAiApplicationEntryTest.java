package com.zzx.zzxaicode.ai.springai;

import com.zzx.zzxaicode.ai.common.AiCodeGenerator;
import com.zzx.zzxaicode.config.springai.SpringAiProperties;
import com.zzx.zzxaicode.constants.UserConstant;
import com.zzx.zzxaicode.controller.AppController;
import com.zzx.zzxaicode.core.AiCodeGeneratorFacade;
import com.zzx.zzxaicode.core.builder.VueProjectBuilder;
import com.zzx.zzxaicode.core.handler.JsonMessageStreamHandler;
import com.zzx.zzxaicode.core.handler.StreamHandlerExecutor;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import com.zzx.zzxaicode.model.po.App;
import com.zzx.zzxaicode.model.po.User;
import com.zzx.zzxaicode.monitor.MonitorContextHolder;
import com.zzx.zzxaicode.service.ChatHistoryService;
import com.zzx.zzxaicode.service.UserService;
import com.zzx.zzxaicode.service.impl.AppServiceImpl;
import com.zzx.zzxaicode.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 不连接数据库或模型端点，贯通原应用入口的权限、事件完成顺序及跨线程监控。 */
class SpringAiApplicationEntryTest {
    /** 各用例结束后释放调用线程的上下文，避免失败断言影响其他测试。 */
    @AfterEach
    void clearMonitorContext() {
        MonitorContextHolder.clearContext();
    }

    /** 未登录在用户服务内拒绝，跨用户在应用服务内拒绝，均不能写历史或调用生成器。 */
    @Test
    void unauthenticatedAndOtherUsersCannotGenerate() {
        var fixture = new Fixture();
        ReflectionTestUtils.setField(fixture.controller, "userService", new UserServiceImpl());
        BusinessException unauthenticated = assertThrows(BusinessException.class,
                () -> fixture.controller.chatToGenCode(fixture.appId, "page", new MockHttpServletRequest()));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), unauthenticated.getCode());
        verify(fixture.service, never()).chatToGenCode(anyLong(), anyString(), any());
        var other = new User();
        other.setId(2L);
        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> fixture.service.chatToGenCode(fixture.appId, "page", other));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), forbidden.getCode());
        verifyNoInteractions(fixture.history, fixture.generator);
    }

    /** 三种类型沿原 Controller/Service/门面/展示处理返回 d 包装，并且最后只有一个 done。 */
    @Test
    void allTypesKeepExistingSseEnvelopeAndDoneOrdering() {
        for (var type : CodeGenTypeEnum.values()) {
            var fixture = new Fixture();
            fixture.app.setCodeGenType(type.getValue());
            String output = switch (type) {
                case HTML -> "```html\n<h1>entry</h1>\n```";
                case MULTI_FILE -> "```html\n<h1>entry</h1>\n```\n```css\nh1{}\n```\n```js\nlet n=0\n```";
                case VUE_PROJECT -> "{\"type\":\"ai_response\",\"data\":\"Vue ready\"}";
            };
            when(fixture.generator.stream(fixture.appId, "page", type)).thenReturn(Flux.just(output));
            var events = fixture.controller.chatToGenCode(fixture.appId, "page", fixture.request()).collectList()
                    .block(Duration.ofSeconds(5));
            assertNotNull(events);
            assertEquals("done", events.getLast().event());
            assertEquals(1, events.stream().filter(event -> "done".equals(event.event())).count());
            assertTrue(events.getFirst().data().startsWith("{\"d\":"));
            verify(fixture.history).addChatMessage(fixture.appId, "page", "user", 1L);
            verify(fixture.history).addChatMessage(eq(fixture.appId), anyString(), eq("ai"), eq(1L));
            if (type == CodeGenTypeEnum.VUE_PROJECT) {
                verify(fixture.builder).buildProject(anyString());
            }
        }
    }

    /** 模型断流、解析失败及 Vue 构建失败都不能被控制器转换成 done 成功事件。 */
    @Test
    void modelParserAndBuildErrorsNeverEmitDone() {
        for (int scenario = 0; scenario < 3; scenario++) {
            var fixture = new Fixture();
            var type = scenario == 2 ? CodeGenTypeEnum.VUE_PROJECT : CodeGenTypeEnum.HTML;
            fixture.app.setCodeGenType(type.getValue());
            Flux<String> output = switch (scenario) {
                case 0 -> Flux.concat(Flux.just("partial"), Flux.error(new IllegalStateException("disconnect")));
                case 1 -> Flux.just("{}");
                default -> Flux.just("{\"type\":\"ai_response\",\"data\":\"ready\"}");
            };
            when(fixture.generator.stream(fixture.appId, "page", type)).thenReturn(output);
            when(fixture.builder.buildProject(anyString())).thenReturn(false);
            List<String> eventNames = new ArrayList<>();
            assertThrows(RuntimeException.class, () -> fixture.controller.chatToGenCode(fixture.appId, "page", fixture.request())
                    .doOnNext(event -> eventNames.add(event.event())).blockLast(Duration.ofSeconds(5)));
            assertFalse(eventNames.contains("done"));
            verify(fixture.history).addChatMessage(eq(fixture.appId), contains("失败"), eq("ai"), eq(1L));
        }
    }

    /** 原入口组装后清理调用线程；订阅切换到工作线程时模型仍收到真实用户与应用标签。 */
    @Test
    void monitorTagsSurviveDeferredSubscriptionWithoutLeakingCallerThread() {
        var fixture = new Fixture();
        var model = mock(ChatModel.class);
        var memory = mock(SpringAiConversationRepository.class);
        when(memory.load(anyLong(), anyString())).thenAnswer(invocation ->
                new ArrayList<Message>(List.of(new UserMessage(invocation.getArgument(1, String.class)))));
        AtomicReference<String> modelUser = new AtomicReference<>();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            var options = (ToolCallingChatOptions) invocation.getArgument(0, Prompt.class).getOptions();
            modelUser.set(String.valueOf(options.getToolContext().get("userId")));
            assertEquals(String.valueOf(fixture.appId), options.getToolContext().get("appId"));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("<h1>ok</h1>"),
                    ChatGenerationMetadata.builder().finishReason("STOP").build()))));
        });
        var realGenerator = new SpringAiCodeGenerator(model, model, new SpringAiPromptLoader(), memory,
                mock(SpringAiToolCallbacks.class), new SpringAiProperties());
        ReflectionTestUtils.setField(fixture.service, "aiCodeGeneratorFacade", new AiCodeGeneratorFacade(realGenerator, fixture.builder));
        var result = fixture.service.chatToGenCode(fixture.appId, "page", fixture.owner);
        assertNull(MonitorContextHolder.getContext());
        result.subscribeOn(Schedulers.boundedElastic()).blockLast(Duration.ofSeconds(5));
        assertEquals("1", modelUser.get());
    }

    /** 独立应用、真实业务层和持久层替身组成可重复的入口验证边界。 */
    private static final class Fixture {
        final long appId = System.nanoTime();
        final App app = new App();
        final User owner = new User();
        final AppServiceImpl service = spy(new AppServiceImpl());
        final AppController controller = new AppController();
        final ChatHistoryService history = mock(ChatHistoryService.class);
        final AiCodeGenerator generator = mock(AiCodeGenerator.class);
        final VueProjectBuilder builder = mock(VueProjectBuilder.class);

        /** 只替换持久化和外部能力，保留应用入口、权限判断、门面与展示处理。 */
        Fixture() {
            owner.setId(1L);
            app.setId(appId);
            app.setUserId(1L);
            app.setCodeGenType("html");
            doReturn(app).when(service).getById(appId);
            when(builder.buildProject(anyString())).thenReturn(true);
            var handlers = new StreamHandlerExecutor();
            ReflectionTestUtils.setField(handlers, "jsonMessageStreamHandler", new JsonMessageStreamHandler());
            ReflectionTestUtils.setField(service, "aiCodeGeneratorFacade", new AiCodeGeneratorFacade(generator, builder));
            ReflectionTestUtils.setField(service, "chatHistoryService", history);
            ReflectionTestUtils.setField(service, "streamHandlerExecutor", handlers);
            ReflectionTestUtils.setField(controller, "appService", service);
            var users = mock(UserService.class);
            when(users.getLoginUser(any())).thenReturn(owner);
            ReflectionTestUtils.setField(controller, "userService", users);
        }

        /** @return 含已有用户会话的独立 Servlet 请求；不启动 HTTP 服务。 */
        MockHttpServletRequest request() {
            var request = new MockHttpServletRequest();
            request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, owner);
            return request;
        }
    }
}
