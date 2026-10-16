#!/usr/bin/env python3
"""One-shot, source-held-out spatial crop preference experiment.

The immutable teacher expresses weak aesthetic preference. Its ranking is not
human ground truth; photographic rules are an independent baseline, not labels.
Only predeclared validation selection is allowed, with one acceptance artifact.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter, ImageOps

from framing_preference import (FEATURE_NAMES, FEATURE_PROTOCOL, HIDDEN_DIM, INPUT_DIM,
                                SCENES, candidates, features, predict, rule_baseline)
from prepare_quality_teacher import sha256, verify_teacher

SEED = 20261016
WEIGHT_DECAYS = (.0001, .001, .01)
TEACHER_MARGIN = .10
SCORE_MARGIN = .10


def write_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")


def read_rows(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def source_scene(row):
    return "food" if row.get("scene_category") == "food_still_life" else row["photo_type"]


def protected_subject(row, object_rows):
    if row["photo_type"] == "portrait":
        faces = row.get("faces", [])
        if len(faces) == 1 and faces[0].get("confidence", 0) >= .85 and not faces[0].get("clipped", True):
            x, y, w, h = faces[0]["bbox"]
            # A measured face cannot certify a complete body; this distinction is retained.
            return [x, y, x + w, y + h], "detected_face_only"
    obj = object_rows.get(row["template_id"], {})
    if obj.get("image_sha256") == row["image_sha256"] and obj.get("object_eligible"):
        primary = obj["objects"][obj["primary_object_index"]]
        x, y, w, h = primary["bbox"]
        return [x, y, x + w, y + h], "reviewed_detector_box"
    return None, "unobserved_subject_no_crop_action_authority"


def prepare(args):
    all_rows = read_rows(args.source_manifest)
    previous = json.loads(args.previous_protocol.read_text(encoding="utf-8"))
    previous_groups = {g for g, split in previous["group_assignments"].items() if split == "acceptance"}
    previous_views = json.loads(args.previous_views.read_text(encoding="utf-8"))
    if isinstance(previous_views, dict):
        previous_views = previous_views["views"]
    previous_hashes = {r["source_sha256"] for r in previous_views if r["split"] == "acceptance"}
    blocked = {r["source_group_id"] for r in all_rows if r.get("historical_holdout_group")
               or r.get("original_split") in ("val", "test") or r["source_group_id"] in previous_groups
               or r.get("source_sha256", r["image_sha256"]) in previous_hashes}
    eligible = [r for r in all_rows if r.get("reference_eligible") and not r.get("synthetic")
                and r.get("original_split") == "train" and r["source_group_id"] not in blocked]
    # One representative per source cluster prevents prolific near-duplicates dominating.
    grouped = defaultdict(list)
    for row in eligible:
        grouped[row["source_group_id"]].append(row)
    rows = [sorted(members, key=lambda r: r["image_sha256"])[0] for _, members in sorted(grouped.items())]
    scene_groups = defaultdict(list)
    for row in rows:
        scene_groups[source_scene(row)].append(row["source_group_id"])
    assignments = {}
    for scene, groups in sorted(scene_groups.items()):
        groups.sort(key=lambda g: hashlib.sha256(f"{SEED}:{g}".encode()).hexdigest())
        n_accept, n_val = int(len(groups) * .25), int(len(groups) * .20)
        for index, group in enumerate(groups):
            assignments[group] = "acceptance" if index < n_accept else "val" if index < n_accept + n_val else "train"
    for row in rows:
        row["framing_split"] = assignments[row["source_group_id"]]
    protocol = {
        "schema_version": 1, "seed": SEED, "source_manifest_sha256": sha256(args.source_manifest),
        "previous_protocol_sha256": sha256(args.previous_protocol), "previous_views_sha256": sha256(args.previous_views),
        "object_structures_sha256": sha256(args.object_structures),
        "teacher_provenance_sha256": sha256(args.vendor_dir / "teacher-provenance.json"),
        "encoder_sha256": sha256(args.encoder),
        "training_script_sha256": sha256(Path(__file__)),
        "feature_script_sha256": sha256(Path(__file__).with_name("framing_preference.py")),
        "feature_protocol": FEATURE_PROTOCOL, "feature_names": FEATURE_NAMES,
        "input_dim": INPUT_DIM, "hidden_dim": HIDDEN_DIM, "epochs": 250,
        "weight_decays": list(WEIGHT_DECAYS), "teacher_margin": TEACHER_MARGIN, "student_margin": SCORE_MARGIN,
        "group_assignments": assignments, "split_counts": dict(Counter(assignments.values())),
        "scene_counts": dict(Counter(source_scene(r) for r in rows)),
        "candidate_scenes": list(SCENES), "candidate_crop_sizes": [.90, .84], "max_crops": 2,
        "crop_preprocessing": "viewport_native_max416_then_crop_bilinear224_v1",
        "teacher_stability_check": "same ranking and margin >=0.10 on original and JPEG90 views",
        "training_pairs": "stable crop comparisons plus independently applicable blur/overexposure defects",
        "selection": "highest validation crop cluster agreement; ties prefer larger weight decay",
        "acceptance_minimum_clusters": 30, "acceptance_minimum_scene_clusters": 10,
        "acceptance_minimum_agreement": .70, "acceptance_lower95_above": .50,
        "acceptance_beats_rule_baseline": True, "acceptance_uses_crop_pairs_only": True,
        "old_historical_holdouts_excluded": True, "previous_quality_acceptance_excluded": True,
        "original_encoder_used": True, "encoder_may_have_seen_source": True,
        "human_aesthetic_labels_used": False,
        "known_subject_protection_only": True, "physical_camera_motion_prediction": False,
        "external_gaic_data_or_weights_used": False,
    }
    path = args.output_dir / "frozen-protocol.json"
    if path.exists() and json.loads(path.read_text(encoding="utf-8")) != protocol:
        raise ValueError("refusing to change frozen framing protocol")
    write_json(path, protocol)
    write_json(args.output_dir / "sources.json", [{k: r[k] for k in ("template_id", "source_group_id", "image_sha256", "framing_split", "photo_type", "scene_category")} for r in rows])
    return rows, protocol


def encode_teacher(args, images):
    import torch
    from torch import nn
    verify_teacher(args.vendor_dir)
    sys.path.insert(0, str(args.vendor_dir.resolve()))
    sys.path.insert(0, str(args.deps_dir.resolve()))
    import clip
    device = "cuda" if torch.cuda.is_available() else "cpu"
    torch.set_num_threads(4)
    teacher, preprocess = clip.load(str(args.vendor_dir / "ViT-L-14.pt"), device=device, jit=False)
    predictor = nn.Module()
    predictor.layers = nn.Sequential(nn.Linear(768, 1024), nn.Dropout(.2), nn.Linear(1024, 128), nn.Dropout(.2),
                                     nn.Linear(128, 64), nn.Dropout(.1), nn.Linear(64, 16), nn.Linear(16, 1))
    predictor.load_state_dict(torch.load(args.vendor_dir / "sac+logos+ava1-l14-linearMSE.pth", map_location="cpu", weights_only=True))
    teacher.eval()
    predictor.to(device).eval()
    scores = []
    with torch.inference_mode():
        for begin in range(0, len(images), args.batch_size):
            # Preserve entire viewport, avoiding CLIP's default aspect-dependent crop.
            tensors = torch.stack([preprocess(im.resize((224, 224), Image.Resampling.BILINEAR)) for im in images[begin:begin + args.batch_size]]).to(device)
            embedding = teacher.encode_image(tensors).float()
            embedding /= embedding.norm(dim=-1, keepdim=True)
            scores.extend(predictor.layers(embedding).flatten().cpu().tolist())
            if begin % (args.batch_size * 10) == 0:
                print(f"teacher {begin}/{len(images)}", flush=True)
    del teacher, predictor
    if device == "cuda":
        torch.cuda.empty_cache()
    return np.asarray(scores, dtype=np.float32)


def encoder_runtime(args):
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(args.encoder), options, providers=["CPUExecutionProvider"])


def encode_image(runtime, image):
    image = image.resize((224, 224), Image.Resampling.BILINEAR)
    tensor = np.asarray(image, dtype=np.float32).transpose(2, 0, 1)[None] / 127.5 - 1.
    result = runtime.run(None, {runtime.get_inputs()[0].name: tensor})[0].reshape(-1)
    if result.shape != (512,) or not np.isfinite(result).all() or abs(float(np.linalg.norm(result)) - 1) > .001:
        raise ValueError("invalid immutable encoder output")
    return result


def extract(args, rows):
    path = args.output_dir / "frozen-features.npz"
    record_path = args.output_dir / "views.json"
    if path.exists():
        data = np.load(path)
        return data["features"], data["teacher"], json.loads(record_path.read_text(encoding="utf-8"))
    objects = {r["template_id"]: r for r in json.loads(args.object_structures.read_text(encoding="utf-8"))["records"]}
    runtime = encoder_runtime(args)
    images, records, vectors = [], [], []
    preview = args.output_dir / "views"
    preview.mkdir(exist_ok=True)
    for index, row in enumerate(rows):
        source = Path(row["image_path"])
        if sha256(source) != row["image_sha256"]:
            raise ValueError("source changed after freezing protocol")
        with Image.open(source) as raw:
            image = ImageOps.exif_transpose(raw).convert("RGB")
        image.thumbnail((416, 416), Image.Resampling.BILINEAR)
        subject, protection = protected_subject(row, objects)
        scene = source_scene(row)
        variants = list(candidates(image, subject))
        original = features(image, subject, scene)
        # Defects are separate from crop metrics and cannot satisfy crop acceptance.
        small = image.resize((256, 256), Image.Resampling.BILINEAR)
        grid = original[:80].reshape(16, 5)
        if float(grid[:, 3:].mean()) > .025:
            variants.append(("defect_blur", small.filter(ImageFilter.GaussianBlur(5)), subject, [0., 0., 1., 1.]))
        if .15 < original[80] < .7 and original[83] < .05:
            exposed = Image.fromarray(np.clip(np.asarray(small, dtype=np.float32) * 1.7 + 32, 0, 255).astype(np.uint8))
            if features(exposed, subject, scene)[83] - original[83] >= .15:
                variants.append(("defect_overexposure", exposed, subject, [0., 0., 1., 1.]))
        for kind, view, mapped, crop in variants:
            key = f"{index:04d}_{kind}"
            # Feature sampling must happen before teacher's 224 resize.
            vectors.append(np.concatenate((encode_image(runtime, view), features(view, mapped, scene))))
            images.append(view.resize((224, 224), Image.Resampling.BILINEAR))
            records.append({"key": key, "source_id": row["template_id"], "source_group_id": row["source_group_id"],
                            "source_sha256": row["image_sha256"], "split": row["framing_split"], "scene": scene,
                            "kind": kind, "crop": crop, "subject": mapped, "protection_scope": protection,
                            "known_subject_preserved": True, "full_subject_verified": protection == "reviewed_detector_box"})
            if kind == "current" or kind.startswith("crop"):
                view.thumbnail((384, 384))
                view.save(preview / f"{key}.jpg", quality=90)
    # Reversible image compression checks weak teacher ranking stability.
    augmented = []
    for image in images:
        memory = io.BytesIO()
        image.save(memory, format="JPEG", quality=90)
        memory.seek(0)
        augmented.append(Image.open(memory).convert("RGB"))
    teacher = encode_teacher(args, images + augmented).reshape(2, len(images)).T
    vectors = np.asarray(vectors, dtype=np.float32)
    np.savez_compressed(path, features=vectors, teacher=teacher)
    write_json(record_path, records)
    return vectors, teacher, records


def build_pairs(records, teacher):
    groups = defaultdict(list)
    for index, record in enumerate(records):
        groups[record["source_group_id"]].append(index)
    pairs = []
    attempted = Counter()
    for group, indices in groups.items():
        reference = next(i for i in indices if records[i]["kind"] == "current")
        for i in indices:
            if i == reference:
                continue
            record = records[i]
            kind = "crop" if record["kind"].startswith("crop") else record["kind"]
            attempted[f"{record['split']}:{kind}"] += 1
            delta = teacher[i] - teacher[reference]
            if kind == "crop":
                if min(abs(float(delta[0])), abs(float(delta[1]))) < TEACHER_MARGIN or delta[0] * delta[1] <= 0:
                    continue
                sign = 1 if delta[0] > 0 else -1
            else:
                sign = -1  # Applies only to verified degradation generation above.
            pairs.append({"a": i, "b": reference, "sign": sign, "source_group_id": group,
                          "scene": record["scene"], "split": record["split"], "kind": kind,
                          "teacher_deltas": delta.tolist(), "human_label": False})
    return pairs, dict(attempted)


def cluster_metrics(scores, vectors, pairs, split, scene=None, kind="crop"):
    selected = [p for p in pairs if p["split"] == split and (scene is None or p["scene"] == scene)
                and (p["kind"] == kind if kind else True)]
    by_group, baseline_groups = defaultdict(list), defaultdict(list)
    covered = 0
    for p in selected:
        delta = float(scores[p["a"]] - scores[p["b"]]) * p["sign"]
        baseline_delta = (rule_baseline(vectors[p["a"]]) - rule_baseline(vectors[p["b"]])) * p["sign"]
        by_group[p["source_group_id"]].append(float(delta > 0))
        baseline_groups[p["source_group_id"]].append(float(baseline_delta > 0))
        covered += abs(delta) >= SCORE_MARGIN
    values = np.asarray([np.mean(v) for _, v in sorted(by_group.items())])
    baseline_values = np.asarray([np.mean(v) for _, v in sorted(baseline_groups.items())])
    if len(values):
        rng = np.random.default_rng(SEED)
        bootstrap = rng.choice(values, size=(2000, len(values)), replace=True).mean(axis=1)
        agreement, baseline, lower = float(values.mean()), float(baseline_values.mean()), float(np.quantile(bootstrap, .025))
    else:
        agreement, baseline, lower = 0., 0., 0.
    return {"acceptance_cluster_count": len(values), "pair_count": len(selected),
            "pairwise_agreement": agreement, "bootstrap_lower95": lower,
            "baseline_pairwise_agreement": baseline, "coverage": covered / len(selected) if selected else 0.,
            "rejection_rate": 1 - covered / len(selected) if selected else 1.,
            "scene_cluster_counts": dict(Counter(p["scene"] for p in {p["source_group_id"]: p for p in selected}.values()))}


def serialize_head(model, mean, std, protocol_sha, encoder_sha):
    weights = np.concatenate([p.detach().cpu().numpy().reshape(-1) for p in model.parameters()]).astype("<f4")
    return {"schema_version": 1, "feature_protocol": FEATURE_PROTOCOL, "input_dim": INPUT_DIM,
            "hidden_dim": HIDDEN_DIM, "activation": "relu", "weight_encoding": "float32-le-base64",
            "weights_f32_base64": base64.b64encode(weights.tobytes()).decode("ascii"),
            "feature_mean": mean.tolist(), "feature_std": std.tolist(), "training_protocol_sha256": protocol_sha,
            "encoder_sha256": encoder_sha, "crop_preprocessing": "viewport_native_max416_then_crop_bilinear224_v1"}


def train(args, vectors, pairs, records):
    import torch
    from torch import nn
    selection_path = args.output_dir / "selection.json"
    chosen_path = args.output_dir / "selected-head.before-acceptance.json"
    if selection_path.exists() and chosen_path.exists():
        return json.loads(chosen_path.read_text()), json.loads(selection_path.read_text())
    training = [p for p in pairs if p["split"] == "train"]
    if not training:
        raise ValueError("no training preference pairs")
    torch.set_num_threads(4)
    train_indices = [i for i, r in enumerate(records) if r["split"] == "train"]
    mean = vectors[train_indices].mean(axis=0)
    std = np.maximum(vectors[train_indices].std(axis=0), np.float32(.05))
    values = torch.tensor((vectors - mean) / std)
    a, b = torch.tensor([p["a"] for p in training]), torch.tensor([p["b"] for p in training])
    sign = torch.tensor([p["sign"] for p in training], dtype=torch.float32)
    # Equal source contribution; stable crop and defect kinds are separately normalized.
    counts = Counter((p["source_group_id"], p["kind"] == "crop") for p in training)
    weight = torch.tensor([1 / counts[(p["source_group_id"], p["kind"] == "crop")] for p in training])
    evaluated = []
    protocol_sha = sha256(args.output_dir / "frozen-protocol.json")
    for decay in WEIGHT_DECAYS:
        torch.manual_seed(SEED)
        model = nn.Sequential(nn.Linear(INPUT_DIM, HIDDEN_DIM), nn.ReLU(), nn.Linear(HIDDEN_DIM, 1))
        optimizer = torch.optim.AdamW(model.parameters(), lr=.003, weight_decay=decay)
        for _ in range(250):
            scores = model(values).flatten()
            loss = (torch.nn.functional.softplus(-sign * (scores[a] - scores[b])) * weight).sum() / weight.sum()
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
        head = serialize_head(model, mean, std, protocol_sha, sha256(args.encoder))
        scores = predict(head, vectors)
        metrics = cluster_metrics(scores, vectors, pairs, "val")
        evaluated.append((metrics["pairwise_agreement"], decay, head, metrics))
        print(f"validation decay={decay} crop agreement={metrics['pairwise_agreement']:.4f} clusters={metrics['acceptance_cluster_count']}", flush=True)
    _, decay, selected, metrics = max(evaluated, key=lambda entry: (entry[0], entry[1]))
    selection = {"selected_weight_decay": decay, "selection_metric": "validation crop cluster agreement",
                 "candidates": [{"weight_decay": d, "validation": m} for _, d, _, m in evaluated],
                 "acceptance_accessed_for_selection": False, "validation": metrics}
    write_json(chosen_path, selected)
    write_json(selection_path, selection)
    return selected, selection


def accept(args, head, vectors, pairs, records, attempted):
    acceptance_path = args.output_dir / "acceptance.json"
    if acceptance_path.exists():
        raise ValueError("acceptance already executed; use --reuse-completed to read it")
    scores = predict(head, vectors)
    metrics = cluster_metrics(scores, vectors, pairs, "acceptance")
    per_scene = {scene: cluster_metrics(scores, vectors, pairs, "acceptance", scene) for scene in SCENES}
    defects = {kind: cluster_metrics(scores, vectors, pairs, "acceptance", kind=kind) for kind in ("defect_blur", "defect_overexposure")}
    constraints_pass = all(r["known_subject_preserved"] for r in records)
    severe_pass = all(m["pairwise_agreement"] >= m["baseline_pairwise_agreement"] for m in defects.values())
    metrics.update(hard_constraints_passed=constraints_pass, severe_defect_non_regression=severe_pass)
    head_path = args.output_dir / "framing_preference_head.json"
    write_json(head_path, head)
    eligible_scenes = [scene for scene, m in per_scene.items() if m["acceptance_cluster_count"] >= 10
                       and m["pairwise_agreement"] >= .7 and m["bootstrap_lower95"] > .5
                       and m["pairwise_agreement"] > m["baseline_pairwise_agreement"]]
    gates = {"minimum_acceptance_clusters": metrics["acceptance_cluster_count"] >= 30,
             "minimum_scene_clusters": bool(eligible_scenes), "teacher_crop_agreement": metrics["pairwise_agreement"] >= .70,
             "bootstrap_above_chance": metrics["bootstrap_lower95"] > .5,
             "beats_rule_baseline": metrics["pairwise_agreement"] > metrics["baseline_pairwise_agreement"],
             "hard_constraints": constraints_pass, "severe_defect_non_regression": severe_pass,
             "weight_budget": head_path.stat().st_size <= 262144}
    enabled = all(gates.values())
    attempted_accept = attempted.get("acceptance:crop", 0)
    report = {"schema_version": 1, "metrics": metrics, "per_scene": per_scene, "severe_defects": defects,
              "gates": gates, "enabled": enabled, "attempted_pairs": attempted,
              "teacher_stable_crop_coverage": metrics["pair_count"] / attempted_accept if attempted_accept else 0.,
              "feature_file_sha256": sha256(args.output_dir / "frozen-features.npz"),
              "selection_sha256": sha256(args.output_dir / "selection.json"), "acceptance_runs": 1,
              "metric_semantics": "Source-cluster macro agreement with CLIP aesthetic teacher stable under JPEG90; not human aesthetic accuracy. Model ties count incorrect.",
              "subject_scope": "Preserves only available detected boxes; unknown subjects and limbs are not certified. No physical movement prediction.",
              "old_historical_holdouts_excluded": True, "previous_quality_acceptance_excluded": True,
              "encoder_used": True, "encoder_may_have_seen_source": True,
              "independent_crop_encoding": True, "human_aesthetic_labels_used": False}
    policy = {"schema_version": 1, "enabled": enabled, "model_sha256": sha256(head_path),
              "feature_protocol": FEATURE_PROTOCOL, "enabled_scenes": eligible_scenes if enabled else [],
              "minimum_score_margin": SCORE_MARGIN, "metrics": metrics, "gates": gates,
              "reasons": [name for name, passed in gates.items() if not passed], "weak_teacher_only": True,
              "historical_holdout_excluded": True, "previous_quality_acceptance_excluded": True,
              "crop_advice_enabled": False, "physical_movement_prediction_enabled": False,
              "training_protocol_sha256": sha256(args.output_dir / "frozen-protocol.json"),
              "encoder_sha256": sha256(args.encoder),
              "crop_preprocessing": "viewport_native_max416_then_crop_bilinear224_v1",
              "acceptance_report_sha256": None}
    write_json(acceptance_path, report)
    policy["acceptance_report_sha256"] = sha256(acceptance_path)
    write_json(args.output_dir / "framing_preference_policy.json", policy)
    # Deterministic RGB formula can be recreated without shipping private photos.
    height, width = 73, 91
    y, x = np.mgrid[:height, :width]
    rgb = np.stack(((x * 7 + y * 3) % 256, (x * 2 + y * 11) % 256, (x * 13 + y * 5) % 256), axis=-1).astype(np.uint8)
    fixture_image = Image.fromarray(rgb)
    fixture_features = np.concatenate((encode_image(encoder_runtime(args), fixture_image),
                                       features(fixture_image, [.2, .1, .7, .8], "food")))
    fixture = {"schema_version": 1, "width": width, "height": height,
               "rgb_formula": ["(x*7+y*3)%256", "(x*2+y*11)%256", "(x*13+y*5)%256"],
               "subject_ltrb": [.2, .1, .7, .8], "scene": "food", "features": fixture_features.tolist(),
               "score": float(predict(head, fixture_features)), "model_sha256": sha256(head_path),
               "feature_protocol": FEATURE_PROTOCOL, "score_absolute_tolerance": .0002}
    write_json(args.output_dir / "framing_preference_fixture.json", fixture)
    print(json.dumps({"enabled": enabled, "metrics": metrics, "reasons": policy["reasons"]}), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source-manifest", "previous-protocol", "previous-views", "object-structures", "encoder", "vendor-dir", "deps-dir", "output-dir"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--reuse-completed", action="store_true")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name in ("HF_HOME", "TORCH_HOME", "XDG_CACHE_HOME", "TEMP", "TMP"):
        target = args.output_dir / "cache" / name.lower()
        target.mkdir(parents=True, exist_ok=True)
        os.environ[name] = str(target.resolve())
    completed = args.output_dir / "acceptance.json"
    if completed.exists():
        if not args.reuse_completed:
            raise ValueError("acceptance is immutable; use --reuse-completed")
        print(completed.read_text(encoding="utf-8"))
        return
    rows, _ = prepare(args)
    print(f"frozen source clusters={len(rows)}", flush=True)
    vectors, teacher, records = extract(args, rows)
    pairs, attempted = build_pairs(records, teacher)
    write_json(args.output_dir / "pairs.json", pairs)
    head, _ = train(args, vectors, pairs, records)
    accept(args, head, vectors, pairs, records, attempted)


if __name__ == "__main__":
    main()
