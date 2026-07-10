# 已知问题与批次状态

## pilot-v1：已隔离，不得进入模板库

2026-07-08 用户人工检查发现首批 Openverse 候选存在系统性问题：

- 搜索标签被误当成视觉识别结果，人像目录混入火车、动物、花和街景。
- 36 张现存图片中只有 10 张接近手机 4:3 / 3:4 比例。
- `category=photograph` 仍可能返回画作或画作翻拍。
- 人像地域代表性不足，没有满足中国用户场景覆盖要求。

因此原批次只保留作失败案例，不得标记为 `accepted`。`preprocess.py` 默认只为人工接受的记录生成任务，当前正式任务数为 0。

## pilot-v2 准入硬门槛

1. 图片必须是横向 4:3 或纵向 3:4，容差 ±0.04。
2. 标题或标签命中 painting、illustration、drawing、artwork 等词时拒绝。
3. 人像必须由真实姿态模型检测到人体；图库关键词不能作为判断依据。
4. 风景/静物检测到显著人体时拒绝或转人工复核。
5. 中国人像通过中国场景/中文及 East Asian 元数据召回后人工确认，不使用肤色或种族自动分类。
6. 社交媒体图片只有在作者书面授权后才可导入，不直接抓取。

## pilot-v2 当前状态：筛选链路已验证，批次仍不得入库

2026-07-09 使用 MediaPipe Pose Landmarker 完成真实筛选验证。

- `manifest.jsonl` 结构校验通过，共 223 条记录。
- 数据审计发现 35 条文件 SHA-256 与 manifest 不一致，其中 29 条仍处于 `accepted` 状态。
- 新增哈希硬门槛后，175 条非 rejected 候选中只有 146 条通过：人像 9、风景 106、静物 31。
- 29 条未通过记录均为 `sha256_mismatch`；在修复或重采前不得进入 `preprocess.py` 正式任务。
- 当前批次只可作为链路验证和失败案例，不可作为模板库。

## pilot-v2 clean 派生清单

2026-07-09 已建立两级隔离流程：

1. `manifest.clean.jsonl`：隔离 29 条 accepted 但 SHA-256 不一致的记录，剩余 146 条 accepted。
2. `manifest.cv-clean.jsonl`：进一步隔离 3 条 torso anchors 置信度不足、无法稳定归一化的人像记录，剩余 143 条 accepted。

最终 clean pilot 验证结果：

- `validate_manifest.py` 通过。
- `audit_dataset.py` 通过。
- `screen_candidates.py`：143/143 通过，人像 6/6 检出 pose。
- `run_cv_preprocess.py`：非人像 137、人像 `pose_detected` 6，返回成功。

这 143 条只是技术链路 clean，不等于最终模板。入库前仍需人工美学、来源权利和肖像权复核。

## Openverse 人像批次判定失败

2026-07-09 用户反馈人像目录中大量照片并非合格人像模板，且原始文件名太长，无法高效反馈。复查后确认：

- `manifest.review-clean.jsonl` 通过敏感词过滤后仍剩 4 条 accepted 人像。
- 审核包显示 2 条为明显成人/不适合内容，另 2 条也不是现代手机人像模板。
- 已用短编号审核流程将 `P001`-`P004` 全部拒绝，生成 `manifest.portrait-clean.jsonl`。
- 当前可用 pilot 仅保留 137 条非人像：风景 106、静物 31；人像 accepted 为 0。

结论：Openverse 匿名搜索不再作为人像模板主来源。后续人像应改为自有拍摄、明确授权创作者投稿、或人工预筛的可信来源，再进入 Pose / CV 预处理。

## 非人像 visual-clean 状态

2026-07-09 继续对非人像 pilot 做自动质量清理：

- `visual_quality_report.py` 对 137 条非人像 accepted 样本生成亮度、对比度和边缘细节指标。
- `quarantine_visual_quality.py` 只按极端硬问题自动隔离，当前仅拒绝 1 张 `very_dark` 城市风景。
- 最新清单为 `manifest.visual-clean.jsonl`：accepted 136，风景 105、静物 31、人像 0。
- `jobs.visual-clean.jsonl` 生成 136 条非人像预处理任务。
- `run_cv_preprocess.py` 已支持无人体任务时不加载 MediaPipe；136 条非人像预处理成功。
- `validate_preprocessed.py` 校验 136 个 `preprocessed.json` 全部通过。
- `build_annotation_requests.py` 生成 `annotation_requests.visual-clean.jsonl`，共 136 条，作为下一步视觉 LLM 美学标注输入。
- `pack_annotation_batches.py` 已生成 `annotation_batches.visual-clean`：3 个批次，分别 50、50、36 条。
- `merge_annotation_results.py` 已就绪，可合并批量标注结果并校验缺失、重复、未知 `photo_id`。
- `quarantine_annotation_results.py`、`build_template_metadata.py`、`validate_template_metadata.py` 已就绪；标注结果回来后可自动隔离低价值样本并生成入库前元数据。
- `validate_embeddings.py --allow-missing` 当前通过，确认 136 条 job 的 embedding 仍处于预期缺失状态。
- `dataset_status.py --root ../..` 可汇总当前 136 条 visual-clean 状态。
- `check_pipeline.py --root ../..` 当前为 6 项检查、0 失败、2 pending；pending 是缺 `annotations.visual-clean.jsonl` 与 `template_metadata.visual-clean.jsonl`。

这批 136 条仍未完成最终人工美学/权利复核，也尚未接入视觉 LLM 标注、MobileCLIP embedding 与 USearch。没有 `annotations.visual-clean.jsonl` 前不要生成最终模板库索引。

## 姿态模型选择

原设计的 Ultralytics YOLOv8 Pose 对闭源商业产品存在 AGPL-3.0 / Enterprise 授权要求。MVP 候选筛选优先验证 Apache-2.0 的 MediaPipe Pose Landmarker；最终端侧方案在精度评测后确认。
