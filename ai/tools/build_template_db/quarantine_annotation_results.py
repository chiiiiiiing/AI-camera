#!/usr/bin/env python3
"""Reject manifest records whose aesthetic annotation says they are weak templates."""

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


def failing_annotations(
    annotations: list[dict[str, Any]],
    min_score: float,
    rejected_values: set[str],
) -> dict[str, dict[str, Any]]:
    failures: dict[str, dict[str, Any]] = {}
    for annotation in annotations:
        photo_id = annotation.get("photo_id")
        if not isinstance(photo_id, str):
            continue
        score = annotation.get("aesthetic_score")
        template_value = annotation.get("template_value")
        if template_value in rejected_values or not isinstance(score, (int, float)) or float(score) < min_score:
            failures[photo_id] = annotation
    return failures


def quarantine_records(
    records: list[dict[str, Any]],
    annotations: list[dict[str, Any]],
    min_score: float = 3.0,
    rejected_values: set[str] | None = None,
    reviewed_at: str | None = None,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    rejected_values = rejected_values or {"reject", "low"}
    reviewed_at = reviewed_at or datetime.now(timezone.utc).isoformat()
    failures = failing_annotations(annotations, min_score, rejected_values)
    summary = {
        "records": len(records),
        "annotation_failures": len(failures),
        "newly_rejected": 0,
        "reasons": {},
    }
    for record in records:
        annotation = failures.get(str(record.get("photo_id")))
        if not annotation:
            continue
        review = record.setdefault("review", {})
        if review.get("quality_status") != "accepted":
            continue
        template_value = annotation.get("template_value")
        score = annotation.get("aesthetic_score")
        reason = f"annotation_{template_value}_score_{score}"
        review["quality_status"] = "rejected"
        review["rejection_reason"] = reason
        review["reviewed_at"] = reviewed_at
        summary["newly_rejected"] += 1
        reasons = summary["reasons"]
        reasons[str(template_value)] = reasons.get(str(template_value), 0) + 1
    return records, summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("annotations", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--min-score", type=float, default=3.0)
    parser.add_argument("--reject-values", nargs="+", default=["reject", "low"])
    args = parser.parse_args()
    try:
        updated, summary = quarantine_records(
            load_jsonl(args.manifest),
            load_jsonl(args.annotations),
            args.min_score,
            set(args.reject_values),
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
