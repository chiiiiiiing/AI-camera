#!/usr/bin/env python3
"""Evaluate held-out embeddings against an independent training gallery with NumPy.

Scene recall@K here is a hit rate: at least one same-scene gallery item in the
first K results. It does not measure composition quality within a scene.
"""

from __future__ import annotations

import argparse
import json
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

import numpy as np


def _normalize(values: Any, name: str) -> np.ndarray:
    try:
        original = np.asarray(values)
        if original.dtype.kind not in "fiu":
            raise ValueError("expected real numeric values")
        matrix = np.asarray(original, dtype=np.float64)
    except (TypeError, ValueError, OverflowError) as error:
        raise ValueError(f"{name}: expected a real numeric matrix") from error
    if matrix.ndim != 2 or min(matrix.shape) < 1:
        raise ValueError(f"{name}: expected a nonempty [rows, dimensions] matrix")
    if not np.isfinite(matrix).all():
        raise ValueError(f"{name}: embeddings must be finite")
    # Scaling first avoids overflow/underflow while checking every nonzero norm.
    scales = np.max(np.abs(matrix), axis=1, keepdims=True)
    if np.any(scales == 0):
        raise ValueError(f"{name}: zero-norm embedding")
    scaled = matrix / scales
    norms = np.linalg.norm(scaled, axis=1, keepdims=True)
    if not np.isfinite(norms).all() or np.any(norms <= 0):
        raise ValueError(f"{name}: invalid L2 norm")
    return scaled / norms


def _validate_records(
    records: Sequence[Mapping[str, Any]], rows: int, name: str
) -> list[dict[str, Any]]:
    if len(records) != rows:
        raise ValueError(f"{name}: {len(records)} records for {rows} embeddings")
    result: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for index, record in enumerate(records):
        if not isinstance(record, Mapping):
            raise ValueError(f"{name}[{index}]: record must be an object")
        normalized = dict(record)
        for field in ("photo_id", "source_sha256", "scene_category"):
            value = record.get(field)
            if not isinstance(value, str) or not value.strip():
                raise ValueError(f"{name}[{index}]: missing nonempty {field}")
            normalized[field] = value.strip()
        normalized["source_sha256"] = normalized["source_sha256"].lower()
        if normalized["scene_category"].lower() in {"unknown", "none", "not_visible"}:
            raise ValueError(f"{name}[{index}]: scene_category must be a known label")
        photo_id = normalized["photo_id"]
        if photo_id in seen_ids:
            raise ValueError(f"{name}: duplicate photo_id {photo_id!r}")
        seen_ids.add(photo_id)
        result.append(normalized)
    return result


def _metrics(
    rankings: np.ndarray,
    query_records: list[dict[str, Any]],
    gallery_records: list[dict[str, Any]],
    ks: tuple[int, ...],
    *,
    mrr_scope: str,
) -> dict[str, Any]:
    query_scenes = np.asarray([row["scene_category"] for row in query_records])
    gallery_scenes = np.asarray([row["scene_category"] for row in gallery_records])
    matches = gallery_scenes[rankings] == query_scenes[:, None]
    found = matches.any(axis=1)
    reciprocal_ranks = np.where(found, 1.0 / (matches.argmax(axis=1) + 1), 0.0)
    hits = {str(k): matches[:, :k].any(axis=1) for k in ks}
    per_scene: dict[str, Any] = {}
    for scene in sorted(set(query_scenes.tolist())):
        selected = query_scenes == scene
        gallery_count = int(np.count_nonzero(gallery_scenes == scene))
        per_scene[scene] = {
            "query_count": int(np.count_nonzero(selected)),
            "gallery_count": gallery_count,
            "scene_recall_at_k": {key: float(value[selected].mean()) for key, value in hits.items()},
            "mrr": float(reciprocal_ranks[selected].mean()),
        }
    return {
        "query_count": len(query_records),
        "gallery_count": len(gallery_records),
        "eligible_query_count": int(np.isin(query_scenes, gallery_scenes).sum()),
        "effective_k": {str(k): min(k, rankings.shape[1]) for k in ks},
        "scene_micro_recall_at_k": {key: float(value.mean()) for key, value in hits.items()},
        "scene_macro_recall_at_k": {
            str(k): float(np.mean([value["scene_recall_at_k"][str(k)] for value in per_scene.values()]))
            for k in ks
        },
        "mrr": float(reciprocal_ranks.mean()),
        "mrr_scope": mrr_scope,
        "per_scene": per_scene,
    }


