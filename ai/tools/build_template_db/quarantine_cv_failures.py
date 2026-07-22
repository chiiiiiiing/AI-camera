#!/usr/bin/env python3
"""Reject manifest records whose CV preprocessing report contains failures."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SUCCESS_STATUSES = {"non_portrait", "pose_detected"}


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON on line {line_number}: {exc}") from exc
            if not isinstance(value, dict):
                raise ValueError(f"line {line_number} is not an object")
            records.append(value)
    return records


def write_jsonl(path: Path, records: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")


def failure_map(report_rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    failures: dict[str, dict[str, Any]] = {}
    for row in report_rows:
        status = row.get("status")
        photo_id = row.get("photo_id")
        if photo_id and status not in SUCCESS_STATUSES:
            failures[str(photo_id)] = row
    return failures


def quarantine_records(
    records: list[dict[str, Any]],
    report_rows: list[dict[str, Any]],
    reviewed_at: str | None = None,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    reviewed_at = reviewed_at or datetime.now(timezone.utc).isoformat()
    failures = failure_map(report_rows)
    summary = {
        "records": len(records),
        "cv_failures": len(failures),
        "newly_rejected": 0,
        "already_rejected": 0,
    }
    for record in records:
        photo_id = record.get("photo_id")
        failure = failures.get(str(photo_id))
        if not failure:
            continue
        review = record.setdefault("review", {})
        if review.get("quality_status") == "rejected":
            summary["already_rejected"] += 1
            continue
        review["quality_status"] = "rejected"
        review["rejection_reason"] = f"cv_preprocess_{failure.get('status')}"
        review["cv_failure_reason"] = failure.get("reason")
        review["reviewed_at"] = reviewed_at
        summary["newly_rejected"] += 1
    return records, summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        records = load_jsonl(args.manifest)
        report_rows = load_jsonl(args.report)
        updated, summary = quarantine_records(records, report_rows)
        write_jsonl(args.output, updated)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    print(f"Wrote: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
