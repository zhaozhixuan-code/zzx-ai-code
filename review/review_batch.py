"""按已过滤输入计划保存、复用、重审或补跑；不负责 Git 范围解析和通知投递。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import tempfile
import uuid

from pi_runner import digest, parse_json, utc_now
from report import InputView, ROOT, read_json, review_identity, save_json, validate_context
from review_one import run_one
from storage import (FILES, GitHubArtifactStore, LocalArtifactStore, StorageError,
                     load_saved_batch, pack_report, stage_bundle)


def load_plan(file):
    """读取受信任计划；每个输入对应完整 commit/base SHA，缺少输入的预期提交仍保留。"""
    plan = read_json(file)
    if (not isinstance(plan, dict) or set(plan) != {"schema_version", "repository", "branch", "expected_commits", "inputs"}
            or plan["schema_version"] != "review-plan.v1"):
        raise ValueError("计划协议无效")
    validate_context(plan["repository"], plan["branch"])
    expected, inputs = plan["expected_commits"], plan["inputs"]
    if (not isinstance(expected, list) or not isinstance(inputs, list) or len(expected) > 50
            or any(not isinstance(sha, str) or not re.fullmatch(r"[a-f0-9]{40}|[a-f0-9]{64}", sha) for sha in expected)
            or len(set(expected)) != len(expected)):
        raise ValueError("计划提交集合无效，单批上限为 50")
    seen = set()
    for item in inputs:
        if (not isinstance(item, dict) or set(item) != {"commit_sha", "base_sha", "input_directory"}
                or item["commit_sha"] not in expected or item["commit_sha"] in seen
                or (item["base_sha"] is not None and (not isinstance(item["base_sha"], str)
                    or not re.fullmatch(r"[a-f0-9]{40}|[a-f0-9]{64}", item["base_sha"])))
                or not isinstance(item["input_directory"], str) or not Path(item["input_directory"]).is_absolute()):
            raise ValueError("计划输入无效、重复或未使用绝对路径")
        seen.add(item["commit_sha"])
    return plan


def retry_selection(previous, plan):
    """只选择上批 failed/incomplete 和未处理提交；不会覆盖或改写原批次。"""
    manifest, _ = load_saved_batch(previous)
    if manifest["repository"] != plan["repository"]:
        raise ValueError("补跑来源仓库不一致")
    selected = set(manifest["unprocessed_commits"])
    selected.update(item["commit_sha"] for item in manifest["reports"] if item["review_status"] in ("failed", "incomplete"))
    if not selected <= set(plan["expected_commits"]):
        raise ValueError("补跑计划缺少上批未完成的提交")
    return selected


def save_reuse(bundle, directory, execution_id, started_at):
    """保留原报告及审查状态，另写 reused 执行记录；原模型记录仅作为来源保存。"""
    directory.mkdir(mode=0o700)
    for name in ("review.json", "review.md"):
        (directory / name).write_bytes(bundle.files[name])
    original = parse_json(bundle.files["execution.json"].decode("utf-8"))
    original = original.get("source_execution", original)
    result = bundle.report
    execution = {"phase": "finished", "execution_status": "reused", "execution_id": execution_id,
        **{field: result[field] for field in ("review_status", "verdict", "error", "started_at", "finished_at")},
        "report_validation": "passed", "reuse_started_at": started_at, "reuse_finished_at": utc_now(),
        "source_artifact": bundle.origin, "source_attempt_id": result["attempt_id"], "model_calls": 0,
        "source_execution": original, "notification_status": "not_requested"}
    save_json(directory / "execution.json", execution)


def record_files(batch, directory):
    """返回三件套的批次相对路径和内容摘要，供归档及仅通知入口核验。"""
    return {"files": {name: str((directory / name).relative_to(batch)) for name in FILES},
            "files_sha256": {name: digest((directory / name).read_bytes()) for name in FILES}}


def run_batch(plan_file, output_root, store_root, *, force_review=False, retry_failed=None,
              github_store=None, allow_duplicate_on_storage_error=False, retention_days=30,
              timeout=480, model="deepseek-v4-pro", run_url=None, runner=None):
    """逐提交执行计划；默认查询失败停止，显式放行才允许在存储异常后重新调用模型。"""
    if force_review and retry_failed:
        raise ValueError("强制重审和失败补跑不能同时启用")
    if timeout <= 0 or type(retention_days) is not int or not 1 <= retention_days <= 90:
        raise ValueError("超时或保留期无效")
    plan = load_plan(plan_file)
    validate_context(plan["repository"], plan["branch"], run_url)
    selected = retry_selection(retry_failed, plan) if retry_failed else set(plan["expected_commits"])
    local = None
    Path(output_root).mkdir(parents=True, exist_ok=True)
    batch = Path(tempfile.mkdtemp(prefix="stored-batch-", dir=Path(output_root).resolve()))
    batch.chmod(0o700)
    manifest = {"schema_version": "manifest.v1", "batch_id": batch.name, "repository": plan["repository"],
        "branch": plan["branch"], "run_url": run_url, "mode": "force_review" if force_review else "retry_failed" if retry_failed else "review",
        "expected_commits": [sha for sha in plan["expected_commits"] if sha in selected],
        "not_selected_commits": [sha for sha in plan["expected_commits"] if sha not in selected],
        "retry_source": str(Path(retry_failed).absolute()) if retry_failed else None,
        "reports": [], "processed_commits": [], "failed_commits": [],
        "unprocessed_commits": [sha for sha in plan["expected_commits"] if sha in selected], "notification_status": "not_requested"}
    save_json(batch / "manifest.json", manifest)
    for item in plan["inputs"]:
        sha = item["commit_sha"]
        if sha not in selected:
            continue
        execution_id, started_at = uuid.uuid4().hex, utc_now()
        entry = {"commit_sha": sha, "execution_id": execution_id, "requested_review_key": None,
            "execution_status": "pending", "storage_status": "not_attempted", "infrastructure_error": None, "files": {}}
        directory = None
        stop = False
        try:
            view = InputView.load(item["input_directory"])
            if (view.metadata["commit_sha"], view.metadata["base_sha"]) != (sha, item["base_sha"]):
                raise ValueError("计划与过滤输入的 SHA 不符")
            identity = review_identity(view, repository=plan["repository"], branch=plan["branch"],
                control_directory=ROOT, model=model, budget=timeout)
            entry["requested_review_key"] = identity["review_key"]
            reused = None
            try:
                # 创建存储目录也可能失败；在批次清单建立后执行，保留全部预期 SHA。
                if local is None:
                    local = LocalArtifactStore(store_root)
                if not force_review:
                    reused = local.find(plan["repository"], identity["review_key"])
                    if reused is None and github_store is not None:
                        reused = github_store.find(plan["repository"], identity["review_key"])
            except StorageError as error:
                if not allow_duplicate_on_storage_error:
                    raise
                entry["lookup_error_explicitly_bypassed"] = str(error)
            if reused:
                result = reused.report
                directory = batch / (sha[:12] + "-" + execution_id)
                save_reuse(reused, directory, execution_id, started_at)
                entry.update(execution_status="reused", storage_status="reused", source_artifact=reused.origin)
            else:
                inner, result = (runner or run_one)(view.root, batch / "model-runs", repository=plan["repository"],
                    branch=plan["branch"], run_url=run_url, timeout=timeout, model=model)
                inner = Path(inner)
                produced, _ = load_saved_batch(inner / "manifest.json")
                if len(produced["reports"]) != 1:
                    raise StorageError("model_batch_report_count_invalid")
                directory = (inner / produced["reports"][0]["files"]["review.json"]).parent
                if result["review_status"] != "failed" and result["review_key"] != identity["review_key"]:
                    raise StorageError("model_report_identity_mismatch")
                entry.update(execution_status="reviewed", storage_status="pending")
                # 先保留本批报告引用，再进行归档；保存失败不改写有效审查的结论。
                entry.update({field: result[field] for field in ("review_key", "attempt_id", "review_status", "verdict", "error")})
                entry.update(record_files(batch, directory))
                bundle = pack_report(directory, retention_days=retention_days)
                if local is None:
                    raise StorageError("local_store_unavailable")
                entry["archive"] = local.publish(bundle)
                entry["upload_directory"] = str(stage_bundle(bundle, batch / "upload-staging").relative_to(batch))
                entry["artifact_name"] = Path(entry["archive"]).stem
                entry["artifact_expires_at"] = bundle.index["expires_at"]
                entry["storage_status"] = "saved_locally"
            entry.update({field: result[field] for field in ("review_key", "attempt_id", "review_status", "verdict", "error")})
            entry.update(record_files(batch, directory))
        except StorageError as error:
            entry.update(infrastructure_error=str(error), storage_status="failed")
            stop = True
        except (OSError, ValueError, KeyError, TypeError):
            entry.update(infrastructure_error="preparation_or_report_invalid")
            stop = True
        if "review_status" not in entry:
            # 尚无有效报告时只保存失败执行记录，不伪造一份无发现报告。
            failed_directory = batch / (sha[:12] + "-" + execution_id)
            failed_directory.mkdir(mode=0o700, exist_ok=True)
            entry.update(review_status="failed", verdict="inconclusive", error=entry["infrastructure_error"], execution_status="blocked")
            save_json(failed_directory / "execution.json", {"phase": "failed", "execution_id": execution_id,
                "commit_sha": sha, "error": entry["infrastructure_error"], "started_at": started_at, "finished_at": utc_now()})
            entry["files"] = {"execution.json": str((failed_directory / "execution.json").relative_to(batch))}
        manifest["reports"].append(entry)
        manifest["processed_commits"].append(sha)
        if entry["review_status"] in ("failed", "incomplete") or entry["infrastructure_error"]:
            manifest["failed_commits"].append(sha)
        manifest["unprocessed_commits"].remove(sha)
        save_json(batch / "manifest.json", manifest)
        if stop:
            break
    return batch, manifest


def main():
    """CLI 分开提供默认复用、强制重审及失败补跑；GitHub token 不进入模型进程。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", required=True)
    parser.add_argument("--output-root", required=True)
    parser.add_argument("--store-root", required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--force-review", action="store_true")
    mode.add_argument("--retry-failed")
    parser.add_argument("--github-artifacts", action="store_true")
    parser.add_argument("--allow-duplicate-on-storage-error", action="store_true")
    parser.add_argument("--retention-days", type=int, default=30)
    parser.add_argument("--timeout", type=float, default=480)
    parser.add_argument("--model", default="deepseek-v4-pro")
    parser.add_argument("--run-url")
    args = parser.parse_args()
    plan = load_plan(args.plan)
    github = GitHubArtifactStore(plan["repository"], token=os.environ.get("GITHUB_TOKEN")) if args.github_artifacts else None
    batch, manifest = run_batch(args.plan, args.output_root, args.store_root, force_review=args.force_review,
        retry_failed=args.retry_failed, github_store=github, allow_duplicate_on_storage_error=args.allow_duplicate_on_storage_error,
        retention_days=args.retention_days, timeout=args.timeout, model=args.model, run_url=args.run_url)
    print(json.dumps({"batch_directory": str(batch), "processed_commits": manifest["processed_commits"],
        "failed_commits": manifest["failed_commits"], "unprocessed_commits": manifest["unprocessed_commits"],
        "executions": [{key: item.get(key) for key in ("commit_sha", "review_status", "execution_status", "storage_status", "infrastructure_error")} for item in manifest["reports"]]}, ensure_ascii=False, indent=2))
    return 1 if manifest["failed_commits"] or manifest["unprocessed_commits"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
