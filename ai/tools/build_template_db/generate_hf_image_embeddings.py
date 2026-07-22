#!/usr/bin/env python3
"""Generate teacher image embeddings with a Hugging Face vision-language model."""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import struct
import sys
from pathlib import Path
from typing import Any, Iterable


DEFAULT_MODEL_ID = "google/siglip2-base-patch16-224"


def missing_dependencies() -> list[str]:
    required = {
        "torch": "torch",
        "transformers": "transformers",
        "PIL": "Pillow",
        "numpy": "numpy",
    }
    return [package for module, package in required.items() if importlib.util.find_spec(module) is None]


def choose_device(torch_module: Any, requested: str) -> str:
    if requested != "auto":
        return requested
    if getattr(torch_module, "cuda", None) is not None and torch_module.cuda.is_available():
        return "cuda"
    backends = getattr(torch_module, "backends", None)
    mps = getattr(backends, "mps", None)
    if mps is not None and mps.is_available():
        return "mps"
    return "cpu"


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


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def write_f32(path: Path, values: list[float]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_suffix(path.suffix + ".tmp")
    with tmp_path.open("wb") as handle:
        handle.write(struct.pack(f"<{len(values)}f", *values))
    tmp_path.replace(path)


def normalize(values: list[float]) -> list[float]:
    magnitude = math.sqrt(sum(value * value for value in values))
    if magnitude <= 1e-12:
        raise ValueError("embedding magnitude is zero")
    return [float(value) / magnitude for value in values]


def batched(rows: list[dict[str, Any]], batch_size: int) -> Iterable[list[dict[str, Any]]]:
    if batch_size <= 0:
        raise ValueError("batch_size must be positive")
    for index in range(0, len(rows), batch_size):
        yield rows[index:index + batch_size]


class HuggingFaceImageEncoder:
    def __init__(self, model_id: str, cache_dir: Path | None, device: str) -> None:
        missing = missing_dependencies()
        if missing:
            raise RuntimeError(
                "missing dependencies for Hugging Face teacher embeddings: "
                + ", ".join(missing)
            )
        import torch
        from transformers import AutoModel, AutoProcessor

        self.torch = torch
        self.device = choose_device(torch, device)
        cache = str(cache_dir) if cache_dir else None
        self.processor = AutoProcessor.from_pretrained(model_id, cache_dir=cache)
        self.model = AutoModel.from_pretrained(model_id, cache_dir=cache)
        self.model.eval()
        self.model.to(self.device)

    def encode(self, image_paths: list[Path]) -> list[list[float]]:
        from PIL import Image

        images = [Image.open(path).convert("RGB") for path in image_paths]
        inputs = self.processor(images=images, return_tensors="pt")
        inputs = {key: value.to(self.device) for key, value in inputs.items()}
        with self.torch.no_grad():
            if hasattr(self.model, "get_image_features"):
                features = self.model.get_image_features(**inputs)
            else:
                outputs = self.model(**inputs)
                features = getattr(outputs, "image_embeds", None)
                if features is None:
                    pooler = getattr(outputs, "pooler_output", None)
                    if pooler is None:
                        raise RuntimeError("model output has no image embedding field")
                    features = pooler
        return [normalize(row.detach().cpu().float().tolist()) for row in features]


def embedding_output_path(output_dir: Path, photo_id: str) -> Path:
    return output_dir / photo_id / "embedding.f32"


def existing_dimension(path: Path) -> int:
    size = path.stat().st_size
    if size % 4 != 0:
        raise ValueError(f"embedding file has invalid byte length: {path}")
    return size // 4


def generate_teacher_metadata(
    metadata_path: Path,
    model_id: str,
    output_dir: Path,
    metadata_output: Path,
    manifest_output: Path,
    cache_dir: Path | None,
    device: str = "auto",
    batch_size: int = 8,
    overwrite: bool = False,
    limit: int | None = None,
) -> dict[str, Any]:
    rows = load_jsonl(metadata_path)
    if limit is not None:
        rows = rows[:limit]
    encoder = HuggingFaceImageEncoder(model_id, cache_dir, device)
    updated: list[dict[str, Any]] = []
    manifest_rows: list[dict[str, Any]] = []
    summary: dict[str, Any] = {
        "source_metadata": str(metadata_path),
        "model_id": model_id,
        "items": len(rows),
        "attempted": 0,
        "written": 0,
        "skipped_existing": 0,
        "dimension": None,
        "errors": [],
    }

    for batch in batched(rows, batch_size):
        pending_records: list[dict[str, Any]] = []
        pending_paths: list[Path] = []
        for record in batch:
            photo_id = str(record.get("photo_id", ""))
            output_path = embedding_output_path(output_dir, photo_id)
            if output_path.exists() and not overwrite:
                dimension = existing_dimension(output_path)
                updated_record = json.loads(json.dumps(record))
                updated_record["embedding"] = {
                    "status": "ready",
                    "dimension": dimension,
                    "path": str(output_path),
                    "model_id": model_id,
                    "role": "teacher",
                }
                updated.append(updated_record)
                manifest_rows.append({
                    "photo_id": photo_id,
                    "template_id": record.get("template_id"),
                    "path": str(output_path),
                    "dimension": dimension,
                    "model_id": model_id,
                })
                summary["skipped_existing"] += 1
                summary["dimension"] = summary["dimension"] or dimension
                continue
            source_path = Path(str(record.get("source_path", "")))
            if not source_path.exists():
                summary["errors"].append(f"{photo_id}: source image not found: {source_path}")
                continue
            pending_records.append(record)
            pending_paths.append(source_path)
        if not pending_records:
            continue
        summary["attempted"] += len(pending_records)
        try:
            embeddings = encoder.encode(pending_paths)
        except Exception as exc:
            for record in pending_records:
                summary["errors"].append(f"{record.get('photo_id')}: {exc}")
            continue
        for record, values in zip(pending_records, embeddings):
            photo_id = str(record["photo_id"])
            output_path = embedding_output_path(output_dir, photo_id)
            write_f32(output_path, values)
            dimension = len(values)
            updated_record = json.loads(json.dumps(record))
            updated_record["embedding"] = {
                "status": "ready",
                "dimension": dimension,
                "path": str(output_path),
                "model_id": model_id,
                "role": "teacher",
            }
            updated.append(updated_record)
            manifest_rows.append({
                "photo_id": photo_id,
                "template_id": record.get("template_id"),
                "path": str(output_path),
                "dimension": dimension,
                "model_id": model_id,
            })
            summary["written"] += 1
            summary["dimension"] = summary["dimension"] or dimension

    if len(updated) != len(rows):
        summary["errors"].append(f"updated metadata count {len(updated)} does not match input {len(rows)}")
    write_jsonl(metadata_output, updated)
    write_jsonl(manifest_output, manifest_rows)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("--model-id", default=DEFAULT_MODEL_ID)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--metadata-output", type=Path, required=True)
    parser.add_argument("--manifest-output", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--check-deps", action="store_true")
    parser.add_argument("--download-only", action="store_true")
    args = parser.parse_args()

    if args.check_deps:
        missing = missing_dependencies()
        print(json.dumps({"missing": missing}, ensure_ascii=False, indent=2))
        return 1 if missing else 0
    if args.download_only:
        try:
            from huggingface_hub import snapshot_download
            destination = snapshot_download(
                args.model_id,
                cache_dir=str(args.cache_dir) if args.cache_dir else None,
            )
            print(json.dumps({"model_id": args.model_id, "snapshot": destination}, indent=2))
        except Exception as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 1
        return 0

    try:
        summary = generate_teacher_metadata(
            args.metadata,
            args.model_id,
            args.output_dir,
            args.metadata_output,
            args.manifest_output,
            args.cache_dir,
            args.device,
            args.batch_size,
            args.overwrite,
            args.limit,
        )
    except (OSError, ValueError, RuntimeError, ImportError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 1 if summary["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
