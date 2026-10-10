#!/usr/bin/env python3
"""Validate float32 embedding files referenced by preprocessing jobs."""

from __future__ import annotations

import argparse
import json
import math
import struct
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


def read_f32(path: Path) -> list[float]:
    payload = path.read_bytes()
    if len(payload) % 4 != 0:
        raise ValueError("file size is not a multiple of 4 bytes")
    count = len(payload) // 4
    return list(struct.unpack(f"<{count}f", payload))


def validate_embedding_values(values: list[float], expected_dimension: int = 256) -> list[str]:
    errors: list[str] = []
    if len(values) != expected_dimension:
        errors.append(f"expected {expected_dimension} floats, got {len(values)}")
        return errors
    if not all(math.isfinite(value) for value in values):
        errors.append("embedding contains non-finite values")
        return errors
    magnitude = math.sqrt(sum(value * value for value in values))
    if not 0.99 <= magnitude <= 1.01:
        errors.append(f"embedding is not L2-normalized: magnitude={magnitude:.6f}")
    return errors


def validate_jobs(
    jobs_path: Path,
    expected_dimension: int = 256,
    allow_missing: bool = False,
) -> tuple[list[str], dict[str, int]]:
    errors: list[str] = []
    summary = {"jobs": 0, "present": 0, "missing": 0, "valid": 0}
    for job in load_jsonl(jobs_path):
        summary["jobs"] += 1
        photo_id = job.get("photo_id", "<unknown>")
        embedding_path = Path(job.get("output", {}).get("embedding_path", ""))
        if not embedding_path.exists():
            summary["missing"] += 1
            if not allow_missing:
                errors.append(f"{photo_id}: missing embedding: {embedding_path}")
            continue
        summary["present"] += 1
        try:
            value_errors = validate_embedding_values(read_f32(embedding_path), expected_dimension)
        except (OSError, ValueError) as exc:
            errors.append(f"{photo_id}: invalid embedding file: {exc}")
            continue
        if value_errors:
            errors.extend(f"{photo_id}: {message}" for message in value_errors)
        else:
            summary["valid"] += 1
    return errors, summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jobs", type=Path)
    parser.add_argument("--dimension", type=int, default=256)
    parser.add_argument("--allow-missing", action="store_true")
    args = parser.parse_args()
    try:
        errors, summary = validate_jobs(args.jobs, args.dimension, args.allow_missing)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
