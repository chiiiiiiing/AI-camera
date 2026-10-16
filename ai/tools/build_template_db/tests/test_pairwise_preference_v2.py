import base64
from pathlib import Path
import sys
import unittest

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_pairwise_preference_v2 import (partition, scene_crops, protected_crop, stable_label,
    pair_features, reverse_pairs, predict, rule_score, metrics, acceptable)


class PairwisePreferenceV2Test(unittest.TestCase):
    def test_prior_validation_and_acceptance_block_entire_source_cluster(self):
        common=dict(photo_type="landscape",scene_category="natural_landscape",reference_eligible=True,
            synthetic=False,original_split="train",historical_holdout_group=False)
        rows=[dict(common,template_id=str(i),source_group_id=g,image_sha256=h) for i,(g,h) in enumerate(
            [("train","a"),("val","b"),("acceptance","c"),("renamed","old"),("good","d"),("good","e")])]
        selected,blocked=partition(rows,[(dict(group_assignments={"train":"train","val":"val","acceptance":"acceptance"}),
            [dict(source_sha256="old",split="acceptance")])])
        self.assertEqual({r["source_group_id"] for r in selected},{"train","good"})
        self.assertEqual(len(selected),2)
        self.assertIn("renamed",blocked)

    def test_ambiguous_teacher_is_retained_as_uncertain_not_forced_keep(self):
        self.assertEqual(stable_label([.3,-.2]),0)
        self.assertEqual(stable_label([.03,.04]),0)
        self.assertEqual(stable_label([-.2,-.3]),-1)
        self.assertEqual(stable_label([.2,.3]),1)

    def test_paired_model_is_antisymmetric_and_zero_for_identical_views(self):
        rng=np.random.default_rng(1)
        raw=rng.normal(0,.02,605*64+129).astype('<f4')
        head=dict(weights=base64.b64encode(raw.tobytes()).decode(),scale=[1.]*605)
        a=rng.normal(size=(3,605)).astype(np.float32);b=rng.normal(size=(3,605)).astype(np.float32)
        a[:,-4:]=b[:,-4:]=[0,1,0,0]
        x=pair_features(a,b)
        np.testing.assert_allclose(predict(head,x),-predict(head,reverse_pairs(x)),atol=1e-7)
        np.testing.assert_allclose(predict(head,pair_features(a,a)),0,atol=1e-7)

    def test_portrait_and_missing_object_cannot_authorize_crop(self):
        image=Image.new('RGB',(101,79))
        self.assertEqual(scene_crops(image,None,'portrait'),[])
        self.assertEqual(scene_crops(image,None,'food'),[])
        self.assertEqual(len(scene_crops(image,None,'landscape')),2)
        self.assertIsNone(protected_crop(image,[.03,.03,.97,.97],[.02,.2,.8,.8]))

    def test_rasterized_subject_protection_is_checked_after_rounding(self):
        image=Image.new('RGB',(101,79))
        result=protected_crop(image,[.03,.03,.97,.97],[.3,.3,.7,.7])
        self.assertIsNotNone(result)
        self.assertEqual(result[0].size,(95,75))
        self.assertGreater(result[1][0],.025)

    def test_landscape_detail_is_not_a_negative_rule_label(self):
        a=np.ones(93,dtype=np.float32);b=np.zeros(93,dtype=np.float32)
        self.assertEqual(rule_score(a,'landscape'),rule_score(b,'landscape'))

    def test_abstention_counts_all_crop_attempts_including_teacher_uncertainty(self):
        pairs=[dict(group='g1',kind='crop',sign=1,rule_delta=0),dict(group='g2',kind='crop',sign=0,rule_delta=0),
            dict(group='g3',kind='crop',sign=-1,rule_delta=0)]
        m=metrics([.4,.5,-.2],pairs,.3)
        self.assertAlmostEqual(m['coverage'],2/3)
        self.assertEqual(m['gain_precision'],.5)
        self.assertEqual(m['uncertain_gain_rate'],1)
        self.assertEqual(metrics([.4,.5,-.2],pairs,None)['coverage'],0)
        self.assertFalse(acceptable(m))

    def test_clusters_not_pair_count_determine_support(self):
        pairs=[dict(group='same',kind='crop',sign=-1,rule_delta=0)]*50
        m=metrics([-1.]*50,pairs,.1)
        self.assertEqual(m['clusters'],1)
        self.assertEqual(m['keep_current'],1)
        self.assertFalse(acceptable(m))


if __name__=='__main__':unittest.main()
