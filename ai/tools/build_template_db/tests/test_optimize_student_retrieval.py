import argparse
import contextlib
import copy
import hashlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import numpy as np
import torch
from torch import nn

import optimize_student_retrieval as runner


def validation_report(top1=0.7, top5=0.9, overlap=0.5):
    return {"raw": {
        "scene_macro_recall_at_k": {"1": top1, "5": top5},
        "teacher_top5_overlap": overlap,
    }}


def dataset_row(photo_id, split, source_group_id=None):
    return {"photo_id": photo_id, "split": split,
            "source_sha256": hashlib.sha256(photo_id.encode()).hexdigest(),
            "source_group_id": source_group_id}


class TrainingSplitValidationTests(unittest.TestCase):
    def rows(self):
        return {"train": [dataset_row("train1", "train", "group-a"),
                          dataset_row("train2", "train", "group-a")],
                "val": [dataset_row("val1", "val", "group-b")]}

    def test_allows_groups_repeated_within_split_and_audits_unknown_groups(self):
        rows = self.rows()
        rows["train"].append(dataset_row("train3", "train"))
        rows["val"].append(dataset_row("val2", "val", "unknown"))
        rows["val"].append(dataset_row("val3", "val", "   "))
        before = copy.deepcopy(rows)
        audit = runner.validate_training_splits(rows)
        self.assertEqual(audit["row_counts"], {"train": 3, "val": 3})
        self.assertEqual(audit["known_source_group_counts"], {"train": 1, "val": 1})
        self.assertEqual(audit["unknown_source_group_rows"], {"train": 1, "val": 2})
        self.assertTrue(audit["known_source_group_disjoint"])
        self.assertFalse(audit["unknown_groups_are_independent"])
        self.assertEqual(audit["near_duplicate_detection"], "not_performed")
        self.assertFalse(audit["test_split_read"])
        self.assertEqual(rows, before)

    def test_rejects_missing_wrong_split_labels_and_empty_splits(self):
        for split in ("train", "val"):
            for label in (None, "test", "train" if split == "val" else "val"):
                rows = self.rows()
                rows[split][0]["split"] = label
                with self.subTest(split=split, label=label), self.assertRaisesRegex(ValueError, "split must be"):
                    runner.validate_training_splits(rows)
            rows = self.rows()
            del rows[split][0]["split"]
            with self.assertRaisesRegex(ValueError, "split must be"):
                runner.validate_training_splits(rows)
            rows[split] = []
            with self.assertRaisesRegex(ValueError, "nonempty"):
                runner.validate_training_splits(rows)

    def test_rejects_each_split_duplicate_ids_and_hashes(self):
        for split in ("train", "val"):
            for field in ("photo_id", "source_sha256"):
                rows = self.rows()
                duplicate = dataset_row("extra", split)
                duplicate[field] = rows[split][0][field]
                if field == "source_sha256":
                    duplicate[field] = duplicate[field].upper()
                rows[split].append(duplicate)
                with self.subTest(split=split, field=field), self.assertRaisesRegex(ValueError, "duplicate " + field):
                    runner.validate_training_splits(rows)

    def test_rejects_cross_split_ids_hashes_and_known_source_groups(self):
        for field in ("photo_id", "source_sha256", "source_group_id"):
            rows = self.rows()
            rows["val"][0][field] = rows["train"][0][field]
            if field == "source_sha256":
                rows["val"][0][field] = rows["val"][0][field].upper()
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "train/val " + field + " overlap"):
                runner.validate_training_splits(rows)

    def test_rejects_absent_identity_values_and_invalid_group_types(self):
        for field in ("photo_id", "source_sha256"):
            for value in (None, "", "  ", 123):
                rows = self.rows()
                rows["train"][0][field] = value
                with self.subTest(field=field, value=value), self.assertRaisesRegex(ValueError, field):
                    runner.validate_training_splits(rows)
        rows = self.rows()
        rows["val"][0]["source_group_id"] = ["group-b"]
        with self.assertRaisesRegex(ValueError, "source_group_id must"):
            runner.validate_training_splits(rows)

    def test_invalid_split_is_rejected_before_image_or_teacher_loading(self):
        old_threads = torch.get_num_threads()
        self.addCleanup(torch.set_num_threads, old_threads)
        model = nn.Linear(2, 2, bias=False)
        checkpoint = {"model": {"image_size": 2}, "model_state": model.state_dict()}
        rows = self.rows()
        rows["val"][0]["source_group_id"] = "group-a"
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            args = argparse.Namespace(seed=7, cpu_threads=1, device="cpu",
                                      output_dir=Path(directory) / "run", dataset_dir=Path(directory) / "data",
                                      init_checkpoint=Path(directory) / "initial.pt")
            read = stack.enter_context(mock.patch.object(runner, "read_rows", side_effect=[rows["train"], rows["val"]]))
            stack.enter_context(mock.patch.object(runner.torch, "load", return_value=checkpoint))
            stack.enter_context(mock.patch.object(runner, "build_student_model", return_value=model))
            images = stack.enter_context(mock.patch.object(runner, "load_images"))
            teachers = stack.enter_context(mock.patch.object(runner, "load_teachers"))
            with self.assertRaisesRegex(ValueError, "source_group_id overlap"):
                runner.optimize(args)
            images.assert_not_called()
            teachers.assert_not_called()
            self.assertEqual([call.args[0].name for call in read.call_args_list], ["train.jsonl", "val.jsonl"])


