# 摄影参考库、小模型训练与评估

本目录负责素材导入、参考图整理、teacher embedding、小模型蒸馏、ONNX 导出、INT8 量化与检索评估。Android 实际加载 `aicamera-app/app/src/main/assets/ai/v9/`。历史 `visual-clean` 的 104 条索引不代表当前 App 的全部资产。

当前推荐模型为 `mobilenet_v3_small_retrieval`：官方预训练特征网络加 train-only 岭回归检索头，FP32 ONNX 4,900,248 bytes。49 张 val 的 Top5 同场景命中从 44 张提高至 47 张；冻结后的 53 张 test 从 45 张提高至 47 张。两次量化兼容实验均被拒绝，未采用失败的 INT8 权重。生活静物等子类存在回退，摄影美感与真机性能尚未证明，详见 [2026-10-15 模型报告](../../../docs/AI识别模型优化与验证-2026-10-15.md)。

[2026-10-14 Tiny 历史实验](../../../docs/AI模型检索优化与实验-2026-10-14.md) 的三组微调未改善结论保持不变。工程入口见 [AI 工具说明](../README.md)。

## 本地目录与环境

以下 PowerShell 命令从仓库根目录运行。复跑使用新实验目录，避免覆盖已有证据。

```powershell
$ModelTools = 'ai/tools/build_template_db'
$ModelRun = 'local-output/model-optimization-rerun'
$ModelData = 'local-data/AI-camera-local-archive/2026-10-10/private-data/tools'
$ModelCheckpoint = "$ModelData/preprocessed/student_models/tiny_cnn_v9_wide_topk_siglip2_512/checkpoint.best.pt"
New-Item -ItemType Directory -Force "$ModelRun/tmp" | Out-Null
$env:TEMP = (Resolve-Path "$ModelRun/tmp").Path
$env:TMP = $env:TEMP
```

训练需要匹配的 PyTorch / torchvision，依赖范围见 `requirements.txt`。本次实际环境为 Python 3.10、PyTorch 2.11.0+cu130、torchvision 0.26.0、ONNX 1.17.0、ONNX Runtime 1.19.2。CUDA 仅用于开发机训练，手机继续离线运行小模型。

`local-data/` 存私有原图、历史权重与归档；`local-output/` 存实验清单、候选权重、裁剪结果、日志与虚拟环境。两者均被 Git 忽略。所有新增本地资料都留在原项目内，原始私有图片不随 GitHub 提交上传。

## 数据审计

```powershell
python "$ModelTools/prepare_retrieval_data.py" --data-root $ModelData --output-dir "$ModelRun/dataset" --exclude-known-group-overlap
```

输出 train/val/test JSONL、manifest 与 audit。检查源图、teacher 向量哈希、维度和数值、跨集合 ID/内容重复，可排除已知来源帖交叉。无法恢复来源组的样本标为未知，不能视为独立。派生裁剪和 AI 修复版本须保留原图所在分组。

当前批次的构图、光线及相机参数含按场景批量生成的规则标签，不是人工摄影真值。不能用这些标签宣称构图准确率或训练“好看照片”评分头。参考图需要另行检查主体完整、单张照片、无干扰 UI/教学字、可模仿及清晰度；AI 视觉分析须保留来源和不确定性。

## 当前推荐模型：预训练特征迁移

以下命令使用与现有报告完全相同的 prepared 清单，以新目录保留复跑证据。`python` 须来自安装了上述训练依赖的环境；本机实际使用的解释器是 `local-output/model-optimization-2026-10-14/.gpu-venv/Scripts/python.exe`。项目移动或重新生成清单后，必须先用旧模型重新产生同清单的 baseline 报告，不能绕过 manifest hash 检查。

```powershell
$RecognitionRun = 'local-output/model-recognition-rerun'
$PreparedData = 'local-output/model-optimization-2026-10-14/dataset'
$RecognitionBaseline = 'local-output/model-recognition-2026-10-15/baseline-assets/student_model.static_int8.onnx'
$RecognitionBaselineReport = 'local-output/model-optimization-2026-10-14/quantization-baseline/retrieval/summary.json'
New-Item -ItemType Directory -Force "$RecognitionRun/tmp" | Out-Null
$env:TEMP = (Resolve-Path "$RecognitionRun/tmp").Path
$env:TMP = $env:TEMP
python "$ModelTools/train_transfer_retrieval.py" --dataset-dir $PreparedData --projection-checkpoint $ModelCheckpoint --baseline-report $RecognitionBaselineReport --output-dir "$RecognitionRun/transfer" --torch-cache "$RecognitionRun/torch-cache" --device auto
python "$ModelTools/benchmark_onnx_retrieval.py" --dataset-dir $PreparedData --output-dir "$RecognitionRun/validation" --model "old_int8=$RecognitionBaseline" --model "fp32=$RecognitionRun/transfer/export/student_model.fp32.onnx" --reference fp32 --deployment-baseline old_int8 --threads 2 --repeats 50 --warmup 5
```

