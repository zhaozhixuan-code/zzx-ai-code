package com.zzx.zzxaicode.ai.springai;

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
     * 以明确 schema 声明工具，不让框架反射出服务端上下文。
     * @param tools 本轮工具表
     * @param appId 已绑定的应用 ID
     * @param name 工具名
     * @param description 工具说明
     * @param parameters 字符串参数名列表
     */
    private void add(Map<String, ToolCallback> tools, long appId, String name, String description, String... parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String parameter : parameters) {
            properties.put(parameter, Map.of("type", "string", "description", parameter));
        }
        try {
            String schema = mapper.writeValueAsString(Map.of("type", "object", "properties", properties,
                    "required", List.of(parameters), "additionalProperties", false));
            ToolDefinition definition = new DefaultToolDefinition(name, description, schema);
            tools.put(name, new ToolCallback() {
                /** @return 仅含业务参数的工具定义。 */
                @Override
                public ToolDefinition getToolDefinition() {
                    return definition;
                }

                /**
                 * 解析工具参数并在服务端已绑定的项目目录中执行。
                 * @param toolInput 模型返回的 JSON 对象
                 * @return 工具结果；参数解析异常作为明确错误文本返回
                 */
                @Override
                public String call(String toolInput) {
                    try {
                        Map<String, Object> arguments = mapper.readValue(toolInput, new TypeReference<>() { });
                        if (arguments == null || !properties.keySet().containsAll(arguments.keySet())) {
                            return "错误：工具参数包含未声明的字段";
                        }
                        return files.execute(appId, name, arguments);
                    } catch (Exception e) {
                        return "错误：工具参数必须是有效 JSON 对象";
                    }
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("工具定义创建失败", e);
        }
    }
}
