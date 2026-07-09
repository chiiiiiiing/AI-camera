#!/usr/bin/env python3
"""Validate the raw photo JSONL manifest against its JSON Schema."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


def _is_url(value: Any) -> bool:
    if value is None:
        return True
    return isinstance(value, str) and urlparse(value).scheme in {"http", "https"}


def validate_core(record: Any) -> list[str]:
    """Dependency-free validation for the invariants used by the pipeline."""
    if not isinstance(record, dict):
        return ["record must be an object"]
    errors: list[str] = []
    required = {
        "schema_version", "photo_id", "photo_type", "scene_category", "query",
        "file_path", "sha256", "width", "height", "reported_width",
        "reported_height", "source", "rights", "review",
    }
    missing = sorted(required - record.keys())
    if missing:
        errors.append(f"missing fields: {', '.join(missing)}")
        return errors
    if record["schema_version"] != 1:
        errors.append("schema_version must be 1")
    if record["photo_type"] not in {"portrait", "landscape", "still_life"}:
        errors.append("invalid photo_type")
    if not isinstance(record["sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", record["sha256"]):
        errors.append("sha256 must contain 64 lowercase hexadecimal characters")
    for field in ("width", "height", "reported_width", "reported_height"):
        if not isinstance(record[field], int) or record[field] < 1:
            errors.append(f"{field} must be a positive integer")

    source = record["source"]
    source_fields = {"provider", "provider_id", "landing_url", "asset_url", "creator", "creator_url"}
    if not isinstance(source, dict) or not source_fields.issubset(source):
        errors.append("source is incomplete")
    elif not all(_is_url(source[field]) for field in ("landing_url", "asset_url", "creator_url")):
        errors.append("source contains an invalid URL")

    rights = record["rights"]
    if not isinstance(rights, dict) or rights.get("license") not in {"cc0", "pdm"}:
        errors.append("rights.license must be cc0 or pdm")
    elif rights.get("verification_status") != "provider_declared_pending_review":
        errors.append("invalid rights.verification_status")
    elif not _is_url(rights.get("license_url")):
        errors.append("invalid rights.license_url")

    review = record["review"]
    if not isinstance(review, dict):
        errors.append("review must be an object")
    else:
        valid_statuses = {"pending", "accepted", "rejected"}
        if review.get("quality_status") not in valid_statuses:
            errors.append("invalid review.quality_status")
        if review.get("rights_status") not in valid_statuses:
            errors.append("invalid review.rights_status")
        if review.get("portrait_release_status") not in valid_statuses | {"not_applicable"}:
            errors.append("invalid review.portrait_release_status")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    script_dir = Path(__file__).resolve().parent
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--schema", type=Path, default=script_dir / "schema" / "raw_photo.schema.json")
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
    with args.manifest.open("r", encoding="utf-8") as handle:
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
                for message in validate_core(record):
                    failures += 1
                    print(f"line {line_number}: {message}")
            else:
                for error in sorted(validator.iter_errors(record), key=lambda item: list(item.path)):
                    failures += 1
                    field = ".".join(str(part) for part in error.path) or "<root>"
                    print(f"line {line_number} {field}: {error.message}")

    if failures:
        print(f"Validation failed: records={records}, errors={failures}")
        return 1
    print(f"Validation passed: records={records}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
