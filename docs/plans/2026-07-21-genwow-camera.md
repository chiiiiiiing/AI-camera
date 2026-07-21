# GenWow AI Composition Camera Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在保留既有相机预览、拍照、模式和模型协议的前提下，为 GenWow 实现可运行、可测试、状态驱动的 Jetpack Compose 构图引导界面与动效，并成功构建可安装 Debug APK。

**Architecture:** 先把现有相机/分析结果适配为不可变的单向 `CameraUiState`，再让 Compose 层按状态渲染控制区、Canvas 构图覆盖层和可取消动画。相机业务、拍照和模型协议保持在既有层中，视觉组件仅消费归一化参数；所有新路径和包名必须在 Android 工程到位后按实际结构替换，不能照搬占位路径。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3、Compose Canvas/Path、现有 CameraX/状态管理/依赖注入方案（待源码确认）、JUnit、Compose UI Test、Gradle Android Plugin。

---

## 执行前提与停止条件

初始审计时原始仓库没有用户提供的 Android 源码、Gradle Wrapper、品牌资源或模型协议，且 Figma MCP 不可用。分布式执行期间新建的脚手架不代表已经获得原项目。此计划可以用于后续执行，但任何声称“保留既有相机业务”的代码任务开始前必须先完成 Task 1；如果实际 Android 工程仍未同步，应如实报告输入缺口，不把静态假相机项目描述成对原有业务的优化。

计划中的 `<actual-package>`、`app` 模块名、测试任务和行号均需在源码审计后替换为真实值。提交步骤是建议检查点；只有用户明确授权创建提交时才执行 `git commit`。

### Task 1: 接入并盘点实际 Android 工程

**Files:**

- Inspect: `settings.gradle.kts` or `settings.gradle`
- Inspect: `build.gradle.kts` or `build.gradle`
- Inspect: `gradle/libs.versions.toml`
- Inspect: `<actual-app-module>/build.gradle.kts`
- Inspect: `<actual-app-module>/src/main/AndroidManifest.xml`
- Modify: `docs/design-audit.md`
- Modify: `docs/implementation-plan.md`

**Step 1: 确认工程文件已同步**

Run:

```bash
rg --files -g 'settings.gradle*' -g 'build.gradle*' -g 'gradlew' -g 'AndroidManifest.xml'
```

Expected: 至少出现 Gradle settings、Wrapper、应用模块构建文件和 Manifest。若仍为空，停止实现并报告缺少项目输入。

**Step 2: 识别模块和版本配置**

Run:

```bash
./gradlew projects
./gradlew tasks
```

Expected: `BUILD SUCCESSFUL`，输出真实应用模块和可用的 compile、lint、test、connected test、assemble 任务。

**Step 3: 搜索现有架构和相机入口**

Run:

```bash
rg -n "CameraX|PreviewView|ProcessCameraProvider|ImageAnalysis|ImageCapture|@Composable|NavHost|ViewModel|Hilt|Koin" .
```

Expected: 定位相机预览、拍照、分析、页面入口、状态持有者和依赖注入实现；没有命中项时记录实际替代实现。

**Step 4: 记录基线配置**

从实际 Gradle 文件回填 applicationId、versionName、versionCode、minSdk、targetSdk、Compose 版本和已有测试依赖到 `docs/implementation-plan.md`，并修正本计划中的模块名与包路径。

**Step 5: 运行未修改代码的基线检查**

Run（按 Task 2 发现的真实任务名调整）：

```bash
./gradlew compileDebugKotlin lintDebug testDebugUnitTest assembleDebug
```

Expected: 所有任务通过；若失败，保存完整错误并在文档中区分基线问题与后续改动问题。

**Step 6: 更新任务状态**

在 `docs/implementation-plan.md` 中仅勾选真实完成的盘点和基线检查项，不提前勾选实现或构建。

**Step 7: Commit（仅在获授权时）**

