#!/usr/bin/env python3
"""Generate template embeddings with a trained student model."""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path
from typing import Any, Iterable

import torch
from PIL import Image, ImageOps
from torch.utils.data import DataLoader, Dataset

from student_embedding_model import build_student_model
from train_student_embedding import (
    choose_device,
    cache_image_path,
    load_resized_image,
    resolve_path,
    transform_for_split,
)


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


def embedding_output_path(output_dir: Path, photo_id: str) -> Path:
    return output_dir / photo_id / "embedding.f32"


def existing_dimension(path: Path) -> int:
    size = path.stat().st_size
    if size % 4 != 0:
        raise ValueError(f"embedding file has invalid byte length: {path}")
    return size // 4


class TemplateImageDataset(Dataset[tuple[int, torch.Tensor]]):
    def __init__(
        self,
        rows: list[dict[str, Any]],
        image_size: int,
        base_dir: Path,
        image_cache_dir: Path | None = None,
    ) -> None:
        self.rows = rows
        self.image_size = image_size
        self.transform = transform_for_split(image_size, train=False, pre_resized=image_cache_dir is not None)
        self.base_dir = base_dir
        self.image_cache_dir = image_cache_dir

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, index: int) -> tuple[int, torch.Tensor]:
        record = self.rows[index]
        image_path = resolve_path(str(record.get("source_path") or ""), self.base_dir)
        photo_id = str(record.get("photo_id"))
        if self.image_cache_dir is None:
            with Image.open(image_path) as image:
                image = ImageOps.exif_transpose(image).convert("RGB")
                tensor = self.transform(image)
        else:
            prepared_path = cache_image_path(self.image_cache_dir, "inference", photo_id, self.image_size, False)
            if prepared_path.exists():
                with Image.open(prepared_path) as image:
                    image = image.convert("RGB")
                    tensor = self.transform(image)
            else:
                image = load_resized_image(image_path, self.image_size, train=False)
                prepared_path.parent.mkdir(parents=True, exist_ok=True)
                image.save(prepared_path, quality=92, optimize=True)
                tensor = self.transform(image)
        return index, tensor


def load_student(checkpoint_path: Path) -> tuple[torch.nn.Module, dict[str, Any]]:
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    model = build_student_model(checkpoint["model"])
    model.load_state_dict(checkpoint["model_state"])
    model.eval()
    return model, checkpoint


def generate_embeddings(
    metadata_path: Path,
    checkpoint_path: Path,
    output_dir: Path,
    metadata_output: Path,
    manifest_output: Path,
    device: str = "auto",
    batch_size: int = 16,
    overwrite: bool = False,
    limit: int | None = None,
    image_cache_dir: Path | None = None,
) -> dict[str, Any]:
    rows = load_jsonl(metadata_path)
    if limit is not None:
        rows = rows[:limit]
    model, checkpoint = load_student(checkpoint_path)
    model_config = checkpoint["model"]
    image_size = int(model_config["image_size"])
    output_dim = int(model_config["output_dim"])
    selected_device = choose_device(device)
    model.to(selected_device)

    updated: list[dict[str, Any]] = []
    manifest_rows: list[dict[str, Any]] = []
    pending: list[dict[str, Any]] = []
    pending_positions: list[int] = []
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
                "model_id": str(checkpoint_path),
                "role": "student",
            }
            updated.append(updated_record)
            manifest_rows.append({
                "photo_id": photo_id,
                "template_id": record.get("template_id"),
                "path": str(output_path),
                "dimension": dimension,
                "model_id": str(checkpoint_path),
            })
            continue
        pending.append(record)
        pending_positions.append(index)

    dataset = TemplateImageDataset(pending, image_size, Path.cwd(), image_cache_dir=image_cache_dir)
    loader = DataLoader(dataset, batch_size=batch_size, shuffle=False, num_workers=0)
    generated_by_position: dict[int, tuple[dict[str, Any], Path, list[float]]] = {}
    errors: list[str] = []
    with torch.no_grad():
        for batch_indices, images in loader:
            images = images.to(selected_device)
            embeddings = model(images).detach().cpu().float()
            for local_index, values in zip(batch_indices.tolist(), embeddings.tolist()):
                record = pending[int(local_index)]
                photo_id = str(record["photo_id"])
                output_path = embedding_output_path(output_dir, photo_id)
                generated_by_position[pending_positions[int(local_index)]] = (record, output_path, values)

    final_updated: list[dict[str, Any]] = []
    final_manifest: list[dict[str, Any]] = []
    existing_by_photo = {row["photo_id"]: row for row in updated}
    existing_manifest_by_photo = {row["photo_id"]: row for row in manifest_rows}
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
            "model_id": str(checkpoint_path),
            "role": "student",
        }
        final_updated.append(updated_record)
        final_manifest.append({
            "photo_id": photo_id,
            "template_id": record.get("template_id"),
            "path": str(output_path),
            "dimension": len(values),
            "model_id": str(checkpoint_path),
        })

    if len(final_updated) != len(rows):
        errors.append(f"updated metadata count {len(final_updated)} does not match input {len(rows)}")
    write_jsonl(metadata_output, final_updated)
    write_jsonl(manifest_output, final_manifest)
    return {
        "source_metadata": str(metadata_path),
        "checkpoint": str(checkpoint_path),
        "items": len(rows),
        "written_or_ready": len(final_updated),
        "dimension": output_dim,
        "device": selected_device,
        "image_cache_dir": str(image_cache_dir) if image_cache_dir else None,
        "errors": errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--metadata-output", type=Path, required=True)
    parser.add_argument("--manifest-output", type=Path, required=True)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--image-cache-dir", type=Path)
    args = parser.parse_args()
    try:
        summary = generate_embeddings(
            args.metadata,
            args.checkpoint,
            args.output_dir,
            args.metadata_output,
            args.manifest_output,
            device=args.device,
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
