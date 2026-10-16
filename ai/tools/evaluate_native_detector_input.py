#!/usr/bin/env python3
"""Fixed, source-cluster sampled old/new NanoDet input regression, without accuracy claims.

The reviewed 15 old candidates are diagnostic cases, never carried forward as
new-protocol accepted references. Additional sources are selected by hash before
running either detector. No original or frozen aesthetic/retrieval split changes.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
import time

import numpy as np
from PIL import Image, ImageDraw, ImageOps

sys.path.insert(0,str(Path(__file__).parent / "build_template_db"))
from multiscene_detector import NanoDetDetector, MODEL_SHA256, PREPROCESSING, NATIVE_PREPROCESSING, iou
from build_multiscene_references import canonical_objects, object_eligibility, live_uncertain_competitors


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def select_rows(rows, review_ids, per_scene=8):
    eligible=[r for r in rows if r.get("reference_eligible") and not r.get("synthetic")]
    selected={r["template_id"]:r for r in eligible if r["template_id"] in review_ids}
    for scene in sorted({r["scene_category"] for r in eligible}):
        groups={}
        for row in sorted(eligible,key=lambda r:r["template_id"]):
            if row["scene_category"] == scene:
                groups.setdefault(row["source_group_id"],row)
        ordered=sorted(groups,key=lambda g:hashlib.sha256(("native_input_eval_v1:"+scene+":"+g).encode()).hexdigest())
        for group in ordered[:per_scene]:
            selected[groups[group]["template_id"]]=groups[group]
    return sorted(selected.values(),key=lambda r:r["template_id"])


def reflection_agreement(detections,reflected):
    confident=[d for d in detections if d["confidence"] >= .5]
    mirrored=[{**d,"box":[1-d["box"][2],d["box"][1],1-d["box"][0],d["box"][3]]} for d in reflected]
    matched=sum(any(d["class_id"]==other["class_id"] and other["confidence"] >= .5 and iou(d["box"],other["box"]) >= .5 for other in mirrored) for d in confident)
    return {"confident_original_count":len(confident),"matched_reflection_count":matched,
            "all_original_confident_recovered":bool(confident) and matched==len(confident)}


def thumbnail(image,detections,label):
    view=image.copy(); view.thumbnail((320,320))
    panel=Image.new("RGB",(340,365),(245,245,245)); x=(340-view.width)//2; y=30
    panel.paste(view,(x,y)); draw=ImageDraw.Draw(panel)
    draw.text((8,8),label,fill=(0,0,0))
    for d in detections:
        if d["confidence"] < .5: continue
        l,t,r,b=d["box"]
        coords=(int(x+l*view.width),int(y+t*view.height),int(x+r*view.width),int(y+b*view.height))
        draw.rectangle(coords,outline=(255,55,30),width=2)
        draw.text((coords[0]+2,coords[1]+2),f'{d["label"]} {d["confidence"]:.2f}',fill=(255,55,30),stroke_width=1,stroke_fill=(255,255,255))
    return panel


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest",type=Path,required=True)
    parser.add_argument("--reviews",type=Path,required=True)
    parser.add_argument("--model",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--per-scene",type=int,default=8)
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/"comparisons").mkdir(exist_ok=True)
    reviews={r["template_id"]:r for r in read_jsonl(args.reviews)}
    selected=select_rows(read_jsonl(args.manifest),reviews,args.per_scene)
    protocol={"schema_version":1,"old_preprocessing":PREPROCESSING,"new_preprocessing":NATIVE_PREPROCESSING,
              "model_sha256":MODEL_SHA256,"source_manifest_sha256":hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
              "selection":"all 15 old AI-reviewed cases plus fixed SHA256-ranked source clusters, max 8 per scene",
              "selected":[{"template_id":r["template_id"],"source_group_id":r["source_group_id"],"image_sha256":r["image_sha256"]} for r in selected],
              "not_accuracy_or_map":True,"not_an_independent_encoder_holdout":True,"prior_reviews_do_not_authorize_new_detections":True}
    (args.output/"protocol.json").write_text(json.dumps(protocol,indent=2)+"\n",encoding="utf-8")
    detector=NanoDetDetector(args.model)
    records=[]; timings={"old":[],"native":[]}
    for index,row in enumerate(selected):
        path=Path(row["image_path"])
        if hashlib.sha256(path.read_bytes()).hexdigest()!=row["image_sha256"]: raise ValueError("Image identity changed")
        with Image.open(path) as source:
            image=ImageOps.exif_transpose(source).convert("RGB")
        record={k:row[k] for k in ("template_id","image_sha256","source_group_id","scene_category")}
        record["prior_review_status"]=reviews.get(row["template_id"],{}).get("status")
        views={}
        for name,detect in (("old",detector.detect),("native",detector.detect_native)):
            started=time.perf_counter(); detections=detect(image); elapsed=(time.perf_counter()-started)*1000
            timings[name].append(elapsed)
            reflected=detect(ImageOps.mirror(image))
            objects=canonical_objects(detections)
            eligible,reasons,primary=object_eligibility(row,objects)
            uncertain=live_uncertain_competitors(objects,primary) if eligible else []
            record[name]={"detections":detections,"desktop_cpu_ms":elapsed,"reflection":reflection_agreement(detections,reflected),
                          "automatic_object_eligible":eligible,"eligible_reasons":reasons,
                          "live_uncertain_competitor_veto":bool(uncertain),"new_review_still_required":True}
            views[name]=detections
        if row["template_id"] in reviews:
            sheet=Image.new("RGB",(1020,365),(255,255,255))
            for col,(label,detections) in enumerate((("Original",[]),("Legacy 224",views["old"]),("Native letterbox 416",views["native"]))):
                sheet.paste(thumbnail(image,detections,label),(col*340,0))
            sheet.save(args.output/"comparisons"/(row["template_id"]+".jpg"),quality=90)
        records.append(record)
        print(f'{index+1}/{len(selected)} {row["template_id"]}',flush=True)
    summary={"schema_version":1,"protocol":protocol,"images":len(records),"source_clusters":len({r["source_group_id"] for r in records}),
             "scenes":dict(Counter(r["scene_category"] for r in records)),"results":{},
             "limits":["Detection coverage and reflection consistency are not accuracy or mAP.",
                       "These source images can have been seen by the existing retrieval encoder.",
                       "Desktop CPU timings include preprocessing and Python decoding; they are not phone latency.",
                       "New boxes need new visual review; old accepted/rejected reviews are diagnostic only."]}
    for name in ("old","native"):
        summary["results"][name]={"images_with_confident_detections":sum(r[name]["reflection"]["confident_original_count"]>0 for r in records),
          "images_all_confident_detections_reflection_consistent":sum(r[name]["reflection"]["all_original_confident_recovered"] for r in records),
          "automatic_object_eligible":sum(r[name]["automatic_object_eligible"] for r in records),
          "automatic_eligible_and_uncertainty_clear":sum(r[name]["automatic_object_eligible"] and not r[name]["live_uncertain_competitor_veto"] for r in records),
          "desktop_cpu_ms":{"p50":float(np.percentile(timings[name],50)),"p90":float(np.percentile(timings[name],90))}}
    summary["prior_three_accepted"]=[r for r in records if r["prior_review_status"]=="accepted"]
    (args.output/"detections.jsonl").write_text("".join(json.dumps(r)+"\n" for r in records),encoding="utf-8")
    (args.output/"summary.json").write_text(json.dumps(summary,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:v for k,v in summary.items() if k not in {"protocol","prior_three_accepted"}},indent=2))


if __name__=="__main__": main()
