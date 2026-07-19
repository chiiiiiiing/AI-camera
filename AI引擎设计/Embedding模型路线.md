# Embedding 模型路线

## 结论先行

已选路线（2026-07-18）：

1. 选择“蒸馏已有模型形成自有端侧模型”作为商业路线。
2. teacher / baseline 已选 `google/siglip2-base-patch16-224`。
3. 现有 `MobileCLIP2-S0` 只保留为阶段 2 原型，不进入商业发布。

原始推荐：

1. 短期继续用 `MobileCLIP2-S0` 维持现有原型，不进入商业发布。
2. 商业候选优先评测 `SigLIP / SigLIP2`，理由是官方模型卡标注 `Apache-2.0`，权利边界更清楚。
3. 中长期做自有轻量 embedding 模型或蒸馏模型，把摄影模板检索做成项目自己的核心资产。

## 路线 A：继续 MobileCLIP2-S0

适合：当前阶段 2 原型和工具链验证。

优点：

- 已在项目中接入，104 条正式模板 embedding 已生成。
- 模型面向移动端，速度/体积方向最贴合端侧相机。
- 继续推进数据链路成本最低。

风险：

- Apple `LICENSE_MODELS` 写明模型仅限 research purposes，不包括商业产品或服务。
- 未拿到 Apple 单独商业授权前，不能作为商业版默认模型。

判断：保留原型，不作为商业路线。

## 路线 B：Google SigLIP / SigLIP2

适合：商业版候选 baseline。

优点：

- Hugging Face 官方模型卡标注 `Apache-2.0`。
- SigLIP/SigLIP2 是图文双塔 embedding，适合模板检索、文本标签和图像相似度。
- SigLIP2 对语义理解、定位和 dense features 有增强，后续可复用空间更大。

风险：

- Base 级模型文件约 1.5GB，端侧需要量化、裁剪或蒸馏。
- Android 实时帧推理要单独测延迟、内存和发热。
- 需要把现有 256 维索引切到新维度，不能混用旧 embedding。

判断：当前最适合做商业候选评测。

## 路线 C：OpenAI CLIP

适合：稳定基线和离线对比。

优点：

- 代码和权重发布多年，生态成熟。
- 官方 GitHub license 是 MIT。
- ONNX/CoreML/NCNN/TFLite 转换资料多。

风险：

- OpenAI model card 明确提示 deployed use case 超出当初评估范围，需要做充分场景测试。
- 模型较老，移动端效率不如专门为手机设计的模型。
- 中文/摄影细粒度语义未必稳定。

判断：可做评测基线，不建议直接定为最终商业端侧模型。

## 路线 D：OpenCLIP 系列

适合：离线评测和高性能对照组。

优点：

- 实现成熟，模型选择多。
- 部分 DataComp / LAION checkpoint 检索效果强。
- 方便快速对比不同 backbone。

风险：

- 每个 checkpoint 的训练数据、权重许可证和下游合规边界要逐个审。
- 很多强模型偏大，不适合第一版实时端侧。

判断：作为候选池可以，不能默认全量商业可用。

## 路线 E：自训练 / 蒸馏轻量模型

适合：长期商业护城河。

优点：

- 权利、模型行为和端侧体积都可控。
- 可以针对摄影模板的构图、光线、姿态、场景类别做专项优化。
- 后续更容易形成差异化推荐能力。

风险：

- 当前 5200 级素材更适合做微调、度量学习、蒸馏或 reranker，不足以从零训练通用图文模型。
- 需要 teacher 模型、训练脚本、评测集、量化导出和持续回归。

判断：中长期目标；第一版可先用商业可用 baseline，再蒸馏成端侧小模型。

## 已决策

用户已选择：

- 路线 E：蒸馏已有模型形成自有端侧 embedding 模型。

执行顺序：

