from __future__ import annotations

import argparse
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import numpy as np
import torch
from torch import nn

import train_transfer_retrieval as transfer
from student_embedding_model import build_student_model


def report(macro1=0.5, macro5=0.8, micro5=0.9, overlap=0.2):
    return {"raw": {"scene_macro_recall_at_k": {"1": macro1, "5": macro5},
                    "scene_micro_recall_at_k": {"5": micro5}, "teacher_top5_overlap": overlap}}


class RidgeTests(unittest.TestCase):
    def test_matches_augmented_least_squares_with_unpenalized_intercept(self):
        rng = np.random.default_rng(9)
        x, y = rng.normal(size=(12, 5)) + 4, rng.normal(size=(12, 3)) + 8
        weight, bias = transfer.fit_ridge(x, y, 0.1)
        augmented = np.column_stack((x, np.ones(len(x))))
        penalty = np.diag([0.1] * 5 + [0])
        expected = np.linalg.solve(augmented.T @ augmented + penalty, augmented.T @ y)
        np.testing.assert_allclose(weight, expected[:-1].T, rtol=1e-5, atol=1e-6)
        np.testing.assert_allclose(bias, expected[-1], rtol=1e-5)

    def test_singular_features_remain_finite(self):
        weight, bias = transfer.fit_ridge(np.ones((3, 7)), np.array([[1, 2], [2, 3], [3, 4]]), 0.01)
        np.testing.assert_allclose(weight, 0)
        np.testing.assert_allclose(bias, [2, 3])

    def test_rejects_invalid_targets_and_strengths(self):
        for strength in (0, -1, float("nan"), float("inf")):
            with self.assertRaises(ValueError):
                transfer.fit_ridge(np.ones((3, 2)), np.ones((3, 4)), strength)
        for target in (np.ones((2, 4)), np.full((3, 4), np.nan), np.empty((3, 0))):
            with self.assertRaises(ValueError):
                transfer.fit_ridge(np.ones((3, 2)), target, 0.1)

    def test_head_normalizes_and_rejects_zero_output(self):
        actual = transfer.apply_head(np.array([[3.0, 4.0]]), np.eye(2), np.zeros(2))
        np.testing.assert_allclose(actual, [[0.6, 0.8]])
        with self.assertRaises(ValueError):
            transfer.apply_head(np.zeros((1, 2)), np.eye(2), np.zeros(2))

    def test_fixed_bounded_candidate_set(self):
        self.assertEqual(transfer.RIDGE_STRENGTHS, (0.01, 0.1, 1.0))


class SelectionTests(unittest.TestCase):
    def test_rejects_regression_in_any_guarded_metric(self):
        base = report()
        for metric in (report(macro1=.49, overlap=.9), report(macro5=.79, overlap=.9),
                       report(micro5=.89, overlap=.9), report(overlap=.19)):
            self.assertFalse(transfer.eligible_against_baseline(metric, base))

    def test_requires_strict_improvement(self):
        self.assertFalse(transfer.eligible_against_baseline(report(), report()))
        self.assertTrue(transfer.eligible_against_baseline(report(overlap=.21), report()))


class ArchitectureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(2)

    def config(self):
        return {"architecture": "mobilenet_v3_small_retrieval", "width_mult": 1.0,
                "output_dim": 512, "image_size": 224, "dropout": 0.0}

    def test_offline_construction_shape_normalization_and_checkpoint_roundtrip(self):
        with mock.patch("torch.hub.download_url_to_file", side_effect=AssertionError("network forbidden")):
            model = build_student_model(self.config()).eval()
            replica = build_student_model(self.config()).eval()
            replica.load_state_dict(model.state_dict(), strict=True)
            image = torch.randn(2, 3, 224, 224).clamp(-1, 1)
            with torch.inference_mode():
                actual = model(image)
                restored = replica(image)
        self.assertEqual(tuple(actual.shape), (2, 512))
        torch.testing.assert_close(actual.norm(dim=1), torch.ones(2))
        torch.testing.assert_close(actual, restored)

    def test_android_input_is_converted_to_imagenet_normalization(self):
        model = build_student_model(self.config()).eval()
        seen = []

        class Capture(nn.Module):
            def forward(self, tensor):
                seen.append(tensor.clone())
                return tensor

        model.features = Capture()
        model.encode_features(torch.zeros(1, 3, 2, 2))
        expected = (torch.full((1, 3, 2, 2), .5) - model.imagenet_mean) / model.imagenet_std
        torch.testing.assert_close(seen[0], expected)

    def test_fixed_pretrained_width_required(self):
        config = self.config()
        config["width_mult"] = .75
        with self.assertRaisesRegex(ValueError, "width_mult"):
            build_student_model(config)

    def test_legacy_architectures_still_load(self):
        for architecture in ("tiny_cnn_v1", "lite_cnn_v2"):
            model = build_student_model({"architecture": architecture, "output_dim": 8}).eval()
            with torch.inference_mode():
                self.assertEqual(tuple(model(torch.zeros(1, 3, 64, 64)).shape), (1, 8))


