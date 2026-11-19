"""归档、GitHub 分页/下载及复用补跑测试；使用受控响应和假 Pi，不调用外部模型。"""
import copy
from datetime import datetime, timedelta, timezone
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import report
from pi_model import PiModel
from review_batch import run_batch
from review_one import run_one
import storage
import test_report as report_tests


class ScriptedTransport:
    """按顺序返回有限 HTTP 响应，记录请求头以验证认证没有发给下载主机。"""

    def __init__(self, responses):
        """responses 为状态、响应头、字节正文的列表。"""
        self.responses, self.requests = list(responses), []

    def get(self, url, headers, limit):
        """记录一次 GET；超出预设次数视为测试失败。"""
        self.requests.append((url, headers, limit))
        if not self.responses:
            raise AssertionError("unexpected extra HTTP request")
        return self.responses.pop(0)


class StorageFixture(report_tests.ReportFixture):
    """沿用 S06 的独立源码及可信任务结构，不修改仓库控制文件。"""

    def setUp(self):
        """初始化 UTC 时钟、模型调用计数和本地归档目录。"""
        super().setUp()
        self.now = datetime.now(timezone.utc)
        self.calls = 0
        self.store = self.root / "archive-store"

    def formal(self, *, candidate=None, failure=None, input_directory=None, budget=480):
        """生成与预计算身份一致的正式三件套；可模拟失败或另一个提交输入。"""
        job = self.make_job(candidate, failure=failure)
        execution = report.read_json(job / "execution.json")
        source = report.InputView.load(input_directory or self.input)
        execution.update(input_directory=str(source.root), commit_sha=source.metadata["commit_sha"], base_sha=source.metadata["base_sha"],
                         diff_sha256=source.diff_sha256, review_budget_seconds=budget)
        report.save_json(job / "execution.json", execution)
        assessment = report.assess_attempt(job, report.ROOT)
        result = self.build(assessment)
        directory = self.root / ("formal-" + uuid.uuid4().hex)
        report.write_report(directory, result, [assessment])
        return directory, result

    def bundle(self, **kwargs):
        """把正式报告打包，使用固定的测试 UTC 时钟。"""
        directory, result = self.formal(**kwargs)
        return storage.pack_report(directory, now=self.now), result

    def plan(self, *, second=False, missing=False):
        """创建单/多提交计划；可包含未准备输入的提交以检查未处理清单。"""
        expected = [self.metadata["commit_sha"]]
        inputs = [{"commit_sha": expected[0], "base_sha": self.metadata["base_sha"], "input_directory": str(self.input)}]
        if second:
            other = report_tests.ReportFixture()
            other.setUp()
            other.metadata["commit_sha"] = "c" * 40
            report.save_json(other.input / "filter-manifest.json", other.metadata)
            expected.append("c" * 40)
            inputs.append({"commit_sha": "c" * 40, "base_sha": other.metadata["base_sha"], "input_directory": str(other.input)})
        if missing:
            expected.append("d" * 40)
        file = self.root / ("plan-" + uuid.uuid4().hex + ".json")
        report.save_json(file, {"schema_version": "review-plan.v1", "repository": "owner/repository", "branch": None,
                               "expected_commits": expected, "inputs": inputs})
        return file

    def runner(self, outcomes=None):
        """返回无外部模型的执行器，按指定顺序生成成功、失败或未完成报告。"""
        outcomes = outcomes or ["completed"]

        def invoke(input_directory, output_root, *, repository, branch, run_url, timeout, model, model_profile):
            """参数与真实 run_one 一致；每次调用都保留独立模型任务和报告。"""
            state = outcomes[min(self.calls, len(outcomes) - 1)]
            self.calls += 1
            candidate = copy.deepcopy(self.candidate)
            if state == "incomplete":
                candidate["review_complete"] = False
            directory, result = self.formal(candidate=candidate, failure="timeout" if state == "failed" else None,
                                             input_directory=input_directory, budget=timeout)
            # 使用真实报告导出流程，将这份受控任务放到调用方指定的批次目录。
            source_job = report.read_json(directory / "execution.json")["model_attempts"][0]["directory"]
            execution_path = Path(source_job) / "execution.json"
            execution = report.read_json(execution_path)
            execution.update(provider=model_profile.provider, model=model,
                             model_connection_sha256=model_profile.connection_sha256)
            report.save_json(execution_path, execution)
            batch, manifest = report.export_batch([source_job], output_root, repository=repository,
                control_directory=report.ROOT, branch=branch, run_url=run_url)
            result = report.read_json(batch / manifest["reports"][0]["files"]["review.json"])
            return batch, result

        return invoke

    def remote_item(self, bundle, identity=1):
        """构造与 GitHub 官方 artifact 结构一致的必要字段。"""
        return {"id": identity, "name": storage.artifact_name(bundle.report), "expired": False,
            "created_at": bundle.index["created_at"], "expires_at": bundle.index["expires_at"],
            "size_in_bytes": len(bundle.archive), "digest": "sha256:" + report.digest(bundle.archive)}

    def page(self, items, *, next_page=False):
        """创建一页列表响应；Link 仅用于指示下一页，客户端不跟随任意 URL。"""
        headers = {"link": '<https://example.invalid/arbitrary>; rel="next"'} if next_page else {}
        return 200, headers, json.dumps({"total_count": len(items), "artifacts": items}).encode()


