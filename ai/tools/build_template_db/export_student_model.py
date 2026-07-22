#!/usr/bin/env python3
"""Export a trained student embedding model and a dynamic-int8 prototype."""

from __future__ import annotations

import argparse
import inspect
import json
import sys
from pathlib import Path
from typing import Any

import torch
from torch import nn

from student_embedding_model import build_student_model, count_parameters


def file_size(path: Path) -> int:
    return path.stat().st_size if path.exists() else 0


def load_checkpoint(path: Path) -> dict[str, Any]:
    return torch.load(path, map_location="cpu", weights_only=False)


def load_model(checkpoint: dict[str, Any]) -> nn.Module:
    model = build_student_model(checkpoint["model"])
    model.load_state_dict(checkpoint["model_state"])
    model.eval()
    return model


def choose_quantized_engine() -> str:
    supported = list(torch.backends.quantized.supported_engines)
    for candidate in ("qnnpack", "fbgemm", "x86"):
        if candidate in supported:
            torch.backends.quantized.engine = candidate
            return candidate
    raise RuntimeError(f"no supported quantized engine found: {supported}")


def try_export_onnx(model: nn.Module, dummy: torch.Tensor, output_dir: Path) -> dict[str, Any]:
    onnx_path = output_dir / "student_model.fp32.onnx"
    export_kwargs: dict[str, Any] = {
        "model": model,
        "args": dummy,
        "f": str(onnx_path),
        "input_names": ["image"],
        "output_names": ["embedding"],
        "dynamic_axes": {
            "image": {0: "batch"},
            "embedding": {0: "batch"},
        },
        "opset_version": 17,
    }
    if "dynamo" in inspect.signature(torch.onnx.export).parameters:
        export_kwargs["dynamo"] = False

    try:
        torch.onnx.export(**export_kwargs)
    except Exception as exc:  # ONNX is optional for the Python prototype environment.
        if onnx_path.exists():
            onnx_path.unlink()
        return {
            "status": "skipped",
            "path": str(onnx_path),
            "reason": f"{type(exc).__name__}: {exc}",
            "next_steps": [
                "install ONNX export dependencies in the build environment",
                "rerun export_student_model.py to produce student_model.fp32.onnx",
                "convert ONNX to the Android runtime target: ONNX Runtime Mobile, TFLite, or NCNN",
                "run static int8 calibration and real-device latency validation",
            ],
        }

    return {
        "status": "ready",
        "path": str(onnx_path),
        "bytes": file_size(onnx_path),
        "opset": 17,
        "input": {
            "name": "image",
            "shape": ["batch", 3, int(dummy.shape[2]), int(dummy.shape[3])],
            "dtype": "float32",
            "normalization": "RGB tensor normalized with mean=(0.5,0.5,0.5), std=(0.5,0.5,0.5)",
        },
        "output": {
            "name": "embedding",
            "shape": ["batch", int(model.config.output_dim) if hasattr(model, "config") else None],
            "dtype": "float32",
            "postprocess": "embedding is L2-normalized by the model",
        },
    }


def export_model(checkpoint_path: Path, output_dir: Path) -> dict[str, Any]:
    checkpoint = load_checkpoint(checkpoint_path)
    model = load_model(checkpoint)
    config = checkpoint["model"]
    image_size = int(config["image_size"])
    output_dim = int(config["output_dim"])
    output_dir.mkdir(parents=True, exist_ok=True)

    checkpoint_copy = output_dir / "student_checkpoint.pt"
    torch.save(checkpoint, checkpoint_copy)

    dummy = torch.randn(1, 3, image_size, image_size)
    fp32_script = torch.jit.trace(model, dummy)
    fp32_path = output_dir / "student_model.fp32.torchscript.pt"
    fp32_script.save(str(fp32_path))

    quantized_engine = choose_quantized_engine()
    quantized = torch.quantization.quantize_dynamic(
        model,
        {nn.Linear},
        dtype=torch.qint8,
    )
    quantized_script = torch.jit.trace(quantized, dummy)
    quantized_path = output_dir / "student_model.dynamic_int8.torchscript.pt"
    quantized_script.save(str(quantized_path))
    onnx_export = try_export_onnx(model, dummy, output_dir)

    manifest = {
        "schema_version": 1,
        "source_checkpoint": str(checkpoint_path),
        "checkpoint_copy": str(checkpoint_copy),
        "model": config,
        "parameter_count": count_parameters(model),
        "exports": {
            "fp32_torchscript": {
                "path": str(fp32_path),
                "bytes": file_size(fp32_path),
            },
            "dynamic_int8_torchscript": {
                "path": str(quantized_path),
                "bytes": file_size(quantized_path),
                "engine": quantized_engine,
                "quantization": "torch dynamic quantization for Linear layers; convolution layers remain fp32",
            },
            "fp32_onnx": onnx_export,
        },
        "android_budget": {
            "model_size_target_bytes": 5 * 1024 * 1024,
            "latency_target": {
                "single_frame_embedding_p50_ms": 80,
                "single_frame_embedding_p90_ms": 150,
            },
            "camera_sampling_policy": "run embedding every 500ms-1000ms or on meaningful scene change, not every preview frame",
            "ranking_policy": "return top5 candidates and apply rule-based reranking; do not rely only on top1",
            "requires_real_device_validation": True,
        },
        "output_dim": output_dim,
        "image_size": image_size,
    }
    manifest_path = output_dir / "export_manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        summary = export_model(args.checkpoint, args.output_dir)
    except (OSError, ValueError, KeyError, RuntimeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
