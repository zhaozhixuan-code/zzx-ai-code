"""报告与单次纠正的边界测试；只执行受控假 Pi，不调用模型或目标项目。"""
import copy
import difflib
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import report
from pi_runner import digest, git_lines, utc_now
from review_one import run_one


class ReportFixture(unittest.TestCase):
    """报告及纠正测试共用的独立输入、执行记录和候选结果。"""

    def setUp(self):
        """创建含 CRLF、Unicode 路径和未修改证据文件的独立模拟输入。"""
        self.root = Path(tempfile.mkdtemp(prefix="s06-report-test-"))
        self.input = self.root / "input"
        self.name = "src/含 空格.java"
        self.files = {
            "parent": {self.name: b"package demo;\r\nint old = 1;\r\nint stable = 3;\r\n", "src/Guard.java": b"boolean valid = true;\n"},
            "current": {self.name: b"package demo;\r\nint fresh = 2;\r\nint stable = 3;\r\n", "src/Guard.java": b"boolean valid = true;\n"},
        }
        self.write_input()
        self.candidate = {"review_complete": True, "summary": "审查候选结果。", "findings": [{
            "id": "F001", "severity": "P2", "title": "受控缺陷", "trigger": "特定输入到达变化分支。",
            "change_anchor": {"revision": "current", "file": self.name, "line": 2, "side": "right"},
            "evidence": [{"revision": "current", "file": "src/Guard.java", "start_line": 1, "end_line": 1, "explanation": "未修改文件也可作为相关证据。"}],
            "impact": "产生错误结果。", "suggestion": "恢复必要校验。",
            "validation": {"kind": "static_analysis", "executed": False, "note": "仅静态分析，未执行项目。"}
        }], "open_questions": [], "limitations": []}

    def write_input(self, *, root_commit=False, excluded=None):
        """按精确字节生成哈希与差异；可构造根提交、过滤范围或文件变化。"""
        self.input.mkdir(exist_ok=True)
        hashes = {revision: {} for revision in self.files}
        for revision, files in self.files.items():
            (self.input / revision).mkdir(exist_ok=True)
            for name, content in files.items():
                target = self.input / revision / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
                hashes[revision][name] = digest(content)
        diff, changed = [], []
        for name in sorted(set(self.files["parent"]) | set(self.files["current"])):
            before, after = self.files["parent"].get(name), self.files["current"].get(name)
            if before == after:
                continue
            changed.append(name)
            for line in difflib.unified_diff(git_lines(before), git_lines(after),
                    fromfile="a/" + name if before is not None else "/dev/null",
                    tofile="b/" + name if after is not None else "/dev/null", n=20):
                diff.append(line if line.endswith("\n") else line + "\n\\ No newline at end of file\n")
        (self.input / "commit.diff").write_bytes("".join(diff).encode("utf-8"))
        self.metadata = {"commit_sha": "a" * 40, "base_sha": None if root_commit else "b" * 40,
            "policy": "s06-controlled-fixture", "file_sha256": hashes, "changed_files": changed,
            "excluded": excluded or {}, "limitations": [], "source_snapshot_skips": {"current": [], "parent": []}}
        report.save_json(self.input / "filter-manifest.json", self.metadata)

    def reads(self):
        """返回所有模拟快照文件的读取记录，默认包含当前和父版本。"""
        return {(revision, name) for revision, files in self.files.items() for name in files}

    def make_job(self, candidate=None, *, reads=None, failure=None, raw=None):
        """构造受信任执行器会生成的任务文件；模型文字和执行状态分别保存。"""
        job = Path(tempfile.mkdtemp(prefix="job-", dir=self.root))
        selected = self.candidate if candidate is None else candidate
        text = json.dumps(selected, ensure_ascii=False) if raw is None else raw
        (job / "pi.stdout.txt").write_text(text, encoding="utf-8")
        report.save_json(job / "tools-ready.json", {"active_tools": report.TOOLS})
        events = []
        for index, (revision, name) in enumerate(sorted(self.reads() if reads is None else reads)):
            events += [{"event": "tool_call", "id": str(index), "tool": "review_read", "input": {"revision": revision, "path": name}},
                       {"event": "tool_end", "id": str(index), "tool": "review_read", "is_error": False}]
        (job / "tool-events.jsonl").write_text("".join(json.dumps(event) + "\n" for event in events), encoding="utf-8")
        execution = {"stage": "failed" if failure else "awaiting_report_validation", "commit_sha": self.metadata["commit_sha"],
            "base_sha": self.metadata["base_sha"], "input_directory": str(self.input), "started_at": utc_now(), "finished_at": utc_now(),
            "provider": "deepseek", "model": "deepseek-v4-pro", "pi_version": "1.5.2", "pi_returncode": 143 if failure else 0,
            "rules_sha256": digest((report.ROOT / "REVIEW.md").read_bytes()), "tools_sha256": digest((report.ROOT / "review-tools.ts").read_bytes()),
            "diff_sha256": digest((self.input / "commit.diff").read_bytes())}
        if failure:
            execution["error"] = failure
        report.save_json(job / "execution.json", execution)
        return job

    def assess(self, job):
        """使用当前控制版本离线校验模拟任务，不改变其原始文件。"""
        return report.assess_attempt(job, report.ROOT)

    def build(self, assessment):
        """使用程序指定的仓库身份生成正式报告，分支与 Actions 链接为空。"""
        return report.build_report(assessment, repository="owner/repository", branch=None, run_url=None, control_directory=report.ROOT)


