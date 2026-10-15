"""Source-grouped, label-free detector stability audit; this does not measure mAP.

No thresholds are selected from these results. Ground truth is absent; consistency
under a known transform is necessary evidence, not proof of correct recognition.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import time

import numpy as np
from PIL import Image, ImageEnhance, ImageOps

from multiscene_detector import NanoDetDetector, MODEL_SHA256, PREPROCESSING, iou


def evaluate(model_path: Path, source_manifest: Path, output_dir: Path, max_groups: int = 120) -> dict:
    output_dir.mkdir(parents=True, exist_ok=True)
    rows = [json.loads(line) for line in source_manifest.read_text(encoding="utf-8").splitlines() if line.strip()]
    groups = defaultdict(list)
    for row in rows:
        if row.get("reference_eligible") and not row.get("synthetic") and row.get("photo_type") != "portrait":
            groups[row["source_group_id"]].append(row)
    selected = sorted(groups, key=lambda key: hashlib.sha256(key.encode()).hexdigest())[:max_groups]
    detector = NanoDetDetector(model_path)
    times = []
    results = []
    for group in selected:
        row = sorted(groups[group], key=lambda item: item["image_sha256"])[0]
        image_path = Path(row["image_path"])
        if hashlib.sha256(image_path.read_bytes()).hexdigest() != row["image_sha256"]:
            raise ValueError(f"Image changed after source manifest: {row['template_id']}")
        with Image.open(image_path) as original:
            image = ImageOps.exif_transpose(original).convert("RGB")
        start = time.perf_counter()
        original = detector.detect(image)
        times.append((time.perf_counter()-start)*1000)
        flipped = detector.detect(ImageOps.mirror(image))
        darker = detector.detect(ImageEnhance.Brightness(image).enhance(.9))
        trusted = [item for item in original if item["confidence"] >= .6]
        mirror_matches = brightness_matches = 0
        for item in trusted:
            box = item["box"]
            mirror_box = [1-box[2],box[1],1-box[0],box[3]]
            mirror_matches += any(other["class_id"] == item["class_id"] and other["confidence"] >= .35 and iou(mirror_box,other["box"]) >= .5 for other in flipped)
            brightness_matches += any(other["class_id"] == item["class_id"] and other["confidence"] >= .35 and iou(box,other["box"]) >= .5 for other in darker)
        results.append(dict(source_group_id=group,template_id=row["template_id"],scene_category=row["scene_category"],
                            detections=len(original),high_confidence_detections=len(trusted),
                            mirror_matches=mirror_matches,brightness_matches=brightness_matches,
                            mirrored_all_consistent=bool(trusted) and mirror_matches==len(trusted),
                            darkened_all_consistent=bool(trusted) and brightness_matches==len(trusted)))
    summary = {}
    for scene in sorted({row["scene_category"] for row in results}):
        subset = [row for row in results if row["scene_category"] == scene]
        supported = [row for row in subset if row["high_confidence_detections"]]
        summary[scene] = dict(source_clusters=len(subset),clusters_with_detection=sum(row["detections"]>0 for row in subset),
                              clusters_with_high_confidence_detection=len(supported),
                              high_confidence_coverage=len(supported)/len(subset),
                              mirrored_all_consistent_clusters=sum(row["mirrored_all_consistent"] for row in supported),
                              darkened_all_consistent_clusters=sum(row["darkened_all_consistent"] for row in supported))
    report = dict(schema_version=1,model_sha256=MODEL_SHA256,preprocessing=PREPROCESSING,
                  source_manifest_sha256=hashlib.sha256(source_manifest.read_bytes()).hexdigest(),
                  selection="One real eligible reference per source cluster, ordered by SHA256(cluster ID); no tuning.",
                  source_clusters=len(results),scenes=summary,
                  desktop_cpu_latency_ms=dict(samples=len(times),median=float(np.median(times)),p95=float(np.percentile(times,95)),
                                              includes_preprocessing_and_decode=True,threads=2),
                  claims=dict(mAP_measured=False,human_aesthetic_improvement_measured=False,phone_latency_measured=False),
                  limits=["No manual object ground truth: stable detections may still be wrong.",
                          "A high-confidence box is not proof that handles, petals or all props are included.",
                          "Known-transform consistency is source-cluster aggregated; no training or threshold selection used this audit."],
                  rows=results)
    (output_dir/"detector_stability_report.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    return {key:value for key,value in report.items() if key != "rows"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model",type=Path,required=True)
    parser.add_argument("--source-manifest",type=Path,required=True)
    parser.add_argument("--output-dir",type=Path,required=True)
    parser.add_argument("--max-groups",type=int,default=120)
    args = parser.parse_args()
    print(json.dumps(evaluate(args.model,args.source_manifest,args.output_dir,args.max_groups),indent=2))
