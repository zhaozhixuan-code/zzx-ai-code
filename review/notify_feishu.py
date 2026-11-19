"""预览、发送或仅补发已归档的飞书摘要。"""
from __future__ import annotations

import argparse
import base64
from contextlib import closing
import hmac
from http.client import HTTPException
import json
from pathlib import Path
import re
import sqlite3
import stat
import time
from urllib.error import HTTPError
from urllib.request import Request, build_opener
from uuid import uuid4

from feishu_summary import POLICIES, read_summary, rich_message
from pi_runner import digest, parse_json, utc_now
from report import canonical, read_json
from storage import NoRedirect, StorageError, clock_now, load_saved_batch, utc_time


class NotificationError(ValueError):
    """仅携带可对外展示的错误分类。"""


def prepare_notification(manifest_path, evidence_paths, policy):
    """用可信归档记录核对正式报告的身份、有效期和三件套摘要。"""
    batch = load_saved_batch(manifest_path)
    manifest, records = batch
    summary = read_summary(manifest_path, policy=policy, saved_batch=batch)
    reports = {report["commit_sha"]: (report, files) for report, files in records}
    if not reports or len(reports) != len(summary["commits"]):
        raise NotificationError("formal_reports_missing")
    attempts = {item["commit_sha"]: item["attempt_id"] for item in summary["commits"]}
    archive_links, run_urls, archive_ids = {}, set(), set()
    for path in evidence_paths:
        proof = read_json(path, maximum=65536)
        sha = proof["report_commit"]
        artifact_id, run_id = proof["artifact_id"], proof["run_id"]
        if (proof.get("server_signed_download") != "passed"
                or proof["report_repository"] != manifest["repository"]
                or sha not in reports or sha in archive_links or artifact_id in archive_ids
                or type(artifact_id) is not int or artifact_id <= 0
                or type(run_id) is not int or run_id <= 0
                or not isinstance(proof.get("github_expires_at"), str)
                or utc_time(proof["github_expires_at"]) <= clock_now()):
            raise NotificationError("archive_evidence_mismatch_or_expired")
        report, files = reports[sha]
        checksums = proof["files_sha256"]
        if (report["attempt_id"] != attempts[sha] or report["review_status"] != proof["report_status"]
                or not isinstance(checksums, dict)
                or any(checksums.get(name) != digest(content) for name, content in files.items())):
            raise NotificationError("archive_report_mismatch")
        run_url = f"https://github.com/{manifest['repository']}/actions/runs/{run_id}"
        run_urls.add(run_url)
        archive_ids.add(artifact_id)
        archive_links[sha] = f"{run_url}/artifacts/{artifact_id}"
    if archive_links.keys() != reports.keys():
        raise NotificationError("archive_coverage_incomplete")
    summary.update(archive_status="verified", archive_links=archive_links, send_ready=summary["should_notify"])
    if summary["run_url"] is None and len(run_urls) == 1:
        summary["run_url"] = next(iter(run_urls))
    message, _ = rich_message(summary)
    return summary, message


def read_destination(path):
    """读取托管的服务器配置，限制密钥文件权限和请求目标。"""
    path = Path(path).absolute()
    info = path.stat()
    if info.st_mode & (stat.S_IRWXG | stat.S_IRWXO) or not stat.S_ISREG(info.st_mode):
        raise NotificationError("destination_config_permissions")
    config = read_json(path, maximum=4096)
    url, secret, target = (config[name] for name in ("webhook_url", "signing_secret", "target_name"))
    if (not isinstance(url, str) or not re.fullmatch(r"https://open\.feishu\.cn/open-apis/bot/v2/hook/[A-Za-z0-9_-]{12,100}", url)
            or not isinstance(secret, str) or not secret or "\n" in secret
            or not isinstance(target, str) or not target.strip()):
        raise NotificationError("destination_config_invalid")
    return url, secret, target


def signed_body(message, secret, timestamp):
    """生成飞书签名。以时间戳和密钥拼接为 HMAC 密钥，对空字节串签名。"""
    key = f"{timestamp}\n{secret}".encode("utf-8")
    signature = base64.b64encode(hmac.digest(key, b"", "sha256")).decode("ascii")
    body = canonical(dict(message, timestamp=str(timestamp), sign=signature))
    if len(body) > 20 * 1024:
        raise NotificationError("signed_message_too_large")
    return body


def post_message(url, body):
    """发送单次 HTTPS 请求，只返回安全的状态及状态码字段。"""
    request = Request(url, data=body, headers={"Content-Type": "application/json; charset=utf-8"}, method="POST")
    try:
        with build_opener(NoRedirect()).open(request, timeout=15) as response:
            status, content = response.status, response.read(16385)
    except HTTPError as error:
        with error:
            status = error.code
        # 4xx 明确拒绝的状态可补发；请求超时和服务端错误可能已产生投递。
        if 400 <= status < 500 and status not in (408, 499):
            return {"status": "failed", "reason": "http_rejected", "http_status": status}
        return {"status": "unknown", "reason": "http_unconfirmed", "http_status": status}
    except (OSError, HTTPException):
        return {"status": "unknown", "reason": "transport_unconfirmed"}
    return classify_response(status, content)


