#!/usr/bin/env bash
set -euo pipefail

source_dir="$1"
shift

# 从本仓库读取完整 Git 对象，工作目录中不检出待审查源码。
[[ "$GITHUB_REPOSITORY" == 'zhaozhixuan-code/zzx-ai-code' ]]
git init --bare --quiet --template= "$source_dir"
remote="https://github.com/$GITHUB_REPOSITORY.git"
for sha in "$@"; do
  if [[ -z "$sha" || "$sha" =~ ^0+$ ]]; then
    continue
  fi
  [[ "$sha" =~ ^([a-f0-9]{40}|[a-f0-9]{64})$ ]]
  if ! git -C "$source_dir" cat-file -e "$sha^{commit}" 2>/dev/null; then
    if ! git -C "$source_dir" fetch --quiet --no-tags --no-write-fetch-head "$remote" "$sha"; then
      printf 'Could not fetch requested commit %s\n' "$sha" >&2
    fi
  fi
done
