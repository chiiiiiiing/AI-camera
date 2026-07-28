# 模板索引质量评估报告

- 生成时间：2026-07-19T08:56:11.878106+00:00
- 索引目录：`../preprocessed/template_index.new_batch.student-tiny-cnn-v9-static-int8-onnx-512`
- 样本数：728，维度：512
- USearch 检查：passed

## 核心指标

| 指标 | top1 | top3 | top5 |
|---|---:|---:|---:|
| 同 photo_type 命中率 | 95.33% | 97.53% | 98.21% |
| 同 scene_category 命中率 | 88.87% | 94.78% | 96.15% |
| 同 scene_category 命中率（排除单例场景） | 88.87% | 94.78% | 96.15% |
| 构图匹配命中率 | 94.64% | 98.21% | 98.90% |
| 构图匹配均分 | 92.00% | 96.39% | 97.62% |
| 光线匹配命中率 | 89.01% | 94.64% | 96.57% |
| 光线匹配均分 | 86.95% | 94.29% | 96.52% |

- top1/top2 相似度差小于 0.01 的查询数：270

## 检索判断

- 当前 embedding/index 可继续用于阶段 2 原型的粗粒度场景召回。
- 发现跨 photo_type top1，需要端侧查询时按当前场景类型做候选过滤。
- 后续更换 embedding 模型或 student 维度时，用同一脚本做 teacher/student A/B 报告。

## 抽样查询

### query key=9 `closeup_portrait`

- 摘要：A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.
- top1: key=11 scene=closeup_portrait sim=0.9106 comp=1.0 light=1.0 summary=A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.
- top2: key=10 scene=closeup_portrait sim=0.8769 comp=1.0 light=1.0 summary=A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.
- top3: key=287 scene=closeup_portrait sim=0.8367 comp=1.0 light=1.0 summary=A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.

### query key=482 `food_still_life`

- 摘要：A food photography shot featuring a plated dish in portrait orientation with balanced exposure.
- top1: key=537 scene=lifestyle_still_life sim=0.7124 comp=0.6666666666666666 light=0.0 summary=A still_life photo in lifestyle_still_life category with portrait framing.
- top2: key=286 scene=closeup_portrait sim=0.5142 comp=0.6666666666666666 light=0.5 summary=A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.
- top3: key=381 scene=natural_landscape sim=0.5082 comp=0.2 light=0.25 summary=A natural landscape scene captured in portrait framing with dark exposure.

### query key=554 `lifestyle_still_life`

- 摘要：A still_life photo in lifestyle_still_life category with portrait framing.
- top1: key=197 scene=lifestyle_still_life sim=0.7969 comp=1.0 light=0.6666666666666666 summary=A still_life photo in lifestyle_still_life category with portrait framing.
- top2: key=553 scene=lifestyle_still_life sim=0.7586 comp=1.0 light=0.6666666666666666 summary=A still_life photo in lifestyle_still_life category with portrait framing.
- top3: key=203 scene=lifestyle_still_life sim=0.7326 comp=1.0 light=0.3333333333333333 summary=A still_life photo in lifestyle_still_life category with portrait framing.

### query key=411 `natural_landscape`

- 摘要：A natural landscape scene captured in portrait framing with balanced exposure.
- top1: key=369 scene=natural_landscape sim=0.8026 comp=1.0 light=1.0 summary=A natural landscape scene captured in portrait framing with dark exposure.
- top2: key=328 scene=closeup_portrait sim=0.7432 comp=0.4 light=0.25 summary=A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.
- top3: key=183 scene=lifestyle_still_life sim=0.6751 comp=0.4 light=0.6666666666666666 summary=A still_life photo in lifestyle_still_life category with portrait framing.

### query key=715 `plant_still_life`

- 摘要：A botanical still-life of plants in portrait framing.
- top1: key=546 scene=lifestyle_still_life sim=0.7174 comp=1.0 light=1.0 summary=A still_life photo in lifestyle_still_life category with portrait framing.
- top2: key=526 scene=lifestyle_still_life sim=0.6884 comp=1.0 light=1.0 summary=A still_life photo in lifestyle_still_life category with portrait framing.
- top3: key=717 scene=plant_still_life sim=0.6677 comp=1.0 light=1.0 summary=A botanical still-life of plants in portrait framing.

### query key=223 `urban_landscape`

