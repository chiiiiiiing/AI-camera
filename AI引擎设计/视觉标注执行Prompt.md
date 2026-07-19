# 视觉标注执行 Prompt

把下面这段交给视觉模型或下一个执行视觉标注的 AI：

```text
你现在接手 `/Volumes/桌面/AI-camera` 项目的新图片批次视觉标注任务。

项目目标：
- 这是 AI 智能摄影相机项目。
- 当前阶段是“摄影模板库 + 端侧推荐能力”。
- 你的任务不是改代码，而是对已人工审核通过的图片做视觉/摄影模板标注。
- 标注结果会用于后续 template metadata、embedding 评测、自有端侧 embedding 模型蒸馏和 template index 构建。

重要状态：
- 用户已确认本批次所有素材均取得完整授权，不再考虑授权、版权、肖像权阻塞。
- 人工审核已完成。
- accepted manifest：`tools/raw_photos/new_batch/manifest.accepted.jsonl`
- accepted 数量：1156 张。
- 分布：portrait 115、landscape 403、still_life 638。
- CV 预处理已完成：`tools/preprocessed/cv_report.new_batch.accepted.jsonl`
- 人像 115 张全部 `pose_detected`。
- 有 24 张需裁剪候选在 `tools/raw_photos/new_batch/review_crop_candidates.jsonl`，不要标注这些，除非之后另行生成裁剪图。

需要处理的输入：
- 标注请求目录：`tools/preprocessed/annotation_batches.new_batch/`
- batch manifest：`tools/preprocessed/annotation_batches.new_batch/batch_manifest.json`
- 共 24 个 batch：
  - `annotation_batch_new_batch_0001.jsonl`
  - ...
  - `annotation_batch_new_batch_0024.jsonl`
- 总请求数：1156。

每条请求里包含：
- `photo_id`
- `photo_type`
- `scene_category`
- `image_path`
- `prompt`

请逐条读取图片并输出视觉标注结果。

输出位置：
- 把结果写到：`tools/preprocessed/annotation_batches.new_batch/results/`
- 如果目录不存在，先创建。
- 每个 batch 对应一个结果文件，命名必须严格对应：
  - 输入：`annotation_batch_new_batch_0001.jsonl`
  - 输出：`results/annotation_batch_new_batch_0001.results.jsonl`
- 每个结果文件必须是 JSONL。
- 每个输入 item 输出一行 JSON。
- 不要合并成一个大文件。
- 不要输出 Markdown。
- 不要漏项，不要重复 `photo_id`。

每条标注必须是一个 JSON object，结构必须匹配：
- `tools/build_template_db/schema/aesthetic_annotation.schema.json`

必须包含这些字段：
- `schema_version`
- `photo_id`
- `photo_type`
- `scene_category`
- `visual_summary`
- `composition`
- `lighting`
- `camera`
- `subject`
- `aesthetic_score`
- `template_value`
- `shooting_guidance`

字段要求：
- `schema_version` 固定为 `1`。
- `photo_id`、`photo_type`、`scene_category` 必须和输入请求完全一致。
- `visual_summary` 用一句话描述画面，不要超过 240 字符。
- `aesthetic_score` 为 0 到 5 的数字。
- `template_value` 只能是 `reject`、`low`、`medium`、`high`。
- `shooting_guidance` 是 1 到 5 条面向拍照新手的具体建议。
- 不要识别真人身份。
- 不要推断敏感属性。
- 不要输出额外解释。

枚举字段必须严格使用 schema 中允许的值。

composition：
- `type`：简短写构图类型，例如 `rule_of_thirds`、`centered_subject`、`leading_lines`、`symmetry`、`frame_within_frame`、`negative_space`、`diagonal_composition`、`layered_depth`。
- `subject_position`：只能是 `left`、`center`、`right`、`top`、`bottom`、`none`、`unknown`。
- `horizon_position`：只能是 `upper_third`、`middle`、`lower_third`、`not_visible`、`unknown`。
- `leading_lines`：true 或 false。
- `negative_space`：只能是 `low`、`medium`、`high`、`unknown`。

lighting：
- `quality`：只能是 `soft`、`hard`、`mixed`、`unknown`。
- `direction`：只能是 `front`、`side`、`back`、`top`、`ambient`、`unknown`。
- `time_of_day`：只能是 `day`、`golden_hour`、`blue_hour`、`night`、`indoor`、`unknown`。
- `contrast`：只能是 `low`、`medium`、`high`、`unknown`。

camera：
- `angle`：只能是 `eye_level`、`low`、`high`、`top_down`、`unknown`。
- `distance`：只能是 `close`、`medium`、`wide`、`unknown`。
- `orientation`：只能是 `landscape`、`portrait`、`square`、`unknown`。

subject：
- `primary_type`：只能是 `person`、`landscape`、`object`、`food`、`plant`、`architecture`、`unknown`。
- `count`：主体数量，整数；风景无人可填 0。
- `pose_description`：只有人像填写简短姿态描述；非人像必须填 null。

摄影技巧文字的处理：
- 有些图片上可能带有摄影技巧说明文字。
- 这些文字可以作为 `shooting_guidance` 的弱参考。
- 但不要把文字、水印、拼图排版、封面设计当成最终视觉模板内容。
- 如果图片本身仍是可用真实照片，就按照片画面标注。
- 如果画面主要是文字、截图、拼贴或封面图，`template_value` 应为 `reject` 或 `low`。

质量判断：
- 高价值模板：主体明确、构图清楚、光线可判断、能给用户形成可执行拍摄建议。
- 低价值模板：主体弱、构图混乱、画质差、过暗/过曝、教学价值弱。
- 人像模板要特别关注姿态、肩颈、躯干、四肢是否清楚。
- 不要把非人像模板冒充人像模板。

示例输出一行：
{"schema_version":1,"photo_id":"new_batch_00021","photo_type":"portrait","scene_category":"group_portrait","visual_summary":"Two people are framed close together in soft daylight with a clean portrait composition.","composition":{"type":"centered_subject","subject_position":"center","horizon_position":"not_visible","leading_lines":false,"negative_space":"low"},"lighting":{"quality":"soft","direction":"front","time_of_day":"day","contrast":"medium"},"camera":{"angle":"eye_level","distance":"medium","orientation":"portrait"},"subject":{"primary_type":"person","count":2,"pose_description":"Two people stand close together facing the camera."},"aesthetic_score":4.1,"template_value":"high","shooting_guidance":["Keep both faces near the center and leave a little headroom.","Use soft front light to keep skin tones even.","Hold the camera at eye level for a natural portrait."]}

完成后请运行或提示项目执行者运行：
```bash
cd /Volumes/桌面/AI-camera/tools/build_template_db
python3 check_annotation_results.py ../preprocessed/annotation_batches.new_batch --require-complete
python3 merge_annotation_results.py ../preprocessed/annotation_requests.new_batch.jsonl \
  ../preprocessed/annotation_batches.new_batch/results/*.jsonl \
  --output ../preprocessed/annotations.new_batch.jsonl
python3 validate_annotations.py ../preprocessed/annotations.new_batch.jsonl
```

如果没有能力直接写文件，就按上述命名返回每个 batch 的 JSONL 内容，不要省略任何 photo_id。
```
