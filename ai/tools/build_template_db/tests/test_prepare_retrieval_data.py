import hashlib
import json
import struct
import tempfile
import unittest
from pathlib import Path

import prepare_retrieval_data as prepare


def write_jsonl(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")


class PrepareRetrievalDataTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "archive" / "tools"
        self.output = Path(self.temp.name) / "experiment" / "dataset"
        self.preprocessed = self.root / "preprocessed"
        self.source = self.preprocessed / "distillation.siglip2_new_batch"
        self.metadata_path = self.preprocessed / "template_metadata.new_batch.siglip2-teacher.jsonl"
        self.raw_path = self.root / "raw_photos/new_batch/manifest.accepted.jsonl"
        self.splits = {"train": [], "val": [], "test": []}
        self.metadata, self.raw = [], []
        # Known group A crosses train/val/test; B crosses val/test; C is test only.
        specs = [("train", "a"), ("val", "a"), ("val", "b"), ("val", None),
                 ("test", "a"), ("test", "b"), ("test", "c"), ("test", None)]
        for index, (split, group) in enumerate(specs, 1):
            photo_id = f"p{index}"
            image = self.root / "raw_photos/new_batch/images" / f"{photo_id}.jpg"
            image.parent.mkdir(parents=True, exist_ok=True)
            # The preparation script audits bytes; image decoding belongs to inference.
            image.write_bytes(f"image bytes {photo_id}".encode())
            digest = hashlib.sha256(image.read_bytes()).hexdigest()
            teacher = self.preprocessed / "teacher_embeddings.siglip2_new_batch" / photo_id / "embedding.f32"
            teacher.parent.mkdir(parents=True, exist_ok=True)
            teacher.write_bytes(struct.pack("<4f", 1, 0, 0, 0))
            relative = f"../raw_photos/new_batch/images/{photo_id}.jpg"
            row = {"photo_id": photo_id, "template_id": photo_id, "split": split,
                   "source_path": relative, "photo_type": "landscape", "scene_category": "natural_landscape",
                   "labels": {"composition": {"type": "rule_of_thirds"}},
                   "teacher_embedding": {"dimension": 4, "model_id": "teacher-test",
                                         "path": f"/Volumes/old/tools/preprocessed/teacher_embeddings.siglip2_new_batch/{photo_id}/embedding.f32"}}
            self.splits[split].append(row)
            self.metadata.append({key: row[key] for key in ("photo_id", "template_id", "photo_type", "scene_category", "source_path")})
            self.metadata[-1].update({"source_sha256": digest,
                                      "embedding": {"status": "ready", "dimension": 4, "model_id": "teacher-test"}})
            self.raw.append({"photo_id": photo_id, "sha256": digest,
                             "source": {"provider": "local_xhs_export", "provider_id": f"post_{group * 24}_01" if group else "opaque_crop",
                                        "landing_url": f"https://www.xiaohongshu.com/explore/{group * 24}" if group else ""}})
        self.write_sources()

    def write_sources(self):
        for split, rows in self.splits.items():
            write_jsonl(self.source / f"{split}.jsonl", rows)
        write_jsonl(self.metadata_path, self.metadata)
        write_jsonl(self.raw_path, self.raw)
        manifest = {"schema_version": 1, "seed": 7, "total": sum(map(len, self.splits.values())),
                    "splits": {split: len(rows) for split, rows in self.splits.items()}}
        (self.source / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    def run_prepare(self, exclude=False):
        return prepare.prepare_dataset(self.root, self.output, exclude)

    def test_recovers_paths_preserves_rows_and_original_data(self):
        originals = {path: path.read_bytes() for path in self.root.rglob("*") if path.is_file()}
        report = self.run_prepare()
        self.assertEqual({s: d["count"] for s, d in report["output_distribution"].items()}, {"train": 1, "val": 3, "test": 4})
        for split in prepare.SPLITS:
            rows = prepare.load_jsonl(self.output / f"{split}.jsonl")
            self.assertEqual([r["photo_id"] for r in rows], [r["photo_id"] for r in self.splits[split]])
            for original, row in zip(self.splits[split], rows):
                self.assertEqual(row["labels"], original["labels"])
                self.assertEqual(row["source_sha256"], prepare.sha256_file(Path(row["source_path"])))
                self.assertTrue(Path(row["source_path"]).is_absolute())
                teacher = row["teacher_embedding"]
                self.assertTrue(Path(teacher["path"]).is_absolute())
                self.assertEqual(teacher["sha256"], prepare.sha256_file(Path(teacher["path"])))
        self.assertFalse(report["label_policy"]["composition_supervision_allowed"])
        self.assertEqual(report["source_groups"]["before"]["unknown_rows"], {"train": 0, "val": 1, "test": 1})
        for name, info in report["outputs"].items():
            self.assertEqual(info["sha256"], prepare.sha256_file(self.output / name))
        self.assertTrue(all(path.read_bytes() == data for path, data in originals.items()))

    def test_excludes_known_overlap_in_split_priority_order_and_keeps_unknowns(self):
        report = self.run_prepare(exclude=True)
        actual = {split: [row["photo_id"] for row in prepare.load_jsonl(self.output / f"{split}.jsonl")] for split in prepare.SPLITS}
        self.assertEqual(actual, {"train": ["p1"], "val": ["p3", "p4"], "test": ["p7", "p8"]})
        self.assertEqual(report["excluded_counts"], {"val": 1, "test": 2})
        self.assertEqual(report["source_groups"]["after"]["cross_split_known_groups"], {"train/val": 0, "train/test": 0, "val/test": 0})
        self.assertFalse(report["source_groups"]["after"]["unknown_groups_are_independent"])

    def test_same_group_within_one_split_is_preserved(self):
        self.raw[3]["source"] = dict(self.raw[2]["source"])
        self.write_sources()
        self.run_prepare(exclude=True)
        self.assertEqual([row["photo_id"] for row in prepare.load_jsonl(self.output / "val.jsonl")], ["p3", "p4"])

    def test_rejects_cross_split_duplicate_photo_id(self):
        self.splits["test"].append(dict(self.splits["train"][0], split="test"))
        self.write_sources()
        with self.assertRaisesRegex(ValueError, "duplicate photo_id"):
            self.run_prepare()
        self.assertFalse(self.output.exists())

    def test_rejects_identical_image_bytes_under_different_names(self):
        image = prepare.archived_image_path(self.metadata[1]["source_path"], self.root)
        image.write_bytes(prepare.archived_image_path(self.metadata[0]["source_path"], self.root).read_bytes())
        self.metadata[1]["source_sha256"] = self.raw[1]["sha256"] = prepare.sha256_file(image)
        self.write_sources()
        with self.assertRaisesRegex(ValueError, "duplicate source_sha256"):
            self.run_prepare()
        self.assertFalse(self.output.exists())

    def test_rejects_modified_image_and_teacher_before_creating_output(self):
        image = prepare.archived_image_path(self.metadata[0]["source_path"], self.root)
        original = image.read_bytes()
        image.write_bytes(b"changed image")
        with self.assertRaisesRegex(ValueError, "source_sha256"):
            self.run_prepare()
        image.write_bytes(original)
        teacher = self.preprocessed / "teacher_embeddings.siglip2_new_batch/p1/embedding.f32"
        for values, message in (([float("nan"), 0, 0, 0], "non-finite"), ([2, 0, 0, 0], "L2-normalized")):
            with self.subTest(message=message):
                teacher.write_bytes(struct.pack("<4f", *values))
                with self.assertRaisesRegex(ValueError, message):
                    self.run_prepare()
                self.assertFalse(self.output.exists())
        teacher.write_bytes(b"short")
        with self.assertRaisesRegex(ValueError, "byte size"):
            self.run_prepare()

    def test_rejects_manifest_and_teacher_dimension_mismatch(self):
        manifest_path = self.source / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["total"] += 1
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "manifest counts"):
            self.run_prepare()
        self.write_sources()
        self.splits["train"][0]["teacher_embedding"]["dimension"] = True
        self.metadata[0]["embedding"]["dimension"] = True
        self.write_sources()
        with self.assertRaisesRegex(ValueError, "positive integer"):
            self.run_prepare()

    def test_group_extraction_is_conservative_and_detects_ambiguous_ids(self):
        record = self.raw[0]
        self.assertTrue(prepare.source_group_id(record).startswith("xhs_post:"))
        record["source"]["landing_url"] = "https://example.test/" + "b" * 24
        self.assertIsNone(prepare.source_group_id(record))
        record["source"]["provider_id"] = "opaque_media_" + "a" * 32
        record["source"]["landing_url"] = ""
        self.assertIsNone(prepare.source_group_id(record))
        record["source"].update(provider="local_openverse_import", provider_id="a" * 24)
        self.assertIsNone(prepare.source_group_id(record))

    def test_recovers_old_absolute_paths_and_rejects_escape(self):
        expected = prepare.archived_image_path(self.metadata[0]["source_path"], self.root)
        for value in ("/Volumes/desktop/AI-camera/tools/raw_photos/new_batch/images/p1.jpg",
                      "C:\\old\\AI-camera\\tools\\raw_photos\\new_batch\\images\\p1.jpg"):
            self.assertEqual(prepare.archived_image_path(value, self.root), expected)
        with self.assertRaisesRegex(ValueError, "inside the archive"):
            prepare.archived_image_path("../../outside.jpg", self.root)

    def test_refuses_existing_output_and_output_inside_archive(self):
        with self.assertRaisesRegex(ValueError, "outside the source"):
            prepare.prepare_dataset(self.root, self.root / "derived")
        self.output.mkdir(parents=True)
        marker = self.output / "preserve.txt"
        marker.write_text("do not overwrite")
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.run_prepare()
        self.assertEqual(marker.read_text(), "do not overwrite")


if __name__ == "__main__":
    unittest.main()
