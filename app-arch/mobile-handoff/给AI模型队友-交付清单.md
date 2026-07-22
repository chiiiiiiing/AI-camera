# 给 AI 模型队友 — 交付清单

> **当前状态**：Android App 全部基础设施已就绪（相机链路 / 帧管线 / 引导渲染引擎 / 状态机）。
> 唯一阻塞项：**端侧推理模型未到位**，FakeInference 在用随机数模拟引导输出。
>
> **本文档告诉你**：需要交付什么、规格如何约定、我们 App 侧如何消费你的输出。

---

## 1. 你需要交付的东西

| 编号 | 交付物 | 优先级 | 说明 |
|------|--------|--------|------|
| ① | `.tflite` 模型文件 | 🔴 阻塞 | 放在 `app/src/main/assets/` 下，文件名协商 |
| ② | 模型输入规格文档 | 🔴 阻塞 | 输入 Tensor 的 shape / dtype / 像素格式 / 归一化参数 |
| ③ | 模型输出规格文档 | 🔴 阻塞 | 输出 Tensor 的 shape 和每个维度含义 |
| ④ | 输出 → App 类型的映射代码 | 🟡 建议 | 把你的 float 数组解析为下面的数据结构 |

---

## 2. 模型输入规格（请填写）

```
输入 Tensor shape:  [1, ____, ____, ____]   (如 [1, 320, 320, 3])
输入 dtype:         □ FLOAT32  □ UINT8  □ INT8
像素格式:           □ RGB    □ BGR
归一化方式:         mean=_____  std=_____   (如 mean=[0,0,0] std=[255,255,255])
或:                □ 已内置在模型中，App 只需 resize + 转 Tensor
是否需要旋转矫正:    □ 是(模型期望正向上图)  □ 否(模型接受任意方向)
```

---

## 3. 模型输出 — 14 种引导指令（必须覆盖）

App 当前定义了 14 种引导指令。你的模型输出需要能映射到这些枚举值：

### 3.1 平移类 (Translation)
| GuidanceCue | 含义 | 触发场景举例 |
|-------------|------|------------|
| `MOVE_LEFT` | 用户需向左移动手机 | 主体偏右，需向左移动取景框 |
| `MOVE_RIGHT` | 用户需向右移动手机 | 主体偏左 |
| `MOVE_UP` | 用户需向上移动手机 | 主体偏下 |
| `MOVE_DOWN` | 用户需向下移动手机 | 主体偏上 |

### 3.2 轴旋转类 (Axis Rotation)
| GuidanceCue | 含义 | 触发场景举例 |
|-------------|------|------------|
| `PITCH_UP` | 手机仰角向上 | 画面顶部构图需更多空间 |
| `PITCH_DOWN` | 手机仰角向下 | 画面底部需更多空间 |
| `YAW_LEFT` | 水平左转 | 偏航角需向左调整 |
| `YAW_RIGHT` | 水平右转 | 偏航角需向右调整 |

### 3.3 面内旋转类 (Planar Rotation)
| GuidanceCue | 含义 | 触发场景举例 |
|-------------|------|------------|
| `ROLL_CLOCKWISE` | 顺时针旋转手机 | 水平线向右倾斜 |
| `ROLL_COUNTERCLOCKWISE` | 逆时针旋转手机 | 水平线向左倾斜 |

### 3.4 主体类 (Subject)
| GuidanceCue | 含义 | 触发场景举例 |
|-------------|------|------------|
| `SUBJECT_TARGET` | 有主体轮廓，需用户对齐 | 检测到人物但位置不理想 |
| `SUBJECT_ALIGNED` | 主体已对齐 | 人物轮廓与推荐构图位置匹配 |

### 3.5 无引导
| GuidanceCue | 含义 | 触发场景举例 |
|-------------|------|------------|
| `NONE` | 无特定引导 | 构图已达推荐标准、或画面无识别主体 |

---

## 4. 模型输出数据结构（请按此 schema 输出）

你的模型输出 Tensor 需要能被解析为以下结构：

