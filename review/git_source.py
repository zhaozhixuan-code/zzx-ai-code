"""从 Git 对象读取提交范围和过滤后的两版源码。"""
from __future__ import annotations

import difflib
from io import BytesIO
from pathlib import Path, PurePosixPath
import re
import subprocess

from pi_runner import digest, git_lines, parse_json
from report import save_json, validate_context

SHA = re.compile(r"[0-9a-f]{40}|[0-9a-f]{64}")
LIMIT = 262144
SOURCE_LIMIT = 32 * 1024 * 1024
DIFF_LIMIT = 524288
SOURCE_SUFFIXES = {".java", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".vue", ".css", ".scss",
                   ".html", ".sql", ".py", ".go", ".kt", ".rs", ".cs", ".rb", ".sh"}
EXCLUDED_DIRECTORIES = {".git", ".github", ".mvn", "dist", "node_modules", "target", "review",
                        "zzx-ai-code-microservice"}
CREDENTIAL = re.compile(
    r"-----BEGIN [^-\r\n]*PRIVATE KEY-----|\b(?:sk-|gh[pousr]_|github_pat_|AKIA)[A-Za-z0-9_-]{16,}"
    r"|\b(?:api[_-]?key|access[_-]?token|password|secret)\s*[=:]\s*['\"][^'\"\r\n]{6,}"
    r"|https://open\.feishu\.cn/open-apis/bot/v2/hook/", re.IGNORECASE)


class GitSourceError(ValueError):
    """允许写入计划或执行记录的 Git 输入失败。"""


