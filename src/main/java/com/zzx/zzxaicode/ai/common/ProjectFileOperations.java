package com.zzx.zzxaicode.ai.common;

import com.zzx.zzxaicode.constants.AppConstant;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 框架无关文件能力，所有路径均限定为服务端 appId 对应的 Vue 项目。 */
@Component
public class ProjectFileOperations {
    /** 删除保护只按文件名判断，在项目任意子目录中都生效。 */
    private static final Set<String> PROTECTED = Set.of("package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
            "vite.config.js", "vite.config.ts", "vue.config.js", "tsconfig.json", "tsconfig.app.json", "tsconfig.node.json",
            "index.html", "main.js", "main.ts", "app.vue", ".gitignore", "readme.md");
    /** 目录清单过滤项，避免依赖、构建产物和本地配置占用模型上下文。 */
    private static final Set<String> IGNORED = Set.of("node_modules", ".git", "dist", "build", ".DS_Store", ".env",
            "target", ".mvn", ".idea", ".vscode", "coverage");
    private final Path outputRoot;

    /** 使用单体应用的代码生成目录。 */
    public ProjectFileOperations() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR));
    }

    /**
     * 为隔离测试或显式配置指定代码输出目录。
     * @param outputRoot 所有应用项目的父目录
     */
    public ProjectFileOperations(Path outputRoot) {
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
    }

    /**
     * 执行单个文件工具；删除只允许一个明确相对文件路径。
     * @param appId 服务端应用 ID，必须大于零
     * @param name 已声明的工具名称
     * @param arguments JSON 参数，不允许包含 appId
     * @return 工具结果文本，预期参数和文件错误以“错误：”返回模型修正
     */
    public String execute(long appId, String name, Map<String, Object> arguments) {
        if (appId <= 0 || arguments.containsKey("appId")) return "错误：应用 ID 只能由服务端提供";
        return executeInWorkspace(outputRoot.resolve("vue_project_" + appId), name, arguments, false);
    }

    /**
     * 在服务端确定的任务目录中执行文件工具，模型无法更换根路径。
     * @param workspace 已校验的任务源码根目录
     * @param name 工具名
     * @param arguments 模型实参
     * @param repair 是否为受限修复；修复只修改源码、公开资源及入口，禁止删除和改构建配置
     * @return 实际工具结果；预期文件错误以文本反馈
     */
    public String executeInWorkspace(Path workspace, String name, Map<String, Object> arguments, boolean repair) {
        try {
            if ("exit".equals(name)) {
                return "不要继续调用工具，可以输出最终结果了";
            }
            boolean directory = "readDir".equals(name);
            String relative = directory ? optionalString(arguments, "relativeDirPath") : required(arguments, "relativeFilePath");
            Path path = resolve(workspace.toAbsolutePath().normalize(), relative, directory);
            Path local = workspace.toAbsolutePath().normalize().relativize(path);
            boolean write = Set.of("writeFile", "modifyFile", "deleteFile").contains(name);
            if (repair && write && ("deleteFile".equals(name)
                    || !(local.startsWith("src") || local.startsWith("public") || local.toString().equals("index.html")))) {
                throw new IllegalArgumentException("修复只允许修改 src、public 和 index.html，不能删除或修改构建配置");
            }
            return switch (name) {
                case "readFile" -> readFile(path);
                case "readDir" -> readDirectory(path);
                case "writeFile" -> {
                    String content = required(arguments, "content");
                    if (repair && content.isBlank()) throw new IllegalArgumentException("修复不能清空文件");
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, content);
                    yield "文件写入成功: " + relative;
                }
                case "modifyFile" -> {
                    String oldContent = required(arguments, "oldContent");
                    String newContent = required(arguments, "newContent");
                    if (oldContent.isEmpty()) {
                        throw new IllegalArgumentException("旧内容不能为空");
                    }
                    String original = Files.readString(path);
                    if (!original.contains(oldContent)) {
                        yield "警告：文件中未找到要替换的内容，文件未修改 - " + relative;
                    }
                    String modified = original.replace(oldContent, newContent);
                    if (repair && modified.isBlank()) throw new IllegalArgumentException("修复不能清空文件");
                    Files.writeString(path, modified);
                    yield "文件修改成功: " + relative;
                }
                case "deleteFile" -> {
                    if (PROTECTED.contains(path.getFileName().toString().toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("不允许删除重要文件");
                    }
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalArgumentException("指定路径不是文件或文件不存在");
                    }
                    Files.delete(path);
                    yield "文件删除成功: " + relative;
                }
                default -> throw new IllegalArgumentException("不支持的工具名称");
            };
        } catch (IOException | IllegalArgumentException e) {
            // 不将绝对路径、系统异常细节或其他应用信息反馈给模型和用户。
            return "错误：工具执行失败（" + name + "）："
                    + (e instanceof IllegalArgumentException ? e.getMessage() : "文件不存在、不可读写或路径无效");
        }
    }

    /**
     * 校验相对路径及每一级已有祖先，拒绝绝对路径、跨目录及符号链接。
     * @param root 服务端源码根目录
     * @param relative 相对文件或目录路径
     * @param directory 是否允许空路径表示项目根目录
     * @return 已规范化的项目内绝对路径
     * @throws IOException 路径类型检查失败
     */
    private Path resolve(Path root, String relative, boolean directory) throws IOException {
        if ((!directory && relative.isBlank()) || relative.contains("*") || relative.contains("?")) {
            throw new IllegalArgumentException("必须提供明确的单个相对路径");
        }
        Path input = Path.of(relative);
        if (input.isAbsolute() || relative.matches("^[A-Za-z]:.*") || relative.startsWith("\\")) {
            throw new IllegalArgumentException("不允许绝对路径");
        }
        Path target = root.resolve(input).normalize();
        // 规范化后再比较路径层级；文件工具还必须排除项目根目录自身。
        if (!target.startsWith(root) || (!directory && target.equals(root))) {
            throw new IllegalArgumentException("路径超出当前应用目录");
        }
        // 检查输出根目录的祖先，避免项目目录自身经链接指向外部。
        for (Path cursor = target; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)
                    || (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(cursor, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isOther())) {
                throw new IllegalArgumentException("不允许通过链接访问文件");
            }
        }
        return target;
    }

    /**
     * 列出目录中的可见文件，不跟随符号链接。
     * @param directory 已验证的项目内目录
     * @return 带换行的相对文件清单
     * @throws IOException 目录不存在或不可读取
     */
    private String readDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("目录不存在或不是目录");
        }
        StringBuilder result=new StringBuilder("项目目录结构:\n");
        Files.walkFileTree(directory,new java.nio.file.SimpleFileVisitor<>() {
            /** 不进入依赖与本轮产物，避免修复轮次遍历 npm 安装后的整个依赖树。 */
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path dir,java.nio.file.attribute.BasicFileAttributes attrs) {
                String name=dir.getFileName().toString();
                return !dir.equals(directory) && (IGNORED.contains(name) || name.startsWith("quality-") || name.startsWith(".env"))
                        ?java.nio.file.FileVisitResult.SKIP_SUBTREE:java.nio.file.FileVisitResult.CONTINUE;
            }
            /** 清单输出限制 65536 字符，超限明确截断，模型可继续读取具体子目录。 */
            @Override public java.nio.file.FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs) {
                String name=file.getFileName().toString();
                if(attrs.isRegularFile() && !IGNORED.contains(name) && !name.startsWith("quality-") && !name.startsWith(".env")) result.append(directory.relativize(file)).append('\n');
                if(result.length()>65536) { result.setLength(65536);result.append("\n清单已截断，请指定子目录读取");return java.nio.file.FileVisitResult.TERMINATE; }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return result.toString();
    }

    /** @param file 已完成路径校验的文件 @return 不超过 256KiB 的原文，超大文件明确拒绝而不返回半段源码 */
    private String readFile(Path file) throws IOException {
        if(Files.size(file)>256*1024) throw new IllegalArgumentException("文件超过 256KiB，请读取具体源码文件");
        return Files.readString(file);
    }

    /**
     * 获取必需字符串，空字符串内容允许用于清空文件。
     * @param arguments 参数表
     * @param key 参数名
     * @return 字符串值
     */
    private String required(Map<String, Object> arguments, String key) {
        if (!(arguments.get(key) instanceof String value)) {
            throw new IllegalArgumentException("缺少字符串参数 " + key);
        }
        return value;
    }

    /**
     * 获取可空目录字符串。
     * @param arguments 参数表
     * @param key 参数名
     * @return 未传入时为空字符串
     */
    private String optionalString(Map<String, Object> arguments, String key) {
        return arguments.get(key) == null ? "" : required(arguments, key);
    }
}
