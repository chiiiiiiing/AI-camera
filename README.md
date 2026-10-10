# GenWow · AI 构图辅助相机

Android 离线相机：V9 ONNX 模型生成图像 embedding，通过模板检索推荐构图，再结合 IMU 与真实人脸检测提供引导。当前优化面向中端 Android 手机。

## 从哪里开始

| 工作 | 唯一入口 |
|---|---|
| Android 开发、运行与打包 | `aicamera-app/`，用 Android Studio 打开此目录 |
| 手机端 AI 推理与检索 | `aicamera-app/app/src/main/java/com/example/aicamera/pipeline/` |
| 几何引导与拍摄状态机 | `aicamera-app/app/src/main/java/com/example/aicamera/guidance/` |
| App 实际打包的模型、索引与运行配置 | `aicamera-app/app/src/main/assets/ai/v9/` |
| 数据处理、蒸馏、模型导出、量化与评估 | [ai/tools](ai/tools/README.md) |
| C++ 检索与接口原型，尚未接入 Android 主链路 | [ai/engine](ai/engine/README.md) |
| 产品与工程文档 | [docs](docs/README.md) |
| Web 界面预览与设计稿 | `ui-review/`、`designs/` |

仓库仅保留一套 Android 工程。旧 `app-arch/`、根目录 `app/`、`engine/`、`frontend/` 属于历史结构，不是当前开发入口。历史实现可从 Git 历史查阅，无需在源码树重复存放。

## 构建与验证

环境：JDK 17、Android SDK 35、Gradle 8.9（使用仓库 wrapper）、AGP 8.7.3、Kotlin 2.0.21。最低支持 Android API 24。CI 使用 JDK 17；Android Studio 的 Gradle JDK 应与此对齐。

```sh
cd aicamera-app
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows 使用 `gradlew.bat`。资产校验从仓库根目录运行，只需 Python 标准库：

```sh
python -B ai/tools/validate_android_v9_assets.py --asset-root aicamera-app/app/src/main/assets/ai/v9
```

数据工具和 C++ 回归命令分别见对应目录 README。`.github/workflows/` 自动检查 Android 单测、打包模型/索引、C++ 与模型数据契约。

## 当前能力与边界

- 相机预览、拍照、滤镜、构图工作流及已有俯仰/偏航流程保留。
- V9 默认每 500ms 最多执行一次模型采样，几何观测独立更新；镜头、旋转和生命周期切换清除旧推荐。
- 模型失败时保留手动拍照，零置信度的降级结果不能触发构图达标或自动拍摄；人像缺少真实主体也不能达标。
- 新 embedding 缓存记录模型、源图、预处理与向量 hash；模型交付重新校验实际索引，而非只读历史质量报告。
- 本次没有重训或替换打包权重。真机耗时、发热与独立照片效果评估仍需实测；人脸框不等于全身姿态。

详细变更与验收见 [AI 引擎优化记录](docs/AI引擎优化与验收-2026-10-10.md)。目录归属、私有数据及本地归档规则见 [仓库与本地目录说明](docs/仓库与本地目录说明.md)。
