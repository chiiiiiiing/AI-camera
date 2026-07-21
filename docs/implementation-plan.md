# 帧好 GenWow AI 构图相机实施清单

## 当前状态

GitHub 远端仓库仍没有可复用的 Android Gradle 工程，因此当前工作区中的 Compose/CameraX MVP 是本轮实现基线。Figma 读取已经恢复：文件 `AteKnDANl4hjp5wBaJ2uNX`、版本 `2378301108976446819`，包含 11 个 `412 × 917` Android 顶层画板。现按 `docs/plans/2026-07-21-figma-compose-refresh.md` 逐阶段实施完整 Figma 重构。

### Figma 重构阶段

- [x] 阶段 1：冻结 Gradle、测试和 APK 基线；核对 Figma 版本与 11 个画板；更新设计审查和画板清单。
- [x] 阶段 2：品牌颜色、渐变、字体、形状、Motion Token 与正式 Logo。
- [x] 阶段 3：Navigation Compose 与本地演示数据边界。
- [x] 阶段 4：启动、登录和注册页面。
- [x] 阶段 5：Figma 相机上下控制区和真实 CameraX 外壳。
- [x] 阶段 6：俯仰、偏航、滚转、平移、人像轮廓与统一动效。
- [x] 阶段 7：社区信息流和摄影师主页。
- [x] 阶段 8（合并验收）：权限/错误/拍摄/导航反馈、关键 Preview、无障碍冒烟测试和常规/小屏视觉校正一次完成。
- [x] 阶段 9（压缩后的最终交付门禁）：复用已通过的完整编译、Lint 和 JVM/UI 测试结果；只补 APK 元数据/签名、一次安装拍照回归、隐私检查和最终文档，不重复逐页校准。

### 压缩后的剩余执行（2026-07-21）

为尽快交付，原计划 Task 9–11 已完成的页面开发、可访问性和双尺寸视觉验收不再重复。剩余工作合并为一个可验证门禁：

1. 汇总现有 Gradle 报告中的 JVM/UI 测试数量、Lint 结果和 Debug/Release APK 大小。
2. 使用 `aapt` 与 `apksigner` 核对包元数据、Debug 签名及 Release 未签名状态。
3. 安装最终 Debug APK，仅回归冷启动、相机出帧、三目的地切换、拍照保存和缩略图更新。
4. 检查 Git 状态、忽略规则和敏感文件；回填视觉验证与最终交付文档后关闭模拟器。

成功标准：上述四项全部有本地证据，Debug APK 可安装，且不重新引入页面级改动。

### 最终交付门禁结果（2026-07-21）

- 合并 Gradle 门禁：Kotlin 编译、36 个 JVM 测试、Lint、20 个 Android 35 UI 测试、Debug APK 和 Release APK 均通过；0 个测试失败。
- Lint：0 error、13 warnings；其中 12 项为可用的新依赖版本提示，1 项为 Compose offset API 建议，不阻断交付。
- Debug APK：`/Users/zhusiwei/Documents/GenWow/app/build/outputs/apk/debug/app-debug.apk`，12,813,514 bytes，Android Debug v2 签名验证通过。
- Release APK：`/Users/zhusiwei/Documents/GenWow/app/build/outputs/apk/release/app-release-unsigned.apk`，8,889,699 bytes；按安全要求保持未签名，正式发布需要配置生产签名。
- APK 元数据：`com.genwow.camera`，`versionName 1.0.0`，`versionCode 1`，`minSdk 24`，`targetSdk 35`。
- 最终模拟器回归：冷启动成功；Camera → Feed → Profile → Camera 成功；CameraX 离开时关闭、返回时重新绑定；拍照后 `Pictures/GenWow` 从 6 张增至 7 张；缩略图语义更新为“最近拍摄照片”。
- 隐私检查：未发现 `.jks`、`.keystore`、PEM、P12、secret 或 password 文件；`local.properties`、构建目录、截图目录均由 `.gitignore` 排除；未执行上传、推送或线上发布。

### 阶段 1 基线结果（2026-07-21）

- Gradle：8.10.2。
- AGP：8.8.2。
- 构建 JDK：必须使用 Java 17；系统默认 Java 11 不满足 AGP 要求。阶段执行使用本机已有 Homebrew OpenJDK 17，未把个人绝对路径写入项目配置。
- 单元测试：11 个通过，0 失败，0 跳过；已使用 `--rerun-tasks` 强制重跑。
- Lint：通过，0 error，12 个依赖新版本提示；本阶段不做无关依赖升级。
- Debug APK：构建成功，`app/build/outputs/apk/debug/app-debug.apk`，11,737,256 bytes。
- 当前所有项目文件仍未被 Git 跟踪；后续使用文件清单、敏感文件扫描和 `.gitignore` 共同检查改动范围。

