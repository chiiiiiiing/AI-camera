#!/usr/bin/env python3
"""Inspect returned visual annotation batch results before merging."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import merge_annotation_results


def load_batch_manifest(batch_dir: Path) -> dict[str, Any]:
    path = batch_dir / "batch_manifest.json"
    if not path.exists():
        raise ValueError(f"missing batch manifest: {path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a JSON object")
    batches = data.get("batches")
    if not isinstance(batches, list):
        raise ValueError(f"{path} missing batches list")
    return data


def result_path(results_dir: Path, batch_id: str) -> Path:
    return results_dir / f"{batch_id}.results.jsonl"


def inspect_batch(batch: dict[str, Any], results_dir: Path) -> dict[str, Any]:
    batch_id = batch.get("batch_id")
    photo_ids = batch.get("photo_ids")
    if not isinstance(batch_id, str) or not batch_id:
        raise ValueError("batch missing batch_id")
    if not isinstance(photo_ids, list) or not all(isinstance(item, str) for item in photo_ids):
        raise ValueError(f"{batch_id} missing photo_ids list")

    expected = set(photo_ids)
    path = result_path(results_dir, batch_id)
    if not path.exists():
        return {
            "batch_id": batch_id,
            "expected": len(photo_ids),
            "errors": [],
            "missing": len(photo_ids),
            "missing_photo_ids": photo_ids,
            "path": str(path),
            "received": 0,
            "status": "pending",
            "unknown": [],
        }

    try:
        annotations, errors = merge_annotation_results.load_annotations([path])
    except (OSError, ValueError) as exc:
        return {
            "batch_id": batch_id,
            "expected": len(photo_ids),
            "errors": [str(exc)],
            "missing": len(photo_ids),
            "missing_photo_ids": photo_ids,
            "path": str(path),
            "received": 0,
            "status": "fail",
            "unknown": [],
        }
    present = sorted(set(annotations) & expected)
    missing_photo_ids = [photo_id for photo_id in photo_ids if photo_id not in annotations]
    unknown = sorted(set(annotations) - expected)
    status = "pass"
    if errors or unknown:
        status = "fail"
    elif missing_photo_ids:
        status = "pending"
    return {
        "batch_id": batch_id,
        "expected": len(photo_ids),
        "errors": errors,
        "missing": len(missing_photo_ids),
        "missing_photo_ids": missing_photo_ids,
        "path": str(path),
        "received": len(present),
        "status": status,
        "unknown": unknown,
    }


def inspect_results(batch_dir: Path, results_dir: Path | None = None) -> dict[str, Any]:
    manifest = load_batch_manifest(batch_dir)
    results_root = results_dir or batch_dir / "results"
    batch_reports = [
        inspect_batch(batch, results_root)
        for batch in manifest["batches"]
    ]
    failed = sum(1 for batch in batch_reports if batch["status"] == "fail")
    pending = sum(1 for batch in batch_reports if batch["status"] == "pending")
    passed = sum(1 for batch in batch_reports if batch["status"] == "pass")
    return {
        "schema_version": 1,
        "batch_dir": str(batch_dir),
        "results_dir": str(results_root),
        "batches": batch_reports,
        "summary": {
            "batches": len(batch_reports),
            "errors": sum(len(batch["errors"]) for batch in batch_reports),
            "expected": sum(batch["expected"] for batch in batch_reports),
            "failed": failed,
            "missing": sum(batch["missing"] for batch in batch_reports),
            "passed": passed,
            "pending": pending,
            "received": sum(batch["received"] for batch in batch_reports),
            "unknown": sum(len(batch["unknown"]) for batch in batch_reports),
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("batch_dir", type=Path)
    parser.add_argument("--results-dir", type=Path)
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()
    try:
        report = inspect_results(args.batch_dir, args.results_dir)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    summary = report["summary"]
    if summary["failed"]:
        return 1
    if args.require_complete and summary["pending"]:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