class FullGalleryDistillationTests(unittest.TestCase):
    def setUp(self):
        self.teacher_gallery = torch.tensor([
            [1., 0., 0.], [.8, .6, 0.], [.8, -.6, 0.], [0., 0., 1.],
        ])
        self.student_gallery = self.teacher_gallery.clone()
        self.teacher_query = self.teacher_gallery[[0]].clone()

    def loss(self, predictions, **kwargs):
        return runner.full_gallery_distillation_loss(
            predictions,
            kwargs.pop("student_gallery", self.student_gallery),
            kwargs.pop("teacher_queries", self.teacher_query),
            kwargs.pop("teacher_gallery", self.teacher_gallery),
            kwargs.pop("self_indices", torch.tensor([0])),
            top_k=kwargs.pop("top_k", 2),
            student_temperature=kwargs.pop("student_temperature", .2),
            teacher_temperature=kwargs.pop("teacher_temperature", .2),
            **kwargs,
        )

    def test_wrong_neighbor_outside_teacher_topk_is_penalized(self):
        good = torch.tensor([[1., 0., 0.]])
        bad = torch.tensor([[0., 0., 1.]], requires_grad=True)
        good_loss, bad_loss = self.loss(good), self.loss(bad)
        self.assertGreater(float(bad_loss.detach()), float(good_loss) + 1.)
        bad_loss.backward()
        self.assertTrue(torch.isfinite(bad.grad).all())
        self.assertGreater(float(bad.grad.abs().sum()), 0.)

    def test_non_target_student_gallery_item_contributes_to_denominator(self):
        # The teacher's two positives and their student scores stay identical;
        # only an outside negative moves onto the query, stealing probability.
        query = torch.tensor([[1., 0., 0.]])
        ordinary = self.student_gallery.clone()
        ordinary[1:3] = query
        wrong_hub = ordinary.clone()
        wrong_hub[3] = query[0]
        baseline = self.loss(query, student_gallery=ordinary)
        changed = self.loss(query, student_gallery=wrong_hub)
        self.assertGreater(float(changed), float(baseline) + .3)

    def test_nonzero_self_index_is_excluded_on_both_sides(self):
        query = torch.tensor([[.9, .1, .2]], requires_grad=True)
        baseline = self.loss(query, self_indices=torch.tensor([2]))
        gradient = torch.autograd.grad(baseline, query)[0]
        student = self.student_gallery.clone()
        teacher = self.teacher_gallery.clone()
        student[2] = torch.tensor([-100., 12., 5.])
        teacher[2] = torch.tensor([-1., 0., 0.])
        changed_query = query.detach().clone().requires_grad_()
        changed = self.loss(changed_query, student_gallery=student, teacher_gallery=teacher,
                            self_indices=torch.tensor([2]))
        changed_gradient = torch.autograd.grad(changed, changed_query)[0]
        torch.testing.assert_close(changed, baseline)
        torch.testing.assert_close(changed_gradient, gradient)

    def test_only_queries_receive_gradients_not_gallery_or_teacher(self):
        query = torch.tensor([[.2, .4, .9]], requires_grad=True)
        gallery = self.student_gallery.clone().requires_grad_()
        teacher_query = self.teacher_query.clone().requires_grad_()
        teacher_gallery = self.teacher_gallery.clone().requires_grad_()
        loss = self.loss(query, student_gallery=gallery, teacher_queries=teacher_query,
                         teacher_gallery=teacher_gallery)
        loss.backward()
        self.assertIsNotNone(query.grad)
        self.assertGreater(float(query.grad.abs().sum()), 0.)
        self.assertIsNone(gallery.grad)
        self.assertIsNone(teacher_query.grad)
        self.assertIsNone(teacher_gallery.grad)

    def test_large_topk_clips_to_nonself_gallery_and_matched_distributions_have_zero_loss(self):
        small = torch.eye(2)
        loss = self.loss(small[[0]], student_gallery=small, teacher_queries=small[[0]],
                         teacher_gallery=small, top_k=100)
        self.assertTrue(torch.isfinite(loss))
        self.assertAlmostEqual(float(loss), 0., places=7)
        full_loss = self.loss(self.teacher_query, top_k=100)
        self.assertAlmostEqual(float(full_loss), 0., places=6)

    def test_teacher_and_student_can_have_different_embedding_dimensions(self):
        student = torch.tensor([[1., 0.], [1., 0.], [1., 0.], [0., 1.]])
        loss = self.loss(torch.tensor([[1., 0.]]), student_gallery=student)
        self.assertTrue(torch.isfinite(loss))

    def test_batch_loss_is_mean_of_independent_queries(self):
        queries = torch.tensor([[.2, .4, .9], [.8, .1, .3]])
        indices = torch.tensor([0, 3])
        batch = self.loss(queries, teacher_queries=self.teacher_gallery[indices], self_indices=indices)
        individual = [self.loss(queries[i:i + 1], teacher_queries=self.teacher_gallery[index:index + 1],
                                self_indices=indices[i:i + 1]) for i, index in enumerate(indices.tolist())]
        torch.testing.assert_close(batch, torch.stack(individual).mean())

    def test_rejects_invalid_self_indices_lengths_and_temperatures(self):
        query = torch.tensor([[1., 0., 0.]])
        invalid = [
            ({"self_indices": torch.tensor([-1])}, "outside gallery"),
            ({"self_indices": torch.tensor([4])}, "outside gallery"),
            ({"self_indices": torch.tensor([0, 1])}, "query lengths"),
            ({"teacher_queries": self.teacher_gallery[:2]}, "query lengths"),
            ({"teacher_gallery": self.teacher_gallery[:2]}, "gallery lengths"),
            ({"student_gallery": self.student_gallery[:1]}, "at least two"),
            ({"top_k": 0}, "positive"),
            ({"student_temperature": 0}, "positive"),
            ({"teacher_temperature": -1}, "positive"),
        ]
        for kwargs, message in invalid:
            with self.subTest(kwargs=kwargs), self.assertRaisesRegex(ValueError, message):
                self.loss(query, **kwargs)