### 阶段 2 结果（2026-07-21）

- 正式 Logo：从 Figma 节点 `1:28` 导出 `90 × 90` SVG，并按原始 Path、透明度和渐变转换为 `ic_genwow_logo.xml`。
- 品牌实测色：深紫 `#4B3A62`、快门粉 `#F4B6C2`；Logo 渐变 `#FFCCF2 → #977DFF`。
- 颜色语义：紫/粉用于品牌；青色仅作为构图引导色，临时 `#63D8C6` 不再作为品牌主色。
- 新增静态粉紫蓝背景 Brush，不使用实时模糊。
- 新增系统 Sans Serif 字体映射；Noto Sans CJK SC 字体文件缺失仍记录为有意偏差。
- 新增统一 Shapes 和 Motion Token。
- 新增 3 个主题 Token 测试；全量 JVM 测试由 11 个增加为 14 个，全部通过。
- Kotlin 编译、Lint 和 Debug APK 打包通过；Lint 仍为 0 error、12 个依赖版本提示。
- 本阶段未修改 CameraX、拍照、权限、导航或页面结构。

### 阶段 3 结果（2026-07-21）

- 新增 Navigation Compose `2.8.5`；未引入 Hilt、Retrofit、Room 或 Coil。
- 建立 `Splash`、`Login`、`Register`、`Camera`、`Feed`、`Profile` 六个稳定路由。
- 登录成功后清除认证返回栈；相机、推荐和主页使用单一主导航边界。
- `CameraRoute` 仅存在于 Camera destination；离开页面后原有 `DisposableEffect(controller::release)` 自动释放 CameraX。
- 新增 UI 测试验证离开 Camera 时组合内容被销毁，以及登录后不能返回认证页。
- 认证 Repository 仅在内存中保存固定演示会话，不保留账号或密码参数。
- 社区 Repository 使用固定不可变演示内容，未建立网络、数据库或文件持久化。
- 阶段 3 的认证占位页已在阶段 4 替换为完整 Figma 页面；Feed/Profile 仍保留“本地演示”占位，等待阶段 7。
- JVM 测试：17 个通过；Android 35 模拟器 Compose UI 测试：7 个通过。
- Kotlin 编译、Lint、Debug APK 打包通过；Lint 为 0 error、13 个依赖版本提示。
- AndroidTest 首轮受到旧增量 dex 产物污染；执行标准 `:app:clean` 后完整重建并通过，未删除源码。

### 阶段 4 结果（2026-07-21）

- 节点 `1:2`：实现正式 Logo、帧好/GenWow 排版、粉紫蓝背景和有限入场动画。
- 节点 `1:3`：实现非 Material 默认风格的底线输入、密码可见性、辅助入口、主按钮、第三方入口和协议。
- 节点 `1:5`：实现四字段注册、密码可见性、本地验证码倒计时、主按钮和协议。
- 认证页面使用状态提升和 `AuthViewModel` 单向数据流；输入只保存在内存，不使用 `SavedStateHandle` 或持久化。
- 验证码倒计时只有一个可取消 `Job`，重复点击不会累积任务；不执行网络或短信请求。
- 登录/注册成功后立即清空 ViewModel 中的密码；注册同时清空验证码。
- 新增 `412 × 917` 与 `360 × 640` Preview；页面使用 `verticalScroll + imePadding`。
- Android 35 模拟器完成接近 `412 × 917 dp` 的登录/注册截图核对，以及 `360 × 640 dp` 小屏核对。
- 首轮背景因半透明渐变叠加深色主题而偏暗，已改为不透明浅色渐变；系统栏图标已按浅色认证页/深色相机页切换。
- Figma REST 节点读取仍受速率限制；按降级规则使用已保存原始 PNG、已确认节点和像素值完成校准。
- JVM 测试：22 个通过；Android 35 Compose UI 测试：11 个通过。
- Kotlin 编译、Lint 和 Debug APK 打包通过；Lint 为 0 error、13 个依赖版本提示。
- 阶段复核修正：登录页第三方入口已从临时“微信/QQ”文字替换为本地 Canvas 双气泡/企鹅图标；界面不显示提供方文字，无障碍描述仍明确标注“未接入”。

### 阶段 5 结果（2026-07-21）

