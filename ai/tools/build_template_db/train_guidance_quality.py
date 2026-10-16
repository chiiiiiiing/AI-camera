#!/usr/bin/env python3
"""Bounded, group-held-out quality distillation; failed acceptance disables deployment.

This learns a weak teacher preference, not a probability of human approval. The
retrieval encoder is immutable and may have seen every source during its earlier
training. All generated views inherit their original source group. No old retrieval
validation/test image is admitted, and the acceptance report is never overwritten.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import math
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageFilter, ImageOps

from prepare_quality_teacher import sha256, verify_teacher

FEATURE_NAMES = ["mean_luma", "luma_std", "dark_fraction", "light_fraction",
                 "face_area", "face_cx", "face_cy", "face_valid"]
WEIGHT_DECAYS = (0.0001, 0.001, 0.01)
SEED = 20261015
TEACHER_MARGIN = 0.20
STUDENT_MARGIN = 0.05
ENABLED_SCENES = ("portrait",)
INPUT_DIM = 520
HIDDEN_DIM = 64
FLOAT_COUNT = HIDDEN_DIM * INPUT_DIM + HIDDEN_DIM + HIDDEN_DIM + 1


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")


def read_rows(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def valid_face(row: dict) -> list[float] | None:
    faces = row.get("faces", [])
    if row.get("face_count", len(faces)) != 1 or len(faces) != 1:
        return None
    face = faces[0]
    bbox = face.get("bbox", [])
    if face.get("clipped", True) or face.get("confidence", 0) < 0.85 or len(bbox) != 4:
        return None
    x, y, w, h = (float(v) for v in bbox)
    if not all(math.isfinite(v) for v in (x, y, w, h)):
        return None
    if w <= 0 or h <= 0 or min(x, y) < 0 or x + w > 1 or y + h > 1:
        return None
    return [x, y, w, h]


def color_features(image: Image.Image, face: list[float] | None = None) -> np.ndarray:
    rgb = np.asarray(image.convert("RGB").resize((224, 224), Image.Resampling.BILINEAR), dtype=np.float64)
    lum = (rgb[..., 0] * 0.299 + rgb[..., 1] * 0.587 + rgb[..., 2] * 0.114) / 255.0
    values = [float(lum.mean()), float(lum.std()), float((lum <= .05).mean()), float((lum >= .95).mean())]
    if face is None:
        values += [0.0] * 4
    else:
        x, y, w, h = face
        values += [w * h, x + w / 2, y + h / 2, 1.0]
    return np.asarray(values, dtype=np.float32)


def source_splits(rows: list[dict]) -> dict[str, str]:
    """Assign complete groups once, before image augmentation; stable within scene."""
    groups: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        group = row.get("source_group_id")
        if not group:
            raise ValueError("every source must have an explicit source_group_id")
        groups[group].append(row)
    by_scene: dict[str, list[str]] = defaultdict(list)
    for group, members in groups.items():
        scene = Counter(r.get("photo_type", "unknown") for r in members).most_common(1)[0][0]
        by_scene[scene].append(group)
    assignments = {}
    for scene, members in sorted(by_scene.items()):
        ordered = sorted(members, key=lambda g: hashlib.sha256(f"{SEED}:{g}".encode()).hexdigest())
        n_accept = max(1, int(len(ordered) * .25)) if len(ordered) >= 4 else 0
        n_val = max(1, int(len(ordered) * .20)) if len(ordered) >= 5 else 0
        for index, group in enumerate(ordered):
            assignments[group] = "acceptance" if index < n_accept else "val" if index < n_accept + n_val else "train"
    return assignments


def safe_crops(face: list[float] | None) -> list[tuple[float, float, float, float]]:
    """Two actual same-aspect crops at most; never infer an unseen nonface subject."""
    if face is None:
        return []
    x, y, w, h = face
    result = []
    for size, target_x, target_y in ((.90, .5, .42), (.84, .5, .42)):
        left = min(1 - size, max(0., x + w / 2 - target_x * size))
        top = min(1 - size, max(0., y + h / 2 - target_y * size))
        crop = (left, top, left + size, top + size)
        margin = .02
        if x >= crop[0] + margin and y >= crop[1] + margin and x + w <= crop[2] - margin and y + h <= crop[3] - margin:
            result.append(crop)
    return result


def transformed_face(face: list[float], crop: tuple[float, float, float, float]) -> list[float]:
    left, top, right, bottom = crop
    x, y, w, h = face
    if x < left or y < top or x + w > right or y + h > bottom:
        raise ValueError("crop removes a detected subject")
    return [(x - left) / (right - left), (y - top) / (bottom - top), w / (right - left), h / (bottom - top)]


def variants(image: Image.Image, row: dict) -> list[tuple[str, Image.Image, list[float] | None, bool]]:
    image = image.convert("RGB")
    face = valid_face(row)
    # Coordinates apply to EXIF-upright full image, before the shared 224 resize.
    result = [("current", image, face, True)]
    for index, crop in enumerate(safe_crops(face)):
        left, top, right, bottom = crop
        pixels = (round(left * image.width), round(top * image.height), round(right * image.width), round(bottom * image.height))
        actual_crop = (pixels[0] / image.width, pixels[1] / image.height, pixels[2] / image.width, pixels[3] / image.height)
        child_face = transformed_face(face, actual_crop)
        result.append((f"crop_{index}", image.crop(pixels), child_face, True))
    small = image.resize((224, 224), Image.Resampling.BILINEAR)
    # Only label demonstrable detail destruction. Flat/very blurred originals abstain.
    gray = np.asarray(small.convert("L"), dtype=np.float64)
    lap = gray[1:-1, :-2] + gray[1:-1, 2:] + gray[:-2, 1:-1] + gray[2:, 1:-1] - 4 * gray[1:-1, 1:-1]
    if float(lap.var()) >= 80:
        result.append(("defect_blur", small.filter(ImageFilter.GaussianBlur(4.0)), face, True))
    stats = color_features(small)
    if .15 <= stats[0] <= .70 and stats[3] <= .05:
        arr = np.asarray(small, dtype=np.float32)
        exposed = Image.fromarray(np.clip(arr * 1.7 + 32, 0, 255).astype(np.uint8))
        if color_features(exposed)[3] >= stats[3] + .15:
            result.append(("defect_overexposure", exposed, face, True))
    return result


def prepare_sources(args: argparse.Namespace) -> list[dict]:
    candidates = read_rows(args.source_manifest)
    # Reject an entire group crossing original splits, even if one member is eligible.
    blocked = {r.get("source_group_id") for r in candidates if r.get("original_split") in ("val", "test")}
    rows = [r for r in candidates if r.get("original_split") == "train" and not r.get("synthetic", False)
            and not r.get("source_only", True) and r.get("reference_eligible", False)
            and not r.get("historical_holdout_group", False)
            and r.get("source_group_id") not in blocked]
    if not rows:
        raise ValueError("no eligible reviewed original training sources")
    splits = source_splits(rows)
    for row in rows:
        row["quality_split"] = splits[row["source_group_id"]]
    counts = Counter(r["quality_split"] for r in rows)
    if any(counts[s] < 2 for s in ("train", "val", "acceptance")):
        raise ValueError("insufficient distinct groups for train/val/acceptance")
    manifest = {"schema_version": 1, "seed": SEED, "source_manifest_sha256": sha256(args.source_manifest),
                "encoder_sha256": sha256(args.encoder), "teacher_provenance_sha256": sha256(args.vendor_dir / "teacher-provenance.json"),
                "training_script_sha256": sha256(Path(__file__)),
                "feature_names": FEATURE_NAMES, "hidden_dim": HIDDEN_DIM, "face_confidence_minimum": .85,
                "crop_sizes": [.90, .84], "crop_face_target": [.5, .42], "crop_subject_margin": .02,
                "split_counts": dict(counts), "group_assignments": splits,
                "candidate_weight_decays": list(WEIGHT_DECAYS), "epochs": 300,
                "teacher_margin": TEACHER_MARGIN, "student_margin": STUDENT_MARGIN,
                "enabled_scene_candidates": list(ENABLED_SCENES), "teacher_augmentation": "JPEG quality90",
                "encoder_seen_source": True, "encoder_exposure_note": "All eligible sources come from the original encoder training split; this is a group-held-out head evaluation, not fresh full-system generalization.",
                "historical_53_test_used": False, "retrieval_gallery": "not used in pair evaluation; no reference retrieval can leak a source",
                "hard_constraints_scope": "Known single-face crop preservation only; other subjects are not claimed observable."}
    protocol_path = args.output_dir / "frozen-protocol.json"
    if protocol_path.exists():
        if json.loads(protocol_path.read_text(encoding="utf-8")) != manifest:
            raise ValueError("frozen protocol cannot be changed on resume")
    else:
        write_json(protocol_path, manifest)
    return rows


def extract(args: argparse.Namespace, rows: list[dict]) -> tuple[np.ndarray, np.ndarray, list[dict]]:
    import torch
    import onnxruntime as ort
    verify_teacher(args.vendor_dir)
    sys.path.insert(0, str(args.vendor_dir.resolve()))
    if args.deps_dir:
        sys.path.insert(0, str(args.deps_dir.resolve()))
    import clip
    from torch import nn

    torch.set_num_threads(4)
    device = ("cuda" if torch.cuda.is_available() else "cpu") if args.device == "auto" else args.device
    teacher, preprocess = clip.load(str(args.vendor_dir / "ViT-L-14.pt"), device=device, jit=False)
    # Match the author's exact no-ReLU architecture, without Lightning dependency.
    predictor = nn.Module()
    predictor.layers = nn.Sequential(nn.Linear(768, 1024), nn.Dropout(.2), nn.Linear(1024, 128), nn.Dropout(.2),
                                     nn.Linear(128, 64), nn.Dropout(.1), nn.Linear(64, 16), nn.Linear(16, 1))
    predictor.load_state_dict(torch.load(args.vendor_dir / "sac+logos+ava1-l14-linearMSE.pth", map_location="cpu", weights_only=True))
    predictor.to(device).eval()
    teacher.eval()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    runtime = ort.InferenceSession(str(args.encoder), options, providers=["CPUExecutionProvider"])
    input_name = runtime.get_inputs()[0].name
    images, records, features = [], [], []
    view_dir = args.output_dir / "views"
    view_dir.mkdir(exist_ok=True)
    for index, row in enumerate(rows):
        source = Path(row.get("source_path") or row["image_path"])
        if sha256(source) != (row.get("source_sha256") or row["image_sha256"]):
            raise ValueError(f"source hash changed: {row.get('template_id')}")
        with Image.open(source) as raw:
            upright = ImageOps.exif_transpose(raw).convert("RGB")
        for kind, picture, face, protected in variants(upright, row):
            image = picture.resize((224, 224), Image.Resampling.BILINEAR)
            key = f"{index:04d}_{kind}"
            image.save(view_dir / f"{key}.png")
            normalized = np.asarray(image, dtype=np.float32).transpose(2, 0, 1)[None] / 127.5 - 1.
            embedding = runtime.run(None, {input_name: normalized})[0].reshape(-1)
            if embedding.shape != (512,) or not np.isfinite(embedding).all() or abs(float(np.linalg.norm(embedding)) - 1) > .001:
                raise ValueError("invalid frozen encoder output")
            features.append(np.concatenate((embedding, color_features(image, face))))
            images.append(image)
            records.append({"key": key, "source_id": row.get("template_id", row.get("photo_id")),
                            "source_group_id": row["source_group_id"], "source_sha256": row.get("source_sha256", row.get("image_sha256")),
                            "split": row["quality_split"], "scene": row.get("photo_type", "unknown"), "kind": kind,
                            "hard_constraints_passed": protected, "face_valid": face is not None, "encoder_seen_source": True})
    scores = []
    # The full visible viewport is represented: shared 224px resize before CLIP
    # preprocessing avoids its default center-crop silently discarding image edges.
    with torch.inference_mode():
        for begin in range(0, len(images), args.batch_size):
            batch = images[begin:begin + args.batch_size]
            tensors = []
            for picture in batch:
                compressed = io.BytesIO()
                picture.save(compressed, format="JPEG", quality=90)
                compressed.seek(0)
                tensors.extend((preprocess(picture), preprocess(Image.open(compressed).convert("RGB"))))
            encoded = teacher.encode_image(torch.stack(tensors).to(device)).float()
            encoded /= encoded.norm(dim=-1, keepdim=True).clamp_min(1e-12)
            value = predictor.layers(encoded).cpu().numpy().reshape(-1, 2)
            if not np.isfinite(value).all():
                raise ValueError("teacher emitted nonfinite scores")
            scores.extend(value.tolist())
            if begin % (args.batch_size * 10) == 0:
                print(f"Teacher scored {min(begin + len(batch), len(images))}/{len(images)} views", flush=True)
    del teacher, predictor
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    x = np.asarray(features, np.float32)
    y = np.asarray(scores, np.float32)
    np.savez_compressed(args.output_dir / "frozen-features.npz", x=x, teacher=y)
    write_json(args.output_dir / "views.json", records)
    write_json(args.output_dir / "feature-cache-integrity.json", {
        "frozen-features.npz": sha256(args.output_dir / "frozen-features.npz"),
        "views.json": sha256(args.output_dir / "views.json"),
    })
    return x, y, records


def make_pairs(records: list[dict], teacher: np.ndarray) -> list[dict]:
    parents: dict[str, list[int]] = defaultdict(list)
    for index, row in enumerate(records):
        parents[row["source_id"]].append(index)
    pairs = []
    for indices in parents.values():
        original = next(i for i in indices if records[i]["kind"] == "current")
        for i in indices:
            if i == original:
                continue
            row = records[i]
            common = {"left": original, "right": i, "source_group_id": row["source_group_id"], "split": row["split"], "scene": row["scene"]}
            delta = teacher[original] - teacher[i]
            # Two independent image encodings must agree beyond the predeclared margin.
            if np.all(delta >= TEACHER_MARGIN) or np.all(delta <= -TEACHER_MARGIN):
                pairs.append({**common, "target": 1 if delta.mean() > 0 else -1, "kind": "teacher_preference", "view_kind": row["kind"]})
            if row["kind"].startswith("defect_"):
                pairs.append({**common, "target": 1, "kind": "known_defect", "view_kind": row["kind"]})
    return pairs


def bootstrap_lower(correct: list[bool], groups: list[str]) -> float:
    grouped: dict[str, list[float]] = defaultdict(list)
    for value, group in zip(correct, groups):
        grouped[group].append(float(value))
    if len(grouped) < 2:
        return 0.0
    # One equally weighted source cluster per draw prevents near-duplicate inflation.
    means = np.asarray([np.mean(v) for _, v in sorted(grouped.items())])
    rng = np.random.default_rng(SEED)
    draws = rng.choice(means, size=(2000, len(means)), replace=True).mean(axis=1)
    return float(np.quantile(draws, .025))


def pair_metrics(scores: np.ndarray, pairs: list[dict], scenes: tuple[str, ...] | None = None) -> dict:
    chosen = [p for p in pairs if scenes is None or p["scene"] in scenes]
    teacher_pairs = [p for p in chosen if p["kind"] == "teacher_preference"]
    correct = [bool((scores[p["left"]] - scores[p["right"]]) * p["target"] > 1e-6) for p in teacher_pairs]
    groups = [p["source_group_id"] for p in teacher_pairs]
    scene_groups: dict[str, set] = defaultdict(set)
    for pair in teacher_pairs:
        scene_groups[pair["scene"]].add(pair["source_group_id"])
    severe = [p for p in chosen if p["kind"] == "known_defect"]
    by_kind = {}
    for kind in sorted({p["view_kind"] for p in severe}):
        subset = [p for p in severe if p["view_kind"] == kind]
        by_kind[kind] = float(np.mean([scores[p["left"]] > scores[p["right"]] + 1e-6 for p in subset]))
    crop = [p for p in teacher_pairs if p["view_kind"].startswith("crop_")]
    confident = sum(abs(float(scores[p["left"]] - scores[p["right"]])) >= STUDENT_MARGIN for p in teacher_pairs)
    return {"acceptance_cluster_count": len(set(groups)), "scene_cluster_counts": {s: len(g) for s, g in sorted(scene_groups.items())},
            "pair_count": len(teacher_pairs), "pairwise_agreement": float(np.mean(correct)) if correct else 0.,
            "bootstrap_lower95": bootstrap_lower(correct, groups), "known_defect_pair_count": len(severe), "defect_agreement_by_kind": by_kind,
            "crop_teacher_pair_count": len(crop), "crop_source_cluster_count": len({p['source_group_id'] for p in crop}),
            "crop_pairwise_agreement": float(np.mean([(scores[p['left']] - scores[p['right']]) * p['target'] > 1e-6 for p in crop])) if crop else None,
            "coverage": confident / len(teacher_pairs) if teacher_pairs else 0., "rejection_rate": 1. - confident / len(teacher_pairs) if teacher_pairs else 1.,
            "coverage_scope": "fraction of non-tie teacher pairs with student absolute score gap >= 0.05; not application recommendation coverage"}


def deployment_gates(candidate: dict, baseline: dict, hard_constraints: bool, byte_count: int) -> dict[str, bool]:
    return {"minimum_acceptance_clusters": candidate["acceptance_cluster_count"] >= 30,
            "minimum_scene_clusters": all(candidate["scene_cluster_counts"].get(s, 0) >= 10 for s in ENABLED_SCENES),
            "teacher_agreement": candidate["pairwise_agreement"] >= .70,
            "bootstrap_above_chance": candidate["bootstrap_lower95"] > .50,
            "beats_structure_baseline": candidate["pairwise_agreement"] > baseline["pairwise_agreement"],
            "hard_constraints": hard_constraints,
            "severe_defect_non_regression": bool(candidate["defect_agreement_by_kind"]) and all(candidate["defect_agreement_by_kind"].get(k, 0) >= v for k, v in baseline["defect_agreement_by_kind"].items()),
            "weight_budget": byte_count <= 262144}


def export_head(model: Any, encoder_sha: str, enabled: bool) -> dict:
    params = [model[0].weight, model[0].bias, model[2].weight, model[2].bias]
    weights = np.concatenate([p.detach().cpu().numpy().reshape(-1) for p in params]).astype("<f4")
    if weights.size != FLOAT_COUNT or not np.isfinite(weights).all():
        raise ValueError("invalid output head")
    return {"schema_version": 1, "enabled": enabled, "embedding_dim": 512, "hidden_dim": 64,
            "feature_names": FEATURE_NAMES, "activation": "relu", "feature_normalization": "none",
            "weight_encoding": "float32-le-base64", "weights_f32_base64": base64.b64encode(weights.tobytes()).decode("ascii"),
            "encoder_sha256": encoder_sha, "enabled_scenes": list(ENABLED_SCENES) if enabled else [],
            "score_semantics": "uncalibrated weak-teacher preference; not human approval probability"}


def fit(args: argparse.Namespace, x: np.ndarray, y: np.ndarray, records: list[dict], pairs: list[dict]) -> dict:
    import torch
    from torch import nn
    from torch.nn import functional as F

    torch.set_num_threads(4)
    train = [p for p in pairs if p["split"] == "train"]
    val = [p for p in pairs if p["split"] == "val"]
    accept = [p for p in pairs if p["split"] == "acceptance"]
    if not train or not val or not accept:
        raise ValueError("each split must contain evaluable pairs")
    left = torch.tensor([p["left"] for p in train])
    right = torch.tensor([p["right"] for p in train])
    target = torch.tensor([p["target"] for p in train], dtype=torch.float32)
    # A source group contributes total weight one regardless of variants/duplicates.
    counts = Counter(p["source_group_id"] for p in train)
    sample_weight = torch.tensor([1 / counts[p["source_group_id"]] for p in train])
    sample_weight /= sample_weight.mean()
    inputs = torch.from_numpy(x)

    def train_one(decay: float, simple: bool = False):
        torch.manual_seed(SEED)
        model = nn.Sequential(nn.Linear(8 if simple else INPUT_DIM, 64), nn.ReLU(), nn.Linear(64, 1)) if not simple else nn.Linear(8, 1)
        data = inputs[:, 512:] if simple else inputs
        optimizer = torch.optim.AdamW(model.parameters(), lr=.003, weight_decay=decay)
        for _ in range(300):
            optimizer.zero_grad(set_to_none=True)
            scores = model(data).flatten()
            loss = (F.softplus(-target * (scores[left] - scores[right])) * sample_weight).mean()
            loss.backward()
            optimizer.step()
        model.eval()
        with torch.inference_mode():
            scores = model(data).flatten().numpy()
        return model, scores

    baseline_model, baseline_scores = train_one(.001, simple=True)
    candidates = []
    models = []
    for decay in WEIGHT_DECAYS:
        model, scores = train_one(decay)
        metrics = pair_metrics(scores, val, ENABLED_SCENES)
        candidates.append({"weight_decay": decay, "validation": metrics})
        models.append((model, scores))
        print(f"Validation weight_decay={decay}: {metrics['pairwise_agreement']:.4f}", flush=True)
    # Fixed tie-break favors stronger regularization. Acceptance has not been scored.
    selected = max(range(len(candidates)), key=lambda i: (candidates[i]["validation"]["pairwise_agreement"], WEIGHT_DECAYS[i]))
    model, scores = models[selected]
    encoder_sha = sha256(args.encoder)
    frozen = export_head(model, encoder_sha, False)
    write_json(args.output_dir / "selected-head.before-acceptance.json", frozen)
    write_json(args.output_dir / "selection.json", {"candidates": candidates, "selected_index": selected,
                                                   "selection_split": "val", "selected_head_sha256": sha256(args.output_dir / "selected-head.before-acceptance.json")})
    if (args.output_dir / "acceptance.json").exists():
        raise ValueError("final acceptance is immutable and must not be rerun")
    candidate_metrics = pair_metrics(scores, accept, ENABLED_SCENES)
    baseline_metrics = pair_metrics(baseline_scores, accept, ENABLED_SCENES)
    hard_constraints = all(r["hard_constraints_passed"] for r in records)
    gates = deployment_gates(candidate_metrics, baseline_metrics, hard_constraints, len(json.dumps(frozen).encode("utf-8")))
    enabled = all(gates.values())
    head = export_head(model, encoder_sha, enabled)
    write_json(args.output_dir / "quality_head.json", head)
    policy = {"schema_version": 1, "enabled": enabled, "encoder_sha256": encoder_sha,
              "head_sha256": sha256(args.output_dir / "quality_head.json"), "enabled_scenes": list(ENABLED_SCENES) if enabled else [],
              "candidate_scenes": list(ENABLED_SCENES), "gates": gates,
              "reasons": [name for name, passed in gates.items() if not passed],
              **candidate_metrics, "baseline_pairwise_agreement": baseline_metrics["pairwise_agreement"],
              "hard_constraints_passed": hard_constraints, "severe_defect_non_regression": gates["severe_defect_non_regression"],
              "metric_semantics": "Agreement with CLIP+MLP weak-teacher rankings stable under JPEG90; ties in student count incorrect. Not human preference accuracy.",
              "encoder_seen_source": True, "historical_test_used": False,
              "phone_latency_verified": False, "head_bytes": (args.output_dir / "quality_head.json").stat().st_size}
    policy["metrics"] = {**candidate_metrics, "baseline_pairwise_agreement": baseline_metrics["pairwise_agreement"],
                         "hard_constraints_passed": hard_constraints, "severe_defect_non_regression": gates["severe_defect_non_regression"]}
    acceptance = {"schema_version": 1, "candidate": candidate_metrics, "structure_baseline": baseline_metrics,
                  "all_scenes_diagnostic": pair_metrics(scores, accept), "policy": policy,
                  "teacher_provenance_sha256": sha256(args.vendor_dir / "teacher-provenance.json"),
                  "frozen_protocol_sha256": sha256(args.output_dir / "frozen-protocol.json"),
                  "encoder_seen_source": True, "true_device_or_human_preference_evaluation": False}
    write_json(args.output_dir / "acceptance.json", acceptance)
    write_json(args.output_dir / "quality_policy.json", policy)
    # Numerical fixture exercises the exact base64 JSON and Android arithmetic.
    fixture_index = next(i for i, r in enumerate(records) if r["split"] == "val")
    write_json(args.output_dir / "quality_fixture.json", {"features": x[fixture_index].tolist(), "expected_score": float(scores[fixture_index]), "absolute_tolerance": .0001})
    return policy


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-manifest", type=Path, required=True)
    parser.add_argument("--encoder", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--vendor-dir", type=Path, required=True)
    parser.add_argument("--deps-dir", type=Path)
    parser.add_argument("--device", default="auto", choices=("auto", "cpu", "cuda"))
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--resume-features", action="store_true", help="Resume interrupted extraction/training under the same frozen protocol; never overwrite acceptance.")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    if (args.output_dir / "acceptance.json").exists():
        raise ValueError("acceptance already exists; this run is frozen")
    if args.batch_size < 1:
        raise ValueError("batch-size must be positive")
    start = time.monotonic()
    if args.resume_features:
        protocol = json.loads((args.output_dir / "frozen-protocol.json").read_text(encoding="utf-8"))
        if protocol["source_manifest_sha256"] != sha256(args.source_manifest) or protocol["encoder_sha256"] != sha256(args.encoder):
            raise ValueError("source manifest or encoder changed since feature extraction")
        if protocol["teacher_provenance_sha256"] != sha256(args.vendor_dir / "teacher-provenance.json"):
            raise ValueError("teacher provenance changed since feature extraction")
        if protocol.get("training_script_sha256") != sha256(Path(__file__)):
            raise ValueError("training code changed since protocol freeze")
        if (args.output_dir / "frozen-features.npz").exists() and (args.output_dir / "views.json").exists():
            integrity = json.loads((args.output_dir / "feature-cache-integrity.json").read_text(encoding="utf-8"))
            if any(integrity.get(name) != sha256(args.output_dir / name) for name in ("frozen-features.npz", "views.json")):
                raise ValueError("frozen feature cache or source mapping changed")
            cached = np.load(args.output_dir / "frozen-features.npz")
            x, y = cached["x"], cached["teacher"]
            records = json.loads((args.output_dir / "views.json").read_text(encoding="utf-8"))
        else:
            rows = prepare_sources(args)
            x, y, records = extract(args, rows)
    else:
        if (args.output_dir / "frozen-protocol.json").exists():
            raise ValueError("output already initialized; use --resume-features after completed extraction")
        rows = prepare_sources(args)
        x, y, records = extract(args, rows)
    pairs = make_pairs(records, y)
    write_json(args.output_dir / "pairs.json", pairs)
    policy = fit(args, x, y, records, pairs)
    write_json(args.output_dir / "run-summary.json", {"seconds": time.monotonic() - start, "views": len(records), "pairs": len(pairs), "policy": policy})
    print(json.dumps({"enabled": policy["enabled"], "reasons": policy["reasons"]}))


if __name__ == "__main__":
    main()