```bash
git add docs/design-audit.md docs/implementation-plan.md docs/plans/2026-07-21-genwow-camera.md
git commit -m "docs: record GenWow camera implementation baseline"
```

### Task 2: 获取并核验设计与品牌输入

**Files:**

- Modify: `docs/design-audit.md`
- Create or reuse: `<actual-app-module>/src/main/res/drawable/` exported assets only when needed
- Create or reuse: `<actual-app-module>/src/main/res/font/` licensed fonts only when provided

**Step 1: 获取设计证据**

使用可用的 Figma 工具读取文件 `AteKnDANl4hjp5wBaJ2uNX` 的节点 `0:1`；如果工具仍不可用，请用户提供关键页面 PNG、SVG 导出、设计变量和字体，不通过网页截图猜测不可见细节。

**Step 2: 列出关键页面与状态**

至少核验默认相机、分析中、四向平移、俯仰、偏航、顺/逆时针旋转、接近、对齐、权限和错误状态。将实际存在与缺失的状态更新到 `docs/design-audit.md`。

**Step 3: 提取设计令牌**

记录真实品牌强调色、完成色、警示色、字体、字号、间距、圆角、图标尺寸和安全区；用真实值替换审计中的临时建议，并删除不再需要的 `TODO(brand)`。

**Step 4: 只导入必要资源**

优先用 Compose Canvas/Path 绘制几何引导。仅对品牌 Logo、不可程序化图形和已授权字体使用导出资源；确保没有重复分辨率或无用大图。

**Step 5: 验证资源**

Run:

```bash
./gradlew :<actual-app-module>:processDebugResources
```

Expected: `BUILD SUCCESSFUL`，没有缺失资源、重复名称或非法 SVG 属性错误。

### Task 3: 用测试定义构图 UI 状态和主动作选择

**Files:**

- Create or Modify: `<actual-app-module>/src/main/java/<actual-package>/camera/CameraUiState.kt`
- Create or Modify: `<actual-app-module>/src/main/java/<actual-package>/camera/CameraGuidanceReducer.kt`
- Test: `<actual-app-module>/src/test/java/<actual-package>/camera/CameraGuidanceReducerTest.kt`

**Step 1: 写失败测试——状态映射**

为现有模型协议中的真实输入类型建立测试，覆盖：无主体、分析中、左/右/上/下平移、俯仰、偏航、滚转、接近、对齐、识别失败。不要发明生产协议；若协议没有某状态，适配现有字段并在测试夹具中表达。

建议目标形态：

```kotlin
sealed interface GuidanceAction {
    data object None : GuidanceAction
    data class Translate(val x: Float, val y: Float) : GuidanceAction
    data class Pitch(val degrees: Float) : GuidanceAction
    data class Yaw(val degrees: Float) : GuidanceAction
    data class Roll(val degrees: Float) : GuidanceAction
}

enum class GuidancePhase { UNDETECTED, ANALYZING, ADJUSTING, APPROACHING, ALIGNED, FAILED }
```

测试应断言动作类型、方向、归一化强度、阶段和可见文案键，而不是动画帧。

**Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :<actual-app-module>:testDebugUnitTest --tests '*CameraGuidanceReducerTest'
```

Expected: FAIL，原因是状态模型或 reducer 尚未实现，而不是测试环境配置错误。

**Step 3: 实现最小不可变状态模型**

使用 `data class`、`sealed interface` 和不可变字段。`CameraUiState` 只包含渲染所需数据，如 `phase`、`primaryAction`、`progress`、`captureState`、`error` 和主体安全区；相机控制器、Bitmap、PreviewView 等对象不得塞入 UI 状态。

**Step 4: 实现主动作选择**

从真实模型输出计算归一化误差，并选择唯一主动作。阈值应来自现有业务常量或经产品确认的配置，不在 Composable 内散落魔法数字。

**Step 5: 运行测试确认通过**

Run:

```bash
./gradlew :<actual-app-module>:testDebugUnitTest --tests '*CameraGuidanceReducerTest'
```

Expected: PASS，覆盖所有现有状态转换和边界阈值。

**Step 6: Commit（仅在获授权时）**

```bash
git add <actual-app-module>/src/main/java/<actual-package>/camera/CameraUiState.kt <actual-app-module>/src/main/java/<actual-package>/camera/CameraGuidanceReducer.kt <actual-app-module>/src/test/java/<actual-package>/camera/CameraGuidanceReducerTest.kt
git commit -m "feat: model camera composition guidance states"
```

### Task 4: 建立相机视觉令牌与动画规范

**Files:**

- Create or Modify: `<actual-app-module>/src/main/java/<actual-package>/ui/theme/CameraTokens.kt`
- Create: `<actual-app-module>/src/main/java/<actual-package>/camera/CameraMotionSpec.kt`
- Test: `<actual-app-module>/src/test/java/<actual-package>/camera/CameraMotionSpecTest.kt`

**Step 1: 写失败测试——阶段到动画参数映射**

测试 `ADJUSTING`、`APPROACHING` 和 `ALIGNED` 的移动幅度、时长、透明度与循环策略不同，并断言接近态幅度小于调整态、对齐态不循环。

**Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :<actual-app-module>:testDebugUnitTest --tests '*CameraMotionSpecTest'
```

Expected: FAIL，因为动画规范尚未建立。

**Step 3: 集中定义令牌**

把颜色、线宽、控制尺寸、文字样式、间距、透明度、动效时长与缓动集中管理。复用现有主题令牌；只补充相机专属值，不重写整个应用主题。

**Step 4: 定义纯数据动画规范**

`CameraMotionSpec` 输出 `durationMillis`、`travelFraction`、`highlightLength`、`alpha` 和 `shouldLoop` 等纯数据。关键常量旁添加短注释，说明它表达“持续移动”“接近时减速”或“完成后收束”的交互意义。

**Step 5: 运行测试确认通过**

Run:

```bash
./gradlew :<actual-app-module>:testDebugUnitTest --tests '*CameraMotionSpecTest'
```

Expected: PASS。

### Task 5: 用 Canvas 实现渐隐虚轴线

**Files:**

- Create: `<actual-app-module>/src/main/java/<actual-package>/camera/components/FadingAxisLine.kt`
- Test: `<actual-app-module>/src/androidTest/java/<actual-package>/camera/FadingAxisLineTest.kt`

**Step 1: 写失败的 Compose 语义测试**

为组件增加稳定的测试标签，断言在旋转引导状态可见、在 `None` 状态不存在；不尝试用语义测试断言每个像素。

**Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :<actual-app-module>:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<actual-package>.camera.FadingAxisLineTest
```

Expected: FAIL，因为组件不存在。没有设备时先完成编译，并把运行验证保持为待办。

**Step 3: 实现最小 Canvas 组件**

参数至少包括轴向、中心、长度、线宽、前景色、描边色、透明度和主体避让区域。使用 `drawLine`/Path 分段和透明度插值形成中心略清晰、两端渐隐的细线；虚轴保持稳定，不参与路径流动动画。

**Step 4: 添加 Preview**

添加明亮背景、暗色背景和复杂背景占位 Preview，使用程序生成或项目已有样图；不把 Preview 假图接入生产相机页面。

**Step 5: 编译并运行测试**

Run:

```bash
./gradlew :<actual-app-module>:compileDebugKotlin :<actual-app-module>:connectedDebugAndroidTest
```

Expected: Kotlin 编译成功；有设备时测试 PASS。

### Task 6: 实现透视弧与连续方向箭头

**Files:**

- Create: `<actual-app-module>/src/main/java/<actual-package>/camera/components/PerspectiveArcIndicator.kt`
- Create: `<actual-app-module>/src/main/java/<actual-package>/camera/components/RotationDirectionArrow.kt`
- Test: `<actual-app-module>/src/androidTest/java/<actual-package>/camera/PerspectiveArcIndicatorTest.kt`

**Step 1: 写失败测试——方向与状态语义**

分别渲染正/负 Pitch、Yaw、Roll，断言组件暴露正确方向描述和状态标签。颜色不作为唯一测试依据。

**Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :<actual-app-module>:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<actual-package>.camera.PerspectiveArcIndicatorTest
```

