"""核对 Actions 批次报告，并按已授权的发送窗口投递飞书。"""
from __future__ import annotations

import argparse
import io
import json
import os
import re
import zipfile
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, build_opener

from feishu_summary import POLICIES, read_summary, rich_message
from notify_feishu import deliver, read_destination
from pi_runner import digest
from report import canonical, read_json, save_json
from storage import GitHubArtifactStore, NoRedirect


def select_range(args):
    """输出当前运行中最近可用的范围归档；只重跑失败作业时沿用原 plan 的归档。"""
    store = GitHubArtifactStore(args.repository, token=os.environ["GITHUB_TOKEN"])
    candidates = {}
    for page in range(1, 101):
        status, headers, content = store._api(f"/actions/runs/{args.run_id}/artifacts?per_page=100&page={page}")
        if status != 200:
            raise ValueError(f"无法查询本次运行的范围归档（HTTP {status}）")
        for item in json.loads(content)["artifacts"]:
            match = re.fullmatch(r"ai-review-range-([1-9][0-9]*)", item["name"])
            if match and not item["expired"] and int(match[1]) <= args.attempt:
                if match[1] in candidates:
                    raise ValueError("范围归档名称不唯一")
                candidates[match[1]] = item["name"]
        if 'rel="next"' not in headers.get("link", ""):
            break
    else:
        raise ValueError("范围归档分页超限")
    if not candidates:
        raise ValueError("找不到本次运行已保存的范围归档")
    with Path(args.github_output).open("a", encoding="utf-8") as stream:
        print(f"name={candidates[max(candidates, key=int)]}", file=stream)
    return 0


def resolve_source(args):
    """输出批次归档位置；仅通知时要求指定原审查运行、尝试和批次。"""
    if args.source_run:
        if (not re.fullmatch(r"[1-9][0-9]{0,18}", args.source_run)
                or not re.fullmatch(r"[1-9][0-9]{0,5}", args.source_attempt)
                or not re.fullmatch(r"[0-4]", args.source_batch)):
            raise ValueError("仅通知要求有效的原运行 ID、尝试号及批次索引")
        run_id, attempt, batch = int(args.source_run), int(args.source_attempt), int(args.source_batch)
        store = GitHubArtifactStore(args.repository, token=os.environ["GITHUB_TOKEN"])
        status, _, content = store._api(f"/actions/runs/{run_id}")
        if status != 200:
            raise ValueError(f"找不到原审查运行（HTTP {status}）")
        run = json.loads(content)
        if (run["path"].split("@", 1)[0] != ".github/workflows/ai-review.yml"
                or run["head_repository"]["full_name"] != args.repository
                or run["head_branch"] not in ("master", "codex/ai-review-e2e")
                or run["status"] != "completed" or run["run_attempt"] < attempt):
            raise ValueError("只能使用本仓库已结束的审查运行中的批次归档")
    else:
        run_id, attempt, batch = args.run_id, args.attempt, args.batch
    with Path(args.github_output).open("a", encoding="utf-8") as stream:
        print(f"run_id={run_id}", file=stream)
        print(f"archive=ai-review-batch-{run_id}-{attempt}-{batch}", file=stream)
        print(f"delivery=ai-review-delivery-{run_id}-{attempt}-{batch}", file=stream)
    return 0


def authorize_delivery(args):
    """核对消息摘要和该批次最近的归档回执，准备本次发送的待确认记录。"""
    root = Path(args.preview)
    summary = read_json(root / "summary.json")
    message = read_json(root / "message.json")
    message_sha = digest(canonical(message))
    if args.manual and args.expected_sha != message_sha:
        raise ValueError("确认的摘要 SHA-256 与待发送消息不一致")
    store = GitHubArtifactStore(summary["repository"], token=os.environ["GITHUB_TOKEN"])
    history = {}
    pattern = re.compile(re.escape(args.delivery) + r"-([1-9][0-9]*)-(pending|receipt)")
    for item in store.list_artifacts():
        match = pattern.fullmatch(item["name"])
        if not match or item["expired"]:
            continue
        run, kind = int(match[1]), match[2]
        if (item.get("workflow_run") or {}).get("id") != run:
            raise ValueError("同名回执归档来自不同工作流运行")
        records = history.setdefault(run, {})
        if kind in records:
            raise ValueError("通知回执归档不唯一")
        records[kind] = item
    if history:
        previous = max(history)
        records = history[previous]
        is_final = "receipt" in records
        item, filename = (records["receipt"], "feishu-receipt.json") if is_final else (records["pending"], "notification-pending.json")
        with zipfile.ZipFile(io.BytesIO(store.download_archive(item))) as archive:
            record = archive.getinfo(filename)
            if record.file_size > 65536:
                raise ValueError("通知回执大小超限")
            receipt = json.loads(archive.read(record))
        previous_state = receipt["status"]
        allowed = ("sent", "failed", "unknown") if is_final else ("unknown",)
        if receipt["batch_id"] != summary["batch_id"] or previous_state not in allowed:
            raise ValueError("原回执与指定批次不符")
        if previous_state == "sent":
            raise ValueError("该批次已有成功回执")
        if not args.manual or args.previous_run != str(previous) or receipt["message_sha256"] != message_sha:
            raise ValueError(f"上次投递状态 {previous_state}，补发须确认运行 {previous} 和相同消息摘要")
    elif args.previous_run:
        raise ValueError("找不到指定的上次投递回执")
    save_json(args.output, {"batch_id": summary["batch_id"], "status": "unknown",
                            "message_sha256": message_sha, "previous_delivery_run": args.previous_run or None})
    return 0


