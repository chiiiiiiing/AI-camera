"""Canonical future V2 framing input, separate from the consumed V1 experiment.

The historical V1 Pillow experiment is not relabelled by these pixel fixtures.
Any future V2 model requires its own frozen training and untouched acceptance.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image

from framing_preference import features, valid_box
from multiscene_detector import _bilinear, native_analysis_image

PREPROCESSING_V2 = "viewport_native416_halfpixel_round_crop_rgb224_halfpixel_round_v2"


def crop_native(native: Image.Image, box=None):
    if box is None:
        return native.copy(), [0, 0, native.width, native.height]
    if not valid_box(box):
        raise ValueError("invalid crop")
    left, top, right, bottom = box
    # Positive Kotlin roundToInt is floor(value+0.5), not Python banker's round.
    pixels = [int(np.floor(left * native.width + .5)), int(np.floor(top * native.height + .5)),
              int(np.floor(right * native.width + .5)), int(np.floor(bottom * native.height + .5))]
    if pixels[2] <= pixels[0] or pixels[3] <= pixels[1]:
        raise ValueError("empty crop after rasterization")
    return native.crop(pixels), pixels


def encoder_rgb(view: Image.Image):
    rgb = np.asarray(view.convert("RGB"), dtype=np.float32)
    return np.floor(_bilinear(rgb, 224, 224) + .5).clip(0, 255).astype(np.uint8)


def encoder_tensor(view: Image.Image):
    rgb = encoder_rgb(view).astype(np.float32)
    return np.ascontiguousarray(rgb.transpose(2, 0, 1)[None] / np.float32(127.5) - np.float32(1.))


def write_fixture(output: Path, encoder: Path):
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    runtime = ort.InferenceSession(str(encoder), options, providers=["CPUExecutionProvider"])
    cases = []
    for width, height in ((997, 601), (601, 997)):
        y, x = np.mgrid[:height, :width]
        rgb = np.stack(((x * 7 + y * 3) % 256, (x * 2 + y * 11) % 256,
                        (x * 13 + y * 5) % 256), axis=-1).astype(np.uint8)
        native, transform = native_analysis_image(Image.fromarray(rgb))
        for kind, box in (("current", None), ("crop", [.1, .1, .9, .9])):
            view, pixels = crop_native(native, box)
            resized = encoder_rgb(view)
            tensor = encoder_tensor(view)
            embedding = runtime.run(None, {runtime.get_inputs()[0].name: tensor})[0].reshape(-1)
            cases.append({"source_width": width, "source_height": height, "kind": kind,
                          "native_width": native.width, "native_height": native.height, "crop_ltrb_pixels": pixels,
                          "native_rgb_sha256": hashlib.sha256(np.asarray(native).tobytes()).hexdigest(),
                          "view_rgb_sha256": hashlib.sha256(np.asarray(view).tobytes()).hexdigest(),
                          "encoder_rgb224_sha256": hashlib.sha256(resized.tobytes()).hexdigest(),
                          "encoder_tensor_nchw_f32le_sha256": hashlib.sha256(tensor.astype("<f4").tobytes()).hexdigest(),
                          "spatial_features": features(view, None, "landscape").tolist(),
                          "embedding512": embedding.tolist()})
    result = {"schema_version": 1, "preprocessing": PREPROCESSING_V2,
              "rgb_formula": ["(x*7+y*3)%256", "(x*2+y*11)%256", "(x*13+y*5)%256"],
              "encoder_sha256": hashlib.sha256(encoder.read_bytes()).hexdigest(),
              "cases": cases, "current_is_independently_encoded": True,
              "does_not_validate_v1_model_acceptance": True}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--encoder", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = write_fixture(args.output, args.encoder)
    print(json.dumps({"preprocessing": PREPROCESSING_V2, "cases": len(result["cases"]), "output": str(args.output)}))
