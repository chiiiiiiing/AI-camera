#!/usr/bin/env python3
"""Audit candidate files, hashes, dimensions, rights and review state."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any


CANDIDATE_LICENSES = {"cc0", "pdm", "local_authorized", "unknown", "cc-by", "cc-by-sa"}
FINAL_ALLOWED_LICENSES = {"cc0", "pdm", "local_authorized"}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON on line {line_number}: {exc}") from exc
            record["_manifest_line"] = line_number
            records.append(record)
    return records


def audit_records(
    records: list[dict[str, Any]], manifest_dir: Path, require_final: bool = False
) -> tuple[list[str], dict[str, Any]]:
    errors: list[str] = []
    photo_ids: set[str] = set()
    hashes: set[str] = set()
    type_counts: Counter[str] = Counter()
    category_counts: Counter[str] = Counter()
    review_counts: Counter[str] = Counter()
    existing_count = 0

    try:
        from PIL import Image
    except ImportError as exc:
        raise RuntimeError("Pillow is required; install requirements.txt") from exc

    for record in records:
        line = record.get("_manifest_line", "?")
        photo_id = record.get("photo_id")
        prefix = f"line {line} ({photo_id})"
        if photo_id in photo_ids:
            errors.append(f"{prefix}: duplicate photo_id")
        photo_ids.add(photo_id)
        digest = record.get("sha256")
        if digest in hashes:
            errors.append(f"{prefix}: duplicate content hash")
        hashes.add(digest)

        review = record.get("review", {})
        quality_status = review.get("quality_status")
        rights_status = review.get("rights_status")
        review_counts[str(quality_status)] += 1
        type_counts[str(record.get("photo_type"))] += 1
        category_counts[str(record.get("scene_category"))] += 1
        license_id = record.get("rights", {}).get("license")
        if license_id not in CANDIDATE_LICENSES:
            errors.append(f"{prefix}: unsupported candidate license")
        if require_final and license_id not in FINAL_ALLOWED_LICENSES:
            errors.append(f"{prefix}: final dataset cannot use license {license_id}")
        if require_final and (quality_status != "accepted" or rights_status != "accepted"):
            errors.append(f"{prefix}: final dataset requires accepted quality and rights")
        if (
            require_final
            and record.get("photo_type") == "portrait"
            and review.get("portrait_release_status") != "accepted"
        ):
            errors.append(f"{prefix}: final portrait requires accepted portrait release")

        image_path = manifest_dir / record.get("file_path", "")
        if not image_path.exists():
            if quality_status != "rejected":
                errors.append(f"{prefix}: image file is missing")
            continue
        existing_count += 1
        if sha256_file(image_path) != digest and quality_status != "rejected":
            errors.append(f"{prefix}: sha256 mismatch")
        try:
            with Image.open(image_path) as image:
                image.verify()
            with Image.open(image_path) as image:
                width, height = image.size
        except Exception as exc:
            errors.append(f"{prefix}: invalid image: {exc}")
            continue
        if width != record.get("width") or height != record.get("height"):
            errors.append(f"{prefix}: decoded dimensions {width}x{height} do not match manifest")

    summary = {
        "records": len(records),
        "existing_files": existing_count,
        "photo_types": dict(sorted(type_counts.items())),
        "scene_categories": dict(sorted(category_counts.items())),
        "quality_status": dict(sorted(review_counts.items())),
    }
    return errors, summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--require-final", action="store_true")
    args = parser.parse_args()
    try:
        records = load_manifest(args.manifest)
        errors, summary = audit_records(records, args.manifest.parent, args.require_final)
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
