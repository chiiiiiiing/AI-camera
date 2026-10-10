# 摄影模板库工具链历史操作记录

> 本文保留 2026 年 7 月批次记录。当前源码、Android 路径及无私有数据校验命令见 [AI 工具说明](../README.md)。以下「当前」状态、素材数量和指标均属于历史批次，不能视作本次模型重训或泛化改善；示例数据路径需按本地数据位置调整。

> 当前阶段：模板库构建与端侧推荐原型。本文只保留还会继续用到的命令和边界。

## 当前正式状态

- 正式 raw manifest：`../raw_photos/manifest.annotation-clean.jsonl`
- 正式 metadata：`../preprocessed/template_metadata.visual-clean.jsonl`
- 正式索引：`../preprocessed/template_index.visual-clean/`
- 正式模板数：104 条非人像模板。
- embedding：全部 ready。
- 不使用项目根目录 `results/`。

## 环境

```bash
cd ai/tools/build_template_db
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python -m pip install -r requirements-screening.txt
```

Android ONNX 导出和静态 int8 量化依赖 `onnx` / `onnxruntime`，已写入 `requirements.txt`。如果某个环境缺依赖，`export_student_model.py` 会把 ONNX 状态写成 `skipped`，不会伪造产物。

MediaPipe 姿态模型当前在：

```text
models/pose_landmarker_lite.task
```

MobileCLIP2-S0 原型 checkpoint 当前在：

```text
models/mobileclip2_s0/mobileclip2_s0.pt
```

SigLIP2 teacher checkpoint 当前在：

```text
models/siglip2_base_patch16_224/
```

## 来源边界

允许候选来源：

- 自有或明确授权的本地素材。
- Openverse / Wikimedia Commons / Flickr 中可追溯的公开候选。

注意：

- Unsplash / Pexels 普通 API 不用于批量 AI 数据收集，除非取得平台书面授权。
- 小红书、Instagram、TikTok、微博等社交平台素材只能走“已授权本地导入”，不能靠爬虫或登录态抓取。
- 人像必须额外确认肖像权。

## 导入本地新批次

```bash
python import_local_photo_batch.py ../../图片素材/全部照片_已筛选 \
  --output-dir ../raw_photos/new_batch \
  --batch-id new_batch \
  --overwrite
```

输出：

- `../raw_photos/new_batch/manifest.jsonl`
- `../raw_photos/new_batch/import_report.json`
- `../raw_photos/new_batch/images/`

本地导入只生成候选，不会进入正式库。

## 基础校验

```bash
python validate_manifest.py ../raw_photos/new_batch/manifest.jsonl
python audit_dataset.py ../raw_photos/new_batch/manifest.jsonl
```

校验内容包括：

- JSONL schema
- 文件可解码
- SHA-256
- 尺寸
- 重复 hash
- 权利和 review 状态字段

## 人像姿态筛选

先生成只含人像的 manifest，再跑筛选：

```bash
python screen_candidates.py ../raw_photos/new_batch/manifest.portrait-screening.jsonl \
  --model models/pose_landmarker_lite.task \
  --output ../raw_photos/new_batch/screening_report.portrait.jsonl
```

未检测到人体的人像候选应隔离，不进入人工 accepted 候选。

## 人工审核包

```bash
python make_review_pack.py ../raw_photos/new_batch/manifest.cv-preprocess-clean.jsonl \
  --photo-type portrait \
  --quality-status pending \
  --output-dir ../raw_photos/new_batch/review_pack/portrait

python make_review_pack.py ../raw_photos/new_batch/manifest.cv-preprocess-clean.jsonl \
  --photo-type landscape \
  --quality-status pending \
  --output-dir ../raw_photos/new_batch/review_pack/landscape

python make_review_pack.py ../raw_photos/new_batch/manifest.cv-preprocess-clean.jsonl \
  --photo-type still_life \
  --quality-status pending \
  --output-dir ../raw_photos/new_batch/review_pack/still_life
```

每包输出：

- `index.html`
- `contact_sheet.jpg`
- `review_index.jsonl`

## 应用人工审核结果

示例：