训练只读取 train/val，固定 3 个 `λ=0.01,0.1,1.0` 候选，模型内部保留 Android RGB `[-1,1]` 输入契约并转换 ImageNet 标准化。512 维输出归一化，旧 Tiny/Lite checkpoint 仍可离线加载。官方权重只在显式训练时下载到 `--torch-cache`；推理和单元测试不下载。

迁移选择器要求 macro R@1、macro R@5、micro R@5、teacher Top5 重合率四项不低于旧产品模型，再选择教师重合率最高候选。`benchmark_onnx_retrieval.py` 同时输出逐项数值；历史 `validation_retrieval_gate_passed` 只覆盖两项 macro 与教师重合，阅读报告时仍须检查 micro R@5。相同输出维度不意味着新旧向量处于同一空间。

模型冻结后才做一次 test 评估，不能观察 test 结果再换 λ。以下冻结文件绑定实际模型 SHA；首次评估产物和失败中间结果均不可覆盖。已有本次正式冻结记录和 test 报告时，直接查阅证据，不重复执行本例：

```powershell
$FrozenCandidate = "$RecognitionRun/transfer/export/student_model.fp32.onnx"
$FrozenSelection = @{
  selection_split = 'val'
  test_used_for_selection = $false
  baseline_sha256 = (Get-FileHash -LiteralPath $RecognitionBaseline -Algorithm SHA256).Hash.ToLowerInvariant()
  candidate_sha256 = (Get-FileHash -LiteralPath $FrozenCandidate -Algorithm SHA256).Hash.ToLowerInvariant()
}
$SelectionPath = Join-Path (Resolve-Path $RecognitionRun).Path 'frozen-selection.json'
if (Test-Path -LiteralPath $SelectionPath) { throw 'Preserve the existing frozen selection' }
[System.IO.File]::WriteAllText($SelectionPath, ($FrozenSelection | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
python "$ModelTools/evaluate_frozen_retrieval.py" --dataset-dir $PreparedData --selection "$RecognitionRun/frozen-selection.json" --baseline-model $RecognitionBaseline --candidate-model $FrozenCandidate --output-dir "$RecognitionRun/frozen-test"
```

冻结评估器先校验模型 hash，再读取 train/test，检查 split、身份/源图哈希/已知来源组交叉。来源组未知仍会记入报告；不能因此宣称完全独立。四指标任一回退时 CLI 返回 2，并保留报告。当前真实证据在 `local-output/model-recognition-2026-10-15/`。

## 历史 Tiny 小模型微调

```powershell
python "$ModelTools/optimize_student_retrieval.py" --dataset-dir "$ModelRun/dataset" --init-checkpoint $ModelCheckpoint --output-dir "$ModelRun/candidate-a" --epochs 16 --device auto
```

旧 V9 权重是 223,680 参数、224×224 输入、512 维输出的 `tiny_cnn_v1`。本节复现历史固定结构实验，只用 train 图库与 val 查询选模：

- 默认不随机翻转或裁切，保留摄影布局。原 `train_student_embedding.py` 的旧空间增强也改为显式 `--spatial-augmentation`。
- teacher top-K 蒸馏在完整学生图库上归一化，惩罚 top-K 之外的错误高分候选；排除自身，图库向量每轮刷新并停止梯度。
- val 按场景平均 recall@1/@5 均不得退步，再比较 teacher top-5 重合率。初始权重也参与选择，训练损失下降不能覆盖检索退步。
- 不读取 test 图片选模。原训练器最终 test 评估已改为加载 best checkpoint；缓存改为源图哈希绑定的无损 PNG。

输出 checkpoint、baseline/best validation、history、summary 和验证 embedding。`best_epoch=0` 或 `improved_validation=false` 表示没有证明微调改善，不能把保存动作称为模型升级。

## 导出、量化、检索对照

以下以历史 Tiny 实验输出为例。新迁移模型的 FP32 ONNX 已由训练命令导出；若重新做量化兼容实验，仍须单独通过数值和检索门槛，不能因为文件更小就替换。

```powershell
python "$ModelTools/export_student_model.py" "$ModelRun/candidate-a/checkpoint.best.pt" --output-dir "$ModelRun/candidate-a/export"
python "$ModelTools/quantize_onnx_static.py" "$ModelRun/candidate-a/export/student_model.fp32.onnx" --metadata "$ModelRun/dataset/train.jsonl" --validation-manifest "$ModelRun/dataset/val.jsonl" --output-model "$ModelRun/candidate-a/export/student_model.static_int8.onnx" --manifest-output "$ModelRun/candidate-a/export/android_onnx_runtime_manifest.json" --calibration-items 128 --batch-size 16
```

