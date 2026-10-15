import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

import export_student_model
from student_embedding_model import StudentModelConfig, build_student_model, config_to_dict


class ExportStudentModelTests(unittest.TestCase):
    def test_exports_fp32_and_dynamic_int8_torchscript(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "中文模型导出"
            root.mkdir()
            config = StudentModelConfig(output_dim=8, image_size=32, width_mult=0.25, dropout=0.0)
            model = build_student_model(config)
            checkpoint = {
                "schema_version": 1,
                "model": config_to_dict(config),
                "model_state": model.state_dict(),
                "projection": {
                    "teacher_dimension": 16,
                    "output_dim": 8,
                    "mean": torch.from_numpy(np.zeros(16, dtype=np.float32)),
                    "components": torch.from_numpy(np.zeros((8, 16), dtype=np.float32)),
                },
                "training": {},
            }
            checkpoint_path = root / "checkpoint.pt"
            torch.save(checkpoint, checkpoint_path)

            summary = export_student_model.export_model(checkpoint_path, root / "export")

            self.assertTrue(Path(summary["exports"]["fp32_torchscript"]["path"]).exists())
            self.assertTrue(Path(summary["exports"]["dynamic_int8_torchscript"]["path"]).exists())
            for name in ("fp32_torchscript", "dynamic_int8_torchscript"):
                with Path(summary["exports"][name]["path"]).open("rb") as stream:
                    restored = torch.jit.load(stream)
                output = restored(torch.zeros(1, 3, 32, 32))
                self.assertEqual(tuple(output.shape), (1, 8))
                self.assertTrue(torch.isfinite(output).all())
            self.assertIn(summary["exports"]["fp32_onnx"]["status"], {"ready", "skipped"})
            self.assertEqual(summary["android_budget"]["model_size_target_bytes"], 5 * 1024 * 1024)
            self.assertTrue((root / "export" / "export_manifest.json").exists())


if __name__ == "__main__":
    unittest.main()
