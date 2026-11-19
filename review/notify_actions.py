"""核对 Actions 批次报告，并按已授权的发送窗口投递飞书。"""
from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, build_opener

from feishu_summary import POLICIES, read_summary, rich_message
from notify_feishu import deliver, read_destination
from report import read_json, save_json
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
