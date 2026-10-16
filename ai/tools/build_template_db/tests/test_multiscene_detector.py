import unittest

import numpy as np
from PIL import Image

from multiscene_detector import (BOX_OUTPUTS, CLASS_OUTPUTS, STRIDES, decode, preprocess,
                                preprocess_native, native_transform, native_box_to_viewport,
                                native_analysis_image, PREPROCESSING, NATIVE_PREPROCESSING)


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

    def test_native_protocol_distinct_and_preserves_fine_detail(self):
        pixels = np.zeros((416,416,3),dtype=np.uint8)
        pixels[:,1::2] = 255
        image = Image.fromarray(pixels)
        tensor, transform = preprocess_native(image)
        legacy = preprocess(image)
        self.assertNotEqual(PREPROCESSING,NATIVE_PREPROCESSING)
        self.assertGreater(abs(tensor[0,0,0,0]-legacy[0,0,0,0]),.5)
        self.assertAlmostEqual(float(tensor[0,0,0,1]-tensor[0,0,0,0]),255/57.375,places=5)
        self.assertEqual(transform["pad_left"],0)

    def test_native_aspect_mapping_for_rotated_mirrored_viewports(self):
        for width,height in ((1920,1080),(1080,1920),(4032,3024),(301,100),(100,301)):
            transform = native_transform(width,height)
            wanted = np.array([.12,.18,.76,.86])
            for mirrored in (False,True):
                expected = wanted.copy()
                if mirrored:
                    expected[[0,2]] = 1-expected[[2,0]]
                box = (expected*np.array([transform["resized_width"],transform["resized_height"]]*2)+np.array([transform["pad_left"],transform["pad_top"]]*2))/416
                np.testing.assert_allclose(native_box_to_viewport(box,transform),expected,atol=1e-6)

    def test_native_padding_does_not_become_subject(self):
        transform = native_transform(1600,900)
        self.assertIsNone(native_box_to_viewport([.2,0,.4,.1],transform))
        self.assertIsNone(native_box_to_viewport([.2,float("nan"),.4,.1],transform))
        self.assertEqual(native_box_to_viewport([.2,.2,.4,.6],transform)[1],0)

    def test_native_gray_padding_and_python_kotlin_pixel_fixture(self):
        pixels = np.array([[(i*31+3)%256,(i*17+5)%256,(i*11+7)%256] for i in range(21)],dtype=np.uint8).reshape(3,7,3)
        image = Image.fromarray(pixels)
        analysis,transform = native_analysis_image(image)
        self.assertEqual(analysis.size,(416,178))
        self.assertEqual(transform["pad_top"],119)
        self.assertEqual(analysis.getpixel((0,0)),(3,5,7))
        self.assertEqual(analysis.getpixel((415,177)),(111,89,227))
        tensor,_ = preprocess_native(image)
        np.testing.assert_allclose(tensor[0,:,0,0],(114-np.array([103.53,116.28,123.675]))/np.array([57.375,57.12,58.395]),atol=1e-6)


if __name__ == "__main__":
    unittest.main()