校准仅使用 train，按场景/照片类型分层抽样；val 单独验证数值漂移。已标记 val/test 的记录不能通过命令声明改作 train。没有验证集会标记 `not_validated` 并返回非零；数值检查失败也返回非零。`consistency_passed` 只表示同图向量接近，不代表检索合格。

默认只对 Conv/Gemm/MatMul 做 QDQ，其他算子保持 FP32。显式 `--quantize-ops Conv` 可让线性头保持浮点，产物应命名为 `mixed_conv_int8` 并据实报告混合精度。2026-10-15 的默认策略和 Conv-only 均未通过 `.95/.98/.02` 原阈值，所以停止尝试并固定 FP32。中文 Windows 路径若触发 ORT shape inference 错误，可在实验输出目录内用 ASCII 相对模型文件名运行；临时目录也保持项目内。

随后各模型分别重算 train 图库和 val 查询，不能共用不同模型的 embedding：

```powershell
python "$ModelTools/benchmark_onnx_retrieval.py" --dataset-dir "$ModelRun/dataset" --output-dir "$ModelRun/onnx-comparison" --model "fp32=$ModelRun/candidate-a/export/student_model.fp32.onnx" --model 'old_int8=local-output/model-recognition-2026-10-15/baseline-assets/student_model.static_int8.onnx' --model "candidate_int8=$ModelRun/candidate-a/export/student_model.static_int8.onnx" --reference fp32 --deployment-baseline old_int8 --threads 2
```

报告分别记录同场景 micro/macro recall@1/@5、teacher top-5 重合率、同图数值余弦和桌面 CPU 耗时。命令完成不等于候选可上线，查看 `validation_retrieval_gate_passed` 与指标差值。`deployment_ready=false` 还表示资产配套和真机检查尚未完成。

历史“INT8 top-5 同场景 96.15%”是在包含 train 的 728 张图库中排除自身检索的结果，不是独立新照片准确率、好看率或商业门槛。桌面 CPU 延迟也不能写成中端手机延迟。

`evaluate_retrieval.py` 支持已有 embedding 的独立评估，拒绝 query/gallery ID 或源图哈希交叉。Android 重排对照只模拟候选自投票，不包含实际光线观测或时序稳定，不能代替 App 测试。

## 恢复拼图、教程和截图中的照片

先看原图，标出确实属于完整照片的区域。裁剪计划绑定源图 SHA-256，坐标为 EXIF 旋转修正后的归一化 `[left, top, right, bottom]`：

```json
{"photo_id":"来源图ID","source_sha256":"原图的64位SHA256","recovery_regions":[{"region_id":"panel_a","bbox":[0.05,0.10,0.48,0.90]}]}
```

```powershell
python "$ModelTools/recover_reference_crops.py" --train-manifest "$ModelRun/dataset/train.jsonl" --review-plan "$ModelRun/crop-plan.jsonl" --output-dir "$ModelRun/recovered-references"
```

工具只处理明确指定的 train 区域，保留原图，输出无损 PNG，记录父/子图哈希、坐标和来源组。短边小于 224 会被拒绝，不放大制造细节；输出限项目 `local-output/` 新目录。

成功裁剪后仍须看主体完整性、残留文字/按钮并重新分析构图、生成 embedding。不能复制原拼图的向量或标签。AI 修复另存生成来源、提示词和父图，逐张复核；不能用生成修补画面证明真实拍摄引导改善。裁剪工具本身不执行生成式修补。

`build_reviewed_reference_bundle.py` 从哈希绑定的 `ai_visual_review`、`candidate_clean` 记录选择 train 原图和复核后的派生图，重新运行指定 ONNX 生成全部向量，输出完整参考包。使用 `--help` 查看 train 清单、原 metadata、review、派生 manifest 和人脸锚点参数。`candidate_clean` 仅代表适合单图参考，不代表美感真值。

未评价美感的参考使用 `aesthetic_score=null`、`template_value=unrated`、空 `shooting_guidance`，并声明 `aesthetic_ground_truth=false`。校验器只对带匹配图像哈希和 AI 审阅来源的记录开放此分支，旧 metadata、embedding 和 pose 校验仍保留。

## 新素材与验证

现有工具顺序可用：`import_local_photo_batch.py` → manifest/audit → review pack → 应用审核记录 → CV 预处理 → 标注/embedding → 索引构建和资产检查。各工具以 `--help` 查看参数。文件名 `accepted` 不能证明做过逐张人工构图审核；素材许可、质量分析和人工审核应分别记录。

换权重必须配套重建全图库向量、索引、模型哈希与 manifest；维度相同不代表新旧向量可混用。

```powershell
Push-Location ai/tools/build_template_db
python -m unittest discover -s tests
Pop-Location
python -B ai/tools/validate_android_v9_assets.py --asset-root aicamera-app/app/src/main/assets/ai/v9
```

测试验证实现行为。“更容易拍出好看照片”还需要实拍执行引导前后的盲评与动作正确率记录。
