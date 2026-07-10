# 阶段 2：摄影模板库工具链

> 首批 `pilot-v1` 候选已因分类、比例和媒介类型问题被隔离，详见 `KNOWN_ISSUES.md`。不得把现有 pending 记录视为有效模板。

当前完成的是第一段流水线：从允许的来源收集候选照片，并为每张照片保存可追溯的来源、授权、哈希和人工复核状态。

## 为什么不直接下载 Unsplash / Pexels

项目旧文档中的图库方案已经不符合平台现行条款：

- Unsplash API 条款把 AI/ML 用途导向单独的数据授权渠道。
- Pexels 条款禁止未经授权的 ML 数据提取和大规模复制。

因此 `source_policy.json` 默认阻止这两个来源。拿到平台书面许可后才能增加对应适配器。

第一批候选使用 Openverse 搜索的 `CC0` / `PDM` 照片。它们仍需要人工核验来源页面；人像还要检查肖像权，不会因为版权开放就自动视为可商用人像素材。

## 本地准备

```bash
cd tools/build_template_db
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
```

真实人体筛选另装：

```bash
python -m pip install -r requirements-screening.txt
```

Openverse 匿名访问足够跑小样本。若申请了 Openverse API Token，可放进环境变量：

```bash
export OPENVERSE_API_TOKEN="你的 token"
```

不要把 Token 写入仓库。

## 运行

先做离线配置检查：

```bash
python collect_openverse.py --dry-run
```

默认每个关键词最多接收 3 张，用于第一轮小样本：

```bash
python collect_openverse.py
```

只跑一个场景：

```bash
python collect_openverse.py \
  --category natural_landscape \
  --limit-per-query 5
```

下载结果默认写入 `tools/raw_photos/`，清单为 `tools/raw_photos/manifest.jsonl`。脚本具备：

- SHA-256 内容去重
- 按 Openverse ID 断点续跑
- 候选最低分辨率过滤（长边 960、短边 640）
- 优选目标仍为长边 1920、短边 1080，原始尺寸会保留供筛选
- 单文件 40 MB 上限
- 严格限制 CC0/PDM
- 下载失败独立记录
- 人像肖像权待复核标记

校验清单：

```bash
python validate_manifest.py ../raw_photos/manifest.jsonl
```

人工接受或淘汰候选：

```bash
python review_candidates.py ../raw_photos/manifest.jsonl PHOTO_ID accepted
python review_candidates.py ../raw_photos/manifest.jsonl PHOTO_ID rejected \
  --reason composition_not_selected --delete-file
```

拒绝记录不会从清单消失，因此采集器不会在断点续跑时把同一张图重新下载。

长文件名不适合人工沟通。先生成短编号审核包，再用 `P001`、`L012`、`S008` 这类编号批量处理：

```bash
python make_review_pack.py ../raw_photos/manifest.review-clean.jsonl \
  --photo-type portrait \
  --output-dir ../raw_photos/review_pack/portrait

python apply_review_ids.py ../raw_photos/manifest.review-clean.jsonl \
  ../raw_photos/review_pack/portrait/review_index.jsonl \
  --reject P001 P002 \
  --reason manual_portrait_quality_reject \
  --output ../raw_photos/manifest.portrait-clean.jsonl
```

审核包会生成：

- `index.html`：可点开原图的缩略图页面
- `contact_sheet.jpg`：一张可截图/圈编号的总览图
- `review_index.jsonl`：短编号到 `photo_id` / 文件路径的映射

检查文件、哈希、重复项、尺寸、许可证和审核状态：

```bash
python audit_dataset.py ../raw_photos/manifest.jsonl
```

如果本地文件与 manifest 的 SHA-256 不一致，先生成隔离后的清单，不要直接进入预处理：

```bash
python quarantine_integrity_failures.py ../raw_photos/manifest.jsonl \
  --output ../raw_photos/manifest.clean.jsonl
```

最终入库前使用 `--require-final`，届时所有照片必须通过质量和权利审核，人像还必须通过肖像权审核。

为 CV 模型生成预处理任务：

```bash
python preprocess.py ../raw_photos/manifest.clean.jsonl \
  --jobs ../preprocessed/jobs.clean.jsonl
```

当前 `preprocess.py` 已固定主人物选择、COCO 17 点 Root-centered Scaling、关节角和 256 维向量校验接口。YOLOv8-Pose、MediaPipe 与 MobileCLIP 适配器将在下一步接入，任务状态在此之前明确为 `pending_cv_models`。

人像候选必须先经过真实姿态检测，不能依赖图库搜索标签：

