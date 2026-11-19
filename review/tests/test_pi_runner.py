"""执行器定向测试：仅运行受控的假 Pi 进程，不调用模型或待审查项目。"""
import difflib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "pi_runner.py"
SPEC = importlib.util.spec_from_file_location("pi_runner", MODULE_PATH)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


@unittest.skipUnless(os.name == "posix", "进程组执行验证需要 Linux/POSIX")
class RunnerTests(unittest.TestCase):
    """覆盖协议失败、隔离、总超时和子进程回收等执行边界。"""

    def setUp(self):
        """创建独立输入和结果目录，避免依赖用户已有快照。"""
        self.root = Path(tempfile.mkdtemp(prefix="s05-runner-test-"))
        self.input = self.root / "input"
        self.input.mkdir()
        files = {"parent": b"old\n", "current": b"new\n"}
        hashes = {}
        for revision, content in files.items():
            (self.input / revision).mkdir()
            (self.input / revision / "Example.java").write_bytes(content)
            hashes[revision] = {"Example.java": runner.digest(content)}
        diff = "".join(difflib.unified_diff(["old\n"], ["new\n"], fromfile="a/Example.java", tofile="b/Example.java", n=20))
        (self.input / "commit.diff").write_text(diff, encoding="utf-8")
        (self.input / "filter-manifest.json").write_text(json.dumps({
            "commit_sha": "a" * 40, "base_sha": "b" * 40,
            "file_sha256": hashes, "changed_files": ["Example.java"],
            "policy": "fixture", "excluded": {}, "limitations": [], "source_snapshot_skips": {},
        }), encoding="utf-8")
        self.key = "fixture-credential-not-real"
        self.environment = patch.dict(os.environ, {
            "DEEPSEEK_API_KEY": self.key, "GITHUB_TOKEN": "must-not-inherit",
            "FEISHU_WEBHOOK_URL": "must-not-inherit", "UNRELATED_SECRET": "must-not-inherit",
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def fake_pi(self, mode):
        """创建指定行为的假 CLI；模式固化在脚本，不通过子进程环境传递。"""
        file = self.root / (mode + ".py")
        program = '''import json, os, subprocess, sys, time
from pathlib import Path
MODE = %r
if '--version' in sys.argv:
    print('1.5.2')
    raise SystemExit(0)
job = Path(os.environ['REVIEW_RUN_DIR'])
config_dir = Path(os.environ['PI_CODING_AGENT_DIR'])
assert (config_dir / 'settings.json').is_file()
(job / 'config-path.txt').write_text(str(config_dir))
if MODE == 'custom':
    provider = sys.argv[sys.argv.index('--provider') + 1]
    assert provider == 'gateway'
    assert sys.argv[sys.argv.index('--model') + 1] == 'gateway-flash'
    assert json.loads((config_dir / 'models.json').read_text())['providers'][provider]['baseUrl'] == 'https://gateway.example/v1'
    key = json.loads((config_dir / 'auth.json').read_text())[provider]['key']
    print('401: authentication_error ' + key)
    raise SystemExit(0)
(job / 'inherited-keys.json').write_text(json.dumps(sorted(os.environ)))
with Path(__file__).with_suffix('.calls').open('a') as stream:
    stream.write('called\\n')
if MODE == 'timeout':
    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
    (job / 'child.pid').write_text(str(child.pid))
    time.sleep(60)
if MODE == 'authentication':
    print('401: authentication_error ' + os.environ['DEEPSEEK_API_KEY'])
    raise SystemExit(0)
if MODE == 'invalid':
    print('```json\\n{}\\n```')
    raise SystemExit(0)
if MODE != 'missing_ready':
    (job / 'tools-ready.json').write_text(json.dumps({'active_tools':['review_list','review_read','review_search']}))
if MODE != 'no_read':
    events = [
      {'event':'tool_call','tool':'review_read'},
      {'event':'tool_end','tool':'review_read','is_error':False},
    ]
    if MODE == 'unexpected':
        events.append({'event':'tool_end','tool':'bash','is_error':True})
    (job / 'tool-events.jsonl').write_text(''.join(json.dumps(e)+'\\n' for e in events))
print(json.dumps({'review_complete':True,'summary':'fixture result','findings':[],'open_questions':[],'limitations':[]}))
'''
        file.write_text(program % mode, encoding="utf-8")
        return file

    def execute(self, mode="success", timeout=10, model_profile=None):
        """调用实际执行器，结果保留在测试临时目录内。"""
        return runner.run_review(self.input, self.root / "results", timeout=timeout,
                                 pi_command=[sys.executable, str(self.fake_pi(mode))], model_profile=model_profile)

    def test_success_and_credentials_isolation(self):
        """候选结果不等于已通过报告校验，且业务凭据不能进入 Pi 环境。"""
        result = self.execute()
        self.assertEqual(result["stage"], "awaiting_report_validation")
        self.assertEqual(result["report_validation"], "not_performed")
        keys = json.loads((Path(result["attempt_directory"]) / "inherited-keys.json").read_text())
        self.assertIn("DEEPSEEK_API_KEY", keys)
        self.assertNotIn("GITHUB_TOKEN", keys)
        self.assertNotIn("FEISHU_WEBHOOK_URL", keys)
        self.assertNotIn("UNRELATED_SECRET", keys)

    def test_authentication_not_retried_and_scrubbed(self):
        """即使 Pi 退出码为零，认证错误仍失败，且原始密钥不保留在诊断中。"""
        result = self.execute("authentication")
        self.assertEqual(result["error"], "authentication_failed")
        self.assertEqual((self.root / "authentication.calls").read_text().count("called"), 1)
        self.assertNotIn(self.key, (Path(result["attempt_directory"]) / "pi.stdout.txt").read_text())

    def test_invalid_output(self):
        """带围栏的文本不能被宽松提取成有效 JSON。"""
        self.assertEqual(self.execute("invalid")["error"], "invalid_model_output")

    def test_no_ready_or_no_read(self):
        """扩展未就绪、未读取代码时，即使给出 JSON 也不能形成候选成功。"""
        self.assertEqual(self.execute("missing_ready")["error"], "tool_initialization_failed")
        self.assertEqual(self.execute("no_read")["error"], "no_code_read")

    def test_unknown_tool(self):
        """SDK 拒绝的未知工具尝试也必须在执行记录中失败。"""
        self.assertEqual(self.execute("unexpected")["error"], "unexpected_tool_attempt")

    def test_diff_mismatch(self):
        """模型调用前拒绝被替换的 diff，而不是相信文件名代表过滤完成。"""
        (self.input / "commit.diff").write_text("unexpected input")
        result = self.execute()
        self.assertEqual(result["stage"], "failed")
        self.assertFalse((self.root / "success.calls").exists())

    def test_new_attempt_has_new_configuration(self):
        """重复执行同一输入必须使用不同的会话、工作目录和执行记录。"""
        first, second = self.execute(), self.execute()
        self.assertNotEqual(first["attempt_directory"], second["attempt_directory"])
        for result in (first, second):
            config = Path((Path(result["attempt_directory"]) / "config-path.txt").read_text())
            self.assertFalse(config.exists())

    def test_custom_provider_uses_pi_model_and_auth_then_removes_temporary_files(self):
        profile = runner.PiModel("gateway", "gateway-flash", {"baseUrl": "https://gateway.example/v1"},
                                 {"type": "api_key", "key": self.key})
        result = self.execute("custom", model_profile=profile)
        job = Path(result["attempt_directory"])
        self.assertEqual(result["error"], "authentication_failed")
        self.assertEqual(result["provider"], "gateway")
        self.assertEqual(result["model_connection_sha256"], profile.connection_sha256)
        self.assertNotIn(self.key, (job / "pi.stdout.txt").read_text())
        self.assertFalse(Path((job / "config-path.txt").read_text()).exists())

    def test_timeout_reaps_child_group(self):
        """总超时终止假 Pi 与其子进程；僵尸状态不视为仍在执行。"""
        result = self.execute("timeout", timeout=1.5)
        self.assertEqual(result["error"], "timeout")
        child = int((Path(result["attempt_directory"]) / "child.pid").read_text())
        state = Path(f"/proc/{child}/stat")
        for _ in range(20):
            if not state.exists() or state.read_text().split()[2] == "Z":
                break
            time.sleep(0.05)
        else:
            self.fail("超时后子进程仍在运行")


class JsonProtocolTests(unittest.TestCase):
    """无需模型的完整文本 JSON 边界检查。"""

    def test_nonstandard_json_rejected(self):
        """拒绝重复字段、非标准数值和多个拼接 JSON 对象。"""
        for text in ('{"a":1,"a":2}', '{"a":NaN}', '{}{}'):
            with self.assertRaises(ValueError):
                runner.parse_json(text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
