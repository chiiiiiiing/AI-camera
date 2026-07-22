# 帧好 GenWow Android 原型

帧好 GenWow 是一个基于 Kotlin、Jetpack Compose 和 CameraX 的 Android AI 构图相机原型。

产品核心不是“加滤镜”，而是帮助没有摄影经验的用户在真实取景过程中快速理解：

- 手机应该往哪里平移
- 应该如何俯仰或偏航调整
- 是否需要做纸面内旋转
- 人像模式下人物应该站在哪里、摆成什么姿态

当前工程已经实现真实相机预览、拍照、构图引导、登录注册、本地社区页和个人主页，并把 AI 引导展示层与相机业务逻辑分离，方便后续接入真实模型输出。

## 用户指南（MVP 版）

### 下载安装

**方式一：直接安装 APK（推荐，无需开发环境）**

1. 在仓库 [Releases](https://github.com/chiiiiiiing/AI-camera/releases) 页面下载最新的 `app-debug.apk`
2. 将 APK 传输到手机（微信文件传输、AirDrop、USB 拷贝等方式均可）
3. 在手机上打开 APK 文件，按提示允许「安装未知来源应用」即可完成安装

> 如果 Releases 暂未发布 APK，可按下方「下载与运行 → 方式二」自行构建。

**方式二：自行构建**

参考下方「下载与运行」章节，克隆仓库后在本地用 Android Studio 或命令行构建安装。

### 快速上手

1. **启动 App** — 安装后打开，先显示品牌启动页（约 2 秒）
2. **相机取景** — 自动进入相机页，对准拍摄对象
3. **构图引导** — 画面中出现引导线/准心/人物轮廓，按提示调整手机位置
4. **拍照** — 对准后点击快门按钮，照片自动保存到系统相册
5. **浏览功能**：
   - 底部切换到「推荐」页浏览示例社区内容
   - 底部切换到「个人」页查看和编辑个人资料

### 核心功能一览

| 功能 | 说明 |
|------|------|
| 真实相机预览 | 基于 CameraX，支持前后摄切换 |
| 变焦 | 滑动变焦条，支持 0.8x / 1x / 2x |
| 构图引导 | 实时提示移动、俯仰、旋转方向 |
| 人像模式 | 两步引导：先对齐画面，再显示人物站位 |
| 登录注册 | 本地演示版，无需真实手机号 |
| 个人主页 | 编辑资料、设置（辅助线/网格/音效等） |

### 注意事项

- 当前为 MVP 原型，使用本地演示数据，不会上传账号和照片
- 构图引导为模拟展示层，不依赖真实 AI 模型推理
- 建议在 Android 10+ 设备上运行以获得最佳体验

## 2026-07-21 最新补充

这一轮按 App 队友的交付清单，已经把原型里缺失但必须可运行的 UI 项补齐：

- 登录页加入品牌 Logo 头图与密码显隐眼睛图标
- 新增忘记密码页，本地演示可闭环，不接入真实短信/邮件
- 个人主页右上角设置按钮进入真实设置页
- 个人主页补上编辑资料入口，并新增本地可运行的编辑资料页
- 设置页支持辅助线、网格、原图保存、拍照音效等分组设置
- 社区卡片补上评论信息，并增加统一空状态
- 补齐 Android launcher / adaptive icon 基线资源
- 相机页补上真实可切换的 compact 变焦条
- 相机引导图示、顶部/底部 icon、提示条和完成态又做了一轮视觉收口

## 当前产品流

截至 2026-07-21，本地原型的主流程如下：

1. 启动 App 后先显示带 Logo 的初始页
2. 初始页停留约 2 秒后自动进入相机页
3. 用户可以直接使用相机、切换模式、查看推荐页
4. 当用户点击“个人主页”时：
   - 若未登录，进入登录/注册流程
   - 若已登录，直接进入个人主页

这套流转更符合“相机为主入口”的产品定位，而不是把登录作为冷启动的第一阻碍。

## 已实现页面

- 启动页
- 登录页
- 注册页
- 忘记密码页
- 相机主页
- 推荐信息流
- 个人主页
- 编辑资料页
- 设置页
- 权限页 / 错误页

## 相机能力

### 真实相机

- 基于 CameraX 进行真实预览
- 支持拍照
- 拍摄后写入系统相册
- 左下角最近照片缩略图会刷新
- 支持前后摄切换
- 支持紧凑型变焦档位切换（当前提供 0.8x / 1x / 2x 的安全预设）

### 构图引导

当前引导层已经拆成明确的视觉语义：

- `MOVE_*`
  - 表示整体平移
  - 使用“准心 + 目标圈/太阳 + 连线”表达

- `PITCH_* / YAW_*`
  - 表示绕轴俯仰 / 偏航调整
  - 使用“渐隐轴线 + 透视椭圆弧 + 路径箭头”表达

- `ROLL_*`
  - 表示纸面内旋转
  - 使用“手机轮廓 + 外圈旋转弧箭头”表达

- `SUBJECT_*`
  - 表示人像站位与姿态引导
  - 使用 AI 输出的人物轮廓点集重建目标轮廓

### 人像模式两阶段引导

人像模式不是一上来就压一个人物轮廓，而是分两步：

1. 先用“太阳 + 准心”做整体画面对齐
2. 第一阶段对齐后，再展示人物目标轮廓

当前实现中，人物目标轮廓一旦出现，会锁定为静态目标，不会跟着后续轻微抖动来回漂移。等用户再次对准后，再切换到完成状态颜色。

## AI 接入边界

当前工程已经为真实 AI 输出预留了结构化边界，重点类型包括：

- `GuidanceSample`
- `GuidanceCue`
- `GuidanceAnchors`
- `SubjectOutline`
- `SubjectContour`
- `NormalizedPoint`

这意味着后续如果 AI 团队提供真实帧级输出流，只需要替换相机路由层的数据输入，不需要重写 Overlay 组件。

## 主要技术栈

- Kotlin
- Jetpack Compose
- Material 3 基础能力
- CameraX
- Navigation Compose
- JUnit4
- Compose UI Test

## 本地构建环境

- JDK 17
- Gradle 8.10.2
- Android Gradle Plugin 8.8.2
- compileSdk 35
- targetSdk 35
- minSdk 24

## 下载与运行

### 方式一：克隆后用 Android Studio 运行（推荐）

```bash
git clone https://github.com/chiiiiiiing/AI-camera.git
cd AI-camera
git checkout UI
```

然后用 Android Studio 打开 `AI-camera` 目录，等待 Gradle 同步完成后，连接手机或启动模拟器，点击 Run 即可。

### 方式二：直接安装 APK

从 GitHub 克隆后在本地构建 Debug APK，发送到手机直接安装：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
# APK 路径：app/build/outputs/apk/debug/app-debug.apk
```

## 构建与校验

```bash
# 构建 Debug APK
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug

# 编译检查
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew compileDebugKotlin

# 运行单元测试
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest

# 编译 Android 测试
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew compileDebugAndroidTestKotlin

# Lint 检查
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew lintDebug

# 如果本机有可用模拟器，可执行 UI 测试
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew connectedDebugAndroidTest
```

## APK 输出

Debug APK：

- `app/build/outputs/apk/debug/app-debug.apk`

未签名 Release APK：

- `app/build/outputs/apk/release/app-release-unsigned.apk`

说明：

- Debug APK 可直接用于本地安装和演示
- Release APK 当前保持未签名状态
- 正式发布前仍需要安全配置生产签名

## 隐私与安全说明

本地原型当前遵循以下边界：

- 登录与注册仅使用本地演示数据
- 不会把输入账号和密码上传到远端
- 不会生成或提交 keystore、密码或生产签名
- 不会把截图、构建缓存、`local.properties` 等本地文件纳入版本控制

## 重要文档

- [设计审查](docs/design-audit.md)
- [实施清单](docs/implementation-plan.md)
- [设计侧待办](docs/design-todo.md)
- [视觉验收记录](docs/figma/visual-validation.md)
- [UI 交付清单调整计划](docs/ui-design-adjustment-plan.md)
- [给 UI 设计队友的校正版交付清单](docs/ui-design-handoff-adjusted.md)
- [GitHub 上传清单](docs/github-upload-checklist.md)
- [截图补拍清单](docs/screenshots/补拍清单.txt)

## 当前边界说明

这个仓库当前交付的是可运行的 Android 产品原型，不包含：

- 生产后端
- 生产级短信验证码能力
- 真实线上账号系统
- 生产级 AI 推理服务
- 正式签名发布配置

如果后续继续接入真实 AI、账号系统或后端接口，建议优先复用当前已经建立好的：

- 导航流
- 相机壳层
- Overlay 语义映射层
- 轮廓数据结构
- 本地测试与构建流程
