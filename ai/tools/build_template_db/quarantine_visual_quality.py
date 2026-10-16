#!/usr/bin/env python3
"""Reject records with severe visual quality flags from a report."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
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
            rows.append(value)
    return rows


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def flagged_photo_ids(report_rows: list[dict[str, Any]], allowed_flags: set[str]) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for row in report_rows:
        flags = sorted(set(row.get("flags") or []) & allowed_flags)
        photo_id = row.get("photo_id")
        if flags and isinstance(photo_id, str):
            result[photo_id] = flags
    return result


def quarantine_records(
    records: list[dict[str, Any]],
    report_rows: list[dict[str, Any]],
    allowed_flags: set[str],
    reviewed_at: str | None = None,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    reviewed_at = reviewed_at or datetime.now(timezone.utc).isoformat()
    failures = flagged_photo_ids(report_rows, allowed_flags)
    summary = {
        "records": len(records),
        "visual_failures": len(failures),
        "newly_rejected": 0,
        "flags": {},
    }
    for record in records:
        photo_id = record.get("photo_id")
        flags = failures.get(str(photo_id))
        if not flags:
            continue
        review = record.setdefault("review", {})
        if review.get("quality_status") != "accepted":
            continue
        review["quality_status"] = "rejected"
        review["rejection_reason"] = f"visual_quality:{','.join(flags)}"
        review["reviewed_at"] = reviewed_at
        summary["newly_rejected"] += 1
        for flag in flags:
            counts = summary["flags"]
            counts[flag] = counts.get(flag, 0) + 1
    return records, summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--flags",
        nargs="+",
        default=["decode_error", "very_dark", "very_bright", "very_low_contrast"],
        help="Visual quality flags that should reject a record",
    )
    args = parser.parse_args()
    try:
        updated, summary = quarantine_records(
            load_jsonl(args.manifest),
            load_jsonl(args.report),
            {str(flag) for flag in args.flags},
        )
        write_jsonl(args.output, updated)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    print(f"Wrote: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
