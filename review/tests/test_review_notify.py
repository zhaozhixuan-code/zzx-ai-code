"""验证直发入口仅保留飞书摘要，且错误状态仍会被汇总。"""
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import review_notify
import review_one


class DirectNotificationTests(unittest.TestCase):
    """用本地边界替身验证临时文件、摘要及投递结果的流转。"""

    def test_cleans_work_and_sends_one_summary_even_on_incomplete_review(self):
        """成功和未完成的提交共用一次直发，模型材料在投递前清理，正式报告不落盘。"""
        first, second = "a" * 40, "b" * 40
        report = {"review_status": "completed", "error": None,
                  "findings": [{"severity": "P1", "title": "错误路径遗漏检查"}]}
        incomplete = {"review_status": "incomplete", "error": None, "findings": []}
        raw_content = "model detail that must stay out of the summary"

        def prepare(source, sha, destination):
            """在 destination 创建测试输入；source 和 sha 由实际 Git 读取器消费。"""
            destination.mkdir()
            (destination / "commit.diff").write_text(raw_content, encoding="utf-8")
            (destination / "sha").write_text(sha, encoding="ascii")

        def model(input_directory, output_root, **_):
            """将 input_directory 的样例写到 output_root，返回待校验任务目录。"""
            output_root.mkdir()
            (output_root / "pi.stdout.txt").write_bytes((input_directory / "commit.diff").read_bytes())
            return {"attempt_directory": output_root}

        def load(directory):
            """读取 directory 中的测试输入及范围样例指定的提交 SHA。"""
            return directory, {"commit_sha": (directory / "sha").read_text(encoding="ascii")}, None

        with tempfile.TemporaryDirectory() as work:
            def post(url, body):
                """在收到 url 对应的签名请求时检查临时文件和精简正文，返回样例回执。"""
                self.assertEqual(url, "https://example.invalid/bot")
                self.assertEqual(list(Path(work).iterdir()), [])
                payload = json.loads(body)
                text = payload["content"]["text"]
                self.assertEqual(payload["msg_type"], "text")
                self.assertTrue(payload["sign"])
                self.assertIn("提交 2 · 完成 1 · 未完成 1", text)
                self.assertIn("P0 0 / P1 1 / P2 0", text)
                self.assertIn("P1 aaaaaaaa 错误路径遗漏检查", text)
                self.assertNotIn(raw_content, text)
                self.assertNotIn("https://", text)
                return {"status": "sent", "business_code": 0}

            context = {"GITHUB_REPOSITORY": "owner/repo", "GITHUB_REF_NAME": "master", "RUNNER_TEMP": work}
            selected = {"commits": [first, second], "head": second}
            destination = ("https://example.invalid/bot", "test-secret", "test")
            with (patch.dict(review_notify.os.environ, context),
                  patch("sys.argv", ["review_notify.py", "--commit", second, "--send"]),
                  patch.object(review_notify, "read_destination", return_value=destination),
                  patch.object(review_notify, "select_commits", return_value=selected),
                  patch.object(review_notify.PiModel, "load", return_value=object()),
                  patch.object(review_notify, "prepare_input", side_effect=prepare),
                  patch.object(review_one, "load_input", side_effect=load),
                  patch.object(review_one, "run_review", side_effect=model),
                  patch.object(review_one, "assess_attempt", return_value={"error": None}),
                  patch.object(review_one, "build_report", side_effect=[report, incomplete]),
                  patch.object(review_one, "write_report") as write_report,
                  patch.object(review_one, "save_json") as write_manifest,
                  patch.object(review_notify, "post_message", side_effect=post) as webhook,
                  redirect_stdout(io.StringIO()) as log):
                self.assertEqual(review_notify.main(), 1)
                write_report.assert_not_called()
                write_manifest.assert_not_called()
                webhook.assert_called_once()
                self.assertNotIn(raw_content, log.getvalue())
                self.assertNotIn("错误路径遗漏检查", log.getvalue())
