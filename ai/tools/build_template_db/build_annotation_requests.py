#!/usr/bin/env python3
"""Build JSONL requests for visual aesthetic annotation."""

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


def default_prompt_template() -> str:
    return """You are labeling candidate photos for an on-device AI photography guidance app.

Return exactly one JSON object. Do not include Markdown, comments, or extra prose.
The JSON must match schema/aesthetic_annotation.schema.json.

Rules:
- photo_id, photo_type, and scene_category must match the metadata exactly.
- aesthetic_score is from 0 to 5.
- template_value is reject, low, medium, or high.
- Prefer unknown over guessing when unclear.
- For non-portrait photos, set subject.pose_description to null.
- shooting_guidance should be concrete camera/composition advice a beginner can follow.
- Do not identify real people or infer sensitive traits.

Metadata:
{{METADATA_JSON}}
"""


def load_prompt_template(path: Path | None) -> str:
    text = path.read_text(encoding="utf-8") if path else default_prompt_template()
    if "{{METADATA_JSON}}" not in text:
        raise ValueError("prompt template must contain {{METADATA_JSON}}")
    return text


def request_metadata(record: dict[str, Any]) -> dict[str, Any]:
    source = record.get("source", {})
    return {
        "photo_id": record.get("photo_id"),
        "photo_type": record.get("photo_type"),
        "scene_category": record.get("scene_category"),
        "query": record.get("query"),
        "width": record.get("width"),
        "height": record.get("height"),
        "source_title": source.get("title"),
        "source_tags": source.get("tags", []),
    }


def build_request(record: dict[str, Any], manifest_dir: Path, prompt_template: str) -> dict[str, Any]:
    metadata = request_metadata(record)
    prompt = prompt_template.replace(
        "{{METADATA_JSON}}",
        json.dumps(metadata, ensure_ascii=False, indent=2, sort_keys=True),
    )
    return {
        "schema_version": 1,
        "photo_id": record["photo_id"],
        "photo_type": record["photo_type"],
        "scene_category": record["scene_category"],
        "image_path": str(manifest_dir / record["file_path"]),
        "prompt": prompt,
    }


def build_requests(
    manifest_path: Path,
    prompt_template_path: Path | None = None,
    quality_status: str = "accepted",
) -> list[dict[str, Any]]:
    prompt_template = load_prompt_template(prompt_template_path)
    requests: list[dict[str, Any]] = []
    for record in load_jsonl(manifest_path):
        if record.get("review", {}).get("quality_status") != quality_status:
            continue
        requests.append(build_request(record, manifest_path.parent, prompt_template))
    return requests


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--quality-status", default="accepted")
    parser.add_argument("--prompt-template", type=Path)
    args = parser.parse_args()
    try:
        requests = build_requests(args.manifest, args.prompt_template, args.quality_status)
        write_jsonl(args.output, requests)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(f"Annotation requests: {args.output}")
    print(f"Items: {len(requests)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
