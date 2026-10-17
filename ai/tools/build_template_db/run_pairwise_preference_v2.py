#!/usr/bin/env python3
"""Frozen V2 same-view comparison. Three candidates, validation abstention, one acceptance.

Experimental only: does not replace the Android encoder or enable a failed head.
No external data is downloaded. Previously consumed validation/acceptance sources
are excluded; prior training sources remain explicitly non-independent system data.
"""
from __future__ import annotations

import argparse
import base64
from collections import Counter, defaultdict
import hashlib
import io
import json
import os
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter, ImageOps

from framing_preference import features, valid_box
from framing_preference_preprocessing import PREPROCESSING_V2, crop_native, encoder_tensor, encoder_rgb
from multiscene_detector import native_analysis_image
from prepare_quality_teacher import sha256
from train_framing_preference import encode_teacher, encoder_runtime, read_rows, write_json

SEED = 2026101602
DECAYS = (.0001, .001, .01)
MARGINS = (.1, .25, .5, 1., 2.)
SCENES = ("landscape", "food", "still_life", "portrait")


def scene_of(row):
    category = row.get("scene_category", "")
    if "plant" in category:
        return None
    return "food" if category == "food_still_life" else row.get("photo_type")


def partition(rows, previous):
    blocked_groups, blocked_hashes = set(), set()
    for protocol, views in previous:
        blocked_groups.update(g for g, split in protocol["group_assignments"].items() if split != "train")
        if isinstance(views, dict):
            views = views["views"]
        blocked_hashes.update(v["source_sha256"] for v in views if v["split"] != "train")
    for row in rows:
        if (row.get("historical_holdout_group") or row.get("original_split") != "train"
                or row["image_sha256"] in blocked_hashes or row.get("source_sha256") in blocked_hashes):
            blocked_groups.add(row["source_group_id"])
    grouped = defaultdict(list)
    for r in rows:
        if (r.get("reference_eligible") and not r.get("synthetic") and scene_of(r) in SCENES
                and r["source_group_id"] not in blocked_groups):
            grouped[r["source_group_id"]].append(r)
    selected = [dict(sorted(v, key=lambda r: r["image_sha256"])[0]) for _, v in sorted(grouped.items())]
    by_scene = defaultdict(list)
    for row in selected:
        by_scene[scene_of(row)].append(row)
    for members in by_scene.values():
        members.sort(key=lambda r: hashlib.sha256(f"{SEED}:{r['source_group_id']}".encode()).hexdigest())
        n = len(members)
        for i, row in enumerate(members):
            row["split"] = "acceptance" if i < int(n * .30) else "val" if i < int(n * .50) else "train"
    return selected, sorted(blocked_groups)


def reviewed_box(row, structures):
    r = structures.get(row["template_id"], {})
    review = r.get("object_review") or {}
    if (r.get("image_sha256") != row["image_sha256"] or not r.get("object_eligible")
            or review.get("status") != "accepted" or review.get("image_sha256") != row["image_sha256"]):
        return None
    obj = r["objects"][r["primary_object_index"]]
    x, y, w, h = obj["bbox"]
    b = [x, y, x+w, y+h]
    return b if valid_box(b) else None


