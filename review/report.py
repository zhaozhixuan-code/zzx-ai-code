"""校验只读审查结果并生成报告；离线整理既有任务时不调用模型。"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import difflib
import html
import json
from pathlib import Path
import re
import tempfile
from urllib.parse import quote
import uuid

from jsonschema import Draft202012Validator

from pi_runner import TOOLS, digest, git_lines, load_input, parse_json, utc_now

ROOT = Path(__file__).resolve().parent
SCHEMA = parse_json((ROOT / "schemas/review-v1.json").read_text(encoding="utf-8"))
Draft202012Validator.check_schema(SCHEMA)
CANDIDATE_SCHEMA = {"$schema": SCHEMA["$schema"], "$ref": "#/$defs/candidate", "$defs": SCHEMA["$defs"]}
GENERAL_LIMIT = "程序仅校验格式、源码引用及变更行读取范围；业务结论和调用链完整性仍需人工复核。"


class InvalidOutput(ValueError):
    """携带有限的程序错误码，不把模型正文或凭据写入校验反馈。"""

    def __init__(self, *details):
        """details 为程序产生的校验说明，最多保留 20 条。"""
        self.details = list(details[:20])
        super().__init__("; ".join(self.details))


def canonical(value):
    """稳定编码普通 JSON 数据，用于配置、源码版本和审查键摘要。"""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def read_json(file, maximum=2 * 1024 * 1024):
    """读取完整且有界的 UTF-8 JSON；拒绝重复字段、链接文件和混杂文本。"""
    file = Path(file).absolute()
    if file.resolve(strict=True) != file or not file.is_file() or file.stat().st_size > maximum:
        raise ValueError("JSON 文件路径、类型或大小无效")
    return parse_json(file.read_text(encoding="utf-8"))


def schema_check(value, schema):
    """离线执行 Draft 2020-12 校验；反馈只含字段位置与规则名称。"""
    errors = []
    for error in Draft202012Validator(schema).iter_errors(value):
        location = "/".join(map(str, error.absolute_path)) or "$"
        errors.append(f"schema:{location[:150]}:{error.validator}")
        if len(errors) == 20:
            break
    if errors:
        raise InvalidOutput(*errors)


def source_revision(directory, names):
    """对指定控制文件内容计算版本；未提交代码明确使用 source-sha256 而非伪造 Git SHA。"""
    root = Path(directory).resolve(strict=True)
    return "source-sha256:" + digest(canonical({name: digest((root / name).read_bytes()) for name in names}))


@dataclass
class InputView:
    """经执行器核验的输入，以及用于引用校验的原始 LF 行和变更行索引。"""
    root: Path
    metadata: dict
    manifest: dict
    lines: dict
    changed: dict
    diff_sha256: str

    @classmethod
    def load(cls, directory):
        """directory 必须是过滤输入；复用摘要及 diff 核验，不读取白名单外源码。"""
        root, metadata, diff = load_input(directory)
        manifest = read_json(root / "filter-manifest.json")
        lines = {revision: {name: git_lines((root / revision / name).read_bytes())
                            for name in manifest["file_sha256"][revision]} for revision in ("current", "parent")}
        changed = {revision: {} for revision in lines}
        # 与 unified_diff 使用同一 SequenceMatcher 默认策略，避免重复行导致锚点错位。
        for name in metadata["changed_files"]:
            before, after = lines["parent"].get(name, []), lines["current"].get(name, [])
            for kind, a, b, c, d in difflib.SequenceMatcher(None, before, after).get_opcodes():
                if kind != "equal":
                    changed["parent"].setdefault(name, set()).update(range(a + 1, b + 1))
                    changed["current"].setdefault(name, set()).update(range(c + 1, d + 1))
        return cls(root, metadata, manifest, lines, changed, digest(diff.encode("utf-8")))


def successful_reads(job, view):
    """按成对事件及固定快照重建安全读取范围；只认可工具能够完整返回的请求窗口。"""
    ready = read_json(job / "tools-ready.json")
    if sorted(ready.get("active_tools", [])) != TOOLS:
        raise InvalidOutput("unexpected_tool_set")
    trace = job / "tool-events.jsonl"
    if trace.stat().st_size > 2 * 1024 * 1024:
        raise InvalidOutput("trace_too_large")
    calls, reads = {}, {}
    for line in trace.read_text(encoding="utf-8").split("\n"):
        if not line:
            continue
        event = parse_json(line)
        kind = event.get("event")
        if kind == "blocked_tool" or (kind in ("tool_call", "tool_end") and event.get("tool") not in TOOLS):
            raise InvalidOutput("unexpected_tool_attempt")
        if kind == "tool_call":
            calls[event.get("id")] = event
        if kind == "tool_end" and event.get("tool") == "review_read" and event.get("is_error") is False:
            call = calls.get(event.get("id"))
            if not call or call.get("tool") != "review_read":
                raise InvalidOutput("unmatched_read_event")
            args = call.get("input", {})
            revision, name = args.get("revision"), args.get("path")
            if revision not in view.lines or name not in view.lines[revision]:
                raise InvalidOutput("read_event_outside_snapshot")
            start, count = args.get("start", 1), args.get("count", 100)
            if type(start) is not int or type(count) is not int or start < 1 or not 1 <= count <= 200:
                raise InvalidOutput("read_event_invalid_window")
            lines = view.lines[revision][name]
            if start > max(len(lines), 1):
                raise InvalidOutput("read_event_invalid_window")
            window = lines[start - 1:start - 1 + count]
            texts = [line[:-1] if line.endswith("\n") else line for line in window]
            covered = reads.setdefault((revision, name), set())
            # 工具每行最多 2000 个 UTF-16 单位、每次源码文本最多 16000 字节。
            # 历史事件没有保存正文；只有整个窗口确定未截断时才认可范围，避免猜测部分结果。
            if (all(len(text.encode("utf-16-le")) <= 4000 for text in texts)
                    and sum(len(text.encode("utf-8")) for text in texts) <= 16000):
                covered.update(range(start, start + len(window)))
    if not reads:
        raise InvalidOutput("no_successful_code_read")
    return reads


def validate_candidate(candidate, view, reads):
    """校验候选结构、全部引用和变更锚点；任何一项错误均拒绝整份候选。"""
    schema_check(candidate, CANDIDATE_SCHEMA)
    if len(canonical(candidate)) > 262144:
        raise InvalidOutput("candidate_too_large")
    identifiers = [finding["id"] for finding in candidate["findings"]]
    if len(set(identifiers)) != len(identifiers):
        raise InvalidOutput("duplicate_finding_id")

    def reference(ref, start, end):
        """ref 为版本、路径引用；起止行必须存在，且该文件实际成功读取过。"""
        revision, name = ref["revision"], ref["file"]
        if revision == "parent" and view.metadata["base_sha"] is None:
            raise InvalidOutput("root_commit_parent_reference")
        if name not in view.lines[revision]:
            raise InvalidOutput("reference_not_in_filtered_snapshot")
        if (type(start) is not int or type(end) is not int
                or not 1 <= start <= end <= len(view.lines[revision][name])):
            raise InvalidOutput("reference_line_out_of_range")
        if (revision, name) not in reads:
            raise InvalidOutput("reference_file_not_read")
        if not set(range(start, end + 1)) <= reads[(revision, name)]:
            raise InvalidOutput("reference_lines_not_read")

    for finding in candidate["findings"]:
        anchor = finding["change_anchor"]
        reference(anchor, anchor["line"], anchor["line"])
        if anchor["side"] != {"current": "right", "parent": "left"}[anchor["revision"]]:
            raise InvalidOutput("anchor_side_mismatch")
        if anchor["line"] not in view.changed[anchor["revision"]].get(anchor["file"], set()):
            raise InvalidOutput("anchor_not_changed_line")
        for evidence in finding["evidence"]:
            reference(evidence, evidence["start_line"], evidence["end_line"])
    unread = [f"{revision}:{name}" for name in view.metadata["changed_files"] for revision in ("current", "parent")
              if name in view.lines[revision] and ((revision, name) not in reads
                  or not view.changed[revision].get(name, set()) <= reads[(revision, name)])]
    coverage = {"unread_changed_files": unread, "has_filtered_inputs": bool(view.metadata.get("excluded_changed_files", view.metadata.get("excluded"))),
                "has_snapshot_skips": any(bool(value) for value in (view.metadata.get("source_snapshot_skips") or {}).values()),
                "reference_validation": "passed"}
    return coverage


def assess_attempt(directory, control_directory):
    """核对既有 Pi 任务及来源；返回校验结果，不覆盖原始执行记录或调用模型。"""
    job = Path(directory).absolute()
    execution = read_json(job / "execution.json")
    result = {"job": str(job), "execution": execution, "candidate": None, "coverage": None,
              "view": None, "error": None, "feedback": []}
    try:
        # 元数据来自控制程序；仍核验 SHA 和时间，不能使用模型填写的身份字段。
        for field in ("commit_sha", "base_sha"):
            if execution.get(field) is None and field == "base_sha":
                continue
            if not re.fullmatch(r"[a-f0-9]{40}|[a-f0-9]{64}", execution.get(field, "")):
                raise ValueError("执行记录缺少完整 SHA")
        started, finished = [datetime.fromisoformat(execution[field]) for field in ("started_at", "finished_at")]
        if any(value.utcoffset() != timezone.utc.utcoffset(value) for value in (started, finished)) or finished < started:
            raise ValueError("执行时间必须为有效 UTC 顺序")
        view = InputView.load(execution["input_directory"])
        result["view"] = view
        if (execution["commit_sha"] != view.metadata["commit_sha"] or execution["base_sha"] != view.metadata["base_sha"]
                or execution["diff_sha256"] != view.diff_sha256):
            raise ValueError("执行记录与过滤输入不一致")
        control = Path(control_directory)
        for field, name in (("rules_sha256", "REVIEW.md"), ("tools_sha256", "review-tools.ts")):
            if execution[field] != digest((control / name).read_bytes()):
                raise ValueError("执行记录与指定控制代码不一致")
        if execution.get("provider") != "deepseek" or not isinstance(execution.get("model"), str):
            raise ValueError("执行记录模型或版本无效")
        if execution["stage"] == "skipped":
            if view.metadata["changed_files"] or execution.get("pi_version") is not None:
                raise ValueError("跳过记录与源码变更或 Pi 版本不符")
            return result
        if execution["stage"] == "failed":
            result["error"] = "invalid_output" if execution.get("error") == "invalid_model_output" else execution.get("error", "pi_failed")
            result["feedback"] = [result["error"]]
            return result
        if execution.get("pi_version") != "1.5.2":
            raise ValueError("实际模型调用需要已验证的 Pi 版本")
        if execution["stage"] != "awaiting_report_validation" or execution.get("pi_returncode") != 0:
            raise ValueError("Pi 任务尚无可校验终态")
        try:
            candidate = read_json(job / "pi.stdout.txt")
        except (OSError, ValueError, TypeError):
            raise InvalidOutput("candidate_not_single_json_object") from None
        result["candidate"] = candidate
        if (job / "model-result.json").exists() and candidate != read_json(job / "model-result.json"):
            raise InvalidOutput("saved_candidate_mismatch")
        try:
            reads = successful_reads(job, view)
        except (OSError, ValueError, KeyError, TypeError) as error:
            if isinstance(error, InvalidOutput):
                raise
            raise InvalidOutput("tool_trace_invalid") from None
        result["coverage"] = validate_candidate(candidate, view, reads)
    except InvalidOutput as error:
        result.update(error="invalid_output", feedback=error.details)
    except (OSError, ValueError, KeyError, TypeError) as error:
        # 准备或记录错误也不能退化为无发现；异常原文不写入公开报告。
        result.update(error="input_or_execution_invalid", feedback=[type(error).__name__])
    return result


def validate_context(repository, branch=None, run_url=None):
    """核对控制程序提供的仓库、可空分支和 Actions 链接，不接受模型填写的发送身份。"""
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) or any(p in (".", "..") for p in repository.split("/")):
        raise ValueError("仓库必须为 owner/name")
    if branch is not None and (not branch or len(branch) > 255 or any(ord(c) < 32 for c in branch)):
        raise ValueError("分支配置无效")
    if run_url is not None and not re.fullmatch(r"https://github\.com/" + re.escape(repository) + r"/actions/runs/[0-9]+(?:/attempts/[0-9]+)?", run_url):
        raise ValueError("运行链接必须属于当前仓库的 GitHub Actions")


def review_key_for(identity):
    """从固定身份字段计算审查键；用于调用前预计算及保存结果后的相互核对。"""
    return digest(canonical({key: identity[key] for key in ("repository", "branch", "commit_sha", "base_sha",
        "schema_version", "controller_revision", "rules_sha256", "review_profile_sha256", "pi_version")}))


def review_identity(view, *, repository, branch, control_directory, model="deepseek-v4-pro", provider="deepseek",
                    pi_version="1.5.2", budget=480, rules_sha256=None, tools_sha256=None, commit_sha=None, base_sha=None):
    """用过滤输入和已选配置预计算完整身份；失败任务的未知版本可为 null，不调用模型。"""
    validate_context(repository, branch)
    control_files = ["pi_runner.py", "review-tools.ts", "REVIEW.md"]
    control_files += [name for name in ("review_one.py", "report.py", "storage.py", "review_batch.py",
                      "schemas/review-v1.json", "schemas/artifact-v1.json", "requirements.txt",
                      "git_source.py", "package-lock.json")
                      if (Path(control_directory) / name).is_file()]
    controller = source_revision(control_directory, control_files)
    validator = source_revision(ROOT, ["report.py", "schemas/review-v1.json"])
    rules = rules_sha256 or digest((Path(control_directory) / "REVIEW.md").read_bytes())
    tools = tools_sha256 or digest((Path(control_directory) / "review-tools.ts").read_bytes())
    profile = digest(canonical({"provider": provider, "model": model,
        "tools_sha256": tools, "validator_revision": validator,
        "input_manifest": view.manifest if view else None, "output_corrections": 1,
        "review_budget_seconds": budget}))
    identity = {"schema_version": "review.v1", "repository": repository, "branch": branch,
        "commit_sha": view.metadata["commit_sha"] if view else commit_sha,
        "base_sha": view.metadata["base_sha"] if view else base_sha, "controller_revision": controller,
        "validator_revision": validator, "rules_sha256": rules, "review_profile_sha256": profile,
        "pi_version": pi_version, "provider": provider, "model": model}
    identity["review_key"] = review_key_for(identity)
    return identity


def build_report(assessment, *, repository, branch, run_url, control_directory, attempt_id=None, started_at=None):
    """用受信任仓库配置与校验结果组装报告；模型无权填写 SHA、链接和终态。"""
    validate_context(repository, branch, run_url)
    execution, candidate, view = assessment["execution"], assessment["candidate"], assessment["view"]
    identity = review_identity(view, repository=repository, branch=branch, control_directory=control_directory,
        provider=execution["provider"], model=execution["model"], pi_version=execution.get("pi_version"),
        budget=execution.get("review_budget_seconds"), rules_sha256=execution["rules_sha256"],
        tools_sha256=execution["tools_sha256"], commit_sha=execution["commit_sha"], base_sha=execution["base_sha"])
    coverage = assessment["coverage"] or {"unread_changed_files": [], "has_filtered_inputs": bool(view and view.metadata.get("excluded_changed_files", view.metadata.get("excluded"))),
        "has_snapshot_skips": bool(view and any(bool(value) for value in (view.metadata.get("source_snapshot_skips") or {}).values())),
        "reference_validation": "not_performed"}
    if assessment["error"]:
        state, verdict = "failed", "inconclusive"
        summary, findings, questions = "未形成有效审查报告，错误分类：" + assessment["error"] + "。原始任务记录已保留。", [], []
        limits, complete = [GENERAL_LIMIT], None
    elif execution["stage"] == "skipped":
        state, verdict = "skipped", "inconclusive"
        summary = "过滤后没有可审查的源码变更，已跳过模型审查。"
        findings, questions, limits, complete = [], [], [GENERAL_LIMIT], None
    else:
        complete = candidate["review_complete"]
        gaps = bool(coverage["unread_changed_files"] or coverage["has_filtered_inputs"] or coverage["has_snapshot_skips"])
        state = "completed" if complete and not gaps else "incomplete"
        verdict = ("issues_found" if candidate["findings"] else "no_findings") if state == "completed" else "inconclusive"
        summary, findings, questions = candidate["summary"], candidate["findings"], candidate["open_questions"]
        limits = list(candidate["limitations"])
        if coverage["unread_changed_files"]:
            limits.append("部分变更文件未读取，或变更行没有完整读取记录，详见 coverage.unread_changed_files。")
        if coverage["has_filtered_inputs"]:
            limits.append("存在未覆盖的过滤路径，不能给出整个提交无问题的结论。")
        if coverage["has_snapshot_skips"]:
            limits.append("源码导出时存在跳过项，相关范围未完成审查。")
        if not complete:
            limits.append("模型标记审查未完成；已通过引用校验的发现仍予保留。")
        limits.append(GENERAL_LIMIT)
    # 系统追加限制也遵守同一上限；不能截断后静默遗漏模型限制。
    limits = list(dict.fromkeys(limits))
    report = {**identity, "attempt_id": attempt_id or uuid.uuid4().hex,
        "commit_url": f"https://github.com/{repository}/commit/{identity['commit_sha']}", "run_url": run_url,
        "review_status": state, "verdict": verdict, "summary": summary, "findings": findings,
        "open_questions": questions, "limitations": limits, "coverage": coverage, "model_review_complete": complete,
        "started_at": started_at or execution["started_at"], "finished_at": utc_now(), "error": assessment["error"], "notification_status": "not_requested"}
    schema_check(report, SCHEMA)
    return report


def markdown_text(value):
    """将模型文字作为可见文本显示，转义 HTML 和 Markdown 控制符。"""
    return re.sub(r"([\\`*_[\]{}()#!|>~])", r"\\\1", html.escape(str(value), quote=False))


def render_markdown(report):
    """根据已校验报告生成可读文本和 GitHub 源码链接，不接受模型提供的 URL。"""
    def source_link(ref, start, end):
        """按版本选择固定 SHA，使用 URL 编码的真实文件路径及 LF 行号。"""
        sha = report["commit_sha"] if ref["revision"] == "current" else report["base_sha"]
        label = f"{ref['revision']}:{ref['file']}:{start}" + (f"-{end}" if end != start else "")
        url = f"https://github.com/{report['repository']}/blob/{sha}/{quote(ref['file'], safe='/')}#L{start}"
        if end != start:
            url += f"-L{end}"
        return f"[{markdown_text(label)}]({url})"

    lines = ["# 提交审查报告", "", f"状态：**{report['review_status']} / {report['verdict']}**", "",
        f"提交：[{report['commit_sha']}]({report['commit_url']})", "",
        "分支：" + markdown_text(report["branch"] or "未指定（手动审查）"), "",
        markdown_text(report["summary"]), "", "## 正式发现", ""]
    if not report["findings"]:
        lines.append("本次没有有效的正式发现。" if report["review_status"] == "completed" else "未形成完整结论；不能据此判断提交无问题。")
    for finding in report["findings"]:
        anchor = finding["change_anchor"]
        lines += [f"### {finding['id']} [{finding['severity']}] {markdown_text(finding['title'])}", "",
            "变更位置：" + source_link(anchor, anchor["line"], anchor["line"]), "",
            "触发条件：" + markdown_text(finding["trigger"]), "", "影响：" + markdown_text(finding["impact"]), "",
            "建议：" + markdown_text(finding["suggestion"]), "", "证据：", ""]
        for evidence in finding["evidence"]:
            lines.append("- " + source_link(evidence, evidence["start_line"], evidence["end_line"]) + "：" + markdown_text(evidence["explanation"]))
        lines += ["", "验证说明：" + markdown_text(finding["validation"]["note"]), ""]
    for title, values in (("待验证事项", report["open_questions"]), ("覆盖限制", report["limitations"])):
        lines += ["", "## " + title, ""] + (["- " + markdown_text(value) for value in values] or ["无。"])
    coverage = report["coverage"]
    lines += ["", "## 程序覆盖检查", "", "- 源码引用校验：" + coverage["reference_validation"],
        "- 存在过滤路径：" + ("是" if coverage["has_filtered_inputs"] else "否"),
        "- 存在快照跳过项：" + ("是" if coverage["has_snapshot_skips"] else "否")]
    if coverage["unread_changed_files"]:
        lines += ["", "未取得完整变更行读取记录的文件：", ""]
        lines += ["- " + markdown_text(name) for name in coverage["unread_changed_files"]]
    lines += ["", "## 执行信息", "", "- 开始时间（UTC）：" + report["started_at"],
        "- 结束时间（UTC）：" + report["finished_at"], "- Pi：" + (report["pi_version"] or "未启动或版本未确认") + "；模型：" + markdown_text(report["model"]),
        "- 任务：" + report["attempt_id"], "- 审查键：" + report["review_key"], "", "报告基于静态源码分析，未执行项目代码或运行验证。", ""]
    return "\n".join(lines)


def save_json(file, value):
    """在本批新建目录中原子更新 JSON；只替换明确的单个输出文件。"""
    file = Path(file)
    temporary = file.with_name(file.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(file)


def write_report(directory, report, assessments, *, corrections=0):
    """在新建结果目录保存正式报告和独立终态，保留全部原始尝试路径。"""
    directory = Path(directory)
    directory.mkdir(mode=0o700)
    save_json(directory / "review.json", report)
    (directory / "review.md").write_bytes(render_markdown(report).encode("utf-8"))
    validation = report["coverage"]["reference_validation"]
    if validation != "passed" and report["review_status"] != "skipped":
        validation = "failed"
    save_json(directory / "execution.json", {"phase": "finished", "review_status": report["review_status"],
        "verdict": report["verdict"], "error": report["error"], "report_validation": validation,
        "started_at": report["started_at"], "finished_at": report["finished_at"], "output_corrections": corrections,
        "model_attempts": [{"directory": item["job"], "execution": item["execution"], "validation_error": item["error"],
                            "validation_feedback": item["feedback"]} for item in assessments
                           if item["execution"].get("stage") != "skipped"], "notification_status": "not_requested"})


def export_batch(attempts, output_root, *, repository, control_directory, branch=None, run_url=None, expected_commits=None):
    """离线整理已结束任务；批次清单逐项更新，预期但未处理的提交保持可见。"""
    assessments = [assess_attempt(job, control_directory) for job in attempts]
    commits = [item["execution"]["commit_sha"] for item in assessments]
    expected = list(expected_commits if expected_commits is not None else commits)
    if (len(set(commits)) != len(commits) or len(set(expected)) != len(expected) or not set(commits) <= set(expected)
            or any(not re.fullmatch(r"[a-f0-9]{40}|[a-f0-9]{64}", sha) for sha in expected)):
        raise ValueError("批次提交集合无效或重复")
    Path(output_root).mkdir(parents=True, exist_ok=True)
    batch = Path(tempfile.mkdtemp(prefix="report-batch-", dir=Path(output_root).resolve()))
    batch.chmod(0o700)
    manifest = {"schema_version": "manifest.v1", "batch_id": batch.name, "repository": repository,
        "expected_commits": expected, "reports": [], "unprocessed_commits": expected.copy(), "notification_status": "not_requested"}
    save_json(batch / "manifest.json", manifest)
    for assessment in assessments:
        report = build_report(assessment, repository=repository, branch=branch, run_url=run_url, control_directory=control_directory)
        directory = batch / (report["commit_sha"][:12] + "-" + report["attempt_id"])
        write_report(directory, report, [assessment])
        manifest["reports"].append({"commit_sha": report["commit_sha"], "attempt_id": report["attempt_id"],
            "review_key": report["review_key"], "review_status": report["review_status"], "verdict": report["verdict"],
            "error": report["error"], "files": {name: str((directory / name).relative_to(batch)) for name in ("review.json", "review.md", "execution.json")}})
        manifest["unprocessed_commits"].remove(report["commit_sha"])
        save_json(batch / "manifest.json", manifest)
    return batch, manifest


def main():
    """CLI 只整理本地保存的模型任务；失败或未完成报告以非零退出码提示。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--attempt", action="append", required=True)
    parser.add_argument("--output-root", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--control-directory", required=True)
    parser.add_argument("--branch")
    parser.add_argument("--run-url")
    parser.add_argument("--expected-commit", action="append")
    args = parser.parse_args()
    batch, manifest = export_batch(args.attempt, args.output_root, repository=args.repository,
        control_directory=args.control_directory, branch=args.branch, run_url=args.run_url, expected_commits=args.expected_commit)
    print(json.dumps({"batch_directory": str(batch), "reports": [{k: item[k] for k in ("commit_sha", "review_status", "verdict", "error")} for item in manifest["reports"]],
                      "unprocessed_commits": manifest["unprocessed_commits"]}, ensure_ascii=False, indent=2))
    return 0 if not manifest["unprocessed_commits"] and all(item["review_status"] == "completed" for item in manifest["reports"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