class TransferOrchestrationTests(unittest.TestCase):
    def test_only_train_and_validation_are_read_and_ridge_fit_uses_train(self):
        with tempfile.TemporaryDirectory(dir=".", prefix="transfer-test-") as temporary:
            root = Path(temporary)
            dataset = root / "dataset"
            dataset.mkdir()
            records = {}
            for split, count in (("train", 3), ("val", 2)):
                records[split] = [{"photo_id": f"{split}-{i}", "source_sha256": f"{split}-hash-{i}",
                                   "split": split, "source_group_id": f"{split}-group-{i}"}
                                  for i in range(count)]
                (dataset / f"{split}.jsonl").write_text("\n".join(json.dumps(row) for row in records[split]), encoding="utf-8")
            checkpoint = root / "projection.pt"
            torch.save({"projection": {"mean": torch.zeros(2), "components": torch.eye(2)}}, checkpoint)
            baseline = root / "baseline.json"
            baseline.write_text(json.dumps({"models": {"old_int8": {"retrieval": report()}},
                                             "splits": {split: len(rows) for split, rows in records.items()},
                                             "dataset_sha256": {split: transfer.sha256(dataset / f"{split}.jsonl") for split in records}}), encoding="utf-8")
            cache = root / "cache"
            (cache / "checkpoints").mkdir(parents=True)
            (cache / "checkpoints" / "weights.pth").write_bytes(b"test weights")
            fake_model = nn.Module()
            fake_model.features = nn.Linear(2, 2)
            fake_model.head = nn.Linear(2, 2)
            fake_pretrained = nn.Module()
            fake_pretrained.features = nn.Linear(2, 2)
            args = argparse.Namespace(dataset_dir=dataset, projection_checkpoint=checkpoint, baseline_report=baseline,
                                      output_dir=root / "out", torch_cache=cache, device="cpu", batch_size=2, cpu_threads=2)
            features = {3: np.array([[1, 0], [0, 1], [.6, .8]], dtype=np.float32),
                        2: np.array([[1, 0], [0, 1]], dtype=np.float32)}
            original_hub_dir = torch.hub.get_dir()
            try:
                with mock.patch.object(transfer, "build_student_model", return_value=fake_model), \
                     mock.patch.object(transfer, "mobilenet_v3_small", return_value=fake_pretrained), \
                     mock.patch.object(transfer, "MobileNet_V3_Small_Weights", SimpleNamespace(IMAGENET1K_V1=SimpleNamespace(url="https://download.pytorch.org/models/weights.pth"))), \
                     mock.patch.object(transfer, "load_images", side_effect=lambda rows, size: torch.zeros(len(rows), 3, 2, 2)), \
                     mock.patch.object(transfer, "load_teachers", side_effect=lambda rows: features[len(rows)]), \
                     mock.patch.object(transfer, "extract_features", side_effect=lambda model, images, device, batch_size: features[len(images)]), \
                     mock.patch.object(transfer, "evaluate_retrieval", return_value=report(overlap=.3)), \
                     mock.patch.object(transfer, "try_export_onnx", return_value={"status": "skipped"}), \
                     mock.patch.object(transfer, "read_rows", wraps=transfer.read_rows) as reads, \
                     mock.patch.object(transfer, "fit_ridge", wraps=transfer.fit_ridge) as fits:
                    summary = transfer.train_transfer(args)
                self.assertEqual([call.args[0].name for call in reads.call_args_list], ["train.jsonl", "val.jsonl"])
                self.assertEqual(len(fits.call_args_list), 3)
                for call in fits.call_args_list:
                    np.testing.assert_array_equal(call.args[0], features[3])
                    self.assertEqual(len(call.args[1]), 3)
                self.assertFalse(summary["deployment_ready"])
                self.assertFalse(summary["provenance"]["test_split_read"])
                self.assertTrue(summary["eligible_for_further_validation"])
                saved = torch.load(args.output_dir / "checkpoint.selected.pt", weights_only=True)
                self.assertEqual(saved["model"]["architecture"], "mobilenet_v3_small_retrieval")
                wrong_baseline = json.loads(baseline.read_text(encoding="utf-8"))
                wrong_baseline["dataset_sha256"]["val"] = "different-manifest"
                baseline.write_text(json.dumps(wrong_baseline), encoding="utf-8")
                args.output_dir = root / "wrong-baseline-out"
                with mock.patch.object(transfer, "mobilenet_v3_small", side_effect=AssertionError("must not download")):
                    with self.assertRaisesRegex(ValueError, "exactly the same"):
                        transfer.train_transfer(args)
            finally:
                torch.hub.set_dir(original_hub_dir)


if __name__ == "__main__":
    unittest.main()
