package com.zzx.zzxaicode.ai.common;

import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;

/** 创建应用时使用的框架无关类型路由契约。 */
public interface CodeGenTypeRouter {
    /**
     * 判断用户需求对应的代码类型。
     * @param userPrompt 非空需求文本
     * @return 支持的生成类型；空值或非法模型响应以业务异常拒绝
     */
    CodeGenTypeEnum routeCodeGenType(String userPrompt);
}
