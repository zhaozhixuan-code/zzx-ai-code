package com.zzx.zzxaicode.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.model.enums.ChatHistoryMessageTypeEnum;
import com.zzx.zzxaicode.model.po.User;
import com.zzx.zzxaicode.service.ChatHistoryService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;

/** 处理项目内部 JSON 事件，转换为前端既有文本流及用户可见历史。 */
@Component
public class JsonMessageStreamHandler {
    private static final Map<String, String> NAMES = Map.of("readFile", "读取文件", "readDir", "读取目录",
            "writeFile", "写入文件", "modifyFile", "修改文件", "deleteFile", "删除文件", "exit", "退出工具调用");

    /**
     * 为每次订阅独立收集事件，完成后保存可见文本，错误路径只记录失败说明。
     * @param originFlux 内部 JSON 事件流
     * @param chatHistoryService 用户可见历史服务
     * @param appId 应用 ID
     * @param loginUser 当前用户
     * @return 既有前端可直接显示的文本流
     */
    public Flux<String> handle(Flux<String> originFlux, ChatHistoryService chatHistoryService,
                               long appId, User loginUser) {
        return Flux.defer(() -> {
            // 每次订阅独立收集正文，不能把上一次订阅的历史再次追加到本次结果中。
            StringBuilder history = new StringBuilder();
            return originFlux.map(chunk -> handleChunk(chunk, history)).filter(StrUtil::isNotEmpty)
                    .doOnComplete(() -> chatHistoryService.addChatMessage(appId, history.toString(),
                            ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId()))
                    .doOnError(error -> chatHistoryService.addChatMessage(appId, "AI回复失败: " + error.getMessage(),
                            ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId()));
        });
    }

    /**
     * 按稳定字段解析事件，不依赖任一模型框架类型。
     * @param chunk JSON 事件
     * @param history 可见历史累积器
     * @return 当前应展示的文本，空的 AI 文本片段由调用方过滤
     */
    private String handleChunk(String chunk, StringBuilder history) {
        // 质量事件由服务端保留前缀标识，单独交给 SSE；不会进入模型可见聊天历史。
        if(chunk.startsWith(com.zzx.zzxaicode.quality.QualityWorkflowService.EVENT_PREFIX)) return chunk;
        JSONObject event = JSONUtil.parseObj(chunk);
        String type = event.getStr("type");
        if ("ai_response".equals(type)) {
            String data = event.getStr("data", "");
            history.append(data);
            return data;
        }
        String name = event.getStr("name", "");
        String display = NAMES.getOrDefault(name, name);
        if ("tool_request".equals(type)) {
            // 生成器在单次模型响应内去重；展示层逐次展示，避免误吞后续轮次复用的 ID。
            return "\n\n[选择工具] " + display + "\n\n";
        }
        if ("tool_executed".equals(type)) {
            String output = formatResult(name, display, event);
            history.append(output);
            return output;
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的生成事件");
    }

    /**
     * 保留代码块展示，同时真实显示失败结果，避免把失败的写入描述成成功。
     * @param name 工具英文名
     * @param display 中文名
     * @param event 工具执行事件，包含模型参数和实际结果
     * @return 可见工具记录
     */
    private String formatResult(String name, String display, JSONObject event) {
        String result = event.getStr("result", "");
        // 执行失败时优先显示真实错误，避免按工具参数拼成“写入成功”的代码展示。
        // 参数错误可能源于无效 JSON；失败展示不能再次解析同一份错误参数。
        if (result.startsWith("错误") || result.startsWith("警告")) {
            return "\n\n[工具失败] " + display + "：" + result + "\n\n";
        }
        if ("exit".equals(name)) {
            return "\n\n[执行结束]\n\n";
        }
        JSONObject arguments = JSONUtil.parseObj(event.getStr("arguments", "{}"));
        String path = arguments.getStr("relativeFilePath", arguments.getStr("relativeDirPath", ""));
        if ("writeFile".equals(name)) {
            String suffix = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : "";
            return "\n\n[工具调用] " + display + " " + path + "\n```" + suffix + "\n"
                    + arguments.getStr("content", "") + "\n```\n\n";
        }
        if ("modifyFile".equals(name)) {
            return "\n\n[工具调用] " + display + " " + path + "\n替换前：\n```\n"
                    + arguments.getStr("oldContent", "") + "\n```\n替换后：\n```\n"
                    + arguments.getStr("newContent", "") + "\n```\n\n";
        }
        return "\n\n[工具调用] " + display + " " + path + "\n\n";
    }
}
