import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createHash } from "node:crypto";
import register from "../review-tools.ts";

const root = fs.mkdtempSync(path.join(os.tmpdir(), "ai-review-tools-fixture-"));
const files = { current: {}, parent: {} };

/** 构造普通文件并登记摘要；全部写入独立临时目录。 */
function put(revision, name, data) {
  const file = path.join(root, revision, name);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
  files[revision][name] = createHash("sha256").update(data).digest("hex");
}

put("current", "src/Service.java", "first\r\none\u2028two\nneedle last\n");
put("parent", "src/Service.java", "parent version\n");
put("current", "src-other/Other.java", "needle outside scope\n");
put("current", "src/Wide.java", "needle" + "中".repeat(5000));
put("current", "src/Many.java", ("needle" + "中".repeat(500) + "\n").repeat(100));
fs.writeFileSync(path.join(root, "current", "application.yml"), "fixture not allowed");
fs.writeFileSync(path.join(root, "filter-manifest.json"), JSON.stringify({ file_sha256: files }));
process.env.REVIEW_INPUT = root;
const tools = {}, hooks = {}, flags = {};
let active = ["bash", "read", "write"];
register({
  registerTool(tool) { tools[tool.name] = tool; },
  on(event, callback) { hooks[event] = callback; },
  registerFlag(name, value) { flags[name] = value; },
  setActiveTools(names) { active = names; },
  getActiveTools() { return active; },
});

/** 直接调用注册工具，测试不依赖模型和外部网络。 */
async function call(name, args) {
  const result = await tools[name].execute("fixture", args);
  assert.ok(Buffer.byteLength(result.content[0].text) <= 65536);
  return JSON.parse(result.content[0].text);
}

assert.deepEqual(Object.keys(tools).sort(), ["review_list", "review_read", "review_search"]);
hooks.session_start();
assert.deepEqual(active.sort(), Object.keys(tools).sort());
assert.equal(hooks.tool_call({ toolName: "bash" }).block, true);
assert.equal(hooks.tool_call({ toolName: "review_read" }), undefined);
assert.equal(flags["review-input-ready"].type, "boolean");

let data = await call("review_read", { revision: "current", path: "src/Service.java" });
assert.equal(data.total_lines, 3);
assert.equal(data.lines[0].text, "first\r");
assert.equal(data.lines[1].text, "one\u2028two");
assert.equal(data.lines[2].line, 3);
assert.equal(data.truncated, false);
data = await call("review_read", { revision: "parent", path: "src/Service.java" });
assert.equal(data.lines[0].text, "parent version");
data = await call("review_read", { revision: "current", path: "src/Service.java", count: 1 });
assert.equal(data.next_line, 2);
data = await call("review_read", { revision: "current", path: "src/Wide.java" });
assert.equal(data.truncated, true);

for (const input of ["../raw-current/x", "/etc/passwd", "C:/x", "src/../x", "src\\x", "application.yml"]) {
  await assert.rejects(() => call("review_read", { revision: "current", path: input }));
}
await assert.rejects(() => call("review_read", { revision: "raw-current", path: "src/Service.java" }));
await assert.rejects(() => call("review_read", { revision: "current", path: "src/Service.java", start: 999 }));
await assert.rejects(() => call("review_read", { revision: "current", path: "src/Service.java", count: 201 }));
await assert.rejects(() => call("review_search", { revision: "current", query: "a\nb" }));
await assert.rejects(() => call("review_search", { revision: "current", query: "", limit: 0 }));
await assert.rejects(() => call("review_list", { revision: "current", directory: "../raw-current" }));

data = await call("review_search", { revision: "current", directory: "src", query: "outside scope" });
assert.equal(data.matches.length, 0);
data = await call("review_search", { revision: "current", directory: "src", query: "needle", limit: 1 });
assert.equal(data.matches.length, 1);
assert.equal(data.truncated, true);
data = await call("review_search", { revision: "current", directory: "src", query: "needle", limit: 100 });
assert.equal(data.truncated, true);
assert.ok(data.matches.length < 100);
data = await call("review_list", { revision: "current" });
assert.deepEqual(data.entries, ["src-other/", "src/"]);
assert.ok(!data.entries.includes("application.yml"));

fs.appendFileSync(path.join(root, "current/src/Service.java"), "tampered");
await assert.rejects(() => call("review_read", { revision: "current", path: "src/Service.java" }), /内容已变化/);

let symlinkCheck = "skipped: Windows symlink creation unavailable";
const link = path.join(root, "current/src/Link.java");
try {
  fs.symlinkSync(path.join(root, "parent/src/Service.java"), link);
  files.current["src/Link.java"] = files.parent["src/Service.java"];
  fs.writeFileSync(path.join(root, "filter-manifest.json"), JSON.stringify({ file_sha256: files }));
  register({
    registerTool(tool) { tools[tool.name] = tool; },
    on(event, callback) { hooks[event] = callback; },
    registerFlag(name, value) { flags[name] = value; },
    setActiveTools(names) { active = names; },
    getActiveTools() { return active; },
  });
  await assert.rejects(() => call("review_read", { revision: "current", path: "src/Link.java" }), /链接/);
  symlinkCheck = "passed";
} catch (error) {
  if (!["EPERM", "EACCES"].includes(error.code)) throw error;
}
console.log(JSON.stringify({
  checks: ["three_tools_only", "blocks_other_tools", "correct_snapshot", "LF_line_numbers",
    "path_traversal_rejection", "absolute_path_rejection", "unlisted_file_rejection",
    "directory_scope", "pagination", "truncation", "bounded_search", "hash_tampering_rejection"],
  symlink_check: symlinkCheck,
  real_model_called: false,
}));