class ReportTests(ReportFixture):
    """验证协议、真实行号、变更位置、覆盖状态与批次输出。"""

    def test_valid_findings_and_exact_source_links(self):
        """CRLF 行号和未修改证据有效，链接固定到对应 SHA 且编码 Unicode 路径。"""
        result = self.build(self.assess(self.make_job()))
        self.assertEqual((result["review_status"], result["verdict"]), ("completed", "issues_found"))
        markdown = report.render_markdown(result)
        self.assertIn("/" + "a" * 40 + "/src/%E5%90%AB%20%E7%A9%BA%E6%A0%BC.java#L2", markdown)
        self.assertEqual(result["notification_status"], "not_requested")

    def test_invalid_paths_ranges_and_diff_context_rejected(self):
        """拒绝路径逃逸、错误版本路径、越界/反向行号、上下文行和左右侧错误。"""
        mutations = [
            lambda f: f["change_anchor"].update(file="../secret"),
            lambda f: f["change_anchor"].update(file="/etc/passwd"),
            lambda f: f["change_anchor"].update(file="src\\Guard.java"),
            lambda f: f["change_anchor"].update(line=100),
            lambda f: f["change_anchor"].update(line=2.0),
            lambda f: f["change_anchor"].update(line=3),
            lambda f: f["change_anchor"].update(side="left"),
            lambda f: f["evidence"][0].update(start_line=2, end_line=1),
        ]
        for change in mutations:
            candidate = copy.deepcopy(self.candidate)
            change(candidate["findings"][0])
            with self.subTest(candidate=candidate):
                self.assertEqual(self.assess(self.make_job(candidate))["error"], "invalid_output")

    def test_schema_types_enums_lengths_and_duplicate_ids(self):
        """协议限制作用于完整对象，不能保留无效发现后宣称无问题。"""
        mutations = [lambda c: c.update(extra="not permitted"), lambda c: c.update(summary="x" * 4001),
            lambda c: c["findings"][0].update(severity="P3"), lambda c: c["findings"][0].update(evidence=[]),
            lambda c: c["findings"][0]["validation"].update(executed=True), lambda c: c["findings"].append(copy.deepcopy(c["findings"][0]))]
        for change in mutations:
            candidate = copy.deepcopy(self.candidate)
            change(candidate)
            self.assertEqual(self.assess(self.make_job(candidate))["error"], "invalid_output")

    def test_mixed_or_nonstandard_json_rejected(self):
        """围栏、拼接对象、重复字段及 NaN 均不能宽松提取或接受。"""
        for raw in ("```json\n{}\n```", "{}{}", '{"a":1,"a":2}', '{"a":NaN}', ""):
            self.assertEqual(self.assess(self.make_job(raw=raw))["error"], "invalid_output")

    def test_root_commit_parent_reference_rejected(self):
        """根提交不能引用不存在的父版本，即使路径在当前版本存在。"""
        self.files["parent"] = {}
        self.write_input(root_commit=True)
        candidate = copy.deepcopy(self.candidate)
        candidate["findings"][0]["change_anchor"].update(revision="parent", side="left")
        self.assertIn("root_commit_parent_reference", self.assess(self.make_job(candidate))["feedback"])

    def test_deleted_and_renamed_paths_use_matching_snapshot(self):
        """删除使用 parent/left，重命名对应的新路径可使用 current/right。"""
        self.files = {"parent": {"old name.java": b"old\n"}, "current": {"new name.java": b"new\n"}}
        self.write_input()
        for revision, name, side in (("parent", "old name.java", "left"), ("current", "new name.java", "right")):
            candidate = copy.deepcopy(self.candidate)
            candidate["findings"][0]["change_anchor"].update(revision=revision, file=name, side=side, line=1)
            candidate["findings"][0]["evidence"] = [{"revision": revision, "file": name, "start_line": 1, "end_line": 1, "explanation": "对应版本的真实源码。"}]
            self.assertIsNone(self.assess(self.make_job(candidate))["error"])
        candidate["findings"][0]["change_anchor"].update(file="old name.java")
        self.assertEqual(self.assess(self.make_job(candidate))["error"], "invalid_output")

    def test_missing_evidence_read_rejected(self):
        """源码存在不等于模型实际取证；未读证据文件不能作为有效引用。"""
        reads = {("current", self.name), ("parent", self.name)}
        self.assertIn("reference_file_not_read", self.assess(self.make_job(reads=reads))["feedback"])

    def test_reference_outside_actual_read_window_rejected(self):
        """只读取第一行不能引用第二行；防止用文件级读取记录冒充实际证据。"""
        job = self.make_job()
        trace = job / "tool-events.jsonl"
        events = [json.loads(line) for line in trace.read_text().splitlines()]
        for event in events:
            if event["event"] == "tool_call" and event["input"]["revision"] == "current" and event["input"]["path"] == self.name:
                event["input"].update(start=1, count=1)
        trace.write_text("".join(json.dumps(event) + "\n" for event in events), encoding="utf-8")
        self.assertIn("reference_lines_not_read", self.assess(job)["feedback"])

    def test_incomplete_preserves_valid_findings(self):
        """过滤路径或模型承认缺口时保留已验证问题，整体仍是 inconclusive。"""
        self.write_input(excluded={"application.yml": "configuration"})
        result = self.build(self.assess(self.make_job()))
        self.assertEqual((result["review_status"], result["verdict"]), ("incomplete", "inconclusive"))
        self.assertEqual(len(result["findings"]), 1)

    def test_no_findings_requires_changed_files_read(self):
        """关键变更未读取不能变成 no_findings；完整反例则可完成且无发现。"""
        candidate = copy.deepcopy(self.candidate)
        candidate["findings"] = []
        result = self.build(self.assess(self.make_job(candidate, reads={("current", "src/Guard.java")})))
        self.assertEqual(result["review_status"], "incomplete")
        complete = self.build(self.assess(self.make_job(candidate)))
        self.assertEqual(complete["verdict"], "no_findings")
        candidate["review_complete"] = False
        self.assertEqual(self.build(self.assess(self.make_job(candidate)))["review_status"], "incomplete")

    def test_truncated_window_cannot_complete_changed_lines(self):
        """超过工具单行上限的读取不能冒充完整证据，空发现仍应标记未完成。"""
        self.files["current"][self.name] = b"first\n" + b"x" * 2001 + b"\n"
        self.write_input()
        candidate = copy.deepcopy(self.candidate)
        candidate["findings"] = []
        result = self.build(self.assess(self.make_job(candidate)))
        self.assertEqual((result["review_status"], result["verdict"]), ("incomplete", "inconclusive"))

    def test_timeout_and_tampered_input_cannot_pass(self):
        """真实超时分类保留，快照内容被替换时明确失败。"""
        result = self.build(self.assess(self.make_job(failure="timeout", raw="")))
        self.assertEqual((result["review_status"], result["verdict"], result["error"]), ("failed", "inconclusive", "timeout"))
        job = self.make_job()
        (self.input / "current" / self.name).write_bytes(b"tampered\n")
        self.assertEqual(self.build(self.assess(job))["review_status"], "failed")

    def test_batch_contains_reports_and_unprocessed_commits(self):
        """报告三件套与批次清单一致，未处理提交不能从清单消失。"""
        job = self.make_job()
        original = (job / "execution.json").read_bytes()
        batch, manifest = report.export_batch([job], self.root / "exports", repository="owner/repository",
            control_directory=report.ROOT, expected_commits=["a" * 40, "c" * 40])
        self.assertEqual(manifest["unprocessed_commits"], ["c" * 40])
        for name, relative in manifest["reports"][0]["files"].items():
            self.assertTrue((batch / relative).is_file(), name)
        self.assertEqual((job / "execution.json").read_bytes(), original)
        same = self.build(self.assess(job))
        self.assertEqual(manifest["reports"][0]["review_key"], same["review_key"])

    def test_markdown_treats_model_content_as_text(self):
        """HTML 和任意模型链接不作为可执行或可点击的报告结构。"""
        candidate = copy.deepcopy(self.candidate)
        candidate["summary"] = '<script>alert(1)</script> [bad](javascript:alert(1))'
        text = report.render_markdown(self.build(self.assess(self.make_job(candidate))))
        self.assertNotIn("<script>", text)
        self.assertNotIn("[bad](javascript:", text)
        self.assertIn("&lt;script&gt;", text)


