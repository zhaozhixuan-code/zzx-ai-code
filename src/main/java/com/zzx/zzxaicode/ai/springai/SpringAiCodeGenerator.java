package com.zzx.zzxaicode.ai.springai;

import cn.hutool.json.JSONUtil;
import com.zzx.zzxaicode.ai.common.AiCodeGenerator;
import com.zzx.zzxaicode.ai.common.PromptSafetyValidator;
import com.zzx.zzxaicode.config.springai.SpringAiProperties;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import com.zzx.zzxaicode.monitor.MonitorContext;
import com.zzx.zzxaicode.monitor.MonitorContextHolder;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Path;
import java.time.Duration;

/** Spring AI 主业务适配，手动工具循环保留请求/执行事件及明确的错误传播。 */
@Service
public class SpringAiCodeGenerator implements AiCodeGenerator {
    private final ChatModel generationModel;
    private final ChatModel vueModel;
    private final SpringAiPromptLoader prompts;
    private final SpringAiConversationRepository conversations;
    private final SpringAiToolCallbacks callbacks;
    private final SpringAiProperties properties;
    /** 仅在当前 JVM 内互斥同一应用的生成请求，不提供跨实例分布式互斥。 */
    private final Set<Long> runningApps = ConcurrentHashMap.newKeySet();

    /**
     * 注入生成依赖，模型角色通过显式限定名与旧 Bean 区分。
     * @param generationModel 普通生成模型
     * @param vueModel Vue 工具模型
     * @param prompts 系统提示词
     * @param conversations 应用隔离上下文
     * @param callbacks 已绑定 appId 的工具
     * @param properties 超时及工具循环上限
     */
    public SpringAiCodeGenerator(@Qualifier("springAiGenerationModel") ChatModel generationModel,
                                  @Qualifier("springAiVueModel") ChatModel vueModel,
                                  SpringAiPromptLoader prompts, SpringAiConversationRepository conversations,
                                  SpringAiToolCallbacks callbacks, SpringAiProperties properties) {
        this.generationModel = generationModel;
        this.vueModel = vueModel;
        this.prompts = prompts;
        this.conversations = conversations;
        this.callbacks = callbacks;
        this.properties = properties;
    }

