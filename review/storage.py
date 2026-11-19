"""审查归档的保存与只读查询；任何存储错误都与“没有可复用报告”分开。"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import io
import json
from pathlib import Path
import re
import stat
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

from pi_runner import digest, parse_json
from report import ROOT, SCHEMA, canonical, read_json, render_markdown, review_key_for, schema_check, validate_context

FILES = ("review.json", "review.md", "execution.json")
MAX_FILE = 2 * 1024 * 1024
MAX_ARCHIVE = 8 * 1024 * 1024
PREFIX = "ai-review-v1-"
ARTIFACT_SCHEMA = read_json(ROOT / "schemas/artifact-v1.json")


class StorageError(RuntimeError):
    """携带固定错误分类；不保留 HTTP 正文、凭据或带签名的下载 URL。"""


def utc_time(value):
    """把带 UTC 时区的 ISO 时间转换为 datetime；不接受本地或无时区时间。"""
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.utcoffset() != timedelta(0):
        raise ValueError("存储时间必须为 UTC")
    return parsed


def clock_now():
    """返回 UTC 当前时间；测试可注入固定时钟验证过期行为。"""
    return datetime.now(timezone.utc)


def artifact_name(report):
    """按完整审查键及原始模型报告尝试 ID 命名，重复审查保留不同归档。"""
    return PREFIX + report["review_key"] + "-" + report["attempt_id"]


def validate_report_files(files):
    """核对报告三件套的协议、身份、终态和 Markdown；返回有效的报告对象。"""
    report = parse_json(files["review.json"].decode("utf-8"))
    execution = parse_json(files["execution.json"].decode("utf-8"))
    schema_check(report, SCHEMA)
    validate_context(report["repository"], report["branch"], report["run_url"])
    if report["commit_url"] != f"https://github.com/{report['repository']}/commit/{report['commit_sha']}":
        raise ValueError("报告提交链接与身份不符")
    if utc_time(report["finished_at"]) < utc_time(report["started_at"]):
        raise ValueError("报告时间顺序无效")
    if report["review_key"] != review_key_for(report):
        raise ValueError("审查键与报告身份不符")
    if files["review.md"].decode("utf-8") != render_markdown(report):
        raise ValueError("Markdown 与机器报告不一致")
    if (execution.get("phase") != "finished" or any(execution.get(field) != report[field]
            for field in ("review_status", "verdict", "error", "started_at", "finished_at"))):
        raise ValueError("执行记录与报告不一致")
    if report["review_status"] == "completed":
        coverage = report["coverage"]
        if (execution.get("report_validation") != "passed" or report["pi_version"] != "1.5.2"
                or coverage["reference_validation"] != "passed" or coverage["unread_changed_files"]
                or coverage["has_filtered_inputs"] or coverage["has_snapshot_skips"]):
            raise ValueError("已完成报告仍有未校验范围")
    return report


@dataclass
class Bundle:
    """经过校验的便携报告包；files 只含报告三件套，不含模型凭据或源码快照。"""
    index: dict
    report: dict
    files: dict
    archive: bytes
    origin: str | None = None


def decode_bundle(data, *, repository=None, key=None):
    """在内存验证 ZIP 白名单、大小、摘要及报告，不把不可信路径解压到文件系统。"""
    try:
        if len(data) > MAX_ARCHIVE:
            raise ValueError("归档过大")
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            if len(entries) != 4 or {entry.filename for entry in entries} != {*FILES, "artifact.json"}:
                raise ValueError("归档文件集合无效")
            if sum(entry.file_size for entry in entries) > MAX_ARCHIVE:
                raise ValueError("解压总大小超过限制")
            for entry in entries:
                mode = stat.S_IFMT(entry.external_attr >> 16)
                if (entry.is_dir() or entry.flag_bits & 1 or mode not in (0, stat.S_IFREG)
                        or entry.file_size > (65536 if entry.filename == "artifact.json" else MAX_FILE)):
                    raise ValueError("归档成员类型或大小无效")
            index = parse_json(archive.read("artifact.json").decode("utf-8"))
            schema_check(index, ARTIFACT_SCHEMA)
            files = {name: archive.read(name) for name in FILES}
        if utc_time(index["expires_at"]) <= utc_time(index["created_at"]):
            raise ValueError("归档有效期无效")
        if repository is not None and index["repository"] != repository:
            raise ValueError("归档仓库身份不符")
        if key is not None and index["review_key"] != key:
            raise ValueError("归档审查键不符")
        for name, content in files.items():
            if index["files"][name] != {"sha256": digest(content), "bytes": len(content)}:
                raise ValueError("归档内容摘要不符")
        report = validate_report_files(files)
        if any(index[field] != report[field] for field in ("repository", "review_key", "attempt_id", "commit_sha", "base_sha", "review_status", "verdict")):
            raise ValueError("归档清单与报告不一致")
        return Bundle(index, report, files, data)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, RuntimeError, NotImplementedError):
        raise StorageError("invalid_artifact") from None


def pack_report(directory, *, retention_days=30, now=None):
    """为已完成校验的报告建立便携包；保留期从本次归档开始，默认 30 天。"""
    try:
        if type(retention_days) is not int or not 1 <= retention_days <= 90:
            raise ValueError("保留期必须为 1～90 天")
        root = Path(directory).absolute()
        if root.resolve(strict=True) != root:
            raise ValueError("报告目录不能经过链接")
        files = {}
        for name in FILES:
            file = root / name
            if file.resolve(strict=True) != file or not file.is_file() or file.stat().st_size > MAX_FILE:
                raise ValueError("报告路径或大小无效")
            files[name] = file.read_bytes()
        report = validate_report_files(files)
        created = now or clock_now()
        index = {"schema_version": "review-artifact.v1", **{key: report[key] for key in
            ("repository", "review_key", "attempt_id", "commit_sha", "base_sha", "review_status", "verdict")},
            "created_at": created.isoformat(), "expires_at": (created + timedelta(days=retention_days)).isoformat(),
            "files": {name: {"sha256": digest(content), "bytes": len(content)} for name, content in files.items()}}
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("artifact.json", canonical(index))
            for name, content in files.items():
                archive.writestr(name, content)
        return decode_bundle(stream.getvalue())
    except (OSError, ValueError, KeyError, TypeError):
        raise StorageError("report_pack_failed") from None


def stage_bundle(bundle, output_root):
    """为后续 upload-artifact 准备仅含四个文件的新目录；此函数本身不上传 GitHub。"""
    Path(output_root).mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix="artifact-stage-", dir=Path(output_root).resolve()))
    stage.chmod(0o700)
    (stage / "artifact.json").write_bytes(canonical(bundle.index))
    for name, data in bundle.files.items():
        (stage / name).write_bytes(data)
    return stage


class LocalArtifactStore:
    """服务器本地的保留期归档；过期文件仅忽略，不自动删除，也不承诺并发去重。"""

    def __init__(self, root, *, now=clock_now):
        """root 为独立归档目录，now 为 UTC 时钟；不能使用链接目录。"""
        self.root = Path(root).absolute()
        try:
            self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
            if self.root.resolve(strict=True) != self.root:
                raise StorageError("local_store_link_rejected")
        except OSError:
            raise StorageError("local_store_unavailable") from None
        self.now = now

    def publish(self, bundle):
        """保存独立尝试，不覆盖已有归档；同一尝试内容不一致时明确失败。"""
        target = self.root / (artifact_name(bundle.report) + ".zip")
        try:
            if not self.root.is_dir() or self.root.resolve(strict=True) != self.root:
                raise StorageError("local_store_unavailable")
            with target.open("xb") as stream:
                stream.write(bundle.archive)
        except FileExistsError:
            existing = self._read(target, bundle.report["repository"], bundle.report["review_key"])
            if existing.files != bundle.files:
                raise StorageError("artifact_attempt_conflict") from None
        except OSError:
            raise StorageError("local_publish_failed") from None
        return str(target)

    def _read(self, file, repository, key):
        """有界读取一份归档，读取或内容错误不能当作缓存未命中。"""
        try:
            if file.resolve(strict=True) != file or file.stat().st_size > MAX_ARCHIVE:
                raise StorageError("invalid_local_artifact")
            bundle = decode_bundle(file.read_bytes(), repository=repository, key=key)
            if file.stem != artifact_name(bundle.report):
                raise StorageError("artifact_name_mismatch")
            bundle.origin = str(file)
            return bundle
        except OSError:
            raise StorageError("local_read_failed") from None

    def find(self, repository, key):
        """只复用同键、未过期、状态 completed 的完整报告；失败和未完成报告仍留档。"""
        if not re.fullmatch(r"[a-f0-9]{64}", key):
            raise StorageError("invalid_lookup_key")
        try:
            if not self.root.is_dir() or self.root.resolve(strict=True) != self.root:
                raise StorageError("local_store_unavailable")
            files = sorted(self.root.glob(PREFIX + key + "-*.zip"))
            candidates = [self._read(file, repository, key) for file in files]
        except OSError:
            raise StorageError("local_query_failed") from None
        reusable = [item for item in candidates if utc_time(item.index["expires_at"]) > self.now() and item.report["review_status"] == "completed"]
        return max(reusable, key=lambda item: (utc_time(item.index["created_at"]), item.report["attempt_id"])) if reusable else None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """下载分两次请求，禁止 urllib 自动把 API 认证带到签名下载地址。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """返回 None 让调用方读取 302 Location；不自动跟随任意跳转。"""
        return None


