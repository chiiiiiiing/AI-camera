#!/usr/bin/env python3
"""Static-quantize a student ONNX model with real calibration images."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort
import torch
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


def compare_outputs(fp32_model: Path, int8_model: Path, rows: list[dict[str, Any]], image_size: int) -> dict[str, Any]:
    if not rows:
        return {"status": "skipped", "reason": "no metadata rows available"}
    providers = ["CPUExecutionProvider"]
    fp32_session = ort.InferenceSession(str(fp32_model), providers=providers)
    int8_session = ort.InferenceSession(str(int8_model), providers=providers)
    input_name = fp32_session.get_inputs()[0].name
    sample = prepare_image(rows[0], image_size, Path.cwd())
    fp32_output = fp32_session.run(None, {input_name: sample})[0]
    int8_output = int8_session.run(None, {input_name: sample})[0]
    cosine = float(
        np.sum(fp32_output * int8_output)
        / max(np.linalg.norm(fp32_output) * np.linalg.norm(int8_output), 1e-12)
    )
    return {
        "status": "ready",
        "sample_photo_id": rows[0].get("photo_id"),
        "fp32_output_shape": list(fp32_output.shape),
        "int8_output_shape": list(int8_output.shape),
        "fp32_int8_cosine": cosine,
        "int8_output_l2_norm": float(np.linalg.norm(int8_output)),
    }


def quantize_android_onnx(
    onnx_model: Path,
    metadata: Path,
    output_model: Path,
    manifest_output: Path,
    image_size: int = 224,
    calibration_items: int = 128,
    batch_size: int = 16,
) -> dict[str, Any]:
    if calibration_items <= 0:
        raise ValueError("calibration_items must be positive")
    if batch_size <= 0:
        raise ValueError("batch_size must be positive")

    rows = load_jsonl(metadata)
    if not rows:
        raise ValueError("metadata contains no rows")
    rows = sorted(rows, key=lambda row: str(row.get("photo_id")))[:calibration_items]

    onnx.checker.check_model(str(onnx_model))
    session = ort.InferenceSession(str(onnx_model), providers=["CPUExecutionProvider"])
    input_info = session.get_inputs()[0]
    input_name = input_info.name

    output_model.parent.mkdir(parents=True, exist_ok=True)
    calibration_reader = ImageCalibrationDataReader(
        rows,
        input_name=input_name,
        image_size=image_size,
        batch_size=batch_size,
        base_dir=Path.cwd(),
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
        op_types_to_quantize=["Conv", "Gemm", "MatMul"],
    )
    onnx.checker.check_model(str(output_model))

    output_check = compare_outputs(onnx_model, output_model, rows, image_size)
    manifest = {
        "schema_version": 1,
        "runtime": "onnxruntime-mobile",
        "source_onnx": str(onnx_model),
        "static_int8_onnx": {
            "path": str(output_model),
            "bytes": file_size(output_model),
            "sha256": sha256_file(output_model),
            "quant_format": "QDQ",
            "activation_type": "QUInt8",
            "weight_type": "QInt8",
            "per_channel": True,
            "op_types_quantized": ["Conv", "Gemm", "MatMul"],
            "calibration_method": "MinMax",
            "calibration_items": len(rows),
            "calibration_metadata": str(metadata),
        },
        "input": {
            "name": input_name,
            "shape": ["batch", 3, image_size, image_size],
            "dtype": "float32",
            "color": "RGB",
            "normalization": "value = (rgb_0_to_1 - 0.5) / 0.5",
        },
        "output": {
            "name": session.get_outputs()[0].name,
            "shape": ["batch", 512],
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
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--output-model", type=Path, required=True)
    parser.add_argument("--manifest-output", type=Path, required=True)
    parser.add_argument("--image-size", type=int, default=224)
    parser.add_argument("--calibration-items", type=int, default=128)
    parser.add_argument("--batch-size", type=int, default=16)
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
        )
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
