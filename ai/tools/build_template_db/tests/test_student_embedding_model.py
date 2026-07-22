import unittest

import torch

from student_embedding_model import StudentModelConfig, build_student_model, count_parameters


class StudentEmbeddingModelTests(unittest.TestCase):
    def test_model_outputs_normalized_embeddings(self):
        model = build_student_model(StudentModelConfig(output_dim=16, image_size=64, width_mult=0.25))
        model.eval()
        with torch.no_grad():
            output = model(torch.randn(2, 3, 64, 64))
        self.assertEqual(tuple(output.shape), (2, 16))
        norms = torch.linalg.vector_norm(output, dim=1)
        self.assertTrue(torch.allclose(norms, torch.ones_like(norms), atol=1e-5))
        self.assertGreater(count_parameters(model), 0)

    def test_lite_model_outputs_normalized_embeddings(self):
        model = build_student_model(
            StudentModelConfig(
                architecture="lite_cnn_v2",
                output_dim=16,
                image_size=64,
                width_mult=0.25,
            )
        )
        model.eval()
        with torch.no_grad():
            output = model(torch.randn(2, 3, 64, 64))
        self.assertEqual(tuple(output.shape), (2, 16))
        norms = torch.linalg.vector_norm(output, dim=1)
        self.assertTrue(torch.allclose(norms, torch.ones_like(norms), atol=1e-5))
        self.assertGreater(count_parameters(model), 0)

    def test_rejects_unknown_architecture(self):
        with self.assertRaisesRegex(ValueError, "unsupported student architecture"):
            build_student_model(StudentModelConfig(architecture="unknown"))


if __name__ == "__main__":
    unittest.main()