def _composition(record: Mapping[str, Any]) -> str:
    annotation = record.get("annotation")
    composition = annotation.get("composition") if isinstance(annotation, Mapping) else None
    value = composition.get("type", "rule_of_thirds") if isinstance(composition, Mapping) else "rule_of_thirds"
    return value if isinstance(value, str) else "rule_of_thirds"


def _consensus(labels: list[str], weights: np.ndarray, threshold: float) -> tuple[str | None, float]:
    votes: dict[str, float] = {}
    for label, weight in zip(labels, weights):
        if label.strip() and label != "unknown" and weight > 0:
            votes[label] = votes.get(label, 0.0) + float(weight)
    total = sum(votes.values())
    if total <= 0:
        return None, 0.0
    winner = max(votes, key=votes.get)  # Insertion order resolves vote ties like linkedMapOf.
    share = votes[winner] / total
    return (winner, share) if share >= threshold else (None, 0.0)


def _android_rankings(
    scores: np.ndarray, raw_rankings: np.ndarray, gallery: list[dict[str, Any]]
) -> tuple[np.ndarray, int]:
    """Port the default top8 self-vote/top5 rerank, excluding live lighting."""
    rank_weights = np.asarray([1.0, 0.84, 0.70, 0.58, 0.48, 0.38, 0.30, 0.24])
    rankings: list[list[int]] = []
    saturated = 0
    for query_index, ranking in enumerate(raw_rankings):
        candidates = ranking[:8]
        cosine = scores[query_index, candidates]
        weights = rank_weights[:len(candidates)] * np.clip(cosine, 0, 1)
        metadata = [gallery[int(index)] for index in candidates]
        photos = [str(row.get("photo_type") or "unknown") for row in metadata]
        scenes = [row["scene_category"] for row in metadata]
        compositions = [_composition(row) for row in metadata]
        photo, _ = _consensus(photos, weights, 0.60)
        scene, scene_share = _consensus(scenes, weights, 0.58)
        composition, _ = _consensus(compositions, weights, 0.66)
        if not any(candidate_scene == scene and candidate_photo == photo
                   for candidate_scene, candidate_photo in zip(scenes, photos)):
            scene, scene_share = None, 0.0
        reranked = []
        for offset, index in enumerate(candidates):
            score = float(cosine[offset])
            if photo is not None:
                score += 0.08 if photos[offset] == photo else -0.08
            if scene is not None and scene_share >= 0.7:
                score += 0.06 if scenes[offset] == scene else -0.04
            if composition is not None and compositions[offset] == composition:
                score += 0.03
            template_id = str(metadata[offset].get("template_id") or metadata[offset]["photo_id"])
            reranked.append((score, template_id, int(index)))
        reranked.sort(key=lambda item: (-item[0], item[1]))
        saturated += int(reranked[0][0] >= 1.0)
        rankings.append([index for _, _, index in reranked[:5]])
    return np.asarray(rankings, dtype=np.int64), saturated


