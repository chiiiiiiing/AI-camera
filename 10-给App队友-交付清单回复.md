# 10-给 App 队友的 AI 交付清单回复

> 本文是对队友文件《给 AI 模型队友 - 交付清单》的逐项回复。结论先说清楚：当前 Android 首发候选不是 `.tflite` 直出 14 种 GuidanceCue，而是 `ONNX Runtime Mobile -> 512 维 embedding -> template index top5 -> 规则重排 -> metadata 转辅助线`。

## 1. 当前能交付什么

| 队友要求 | 当前状态 | AI 侧补充说明 |
|---|---|---|
| `.tflite` 模型文件 | 暂不提供 | 当前已完成并评估的是 static int8 ONNX。若强制改 TFLite/LiteRT，需要重新导出、量化、跑质量报告和 Android 真机验证。 |
| 模型输入规格文档 | 已补齐 | 见本文第 3 节，也见 `android_onnx_runtime_manifest.json`。 |
| 模型输出规格文档 | 已补齐 | 当前模型输出 `[1,512]` embedding，不直接输出 `ModelOutput`。 |
| 输出到 App 类型的映射代码 | 本文补桥接方案 | App 侧应把 top5 检索结果和模板 metadata 转成 UI 需要的 `ModelOutput`。 |
| 14 种 GuidanceCue | 模型不直接覆盖 | `MOVE_*`、`ROLL_*`、`SUBJECT_*` 需要 App 侧主体/水平线/状态机参与；embedding 模型只负责推荐模板。 |

## 2. 必须放进 Android 包的文件

模型文件目录：

```text
tools/preprocessed/student_models/tiny_cnn_v9_wide_topk_siglip2_512/export/
```

| 文件 | 必需 | 大小 | sha256 | 用途 |
|---|---:|---:|---|---|
| `student_model.static_int8.onnx` | 是 | 301,677 bytes | `752bfef24b553a27fb0d419e939a6fc2583a22efda1287b01ce1184409f3e5d9` | Android 首发候选模型 |
| `android_onnx_runtime_manifest.json` | 是 | 约 1.7KB | `ad019cb7df2ce5056c80b2783198b4da4c7b167c07db4874ed583dad6bad4ad6` | 输入输出、量化、校验信息 |
| `android_runtime_config.json` | 是 | 约 3.0KB | `1b7af812d9d8e150047bb02073633a1bf4d649e9cb96a0f57fc6fb91d033fbac` | 抽帧、topK、重排、预算配置 |
| `student_model.fp32.onnx` | 建议 | 约 877KB | 以本地文件为准 | 排查 int8 精度问题的对照模型 |

模板索引目录：

```text
tools/preprocessed/template_index.new_batch.student-tiny-cnn-v9-static-int8-onnx-512/
```

| 文件 | 必需 | 大小 | 用途 |
|---|---:|---:|---|
| `templates.embeddings.f32` | 是 | 1,490,944 bytes | 728 条 512 维模板 embedding |
| `templates.keys.json` | 是 | 134,747 bytes | key 与 template_id 映射 |
| `templates.metadata.jsonl` | 是 | 1,772,514 bytes | 模板 metadata，App 从这里生成辅助线和提示 |
| `templates.usearch.idx` | 建议 | 约 1.5MB | USearch 原型索引 |
| `templates.usearch.json` | 建议 | 241 bytes | USearch manifest |
| `templates.quality_report.md/json` | 建议 | 约 333KB | 离线质量报告 |

建议 Android 落盘结构：

```text
filesDir/ai/v9/
  student_model.static_int8.onnx
  android_onnx_runtime_manifest.json
  android_runtime_config.json
  student_model.fp32.onnx
  template_index/
    templates.embeddings.f32
    templates.keys.json
    templates.metadata.jsonl
    templates.usearch.idx
    templates.usearch.json
```

注意：模型和 index 必须同版本发布。v9 模型不能混用 v8 或 visual-clean 256 维 index。

## 3. 队友清单中的输入规格填空

```text
输入 Tensor shape:  [1, 3, 224, 224]
输入 dtype:         FLOAT32
像素格式:           RGB
Tensor layout:      NCHW
归一化方式:         value = (rgb / 255.0 - 0.5) / 0.5
等价写法:           mean=[0.5,0.5,0.5] std=[0.5,0.5,0.5]，前提是 rgb 已先除以 255 到 0.0-1.0
是否内置归一化:      否，App 需要 resize + RGB + NCHW + normalize 后再喂模型
是否需要旋转矫正:    是，模型期望正向图；App 应先按 ImageProxy.rotationDegrees 转正
前摄镜像:            不建议改变模型输入语义；overlay 坐标镜像由 App 渲染层处理
```

Android 预处理顺序必须固定：

```text
ImageProxy
  -> 按 rotationDegrees 转正
  -> 转 RGB
  -> resize 224x224
  -> 写入 float32 NCHW
  -> value = (rgb / 255.0 - 0.5) / 0.5
```

