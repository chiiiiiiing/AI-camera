#!/usr/bin/env python3
"""Compare ONNX encoders on train-gallery/validation-query retrieval, never test.

Desktop CPU inference timings are not estimates of Android latency. Paired
embedding cosine measures numerical drift; retrieval metrics are computed
separately with each model's own query and gallery embeddings.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import platform
import re
import time
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageOps

from evaluate_retrieval import evaluate_retrieval
from quantize_onnx_static import load_jsonl, model_contract, require_disjoint, require_split, sha256_file


def write_json(path: Path, value: Any) -> None:
    # Report hashes must survive Git's text normalization on Windows and Linux.
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                    encoding="utf-8", newline="\n")


def load_images(rows: list[dict[str, Any]], size: int) -> np.ndarray:
    """Same EXIF, RGB, full-frame PIL bilinear resize as optimize_student_retrieval."""
    images = []
    for row in rows:
        path = Path(row["source_path"])
        if not path.is_absolute():
            raise ValueError("benchmark requires prepared absolute source paths")
        if sha256_file(path) != row.get("source_sha256"):
            raise ValueError(f"source image hash mismatch: {row['photo_id']}")
        with Image.open(path) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            pixels = np.asarray(image.resize((size, size), Image.Resampling.BILINEAR)).copy()
        images.append(pixels.transpose(2, 0, 1))
    return np.stack(images)  # Reused lossless uint8 cache, not a recompressed JPEG cache.


def prepare_batch(images: np.ndarray) -> np.ndarray:
    values = images.astype(np.float32) / np.float32(255.0)
    return np.ascontiguousarray((values - np.float32(0.5)) / np.float32(0.5))


def load_teachers(rows: list[dict[str, Any]]) -> np.ndarray:
    vectors = []
    for row in rows:
        info = row["teacher_embedding"]
        path = Path(info["path"])
        expected_hash = info.get("sha256") or info.get("embedding_sha256")
        if expected_hash and sha256_file(path) != expected_hash:
            raise ValueError(f"teacher hash mismatch: {row['photo_id']}")
        vector = np.frombuffer(path.read_bytes(), dtype="<f4").copy()
        if len(vector) != int(info["dimension"]) or not np.isfinite(vector).all():
            raise ValueError(f"invalid teacher vector: {row['photo_id']}")
        norm = np.linalg.norm(vector.astype(np.float64))
        if norm <= 0:
            raise ValueError("zero teacher embedding")
        vectors.append(vector / norm)
    return np.stack(vectors).astype(np.float32)


def encode(session: Any, images: np.ndarray, batch_size: int) -> tuple[np.ndarray, float]:
    input_info, output_info, _ = model_contract(session, None)
    if input_info.shape[0] == 1:
        batch_size = 1
    vectors = []
    start = time.perf_counter()
    for offset in range(0, len(images), batch_size):
        batch = prepare_batch(images[offset:offset + batch_size])
        output = np.asarray(session.run([output_info.name], {input_info.name: batch})[0])
        if output.shape != (len(batch), output_info.shape[1]) or not np.isfinite(output).all():
            raise ValueError("model emitted invalid embedding shape or non-finite values")
        if np.any(np.linalg.norm(output.astype(np.float64), axis=1) <= 1e-12):
            raise ValueError("model emitted a zero embedding")
        vectors.append(output)
    return np.concatenate(vectors), time.perf_counter() - start


def latency(session: Any, images: np.ndarray, repeats: int, warmup: int) -> dict[str, Any]:
    input_name, output_name = session.get_inputs()[0].name, session.get_outputs()[0].name
    inputs = [prepare_batch(images[index:index + 1]) for index in range(min(16, len(images)))]
    times = []
    for index in range(warmup + repeats):
        feed = {input_name: inputs[index % len(inputs)]}
        start = time.perf_counter()
        session.run([output_name], feed)
        elapsed = (time.perf_counter() - start) * 1000.0
        if index >= warmup:
            times.append(elapsed)
    return {"scope": "desktop_cpu_batch1_session_run_only", "android_latency_measured": False,
            "preprocessing_timed": False, "warmup_runs": warmup, "measured_runs": repeats,
            "input_images": len(inputs), "p50_ms": float(np.percentile(times, 50)),
            "p90_ms": float(np.percentile(times, 90)), "mean_ms": float(np.mean(times))}


def paired_cosine(reference: np.ndarray, candidate: np.ndarray) -> dict[str, Any]:
    if reference.shape != candidate.shape:
        raise ValueError("paired numerical comparison requires identical embedding shapes")
    left, right = reference.astype(np.float64), candidate.astype(np.float64)
    left /= np.linalg.norm(left, axis=1, keepdims=True)
    right /= np.linalg.norm(right, axis=1, keepdims=True)
    values = np.clip(np.sum(left * right, axis=1), -1.0, 1.0)
    return {"scope": "same_image_numerical_consistency_not_retrieval_quality", "count": len(values),
            "mean": float(np.mean(values)), "minimum": float(np.min(values)),
            "p05": float(np.percentile(values, 5))}


def non_regression(candidate: dict[str, Any], baseline: dict[str, Any]) -> dict[str, Any]:
    raw, reference = candidate["raw"], baseline["raw"]
    deltas = {f"scene_macro_recall_at_{k}": raw["scene_macro_recall_at_k"][k] -
              reference["scene_macro_recall_at_k"][k] for k in ("1", "5")}
    deltas["scene_micro_recall_at_5"] = raw["scene_micro_recall_at_k"]["5"] - reference["scene_micro_recall_at_k"]["5"]
    deltas["teacher_top5_overlap"] = raw["teacher_top5_overlap"] - reference["teacher_top5_overlap"]
    metrics = [value for report in (raw, reference) for value in
               (report["scene_macro_recall_at_k"]["1"], report["scene_macro_recall_at_k"]["5"],
                report["scene_micro_recall_at_k"]["5"], report["teacher_top5_overlap"])]
    valid = all(math.isfinite(value) and 0 <= value <= 1 for value in metrics)
    return {"passed": valid and all(value >= -1e-12 for value in deltas.values()), "deltas": deltas}


def benchmark(args: argparse.Namespace) -> dict[str, Any]:
    models = {}
    for specification in args.model:
        name, separator, path = specification.partition("=")
        if not separator or not re.fullmatch(r"[A-Za-z0-9_-]+", name) or name in models:
            raise ValueError("--model must be a unique simple NAME=PATH")
        models[name] = Path(path).resolve()
    if args.reference not in models or args.deployment_baseline not in models:
        raise ValueError("reference and deployment baseline must be named models")
    if min(args.threads, args.batch_size, args.repeats) <= 0 or args.warmup < 0:
        raise ValueError("threads, batch size and repeats must be positive; warmup must be nonnegative")
    if args.output_dir.exists() and any(args.output_dir.iterdir()):
        raise ValueError("output directory must be empty; preserve previous experiment results")
    rows = {split: require_split(load_jsonl(args.dataset_dir / f"{split}.jsonl"), split)
            for split in ("train", "val")}
    require_disjoint(rows["train"], rows["val"], args.dataset_dir, args.dataset_dir)
    options = ort.SessionOptions()
    options.intra_op_num_threads = args.threads
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    sizes = {}
    for name, path in models.items():
        session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
        sizes[name] = model_contract(session, None)[2]
        del session
    if len(set(sizes.values())) != 1:
        raise ValueError("models must share an image size for this controlled comparison")
    image_size = next(iter(sizes.values()))
    images = {split: load_images(records, image_size) for split, records in rows.items()}
    teachers = {split: load_teachers(records) for split, records in rows.items()}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    reports, embeddings = {}, {}
    for name, path in models.items():
        session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
        speed = latency(session, images["val"], args.repeats, args.warmup)
        gallery, gallery_seconds = encode(session, images["train"], args.batch_size)
        queries, query_seconds = encode(session, images["val"], args.batch_size)
        report = evaluate_retrieval(queries, gallery, rows["val"], rows["train"],
                                    teacher_query_embeddings=teachers["val"],
                                    teacher_gallery_embeddings=teachers["train"], compare_android_rerank=True)
        reports[name] = {"path": str(path), "sha256": sha256_file(path), "bytes": path.stat().st_size,
                         "retrieval": report, "desktop_cpu_latency": speed,
                         "encode_with_preprocessing_seconds": {"train": gallery_seconds, "val": query_seconds},
                         "providers": session.get_providers()}
        embeddings[name] = {"train": gallery, "val": queries}
        np.savez_compressed(args.output_dir / f"{name}.embeddings.npz", gallery=gallery, queries=queries,
                            gallery_photo_ids=np.asarray([row["photo_id"] for row in rows["train"]]),
                            query_photo_ids=np.asarray([row["photo_id"] for row in rows["val"]]))
        write_json(args.output_dir / f"{name}.json", reports[name])
        print(json.dumps({"model": name, "event": "encoded", "bytes": path.stat().st_size,
                          "scene_macro": report["raw"]["scene_macro_recall_at_k"],
                          "teacher_top5_overlap": report["raw"]["teacher_top5_overlap"]}), flush=True)
        del session
    for name, report in reports.items():
        report["paired_cosine_vs_fp32_reference"] = {
            split: paired_cosine(embeddings[args.reference][split], embeddings[name][split])
            for split in ("train", "val")}
        report["validation_non_regression"] = {
            "vs_fp32_reference": non_regression(report["retrieval"], reports[args.reference]["retrieval"]),
            "vs_deployed_model": non_regression(report["retrieval"], reports[args.deployment_baseline]["retrieval"])}
        report["validation_retrieval_gate_passed"] = all(
            item["passed"] for item in report["validation_non_regression"].values())
        write_json(args.output_dir / f"{name}.json", report)
    summary = {"schema_version": 1, "reference": args.reference, "deployment_baseline": args.deployment_baseline,
               "models": reports, "splits": {split: len(records) for split, records in rows.items()},
               "dataset_sha256": {split: sha256_file(args.dataset_dir / f"{split}.jsonl") for split in rows},
               "test_data_read": False, "production_assets_modified": False, "deployment_ready": False,
               "selection_scope": "validation only; numerical consistency and device validation remain separate gates",
               "hardware": {"platform": platform.platform(), "processor": platform.processor(),
                            "logical_cpu_count": os.cpu_count(), "intra_op_threads": args.threads,
                            "inter_op_threads": 1, "onnxruntime": ort.__version__},
               "preprocessing": {"image_size": image_size, "exif_transpose": True, "color": "RGB",
                                 "resize": "full-frame PIL bilinear", "normalization": "(rgb/255 - 0.5)/0.5"}}
    write_json(args.output_dir / "summary.json", summary)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--model", action="append", required=True, help="NAME=PATH; repeat for each encoder")
    parser.add_argument("--reference", default="fp32")
    parser.add_argument("--deployment-baseline", default="old_int8")
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--repeats", type=int, default=50)
    parser.add_argument("--warmup", type=int, default=5)
    args = parser.parse_args()
    benchmark(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
