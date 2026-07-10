#!/usr/bin/env python3
"""Run CV preprocessing on accepted jobs using MediaPipe Pose Landmarker."""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any

MP_TO_COCO = [0, 2, 5, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28]
COCO_NAMES = [
    "nose", "left_eye", "right_eye", "left_ear", "right_ear",
    "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
    "left_wrist", "right_wrist", "left_hip", "right_hip",
    "left_knee", "right_knee", "left_ankle", "right_ankle",
]
LEFT_SHOULDER, RIGHT_SHOULDER = 5, 6
LEFT_HIP, RIGHT_HIP = 11, 12
LEFT_ELBOW, RIGHT_ELBOW = 7, 8
LEFT_WRIST, RIGHT_WRIST = 9, 10
LEFT_KNEE, RIGHT_KNEE = 13, 14
LEFT_ANKLE, RIGHT_ANKLE = 15, 16


def load_pose_landmarker(model_path: Path):
    import mediapipe as mp
    from mediapipe.tasks import python
    from mediapipe.tasks.python import vision
    options = vision.PoseLandmarkerOptions(
        base_options=python.BaseOptions(
            model_asset_path=str(model_path),
            delegate=python.BaseOptions.Delegate.CPU,
        ),
        running_mode=vision.RunningMode.IMAGE,
        num_poses=1,
        min_pose_detection_confidence=0.5,
        min_pose_presence_confidence=0.5,
        output_segmentation_masks=False,
    )
    landmarker = vision.PoseLandmarker.create_from_options(options)
    return mp, landmarker


def mediapipe_to_coco(mp_landmarks) -> list[dict[str, float]]:
    coco = []
    for i, mp_idx in enumerate(MP_TO_COCO):
        lm = mp_landmarks[mp_idx]
        coco.append({
            "idx": i,
            "x": lm.x,
            "y": lm.y,
            "z": lm.z,
            "visibility": lm.visibility,
            "name": COCO_NAMES[i],
        })
    return coco


def bbox_from_keypoints(kpts: list[dict]) -> tuple[float, float, float, float]:
    xs = [k["x"] for k in kpts]
    ys = [k["y"] for k in kpts]
    return (min(xs), min(ys), max(xs), max(ys))


def normalize_keypoints(kpts: list[dict], min_conf: float = 0.2):
    raw_norm = [{"idx": k["idx"], "x": k["x"], "y": k["y"],
                 "confidence": k.get("visibility", 1.0)} for k in kpts]
    anchors = (raw_norm[LEFT_SHOULDER], raw_norm[RIGHT_HIP])
    if min(a["confidence"] for a in anchors) < min_conf:
        anchors = (raw_norm[RIGHT_SHOULDER], raw_norm[LEFT_HIP])
    if min(a["confidence"] for a in anchors) < min_conf:
        raise ValueError("torso anchors not confident enough")
    root_x = (raw_norm[LEFT_HIP]["x"] + raw_norm[RIGHT_HIP]["x"]) / 2.0
    root_y = (raw_norm[LEFT_HIP]["y"] + raw_norm[RIGHT_HIP]["y"]) / 2.0
    scale = math.hypot(anchors[0]["x"] - anchors[1]["x"],
                       anchors[0]["y"] - anchors[1]["y"])
    if scale <= 1e-6:
        raise ValueError("torso scale is zero")
    normalized = []
    for k in raw_norm:
        normalized.append({
            "idx": k["idx"],
            "x": (k["x"] - root_x) / scale,
            "y": (k["y"] - root_y) / scale,
            "confidence": k["confidence"],
        })
    return normalized, raw_norm


def compute_joint_angles(kpts: list[dict]) -> dict[str, float]:
    def _angle(a, vertex, c):
        v1 = (a["x"] - vertex["x"], a["y"] - vertex["y"])
        v2 = (c["x"] - vertex["x"], c["y"] - vertex["y"])
        l1 = math.hypot(*v1)
        l2 = math.hypot(*v2)
        if l1 <= 1e-6 or l2 <= 1e-6:
            return None
        cos = (v1[0]*v2[0] + v1[1]*v2[1]) / (l1 * l2)
        return math.degrees(math.acos(max(-1.0, min(1.0, cos))))
    result = {
        "left_elbow": _angle(kpts[LEFT_SHOULDER], kpts[LEFT_ELBOW], kpts[LEFT_WRIST]),
        "right_elbow": _angle(kpts[RIGHT_SHOULDER], kpts[RIGHT_ELBOW], kpts[RIGHT_WRIST]),
        "left_knee": _angle(kpts[LEFT_HIP], kpts[LEFT_KNEE], kpts[LEFT_ANKLE]),
        "right_knee": _angle(kpts[RIGHT_HIP], kpts[RIGHT_KNEE], kpts[RIGHT_ANKLE]),
    }
    return {k: v for k, v in result.items() if v is not None}


