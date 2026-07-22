#!/usr/bin/env python3
"""Merge and validate visual annotation result JSONL files."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import validate_annotations


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON in {path} on line {line_number}: {exc}") from exc
            if not isinstance(value, dict):
                raise ValueError(f"{path} line {line_number} is not an object")
            rows.append(value)
    return rows


def request_order(requests_path: Path) -> list[str]:
    order: list[str] = []
    seen: set[str] = set()
    for request in load_jsonl(requests_path):
        photo_id = request.get("photo_id")
        if not isinstance(photo_id, str) or not photo_id:
            raise ValueError("annotation request missing photo_id")
        if photo_id in seen:
            raise ValueError(f"duplicate photo_id in requests: {photo_id}")
        seen.add(photo_id)
        order.append(photo_id)
    return order


def parse_json_from_text(text: str) -> dict[str, Any]:
    stripped = text.strip()
    if stripped.startswith("```"):
        lines = stripped.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        stripped = "\n".join(lines).strip()
    decoder = json.JSONDecoder()
    for index, character in enumerate(stripped):
        if character != "{":
            continue
        try:
            value, _ = decoder.raw_decode(stripped[index:])
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            return value
    raise ValueError("no JSON object found in text")


def annotation_from_record(record: dict[str, Any]) -> dict[str, Any]:
    if not validate_annotations.validate_core(record):
        return record
    if isinstance(record.get("annotation"), dict):
        annotation = dict(record["annotation"])
    else:
        text = None
        for field in ("response_text", "content", "text"):
            if isinstance(record.get(field), str):
                text = record[field]
                break
        if text is None:
            return record
        annotation = parse_json_from_text(text)
    if "photo_id" not in annotation and isinstance(record.get("photo_id"), str):
        annotation["photo_id"] = record["photo_id"]
    return annotation


def load_annotations(paths: list[Path]) -> tuple[dict[str, dict[str, Any]], list[str]]:
    annotations: dict[str, dict[str, Any]] = {}
    errors: list[str] = []
    for path in paths:
        for record in load_jsonl(path):
            try:
                annotation = annotation_from_record(record)
            except ValueError as exc:
                errors.append(f"{path}: {exc}")
                continue
            photo_id = annotation.get("photo_id")
            if not isinstance(photo_id, str) or not photo_id:
                errors.append(f"{path}: annotation missing photo_id")
                continue
            if photo_id in annotations:
                errors.append(f"{path}: duplicate annotation for {photo_id}")
                continue
            validation_errors = validate_annotations.validate_core(annotation)
            if validation_errors:
                errors.extend(f"{path}:{photo_id}: {message}" for message in validation_errors)
                continue
            annotations[photo_id] = annotation
    return annotations, errors


def merge_results(
    requests_path: Path,
    result_paths: list[Path],
    allow_missing: bool = False,
) -> tuple[list[dict[str, Any]], dict[str, int], list[str]]:
    order = request_order(requests_path)
    expected = set(order)
    annotations, errors = load_annotations(result_paths)
    unknown = sorted(set(annotations) - expected)
    errors.extend(f"unknown annotation photo_id: {photo_id}" for photo_id in unknown)
    missing = [photo_id for photo_id in order if photo_id not in annotations]
    if missing and not allow_missing:
        preview = ", ".join(missing[:10])
        suffix = "" if len(missing) <= 10 else f" ... (+{len(missing) - 10} more)"
        errors.append(f"missing annotations: {preview}{suffix}")
    merged = [annotations[photo_id] for photo_id in order if photo_id in annotations and photo_id in expected]
    summary = {
        "requests": len(order),
        "annotations": len(annotations),
        "merged": len(merged),
        "missing": len(missing),
        "unknown": len(unknown),
        "errors": len(errors),
    }
    return merged, summary, errors


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("requests", type=Path)
    parser.add_argument("results", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--allow-missing", action="store_true")
    args = parser.parse_args()
    try:
        merged, summary, errors = merge_results(args.requests, args.results, args.allow_missing)
        if not errors:
            write_jsonl(args.output, merged)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
