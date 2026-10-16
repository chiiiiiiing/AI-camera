"""Pinned, offline NanoDet support for observable still-life subjects.

This is an auxiliary detector, not an aesthetic score or segmentation model.
The original ``preprocess`` / ``detect`` entry points preserve the historical
224-square protocol. ``preprocess_native`` / ``detect_native`` use the upright
native viewport with an aspect-preserving 416 letterbox. The protocols have
different identifiers and their reference annotations must not be interchanged.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.request

import numpy as np

SOURCE_COMMIT = "47534e27c9851bb1128ccc0102f1145e27f23f98"
MODEL_NAME = "object_detection_nanodet_2022nov.onnx"
MODEL_SHA256 = "4b82da9944b88577175ee23a459dce2e26e6e4be573def65b1055dc2d9720186"
MODEL_BYTES = 3800954
LICENSE_SHA256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
PREPROCESSING = "viewport_rgb224_bilinear416_bgr_mean_std_v1"
NATIVE_PREPROCESSING = "viewport_native_letterbox416_bgr_mean_std_v2"
INPUT_SIZE = 416
STRIDES = (8, 16, 32)
CLASS_OUTPUTS = ("792", "814", "836")
BOX_OUTPUTS = ("795", "817", "839")
MEAN_BGR = np.array([103.53, 116.28, 123.675], dtype=np.float32)
STD_BGR = np.array([57.375, 57.12, 58.395], dtype=np.float32)
LABELS = tuple("person,bicycle,car,motorcycle,airplane,bus,train,truck,boat,traffic light,fire hydrant,stop sign,parking meter,bench,bird,cat,dog,horse,sheep,cow,elephant,bear,zebra,giraffe,backpack,umbrella,handbag,tie,suitcase,frisbee,skis,snowboard,sports ball,kite,baseball bat,baseball glove,skateboard,surfboard,tennis racket,bottle,wine glass,cup,fork,knife,spoon,bowl,banana,apple,sandwich,orange,broccoli,carrot,hot dog,pizza,donut,cake,chair,couch,potted plant,bed,dining table,toilet,tv,laptop,mouse,remote,keyboard,cell phone,microwave,oven,toaster,sink,refrigerator,book,clock,vase,scissors,teddy bear,hair drier,toothbrush".split(","))


def _bilinear(rgb: np.ndarray, width: int, height: int) -> np.ndarray:
    """Half-pixel bilinear in float32; matches the Kotlin implementation."""
    h, w, _ = rgb.shape
    x = np.maximum(0, (np.arange(width, dtype=np.float32) + .5) * (w / width) - .5)
    y = np.maximum(0, (np.arange(height, dtype=np.float32) + .5) * (h / height) - .5)
    x0, y0 = x.astype(np.int32), y.astype(np.int32)
    x1, y1 = np.minimum(x0 + 1, w - 1), np.minimum(y0 + 1, h - 1)
    wx, wy = (x - x0)[None, :, None], (y - y0)[:, None, None]
    top = rgb[y0[:, None], x0] * (1 - wx) + rgb[y0[:, None], x1] * wx
    bottom = rgb[y1[:, None], x0] * (1 - wx) + rgb[y1[:, None], x1] * wx
    return (top * (1 - wy) + bottom * wy).astype(np.float32)


def preprocess(image) -> np.ndarray:
    """Accept PIL RGB images, applying EXIF orientation before calling is caller's job."""
    rgb = np.asarray(image.convert("RGB"), dtype=np.float32)
    if rgb.shape[:2] != (224, 224):
        rgb = np.floor(_bilinear(rgb, 224, 224) + .5).clip(0, 255)
    bgr = _bilinear(rgb, INPUT_SIZE, INPUT_SIZE)[..., ::-1]
    return np.ascontiguousarray(((bgr - MEAN_BGR) / STD_BGR).transpose(2, 0, 1)[None])


