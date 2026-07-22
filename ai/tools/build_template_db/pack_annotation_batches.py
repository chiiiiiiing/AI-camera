#!/usr/bin/env python3
"""Pack visual annotation requests into deterministic JSONL batches."""

from __future__ import annotations

import argparse
import json
import sys
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


def split_batches(rows: list[dict[str, Any]], max_items: int) -> list[list[dict[str, Any]]]:
    if max_items <= 0:
        raise ValueError("max_items must be positive")
    return [rows[index : index + max_items] for index in range(0, len(rows), max_items)]


def validate_request_ids(rows: list[dict[str, Any]]) -> None:
    seen: set[str] = set()
    for row in rows:
        photo_id = row.get("photo_id")
        if not isinstance(photo_id, str) or not photo_id:
            raise ValueError("annotation request missing photo_id")
        if photo_id in seen:
            raise ValueError(f"duplicate photo_id in requests: {photo_id}")
        seen.add(photo_id)


def pack_batches(
    requests_path: Path,
    output_dir: Path,
    max_items: int = 50,
    prefix: str = "annotation_batch",
) -> dict[str, Any]:
    rows = load_jsonl(requests_path)
    validate_request_ids(rows)
    batches = split_batches(rows, max_items)
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest_batches: list[dict[str, Any]] = []
    for index, batch_rows in enumerate(batches, 1):
        batch_id = f"{prefix}_{index:04d}"
        path = output_dir / f"{batch_id}.jsonl"
        write_jsonl(path, batch_rows)
        manifest_batches.append(
            {
                "batch_id": batch_id,
                "path": str(path),
                "items": len(batch_rows),
                "photo_ids": [str(row["photo_id"]) for row in batch_rows],
            }
        )
    summary = {
        "schema_version": 1,
        "source": str(requests_path),
        "output_dir": str(output_dir),
        "total_requests": len(rows),
        "max_items": max_items,
        "batches": manifest_batches,
    }
    (output_dir / "batch_manifest.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("requests", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--max-items", type=int, default=50)
    parser.add_argument("--prefix", default="annotation_batch")
    args = parser.parse_args()
    try:
        summary = pack_batches(args.requests, args.output_dir, args.max_items, args.prefix)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
