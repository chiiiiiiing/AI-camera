#!/usr/bin/env python3
"""Screen candidates with real pose detection and mobile aspect-ratio rules."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

from collect_openverse import matches_mobile_aspect_ratio


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_integrity_reasons(record: dict[str, Any], image_path: Path) -> list[str]:
    if not image_path.exists():
        return ["missing_file"]
    if sha256_file(image_path) != record.get("sha256"):
        return ["sha256_mismatch"]
    return []


def screening_decision(
    photo_type: str,
    pose_count: int,
    aspect_ratio_ok: bool,
    media_type_ok: bool,
) -> tuple[bool, list[str]]:
    reasons: list[str] = []
    if not aspect_ratio_ok:
        reasons.append("not_mobile_4_3")
    if not media_type_ok:
        reasons.append("not_verified_photo")
    if photo_type == "portrait" and pose_count < 1:
        reasons.append("portrait_without_detected_person")
    if photo_type != "portrait" and pose_count > 0:
        reasons.append("person_detected_in_non_portrait")
    return not reasons, reasons


def create_pose_landmarker(model_path: Path, num_poses: int):
    try:
        import mediapipe as mp
        from mediapipe.tasks import python
        from mediapipe.tasks.python import vision
    except ImportError as exc:
        raise RuntimeError("mediapipe is required for candidate screening") from exc
    options = vision.PoseLandmarkerOptions(
        base_options=python.BaseOptions(
            model_asset_path=str(model_path),
            delegate=python.BaseOptions.Delegate.CPU,
        ),
        running_mode=vision.RunningMode.IMAGE,
        num_poses=num_poses,
        min_pose_detection_confidence=0.6,
        min_pose_presence_confidence=0.6,
        output_segmentation_masks=False,
    )
    return mp, vision.PoseLandmarker.create_from_options(options)


def screen_manifest(
    manifest_path: Path,
    model_path: Path,
    target_ratio: float = 4.0 / 3.0,
    tolerance: float = 0.04,
) -> list[dict[str, Any]]:
    records = [
        json.loads(line)
        for line in manifest_path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    mp, landmarker = create_pose_landmarker(model_path, num_poses=6)
    results: list[dict[str, Any]] = []
    try:
        for record in records:
            if record.get("review", {}).get("quality_status") == "rejected":
                continue
            image_path = manifest_path.parent / record["file_path"]
            integrity_reasons = file_integrity_reasons(record, image_path)
            if integrity_reasons:
                results.append(
                    {
                        "photo_id": record["photo_id"],
                        "passed": False,
                        "reasons": integrity_reasons,
                    }
                )
                continue
            image = mp.Image.create_from_file(str(image_path))
            detection = landmarker.detect(image)
            pose_count = len(detection.pose_landmarks)
            aspect_ok = matches_mobile_aspect_ratio(
                record["width"], record["height"], target_ratio, tolerance
            )
            source = record.get("source", {})
            # Old records lack title/tags, so they require manual media review.
            media_type_ok = "title" in source and "tags" in source
            passed, reasons = screening_decision(
                record["photo_type"], pose_count, aspect_ok, media_type_ok
            )
            results.append(
                {
                    "photo_id": record["photo_id"],
                    "photo_type": record["photo_type"],
                    "scene_category": record["scene_category"],
                    "file_path": record["file_path"],
                    "pose_count": pose_count,
                    "aspect_ratio_ok": aspect_ok,
                    "media_metadata_available": media_type_ok,
                    "passed": passed,
                    "reasons": reasons,
                }
            )
    finally:
        landmarker.close()
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = args.output or args.manifest.parent / "screening_report.jsonl"
    try:
        results = screen_manifest(args.manifest, args.model)
        with output.open("w", encoding="utf-8") as handle:
            for result in results:
                handle.write(json.dumps(result, ensure_ascii=False, sort_keys=True) + "\n")
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    passed = sum(bool(result["passed"]) for result in results)
    portraits = [result for result in results if result.get("photo_type") == "portrait"]
    portrait_with_pose = sum(result.get("pose_count", 0) > 0 for result in portraits)
    print(
        f"Screened {len(results)} candidates: passed={passed}, "
        f"portrait_with_pose={portrait_with_pose}/{len(portraits)}"
    )
    print(f"Report: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