## 4. 队友清单中的输出规格填空

当前模型输出不是队友文档里的单个 `float[] ModelOutput` layout。真实输出如下：

```text
输出 Tensor name:   embedding
输出 Tensor shape:  [1, 512]
输出 dtype:         FLOAT32
后处理:             模型内已 L2 normalize；App 可再做一次防御性 L2 normalize
含义:               当前画面的 512 维视觉语义向量，用于 template index 检索
```

输出链路应改成：

```text
embedding[512]
  -> template index search top8/top10
  -> top5 + rule rerank
  -> selected TemplateMetadata
  -> App Adapter 生成 ModelOutput / GuidanceState
```

离线质量：

| 指标 | top1 | top3 | top5 |
|---|---:|---:|---:|
| 同 photo_type 命中率 | 95.33% | 97.53% | 98.21% |
| 同 scene_category 命中率 | 88.87% | 94.78% | 96.15% |
| 构图匹配命中率 | 94.64% | 98.21% | 98.90% |
| 光线匹配命中率 | 89.01% | 94.64% | 96.57% |

top1/top2 相似度差小于 0.01 的查询数是 270，所以 App 不要直接使用 top1 切换 UI，应使用 top5 + 规则重排 + 稳定策略。

## 5. 14 种 GuidanceCue 的当前可支持程度

| GuidanceCue | 当前能否由 v9 模型直接给出 | MVP 建议来源 |
|---|---|---|
| `NONE` | 可以间接支持 | 没有稳定模板、margin 低、或构图已经稳定时由 App 状态机输出 |
| `SUBJECT_TARGET` | 不能直接输出 | App 侧主体检测到 subjectBox，且与模板目标区不一致 |
| `SUBJECT_ALIGNED` | 不能直接输出 | App 侧主体检测到 subjectBox，且中心接近模板目标区 |
| `MOVE_LEFT/RIGHT/UP/DOWN` | 不能直接输出 | App 比较当前 subjectBox 中心和模板 targetZone 后计算 |
| `ROLL_CLOCKWISE/ROLL_COUNTERCLOCKWISE` | 不能直接输出 | App 侧水平线检测或 IMU 估计 horizonAngleDegrees 后计算 |
| `PITCH_UP/PITCH_DOWN` | 不能可靠输出 | 需要 App 侧姿态/机位判断、IMU 或后续专门模型 |
| `YAW_LEFT/YAW_RIGHT` | 不能可靠输出 | 需要 App 侧姿态/机位判断、IMU 或后续专门模型 |

也就是说，当前 AI 侧能稳定提供的是“推荐哪个模板”和“这个模板的构图 metadata”。把它变成 14 种实时移动/旋转指令，需要 App 侧拿当前画面的几何检测结果做差值。

## 6. App Adapter 建议

队友文档里的 `ModelOutput` 可以保留，但不要把它理解为模型 tensor 直接输出。建议改成 App 内部桥接结果：

```kotlin
data class EmbeddingRecommendation(
    val templateId: String,
    val cosine: Float,
    val rerankScore: Float,
    val margin: Float,
    val photoType: String,
    val sceneType: String,
    val compositionType: String,
    val subjectPosition: String?,
    val horizonPosition: String?,
    val negativeSpace: String?,
    val leadingLines: Boolean,
    val visualSummary: String?
)
```

再由 `EmbeddingRecommendation` 和 App 的当前帧几何信息生成 `ModelOutput`：

```kotlin
data class FrameGeometry(
    val subjectBox: FloatArray?,          // [x, y, width, height], normalized
    val horizonAngleDegrees: Float?,      // null = 未检测
    val sceneConfidence: Float
)

fun toModelOutput(
    rec: EmbeddingRecommendation?,
    geometry: FrameGeometry?
): ModelOutput {
    if (rec == null) {
        return ModelOutput(
            guidanceCue = GuidanceCue.NONE,
            compositionProgress = 0f,
            sceneType = "unknown",
            confidence = 0f,
            subjectContour = null,
            subjectBox = null,
            targetZone = null
        )
    }

    val targetZone = targetZoneFromTemplate(rec)
    val subjectBox = geometry?.subjectBox
    val cue = guidanceCueFromGeometry(subjectBox, targetZone, geometry?.horizonAngleDegrees)

    return ModelOutput(
        guidanceCue = cue,
        compositionProgress = compositionProgress(subjectBox, targetZone, rec.margin),
        sceneType = rec.sceneType,
        confidence = rec.rerankScore.coerceIn(0f, 1f),
        subjectContour = null,
        contourClosed = false,
        verticalLines = floatArrayOf(0.333f, 0.667f),
        horizontalLines = floatArrayOf(0.333f, 0.667f),
        subjectBox = subjectBox,
        targetZone = targetZone,
        horizonAngleDegrees = geometry?.horizonAngleDegrees ?: 0f
    )
}
```

