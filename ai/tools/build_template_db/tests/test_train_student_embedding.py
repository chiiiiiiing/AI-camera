import unittest

import numpy as np
import torch

import train_student_embedding


class TrainStudentEmbeddingTests(unittest.TestCase):
    def test_wrong_neighbor_outside_teacher_topk_is_penalized(self):
        vectors = torch.tensor([[1., 0., 0.], [.8, .6, 0.], [.8, -.6, 0.], [0., 0., 1.]])
        bank = train_student_embedding.ReferenceBank(vectors, vectors, torch.zeros(4).long(), torch.zeros(4).long())
        good = torch.tensor([[1., 0., 0.]])
        bad = torch.tensor([[0., 0., 1.]], requires_grad=True)
        def loss(query):
            return train_student_embedding.teacher_topk_listwise_loss(query, torch.tensor([0]), bank, 2, .2, .2)
        self.assertGreater(float(loss(bad)), float(loss(good)) + 1.)
        loss(bad).backward()
        self.assertGreater(float(bad.grad.abs().sum()), 0.)

    def test_default_augmentation_preserves_spatial_layout(self):
        transform = train_student_embedding.transform_for_split(224, train=True)
        names = [type(step).__name__ for step in transform.transforms]
        self.assertNotIn("RandomHorizontalFlip", names)
        self.assertNotIn("RandomResizedCrop", names)

    def test_teacher_projection_outputs_unit_vectors(self):
        embeddings = np.array(
            [
                [1.0, 0.0, 0.0],
                [0.0, 1.0, 0.0],
                [0.0, 0.0, 1.0],
                [1.0, 1.0, 0.0],
            ],
            dtype=np.float32,
        )
        projection = train_student_embedding.fit_teacher_projection(embeddings, output_dim=2)
        projected = train_student_embedding.project_teacher_embeddings(embeddings, projection)
        self.assertEqual(projected.shape, (4, 2))
        self.assertTrue(np.allclose(np.linalg.norm(projected, axis=1), 1.0, atol=1e-5))

    def test_rejects_output_dim_above_pca_rank(self):
        embeddings = np.ones((2, 4), dtype=np.float32)
        with self.assertRaisesRegex(ValueError, "exceeds available PCA rank"):
            train_student_embedding.fit_teacher_projection(embeddings, output_dim=3)

    def test_teacher_topk_listwise_prefers_teacher_order(self):
        bank = train_student_embedding.ReferenceBank(
            targets=torch.tensor(
                [
                    [1.0, 0.0],
                    [0.92, 0.39],
                    [0.0, 1.0],
                ],
                dtype=torch.float32,
            ),
            teacher_embeddings=torch.tensor(
                [
                    [1.0, 0.0],
                    [0.92, 0.39],
                    [0.0, 1.0],
                ],
                dtype=torch.float32,
            ),
            photo_type_labels=torch.tensor([0, 0, 0], dtype=torch.long),
            scene_labels=torch.tensor([0, 0, 1], dtype=torch.long),
        )
        sample_indices = torch.tensor([0], dtype=torch.long)
        good_prediction = torch.tensor([[0.92, 0.39]], dtype=torch.float32)
        bad_prediction = torch.tensor([[0.0, 1.0]], dtype=torch.float32)

        good_loss = train_student_embedding.teacher_topk_listwise_loss(
            good_prediction,
            sample_indices,
            bank,
            top_k=2,
            student_temperature=0.2,
            teacher_temperature=0.2,
        )
        bad_loss = train_student_embedding.teacher_topk_listwise_loss(
            bad_prediction,
            sample_indices,
            bank,
            top_k=2,
            student_temperature=0.2,
            teacher_temperature=0.2,
        )

        self.assertLess(float(good_loss), float(bad_loss))

    def test_hard_negative_margin_penalizes_wrong_scene(self):
        bank = train_student_embedding.ReferenceBank(
            targets=torch.tensor(
                [
                    [1.0, 0.0],
                    [0.95, 0.31],
                    [0.95, -0.31],
                ],
                dtype=torch.float32,
            ),
            teacher_embeddings=torch.tensor(
                [
                    [1.0, 0.0],
                    [0.95, 0.31],
                    [0.95, -0.31],
                ],
                dtype=torch.float32,
            ),
            photo_type_labels=torch.tensor([0, 0, 0], dtype=torch.long),
            scene_labels=torch.tensor([0, 0, 1], dtype=torch.long),
        )
        sample_indices = torch.tensor([0], dtype=torch.long)
        good_prediction = torch.tensor([[0.95, 0.31]], dtype=torch.float32)
        bad_prediction = torch.tensor([[0.95, -0.31]], dtype=torch.float32)

        good_loss = train_student_embedding.hard_negative_margin_loss(
            good_prediction,
            sample_indices,
            bank,
            positive_count=1,
            negative_count=1,
            margin=0.05,
            same_photo_type_only=True,
        )
        bad_loss = train_student_embedding.hard_negative_margin_loss(
            bad_prediction,
            sample_indices,
            bank,
            positive_count=1,
            negative_count=1,
            margin=0.05,
            same_photo_type_only=True,
        )

        self.assertLess(float(good_loss), float(bad_loss))


if __name__ == "__main__":
    unittest.main()