def classify_response(status, content):
    """仅在 HTTP 200 并且响应含明确整数业务码时判定成功或拒绝。"""
    if status == 200 and len(content) <= 16384:
        try:
            result = parse_json(content.decode("utf-8"))
        except (ValueError, RecursionError):
            result = None
        if isinstance(result, dict) and type(result.get("code")) is int:
            code = result["code"]
            return {"status": "sent" if code == 0 else "failed", "http_status": status, "business_code": code}
    return {"status": "unknown", "reason": "response_unconfirmed", "http_status": status}


def deliver(summary, message, destination, state_root, previous=None):
    """在专用 Linux 服务器串行发送，先持久化未知回执，再覆盖为已判定结果。"""
    import fcntl

    url, secret, target = destination
    message_sha = digest(canonical(message))
    key = digest(canonical([summary["repository"], summary["batch_id"], url]))
    root = Path(state_root).absolute()
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    if root.resolve(strict=True) != root or root.stat().st_mode & (stat.S_IRWXG | stat.S_IRWXO):
        raise NotificationError("receipt_directory_permissions")
    with (root / "delivery.lock").open("ab") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise NotificationError("delivery_in_progress") from None
        with closing(sqlite3.connect(root / "receipts.sqlite3")) as journal:
            journal.execute("PRAGMA synchronous = FULL")
            journal.execute("CREATE TABLE IF NOT EXISTS attempts (delivery_key TEXT NOT NULL, receipt TEXT NOT NULL)")
            row = journal.execute("SELECT receipt FROM attempts WHERE delivery_key = ? ORDER BY rowid DESC LIMIT 1", (key,)).fetchone()
            prior = json.loads(row[0]) if row else None
            if previous is None and prior:
                return dict(prior, duplicate=True)
            if previous is not None and (prior is None or prior["attempt_id"] != previous
                    or prior["status"] not in ("failed", "unknown") or prior["message_sha256"] != message_sha):
                raise NotificationError("resend_requires_latest_failed_or_unknown_receipt_for_message")
            body = signed_body(message, secret, int(time.time()))
            receipt = {"attempt_id": uuid4().hex, "batch_id": summary["batch_id"], "repository": summary["repository"],
                       "target": target, "message_sha256": message_sha, "previous_attempt_id": previous,
                       "status": "unknown", "started_at": utc_now()}
            pending = dict(receipt, reason="delivery_unconfirmed")
            row_id = journal.execute("INSERT INTO attempts VALUES (?, ?)", (key, canonical(pending).decode())).lastrowid
            journal.commit()
            result = post_message(url, body)
            final = {**receipt, **result, "finished_at": utc_now()}
            try:
                journal.execute("UPDATE attempts SET receipt = ? WHERE rowid = ?", (canonical(final).decode(), row_id))
                journal.commit()
            except sqlite3.Error:
                return dict(receipt, reason="final_receipt_save_failed")
            return final


def main():
    """默认输出待发送预览；发送需提供预览摘要和确认的目标群。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--archive-evidence", action="append", required=True)
    parser.add_argument("--policy", choices=POLICIES, default="all")
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--send", action="store_true")
    action.add_argument("--resend", metavar="ATTEMPT_ID")
    parser.add_argument("--config")
    parser.add_argument("--state-root")
    parser.add_argument("--target")
    parser.add_argument("--message-sha256")
    args = parser.parse_args()
    try:
        summary, message = prepare_notification(args.manifest, args.archive_evidence, args.policy)
        message_sha = digest(canonical(message))
        if not summary["should_notify"]:
            result = {"status": "skipped", "reason": "notification_policy"}
        elif not (args.send or args.resend):
            result = {"status": "prepared", "summary": summary, "message_sha256": message_sha, "message": message}
        else:
            if not (args.config and args.state_root and args.target and args.message_sha256 == message_sha):
                raise NotificationError("send_requires_config_state_target_and_matching_preview")
            destination = read_destination(args.config)
            if destination[2] != args.target:
                raise NotificationError("target_confirmation_mismatch")
            result = deliver(summary, message, destination, args.state_root, previous=args.resend)
    except NotificationError as error:
        result = {"status": "failed", "reason": str(error)}
    except (StorageError, OSError, ValueError, KeyError, TypeError, ImportError, RecursionError, sqlite3.Error):
        result = {"status": "failed", "reason": "notification_input_or_state_invalid"}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] in ("prepared", "skipped", "sent") else 1


if __name__ == "__main__":
    raise SystemExit(main())