- 节点 `1:7` / `1:9` 的相机外壳已实现为顶部黑色模式控制区、真实 CameraX 预览、底部黑色拍摄控制区；未使用 Figma 静态相机图替代预览。
- Camera destination 自己承载相机、推荐和个人主页三个图标入口，移除了相机页外部额外的文字 Bottom Bar；离开 Camera destination 仍会释放 CameraX。
- 快门改为粉色内圆与白色外环，按压缩放和对齐色由状态驱动；最近照片使用明确的本地程序化占位缩略图，切镜头保持真实 CameraX 绑定。
- 顶部咖啡、风景、人像和设置入口，以及底部相机、分享、人物入口均使用本地 Canvas 几何图标；没有 Emoji、联网图标库或第三方素材上传。
- Android 35 模拟器实测布局边界：顶部控件结束于 `y=233px`，真实预览为 `y=233..1954px`，底部控制区为 `y=1954..2400px`，在 420dpi 下约为设计要求的 `170dp`；预览四角裁切正常。
- CameraX 真实预览完成出帧；切换后使用前置 `Camera id=1`；真实 `ImageCapture` 将 `/sdcard/Pictures/GenWow` 文件数从 3 增至 4，新文件为 `GenWow_20260721_144835_532.jpg`。
- 常规尺寸视觉证据保存在本机临时文件 `/tmp/genwow-camera-shell-3.png`，未加入 Git、未上传；对照源为 `/tmp/genwow-figma-frames/1-7.png`。
- JVM 测试：22 个通过；Android 35 Compose UI 测试：12 个通过，0 失败，0 跳过。
- Kotlin 编译、Lint 和 Debug APK 打包通过；Lint 为 0 error、12 个依赖版本提示。
- 阶段 Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，12,094,274 bytes。该 APK 仍是阶段产物，不作为最终全产品交付。

### 阶段 6 结果（2026-07-21）

- 新增 `WRONG_DIRECTION`、`SUBJECT_TARGET` 和 `SUBJECT_ALIGNED` 状态；保留 Needs Adjustment、Near、Aligned 双阈值和连续帧防抖。
- 平移、Pitch、Yaw 和 Roll 使用统一 Canvas 几何语法：稳定目标框、渐隐轴线、透视椭圆弧和沿椭圆真实切线方向绘制的开放式箭头。
- 虚轴线改为跟随当前引导状态颜色，并使用中心清晰、两端平方渐隐的低干扰分段；明亮背景保留轻微暗色外描边。
- Wrong Direction 同时使用暖色、反向淡箭头、端点阻挡线和一次 `120ms` 回弹，不再只依赖橙色，也不抖动画面。
- Near 状态将引导运动幅度降为 `45%`，循环周期由 `1400ms` 延长到 `1900ms`；Aligned 状态停止循环并显示稳定完成标记。
- 相机页仍最多只有一个可取消的持续 `Animatable` 时钟；Cue、Phase、页面生命周期改变时旧任务立即取消，不会因重组累积协程。
- 人物轮廓不包含固定 SVG、Drawable 或人物 Path。新增 `SubjectOutline` / `SubjectContour` / `NormalizedPoint`，每个 AI 帧可以提供不同的归一化点集，Compose 每帧按预览尺寸重建 Path。
- `CameraRoute` 可接收生产 `Flow<GuidanceSample>`；生产流存在时完全替代 Debug 演示。AI 样本包含 cue、progress、wrongDirection 和可选 subjectOutline；流异常进入分析失败状态，取消异常正确向上传播。
- AI 轮廓输入会丢弃 NaN/Infinity 点、夹紧越界坐标并忽略少于两个有效点的轮廓；下一帧轮廓会替换上一帧，不缓存固定人物形状。
- Debug 仅使用代码中明确标注的逐帧生成轮廓样本完成视觉验证，不声称已经接入真实人体关键点或构图模型。
- Android 35 模拟器完成正常旋转、错误方向、人物目标和人物对齐状态检查；最终本地证据为 `/tmp/genwow-stage6-final-roll.png`、`/tmp/genwow-stage6-final-subject-23.png`、`/tmp/genwow-stage6-final-subject-24.png` 和 `/tmp/genwow-stage6-final-subject-29.png`，均未加入 Git 或上传。
- JVM 测试：35 个通过，0 失败；覆盖状态转换、AI 样本映射、实时轮廓替换、椭圆边界、切线、渐隐对称、Near 幅度、零尺寸和非法坐标安全。
- Android 35 Compose UI 测试：15 个通过，0 失败，0 跳过；覆盖错误方向的非颜色提示、AI 人物目标和对齐无障碍状态。
- Kotlin 编译、Lint 和 Debug APK 打包通过；Lint 为 0 error、12 个依赖版本提示。
- 阶段 Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，12,110,435 bytes。该 APK 仍是阶段产物，不作为最终全产品交付。

## 重构前 MVP 已完成能力

