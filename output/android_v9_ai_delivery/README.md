# Android v9 AI Delivery

这是给 Android App 队友的 v9 首发候选 AI 交付目录。

## 1. 重要结论

- 当前不是 `.tflite` 方案。
- 当前 runtime 是 ONNX Runtime Mobile。
- 当前模型文件是 `student_model.static_int8.onnx`。
- 当前模型输出是 512 维 embedding，不直接输出 14 种 `GuidanceCue`。
- App 侧需要执行：embedding -> template index top5 -> 规则重排 -> metadata 转辅助线/`ModelOutput`。

完整说明见：

```text
App技术设计/09-AI接入包说明.md
App技术设计/10-给App队友-交付清单回复.md
```

## 2. 文件结构

```text
output/android_v9_ai_delivery/
  student_model.static_int8.onnx
  student_model.fp32.onnx
  android_onnx_runtime_manifest.json
  android_runtime_config.json
  template_index/
    templates.embeddings.f32
    templates.keys.json
    templates.metadata.jsonl
    templates.quality_report.json
    templates.quality_report.md
    templates.usearch.idx
    templates.usearch.json
```

## 3. 模型输入

```text
input name:   image
shape:        [1, 3, 224, 224]
dtype:        float32
color:        RGB
layout:       NCHW
normalize:    value = (rgb / 255.0 - 0.5) / 0.5
rotation:     App 侧按 ImageProxy.rotationDegrees 转正后再 resize
```

## 4. 模型输出

```text
output name:  embedding
shape:        [1, 512]
dtype:        float32
meaning:      当前画面的 L2 normalized embedding
postprocess:  App 可再做一次防御性 L2 normalize
```

## 5. App 接入要求

1. 使用 ONNX Runtime Android 加载 `student_model.static_int8.onnx`。
2. 把相机帧预处理成 RGB / 224x224 / NCHW / float32 / normalize。
3. 推理得到 512 维 embedding。
4. 用 `template_index/templates.embeddings.f32` 检索 top8/top10。
5. 用 top5 + 规则重排生成稳定推荐。
6. 从 `templates.metadata.jsonl` 读取模板构图信息，转成 UI 辅助线和 `ModelOutput`。
7. 不要每帧推理；按 500ms-1000ms 或画面变化触发。
8. 低 margin 时不要立即切模板，要求 2-3 次稳定领先。

## 6. 真机验收

App 侧需要补以下数据：

- ONNX Runtime Android 版本。
- 首发目标设备型号和芯片。
- 单次 embedding p50 / p90。
- 连续预览内存、温度、耗电。
- 前后摄切换、旋转、后台恢复后的稳定性。
- top5 + rerank 后 UI 是否稳定。