def scene_crops(image, subject, scene):
    """Match bounded Android rule families; no portrait/body or plant crop authority."""
    if scene == "portrait" or (scene in ("food", "still_life") and subject is None):
        return []
    if scene == "landscape":
        return [[.03, .03, .97, .97], [.06, .06, .94, .94]]
    cx, cy = (subject[0]+subject[2])/2, (subject[1]+subject[3])/2
    def box(x):
        left, top = np.clip(cx-.9*x, 0, .1), np.clip(cy-.45, 0, .1)
        return [float(left), float(top), float(left+.9), float(top+.9)]
    result = [box(.5)]
    rgb = np.asarray(image, dtype=np.float32)/255
    luma = rgb @ np.asarray([.299,.587,.114], dtype=np.float32)
    edge = np.zeros_like(luma)
    edge[:, :-1] += np.abs(np.diff(luma, axis=1))/2
    edge[:-1, :] += np.abs(np.diff(luma, axis=0))/2
    # The live policy uses a 3x3 layout, with each row equally weighted.
    h,w = luma.shape
    grid = [float(edge[y*h//3:(y+1)*h//3,x*w//3:(x+1)*w//3].mean()) for y in range(3) for x in range(3)]
    left, right = np.mean(grid[0::3]), np.mean(grid[2::3])
    if abs(left-right)>.012:
        other = box(.62 if left<right else .38)
        if other != result[0]:
            result.append(other)
    return result


def protected_crop(native, crop, subject):
    view, px = crop_native(native, crop)
    actual = [px[0]/native.width,px[1]/native.height,px[2]/native.width,px[3]/native.height]
    l,t,r,b = actual
    if r-l<.82 or b-t<.82:
        return None
    if subject is None:
        return view, None, actual
    if not valid_box(subject) or not (subject[0]>=l+.025 and subject[1]>=t+.025 and subject[2]<=r-.025 and subject[3]<=b-.025):
        return None
    return view, [(subject[0]-l)/(r-l),(subject[1]-t)/(b-t),(subject[2]-l)/(r-l),(subject[3]-t)/(b-t)], actual


def stable_label(delta):
    return int(np.sign(delta[0])) if min(abs(float(x)) for x in delta)>=.1 and delta[0]*delta[1]>0 else 0


def pair_features(a, b):
    d = np.asarray(a)-np.asarray(b)
    d = d.copy()
    d[..., -4:] = np.asarray(b)[..., -4:]  # scene context survives subtraction
    return d


def reverse_pairs(x):
    other = -x.copy()
    other[..., -4:] = x[..., -4:]
    return other


def predict(head, x):
    raw = np.frombuffer(base64.b64decode(head["weights"]), dtype="<f4")
    w = raw[:605*64].reshape(64,605); b = raw[605*64:605*64+64]; out = raw[-65:-1]
    scale = np.asarray(head["scale"], dtype=np.float32)
    z = np.asarray(x, dtype=np.float32)/scale
    return ((np.maximum(z@w.T+b,0)-np.maximum(reverse_pairs(z)@w.T+b,0))@out)/2


def rule_score(spatial, scene):
    # Landscape detail is not clutter. No edge penalty and no forced thirds.
    if scene not in ("food", "still_life") or spatial[84]<.5:
        return 0.
    l,t,r,b = spatial[85:89]
    return -4*max(0., .08-min(l,t,1-r,1-b))


def metrics(scores, pairs, margin):
    crop = [(float(s),p) for s,p in zip(scores,pairs) if p["kind"]=="crop"]
    stable = [(s,p) for s,p in crop if p["sign"]!=0]
    grouped = defaultdict(list); keep=defaultdict(list); rules=defaultdict(list)
    for s,p in stable:
        g=p["group"]
        grouped[g].append(float(s*p["sign"]>0))
        keep[g].append(float(p["sign"]<0))
        rules[g].append(float(p["rule_delta"]*p["sign"]>0))
    means=np.asarray([np.mean(v) for _,v in sorted(grouped.items())])
    lower=float(np.quantile(np.random.default_rng(SEED).choice(means,(2000,len(means)),replace=True).mean(1),.025)) if len(means) else 0.
    emitted=[(s,p) for s,p in crop if margin is not None and abs(s)>=margin]
    gains=[(s,p) for s,p in emitted if s>0]
    stable_emitted=[(s,p) for s,p in emitted if p["sign"]!=0]
    uncertain=[(s,p) for s,p in crop if p["sign"]==0]
    defects=[(float(s),p) for s,p in zip(scores,pairs) if p["kind"]!="crop"]
    return {"clusters":len(means),"stable_pairs":len(stable),"all_crop_pairs":len(crop),
        "agreement":float(means.mean()) if len(means) else 0.,"lower95":lower,
        "keep_current":float(np.mean([np.mean(v) for v in keep.values()])) if keep else 0.,
        "rule_baseline":float(np.mean([np.mean(v) for v in rules.values()])) if rules else 0.,
        "coverage":len(emitted)/len(crop) if crop else 0.,"abstentions":len(crop)-len(emitted),
        "selective_agreement":sum(s*p["sign"]>0 for s,p in stable_emitted)/len(stable_emitted) if stable_emitted else 0.,
        "gain_count":len(gains),"gain_precision":sum(p["sign"]>0 for s,p in gains)/len(gains) if gains else 0.,
        "uncertain_gain_rate":sum(margin is not None and s>=margin for s,p in uncertain)/len(uncertain) if uncertain else 0.,
        "defect_agreement":sum(s*p["sign"]>0 for s,p in defects)/len(defects) if defects else 0.,
        "defect_pairs":len(defects)}


def acceptable(m):
    return (m["clusters"]>=30 and m["agreement"]>=.7 and m["lower95"]>.5
            and m["agreement"]>max(m["keep_current"],m["rule_baseline"])
            and m["coverage"]>=.1 and m["selective_agreement"]>=.8
            and m["gain_count"]>=10 and m["gain_precision"]>=.8 and m["uncertain_gain_rate"]<=.05)


def run(args):
    out=args.output_dir
    if out.exists() and any(out.iterdir()):
        if args.reuse_completed:
            report=json.loads((out/"acceptance.json").read_text(encoding="utf-8"))
            for name,digest in report["artifact_sha256"].items():
                if sha256(out/name)!=digest: raise ValueError(f"changed frozen artifact: {name}")
            return report
        raise ValueError("new experiment requires an empty directory; completed acceptance is immutable")
    out.mkdir(parents=True,exist_ok=True)
    for name in ("HF_HOME","TORCH_HOME","XDG_CACHE_HOME","TEMP","TMP","CUDA_CACHE_PATH","TRITON_CACHE_DIR"):
        path=out/"cache"/name.lower();path.mkdir(parents=True,exist_ok=True);os.environ[name]=str(path.resolve())
    previous=[]
    for directory in (args.previous_quality,args.previous_framing):
        previous.append((json.loads((directory/"frozen-protocol.json").read_text(encoding="utf-8")),
                         json.loads((directory/"views.json").read_text(encoding="utf-8"))))
    rows,blocked=partition(read_rows(args.source_manifest),previous)
    protocol={"schema_version":2,"seed":SEED,"preprocessing":PREPROCESSING_V2,"epochs":250,
        "weight_decays":DECAYS,"validation_margins":MARGINS,"teacher_margin":.1,
        "acceptance_min_clusters":30,"scene_min_clusters":10,"min_agreement":.7,
        "min_selective_agreement":.8,"min_gain_precision":.8,"min_gains":10,"min_coverage":.1,
        "max_uncertain_gain_rate":.05,"beats_keep_and_rules":True,"lower95_above":.5,
        "head":"antisymmetric_pair_delta605_scene_context_to64_to1",
        "encoder_sha256":sha256(args.encoder),"source_manifest_sha256":sha256(args.source_manifest),
        "object_structures_sha256":sha256(args.object_structures),"script_sha256":sha256(Path(__file__)),
        "preprocessing_script_sha256":sha256(Path(__file__).with_name("framing_preference_preprocessing.py")),
        "dependency_sha256":{n:sha256(Path(__file__).with_name(n)) for n in
            ("framing_preference.py","multiscene_detector.py","train_framing_preference.py","prepare_quality_teacher.py")},
        "teacher_provenance_sha256":sha256(args.vendor_dir/"teacher-provenance.json"),
        "previous_artifacts_sha256":{str(p.name)+":"+n:sha256(p/n) for p in (args.previous_quality,args.previous_framing) for n in ("frozen-protocol.json","views.json")},
        "group_assignments":{r["source_group_id"]:r["split"] for r in rows},"excluded_groups":blocked,
        "prior_validation_and_acceptance_excluded":True,"prior_training_sources_used":True,
        "encoder_may_have_seen_sources":True,"human_aesthetic_ground_truth":False,
        "selection":"validation cluster agreement, then larger weight decay; validation-only margin; never acceptance",
        "runtime_crop_family_match":True,"unknown_semantic_boundaries_not_certified":True}
    write_json(out/"frozen-protocol.json",protocol)
    write_json(out/"sources.json",[{k:r[k] for k in ("template_id","source_group_id","image_sha256","split","photo_type","scene_category")} for r in rows])
    print("source clusters",len(rows),dict(Counter(r["split"] for r in rows)),flush=True)
    structures={r["template_id"]:r for r in json.loads(args.object_structures.read_text(encoding="utf-8"))["records"]}
    runtime=encoder_runtime(args); images=[];vectors=[];records=[];pairs=[]
    for row in rows:
        path=Path(row["image_path"])
        if sha256(path)!=row["image_sha256"]:raise ValueError("source hash changed")
        with Image.open(path) as raw:native,_=native_analysis_image(ImageOps.exif_transpose(raw).convert("RGB"))
        scene=scene_of(row);subject=reviewed_box(row,structures) if scene in ("food","still_life") else None
        views=[("current",native,subject,[0,0,1,1])]
        for i,crop in enumerate(scene_crops(native,subject,scene)):
            result=protected_crop(native,crop,subject)
            if result is not None:views.append((f"crop_{i}",*result))
        spatial=features(native,subject,scene)
        if spatial[:80].reshape(16,5)[:,3:].mean()>.025:
            views.append(("defect_blur",native.filter(ImageFilter.GaussianBlur(5)),subject,[0,0,1,1]))
        if .15<spatial[80]<.7 and spatial[83]<.05:
            exposed=Image.fromarray(np.clip(np.asarray(native,dtype=np.float32)*1.7+32,0,255).astype(np.uint8))
            if features(exposed,subject,scene)[83]-spatial[83]>=.15:views.append(("defect_overexposure",exposed,subject,[0,0,1,1]))
        current=len(records)
        for kind,view,box,crop in views:
            tensor=encoder_tensor(view);emb=runtime.run(None,{runtime.get_inputs()[0].name:tensor})[0].reshape(-1)
            if emb.shape!=(512,) or not np.isfinite(emb).all():raise ValueError("bad embedding")
            spatial=features(view,box,scene);vectors.append(np.concatenate((emb,spatial)))
            images.append(Image.fromarray(encoder_rgb(view)))
            records.append({"group":row["source_group_id"],"scene":scene,"split":row["split"],"kind":kind,"crop":crop,
                "source_sha256":row["image_sha256"],"tensor_sha256":hashlib.sha256(tensor.tobytes()).hexdigest(),
                "known_subject_preserved":True,"semantic_subject_protection":subject is not None})
            if kind!="current":pairs.append({"a":len(records)-1,"b":current,"group":row["source_group_id"],
                "scene":scene,"split":row["split"],"kind":"crop" if kind.startswith("crop") else kind})
    write_json(out/"views.json",records)
    augmented=[]
    for image in images:
        memory=io.BytesIO();image.save(memory,"JPEG",quality=90);memory.seek(0)
        augmented.append(Image.open(memory).convert("RGB"))
    teacher=encode_teacher(args,images+augmented).reshape(2,len(images)).T
    vectors=np.asarray(vectors,dtype=np.float32)
    for p in pairs:
        delta=teacher[p["a"]]-teacher[p["b"]]
        p["sign"]=stable_label(delta) if p["kind"]=="crop" else -1
        p["teacher_delta"]=delta.tolist()
        p["rule_delta"]=rule_score(vectors[p["a"],-93:],p["scene"])-rule_score(vectors[p["b"],-93:],p["scene"])
    write_json(out/"pairs.json",pairs)
    x=np.asarray([pair_features(vectors[p["a"]],vectors[p["b"]]) for p in pairs],dtype=np.float32)
    np.savez_compressed(out/"features.npz",views=vectors,pairs=x,teacher=teacher)
    head,selection=train(x,pairs)
    write_json(out/"selected-head.json",head);write_json(out/"selection.json",selection)
    chosen=[i for i,p in enumerate(pairs) if p["split"]=="acceptance"]
    acceptance_pairs=[pairs[i] for i in chosen];scores=predict(head,x[chosen]);margin=selection["margin"]
    result=metrics(scores,acceptance_pairs,margin)
    per_scene={s:metrics([v for v,p in zip(scores,acceptance_pairs) if p["scene"]==s],
        [p for p in acceptance_pairs if p["scene"]==s],margin) for s in SCENES}
    scene_ok=any(m["clusters"]>=10 and m["agreement"]>=.7 and m["lower95"]>.5 and
        m["agreement"]>max(m["keep_current"],m["rule_baseline"]) for m in per_scene.values())
    gates={"crop_preference":acceptable(result),"scene_support":scene_ok,"validation_abstention_calibrated":margin is not None,
        "parameter_budget":len(base64.b64decode(head["weights"]))<=262144,
        "defects_not_worse_than_keep":result["defect_pairs"]>0 and result["defect_agreement"]==1.}
    report={"schema_version":2,"metrics":result,"per_scene":per_scene,"gates":gates,
        "offline_gate_passed":all(gates.values()),"deployed":False,"acceptance_runs":1,
        "human_preference_verified":False,"physical_device_verified":False,
        "retained_baseline":"current Android MobileNetV3 and disabled preference policies unchanged",
        "artifact_sha256":{n:sha256(out/n) for n in ("frozen-protocol.json","sources.json","views.json","pairs.json","features.npz","selected-head.json","selection.json")}}
    write_json(out/"acceptance.json",report)
    return report


def train(x,pairs):
    import torch
    from torch import nn
    torch.set_num_threads(4)
    ti=[i for i,p in enumerate(pairs) if p["split"]=="train"]
    vi=[i for i,p in enumerate(pairs) if p["split"]=="val"]
    if not ti or not vi:raise ValueError("insufficient independent train/validation pairs")
    scale=np.maximum(np.sqrt(np.mean(x[ti]**2,axis=0)),.05).astype(np.float32);scale[-4:]=1
    z=torch.tensor(x[ti]/scale);opposite=torch.tensor(reverse_pairs(x[ti])/scale)
    labels=torch.tensor([pairs[i]["sign"] for i in ti],dtype=torch.float32)
    count=Counter((pairs[i]["group"],pairs[i]["kind"]) for i in ti)
    weights=torch.tensor([1/count[(pairs[i]["group"],pairs[i]["kind"])] for i in ti])
    validation=[pairs[i] for i in vi];candidates=[]
    for decay in DECAYS:
        torch.manual_seed(SEED);model=nn.Sequential(nn.Linear(605,64),nn.ReLU(),nn.Linear(64,1))
        optim=torch.optim.AdamW(model.parameters(),lr=.003,weight_decay=decay)
        for _ in range(250):
            score=(model(z).flatten()-model(opposite).flatten())/2
            loss=torch.where(labels!=0,torch.nn.functional.softplus(-labels*score),score.square())
            loss=(loss*weights).sum()/weights.sum();optim.zero_grad();loss.backward();optim.step()
        raw=np.concatenate([p.detach().numpy().reshape(-1) for p in model.parameters()]).astype('<f4')
        head={"schema_version":2,"protocol":"antisymmetric_delta605_scene_context_v2","preprocessing":PREPROCESSING_V2,
            "weights":base64.b64encode(raw.tobytes()).decode('ascii'),"scale":scale.tolist(),"hidden_dim":64}
        scores=predict(head,x[vi]);m=metrics(scores,validation,None)
        candidates.append((m["agreement"],decay,head,scores,m))
        print('validation',decay,m,flush=True)
    _,decay,head,scores,m=max(candidates,key=lambda v:(v[0],v[1]))
    options=[(margin,metrics(scores,validation,margin)) for margin in MARGINS]
    reliable=[(margin,r) for margin,r in options if r["clusters"]>=10 and r["gain_count"]>=5 and
        r["gain_precision"]>=.8 and r["selective_agreement"]>=.8 and r["uncertain_gain_rate"]<=.05]
    selected=max(reliable,key=lambda v:(v[1]["coverage"],v[0])) if reliable else None
    return head,{"weight_decay":decay,"margin":selected[0] if selected else None,
        "abstain_all":selected is None,"validation":m,"margin_candidates":[{"margin":v,"metrics":r} for v,r in options],
        "candidates":[{"weight_decay":v[1],"metrics":v[4]} for v in candidates],"acceptance_used":False}


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ("source-manifest","previous-quality","previous-framing","object-structures","encoder","vendor-dir","deps-dir","output-dir"):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--batch-size',type=int,default=16)
    parser.add_argument('--reuse-completed',action='store_true')
    print(json.dumps(run(parser.parse_args()),ensure_ascii=False,allow_nan=False))
