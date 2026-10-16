import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import run_core_recommendation as runner


class AutonomousRunTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.run = self.root / "local-output/run"
        self.run.mkdir(parents=True)
        self.manifest = self.run / "source_manifest.jsonl"
        self.manifest.write_text('{"id":"source"}\n', encoding="utf-8")
        self.encoder = self.run / "encoder.onnx"
        self.encoder.write_bytes(b"frozen-encoder")
        runner.write(self.run / "frozen-protocol.json", {
            "source_manifest_sha256": runner.sha256(self.manifest),
            "encoder_sha256": runner.sha256(self.encoder)})
        runner.write(self.run / "quality_head.json", {"weights": "frozen-candidate"})
        policy = {"enabled": False, "head_sha256": runner.sha256(self.run / "quality_head.json")}
        runner.write(self.run / "quality_policy.json", policy)
        runner.write(self.run / "acceptance.json", {
            "frozen_protocol_sha256": runner.sha256(self.run / "frozen-protocol.json"), "policy": policy})

    def test_completed_rejected_model_is_reused_without_refitting(self):
        self.assertFalse(runner.verify_completed_quality(self.run, self.manifest, self.encoder)["enabled"])

    def test_changed_sources_or_encoder_cannot_reuse_acceptance(self):
        self.manifest.write_text("changed source set", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "inputs changed"):
            runner.verify_completed_quality(self.run, self.manifest, self.encoder)

    def test_enabled_flag_cannot_be_changed_after_frozen_rejection(self):
        policy = runner.read(self.run / "quality_policy.json")
        policy["enabled"] = True
        runner.write(self.run / "quality_policy.json", policy)
        with self.assertRaisesRegex(ValueError, "differs from frozen"):
            runner.verify_completed_quality(self.run, self.manifest, self.encoder)

    def test_candidate_weights_cannot_change_after_acceptance(self):
        (self.run / "quality_head.json").write_text("replacement", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "weights changed"):
            runner.verify_completed_quality(self.run, self.manifest, self.encoder)

    def test_outputs_cannot_escape_original_project(self):
        self.assertEqual(self.run.resolve(), runner.inside_output(self.root, self.run))
        with self.assertRaisesRegex(ValueError, "inside project"):
            runner.inside_output(self.root, self.run / "../../../outside")


if __name__ == "__main__":
    unittest.main()
