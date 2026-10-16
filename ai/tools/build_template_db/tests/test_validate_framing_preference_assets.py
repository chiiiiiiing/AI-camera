import base64
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import validate_framing_preference_assets as gate


class FramingReleaseGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.metrics = {"acceptance_cluster_count": 30, "pairwise_agreement": .8, "bootstrap_lower95": .6,
            "baseline_pairwise_agreement": .6, "keep_current_pairwise_agreement": .5, "scene_cluster_counts": {"landscape": 30},
            "hard_constraints_passed": True, "severe_defect_non_regression": True, "coverage": .7, "rejection_rate": .3,
            "preprocessing_parity_passed": True}
        self.policy = {"schema_version": 1, "enabled": True, "feature_protocol": gate.PROTOCOL,
            "crop_preprocessing": gate.CROP_PREPROCESSING, "encoder_sha256": "1"*64,
            "weak_teacher_only": True, "crop_advice_enabled": False, "physical_movement_prediction_enabled": False,
            "training_protocol_sha256": "2"*64, "acceptance_report_sha256": "3"*64,
            "historical_holdout_excluded": True, "previous_quality_acceptance_excluded": True,
            "enabled_scenes": ["landscape"], "minimum_score_margin": .1, "metrics": self.metrics,
            "per_scene": {"landscape": copy.deepcopy(self.metrics)}}
        self.head = {"schema_version": 1, "input_dim": 605, "hidden_dim": 64, "activation": "relu",
            "feature_protocol": gate.PROTOCOL, "crop_preprocessing": gate.CROP_PREPROCESSING, "encoder_sha256": "1"*64,
            "training_protocol_sha256": "2"*64, "weight_encoding": "float32-le-base64",
            "weights_f32_base64": base64.b64encode(bytes(gate.WEIGHT_COUNT*4)).decode(),
            "feature_mean": [0.]*605, "feature_std": [1.]*605}

    def bundle(self, include_head=True):
        profile = {"schema_version": 1}
        if include_head:
            (self.root/gate.HEAD).write_text(json.dumps(self.head), encoding="utf-8")
            self.policy["model_sha256"] = gate.sha256(self.root/gate.HEAD)
            profile["head"] = {"file": gate.HEAD, "sha256": self.policy["model_sha256"]}
        (self.root/gate.POLICY).write_text(json.dumps(self.policy), encoding="utf-8")
        profile["policy"] = {"file": gate.POLICY, "sha256": gate.sha256(self.root/gate.POLICY)}
        return {"framing_preference": profile}

    def check(self):
        return gate.validate(self.root, self.bundle(), "1"*64)

    def test_enabled_gate_recomputes_evidence_not_boolean_flag(self):
        self.assertTrue(self.check()["framing_preference_enabled"])
        for field, bad in (("acceptance_cluster_count",29),("pairwise_agreement",.69),
                           ("bootstrap_lower95",.5),("baseline_pairwise_agreement",.8),
                           ("keep_current_pairwise_agreement",.81),("hard_constraints_passed",False),
                           ("severe_defect_non_regression",False),("rejection_rate",.9)):
            old=self.metrics[field]
            self.metrics[field]=bad
            with self.subTest(field=field), self.assertRaises(ValueError): self.check()
            self.metrics[field]=old

    def test_failed_scene_cannot_hide_behind_passing_global_average(self):
        self.policy["per_scene"]["landscape"]["pairwise_agreement"] = .5
        with self.assertRaisesRegex(ValueError,"enabled-scene"): self.check()

    def test_wrong_encoder_protocol_or_weights_fail_closed(self):
        for field,bad in (("encoder_sha256","9"*64),("crop_preprocessing","old"),
                           ("input_dim",93),("weights_f32_base64","invalid"),
                           ("feature_std",[0.]*605)):
            old=self.head[field]
            self.head[field]=bad
            with self.subTest(field=field), self.assertRaises(ValueError): self.check()
            self.head[field]=old

    def test_disabled_policy_never_ships_failed_head(self):
        self.policy.update(enabled=False, enabled_scenes=[], reasons=["teacher_crop_agreement"])
        config=self.bundle(False)
        self.assertFalse(gate.validate(self.root,config,"1"*64)["framing_preference_enabled"])
        (self.root/gate.HEAD).write_text("local failed candidate",encoding="utf-8")
        with self.assertRaisesRegex(ValueError,"remain local"): gate.validate(self.root,config,"1"*64)

    def test_policy_hash_or_absent_binding_cannot_bypass_release_gate(self):
        config=self.bundle()
        config["framing_preference"]["policy"]["sha256"]="0"*64
        with self.assertRaisesRegex(ValueError,"policy hash"): gate.validate(self.root,config,"1"*64)
        with self.assertRaisesRegex(ValueError,"unbound"): gate.validate(self.root,{},"1"*64)

    def test_packager_preserves_input_and_previous_experiment(self):
        from package_framing_preference import package
        output=self.root/'local-output'
        experiment=output/'experiment'
        experiment.mkdir(parents=True)
        for staging in (output,experiment,experiment/'stage'):
            with self.assertRaises(ValueError): package(self.root,experiment,staging)
        previous=output/'previous'
        previous.mkdir()
        (previous/'evidence.json').write_text('preserve',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'empty'): package(self.root,experiment,previous)
        self.assertEqual('preserve',(previous/'evidence.json').read_text())


if __name__ == "__main__":
    unittest.main()