class ArtifactTests(StorageFixture):
    """验证本地归档、保留期和不可信 ZIP 边界。"""

    def test_complete_report_round_trip_and_upload_stage(self):
        """完整报告可保存、读取和准备上传目录，内容不含快照或模型会话。"""
        bundle, expected = self.bundle()
        store = storage.LocalArtifactStore(self.store, now=lambda: self.now)
        original = store.publish(bundle)
        loaded = store.find("owner/repository", expected["review_key"])
        self.assertEqual(loaded.report, expected)
        self.assertEqual(loaded.files, bundle.files)
        self.assertEqual(loaded.origin, original)
        stage = storage.stage_bundle(bundle, self.root / "staging")
        self.assertEqual({p.name for p in stage.iterdir()}, {*storage.FILES, "artifact.json"})

    def test_expired_archive_is_ignored_without_deletion(self):
        """超过保留期后允许重新审查，但旧归档不会自动删除。"""
        bundle, result = self.bundle()
        store = storage.LocalArtifactStore(self.store, now=lambda: self.now + timedelta(days=31))
        file = store.publish(bundle)
        self.assertIsNone(store.find("owner/repository", result["review_key"]))
        self.assertTrue(Path(file).exists())

    def test_failed_and_incomplete_reports_are_not_reusable(self):
        """失败和未完成报告可留档，但不能作为完成报告复用。"""
        candidate = copy.deepcopy(self.candidate)
        candidate["review_complete"] = False
        store = storage.LocalArtifactStore(self.store, now=lambda: self.now)
        for arguments in ({"failure": "timeout"}, {"candidate": candidate}):
            bundle, result = self.bundle(**arguments)
            store.publish(bundle)
            self.assertIsNone(store.find("owner/repository", result["review_key"]))

    def test_missing_or_corrupted_store_is_an_error(self):
        """目录不可用或同键包损坏不能被当成缓存未命中。"""
        bundle, result = self.bundle()
        store = storage.LocalArtifactStore(self.store)
        file = Path(store.publish(bundle))
        file.write_bytes(b"invalid zip")
        with self.assertRaises(storage.StorageError):
            store.find("owner/repository", result["review_key"])
        store.root = self.root / "nonexistent-store"
        with self.assertRaises(storage.StorageError):
            store.find("owner/repository", result["review_key"])

    def test_name_alone_or_changed_content_cannot_be_reused(self):
        """名称匹配仍需校验内部清单、摘要、审查键和完成状态。"""
        bundle, result = self.bundle()
        for replacement in ({"review.md": b"altered"}, {"../outside": b"escape"}, {"review.md": b"x" * (storage.MAX_FILE + 1)}):
            members = {"artifact.json": report.canonical(bundle.index), **bundle.files, **replacement}
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
                for name, data in members.items():
                    archive.writestr(name, data)
            with self.assertRaises(storage.StorageError):
                storage.decode_bundle(stream.getvalue(), key=result["review_key"])

    def test_zip_symlink_and_repository_mismatch_rejected(self):
        """拒绝 ZIP 链接成员及与请求不一致的仓库身份。"""
        bundle, _ = self.bundle()
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            for name, data in {"artifact.json": report.canonical(bundle.index), **bundle.files}.items():
                entry = zipfile.ZipInfo(name)
                entry.external_attr = ((stat.S_IFLNK | 0o777) << 16) if name == "review.md" else ((stat.S_IFREG | 0o600) << 16)
                archive.writestr(entry, data)
        with self.assertRaises(storage.StorageError):
            storage.decode_bundle(stream.getvalue())
        with self.assertRaises(storage.StorageError):
            storage.decode_bundle(bundle.archive, repository="wrong/repository")

    def test_model_rules_branch_and_base_affect_lookup_key(self):
        """模型、规则摘要、分支和比较基准改变时不会误用同提交的旧结果。"""
        bundle, result = self.bundle()
        store = storage.LocalArtifactStore(self.store, now=lambda: self.now)
        store.publish(bundle)
        view = report.InputView.load(self.input)
        baseline = report.review_identity(view, repository="owner/repository", branch=None, control_directory=report.ROOT)
        self.assertEqual(baseline["review_key"], result["review_key"])
        for changes in ({"model": "deepseek-v4-pro"}, {"rules_sha256": "0" * 64}, {"branch": "another-branch"}):
            options = {"repository": "owner/repository", "branch": None, "control_directory": report.ROOT, **changes}
            changed = report.review_identity(view, **options)
            self.assertIsNone(store.find("owner/repository", changed["review_key"]))
        view.metadata["base_sha"] = "e" * 40
        changed = report.review_identity(view, repository="owner/repository", branch=None, control_directory=report.ROOT)
        self.assertNotEqual(changed["review_key"], baseline["review_key"])


