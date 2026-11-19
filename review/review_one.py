"""单提交模型审查及报告校验；最多纠正一次输出，共享同一个总超时。"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import tempfile
import time

from pi_runner import DEFAULT_MODEL, load_input, parse_json, run_review, utc_now
from report import ROOT, assess_attempt, build_report, save_json, write_report


def run_one(input_directory, output_root, *, repository, branch=None, run_url=None, timeout=480,
            model=DEFAULT_MODEL, pi_command=None, model_profile=None):
    """审查已过滤输入；timeout 为包含预检、两次模型输出和校验的总秒数。"""
    if timeout <= 0:
        raise ValueError("总超时必须大于 0")
    started, started_at = time.monotonic(), utc_now()
    root, metadata, _ = load_input(input_directory)
    Path(output_root).mkdir(parents=True, exist_ok=True)
    batch = Path(tempfile.mkdtemp(prefix="review-batch-", dir=Path(output_root).resolve()))
    batch.chmod(0o700)
    manifest = {"schema_version": "manifest.v1", "batch_id": batch.name, "repository": repository,
        "expected_commits": [metadata["commit_sha"]], "reports": [], "unprocessed_commits": [metadata["commit_sha"]],
        "notification_status": "not_requested"}
    save_json(batch / "manifest.json", manifest)
    assessments, baseline = [], None
    for index in range(2):
        correction = None
        if index:
            previous = assessments[0]
            raw = (Path(previous["job"]) / "pi.stdout.txt").read_text(encoding="utf-8")
            correction = {"errors": previous["feedback"], "previous_output": raw}
            if len(json.dumps(correction, ensure_ascii=False).encode("utf-8")) > 131072:
                previous["feedback"].append("correction_material_too_large")
                break
            try:
                parsed = parse_json(raw)
                if isinstance(parsed, dict):
                    baseline = parsed
            except ValueError:
                pass
        remaining = timeout - (time.monotonic() - started)
        if remaining <= 0:
            if not assessments:
                raise ValueError("输入准备已耗尽总预算，未启动模型")
            assessments[-1].update(error="timeout", feedback=["review_budget_exhausted"])
            break
        execution = run_review(root, batch / "model-attempts", timeout=remaining, label=f"model-{index + 1}",
            model=model, pi_command=pi_command, correction=correction, review_budget_seconds=timeout,
            model_profile=model_profile)
        assessment = assess_attempt(execution["attempt_directory"], ROOT)
        assessments.append(assessment)
        if assessment["error"] != "invalid_output":
            break
    final = assessments[-1]
    if not final["error"] and len(assessments) > 1 and not final["candidate"]["findings"] and (
            not isinstance(baseline, dict) or not isinstance(baseline.get("findings"), list)):
        final.update(error="invalid_output", feedback=["cannot_verify_empty_correction_preserved_findings"])
    if not final["error"] and baseline:
        # 修正路径或协议不能靠丢弃已提出的问题、把未完成提升为完成来通过校验。
        original = baseline.get("findings")
        original_ids = {item["id"] for item in original if isinstance(item, dict) and isinstance(item.get("id"), str)
                        and re.fullmatch(r"F[0-9]{3,6}", item["id"])} if isinstance(original, list) else set()
        if isinstance(original, list) and len(final["candidate"]["findings"]) < len(original):
            final.update(error="invalid_output", feedback=["correction_dropped_findings"])
        elif not original_ids <= {item["id"] for item in final["candidate"]["findings"]}:
            final.update(error="invalid_output", feedback=["correction_replaced_finding_ids"])
        elif baseline.get("review_complete") is False and final["candidate"]["review_complete"]:
            final.update(error="invalid_output", feedback=["correction_changed_completion_claim"])
    if time.monotonic() - started >= timeout:
        final.update(error="timeout", feedback=["review_budget_exhausted"])
    report = build_report(final, repository=repository, branch=branch, run_url=run_url,
                          control_directory=ROOT, started_at=started_at)
    directory = batch / (report["commit_sha"][:12] + "-" + report["attempt_id"])
    write_report(directory, report, assessments, corrections=len(assessments) - 1)
    manifest["reports"] = [{"commit_sha": report["commit_sha"], "attempt_id": report["attempt_id"],
        "review_key": report["review_key"], "review_status": report["review_status"], "verdict": report["verdict"],
        "error": report["error"], "files": {name: str((directory / name).relative_to(batch)) for name in ("review.json", "review.md", "execution.json")}}]
    manifest["unprocessed_commits"] = []
    save_json(batch / "manifest.json", manifest)
    return batch, report


def main():
    """CLI 仅审查指定过滤输入；未完成或失败返回非零，不发送通知。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output-root", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--branch")
    parser.add_argument("--run-url")
    parser.add_argument("--timeout", type=float, default=480)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    args = parser.parse_args()
    batch, report = run_one(args.input, args.output_root, repository=args.repository, branch=args.branch,
        run_url=args.run_url, timeout=args.timeout, model=args.model)
    print(json.dumps({"batch_directory": str(batch), "review_status": report["review_status"],
        "verdict": report["verdict"], "error": report["error"]}, ensure_ascii=False, indent=2))
    return 0 if report["review_status"] == "completed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
