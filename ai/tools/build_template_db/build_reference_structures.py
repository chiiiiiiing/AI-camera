#!/usr/bin/env python3
"""Build hash-bound, measured reference structures without changing source assets.

All automatic labels are observations, not composition or aesthetic ground truth.
Only existing hash-reviewed photographs can become actionable reference targets.
Unreviewed separator crops are retained as quarantined candidates, never deployed.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import importlib.metadata
import json
import math
from pathlib import Path
import sys
import urllib.request

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageOps

from prepare_retrieval_data import archived_image_path, source_group_id

FACE_URL = "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite"
MP_TO_COCO = [0, 2, 5, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28]
COCO_NAMES = ["nose", "left_eye", "right_eye", "left_ear", "right_ear", "left_shoulder", "right_shoulder", "left_elbow", "right_elbow", "left_wrist", "right_wrist", "left_hip", "right_hip", "left_knee", "right_knee", "left_ankle", "right_ankle"]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_rows(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8", newline="\n")


def write_rows(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n" for row in rows), encoding="utf-8", newline="\n")


def upright_image(path: Path) -> Image.Image:
    with Image.open(path) as image:
        return ImageOps.exif_transpose(image).convert("RGB")


def perceptual_hashes(image: Image.Image) -> tuple[str, str]:
    gray = np.asarray(image.convert("L").resize((32, 32)), dtype=np.float32)
    dct = cv2.dct(gray)[:8, :8].flatten()
    median = np.median(dct[1:])
    bits = dct > median
    bits[0] = False
    dh = np.asarray(image.convert("L").resize((9, 8)))
    return (f"{sum(int(v) << i for i, v in enumerate(bits)):016x}",
            f"{sum(int(v) << i for i, v in enumerate((dh[:, 1:] > dh[:, :-1]).flatten())):016x}")


def quality_metrics(image: Image.Image, box: list[float] | None = None) -> dict:
    if box is not None:
        x, y, w, h = box
        image = image.crop((max(0, int(x * image.width)), max(0, int(y * image.height)),
                            min(image.width, max(1, math.ceil((x + w) * image.width))),
                            min(image.height, max(1, math.ceil((y + h) * image.height)))))
    small = image.copy()
    small.thumbnail((256, 256), Image.Resampling.LANCZOS)
    rgb = np.asarray(small, dtype=np.float32) / 255.0
    gray = rgb @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    gray255 = np.round(gray * 255).astype(np.uint8)
    mean, std = float(gray.mean()), float(gray.std())
    return {"mean_luminance": round(mean, 6), "luminance_stddev": round(std, 6),
            "overexposed_fraction": round(float(np.mean(gray >= 0.98)), 6),
            "underexposed_fraction": round(float(np.mean(gray <= 0.02)), 6),
            "laplacian_variance": round(float(cv2.Laplacian(gray255, cv2.CV_64F).var()), 6),
            "measurement_max_edge": 256,
            "severe_quality_issue": bool((mean < 0.035 or mean > 0.965) and std < 0.025)}


def separator_regions(image: Image.Image) -> list[list[int]]:
    """Suggest only regions separated by broad, almost solid full-span gutters.

    This is a quarantine heuristic, not an OCR detector or photograph validator.
    Natural horizons/sky can also trigger it; no result is automatically promoted.
    """
    small = image.copy()
    small.thumbnail((768, 768))
    rgb = np.asarray(small).astype(np.float32)
    gray = rgb.mean(axis=2)
    boundaries = []
    for axis, size in ((1, image.height), (0, image.width)):
        white = (gray > 246).mean(axis=axis) > 0.985
        black = (gray < 8).mean(axis=axis) > 0.985
        uniform = gray.std(axis=axis) < 3.0
        mask = (white | black) & uniform
        starts, spans = None, []
        for i, flag in enumerate(np.r_[mask, False]):
            if flag and starts is None:
                starts = i
            elif not flag and starts is not None:
                if i - starts >= 3:
                    spans.append((starts / len(mask), i / len(mask)))
                starts = None
        cuts = [(round(a * size), round(b * size)) for a, b in spans if a > 0.04 and b < 0.96]
        edges = [(0, 0)] + cuts + [(size, size)]
        segments = [(edges[i][1], edges[i + 1][0]) for i in range(len(edges) - 1)]
        if cuts and 2 <= len(segments) <= 4 and all(b - a >= 224 for a, b in segments):
            boundaries.append((axis, segments))
    if not boundaries:
        return []
    # One axis per parent prevents speculative overlapping grid segmentation.
    axis, segments = min(boundaries, key=lambda item: len(item[1]))
    return [[0, a, image.width, b] if axis == 1 else [a, 0, b, image.height] for a, b in segments]


def group_sources(rows: list[dict]) -> tuple[list[dict], list[dict]]:
    parent = list(range(len(rows)))
    def root(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    def union(a, b):
        parent[root(b)] = root(a)
    by_id = {row["photo_id"]: i for i, row in enumerate(rows)}
    if len(by_id) != len(rows):
        raise ValueError("duplicate source photo_id")
    keys = {}
    links = []
    for i, row in enumerate(rows):
        for kind, key in (("sha256", row["image_sha256"]), ("source", row.get("original_source_group_id"))):
            if key:
                if (kind, key) in keys:
                    j = keys[kind, key]
                    union(i, j)
                    links.append({"left": rows[j]["photo_id"], "right": row["photo_id"], "reason": kind})
                keys[kind, key] = i
        if row.get("parent_photo_id") and row["parent_photo_id"] not in by_id:
            raise ValueError("derived parent missing: " + row["photo_id"])
        if row.get("parent_photo_id") in by_id:
            j = by_id[row["parent_photo_id"]]
            if row.get("parent_source_sha256") != rows[j]["image_sha256"]:
                raise ValueError("derived parent hash mismatch: " + row["photo_id"])
            union(i, j)
            links.append({"left": rows[j]["photo_id"], "right": row["photo_id"], "reason": "derived_parent"})
        for j in range(i):
            other = rows[j]
            if (int(row["phash"], 16) ^ int(other["phash"], 16)).bit_count() <= 6 and (int(row["dhash"], 16) ^ int(other["dhash"], 16)).bit_count() <= 8:
                union(i, j)
                links.append({"left": other["photo_id"], "right": row["photo_id"], "reason": "near_duplicate_phash6_dhash8"})
    members = defaultdict(list)
    for i, row in enumerate(rows):
        members[root(i)].append(row)
    for group in members.values():
        token = hashlib.sha256("\n".join(sorted(row["image_sha256"] for row in group)).encode()).hexdigest()
        splits = sorted({row["original_split"] for row in group})
        for row in group:
            row["source_group_id"] = "source_cluster:" + token
            row["group_historical_splits"] = splits
            row["historical_holdout_group"] = bool({"val", "test"} & set(splits))
            row["group_size"] = len(group)
    return rows, links


def structure_reasons(row: dict, faces: list[dict], quality: dict) -> list[str]:
    reasons = []
    if not row.get("reference_eligible"):
        reasons.append("not_existing_hash_reviewed_reference")
    if row.get("synthetic"):
        reasons.append("synthetic_reference")
    if row.get("photo_type") != "portrait":
        reasons.append("not_portrait_scope")
    if len(faces) != 1:
        reasons.append("no_face" if not faces else "multiple_faces")
    else:
        face = faces[0]
        if face["confidence"] < 0.75:
            reasons.append("face_confidence_below_0.75")
        if min(face["bbox"][2:]) < 0.04:
            reasons.append("face_too_small")
        if face["clipped"]:
            reasons.append("face_touches_boundary")
    if quality["severe_quality_issue"]:
        reasons.append("severe_quality_issue")
    return reasons


class Detectors:
    def __init__(self, face_model: Path, pose_model: Path):
        # Newer published wheels can initialize undocumented native telemetry.
        # Preserve prior experimental outputs, but never silently switch runtimes.
        if importlib.metadata.version("mediapipe") != "0.10.21":
            raise RuntimeError("reference annotation requires pinned mediapipe==0.10.21; install requirements-reference-structures.txt in an isolated environment")
        import mediapipe as mp
        from mediapipe.tasks import python
        from mediapipe.tasks.python import vision
        self.mp = mp
        self.face = vision.FaceDetector.create_from_options(vision.FaceDetectorOptions(
            base_options=python.BaseOptions(model_asset_buffer=face_model.read_bytes()),
            running_mode=vision.RunningMode.IMAGE, min_detection_confidence=0.5))
        self.pose = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
            base_options=python.BaseOptions(model_asset_buffer=pose_model.read_bytes()),
            running_mode=vision.RunningMode.IMAGE, num_poses=4,
            min_pose_detection_confidence=0.6, min_pose_presence_confidence=0.6,
            output_segmentation_masks=False))
        self.provenance = {"method": "local_mediapipe_image_detection", "mediapipe_version": mp.__version__,
            "face_model": "blaze_face_short_range_float16_v1", "face_model_sha256": sha256(face_model),
            "face_model_url": FACE_URL, "pose_model": "pose_landmarker_lite", "pose_model_sha256": sha256(pose_model),
            "pose_model_url": "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task",
            "human_reviewed": False, "aesthetic_ground_truth": False, "mask_available": False,
            "yaw_available": False, "coordinate_space": "exif_upright_full_frame"}

    def detect(self, image: Image.Image) -> tuple[list[dict], dict]:
        sample = image.copy()
        sample.thumbnail((1280, 1280))
        mp_image = self.mp.Image(image_format=self.mp.ImageFormat.SRGB, data=np.asarray(sample).copy())
        faces = []
        for detection in self.face.detect(mp_image).detections:
            b = detection.bounding_box
            raw = [b.origin_x / sample.width, b.origin_y / sample.height,
                   b.width / sample.width, b.height / sample.height]
            x, y = max(0., raw[0]), max(0., raw[1])
            right, bottom = min(1., raw[0] + raw[2]), min(1., raw[1] + raw[3])
            if right <= x or bottom <= y:
                continue
            box = [round(v, 6) for v in (x, y, right - x, bottom - y)]
            confidence = max(float(category.score) for category in detection.categories)
            if not math.isfinite(confidence):
                continue
            faces.append({"bbox": box, "confidence": round(confidence, 6), "yaw_degrees": None,
                "clipped": bool(raw[0] <= 0.015 or raw[1] <= 0.015 or raw[0] + raw[2] >= 0.985 or raw[1] + raw[3] >= 0.985),
                "keypoints": [{"x": round(float(point.x), 6), "y": round(float(point.y), 6)} for point in detection.keypoints],
                "local_quality": quality_metrics(image, box),
                "headroom_face_fraction": round(y, 6)})
        detected_pose = self.pose.detect(mp_image)
        poses = []
        for landmarks in detected_pose.pose_landmarks:
            points = []
            for index, mp_index in enumerate(MP_TO_COCO):
                point = landmarks[mp_index]
                values = [float(point.x), float(point.y), float(point.visibility), float(point.presence)]
                if not all(math.isfinite(v) for v in values):
                    continue
                visible = min(values[2:]) >= 0.6 and 0 <= values[0] <= 1 and 0 <= values[1] <= 1
                points.append({"index": index, "name": COCO_NAMES[index], "x": round(values[0], 6),
                    "y": round(values[1], 6), "visibility": round(values[2], 6), "presence": round(values[3], 6), "valid": visible})
            valid = [point for point in points if point["valid"]]
            box = None
            if len(valid) >= 3:
                xs, ys = [p["x"] for p in valid], [p["y"] for p in valid]
                box = [min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys)]
            poses.append({"keypoints_coco17": points, "visible_keypoints": len(valid),
                "visible_keypoint_extent": box, "full_body_box_available": False})
        faces.sort(key=lambda face: -face["confidence"])
        return faces, {"detected_count": len(poses), "poses": poses, "valid_for_mobile_guidance": False}

    def close(self):
        self.face.close()
        self.pose.close()


def load_sources(project: Path) -> tuple[list[dict], dict]:
    prior = project / "local-output/model-optimization-2026-10-14"
    archive = project / "local-data/AI-camera-local-archive/2026-10-10/private-data/tools"
    baseline = project / "local-output/model-recognition-2026-10-15/baseline-assets/template_index/templates.metadata.jsonl"
    deployed = project / "aicamera-app/app/src/main/assets/ai/v9/template_index/templates.metadata.jsonl"
    metadata = {row["photo_id"]: row for row in read_rows(deployed)}
    originals = {row["photo_id"]: row for row in read_rows(baseline)}
    raw = {row["photo_id"]: row for row in read_rows(archive / "raw_photos/new_batch/manifest.jsonl")}
    split_rows = {}
    for split in ("train", "val", "test"):
        for row in read_rows(prior / f"dataset/{split}.jsonl"):
            row["original_split"] = split
            split_rows[row["photo_id"]] = row
    for path in (prior / "recovered-references/crops/recovered.jsonl", prior / "recovered-references/ai-restored/recovered.jsonl"):
        for row in read_rows(path):
            parent = split_rows.get(row.get("parent_photo_id"), {})
            row["original_split"] = parent.get("original_split", "excluded")
            row["photo_type"] = metadata.get(row["photo_id"], parent).get("photo_type", "unknown")
            row["scene_category"] = metadata.get(row["photo_id"], parent).get("scene_category", "unknown")
            originals[row["photo_id"]] = row
    rows = []
    for identity, original in sorted(originals.items()):
        item = split_rows.get(identity, original)
        path = Path(item["source_path"])
        if not path.is_absolute():
            path = archived_image_path(item["source_path"], archive)
        path = path.resolve()
        if not path.is_relative_to(project):
            raise ValueError("all image paths must remain inside project")
        digest = sha256(path)
        if digest != item["source_sha256"]:
            raise ValueError("source SHA mismatch: " + identity)
        image = upright_image(path)
        phash, dhash = perceptual_hashes(image)
        review = metadata.get(identity, {}).get("reference_review", {})
        eligible = review.get("status") == "candidate_clean" and review.get("source_sha256") == digest and review.get("provenance", {}).get("method") == "ai_visual_review"
        row = {"schema_version": 1, "template_id": identity, "photo_id": identity,
            "image_path": str(path), "source_path": str(path), "image_sha256": digest, "source_sha256": digest,
            "image_width": image.width, "image_height": image.height, "width": image.width, "height": image.height,
            "photo_type": item["photo_type"], "scene_category": item["scene_category"],
            "original_split": item.get("original_split", "excluded"),
            "original_source_group_id": item.get("source_group_id") or source_group_id(raw.get(identity, {})),
            "reference_eligible": eligible, "source_only": identity not in metadata,
            "synthetic": bool(item.get("synthetic", False)), "phash": phash, "dhash": dhash,
            "coordinate_space": "exif_upright_full_frame"}
        for key in ("parent_photo_id", "parent_source_sha256", "recovery"):
            if key in item:
                row[key] = item[key]
        rows.append(row)
    if set(metadata) - {row["photo_id"] for row in rows}:
        raise ValueError("deployed references missing from source manifests")
    return rows, metadata


def comparison_sheet(row: dict, by_id: dict, output: Path) -> str:
    image = upright_image(Path(row["image_path"]))
    parent = by_id.get(row.get("parent_photo_id"), row)
    original = upright_image(Path(parent["image_path"]))
    annotated = image.copy()
    draw = ImageDraw.Draw(annotated)
    width = max(2, image.width // 200)
    for face in row["faces"]:
        x, y, w, h = face["bbox"]
        draw.rectangle((x * image.width, y * image.height, (x + w) * image.width, (y + h) * image.height), outline="lime" if row["structure_eligible"] else "orange", width=width)
    for pose in row["pose"]["poses"]:
        for point in pose["keypoints_coco17"]:
            if point["valid"]:
                x, y = point["x"] * image.width, point["y"] * image.height
                draw.ellipse((x-width, y-width, x+width, y+width), fill="cyan")
    sheet = Image.new("RGB", (960, 380), "#202020")
    pen = ImageDraw.Draw(sheet)
    for i, (label, panel) in enumerate(zip(("SOURCE / PARENT", "UPRIGHT / RECOVERED", "MEASURED STRUCTURE"), (original, image, annotated))):
        panel.thumbnail((310, 320))
        sheet.paste(panel, (i * 320 + (320 - panel.width) // 2, 30 + (320 - panel.height) // 2))
        pen.text((i * 320 + 10, 8), label, fill="white")
    pen.text((10, 355), f"{row['photo_id']} | faces={row['face_count']} | eligible={row['structure_eligible']}", fill="white")
    target = output / "comparisons" / (row["photo_id"] + ".jpg")
    target.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(target, quality=85)
    return target.relative_to(output).as_posix()


def run(args) -> dict:
    project, output = args.project_root.resolve(), args.output_dir.resolve()
    if not output.is_relative_to(project / "local-output"):
        raise ValueError("output-dir must remain inside project local-output")
    output.mkdir(parents=True, exist_ok=True)
    face_model = args.face_model or output / "models/blaze_face_short_range.tflite"
    if not face_model.exists():
        face_model.parent.mkdir(parents=True, exist_ok=True)
        for attempt in range(2):
            try:
                with urllib.request.urlopen(FACE_URL, timeout=60) as response:
                    content = response.read()
                if len(content) < 100_000:
                    raise ValueError("face model download unexpectedly small")
                face_model.write_bytes(content)
                break
            except Exception:
                if attempt:
                    raise
    pose_model = args.pose_model or project / "local-data/AI-camera-local-archive/2026-10-10/private-data/tools/build_template_db/models/pose_landmarker_lite.task"
    rows, metadata = load_sources(project)
    rows, links = group_sources(rows)
    config = {"pipeline_revision": 1, "source_identity_sha256": hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest(),
        "face_model_sha256": sha256(face_model), "pose_model_sha256": sha256(pose_model)}
    config_path = output / "run-config.json"
    if config_path.exists():
        if not args.resume:
            raise ValueError("existing run: use --resume, or select a new output directory")
        if json.loads(config_path.read_text(encoding="utf-8")) != config:
            raise ValueError("run inputs changed; preserve this run and select a new output directory")
        if (output / "report.json").exists():
            report = json.loads((output / "report.json").read_text(encoding="utf-8"))
            for name, key in (("reference_structures.json", "reference_structures_sha256"), ("source_manifest.jsonl", "source_manifest_sha256")):
                if sha256(output / name) != report[key]:
                    raise ValueError("completed run output hash mismatch: " + name)
            return report
    else:
        write_json(config_path, config)
    write_rows(output / "source_manifest.grouped.jsonl", rows)
    write_rows(output / "source_group_links.jsonl", links)
    detectors = Detectors(face_model, pose_model)
    annotated, candidates = [], []
    cache_key = hashlib.sha256(json.dumps(detectors.provenance, sort_keys=True).encode()).hexdigest()
    try:
        for index, row in enumerate(rows):
            image = upright_image(Path(row["image_path"]))
            cache = output / "observations" / (row["image_sha256"] + ".json")
            observation = json.loads(cache.read_text(encoding="utf-8")) if cache.exists() else None
            if observation is None or observation.get("detector_key") != cache_key:
                faces, pose = detectors.detect(image)
                observation = {"detector_key": cache_key, "faces": faces, "pose": pose, "quality": quality_metrics(image)}
                write_json(cache, observation)
            row.update({key: observation[key] for key in ("faces", "pose", "quality")})
            row["face_count"] = len(row["faces"])
            row["structure_reasons"] = structure_reasons(row, row["faces"], row["quality"])
            row["structure_eligible"] = not row["structure_reasons"]
            row["provenance"] = detectors.provenance
            row["automatic_content_flags"] = []
            regions = separator_regions(image)
            if regions:
                row["automatic_content_flags"].append("possible_full_span_gutter_requires_content_review")
            # Existing reviewed references are not overturned by this low-specificity heuristic.
            if row["source_only"] and row["original_split"] == "train" and regions and len(candidates) < 32:
                for number, box in enumerate(regions):
                    crop = image.crop(box)
                    if min(crop.size) < 224 or quality_metrics(crop)["severe_quality_issue"]:
                        continue
                    identity = row["photo_id"] + f"__auto_separator_{number}"
                    path = output / "quarantined-crops" / (identity + ".png")
                    path.parent.mkdir(parents=True, exist_ok=True)
                    crop.save(path)
                    candidates.append({"photo_id": identity, "image_path": str(path), "image_sha256": sha256(path),
                        "parent_photo_id": row["photo_id"], "parent_source_sha256": row["image_sha256"],
                        "source_group_id": row["source_group_id"], "bbox_pixels_xyxy": box,
                        "coordinate_space": "exif_upright_full_frame", "synthetic": False,
                        "status": "quarantined", "reference_eligible": False,
                        "reason": "automatic_separator_does_not_prove_photographic_content"})
                    if len(candidates) >= 32:
                        break
            annotated.append(row)
            if (index + 1) % 25 == 0:
                print(json.dumps({"annotated": index + 1, "total": len(rows)}), flush=True)
    finally:
        detectors.close()
    write_rows(output / "source_manifest.jsonl", annotated)
    write_rows(output / "quarantined_crops.jsonl", candidates)
    by_id = {row["photo_id"]: row for row in annotated}
    public_fields = ("template_id", "image_sha256", "image_width", "image_height", "synthetic", "coordinate_space", "source_group_id", "photo_type", "face_count", "faces", "quality", "structure_eligible", "structure_reasons", "provenance")
    public = [{key: row[key] for key in public_fields} for row in annotated if not row["source_only"]]
    write_json(output / "reference_structures.json", {"schema_version": 1, "coordinate_space": "exif_upright_full_frame", "provenance": detectors.provenance, "records": public})
    html = ["<!doctype html><meta charset='utf-8'><title>Measured reference structures</title><style>body{background:#191919;color:white;font:16px sans-serif}img{width:min(100%,960px)}figure{margin:20px 0}</style><h1>Source, processed image, measured structure</h1><p>Automatic detections are not human labels or proof of aesthetic quality. No placeholder masks. Cyan points require visibility and presence >= 0.6.</p>"]
    for row in annotated:
        if not row["source_only"]:
            relative = comparison_sheet(row, by_id, output)
            html.append(f"<figure><img loading='lazy' src='{relative}'><figcaption>{row['photo_id']} — {', '.join(row['structure_reasons']) or 'actionable single-face structure'}</figcaption></figure>")
    (output / "comparisons.html").write_text("\n".join(html), encoding="utf-8")
    counts = Counter(reason for row in public for reason in row["structure_reasons"])
    report = {"schema_version": 1, "source_count": len(rows), "reference_count": len(public),
        "source_groups": len({row["source_group_id"] for row in rows}), "source_link_reasons": dict(Counter(link["reason"] for link in links)),
        "structure_eligible_count": sum(row["structure_eligible"] for row in public),
        "reference_face_count_distribution": dict(Counter(str(row["face_count"]) for row in public)),
        "reference_structure_reasons": dict(counts), "synthetic_reference_count": sum(row["synthetic"] for row in public),
        "quarantined_crop_count": len(candidates), "comparison_count": len(public),
        "historical_holdout_group_rows": sum(row["historical_holdout_group"] for row in rows),
        "reference_structures_sha256": sha256(output / "reference_structures.json"),
        "source_manifest_sha256": sha256(output / "source_manifest.jsonl"),
        "known_limitations": ["No OCR: inherited visual reviews plus gutter candidates only; new candidates remain quarantined.",
            "Short-range face detector can miss small or occluded faces; no face is not evidence of absence.",
            "Visible keypoint extent is not full-body segmentation or a complete subject box.",
            "Yaw unavailable; face headroom is not top-of-hair headroom.",
            "No new human aesthetic evidence; unknown source families can remain undiscovered.",
            "Existing retrieval encoder has seen some source groups; this is not a new independent end-to-end test."],
        "provenance": detectors.provenance}
    write_json(output / "report.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--face-model", type=Path)
    parser.add_argument("--pose-model", type=Path)
    parser.add_argument("--resume", action="store_true", help="reuse matching partial observations or return verified completed artifacts")
    args = parser.parse_args()
    print(json.dumps(run(args), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