class GitHubTests(StorageFixture):
    """按官方 REST 响应验证分页、跳转、过期和错误分类。"""

    def test_pagination_download_and_token_isolation(self):
        """第 2 页可命中，下载请求不携带 API 认证，不信任列表中的任意 URL。"""
        bundle, result = self.bundle()
        unrelated = {**self.remote_item(bundle, 2), "name": "unrelated"}
        transport = ScriptedTransport([self.page([unrelated], next_page=True), self.page([self.remote_item(bundle)]),
            (302, {"location": "https://download.example.invalid/object?sig=fixture"}, b""), (200, {}, bundle.archive)])
        store = storage.GitHubArtifactStore("owner/repository", token="fixture-github-token", transport=transport, now=lambda: self.now)
        reused = store.find("owner/repository", result["review_key"])
        self.assertEqual(reused.report, result)
        self.assertIn("page=2", transport.requests[1][0])
        self.assertTrue(all(request[0].startswith("https://api.github.com/") for request in transport.requests[:3]))
        self.assertIn("Authorization", transport.requests[0][1])
        self.assertNotIn("Authorization", transport.requests[-1][1])

    def test_empty_and_expired_artifacts_do_not_download(self):
        """空列表及平台标记过期的候选都不下载，也不伪造完成结果。"""
        bundle, result = self.bundle()
        for items in ([], [{**self.remote_item(bundle), "expired": True}],
                      [{**self.remote_item(bundle), "expires_at": (self.now - timedelta(days=1)).isoformat()}]):
            transport = ScriptedTransport([self.page(items)])
            store = storage.GitHubArtifactStore("owner/repository", transport=transport, now=lambda: self.now)
            self.assertIsNone(store.find("owner/repository", result["review_key"]))
            self.assertEqual(len(transport.requests), 1)

    def test_http_listing_or_download_failure_is_not_miss(self):
        """403、410、下载失败、摘要错误和响应无效全部明确失败。"""
        bundle, result = self.bundle()
        cases = [[(403, {}, b"private error")], [(200, {}, b"not json")],
            [self.page([self.remote_item(bundle)]), (410, {}, b"")],
            [self.page([self.remote_item(bundle)]), (302, {"location": "https://download.example.invalid/signed"}, b""), (500, {}, b"")],
            [self.page([{**self.remote_item(bundle), "digest": "sha256:" + "0" * 64}]),
             (302, {"location": "https://download.example.invalid/signed"}, b""), (200, {}, bundle.archive)]]
        for responses in cases:
            store = storage.GitHubArtifactStore("owner/repository", transport=ScriptedTransport(responses), now=lambda: self.now)
            with self.assertRaises(storage.StorageError) as error:
                store.find("owner/repository", result["review_key"])
            self.assertNotIn("private error", str(error.exception))
            self.assertNotIn("signed", str(error.exception))

    def test_matching_name_cannot_override_inner_key(self):
        """GitHub 同名 artifact 内容不符时停止，不采信名称或继续调用模型。"""
        bundle, result = self.bundle()
        wrong = "0" * 64
        item = {**self.remote_item(bundle), "name": storage.PREFIX + wrong + "-" + result["attempt_id"]}
        transport = ScriptedTransport([self.page([item]), (302, {"location": "https://download.example.invalid/a"}, b""), (200, {}, bundle.archive)])
        store = storage.GitHubArtifactStore("owner/repository", transport=transport, now=lambda: self.now)
        with self.assertRaises(storage.StorageError):
            store.find("owner/repository", wrong)


