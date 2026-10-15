import unittest

import numpy as np
from PIL import Image

from multiscene_detector import BOX_OUTPUTS, CLASS_OUTPUTS, STRIDES, decode, preprocess


class MultiSceneDetectorTests(unittest.TestCase):
    @staticmethod
    def heads():
        result = {}
        for stride, cls, box in zip(STRIDES, CLASS_OUTPUTS, BOX_OUTPUTS):
            count = (416//stride)**2
            result[cls] = np.zeros((1,count,80), dtype=np.float32)
            result[box] = np.zeros((1,count,32), dtype=np.float32)
        return result

    def test_rgb_becomes_bgr_with_official_channel_constants(self):
        tensor = preprocess(Image.new("RGB", (224,224), (255,0,0)))
        self.assertEqual(tensor.shape, (1,3,416,416))
        np.testing.assert_allclose(tensor[0,:,200,200], [-103.53/57.375,-116.28/57.12,(255-123.675)/58.395], atol=1e-6)

    def test_named_onnx_heads_have_three_strides_and_correct_distribution_decode(self):
        heads = self.heads()
        # Uniform eight-bin distributions have mean 3.5 bins, stride16 ->56 px.
        heads[CLASS_OUTPUTS[1]][0, 10*26+12, 41] = .8
        result = decode(dict(reversed(list(heads.items()))))
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["class_id"],41)
        self.assertEqual(result[0]["label"],"cup")
        np.testing.assert_allclose(result[0]["box"], [(199.5-56)/416,(167.5-56)/416,(199.5+56)/416,(167.5+56)/416], atol=1e-7)

    def test_large_logits_are_stable_and_nonfinite_rows_are_rejected(self):
        heads = self.heads()
        heads[CLASS_OUTPUTS[0]][0, 20*52+20, 39] = .9
        heads[BOX_OUTPUTS[0]][0, 20*52+20, :] = 10000
        heads[CLASS_OUTPUTS[0]][0, 20*52+22, 41] = np.nan
        result = decode(heads)
        self.assertEqual(len(result), 1)
        self.assertTrue(np.isfinite(result[0]["box"]).all())

    def test_class_agnostic_nms_rejects_duplicate_box_of_different_class(self):
        heads = self.heads()
        heads[CLASS_OUTPUTS[0]][0, 20*52+20, 39] = .9
        heads[CLASS_OUTPUTS[0]][0, 20*52+21, 41] = .8
        self.assertEqual(len(decode(heads)),1)

    def test_malformed_shape_fails_closed(self):
        heads = self.heads()
        heads[BOX_OUTPUTS[0]] = np.zeros((1,2704,31))
        with self.assertRaises(ValueError):
            decode(heads)

    def test_blank_image_has_finite_input_and_empty_scores_produce_no_detection(self):
        self.assertTrue(np.isfinite(preprocess(Image.new("RGB",(3,5)))).all())
        self.assertEqual(decode(self.heads()),[])


if __name__ == "__main__":
    unittest.main()