    /**
     * 同步等待普通模式的同一生成管线，不建立第二份上下文。
     * @param appId 应用 ID
     * @param message 本轮需求
     * @param type HTML 或多文件类型
     * @return 完整生成文本
     * @throws BusinessException Vue 类型必须改用流式工具入口
     */
    @Override
    public String generate(long appId, String message, CodeGenTypeEnum type) {
        if (type == CodeGenTypeEnum.VUE_PROJECT) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Vue 项目需要使用流式工具生成");
        }
        return stream(appId, message, type).collectList().map(parts -> String.join("", parts))
                .block(properties.getTimeout().plusSeconds(10));
    }

    /**
     * 订阅时加载历史，成功完成后保存完整模型上下文；取消和失败不保存半轮消息。
     * @param appId 当前应用 ID
     * @param message 本轮需求
     * @param type 生成类型
     * @return 普通文本片段或既有 JSON 事件协议
     * @throws BusinessException 输入未通过安全校验；订阅后的生成失败通过 error 信号传播
     */
    @Override
    public Flux<String> stream(long appId, String message, CodeGenTypeEnum type) {
        PromptSafetyValidator.validate(message);
        // 服务层会在组装完流后清理 ThreadLocal，用户标签必须在订阅切线程前取出。
        MonitorContext context = MonitorContextHolder.getContext();
        String userId = context == null || context.getUserId() == null ? "unknown" : context.getUserId();
        return Flux.defer(() -> {
            // 每次订阅才获取生成资格；失败的竞争请求不进入释放逻辑，不能释放别人的资格。
            if (!runningApps.add(appId)) {
                return Flux.error(new BusinessException(ErrorCode.OPERATION_ERROR, "当前应用正在生成，请稍后重试"));
            }
            return Mono.fromCallable(() -> conversations.load(appId, message))
                    .flatMapMany(history -> {
                        Flux<String> result = type == CodeGenTypeEnum.VUE_PROJECT
                                ? round(appId, userId, history, callbacks.forApp(appId), 0)
                                : response(generationModel, prompt(type, history, options(appId, userId, List.of())), history, false);
                        // concatWith 仅在模型流正常完成后执行；错误或取消不会写入半轮模型消息。
                        // 此时门面尚未进行普通模式落盘或 Vue 构建，保存不代表产物已经可预览。
                        return result.concatWith(Mono.fromRunnable(() -> conversations.save(appId, history)).thenMany(Flux.empty()));
                    })
                    .doFinally(signal -> runningApps.remove(appId));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 打开任务私有会话；调用者必须已获得完整任务租约，不能与普通 Vue 生成并发。
     * @param appId 已鉴权的应用
     * @param userId 已鉴权的用户监控标签
     * @param requirement 原始用户需求
     * @param workspace 服务端任务源码根目录
     * @return 不会自动保存的会话；成功发布时才调用 save
     */
    public WorkspaceSession openWorkspaceSession(long appId, String userId, String requirement, Path workspace) {
        PromptSafetyValidator.validate(requirement);
        return new WorkspaceSession(appId, userId, conversations.load(appId, requirement), workspace);
    }

    /** 任务独占模型上下文及文件工具执行门，执行资源不进入 Graph 状态。 */
    public final class WorkspaceSession {
        private final long appId;
        private final String userId;
        private final List<Message> history;
        private final List<Message> previousHistory;
        private final Path workspace;
        private final Object fileGate = new Object();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /** @param appId 应用 @param userId 用户 @param history 私有消息列表 @param workspace 任务目录 */
        private WorkspaceSession(long appId, String userId, List<Message> history, Path workspace) {
            this.appId = appId;
            this.userId = userId;
            this.history = history;
            this.previousHistory = List.copyOf(history.subList(0, Math.max(0, history.size()-1)));
            this.workspace = workspace;
        }

        /**
         * 执行一次生成或定向修复，复用同一个手动工具循环及协议聚合器。
         * @param repairPrompt null 表示首次生成，非空表示追加的服务端修复诊断
         * @param maxRounds 本次模型请求预算，包含最终续答
         * @param timeout 本阶段整体超时，不是相邻片段超时
         * @return 既有正文与工具事件；失败/取消不保存上下文
         */
        public Flux<String> stream(String repairPrompt, int maxRounds, Duration timeout) {
            return Flux.defer(() -> {
                if (cancelled.get()) return Flux.error(new java.util.concurrent.CancellationException());
                if (repairPrompt != null) history.add(new UserMessage(repairPrompt));
                Map<String, ToolCallback> guarded = new LinkedHashMap<>();
                callbacks.forWorkspace(workspace, repairPrompt != null).forEach((name, delegate) -> guarded.put(name,
                        new ToolCallback() {
                            /** @return 已绑定任务的工具参数声明 */
                            @Override
                            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                                return delegate.getToolDefinition();
                            }

                            /** @param input 模型实参 @return 实际文件结果；取消后禁止继续写入 */
                            @Override
                            public String call(String input) {
                                synchronized (fileGate) {
                                    if (cancelled.get()) throw new java.util.concurrent.CancellationException();
                                    return delegate.call(input);
                                }
                            }
                        }));
                return round(appId, userId, history, guarded, 0, maxRounds)
                        .takeUntilOther(Mono.delay(timeout).flatMap(ignored ->
                                Mono.error(new java.util.concurrent.TimeoutException("模型阶段超时"))));
            }).subscribeOn(Schedulers.boundedElastic());
        }

        /** 保存最终通过的上下文；调用者负责与报告和产物确认顺序协调。 */
        public void save() {
            conversations.save(appId, history);
        }

        /** 产物或报告确认失败时恢复任务开始前的模型消息，避免上下文描述未发布的代码。 */
        public void restorePreviousContext() {
            conversations.save(appId, previousHistory);
        }

        /** 设置取消标记并等待实际文件操作返回，然后才允许任务租约释放。 */
        public void cancelAndAwaitFiles() {
            cancelled.set(true);
            synchronized (fileGate) { /* 等待本任务的同步文件操作停止。 */ }
        }
    }

    /**
     * 共用一次模型请求的流式处理，完整响应由框架聚合，通过结束校验后才追加 assistant。
     * @param model 普通生成或 Vue 工具模型
     * @param prompt 已包含系统提示词、历史和本轮工具声明的请求
     * @param history 本轮可修改的模型消息列表，不含系统提示词
     * @param json Vue 模式为 true，返回项目 JSON 事件；普通模式为 false，返回原始文本
     * @return 实时片段；取消、网络错误或不完整结束时不追加 assistant
     */
    private Flux<String> response(ChatModel model, Prompt prompt, List<Message> history, boolean json) {
        var state = new RoundState();
        Flux<ChatResponse> fragments = model.stream(prompt)
                .timeout(properties.getTimeout())
                .publishOn(Schedulers.boundedElastic());
        // 聚合器保留正文与消息元数据，同时照常转发原始片段，不等待全文才向页面输出。
        return new MessageAggregator().aggregate(fragments, aggregated -> state.response = aggregated)
                .concatMap(fragment -> Flux.fromIterable(state.accept(fragment, json)))
                .concatWith(Flux.defer(() -> {
                    state.validateFinished();
                    if (!json && !state.calls.isEmpty()) {
                        return Flux.error(new BusinessException(ErrorCode.OPERATION_ERROR, "普通生成返回了未声明工具"));
                    }
                    AssistantMessage output = state.response.getResult().getOutput();
                    // 聚合器按片段收集工具调用；这里按 ID 保留一次，避免重复执行或重复写入协议。
                    history.add(AssistantMessage.builder().content(output.getText()).properties(output.getMetadata())
                            .toolCalls(new ArrayList<>(state.calls.values())).build());
                    return Flux.empty();
                }));
    }

    /**
     * 显式执行一轮 Vue 请求；工具结果全部回填后才发下一轮，保持协议配对。
     * @param appId 应用 ID
     * @param userId 监控用户 ID
     * @param history 当前完整消息列表
     * @param tools 本应用工具表
     * @param round 已完成的模型请求数，最终无工具续答也占用一次请求额度
     * @return 文本、工具请求、工具执行事件及最终续答
     * @throws BusinessException 模型请求超过上限或模型返回本轮未声明的工具，异常通过流传播
     */
    private Flux<String> round(long appId, String userId, List<Message> history,
                                Map<String, ToolCallback> tools, int round) {
        return round(appId, userId, history, tools, round, properties.getMaxToolRounds());
    }

    /**
     * 按任务预算执行一轮模型请求，业务修复预算不参与此计数。
     * @param appId 应用 @param userId 用户 @param history 私有消息
     * @param tools 本轮工具 @param round 已发起请求数 @param maxRounds 本阶段请求上限
     * @return 模型正文与工具事件
     */
    private Flux<String> round(long appId, String userId, List<Message> history,
                                Map<String, ToolCallback> tools, int round, int maxRounds) {
        if (round >= maxRounds) {
            return Flux.error(new BusinessException(ErrorCode.OPERATION_ERROR, "工具调用次数超过上限"));
        }
        return response(vueModel, prompt(CodeGenTypeEnum.VUE_PROJECT, history,
                        options(appId, userId, new ArrayList<>(tools.values()))), history, true)
                .concatWith(Flux.defer(() -> {
                    // 共用响应流程已经追加完整 assistant，再以相同 ID 回填工具结果。
                    List<AssistantMessage.ToolCall> calls = ((AssistantMessage) history.getLast()).getToolCalls();
                    if (calls.isEmpty()) {
                        return Flux.empty();
                    }
                    List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
                    // response() 已切到 boundedElastic；直接顺序执行文件工具，不再为每个工具重复调度。
                    return Flux.fromIterable(calls).map(call -> {
                        ToolCallback callback = tools.get(call.name());
                        // 未声明工具是协议错误，不能伪装成已执行结果交回模型继续循环。
                        if (callback == null) {
                            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型返回了未声明的工具：" + call.name());
                        }
                        String result = callback.call(call.arguments());
                        results.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
                        return toolEvent("tool_executed", call, result);
                    })
                            .concatWith(Flux.defer(() -> {
                                history.add(ToolResponseMessage.builder().responses(results).build());
                                // exit 之后仍请求一次模型续答，但不再向它提供工具声明。
                                Map<String, ToolCallback> nextTools = calls.stream().anyMatch(call -> "exit".equals(call.name()))
                                        ? Map.of() : tools;
                                return round(appId, userId, history, nextTools, round + 1, maxRounds);
                            }));
                }));
    }

    /**
     * 添加系统角色，保留磁盘提示词原文。
     * @param type 生成类型
     * @param history 不含系统消息的历史
     * @param options 单次工具及监控上下文
     * @return Spring AI 内部请求
     */
    private Prompt prompt(CodeGenTypeEnum type, List<Message> history, OpenAiChatOptions options) {
        List<Message> messages = new ArrayList<>();
        // 系统提示词每次请求从当前资源加载，不随用户轮次重复写入 Redis 上下文。
        messages.add(new SystemMessage(prompts.forType(type)));
        messages.addAll(history);
        return new Prompt(messages, options);
    }

    /**
     * 使用工具上下文传递监控标签，不把 appId 置于模型可编辑的参数中。
     * @param appId 应用 ID
     * @param userId 用户 ID
     * @param tools 当前可用工具，无工具时为空列表
     * @return 模型请求选项
     */
    private OpenAiChatOptions options(long appId, String userId, List<ToolCallback> tools) {
        return OpenAiChatOptions.builder().internalToolExecutionEnabled(false).toolCallbacks(tools)
                .toolContext(Map.of("appId", String.valueOf(appId), "userId", userId)).build();
    }

    /**
     * 转换成既有工具事件结构。
     * @param type tool_request 或 tool_executed
     * @param call 完整工具调用
     * @param result 执行结果，请求事件为 null
     * @return JSON 事件字符串
     */
    private static String toolEvent(String type, AssistantMessage.ToolCall call, String result) {
        return JSONUtil.createObj().set("type", type).set("id", call.id()).set("name", call.name())
                .set("arguments", call.arguments()).set("result", result).toString();
    }

    /** 单次网络响应状态，仅在单个订阅内部使用。 */
    private static final class RoundState {
        /** 框架在正常完成时提供的完整响应；取消或错误时不会赋值。 */
        private ChatResponse response;
        /** 按模型交付顺序保存完整工具调用，同一 ID 只记录一次。 */
        private final Map<String, AssistantMessage.ToolCall> calls = new LinkedHashMap<>();
        /** 最近收到的非空结束原因；最后的 usage 片段不能把它清空。 */
        private String finishReason;

        /**
         * 收集文本与已聚合工具调用；Spring AI OpenAI 1.1.2 在 API 层聚合工具参数片段。
         * @param response 当前响应片段
         * @param json 是否转换成内部 JSON 事件
         * @return 该片段对应的零个或多个项目事件
         */
        private List<String> accept(ChatResponse response, boolean json) {
            List<String> events = new ArrayList<>();
            // 仅携带 usage 等信息的片段可以没有 generation，不产生页面事件。
            if (response.getResult() == null) {
                return events;
            }
            var generation = response.getResult();
            String finish = generation.getMetadata().getFinishReason();
            if (finish != null && !finish.isBlank()) {
                finishReason = finish;
            }
            AssistantMessage output = generation.getOutput();
            String content = output.getText();
            if (content != null && !content.isEmpty()) {
                events.add(json ? JSONUtil.createObj().set("type", "ai_response").set("data", content).toString() : content);
            }
            for (var call : output.getToolCalls()) {
                if (call.id() == null || call.id().isBlank() || call.name() == null || call.name().isBlank()) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型返回了缺少标识或名称的工具调用");
                }
                // Spring AI 已聚合工具参数；按 ID 去重，防止后续片段重复触发工具请求展示。
                if (calls.putIfAbsent(call.id(), call) == null && json) {
                    events.add(toolEvent("tool_request", call, null));
                }
            }
            return events;
        }

        /**
         * 只有正常 stop/tool_calls 才允许进入保存或下一轮，提前断流不能显示成功。
         * @throws BusinessException 缺少结束原因、token 耗尽或模型拒绝
         */
        private void validateFinished() {
            if (!("STOP".equalsIgnoreCase(finishReason) || "TOOL_CALLS".equalsIgnoreCase(finishReason))
                    || (response.getResult().getOutput().getText().isEmpty() && calls.isEmpty())) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型未正常完成生成：" + finishReason);
            }
        }
    }
}
