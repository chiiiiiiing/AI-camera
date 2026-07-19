#!/usr/bin/env python3
"""Generate real MobileCLIP2-S0 image embeddings for preprocessing jobs."""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import struct
import sys
from pathlib import Path
from typing import Any, Protocol, Sequence

import preprocess


DEFAULT_CHECKPOINT = Path(__file__).resolve().parent / "models" / "mobileclip2_s0" / "mobileclip2_s0.pt"


class ImageEmbeddingBackend(Protocol):
    dimension: int

    def encode_image(self, image_path: Path) -> Sequence[float]: ...


class MobileClip2Backend:
    def __init__(self, checkpoint: Path, device: str = "auto", model_name: str = "MobileCLIP2-S0") -> None:
        missing = missing_dependencies()
        if missing:
            raise RuntimeError(
                "missing Python dependencies for MobileCLIP2-S0 inference: "
                + ", ".join(missing)
                + ". Install torch, open_clip_torch and ml-mobileclip before generating embeddings."
            )
        if not checkpoint.exists():
            raise FileNotFoundError(f"missing checkpoint: {checkpoint}")

        import torch
        import open_clip
        from mobileclip.modules.common.mobileone import reparameterize_model

        self.torch = torch
        self.device = choose_device(torch, device)
        self.model, _, self.preprocess_image = open_clip.create_model_and_transforms(
            model_name,
            pretrained=str(checkpoint),
        )
        self.model.eval()
        self.model = reparameterize_model(self.model)
        self.model.to(self.device)
        self.dimension = 512

    def encode_image(self, image_path: Path) -> Sequence[float]:
        from PIL import Image

        image = self.preprocess_image(Image.open(image_path).convert("RGB")).unsqueeze(0)
        image = image.to(self.device)
        with self.torch.no_grad():
            features = self.model.encode_image(image)
        return features.squeeze(0).detach().cpu().float().tolist()


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


def missing_dependencies() -> list[str]:
    required = {
        "torch": "torch",
        "open_clip": "open_clip_torch",
        "mobileclip": "ml-mobileclip",
        "PIL": "Pillow",
    }
    return [
        package
        for module, package in required.items()
        if importlib.util.find_spec(module) is None
    ]


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"{path} line {line_number} is not an object")
            rows.append(value)
    return rows


def resolve_path(value: str, base_dir: Path) -> Path:
    path = Path(value)
    return path if path.is_absolute() else (base_dir / path).resolve()


def input_image_for_job(job: dict[str, Any], jobs_path: Path) -> Path:
    output = job.get("output", {})
    base_dir = jobs_path.parent
    background = output.get("background_path")
    if isinstance(background, str):
        background_path = resolve_path(background, base_dir)
        if background_path.exists():
            return background_path
    source = job.get("source_path")
    if not isinstance(source, str) or not source:
        raise ValueError(f"{job.get('photo_id', '<unknown>')}: missing source_path")
    source_path = resolve_path(source, base_dir)
    if not source_path.exists():
        raise ValueError(f"{job.get('photo_id', '<unknown>')}: missing input image: {source_path}")
    return source_path


def embedding_path_for_job(job: dict[str, Any], jobs_path: Path) -> Path:
    embedding = job.get("output", {}).get("embedding_path")
    if not isinstance(embedding, str) or not embedding:
        raise ValueError(f"{job.get('photo_id', '<unknown>')}: missing output.embedding_path")
    return resolve_path(embedding, jobs_path.parent)


def write_embedding_f32(path: Path, values: Sequence[float]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_suffix(path.suffix + ".tmp")
    with tmp_path.open("wb") as handle:
        handle.write(struct.pack(f"<{len(values)}f", *values))
    tmp_path.replace(path)


def project_embedding(
    values: Sequence[float],
    expected_dimension: int,
    projection: str = "none",
) -> Sequence[float]:
    if len(values) == expected_dimension:
        return values
    if projection == "none":
        return values
    if projection == "pairwise":
        if len(values) != expected_dimension * 2:
            raise ValueError(
                f"pairwise projection requires {expected_dimension * 2} input values, "
                f"got {len(values)}"
            )
        scale = 1 / math.sqrt(2)
        return [
            (float(values[index]) + float(values[index + expected_dimension])) * scale
            for index in range(expected_dimension)
        ]
    raise ValueError(f"unknown projection: {projection}")


def generate_embeddings(
    jobs_path: Path,
    backend: ImageEmbeddingBackend,
    expected_dimension: int = 256,
    projection: str = "none",
    overwrite: bool = False,
    limit: int | None = None,
) -> dict[str, Any]:
    rows = load_jsonl(jobs_path)
    summary: dict[str, Any] = {
        "jobs": len(rows),
        "attempted": 0,
        "written": 0,
        "skipped_existing": 0,
        "errors": [],
    }
    for job in rows:
        if limit is not None and summary["attempted"] >= limit:
            break
        photo_id = job.get("photo_id", "<unknown>")
        try:
            output_path = embedding_path_for_job(job, jobs_path)
            if output_path.exists() and not overwrite:
                summary["skipped_existing"] += 1
                continue
            image_path = input_image_for_job(job, jobs_path)
            summary["attempted"] += 1
            values = preprocess.validate_embedding(
                project_embedding(
                    backend.encode_image(image_path),
                    expected_dimension=expected_dimension,
                    projection=projection,
                ),
                expected_dimension=expected_dimension,
            )
            write_embedding_f32(output_path, values)
            summary["written"] += 1
        except (OSError, ValueError, RuntimeError) as exc:
            summary["errors"].append(f"{photo_id}: {exc}")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jobs", type=Path)
    parser.add_argument("--checkpoint", type=Path, default=DEFAULT_CHECKPOINT)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--dimension", type=int, default=256)
    parser.add_argument(
        "--projection",
        choices=("none", "pairwise"),
        default="none",
        help=(
            "Projection to apply when model output dimension does not match --dimension. "
            "'pairwise' maps 2N values to N by scaled pair sums."
        ),
    )
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--check-deps", action="store_true")
    args = parser.parse_args()

    if args.check_deps:
        missing = missing_dependencies()
        print(json.dumps({"missing": missing}, ensure_ascii=False, indent=2))
        return 1 if missing else 0

    try:
        backend = MobileClip2Backend(args.checkpoint, args.device)
        summary = generate_embeddings(
            args.jobs,
            backend,
            expected_dimension=args.dimension,
            projection=args.projection,
            overwrite=args.overwrite,
            limit=args.limit,
        )
    except (OSError, ValueError, RuntimeError, ImportError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 1 if summary["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
