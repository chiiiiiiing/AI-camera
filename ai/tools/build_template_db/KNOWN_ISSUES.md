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
- `merge_annotation_results.py` 已合并正式批量标注结果为 `annotations.visual-clean.jsonl`，共 136 条。
- `quarantine_annotation_results.py` 已隔离 5 条 low 样本，生成初版 `manifest.annotation-clean.jsonl`：accepted 131，rejected 92。
- 用户人工复核拒绝清单已应用，当前 `manifest.annotation-clean.jsonl`：accepted 104，rejected 119。
- `build_template_metadata.py` 与 `validate_template_metadata.py` 已生成并校验 `template_metadata.visual-clean.jsonl`，当前共 104 条，embedding status 全部为 `ready`。
- `generate_mobileclip_embeddings.py` 已使用 MobileCLIP2-S0 checkpoint 生成 136 个真实 `embedding.f32`；MobileCLIP2-S0 原生输出 512 维，本阶段用显式 `--projection pairwise` 投影为 256 维并 L2 normalize。
- `validate_embeddings.py` 当前通过，确认 136 条 job 的 embedding 全部存在且有效。
- `dataset_status.py --root ../..` 可汇总当前 136 条 annotation 与 104 条 template metadata 状态。
- `check_pipeline.py --root ../.. --require-embeddings` 当前为 7 项检查、0 failures、0 pending。
- `build_template_index.py`、`validate_template_index.py` 与 `build_usearch_index.py` 已生成并校验 `tools/preprocessed/template_index.visual-clean/` 下的 portable index 与 USearch 原型索引。

这批非人像样本已完成视觉 LLM 标注、人工复核、MobileCLIP2-S0 原型 embedding、模板元数据 ready、portable index 和 USearch 原型索引。后续风险不再是“缺 embedding”，而是需要评估 pairwise 512→256 投影的检索质量，并确认商业版本模型授权或替代模型路线。

2026-07-14 索引质量评估补充：

- 新增 `evaluate_template_index_quality.py`，已对 `template_index.visual-clean` 生成 `templates.quality_report.json` 与 `templates.quality_report.md`。
- USearch `.idx` 抽样返回与 portable 精确检索一致，`usearch_check.status=passed`，检查 12 条，size 104。
- 当前 pairwise 256 维投影可用于阶段 2 粗粒度 top5 召回，但不能直接把 ANN top1 当最终模板。
- 同场景命中率：top1 约 56.73%，top3 约 76.92%，top5 约 87.50%；排除单例场景后 top5 约 88.35%。
- 构图匹配：top1 命中约 36.54%，top5 约 81.73%；光线匹配 top1 约 16.35%，top5 约 57.69%。
- 发现跨 `photo_type` top1 问题，主要集中在静物、植物、创意构图被自然风景或城市场景吸走。
- 端侧接入必须 top5/top8 召回后按 `photo_type`、高置信 `scene_category`、构图字段和光线字段做过滤/重排；`top1/top2` margin 小于 0.01 时不要频繁切模板。
- 未发现相似度 >= 0.985 的近重复候选。
- 这不是“缺模型、缺 embedding、缺索引”的问题；索引产物已完成，当前风险是检索质量和商业授权边界。

2026-07-12 复验状态：

- SMB 挂载与本地数据目录可访问。
- `check_annotation_results.py` 已验证正式结果目录 3 个批次全部通过，received 136/136。
- `python3 -m unittest discover -s tests -v`：93 个测试通过，1 个因普通 Python 未安装 `usearch` skip。
- `python3 dataset_status.py --root ../..`：annotations 136，template_metadata 104，embedding present 136 / missing 0，template_metadata embedding ready 104。
- `python3 check_pipeline.py --root ../.. --require-embeddings`：7 项检查、0 failures、0 pending。
- `python3 validate_template_index.py ../../tools/preprocessed/template_index.visual-clean`：通过，count 104、dimension 256。
- `/private/tmp/mobileclip-hf-venv/bin/python build_usearch_index.py ../../tools/preprocessed/template_index.visual-clean`：生成 `templates.usearch.idx` 与 `templates.usearch.json`。
- USearch 持久化加载复验：`.idx` 可 load，size 104，首条向量 top-1 key=1、distance=0.0。
- Embedding 原型方案已定：MobileCLIP2-S0，接口保持 256 维、L2 normalize、cosine similarity、USearch；商业版本不直接使用 Apple 官方预训练权重。
- 2026-07-12 已通过 `hf-mirror.com` 下载 `mobileclip2_s0.pt` 到 `tools/build_template_db/models/mobileclip2_s0/`，大小 299,864,852 bytes，SHA256 `bc3bc861baa680df2f9e3dc1aa0acbe5a21af21032df385026dda70c33505aa6`。
- 仍需要用户或外部资源完成的任务是确认 MobileCLIP2-S0 许可边界/后续商业替代方案和后续人像素材。
- 需要用户或外部资源完成的任务见 `AI引擎设计/需要用户完成的任务.md`。

## 姿态模型选择

原设计的 Ultralytics YOLOv8 Pose 对闭源商业产品存在 AGPL-3.0 / Enterprise 授权要求。MVP 候选筛选优先验证 Apache-2.0 的 MediaPipe Pose Landmarker；最终端侧方案在精度评测后确认。
