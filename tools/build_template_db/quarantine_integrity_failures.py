#!/usr/bin/env python3
"""Reject manifest records whose local files fail integrity checks."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_records(path: Path) -> list[dict[str, Any]]:
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
            value["_manifest_line"] = line_number
            records.append(value)
    return records


def write_records(path: Path, records: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for record in records:
            record.pop("_manifest_line", None)
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")


def integrity_failure(record: dict[str, Any], manifest_dir: Path) -> str | None:
    image_path = manifest_dir / record.get("file_path", "")
    if not image_path.exists():
        return "missing_file"
    if sha256_file(image_path) != record.get("sha256"):
        return "sha256_mismatch"
    return None


def quarantine_records(
    records: list[dict[str, Any]], manifest_dir: Path, reviewed_at: str | None = None
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    reviewed_at = reviewed_at or datetime.now(timezone.utc).isoformat()
    summary = {
        "records": len(records),
        "integrity_failures": 0,
        "newly_rejected": 0,
        "already_rejected": 0,
        "reasons": {},
    }
    updated: list[dict[str, Any]] = []
    for record in records:
        failure = integrity_failure(record, manifest_dir)
        if not failure:
            updated.append(record)
            continue
        summary["integrity_failures"] += 1
        reasons = summary["reasons"]
        reasons[failure] = reasons.get(failure, 0) + 1
        review = record.setdefault("review", {})
        if review.get("quality_status") == "rejected":
            summary["already_rejected"] += 1
        else:
            review["quality_status"] = "rejected"
            review["rejection_reason"] = failure
            review["reviewed_at"] = reviewed_at
            summary["newly_rejected"] += 1
        updated.append(record)
    return updated, summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        records = load_records(args.manifest)
        updated, summary = quarantine_records(records, args.manifest.parent)
        write_records(args.output, updated)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    print(f"Wrote: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
