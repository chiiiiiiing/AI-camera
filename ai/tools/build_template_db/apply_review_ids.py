#!/usr/bin/env python3
"""Apply accept/reject decisions using short IDs from a review pack."""

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
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def load_short_id_map(index_path: Path) -> dict[str, str]:
    mapping: dict[str, str] = {}
    for row in load_jsonl(index_path):
        short_id = str(row.get("short_id", "")).upper()
        photo_id = row.get("photo_id")
        if short_id and isinstance(photo_id, str):
            mapping[short_id] = photo_id
    return mapping


def apply_decisions(
    records: list[dict[str, Any]],
    short_to_photo: dict[str, str],
    short_ids: list[str],
    status: str,
    reason: str | None,
    reviewed_at: str | None = None,
) -> list[str]:
    reviewed_at = reviewed_at or datetime.now(timezone.utc).isoformat()
    wanted = {short_id.upper() for short_id in short_ids}
    photo_ids: set[str] = set()
    unknown = sorted(short_id for short_id in wanted if short_id not in short_to_photo)
    if unknown:
        raise ValueError(f"unknown short IDs: {', '.join(unknown)}")
    for short_id in wanted:
        photo_ids.add(short_to_photo[short_id])
    changed: list[str] = []
    for record in records:
        if record.get("photo_id") not in photo_ids:
            continue
        review = record.setdefault("review", {})
        review["quality_status"] = status
        review["rejection_reason"] = reason if status == "rejected" else None
        review["reviewed_at"] = reviewed_at
        changed.append(str(record.get("photo_id")))
    if len(changed) != len(photo_ids):
        missing = sorted(photo_ids - set(changed))
        raise ValueError(f"photo IDs missing from manifest: {', '.join(missing)}")
    return changed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("review_index", type=Path)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--reject", nargs="+", help="Short IDs to reject, e.g. P001 P004")
    group.add_argument("--accept", nargs="+", help="Short IDs to accept, e.g. P002 P003")
    parser.add_argument("--reason", default="human_rejected", help="Reason used for rejected records")
    parser.add_argument("--output", type=Path, help="Write updated manifest here; defaults to in-place")
    args = parser.parse_args()
    status = "rejected" if args.reject else "accepted"
    short_ids = args.reject or args.accept or []
    try:
        records = load_jsonl(args.manifest)
        changed = apply_decisions(
            records,
            load_short_id_map(args.review_index),
            short_ids,
            status,
            args.reason,
        )
        write_jsonl(args.output or args.manifest, records)
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(f"{status}: {len(changed)} records")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