def native_transform(width: int, height: int) -> dict:
    if width <= 0 or height <= 0:
        raise ValueError("Viewport dimensions must be positive")
    scale = INPUT_SIZE / max(width, height)
    w, h = max(1, min(INPUT_SIZE, int(np.floor(width*scale+.5)))), max(1, min(INPUT_SIZE, int(np.floor(height*scale+.5))))
    return dict(source_width=width, source_height=height, resized_width=w, resized_height=h,
                pad_left=(INPUT_SIZE-w)//2, pad_top=(INPUT_SIZE-h)//2, input_size=INPUT_SIZE)


def native_analysis_image(image):
    """Return real aspect-preserved viewport pixels and exact inverse-map metadata."""
    from PIL import Image
    rgb = np.asarray(image.convert("RGB"), dtype=np.float32)
    transform = native_transform(rgb.shape[1], rgb.shape[0])
    resized = np.floor(_bilinear(rgb, transform["resized_width"], transform["resized_height"]) + .5).clip(0,255).astype(np.uint8)
    return Image.fromarray(resized), transform


def preprocess_native(image) -> tuple[np.ndarray, dict]:
    analysis, transform = native_analysis_image(image)
    rgb = np.full((INPUT_SIZE,INPUT_SIZE,3),114,dtype=np.float32)
    x, y = transform["pad_left"], transform["pad_top"]
    rgb[y:y+analysis.height,x:x+analysis.width] = np.asarray(analysis)
    bgr = rgb[...,::-1]
    tensor = np.ascontiguousarray(((bgr-MEAN_BGR)/STD_BGR).transpose(2,0,1)[None])
    return tensor, transform


def native_box_to_viewport(box, transform: dict) -> list[float] | None:
    values = np.asarray(box,dtype=np.float32)
    if values.shape != (4,) or not np.isfinite(values).all() or values[2] <= values[0] or values[3] <= values[1]:
        return None
    mapped = (values*transform["input_size"] - np.array([transform["pad_left"],transform["pad_top"]]*2,dtype=np.float32)) / np.array([transform["resized_width"],transform["resized_height"]]*2,dtype=np.float32)
    visible = mapped.clip(0,1)
    if visible[2] <= visible[0] or visible[3] <= visible[1] or (visible[2]-visible[0])*(visible[3]-visible[1]) < .5*(mapped[2]-mapped[0])*(mapped[3]-mapped[1]):
        return None
    return visible.tolist()


def iou(a, b) -> float:
    intersection = max(0., min(a[2], b[2]) - max(a[0], b[0])) * max(0., min(a[3], b[3]) - max(a[1], b[1]))
    union = (a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - intersection
    return float(intersection / union) if union > 0 else 0.


def decode(outputs: dict[str, np.ndarray], score_threshold: float = .35, iou_threshold: float = .6) -> list[dict]:
    """Three real ONNX heads; named mapping avoids OpenCV/ORT output-order differences."""
    candidates = []
    for stride, cls_name, box_name in zip(STRIDES, CLASS_OUTPUTS, BOX_OUTPUTS):
        side = INPUT_SIZE // stride
        scores = np.asarray(outputs[cls_name], dtype=np.float32)
        logits = np.asarray(outputs[box_name], dtype=np.float32)
        if scores.shape != (1, side*side, 80) or logits.shape != (1, side*side, 32):
            raise ValueError("Unexpected NanoDet head shape")
        scores, logits = scores[0], logits[0]
        best = scores.max(axis=1)
        indices = np.flatnonzero(np.isfinite(scores).all(axis=1) & np.isfinite(logits).all(axis=1) & (best >= score_threshold) & (best <= 1))
        indices = sorted(indices, key=lambda index: (-float(best[index]), int(index)))[:1000]
        for index in indices:
            bins = logits[index].reshape(4, 8)
            probabilities = np.exp(bins - bins.max(axis=1, keepdims=True))
            probabilities /= probabilities.sum(axis=1, keepdims=True)
            distances = (probabilities * np.arange(8)).sum(axis=1) * stride
            cx, cy = (index % side)*stride + .5*(stride-1), (index // side)*stride + .5*(stride-1)
            box = (np.array([cx-distances[0], cy-distances[1], cx+distances[2], cy+distances[3]]) / INPUT_SIZE).clip(0, 1).tolist()
            if box[2] <= box[0] or box[3] <= box[1]:
                continue
            class_id = int(scores[index].argmax())
            candidates.append(dict(class_id=class_id, label=LABELS[class_id], confidence=float(best[index]), box=box))
    # Official reference uses class-agnostic NMS. Use stable ties and bounded output.
    selected = []
    for candidate in sorted(candidates, key=lambda item: -item["confidence"]):
        if all(iou(candidate["box"], kept["box"]) <= iou_threshold for kept in selected):
            selected.append(candidate)
            if len(selected) == 100:
                break
    return selected


class NanoDetDetector:
    def __init__(self, model_path: str | Path):
        import onnxruntime as ort
        path = Path(model_path)
        if hashlib.sha256(path.read_bytes()).hexdigest() != MODEL_SHA256:
            raise ValueError("NanoDet model SHA256 does not match pinned deployment model")
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        options.log_severity_level = 3
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
        self.session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
        inputs = self.session.get_inputs()
        if len(inputs) != 1 or inputs[0].name != "input.1" or inputs[0].shape != [1, 3, 416, 416]:
            raise ValueError("Unexpected NanoDet input")
        self.output_names = [node.name for node in self.session.get_outputs()]
        if set(self.output_names) != set(CLASS_OUTPUTS + BOX_OUTPUTS):
            raise ValueError("Unexpected NanoDet outputs")

    def detect(self, image) -> list[dict]:
        outputs = self.session.run(self.output_names, {"input.1": preprocess(image)})
        return decode(dict(zip(self.output_names, outputs)))

    def detect_native(self, image) -> list[dict]:
        tensor, transform = preprocess_native(image)
        outputs = self.session.run(self.output_names, {"input.1": tensor})
        result = []
        for detection in decode(dict(zip(self.output_names, outputs))):
            box = native_box_to_viewport(detection["box"], transform)
            if box is not None:
                result.append({**detection,"box":box})
        return result


def download(directory: Path) -> dict:
    directory.mkdir(parents=True, exist_ok=True)
    base = f"https://raw.githubusercontent.com/opencv/opencv_zoo/{SOURCE_COMMIT}/models/object_detection_nanodet/"
    model_url = f"https://media.githubusercontent.com/media/opencv/opencv_zoo/{SOURCE_COMMIT}/models/object_detection_nanodet/{MODEL_NAME}"
    for filename, url, expected in ((MODEL_NAME, model_url, MODEL_SHA256), ("LICENSE", base + "LICENSE", LICENSE_SHA256)):
        target = directory / filename
        if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != expected:
            for attempt in range(2):
                try:
                    with urllib.request.urlopen(url, timeout=60) as response:
                        content = response.read()
                    if hashlib.sha256(content).hexdigest() != expected:
                        raise ValueError(f"Downloaded {filename} failed hash verification")
                    target.write_bytes(content)
                    break
                except Exception:
                    if attempt:
                        raise
    manifest = dict(schema_version=1, model=MODEL_NAME, model_sha256=MODEL_SHA256, bytes=MODEL_BYTES,
                    source_commit=SOURCE_COMMIT, source_url=model_url, license="Apache-2.0",
                    license_sha256=LICENSE_SHA256, license_url=base+"LICENSE", preprocessing=PREPROCESSING,
                    input=dict(name="input.1", shape=[1,3,416,416], dtype="float32", order="BGR", mean=MEAN_BGR.tolist(), std=STD_BGR.tolist()),
                    heads=[dict(stride=s, classes=c, boxes=b, anchors=(416//s)**2) for s,c,b in zip(STRIDES,CLASS_OUTPUTS,BOX_OUTPUTS)],
                    decoder="named_heads_distribution8_anchor_half_stride_v1",
                    score_threshold=.35, nms_iou_threshold=.6, nms_class_agnostic=True, classes=list(LABELS),
                    limits=["Object detection is not segmentation or aesthetic assessment.",
                            "Input uses a 224 px preview; no native 416 px detail or phone latency claim.",
                            "Automatic labels may be incorrect; ambiguous or clipped subjects must not drive movement."])
    (directory / "object_detector_manifest.json").write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8", newline="\n")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download-dir", type=Path)
    parser.add_argument("--model", type=Path)
    parser.add_argument("--image", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--native", action="store_true", help="Use native viewport letterbox v2 instead of historical 224-square protocol")
    args = parser.parse_args()
    if args.download_dir:
        print(json.dumps(download(args.download_dir), indent=2))
    if args.image:
        from PIL import Image, ImageOps
        detector = NanoDetDetector(args.model or args.download_dir / MODEL_NAME)
        with Image.open(args.image) as original:
            image = ImageOps.exif_transpose(original).convert("RGB")
        start = time.perf_counter()
        detections = detector.detect_native(image) if args.native else detector.detect(image)
        result = dict(detections=detections, preprocessing=NATIVE_PREPROCESSING if args.native else PREPROCESSING,
                      desktop_cpu_ms=(time.perf_counter()-start)*1000)
        text = json.dumps(result, indent=2)
        if args.output:
            args.output.write_text(text+"\n", encoding="utf-8")
        print(text)


if __name__ == "__main__":
    main()