```kotlin
// 每帧输出
data class ModelOutput(
    // ★ 当前帧的引导指令（必填，14 选 1）
    val guidanceCue: GuidanceCue,       // 见上表

    // ★ 构图匹配度 0.0–1.0（必填）
    // 0.0 = 完全不对, 0.5 = 正在接近, 1.0 = 完美匹配
    val compositionProgress: Float,

    // 场景类型（选填，用于调试 HUD 显示）
    val sceneType: String,              // "portrait" / "landscape" / "food" / "architecture" / ...

    // 置信度 0.0–1.0（选填）
    val confidence: Float,

    // ── 主体轮廓（选填，仅当 guidanceCue 为 SUBJECT_TARGET/SUBJECT_ALIGNED 时需提供） ──

    // 归一化轮廓点数组 [x0,y0, x1,y1, x2,y2, ...]
    // 坐标范围: 0.0–1.0，原点为画面左上角
    val subjectContour: FloatArray?,

    // 轮廓是否闭合
    val contourClosed: Boolean = false,

    // ── 构图参考线（选填） ──

    // 三分线位置（归一化 0.0–1.0）
    val verticalLines: FloatArray = floatArrayOf(0.333f, 0.667f),
    val horizontalLines: FloatArray = floatArrayOf(0.333f, 0.667f),

    // 主体定位框 [x, y, width, height]（归一化 0.0–1.0）
    val subjectBox: FloatArray?,        // null = 无主体

    // 目标区域 [centerX, centerY, radius]（归一化 0.0–1.0）
    val targetZone: FloatArray?,

    // 水平仪角度（度，正 = 顺时针偏转）
    val horizonAngleDegrees: Float = 0f
)
```

### 输出 Tensor 映射建议

如果你用单个 float 数组输出，建议 layout：

```
[0]:   guidanceCue (int, 0–13，映射到 14 种枚举)
[1]:   compositionProgress (float, 0.0–1.0)
[2]:   confidence (float, 0.0–1.0)
[3]:   sceneType (int, 场景分类 ID)
[4]:   horizonAngleDegrees (float, -180~180)
[5]:   hasSubjectContour (int, 0/1)
       — 如果 hasSubjectContour=1，后续是 N 个轮廓点坐标 —
[6..]: subjectContourPoints (float[], 归一化 x,y 交替)
...
```

---

## 5. 坐标约定（非常重要）

| 约定 | 说明 |
|------|------|
| 归一化坐标系 | **所有坐标输出使用 0.0–1.0**，原点在画面左上角 |
| 前摄镜像 | **不需要你处理**，App 侧自动用 `(1.0 - x)` 翻转 X 坐标 |
| 旋转 | 输出坐标基于**分析帧的方向**，App 侧根据 `rotationDegrees` 自动映射 |
| 非有限值 | NaN/Infinity 坐标会被 App 侧丢弃 |

---

## 6. 模型交付后的接入流程

```
你的 .tflite 放 assets/
    │
    ▼
App 侧实现 LiteRTInference : IInference
    │  加载模型 → 预处理图像帧 → 推理 → 解析 float[] 为 ModelOutput
    │
    ▼
GuidanceStateMachine.reduceFrame()
    │  输入 ModelOutput → 滞回过滤 → 输出 GuidanceState
    │
    ▼
ComposeGuidanceOverlay 渲染引导动画
```

你只需要交付 ①②③④（.tflite + 输入/输出规格），App 侧我来对接。

---

## 7. FAQ

**Q: 我的模型暂时只能输出场景类型，还做不到 14 种引导指令？**
A: 没关系。先交付能输出 `sceneType + confidence` 的模型，我们 App 侧可以先用 FakeInference 的映射逻辑过渡。后续你迭代模型时逐步增加 cue 输出维度。

**Q: 输出 Tensor 格式跟你建议的不一样？**
A: 完全 OK。你给什么格式我就接什么格式——只要把**输出 Tensor shape 和每个维度的含义**写清楚即可。

**Q: 模型需要 INT8 量化吗？**
A: 建议提供。INT8 模型体积更小、推理更快，对低端机更友好。如果暂时做不到，FLOAT32 也可以先跑。

**Q: 可以有多个 .tflite 吗（场景识别 + 构图引导分开）？**
A: 可以。只要每个模型的输入/输出规格都写清楚。

---

> **联系方式**：请把模型文件和规格文档放到 GitHub 仓库的 `ai-engine` 分支，或直接发我文件。
>
> **最后更新**：2026-07-21
