#!/usr/bin/env python3
"""Generate conservative visual quality metrics for accepted candidates."""

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


def severe_flags(mean_luma: float, contrast: float, edge_mean: float) -> list[str]:
    flags: list[str] = []
    if mean_luma < 25:
        flags.append("very_dark")
    if mean_luma > 235:
        flags.append("very_bright")
    if contrast < 8:
        flags.append("very_low_contrast")
    if edge_mean < 1.2:
        flags.append("very_low_edge_detail")
    return flags


def analyze_image(path: Path) -> dict[str, Any]:
    from PIL import Image, ImageFilter, ImageOps, ImageStat

    try:
        with Image.open(path) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            width, height = image.size
            gray = image.convert("L")
            stat = ImageStat.Stat(gray)
            mean_luma = float(stat.mean[0])
            contrast = float(stat.stddev[0])
            small = gray.resize((256, max(1, round(256 * height / width))))
            edges = small.filter(ImageFilter.FIND_EDGES)
            edge_stat = ImageStat.Stat(edges)
            edge_mean = float(edge_stat.mean[0])
    except Exception as exc:
        return {
            "status": "error",
            "flags": ["decode_error"],
            "reason": str(exc),
            "image_path": str(path),
        }
    return {
        "status": "ok",
        "image_path": str(path),
        "width": width,
        "height": height,
        "mean_luma": round(mean_luma, 3),
        "contrast": round(contrast, 3),
        "edge_mean": round(edge_mean, 3),
        "flags": severe_flags(mean_luma, contrast, edge_mean),
    }


def report_for_manifest(
    manifest_path: Path,
    quality_status: str = "accepted",
) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    manifest_dir = manifest_path.parent
    for record in load_jsonl(manifest_path):
        if record.get("review", {}).get("quality_status") != quality_status:
            continue
        image_path = manifest_dir / record.get("file_path", "")
        analysis = analyze_image(image_path)
        analysis.update(
            {
                "photo_id": record.get("photo_id"),
                "photo_type": record.get("photo_type"),
                "scene_category": record.get("scene_category"),
                "file_path": record.get("file_path"),
            }
        )
        results.append(analysis)
    return results


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
    args = parser.parse_args()
    try:
        rows = report_for_manifest(args.manifest, args.quality_status)
        write_jsonl(args.output, rows)
    except (OSError, ValueError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    flagged = sum(bool(row.get("flags")) for row in rows)
    print(f"Visual quality report: {args.output}")
    print(f"Items: {len(rows)}, flagged={flagged}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
