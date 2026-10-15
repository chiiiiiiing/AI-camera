#!/usr/bin/env python3
"""Validate template metadata JSONL before index construction."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path
from typing import Any


def finite_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and math.isfinite(float(value))


def validate_keypoints(value: Any, field: str) -> list[str]:
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


def validate_core(record: Any) -> list[str]:
    if not isinstance(record, dict):
        return ["record must be an object"]
    errors: list[str] = []
    required = {
        "schema_version", "template_id", "photo_id", "photo_type", "scene_category",
        "source_path", "source_sha256", "license", "landing_url", "creator",
        "width", "height", "pose", "annotation", "embedding",
    }
    missing = sorted(required - record.keys())
    if missing:
        errors.append(f"missing fields: {', '.join(missing)}")
        return errors
    if record["schema_version"] != 1:
        errors.append("schema_version must be 1")
    if record["template_id"] != record["photo_id"]:
        errors.append("template_id must match photo_id for pilot metadata")
    if record["photo_type"] not in {"portrait", "landscape", "still_life"}:
        errors.append("invalid photo_type")
    if not isinstance(record["width"], int) or record["width"] <= 0:
        errors.append("width must be a positive integer")
    if not isinstance(record["height"], int) or record["height"] <= 0:
        errors.append("height must be a positive integer")

    pose = record["pose"]
    if not isinstance(pose, dict):
        errors.append("pose must be an object")
    else:
        pose_required = pose.get("required")
        pose_detected = pose.get("detected")
        if not isinstance(pose_required, bool) or not isinstance(pose_detected, bool):
            errors.append("pose.required and pose.detected must be booleans")
        elif pose_required:
            if pose_detected is not True:
                errors.append("pose.required=true requires pose.detected=true")
            errors.extend(validate_keypoints(pose.get("keypoints_coco17"), "pose.keypoints_coco17"))
            errors.extend(
                validate_keypoints(pose.get("keypoints_normalized"), "pose.keypoints_normalized")
            )
            angles = pose.get("joint_angles_degrees")
            if not isinstance(angles, dict) or not angles:
                errors.append("pose.joint_angles_degrees must be a non-empty object for portraits")
            elif any(not finite_number(value) or not 0 <= float(value) <= 180 for value in angles.values()):
                errors.append("pose.joint_angles_degrees values must be finite degrees in [0, 180]")
        elif pose_detected:
            errors.append("non-pose template must not set pose.detected=true")

    annotation = record["annotation"]
    if not isinstance(annotation, dict):
        errors.append("annotation must be an object")
    else:
        value = annotation.get("template_value")
        score = annotation.get("aesthetic_score")
        guidance = annotation.get("shooting_guidance")
        if value == "unrated":
            if "aesthetic_score" not in annotation or score is not None:
                errors.append("unrated annotation.aesthetic_score must be explicit null")
            if guidance != []:
                errors.append("unrated annotation.shooting_guidance must be empty")
            provenance = annotation.get("provenance")
            if not isinstance(provenance, dict) or provenance.get("aesthetic_ground_truth") is not False:
                errors.append("unrated annotation requires provenance.aesthetic_ground_truth=false")
            if "reference_review" not in record:
                errors.append("unrated annotation requires hash-bound AI reference_review")
        else:
            if value not in {"medium", "high"}:
                errors.append("annotation.template_value must be medium or high")
            if not finite_number(score) or not 3 <= float(score) <= 5:
                errors.append("annotation.aesthetic_score must be in [3, 5]")
            if not isinstance(guidance, list) or not 1 <= len(guidance) <= 5:
                errors.append("annotation.shooting_guidance must contain 1..5 items")

    if "reference_review" in record:
        review = record["reference_review"]
        if not isinstance(review, dict):
            errors.append("reference_review must be an object")
        else:
            if (review.get("photo_id") != record["photo_id"] or
                    review.get("source_sha256") != record["source_sha256"]):
                errors.append("reference_review must match photo_id and source_sha256")
            digest = review.get("source_sha256")
            if not isinstance(digest, str) or re.fullmatch(r"[a-f0-9]{64}", digest) is None:
                errors.append("reference_review.source_sha256 must be a valid SHA-256")
            if review.get("status") != "candidate_clean":
                errors.append("reference_review.status must be candidate_clean")
            provenance = review.get("provenance")
            if not isinstance(provenance, dict) or provenance.get("method") != "ai_visual_review":
                errors.append("reference_review requires provenance.method=ai_visual_review")

    embedding = record["embedding"]
    if not isinstance(embedding, dict):
        errors.append("embedding must be an object")
    else:
        if embedding.get("status") not in {"pending", "ready"}:
            errors.append("embedding.status must be pending or ready")
        if not isinstance(embedding.get("dimension"), int) or embedding["dimension"] <= 0:
            errors.append("embedding.dimension must be a positive integer")
        if not isinstance(embedding.get("path"), str) or not embedding["path"]:
            errors.append("embedding.path must be a non-empty string")
    return errors


def validate_jsonl(path: Path, schema_path: Path) -> tuple[list[str], dict[str, int]]:
    try:
        from jsonschema import Draft202012Validator, FormatChecker
    except ImportError:
        validator = None
    else:
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        validator = Draft202012Validator(schema, format_checker=FormatChecker())

    errors: list[str] = []
    seen_template_ids: set[str] = set()
    summary = {"records": 0}
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            summary["records"] += 1
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                errors.append(f"line {line_number}: invalid JSON: {exc}")
                continue
            if validator is None:
                messages = validate_core(record)
            else:
                messages = [
                    f"{'.'.join(str(part) for part in error.path) or '<root>'}: {error.message}"
                    for error in sorted(validator.iter_errors(record), key=lambda item: list(item.path))
                ]
                messages.extend(validate_core(record))
            template_id = record.get("template_id") if isinstance(record, dict) else None
            if isinstance(template_id, str):
                if template_id in seen_template_ids:
                    messages.append(f"duplicate template_id: {template_id}")
                seen_template_ids.add(template_id)
            errors.extend(f"line {line_number}: {message}" for message in messages)
    return errors, summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    script_dir = Path(__file__).resolve().parent
    parser.add_argument("metadata", type=Path)
    parser.add_argument("--schema", type=Path, default=script_dir / "schema" / "template_metadata.schema.json")
    args = parser.parse_args()
    try:
        errors, summary = validate_jsonl(args.metadata, args.schema)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
