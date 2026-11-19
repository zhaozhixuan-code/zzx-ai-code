from pathlib import Path
import subprocess
import tempfile
import unittest

from git_source import GitSourceError, enumerate_commits, prepare_input
from pi_runner import load_input


class GitSourceTests(unittest.TestCase):
    """通过实际 Git 对象覆盖版本范围和输入边界。"""

    def setUp(self):
        """新建独立仓库和带源码的根提交。"""
        self.root = Path(tempfile.mkdtemp(prefix="review-source-test-"))
        self.repo = self.root / "repo"
        self.command("init", "-q", "-b", "main", "--template=", str(self.repo), in_repo=False)
        self.write("src/旧 源码.py", "value = 1\n")
        self.root_sha = self.commit("root")
        self.context = {"repository": "owner/repo", "branch": "main"}

    def command(self, *args, in_repo=True):
        """执行测试仓库中的 Git 操作，返回命令输出。"""
        command = ["git", "-c", "user.name=Review Fixture", "-c", "user.email=fixture@example.invalid",
                   "-c", "commit.gpgSign=false", "-c", "core.autocrlf=false"]
        if in_repo:
            command.extend(["-C", str(self.repo)])
        return subprocess.check_output([*command, *args], stderr=subprocess.PIPE).decode("utf-8").strip()

    def write(self, name, content):
        """把字符串 content 写入测试仓库指定的相对路径 name。"""
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content.encode())

    def commit(self, message):
        """提交测试输入，返回完整提交 SHA。"""
        self.command("add", "--all")
        self.command("commit", "-qm", message)
        return self.command("rev-parse", "HEAD")

    def test_root_rename_delete_and_filtered_diff(self):
        """根提交使用空父目录，Unicode 重命名保留路径，过滤配置和密钥不进 diff。"""
        first = self.root / "first"
        self.assertIsNone(prepare_input(self.repo, self.root_sha, first))
        self.assertIn("--- /dev/null", load_input(first)[2])
        self.command("mv", "src/旧 源码.py", "src/新 源码.py")
        self.write("src/application.yml", "token: private test data\n")
        self.write("src/local.py", "password = 'fake-credential-for-filter'\n")
        renamed = self.commit("rename")
        second = self.root / "second"
        self.assertEqual(prepare_input(self.repo, renamed, second), self.root_sha)
        _, metadata, diff = load_input(second)
        self.assertEqual(metadata["renames"], [{"from": "src/旧 源码.py", "to": "src/新 源码.py"}])
        self.assertEqual(metadata["excluded_changed_files"], ["src/application.yml", "src/local.py"])
        self.assertNotIn("fake-credential", diff)
        self.assertNotIn("private test data", diff)
        self.command("rm", "src/新 源码.py")
        deleted = self.commit("delete")
        third = self.root / "third"
        self.assertEqual(prepare_input(self.repo, deleted, third), renamed)
        _, metadata, diff = load_input(third)
        self.assertIn("+++ /dev/null", diff)
        self.assertIn("src/application.yml", metadata["excluded"])
        self.assertEqual(metadata["excluded_changed_files"], [])
        self.assertTrue((second / "current" / "src" / "新 源码.py").is_file())

    def test_merge_and_force_push_ranges(self):
        """新增范围包含合并的侧枝，强推只排除旧头可达的提交。"""
        self.command("switch", "-qc", "side")
        self.write("src/side.py", "side = 1\n")
        side = self.commit("side")
        self.command("switch", "-q", "main")
        self.write("src/main.py", "main = 1\n")
        main = self.commit("main")
        self.command("merge", "--no-ff", "-qm", "merge", "side")
        merge = self.command("rev-parse", "HEAD")
        selection = enumerate_commits(self.repo, **self.context, base=self.root_sha, head=merge)
        self.assertEqual(set(selection["commits"]), {side, main, merge})
        self.assertEqual(selection["commits"][-1], merge)
        self.assertEqual(prepare_input(self.repo, merge, self.root / "merged"), main)
        event = {"ref": "refs/heads/main", "repository": {"full_name": "owner/repo"},
                 "before": main, "after": side, "forced": True, "commits": []}
        selection = enumerate_commits(self.repo, **self.context, event=event)
        self.assertEqual(selection["commits"], [side])
        self.assertTrue(selection["forced"])

    def test_new_deleted_and_missing(self):
        """新分支要求基准，分支删除被记录，缺失对象报错中保留原请求 SHA。"""
        event = {"ref": "refs/heads/main", "repository": {"full_name": "owner/repo"},
                 "before": "0" * 40, "after": self.root_sha}
        with self.assertRaisesRegex(GitSourceError, "需要提供基准"):
            enumerate_commits(self.repo, **self.context, event=event)
        self.assertEqual(enumerate_commits(self.repo, **self.context, event=event, new_branch_all=True)["commits"],
                         [self.root_sha])
        event.update(before=self.root_sha, after="0" * 40, deleted=True)
        self.assertEqual(enumerate_commits(self.repo, **self.context, event=event)["status"], "deleted")
        missing = "f" * 40
        with self.assertRaisesRegex(GitSourceError, missing):
            enumerate_commits(self.repo, **self.context, single=missing)


if __name__ == "__main__":
    unittest.main()