```bash
python screen_candidates.py ../raw_photos/manifest.jsonl \
  --model /path/to/pose_landmarker_lite.task
```

筛选和预处理都会校验文件 SHA-256。只要图片文件与 manifest 记录不一致，就会以 `sha256_mismatch` 拒绝，不能进入正式 CV 任务。

默认正式预处理只接收人工标记为 `accepted` 的记录。仅在筛选阶段需要时使用 `preprocess.py --include-pending`。

运行真实 CV 预处理后，若有人像因关键点质量不足失败，先隔离失败记录，再生成最终 jobs：

```bash
python run_cv_preprocess.py ../preprocessed/jobs.clean.jsonl \
  --model models/pose_landmarker_lite.task \
  --report ../preprocessed/cv_report.clean.jsonl \
  --allow-partial

python quarantine_cv_failures.py ../raw_photos/manifest.clean.jsonl \
  ../preprocessed/cv_report.clean.jsonl \
  --output ../raw_photos/manifest.cv-clean.jsonl

python quarantine_metadata_terms.py ../raw_photos/manifest.cv-clean.jsonl \
  --config config/search_queries.json \
  --output ../raw_photos/manifest.review-clean.jsonl

python visual_quality_report.py ../raw_photos/manifest.portrait-clean.jsonl \
  --output ../raw_photos/visual_quality.portrait-clean.jsonl

python quarantine_visual_quality.py ../raw_photos/manifest.portrait-clean.jsonl \
  ../raw_photos/visual_quality.portrait-clean.jsonl \
  --output ../raw_photos/manifest.visual-clean.jsonl

python preprocess.py ../raw_photos/manifest.visual-clean.jsonl \
  --jobs ../preprocessed/jobs.visual-clean.jsonl

python run_cv_preprocess.py ../preprocessed/jobs.visual-clean.jsonl \
  --report ../preprocessed/cv_report.visual-clean.jsonl

python validate_preprocessed.py ../preprocessed/jobs.visual-clean.jsonl
```

当前非人像 clean pilot 可以直接生成 LLM 视觉标注请求：

```bash
python build_annotation_requests.py ../raw_photos/manifest.visual-clean.jsonl \
  --output ../preprocessed/annotation_requests.visual-clean.jsonl

python pack_annotation_batches.py ../preprocessed/annotation_requests.visual-clean.jsonl \
  --output-dir ../preprocessed/annotation_batches.visual-clean \
  --max-items 50

python merge_annotation_results.py ../preprocessed/annotation_requests.visual-clean.jsonl \
  ../preprocessed/annotation_batches.visual-clean/results/*.jsonl \
  --output ../preprocessed/annotations.visual-clean.jsonl

python validate_annotations.py ../preprocessed/annotations.visual-clean.jsonl

python quarantine_annotation_results.py ../raw_photos/manifest.visual-clean.jsonl \
  ../preprocessed/annotations.visual-clean.jsonl \
  --output ../raw_photos/manifest.annotation-clean.jsonl

python build_template_metadata.py ../raw_photos/manifest.annotation-clean.jsonl \
  ../preprocessed/jobs.visual-clean.jsonl \
  ../preprocessed/annotations.visual-clean.jsonl \
  --output ../preprocessed/template_metadata.visual-clean.jsonl

python validate_template_metadata.py ../preprocessed/template_metadata.visual-clean.jsonl

python validate_embeddings.py ../preprocessed/jobs.visual-clean.jsonl --allow-missing

python dataset_status.py --root ../..

python check_pipeline.py --root ../..
```

`validate_template_metadata.py` 是入库前闸门：`reject`、`low` 或低于 3 分的标注结果不得进入模板元数据。当前 MobileCLIP 尚未接入时，embedding 可保持 `pending`；接入后再去掉 `validate_embeddings.py --allow-missing`。

`pack_annotation_batches.py` 已将当前 136 条请求切成 3 个批次（50、50、36）。`merge_annotation_results.py` 可以合并直接 JSON 标注结果，也能从常见的 `response_text`、`content`、`text` 或 `annotation` 包装字段里抽取 JSON。

运行单元测试：

```bash
PYTHONPATH=. python -m unittest discover -s tests -v
```

## 小样本验收

第一批先控制在 30–100 张，不直接追求 5000 张。逐张检查：

1. 照片是否具有可学习的构图价值。
2. `photo_type` 和 `scene_category` 是否正确。
3. 来源页、作者和许可证是否匹配。
4. 人像是否存在肖像权或敏感内容风险。
5. 画质是否足够，是否有水印、拼贴或明显 AI 生成痕迹。

小样本通过后，才进入 YOLOv8-Pose、MediaPipe 和 MobileCLIP 预处理。