- 摘要：An urban landscape or cityscape in portrait framing with balanced exposure.
- top1: key=222 scene=urban_landscape sim=0.9512 comp=1.0 light=1.0 summary=An urban landscape or cityscape in portrait framing with balanced exposure.
- top2: key=395 scene=natural_landscape sim=0.8673 comp=0.6666666666666666 light=0.5 summary=A natural landscape scene captured in portrait framing with balanced exposure.
- top3: key=353 scene=natural_landscape sim=0.8174 comp=0.6666666666666666 light=0.75 summary=A natural landscape scene captured in portrait framing with balanced exposure.

### query key=11 `closeup_portrait`

- 摘要：A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.
- top1: key=10 scene=closeup_portrait sim=0.9727 comp=1.0 light=1.0 summary=A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.
- top2: key=9 scene=closeup_portrait sim=0.9106 comp=1.0 light=1.0 summary=A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.
- top3: key=287 scene=closeup_portrait sim=0.8316 comp=1.0 light=1.0 summary=A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.

### query key=519 `food_still_life`

- 摘要：A food photography shot featuring a plated dish in portrait orientation with bright exposure.
- top1: key=517 scene=food_still_life sim=0.9423 comp=1.0 light=1.0 summary=A food photography shot featuring a plated dish in portrait orientation with bright exposure.
- top2: key=132 scene=food_still_life sim=0.9355 comp=1.0 light=1.0 summary=A food photography shot featuring a plated dish in portrait orientation with bright exposure.
- top3: key=131 scene=food_still_life sim=0.9300 comp=1.0 light=1.0 summary=A food photography shot featuring a plated dish in portrait orientation with bright exposure.

### query key=172 `lifestyle_still_life`

- 摘要：A still_life photo in lifestyle_still_life category with portrait framing.
- top1: key=177 scene=lifestyle_still_life sim=0.8743 comp=1.0 light=1.0 summary=A still_life photo in lifestyle_still_life category with portrait framing.
- top2: key=174 scene=lifestyle_still_life sim=0.8650 comp=1.0 light=1.0 summary=A still_life photo in lifestyle_still_life category with portrait framing.
- top3: key=166 scene=lifestyle_still_life sim=0.8568 comp=1.0 light=1.0 summary=A still_life photo in lifestyle_still_life category with portrait framing.

### query key=687 `natural_landscape`

- 摘要：A natural landscape scene captured in landscape framing with balanced exposure.
- top1: key=659 scene=natural_landscape sim=0.8781 comp=0.8333333333333334 light=1.0 summary=A natural landscape scene captured in portrait framing with dark exposure.
- top2: key=612 scene=natural_landscape sim=0.8546 comp=1.0 light=0.75 summary=A natural landscape scene captured in landscape framing with dark exposure.
- top3: key=669 scene=natural_landscape sim=0.8527 comp=1.0 light=1.0 summary=A natural landscape scene captured in landscape framing with dark exposure.

### query key=717 `plant_still_life`

- 摘要：A botanical still-life of plants in portrait framing.
- top1: key=718 scene=plant_still_life sim=0.7447 comp=1.0 light=0.6666666666666666 summary=A botanical still-life of plants in portrait framing.
- top2: key=147 scene=lifestyle_still_life sim=0.7359 comp=1.0 light=1.0 summary=A still_life photo in lifestyle_still_life category with portrait framing.
- top3: key=546 scene=lifestyle_still_life sim=0.7355 comp=1.0 light=1.0 summary=A still_life photo in lifestyle_still_life category with portrait framing.

### query key=583 `urban_landscape`

- 摘要：An urban landscape or cityscape in portrait framing with balanced exposure.
- top1: key=598 scene=urban_landscape sim=0.8108 comp=0.8571428571428571 light=1.0 summary=An urban landscape or cityscape in landscape framing with balanced exposure.
- top2: key=582 scene=urban_landscape sim=0.7859 comp=0.8571428571428571 light=0.75 summary=An urban landscape or cityscape in landscape framing with balanced exposure.
- top3: key=581 scene=urban_landscape sim=0.7767 comp=0.8571428571428571 light=0.75 summary=An urban landscape or cityscape in landscape framing with balanced exposure.

### query key=278 `closeup_portrait`

- 摘要：A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.
- top1: key=280 scene=closeup_portrait sim=0.8244 comp=1.0 light=1.0 summary=A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.
- top2: key=328 scene=closeup_portrait sim=0.7945 comp=1.0 light=1.0 summary=A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.
- top3: key=656 scene=natural_landscape sim=0.7728 comp=0.2 light=0.25 summary=A natural landscape scene captured in landscape framing with balanced exposure.