class HttpTransport:
    """有界 HTTPS 请求适配器；不记录请求头、响应正文或签名 URL。"""

    def get(self, url, headers, limit):
        """GET 返回状态、响应头和有界内容；HTTP 错误交由存储层分类。"""
        try:
            opener = urllib.request.build_opener(NoRedirect())
            try:
                response = opener.open(urllib.request.Request(url, headers=headers), timeout=20)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                status, metadata = response.code, dict(response.headers)
                content = response.read(limit + 1) if status == 200 else b""
            if len(content) > limit:
                raise StorageError("http_response_too_large")
            return status, {key.lower(): value for key, value in metadata.items()}, content
        except (OSError, urllib.error.URLError, ValueError):
            raise StorageError("github_transport_failed") from None


class GitHubArtifactStore:
    """按 GitHub REST artifacts API 分页查询和下载；真实上传由 S09 Actions 负责。"""

    def __init__(self, repository, *, token=None, transport=None, now=clock_now, workflow_path=None, head_branch=None):
        """按 owner/name 查询，可限定生产报告的 workflow_path 和 head_branch。token 仅发给 GitHub API。"""
        validate_context(repository, head_branch)
        self.repository, self.token = repository, token
        self.transport, self.now = transport or HttpTransport(), now
        self.workflow_path, self.head_branch = workflow_path, head_branch

    def _trusted_producer(self, item):
        """限定生产来源时，核对 artifact 的仓库、分支及运行所使用的工作流路径。"""
        if self.workflow_path is None:
            return True
        run = item.get("workflow_run")
        if (not isinstance(run, dict) or type(run.get("id")) is not int or run["id"] <= 0
                or run.get("repository_id") != run.get("head_repository_id")
                or run.get("head_branch") != self.head_branch):
            return False
        status, _, content = self._api(f"/actions/runs/{run['id']}")
        if status != 200:
            raise StorageError(f"github_producer_http_{status}")
        info = parse_json(content.decode("utf-8"))
        return (info["path"].split("@", 1)[0] == self.workflow_path
                and info["event"] in ("push", "workflow_dispatch"))

    def _api(self, path, limit=2 * 1024 * 1024):
        """只向固定 GitHub API 主机发送认证，不使用 artifact 提供的任意下载 URL。"""
        headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "ai-commit-review"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        return self.transport.get("https://api.github.com/repos/" + self.repository + path, headers, limit)

    def list_artifacts(self):
        """按固定页码遍历列表；分页失败或超过上限明确报错，不返回不完整成功列表。"""
        result = []
        try:
            for page in range(1, 1001):
                status, headers, content = self._api(f"/actions/artifacts?per_page=100&page={page}")
                if status != 200:
                    raise StorageError(f"github_list_http_{status}")
                body = parse_json(content.decode("utf-8"))
                items = body["artifacts"]
                if not isinstance(items, list) or len(items) > 100 or type(body["total_count"]) is not int:
                    raise ValueError("列表协议无效")
                for item in items:
                    if (not isinstance(item, dict) or type(item.get("id")) is not int or item["id"] <= 0
                            or not isinstance(item.get("name"), str) or type(item.get("expired")) is not bool):
                        raise ValueError("归档元数据无效")
                result.extend(items)
                if not re.search(r'rel="next"', headers.get("link", "")) and len(items) < 100:
                    return result
            raise StorageError("github_pagination_limit")
        except (ValueError, KeyError, TypeError):
            raise StorageError("github_list_invalid") from None

    def _download(self, item, key):
        """下载 API 的一分钟有效签名链接；第二次请求不携带 GitHub token。"""
        status, headers, _ = self._api(f"/actions/artifacts/{item['id']}/zip", MAX_ARCHIVE)
        if status != 302:
            raise StorageError(f"github_download_http_{status}")
        location = headers.get("location", "")
        parsed = urllib.parse.urlsplit(location)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
                or parsed.fragment or parsed.port not in (None, 443) or any(ord(c) < 32 for c in location)):
            raise StorageError("github_download_location_invalid")
        status, _, data = self.transport.get(location, {"User-Agent": "ai-commit-review"}, MAX_ARCHIVE)
        if status != 200:
            raise StorageError(f"artifact_download_http_{status}")
        expected_digest = item.get("digest")
        if expected_digest is not None and expected_digest != "sha256:" + digest(data):
            raise StorageError("github_artifact_digest_mismatch")
        bundle = decode_bundle(data, repository=self.repository, key=key)
        if item["name"] != artifact_name(bundle.report):
            raise StorageError("artifact_name_mismatch")
        bundle.origin = f"github-artifact:{item['id']}"
        return bundle

    def find(self, repository, key):
        """完整查询后核验候选包；下载或核验失败不降级为未审查。"""
        if repository != self.repository or not re.fullmatch(r"[a-f0-9]{64}", key):
            raise StorageError("invalid_lookup_identity")
        try:
            candidates = []
            for item in self.list_artifacts():
                if not item["name"].startswith(PREFIX + key + "-"):
                    continue
                if item["expired"] or utc_time(item["expires_at"]) <= self.now() or not self._trusted_producer(item):
                    continue
                if type(item.get("size_in_bytes")) is not int or not 0 < item["size_in_bytes"] <= MAX_ARCHIVE:
                    raise StorageError("github_artifact_size_invalid")
                candidates.append(item)
            candidates.sort(key=lambda item: (utc_time(item["created_at"]), item["id"]), reverse=True)
            for item in candidates:
                bundle = self._download(item, key)
                if utc_time(bundle.index["expires_at"]) > self.now() and bundle.report["review_status"] == "completed":
                    return bundle
            return None
        except (ValueError, KeyError, TypeError):
            raise StorageError("github_artifact_metadata_invalid") from None