```bash
python apply_review_decisions_file.py ../raw_photos/new_batch/manifest.cv-preprocess-clean.jsonl \
  ../raw_photos/new_batch/review_decisions.md \
  --review-pack-root ../raw_photos/new_batch/review_pack \
  --output ../raw_photos/new_batch/manifest.reviewed.jsonl \
  --accepted-output ../raw_photos/new_batch/manifest.accepted.jsonl \
  --crop-output ../raw_photos/new_batch/review_crop_candidates.jsonl \
  --summary-output ../raw_photos/new_batch/review_summary.json
```

最终入库前必须满足：

- `quality_status=accepted`
- `rights_status=accepted`
- 人像还必须 `portrait_release_status=accepted`

本批次已由用户确认完整授权，审核应用工具会把输出 manifest 的 `rights.license` 规范化为 `local_authorized`，原始来源仍保留在 `source` 字段。

多图拼接、封面图、带摄影教学文字但需要抠图的样本先写入 `review_crop_candidates.jsonl`，不直接进入 accepted manifest。

## CV 预处理

审核前可用 `--include-pending` 做机器排查；正式输出只对 accepted 样本跑。

```bash
python preprocess.py ../raw_photos/new_batch/manifest.cv-preprocess-clean.jsonl \
  --include-pending \
  --output-root ../preprocessed/new_batch \
  --jobs ../preprocessed/jobs.new_batch.pending.jsonl

python run_cv_preprocess.py ../preprocessed/jobs.new_batch.pending.jsonl \
  --model models/pose_landmarker_lite.task \
  --report ../preprocessed/cv_report.new_batch.pending.jsonl \
  --allow-partial
```

如果有人像关键点失败：

```bash
python quarantine_cv_failures.py ../raw_photos/new_batch/manifest.cv-screened.jsonl \
  ../preprocessed/cv_report.new_batch.pending.jsonl \
  --output ../raw_photos/new_batch/manifest.cv-preprocess-clean.jsonl
```

## 视觉标注

只有人工审核后的 accepted manifest 才能进入视觉标注。本批次素材授权已由用户于 2026-07-18 确认，不再作为阻塞项。

```bash
python build_annotation_requests.py ../raw_photos/new_batch/manifest.accepted.jsonl \
  --prompt-template config/annotation_prompt.photography_tips.txt \
  --output ../preprocessed/annotation_requests.new_batch.jsonl

python pack_annotation_batches.py ../preprocessed/annotation_requests.new_batch.jsonl \
  --output-dir ../preprocessed/annotation_batches.new_batch \
  --max-items 50 \
  --prefix annotation_batch_new_batch
```

视觉模型结果回来后：

```bash
python check_annotation_results.py ../preprocessed/annotation_batches.new_batch --require-complete
python merge_annotation_results.py ../preprocessed/annotation_requests.new_batch.jsonl \
  ../preprocessed/annotation_batches.new_batch/results/*.jsonl \
  --output ../preprocessed/annotations.new_batch.jsonl
python validate_annotations.py ../preprocessed/annotations.new_batch.jsonl
```

不要编造视觉标注结果。

视觉标注里 `template_value=low/reject` 或 `aesthetic_score<3` 的样本不能进入模板 metadata：

```bash
python quarantine_annotation_results.py ../raw_photos/new_batch/manifest.accepted.jsonl \
  ../preprocessed/annotations.new_batch.jsonl \
  --output ../raw_photos/new_batch/manifest.template-candidates.jsonl
```

## Metadata、embedding、索引

正式 template candidates + annotation 完成后：

```bash
python build_template_metadata.py ../raw_photos/new_batch/manifest.template-candidates.jsonl \
  ../preprocessed/jobs.new_batch.accepted.jsonl \
  ../preprocessed/annotations.new_batch.jsonl \
  --output ../preprocessed/template_metadata.new_batch.jsonl
```

MobileCLIP2-S0 只保留为原型验证，不能作为商业默认路线。若只是复现阶段 2 原型，可执行：

```bash
python generate_mobileclip_embeddings.py ../preprocessed/jobs.new_batch.accepted.jsonl \
  --checkpoint models/mobileclip2_s0/mobileclip2_s0.pt \
  --projection pairwise
```

商业路线已选自有端侧 embedding 蒸馏。新 batch 应先用 teacher / baseline 生成 embedding 并评估质量，再做小模型蒸馏和端侧导出。