class BatchTests(StorageFixture):
    """验证实际调用次数、独立执行记录、默认停止和显式重审/补跑。"""

    @unittest.skipUnless(os.name == "posix", "批次执行入口需要 POSIX")
    def test_filtered_only_commit_is_archived_without_running_pi(self):
        """过滤后没有源码变更时保存跳过报告，即使未配置模型密钥。"""
        self.files["current"] = self.files["parent"].copy()
        self.write_input(excluded={"review/README.md": "excluded_path"})
        with patch.dict(os.environ, {"DEEPSEEK_API_KEY": ""}):
            batch, manifest = run_batch(self.plan(), self.root / "runs", self.store)
        entry = manifest["reports"][0]
        self.assertEqual(manifest["failed_commits"], [])
        self.assertEqual(entry["execution_status"], "skipped")
        self.assertEqual(entry["storage_status"], "saved_locally")
        self.assertEqual(entry["review_key"], entry["requested_review_key"])
        _, [(result, _)] = storage.load_saved_batch(batch / "manifest.json")
        self.assertEqual((result["review_status"], result["verdict"]), ("skipped", "inconclusive"))
        self.assertIsNone(result["pi_version"])
        record = report.read_json(batch / entry["files"]["execution.json"])
        self.assertEqual(record["model_attempts"], [])
        self.assertEqual(record["report_validation"], "not_performed")

    def test_pi_base_url_change_invalidates_existing_review(self):
        plan = self.plan()
        runtime = self.runner()
        profiles = [PiModel("proxy", "flash", {"baseUrl": f"https://{host}.example/v1"})
                    for host in ("one", "one", "two")]
        entries = []
        for profile in profiles:
            _, manifest = run_batch(plan, self.root / "runs", self.store, runner=runtime, model_profile=profile)
            entries.append(manifest["reports"][0])
        self.assertEqual([entry["execution_status"] for entry in entries], ["reviewed", "reused", "reviewed"])
        self.assertEqual(self.calls, 2)
        self.assertNotEqual(entries[0]["review_key"], entries[2]["review_key"])

    def test_same_identity_reuses_without_calling_runner(self):
        """首次审查后同键复用，旧报告状态及尝试 ID 不变，新执行另记 reused。"""
        plan = self.plan()
        runner = self.runner()
        first, first_manifest = run_batch(plan, self.root / "runs", self.store, runner=runner)
        second, second_manifest = run_batch(plan, self.root / "runs", self.store, runner=runner)
        self.assertEqual(self.calls, 1)
        old, new = first_manifest["reports"][0], second_manifest["reports"][0]
        self.assertEqual(new["execution_status"], "reused")
        self.assertEqual(new["review_status"], "completed")
        self.assertEqual(old["attempt_id"], new["attempt_id"])
        self.assertNotEqual(old["execution_id"], new["execution_id"])
        execution = report.read_json(second / new["files"]["execution.json"])
        self.assertEqual(execution["model_calls"], 0)
        self.assertIn("source_execution", execution)
        manifest, records = storage.load_saved_batch(second / "manifest.json")
        self.assertEqual(len(records), 1)

    def test_force_review_keeps_two_attempts(self):
        """强制重审绕过复用，旧归档和新归档均保留，审查键仍相同。"""
        plan, runner = self.plan(), self.runner()
        _, first = run_batch(plan, self.root / "runs", self.store, runner=runner)
        _, second = run_batch(plan, self.root / "runs", self.store, runner=runner, force_review=True)
        self.assertEqual(self.calls, 2)
        self.assertEqual(first["reports"][0]["review_key"], second["reports"][0]["review_key"])
        self.assertNotEqual(first["reports"][0]["attempt_id"], second["reports"][0]["attempt_id"])
        self.assertEqual(len(list(self.store.glob("*.zip"))), 2)

    def test_storage_failure_stops_before_model_and_preserves_unprocessed(self):
        """查询失败时不调用模型，当前提交记失败，其余提交明确未处理。"""
        plan = self.plan(second=True)
        broken = unittest.mock.Mock()
        broken.find.side_effect = storage.StorageError("github_list_http_403")
        _, manifest = run_batch(plan, self.root / "runs", self.store, runner=self.runner(), github_store=broken)
        self.assertEqual(self.calls, 0)
        self.assertEqual(manifest["failed_commits"], ["a" * 40])
        self.assertEqual(manifest["unprocessed_commits"], ["c" * 40])
        self.assertEqual(manifest["reports"][0]["execution_status"], "blocked")

    def test_explicit_duplicate_override_is_recorded(self):
        """只有显式配置才允许存储异常后审查，且保留绕过原因。"""
        broken = unittest.mock.Mock()
        broken.find.side_effect = storage.StorageError("github_list_http_403")
        _, manifest = run_batch(self.plan(), self.root / "runs", self.store, runner=self.runner(),
            github_store=broken, allow_duplicate_on_storage_error=True)
        self.assertEqual(self.calls, 1)
        self.assertEqual(manifest["reports"][0]["lookup_error_explicitly_bypassed"], "github_list_http_403")

    def test_store_initialization_failure_still_writes_manifest(self):
        """存储目录是普通文件时，零模型调用并保留失败及剩余 SHA；显式放行仍保存有效报告。"""
        self.store.write_text("occupied", encoding="utf-8")
        batch, manifest = run_batch(self.plan(second=True), self.root / "runs", self.store, runner=self.runner())
        self.assertEqual(self.calls, 0)
        self.assertEqual(manifest["failed_commits"], ["a" * 40])
        self.assertEqual(manifest["unprocessed_commits"], ["c" * 40])
        self.assertEqual(report.read_json(batch / "manifest.json"), manifest)
        self.assertEqual(manifest["reports"][0]["infrastructure_error"], "local_store_unavailable")
        batch, manifest = run_batch(self.plan(), self.root / "runs", self.store, runner=self.runner(),
                                    allow_duplicate_on_storage_error=True)
        self.assertEqual(self.calls, 1)
        self.assertEqual(manifest["reports"][0]["review_status"], "completed")
        self.assertEqual(manifest["reports"][0]["storage_status"], "failed")
        self.assertTrue((batch / manifest["reports"][0]["files"]["review.json"]).is_file())

    def test_retry_failed_selects_only_failed_and_unprocessed(self):
        """仅补跑失败/未完成和待处理项，不重审同批已经完成的提交。"""
        plan = self.plan(second=True, missing=True)
        first, original = run_batch(plan, self.root / "runs", self.store, runner=self.runner(["completed", "failed"]))
        self.assertEqual(self.calls, 2)
        _, retried = run_batch(plan, self.root / "runs", self.store, retry_failed=first / "manifest.json", runner=self.runner())
        self.assertEqual(self.calls, 3)
        self.assertEqual(retried["not_selected_commits"], ["a" * 40])
        self.assertEqual(retried["processed_commits"], ["c" * 40])
        self.assertEqual(retried["unprocessed_commits"], ["d" * 40])
        self.assertEqual(report.read_json(first / "manifest.json"), original)

    def test_publish_failure_preserves_valid_report(self):
        """归档失败不改写已经完成的审查；报告仍可供单独修复存储使用。"""
        with patch.object(storage.LocalArtifactStore, "publish", side_effect=storage.StorageError("local_publish_failed")):
            batch, manifest = run_batch(self.plan(), self.root / "runs", self.store, runner=self.runner())
        item = manifest["reports"][0]
        self.assertEqual((item["review_status"], item["storage_status"]), ("completed", "failed"))
        self.assertEqual(manifest["failed_commits"], ["a" * 40])
        self.assertTrue((batch / item["files"]["review.json"]).is_file())

    def test_saved_batch_read_never_invokes_model(self):
        """仅加载通知或重新归档的数据时，不进入模型执行路径，且会校验文件摘要。"""
        batch, manifest = run_batch(self.plan(), self.root / "runs", self.store, runner=self.runner())
        with patch("review_batch.run_one", side_effect=AssertionError("model must not run")):
            _, records = storage.load_saved_batch(batch / "manifest.json")
        self.assertEqual(len(records), 1)
        file = batch / manifest["reports"][0]["files"]["review.md"]
        file.write_text("tampered", encoding="utf-8")
        with self.assertRaises(storage.StorageError):
            storage.load_saved_batch(batch / "manifest.json")

    @unittest.skipUnless(os.name == "posix", "端到端假 Pi 进程需要 POSIX")
    def test_real_runner_entry_hits_cache_on_second_run(self):
        """通过真实单提交执行器及假 Pi 验证调用前后审查键一致，第二轮无模型进程。"""
        command, calls = report_tests.CorrectionTests.fake_pi(self, [json.dumps(self.candidate)])

        def runner(*args, **kwargs):
            """只替换 Pi 可执行程序，其余预检、总预算及正式报告走生产代码。"""
            return run_one(*args, **kwargs, pi_command=command)

        plan = self.plan()
        with patch.dict(os.environ, {"DEEPSEEK_API_KEY": "fixture-not-real"}):
            _, first = run_batch(plan, self.root / "runs", self.store, runner=runner, timeout=10)
            _, second = run_batch(plan, self.root / "runs", self.store, runner=runner, timeout=10)
        self.assertEqual(len(calls.read_text().splitlines()), 1)
        self.assertEqual(first["reports"][0]["requested_review_key"], first["reports"][0]["review_key"])
        self.assertEqual(second["reports"][0]["execution_status"], "reused")


if __name__ == "__main__":
    unittest.main(verbosity=2)
