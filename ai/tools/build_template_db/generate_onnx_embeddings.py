#!/usr/bin/env python3
"""Generate template embeddings with an exported ONNX student model."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageOps

from generate_student_embeddings import (
    embedding_output_path,
    existing_dimension,
    load_jsonl,
    write_f32,
    write_jsonl,
)
from train_student_embedding import (
    cache_image_path,
    load_resized_image,
    resolve_path,
    transform_for_split,
)


def load_tensor(
    record: dict[str, Any],
    image_size: int,
    base_dir: Path,
    image_cache_dir: Path | None,
) -> np.ndarray:
    photo_id = str(record.get("photo_id"))
    transform = transform_for_split(
        image_size,
        train=False,
        pre_resized=image_cache_dir is not None,
    )
    if image_cache_dir is None:
        image_path = resolve_path(str(record.get("source_path") or ""), base_dir)
        with Image.open(image_path) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            tensor = transform(image)
    else:
        prepared_path = cache_image_path(image_cache_dir, "inference", photo_id, image_size, False)
        if prepared_path.exists():
            with Image.open(prepared_path) as image:
                tensor = transform(image.convert("RGB"))
        else:
            image_path = resolve_path(str(record.get("source_path") or ""), base_dir)
            image = load_resized_image(image_path, image_size, train=False)
            prepared_path.parent.mkdir(parents=True, exist_ok=True)
            image.save(prepared_path, quality=92, optimize=True)
            tensor = transform(image)
    return tensor.numpy().astype(np.float32)


def l2_normalize(values: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(values, axis=1, keepdims=True)
    norms = np.maximum(norms, 1e-12)
    return (values / norms).astype(np.float32)


def generate_embeddings(
    metadata_path: Path,
    onnx_model: Path,
    output_dir: Path,
    metadata_output: Path,
    manifest_output: Path,
    image_size: int = 224,
    batch_size: int = 16,
    overwrite: bool = False,
    limit: int | None = None,
    image_cache_dir: Path | None = None,
) -> dict[str, Any]:
    rows = load_jsonl(metadata_path)
    if limit is not None:
        rows = rows[:limit]
    session = ort.InferenceSession(str(onnx_model), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    output_name = session.get_outputs()[0].name

    updated: list[dict[str, Any]] = []
    manifest_rows: list[dict[str, Any]] = []
    pending: list[tuple[int, dict[str, Any]]] = []
    for index, record in enumerate(rows):
        photo_id = str(record.get("photo_id", ""))
        output_path = embedding_output_path(output_dir, photo_id)
        if output_path.exists() and not overwrite:
            dimension = existing_dimension(output_path)
            updated_record = json.loads(json.dumps(record))
            updated_record["embedding"] = {
                "status": "ready",
                "dimension": dimension,
                "path": str(output_path),
                "model_id": str(onnx_model),
                "role": "student",
            }
            updated.append(updated_record)
            manifest_rows.append({
                "photo_id": photo_id,
                "template_id": record.get("template_id"),
                "path": str(output_path),
                "dimension": dimension,
                "model_id": str(onnx_model),
            })
            continue
        pending.append((index, record))

    generated_by_position: dict[int, tuple[dict[str, Any], Path, list[float]]] = {}
    for start in range(0, len(pending), batch_size):
        batch_items = pending[start:start + batch_size]
        batch = np.stack(
            [
                load_tensor(record, image_size, Path.cwd(), image_cache_dir)
                for _, record in batch_items
            ],
            axis=0,
        )
        embeddings = l2_normalize(session.run([output_name], {input_name: batch})[0])
        for (position, record), values in zip(batch_items, embeddings.tolist()):
            photo_id = str(record["photo_id"])
            output_path = embedding_output_path(output_dir, photo_id)
            generated_by_position[position] = (record, output_path, values)
        processed = min(start + batch_size, len(pending))
        if processed % 100 == 0 or processed == len(pending):
            print(
                json.dumps(
                    {"event": "onnx_embedding_progress", "processed": processed, "total": len(pending)},
                    ensure_ascii=False,
                    sort_keys=True,
                ),
                file=sys.stderr,
                flush=True,
            )

    final_updated: list[dict[str, Any]] = []
    final_manifest: list[dict[str, Any]] = []
    existing_by_photo = {row["photo_id"]: row for row in updated}
    existing_manifest_by_photo = {row["photo_id"]: row for row in manifest_rows}
    errors: list[str] = []
    for index, record in enumerate(rows):
        photo_id = str(record["photo_id"])
        if photo_id in existing_by_photo:
            final_updated.append(existing_by_photo[photo_id])
            final_manifest.append(existing_manifest_by_photo[photo_id])
            continue
        generated = generated_by_position.get(index)
        if generated is None:
            errors.append(f"{photo_id}: embedding was not generated")
            continue
        _, output_path, values = generated
        write_f32(output_path, values)
        updated_record = json.loads(json.dumps(record))
        updated_record["embedding"] = {
            "status": "ready",
            "dimension": len(values),
            "path": str(output_path),
            "model_id": str(onnx_model),
            "role": "student",
        }
        final_updated.append(updated_record)
        final_manifest.append({
            "photo_id": photo_id,
            "template_id": record.get("template_id"),
            "path": str(output_path),
            "dimension": len(values),
            "model_id": str(onnx_model),
        })

    if len(final_updated) != len(rows):
        errors.append(f"updated metadata count {len(final_updated)} does not match input {len(rows)}")
    write_jsonl(metadata_output, final_updated)
    write_jsonl(manifest_output, final_manifest)
    return {
        "source_metadata": str(metadata_path),
        "onnx_model": str(onnx_model),
        "items": len(rows),
        "written_or_ready": len(final_updated),
        "dimension": int(session.get_outputs()[0].shape[-1]),
        "provider": "CPUExecutionProvider",
        "image_cache_dir": str(image_cache_dir) if image_cache_dir else None,
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("--onnx-model", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--metadata-output", type=Path, required=True)
    parser.add_argument("--manifest-output", type=Path, required=True)
    parser.add_argument("--image-size", type=int, default=224)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--image-cache-dir", type=Path)
    args = parser.parse_args()
    try:
        summary = generate_embeddings(
            args.metadata,
            args.onnx_model,
            args.output_dir,
            args.metadata_output,
            args.manifest_output,
            image_size=args.image_size,
            batch_size=args.batch_size,
            overwrite=args.overwrite,
            limit=args.limit,
            image_cache_dir=args.image_cache_dir,
        )
    except (OSError, ValueError, RuntimeError, KeyError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
