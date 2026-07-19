#!/usr/bin/env python3
"""Build template metadata JSONL from manifest, preprocessing output and annotations."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import validate_embeddings


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


def index_by_photo_id(rows: list[dict[str, Any]], name: str) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        photo_id = row.get("photo_id")
        if not isinstance(photo_id, str):
            raise ValueError(f"{name} row missing photo_id")
        if photo_id in result:
            raise ValueError(f"duplicate photo_id in {name}: {photo_id}")
        result[photo_id] = row
    return result


def accepted_manifest_records(path: Path) -> list[dict[str, Any]]:
    return [
        record
        for record in load_jsonl(path)
        if record.get("review", {}).get("quality_status") == "accepted"
    ]


def load_preprocessed_record(job: dict[str, Any]) -> dict[str, Any]:
    record_path = Path(job.get("output", {}).get("record_path", ""))
    if not record_path.exists():
        raise ValueError(f"missing preprocessed record for {job.get('photo_id')}: {record_path}")
    return json.loads(record_path.read_text(encoding="utf-8"))


def embedding_status(job: dict[str, Any]) -> str:
    photo_id = job.get("photo_id", "<unknown>")
    embedding_path = Path(job.get("output", {}).get("embedding_path", ""))
    if not embedding_path.exists():
        return "pending"
    errors = validate_embeddings.validate_embedding_values(validate_embeddings.read_f32(embedding_path))
    if errors:
        raise ValueError(f"invalid embedding for {photo_id}: {'; '.join(errors)}")
    return "ready"


def build_metadata_record(
    manifest_record: dict[str, Any],
    job: dict[str, Any],
    preprocessed: dict[str, Any],
    annotation: dict[str, Any],
) -> dict[str, Any]:
    source = manifest_record.get("source", {})
    rights = manifest_record.get("rights", {})
    return {
        "schema_version": 1,
        "template_id": manifest_record["photo_id"],
        "photo_id": manifest_record["photo_id"],
        "photo_type": manifest_record["photo_type"],
        "scene_category": manifest_record["scene_category"],
        "source_path": job["source_path"],
        "source_sha256": manifest_record["sha256"],
        "license": rights.get("license"),
        "landing_url": source.get("landing_url"),
        "creator": source.get("creator"),
        "width": manifest_record.get("width"),
        "height": manifest_record.get("height"),
        "pose": {
            "required": bool(job.get("pose_required")),
            "detected": bool(preprocessed.get("pose_detected")),
            "keypoints_coco17": preprocessed.get("keypoints_coco17"),
            "keypoints_normalized": preprocessed.get("keypoints_normalized"),
            "joint_angles_degrees": preprocessed.get("joint_angles_degrees"),
        },
        "annotation": {
            "visual_summary": annotation.get("visual_summary"),
            "composition": annotation.get("composition"),
            "lighting": annotation.get("lighting"),
            "camera": annotation.get("camera"),
            "subject": annotation.get("subject"),
            "aesthetic_score": annotation.get("aesthetic_score"),
            "template_value": annotation.get("template_value"),
            "shooting_guidance": annotation.get("shooting_guidance"),
        },
        "embedding": {
            "status": embedding_status(job),
            "dimension": 256,
            "path": job.get("output", {}).get("embedding_path"),
        },
    }


def build_metadata(manifest: Path, jobs: Path, annotations: Path) -> list[dict[str, Any]]:
    manifest_rows = accepted_manifest_records(manifest)
    jobs_by_id = index_by_photo_id(load_jsonl(jobs), "jobs")
    annotations_by_id = index_by_photo_id(load_jsonl(annotations), "annotations")
    output: list[dict[str, Any]] = []
    for record in manifest_rows:
        photo_id = record["photo_id"]
        if photo_id not in jobs_by_id:
            raise ValueError(f"missing job for accepted photo: {photo_id}")
        if photo_id not in annotations_by_id:
            raise ValueError(f"missing annotation for accepted photo: {photo_id}")
        job = jobs_by_id[photo_id]
        output.append(
            build_metadata_record(
                record,
                job,
                load_preprocessed_record(job),
                annotations_by_id[photo_id],
            )
        )
    return output


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("jobs", type=Path)
    parser.add_argument("annotations", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        rows = build_metadata(args.manifest, args.jobs, args.annotations)
        write_jsonl(args.output, rows)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(f"Template metadata: {args.output}")
    print(f"Items: {len(rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
