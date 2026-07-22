#!/usr/bin/env python3
"""Validate LLM aesthetic annotation JSONL files."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def validate_core(record: Any) -> list[str]:
    if not isinstance(record, dict):
        return ["record must be an object"]
    errors: list[str] = []
    required = {
        "schema_version", "photo_id", "photo_type", "scene_category",
        "visual_summary", "composition", "lighting", "camera", "subject",
        "aesthetic_score", "template_value", "shooting_guidance",
    }
    missing = sorted(required - record.keys())
    if missing:
        errors.append(f"missing fields: {', '.join(missing)}")
        return errors
    if record["schema_version"] != 1:
        errors.append("schema_version must be 1")
    if record["photo_type"] not in {"portrait", "landscape", "still_life"}:
        errors.append("invalid photo_type")
    if not isinstance(record["aesthetic_score"], (int, float)) or not 0 <= record["aesthetic_score"] <= 5:
        errors.append("aesthetic_score must be in [0, 5]")
    if record["template_value"] not in {"reject", "low", "medium", "high"}:
        errors.append("invalid template_value")
    guidance = record["shooting_guidance"]
    if not isinstance(guidance, list) or not 1 <= len(guidance) <= 5:
        errors.append("shooting_guidance must contain 1..5 items")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    script_dir = Path(__file__).resolve().parent
    parser.add_argument("annotations", type=Path)
    parser.add_argument("--schema", type=Path, default=script_dir / "schema" / "aesthetic_annotation.schema.json")
    args = parser.parse_args()
    try:
        from jsonschema import Draft202012Validator, FormatChecker
    except ImportError:
        validator = None
        print("jsonschema not installed; running core validation")
    else:
        schema = json.loads(args.schema.read_text(encoding="utf-8"))
        validator = Draft202012Validator(schema, format_checker=FormatChecker())

    failures = 0
    records = 0
    with args.annotations.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            records += 1
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                failures += 1
                print(f"line {line_number}: invalid JSON: {exc}")
                continue
            if validator is None:
                messages = validate_core(record)
            else:
                messages = [
                    f"{'.'.join(str(part) for part in error.path) or '<root>'}: {error.message}"
                    for error in sorted(validator.iter_errors(record), key=lambda item: list(item.path))
                ]
            for message in messages:
                failures += 1
                print(f"line {line_number}: {message}")
    if failures:
        print(f"Validation failed: records={records}, errors={failures}")
        return 1
    print(f"Validation passed: records={records}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