### query key=127 `food_still_life`

- 摘要：A food photography shot featuring a plated dish in portrait orientation with balanced exposure.
- top1: key=71 scene=food_still_life sim=0.9663 comp=1.0 light=0.75 summary=A food photography shot featuring a plated dish in portrait orientation with balanced exposure.
- top2: key=479 scene=food_still_life sim=0.9550 comp=1.0 light=1.0 summary=A food photography shot featuring a plated dish in portrait orientation with balanced exposure.
- top3: key=66 scene=food_still_life sim=0.9541 comp=1.0 light=1.0 summary=A food photography shot featuring a plated dish in portrait orientation with balanced exposure.

### query key=529 `lifestyle_still_life`

- 摘要：A still_life photo in lifestyle_still_life category with portrait framing.
- top1: key=310 scene=closeup_portrait sim=0.6622 comp=0.5 light=0.3333333333333333 summary=A close-up portrait in landscape orientation with dark exposure, focusing on the face and expression.
- top2: key=335 scene=closeup_portrait sim=0.6589 comp=0.6666666666666666 light=0.0 summary=A close-up portrait in portrait orientation with dark exposure, focusing on the face and expression.
- top3: key=300 scene=closeup_portrait sim=0.6506 comp=0.6666666666666666 light=0.3333333333333333 summary=A close-up portrait in portrait orientation with dark exposure, focusing on the face and expression.

### query key=403 `natural_landscape`

- 摘要：A natural landscape scene captured in portrait framing with balanced exposure.
- top1: key=430 scene=natural_landscape sim=0.9420 comp=1.0 light=1.0 summary=A natural landscape scene captured in portrait framing with balanced exposure.
- top2: key=263 scene=natural_landscape sim=0.9395 comp=1.0 light=1.0 summary=A natural landscape scene captured in portrait framing with balanced exposure.
- top3: key=431 scene=natural_landscape sim=0.9357 comp=1.0 light=0.75 summary=A natural landscape scene captured in portrait framing with balanced exposure.

### query key=714 `plant_still_life`

- 摘要：A botanical still-life of plants in portrait framing.
- top1: key=177 scene=lifestyle_still_life sim=0.7805 comp=1.0 light=1.0 summary=A still_life photo in lifestyle_still_life category with portrait framing.
- top2: key=174 scene=lifestyle_still_life sim=0.7793 comp=1.0 light=1.0 summary=A still_life photo in lifestyle_still_life category with portrait framing.
- top3: key=163 scene=lifestyle_still_life sim=0.7690 comp=1.0 light=1.0 summary=A still_life photo in lifestyle_still_life category with portrait framing.

### query key=697 `urban_landscape`

- 摘要：An urban landscape or cityscape in portrait framing with dark exposure.
- top1: key=423 scene=natural_landscape sim=0.7494 comp=0.6666666666666666 light=0.5 summary=A natural landscape scene captured in portrait framing with balanced exposure.
- top2: key=355 scene=natural_landscape sim=0.7369 comp=0.6666666666666666 light=0.75 summary=A natural landscape scene captured in portrait framing with dark exposure.
- top3: key=378 scene=natural_landscape sim=0.7328 comp=0.6666666666666666 light=0.75 summary=A natural landscape scene captured in portrait framing with dark exposure.

### query key=332 `closeup_portrait`

- 摘要：A close-up portrait in portrait orientation with dark exposure, focusing on the face and expression.
- top1: key=334 scene=closeup_portrait sim=0.9134 comp=1.0 light=0.75 summary=A close-up portrait in portrait orientation with dark exposure, focusing on the face and expression.
- top2: key=333 scene=closeup_portrait sim=0.9012 comp=1.0 light=0.75 summary=A close-up portrait in portrait orientation with balanced exposure, focusing on the face and expression.
- top3: key=338 scene=closeup_portrait sim=0.8952 comp=0.8333333333333334 light=0.75 summary=A close-up portrait in landscape orientation with balanced exposure, focusing on the face and expression.

### query key=650 `food_still_life`

- 摘要：A food photography shot featuring a plated dish in portrait orientation with balanced exposure.
- top1: key=637 scene=food_still_life sim=0.8739 comp=1.0 light=1.0 summary=A food photography shot featuring a plated dish in portrait orientation with balanced exposure.
- top2: key=643 scene=food_still_life sim=0.8643 comp=1.0 light=1.0 summary=A food photography shot featuring a plated dish in portrait orientation with balanced exposure.
- top3: key=105 scene=food_still_life sim=0.8558 comp=1.0 light=1.0 summary=A food photography shot featuring a plated dish in portrait orientation with balanced exposure.

