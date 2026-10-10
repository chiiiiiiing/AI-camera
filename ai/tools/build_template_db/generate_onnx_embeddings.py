#!/usr/bin/env python3
"""Generate template embeddings with an exported ONNX student model."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageOps

from build_distillation_dataset import load_jsonl, write_jsonl, resolve_path
from validate_embeddings import read_f32, validate_embedding_values


def create_session(model: Path):
    try:
        import onnxruntime as ort
    except ImportError as exc:
        raise RuntimeError("install onnxruntime before generating ONNX embeddings") from exc
    return ort.InferenceSession(str(model), providers=["CPUExecutionProvider"])


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def embedding_output_path(output_dir: Path, photo_id: str) -> Path:
    if not photo_id or photo_id in {".", ".."} or "/" in photo_id or "\\" in photo_id or ":" in photo_id:
        raise ValueError(f"invalid photo_id for embedding output: {photo_id!r}")
    return output_dir / photo_id / "embedding.f32"


def cache_identity(record: dict[str, Any], model_hash: str, image_size: int) -> dict[str, Any]:
    source_path = resolve_path(str(record.get("source_path") or ""), Path.cwd())
    source_hash = file_hash(source_path)
    if record.get("source_sha256") and str(record["source_sha256"]).lower() != source_hash:
        raise ValueError(f"{record.get('photo_id')}: source_sha256 does not match current image bytes")
    return {"model_sha256": model_hash, "image_size": image_size, "source_sha256": source_hash,
            "preprocess_version": 1}


def validate_cached_embedding(path: Path, identity: dict[str, Any], dimension: int) -> None:
    provenance_path = path.with_suffix(".provenance.json")
    if not provenance_path.exists():
        raise ValueError(f"{path}: cached embedding has no provenance; use --overwrite to regenerate")
    provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
    if any(provenance.get(key) != value for key, value in identity.items()):
        raise ValueError(f"{path}: cached embedding model, image or preprocessing changed; use --overwrite")
    if provenance.get("embedding_sha256") != file_hash(path):
        raise ValueError(f"{path}: cached embedding hash mismatch; use --overwrite")
    errors = validate_embedding_values(read_f32(path), dimension)
    if errors:
        raise ValueError(f"{path}: {'; '.join(errors)}")


def write_f32(path: Path, values: list[float]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".f32.tmp")
    temporary.write_bytes(struct.pack(f"<{len(values)}f", *values))
    temporary.replace(path)


def load_tensor(
    record: dict[str, Any],
    image_size: int,
    base_dir: Path,
    image_cache_dir: Path | None,
) -> np.ndarray:
    from train_student_embedding import cache_image_path, load_resized_image, transform_for_split
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
        image_path = resolve_path(str(record.get("source_path") or ""), base_dir)
        cache_id = photo_id + "_" + file_hash(image_path)[:16]
        prepared_path = cache_image_path(image_cache_dir, "inference", cache_id, image_size, False).with_suffix(".png")
        if prepared_path.exists():
            with Image.open(prepared_path) as image:
                tensor = transform(image.convert("RGB"))
        else:
            image = load_resized_image(image_path, image_size, train=False)
            prepared_path.parent.mkdir(parents=True, exist_ok=True)
            # Lossless storage keeps the first inference and subsequent cache
            # reads pixel-identical under the same preprocessing identity.
            image.save(prepared_path, format="PNG")
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
    if batch_size <= 0 or image_size <= 0 or (limit is not None and limit <= 0):
        raise ValueError("batch_size, image_size and limit must be positive")
    rows = load_jsonl(metadata_path)
    if limit is not None:
        rows = rows[:limit]
    photo_ids = [record.get("photo_id") for record in rows]
    if not rows or any(not isinstance(photo_id, str) or not photo_id for photo_id in photo_ids):
        raise ValueError("metadata must contain non-empty photo_ids")
    if len(set(photo_ids)) != len(photo_ids):
        raise ValueError("duplicate photo_id in metadata")
    model_hash = file_hash(onnx_model)
    session = create_session(onnx_model)
    input_name = session.get_inputs()[0].name
    output_name = session.get_outputs()[0].name
    expected_dimension = int(session.get_outputs()[0].shape[-1])
    identities = {record["photo_id"]: {**cache_identity(record, model_hash, image_size),
                                      "image_cache_enabled": image_cache_dir is not None} for record in rows}

    updated: list[dict[str, Any]] = []
    manifest_rows: list[dict[str, Any]] = []
    pending: list[tuple[int, dict[str, Any]]] = []
    for index, record in enumerate(rows):
        photo_id = str(record.get("photo_id", ""))
        output_path = embedding_output_path(output_dir, photo_id)
        if output_path.exists() and not overwrite:
            validate_cached_embedding(output_path, identities[photo_id], expected_dimension)
            dimension = expected_dimension
            updated_record = json.loads(json.dumps(record))
            updated_record["embedding"] = {
                "status": "ready",
                "dimension": dimension,
                "path": str(output_path),
                "model_id": str(onnx_model),
                "model_sha256": model_hash,
                "role": "student",
            }
            updated.append(updated_record)
            manifest_rows.append({
                "photo_id": photo_id,
                "template_id": record.get("template_id"),
                "path": str(output_path),
                "dimension": dimension,
                "model_id": str(onnx_model),
                "model_sha256": model_hash,
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
        if embeddings.shape != (len(batch_items), expected_dimension):
            raise ValueError(f"unexpected ONNX output shape: {embeddings.shape}")
        for (position, record), values in zip(batch_items, embeddings.tolist()):
            errors = validate_embedding_values(values, expected_dimension)
            if errors:
                raise ValueError(f"{record['photo_id']}: invalid ONNX embedding: {'; '.join(errors)}")
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
        provenance = {**identities[photo_id], "embedding_sha256": file_hash(output_path)}
        output_path.with_suffix(".provenance.json").write_text(json.dumps(provenance, sort_keys=True) + "\n", encoding="utf-8")
        updated_record = json.loads(json.dumps(record))
        updated_record["embedding"] = {
            "status": "ready",
            "dimension": len(values),
            "path": str(output_path),
            "model_id": str(onnx_model),
            "model_sha256": model_hash,
            "role": "student",
        }
        final_updated.append(updated_record)
        final_manifest.append({
            "photo_id": photo_id,
            "template_id": record.get("template_id"),
            "path": str(output_path),
            "dimension": len(values),
            "model_id": str(onnx_model),
            "model_sha256": model_hash,
        })

    if len(final_updated) != len(rows):
        errors.append(f"updated metadata count {len(final_updated)} does not match input {len(rows)}")
    write_jsonl(metadata_output, final_updated)
    write_jsonl(manifest_output, final_manifest)
    return {
        "source_metadata": str(metadata_path),
        "onnx_model": str(onnx_model),
        "model_sha256": model_hash,
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
