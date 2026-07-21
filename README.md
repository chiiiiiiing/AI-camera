# GenWow

GenWow 是一款 Android AI 构图相机原型，基于 Kotlin、Jetpack Compose 和 CameraX 构建。

应用以相机预览为核心体验，通过轻量级叠加层、动感提示和构图进度反馈，在拍摄前引导用户到达推荐的取景位置。

## 项目范围

本仓库包含 GenWow 原型的本地产品工作：

- 基于 Figma 的视觉刷新：闪屏、登录、相机、动态和个人资料流程
- 基于 Compose 的相机外壳和交互组件
- CameraX 预览、拍摄、保存到相册和缩略图刷新
- AI 引导叠加层架构：俯仰、偏航、横滚、平移和主体轮廓输入
- 动效系统、预览、单元测试、UI 测试和构建验证
- 设计审查、实施计划和视觉验证说明

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3 基础 + 自定义视觉令牌
- CameraX
- Navigation Compose
- JUnit4
- Compose UI Test

## 当前应用覆盖

- 闪屏
- 基于本地 Demo 认证状态的登录和注册流程
- 相机屏幕（含真实 CameraX 预览和图像拍摄）
- 构图引导叠加层和动画反馈状态
- 动态和个人资料 Demo 页面（使用本地示例数据）
- 权限和错误状态

## AI 引导集成边界

UI 已准备好通过相机路由层接收实时 AI 引导帧。

主要模型面向类型：

- `GuidanceSample`
- `SubjectOutline`
- `SubjectContour`
- `NormalizedPoint`

项目保持生产级 AI 集成的隔离性。后续提供真实模型流时，可替换本地 Demo 引导流程，无需重写叠加层组件。

## 构建环境

- JDK 17
- Gradle 8.10.2
- Android Gradle Plugin 8.8.2
- `compileSdk 35`
- `targetSdk 35`
- `minSdk 24`

## 本地运行

```bash
./gradlew assembleDebug
```

常用验证命令：

```bash
./gradlew compileDebugKotlin
./gradlew lint
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
./gradlew assembleRelease
```

## APK 输出

Debug APK：

- `app/build/outputs/apk/debug/app-debug.apk`

未签名 Release APK：

- `app/build/outputs/apk/release/app-release-unsigned.apk`

Release 构建产物故意不签名。生产签名需单独配置，不应提交到版本控制。

## 仓库规范

项目已将本地专属和敏感文件排除在 Git 外：

- `local.properties`
- `.gradle/`
- `.kotlin/`
- `build/`
- `**/build/`
- `.idea/`
- `captures/`
- `docs/screenshots/`
- `*.jks`
- `*.keystore`

## 关键文档

- [设计审查](docs/design-audit.md)
- [实施计划](docs/implementation-plan.md)
- [Figma 视觉验证](docs/figma/visual-validation.md)
- [GitHub 上传清单](docs/github-upload-checklist.md)

## 备注

- 动态、个人资料、登录、注册和验证码使用明确隔离的本地 Demo 数据
- 应用不包含生产后端、生产 AI 模型或发布签名材料
- 截图和构建产物保留在本地，除非有意导出到其他地方