### query key=169 `lifestyle_still_life`

- 摘要：A still_life photo in lifestyle_still_life category with portrait framing.
- top1: key=165 scene=lifestyle_still_life sim=0.8825 comp=1.0 light=0.6666666666666666 summary=A still_life photo in lifestyle_still_life category with portrait framing.
- top2: key=166 scene=lifestyle_still_life sim=0.8688 comp=1.0 light=0.6666666666666666 summary=A still_life photo in lifestyle_still_life category with portrait framing.
- top3: key=168 scene=lifestyle_still_life sim=0.8613 comp=1.0 light=0.6666666666666666 summary=A still_life photo in lifestyle_still_life category with portrait framing.

### query key=663 `natural_landscape`

- 摘要：A natural landscape scene captured in landscape framing with balanced exposure.
- top1: key=613 scene=natural_landscape sim=0.8085 comp=0.8333333333333334 light=1.0 summary=A natural landscape scene captured in portrait framing with balanced exposure.
- top2: key=587 scene=natural_landscape sim=0.7592 comp=1.0 light=1.0 summary=A natural landscape scene captured in landscape framing with balanced exposure.
- top3: key=586 scene=natural_landscape sim=0.7517 comp=1.0 light=0.75 summary=A natural landscape scene captured in landscape framing with balanced exposure.

### query key=718 `plant_still_life`

- 摘要：A botanical still-life of plants in portrait framing.
- top1: key=717 scene=plant_still_life sim=0.7447 comp=1.0 light=0.6666666666666666 summary=A botanical still-life of plants in portrait framing.
- top2: key=147 scene=lifestyle_still_life sim=0.7378 comp=1.0 light=0.6666666666666666 summary=A still_life photo in lifestyle_still_life category with portrait framing.
- top3: key=208 scene=lifestyle_still_life sim=0.6745 comp=1.0 light=0.6666666666666666 summary=A still_life photo in lifestyle_still_life category with portrait framing.

### query key=218 `urban_landscape`

- 摘要：An urban landscape or cityscape in portrait framing with balanced exposure.
- top1: key=219 scene=urban_landscape sim=0.9107 comp=1.0 light=1.0 summary=An urban landscape or cityscape in portrait framing with dark exposure.
- top2: key=400 scene=natural_landscape sim=0.8292 comp=0.6666666666666666 light=0.75 summary=A natural landscape scene captured in portrait framing with dark exposure.
- top3: key=393 scene=natural_landscape sim=0.7971 comp=0.6666666666666666 light=0.5 summary=A natural landscape scene captured in portrait framing with dark exposure.

## 问题样本

- top1_photo_type_mismatch: query key=617 scene=natural_landscape -> top1 key=304 scene=closeup_portrait sim=0.9112
- top1_photo_type_mismatch: query key=328 scene=closeup_portrait -> top1 key=369 scene=natural_landscape sim=0.9034
- top1_photo_type_mismatch: query key=369 scene=natural_landscape -> top1 key=328 scene=closeup_portrait sim=0.9034
- top1_photo_type_mismatch: query key=286 scene=closeup_portrait -> top1 key=154 scene=lifestyle_still_life sim=0.8689
- top1_photo_type_mismatch: query key=12 scene=closeup_portrait -> top1 key=424 scene=natural_landscape sim=0.8605
- top1_photo_type_mismatch: query key=58 scene=food_still_life -> top1 key=10 scene=closeup_portrait sim=0.8599
- top1_photo_type_mismatch: query key=550 scene=lifestyle_still_life -> top1 key=600 scene=natural_landscape sim=0.8567
- top1_photo_type_mismatch: query key=288 scene=closeup_portrait -> top1 key=202 scene=lifestyle_still_life sim=0.8523
- top1_photo_type_mismatch: query key=300 scene=closeup_portrait -> top1 key=506 scene=food_still_life sim=0.8522
- top1_photo_type_mismatch: query key=538 scene=lifestyle_still_life -> top1 key=601 scene=natural_landscape sim=0.8448
- top1_photo_type_mismatch: query key=668 scene=natural_landscape -> top1 key=709 scene=food_still_life sim=0.8326
- top1_photo_type_mismatch: query key=709 scene=food_still_life -> top1 key=668 scene=natural_landscape sim=0.8326

## 近重复提示

- sim=0.9870: key=622 / key=662
- sim=0.9863: key=29 / key=31
