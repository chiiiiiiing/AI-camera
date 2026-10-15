#!/usr/bin/env python3
"""Bounded MobileNetV3-Small transfer experiment using train-only ridge heads.

Only train and validation are opened. The fixed candidate set is 0.01, 0.1, 1.0;
validation selects the head, while deployment still requires separate ONNX/int8
checks, a frozen held-out evaluation, and Android performance measurements.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import platform
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import numpy as np
import torch
import torchvision
from torch.nn import functional as F
from torchvision.models import MobileNet_V3_Small_Weights, mobilenet_v3_small

from evaluate_retrieval import evaluate_retrieval
from export_student_model import try_export_onnx
from optimize_student_retrieval import (
    load_images, load_teachers, prepare_batch, read_rows, sha256,
    validate_training_splits, write_json,
)
from student_embedding_model import build_student_model, count_parameters


RIDGE_STRENGTHS = (0.01, 0.1, 1.0)


def fit_ridge(features: np.ndarray, targets: np.ndarray, strength: float) -> tuple[np.ndarray, np.ndarray]:
    """Fit an intercept without regularizing it; training arrays only.

    Objective: sum of squared errors + strength * sum(weight ** 2).
    Float64 solve avoids conditioning loss with only 579 training images.
    """
    x, y = np.asarray(features, dtype=np.float64), np.asarray(targets, dtype=np.float64)
    if x.ndim != 2 or y.ndim != 2 or len(x) != len(y) or len(x) < 2:
        raise ValueError("features and targets must have matching nonempty sample axes")
    if x.shape[1] == 0 or y.shape[1] == 0 or not np.isfinite(x).all() or not np.isfinite(y).all():
        raise ValueError("features and targets must have finite, nonempty dimensions")
    if not math.isfinite(strength) or strength <= 0:
        raise ValueError("ridge strength must be positive and finite")
    x_mean, y_mean = x.mean(axis=0), y.mean(axis=0)
    centered = x - x_mean
    gram = centered.T @ centered + strength * np.eye(x.shape[1])
    coefficients = np.linalg.solve(gram, centered.T @ (y - y_mean))
    return coefficients.T.astype(np.float32), (y_mean - x_mean @ coefficients).astype(np.float32)


def apply_head(features: np.ndarray, weight: np.ndarray, bias: np.ndarray) -> np.ndarray:
    values = features @ weight.T + bias
    norms = np.linalg.norm(values.astype(np.float64), axis=1, keepdims=True)
    if not np.isfinite(values).all() or np.any(norms < 1e-12):
        raise ValueError("ridge head produced invalid embeddings")
    return (values / norms).astype(np.float32)


def metric_vector(report: dict[str, Any]) -> tuple[float, float, float, float]:
    raw = report["raw"]
    return (
        raw["scene_macro_recall_at_k"]["1"],
        raw["scene_macro_recall_at_k"]["5"],
        raw["scene_micro_recall_at_k"]["5"],
        raw["teacher_top5_overlap"],
    )


def eligible_against_baseline(report: dict[str, Any], baseline: dict[str, Any]) -> bool:
    candidate, reference = metric_vector(report), metric_vector(baseline)
    return all(a + 1e-12 >= b for a, b in zip(candidate, reference)) and any(
        a > b + 1e-12 for a, b in zip(candidate, reference)
    )


@torch.inference_mode()
def extract_features(model: Any, images: torch.Tensor, device: str, batch_size: int) -> np.ndarray:
    model.eval()
    values = [model.encode_features(prepare_batch(batch, device)).cpu().numpy()
              for batch in images.split(batch_size)]
    features = np.concatenate(values)
    if not np.isfinite(features).all() or not np.allclose(np.linalg.norm(features, axis=1), 1.0, atol=1e-5):
        raise ValueError("pretrained backbone produced invalid features")
    return features


def train_transfer(args: argparse.Namespace) -> dict[str, Any]:
    start = time.monotonic()
    if args.output_dir.exists() and any(args.output_dir.iterdir()):
        raise ValueError("output directory must be empty")
    if args.batch_size <= 0 or args.cpu_threads <= 0:
        raise ValueError("batch_size and cpu_threads must be positive")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    args.torch_cache.mkdir(parents=True, exist_ok=True)
    torch.hub.set_dir(str(args.torch_cache.resolve()))
    torch.manual_seed(20261015)
    np.random.seed(20261015)
    torch.set_num_threads(args.cpu_threads)
    device = ("cuda" if torch.cuda.is_available() else "cpu") if args.device == "auto" else args.device

    rows = {split: read_rows(args.dataset_dir / f"{split}.jsonl") for split in ("train", "val")}
    validation = validate_training_splits(rows)
    write_json(args.output_dir / "dataset-validation.json", validation)
    dataset_hashes = {split: sha256(args.dataset_dir / f"{split}.jsonl") for split in rows}
    baseline_document = json.loads(args.baseline_report.read_text(encoding="utf-8"))
    if baseline_document.get("dataset_sha256") != dataset_hashes:
        raise ValueError("baseline report must use exactly the same train/val manifests")
    if baseline_document.get("splits") != {split: len(records) for split, records in rows.items()}:
        raise ValueError("baseline report split counts do not match")
    baseline = baseline_document["models"]["old_int8"]["retrieval"]
    checkpoint = torch.load(args.projection_checkpoint, map_location="cpu", weights_only=True)
    teachers = {split: load_teachers(records) for split, records in rows.items()}
    projection = checkpoint["projection"]
    targets = F.normalize((torch.from_numpy(teachers["train"]) - projection["mean"].float())
                          @ projection["components"].float().T, dim=1).numpy()
    if not np.isfinite(targets).all() or np.any(np.linalg.norm(targets, axis=1) < 1e-8):
        raise ValueError("invalid projected teacher targets")

    config = {"architecture": "mobilenet_v3_small_retrieval", "output_dim": targets.shape[1],
              "image_size": 224, "width_mult": 1.0, "dropout": 0.0}
    model = build_student_model(config)
    weights = MobileNet_V3_Small_Weights.IMAGENET1K_V1
    pretrained = mobilenet_v3_small(weights=weights)
    model.features.load_state_dict(pretrained.features.state_dict(), strict=True)
    del pretrained
    weight_file = args.torch_cache / "checkpoints" / Path(urlparse(weights.url).path).name
    source = {
        "weights_enum": "MobileNet_V3_Small_Weights.IMAGENET1K_V1", "url": weights.url,
        "sha256": sha256(weight_file), "bytes": weight_file.stat().st_size,
        "pretraining_dataset": "ImageNet-1K",
        "implementation_license": "torchvision BSD-3-Clause (implementation, not a license for source images)",
        "implementation_license_url": "https://github.com/pytorch/vision/blob/main/LICENSE",
        "documentation_url": "https://docs.pytorch.org/vision/stable/models/generated/torchvision.models.mobilenet_v3_small.html",
        "image_normalization": "app RGB [-1,1] -> RGB [0,1] -> ImageNet mean/std inside model",
        "resize": "EXIF corrected RGB, direct 224x224 bilinear; no crop or mirror",
        "feature_normalization": "L2 of 576 pooled feature channels",
        "classifier_discarded": True, "backbone_frozen": True,
    }
    model.to(device).eval()
    images = {split: load_images(records, 224) for split, records in rows.items()}
    features = {split: extract_features(model, values, device, args.batch_size)
                for split, values in images.items()}
    np.savez_compressed(args.output_dir / "frozen-features.npz", **features)
    candidates = []
    heads = {}
    for strength in RIDGE_STRENGTHS:
        weight, bias = fit_ridge(features["train"], targets, strength)
        heads[strength] = (weight, bias)
        gallery, queries = (apply_head(features[split], weight, bias) for split in ("train", "val"))
        report = evaluate_retrieval(queries, gallery, rows["val"], rows["train"],
                                   teacher_query_embeddings=teachers["val"],
                                   teacher_gallery_embeddings=teachers["train"], compare_android_rerank=True)
        record = {"ridge_strength": strength, "eligible_against_old_int8": eligible_against_baseline(report, baseline),
                  "validation": report}
        candidates.append(record)
        write_json(args.output_dir / f"validation-ridge-{strength:g}.json", record)
        print(json.dumps({"ridge_strength": strength, "metrics": metric_vector(report),
                          "eligible": record["eligible_against_old_int8"]}), flush=True)
    eligible = [candidate for candidate in candidates if candidate["eligible_against_old_int8"]]
    selected = max(eligible or candidates, key=lambda entry: (
        metric_vector(entry["validation"])[3], *metric_vector(entry["validation"])[:3]
    ))
    weight, bias = heads[selected["ridge_strength"]]
    with torch.no_grad():
        model.head.weight.copy_(torch.from_numpy(weight).to(device))
        model.head.bias.copy_(torch.from_numpy(bias).to(device))
    model.cpu().eval()
    provenance = {
        "pretrained_backbone": source, "projection_checkpoint_sha256": sha256(args.projection_checkpoint),
        "teacher_projection": "existing checkpoint projection fitted on original train split; reused without validation refit",
        "ridge_candidates": list(RIDGE_STRENGTHS), "selected_ridge_strength": selected["ridge_strength"],
        "fit_split": "train", "selection_split": "val", "test_split_read": False,
        "selection_policy": "no regression in macro scene recall@1/@5, micro scene recall@5, teacher top5 overlap versus old_int8; then maximize teacher overlap",
        "dataset_sha256": dataset_hashes,
        "dataset_validation": validation, "baseline_report_sha256": sha256(args.baseline_report),
    }
    selected_checkpoint = {"model": config, "model_state": model.state_dict(),
                           "projection": copy.deepcopy(projection), "transfer_training": provenance}
    torch.save(selected_checkpoint, args.output_dir / "checkpoint.selected.pt")
    export_dir = args.output_dir / "export"
    export_dir.mkdir()
    export = try_export_onnx(model, torch.zeros(1, 3, 224, 224), export_dir)
    if export["status"] == "ready":
        export["sha256"] = sha256(Path(export["path"]))
    summary = {
        "schema_version": 1, "model": config, "parameter_count": count_parameters(model),
        "frozen_feature_parameter_count": count_parameters(model.features), "fitted_head_parameter_count": count_parameters(model.head),
        "baseline_validation": baseline, "candidates": candidates, "selected": selected,
        "eligible_for_further_validation": bool(eligible), "deployment_ready": False,
        "remaining_checks": ["ONNX correctness", "balanced train-only static int8 calibration", "int8 retrieval non-regression", "frozen held-out evaluation", "mid-range Android latency and thermal behavior"],
        "export": export, "checkpoint_sha256": sha256(args.output_dir / "checkpoint.selected.pt"),
        "provenance": provenance, "device": device, "elapsed_seconds": time.monotonic() - start,
        "versions": {"python": platform.python_version(), "torch": str(torch.__version__),
                     "torchvision": str(torchvision.__version__), "numpy": np.__version__},
    }
    write_json(args.output_dir / "summary.json", summary)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", required=True, type=Path)
    parser.add_argument("--projection-checkpoint", required=True, type=Path)
    parser.add_argument("--baseline-report", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--torch-cache", required=True, type=Path)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--cpu-threads", type=int, default=4)
    train_transfer(parser.parse_args())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