def preview(args):
    """校验已下载的批次，查找本次运行的真实归档 URL，输出待发送摘要。"""
    manifest = Path(args.manifest)
    summary = read_summary(manifest, policy=args.policy)
    repository, run_url = summary["repository"], summary["run_url"]
    run_id = args.run_id
    if run_url != f"https://github.com/{repository}/actions/runs/{run_id}":
        raise ValueError("批次与 GitHub 运行不符")
    query = urlencode({"name": args.artifact})
    url = f"https://api.github.com/repos/{repository}/actions/runs/{run_id}/artifacts?{query}"
    request = Request(url, headers={"Authorization": "Bearer " + os.environ["GITHUB_TOKEN"],
                                   "Accept": "application/vnd.github+json"})
    with build_opener(NoRedirect()).open(request, timeout=15) as response:
        candidates = json.load(response)["artifacts"]
    matches = [item for item in candidates if item["name"] == args.artifact and item["expired"] is False]
    if len(matches) != 1 or type(matches[0]["id"]) is not int:
        raise ValueError("报告 archive 缺失或不唯一")
    link = f"{run_url}/artifacts/{matches[0]['id']}"
    summary.update(archive_status="verified", archive_links={entry["commit_sha"]: link
                      for entry in summary["commits"] if entry["formal_report_available"]})
    if not summary["archive_links"]:
        summary["source_note"] = "本批无正式报告，执行状态详见归档 " + link
    message, _ = rich_message(summary)
    output = Path(args.output)
    output.mkdir(mode=0o700)
    save_json(output / "summary.json", summary)
    save_json(output / "message.json", message)
    with Path(args.github_output).open("a", encoding="utf-8") as stream:
        print(f"notify={str(summary['should_notify']).lower()}", file=stream)
    message_sha = digest(canonical(message))
    if step_summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(step_summary).open("a", encoding="utf-8") as stream:
            print(f"Batch `{summary['batch_id']}` · [Report]({link})", file=stream)
            print(f"\nMessage SHA-256 `{message_sha}`", file=stream)
    return 0


def send(args):
    """发送已验证摘要。工作流须先归档占位记录，防止重跑后重复投递。"""
    root = Path(args.preview)
    summary = read_json(root / "summary.json")
    if summary["archive_status"] != "verified" or summary["should_notify"] is not True:
        raise ValueError("摘要当前不可投递")
    message = read_json(root / "message.json")
    config = Path(args.config)
    fd = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump({"webhook_url": os.environ["FEISHU_WEBHOOK_URL"], "signing_secret": os.environ["FEISHU_SIGNING_SECRET"],
                   "target_name": os.environ["FEISHU_TARGET_NAME"]}, stream)
    try:
        destination = read_destination(config)
        receipt = deliver(summary, message, destination, Path(args.state))
    finally:
        config.unlink()
    receipt.pop("target", None)
    save_json(args.receipt, receipt)
    print(f"Feishu status: {receipt['status']}")
    return int(receipt["status"] != "sent")


def main():
    """预览只需 GitHub 只读令牌，发送只需飞书配置；两者由不同步骤调用。"""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(required=True)
    select = commands.add_parser("select-range")
    for name in ("repository", "github-output"):
        select.add_argument("--" + name, required=True)
    for name in ("run-id", "attempt"):
        select.add_argument("--" + name, type=int, required=True)
    select.set_defaults(handler=select_range)
    source = commands.add_parser("resolve-source")
    for name in ("repository", "github-output"):
        source.add_argument("--" + name, required=True)
    for name in ("run-id", "attempt", "batch"):
        source.add_argument("--" + name, type=int, required=True)
    for name in ("source-run", "source-attempt", "source-batch"):
        source.add_argument("--" + name, default="")
    source.set_defaults(handler=resolve_source)
    pending = commands.add_parser("authorize-delivery")
    for name in ("preview", "delivery", "output"):
        pending.add_argument("--" + name, required=True)
    pending.add_argument("--manual", action="store_true")
    pending.add_argument("--expected-sha", default="")
    pending.add_argument("--previous-run", default="")
    pending.set_defaults(handler=authorize_delivery)
    prepare = commands.add_parser("preview")
    for name in ("manifest", "artifact", "output", "github-output"):
        prepare.add_argument("--" + name, required=True)
    prepare.add_argument("--run-id", type=int, required=True)
    prepare.add_argument("--policy", choices=POLICIES, default="all")
    prepare.set_defaults(handler=preview)
    dispatch = commands.add_parser("send")
    for name in ("preview", "config", "state", "receipt"):
        dispatch.add_argument("--" + name, required=True)
    dispatch.set_defaults(handler=send)
    args = parser.parse_args()
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
