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

/** Spring AI 主业务适配，手动工具循环保留请求/执行事件及明确的错误传播。 */
@Service
public class SpringAiCodeGenerator implements AiCodeGenerator {
    private final ChatModel generationModel;
    private final ChatModel vueModel;
    private final SpringAiPromptLoader prompts;
    private final SpringAiConversationRepository conversations;
    private final SpringAiToolCallbacks callbacks;
    private final SpringAiProperties properties;
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
     */
    @Override
    public Flux<String> stream(long appId, String message, CodeGenTypeEnum type) {
        PromptSafetyValidator.validate(message);
        MonitorContext context = MonitorContextHolder.getContext();
        String userId = context == null || context.getUserId() == null ? "unknown" : context.getUserId();
        return Flux.defer(() -> {
            if (!runningApps.add(appId)) {
                return Flux.error(new BusinessException(ErrorCode.OPERATION_ERROR, "当前应用正在生成，请稍后重试"));
            }
            return Mono.fromCallable(() -> conversations.load(appId, message))
                    .flatMapMany(history -> {
                        Flux<String> result = type == CodeGenTypeEnum.VUE_PROJECT
                                ? round(appId, userId, history, callbacks.forApp(appId), 0)
                                : text(appId, userId, type, history);
                        return result.concatWith(Mono.fromRunnable(() -> conversations.save(appId, history)).thenMany(Flux.empty()));
                    })
                    .doFinally(signal -> runningApps.remove(appId));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 普通模式按片段转发并在正常结束时把 assistant 文本加入模型上下文。
     * @param appId 当前应用 ID
     * @param userId 监控用户 ID
     * @param type 普通生成类型
     * @param history 本轮模型消息
     * @return 文本片段
     */
    private Flux<String> text(long appId, String userId, CodeGenTypeEnum type, List<Message> history) {
        var state = new RoundState();
        return generationModel.stream(prompt(type, history, options(appId, userId, List.of())))
                .timeout(properties.getTimeout())
                .publishOn(Schedulers.boundedElastic())
                .concatMap(response -> Flux.fromIterable(state.accept(response, false)))
                .concatWith(Flux.defer(() -> {
                    state.validateFinished();
                    if (!state.calls.isEmpty()) {
                        return Flux.error(new BusinessException(ErrorCode.OPERATION_ERROR, "普通生成返回了未声明工具"));
                    }
                    history.add(new AssistantMessage(state.text.toString()));
                    return Flux.empty();
                }));
    }

    /**
     * 显式执行一轮 Vue 请求；工具结果全部回填后才发下一轮，保持协议配对。
     * @param appId 应用 ID
     * @param userId 监控用户 ID
     * @param history 当前完整消息列表
     * @param tools 本应用工具表
     * @param round 已执行工具轮数
     * @return 文本、工具请求、工具执行事件及最终续答
     */
    private Flux<String> round(long appId, String userId, List<Message> history,
                                Map<String, ToolCallback> tools, int round) {
        if (round >= properties.getMaxToolRounds()) {
            return Flux.error(new BusinessException(ErrorCode.OPERATION_ERROR, "工具调用次数超过上限"));
        }
        var state = new RoundState();
        return vueModel.stream(prompt(CodeGenTypeEnum.VUE_PROJECT, history,
                        options(appId, userId, new ArrayList<>(tools.values()))))
                .timeout(properties.getTimeout())
                .publishOn(Schedulers.boundedElastic())
                .concatMap(response -> Flux.fromIterable(state.accept(response, true)))
                .concatWith(Flux.defer(() -> {
                    state.validateFinished();
                    List<AssistantMessage.ToolCall> calls = new ArrayList<>(state.calls.values());
                    history.add(AssistantMessage.builder().content(state.text.toString()).toolCalls(calls).build());
                    if (calls.isEmpty()) {
                        return Flux.empty();
                    }
                    List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
                    return Flux.fromIterable(calls).concatMap(call -> Mono.fromCallable(() -> {
                        ToolCallback callback = tools.get(call.name());
                        String result = callback == null ? "错误：不存在工具 " + call.name() : callback.call(call.arguments());
                        results.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
                        return toolEvent("tool_executed", call, result);
                    }).subscribeOn(Schedulers.boundedElastic()))
                            .concatWith(Flux.defer(() -> {
                                history.add(ToolResponseMessage.builder().responses(results).build());
                                Map<String, ToolCallback> nextTools = calls.stream().anyMatch(call -> "exit".equals(call.name()))
                                        ? Map.of() : tools;
                                return round(appId, userId, history, nextTools, round + 1);
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
        private final StringBuilder text = new StringBuilder();
        private final Map<String, AssistantMessage.ToolCall> calls = new LinkedHashMap<>();
        private String finishReason;

        /**
         * 收集文本与已聚合工具调用；Spring AI OpenAI 1.1.2 在 API 层聚合工具参数片段。
         * @param response 当前响应片段
         * @param json 是否转换成内部 JSON 事件
         * @return 该片段对应的零个或多个项目事件
         */
        private List<String> accept(ChatResponse response, boolean json) {
            List<String> events = new ArrayList<>();
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
                text.append(content);
                events.add(json ? JSONUtil.createObj().set("type", "ai_response").set("data", content).toString() : content);
            }
            for (var call : output.getToolCalls()) {
                if (call.id() == null || call.id().isBlank() || call.name() == null || call.name().isBlank()) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型返回了缺少标识或名称的工具调用");
                }
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
                    || (text.isEmpty() && calls.isEmpty())) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型未正常完成生成：" + finishReason);
            }
        }
    }
}
