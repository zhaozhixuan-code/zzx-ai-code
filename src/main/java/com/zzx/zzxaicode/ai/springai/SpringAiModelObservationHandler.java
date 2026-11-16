package com.zzx.zzxaicode.ai.springai;

import com.zzx.zzxaicode.monitor.AiModelMetricsCollector;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/** 消费 Spring AI Observation，沿用原业务指标名称与应用/用户标签。 */
@Component
public class SpringAiModelObservationHandler implements ObservationHandler<ChatModelObservationContext> {
    private static final String START = "app.ai.startedAt";
    private final AiModelMetricsCollector metrics;

    /**
     * 注入框架无关 Micrometer 指标收集器。
     * @param metrics 项目原有指标注册器
     */
    public SpringAiModelObservationHandler(AiModelMetricsCollector metrics) {
        this.metrics = metrics;
    }

    /**
     * 只处理模型请求观察事件。
     * @param context Observation 上下文
     * @return 是否为 Spring AI 聊天模型事件
     */
    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    /**
     * 从请求选项中读取跨线程监控标签。
     * @param context 当前模型请求观察上下文
     */
    @Override
    public void onStart(ChatModelObservationContext context) {
        context.put(START, System.nanoTime());
        var tags = tags(context);
        metrics.recordRequest(tags[0], tags[1], tags[2], "started");
    }

    /**
     * 记录成功、失败或取消，缺失 usage 不生成虚假的 token 指标。
     * @param context 已完成请求观察上下文
     */
    @Override
    public void onStop(ChatModelObservationContext context) {
        var tags = tags(context);
        String status = context.getError() != null ? "error" : context.getResponse() == null ? "cancelled" : "success";
        metrics.recordRequest(tags[0], tags[1], tags[2], status);
        Long started = context.get(START);
        if (started != null) {
            metrics.recordResponseTime(tags[0], tags[1], tags[2], Duration.ofNanos(System.nanoTime() - started));
        }
        if (context.getError() != null) {
            metrics.recordError(tags[0], tags[1], tags[2], context.getError().getClass().getSimpleName());
        }
        if (context.getResponse() != null && context.getResponse().getMetadata() != null) {
            var usage = context.getResponse().getMetadata().getUsage();
            // 使用原始字段判断是否缺失，不能使用可能把 null 转为 0 的便利 getter。
            if (usage != null && usage.getNativeUsage() instanceof OpenAiApi.Usage raw) {
                recordTokens(tags, "input", raw.promptTokens());
                recordTokens(tags, "output", raw.completionTokens());
                recordTokens(tags, "total", raw.totalTokens());
            }
        }
    }

    /**
     * 读取请求携带的标签，非业务调用使用 unknown。
     * @param context 模型观察上下文
     * @return 用户、应用、模型标签
     */
    private String[] tags(ChatModelObservationContext context) {
        var options = context.getRequest().getOptions();
        Map<String, Object> values = options instanceof ToolCallingChatOptions tools ? tools.getToolContext() : Map.of();
        if (values == null) {
            values = Map.of();
        }
        String model = options == null || options.getModel() == null ? "unknown" : options.getModel();
        return new String[]{String.valueOf(values.getOrDefault("userId", "unknown")),
                String.valueOf(values.getOrDefault("appId", "unknown")), model};
    }

    /**
     * 仅记录服务端实际提供的非负 token 数。
     * @param tags 用户、应用、模型标签
     * @param type input/output/total
     * @param count 可空的原始计数
     */
    private void recordTokens(String[] tags, String type, Integer count) {
        if (count != null && count >= 0) {
            metrics.recordTokenUsage(tags[0], tags[1], tags[2], type, count);
        }
    }
}