```bash
python generate_hf_image_embeddings.py ../preprocessed/template_metadata.new_batch.jsonl \
  --model-id models/siglip2_base_patch16_224 \
  --output-dir ../preprocessed/teacher_embeddings.siglip2_new_batch \
  --metadata-output ../preprocessed/template_metadata.new_batch.siglip2-teacher.jsonl \
  --manifest-output ../preprocessed/teacher_embeddings.siglip2_new_batch.jsonl

python build_template_index.py ../preprocessed/template_metadata.new_batch.siglip2-teacher.jsonl \
  --dimension 768 \
  --output-dir ../preprocessed/template_index.new_batch.siglip2-teacher

python build_usearch_index.py ../preprocessed/template_index.new_batch.siglip2-teacher
python validate_template_index.py ../preprocessed/template_index.new_batch.siglip2-teacher

python evaluate_template_index_quality.py ../preprocessed/template_index.new_batch.siglip2-teacher \
  --top-k 5 \
  --sample-size 24 \
  --output-json ../preprocessed/template_index.new_batch.siglip2-teacher/templates.quality_report.json \
  --output-md ../preprocessed/template_index.new_batch.siglip2-teacher/templates.quality_report.md

python build_distillation_dataset.py ../preprocessed/template_metadata.new_batch.siglip2-teacher.jsonl \
  --output-dir ../preprocessed/distillation.siglip2_new_batch

python train_student_embedding.py ../preprocessed/distillation.siglip2_new_batch \
  --output-dir ../preprocessed/student_models/tiny_cnn_v9_wide_topk_siglip2_512 \
  --architecture tiny_cnn_v1 \
  --output-dim 512 \
  --image-size 224 \
  --width-mult 1.5 \
  --epochs 56 \
  --batch-size 20 \
  --learning-rate 0.0009 \
  --lr-schedule cosine \
  --min-learning-rate 0.00003 \
  --weight-decay 0.0001 \
  --relation-loss-weight 0.35 \
  --listwise-loss-weight 0.06 \
  --teacher-top-k 12 \
  --listwise-temperature 0.12 \
  --teacher-temperature 0.08 \
  --hard-negative-loss-weight 0.04 \
  --hard-negative-margin 0.04 \
  --hard-negative-positives 4 \
  --hard-negative-count 8 \
  --photo-type-loss-weight 0.08 \
  --scene-loss-weight 0.16 \
  --cache-images \
  --image-cache-dir ../preprocessed/student_image_cache_224

python export_student_model.py ../preprocessed/student_models/tiny_cnn_v9_wide_topk_siglip2_512/checkpoint.best.pt \
  --output-dir ../preprocessed/student_models/tiny_cnn_v9_wide_topk_siglip2_512/export

python quantize_onnx_static.py \
  ../preprocessed/student_models/tiny_cnn_v9_wide_topk_siglip2_512/export/student_model.fp32.onnx \
  --metadata ../preprocessed/template_metadata.new_batch.siglip2-teacher.jsonl \
  --output-model ../preprocessed/student_models/tiny_cnn_v9_wide_topk_siglip2_512/export/student_model.static_int8.onnx \
  --manifest-output ../preprocessed/student_models/tiny_cnn_v9_wide_topk_siglip2_512/export/android_onnx_runtime_manifest.json \
  --calibration-items 128 \
  --batch-size 16

python generate_student_embeddings.py ../preprocessed/template_metadata.new_batch.siglip2-teacher.jsonl \
  --checkpoint ../preprocessed/student_models/tiny_cnn_v9_wide_topk_siglip2_512/checkpoint.best.pt \
  --output-dir ../preprocessed/student_embeddings.tiny_cnn_v9_wide_topk_siglip2_512_new_batch \
  --metadata-output ../preprocessed/template_metadata.new_batch.student-tiny-cnn-v9-wide-topk-512.jsonl \
  --manifest-output ../preprocessed/student_embeddings.tiny_cnn_v9_wide_topk_siglip2_512_new_batch.jsonl \
  --batch-size 20 \
  --image-cache-dir ../preprocessed/student_image_cache_224

python build_template_index.py ../preprocessed/template_metadata.new_batch.student-tiny-cnn-v9-wide-topk-512.jsonl \
  --dimension 512 \
  --output-dir ../preprocessed/template_index.new_batch.student-tiny-cnn-v9-wide-topk-512

python build_usearch_index.py ../preprocessed/template_index.new_batch.student-tiny-cnn-v9-wide-topk-512
python validate_template_index.py ../preprocessed/template_index.new_batch.student-tiny-cnn-v9-wide-topk-512

python evaluate_template_index_quality.py ../preprocessed/template_index.new_batch.student-tiny-cnn-v9-wide-topk-512 \
  --top-k 5 \
  --sample-size 24 \
  --output-json ../preprocessed/template_index.new_batch.student-tiny-cnn-v9-wide-topk-512/templates.quality_report.json \
  --output-md ../preprocessed/template_index.new_batch.student-tiny-cnn-v9-wide-topk-512/templates.quality_report.md

python generate_onnx_embeddings.py ../preprocessed/template_metadata.new_batch.siglip2-teacher.jsonl \
  --onnx-model ../preprocessed/student_models/tiny_cnn_v9_wide_topk_siglip2_512/export/student_model.static_int8.onnx \
  --output-dir ../preprocessed/student_embeddings.tiny_cnn_v9_static_int8_onnx_new_batch \
  --metadata-output ../preprocessed/template_metadata.new_batch.student-tiny-cnn-v9-static-int8-onnx-512.jsonl \
  --manifest-output ../preprocessed/student_embeddings.tiny_cnn_v9_static_int8_onnx_new_batch.jsonl \
  --batch-size 16 \
  --image-cache-dir ../preprocessed/student_image_cache_224

python build_template_index.py ../preprocessed/template_metadata.new_batch.student-tiny-cnn-v9-static-int8-onnx-512.jsonl \
  --dimension 512 \
  --output-dir ../preprocessed/template_index.new_batch.student-tiny-cnn-v9-static-int8-onnx-512

python build_usearch_index.py ../preprocessed/template_index.new_batch.student-tiny-cnn-v9-static-int8-onnx-512
python validate_template_index.py ../preprocessed/template_index.new_batch.student-tiny-cnn-v9-static-int8-onnx-512

python evaluate_template_index_quality.py ../preprocessed/template_index.new_batch.student-tiny-cnn-v9-static-int8-onnx-512 \
  --top-k 5 \
  --sample-size 24 \
  --output-json ../preprocessed/template_index.new_batch.student-tiny-cnn-v9-static-int8-onnx-512/templates.quality_report.json \
  --output-md ../preprocessed/template_index.new_batch.student-tiny-cnn-v9-static-int8-onnx-512/templates.quality_report.md
```