Expected: FAIL，因为组件尚未实现。

**Step 3: 创建共享椭圆路径**

根据动作类型计算椭圆矩形、起止角和近/远侧分段；使用压缩比例表达透视。近侧弧段较清晰，远侧弧段降低透明度，避免完整等权圆环。

**Step 4: 让箭头沿相同 Path 取切线方向**

使用 PathMeasure 或等效几何计算位置和切线，让短折线箭头头部与弧线连续。箭头线宽只略高于弧线，不使用实心粗三角。

**Step 5: 添加可取消的路径高亮动画**

动画 key 至少包含动作类型、方向和阶段；状态改变后旧动画应取消并从新状态平滑过渡。调整态允许单一低频循环，接近态减速减幅，对齐态停止循环并执行一次收束。

**Step 6: 编译和运行测试**

Run:

```bash
./gradlew :<actual-app-module>:compileDebugKotlin :<actual-app-module>:connectedDebugAndroidTest
```

Expected: 编译成功；有设备时全部相关测试 PASS，切换状态后没有残留的旧方向语义节点。

### Task 7: 实现平移、进度与引导文案

**Files:**

- Create: `<actual-app-module>/src/main/java/<actual-package>/camera/components/TranslationIndicator.kt`
- Create: `<actual-app-module>/src/main/java/<actual-package>/camera/components/CompositionProgressIndicator.kt`
- Create: `<actual-app-module>/src/main/java/<actual-package>/camera/components/GuidanceMessage.kt`
- Test: `<actual-app-module>/src/androidTest/java/<actual-package>/camera/GuidanceComponentsTest.kt`

**Step 1: 写失败测试**

断言左/右/上/下方向各自具有可区分的内容描述；未识别态不显示虚假百分比；接近态显示较稳定的进度形态；对齐态显示确认图形和短文案。

**Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :<actual-app-module>:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<actual-package>.camera.GuidanceComponentsTest
```

Expected: FAIL，因为组件尚未实现。

**Step 3: 实现平移箭头**

使用短线性轨迹和几何箭头，方向由位置、朝向和语义共同表达。只在 `Translate` 为主动作时循环，接近目标后缩短行程并降低速度。

**Step 4: 实现完成度和文案**

完成度使用图形稳定度、开放程度和颜色组合，不用大号百分比。文案保持单行短句，并为屏幕阅读器提供完整状态描述。

**Step 5: 运行测试确认通过**

Run:

```bash
./gradlew :<actual-app-module>:connectedDebugAndroidTest
```

Expected: 相关测试 PASS。

### Task 8: 组装构图覆盖层并验证动画生命周期

**Files:**

- Create: `<actual-app-module>/src/main/java/<actual-package>/camera/components/CompositionGuideOverlay.kt`
- Test: `<actual-app-module>/src/androidTest/java/<actual-package>/camera/CompositionGuideOverlayTest.kt`

**Step 1: 写失败测试——一次只显示一个主动作**

对每个 `CameraUiState` 断言最多存在一个主方向指示器；切换到 `ALIGNED` 后旧的平移或旋转指示器消失。

**Step 2: 写失败测试——状态切换**

使用 Compose test clock 推进时间，验证 `ADJUSTING → APPROACHING → ALIGNED` 时移动幅度和循环状态发生变化；退出组合后没有继续推进的动画任务或异常。

**Step 3: 实现覆盖层**

使用单一入口按状态组合目标框、轴线、主动作、进度和文案。主体安全区作为参数传入；没有主体区域数据时使用经过设计确认的安全区，不在 Canvas 内读取模型。

**Step 4: 尊重系统动画缩放**

优先使用 Compose 动画基础设施，让系统动画缩放设置自然生效。不要创建全局无限协程；任何 `LaunchedEffect` 必须以状态 key 驱动并可取消。

**Step 5: 运行测试**

Run:

```bash
./gradlew :<actual-app-module>:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<actual-package>.camera.CompositionGuideOverlayTest
```

Expected: PASS，状态切换后只有当前主动作可见。

### Task 9: 优化顶部、底部、模式与快门控制

**Files:**

- Create or Modify: `<actual-app-module>/src/main/java/<actual-package>/camera/components/CameraTopControls.kt`
- Create or Modify: `<actual-app-module>/src/main/java/<actual-package>/camera/components/CameraBottomControls.kt`
- Create or Modify: `<actual-app-module>/src/main/java/<actual-package>/camera/components/ModeSelector.kt`
- Create or Modify: `<actual-app-module>/src/main/java/<actual-package>/camera/components/CaptureButton.kt`
- Test: `<actual-app-module>/src/androidTest/java/<actual-package>/camera/CameraControlsTest.kt`

**Step 1: 写失败测试**

断言所有可点击控制具有可访问标签，快门与核心控制的触控区域至少 `48 dp`，模式切换回调只在选择改变时触发，拍摄处理中快门不会重复提交。

**Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :<actual-app-module>:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<actual-package>.camera.CameraControlsTest
```