1. 用 1156 条 accepted 新批次建立视觉标注和评测集。
2. 用 `SigLIP2` 做 teacher / baseline。
3. 先做离线 embedding 质量评测，再做小模型蒸馏。
4. 导出端侧模型并替换实时 frame embedding。
5. 重建 template index，不能混用旧 `MobileCLIP2-S0` embedding。

当前状态：

- 视觉标注、teacher embedding、teacher index、蒸馏数据集均已完成。
- `SigLIP2 teacher`：728 条、768 维，top5 同场景 97.53%，作为离线质量上限。
- 当前最佳 student 检索候选：`tools/preprocessed/student_models/tiny_cnn_v9_wide_topk_siglip2_512/`。
- v9 student：223,680 参数，224px 输入，512 维输出，FP32 TorchScript 约 1.03MB，dynamic int8 原型约 671KB。
- v9 FP32 ONNX：897,730 bytes，`export/student_model.fp32.onnx`。
- v9 static int8 ONNX：301,677 bytes，`export/student_model.static_int8.onnx`，Android 首发 runtime 候选。
- v9 index：`tools/preprocessed/template_index.new_batch.student-tiny-cnn-v9-wide-topk-512/`。
- v9 static int8 ONNX index：`tools/preprocessed/template_index.new_batch.student-tiny-cnn-v9-static-int8-onnx-512/`。
- v9 质量：top1 同场景 88.74%，top5 同场景 96.57%，top5 同类型 98.49%。
- v9 static int8 ONNX 质量：top1 同场景 88.87%，top5 同场景 96.15%，top5 同类型 98.21%。
- v9 训练：best epoch 55，best val cosine 0.406044，test cosine 0.405431。
- v5 已被 v9 替代：v5 top5 同场景 94.37%，未达到 95%+ 建议门槛。
- v6 cosine schedule 版本 test cosine 与 v9 接近，但真实检索 top5 同场景为 93.96%，暂不作为最佳检索候选。
- v8 强 listwise / hard negative 版本降低了 top1/top2 低 margin，但 top5 同场景 94.23%，不替代 v9。

当前商用判断：

- 蒸馏路线可行，工具链已闭环。
- v9 已达到 student top5 同场景 95%+ 质量门槛，接近 teacher 97.53%。
- 当前已完成 ONNX Runtime Mobile 路线的 FP32 ONNX 导出和 static int8 ONNX 量化；dynamic int8 TorchScript 只保留为 PyTorch 原型。
- Android 商业端侧还需要接入真实 App 工程并完成目标设备 p50/p90、内存、发热、连续预览稳定性验证。
- 当前训练集 579 条，类别不均衡，无法支撑“商用品质已稳定”的结论。

下一步模型工作：

- 以 v9 static int8 ONNX 为 Android 端侧检索候选做 App 接入。
- App 侧按 500ms-1000ms 抽帧或画面变化触发 embedding，并使用 top5 + 规则重排。
- 在目标 Android 设备上实测 p50 <= 80ms、p90 <= 150ms、内存、发热和连续预览稳定性。
- 补充更多训练数据，尤其 portrait、plant、urban 等弱类别。
- 继续做更稳的 hard negative / listwise 蒸馏，让 student 学 teacher 的 topK 排序，同时避免 v8 那类强约束压低 embedding cosine。

仍不要选：

- 直接用 `MobileCLIP2-S0` 商业发布，除非拿到 Apple 单独商业授权。

## 参考来源

- Apple MobileCLIP2 repo：`https://github.com/apple/ml-mobileclip`
- Apple model license：`https://github.com/apple/ml-mobileclip/blob/main/LICENSE_MODELS`
- Google SigLIP：`https://huggingface.co/google/siglip-base-patch16-224`
- Google SigLIP2：`https://huggingface.co/google/siglip2-base-patch16-224`
- OpenAI CLIP license：`https://github.com/openai/CLIP/blob/main/LICENSE`
- OpenAI CLIP model card：`https://github.com/openai/CLIP/blob/main/model-card.md`
- OpenCLIP：`https://github.com/mlfoundations/open_clip`
