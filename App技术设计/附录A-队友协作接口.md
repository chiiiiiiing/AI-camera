# 附录 A：与 AI 队友的协作接口

> 最新交付已固定为 v9 ONNX embedding + 模板索引方案。完整接入说明见 [09-AI接入包说明](./09-AI接入包说明.md)。

---

## A.1 你需要从 AI 侧拿到什么

| 交付物 | 当前状态 | 优先级 |
|--------|----------|--------|
| 模型文件 | `student_model.static_int8.onnx` 已生成 | 阻塞实际推理 |
| runtime manifest | `android_onnx_runtime_manifest.json` 已生成 | 阻塞输入输出解析 |
| App 接入配置 | `android_runtime_config.json` 已生成 | 阻塞重排和抽帧统一 |
| 模板索引 | `template_index.new_batch.student-tiny-cnn-v9-static-int8-onnx-512/` 已生成 | 阻塞推荐 |
| 输入规格 | RGB / 224x224 / NCHW / float32 / `(rgb/255 - 0.5) / 0.5` | 已固定 |
| 输出规格 | `[1,512]` embedding float32 | 已固定 |
| 辅助线映射规则 | 从 metadata 映射，第一版实现三分线、中心线、水平线、留白提示 | 需要 App 实现 |
| 真机性能结果 | p50 / p90 / 内存 / 发热 | 需要 App 提供 |

## A.2 当前推荐输出对象

模型本身不输出这个 JSON；这是 App 在 top5 检索和重排后组装给 UI overlay 的对象。

```json
{
  "template_id": "new_batch_00149",
  "scene_type": "food_still_life",
  "photo_type": "still_life",
  "composition_strategy": "rule_of_thirds",
  "score": 0.82,
  "source": "v9_static_int8_onnx_top5_rerank",
  "guide_lines": [
    {
      "type": "rule_of_thirds_grid",
      "visible": true,
      "params": {
        "vertical_lines": [0.333, 0.667],
        "horizontal_lines": [0.333, 0.667]
      }
    },
    {
      "type": "horizon_level",
      "visible": true,
      "params": {
        "position": "upper_third"
      }
    }
  ]
}
```

App 第一版可以先根据 `composition.type`、`horizon_position`、`negative_space` 等 metadata 字段生成这些 guide line。精确主体框和真实引导线坐标不在当前 v9 embedding 输出里。

## A.3 需要确认的问题清单

- [x] 模型输入分辨率：224x224。
- [x] 输入格式：RGB、NCHW、float32。
- [x] 归一化：`(rgb/255 - 0.5) / 0.5`。
- [x] 输出：512 维 embedding。
- [x] 推荐策略：top5 + 规则重排，不只用 top1。
- [x] 抽帧策略：500ms-1000ms 或画面变化触发。
- [ ] Android 工程使用的 ONNX Runtime 版本。
- [ ] 首发目标设备型号。
- [ ] 真机 p50/p90、内存、发热结果。
- [ ] 辅助线 UI 类型和视觉样式。
- [ ] 前置摄像头时 overlay 坐标镜像策略。
- [ ] 动画效果有参考吗？（给视频/动图）
- [ ] UI 设计稿/标注稿什么时候能给？
- [ ] 辅助线的颜色、透明度、线宽有规范吗？

## A.4 拿到答案前后的工作分配

### 拿到 App 工程前也能做

| 模块 | 说明 |
|------|------|
| [01-相机链路](./01-相机链路.md) | CameraX 预览、拍照、权限 |
| [02-帧处理管线](./02-帧处理管线.md) | 用固定 800ms 抽帧和假 embedding 验证 |
| [04-辅助线渲染引擎](./04-辅助线渲染引擎.md) | 搭好 View + 坐标映射 + metadata 映射，用硬编码模板测试 |
| [06-性能优化](./06-性能优化.md) | 埋点 p50/p90、内存、发热 |
| [07-上线工程](./07-上线工程.md) | 模型/索引版本管理和 asset 复制 |

### 进入 Android 工程后必须做

- 加载 static int8 ONNX。
- 加载模板索引和 metadata。
- 接 ImageAnalysis 抽帧链路。
- 接 top5 + 规则重排。
- 接 overlay 渲染。
- 真机性能验证：p50 <= 80ms，p90 <= 150ms。

---

> 上一篇：[08-MVP方案与迭代](./08-MVP方案与迭代.md)
> 下一篇：[附录B-技术选型速查](./附录B-技术选型速查.md)
