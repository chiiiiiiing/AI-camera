#!/usr/bin/env python3
"""Recover explicitly reviewed regions from training references as lossless PNGs.

Input review JSONL rows (or a JSON list / {"items": [...]}) have photo_id,
source_sha256 and recovery_regions: [{"region_id": "panel_a", "bbox":
[left, top, right, bottom]}]. Coordinates are normalized xyxy AFTER EXIF
orientation. No automatic region detection, content generation or pixel repair
is performed. Derived images remain pending visual review and re-annotation.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import re
import sys
from pathlib import Path
from typing import Any

from PIL import Image, ImageOps


WORKSPACE = Path(__file__).resolve().parents[3]
OUTPUT_ROOT = WORKSPACE / "local-output"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.strip():
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{number}: expected an object")
            rows.append(row)
    return rows


def read_plan(path: Path) -> list[dict[str, Any]]:
    if path.suffix.lower() == ".jsonl":
        return read_jsonl(path)
    value = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(value, dict):
        value = value.get("items")
    if not isinstance(value, list) or any(not isinstance(row, dict) for row in value):
        raise ValueError("JSON plan must be a list of review objects or an object with items")
    return value


def required_string(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"missing nonempty {name}")
    return value.strip()


def required_sha(value: Any, name: str) -> str:
    digest = required_string(value, name).lower()
    if not re.fullmatch(r"[a-f0-9]{64}", digest):
        raise ValueError(f"invalid SHA-256 in {name}")
    return digest


def validate_output_directory(path: Path) -> Path:
    output = path.resolve()
    allowed = OUTPUT_ROOT.resolve()
    if output == allowed or not output.is_relative_to(allowed):
        raise ValueError("output directory must be a subdirectory of workspace local-output")
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError("output directory must be new or empty; existing artifacts are never overwritten")
    return output


def normalized_box(region: dict[str, Any]) -> tuple[float, float, float, float]:
    if region.get("bbox_format", "xyxy") != "xyxy":
        raise ValueError("bbox_format must be xyxy")
    if region.get("coordinate_space", "exif_normalized") != "exif_normalized":
        raise ValueError("coordinate_space must be exif_normalized")
    values = region.get("bbox")
    if (not isinstance(values, list) or len(values) != 4 or
            any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in values)):
        raise ValueError("bbox must contain four explicit numeric xyxy coordinates")
    box = tuple(float(value) for value in values)
    if not all(math.isfinite(value) and 0.0 <= value <= 1.0 for value in box):
        raise ValueError("bbox coordinates must be finite and within 0..1; no automatic clipping")
    if box[0] >= box[2] or box[1] >= box[3]:
        raise ValueError("bbox must have positive width and height")
    return box


def pixel_box(box: tuple[float, float, float, float], width: int, height: int) -> tuple[int, int, int, int]:
    # Round inward so the crop never includes pixels beyond the approved region.
    return (math.ceil(box[0] * width), math.ceil(box[1] * height),
            math.floor(box[2] * width), math.floor(box[3] * height))


def child_id(parent_id: str, parent_hash: str, region_id: str, box: tuple[float, ...]) -> str:
    parent_slug = re.sub(r"[^A-Za-z0-9_-]", "_", parent_id)[:48] or "parent"
    identity = json.dumps([parent_id, parent_hash, region_id, box], separators=(",", ":"))
    suffix = sha256_bytes(identity.encode("utf-8"))[:16]
    return f"{parent_slug}__crop_{region_id}_{suffix}"


def recover(train_manifest: Path, review_plan: Path, output_dir: Path, min_short_edge: int = 224) -> dict[str, Any]:
    if isinstance(min_short_edge, bool) or not isinstance(min_short_edge, int) or min_short_edge < 224:
        raise ValueError("min_short_edge must be at least 224; crops are never upscaled")
    output_dir = validate_output_directory(output_dir)
    parents: dict[str, dict[str, Any]] = {}
    for row in read_jsonl(train_manifest):
        identity = required_string(row.get("photo_id"), "photo_id")
        if row.get("split") != "train":
            raise ValueError(f"{identity}: only split=train sources are allowed; val/test/unknown are forbidden")
        if identity in parents:
            raise ValueError(f"duplicate parent photo_id: {identity}")
        parents[identity] = row
    if not parents:
        raise ValueError("training manifest is empty")

    jobs = []
    identities, seen_regions, seen_boxes = set(), set(), set()
    for review in read_plan(review_plan):
        regions = review.get("recovery_regions", [])
        if not isinstance(regions, list):
            raise ValueError("recovery_regions must be a list")
        if not regions:
            continue  # Ordinary review rows without a crop authorization are not recovered.
        parent_id = required_string(review.get("parent_photo_id") or review.get("photo_id"), "parent photo_id")
        if review.get("photo_id") is not None and review.get("photo_id") != parent_id:
            raise ValueError("review photo_id and parent_photo_id disagree")
        if review.get("split", "train") != "train":
            raise ValueError(f"{parent_id}: review authorizes a non-train source")
        if parent_id not in parents:
            raise ValueError(f"parent photo_id is not in training manifest: {parent_id}")
        parent = parents[parent_id]
        expected_hash = required_sha(parent.get("source_sha256"), "parent source_sha256")
        review_hash = required_sha(review.get("parent_source_sha256") or review.get("source_sha256"), "review source_sha256")
        if review.get("source_sha256") is not None and required_sha(review["source_sha256"], "review source_sha256") != review_hash:
            raise ValueError("review source_sha256 and parent_source_sha256 disagree")
        if review_hash != expected_hash:
            raise ValueError(f"review/parent SHA mismatch: {parent_id}")
        source = Path(required_string(parent.get("source_path"), "parent source_path"))
        source = source.resolve() if source.is_absolute() else (train_manifest.parent / source).resolve()
        data = source.read_bytes()
        if sha256_bytes(data) != expected_hash:
            raise ValueError(f"source image SHA mismatch: {parent_id}")
        group = parent.get("source_group_id")
        if group is not None and not isinstance(group, str):
            raise ValueError(f"{parent_id}: source_group_id must be a string or null")
        group = group.strip() if group else None
        if group and group.lower() in ("unknown", "none", "null"):
            group = None
        with Image.open(io.BytesIO(data)) as original:
            original_size = list(original.size)
            original_mode = original.mode
            oriented = ImageOps.exif_transpose(original)
            mode = "RGBA" if "A" in oriented.getbands() or "transparency" in oriented.info else "RGB"
            oriented = oriented.convert(mode)
            for region in regions:
                if not isinstance(region, dict):
                    raise ValueError("every recovery region must be an object")
                region_id = required_string(region.get("region_id"), "region_id")
                if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", region_id):
                    raise ValueError("region_id must be a simple ASCII identifier")
                box = normalized_box(region)
                if (parent_id, region_id) in seen_regions or (parent_id, box) in seen_boxes:
                    raise ValueError(f"duplicate recovery region: {parent_id}/{region_id}")
                seen_regions.add((parent_id, region_id))
                seen_boxes.add((parent_id, box))
                pixels = pixel_box(box, *oriented.size)
                width, height = pixels[2] - pixels[0], pixels[3] - pixels[1]
                if min(width, height) < min_short_edge:
                    raise ValueError(f"{parent_id}/{region_id}: crop {width}x{height} has shortest edge below {min_short_edge}")
                identity = child_id(parent_id, expected_hash, region_id, box)
                if identity in identities or identity in parents:
                    raise ValueError(f"child photo_id collision: {identity}")
                identities.add(identity)
                metadata = {
                    "schema_version": 1, "photo_id": identity, "template_id": identity,
                    "parent_photo_id": parent_id, "parent_source_path": str(source),
                    "parent_source_sha256": expected_hash, "split": "train",
                    "source_group_id": group or f"parent_sha256:{expected_hash}",
                    "source_group_origin": "inherited" if group else "parent_sha256",
                    "source_path": str(output_dir / "images" / f"{identity}.png"),
                    "width": width, "height": height, "needs_visual_review": True, "synthetic": False,
                    "recovery": {"method": "explicit_crop", "region_id": region_id,
                                 "bbox_normalized_xyxy": list(box), "bbox_pixels_xyxy": list(pixels),
                                 "coordinate_space": "exif_transposed_image", "pixel_rounding": "inward",
                                 "source_original_size": original_size, "source_oriented_size": list(oriented.size),
                                 "source_original_mode": original_mode, "output_mode": mode,
                                 "requires_new_embeddings": True, "requires_new_annotations": True},
                }
                if isinstance(region.get("reason"), str):
                    metadata["recovery"]["review_reason"] = region["reason"]
                jobs.append((oriented.crop(pixels), metadata))
    if not jobs:
        raise ValueError("review plan contains no explicit recovery regions")

    # All identity/hash/region checks complete before creating any output image.
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "images").mkdir()
    rows = []
    for crop, metadata in jobs:
        destination = Path(metadata["source_path"])
        with destination.open("xb") as stream:
            crop.save(stream, format="PNG")
        crop.close()
        metadata["source_sha256"] = sha256_bytes(destination.read_bytes())
        rows.append(metadata)
    manifest_path = output_dir / "recovered.jsonl"
    with manifest_path.open("x", encoding="utf-8") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True, allow_nan=False) + "\n")
    summary = {"schema_version": 1, "status": "needs_visual_review", "recovered_count": len(rows),
               "parent_count": len({row["parent_photo_id"] for row in rows}), "split": "train",
               "synthetic": False, "min_short_edge": min_short_edge, "manifest": str(manifest_path),
               "train_manifest": str(train_manifest.resolve()), "review_plan": str(review_plan.resolve()),
               "train_manifest_sha256": sha256_bytes(train_manifest.read_bytes()),
               "review_plan_sha256": sha256_bytes(review_plan.read_bytes()),
               "original_images_modified": False, "previous_embeddings_or_annotations_copied": False,
               "next_steps": ["visual review of each crop", "recompute teacher/student embeddings", "reannotate composition and scene"]}
    with (output_dir / "summary.json").open("x", encoding="utf-8") as stream:
        json.dump(summary, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--train-manifest", type=Path, required=True)
    parser.add_argument("--review-plan", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--min-short-edge", type=int, default=224)
    args = parser.parse_args()
    try:
        result = recover(args.train_manifest, args.review_plan, args.output_dir, args.min_short_edge)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
