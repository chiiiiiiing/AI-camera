#!/usr/bin/env python3
"""Fine-tune a small encoder against a train-only student gallery; select on validation.

The initial checkpoint is a real baseline, including epoch zero in selection. Test
images are never read during selection. Model dimensions and the original teacher
projection remain unchanged, so the experiment isolates training-objective changes.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import random
import time
from pathlib import Path
from typing import Any

import numpy as np
import torch
from PIL import Image, ImageOps
from torch import nn
from torch.nn import functional as F
from torchvision import transforms

from evaluate_retrieval import evaluate_retrieval
from student_embedding_model import build_student_model, count_parameters


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_rows(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def validate_training_splits(rows: dict[str, list[dict[str, Any]]]) -> dict[str, Any]:
    """Reject train/val identity leakage before loading images or teacher vectors.

    Missing, blank, or explicit unknown source groups remain unverified; they
    are never treated as independent groups. Repeated known groups within one
    split are allowed, but cannot occur across train and validation.
    """
    identities: dict[str, dict[str, set[str]]] = {}
    unknown_groups: dict[str, int] = {}
    for split in ("train", "val"):
        records = rows.get(split)
        if not records:
            raise ValueError("train and val must be nonempty")
        seen = {field: set() for field in ("photo_id", "source_sha256", "source_group_id")}
        unknown_groups[split] = 0
        for index, row in enumerate(records):
            if not isinstance(row, dict):
                raise ValueError(f"{split}[{index}]: record must be an object")
            if row.get("split") != split:
                raise ValueError(f"{split}[{index}]: split must be {split!r}")
            for field in ("photo_id", "source_sha256"):
                value = row.get(field)
                if not isinstance(value, str) or not value.strip():
                    raise ValueError(f"{split}[{index}]: missing nonempty {field}")
                value = value.strip().lower() if field == "source_sha256" else value.strip()
                if value in seen[field]:
                    raise ValueError(f"{split}: duplicate {field} {value!r}")
                seen[field].add(value)
            group = row.get("source_group_id")
            if group is not None and not isinstance(group, str):
                raise ValueError(f"{split}[{index}]: source_group_id must be a string or null")
            group = group.strip() if group is not None else ""
            if group.lower() in {"", "unknown", "none", "null", "n/a"}:
                unknown_groups[split] += 1
            else:
                seen["source_group_id"].add(group)
        identities[split] = seen
    for field in ("photo_id", "source_sha256", "source_group_id"):
        overlap = identities["train"][field] & identities["val"][field]
        if overlap:
            raise ValueError(f"train/val {field} overlap: {sorted(overlap)[:3]}")
    return {
        "row_counts": {split: len(rows[split]) for split in ("train", "val")},
        "photo_id_disjoint": True,
        "source_sha256_disjoint": True,
        "known_source_group_disjoint": True,
        "known_source_group_counts": {split: len(identities[split]["source_group_id"]) for split in identities},
        "unknown_source_group_rows": unknown_groups,
        "unknown_groups_are_independent": False,
        "near_duplicate_detection": "not_performed",
        "test_split_read": False,
    }


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def load_images(rows: list[dict[str, Any]], size: int) -> torch.Tensor:
    images = []
    for row in rows:
        path = Path(row["source_path"])
        if sha256(path) != row["source_sha256"]:
            raise ValueError(f"source image hash mismatch: {row['photo_id']}")
        with Image.open(path) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            pixels = np.asarray(image.resize((size, size), Image.Resampling.BILINEAR)).copy()
        images.append(torch.from_numpy(pixels).permute(2, 0, 1))
    return torch.stack(images)  # uint8 CPU cache: lossless, no train/eval JPEG divergence


def load_teachers(rows: list[dict[str, Any]]) -> np.ndarray:
    vectors = []
    for row in rows:
        info = row["teacher_embedding"]
        path = Path(info["path"])
        expected_hash = info.get("sha256") or info.get("embedding_sha256")
        if expected_hash and sha256(path) != expected_hash:
            raise ValueError(f"teacher hash mismatch: {row['photo_id']}")
        vector = np.frombuffer(path.read_bytes(), dtype="<f4").copy()
        if len(vector) != int(info["dimension"]) or not np.isfinite(vector).all():
            raise ValueError(f"invalid teacher vector: {row['photo_id']}")
        norm = np.linalg.norm(vector.astype(np.float64))
        if norm <= 0:
            raise ValueError("zero teacher embedding")
        vectors.append(vector / norm)
    return np.stack(vectors).astype(np.float32)


def prepare_batch(images: torch.Tensor, device: str, *, augment: bool = False) -> torch.Tensor:
    values = images.to(device).float().div_(255.0)
    if augment:
        # Geometry stays fixed: cached teachers describe the unflipped, uncropped frame.
        # Each image receives an independent, mild exposure perturbation.
        gain = torch.empty((len(values), 1, 1, 1), device=device).uniform_(0.94, 1.06)
        values = (values * gain).clamp_(0.0, 1.0)
    return values.sub_(0.5).div_(0.5)


@torch.inference_mode()
def encode(model: nn.Module, images: torch.Tensor, device: str, batch_size: int) -> np.ndarray:
    model.eval()
    vectors = [model(prepare_batch(batch, device)).float().cpu().numpy()
               for batch in images.split(batch_size)]
    result = np.concatenate(vectors)
    if not np.isfinite(result).all() or np.any(np.linalg.norm(result, axis=1) < 1e-8):
        raise ValueError("encoder emitted invalid embeddings")
    return result


def full_gallery_distillation_loss(
    predictions: torch.Tensor,
    student_gallery: torch.Tensor,
    teacher_queries: torch.Tensor,
    teacher_gallery: torch.Tensor,
    self_indices: torch.Tensor,
    *,
    top_k: int = 16,
    student_temperature: float = 0.1,
    teacher_temperature: float = 0.07,
) -> torch.Tensor:
    """Teacher top-K targets, but ALL student negatives contribute to the denominator.

    Both sides exclude the query's own gallery identity. The detached student
    gallery is refreshed every epoch and represents the same encoder used at
    deployment, instead of comparing student queries to teacher-space targets.
    """
    if student_temperature <= 0 or teacher_temperature <= 0 or top_k <= 0:
        raise ValueError("temperatures and top_k must be positive")
    if len(student_gallery) < 2:
        raise ValueError("gallery must contain at least two examples")
    if len(predictions) != len(self_indices) or len(teacher_queries) != len(predictions):
        raise ValueError("query lengths disagree")
    if len(student_gallery) != len(teacher_gallery):
        raise ValueError("gallery lengths disagree")
    if torch.any(self_indices < 0) or torch.any(self_indices >= len(student_gallery)):
        raise ValueError("self index outside gallery")
    teacher_scores = F.normalize(teacher_queries, dim=1) @ F.normalize(teacher_gallery, dim=1).T
    student_scores = F.normalize(predictions, dim=1) @ F.normalize(student_gallery.detach(), dim=1).T
    mask = F.one_hot(self_indices.long(), num_classes=len(student_gallery)).bool()
    teacher_scores = teacher_scores.masked_fill(mask, -torch.inf)
    student_scores = student_scores.masked_fill(mask, -torch.inf)
    target_scores, neighbors = teacher_scores.topk(min(top_k, len(student_gallery) - 1), dim=1)
    target_probs = F.softmax(target_scores / teacher_temperature, dim=1).detach()
    log_probs = F.log_softmax(student_scores / student_temperature, dim=1).gather(1, neighbors)
    return F.kl_div(log_probs, target_probs, reduction="batchmean")


def selection_key(report: dict[str, Any], baseline: dict[str, Any]) -> tuple[float, ...] | None:
    """Freeze test; protect both validation scene recalls, then improve teacher neighbors."""
    raw, reference = report["raw"], baseline["raw"]
    for k in ("1", "5"):
        if raw["scene_macro_recall_at_k"][k] + 1e-12 < reference["scene_macro_recall_at_k"][k]:
            return None
    return (raw["teacher_top5_overlap"], raw["scene_macro_recall_at_k"]["5"],
            raw["scene_macro_recall_at_k"]["1"])


def optimize(args: argparse.Namespace) -> dict[str, Any]:
    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)
    torch.set_num_threads(args.cpu_threads)
    device = ("cuda" if torch.cuda.is_available() else "cpu") if args.device == "auto" else args.device
    if args.output_dir.exists() and any(args.output_dir.iterdir()):
        raise ValueError("output directory must be empty; preserve previous experiments")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    # User-owned checkpoint; weights_only prevents arbitrary pickled object loading.
    checkpoint = torch.load(args.init_checkpoint, map_location="cpu", weights_only=True)
    config = checkpoint["model"]
    model = build_student_model(config).to(device)
    model.load_state_dict(checkpoint["model_state"], strict=True)
    rows = {split: read_rows(args.dataset_dir / f"{split}.jsonl") for split in ("train", "val")}
    dataset_validation = validate_training_splits(rows)
    write_json(args.output_dir / "dataset-validation.json", dataset_validation)
    images = {split: load_images(records, int(config["image_size"])) for split, records in rows.items()}
    teachers = {split: load_teachers(records) for split, records in rows.items()}
    tensors = {split: torch.from_numpy(value).to(device) for split, value in teachers.items()}
    projection = checkpoint["projection"]
    mean = projection["mean"].float().to(device)
    components = projection["components"].float().to(device)
    targets = F.normalize((tensors["train"] - mean) @ components.T, dim=1)
    if not torch.isfinite(targets).all() or torch.any(targets.norm(dim=1) < 1e-8):
        raise ValueError("invalid projected teacher targets")

    def measure() -> tuple[dict[str, Any], np.ndarray, np.ndarray]:
        gallery = encode(model, images["train"], device, args.batch_size)
        queries = encode(model, images["val"], device, args.batch_size)
        report = evaluate_retrieval(
            queries, gallery, rows["val"], rows["train"],
            teacher_query_embeddings=teachers["val"], teacher_gallery_embeddings=teachers["train"],
            compare_android_rerank=True,
        )
        return report, gallery, queries

    baseline, gallery, _ = measure()
    write_json(args.output_dir / "baseline-validation.json", baseline)
    best_key = selection_key(baseline, baseline)
    best_epoch = 0
    best_report = baseline
    best_state = {k: value.detach().cpu().clone() for k, value in model.state_dict().items()}
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate, weight_decay=args.weight_decay)
    history = []
    start = time.monotonic()
    print(json.dumps({"event": "baseline", "device": device, "selection": best_key}), flush=True)
    for epoch in range(1, args.epochs + 1):
        lr = args.learning_rate * (0.1 + 0.9 * 0.5 * (1 + math.cos(math.pi * (epoch - 1) / max(args.epochs, 1))))
        for group in optimizer.param_groups:
            group["lr"] = lr
        # Refresh a detached student gallery from the complete train split only.
        bank = torch.from_numpy(gallery).to(device)
        model.train()
        # Keep calibrated BN statistics: 579 images are too few for stable running estimates.
        if not args.update_batchnorm:
            for module in model.modules():
                if isinstance(module, nn.modules.batchnorm._BatchNorm):
                    module.eval()
        order = torch.randperm(len(images["train"]))
        losses = []
        for indices in order.split(args.batch_size):
            idx = indices.to(device)
            batch = prepare_batch(images["train"][indices], device, augment=not args.no_augmentation)
            predicted = model(batch)
            anchor_loss = 1 - F.cosine_similarity(predicted, targets[idx], dim=1).mean()
            rank_loss = full_gallery_distillation_loss(
                predicted, bank, tensors["train"][idx], tensors["train"], idx,
                top_k=args.teacher_top_k, student_temperature=args.student_temperature,
                teacher_temperature=args.teacher_temperature,
            )
            loss = args.embedding_weight * anchor_loss + args.ranking_weight * rank_loss
            if not torch.isfinite(loss):
                raise ValueError("non-finite training loss")
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            optimizer.step()
            losses.append(float(loss.detach()))
        report, gallery, _ = measure()
        key = selection_key(report, baseline)
        selected = key is not None and key > best_key
        if selected:
            best_key, best_epoch, best_report = key, epoch, report
            best_state = {k: value.detach().cpu().clone() for k, value in model.state_dict().items()}
        row = {"epoch": epoch, "loss": sum(losses) / len(losses), "lr": lr,
               "validation": report, "eligible_selection_key": key, "selected": selected,
               "elapsed_seconds": time.monotonic() - start}
        history.append(row)
        print(json.dumps({k: v for k, v in row.items() if k != "validation"}), flush=True)
        write_json(args.output_dir / "history.json", history)
    model.load_state_dict(best_state)
    output = copy.deepcopy(checkpoint)
    output["model_state"] = best_state
    output["retrieval_optimization"] = {
        "source_checkpoint_sha256": sha256(args.init_checkpoint), "best_epoch": best_epoch,
        "selection": "validation macro scene recall@1/@5 non-decreasing, maximize teacher top5 overlap",
        "test_used_for_selection": False, "seed": args.seed,
        "training_gallery": "train only, detached student embeddings refreshed each epoch",
        "spatial_augmentation": False,
        "args": {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
        "dataset_sha256": {s: sha256(args.dataset_dir / f"{s}.jsonl") for s in rows},
        "dataset_validation": dataset_validation,
    }
    torch.save(output, args.output_dir / "checkpoint.best.pt")
    write_json(args.output_dir / "best-validation.json", best_report)
    gallery = encode(model, images["train"], device, args.batch_size)
    queries = encode(model, images["val"], device, args.batch_size)
    np.savez_compressed(args.output_dir / "validation-embeddings.npz", gallery=gallery, queries=queries)
    summary = {"model": config, "parameter_count": count_parameters(model), "best_epoch": best_epoch,
               "improved_validation": best_epoch > 0, "baseline_validation": baseline,
               "best_validation": best_report, "device": device, "seconds": time.monotonic() - start,
               "checkpoint_sha256": sha256(args.output_dir / "checkpoint.best.pt"),
               "test_evaluated": False, "provenance": output["retrieval_optimization"]}
    write_json(args.output_dir / "summary.json", summary)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", required=True, type=Path)
    parser.add_argument("--init-checkpoint", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--epochs", type=int, default=16)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--learning-rate", type=float, default=0.0001)
    parser.add_argument("--weight-decay", type=float, default=0.0001)
    parser.add_argument("--embedding-weight", type=float, default=1.0)
    parser.add_argument("--ranking-weight", type=float, default=0.3)
    parser.add_argument("--teacher-top-k", type=int, default=16)
    parser.add_argument("--student-temperature", type=float, default=0.1)
    parser.add_argument("--teacher-temperature", type=float, default=0.07)
    parser.add_argument("--seed", type=int, default=20261014)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--cpu-threads", type=int, default=4)
    parser.add_argument("--no-augmentation", action="store_true")
    parser.add_argument("--update-batchnorm", action="store_true")
    args = parser.parse_args()
    for name in ("epochs", "batch_size", "learning_rate", "teacher_top_k", "cpu_threads"):
        if getattr(args, name) <= 0:
            parser.error(f"{name} must be positive")
    optimize(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
