#!/usr/bin/env python3
"""Validate preprocessed output records referenced by a jobs JSONL file."""

from __future__ import annotations

import argparse
import json
import math
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


def finite_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and math.isfinite(float(value))


def validate_keypoint_list(value: Any, field: str) -> list[str]:
    errors: list[str] = []
    if not isinstance(value, list) or len(value) != 17:
        return [f"{field} must contain 17 keypoints"]
    for index, point in enumerate(value):
        if not isinstance(point, dict):
            errors.append(f"{field}[{index}] must be an object")
            continue
        if point.get("idx") != index:
            errors.append(f"{field}[{index}].idx must equal {index}")
        for coordinate in ("x", "y", "confidence"):
            if not finite_number(point.get(coordinate)):
                errors.append(f"{field}[{index}].{coordinate} must be finite")
    return errors


def validate_record(job: dict[str, Any], record: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if record.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    for field in ("photo_id", "photo_type", "scene_category", "source_sha256"):
        if record.get(field) != job.get(field if field != "source_sha256" else "source_sha256"):
            errors.append(f"{field} does not match job")
    pose_required = bool(job.get("pose_required"))
    if pose_required:
        if record.get("pose_detected") is not True:
            errors.append("portrait record must have pose_detected=true")
        errors.extend(validate_keypoint_list(record.get("keypoints_coco17"), "keypoints_coco17"))
        errors.extend(validate_keypoint_list(record.get("keypoints_normalized"), "keypoints_normalized"))
        angles = record.get("joint_angles_degrees")
        if not isinstance(angles, dict) or not angles:
            errors.append("joint_angles_degrees must be a non-empty object")
        elif any(not finite_number(value) or not 0 <= float(value) <= 180 for value in angles.values()):
            errors.append("joint_angles_degrees values must be finite degrees in [0, 180]")
    else:
        if record.get("pose_detected") is not False:
            errors.append("non-portrait record must have pose_detected=false")
        if record.get("pose_required") is not False:
            errors.append("non-portrait record must have pose_required=false")
    return errors


def validate_jobs(jobs_path: Path) -> tuple[list[str], dict[str, int]]:
    errors: list[str] = []
    summary = {"jobs": 0, "records": 0, "portrait": 0, "non_portrait": 0}
    for job in load_jsonl(jobs_path):
        summary["jobs"] += 1
        record_path = Path(job.get("output", {}).get("record_path", ""))
        prefix = f"{job.get('photo_id', '<unknown>')}:"
        if not record_path.exists():
            errors.append(f"{prefix} missing preprocessed record: {record_path}")
            continue
        try:
            record = json.loads(record_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            errors.append(f"{prefix} invalid preprocessed JSON: {exc}")
            continue
        summary["records"] += 1
        if job.get("pose_required"):
            summary["portrait"] += 1
        else:
            summary["non_portrait"] += 1
        for error in validate_record(job, record):
            errors.append(f"{prefix} {error}")
    return errors, summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jobs", type=Path)
    args = parser.parse_args()
    try:
        errors, summary = validate_jobs(args.jobs)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
