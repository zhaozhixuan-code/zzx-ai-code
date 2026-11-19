"""将固定的 Git push 范围拆为批次，生成审查输入及可归档的执行结果。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import sys

from git_source import GitSourceError, enumerate_commits, load_event, prepare_input
from pi_model import PiModel
from report import read_json, save_json, validate_context
from review_batch import run_batch
from storage import GitHubArtifactStore


BATCH_SIZE = 10
PUSH_LIMIT = 50


def plan(args):
    """读取 Git push 或命令行指定范围，在 output 保存范围和矩阵索引。"""
    output = Path(args.output).resolve()
    validate_context(args.repository, args.branch)
    branch = args.branch
    output.mkdir(parents=True, exist_ok=True)
    try:
        event = load_event(args.event) if args.event else None
        selection = enumerate_commits(args.git, event=event, repository=args.repository, branch=branch,
                                      single=args.commit, base=args.base, head=args.head,
                                      new_branch_all=args.new_branch_all)
    except (OSError, ValueError) as error:
        detail = str(error) if isinstance(error, GitSourceError) else "事件文件无效或不可用"
        save_json(output / "range.json", {"repository": args.repository, "branch": branch,
                  "status": "failed", "error": detail, "batches": [], "deferred": []})
        print(detail)
        return 1
    commits = selection.pop("commits")
    active = commits[:PUSH_LIMIT]
    batches = [active[start:start + BATCH_SIZE] for start in range(0, len(active), BATCH_SIZE)]
    save_json(output / "range.json", {"repository": args.repository, "branch": branch, **selection,
              "expected_commits": commits, "batches": batches, "deferred": commits[PUSH_LIMIT:]})
    if args.github_output:
        with Path(args.github_output).open("a", encoding="utf-8") as stream:
            print(f"batches={json.dumps(list(range(len(batches))))}", file=stream)
            print(f"scheduled={len(active)}", file=stream)
    print(f"{selection['status']}: expected={len(commits)} scheduled={len(active)} deferred={len(commits) - len(active)}")
    return 0


def execute(args):
    """准备一批提交并运行现有批次执行器，发布目录仅含正式结果和准备失败记录。"""
    parent = Path(args.work).resolve()
    publish = Path(args.output).resolve()
    selection = read_json(args.selection)
    batches = selection["batches"]
    if not 0 <= args.batch < len(batches):
        raise ValueError("批次索引无效")
    commits = batches[args.batch]
    if (not isinstance(commits, list) or not 1 <= len(commits) <= BATCH_SIZE
            or any(not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", sha) for sha in commits)):
        raise ValueError("范围中的批次提交无效")
    parent.mkdir(mode=0o700)
    publish.mkdir(mode=0o700)
    prepared, failures = [], {}
    profile = None
    if args.pi_config:
        try:
            profile = PiModel.load(args.pi_config)
            profile.connection_sha256
            profile.credentials()
        except ValueError as error:
            print(f"Pi configuration error: {error}", file=sys.stderr)
            profile = None
            failures = dict.fromkeys(commits, "pi_configuration_invalid")
    for sha in commits:
        if sha in failures:
            continue
        location = parent / sha
        try:
            base = prepare_input(args.git, sha, location)
        except (GitSourceError, OSError):
            failures[sha] = "snapshot_preparation_failed"
            continue
        prepared.append({"commit_sha": sha, "base_sha": base, "input_directory": str(location)})
    batch_plan = parent / "plan.json"
    save_json(batch_plan, {"schema_version": "review-plan.v1", "repository": selection["repository"],
              "branch": selection["branch"], "expected_commits": commits, "inputs": prepared})
    github_store = None
    if args.github_artifacts:
        github_store = GitHubArtifactStore(selection["repository"], token=os.environ["GITHUB_TOKEN"],
            workflow_path=".github/workflows/ai-review.yml", head_branch=selection["branch"])
    directory, manifest = run_batch(batch_plan, parent / "results", parent / "store", force_review=args.force,
                                     github_store=github_store, run_url=args.run_url, timeout=args.timeout,
                                     model_profile=profile)
    manifest["preparation_errors"] = failures
    manifest["batch_id"] = publish.name
    manifest["control_sha"] = args.control_sha
    for record in manifest["reports"]:
        for relative in record["files"].values():
            target = publish / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(directory / relative, target)
        if args.upload_staging and (staged := record.get("upload_directory")):
            shutil.copytree(directory / staged, Path(args.upload_staging) / record["artifact_name"])
        for key in ("archive", "upload_directory"):
            record.pop(key, None)
    save_json(publish / "manifest.json", manifest)
    print(f"processed={len(manifest['processed_commits'])} failed={len(manifest['failed_commits'])} "
          f"unprocessed={len(manifest['unprocessed_commits'])}")
    return int(bool(manifest["failed_commits"] or manifest["unprocessed_commits"]))


def main():
    """CLI 提供 plan 和 review；输入和输出目录交给工作流显式传递。"""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(required=True)
    planner = commands.add_parser("plan")
    for option in ("git", "output", "repository", "branch"):
        planner.add_argument("--" + option, required=True)
    for option in ("event", "commit", "base", "head", "github-output"):
        planner.add_argument("--" + option)
    planner.add_argument("--new-branch-all", action="store_true")
    planner.set_defaults(handler=plan)
    runner = commands.add_parser("review")
    for option in ("git", "work", "output", "selection"):
        runner.add_argument("--" + option, required=True)
    runner.add_argument("--batch", required=True, type=int)
    runner.add_argument("--run-url")
    runner.add_argument("--control-sha")
    runner.add_argument("--pi-config", type=Path, help="Path to the runner user’s Pi agent configuration")
    runner.add_argument("--timeout", type=float, default=480)
    runner.add_argument("--force", action="store_true")
    runner.add_argument("--github-artifacts", action="store_true")
    runner.add_argument("--upload-staging")
    runner.set_defaults(handler=execute)
    args = parser.parse_args()
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
