# 上线工程 — Android 项目骨架（已集成全部模块）

> 状态：✅ 可编译运行（需 Android Studio 同步） | 对应文档：[07-上线工程.md](../md/07-上线工程.md)

## 已集成的模块

| 模块 | 包路径 | 文件 |
|------|--------|:--:|
| 相机链路 | `camera/` | CameraManager / Capture / Controls / Focus / Permission / Config |
| 帧处理管线 | `pipeline/` | FrameScheduler / Preprocessor / Pipeline / FakeInference |
| 辅助线渲染 | `overlay/` | CompositionOverlayView / GuideLineRenderer / CoordinateMapper / AnimationController |
| MVVM 状态 | `viewmodel/` | CameraViewModel / PipelineViewModel / RenderViewModel |

## 完整数据流

```
MainActivity (Compose)
  │
  ├─ AndroidView: PreviewView          ← CameraX 实时画面
  │     │
  │     └─ CameraViewModel.initialize(viewLifecycleOwner)
  │            │
  │            └─ CameraManager.bindUseCases()
  │                   ├── Preview       → PreviewView
  │                   ├── ImageCapture  → 拍照
  │                   └── ImageAnalysis → frameFlow
  │
  ├─ AndroidView: CompositionOverlayView ← 辅助线叠加
  │     │
  │     └─ RenderViewModel.renderInput
  │            │
  │            └─ RenderViewModel.connect(pipelineResults)
  │
  └─ Compose 控件层 (快门 / 变焦 / 闪光灯 / 切换 / 性能 HUD)
  
管线：
  CameraViewModel.frameFlow
    → PipelineViewModel.connect()
      → FramePipeline.process()
        → FrameScheduler (跳帧)
          → FramePreprocessor (YUV→Float32)
            → FakeInference (亮度→场景→辅助线)
              → PipelineResult
                → RenderViewModel (解析)
                  → CompositionOverlayView.update()
```

## 如何跑起来

### 1. 打开 Android Studio
```
File → Open → 选择 上线工程/ 目录
```

### 2. 等待 Gradle Sync
首次同步需下载依赖（CameraX / LiteRT / Compose / Hilt），约 2-5 分钟。

### 3. 连接设备
- USB 连接 Android 手机（需开启开发者模式 + USB 调试）
- 或启动模拟器（API 24+）

### 4. 点击 Run
```
Run → Run 'app'
```

### 5. 观察日志
```bash
adb logcat -s CameraLink:*
# 预期看到:
# [Manager] CameraX 初始化成功
# [Scheduler] 帧处理管线已启动
# [Pipeline] processed=1, avgInferenceMs=65
# [RenderVM] sceneType=bright, guideLines=4
```

### 6. 预期效果
- 手机上看到相机预览画面
- 画面上叠加白色三分线 + 橙色目标区域 + 水平仪
- 目标区域有脉冲呼吸动画
- 顶部 HUD 显示场景类型和帧统计
- 底部快门按钮可拍照

## 技术栈

| 模块 | 选型 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.0.21 |
| 构建 | Gradle (Kotlin DSL) | AGP 8.7.3 |
| UI | Jetpack Compose + Material3 | BOM 2024.12 |
| 架构 | MVVM + Hilt DI | ViewModel + StateFlow |
| 相机 | CameraX | 1.4.1 |
| 推理 | LiteRT (TFLite) | 2.16.1 |
| minSdk / targetSdk | 24 / 35 |

## 项目结构

```
上线工程/
├── build.gradle.kts + settings.gradle.kts
├── gradle/libs.versions.toml          # ★ 统一版本管理
├── gradle.properties + .gitignore
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/aicamera/
│       │   ├── AiCameraApp.kt         # @HiltAndroidApp
│       │   ├── MainActivity.kt        # ★ 全部串联入口
│       │   ├── camera/                # 模块一：相机链路
│       │   ├── pipeline/              # 模块二：帧处理管线
│       │   ├── overlay/               # 模块四：辅助线渲染
│       │   ├── viewmodel/             # MVVM 层
│       │   ├── di/AppModule.kt        # Hilt 依赖注入
│       │   └── ui/theme/              # Material3 主题
│       └── res/ (values + xml)
└── README.md
```

## 后续迭代

| 阶段 | 做什么 |
|------|--------|
| 队友给模型 | 实现 `IInference` → `pipeline.setInference()` |
| 队友出设计稿 | 调 `RenderConfig` 颜色/透明度 → 加新辅助线类型 |
| 性能优化 | 用 HUD 上的帧统计数据调优 |
| 上线准备 | 加 Firebase Crashlytics、多渠道打包 |

---

> 独立模块文档：[相机链路](../相机链路/README.md) | [帧处理管线](../帧处理管线/README.md) | [辅助线渲染引擎](../辅助线渲染引擎/README.md)
