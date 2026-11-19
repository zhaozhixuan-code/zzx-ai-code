package com.zzx.zzxaicode.quality.graph;

import com.alibaba.cloud.ai.graph.StateGraph;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 用实际 Graph 和受控假模型验证片段实时性、订阅次数、异常及取消传播。 */
class GraphEventBridgeTest {
    /** 生成节点尚未完成时已收到正文和工具事件，且每次请求只订阅一次模型。 */
    @Test
    void forwardsBeforeNodeCompletionAndCancelsModel() throws Exception {
        Sinks.Many<String> model = Sinks.many().unicast().onBackpressureBuffer();
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        Flux<String> stream = GraphEventBridge.stream(execution -> {
            try {
                return new StateGraph(QualityGraphStateContract.keyStrategyFactory())
                        .addNode("generate", state -> execution.forward(model.asFlux()
                                .doOnSubscribe(s -> subscriptions.incrementAndGet())
                                .doOnCancel(() -> cancelled.set(true)), Map.of("stage", "done")))
                        .addEdge(StateGraph.START, "generate").addEdge("generate", StateGraph.END)
                        .compile().stream(Map.of());
            } catch (com.alibaba.cloud.ai.graph.exception.GraphStateException e) {
                return Flux.error(e);
            }
        });
        List<String> received = new CopyOnWriteArrayList<>();
        var subscription = stream.subscribe(received::add, error -> fail(error), () -> completed.set(true));
        model.tryEmitNext("正文");
        model.tryEmitNext("工具请求");
        assertEquals(List.of("正文", "工具请求"), received);
        assertFalse(completed.get());
        assertEquals(1, subscriptions.get());
        subscription.dispose();
        assertTrue(cancelled.get());
    }

    /** 正常结束及模型异常分别保留 complete/error 语义，不将失败包装成正常完成。 */
    @Test
    void preservesCompletionAndError() {
        assertEquals(List.of("a", "b"), GraphEventBridge.stream(execution ->
                Mono.fromFuture(execution.forward(Flux.just("a", "b"), Map.of())).flux())
                .collectList().block(Duration.ofSeconds(3)));
        assertThrows(IllegalStateException.class, () -> GraphEventBridge.stream(execution ->
                Mono.fromFuture(execution.forward(Flux.error(new IllegalStateException("model failed")), Map.of())).flux())
                .blockLast(Duration.ofSeconds(3)));
    }
}
