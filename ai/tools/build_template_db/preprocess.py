#!/usr/bin/env python3
"""Prepare CV jobs and provide model-independent pose processing utilities.

Actual YOLO, segmentation and MobileCLIP adapters plug into the Protocols in
this module. Keeping normalization here makes offline processing and the C++
runtime use the same mathematical contract.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol, Sequence


COCO_KEYPOINT_COUNT = 17
LEFT_SHOULDER = 5
RIGHT_SHOULDER = 6
LEFT_ELBOW = 7
RIGHT_ELBOW = 8
LEFT_WRIST = 9
RIGHT_WRIST = 10
LEFT_HIP = 11
RIGHT_HIP = 12
LEFT_KNEE = 13
RIGHT_KNEE = 14
LEFT_ANKLE = 15
RIGHT_ANKLE = 16


@dataclass(frozen=True)
class Keypoint:
    x: float
    y: float
    confidence: float


@dataclass(frozen=True)
class DetectedPerson:
    bbox: tuple[float, float, float, float]
    confidence: float
    keypoints: tuple[Keypoint, ...]


class PoseBackend(Protocol):
    def detect(self, image_path: Path) -> Sequence[DetectedPerson]: ...


class SegmentationBackend(Protocol):
    def segment_primary_person(
        self, image_path: Path, person: DetectedPerson, output_path: Path
    ) -> Path: ...


class EmbeddingBackend(Protocol):
    dimension: int

    def encode(self, image_path: Path) -> Sequence[float]: ...


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bbox_area(person: DetectedPerson) -> float:
    left, top, right, bottom = person.bbox
    return max(0.0, right - left) * max(0.0, bottom - top)


def select_primary_person(people: Sequence[DetectedPerson]) -> DetectedPerson | None:
    """Select the largest person, using confidence only as a tie breaker."""
    if not people:
        return None
    return max(people, key=lambda person: (bbox_area(person), person.confidence))


def _distance(a: Keypoint, b: Keypoint) -> float:
    return math.hypot(a.x - b.x, a.y - b.y)


def normalize_keypoints(
    keypoints: Sequence[Keypoint], minimum_confidence: float = 0.2
) -> list[dict[str, float | int]]:
    """Root-center and scale a COCO pose by left-shoulder to right-hip distance."""
    if len(keypoints) != COCO_KEYPOINT_COUNT:
        raise ValueError(f"expected {COCO_KEYPOINT_COUNT} COCO keypoints")
    anchors = (keypoints[LEFT_SHOULDER], keypoints[RIGHT_HIP])
    if min(anchor.confidence for anchor in anchors) < minimum_confidence:
        anchors = (keypoints[RIGHT_SHOULDER], keypoints[LEFT_HIP])
    if min(anchor.confidence for anchor in anchors) < minimum_confidence:
        raise ValueError("torso anchors are not confident enough for normalization")

    root_x = (keypoints[LEFT_HIP].x + keypoints[RIGHT_HIP].x) / 2.0
    root_y = (keypoints[LEFT_HIP].y + keypoints[RIGHT_HIP].y) / 2.0
    scale = _distance(*anchors)
    if scale <= 1e-6:
        raise ValueError("torso scale is zero")
    return [
        {
            "idx": index,
            "x": (point.x - root_x) / scale,
            "y": (point.y - root_y) / scale,
            "confidence": point.confidence,
        }
        for index, point in enumerate(keypoints)
    ]


def joint_angle(a: Keypoint, vertex: Keypoint, c: Keypoint) -> float:
    """Return the smaller A-vertex-C angle in degrees."""
    first = (a.x - vertex.x, a.y - vertex.y)
    second = (c.x - vertex.x, c.y - vertex.y)
    first_len = math.hypot(*first)
    second_len = math.hypot(*second)
    if first_len <= 1e-6 or second_len <= 1e-6:
        raise ValueError("cannot calculate an angle from coincident keypoints")
    cosine = (first[0] * second[0] + first[1] * second[1]) / (first_len * second_len)
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def compute_joint_angles(keypoints: Sequence[Keypoint]) -> dict[str, float]:
    if len(keypoints) != COCO_KEYPOINT_COUNT:
        raise ValueError(f"expected {COCO_KEYPOINT_COUNT} COCO keypoints")
    return {
        "left_elbow": joint_angle(
            keypoints[LEFT_SHOULDER], keypoints[LEFT_ELBOW], keypoints[LEFT_WRIST]
        ),
        "right_elbow": joint_angle(
            keypoints[RIGHT_SHOULDER], keypoints[RIGHT_ELBOW], keypoints[RIGHT_WRIST]
        ),
        "left_knee": joint_angle(
            keypoints[LEFT_HIP], keypoints[LEFT_KNEE], keypoints[LEFT_ANKLE]
        ),
        "right_knee": joint_angle(
            keypoints[RIGHT_HIP], keypoints[RIGHT_KNEE], keypoints[RIGHT_ANKLE]
        ),
    }


def validate_embedding(values: Sequence[float], expected_dimension: int = 256) -> list[float]:
    if len(values) != expected_dimension:
        raise ValueError(f"expected embedding dimension {expected_dimension}, got {len(values)}")
    result = [float(value) for value in values]
    if not all(math.isfinite(value) for value in result):
        raise ValueError("embedding contains a non-finite value")
    magnitude = math.sqrt(sum(value * value for value in result))
    if magnitude <= 1e-12:
        raise ValueError("embedding magnitude is zero")
    return [value / magnitude for value in result]


def prepare_jobs(
    manifest_path: Path, output_root: Path, include_pending: bool = False
) -> list[dict[str, Any]]:
    jobs: list[dict[str, Any]] = []
    with manifest_path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            record = json.loads(line)
            quality_status = record.get("review", {}).get("quality_status")
            if quality_status == "rejected" or (quality_status != "accepted" and not include_pending):
                continue
            image_path = manifest_path.parent / record["file_path"]
            if not image_path.exists():
                raise ValueError(f"line {line_number}: image does not exist: {image_path}")
            expected_sha = record["sha256"]
            if sha256_file(image_path) != expected_sha:
                raise ValueError(f"line {line_number}: sha256 mismatch: {image_path}")
            photo_id = record["photo_id"]
            job_dir = output_root / photo_id
            jobs.append(
                {
                    "schema_version": 1,
                    "photo_id": photo_id,
                    "photo_type": record["photo_type"],
                    "scene_category": record["scene_category"],
                    "source_path": str(image_path),
                    "source_sha256": expected_sha,
                    "pose_required": record["photo_type"] == "portrait",
                    "output": {
                        "record_path": str(job_dir / "preprocessed.json"),
                        "mask_path": str(job_dir / "person_mask.png"),
                        "background_path": str(job_dir / "background.jpg"),
                        "embedding_path": str(job_dir / "embedding.f32"),
                    },
                    "status": "pending_cv_models",
                }
            )
    return jobs


def write_jobs(path: Path, jobs: Sequence[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for job in jobs:
            handle.write(json.dumps(job, ensure_ascii=False, sort_keys=True) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument(
        "--output-root",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "preprocessed",
    )
    parser.add_argument("--jobs", type=Path, help="Output JSONL path")
    parser.add_argument(
        "--include-pending",
        action="store_true",
        help="Create screening jobs for unreviewed candidates; never use for final output",
    )
    args = parser.parse_args()
    jobs_path = args.jobs or args.output_root / "jobs.jsonl"
    try:
        jobs = prepare_jobs(args.manifest, args.output_root, args.include_pending)
        write_jobs(jobs_path, jobs)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    counts: dict[str, int] = {}
    for job in jobs:
        counts[job["photo_type"]] = counts.get(job["photo_type"], 0) + 1
    print(f"Prepared {len(jobs)} jobs: {json.dumps(counts, sort_keys=True)}")
    print(f"Jobs file: {jobs_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
