#!/usr/bin/env python3
"""Static-quantize a student ONNX model with real calibration images.

On Windows, older ONNX shape inference may reject non-ASCII model paths. Run
from the model directory with ASCII relative model filenames in that case;
metadata and image paths may remain absolute. Preserve the verification report
even when the CLI returns 2 for a failed or missing validation gate.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort
from onnxruntime.quantization import (
    CalibrationDataReader,
    CalibrationMethod,
    QuantFormat,
    QuantType,
    quantize_static,
)
from PIL import Image, ImageOps

from train_student_embedding import resolve_path, transform_for_split


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"{path}:{line_number}: line is not an object")
            rows.append(value)
    return rows


def file_size(path: Path) -> int:
    return path.stat().st_size if path.exists() else 0


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_split(
    rows: list[dict[str, Any]], expected: str, declared_split: str | None = None,
) -> list[dict[str, Any]]:
    """Accept only a known split; a declaration may fill missing provenance, never override it."""
    if not rows:
        raise ValueError(f"{expected} manifest contains no rows")
    if declared_split is not None and declared_split != expected:
        raise ValueError(f"expected an explicit {expected} split declaration")
    seen: set[str] = set()
    for row in rows:
        photo_id = str(row.get("photo_id") or "").strip()
        if not photo_id or photo_id in seen:
            raise ValueError(f"{expected} manifest has missing or duplicate photo_id: {photo_id!r}")
        seen.add(photo_id)
        split = str(row.get("split") or "").strip().lower()
        if split in ("", "unknown"):
            split = declared_split or "unknown"
        if split != expected:
            raise ValueError(
                f"{photo_id}: expected split={expected}, got {split}; use a dedicated {expected} manifest "
                f"and explicitly declare its split only when the rows lack provenance"
            )
        if not str(row.get("source_path") or "").strip():
            raise ValueError(f"{photo_id}: missing source_path")
    return rows


def stratum(row: dict[str, Any]) -> tuple[str, str]:
    return (str(row.get("photo_type") or "unknown"), str(row.get("scene_category") or "unknown"))


def distribution(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "photo_type": dict(sorted(Counter(stratum(row)[0] for row in rows).items())),
        "scene_category": dict(sorted(Counter(stratum(row)[1] for row in rows).items())),
        "strata": [
            {"photo_type": key[0], "scene_category": key[1], "count": count}
            for key, count in sorted(Counter(stratum(row) for row in rows).items())
        ],
    }


def stratified_sample(rows: list[dict[str, Any]], limit: int, seed: int) -> list[dict[str, Any]]:
    if limit <= 0:
        raise ValueError("calibration_items must be positive")
    groups: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in sorted(rows, key=lambda item: str(item["photo_id"])):
        groups[stratum(row)].append(row)
    if limit < len(groups):
        raise ValueError(f"calibration_items={limit} cannot cover all {len(groups)} strata")
    rng = random.Random(seed)
    keys = sorted(groups)
    for key in keys:
        rng.shuffle(groups[key])
    rng.shuffle(keys)
    selected: list[dict[str, Any]] = []
    # Equal coverage first; exhausted rare strata give their remaining slots to the rest.
    while len(selected) < min(limit, len(rows)):
        for key in keys:
            if groups[key] and len(selected) < limit:
                selected.append(groups[key].pop())
    return selected


def require_disjoint(
    train_rows: list[dict[str, Any]], val_rows: list[dict[str, Any]],
    train_base: Path, val_base: Path,
) -> None:
    for field in ("photo_id", "template_id", "source_sha256", "source_path", "source_group_id"):
        def identities(rows: list[dict[str, Any]], base: Path) -> set[str]:
            values = set()
            for row in rows:
                value = str(row.get(field) or "").strip()
                if not value:
                    continue
                if field == "source_path":
                    value = os.path.normcase(str(resolve_path(value, base)))
                if field == "source_sha256":
                    value = value.lower()
                values.add(value)
            return values
        overlap = identities(train_rows, train_base) & identities(val_rows, val_base)
        if overlap:
            raise ValueError(f"train/val overlap in {field}: {sorted(overlap)[0]}")


def prepare_image(record: dict[str, Any], image_size: int, base_dir: Path) -> np.ndarray:
    image_path = resolve_path(str(record.get("source_path") or ""), base_dir)
    transform = transform_for_split(image_size, train=False)
    with Image.open(image_path) as image:
        image = ImageOps.exif_transpose(image).convert("RGB")
        tensor = transform(image)
    return tensor.unsqueeze(0).numpy().astype(np.float32)


class ImageCalibrationDataReader(CalibrationDataReader):
    def __init__(
        self,
        rows: list[dict[str, Any]],
        input_name: str,
        image_size: int,
        batch_size: int,
        base_dir: Path,
    ) -> None:
        self.rows = rows
        self.input_name = input_name
        self.image_size = image_size
        self.batch_size = batch_size
        self.base_dir = base_dir
        self.cursor = 0

    def get_next(self) -> dict[str, np.ndarray] | None:
        if self.cursor >= len(self.rows):
            return None
        batch_rows = self.rows[self.cursor:self.cursor + self.batch_size]
        self.cursor += len(batch_rows)
        batch = np.concatenate(
            [prepare_image(row, self.image_size, self.base_dir) for row in batch_rows],
            axis=0,
        )
        return {self.input_name: batch}


def compare_outputs(
    fp32_model: Path, int8_model: Path, rows: list[dict[str, Any]], image_size: int,
    base_dir: Path | None = None, min_cosine: float = 0.95,
    min_mean_cosine: float = 0.98, norm_tolerance: float = 0.02,
) -> dict[str, Any]:
    """Measure paired numerical consistency, not retrieval accuracy or relevance."""
    if not -1.0 <= min_cosine <= min_mean_cosine <= 1.0:
        raise ValueError("cosine thresholds must satisfy -1 <= minimum <= mean <= 1")
    if not np.isfinite(norm_tolerance) or norm_tolerance < 0.0:
        raise ValueError("norm_tolerance must be finite and nonnegative")
    report: dict[str, Any] = {
        "status": "not_validated", "scope": "paired_embedding_numerical_consistency",
        "retrieval_quality": "not_evaluated", "validation_items": len(rows),
        "validation_photo_ids": [row["photo_id"] for row in rows],
        "distribution": distribution(rows),
        "thresholds": {"min_cosine": min_cosine, "min_mean_cosine": min_mean_cosine,
                       "max_unit_norm_error": norm_tolerance, "min_validation_items": 2},
    }
    if len(rows) < 2:
        return {**report, "reason": "at least two independent validation images are required"}
    providers = ["CPUExecutionProvider"]
    fp32_session = ort.InferenceSession(str(fp32_model), providers=providers)
    int8_session = ort.InferenceSession(str(int8_model), providers=providers)
    samples: list[dict[str, Any]] = []
    failures: list[dict[str, str]] = []
    expected_dim = fp32_session.get_outputs()[0].shape[-1]
    for row in rows:
        sample = prepare_image(row, image_size, base_dir or Path.cwd())
        fp32_output = np.asarray(fp32_session.run(None, {fp32_session.get_inputs()[0].name: sample})[0], dtype=np.float64)
        int8_output = np.asarray(int8_session.run(None, {int8_session.get_inputs()[0].name: sample})[0], dtype=np.float64)
        reason = None
        if (fp32_output.ndim != 2 or fp32_output.shape[0] != 1 or
                fp32_output.shape != int8_output.shape or fp32_output.shape[1] == 0 or
                (isinstance(expected_dim, int) and fp32_output.shape[1] != expected_dim)):
            reason = "embedding shape mismatch"
        elif not np.isfinite(fp32_output).all() or not np.isfinite(int8_output).all():
            reason = "non-finite embedding"
        else:
            fp32_norm, int8_norm = float(np.linalg.norm(fp32_output)), float(np.linalg.norm(int8_output))
            if min(fp32_norm, int8_norm) <= 1e-12:
                reason = "zero embedding"
            else:
                cosine = float(np.clip(np.sum(fp32_output * int8_output) / (fp32_norm * int8_norm), -1.0, 1.0))
                samples.append({"photo_id": row["photo_id"], "photo_type": stratum(row)[0],
                                "scene_category": stratum(row)[1], "cosine": cosine,
                                "fp32_l2_norm": fp32_norm, "int8_l2_norm": int8_norm})
                if cosine < min_cosine:
                    reason = "cosine below minimum"
                elif max(abs(fp32_norm - 1.0), abs(int8_norm - 1.0)) > norm_tolerance:
                    reason = "embedding is not unit-normalized"
        if reason:
            failures.append({"photo_id": str(row["photo_id"]), "reason": reason})

    def summarize(items: list[dict[str, Any]]) -> dict[str, Any]:
        cosines = [item["cosine"] for item in items]
        return {"count": len(items), "cosine_mean": float(np.mean(cosines)),
                "cosine_min": min(cosines), "cosine_p05": float(np.percentile(cosines, 5)),
                "max_unit_norm_error": max(abs(item[key] - 1.0) for item in items
                                           for key in ("fp32_l2_norm", "int8_l2_norm"))}

    metrics = summarize(samples) if samples else None
    strata = sorted({(item["photo_type"], item["scene_category"]) for item in samples})
    per_stratum = [{"photo_type": key[0], "scene_category": key[1],
                    **summarize([item for item in samples if stratum(item) == key])} for key in strata]
    mean_passed = metrics is not None and metrics["cosine_mean"] >= min_mean_cosine
    return {**report, "status": "passed" if not failures and mean_passed else "rejected",
            "metrics": metrics, "per_stratum": per_stratum, "samples": samples,
            "failures": failures, "mean_threshold_passed": mean_passed}


def model_contract(session: Any, image_size: int | None) -> tuple[Any, Any, int]:
    inputs, outputs = session.get_inputs(), session.get_outputs()
    if len(inputs) != 1 or len(outputs) != 1:
        raise ValueError("student model must have exactly one input and one output")
    input_info, output_info = inputs[0], outputs[0]
    shape = input_info.shape
    if (input_info.type != "tensor(float)" or len(shape) != 4 or shape[1] != 3 or
            not isinstance(shape[2], int) or shape[2] <= 0 or shape[2] != shape[3]):
        raise ValueError(f"expected float32 NCHW RGB square input, got {shape}")
    if isinstance(shape[0], int) and shape[0] != 1:
        raise ValueError("Android embedding model must support batch size one")
    if image_size is not None and image_size != shape[2]:
        raise ValueError(f"image_size {image_size} does not match model input {shape[2]}")
    if (output_info.type != "tensor(float)" or len(output_info.shape) != 2 or
            not isinstance(output_info.shape[1], int) or output_info.shape[1] <= 0):
        raise ValueError(f"expected float32 embedding with a known dimension, got {output_info.shape}")
    return input_info, output_info, shape[2]


def quantize_android_onnx(
    onnx_model: Path,
    metadata: Path,
    output_model: Path,
    manifest_output: Path,
    image_size: int | None = None,
    calibration_items: int = 128,
    batch_size: int = 16,
    validation_manifest: Path | None = None,
    calibration_split: str | None = None,
    validation_split: str | None = None,
    seed: int = 20260718,
    min_cosine: float = 0.95,
    min_mean_cosine: float = 0.98,
    norm_tolerance: float = 0.02,
    quantize_ops: list[str] | None = None,
) -> dict[str, Any]:
    if calibration_items <= 0:
        raise ValueError("calibration_items must be positive")
    if batch_size <= 0:
        raise ValueError("batch_size must be positive")
    selected_ops = ["Conv", "Gemm", "MatMul"] if quantize_ops is None else list(quantize_ops)
    if not selected_ops or len(set(selected_ops)) != len(selected_ops) or any(
        name not in {"Conv", "Gemm", "MatMul"} for name in selected_ops
    ):
        raise ValueError("quantize_ops must be a nonempty unique subset of Conv, Gemm, MatMul")

    train_rows = require_split(load_jsonl(metadata), "train", calibration_split)
    rows = stratified_sample(train_rows, calibration_items, seed)
    validation_rows = []
    if validation_manifest is not None:
        validation_rows = require_split(load_jsonl(validation_manifest), "val", validation_split)
        require_disjoint(train_rows, validation_rows, metadata.parent, validation_manifest.parent)
        if len(validation_rows) < 2:
            raise ValueError("validation manifest must contain at least two independent images")
    elif validation_split is not None:
        raise ValueError("validation_split requires validation_manifest")
    # Validate thresholds before spending time quantizing or writing a model.
    compare_outputs(onnx_model, output_model, [], 1, min_cosine=min_cosine,
                    min_mean_cosine=min_mean_cosine, norm_tolerance=norm_tolerance)

    onnx.checker.check_model(str(onnx_model))
    session = ort.InferenceSession(str(onnx_model), providers=["CPUExecutionProvider"])
    input_info, output_info, image_size = model_contract(session, image_size)
    input_name = input_info.name

    output_model.parent.mkdir(parents=True, exist_ok=True)
    calibration_reader = ImageCalibrationDataReader(
        rows,
        input_name=input_name,
        image_size=image_size,
        batch_size=1 if input_info.shape[0] == 1 else batch_size,
        base_dir=metadata.parent,
    )
    quantize_static(
        model_input=str(onnx_model),
        model_output=str(output_model),
        calibration_data_reader=calibration_reader,
        quant_format=QuantFormat.QDQ,
        activation_type=QuantType.QUInt8,
        weight_type=QuantType.QInt8,
        per_channel=True,
        calibrate_method=CalibrationMethod.MinMax,
        op_types_to_quantize=selected_ops,
    )
    onnx.checker.check_model(str(output_model))

    output_check = compare_outputs(
        onnx_model, output_model, validation_rows, image_size,
        base_dir=validation_manifest.parent if validation_manifest else None,
        min_cosine=min_cosine, min_mean_cosine=min_mean_cosine, norm_tolerance=norm_tolerance,
    )
    output_check["validation_manifest"] = str(validation_manifest) if validation_manifest else None
    output_check["validation_manifest_sha256"] = sha256_file(validation_manifest) if validation_manifest else None
    output_check["split_source"] = {"expected": "val", "declared_for_missing_rows": validation_split}
    output_check["disjointness"] = {
        "checked_against_all_train_rows": bool(validation_rows),
        "identity_fields": ["photo_id", "template_id", "source_sha256", "source_path", "source_group_id"],
        "missing_fields_do_not_prove_independence": True,
        "near_duplicates_checked": False,
    }
    status = "consistency_passed" if output_check["status"] == "passed" else output_check["status"]
    manifest = {
        "schema_version": 2,
        "status": status,
        "retrieval_quality": "not_evaluated",
        "runtime": "onnxruntime-mobile",
        "source_onnx": str(onnx_model),
        "static_int8_onnx": {
            "status": status,
            "path": str(output_model),
            "bytes": file_size(output_model),
            "sha256": sha256_file(output_model),
            "quant_format": "QDQ",
            "activation_type": "QUInt8",
            "weight_type": "QInt8",
            "per_channel": True,
            "op_types_quantized": selected_ops,
            "precision_scope": "selected operators use QDQ int8; unselected operators remain floating point",
            "calibration_method": "MinMax",
            "calibration_items": len(rows),
            "calibration_metadata": str(metadata),
            "calibration_metadata_sha256": sha256_file(metadata),
            "calibration_photo_ids": [row["photo_id"] for row in rows],
            "calibration_distribution": distribution(rows),
            "available_train_distribution": distribution(train_rows),
            "calibration_sampling": {"method": "balanced_photo_type_scene", "seed": seed},
            "split_source": {"expected": "train", "declared_for_missing_rows": calibration_split},
        },
        "input": {
            "name": input_name,
            "shape": list(input_info.shape),
            "dtype": "float32",
            "color": "RGB",
            "normalization": "value = (rgb_0_to_1 - 0.5) / 0.5",
        },
        "output": {
            "name": output_info.name,
            "shape": list(output_info.shape),
            "dtype": "float32",
            "postprocess": "embedding is L2-normalized by the model; normalize again defensively before index search",
        },
        "android_budget": {
            "model_size_target_bytes": 5 * 1024 * 1024,
            "single_frame_embedding_p50_ms": 80,
            "single_frame_embedding_p90_ms": 150,
            "preview_sampling": "500ms-1000ms or meaningful scene change",
            "ranking": "top5 plus rule-based reranking",
        },
        "verification": output_check,
    }
    manifest_output.parent.mkdir(parents=True, exist_ok=True)
    manifest_output.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    export_manifest_path = output_model.parent / "export_manifest.json"
    if export_manifest_path.exists():
        export_manifest = json.loads(export_manifest_path.read_text(encoding="utf-8"))
        export_manifest.setdefault("exports", {})["static_int8_onnx"] = manifest["static_int8_onnx"]
        export_manifest["exports"]["static_int8_onnx"]["runtime"] = "onnxruntime-mobile"
        export_manifest["exports"]["static_int8_onnx"]["manifest"] = str(manifest_output)
        export_manifest_path.write_text(
            json.dumps(export_manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("onnx_model", type=Path)
    parser.add_argument("--metadata", type=Path, required=True, help="Dedicated training split manifest")
    parser.add_argument("--output-model", type=Path, required=True)
    parser.add_argument("--manifest-output", type=Path, required=True)
    parser.add_argument("--image-size", type=int, help="Optional assertion; otherwise read from ONNX")
    parser.add_argument("--calibration-items", type=int, default=128)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--validation-manifest", type=Path, help="Independent val split; never test data")
    parser.add_argument("--calibration-split", choices=("train",), help="Declare provenance only for rows missing split")
    parser.add_argument("--validation-split", choices=("val",), help="Declare provenance only for rows missing split")
    parser.add_argument("--seed", type=int, default=20260718)
    parser.add_argument("--min-cosine", type=float, default=0.95)
    parser.add_argument("--min-mean-cosine", type=float, default=0.98)
    parser.add_argument("--norm-tolerance", type=float, default=0.02)
    parser.add_argument("--quantize-ops", nargs="+", choices=("Conv", "Gemm", "MatMul"),
                        help="Explicit operator subset; default: Conv Gemm MatMul. Other operators stay float32.")
    args = parser.parse_args()
    try:
        summary = quantize_android_onnx(
            args.onnx_model,
            args.metadata,
            args.output_model,
            args.manifest_output,
            image_size=args.image_size,
            calibration_items=args.calibration_items,
            batch_size=args.batch_size,
            validation_manifest=args.validation_manifest,
            calibration_split=args.calibration_split,
            validation_split=args.validation_split,
            seed=args.seed,
            min_cosine=args.min_cosine,
            min_mean_cosine=args.min_mean_cosine,
            norm_tolerance=args.norm_tolerance,
            quantize_ops=args.quantize_ops,
        )
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if summary["verification"]["status"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