不要重复生成已有 104 条正式 embedding，除非明确重建全库。

当前推荐 student 检索候选是 `tiny_cnn_v9_wide_topk_siglip2_512`：512 维，223,680 参数，FP32 TorchScript 约 1.03MB，dynamic int8 原型约 671KB，FP32 ONNX 897,730 bytes，static int8 ONNX 301,677 bytes。v9 FP32 top5 同场景 96.57%，static int8 ONNX top5 同场景 96.15%，均达到 95%+ 质量门槛并接近 SigLIP2 teacher 的 97.53%。`export_student_model.py` 的 dynamic int8 只量化 Linear 层；Android 首发使用 ONNX Runtime Mobile static int8 包，仍需要真机延迟验证。

Android 端侧预算按平衡档记录在 `export/export_manifest.json`：

- 单帧 embedding 延迟目标：p50 <= 80ms，p90 <= 150ms。
- 量化后模型大小目标：<= 5MB。
- 相机预览中不要每帧跑 embedding，建议 500ms-1000ms 抽帧或画面变化触发。
- 推荐结果使用 top5 + 规则重排，不只依赖 top1。

## 全量验证

```bash
python3 -m unittest discover -s tests
cd ../../..
python3 ai/tools/build_template_db/check_pipeline.py --root . --require-embeddings
python3 ai/tools/validate_android_v9_assets.py --asset-root aicamera-app/app/src/main/assets/ai/v9
```

## 当前边界

- `template_index.visual-clean` 是当前正式索引。
- `new_batch` 已完成人工审核、视觉标注、teacher embedding、teacher index、蒸馏数据集、student v5/v6/v8/v9 训练和 student index；尚未合并为正式索引。
- MobileCLIP2-S0 只用于阶段 2 原型；商业路线已选自有端侧 embedding 蒸馏。
- `engine/src/utils/usearch.hpp` 不是有效 USearch C++ 依赖，生产 ANN 接入前必须替换。
