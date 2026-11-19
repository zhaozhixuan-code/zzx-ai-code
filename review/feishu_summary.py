"""读取已保存的真实批次并生成飞书摘要文件；此入口不发送请求，也不启动模型。"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import tempfile
import unicodedata
from urllib.parse import quote

from pi_runner import digest, utc_now
from report import canonical, read_json, save_json, validate_context
from storage import StorageError, load_saved_batch

STATUSES = {"completed": "已完成", "incomplete": "未完成", "failed": "失败", "skipped": "已跳过"}
VERDICTS = {"issues_found": "有发现", "no_findings": "本次未发现问题", "inconclusive": "未形成结论"}
LEVELS = ("P0", "P1", "P2")
POLICIES = ("all", "issues_or_failures", "failures_only")
# 官方请求体上限为 20 KB；未签名消息限制为 18,000 字节，为后续时间戳和签名留余量。
MAX_MESSAGE_BYTES = 18000


def display_text(value, maximum=200):
    """将模型或批次文本折成可见单行，移除控制字符；超过 maximum 个字符时标明省略。"""
    text = " ".join("".join(c for c in str(value) if not unicodedata.category(c).startswith("C") or c in "\n\t").split())
    return text if len(text) <= maximum else text[:maximum - 1] + "…"


def commit_set(values):
    """校验最多 50 个完整 Git SHA 的有序清单；重复或错误类型会拒绝整个输入。"""
    if (not isinstance(values, list) or len(values) > 50
            or any(not isinstance(sha, str) or not re.fullmatch(r"[a-f0-9]{40}|[a-f0-9]{64}", sha) for sha in values)
            or len(set(values)) != len(values)):
        raise ValueError("提交清单无效")
    return set(values)


def source_url(report, anchor):
    """按已校验报告的 current/parent 锚点生成固定 SHA 的源码链接；仅保证链接结构。"""
    sha = report["commit_sha"] if anchor["revision"] == "current" else report["base_sha"]
    if sha is None:
        raise ValueError("父版本锚点缺少 SHA")
    return f"https://github.com/{report['repository']}/blob/{sha}/{quote(anchor['file'], safe='/')}#L{anchor['line']}"


def read_summary(manifest_path, *, policy="all", source_note=None, saved_batch=None):
    """读取正式批次、核对状态集合并汇总；policy 控制通知条件，source_note 为可信来源说明。"""
    if policy not in POLICIES or (source_note is not None and (not isinstance(source_note, str) or len(source_note) > 500)):
        raise ValueError("通知策略或来源说明无效")
    manifest, records = saved_batch if saved_batch is not None else load_saved_batch(manifest_path)
    expected, unprocessed = commit_set(manifest["expected_commits"]), commit_set(manifest["unprocessed_commits"])
    if not isinstance(manifest.get("batch_id"), str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,120}", manifest["batch_id"]):
        raise ValueError("批次编号无效")
    if not isinstance(manifest["reports"], list) or len(manifest["reports"]) > 50:
        raise ValueError("批次报告集合无效")
    processed = commit_set([item["commit_sha"] for item in manifest["reports"]])
    if processed & unprocessed or processed | unprocessed != expected:
        raise ValueError("预期、已处理及未处理集合不一致")
    if "processed_commits" in manifest and commit_set(manifest["processed_commits"]) != processed:
        raise ValueError("已处理清单与报告不一致")
    if "failed_commits" in manifest:
        failures = {item["commit_sha"] for item in manifest["reports"]
                    if item["review_status"] in ("failed", "incomplete") or item.get("infrastructure_error")}
        if commit_set(manifest["failed_commits"]) != failures:
            raise ValueError("失败清单与报告不一致")
    by_sha = {report["commit_sha"]: report for report, _ in records}
    if len(by_sha) != len(records):
        raise ValueError("重复的正式报告")
    # S06 离线批次未在清单存分支，只有所有正式报告一致时才能推导，不能猜测监听分支。
    branches = {report["branch"] for report in by_sha.values()}
    branch = manifest.get("branch", next(iter(branches)) if len(branches) == 1 else None)
    if any(value != branch for value in branches):
        raise ValueError("批次和报告分支不一致")
    run_url = manifest.get("run_url")
    validate_context(manifest["repository"], branch, run_url)
    mode = manifest.get("mode", "unspecified")
    if mode not in ("unspecified", "review", "force_review", "retry_failed"):
        raise ValueError("批次模式无效")
    commits, findings, totals = [], [], Counter()
    for item in manifest["reports"]:
        sha = item["commit_sha"]
        state, verdict = item["review_status"], item["verdict"]
        if state not in STATUSES or verdict not in VERDICTS:
            raise ValueError("报告状态无效")
        report = by_sha.get(sha)
        if report is None:
            # 存储/输入准备失败可以只有执行记录；核对真实记录后再显示失败，不能伪造无发现。
            if state != "failed" or verdict != "inconclusive" or set(item["files"]) != {"execution.json"}:
                raise ValueError("缺少正式报告的条目不是明确失败")
            relative = Path(item["files"]["execution.json"])
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("执行记录路径无效")
            execution = read_json(Path(manifest_path).absolute().parent / relative)
            if execution.get("phase") != "failed" or execution.get("commit_sha") != sha or not execution.get("error"):
                raise ValueError("执行记录不能证明失败状态")
        counts = Counter(finding["severity"] for finding in report["findings"]) if report else Counter()
        totals.update(counts)
        for finding in report["findings"] if report else []:
            findings.append({"commit_sha": sha, "id": finding["id"], "severity": finding["severity"],
                "title": finding["title"], "trigger": finding["trigger"], "impact": finding["impact"],
                "change_anchor": finding["change_anchor"], "source_url": source_url(report, finding["change_anchor"])})
        commits.append({"commit_sha": sha, "commit_url": f"https://github.com/{manifest['repository']}/commit/{sha}",
            "review_status": state, "verdict": verdict, "finding_counts": {level: counts[level] for level in LEVELS},
            "formal_report_available": report is not None, "review_key": item.get("review_key"),
            "attempt_id": item.get("attempt_id"), "error": item.get("error"),
            "execution_status": item.get("execution_status"), "storage_status": item.get("storage_status"),
            "infrastructure_error": item.get("infrastructure_error")})
    statuses = Counter(item["review_status"] for item in commits)
    infrastructure_failures = sum(bool(item["infrastructure_error"]) for item in commits)
    needs_attention = bool(statuses["failed"] or statuses["incomplete"] or unprocessed or infrastructure_failures)
    should_notify = policy == "all" or needs_attention or (policy == "issues_or_failures" and bool(findings))
    return {"schema_version": "feishu-summary.v1", "batch_id": manifest["batch_id"],
        "repository": manifest["repository"], "branch": branch, "run_url": run_url, "mode": mode,
        "policy": policy, "should_notify": should_notify, "source_note": source_note,
        "statistics": {"expected": len(expected), "processed": len(processed), "unprocessed": len(unprocessed),
                       **{state: statuses[state] for state in STATUSES}, "infrastructure_failures": infrastructure_failures},
        "finding_counts": {level: totals[level] for level in LEVELS}, "commits": commits,
        "unprocessed_commits": manifest["unprocessed_commits"],
        "findings": sorted(findings, key=lambda item: LEVELS.index(item["severity"])),
        "archive_status": "not_verified", "link_validation": "format_only",
        "notification_status": "not_requested", "send_ready": False}


def text_node(text):
    """创建纯文本节点；模型文字不会被转换为 at、HTML 或可执行卡片节点。"""
    return {"tag": "text", "text": text}


def rich_message(summary):
    """生成未签名 post 消息和展示统计；按 UTF-8 字节限额省略展示，完整汇总不截断。"""
    stats = summary["statistics"]
    modes = {"unspecified": "未记录", "review": "常规审查", "force_review": "强制重审", "retry_failed": "失败补跑"}
    header = [[text_node("仓库：" + summary["repository"])],
        [text_node("分支：" + display_text(summary["branch"] or "未指定（手动批次）", 255))],
        [text_node("批次：" + summary["batch_id"] + "｜模式：" + modes[summary["mode"]])],
        [text_node(f"预期 {stats['expected']}｜已处理 {stats['processed']}｜已完成 {stats['completed']}｜未完成 {stats['incomplete']}｜失败 {stats['failed']}｜跳过 {stats['skipped']}｜未处理 {stats['unprocessed']}")],
        [text_node("有效发现：" + " / ".join(f"{level} {summary['finding_counts'][level]}" for level in LEVELS))]]
    if stats["infrastructure_failures"]:
        header.append([text_node(f"另有 {stats['infrastructure_failures']} 个提交存在基础设施/存储错误，审查结论单独保留。")])
    if summary["source_note"]:
        header.append([text_node("来源说明：" + display_text(summary["source_note"], 500))])
    archive_links = summary.get("archive_links", {})
    rows = []
    for item in summary["commits"]:
        details = f"｜{STATUSES[item['review_status']]}，{VERDICTS[item['verdict']]}｜" + " / ".join(f"{level} {item['finding_counts'][level]}" for level in LEVELS)
        if not item["formal_report_available"]:
            details += "｜尚无有效报告"
        if item["execution_status"] == "reused":
            details += "｜复用已有报告"
        if item["error"]:
            details += "｜原因：" + display_text(item["error"], 60)
        if item["infrastructure_error"]:
            details += "｜基础设施错误：" + display_text(item["infrastructure_error"], 60)
        row = [{"tag": "a", "text": item["commit_sha"][:12], "href": item["commit_url"]}, text_node(details)]
        if item["commit_sha"] in archive_links:
            row.extend([text_node("｜"), {"tag": "a", "text": "完整报告", "href": archive_links[item["commit_sha"]]}])
        rows.append(row)
    for sha in summary["unprocessed_commits"]:
        rows.append([{"tag": "a", "text": sha[:12], "href": f"https://github.com/{summary['repository']}/commit/{sha}"}, text_node("｜未处理，尚无审查结论")])
    focus_rows = []
    for finding in summary["findings"][:3]:
        title = f"重点 [{finding['severity']}] {finding['commit_sha'][:12]} {finding['id']}：" + display_text(finding["title"], 120)
        focus_rows.append([text_node(title + "；触发：" + display_text(finding["trigger"], 140) + "；影响：" + display_text(finding["impact"], 140) + " "),
                           {"tag": "a", "text": "源码位置", "href": finding["source_url"]}])
    footer = [[text_node("失败、未完成或未处理不代表无问题；已完成且无发现也不保证代码绝对正确。")],
        [text_node("完整报告已归档到 GitHub；下载受保留期和仓库访问权限限制。" if archive_links else
                   "完整报告尚无已核验的 GitHub 归档入口；当前仅生成摘要文件。")],
        [text_node("提交和源码链接按固定 SHA 生成，可访问性尚未核验。")]]
    footer.append([{"tag": "a", "text": "Actions 运行", "href": summary["run_url"]}] if summary["run_url"] else [text_node("Actions 运行：尚未接入")])

    def assemble(shown_rows, shown_focus):
        """按当前展示条数组装消息，省略提示和最终字节计算使用同一个对象。"""
        content = header + rows[:shown_rows] + focus_rows[:shown_focus]
        if shown_rows < len(rows) or shown_focus < len(summary["findings"]):
            content += [[text_node(f"展示省略：{len(rows)-shown_rows} 个提交明细、{len(summary['findings'])-shown_focus} 个发现未展开；完整内容保留在报告及本地汇总中。")]]
        return {"msg_type": "post", "content": {"post": {"zh_cn": {"title": "AI 代码审查｜批次摘要", "content": content + footer}}}}

    shown_rows, shown_focus = len(rows), len(focus_rows)
    payload = assemble(shown_rows, shown_focus)
    while len(canonical(payload)) > MAX_MESSAGE_BYTES and (shown_focus or shown_rows):
        if shown_focus:
            shown_focus -= 1
        else:
            shown_rows -= 1
        payload = assemble(shown_rows, shown_focus)
    if len(canonical(payload)) > MAX_MESSAGE_BYTES:
        raise ValueError("固定摘要超过消息大小限制")
    return payload, {"message_bytes": len(canonical(payload)), "shown_commit_rows": shown_rows,
                     "omitted_commit_rows": len(rows)-shown_rows, "shown_findings": shown_focus,
                     "omitted_findings": len(summary["findings"])-shown_focus}


def prepare_summary(manifest_path, output_root, *, policy="all", source_note=None):
    """核验 manifest_path 指向的既有批次，在 output_root 的独立目录保存完整汇总和展示文件。"""
    summary = read_summary(manifest_path, policy=policy, source_note=source_note)
    payload, presentation = rich_message(summary)
    Path(output_root).mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix="feishu-summary-", dir=Path(output_root).resolve()))
    output.chmod(0o700)
    save_json(output / "summary.json", summary)
    # 保存实际采用的紧凑编码，确保 message_bytes 与未来签名前的真实文件长度一致。
    (output / "message.json").write_bytes(canonical(payload))
    post = payload["content"]["post"]["zh_cn"]
    paragraphs = [post["title"]] + ["".join(node.get("text", "") + (" ("+node["href"]+")" if node["tag"] == "a" else "") for node in row) for row in post["content"]]
    (output / "summary.txt").write_text("\n".join(paragraphs) + "\n", encoding="utf-8")
    metadata = {"schema_version": "summary-preparation.v1", "prepared_at": utc_now(),
        "manifest_path": str(Path(manifest_path).absolute()), "manifest_sha256": digest(Path(manifest_path).read_bytes()),
        "summary_sha256": digest((output / "summary.json").read_bytes()), "message_sha256": digest(canonical(payload)),
        "policy": policy, "should_notify": summary["should_notify"], **presentation,
        "notification_status": "not_requested", "http_requests": 0, "model_calls": 0,
        "send_ready": False, "remaining_prerequisite": "verified_github_report_archive"}
    save_json(output / "preparation.json", metadata)
    return output, summary, metadata


def main():
    """CLI 只读取已保存报告并准备摘要；不会读取机器人配置、发送飞书或调用审查器。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--output-root", required=True)
    parser.add_argument("--policy", choices=POLICIES, default="all")
    parser.add_argument("--source-note")
    args = parser.parse_args()
    try:
        output, summary, metadata = prepare_summary(args.manifest, args.output_root, policy=args.policy, source_note=args.source_note)
    except (StorageError, OSError, ValueError, KeyError, TypeError):
        print(json.dumps({"status": "failed", "error": "summary_input_or_output_invalid", "http_requests": 0, "model_calls": 0}))
        return 1
    print(json.dumps({"status": "prepared", "output_directory": str(output), "statistics": summary["statistics"],
        "finding_counts": summary["finding_counts"], "message_bytes": metadata["message_bytes"],
        "should_notify": summary["should_notify"], "send_ready": False, "http_requests": 0, "model_calls": 0}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