def evaluate_retrieval(
    query_embeddings: Any,
    gallery_embeddings: Any,
    query_records: Sequence[Mapping[str, Any]],
    gallery_records: Sequence[Mapping[str, Any]],
    *,
    ks: Sequence[int] = (1, 5),
    teacher_query_embeddings: Any | None = None,
    teacher_gallery_embeddings: Any | None = None,
    compare_android_rerank: bool = False,
) -> dict[str, Any]:
    """Return a JSON-serializable report for held-out queries vs training gallery.

    Matrices are [N,D] and [G,D], finite and nonzero, and are L2-normalized
    internally. Records must align with matrix rows and contain nonempty
    photo_id, source_sha256 and a known scene_category. Duplicate IDs within a
    side or overlapping IDs/content hashes across sides raise ValueError.
    Source-group/near-duplicate separation must be enforced by the caller.

    raw.scene_{micro,macro}_recall_at_k have string keys (default "1", "5")
    and float values in [0,1]. Macro averages the scenes present in queries;
    scenes absent from the gallery count as misses. raw.mrr uses the full
    gallery. Optional raw.teacher_top5_overlap is a float in [0,1]: mean
    intersection size of student/teacher top min(5,G), divided by min(5,G).
    Teacher matrices may have a different dimension from student matrices,
    but must have identical row identities/order and both must be provided.

    Android comparison ports default self-voting metadata reranking in float64
    (not bit-exact device math). It uses no query scene label or live lighting.
    Its MRR is truncated to the returned top5. Neither mode measures composition
    correctness, confidence calibration or temporal template stabilization.
    """
    ks = tuple(ks)
    if not ks or any(isinstance(k, (bool, np.bool_)) or not isinstance(k, (int, np.integer)) or k <= 0 for k in ks):
        raise ValueError("ks must contain positive integers")
    ks = tuple(dict.fromkeys(int(k) for k in ks))
    query = _normalize(query_embeddings, "query_embeddings")
    gallery = _normalize(gallery_embeddings, "gallery_embeddings")
    if query.shape[1] != gallery.shape[1]:
        raise ValueError("query/gallery embedding dimensions differ")
    query_records = _validate_records(query_records, len(query), "query_records")
    gallery_records = _validate_records(gallery_records, len(gallery), "gallery_records")
    for field in ("photo_id", "source_sha256"):
        overlap = {row[field] for row in query_records} & {row[field] for row in gallery_records}
        if overlap:
            raise ValueError(f"query/gallery {field} overlap: {sorted(overlap)[:3]}")
    scores = np.clip(query @ gallery.T, -1.0, 1.0)
    rankings = np.argsort(-scores, axis=1, kind="stable")
    raw = _metrics(rankings, query_records, gallery_records, ks, mrr_scope="full_gallery")
    if (teacher_query_embeddings is None) != (teacher_gallery_embeddings is None):
        raise ValueError("teacher query and gallery embeddings must be provided together")
    if teacher_query_embeddings is not None:
        teacher_query = _normalize(teacher_query_embeddings, "teacher_query_embeddings")
        teacher_gallery = _normalize(teacher_gallery_embeddings, "teacher_gallery_embeddings")
        if len(teacher_query) != len(query) or len(teacher_gallery) != len(gallery):
            raise ValueError("teacher/student row counts differ")
        if teacher_query.shape[1] != teacher_gallery.shape[1]:
            raise ValueError("teacher query/gallery embedding dimensions differ")
        teacher_scores = np.clip(teacher_query @ teacher_gallery.T, -1.0, 1.0)
        top_k = min(5, len(gallery))
        teacher_rankings = np.argsort(-teacher_scores, axis=1, kind="stable")[:, :top_k]
        overlaps = [len(set(student[:top_k]) & set(teacher)) / top_k
                    for student, teacher in zip(rankings, teacher_rankings)]
        raw["teacher_top5_overlap"] = float(np.mean(overlaps))
        raw["teacher_overlap_effective_k"] = top_k
    report: dict[str, Any] = {
        "schema_version": 1,
        "protocol": {
            "query_gallery_photo_id_disjoint": True,
            "query_gallery_source_sha256_disjoint": True,
            "source_group_disjointness_verified": False,
            "near_duplicate_disjointness_verified": False,
            "scene_recall_definition": "fraction of queries with at least one same-scene result in top K",
            "macro_scene_population": "scenes present in queries, including scenes absent from gallery",
            "raw_tie_break": "gallery row order",
            "normalization": "L2, float64",
        },
        "raw": raw,
    }
    if compare_android_rerank:
        android_rankings, saturated = _android_rankings(scores, rankings, gallery_records)
        android = _metrics(android_rankings, query_records, gallery_records, ks, mrr_scope="returned_top5")
        android["comparison_config"] = {
            "retrieval_top_k": 8,
            "returned_top_k": 5,
            "lighting_bonus_included": False,
            "context_source": "self-vote of retrieved gallery metadata, not query labels",
            "composition_default": "rule_of_thirds (matches Android missing metadata default)",
            "numerical_precision": "float64, not bit-exact Android",
            "temporal_stabilization_included": False,
        }
        android["top1_changed_count"] = int(np.count_nonzero(android_rankings[:, 0] != rankings[:, 0]))
        android["top1_score_at_least_one_count"] = saturated
        report["android_rerank"] = android
    return report


def _load_records(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--embeddings", type=Path, required=True,
                        help="NPZ with query_embeddings/gallery_embeddings and optional teacher counterparts")
    parser.add_argument("--query-records", type=Path, required=True, help="Query JSONL in embedding row order")
    parser.add_argument("--gallery-records", type=Path, required=True, help="Training gallery JSONL in embedding row order")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ks", type=int, nargs="+", default=[1, 5])
    parser.add_argument("--compare-android-rerank", action="store_true")
    args = parser.parse_args(argv)
    try:
        with np.load(args.embeddings, allow_pickle=False) as arrays:
            report = evaluate_retrieval(
                arrays["query_embeddings"], arrays["gallery_embeddings"],
                _load_records(args.query_records), _load_records(args.gallery_records),
                ks=args.ks,
                teacher_query_embeddings=arrays.get("teacher_query_embeddings"),
                teacher_gallery_embeddings=arrays.get("teacher_gallery_embeddings"),
                compare_android_rerank=args.compare_android_rerank,
            )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