def process_portrait_job(job: dict, landmarker) -> dict[str, Any]:
    from PIL import Image as PILImage
    import mediapipe as mp
    image_path = Path(job["source_path"])
    if not image_path.exists():
        return {"photo_id": job["photo_id"], "status": "error", "reason": "image_not_found"}
    mp_image = mp.Image.create_from_file(str(image_path))
    detection = landmarker.detect(mp_image)
    if not detection.pose_landmarks:
        return {"photo_id": job["photo_id"], "status": "no_person"}
    mp_landmarks = detection.pose_landmarks[0]
    coco_keypoints = mediapipe_to_coco(mp_landmarks)
    try:
        normalized_kpts, raw_kpts = normalize_keypoints(coco_keypoints)
    except ValueError as e:
        return {"photo_id": job["photo_id"], "status": "error", "reason": str(e)}
    angles = compute_joint_angles(normalized_kpts)
    bbox = bbox_from_keypoints(coco_keypoints)
    record_path = Path(job["output"]["record_path"])
    record_path.parent.mkdir(parents=True, exist_ok=True)
    output = {
        "schema_version": 1,
        "photo_id": job["photo_id"],
        "photo_type": "portrait",
        "scene_category": job["scene_category"],
        "source_path": job["source_path"],
        "source_sha256": job["source_sha256"],
        "pose_detected": True,
        "detection_confidence": min(k["confidence"] for k in normalized_kpts),
        "bbox": {"left": bbox[0], "top": bbox[1], "right": bbox[2], "bottom": bbox[3]},
        "keypoints_coco17": raw_kpts,
        "keypoints_normalized": normalized_kpts,
        "joint_angles_degrees": angles,
        "torso_anchor": {
            "method": "left_shoulder_to_right_hip",
            "root": {
                "x": (coco_keypoints[LEFT_HIP]["x"] + coco_keypoints[RIGHT_HIP]["x"]) / 2,
                "y": (coco_keypoints[LEFT_HIP]["y"] + coco_keypoints[RIGHT_HIP]["y"]) / 2,
            },
        },
    }
    with open(str(record_path), "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2, ensure_ascii=False)
    mask_path = Path(job["output"]["mask_path"])
    mask_path.parent.mkdir(parents=True, exist_ok=True)
    if not mask_path.exists():
        img = PILImage.new("RGBA", (1, 1), (0, 0, 0, 0))
        img.save(str(mask_path))
    bg_path = Path(job["output"]["background_path"])
    bg_path.parent.mkdir(parents=True, exist_ok=True)
    if not bg_path.exists():
        orig = PILImage.open(str(image_path))
        orig.save(str(bg_path))
    return {"photo_id": job["photo_id"], "status": "pose_detected",
            "keypoints": len(coco_keypoints)}


def process_non_portrait_job(job: dict) -> dict[str, Any]:
    output = {
        "schema_version": 1,
        "photo_id": job["photo_id"],
        "photo_type": job["photo_type"],
        "scene_category": job["scene_category"],
        "source_path": job["source_path"],
        "source_sha256": job["source_sha256"],
        "pose_detected": False,
        "pose_required": False,
    }
    record_path = Path(job["output"]["record_path"])
    record_path.parent.mkdir(parents=True, exist_ok=True)
    with open(str(record_path), "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2, ensure_ascii=False)
    return {"photo_id": job["photo_id"], "status": "non_portrait"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jobs", type=Path)
    parser.add_argument("--model", type=Path)
    parser.add_argument("--report", type=Path, help="Write per-job preprocessing status JSONL")
    parser.add_argument(
        "--allow-partial",
        action="store_true",
        help="Return success even when some jobs fail; useful for exploratory pilots",
    )
    args = parser.parse_args()
    jobs = [json.loads(line) for line in args.jobs.read_text().splitlines() if line.strip()]
    portrait_count = sum(1 for job in jobs if job.get("pose_required"))
    landmarker = None
    if portrait_count:
        if args.model is None:
            print("error: --model is required when jobs include pose_required=true", file=sys.stderr)
            return 2
        print(f"Loading {len(jobs)} jobs, initializing MediaPipe...", flush=True)
        _, landmarker = load_pose_landmarker(args.model)
    else:
        print(f"Loading {len(jobs)} non-portrait jobs; MediaPipe not required.", flush=True)
    results = []
    for job in jobs:
        if job.get("pose_required"):
            assert landmarker is not None
            result = process_portrait_job(job, landmarker)
        else:
            result = process_non_portrait_job(job)
        results.append(result)
    if landmarker is not None:
        landmarker.close()
    status_counts = {}
    for r in results:
        s = r["status"]
        status_counts[s] = status_counts.get(s, 0) + 1
    for s, c in sorted(status_counts.items()):
        print(f"  {s}: {c}")
    failures = [r for r in results if r["status"] not in {"non_portrait", "pose_detected"}]
    for failure in failures:
        reason = failure.get("reason", "")
        print(f"  failed {failure['photo_id']}: {failure['status']} {reason}".rstrip())
    print(f"Total: {len(results)} (portrait={portrait_count})")
    report_path = args.report or args.jobs.with_suffix(".cv_report.jsonl")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    with report_path.open("w", encoding="utf-8") as handle:
        for result in results:
            handle.write(json.dumps(result, ensure_ascii=False, sort_keys=True) + "\n")
    print(f"Report: {report_path}")
    return 0 if args.allow_partial or not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
