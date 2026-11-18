package com.zzx.zzxaicode.quality.graph;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 将节点内部的既有模型/工具事件实时转发到 SSE 上游，图状态仅保存节点完成后的业务增量。
 * 执行资源保存在单次订阅上下文；取消时同时取消 Graph 订阅和正在运行的节点 future。
 */
public final class GraphEventBridge {
    private GraphEventBridge() { }

    /**
     * 每个订阅只启动一次 Graph，并在外层取消、异常或完成时释放订阅资源。
     * @param graph 使用单次执行上下文创建的 Graph 输出流
     * @return 实时项目事件流；下游无需求时明确失败，不无界缓存片段
     */
    public static Flux<String> stream(Function<Execution, Flux<?>> graph) {
        return Flux.create(sink -> {
            Execution execution = new Execution(sink);
            sink.onDispose(execution.resources);
            execution.resources.add(Flux.defer(() -> graph.apply(execution))
                    .subscribe(ignored -> { }, sink::error, sink::complete));
        }, FluxSink.OverflowStrategy.ERROR);
    }

    /** 单次执行的事件发送器和取消资源集合，不写入需要序列化的 Graph 状态。 */
    public static final class Execution {
        private final FluxSink<String> sink;
        private final Disposable.Composite resources = Disposables.composite();

        /**
         * 绑定唯一订阅的事件出口。
         * @param sink 当前订阅的响应出口
         */
        private Execution(FluxSink<String> sink) {
            this.sink = sink;
        }

        /**
         * 按源顺序转发一个完整模型轮次；future 仅在源正常完成后交付节点增量。
         * @param source 既有正文/工具事件源，取消不得重新订阅
         * @param update 模型轮次完成后更新的 Graph 数据
         * @return 可作为 AsyncNodeAction 结果的 future
         */
        public CompletableFuture<Map<String, Object>> forward(Flux<String> source, Map<String, Object> update) {
            return complete(source.doOnNext(this::emit).then().thenReturn(update));
        }

        /** @param result 异步业务节点产生的状态增量 @return 随 Graph 一起取消的执行 future */
        public CompletableFuture<Map<String,Object>> complete(Mono<Map<String,Object>> result) {
            CompletableFuture<Map<String, Object>> future = result.toFuture();
            Disposable cancellation = () -> future.cancel(true);
            resources.add(cancellation);
            future.whenComplete((value, error) -> resources.remove(cancellation));
            return future;
        }

        /**
         * 发送模型或质量阶段事件；取消后的事件不会交付给客户端。
         * @param event 项目自己的字符串事件，不含 Graph 框架类型
         */
        public void emit(String event) {
            if (!resources.isDisposed()) {
                sink.next(event);
            }
        }

        /** @return 客户端是否已取消或执行已经结束。 */
        public boolean isDisposed() {
            return resources.isDisposed();
        }
    }
}