- [x] 检查初始仓库目录和可用文件，确认原项目输入中不存在 Android/Gradle 源码与资源。
- [x] 记录 Figma、品牌素材、模型协议和运行环境的可用性与缺口。
- [x] 完成设计审计、构图引导规范、动效状态表和临时设计令牌。
- [x] 编写可执行的分阶段实施计划与验证门槛。
- [x] 核对 GitHub 最新远端分支；确认仍无 Android 源码，并以新脚手架作为交付基线。
- [x] 恢复 Figma 读取并核对 11 个顶层画板、设计版本、正式 Logo 节点和本地 PNG 渲染。
- [x] 建立相机入口、CameraX 用例、权限流程、拍照流程和 UI 状态来源；模型协议保持可替换边界。
- [x] 建立构图引导 UI 状态模型，并为状态归一化、阈值防抖和拍摄保护添加单元测试。
- [x] 将 CameraX 控制器与 Compose 视觉层分离，提供真实预览和拍照能力。
- [x] 实现相机主屏、顶部控制、底部控制、模式选择和快门组件。
- [x] 使用 Canvas/Path 实现渐隐虚轴线、透视椭圆弧、方向箭头和完成度反馈。
- [x] 实现未识别、分析中、平移、俯仰、偏航、滚转、接近和对齐动效。
- [x] 实现快门按压、拍摄成功、权限未开启、分析失败和相机初始化失败反馈。
- [x] 添加 393×852 和 360×640 的主要状态 Preview。
- [x] 添加核心状态转换单元测试和 Compose UI 测试源码。
- [x] 在 Android 35 模拟器的常规尺寸与 360×640 dp 等效小屏运行，检查裁切、Insets、遮挡、权限页和动画状态。
- [x] 执行 Gradle 配置检查并修复问题。
- [x] 执行 Kotlin 编译并修复问题。
- [x] 执行 Lint/静态检查并修复阻断问题。
- [x] 执行单元测试并修复失败。
- [x] 编译 Compose UI 测试源码；设备执行结果待模拟器启动后回填。
- [x] 构建、签名验证并在 Android 35 模拟器安装可用 Debug APK。
- [x] 在不修改或泄露签名配置的前提下构建未签名 Release APK。
- [x] 检查 Git diff，并通过 `.gitignore` 排除密钥、缓存、local.properties、截图和构建产物。
- [x] 回填 APK 绝对路径、大小、applicationId、版本号和 SDK 配置（见最终交付报告）。

## 原始 MVP 规划记录

以下路径是 Figma 可读取之前的历史建议结构，仅用于说明 MVP 的分层思路；新的实际文件清单以 `docs/plans/2026-07-21-figma-compose-refresh.md` 为准。

- `app/src/main/java/<actual-package>/camera/CameraPreviewScreen.kt`
- `app/src/main/java/<actual-package>/camera/CameraUiState.kt`
- `app/src/main/java/<actual-package>/camera/components/CameraControls.kt`
- `app/src/main/java/<actual-package>/camera/components/CompositionGuideOverlay.kt`
- `app/src/main/java/<actual-package>/camera/components/GuidanceCanvas.kt`
- `app/src/main/java/<actual-package>/camera/components/CaptureFeedbackOverlay.kt`
- `app/src/main/java/<actual-package>/camera/CameraMotionSpec.kt`
- `app/src/main/java/<actual-package>/ui/theme/CameraTokens.kt`
- `app/src/test/java/<actual-package>/camera/CameraGuidanceReducerTest.kt`
- `app/src/androidTest/java/<actual-package>/camera/CameraPreviewScreenTest.kt`

## 状态模型边界

建议由不可变 `CameraUiState` 表达识别状态、当前主动作、误差幅度、进度、拍摄状态和错误状态；由现有 ViewModel 或等效状态持有者负责把相机/模型结果归一化为 UI 状态。Canvas 组件只接收绘制参数，不读取相机或模型对象；动画状态依据 `CameraUiState` 派生，并在状态改变或离开页面时取消。

## MVP 验证与构建命令记录

当前单模块工程使用以下命令；最终 Figma 重构完成后会重新完整执行，不能把当前结果冒充最终交付：

```bash
./gradlew tasks
./gradlew compileDebugKotlin
./gradlew lintDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
./gradlew assembleRelease
```

若无设备或模拟器，`connectedDebugAndroidTest` 应记录为未验证，不得通过删除 UI 测试掩盖；其余可离线执行的编译、Lint、单元测试和 Debug APK 构建仍必须完成。

## 当前限制

- GitHub 仓库没有 Android 原工程：当前交付是本工作区中新建并已验证的 CameraX MVP，无法声称保留不存在的旧 Android 实现。
- Figma 可读取，但没有 Code Connect/变量 API；使用 REST 节点数据、渲染图和局部导出完成核对。
- Figma 指定 Noto Sans CJK SC，但项目没有已授权字体文件；阶段 2 默认映射系统 Sans Serif并记录偏差。
- 认证、验证码、社区和真实 AI 模型协议缺失；不得虚构生产接口，只能使用明确隔离的本地演示数据。
- 终端默认 Java 11；Android 构建必须显式使用本机 JDK 17，或后续由用户调整全局开发环境。

完整的 Figma 重构执行方案见 `docs/plans/2026-07-21-figma-compose-refresh.md`。
