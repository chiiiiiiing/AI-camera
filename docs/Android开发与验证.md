# Android 开发与验证

核对日期：2026-10-16。本文合并旧相机、帧处理、性能和上线教程中的有效入口及检查项；实现以当前代码为准，不再提供与源码分叉的示例状态机。

## 工程与构建

唯一 Android 工程为 `aicamera-app/`。JDK 17、SDK 35、Gradle wrapper 及版本说明见[项目 README](../README.md)。不要恢复历史 `app-arch/` 或另建第二套相机工程。

从该目录执行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows 使用 `gradlew.bat`。SDK 路径放本地配置，不提交密钥或绝对路径。新环境、缓存与实验产物遵守[目录说明](./仓库与本地目录说明.md)，保留在原项目内。

## 修改入口

以下源码路径相对 `aicamera-app/app/src/main/java/com/example/aicamera/`：

| 部分 | 入口 | 需要保持的约束 |
|---|---|---|
| 相机绑定、预览与画幅 | `camera/CameraManager.kt` | Preview、Capture、Analysis 使用一致的 ViewPort |
| 对焦曝光及拍摄 | `camera/CapturePreparation.kt`、`camera/CameraCapture.kt` | 异步、可取消；自动拍摄准备后重新检查 Ready |
| 调度和预处理 | `pipeline/FrameScheduler.kt`、`FramePipeline.kt`、`FramePreprocessor.kt` | 及时关闭 ImageProxy；模型采样与几何观测分开，不能堵塞预览 |
| 模型与检索 | `pipeline/V9Inference.kt` 等 | 模型和索引成套更新；见 AI 接入说明 |
| 观测桥接 | `viewmodel/RenderViewModel.kt` | 时间、观测 ID、主体及坐标版本一致；重复缓存不是新证据 |
| 最终动作与 Ready | `guidance/CompositionSessionController.kt` | 保留单一最终决策源，UI 不另建完成判断 |
| 实际引导覆盖层 | `ui/components/CaptureWorkflowOverlay.kt` | 遵从控制器；不要改未挂载旧组件后当作页面已更新 |
| 页面与拍摄工作流 | `ui/screen/CameraScreen.kt` | 保留手动拍摄回退；切摄像头和生命周期变化撤销旧依据 |

模型规格及资产校验见[AI 接入包说明](./09-AI接入包说明.md)。动作、镜像、执行反馈及已知 UI 接线问题统一见[引导动画制作与接入规范](./引导动画制作与接入规范.md)。

## 自动验证

| 变更 | 检查 |
|---|---|
| Kotlin 纯逻辑、控制器或坐标 | 对应 JVM 单测；修改跨层链路时运行完整 `:app:testDebugUnitTest` |
| Android 接线、依赖或资源 | `:app:assembleDebug`；必要时检查 Release 构建及混淆行为 |
| 模型、参考索引或侧包 | 根目录执行 `python -B ai/tools/validate_android_v9_assets.py --asset-root aicamera-app/app/src/main/assets/ai/v9` |
| 数据处理或训练脚本 | [AI 工具测试](../ai/tools/README.md) |
| C++ 原型 | [原生引擎构建与测试](../ai/engine/README.md) |
| 文档 | 相对链接、源文件引用、JSON 示例和过时术语检查，无需重复模型训练 |

现有 CI 定义见 [.github/workflows](../.github/workflows/)。工作流有路径过滤；仅改文档不代表 Android CI 一定会触发。CI／桌面测试通过不能替代下面的真机验证。

## 性能与设备验证范围

默认模型采样至少间隔 500ms，慢推理可降到 1000ms；不是旧文档中的每秒 5～15 次 embedding。不能为追求流畅随意改变输入尺寸、归一化或模型精度。

验证需记录设备、Android 版本、构建提交、模型 hash、镜头和画幅，并区分桌面、模拟器与真机数据：

- 推理 p50/p90、预览掉帧、启动至可拍摄耗时、内存和长时内存增长。
- 连续预览 10～30 分钟的温度、降频、耗电及恢复情况。
- 前后摄、四种显示旋转、不同画幅、切后台和重新进入。
- 弱光、亮背景、运动主体、多人、主体丢失、缓存和传感器过期。
- Ready 后 AF/AE 期间画面改变，确认不会使用过期依据自动拍摄。
- 字体缩放、读屏、暂停动效、触控区域和提示遮挡。

p50 ≤80ms、p90 ≤150ms 是当前检索模型预算，尚不能标为真机已达成。引导后照片是否更好看需独立审美评估；几何误差下降不等于用户偏好提升。

## 发布前检查

这是未完成项的检查入口，不是上线许可或已验收声明：

- 相机权限拒绝、再次授权、不可用镜头、无闪光灯及保存失败的回退。
- 照片真实保存、系统相册可见性及设备对应的权限行为。
- Release 签名、版本、混淆后的模型加载与拍摄链路。
- 模型失败时手动拍照可用，禁用的评分头不被自动开启。
- 认证和社区区分本地演示与生产服务；固定验证码和演示账号不能成为生产认证。
- 当前没有模型 OTA 发布服务；不能把旧 Firebase 下载伪代码当已实现能力。离线资产跟随版本交付。
- 当前引导的已知接线缺口见动画规范第 12 节，修复后按其联调清单验收。

旧阶段计划和审查对话可从 Git 历史追溯，例如先运行 `git log --all -- docs/`；需要某篇旧文件时查看删除前的提交，不要整批恢复旧文档覆盖当前规格。
