# 下一对话接手 Prompt

请继续 `/Volumes/桌面/AI-camera` 项目的阶段 2：模板库构建。先确认 SMB 挂载存在；如果路径不存在，提醒用户重新挂载 Windows 共享盘。

## 当前状态

- 最新有效 raw manifest：`tools/raw_photos/manifest.visual-clean.jsonl`
- accepted 136 条：风景 105、静物 31、人像 0
- 人像 Openverse 批次判定失败，暂停 Openverse 人像扩采；后续人像改用自有拍摄、创作者明确授权或人工预筛来源
- 最新 jobs：`tools/preprocessed/jobs.visual-clean.jsonl`
- 最新预处理报告：`tools/preprocessed/cv_report.visual-clean.jsonl`
- 视觉标注请求：`tools/preprocessed/annotation_requests.visual-clean.jsonl`
- 视觉标注批次：`tools/preprocessed/annotation_batches.visual-clean/`
  - `annotation_batch_0001.jsonl`：50 条
  - `annotation_batch_0002.jsonl`：50 条
  - `annotation_batch_0003.jsonl`：36 条
- 标注批次工具：
  - `tools/build_template_db/pack_annotation_batches.py`
  - `tools/build_template_db/merge_annotation_results.py`
- 标注结果后处理工具：`tools/build_template_db/quarantine_annotation_results.py`
- 模板元数据构建与校验工具：
  - `tools/build_template_db/build_template_metadata.py`
  - `tools/build_template_db/validate_template_metadata.py`
  - `tools/build_template_db/schema/template_metadata.schema.json`
- embedding 校验工具：`tools/build_template_db/validate_embeddings.py`
- 一键状态检查：`tools/build_template_db/check_pipeline.py`
- 当前还没有 `tools/preprocessed/annotations.visual-clean.jsonl`，也没有 MobileCLIP embedding
- 审核包：
  - `tools/raw_photos/review_pack/landscape_visual_clean/index.html`
  - `tools/raw_photos/review_pack/still_life_visual_clean/index.html`

## 已完成验证

- `python -m unittest discover -s tests -v`：76/76 通过
- `validate_manifest.py ../../tools/raw_photos/manifest.visual-clean.jsonl` 通过
- `audit_dataset.py ../../tools/raw_photos/manifest.visual-clean.jsonl` 通过
- `run_cv_preprocess.py ../../tools/preprocessed/jobs.visual-clean.jsonl --report ../../tools/preprocessed/cv_report.visual-clean.jsonl` 通过，且无须 MediaPipe
- `validate_preprocessed.py ../../tools/preprocessed/jobs.visual-clean.jsonl` 通过
- `validate_embeddings.py ../../tools/preprocessed/jobs.visual-clean.jsonl --allow-missing` 通过：136 条 job 暂无 embedding，符合当前阶段预期
- `dataset_status.py --root ../..` 输出：manifest accepted 136，jobs 136，CV report 136，annotation requests 136，annotation batches 3
- `check_pipeline.py --root ../..` 输出：6 项检查、0 失败、2 pending；pending 是缺 `annotations.visual-clean.jsonl` 和 `template_metadata.visual-clean.jsonl`
- `build_annotation_requests.py ../../tools/raw_photos/manifest.visual-clean.jsonl --output ../../tools/preprocessed/annotation_requests.visual-clean.jsonl` 生成 136 条请求
- `pack_annotation_batches.py ../../tools/preprocessed/annotation_requests.visual-clean.jsonl --output-dir ../../tools/preprocessed/annotation_batches.visual-clean --max-items 50` 生成 3 个批次

## 接下来优先做

1. 若有视觉 LLM/API 资源，直接提交 `annotation_batches.visual-clean` 下 3 个 JSONL 批次做标注。
2. 批量结果返回后，用 `merge_annotation_results.py` 合并为 `annotations.visual-clean.jsonl`；该工具可处理直接 JSON，也可从 `response_text`、`content`、`text`、`annotation` 包装字段中抽取 JSON。
3. 标注输出必须通过 `validate_annotations.py` 和 `schema/aesthetic_annotation.schema.json`。
4. 标注完成后运行 `quarantine_annotation_results.py`，隔离 `reject`、`low` 或低分样本，生成 `manifest.annotation-clean.jsonl`。
5. 使用 `build_template_metadata.py` 生成 `template_metadata.visual-clean.jsonl`，再用 `validate_template_metadata.py` 校验；只有 `medium/high` 且 3 分以上样本允许进入后续索引。
6. 不要继续 Openverse 人像扩采。
7. 不要把 `tools/raw_photos/`、`tools/preprocessed/`、`tools/.venv/`、`tools/build_template_db/models/` 传入 Git。
8. 完成工作后更新：
   - `AI引擎设计/工作进度.md`
   - `AI引擎设计/新AI接手提示词.md`
   - `tools/build_template_db/README.md`
   - `tools/build_template_db/KNOWN_ISSUES.md`

## Git 提醒

项目在 SMB 挂载盘上，Mac 端不要执行 Git 提交。需要在 Windows 存储电脑本机执行：

```bash
git add .gitignore AI引擎设计 tools/build_template_db
git commit -m "feat: 完成非人像模板库 visual-clean 与入库前校验"
git push
```
