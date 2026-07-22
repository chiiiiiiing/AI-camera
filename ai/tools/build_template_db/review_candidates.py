#!/usr/bin/env python3
"""Record human quality decisions in a candidate manifest."""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def load_records(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON on line {line_number}: {exc}") from exc
            if not isinstance(record, dict):
                raise ValueError(f"line {line_number} is not an object")
            records.append(record)
    return records


def save_records_atomic(path: Path, records: list[dict[str, Any]]) -> None:
    temp_path = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        with temp_path.open("w", encoding="utf-8") as handle:
            for record in records:
                handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
        temp_path.replace(path)
    finally:
        if temp_path.exists():
            temp_path.unlink()


def set_quality_status(
    records: list[dict[str, Any]],
    photo_id: str,
    status: str,
    reason: str | None,
) -> dict[str, Any]:
    matches = [record for record in records if record.get("photo_id") == photo_id]
    if len(matches) != 1:
        raise ValueError(f"expected one record for {photo_id}, found {len(matches)}")
    record = matches[0]
    review = record.setdefault("review", {})
    review["quality_status"] = status
    review["rejection_reason"] = reason if status == "rejected" else None
    review["reviewed_at"] = datetime.now(timezone.utc).isoformat()
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("photo_id")
    parser.add_argument("status", choices=("accepted", "rejected"))
    parser.add_argument("--reason", help="Required for rejected candidates")
    parser.add_argument(
        "--delete-file",
        action="store_true",
        help="Delete the local image after recording a rejection",
    )
    args = parser.parse_args()
    if args.status == "rejected" and not args.reason:
        parser.error("--reason is required when rejecting a candidate")
    if args.status == "accepted" and args.delete_file:
        parser.error("--delete-file is only valid when rejecting")

    try:
        records = load_records(args.manifest)
        record = set_quality_status(records, args.photo_id, args.status, args.reason)
        save_records_atomic(args.manifest, records)
        if args.delete_file:
            image_path = args.manifest.parent / record["file_path"]
            if image_path.exists():
                image_path.unlink()
        print(f"{args.photo_id}: quality_status={args.status}")
        return 0
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
