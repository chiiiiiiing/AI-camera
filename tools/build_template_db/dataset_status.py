#!/usr/bin/env python3
"""Summarize the current template dataset pipeline artifacts."""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
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


def count_by(rows: list[dict[str, Any]], key: str) -> dict[str, int]:
    return dict(sorted(Counter(str(row.get(key)) for row in rows).items()))


def manifest_summary(path: Path) -> dict[str, Any]:
    rows = load_jsonl(path)
    accepted = [row for row in rows if row.get("review", {}).get("quality_status") == "accepted"]
    return {
        "path": str(path),
        "records": len(rows),
        "quality_status": dict(sorted(Counter(str(row.get("review", {}).get("quality_status")) for row in rows).items())),
        "accepted_photo_types": count_by(accepted, "photo_type"),
        "accepted_scene_categories": count_by(accepted, "scene_category"),
    }


def jobs_summary(path: Path) -> dict[str, Any]:
    rows = load_jsonl(path)
    return {
        "path": str(path),
        "jobs": len(rows),
        "photo_types": count_by(rows, "photo_type"),
        "pose_required": dict(sorted(Counter(str(bool(row.get("pose_required"))) for row in rows).items())),
    }


def report_summary(path: Path) -> dict[str, Any]:
    rows = load_jsonl(path)
    return {
        "path": str(path),
        "records": len(rows),
        "status": count_by(rows, "status"),
    }


def annotation_request_summary(path: Path) -> dict[str, Any]:
    rows = load_jsonl(path)
    return {
        "path": str(path),
        "requests": len(rows),
        "photo_types": count_by(rows, "photo_type"),
    }


def annotation_batch_summary(path: Path) -> dict[str, Any]:
    manifest = path / "batch_manifest.json"
    if manifest.exists():
        data = json.loads(manifest.read_text(encoding="utf-8"))
        batches = data.get("batches", [])
        return {
            "path": str(path),
            "manifest": str(manifest),
            "batches": len(batches) if isinstance(batches, list) else 0,
            "requests": int(data.get("total_requests", 0)),
        }
    batch_files = sorted(path.glob("*.jsonl")) if path.exists() else []
    return {
        "path": str(path),
        "manifest": None,
        "batches": len(batch_files),
        "requests": sum(len(load_jsonl(batch_file)) for batch_file in batch_files),
    }


def annotation_summary(path: Path) -> dict[str, Any]:
    rows = load_jsonl(path)
    return {
        "path": str(path),
        "records": len(rows),
        "photo_types": count_by(rows, "photo_type"),
        "template_value": count_by(rows, "template_value"),
    }


def template_metadata_summary(path: Path) -> dict[str, Any]:
    rows = load_jsonl(path)
    return {
        "path": str(path),
        "records": len(rows),
        "photo_types": count_by(rows, "photo_type"),
        "embedding_status": dict(
            sorted(Counter(str(row.get("embedding", {}).get("status")) for row in rows).items())
        ),
    }


def embedding_summary(jobs_path: Path) -> dict[str, Any]:
    rows = load_jsonl(jobs_path)
    present = 0
    missing = 0
    for row in rows:
        embedding_path = Path(row.get("output", {}).get("embedding_path", ""))
        if embedding_path.exists():
            present += 1
        else:
            missing += 1
    return {
        "jobs": len(rows),
        "present": present,
        "missing": missing,
    }


def build_status(root: Path) -> dict[str, Any]:
    raw = root / "tools" / "raw_photos"
    preprocessed = root / "tools" / "preprocessed"
    jobs_path = preprocessed / "jobs.visual-clean.jsonl"
    return {
        "manifest": manifest_summary(raw / "manifest.visual-clean.jsonl"),
        "jobs": jobs_summary(jobs_path),
        "cv_report": report_summary(preprocessed / "cv_report.visual-clean.jsonl"),
        "annotation_requests": annotation_request_summary(preprocessed / "annotation_requests.visual-clean.jsonl"),
        "annotation_batches": annotation_batch_summary(preprocessed / "annotation_batches.visual-clean"),
        "annotations": annotation_summary(preprocessed / "annotations.visual-clean.jsonl"),
        "template_metadata": template_metadata_summary(preprocessed / "template_metadata.visual-clean.jsonl"),
        "embeddings": embedding_summary(jobs_path),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    try:
        print(json.dumps(build_status(args.root), ensure_ascii=False, indent=2, sort_keys=True))
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
