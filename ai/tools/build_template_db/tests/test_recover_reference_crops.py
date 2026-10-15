import copy
import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageOps

from recover_reference_crops import OUTPUT_ROOT, normalized_box, read_jsonl, recover, sha256_bytes


class RecoverReferenceCropsTests(unittest.TestCase):
    def setUp(self):
        temporary_root = OUTPUT_ROOT / "model-optimization-2026-10-14" / "recovery-tests" / "tmp"
        temporary_root.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=temporary_root)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "original.png"
        image = Image.new("RGB", (640, 480), "red")
        image.paste("blue", (320, 0, 640, 480))
        image.save(self.source)
        self.parent = {
            "photo_id": "parent", "split": "train", "source_path": str(self.source),
            "source_sha256": sha256_bytes(self.source.read_bytes()), "source_group_id": "source-post-7",
            "embedding_path": "obsolete.npy", "teacher_embedding": [1, 0],
            "annotation": {"subject_x": 0.8}, "scene_category": "portrait", "photo_type": "portrait",
        }
        self.plan = {"photo_id": "parent", "source_sha256": self.parent["source_sha256"],
                     "recovery_regions": [{"region_id": "right", "bbox": [0.5, 0, 1, 1]}]}
        self.manifest = self.root / "train.jsonl"
        self.review = self.root / "plan.json"
        self.output = self.root / "recovered"

    def write_inputs(self, parents=None, plans=None):
        self.manifest.write_text("\n".join(json.dumps(row) for row in (parents or [self.parent])) + "\n", encoding="utf-8")
        self.review.write_text(json.dumps({"items": plans or [self.plan]}), encoding="utf-8")

    def run_recovery(self):
        self.write_inputs()
        summary = recover(self.manifest, self.review, self.output)
        return summary, read_jsonl(Path(summary["manifest"]))

    def test_exact_lossless_crop_has_provenance_and_no_stale_labels(self):
        original_bytes = self.source.read_bytes()
        summary, rows = self.run_recovery()
        row = rows[0]
        with Image.open(row["source_path"]) as crop:
            self.assertEqual(crop.format, "PNG")
            self.assertEqual(crop.size, (320, 480))
            with Image.open(self.source) as original:
                self.assertEqual(crop.tobytes(), original.crop((320, 0, 640, 480)).tobytes())
        self.assertEqual(self.source.read_bytes(), original_bytes)
        self.assertEqual(row["source_sha256"], sha256_bytes(Path(row["source_path"]).read_bytes()))
        self.assertEqual(row["parent_source_sha256"], self.parent["source_sha256"])
        self.assertEqual(row["parent_photo_id"], "parent")
        self.assertEqual(row["split"], "train")
        self.assertEqual(row["source_group_id"], "source-post-7")
        self.assertTrue(row["needs_visual_review"])
        self.assertFalse(row["synthetic"])
        self.assertEqual(summary["status"], "needs_visual_review")
        for stale in ("embedding_path", "teacher_embedding", "annotation", "scene_category", "photo_type"):
            self.assertNotIn(stale, row)

    def test_exif_orientation_is_applied_before_region_coordinates(self):
        self.source = self.root / "rotated.jpg"
        image = Image.new("RGB", (600, 400), "red")
        image.paste("blue", (300, 0, 600, 400))
        exif = Image.Exif()
        exif[274] = 6
        image.save(self.source, exif=exif)
        self.parent.update(source_path=str(self.source), source_sha256=sha256_bytes(self.source.read_bytes()))
        self.plan.update(source_sha256=self.parent["source_sha256"])
        self.plan["recovery_regions"][0]["bbox"] = [0, 0, 1, 0.5]
        _, rows = self.run_recovery()
        self.assertEqual(rows[0]["recovery"]["source_oriented_size"], [400, 600])
        with Image.open(self.source) as original, Image.open(rows[0]["source_path"]) as crop:
            expected = ImageOps.exif_transpose(original).convert("RGB").crop((0, 0, 400, 300))
            self.assertEqual(crop.size, (400, 300))
            self.assertEqual(crop.tobytes(), expected.tobytes())
            self.assertNotEqual(crop.getexif().get(274), 6)

    def test_unknown_group_uses_same_parent_hash_for_sibling_crops(self):
        self.parent["source_group_id"] = "unknown"
        self.plan["recovery_regions"].append({"region_id": "left", "bbox": [0, 0, 0.5, 1]})
        _, rows = self.run_recovery()
        self.assertNotEqual(rows[0]["photo_id"], rows[1]["photo_id"])
        self.assertEqual(rows[0]["source_group_id"], "parent_sha256:" + self.parent["source_sha256"])
        self.assertEqual(rows[0]["source_group_id"], rows[1]["source_group_id"])

    def test_rejects_non_train_and_unknown_split_even_with_valid_image(self):
        for split in ("val", "test", "unknown", None):
            with self.subTest(split=split):
                self.parent["split"] = split
                self.write_inputs()
                with self.assertRaisesRegex(ValueError, "only split=train"):
                    recover(self.manifest, self.review, self.output)
                self.assertFalse(self.output.exists())

    def test_rejects_review_nontrain_and_parent_not_in_manifest(self):
        for change, message in (({"split": "val"}, "non-train"), ({"photo_id": "held-out"}, "not in training")):
            with self.subTest(change=change):
                plan = dict(self.plan, **change)
                self.write_inputs(plans=[plan])
                with self.assertRaisesRegex(ValueError, message):
                    recover(self.manifest, self.review, self.output)

    def test_rejects_review_hash_mismatch_and_changed_source_bytes(self):
        self.plan["source_sha256"] = "f" * 64
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "review/parent SHA mismatch"):
            recover(self.manifest, self.review, self.output)
        self.plan["source_sha256"] = self.parent["source_sha256"]
        self.source.write_bytes(self.source.read_bytes() + b"changed")
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "source image SHA mismatch"):
            recover(self.manifest, self.review, self.output)

    def test_invalid_coordinates_are_not_clamped_or_inferred(self):
        for box in ([-0.1, 0, 1, 1], [0, 0, 1.1, 1], [0, 0, float("nan"), 1],
                    [0.6, 0, 0.5, 1], [0, 0, 0, 1], [False, 0, 1, 1], [0, 0, 1]):
            with self.subTest(box=box), self.assertRaises(ValueError):
                normalized_box({"bbox": box})
        with self.assertRaises(ValueError):
            normalized_box({"bbox": [0, 0, 1, 1], "coordinate_space": "unrotated"})

    def test_small_crop_is_rejected_without_upscaling_or_partial_output(self):
        self.plan["recovery_regions"].append({"region_id": "tiny", "bbox": [0, 0, 0.1, 1]})
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "shortest edge"):
            recover(self.manifest, self.review, self.output)
        self.assertFalse(self.output.exists())
        with self.assertRaisesRegex(ValueError, "at least 224"):
            recover(self.manifest, self.review, self.output, min_short_edge=100)

    def test_duplicate_region_ids_or_boxes_are_rejected(self):
        for second in ({"region_id": "right", "bbox": [0, 0, 0.5, 1]},
                       {"region_id": "duplicate", "bbox": [0.5, 0, 1, 1]}):
            plan = copy.deepcopy(self.plan)
            plan["recovery_regions"].append(second)
            self.write_inputs(plans=[plan])
            with self.assertRaisesRegex(ValueError, "duplicate recovery"):
                recover(self.manifest, self.review, self.output)
            self.assertFalse(self.output.exists())

    def test_output_must_be_empty_and_inside_workspace_local_output(self):
        self.write_inputs()
        self.output.mkdir()
        artifact = self.output / "keep.txt"
        artifact.write_text("keep", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "never overwritten"):
            recover(self.manifest, self.review, self.output)
        self.assertEqual(artifact.read_text(encoding="utf-8"), "keep")
        with self.assertRaisesRegex(ValueError, "workspace local-output"):
            recover(self.manifest, self.review, OUTPUT_ROOT.parent / "forbidden-recovery-test")

    def test_jsonl_review_skips_unapproved_rows_and_has_stable_child_id(self):
        _, first = self.run_recovery()
        self.review = self.root / "review.jsonl"
        self.review.write_text(json.dumps({"photo_id": "unapproved"}) + "\n" + json.dumps(self.plan) + "\n", encoding="utf-8")
        summary = recover(self.manifest, self.review, self.root / "second")
        second = read_jsonl(Path(summary["manifest"]))
        self.assertEqual(first[0]["photo_id"], second[0]["photo_id"])
        self.assertEqual(first[0]["source_sha256"], second[0]["source_sha256"])


if __name__ == "__main__":
    unittest.main()