@unittest.skipUnless(os.name == "posix", "Pi 进程组验证需要 Linux/POSIX")
class CorrectionTests(ReportFixture):
    """在同一个总预算下测试一次纠正、失败和不允许丢弃发现。"""

    def fake_pi(self, outputs, *, delay_second=0, auth=False):
        """创建受控 CLI；outputs 为每轮最终文本，记录调用次数而不访问外部模型。"""
        script = self.root / "fake_pi.py"
        calls = self.root / "fake-calls.txt"
        program = '''import json, os, sys, time
from pathlib import Path
OUTPUTS = %r
CALLS = Path(%r)
if '--version' in sys.argv:
    print('1.5.2')
    raise SystemExit(0)
ordinal = len(CALLS.read_text().splitlines()) if CALLS.exists() else 0
with CALLS.open('a') as stream: stream.write('call\\n')
job = Path(os.environ['REVIEW_RUN_DIR'])
request = Path(sys.argv[-1][1:]).read_text()
if ordinal:
    assert '<correction_data>' in request
    time.sleep(%r)
if %r:
    print('401: authentication_error')
    raise SystemExit(0)
view = Path(os.environ['REVIEW_INPUT'])
manifest = json.loads((view/'filter-manifest.json').read_text())
(job/'tools-ready.json').write_text(json.dumps({'active_tools':['review_list','review_read','review_search']}))
events = []
for revision, files in manifest['file_sha256'].items():
    for name in files:
        identity = str(len(events))
        events += [{'event':'tool_call','id':identity,'tool':'review_read','input':{'revision':revision,'path':name}},
                   {'event':'tool_end','id':identity,'tool':'review_read','is_error':False}]
(job/'tool-events.jsonl').write_text(''.join(json.dumps(e)+'\\n' for e in events))
print(OUTPUTS[min(ordinal, len(OUTPUTS)-1)])
'''
        script.write_text(program % (outputs, str(calls), delay_second, auth), encoding="utf-8")
        return [sys.executable, str(script)], calls

    def run_fake(self, outputs, *, timeout=10, delay_second=0, auth=False):
        """执行新单提交入口，只向假 CLI 提供非真实测试 Key。"""
        command, calls = self.fake_pi(outputs, delay_second=delay_second, auth=auth)
        with patch.dict(os.environ, {"DEEPSEEK_API_KEY": "fixture-not-a-real-key"}):
            batch, result = run_one(self.input, self.root / "runs", repository="owner/repository", timeout=timeout, pi_command=command)
        return batch, result, len(calls.read_text().splitlines())

    def test_one_correction_succeeds_and_keeps_attempts(self):
        """第一次锚点左右侧错误，第二次修正；两份原始输出都保留。"""
        invalid = copy.deepcopy(self.candidate)
        invalid["findings"][0]["change_anchor"]["side"] = "left"
        batch, result, count = self.run_fake([json.dumps(invalid), json.dumps(self.candidate)])
        self.assertEqual((count, result["review_status"]), (2, "completed"))
        manifest = report.read_json(batch / "manifest.json")
        execution = report.read_json(batch / manifest["reports"][0]["files"]["execution.json"])
        self.assertEqual(execution["output_corrections"], 1)
        self.assertEqual(len(execution["model_attempts"]), 2)

    def test_format_failure_can_be_corrected_once(self):
        """Pi 初步协议拒绝的围栏文本也进入唯一一次纠正流程。"""
        _, result, count = self.run_fake(["```json\n{}\n```", json.dumps(self.candidate)])
        self.assertEqual((count, result["review_status"]), (2, "completed"))

    def test_second_invalid_output_fails_without_third_call(self):
        """第二次仍无效时不无限重试或转换为无发现。"""
        _, result, count = self.run_fake(["{}", "{}"])
        self.assertEqual((count, result["review_status"], result["error"]), (2, "failed", "invalid_output"))

    def test_correction_cannot_drop_findings(self):
        """通过删掉无效问题得到空数组也必须失败，不能冒充审查通过。"""
        invalid = copy.deepcopy(self.candidate)
        invalid["findings"][0]["change_anchor"]["line"] = 100
        empty = copy.deepcopy(self.candidate)
        empty["findings"] = []
        _, result, count = self.run_fake([json.dumps(invalid), json.dumps(empty)])
        self.assertEqual((count, result["review_status"], result["error"]), (2, "failed", "invalid_output"))

    def test_unparseable_output_cannot_be_cleared_by_empty_correction(self):
        """首轮无法解析时，空数组无法证明保留了原问题，保守标记失败。"""
        empty = copy.deepcopy(self.candidate)
        empty["findings"] = []
        _, result, count = self.run_fake(["发现权限问题，但输出不符合 JSON。", json.dumps(empty)])
        self.assertEqual((count, result["error"]), (2, "invalid_output"))

    def test_authentication_failure_is_not_output_correction(self):
        """认证失败不触发额外模型调用。"""
        _, result, count = self.run_fake(["unused"], auth=True)
        self.assertEqual((count, result["error"]), (1, "authentication_failed"))

    def test_missing_key_produces_failure_without_starting_pi(self):
        """未提供模型 Key 时保存失败报告，不能因缺少 Pi 版本而在报告阶段崩溃。"""
        with patch.dict(os.environ, {}, clear=True):
            batch, result = run_one(self.input, self.root / "no-key-runs", repository="owner/repository")
        self.assertEqual((result["review_status"], result["error"]), ("failed", "authentication_failed"))
        self.assertIsNone(result["pi_version"])
        self.assertTrue((batch / "manifest.json").is_file())

    def test_correction_uses_remaining_total_budget(self):
        """第二轮等待超出剩余预算时整体超时，不再获得一个完整新预算。"""
        started = time.monotonic()
        _, result, count = self.run_fake(["{}", json.dumps(self.candidate)], timeout=1.5, delay_second=5)
        self.assertEqual((count, result["error"]), (2, "timeout"))
        self.assertLess(time.monotonic() - started, 2.5)


if __name__ == "__main__":
    unittest.main(verbosity=2)