def load_saved_batch(manifest_path):
    """读取通知/归档所需的既有报告；只核对文件和终态，绝不调用审查器或模型。"""
    try:
        manifest_path = Path(manifest_path).absolute()
        manifest = read_json(manifest_path)
        if manifest["schema_version"] != "manifest.v1":
            raise ValueError("批次协议无效")
        records = []
        for item in manifest["reports"]:
            if "review.json" not in item.get("files", {}):
                continue
            files = {}
            for name in FILES:
                relative = Path(item["files"][name])
                target = manifest_path.parent / relative
                if relative.is_absolute() or ".." in relative.parts or target.resolve(strict=True) != target or target.stat().st_size > MAX_FILE:
                    raise ValueError("报告引用路径无效")
                files[name] = target.read_bytes()
                if "files_sha256" in item and item["files_sha256"].get(name) != digest(files[name]):
                    raise ValueError("批次文件摘要不符")
            report = validate_report_files(files)
            if any(item[field] != report[field] for field in ("commit_sha", "review_key", "review_status", "verdict")) or report["repository"] != manifest["repository"]:
                raise ValueError("批次清单与报告不符")
            records.append((report, files))
        return manifest, records
    except (OSError, ValueError, KeyError, TypeError):
        raise StorageError("saved_batch_invalid") from None


def main():
    """CLI 将正式报告归档并准备上传目录；不执行模型或发送通知。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-directory", required=True)
    parser.add_argument("--store-root", required=True)
    parser.add_argument("--stage-root", required=True)
    parser.add_argument("--retention-days", type=int, default=30)
    args = parser.parse_args()
    bundle = pack_report(args.report_directory, retention_days=args.retention_days)
    location = LocalArtifactStore(args.store_root).publish(bundle)
    stage = stage_bundle(bundle, args.stage_root)
    print(json.dumps({"artifact_name": artifact_name(bundle.report), "archive": location,
        "upload_directory": str(stage), "expires_at": bundle.index["expires_at"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