def git(repo, *args, input_data=None):
    """以参数数组在 repo 读取 Git 对象，返回原始字节。"""
    result = subprocess.run(["git", "-C", str(repo), "--no-replace-objects", *args],
                            input=input_data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise GitSourceError(f"Git {args[0]} 失败")
    return result.stdout


def commit(repo, value):
    """核对完整 SHA 对应现有提交；缺失对象时为记录保留请求的 SHA。"""
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise GitSourceError("需要完整格式的提交 SHA")
    try:
        if git(repo, "cat-file", "-t", value).strip() == b"commit":
            return value
    except GitSourceError:
        pass
    raise GitSourceError(f"缺少提交对象 {value}")


def parent_commit(repo, sha):
    """直接读取提交对象的第一父节点；根提交返回 None。"""
    header = git(repo, "cat-file", "commit", commit(repo, sha)).split(b"\n\n", 1)[0]
    first = next((line[7:].decode("ascii") for line in header.split(b"\n")
                  if line.startswith(b"parent ")), None)
    return commit(repo, first) if first is not None else None


def enumerate_commits(repo, *, event=None, repository, branch, single=None, base=None, head=None,
                      new_branch_all=False):
    """为指定仓库和分支返回固定提交范围；event 为 GitHub push JSON 对象或 None。"""
    validate_context(repository, branch)
    if git(repo, "rev-parse", "--is-shallow-repository").strip() != b"false":
        raise GitSourceError("需要完整的 Git 历史")
    forced = False
    if event is not None:
        if not isinstance(event, dict) or single is not None or head is not None:
            raise GitSourceError("push 输入无效")
        if (event.get("ref") != "refs/heads/" + branch
                or not isinstance(event.get("repository"), dict)
                or event["repository"].get("full_name") != repository):
            raise GitSourceError("push 仓库或分支与配置不符")
        before, head = event.get("before"), event.get("after")
        if any(not isinstance(sha, str) or not SHA.fullmatch(sha) for sha in (before, head)):
            raise GitSourceError("push 事件 SHA 无效")
        forced = event.get("forced") is True
        if event.get("deleted") is True or not int(head, 16):
            return {"status": "deleted", "base": before, "head": head, "forced": forced, "commits": []}
        if int(before, 16):
            base = before
        elif not base and not new_branch_all:
            raise GitSourceError("新分支需要提供基准 SHA，或显式选择全历史")
    if single is not None:
        if base is not None or head is not None:
            raise GitSourceError("单提交与范围参数互斥")
        shas = [commit(repo, single)]
        head = single
    else:
        head = commit(repo, head)
        if base is not None:
            base = commit(repo, base)
        elif event is None:
            raise GitSourceError("指定范围需要基准和目标 SHA")
        revision = f"{base}..{head}" if base else head
        shas = git(repo, "rev-list", "--reverse", "--topo-order", revision, "--").decode("ascii").splitlines()
    return {"status": "planned" if shas else "empty", "base": base, "head": head,
            "forced": forced, "commits": shas}


def path_exclusion(name):
    """仅接受明确允许的文本源码路径；返回排除原因或 None。"""
    path = PurePosixPath(name)
    parts = name.split("/")
    if (path.is_absolute() or any(ord(char) < 32 for char in name) or "\\" in name or ":" in name
            or any(part in ("", ".", "..") for part in parts) or len(name.encode("utf-8")) > 1024):
        return "unsafe_path"
    if any(part.lower() in EXCLUDED_DIRECTORIES or part.startswith(".") for part in parts):
        return "excluded_path"
    lower = path.name.lower()
    if re.search(r"(?:^|[_.-])(secret|secrets|credential|credentials|passwords?)(?:[_.-]|$)", lower):
        return "sensitive_path"
    if path.suffix.lower() not in SOURCE_SUFFIXES and lower not in {"pom.xml", "package.json"}:
        return "outside_source_scope"
    return None


def tree_files(repo, sha):
    """枚举指定版本普通 blob 的路径、对象 ID 和大小，另记录特殊或受限条目。"""
    files, excluded = {}, {}
    if sha is None:
        return files, excluded
    for record in git(repo, "ls-tree", "-r", "-l", "-z", sha).split(b"\0"):
        if not record:
            continue
        metadata, raw = record.split(b"\t", 1)
        mode, kind, oid, size = metadata.split()
        try:
            path = raw.decode("utf-8")
        except UnicodeDecodeError:
            excluded["invalid-path-" + digest(raw)[:16]] = "non_utf8_path"
            continue
        reason = path_exclusion(path)
        if reason == "unsafe_path" or CREDENTIAL.search(path):
            excluded["invalid-path-" + digest(raw)[:16]] = reason or "possible_credential_path"
            continue
        if kind != b"blob" or mode not in (b"100644", b"100755"):
            reason = "special_git_entry"
        elif int(size) > LIMIT:
            reason = "file_over_256KiB"
        if reason:
            excluded[path] = reason
        else:
            files[path] = (oid.decode("ascii"), int(size))
    if sum(size for _, size in files.values()) > SOURCE_LIMIT:
        raise GitSourceError("过滤前候选源码超过单版本 32MiB")
    return files, excluded


def prepare_input(repo, sha, directory):
    """为 sha 生成 Pi 可校验的过滤输入；directory 必须是新目录，返回第一父 SHA 或 None。"""
    base = parent_commit(repo, sha)
    versions = {"current": tree_files(repo, sha), "parent": tree_files(repo, base)}
    sources = {"current": {}, "parent": {}}
    exclusions = {name: {} for name in versions}
    for version, (files, excluded) in versions.items():
        exclusions[version].update(excluded)
        request = ''.join(oid + '\n' for oid, _ in files.values()).encode('ascii')
        objects = BytesIO(git(repo, "cat-file", "--batch", input_data=request))
        for name, (oid, size) in files.items():
            if objects.readline() != f"{oid} blob {size}\n".encode("ascii"):
                raise GitSourceError("Git 对象头不匹配")
            data = objects.read(size)
            if len(data) != size or objects.read(1) != b"\n":
                raise GitSourceError("Git 对象大小不一致")
            try:
                text = data.decode("utf-8")
            except UnicodeDecodeError:
                exclusions[version][name] = "non_utf8_file"
                continue
            if "\0" in text or text.startswith("version https://git-lfs.github.com/spec/v1\n"):
                exclusions[version][name] = "binary_or_lfs_pointer"
            elif CREDENTIAL.search(text):
                exclusions[version][name] = "possible_credential_content"
            else:
                sources[version][name] = data
    # 任意版本在这个路径受限时，两版都隐藏，避免在差异中暴露敏感路径的另一侧。
    excluded = {**exclusions["parent"], **exclusions["current"]}
    for source in sources.values():
        for name in excluded:
            source.pop(name, None)
    current, parent = sources["current"], sources["parent"]
    changed = [name for name in sorted(current.keys() | parent.keys()) if current.get(name) != parent.get(name)]
    lines = []
    diff_size = 0
    for name in changed:
        before, after = parent.get(name), current.get(name)
        for line in difflib.unified_diff(git_lines(before), git_lines(after),
                fromfile=f"a/{name}" if before is not None else "/dev/null",
                tofile=f"b/{name}" if after is not None else "/dev/null", n=20):
            full_line = line if line.endswith("\n") else line + "\n\\ No newline at end of file\n"
            diff_size += len(full_line.encode("utf-8"))
            if diff_size > DIFF_LIMIT:
                raise GitSourceError("过滤差异超过 512KiB，需要拆分提交")
            lines.append(full_line)
    directory = Path(directory)
    directory.mkdir(mode=0o700)
    hashes = {}
    for version, files in sources.items():
        (directory / version).mkdir()
        hashes[version] = {}
        for name, data in files.items():
            output = directory / version / name
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(data)
            hashes[version][name] = digest(data)
    (directory / "commit.diff").write_bytes("".join(lines).encode("utf-8"))
    renames = []
    if base:
        # 仅保留两端都可审查的重命名；差异仍同时保留旧路径删除和新路径新增。
        names = iter(git(repo, "diff", "--no-ext-diff", "--no-textconv", "--name-status", "-z",
                         "--find-renames", base, sha, "--").split(b"\0"))
        for status in names:
            if not status:
                break
            old = next(names).decode("utf-8", errors="replace")
            if status.startswith(b"R"):
                new = next(names).decode("utf-8", errors="replace")
                if old in parent and new in current:
                    renames.append({"from": old, "to": new})
    # Git 变更清单覆盖受限、二进制及模式变化。未变动的受限路径只限制工具可见范围。
    if base:
        raw_changes = git(repo, "diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--name-only", "-z", base, sha, "--")
    else:
        raw_changes = git(repo, "diff-tree", "--root", "-r", "--no-commit-id", "--no-renames", "--name-only", "-z", sha, "--")
    excluded_changes = set()
    for raw in raw_changes.split(b"\0"):
        if raw:
            escaped = "invalid-path-" + digest(raw)[:16]
            name = escaped if escaped in excluded else raw.decode("utf-8")
            if name in excluded:
                excluded_changes.add(name)
    save_json(directory / "filter-manifest.json", {
        "commit_sha": sha, "base_sha": base, "policy": "git-source-conservative-v2",
        "excluded_changed_files": sorted(excluded_changes),
        "file_sha256": hashes, "changed_files": changed, "excluded": excluded, "renames": renames,
        "limitations": ["只读取允许的源码和构建清单；启发式过滤不能识别全部秘密。",
                        "提交说明未发送给模型。"], "source_snapshot_skips": {"current": [], "parent": []},
    })
    return base


def load_event(path):
    """从 GitHub 事件文件读取完整 JSON，不解释其中的脚本或其他内容。"""
    return parse_json(Path(path).read_text(encoding="utf-8"))
