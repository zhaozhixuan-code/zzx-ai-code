package com.zzx.zzxaicode.ai.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.zzxaicode.ai.common.ProjectFileOperations;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.function.BiFunction;

/** 为每次应用生成绑定 appId，工具 schema 中没有应用 ID 参数。 */
@Component
public class SpringAiToolCallbacks {
    private final ObjectMapper mapper;
    private final ProjectFileOperations files;

    /**
     * 注入纯文件能力与 JSON 解析器。
     * @param mapper 工具 JSON 序列化器
     * @param files 框架无关文件操作
     */
    public SpringAiToolCallbacks(ObjectMapper mapper, ProjectFileOperations files) {
        this.mapper = mapper;
        this.files = files;
    }

    /**
     * 创建单次请求工具集合，禁止共享可变 appId。
     * @param appId 当前服务端应用 ID
     * @return 按名称索引的工具回调
     */
    public Map<String, ToolCallback> forApp(long appId) {
        Map<String, ToolCallback> tools = new LinkedHashMap<>();
        add(tools, appId, "readFile", "读取指定相对路径的文件内容", "relativeFilePath");
        add(tools, appId, "readDir", "读取项目目录结构，relativeDirPath 为空表示根目录", "relativeDirPath");
        add(tools, appId, "writeFile", "写入文件到指定相对路径", "relativeFilePath", "content");
        add(tools, appId, "modifyFile", "使用新内容替换文件中的旧内容", "relativeFilePath", "oldContent", "newContent");
        add(tools, appId, "deleteFile", "删除单个文件，禁止删除目录或项目重要文件", "relativeFilePath");
        add(tools, appId, "exit", "任务完成后退出工具调用，随后输出最终结果");
        return tools;
    }

    /**
     * 创建受服务端工作区约束的工具表；修复不声明删除工具，写入策略仍在文件层强制执行。
     * @param workspace 服务端任务源码根目录
     * @param repair 是否使用受限修复策略
     * @return 本任务独立工具回调
     */
    public Map<String, ToolCallback> forWorkspace(Path workspace, boolean repair) {
        Map<String, ToolCallback> tools = new LinkedHashMap<>();
        BiFunction<String, Map<String, Object>, String> execute =
                (name, arguments) -> files.executeInWorkspace(workspace, name, arguments, repair);
        add(tools, execute, "readFile", "读取指定相对路径的文件内容", "relativeFilePath");
        add(tools, execute, "readDir", "读取项目目录结构，空路径表示根目录", "relativeDirPath");
        add(tools, execute, "writeFile", "写入文件到相对路径", "relativeFilePath", "content");
        add(tools, execute, "modifyFile", "将文件中旧内容替换为新内容", "relativeFilePath", "oldContent", "newContent");
        add(tools, execute, "exit", "结束工具调用，输出结果");
        return tools;
    }

    /**
     * 以明确 schema 声明工具，不让框架反射出服务端上下文。
     * @param tools 本轮工具表
     * @param appId 已绑定的应用 ID
     * @param name 工具名
     * @param description 工具说明
     * @param parameters 字符串参数名列表
     * @throws IllegalStateException 工具 schema 无法序列化
     */
    private void add(Map<String, ToolCallback> tools, long appId, String name, String description, String... parameters) {
        add(tools, (tool, arguments) -> files.execute(appId, tool, arguments), name, description, parameters);
    }

    /**
     * 将声明与服务端文件执行绑定，根目录和任务标识不会出现在模型参数中。
     * @param tools 当前任务工具表
     * @param execute 服务端绑定的执行函数
     * @param name 工具名称
     * @param description 业务描述
     * @param parameters 模型可填写的字符串参数
     */
    private void add(Map<String, ToolCallback> tools, BiFunction<String, Map<String, Object>, String> execute,
                     String name, String description, String... parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String parameter : parameters) {
            properties.put(parameter, Map.of("type", "string", "description", parameter));
        }
        try {
            // schema 约束所有参数为字符串，并关闭额外字段；回调仍要校验不可信的模型实参。
            String schema = mapper.writeValueAsString(Map.of("type", "object", "properties", properties,
                    "required", List.of(parameters), "additionalProperties", false));
            ToolDefinition definition = new DefaultToolDefinition(name, description, schema);
            tools.put(name, new ToolCallback() {
                /**
                 * 提供模型可见的参数声明，应用 ID 保留在服务端闭包中。
                 * @return 仅含业务参数的工具定义
                 */
                @Override
                public ToolDefinition getToolDefinition() {
                    return definition;
                }

                /**
                 * 解析工具参数并在服务端已绑定的项目目录中执行。
                 * @param toolInput 模型返回的 JSON 对象
                 * @return 工具结果；JSON 格式错误和预期文件/参数错误以文本交给模型修正
                 */
                @Override
                public String call(String toolInput) {
                    try {
                        Map<String, Object> arguments = mapper.readValue(toolInput, new TypeReference<>() { });
                        // 部分端点不会严格执行 schema，服务端仍拒绝 appId 等未声明字段。
                        if (arguments == null || !properties.keySet().containsAll(arguments.keySet())) {
                            return "错误：工具参数包含未声明的字段";
                        }
                        return execute.apply(name, arguments);
                    } catch (JsonProcessingException e) {
                        // 这里只处理输入格式；执行期的非预期异常继续向上传播，不能伪装成 JSON 错误。
                        return "错误：工具参数必须是有效 JSON 对象";
                    }
                }
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具定义创建失败", e);
        }
    }
}
