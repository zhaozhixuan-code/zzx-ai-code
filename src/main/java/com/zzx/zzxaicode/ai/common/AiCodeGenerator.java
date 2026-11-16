package com.zzx.zzxaicode.ai.common;

import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import reactor.core.publisher.Flux;

/** 主业务代码生成契约，业务层只接触项目类型及响应流。 */
public interface AiCodeGenerator {
    /**
     * 同步生成普通模式代码。
     * @param appId 服务端校验后的应用 ID
     * @param message 本轮用户需求
     * @param type HTML 或多文件类型
     * @return 可由项目解析器处理的完整文本
     */
    String generate(long appId, String message, CodeGenTypeEnum type);

    /**
     * 流式生成代码，Vue 模式返回项目内部 JSON 事件。
     * @param appId 服务端校验后的应用 ID
     * @param message 本轮用户需求
     * @param type 代码生成类型
     * @return 文本片段或项目内部事件，失败以 error 信号传播
     */
    Flux<String> stream(long appId, String message, CodeGenTypeEnum type);
}