class RetrievalSelectionTests(unittest.TestCase):
    def test_epoch_zero_baseline_is_eligible_and_tied_epochs_do_not_beat_it(self):
        baseline = validation_report()
        baseline_key = runner.selection_key(baseline, baseline)
        self.assertEqual(baseline_key, (.5, .9, .7))
        self.assertFalse(runner.selection_key(copy.deepcopy(baseline), baseline) > baseline_key)

    def test_scene_macro_regression_rejects_candidate_even_with_better_teacher_overlap(self):
        baseline = validation_report()
        for candidate in (validation_report(top1=.69, overlap=.99),
                          validation_report(top5=.89, overlap=.99)):
            with self.subTest(candidate=candidate):
                self.assertIsNone(runner.selection_key(candidate, baseline))

    def test_teacher_overlap_is_primary_selection_metric_after_both_scene_guards(self):
        baseline = validation_report()
        baseline_key = runner.selection_key(baseline, baseline)
        self.assertGreater(runner.selection_key(validation_report(overlap=.6), baseline), baseline_key)
        self.assertLess(runner.selection_key(validation_report(top1=.9, top5=1., overlap=.4), baseline),
                        baseline_key)

    def test_optimization_keeps_baseline_checkpoint_when_epoch_validation_regresses(self):
        self.assert_mock_optimization_selection(validation_report(top5=.8, overlap=.9), expected_epoch=0)

    def test_optimization_selects_improvement_without_reading_test_split(self):
        self.assert_mock_optimization_selection(validation_report(overlap=.6), expected_epoch=1)

    def assert_mock_optimization_selection(self, candidate, expected_epoch):
        """Exercise selection/checkpoint orchestration with no files, CNN, or real evaluation."""
        model = nn.Linear(2, 2, bias=False)
        with torch.no_grad():
            model.weight.copy_(torch.eye(2))
        initial = copy.deepcopy(model.state_dict())
        checkpoint = {
            "model": {"image_size": 2}, "model_state": initial,
            "projection": {"mean": torch.zeros(2), "components": torch.eye(2)},
        }
        baseline = validation_report()
        old_threads = torch.get_num_threads()
        self.addCleanup(torch.set_num_threads, old_threads)
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            root = Path(directory)
            args = argparse.Namespace(
                dataset_dir=root / "data", init_checkpoint=root / "initial.pt", output_dir=root / "run",
                seed=7, cpu_threads=1, device="cpu", batch_size=2, epochs=1,
                learning_rate=.1, weight_decay=.1, update_batchnorm=False, no_augmentation=True,
                teacher_top_k=1, student_temperature=.2, teacher_temperature=.2,
                embedding_weight=1., ranking_weight=1.,
            )
            read = stack.enter_context(mock.patch.object(runner, "read_rows", side_effect=[
                [dataset_row("train1", "train"), dataset_row("train2", "train")],
                [dataset_row("val1", "val")],
            ]))
            stack.enter_context(mock.patch.object(runner, "load_images", side_effect=[
                torch.eye(2), torch.tensor([[1., 0.]]),
            ]))
            stack.enter_context(mock.patch.object(runner, "load_teachers", side_effect=[
                np.eye(2, dtype=np.float32), np.array([[1., 0.]], dtype=np.float32),
            ]))
            stack.enter_context(mock.patch.object(runner, "build_student_model", return_value=model))
            stack.enter_context(mock.patch.object(runner.torch, "load", return_value=checkpoint))
            saved = stack.enter_context(mock.patch.object(runner.torch, "save"))
            stack.enter_context(mock.patch.object(runner, "sha256", return_value="0" * 64))
            stack.enter_context(mock.patch.object(runner, "write_json"))
            stack.enter_context(mock.patch.object(runner.np, "savez_compressed"))
            stack.enter_context(mock.patch.object(runner, "prepare_batch", side_effect=lambda value, *a, **kw: value))
            stack.enter_context(mock.patch.object(runner, "encode", side_effect=lambda model, images, *a: images.numpy()))
            measure = stack.enter_context(mock.patch.object(runner, "evaluate_retrieval", side_effect=[baseline, candidate]))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            summary = runner.optimize(args)

        self.assertEqual(summary["best_epoch"], expected_epoch)
        self.assertEqual(summary["improved_validation"], expected_epoch > 0)
        self.assertFalse(summary["test_evaluated"])
        self.assertFalse(summary["provenance"]["test_used_for_selection"])
        self.assertEqual(summary["provenance"]["dataset_validation"]["unknown_source_group_rows"],
                         {"train": 2, "val": 1})
        self.assertEqual([call.args[0].name for call in read.call_args_list], ["train.jsonl", "val.jsonl"])
        self.assertEqual(measure.call_count, 2)
        saved_checkpoint = saved.call_args.args[0]
        self.assertEqual(saved_checkpoint["retrieval_optimization"]["best_epoch"], expected_epoch)
        self.assertEqual(summary["best_validation"], baseline if expected_epoch == 0 else candidate)
        if expected_epoch == 0:
            torch.testing.assert_close(saved_checkpoint["model_state"]["weight"], initial["weight"], rtol=0, atol=0)
            torch.testing.assert_close(model.weight, initial["weight"], rtol=0, atol=0)
        else:
            self.assertFalse(torch.equal(saved_checkpoint["model_state"]["weight"], initial["weight"]))


if __name__ == "__main__":
    unittest.main()
