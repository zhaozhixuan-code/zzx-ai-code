"""在自托管 Runner 审查固定提交，并直接向飞书发送一条摘要。"""
from __future__ import annotations

import argparse
from collections import Counter
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time

from feishu_summary import display_text
from git_source import GitSourceError, enumerate_commits, load_event, prepare_input
from notify_feishu import post_message, read_destination, signed_body
from pi_model import PiModel
from review_one import run_one
from review_push import PUSH_LIMIT


STATUS_LABELS = {"completed": "完成", "incomplete": "未完成", "failed": "失败", "skipped": "跳过"}
SEVERITIES = ("P0", "P1", "P2")
# 为环境准备和飞书投递预留时间，避免作业超时前来不及汇报未处理的提交。
REVIEW_BUDGET = 320 * 60


def select_commits(args, source, repository, branch):
    """从 args 指定的事件或固定 SHA 拉取到裸仓库 source，返回 repository/branch 的提交清单。"""
    event = load_event(args.event) if args.event else None
    if args.event and not isinstance(event, dict):
        raise GitSourceError("push 事件必须为 JSON 对象")
    if event is not None:
        requested = (event.get("after"), event.get("before"))
    else:
        requested = (args.commit, args.head, args.base)
    subprocess.run(["bash", str(Path(__file__).with_name("fetch-source.sh")), str(source),
                    *(sha for sha in requested if isinstance(sha, str))], check=True, timeout=300)
    return enumerate_commits(source, event=event, repository=repository, branch=branch,
                             single=args.commit, base=args.base, head=args.head)


def review_commits(source, commits, work, repository, branch, profile, timeout):
    """逐个审查 commits 中的完整 SHA，返回状态计数及每级最多三条已校验的标题。

    source 是裸仓库，work 是任务临时目录，repository、branch 和 profile 确定运行上下文和模型。
    timeout 是单提交的最大审查秒数；超过本次任务总预算的提交由调用方统计为未处理。
    """
    deadline = time.monotonic() + REVIEW_BUDGET
    statuses, findings = Counter(), Counter()
    titles = {level: [] for level in SEVERITIES}
    for sha in commits[:PUSH_LIMIT]:
        if time.monotonic() >= deadline:
            break
        status, error = "failed", None
        try:
            with tempfile.TemporaryDirectory(prefix="commit-", dir=work) as item:
                input_dir = Path(item) / "input"
                prepare_input(source, sha, input_dir)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                _, report = run_one(input_dir, Path(item) / "model", repository=repository, branch=branch,
                                    timeout=min(timeout, remaining), model_profile=profile, save_report=False)
                status, error = report["review_status"], report["error"]
            for finding in report["findings"]:
                level = finding["severity"]
                findings[level] += 1
                if len(titles[level]) < 3:
                    titles[level].append(f"{level} {sha[:8]} {display_text(finding['title'], 100)}")
        except (OSError, ValueError):
            status, error = "failed", "review_input_or_output_error"
        statuses[status] += 1
        print(f"{sha[:12]} {status}" + (f" ({error})" if error else ""))
    return statuses, findings, titles


def summary_text(repository, branch, head, total, statuses, findings, titles):
    """用仓库、分支、范围末端 SHA、提交总数及已校验的计数和标题生成纯文本消息。"""
    unprocessed = total - sum(statuses.values())
    counts = " · ".join(f"{label} {statuses[key]}" for key, label in STATUS_LABELS.items() if statuses[key])
    if unprocessed:
        counts = f"{counts} · 未处理 {unprocessed}" if counts else f"未处理 {unprocessed}"
    severity = " / ".join(f"{level} {findings[level]}" for level in SEVERITIES)
    top = [title for level in SEVERITIES for title in titles[level]][:3]
    return "\n".join([f"AI 代码审查｜{repository.rsplit('/', 1)[-1]}/{branch} {head[:8]}",
                      f"提交 {total} · {counts}", f"有效发现 {severity}", *top])


def main():
    """在 Runner 临时目录编排拉取、审查和直接发送。失败或未处理提交以非零状态退出。"""
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("event", "commit", "base", "head"):
        parser.add_argument("--" + option)
    parser.add_argument("--timeout", type=int, default=480)
    parser.add_argument("--send", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 480:
        parser.error("--timeout 必须在 1 到 480 秒之间")

    repository, branch = os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_REF_NAME"]
    if args.send:
        try:
            url, secret, _ = read_destination(Path.home() / ".config/ai-commit-review/feishu.json")
        except (KeyError, OSError, ValueError):
            print("Feishu configuration unavailable or invalid: ~/.config/ai-commit-review/feishu.json")
            return 1

    with tempfile.TemporaryDirectory(prefix="ai-review-", dir=os.environ["RUNNER_TEMP"]) as directory:
        work = Path(directory)
        source = work / "git"
        try:
            selection = select_commits(args, source, repository, branch)
        except (ValueError, OSError, subprocess.SubprocessError):
            print("Could not determine the review commit range")
            return 1
        commits = selection["commits"]
        if not commits:
            print(f"No commits to review ({selection['status']})")
            return 0
        try:
            profile = PiModel.load(os.environ.get("PI_CODING_AGENT_DIR") or Path.home() / ".pi/agent")
        except ValueError:
            print("Could not load the Runner user's Pi configuration")
            statuses, findings = Counter(failed=min(len(commits), PUSH_LIMIT)), Counter()
            titles = {level: [] for level in SEVERITIES}
        else:
            statuses, findings, titles = review_commits(source, commits, work, repository, branch, profile, args.timeout)
        message = summary_text(repository, branch, selection["head"], len(commits), statuses, findings, titles)

    rejected = False
    if args.send:
        body = signed_body({"msg_type": "text", "content": {"text": message}}, secret, int(time.time()))
        result = post_message(url, body)
        print(f"Feishu delivery: {result}")
        rejected = result["status"] != "sent"
    else:
        print("Feishu delivery: disabled for this execution")
    processed = sum(statuses.values())
    print(f"Review counts: expected={len(commits)}, processed={processed}, "
          f"failed={statuses['failed']}, incomplete={statuses['incomplete']}")
    return int(bool(rejected or statuses["failed"] or statuses["incomplete"] or len(commits) != processed))


if __name__ == "__main__":
    # 取消作业时让 Python 展开临时目录和 Pi 子进程的清理逻辑。
    signal.signal(signal.SIGTERM, signal.default_int_handler)
    raise SystemExit(main())
