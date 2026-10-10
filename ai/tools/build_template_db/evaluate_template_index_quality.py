#!/usr/bin/env python3
"""Evaluate retrieval quality for a portable template index bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import validate_template_index


UNKNOWN_VALUES = {"", "unknown", "none", "not_visible", None}
COMPOSITION_FIELDS = [
    ("composition", "subject_position"),
    ("composition", "horizon_position"),
    ("composition", "leading_lines"),
    ("composition", "negative_space"),
    ("camera", "angle"),
    ("camera", "distance"),
    ("camera", "orientation"),
]
LIGHTING_FIELDS = [
    ("lighting", "quality"),
    ("lighting", "direction"),
    ("lighting", "time_of_day"),
    ("lighting", "contrast"),
]


def load_index(index_dir: Path) -> tuple[dict[str, Any], list[dict[str, Any]], list[list[float]]]:
    errors, summary = validate_template_index.validate_index(index_dir)
    if errors:
        raise ValueError("; ".join(errors))
    keys = json.loads((index_dir / "templates.keys.json").read_text(encoding="utf-8"))
    metadata = validate_template_index.load_jsonl(index_dir / "templates.metadata.jsonl")
    vectors = validate_template_index.read_embeddings(
        index_dir / "templates.embeddings.f32",
        int(summary["count"]),
        int(summary["dimension"]),
    )
    return keys, metadata, vectors


def dot(left: list[float], right: list[float]) -> float:
    return sum(a * b for a, b in zip(left, right))


def nearest_neighbors(
    vectors: list[list[float]],
    query_index: int,
    top_k: int,
    include_self: bool = False,
) -> list[dict[str, Any]]:
    scored: list[tuple[float, int]] = []
    query = vectors[query_index]
    for index, vector in enumerate(vectors):
        if not include_self and index == query_index:
            continue
        scored.append((dot(query, vector), index))
    scored.sort(key=lambda item: (-item[0], item[1]))
    return [
        {
            "index": index,
            "similarity": similarity,
            "distance": max(0.0, 1.0 - similarity),
        }
        for similarity, index in scored[:top_k]
    ]


def annotation(record: dict[str, Any]) -> dict[str, Any]:
    value = record.get("annotation")
    return value if isinstance(value, dict) else {}


def nested(record: dict[str, Any], group: str, field: str) -> Any:
    container = annotation(record).get(group)
    if isinstance(container, dict):
        return container.get(field)
    return None


def comparable(value: Any) -> bool:
    if isinstance(value, str):
        return value.strip().lower() not in UNKNOWN_VALUES
    return isinstance(value, (bool, int, float)) and math.isfinite(value)


def same_known_label(left: Any, right: Any) -> bool:
    return comparable(left) and comparable(right) and left == right


def field_match_score(
    query: dict[str, Any],
    candidate: dict[str, Any],
    fields: list[tuple[str, str]],
) -> dict[str, Any]:
    matched = 0
    available = 0
    details: dict[str, bool] = {}
    for group, field in fields:
        left = nested(query, group, field)
        right = nested(candidate, group, field)
        if not comparable(left) or not comparable(right):
            continue
        available += 1
        same = left == right
        if same:
            matched += 1
        details[f"{group}.{field}"] = same
    score = matched / available if available else None
    return {
        "matched": matched,
        "available": available,
        "score": score,
        "details": details,
    }


def describe_record(record: dict[str, Any]) -> dict[str, Any]:
    ann = annotation(record)
    return {
        "key": record.get("_key"),
        "template_id": record.get("template_id"),
        "photo_id": record.get("photo_id"),
        "photo_type": record.get("photo_type"),
        "scene_category": record.get("scene_category"),
        "aesthetic_score": ann.get("aesthetic_score"),
        "template_value": ann.get("template_value"),
        "visual_summary": ann.get("visual_summary"),
        "composition": ann.get("composition"),
        "lighting": ann.get("lighting"),
        "camera": ann.get("camera"),
        "source_path": record.get("source_path"),
        "landing_url": record.get("landing_url"),
    }


def attach_keys(keys: dict[str, Any], metadata: list[dict[str, Any]]) -> list[dict[str, Any]]:
    items = keys.get("items", [])
    enriched: list[dict[str, Any]] = []
    for index, record in enumerate(metadata):
        copy = dict(record)
        if index < len(items) and isinstance(items[index], dict):
            copy["_key"] = items[index].get("key")
        enriched.append(copy)
    return enriched


def same_scene_available(records: list[dict[str, Any]], index: int) -> bool:
    scene = records[index].get("scene_category")
    return any(same_known_label(other.get("scene_category"), scene)
               for other_i, other in enumerate(records) if other_i != index)


def evaluate_neighbors(
    records: list[dict[str, Any]],
    vectors: list[list[float]],
    top_k: int,
) -> list[dict[str, Any]]:
    evaluations: list[dict[str, Any]] = []
    for index, record in enumerate(records):
        neighbors = nearest_neighbors(vectors, index, top_k)
        top_neighbors = []
        for item in neighbors:
            candidate = records[int(item["index"])]
            comp = field_match_score(record, candidate, COMPOSITION_FIELDS)
            light = field_match_score(record, candidate, LIGHTING_FIELDS)
            top_neighbors.append(
                {
                    **item,
                    "same_photo_type": same_known_label(candidate.get("photo_type"), record.get("photo_type")),
                    "same_scene_category": same_known_label(candidate.get("scene_category"), record.get("scene_category")),
                    "composition_score": comp["score"],
                    "composition_matched": comp["matched"],
                    "composition_available": comp["available"],
                    "lighting_score": light["score"],
                    "lighting_matched": light["matched"],
                    "lighting_available": light["available"],
                    "record": describe_record(candidate),
                }
            )
        evaluations.append(
            {
                "index": index,
                "record": describe_record(record),
                "same_scene_available": same_scene_available(records, index),
                "neighbors": top_neighbors,
            }
        )
    return evaluations


def pct(value: float | None) -> float | None:
    if value is None:
        return None
    return round(value * 100.0, 2)


def mean(values: list[float]) -> float | None:
    if not values:
        return None
    return sum(values) / len(values)


def hit_rate(values: list[bool]) -> float | None:
    if not values:
        return None
    return sum(1 for value in values if value) / len(values)


def summarize(records: list[dict[str, Any]], evaluations: list[dict[str, Any]], top_k: int) -> dict[str, Any]:
    scene_counts: dict[str, int] = {}
    type_counts: dict[str, int] = {}
    for record in records:
        scene = str(record.get("scene_category"))
        photo_type = str(record.get("photo_type"))
        scene_counts[scene] = scene_counts.get(scene, 0) + 1
        type_counts[photo_type] = type_counts.get(photo_type, 0) + 1

    metrics: dict[str, Any] = {
        "scene_counts": scene_counts,
        "photo_type_counts": type_counts,
        "top_k": top_k,
    }
    for k in (1, 3, 5):
        if k > top_k:
            continue
        same_type_hits: list[bool] = []
        same_scene_hits: list[bool] = []
        same_scene_hits_eligible: list[bool] = []
        composition_hits: list[bool] = []
        lighting_hits: list[bool] = []
        composition_scores: list[float] = []
        lighting_scores: list[float] = []
        top1_margins: list[float] = []
        for evaluation in evaluations:
            neighbors = evaluation["neighbors"][:k]
            same_type_hits.append(any(item["same_photo_type"] for item in neighbors))
            same_scene = any(item["same_scene_category"] for item in neighbors)
            same_scene_hits.append(same_scene)
            if evaluation["same_scene_available"]:
                same_scene_hits_eligible.append(same_scene)
            best_comp = max(
                (item["composition_score"] for item in neighbors if item["composition_score"] is not None),
                default=None,
            )
            best_light = max(
                (item["lighting_score"] for item in neighbors if item["lighting_score"] is not None),
                default=None,
            )
            if best_comp is not None:
                composition_scores.append(best_comp)
                composition_hits.append(best_comp >= 0.60)
            if best_light is not None:
                lighting_scores.append(best_light)
                lighting_hits.append(best_light >= 0.75)
            if k == 1 and len(evaluation["neighbors"]) >= 2:
                top1_margins.append(
                    float(evaluation["neighbors"][0]["similarity"])
                    - float(evaluation["neighbors"][1]["similarity"])
                )
        metrics[f"top{k}"] = {
            "query_count": len(same_scene_hits),
            "same_scene_eligible_query_count": len(same_scene_hits_eligible),
            "composition_evaluable_query_count": len(composition_hits),
            "lighting_evaluable_query_count": len(lighting_hits),
            "queries_with_k_neighbors": sum(len(evaluation["neighbors"]) >= k for evaluation in evaluations),
            "same_photo_type_hit_rate_pct": pct(hit_rate(same_type_hits)),
            "same_scene_hit_rate_pct": pct(hit_rate(same_scene_hits)),
            "same_scene_hit_rate_eligible_pct": pct(hit_rate(same_scene_hits_eligible)),
            "composition_match_hit_rate_pct": pct(hit_rate(composition_hits)),
            "composition_match_score_mean_pct": pct(mean(composition_scores)),
            "lighting_match_hit_rate_pct": pct(hit_rate(lighting_hits)),
            "lighting_match_score_mean_pct": pct(mean(lighting_scores)),
        }
        if top1_margins:
            metrics[f"top{k}"]["mean_top1_margin"] = round(mean(top1_margins) or 0.0, 6)
            metrics[f"top{k}"]["low_margin_count_lt_0_01"] = sum(1 for value in top1_margins if value < 0.01)
    return metrics


def problem_samples(evaluations: list[dict[str, Any]], limit: int = 12) -> list[dict[str, Any]]:
    samples: list[dict[str, Any]] = []
    for evaluation in evaluations:
        neighbors = evaluation["neighbors"]
        if not neighbors:
            continue
        top1 = neighbors[0]
        reason: str | None = None
        severity = 0
        if not top1["same_photo_type"]:
            reason = "top1_photo_type_mismatch"
            severity = 5
        elif evaluation["same_scene_available"] and not any(item["same_scene_category"] for item in neighbors[:5]):
            reason = "no_same_scene_in_top5"
            severity = 4
        elif evaluation["same_scene_available"] and not top1["same_scene_category"]:
            reason = "top1_scene_mismatch"
            severity = 4
        elif top1["composition_score"] is not None and top1["composition_score"] < 0.40:
            reason = "top1_composition_weak_match"
            severity = 3
        elif top1["lighting_score"] is not None and top1["lighting_score"] < 0.50:
            reason = "top1_lighting_weak_match"
            severity = 2
        elif len(neighbors) >= 2 and float(top1["similarity"]) - float(neighbors[1]["similarity"]) < 0.005:
            reason = "top1_margin_too_small"
            severity = 1
        if reason is None:
            continue
        samples.append(
            {
                "reason": reason,
                "severity": severity,
                "query": evaluation["record"],
                "top_neighbors": neighbors[:3],
            }
        )
    samples.sort(
        key=lambda item: (
            -int(item["severity"]),
            -float(item["top_neighbors"][0]["similarity"]),
            str(item["query"].get("template_id")),
        )
    )
    return samples[:limit]


def select_sample_queries(
    evaluations: list[dict[str, Any]],
    sample_size: int,
    seed: int,
) -> list[dict[str, Any]]:
    if sample_size <= 0:
        return []
    by_scene: dict[str, list[dict[str, Any]]] = {}
    for evaluation in evaluations:
        scene = str(evaluation["record"].get("scene_category"))
        by_scene.setdefault(scene, []).append(evaluation)
    rng = random.Random(seed)
    for values in by_scene.values():
        rng.shuffle(values)
    selected: list[dict[str, Any]] = []
    scenes = sorted(by_scene)
    cursor = 0
    while len(selected) < sample_size and any(by_scene.values()):
        scene = scenes[cursor % len(scenes)]
        if by_scene[scene]:
            selected.append(by_scene[scene].pop())
        cursor += 1
    return selected[:sample_size]


def near_duplicate_pairs(evaluations: list[dict[str, Any]], threshold: float = 0.985, limit: int = 12) -> list[dict[str, Any]]:
    pairs: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for evaluation in evaluations:
        query_id = str(evaluation["record"].get("template_id"))
        for neighbor in evaluation["neighbors"]:
            candidate_id = str(neighbor["record"].get("template_id"))
            pair = tuple(sorted((query_id, candidate_id)))
            if pair in seen:
                continue
            seen.add(pair)
            if float(neighbor["similarity"]) >= threshold:
                pairs.append(
                    {
                        "similarity": neighbor["similarity"],
                        "distance": neighbor["distance"],
                        "left": evaluation["record"],
                        "right": neighbor["record"],
                    }
                )
    pairs.sort(key=lambda item: -float(item["similarity"]))
    return pairs[:limit]


def usearch_check(
    index_dir: Path,
    keys: dict[str, Any],
    vectors: list[list[float]],
    evaluations: list[dict[str, Any]],
    sample_size: int,
) -> dict[str, Any]:
    if sample_size <= 0 or not evaluations:
        return {"status": "skipped", "reason": "no queries requested for ANN validation", "checked": 0}
    idx_path = index_dir / "templates.usearch.idx"
    if not idx_path.exists():
        return {"status": "skipped", "reason": "templates.usearch.idx not found"}
    try:
        import numpy as np
        from usearch.index import Index
    except ImportError:
        return {"status": "skipped", "reason": "Python package usearch is not installed"}

    dimension = int(keys.get("dimension"))
    items = keys.get("items", [])
    key_by_index = [int(item["key"]) for item in items]
    index_by_key = {key: i for i, key in enumerate(key_by_index)}
    search_index = Index(ndim=dimension, metric="cos", dtype="f32")
    search_index.load(str(idx_path))
    if int(search_index.size) != len(vectors):
        return {"status": "mismatch", "checked": 0, "size": int(search_index.size),
                "reason": "ANN index size does not match portable bundle"}
    mismatches: list[dict[str, Any]] = []
    checked = 0
    for evaluation in evaluations[:sample_size]:
        query_index = int(evaluation["index"])
        matches = search_index.search(np.array(vectors[query_index], dtype=np.float32), min(6, len(vectors)))
        brute_keys = [int(evaluation["record"]["key"])]
        brute_keys.extend(int(item["record"]["key"]) for item in evaluation["neighbors"][:5])
        usearch_keys = [int(key) for key in matches.keys[: len(brute_keys)]]
        # USearch returns the query itself at rank 1 for exact self-search.
        if brute_keys != usearch_keys:
            mismatches.append(
                {
                    "query_key": int(evaluation["record"]["key"]),
                    "expected_keys": brute_keys,
                    "usearch_keys": usearch_keys,
                    "known_keys": [index_by_key.get(key) for key in usearch_keys],
                }
            )
        checked += 1
    return {
        "status": "passed" if not mismatches else "mismatch",
        "checked": checked,
        "size": int(search_index.size),
        "mismatches": mismatches[:10],
    }


def normalize_evaluations(evaluations: list[dict[str, Any]]) -> None:
    for evaluation in evaluations:
        record = evaluation["record"]
        if record.get("key") is None and record.get("_key") is not None:
            record["key"] = record["_key"]
        for neighbor in evaluation["neighbors"]:
            neighbor_record = neighbor["record"]
            if neighbor_record.get("key") is None and neighbor_record.get("_key") is not None:
                neighbor_record["key"] = neighbor_record["_key"]


def recommendations(metrics: dict[str, Any], problems: list[dict[str, Any]]) -> list[str]:
    recs: list[str] = []
    top1 = metrics.get("top1", {})
    top5 = metrics.get("top5", {})
    same_scene_top1 = top1.get("same_scene_hit_rate_eligible_pct")
    same_scene_top5 = top5.get("same_scene_hit_rate_eligible_pct")
    comp_top1 = top1.get("composition_match_score_mean_pct")
    light_top1 = top1.get("lighting_match_score_mean_pct")

    if same_scene_top5 is not None and same_scene_top5 >= 85:
        recs.append("当前 embedding/index 可继续用于阶段 2 原型的粗粒度场景召回。")
    else:
        recs.append("同场景 top5 召回不足时，不建议直接把当前 embedding/index 作为端侧默认检索方案。")
    if same_scene_top1 is not None and same_scene_top1 < 65:
        recs.append("top1 同场景不够稳，端侧应返回 top3/top5 并结合规则重排，而不是只信 top1。")
    if comp_top1 is not None and comp_top1 < 65:
        recs.append("构图字段匹配偏弱，建议加入构图规则特征重排，或训练 512→256 投影/蒸馏头。")
    if light_top1 is not None and light_top1 < 65:
        recs.append("光线字段匹配偏弱，建议将 lighting/time_of_day 作为二级 rerank 或过滤条件。")
    if any(problem["reason"] == "top1_photo_type_mismatch" for problem in problems):
        recs.append("发现跨 photo_type top1，需要端侧查询时按当前场景类型做候选过滤。")
    recs.append("这是库内 leave-one-out 诊断；未验证训练集隔离，不能代替独立查询集、按拍摄组隔离的泛化验收。")
    recs.append("后续更换 embedding 模型或 student 维度时，用同一脚本做 teacher/student A/B 报告。")
    return recs


def write_markdown_report(report: dict[str, Any], output: Path) -> None:
    lines: list[str] = []
    summary = report["summary"]
    metrics = report["metrics"]
    lines.append("# 模板索引质量评估报告")
    lines.append("")
    lines.append(f"- 生成时间：{report['generated_at']}")
    lines.append(f"- 索引目录：`{report['index_dir']}`")
    lines.append(f"- 样本数：{summary['count']}，维度：{summary['dimension']}")
    lines.append(f"- USearch 检查：{report['usearch_check']['status']}")
    lines.append("- 评估口径：库内 leave-one-out，排除自身；未验证训练集隔离，不代表新照片泛化性能。")
    lines.append("")
    lines.append("## 核心指标")
    lines.append("")
    lines.append("| 指标 | top1 | top3 | top5 |")
    lines.append("|---|---:|---:|---:|")
    for label, key in [
        ("同 photo_type 命中率", "same_photo_type_hit_rate_pct"),
        ("同 scene_category 命中率", "same_scene_hit_rate_pct"),
        ("同 scene_category 命中率（排除单例场景）", "same_scene_hit_rate_eligible_pct"),
        ("构图匹配命中率", "composition_match_hit_rate_pct"),
        ("构图匹配均分", "composition_match_score_mean_pct"),
        ("光线匹配命中率", "lighting_match_hit_rate_pct"),
        ("光线匹配均分", "lighting_match_score_mean_pct"),
    ]:
        values = []
        for top in ("top1", "top3", "top5"):
            value = metrics.get(top, {}).get(key)
            values.append("-" if value is None else f"{value:.2f}%")
        lines.append(f"| {label} | {values[0]} | {values[1]} | {values[2]} |")
    lines.append("")
    if "top1" in metrics and "low_margin_count_lt_0_01" in metrics["top1"]:
        lines.append(
            f"- top1/top2 相似度差小于 0.01 的查询数：{metrics['top1']['low_margin_count_lt_0_01']}"
        )
    lines.append("")
    lines.append("## 检索判断")
    lines.append("")
    for item in report["recommendations"]:
        lines.append(f"- {item}")
    lines.append("")
    lines.append("## 抽样查询")
    lines.append("")
    for sample in report["sample_queries"]:
        query = sample["record"]
        lines.append(f"### query key={query.get('key')} `{query.get('scene_category')}`")
        lines.append("")
        lines.append(f"- 摘要：{query.get('visual_summary')}")
        for rank, neighbor in enumerate(sample["neighbors"][:3], 1):
            candidate = neighbor["record"]
            lines.append(
                f"- top{rank}: key={candidate.get('key')} "
                f"scene={candidate.get('scene_category')} sim={neighbor['similarity']:.4f} "
                f"comp={neighbor.get('composition_score')} light={neighbor.get('lighting_score')} "
                f"summary={candidate.get('visual_summary')}"
            )
        lines.append("")
    lines.append("## 问题样本")
    lines.append("")
    if not report["problem_samples"]:
        lines.append("- 未发现明显问题样本。")
    else:
        for problem in report["problem_samples"]:
            query = problem["query"]
            top = problem["top_neighbors"][0]
            candidate = top["record"]
            lines.append(
                f"- {problem['reason']}: query key={query.get('key')} "
                f"scene={query.get('scene_category')} -> top1 key={candidate.get('key')} "
                f"scene={candidate.get('scene_category')} sim={top['similarity']:.4f}"
            )
    lines.append("")
    lines.append("## 近重复提示")
    lines.append("")
    if not report["near_duplicate_pairs"]:
        lines.append("- 未发现相似度 >= 0.985 的近重复候选。")
    else:
        for pair in report["near_duplicate_pairs"]:
            lines.append(
                f"- sim={pair['similarity']:.4f}: key={pair['left'].get('key')} / key={pair['right'].get('key')}"
            )
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def build_report(index_dir: Path, top_k: int, sample_size: int, seed: int) -> dict[str, Any]:
    if top_k <= 0:
        raise ValueError("top_k must be positive")
    if sample_size < 0:
        raise ValueError("sample_size must be non-negative")
    keys, metadata, vectors = load_index(index_dir)
    records = attach_keys(keys, metadata)
    evaluations = evaluate_neighbors(records, vectors, max(top_k, 5))
    normalize_evaluations(evaluations)
    metrics = summarize(records, evaluations, max(top_k, 5))
    problems = problem_samples(evaluations)
    samples = select_sample_queries(evaluations, sample_size, seed)
    usearch = usearch_check(index_dir, keys, vectors, evaluations, min(sample_size, len(evaluations)))
    report = {
        "schema_version": 2,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "index_dir": str(index_dir),
        "evaluation_protocol": {
            "query_source": "index_embeddings",
            "gallery_source": "same_index",
            "self_match_excluded": True,
            "training_split_disjointness_verified": False,
            "near_duplicates_excluded": False,
            "metric_scope": "leave_one_out_diagnostic_not_held_out_generalization",
        },
        "input_artifacts": {
            name: {"sha256": hashlib.sha256((index_dir / name).read_bytes()).hexdigest(),
                   "bytes": (index_dir / name).stat().st_size}
            for name in ("templates.keys.json", "templates.metadata.jsonl", "templates.embeddings.f32")
        },
        "summary": {
            "count": int(keys.get("count")),
            "dimension": int(keys.get("dimension")),
            "metric": keys.get("metric"),
            "scene_counts": metrics["scene_counts"],
            "photo_type_counts": metrics["photo_type_counts"],
        },
        "metrics": metrics,
        "usearch_check": usearch,
        "sample_queries": [
            {
                "record": sample["record"],
                "neighbors": sample["neighbors"][:top_k],
            }
            for sample in samples
        ],
        "problem_samples": problems,
        "near_duplicate_pairs": near_duplicate_pairs(evaluations),
    }
    report["recommendations"] = recommendations(metrics, problems)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("index_dir", type=Path)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--sample-size", type=int, default=12)
    parser.add_argument("--seed", type=int, default=20260714)
    parser.add_argument("--output-json", type=Path)
    parser.add_argument("--output-md", type=Path)
    args = parser.parse_args()
    try:
        report = build_report(args.index_dir, args.top_k, args.sample_size, args.seed)
        output_json = args.output_json or args.index_dir / "templates.quality_report.json"
        output_md = args.output_md or args.index_dir / "templates.quality_report.md"
        output_json.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        write_markdown_report(report, output_md)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps({"json": str(output_json), "markdown": str(output_md)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
