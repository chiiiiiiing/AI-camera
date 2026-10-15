#!/usr/bin/env python3
"""Train a lightweight student embedding model from teacher embeddings."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import struct
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import torch
from PIL import Image, ImageOps
from torch import nn
from torch.nn import functional as F
from torch.utils.data import DataLoader, Dataset
from torchvision import transforms

from student_embedding_model import (
    StudentModelConfig,
    build_student_model,
    config_to_dict,
    count_parameters,
)


IMAGE_MEAN = (0.5, 0.5, 0.5)
IMAGE_STD = (0.5, 0.5, 0.5)


@dataclass(frozen=True)
class ReferenceBank:
    targets: torch.Tensor
    teacher_embeddings: torch.Tensor
    photo_type_labels: torch.Tensor
    scene_labels: torch.Tensor

    def to(self, device: str) -> "ReferenceBank":
        return ReferenceBank(
            targets=self.targets.to(device),
            teacher_embeddings=self.teacher_embeddings.to(device),
            photo_type_labels=self.photo_type_labels.to(device),
            scene_labels=self.scene_labels.to(device),
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


def read_f32(path: Path, dimension: int) -> np.ndarray:
    data = path.read_bytes()
    expected = dimension * struct.calcsize("<f")
    if len(data) != expected:
        raise ValueError(f"{path}: byte size {len(data)} != expected {expected}")
    return np.frombuffer(data, dtype="<f4").astype(np.float32)


def resolve_path(path: str, base_dir: Path) -> Path:
    value = Path(path)
    if value.is_absolute():
        return value
    candidates = [
        (Path.cwd() / value).resolve(),
        (base_dir / value).resolve(),
        (Path(__file__).resolve().parent / value).resolve(),
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return candidates[0]


def teacher_embedding(record: dict[str, Any], base_dir: Path) -> np.ndarray:
    info = record.get("teacher_embedding")
    if not isinstance(info, dict):
        raise ValueError(f"{record.get('photo_id')}: missing teacher_embedding")
    dimension = int(info.get("dimension") or 0)
    if dimension <= 0:
        raise ValueError(f"{record.get('photo_id')}: invalid teacher dimension")
    path = resolve_path(str(info.get("path") or ""), base_dir)
    return read_f32(path, dimension)


def fit_teacher_projection(embeddings: np.ndarray, output_dim: int) -> dict[str, np.ndarray]:
    if embeddings.ndim != 2:
        raise ValueError("teacher embeddings must be a 2D array")
    if not np.isfinite(embeddings).all():
        raise ValueError("teacher embeddings contain non-finite values")
    if output_dim <= 0:
        raise ValueError("output_dim must be positive")
    if output_dim > min(embeddings.shape):
        raise ValueError(
            f"output_dim {output_dim} exceeds available PCA rank {min(embeddings.shape)}"
        )
    stable_embeddings = embeddings.astype(np.float64, copy=False)
    mean = stable_embeddings.mean(axis=0, keepdims=True)
    centered = stable_embeddings - mean
    _, _, vt = np.linalg.svd(centered, full_matrices=False)
    components = vt[:output_dim].astype(np.float32)
    if not np.isfinite(components).all():
        raise ValueError("teacher PCA components contain non-finite values")
    return {"mean": mean.squeeze(0).astype(np.float32), "components": components}


def project_teacher_embeddings(embeddings: np.ndarray, projection: dict[str, np.ndarray]) -> np.ndarray:
    if not np.isfinite(embeddings).all():
        raise ValueError("teacher embeddings contain non-finite values")
    embedding_tensor = torch.from_numpy(embeddings.astype(np.float64, copy=False))
    mean_tensor = torch.from_numpy(projection["mean"].astype(np.float64, copy=False)).reshape(1, -1)
    component_tensor = torch.from_numpy(projection["components"].astype(np.float64, copy=False))
    projected_tensor = (embedding_tensor - mean_tensor) @ component_tensor.T
    projected = projected_tensor.numpy()
    if not np.isfinite(projected).all():
        raise ValueError("projected teacher embeddings contain non-finite values")
    norms = np.linalg.norm(projected, axis=1, keepdims=True)
    norms = np.maximum(norms, 1e-12)
    return (projected / norms).astype(np.float32)


def transform_for_split(image_size: int, train: bool, pre_resized: bool = False,
                        spatial_augmentation: bool = False) -> transforms.Compose:
    if train:
        if not spatial_augmentation:
            # Cached teacher targets describe the original composition. Cropping or
            # mirroring only the student input teaches the wrong spatial invariance.
            return transforms.Compose([
                transforms.Resize((image_size, image_size)),
                transforms.ColorJitter(brightness=0.06, contrast=0.06, saturation=0.04),
                transforms.ToTensor(),
                transforms.Normalize(IMAGE_MEAN, IMAGE_STD),
            ])
        steps: list[Any] = []
        if not pre_resized:
            steps.append(transforms.Resize((image_size + 16, image_size + 16)))
        steps.extend(
            [
                transforms.RandomResizedCrop(image_size, scale=(0.82, 1.0), ratio=(0.9, 1.1)),
                transforms.RandomHorizontalFlip(p=0.5),
                transforms.ColorJitter(brightness=0.08, contrast=0.08, saturation=0.06, hue=0.02),
                transforms.ToTensor(),
                transforms.Normalize(IMAGE_MEAN, IMAGE_STD),
            ]
        )
        return transforms.Compose(steps)
    steps = []
    if not pre_resized:
        steps.append(transforms.Resize((image_size, image_size)))
    steps.extend(
        [
            transforms.ToTensor(),
            transforms.Normalize(IMAGE_MEAN, IMAGE_STD),
        ]
    )
    return transforms.Compose(steps)


def resize_for_cache(image: Image.Image, image_size: int, train: bool) -> Image.Image:
    target_size = image_size + 16 if train else image_size
    return image.resize((target_size, target_size), Image.Resampling.BILINEAR)


def load_resized_image(image_path: Path, image_size: int, train: bool) -> Image.Image:
    target_size = image_size + 16 if train else image_size
    with Image.open(image_path) as image:
        try:
            image.draft("RGB", (target_size, target_size))
        except (AttributeError, OSError):
            pass
        image = ImageOps.exif_transpose(image).convert("RGB")
        return resize_for_cache(image, image_size, train).copy()


def cache_image_path(cache_dir: Path, split: str, photo_id: str, image_size: int, train: bool) -> Path:
    suffix = "train" if train else "eval"
    return cache_dir / split / f"{photo_id}.{image_size}.{suffix}.v2.png"


class DistillationImageDataset(
    Dataset[tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]]
):
    def __init__(
        self,
        rows: list[dict[str, Any]],
        targets_by_photo_id: dict[str, np.ndarray],
        labels_by_photo_id: dict[str, tuple[int, int]],
        sample_indices_by_photo_id: dict[str, int],
        image_size: int,
        train: bool,
        base_dir: Path,
        split: str,
        cache_images: bool = False,
        image_cache_dir: Path | None = None,
        spatial_augmentation: bool = False,
    ) -> None:
        self.rows = rows
        self.targets_by_photo_id = targets_by_photo_id
        self.labels_by_photo_id = labels_by_photo_id
        self.sample_indices_by_photo_id = sample_indices_by_photo_id
        self.transform = transform_for_split(image_size, train, pre_resized=cache_images,
                                            spatial_augmentation=spatial_augmentation)
        self.base_dir = base_dir
        self.cached_images: list[Image.Image] | None = None
        if cache_images:
            self.cached_images = []
            for index, record in enumerate(rows, 1):
                photo_id = str(record.get("photo_id"))
                image_path = resolve_path(str(record.get("source_path") or ""), self.base_dir)
                source_hash = hashlib.sha256(image_path.read_bytes()).hexdigest()
                prepared_path = (
                    cache_image_path(image_cache_dir, split, photo_id + "_" + source_hash[:16], image_size, train)
                    if image_cache_dir is not None
                    else None
                )
                if prepared_path is not None and prepared_path.exists():
                    with Image.open(prepared_path) as image:
                        resized = image.convert("RGB").copy()
                else:
                    resized = load_resized_image(image_path, image_size, train)
                    if prepared_path is not None:
                        prepared_path.parent.mkdir(parents=True, exist_ok=True)
                        resized.save(prepared_path, format="PNG")
                self.cached_images.append(resized)
                if index % 100 == 0 or index == len(rows):
                    print(
                        json.dumps(
                            {
                                "event": "image_cache_progress",
                                "split": split,
                                "cached": index,
                                "total": len(rows),
                            },
                            ensure_ascii=False,
                            sort_keys=True,
                        ),
                        file=sys.stderr,
                        flush=True,
                    )

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(
        self,
        index: int,
    ) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
        record = self.rows[index]
        photo_id = str(record.get("photo_id"))
        if self.cached_images is None:
            image_path = resolve_path(str(record.get("source_path") or ""), self.base_dir)
            with Image.open(image_path) as image:
                image = ImageOps.exif_transpose(image).convert("RGB")
                tensor = self.transform(image)
        else:
            tensor = self.transform(self.cached_images[index])
        target = torch.from_numpy(self.targets_by_photo_id[photo_id])
        photo_type_label, scene_label = self.labels_by_photo_id[photo_id]
        return (
            tensor,
            target,
            torch.tensor(photo_type_label, dtype=torch.long),
            torch.tensor(scene_label, dtype=torch.long),
            torch.tensor(self.sample_indices_by_photo_id[photo_id], dtype=torch.long),
        )


class StudentTrainingModule(nn.Module):
    def __init__(self, encoder: nn.Module, output_dim: int, photo_type_count: int, scene_count: int) -> None:
        super().__init__()
        self.encoder = encoder
        self.photo_type_head = nn.Linear(output_dim, photo_type_count)
        self.scene_head = nn.Linear(output_dim, scene_count)

    def forward(self, images: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        embedding = self.encoder(images)
        return embedding, self.photo_type_head(embedding), self.scene_head(embedding)


def choose_device(requested: str) -> str:
    if requested != "auto":
        return requested
    if torch.cuda.is_available():
        return "cuda"
    if torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def seed_everything(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


def cosine_loss(prediction: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    prediction = F.normalize(prediction, p=2, dim=1)
    target = F.normalize(target, p=2, dim=1)
    return 1.0 - F.cosine_similarity(prediction, target, dim=1).mean()


def relation_loss(prediction: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    if prediction.shape[0] < 2:
        return prediction.new_tensor(0.0)
    prediction = F.normalize(prediction, p=2, dim=1)
    target = F.normalize(target, p=2, dim=1)
    return F.mse_loss(prediction @ prediction.T, target @ target.T)


def teacher_topk_listwise_loss(
    prediction: torch.Tensor,
    sample_indices: torch.Tensor,
    reference_bank: ReferenceBank,
    top_k: int,
    student_temperature: float,
    teacher_temperature: float,
) -> torch.Tensor:
    if top_k <= 0 or reference_bank.targets.shape[0] < 2:
        return prediction.new_tensor(0.0)
    if student_temperature <= 0.0 or teacher_temperature <= 0.0:
        raise ValueError("listwise temperatures must be positive")

    prediction = F.normalize(prediction, p=2, dim=1)
    reference_targets = F.normalize(reference_bank.targets, p=2, dim=1)
    reference_teacher = F.normalize(reference_bank.teacher_embeddings, p=2, dim=1)
    sample_indices = sample_indices.to(dtype=torch.long)

    teacher_scores = reference_teacher.index_select(0, sample_indices) @ reference_teacher.T
    row_indices = torch.arange(sample_indices.shape[0], device=sample_indices.device)
    teacher_scores = teacher_scores.clone()
    teacher_scores[row_indices, sample_indices] = torch.finfo(teacher_scores.dtype).min

    effective_top_k = min(top_k, reference_bank.targets.shape[0] - 1)
    top_teacher_scores, top_indices = torch.topk(teacher_scores, k=effective_top_k, dim=1)
    student_scores = prediction @ reference_targets.T
    teacher_probs = F.softmax(top_teacher_scores / teacher_temperature, dim=1).detach()
    # Penalize every wrong candidate, including those outside teacher top-K.
    # The query itself is excluded from both distributions.
    student_scores = student_scores.clone()
    student_scores[row_indices, sample_indices] = -torch.inf
    student_log_probs = F.log_softmax(student_scores / student_temperature, dim=1).gather(1, top_indices)
    return F.kl_div(student_log_probs, teacher_probs, reduction="batchmean")


def hard_negative_margin_loss(
    prediction: torch.Tensor,
    sample_indices: torch.Tensor,
    reference_bank: ReferenceBank,
    positive_count: int,
    negative_count: int,
    margin: float,
    same_photo_type_only: bool,
) -> torch.Tensor:
    if positive_count <= 0 or negative_count <= 0 or margin <= 0.0 or reference_bank.targets.shape[0] < 3:
        return prediction.new_tensor(0.0)

    prediction = F.normalize(prediction, p=2, dim=1)
    reference_targets = F.normalize(reference_bank.targets, p=2, dim=1)
    reference_teacher = F.normalize(reference_bank.teacher_embeddings, p=2, dim=1)
    sample_indices = sample_indices.to(dtype=torch.long)
    teacher_scores = reference_teacher.index_select(0, sample_indices) @ reference_teacher.T
    student_scores = prediction @ reference_targets.T

    losses: list[torch.Tensor] = []
    for row_index in range(sample_indices.shape[0]):
        anchor_index = int(sample_indices[row_index].detach().cpu())
        anchor_scene = reference_bank.scene_labels[anchor_index]
        anchor_photo_type = reference_bank.photo_type_labels[anchor_index]

        positive_mask = reference_bank.scene_labels == anchor_scene
        positive_mask = positive_mask.clone()
        positive_mask[anchor_index] = False
        negative_mask = reference_bank.scene_labels != anchor_scene
        if same_photo_type_only:
            negative_mask = negative_mask & (reference_bank.photo_type_labels == anchor_photo_type)

        positive_indices = torch.nonzero(positive_mask, as_tuple=False).flatten()
        negative_indices = torch.nonzero(negative_mask, as_tuple=False).flatten()
        if positive_indices.numel() == 0 or negative_indices.numel() == 0:
            continue

        positive_take = min(positive_count, int(positive_indices.numel()))
        negative_take = min(negative_count, int(negative_indices.numel()))
        positive_order = torch.topk(
            teacher_scores[row_index, positive_indices],
            k=positive_take,
            dim=0,
        ).indices
        negative_order = torch.topk(
            teacher_scores[row_index, negative_indices],
            k=negative_take,
            dim=0,
        ).indices
        selected_positive = positive_indices[positive_order]
        selected_negative = negative_indices[negative_order]

        positive_scores = student_scores[row_index, selected_positive]
        negative_scores = student_scores[row_index, selected_negative]
        losses.append(F.relu(margin - positive_scores[:, None] + negative_scores[None, :]).mean())

    if not losses:
        return prediction.new_tensor(0.0)
    return torch.stack(losses).mean()


def run_epoch(
    model: StudentTrainingModule,
    loader: DataLoader[tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]],
    reference_bank: ReferenceBank,
    device: str,
    optimizer: torch.optim.Optimizer | None,
    args: argparse.Namespace,
) -> dict[str, float]:
    train = optimizer is not None
    model.train(train)
    total_loss = 0.0
    total_embedding_loss = 0.0
    total_relation_loss = 0.0
    total_listwise_loss = 0.0
    total_hard_negative_loss = 0.0
    total_photo_type_loss = 0.0
    total_scene_loss = 0.0
    total_cosine = 0.0
    total = 0
    for images, targets, photo_type_labels, scene_labels, sample_indices in loader:
        images = images.to(device)
        targets = targets.to(device)
        photo_type_labels = photo_type_labels.to(device)
        scene_labels = scene_labels.to(device)
        sample_indices = sample_indices.to(device)
        if train:
            optimizer.zero_grad(set_to_none=True)
        with torch.set_grad_enabled(train):
            predictions, photo_logits, scene_logits = model(images)
            embedding_loss = cosine_loss(predictions, targets)
            batch_relation_loss = relation_loss(predictions, targets)
            batch_listwise_loss = (
                teacher_topk_listwise_loss(
                    predictions,
                    sample_indices,
                    reference_bank,
                    args.teacher_top_k,
                    args.listwise_temperature,
                    args.teacher_temperature,
                )
                if args.listwise_loss_weight > 0.0
                else predictions.new_tensor(0.0)
            )
            batch_hard_negative_loss = (
                hard_negative_margin_loss(
                    predictions,
                    sample_indices,
                    reference_bank,
                    args.hard_negative_positives,
                    args.hard_negative_count,
                    args.hard_negative_margin,
                    not args.hard_negative_cross_photo_type,
                )
                if args.hard_negative_loss_weight > 0.0
                else predictions.new_tensor(0.0)
            )
            photo_type_loss = F.cross_entropy(photo_logits, photo_type_labels)
            scene_loss = F.cross_entropy(scene_logits, scene_labels)
            loss = (
                args.embedding_loss_weight * embedding_loss
                + args.relation_loss_weight * batch_relation_loss
                + args.listwise_loss_weight * batch_listwise_loss
                + args.hard_negative_loss_weight * batch_hard_negative_loss
                + args.photo_type_loss_weight * photo_type_loss
                + args.scene_loss_weight * scene_loss
            )
            if train:
                loss.backward()
                optimizer.step()
        batch_size = int(images.shape[0])
        cosine = 1.0 - float(embedding_loss.detach().cpu())
        total_loss += float(loss.detach().cpu()) * batch_size
        total_embedding_loss += float(embedding_loss.detach().cpu()) * batch_size
        total_relation_loss += float(batch_relation_loss.detach().cpu()) * batch_size
        total_listwise_loss += float(batch_listwise_loss.detach().cpu()) * batch_size
        total_hard_negative_loss += float(batch_hard_negative_loss.detach().cpu()) * batch_size
        total_photo_type_loss += float(photo_type_loss.detach().cpu()) * batch_size
        total_scene_loss += float(scene_loss.detach().cpu()) * batch_size
        total_cosine += cosine * batch_size
        total += batch_size
    if total == 0:
        return {"loss": math.nan, "cosine": math.nan}
    return {
        "loss": total_loss / total,
        "embedding_loss": total_embedding_loss / total,
        "relation_loss": total_relation_loss / total,
        "listwise_loss": total_listwise_loss / total,
        "hard_negative_loss": total_hard_negative_loss / total,
        "photo_type_loss": total_photo_type_loss / total,
        "scene_loss": total_scene_loss / total,
        "cosine": total_cosine / total,
    }


def set_learning_rate(optimizer: torch.optim.Optimizer, args: argparse.Namespace, epoch: int) -> float:
    if args.lr_schedule == "cosine":
        if args.epochs <= 1:
            progress = 1.0
        else:
            progress = (epoch - 1) / (args.epochs - 1)
        learning_rate = args.min_learning_rate + 0.5 * (
            args.learning_rate - args.min_learning_rate
        ) * (1.0 + math.cos(math.pi * progress))
    else:
        learning_rate = args.learning_rate
    for group in optimizer.param_groups:
        group["lr"] = learning_rate
    return learning_rate


def checkpoint_payload(
    model: nn.Module,
    config: StudentModelConfig,
    projection: dict[str, np.ndarray],
    args: argparse.Namespace,
    metrics: dict[str, Any],
    split_counts: dict[str, int],
    label_maps: dict[str, dict[str, int]],
) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "model": config_to_dict(config),
        "model_state": model.cpu().state_dict(),
        "projection": {
            "teacher_dimension": int(projection["mean"].shape[0]),
            "output_dim": int(projection["components"].shape[0]),
            "mean": torch.from_numpy(projection["mean"].astype(np.float32)),
            "components": torch.from_numpy(projection["components"].astype(np.float32)),
        },
        "training": {
            "dataset_dir": str(args.dataset_dir),
            "epochs": args.epochs,
            "batch_size": args.batch_size,
            "learning_rate": args.learning_rate,
            "lr_schedule": args.lr_schedule,
            "min_learning_rate": args.min_learning_rate,
            "weight_decay": args.weight_decay,
            "seed": args.seed,
            "split_counts": split_counts,
            "label_maps": label_maps,
            "loss_weights": {
                "embedding": args.embedding_loss_weight,
                "relation": args.relation_loss_weight,
                "listwise": args.listwise_loss_weight,
                "hard_negative": args.hard_negative_loss_weight,
                "photo_type": args.photo_type_loss_weight,
                "scene": args.scene_loss_weight,
            },
            "ranking": {
                "teacher_top_k": args.teacher_top_k,
                "listwise_temperature": args.listwise_temperature,
                "teacher_temperature": args.teacher_temperature,
                "hard_negative_margin": args.hard_negative_margin,
                "hard_negative_positives": args.hard_negative_positives,
                "hard_negative_count": args.hard_negative_count,
                "hard_negative_same_photo_type_only": not args.hard_negative_cross_photo_type,
            },
            "cache_images": bool(args.cache_images),
            "image_cache_dir": str(args.image_cache_dir) if args.image_cache_dir else None,
            "metrics": metrics,
        },
    }


def build_label_maps(rows_by_split: dict[str, list[dict[str, Any]]]) -> dict[str, dict[str, int]]:
    all_rows = [row for rows in rows_by_split.values() for row in rows]
    photo_types = sorted({str(row.get("photo_type")) for row in all_rows})
    scenes = sorted({str(row.get("scene_category")) for row in all_rows})
    return {
        "photo_type": {value: index for index, value in enumerate(photo_types)},
        "scene_category": {value: index for index, value in enumerate(scenes)},
    }


def labels_by_photo_id(
    rows_by_split: dict[str, list[dict[str, Any]]],
    label_maps: dict[str, dict[str, int]],
) -> dict[str, tuple[int, int]]:
    labels: dict[str, tuple[int, int]] = {}
    for rows in rows_by_split.values():
        for row in rows:
            labels[str(row["photo_id"])] = (
                label_maps["photo_type"][str(row.get("photo_type"))],
                label_maps["scene_category"][str(row.get("scene_category"))],
            )
    return labels


def sample_indices_by_photo_id(rows: list[dict[str, Any]]) -> dict[str, int]:
    return {str(row["photo_id"]): index for index, row in enumerate(rows)}


def build_reference_bank(
    rows: list[dict[str, Any]],
    targets_by_photo_id: dict[str, np.ndarray],
    teacher_by_photo_id: dict[str, np.ndarray],
    labels_by_id: dict[str, tuple[int, int]],
) -> ReferenceBank:
    if not rows:
        target_dim = len(next(iter(targets_by_photo_id.values()))) if targets_by_photo_id else 0
        teacher_dim = len(next(iter(teacher_by_photo_id.values()))) if teacher_by_photo_id else 0
        return ReferenceBank(
            targets=torch.zeros((0, target_dim), dtype=torch.float32),
            teacher_embeddings=torch.zeros((0, teacher_dim), dtype=torch.float32),
            photo_type_labels=torch.zeros((0,), dtype=torch.long),
            scene_labels=torch.zeros((0,), dtype=torch.long),
        )
    photo_ids = [str(row["photo_id"]) for row in rows]
    targets = np.stack([targets_by_photo_id[photo_id] for photo_id in photo_ids], axis=0)
    teachers = np.stack([teacher_by_photo_id[photo_id] for photo_id in photo_ids], axis=0)
    photo_type_labels = [labels_by_id[photo_id][0] for photo_id in photo_ids]
    scene_labels = [labels_by_id[photo_id][1] for photo_id in photo_ids]
    return ReferenceBank(
        targets=torch.from_numpy(targets.astype(np.float32, copy=False)),
        teacher_embeddings=torch.from_numpy(teachers.astype(np.float32, copy=False)),
        photo_type_labels=torch.tensor(photo_type_labels, dtype=torch.long),
        scene_labels=torch.tensor(scene_labels, dtype=torch.long),
    )


def train_student(args: argparse.Namespace) -> dict[str, Any]:
    seed_everything(args.seed)
    dataset_dir = args.dataset_dir
    rows_by_split = {
        split: load_jsonl(dataset_dir / f"{split}.jsonl")
        for split in ("train", "val", "test")
    }
    if args.max_train_items:
        rows_by_split["train"] = rows_by_split["train"][: args.max_train_items]
    base_dir = Path.cwd()
    teacher_by_photo_id: dict[str, np.ndarray] = {}
    total_teacher_rows = sum(len(rows) for rows in rows_by_split.values())
    loaded_teacher_rows = 0
    for split_name, split_rows in rows_by_split.items():
        for row in split_rows:
            photo_id = str(row["photo_id"])
            teacher_by_photo_id[photo_id] = teacher_embedding(row, base_dir)
            loaded_teacher_rows += 1
            if loaded_teacher_rows % 100 == 0 or loaded_teacher_rows == total_teacher_rows:
                print(
                    json.dumps(
                        {
                            "event": "teacher_embedding_load_progress",
                            "split": split_name,
                            "loaded": loaded_teacher_rows,
                            "total": total_teacher_rows,
                        },
                        ensure_ascii=False,
                        sort_keys=True,
                    ),
                    file=sys.stderr,
                    flush=True,
                )

    train_teacher = np.stack(
        [teacher_by_photo_id[str(row["photo_id"])] for row in rows_by_split["train"]],
        axis=0,
    )
    projection = fit_teacher_projection(train_teacher, args.output_dim)
    targets_by_photo_id: dict[str, np.ndarray] = {}
    for split_rows in rows_by_split.values():
        if not split_rows:
            continue
        split_teacher = np.stack(
            [teacher_by_photo_id[str(row["photo_id"])] for row in split_rows],
            axis=0,
        )
        split_targets = project_teacher_embeddings(split_teacher, projection)
        for record, target in zip(split_rows, split_targets):
            photo_id = str(record["photo_id"])
            targets_by_photo_id[photo_id] = target

    config = StudentModelConfig(
        architecture=args.architecture,
        output_dim=args.output_dim,
        image_size=args.image_size,
        width_mult=args.width_mult,
        dropout=args.dropout,
    )
    encoder = build_student_model(config)
    parameter_count = count_parameters(encoder)
    label_maps = build_label_maps(rows_by_split)
    labels = labels_by_photo_id(rows_by_split, label_maps)
    sample_indices = {
        split: sample_indices_by_photo_id(rows)
        for split, rows in rows_by_split.items()
    }
    model = StudentTrainingModule(
        encoder,
        args.output_dim,
        photo_type_count=len(label_maps["photo_type"]),
        scene_count=len(label_maps["scene_category"]),
    )
    training_parameter_count = count_parameters(model)
    device = choose_device(args.device)
    model.to(device)
    reference_banks = {
        split: build_reference_bank(rows, targets_by_photo_id, teacher_by_photo_id, labels).to(device)
        for split, rows in rows_by_split.items()
    }
    loaders = {
        split: DataLoader(
            DistillationImageDataset(
                rows,
                targets_by_photo_id,
                labels,
                sample_indices[split],
                args.image_size,
                train=(split == "train"),
                base_dir=base_dir,
                split=split,
                cache_images=args.cache_images,
                image_cache_dir=args.image_cache_dir,
                spatial_augmentation=getattr(args, "spatial_augmentation", False),
            ),
            batch_size=args.batch_size,
            shuffle=(split == "train"),
            num_workers=args.num_workers,
        )
        for split, rows in rows_by_split.items()
    }
    optimizer = torch.optim.AdamW(
        model.parameters(),
        lr=args.learning_rate,
        weight_decay=args.weight_decay,
    )

    args.output_dir.mkdir(parents=True, exist_ok=True)
    history: list[dict[str, Any]] = []
    best_val = -1.0
    best_epoch = 0
    best_path = args.output_dir / "checkpoint.best.pt"
    for epoch in range(1, args.epochs + 1):
        epoch_learning_rate = set_learning_rate(optimizer, args, epoch)
        train_metrics = run_epoch(model, loaders["train"], reference_banks["train"], device, optimizer, args)
        val_metrics = run_epoch(model, loaders["val"], reference_banks["val"], device, None, args)
        row = {
            "epoch": epoch,
            "learning_rate": epoch_learning_rate,
            "train_loss": train_metrics["loss"],
            "train_embedding_loss": train_metrics["embedding_loss"],
            "train_relation_loss": train_metrics["relation_loss"],
            "train_listwise_loss": train_metrics["listwise_loss"],
            "train_hard_negative_loss": train_metrics["hard_negative_loss"],
            "train_photo_type_loss": train_metrics["photo_type_loss"],
            "train_scene_loss": train_metrics["scene_loss"],
            "train_cosine": train_metrics["cosine"],
            "val_loss": val_metrics["loss"],
            "val_embedding_loss": val_metrics["embedding_loss"],
            "val_relation_loss": val_metrics["relation_loss"],
            "val_listwise_loss": val_metrics["listwise_loss"],
            "val_hard_negative_loss": val_metrics["hard_negative_loss"],
            "val_photo_type_loss": val_metrics["photo_type_loss"],
            "val_scene_loss": val_metrics["scene_loss"],
            "val_cosine": val_metrics["cosine"],
        }
        history.append(row)
        print(json.dumps(row, ensure_ascii=False, sort_keys=True), flush=True)
        if val_metrics["cosine"] > best_val:
            best_val = val_metrics["cosine"]
            best_epoch = epoch
            model.to("cpu")
            best_payload = checkpoint_payload(
                    model.encoder,
                    config,
                    projection,
                    args,
                    {"history": history, "best_epoch": best_epoch, "best_val_cosine": best_val},
                    {split: len(rows) for split, rows in rows_by_split.items()},
                    label_maps,
                )
            # Preserve auxiliary heads too, so best-epoch evaluation is coherent.
            best_payload["training_module_state"] = model.state_dict()
            torch.save(best_payload, best_path)
            model.to(device)

    last_path = args.output_dir / "checkpoint.last.pt"
    model.to("cpu")
    final_metrics = {
        "history": history,
        "best_epoch": best_epoch,
        "best_val_cosine": best_val,
    }
    torch.save(
        checkpoint_payload(
            model.encoder,
            config,
            projection,
            args,
            final_metrics,
            {split: len(rows) for split, rows in rows_by_split.items()},
            label_maps,
        ),
        last_path,
    )
    best_payload = torch.load(best_path, map_location=device, weights_only=True)
    model.to(device)
    model.load_state_dict(best_payload["training_module_state"])
    test_metrics = run_epoch(model, loaders["test"], reference_banks["test"], device, None, args)
    final_metrics.update({"test_loss": test_metrics["loss"], "test_cosine": test_metrics["cosine"],
                          "test_checkpoint": str(best_path), "test_epoch": best_epoch})
    summary = {
        "schema_version": 1,
        "dataset_dir": str(args.dataset_dir),
        "output_dir": str(args.output_dir),
        "checkpoint_best": str(best_path),
        "checkpoint_last": str(last_path),
        "device": device,
        "model": config_to_dict(config),
        "parameter_count": parameter_count,
        "training_parameter_count": training_parameter_count,
        "teacher_dimension": int(projection["mean"].shape[0]),
        "target_dimension": args.output_dim,
        "split_counts": {split: len(rows) for split, rows in rows_by_split.items()},
        "label_maps": label_maps,
        "loss_weights": {
            "embedding": args.embedding_loss_weight,
            "relation": args.relation_loss_weight,
            "listwise": args.listwise_loss_weight,
            "hard_negative": args.hard_negative_loss_weight,
            "photo_type": args.photo_type_loss_weight,
            "scene": args.scene_loss_weight,
        },
        "ranking": {
            "teacher_top_k": args.teacher_top_k,
            "listwise_temperature": args.listwise_temperature,
            "teacher_temperature": args.teacher_temperature,
            "hard_negative_margin": args.hard_negative_margin,
            "hard_negative_positives": args.hard_negative_positives,
            "hard_negative_count": args.hard_negative_count,
            "hard_negative_same_photo_type_only": not args.hard_negative_cross_photo_type,
        },
        "lr_schedule": args.lr_schedule,
        "min_learning_rate": args.min_learning_rate,
        "image_cache_dir": str(args.image_cache_dir) if args.image_cache_dir else None,
        "metrics": final_metrics,
    }
    (args.output_dir / "training_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset_dir", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--architecture", default="tiny_cnn_v1", choices=("tiny_cnn_v1", "lite_cnn_v2"))
    parser.add_argument("--output-dim", type=int, default=256)
    parser.add_argument("--image-size", type=int, default=160)
    parser.add_argument("--width-mult", type=float, default=0.75)
    parser.add_argument("--dropout", type=float, default=0.1)
    parser.add_argument("--epochs", type=int, default=12)
    parser.add_argument("--batch-size", type=int, default=24)
    parser.add_argument("--learning-rate", type=float, default=1e-3)
    parser.add_argument("--lr-schedule", choices=("none", "cosine"), default="none")
    parser.add_argument("--min-learning-rate", type=float, default=1e-5)
    parser.add_argument("--weight-decay", type=float, default=1e-4)
    parser.add_argument("--embedding-loss-weight", type=float, default=1.0)
    parser.add_argument("--relation-loss-weight", type=float, default=0.35)
    parser.add_argument("--listwise-loss-weight", type=float, default=0.0)
    parser.add_argument("--teacher-top-k", type=int, default=16)
    parser.add_argument("--listwise-temperature", type=float, default=0.07)
    parser.add_argument("--teacher-temperature", type=float, default=0.07)
    parser.add_argument("--hard-negative-loss-weight", type=float, default=0.0)
    parser.add_argument("--hard-negative-margin", type=float, default=0.05)
    parser.add_argument("--hard-negative-positives", type=int, default=4)
    parser.add_argument("--hard-negative-count", type=int, default=8)
    parser.add_argument("--hard-negative-cross-photo-type", action="store_true")
    parser.add_argument("--photo-type-loss-weight", type=float, default=0.08)
    parser.add_argument("--scene-loss-weight", type=float, default=0.16)
    parser.add_argument("--seed", type=int, default=20260718)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--num-workers", type=int, default=0)
    parser.add_argument("--max-train-items", type=int)
    parser.add_argument("--cache-images", action="store_true")
    parser.add_argument("--image-cache-dir", type=Path)
    parser.add_argument("--spatial-augmentation", action="store_true",
                        help="Legacy crop/flip augmentation; changes composition relative to cached teacher targets")
    args = parser.parse_args()
    try:
        summary = train_student(args)
    except (OSError, ValueError, KeyError, json.JSONDecodeError, RuntimeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
