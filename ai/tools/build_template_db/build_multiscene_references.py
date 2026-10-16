#!/usr/bin/env python3
"""Measure non-portrait reference structure; observations are not aesthetic labels.

The input is the existing hash-reviewed reference manifest. Source photos remain
local. The deployable sidecar contains no local paths or images. No source image,
retrieval split, encoder, or previously frozen acceptance set is changed.
"""
from __future__ import annotations

import argparse
from collections import Counter
import html
import hashlib
import json
import math
from pathlib import Path
import time

import numpy as np
from PIL import Image, ImageDraw, ImageOps

from build_reference_structures import read_rows, sha256, upright_image, write_json
from multiscene_detector import PREPROCESSING, NATIVE_PREPROCESSING, _bilinear

LAYOUT_ALGORITHM = "rgb224_rec601_grid3_forward_edges_v1"
OBJECT_POLICY = "single_complete_coco_still_life_v1"
OBJECT_REVIEW_POLICY = "hash_bound_ai_visual_object_review_v1"
ALLOWED_CLASSES = {39, 40, 41, *range(45, 56), 73, 74, 77}
ALLOWED_SCENES = {"food_still_life", "lifestyle_still_life"}


def layout_descriptor(image: Image.Image) -> dict:
    rgb = np.asarray(image.convert("RGB"), dtype=np.float32)
    if rgb.shape[:2] != (224, 224):
        rgb = np.floor(_bilinear(rgb, 224, 224) + .5).clip(0, 255)
    rgb = rgb.astype(np.float64) / 255.0
    luma = rgb @ np.array([0.299, 0.587, 0.114])
    edge = np.zeros_like(luma)
    edge[:, :-1] += np.abs(luma[:, 1:] - luma[:, :-1]) / 2
    edge[:-1, :] += np.abs(luma[1:, :] - luma[:-1, :]) / 2
    grids = {"luma_grid": [], "edge_grid": [], "rgb_mean_grid": []}
    for row in range(3):
        for col in range(3):
            ys, xs = slice(row * 224 // 3, (row + 1) * 224 // 3), slice(col * 224 // 3, (col + 1) * 224 // 3)
            grids["luma_grid"].append(round(float(luma[ys, xs].mean()), 7))
            grids["edge_grid"].append(round(float(edge[ys, xs].mean()), 7))
            grids["rgb_mean_grid"].extend(np.round(rgb[ys, xs].mean(axis=(0, 1)), 7).tolist())
    return {"algorithm": LAYOUT_ALGORITHM, "grid_size": 3, "input_size": 224,
            **grids, "mean_luma": round(float(luma.mean()), 7),
            "dark_fraction": round(float((luma <= .02).mean()), 7),
            "light_fraction": round(float((luma >= .98).mean()), 7),
            "is_aesthetic_score": False, "horizon_available": False}


def canonical_objects(detections: list[dict]) -> list[dict]:
    objects = []
    for value in detections:
        left, top, right, bottom = map(float, value["box"])
        conf = float(value["confidence"])
        if not all(math.isfinite(v) for v in (left, top, right, bottom, conf)):
            continue
        if not (0 <= left < right <= 1 and 0 <= top < bottom <= 1 and 0 <= conf <= 1):
            continue
        objects.append({"class_id": int(value["class_id"]), "label": str(value.get("label", "")),
                        "confidence": round(conf, 7),
                        "bbox": [round(left, 7), round(top, 7), round(right - left, 7), round(bottom - top, 7)],
                        "clipped": min(left, top, 1 - right, 1 - bottom) < .02})
    return objects


def object_eligibility(row: dict, objects: list[dict]) -> tuple[bool, list[str], int | None]:
    reasons = []
    if not row.get("reference_eligible", False):
        reasons.append("not_hash_reviewed_reference")
    if row.get("synthetic", False):
        reasons.append("synthetic_reference")
    if row.get("scene_category") not in ALLOWED_SCENES:
        reasons.append("scene_not_supported_for_object_target")
    candidates = [(i, obj) for i, obj in enumerate(objects)
                  if obj["confidence"] >= .50 and obj["bbox"][2] * obj["bbox"][3] >= .02]
    candidates.sort(key=lambda pair: (-pair[1]["bbox"][2] * pair[1]["bbox"][3], pair[0]))
    if not candidates:
        reasons.append("no_confident_supported_size_object")
        return False, reasons, None
    primary_index, primary = candidates[0]
    area = primary["bbox"][2] * primary["bbox"][3]
    if primary["class_id"] not in ALLOWED_CLASSES:
        reasons.append("primary_class_not_supported")
    if area > .65:
        reasons.append("primary_too_large")
    x, y, width, height = primary["bbox"]
    if primary.get("clipped", False) or min(x, y, 1 - x - width, 1 - y - height) < .02 - 1e-7:
        reasons.append("primary_touches_frame_boundary")
    if any(obj["class_id"] == 0 for _, obj in candidates):
        reasons.append("person_context")
    if any(obj["bbox"][2] * obj["bbox"][3] >= area * .35 for _, obj in candidates[1:]):
        reasons.append("multiple_competing_objects")
    if row.get("quality", {}).get("severe_quality_issue", False):
        reasons.append("severe_quality_issue")
    return not reasons, reasons, primary_index


def observation_sha256(objects: list[dict]) -> str:
    return hashlib.sha256(json.dumps(objects, sort_keys=True, separators=(",", ":"),
                                    ensure_ascii=False, allow_nan=False).encode("utf-8")).hexdigest()


def reviewed_object_eligibility(row: dict, objects: list[dict], review: dict | None,
                                model_hash: str, required: bool = True,
                                preprocessing: str = PREPROCESSING) -> tuple[bool, list[str], int | None]:
    eligible, reasons, primary = object_eligibility(row, objects)
    if review is not None:
        if (review.get("template_id") != row["template_id"] or
                review.get("image_sha256") != row["image_sha256"] or
                review.get("model_sha256") != model_hash or
                review.get("method") != "ai_visual_review" or
                review.get("human_reviewed") is not False or
                review.get("status") not in {"accepted", "excluded"}):
            raise ValueError(f"Object review identity/method mismatch: {row['template_id']}")
        if preprocessing == NATIVE_PREPROCESSING and (
                review.get("preprocessing") != preprocessing or
                review.get("objects_sha256") != observation_sha256(objects)):
            raise ValueError(f"Object review observation/preprocessing mismatch: {row['template_id']}")
        if review["status"] == "accepted" and review.get("reason_codes"):
            raise ValueError("Accepted object review cannot carry rejection reasons")
        if review["status"] == "excluded":
            reasons.append("object_visual_review_excluded")
            reasons.extend(review.get("reason_codes", []))
    elif eligible and required:
        reasons.append("object_visual_review_missing")
    return eligible and not reasons, reasons, primary


def retained_fraction(box: list[float], crop: list[float]) -> float:
    x, y, w, h = box
    left, top, right, bottom = crop
    if w <= 0 or h <= 0 or right <= left or bottom <= top:
        return 0.0
    return max(0., min(x + w, right) - max(x, left)) * max(0., min(y + h, bottom) - max(y, top)) / (w * h)


def crop_preserves_objects(objects: list[dict], crop: list[float]) -> bool:
    left, top, right, bottom = crop
    if not (0 <= left < right <= 1 and 0 <= top < bottom <= 1):
        return False
    for obj in objects:
        if obj["confidence"] < .5 or obj["bbox"][2] * obj["bbox"][3] < .02:
            continue
        if retained_fraction(obj["bbox"], crop) < .98 - 1e-7:
            return False
    return True


def box_iou(a: list[float], b: list[float]) -> float:
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    intersection = max(0., min(ax + aw, bx + bw) - max(ax, bx)) * max(0., min(ay + ah, by + bh) - max(ay, by))
    return intersection / max(1e-12, aw * ah + bw * bh - intersection)


def live_uncertain_competitors(objects: list[dict], primary_index: int) -> list[dict]:
    """Replay the stricter live veto, separate from reviewed-reference validity."""
    primary = objects[primary_index]
    area = primary["bbox"][2] * primary["bbox"][3]
    return [obj for i, obj in enumerate(objects) if i != primary_index
            and .35 <= obj["confidence"] < .50
            and obj["bbox"][2] * obj["bbox"][3] >= area * .35
            and box_iou(primary["bbox"], obj["bbox"]) < .60]


def live_uncertainty_replay(records: list[dict]) -> dict:
    rows = []
    for record in records:
        if not record["object_eligible"]:
            continue
        conflicts = live_uncertain_competitors(record["objects"], record["primary_object_index"])
        rows.append({"template_id": record["template_id"], "source_cluster_id": record["source_cluster_id"],
                     "passes_uncertain_competitor_veto": not conflicts,
                     "uncertain_competitors": conflicts})
    return {"input_kind": "existing_reference_images_as_observations",
            "policy": "score_035_to_050_area035_iou_below060_v1",
            "reference_count": len(rows), "passes": sum(r["passes_uncertain_competitor_veto"] for r in rows),
            "rejected": sum(not r["passes_uncertain_competitor_veto"] for r in rows),
            "not_query_coverage_or_accuracy": True,
            "does_not_test_same_source_excluded_retrieval_or_full_ready_workflow": True,
            "records": rows}


def coverage_report(rows: list[dict]) -> dict:
    by_scene = {}
    for scene in sorted({r["scene_category"] for r in rows}):
        selected = [r for r in rows if r["scene_category"] == scene]
        good = [r for r in selected if r["object_eligible"]]
        groups = {r["source_cluster_id"] for r in selected}
        eligible_groups = {r["source_cluster_id"] for r in good}
        by_scene[scene] = {"reference_count": len(selected), "source_clusters": len(groups),
                           "eligible_object_references": len(good), "eligible_object_clusters": len(eligible_groups),
                           "image_coverage": len(good) / len(selected), "cluster_coverage": len(eligible_groups) / len(groups),
                           "eligible_labels": dict(Counter(r["objects"][r["primary_object_index"]]["label"] for r in good)),
                           "rejection_reasons": dict(Counter(reason for r in selected for reason in r["object_reasons"]))}
    return {"reference_count": len(rows), "source_clusters": len({r["source_cluster_id"] for r in rows}),
            "scenes": by_scene, "coverage_is_not_detection_accuracy": True,
            "coverage_is_not_aesthetic_improvement": True,
            "plant_scope": "Only three existing plant reference clusters; insufficient for dedicated scene claims."}


def write_comparisons(output: Path, source_rows: list[dict], records: list[dict]) -> None:
    thumbnails = output / "comparison-images"
    thumbnails.mkdir(parents=True, exist_ok=True)
    cards = []
    for source, record in zip(source_rows, records):
        original = upright_image(Path(source["image_path"]))
        original.thumbnail((270, 300), Image.Resampling.LANCZOS)
        marked = original.copy()
        draw = ImageDraw.Draw(marked)
        for i, obj in enumerate(record["objects"]):
            if obj["confidence"] < .5:
                continue
            x, y, w, h = obj["bbox"]
            color = "lime" if record["object_eligible"] and i == record["primary_object_index"] else "orange"
            draw.rectangle([x * marked.width, y * marked.height, (x + w) * marked.width, (y + h) * marked.height], outline=color, width=3)
            draw.text((x * marked.width + 2, y * marked.height + 2), f"{obj['label']} {obj['confidence']:.2f}", fill=color)
        for k in (1, 2):
            draw.line((marked.width * k / 3, 0, marked.width * k / 3, marked.height), fill="#80b0ff", width=1)
            draw.line((0, marked.height * k / 3, marked.width, marked.height * k / 3), fill="#80b0ff", width=1)
        stem = record["template_id"]
        original.save(thumbnails / f"{stem}-original.jpg", quality=85)
        marked.save(thumbnails / f"{stem}-structure.jpg", quality=90)
        text = "eligible object target" if record["object_eligible"] else "; ".join(record["object_reasons"])
        cards.append(f'<article><h3>{html.escape(stem)} · {html.escape(record["scene_category"])}</h3><img loading="lazy" src="comparison-images/{stem}-original.jpg"><img loading="lazy" src="comparison-images/{stem}-structure.jpg"><p>{html.escape(text)}</p></article>')
    page = '<!doctype html><meta charset="utf-8"><title>Multi-scene measured structure</title><style>body{font:15px sans-serif;background:#f5f5f5;margin:24px}article{display:inline-block;vertical-align:top;background:white;padding:12px;margin:6px;width:560px}img{max-width:270px;max-height:300px}p{overflow-wrap:anywhere}</style><h1>原图与自动测量结构</h1><p>绿色：满足静物动作条件的检测框；橙色：其他检测；蓝线：3×3布局采样。检测结果未经人工标注验证，不代表完整主体或审美改善。</p>' + ''.join(cards)
    (output / "comparisons.html").write_text(page, encoding="utf-8", newline="\n")


def automatic_evaluation(source_rows: list[dict], records: list[dict], detector,
                         preprocessing: str = PREPROCESSING) -> dict:
    """Fixed diagnostic transformations, not selection against held-out labels."""
    by_id = {row["template_id"]: row for row in source_rows}
    tested, seen = [], set()
    # Every eligible source cluster once; no performance-driven threshold search.
    for record in records:
        if not record["object_eligible"] or record["source_cluster_id"] in seen:
            continue
        seen.add(record["source_cluster_id"])
        image = upright_image(Path(by_id[record["template_id"]]["image_path"]))
        primary = record["objects"][record["primary_object_index"]]
        x, y, w, h = primary["bbox"]
        expected = [1 - x - w, y, w, h]
        detect = detector.detect_native if preprocessing == NATIVE_PREPROCESSING else detector.detect
        mirrored = canonical_objects(detect(ImageOps.mirror(image)))
        candidates = [o for o in mirrored if o["class_id"] == primary["class_id"] and o["confidence"] >= .5]
        best_iou = max((box_iou(expected, o["bbox"]) for o in candidates), default=0.)
        # Full frame is always a candidate. Crops are true pixel rectangles; this
        # test only certifies measured-box retention, never unobserved content.
        crops = [[0., 0., 1., 1.], [.1, .1, .9, .9], [0., 0., .8, .8], [.2, .2, 1., 1.]]
        decisions = [{"crop": c, "safe": crop_preserves_objects(record["objects"], c),
                      "primary_retained_fraction": retained_fraction(primary["bbox"], c)} for c in crops]
        tested.append({"template_id": record["template_id"], "source_cluster_id": record["source_cluster_id"],
                       "scene_category": record["scene_category"], "mirror_same_class_iou": best_iou,
                       "mirror_consistent_at_iou_0_5": best_iou >= .5, "crops": decisions})
    # Style counterexamples are existing photos, never automatically labelled bad
    # based on high/low global luminance alone.
    style_rows = []
    for record in records:
        l = record["layout"]
        if l["mean_luma"] < .25 or l["mean_luma"] > .75 or l["dark_fraction"] > .15 or l["light_fraction"] > .15:
            style_rows.append({"template_id": record["template_id"], "source_cluster_id": record["source_cluster_id"],
                               "scene_category": record["scene_category"], "mean_luma": l["mean_luma"],
                               "dark_fraction": l["dark_fraction"], "light_fraction": l["light_fraction"],
                               "no_global_style_rejection": not any("luminance" in r or "exposure" in r for r in record["object_reasons"])})
    return {"diagnostic_only": True, "no_old_test_or_aesthetic_acceptance_used": True,
            "live_uncertainty_replay": live_uncertainty_replay(records),
            "grouped_mirror_consistency": {"source_clusters": len(tested),
                "consistent_clusters": sum(r["mirror_consistent_at_iou_0_5"] for r in tested),
                "rate": sum(r["mirror_consistent_at_iou_0_5"] for r in tested) / len(tested) if tested else None,
                "not_ground_truth_accuracy": True},
            "crop_protection": {"candidate_count": sum(len(r["crops"]) for r in tested),
                "rejected_candidate_count": sum(not c["safe"] for r in tested for c in r["crops"]),
                "accepted_crop_truncation_violations": sum(c["safe"] and c["primary_retained_fraction"] < .98 - 1e-7 for r in tested for c in r["crops"]),
                "only_measured_box_guarantee": True},
            "style_counterexamples": style_rows, "source_cluster_diagnostics": tested,
            "limits": ["No human object boxes, horizon labels, or beauty preferences available.",
                       "Mirror consistency can agree on a wrong object; it is not mAP.",
                       "Layout describes coarse appearance and does not identify a horizon.",
                       "Local measurements do not establish phone latency or perceptual improvement."]}


def build(source_manifest: Path, model: Path, output: Path,
          object_review: Path | None = None, reuse_sidecar: Path | None = None,
          preprocessing: str = NATIVE_PREPROCESSING) -> dict:
    from multiscene_detector import NanoDetDetector
    if preprocessing not in {PREPROCESSING, NATIVE_PREPROCESSING}:
        raise ValueError("Unsupported detector preprocessing")
    if output.exists() and any(output.iterdir()):
        raise ValueError("Output must be empty; retain existing experiment evidence.")
    sources = [r for r in read_rows(source_manifest) if r.get("reference_eligible")]
    model_hash = sha256(model)
    reviews = {}
    if object_review:
        for review in read_rows(object_review):
            if review["template_id"] in reviews:
                raise ValueError("Duplicate object review id")
            reviews[review["template_id"]] = review
    ids = [r["template_id"] for r in sources]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate reference id")
    if set(reviews) - set(ids):
        raise ValueError("Object review contains unknown reference id")
    reused = {}
    if reuse_sidecar:
        previous = json.loads(reuse_sidecar.read_text(encoding="utf-8"))
        if (previous.get("model_sha256") != model_hash or
                previous.get("preprocessing") != preprocessing or
                previous.get("layout_algorithm") != LAYOUT_ALGORITHM):
            raise ValueError("Cached observations have incompatible model/preprocessing/layout")
        reused = {row["template_id"]: row for row in previous["records"]}
        if len(reused) != len(previous["records"]) or set(reused) != set(ids):
            raise ValueError("Cached observation ids do not match source references")
    for row in sources:
        if sha256(Path(row["image_path"])) != row["image_sha256"]:
            raise ValueError(f"Reference hash mismatch: {row['template_id']}")
    output.mkdir(parents=True, exist_ok=True)
    detector = NanoDetDetector(model)
    detect = detector.detect_native if preprocessing == NATIVE_PREPROCESSING else detector.detect
    records, durations = [], []
    for i, row in enumerate(sources):
        image = upright_image(Path(row["image_path"]))
        if image.size != (row["image_width"], row["image_height"]):
            raise ValueError(f"EXIF upright size mismatch: {row['template_id']}")
        if reuse_sidecar:
            cached = reused[row["template_id"]]
            if (cached.get("image_sha256") != row["image_sha256"] or
                    cached.get("source_cluster_id") != row["source_group_id"] or
                    cached.get("image_width") != image.width or cached.get("image_height") != image.height):
                raise ValueError(f"Cached observation source mismatch: {row['template_id']}")
            objects = cached["objects"]
        else:
            start = time.perf_counter()
            objects = canonical_objects(detect(image))
            durations.append((time.perf_counter() - start) * 1000)
        review = reviews.get(row["template_id"])
        eligible, reasons, primary_index = reviewed_object_eligibility(
            row, objects, review, model_hash, required=object_review is not None, preprocessing=preprocessing)
        records.append({"template_id": row["template_id"], "image_sha256": row["image_sha256"],
                        "coordinate_space": "exif_upright_full_frame",
                        "image_width": image.width, "image_height": image.height,
                        "source_cluster_id": row["source_group_id"], "photo_type": row["photo_type"],
                        "scene_category": row["scene_category"], "synthetic": bool(row.get("synthetic", False)),
                        "objects": objects, "primary_object_index": primary_index,
                        "object_eligible": eligible, "object_reasons": reasons,
                        "object_review": review, "layout": layout_descriptor(image)})
        if (i + 1) % 50 == 0:
            print(f"Measured {i + 1}/{len(sources)} references", flush=True)
    sidecar = {"schema_version": 1, "coordinate_space": "exif_upright_full_frame",
               "model_sha256": model_hash, "preprocessing": preprocessing, "object_policy": OBJECT_POLICY,
               "object_review_policy": OBJECT_REVIEW_POLICY if object_review else "unreviewed_diagnostic_only",
               "object_review_sha256": sha256(object_review) if object_review else None,
               "layout_algorithm": LAYOUT_ALGORITHM, "human_reviewed": False,
               "aesthetic_ground_truth": False, "reference_count": len(records), "records": records}
    write_json(output / "multi_scene_references.json", sidecar)
    report = coverage_report(records)
    report.update({"source_manifest_sha256": sha256(source_manifest), "model_sha256": model_hash,
                   "object_policy": OBJECT_POLICY, "preprocessing": preprocessing, "layout_algorithm": LAYOUT_ALGORITHM,
                   "object_review_policy": sidecar["object_review_policy"],
                   "object_review_counts": dict(Counter(r["status"] for r in reviews.values())),
                   "reuse_sidecar_sha256": sha256(reuse_sidecar) if reuse_sidecar else None,
                   "detector_local_cpu_ms": {"p50": float(np.percentile(durations, 50)) if durations else None,
                        "p90": float(np.percentile(durations, 90)) if durations else None,
                        "samples": len(durations), "cached_observations": reuse_sidecar is not None,
                        "includes_pre_and_postprocess": True, "is_phone_benchmark": False}})
    write_json(output / "coverage-report.json", report)
    write_json(output / "automatic-evaluation.json", automatic_evaluation(sources, records, detector, preprocessing))
    write_comparisons(output, sources, records)
    print(json.dumps(report, ensure_ascii=True), flush=True)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-manifest", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--object-review", type=Path, help="Hash-bound AI visual review; missing review cannot become an action reference.")
    parser.add_argument("--reuse-sidecar", type=Path, help="Reuse pinned raw detections while preserving the original diagnostic run.")
    parser.add_argument("--preprocessing", choices=[PREPROCESSING, NATIVE_PREPROCESSING], default=NATIVE_PREPROCESSING)
    args = parser.parse_args()
    build(args.source_manifest, args.model, args.output, args.object_review, args.reuse_sidecar, args.preprocessing)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