MVP 版 `guidanceCueFromGeometry` 可以先只覆盖稳定的 7 类：

```kotlin
fun guidanceCueFromGeometry(
    subjectBox: FloatArray?,
    targetZone: FloatArray?,
    horizonAngleDegrees: Float?
): GuidanceCue {
    val angle = horizonAngleDegrees
    if (angle != null && angle > 2f) return GuidanceCue.ROLL_COUNTERCLOCKWISE
    if (angle != null && angle < -2f) return GuidanceCue.ROLL_CLOCKWISE

    if (subjectBox == null || targetZone == null) return GuidanceCue.NONE

    val subjectCx = subjectBox[0] + subjectBox[2] / 2f
    val subjectCy = subjectBox[1] + subjectBox[3] / 2f
    val targetCx = targetZone[0]
    val targetCy = targetZone[1]
    val tolerance = targetZone[2].coerceAtLeast(0.06f)

    val dx = subjectCx - targetCx
    val dy = subjectCy - targetCy

    if (kotlin.math.abs(dx) <= tolerance && kotlin.math.abs(dy) <= tolerance) {
        return GuidanceCue.SUBJECT_ALIGNED
    }
    if (dx > tolerance) return GuidanceCue.MOVE_LEFT
    if (dx < -tolerance) return GuidanceCue.MOVE_RIGHT
    if (dy > tolerance) return GuidanceCue.MOVE_UP
    if (dy < -tolerance) return GuidanceCue.MOVE_DOWN
    return GuidanceCue.SUBJECT_TARGET
}
```

`PITCH_*` 和 `YAW_*` 第一版不建议硬做，除非 App 已经有 IMU、主体尺度变化、消失边界、地平线/透视线等额外信号。

## 7. 从模板 metadata 生成辅助线

当前 new_batch v9 index 中模板 metadata 的主要构图分布：

| 字段 | 分布 |
|---|---|
| `photo_type` | still_life 339、landscape 292、portrait 97 |
| `scene_category` | natural_landscape 263、food_still_life 202、lifestyle_still_life 132、closeup_portrait 97、urban_landscape 29、plant_still_life 5 |
| `annotation.composition.type` | rule_of_thirds 602、centered_subject 97、leading_lines 29 |
| `annotation.composition.horizon_position` | not_visible 304、upper_third 263、unknown 132、middle 29 |
| `annotation.composition.subject_position` | center 465、none 263 |
| `annotation.composition.leading_lines` | true 292、false 436 |
| `annotation.composition.negative_space` | medium 368、high 263、low 97 |

MVP 映射建议：

| metadata | UI 可以怎么画 |
|---|---|
| `type=rule_of_thirds` | 显示三分线，targetZone 取三分交点或中心偏三分位置 |
| `type=centered_subject` | 显示中心线/中心目标区 |
| `type=leading_lines` 或 `leading_lines=true` | 显示“寻找延伸线”的提示；不要假画不存在的真实线段 |
| `horizon_position=upper_third` | 显示上三分水平参考线 |
| `horizon_position=middle` | 显示中心水平参考线 |
| `negative_space=high` | 提示保留留白，targetZone 不要铺满主体 |

## 8. 仍缺什么

| 缺口 | 归属 | 影响 |
|---|---|---|
| `.tflite` 模型 | AI 侧，若产品强制 LiteRT | 当前路线没有，需要重新导出和评估 |
| 直接输出 14 cue 的模型头 | AI 侧未来迭代 | 当前 v9 不做实时几何控制 |
| subjectBox / subjectContour | App 侧或后续检测模型 | 没有它就不能可靠输出 `MOVE_*` / `SUBJECT_*` |
| horizonAngleDegrees | App 侧或后续检测模型 | 没有它就不能可靠输出 `ROLL_*` |
| pitch/yaw 信号 | App 侧 IMU/视觉几何/后续模型 | 当前不建议硬映射 |
| Android 真机 p50/p90、内存、发热 | App 侧 | 没有真机数据不能商业默认上线 |
| ONNX Runtime Android 版本锁定 | App 侧 | 建议先用 1.19.x 起测 |

## 9. 给 App 队友的最短接入结论

1. 不要等 `.tflite`，当前可接的是 `student_model.static_int8.onnx`。
2. 不要等模型直接吐 14 种 GuidanceCue，当前模型只吐 512 维 embedding。
3. App 侧先实现：ONNX 推理、template index 检索、top5 + 规则重排、metadata 转辅助线。
4. `ModelOutput` 可以作为 UI 层对象，但它应由 App Adapter 生成，不是 v9 模型 tensor 直接输出。
5. 真机必须测 p50/p90、内存和发热；离线 top5 同场景 96.15% 只能说明模型候选池质量，不等于 App 已可上线。

