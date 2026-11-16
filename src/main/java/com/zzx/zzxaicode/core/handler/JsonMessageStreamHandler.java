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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
            // 重复订阅不能共用正文和工具去重集合，否则会重复拼接历史或遗漏工具展示。
            StringBuilder history = new StringBuilder();
            Set<String> seen = new HashSet<>();
            return originFlux.map(chunk -> handleChunk(chunk, history, seen)).filter(StrUtil::isNotEmpty)
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
     * @param seen 已展示的工具调用 ID
     * @return 当前应展示的文本；重复工具请求返回空字符串
     */
    private String handleChunk(String chunk, StringBuilder history, Set<String> seen) {
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
            // 选择提示只用于当前页面展示；工具执行结果才追加到可见历史。
            String id = event.getStr("id");
            return id != null && seen.add(id) ? "\n\n[选择工具] " + display + "\n\n" : "";
        }
        if ("tool_executed".equals(type)) {
            JSONObject arguments = JSONUtil.parseObj(event.getStr("arguments", "{}"));
            String result = event.getStr("result", "");
            String output = formatResult(name, display, arguments, result);
            history.append(output);
            return output;
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的生成事件");
    }

    /**
     * 保留代码块展示，同时真实显示失败结果，避免把失败的写入描述成成功。
     * @param name 工具英文名
     * @param display 中文名
     * @param arguments 工具参数
     * @param result 工具实际结果
     * @return 可见工具记录
     */
    private String formatResult(String name, String display, JSONObject arguments, String result) {
        // 执行失败时优先显示真实错误，避免按工具参数拼成“写入成功”的代码展示。
        if (result.startsWith("错误") || result.startsWith("警告")) {
            return "\n\n[工具失败] " + display + "：" + result + "\n\n";
        }
        String path = arguments.getStr("relativeFilePath", arguments.getStr("relativeDirPath", ""));
        if ("exit".equals(name)) {
            return "\n\n[执行结束]\n\n";
        }
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