Expected: FAIL，指出待实现或现有控件不满足的行为。

**Step 3: 保留原有功能入口并优化外观**

沿用 Figma 和现有代码中的入口、顺序和回调。应用 Window Insets，避免固定系统栏高度；使用局部低透明度底色和细描边，不增加底部导航、卡片或无关按钮。

**Step 4: 实现模式切换动效**

选中项通过位置、字重和短距离指示器过渡表达；动画由选中模式驱动，可中断，不为每个模式创建独立无限动画。

**Step 5: 实现快门按压反馈**

按下时快门环轻微收缩，释放后回弹；拍摄中进入明确的不可重复触发状态。保留既有 `onCapture` 业务回调，不在 Composable 内执行文件 I/O。

**Step 6: 运行测试确认通过**

Run:

```bash
./gradlew :<actual-app-module>:connectedDebugAndroidTest
```

Expected: 控制测试 PASS。

### Task 10: 实现拍摄、权限与错误反馈

**Files:**

- Create or Modify: `<actual-app-module>/src/main/java/<actual-package>/camera/components/CaptureFeedbackOverlay.kt`
- Create or Modify: `<actual-app-module>/src/main/java/<actual-package>/camera/PermissionStateScreen.kt`
- Create or Modify: `<actual-app-module>/src/main/java/<actual-package>/camera/ErrorStateScreen.kt`
- Test: `<actual-app-module>/src/androidTest/java/<actual-package>/camera/CameraStateScreensTest.kt`

**Step 1: 写失败测试**

覆盖权限未请求、被拒绝、永久拒绝、相机初始化失败、分析失败、拍摄中和拍摄成功；断言主要操作回调与文案正确，分析失败时按现有业务策略保留可用的预览和拍照能力。

**Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :<actual-app-module>:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<actual-package>.camera.CameraStateScreensTest
```

Expected: FAIL，因为状态页面或反馈尚未完成。

**Step 3: 实现一次性拍摄反馈**

拍摄成功使用短暂边缘闪光或缩略图淡入，不使用长时间全屏白屏。事件由状态持有者消费或通过带标识的一次性状态表达，避免重组重复播放。

**Step 4: 区分权限与错误**

权限页解释相机用途并提供授权/设置操作；初始化失败提供重试；分析失败使用短提示且不默认阻断拍照。所有文案使用字符串资源。

**Step 5: 运行测试确认通过**

Run:

```bash
./gradlew :<actual-app-module>:connectedDebugAndroidTest
```

Expected: 状态页面测试 PASS。

### Task 11: 接入主相机页面而不改写业务逻辑

**Files:**

- Modify: `<actual-existing-camera-screen-path>`
- Modify: `<actual-existing-camera-viewmodel-or-state-holder-path>` only if an adapter is required
- Create or Modify: `<actual-app-module>/src/main/java/<actual-package>/camera/CameraPreviewScreen.kt`
- Test: `<actual-app-module>/src/androidTest/java/<actual-package>/camera/CameraPreviewScreenTest.kt`

**Step 1: 写失败的主流程测试**

使用 fake 状态持有者测试：页面显示相机预览容器、模式、快门和构图覆盖层；点击快门只调用现有拍照入口一次；切换模式调用现有模式入口；错误或权限状态正确替换/覆盖对应区域。

**Step 2: 运行测试确认失败**

Run:

```bash
./gradlew :<actual-app-module>:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<actual-package>.camera.CameraPreviewScreenTest
```

Expected: FAIL，显示尚未接入的 UI 或回调。

**Step 3: 最小化接入**

在现有预览之上叠加 `CompositionGuideOverlay`，在安全区放置控制层。复用既有生命周期、权限、CameraX 用例和拍照回调；不要重新绑定相机或复制 ViewModel。

**Step 4: 隔离 Preview 数据**

为主要状态创建 PreviewProvider 或不可变样例列表。Preview 使用 fake 状态和占位背景，不初始化 CameraX、模型或文件系统。

**Step 5: 运行测试和编译**

Run:

```bash
./gradlew :<actual-app-module>:compileDebugKotlin :<actual-app-module>:testDebugUnitTest :<actual-app-module>:connectedDebugAndroidTest
```

Expected: 编译、单元测试和有设备时的 UI 测试全部通过。

**Step 6: Commit（仅在获授权时）**

```bash
git add <actual-app-module>/src/main/java/<actual-package>/camera <actual-app-module>/src/androidTest/java/<actual-package>/camera <actual-app-module>/src/test/java/<actual-package>/camera
git commit -m "feat: implement GenWow composition camera UI"
```

### Task 12: 视觉验证与可访问性检查

**Files:**

- Modify as needed: only camera UI files changed in Tasks 4–11
- Modify: `docs/implementation-plan.md`

**Step 1: 检查可用设备**

Run:

```bash
adb devices -l
```

Expected: 至少一个在线设备或模拟器。若没有，记录设备验证未完成，并继续 Preview、编译和 APK 构建。

**Step 2: 在小屏设备运行**

选择接近 `360 × 640 dp` 的设备，启动主相机页面。检查系统栏、底部手势区、模式拥挤、文案裁切、快门触控区和主体遮挡。

**Step 3: 在常规尺寸运行**

选择接近 `393 × 852 dp` 的设备，检查视觉层级、顶部/底部平衡、中央安全区和所有动作方向。

**Step 4: 检查背景可见性**

至少使用明亮天空、暗色室内、复杂街景和人物面部四类画面，观察辅助线是否消失或遮挡主体。只调整局部描边与平滑透明度，不添加大面积黑色遮罩。

**Step 5: 检查动画和中断**

连续切换各状态，验证旧动画立即平滑退出、接近态减速、对齐态停止循环、离开页面后没有异常或继续耗电的任务。将系统动画缩放设为关闭和非默认倍率分别复查。

**Step 6: 检查无障碍**

使用 TalkBack/语义检查确认图标、快门、模式和状态拥有可理解标签；颜色不是唯一状态线索；触控区域至少 `48 dp`。

**Step 7: 回填结果**

在 `docs/implementation-plan.md` 中逐项记录设备型号/尺寸、通过项、真实未验证项和修复内容。

### Task 13: 完整质量门禁与 APK 构建

**Files:**

- Modify if necessary: only files directly causing current changes to fail checks
- Modify: `docs/implementation-plan.md`

**Step 1: Gradle 配置与 Kotlin 编译**

Run:

```bash
./gradlew projects tasks :<actual-app-module>:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`。

**Step 2: Lint**

Run:

```bash
./gradlew :<actual-app-module>:lintDebug
```

Expected: `BUILD SUCCESSFUL`，没有本次改动新增的错误；不要通过全局关闭规则掩盖问题。

**Step 3: 单元测试**

Run:

```bash
./gradlew :<actual-app-module>:testDebugUnitTest
```

Expected: 所有测试 PASS。

**Step 4: Compose UI 测试**

Run:

```bash
./gradlew :<actual-app-module>:connectedDebugAndroidTest
```

Expected: 有设备时所有测试 PASS；无设备时明确记录未验证，不能将其描述为通过。

**Step 5: 构建 Debug APK**

Run:

```bash
./gradlew :<actual-app-module>:assembleDebug
```

Expected: `BUILD SUCCESSFUL`，在 `<actual-app-module>/build/outputs/apk/debug/` 生成可安装 APK。

**Step 6: 安装并启动 Debug APK**

Run:

```bash
adb install -r <absolute-debug-apk-path>
adb shell monkey -p <actual-application-id> 1
```

Expected: 安装成功，应用主页面可启动；相机权限流程和预览没有崩溃。

**Step 7: 条件允许时构建 Release**

先检查现有签名配置，绝不创建或提交带固定密码的生产 keystore。若 Release 任务支持未签名输出：

```bash
./gradlew :<actual-app-module>:assembleRelease
```

Expected: `BUILD SUCCESSFUL` 并生成 release APK；若需要缺失的安全签名，记录为未构建，不覆盖用户配置。

**Step 8: 提取 APK 元数据**

Run（使用本机 Android SDK 中真实 `aapt`/`apkanalyzer` 路径）：

```bash
apkanalyzer manifest application-id <absolute-debug-apk-path>
apkanalyzer manifest version-name <absolute-debug-apk-path>
apkanalyzer manifest version-code <absolute-debug-apk-path>
ls -lh <absolute-debug-apk-path>
```

Expected: 获得最终 applicationId、versionName、versionCode 和文件大小；minSdk、targetSdk 同时从合并 Manifest/Gradle 配置核对。

**Step 9: 最终 diff 安全检查**

Run:

```bash
git status --short
git diff --stat
git diff --check
git diff -- . ':(exclude)**/build/**'
```

Expected: 只有与任务直接相关的源码、测试、资源和文档；没有 keystore、密码、个人文件、构建缓存或意外大文件。APK 可作为本地构建产物保留，但不默认加入版本控制。

**Step 10: 完成交付记录**

在 `docs/implementation-plan.md` 勾选真实通过项，记录每条命令结果、设备验证、未解决问题、Debug/Release APK 的准确绝对路径、文件大小和版本元数据。

**Step 11: Commit（仅在获授权时）**

```bash
git add <explicit-source-and-test-files> docs/design-audit.md docs/implementation-plan.md docs/plans/2026-07-21-genwow-camera.md
git commit -m "test: verify GenWow camera and build debug APK"
```

## 完成定义

只有以下条件全部满足，才可以宣布实现完成：

- 实际 Android 工程能够成功编译。
- 主相机页可打开，原有预览、模式和拍照主流程保持可用。
- 俯仰、偏航、滚转和平移具有统一且方向明确的引导。
- 状态动画可以启动、切换、结束和取消，不因重组累积任务。
- 权限、初始化失败、分析失败和拍摄反馈有明确界面。
- 状态 reducer、动画规范和主要 UI 状态具备测试或 Preview。
- Lint 与单元测试通过；UI 测试在有设备时通过，没设备时如实记录。
- 小屏和常规尺寸没有明显裁切、遮挡或 Insets 问题。
- 可安装 Debug APK 构建成功并提供准确绝对路径与元数据。
- Git diff 不包含密钥、密码、缓存、个人文件或与任务无关的修改。
