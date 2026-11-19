import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";

/** 注册仅访问过滤清单内文件的工具；根目录由受信任的启动环境指定。 */
export default function (pi) {
  if (!process.env.REVIEW_INPUT) throw new Error("缺少 REVIEW_INPUT");
  const root = path.resolve(process.env.REVIEW_INPUT);
  if (fs.realpathSync(root) !== root) throw new Error("输入根目录不能经过链接");
  const manifest = JSON.parse(fs.readFileSync(path.join(root, "filter-manifest.json"), "utf8"));
  const maps = manifest.file_sha256;
  const allowed = ["review_read", "review_search", "review_list"];
  const runDirectory = process.env.REVIEW_RUN_DIR ? path.resolve(process.env.REVIEW_RUN_DIR) : null;
  if (runDirectory) {
    const location = path.relative(root, runDirectory);
    if (fs.realpathSync(runDirectory) !== runDirectory || !location.startsWith(".." + path.sep)) {
      throw new Error("执行记录目录必须位于快照根目录之外，且不能经过链接");
    }
  }

  /** 将工具元数据写入受信任执行目录；不记录源码正文或环境变量。 */
  function record(event) {
    if (runDirectory) fs.appendFileSync(path.join(runDirectory, "tool-events.jsonl"),
      JSON.stringify({ time: new Date().toISOString(), ...event }) + "\n", { mode: 0o600 });
  }

  /** 校验快照名并返回对应白名单；不接受任意文件系统根目录。 */
  function index(revision) {
    if (!["current", "parent"].includes(revision)) throw new Error("快照必须是 current 或 parent");
    const value = maps?.[revision];
    if (!value || typeof value !== "object") throw new Error("缺少文件白名单");
    return value;
  }

  /** 接收规范相对路径；目录查询允许空字符串或点表示根目录。 */
  function relative(value, directory = false) {
    if (directory && (value === undefined || value === "" || value === ".")) return "";
    if (typeof value !== "string" || value.length > 1024 || path.isAbsolute(value)
        || /[\x00-\x1f\\:]/.test(value)
        || value.split("/").some(part => !part || [".", "..", ".git"].includes(part))) {
      throw new Error("只接受规范的相对路径");
    }
    return value;
  }

  /** 从白名单筛选目录内文件，拒绝不存在或被过滤的目录。 */
  function names(revision, directory) {
    const prefix = relative(directory, true);
    const result = Object.keys(index(revision)).sort().filter(name => !prefix || name.startsWith(prefix + "/"));
    if (prefix && !result.length) throw new Error("目录不在允许范围内");
    return result;
  }

  /** 只读普通文件，逐级拒绝链接，并核对导出时的摘要及大小。 */
  function read(revision, input) {
    const name = relative(input);
    const hashes = index(revision);
    if (!Object.hasOwn(hashes, name) || !/^[a-f0-9]{64}$/.test(hashes[name])) {
      throw new Error("文件不在过滤白名单内");
    }
    let file = root;
    for (const part of [revision, ...name.split("/")]) {
      file = path.join(file, part);
      if (fs.lstatSync(file).isSymbolicLink()) throw new Error("拒绝链接路径");
    }
    if (fs.realpathSync(file) !== file) throw new Error("拒绝真实路径变化");
    const descriptor = fs.openSync(file, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW ?? 0));
    try {
      const stat = fs.fstatSync(descriptor);
      if (!stat.isFile() || stat.size > 262144) throw new Error("文件类型或大小不符合限制");
      const data = fs.readFileSync(descriptor);
      if (data.length > 262144 || createHash("sha256").update(data).digest("hex") !== hashes[name]) {
        throw new Error("文件内容已变化，需重新准备输入");
      }
      const text = data.toString("utf8");
      const lines = text.split("\n");
      if (text.endsWith("\n") || text === "") lines.pop();
      return lines;
    } finally {
      fs.closeSync(descriptor);
    }
  }

  /** 检查整数限额；默认值不替代非法输入。 */
  function integer(value, fallback, maximum) {
    const result = value === undefined ? fallback : value;
    if (!Number.isInteger(result) || result < 1 || result > maximum) throw new Error("整数参数超出范围");
    return result;
  }

  /** 返回有界 JSON 结果，超出最终大小限制时明确失败。 */
  function reply(data) {
    const text = JSON.stringify(data);
    if (Buffer.byteLength(text) > 65536) throw new Error("工具结果超过 64KiB");
    return { content: [{ type: "text", text }], details: {} };
  }

  /** 构造严格工具参数协议，所有工具均要求显式指定快照。 */
  function schema(properties, required) {
    return { type: "object", properties: {
      revision: { type: "string", enum: ["current", "parent"] }, ...properties,
    }, required: ["revision", ...required], additionalProperties: false };
  }

  pi.registerTool({
    name: "review_read", label: "读取过滤源码",
    description: "按原始行号读取过滤白名单内的源码；next_line 可用于继续读取。",
    parameters: schema({ path: { type: "string" }, start: { type: "integer", minimum: 1 },
      count: { type: "integer", minimum: 1, maximum: 200 } }, ["path"]),
    /** 参数为版本、路径、1 起始行和行数；返回源码、下一页位置及截断标记。 */
    async execute(_id, args, signal) {
      if (signal?.aborted) throw new Error("已取消");
      const lines = read(args.revision, args.path);
      const start = integer(args.start, 1, 1000000);
      const count = integer(args.count, 100, 200);
      if (start > Math.max(lines.length, 1)) throw new Error("起始行超出文件范围");
      const result = [];
      let size = 0, truncated = false;
      for (let position = start - 1; position < Math.min(lines.length, start - 1 + count); position++) {
        const original = lines[position];
        const text = original.slice(0, 2000);
        if (size + Buffer.byteLength(text) > 16000) { truncated = true; break; }
        truncated ||= text.length < original.length;
        result.push({ line: position + 1, text });
        size += Buffer.byteLength(text);
      }
      const next = start + result.length;
      return reply({ revision: args.revision, path: args.path, total_lines: lines.length,
        lines: result, truncated, next_line: next <= lines.length ? next : null });
    },
  });

  pi.registerTool({
    name: "review_search", label: "搜索过滤源码",
    description: "在指定版本和目录的过滤源码中搜索固定字符串，返回实际行号。",
    parameters: schema({ query: { type: "string", minLength: 1, maxLength: 200 },
      directory: { type: "string" }, limit: { type: "integer", minimum: 1, maximum: 100 } }, ["query"]),
    /** 参数为版本、固定字符串、可选目录和结果上限；达到数量或时间上限时标记截断。 */
    async execute(_id, args, signal) {
      if (typeof args.query !== "string" || !args.query.length || args.query.length > 200 || /[\r\n]/.test(args.query)) throw new Error("搜索词必须是单行固定字符串");
      const limit = integer(args.limit, 50, 100), matches = [], deadline = Date.now() + 3000;
      let truncated = false, size = 0;
      search: for (const name of names(args.revision, args.directory)) {
        if (signal?.aborted) throw new Error("已取消");
        const lines = read(args.revision, name);
        for (let position = 0; position < lines.length; position++) {
          if (Date.now() >= deadline) { truncated = true; break search; }
          const column = lines[position].indexOf(args.query);
          if (column < 0) continue;
          const begin = Math.max(0, column - 100), excerpt = lines[position].slice(begin, begin + 500);
          const clipped = begin > 0 || begin + excerpt.length < lines[position].length;
          const match = { path: name, line: position + 1, column: column + 1,
            text: excerpt, excerpt_truncated: clipped };
          const bytes = Buffer.byteLength(JSON.stringify(match));
          if (size + bytes > 48000) { truncated = true; break search; }
          matches.push(match);
          size += bytes;
          if (matches.length >= limit) { truncated = true; break search; }
        }
      }
      return reply({ revision: args.revision, matches, truncated });
    },
  });

  pi.registerTool({
    name: "review_list", label: "列出过滤目录",
    description: "从过滤白名单列出目录的直接子项；默认查看根目录。",
    parameters: schema({ directory: { type: "string" } }, []),
    /** 参数为版本和可选目录；仅列出白名单中的直接子项，最多 200 项。 */
    async execute(_id, args, signal) {
      if (signal?.aborted) throw new Error("已取消");
      const directory = relative(args.directory, true);
      const children = new Set(names(args.revision, directory).map(name => {
        const rest = directory ? name.slice(directory.length + 1) : name;
        return rest.split("/")[0] + (rest.includes("/") ? "/" : "");
      }));
      const entries = [...children].sort();
      return reply({ revision: args.revision, directory: directory || ".",
        entries: entries.slice(0, 200), truncated: entries.length > 200 });
    },
  });

  // 工具层仅允许这三个工具；启动命令还需禁用内置工具和资源自动发现。
  pi.on("session_start", () => {
    pi.setActiveTools(allowed);
    const active = pi.getActiveTools().slice().sort();
    if (JSON.stringify(active) !== JSON.stringify(allowed.slice().sort())) throw new Error("实际工具集合不符合审查策略");
    if (runDirectory) fs.writeFileSync(path.join(runDirectory, "tools-ready.json"),
      JSON.stringify({ active_tools: active }), { flag: "wx", mode: 0o600 });
  });
  pi.on("tool_call", event => {
    if (!allowed.includes(event.toolName)) {
      record({ event: "blocked_tool", tool: event.toolName });
      return { block: true, reason: "仅允许审查只读工具", terminate: true };
    }
    record({ event: "tool_call", id: event.toolCallId, tool: event.toolName, input: event.input });
  });
  pi.on("tool_execution_end", event => {
    record({ event: "tool_end", id: event.toolCallId, tool: event.toolName,
      is_error: Boolean(event.isError), duration_ms: event.durationMs });
  });
  pi.on("auto_retry_start", event => {
    record({ event: "retry", attempt: event.attempt, delay_ms: event.delayMs });
  });
  pi.registerFlag("review-input-ready", {
    description: "过滤源码只读扩展已加载", type: "boolean", default: false,
  });
}
