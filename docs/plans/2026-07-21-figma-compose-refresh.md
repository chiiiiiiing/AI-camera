# 帧好 GenWow Figma 全产品 Compose 重构实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task. Figma implementation must continue using the connected Figma MCP for node-level verification.

**Goal:** 基于 Figma 文件 `AteKnDANl4hjp5wBaJ2uNX` 的 11 个 `412 × 917` Android 画板，重构现有 Compose 应用的品牌、认证、相机、社区和个人主页 UI，保留真实 CameraX 预览与拍照，补齐状态驱动动效、测试、模拟器视觉验证，并生成可安装 Debug APK。

**Architecture:** 使用单 Activity + Navigation Compose，拆分认证图、主应用图和 CameraX 生命周期边界。登录、注册、信息流和主页在没有后端协议时使用明确的本地演示 Repository；相机继续使用现有 `CameraController`、真实 `PreviewView` 与 `ImageCapture`。构图 Overlay 由不可变 UI 状态驱动，动画只拥有一个可取消时钟；Figma 提供品牌与布局基准，构图轴线和旋转弧允许在不改变语义的前提下做轻量化视觉增强。

**Tech Stack:** Kotlin 2.0.21、Jetpack Compose、Material 3、Navigation Compose、CameraX 1.4.1、Canvas/Path、StateFlow/ViewModel、JUnit4、Compose UI Test、Android 35 ARM64 Emulator、Gradle 8.10.2。

---

## 1. 范围与执行假设

### 默认交付范围

实现 Figma 的全部 11 个顶层画板：

| Figma 节点 | 页面 | 代码目标 |
|---|---|---|
| `1:2` | 启动页 | `SplashScreen` |
| `1:3` | 登录页 | `LoginScreen` |
| `1:5` | 注册页 | `RegisterScreen` |
| `1:7` | 梨子构图调整 | `CameraScreen` + Adjusting demo state |
| `1:9` | 梨子构图对齐 | `CameraScreen` + Aligned demo state |
| `1:11` | 海滩青色旋转提示 | Pitch/Yaw guidance state |
| `1:13` | 海滩橙色警示提示 | Wrong-direction guidance state |
| `1:15` | 人物推荐轮廓 | Subject target outline state |
| `1:17` | 人物轮廓对齐 | Subject aligned outline state |
| `1:19` | 推荐信息流 | `FeedScreen` |
| `1:21` | 摄影师主页 | `ProfileScreen` |

### 明确非目标

- 不虚构登录、注册、验证码或社区后端接口。
- 不实现真实粉丝、点赞、评论和消息网络同步。
- 不虚构 AI 模型推理；保留 `GuidanceAnalyzer` 接口，Debug 使用明确的演示状态。
- 不在本轮增加图片编辑、滤镜、云同步、支付或无关设置功能。
- 不引入 Hilt、Room、Retrofit、Coil 等当前没有必要的依赖；Figma 图片素材优先作为本地资源。
- 不提交生产 keystore、密码或签名文件。

### 验收标准

- 11 个 Figma 画板都有可导航的 Compose 对应页面或相机状态。
- 主设计基准从临时 `393 × 852` 更正为 Figma 的 `412 × 917`，同时兼容 `360 × 640 dp`。
- Figma 品牌渐变、深紫主色、粉色快门和正式 Logo 替换临时青绿色占位。
- CameraX 真实预览、切换摄像头、拍照和 MediaStore 保存继续工作。
- 俯仰、偏航、滚转、错误方向、接近目标、已对齐和人像轮廓状态可独立展示与切换。
- 所有无限动画均可取消，离开相机目的地后 CameraX 和动画释放。
- JVM 测试、Compose UI 测试、Lint、Debug APK 构建通过。
- Android 35 模拟器完成 `412 × 917` 和 `360 × 640` 视觉验证。

## 2. 方案比较与架构决策

### 方案 A：只重绘当前相机页

优点：改动最小、最快生成 APK、CameraX 风险最低。

缺点：忽略 Figma 中启动、认证、社区和主页 5 类页面，无法称为“基于产品 Figma 的完整实现”。

### 方案 B：完整 11 画板可导航原型 + 真实相机（推荐）

优点：完整呈现 Figma 产品结构；真实相机链路可用；无后端部分可清楚隔离为本地演示；后续接 API 不需要重写 UI。

缺点：新增导航和多个页面，测试与视觉验证工作量较大。

### 方案 C：完整生产级认证、社区和相机

优点：功能完整。

缺点：缺少后端协议、账号体系和数据源，任何实现都会虚构接口并超出当前授权范围。

### ADR-001：选择方案 B

**Decision:** 使用完整可导航 Compose 原型承载 11 个画板，相机使用真实 CameraX，非相机业务使用本地不可变演示数据。

**Consequences:** 用户可安装并完整体验设计流；认证和社区必须显示为演示数据，不声称已连接生产服务。

### ADR-002：采用 Navigation Compose

**Decision:** 新增 `androidx.navigation:navigation-compose`，使用单 Activity、认证子图和主应用子图。

**Rationale:** 11 个页面已经超过手写 `when(destination)` 的合理复杂度；Navigation Compose 能正确处理返回栈、深层页面和 CameraX 目的地生命周期。

### ADR-003：Figma 忠实还原与构图增强并存

**Decision:** 品牌、布局、字体层级、黑色相机控制区、粉色快门和导航结构按 Figma；粗等距虚轴和二维旋转符号改为细渐隐轴、透视椭圆弧和几何箭头。

**Rationale:** 原任务明确要求优化 Figma，而不是机械复制；增强只改变视觉表达质量，不改变操作语义和信息架构。

## 3. 目标代码结构

```text
app/src/main/java/com/genwow/camera/
├── MainActivity.kt
├── GenWowApp.kt
├── navigation/
│   ├── AppDestination.kt
│   ├── GenWowNavHost.kt
│   └── MainBottomBar.kt
├── data/demo/
│   ├── DemoAuthRepository.kt
│   ├── DemoCommunityRepository.kt
│   └── DemoModels.kt
├── camera/
│   ├── CameraController.kt
│   ├── CameraPreview.kt
│   └── GuidanceAnalyzer.kt
├── ui/splash/
│   └── SplashScreen.kt
├── ui/auth/
│   ├── AuthContract.kt
│   ├── AuthViewModel.kt
│   ├── AuthComponents.kt
│   ├── LoginScreen.kt
│   └── RegisterScreen.kt
├── ui/camera/
│   ├── CameraContract.kt
│   ├── GuidanceStateMachine.kt
│   ├── CameraRoute.kt
│   ├── CameraScreen.kt
│   ├── CameraControls.kt
│   ├── CompositionGuideOverlay.kt
│   ├── SubjectOutlineOverlay.kt
│   └── CameraStateScreens.kt
├── ui/feed/
│   ├── FeedScreen.kt
│   └── FeedComponents.kt
├── ui/profile/
│   ├── ProfileScreen.kt
│   └── ProfileComponents.kt
└── ui/theme/
    ├── Color.kt
    ├── Gradient.kt
    ├── Shape.kt
    ├── Type.kt
    ├── Motion.kt
    └── Theme.kt
```

## 4. 逐任务实施计划

### Task 1：冻结基线并保存 Figma 证据

**Status:** ✅ Completed on 2026-07-21. Baseline build, 11 JVM tests, Lint and Debug APK passed with JDK 17; Figma version and all 11 top-level frames were revalidated. Evidence is recorded in `docs/design-audit.md` and `docs/figma/frame-inventory.md`.

**Files:**

- Create: `docs/figma/frame-inventory.md`
- Create: `docs/figma/visual-baseline/README.md`
- Modify: `docs/design-audit.md`

**Steps:**

1. 运行当前基线测试：

   ```bash
   ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   ```

   Expected: `BUILD SUCCESSFUL`。

2. 通过 Figma MCP 再次读取节点 `0:1`，记录文件版本 `2378301108976446819`。
3. 导出节点 `1:2` 至 `1:21` 的 PNG，并记录 11 个画板的尺寸、Ready for Dev 状态和关键子节点。
4. 更新 `docs/design-audit.md`，删除“Figma MCP 不可用”的过期结论，写入真实字体、颜色、尺寸和页面结构。
5. 检查：所有设计事实必须能追溯到具体 Figma nodeId。

### Task 2：建立 Figma 品牌 Token 与正式 Logo

**Status:** ✅ Completed on 2026-07-21. Official node `1:28` was converted to an Android VectorDrawable; measured Figma purple/pink and exact logo gradient tokens, typography fallback, shapes and centralized motion specs compile and pass 14 JVM tests plus Lint.

**Files:**

- Modify: `app/src/main/java/com/genwow/camera/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/genwow/camera/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/genwow/camera/ui/theme/Gradient.kt`
- Create: `app/src/main/java/com/genwow/camera/ui/theme/Shape.kt`
- Create: `app/src/main/java/com/genwow/camera/ui/theme/Type.kt`
- Create: `app/src/main/java/com/genwow/camera/ui/theme/Motion.kt`
- Replace: `app/src/main/res/drawable/ic_app_placeholder.xml`
- Create: `app/src/main/res/drawable/ic_genwow_logo.xml`

**Steps:**

1. 写主题 Token 测试或静态断言，验证临时 `#63D8C6` 不再作为品牌主色。
2. 从节点 `1:28` 及其 Vector 子节点导出/转换正式 Logo SVG/VectorDrawable。
3. 建立语义颜色：

   - `BrandPurple`
   - `BrandPink`
   - `BrandLavender`
   - `BrandBlueTint`
   - `CameraChrome`
   - `GuidePrimary`
   - `GuideWarning`
   - `GuideAligned`

4. 创建粉紫蓝背景渐变，避免昂贵实时模糊；使用静态 Brush 和低频位移。
5. 字体以 Figma 的 Noto Sans CJK SC、Roboto、Inter 层级映射到 Compose；没有合法字体文件时使用系统 Sans Serif，并在文档中记录偏差。
6. 将动画时长、缓动和幅度集中到 `Motion.kt`。
7. 运行：

   ```bash
   ./gradlew :app:compileDebugKotlin :app:lintDebug
   ```

### Task 3：引入应用导航和演示数据边界

**Status:** ✅ Completed on 2026-07-21. Six Navigation Compose routes, auth/main back-stack boundaries, in-memory demo repositories and Camera destination disposal tests pass 17 JVM tests and 7 Android 35 emulator UI tests.

**Files:**

- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/genwow/camera/MainActivity.kt`
- Create: `app/src/main/java/com/genwow/camera/GenWowApp.kt`
- Create: `app/src/main/java/com/genwow/camera/navigation/AppDestination.kt`
- Create: `app/src/main/java/com/genwow/camera/navigation/GenWowNavHost.kt`
- Create: `app/src/main/java/com/genwow/camera/navigation/MainBottomBar.kt`
- Create: `app/src/main/java/com/genwow/camera/data/demo/DemoModels.kt`
- Create: `app/src/main/java/com/genwow/camera/data/demo/DemoAuthRepository.kt`
- Create: `app/src/main/java/com/genwow/camera/data/demo/DemoCommunityRepository.kt`
- Test: `app/src/androidTest/java/com/genwow/camera/navigation/GenWowNavigationTest.kt`

**Steps:**

1. 添加 Navigation Compose 依赖，不添加 Hilt、Retrofit 或 Room。
2. 先写 UI 测试：启动页结束后进入登录；登录演示成功后进入相机；底部入口能在相机、信息流、主页之间切换。
3. 实现目的地：`Splash`、`Login`、`Register`、`Camera`、`Feed`、`Profile`。
4. 使用 nested graph 保证认证页不显示主 Bottom Bar。
5. Camera 目的地离开组合树时释放 `CameraController`。
6. 演示 Repository 返回固定且不可变的数据；UI 文案或 Debug 标识说明其为演示内容。
7. 运行导航测试并验证返回栈。

### Task 4：实现启动页 `1:2`

**Status:** ✅ Completed on 2026-07-21. Official logo, responsive brand layout, finite entrance motion and 412×917/360×640 previews pass device UI tests.

**Files:**

- Create: `app/src/main/java/com/genwow/camera/ui/splash/SplashScreen.kt`
- Test: `app/src/androidTest/java/com/genwow/camera/ui/splash/SplashScreenTest.kt`

**Steps:**

1. 写测试验证 Logo、“帧好”和 `GenWow` 存在。
2. 按 `412 × 917` 实现背景渐变、Logo 容器和排版。
3. 动画：Logo 240ms 淡入+缩放、中文名随后 120ms 淡入、英文名轻微字距展开；总时长不超过 900ms。
4. 系统动画缩放为 0 时直接显示终态。
5. Preview：`412 × 917`、`360 × 640`。
6. 对比 Figma PNG，最多两轮截图修正。

### Task 5：实现登录页 `1:3`

**Status:** ✅ Completed on 2026-07-21. Custom underline fields, in-memory validation, password visibility, local-only navigation, Canvas WeChat/QQ icon silhouettes and Figma-aligned emulator layout pass tests.

**Files:**

- Create: `app/src/main/java/com/genwow/camera/ui/auth/AuthContract.kt`
- Create: `app/src/main/java/com/genwow/camera/ui/auth/AuthViewModel.kt`
- Create: `app/src/main/java/com/genwow/camera/ui/auth/AuthComponents.kt`
- Create: `app/src/main/java/com/genwow/camera/ui/auth/LoginScreen.kt`
- Test: `app/src/test/java/com/genwow/camera/ui/auth/AuthViewModelTest.kt`
- Test: `app/src/androidTest/java/com/genwow/camera/ui/auth/LoginScreenTest.kt`

**Steps:**

1. 写失败测试：空账号、空密码不能提交；输入后按钮可用；演示登录产生导航事件。
2. 实现底线输入框、账号/锁图标、忘记密码、注册入口、主按钮、第三方登录和协议文案。
3. 使用真实 PasswordVisualTransformation，不记录或持久化用户输入。
4. 动画：焦点底线颜色 180ms、错误文案高度动画、按钮按压 90ms、页面进入 220ms。
5. 使用 Figma 深紫主按钮，不保留默认 Material TextField 外观。
6. UI 测试键盘遮挡和小屏滚动。

### Task 6：实现注册页 `1:5`

**Status:** ✅ Completed on 2026-07-21. Four-field registration, finite local verification countdown, sensitive-field clearing, previews and Android 35 visual checks pass tests.

**Files:**

- Create: `app/src/main/java/com/genwow/camera/ui/auth/RegisterScreen.kt`
- Modify: `app/src/main/java/com/genwow/camera/ui/auth/AuthViewModel.kt`
- Test: `app/src/test/java/com/genwow/camera/ui/auth/AuthViewModelTest.kt`
- Test: `app/src/androidTest/java/com/genwow/camera/ui/auth/RegisterScreenTest.kt`

**Steps:**

1. 测试用户名、密码、账号、验证码四项验证。
2. 验证码按钮只做本地演示倒计时，不发送网络请求，并明确标注演示行为。
3. 实现 Figma 视觉：标题、副标题、四条底线输入、验证码胶囊、主按钮、协议。
4. 动画：验证码倒计时文本切换、字段错误轻回弹、注册成功导航淡出。
5. 不存储真实密码或账号信息。

### Task 7：按 Figma 重构相机外壳

**Status:** ✅ Completed on 2026-07-21. The Camera destination now owns the Figma-style black top and bottom chrome around a real CameraX `PreviewView`, with a pink/white shutter, local thumbnail placeholder, lens switch and icon-only camera/feed/profile destinations. Android 35 visual validation confirmed the preview bounds and 170dp bottom control region; back/front lens binding and a real MediaStore capture were exercised successfully. The stage gate passed 22 JVM tests, 12 connected UI tests, Lint and Debug APK assembly.

**Files:**

- Modify: `app/src/main/java/com/genwow/camera/ui/camera/CameraRoute.kt`
- Modify: `app/src/main/java/com/genwow/camera/ui/camera/CameraScreen.kt`
- Modify: `app/src/main/java/com/genwow/camera/ui/camera/CameraControls.kt`
- Modify: `app/src/main/java/com/genwow/camera/camera/CameraController.kt`
- Test: `app/src/androidTest/java/com/genwow/camera/ui/camera/CameraScreenTest.kt`

**Steps:**

1. 先更新 UI 测试，锁定 Figma 相机结构：顶部黑栏、预览、底部黑栏、粉色快门、缩略图、切镜头和底部三入口。
2. 将当前悬浮式控件改为 Figma 的黑色上下控制区，但保留系统 Insets。
3. 快门视觉按 Figma 改为粉色内圆、白色外环；触控区域保持至少 80dp。
4. 顶部三个功能入口和设置使用 Figma 图标或导出 VectorDrawable。
5. 相册入口在无真实缩略图时使用本地演示图；拍摄成功后更新为最近照片。
6. 确保 PreviewView 仍为真实 CameraX 预览，不使用 Figma 静态照片替代生产预览。
7. 测试切换前后摄像头和拍摄保存回归。

### Task 8：重构构图引导状态和动画

**Status:** ✅ Completed on 2026-07-21. Pitch, yaw, roll, translation, wrong-direction, near, aligned and AI-driven subject states now share one cancellable guidance clock and tested geometry primitives. The subject overlay contains no fixed human path: production can provide a `Flow<GuidanceSample>` whose normalized contours replace the previous frame in real time; Debug uses explicitly generated changing samples only. The stage passed 35 JVM tests, 15 Android 35 UI tests, Lint and Debug APK assembly.

**Files:**

- Modify: `app/src/main/java/com/genwow/camera/ui/camera/CameraContract.kt`
- Modify: `app/src/main/java/com/genwow/camera/ui/camera/GuidanceStateMachine.kt`
- Modify: `app/src/main/java/com/genwow/camera/ui/camera/CompositionGuideOverlay.kt`
- Create: `app/src/main/java/com/genwow/camera/ui/camera/SubjectOutlineOverlay.kt`
- Test: `app/src/test/java/com/genwow/camera/ui/camera/GuidanceStateMachineTest.kt`
- Test: `app/src/test/java/com/genwow/camera/ui/camera/GuideGeometryTest.kt`

**Steps:**

1. 先写测试扩展状态：

   - `WRONG_DIRECTION`
   - `SUBJECT_TARGET`
   - `SUBJECT_ALIGNED`
   - Pitch/Yaw/Roll 正反方向
   - NeedsAdjustment/Near/Aligned 双阈值

2. 从 `1:11`/`1:13` 建立青色正常提示和橙色警示语义，但禁止只靠颜色区分。
3. 保留单一可取消动画时钟；不同 Cue 不创建多个无限协程。
4. 旋转指示使用渐隐虚轴和透视椭圆弧；箭头沿切线运动。
5. Wrong Direction 使用一次 100–140ms 反向回弹和暖色过渡，不抖动画面。
6. Near 状态将路径幅度降至约 45%，周期延长约 35%。
7. Aligned 状态停止循环动画并收束为 Figma 的小型完成标记。
8. 以 `1:15`/`1:17` 为视觉参考，但不导出固定人物 Path；Overlay 接收 AI 每帧提供的归一化轮廓点，Debug 仅使用明确标注的逐帧生成样本。
9. 几何测试验证椭圆边界、箭头切线、渐隐对称性和零尺寸安全。

### Task 9：实现信息流 `1:19`

**Status:** ✅ Completed on 2026-07-21. Figma `1:19` feed, local-only demo posts, three tabs, semantic actions, local like state, responsive scrolling and Android UI coverage are implemented and visually checked at regular and small sizes.

**Files:**

- Create: `app/src/main/java/com/genwow/camera/ui/feed/FeedScreen.kt`
- Create: `app/src/main/java/com/genwow/camera/ui/feed/FeedComponents.kt`
- Modify: `app/src/main/java/com/genwow/camera/data/demo/DemoCommunityRepository.kt`
- Test: `app/src/androidTest/java/com/genwow/camera/ui/feed/FeedScreenTest.kt`

**Steps:**

1. 写 UI 测试：顶部三标签、Helena 帖子、Daniel 帖子、点赞/评论语义存在。
2. 从 Figma 导出郁金香和头像资源；记录素材来源 nodeId。
3. 实现粉紫背景、顶部标签、帖子卡片和底部导航。
4. 点赞仅修改本地演示状态，不写网络。
5. 动画：标签指示线 220ms 滑动、图片 180ms 淡入、点赞 160ms 轻缩放。
6. LazyColumn 只渲染可见帖子，避免不必要重组。

### Task 10：实现摄影师主页 `1:21`

**Status:** ✅ Completed on 2026-07-21. Figma `1:21` profile, photographer statistics, six-image local gallery, settings affordance, responsive scrolling and Android UI coverage are implemented and visually checked at regular and small sizes.

**Files:**

- Create: `app/src/main/java/com/genwow/camera/ui/profile/ProfileScreen.kt`
- Create: `app/src/main/java/com/genwow/camera/ui/profile/ProfileComponents.kt`
- Modify: `app/src/main/java/com/genwow/camera/data/demo/DemoCommunityRepository.kt`
- Test: `app/src/androidTest/java/com/genwow/camera/ui/profile/ProfileScreenTest.kt`

**Steps:**

1. 写 UI 测试：摄影师名称、品牌描述、128/256/64 统计、六图网格和设置入口。
2. 导出六张 Figma 作品图；使用本地 drawable，避免运行时网络依赖。
3. 实现头像占位、统计行、3×2 网格和底部导航。
4. 动画：页面进入时头像/统计轻微错峰淡入；图片点击只做按压反馈，本轮不新增详情页。

### Task 11：合并产品反馈、可访问性与双尺寸视觉验收

**Status:** ✅ Completed on 2026-07-21. Branded permission/error states, capture thumbnail refresh, system-bar handling, key accessibility semantics, camera/feed/profile navigation, regular-size validation and `360 × 640dp` validation are complete.

**Files:**

- Modify: `app/src/main/java/com/genwow/camera/ui/camera/CameraStateScreens.kt`
- Modify: `app/src/main/java/com/genwow/camera/ui/camera/CameraRoute.kt`
- Modify: `app/src/main/java/com/genwow/camera/navigation/MainBottomBar.kt`
- Test: `app/src/androidTest/java/com/genwow/camera/ui/camera/CameraStateScreenTest.kt`
- Test: `app/src/androidTest/java/com/genwow/camera/AccessibilitySmokeTest.kt`
- Create/Update: `docs/figma/visual-validation.md`

**Steps:**

1. 权限和错误页改用 Figma 品牌渐变、Logo 和深紫按钮。
2. 相机初始化失败继续提供重试；分析失败不阻断拍照。
3. 拍摄成功使用短白闪和缩略图更新。
4. Bottom Bar 目的地切换使用 180–220ms 颜色/缩放过渡，不移动整个预览画面。
5. 确保导航到 Feed/Profile 时 CameraX 已解绑，返回相机时重新绑定一次。
6. 只补关键 Preview：启动/登录、相机调整/对齐/错误方向/人物、Feed、Profile；已有等价 Preview 不重复创建。
7. 做一次无障碍冒烟检查：所有可点击图标有描述、触控目标不小于 48dp、快门不小于 80dp、警示和完成不只依赖颜色。
8. 同一次模拟器会话完成 `412 × 917` 常规尺寸和 `360 × 640` 小屏核对；非相机页各保留一张最终截图，相机只抽查调整、警示、人物和对齐代表状态。
9. 每页最多一轮修正；只有裁切、遮挡、无法操作或严重偏离 Figma 的问题阻断最终构建。
10. 在 `docs/figma/visual-validation.md` 记录已验证页面、有意偏差和未阻断的细节差异。

### Task 12：完整测试和 APK 构建

**Status:** ✅ Completed on 2026-07-21. The compressed final gate passed with 36 JVM tests, 20 connected Android 35 UI tests, Lint (0 errors), Debug and unsigned Release assembly. The final Debug APK was installed and exercised through Camera → Feed → Profile → Camera; CameraX detached and rebound, capture increased `Pictures/GenWow` from 6 to 7 files, and the live thumbnail changed to “最近拍摄照片”. APK metadata, Debug signature and local privacy exclusions were verified.

**Compressed remaining steps:**

1. Read existing test/Lint reports and record exact totals; do not rerun unchanged page-level visual work.
2. Record exact Debug/Release paths and sizes; verify metadata with `aapt` and signatures with `apksigner`.
3. Install the final Debug APK and perform one smoke path: cold start → camera → feed → profile → camera → capture → thumbnail/MediaStore confirmation.
4. Run Git/ignore/sensitive-file checks, update delivery documentation and stop the emulator.

**Files:**

- Modify: `docs/implementation-plan.md`
- Build outputs: `app/build/outputs/apk/debug/app-debug.apk`
- Build outputs: `app/build/outputs/apk/release/app-release-unsigned.apk`

**Steps:**

1. Gradle 与 Kotlin：

   ```bash
   ./gradlew --version
   ./gradlew :app:compileDebugKotlin
   ```

2. 单元测试：

   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

3. Lint：

   ```bash
   ./gradlew :app:lintDebug
   ```

4. UI 测试：

   ```bash
   ./gradlew :app:connectedDebugAndroidTest
   ```

5. Debug/Release：

   ```bash
   ./gradlew :app:assembleDebug :app:assembleRelease
   ```

6. 使用 `aapt dump badging` 核对 applicationId、versionName、versionCode、minSdk、targetSdk。
7. 使用 `apksigner verify` 核对 Debug APK 签名。
8. 安装 Debug APK，冷启动，授予权限，切换三次目的地并拍摄一张照片。
9. 查询 MediaStore 确认照片保存到 `Pictures/GenWow`。
10. 检查 Git diff，排除 PAT、keystore、local.properties、Gradle 缓存、Figma 临时下载和构建缓存。

## 5. 动效预算

同一时刻允许的持续动画：

- 非相机页：最多一个低频背景渐变位移。
- 相机页：最多一个 Guidance Path 动画时钟。
- 页面切换：只运行一次有限过渡。
- 快门/点赞/模式：只运行手势触发的有限动画。

建议时长：

| 动效 | 时长 |
|---|---:|
| 按钮按下 | 70–100ms |
| 按钮回弹 | 120–180ms |
| 标签/模式切换 | 180–220ms |
| 页面淡入/淡出 | 200–280ms |
| 错误方向回弹 | 100–140ms + 180ms 恢复 |
| 构图调整循环 | 1200–1500ms |
| 接近目标循环 | 1700–2000ms |
| 对齐收束 | 220–360ms |
| 拍摄白闪 | 70ms 淡入 + 190ms 淡出 |

## 6. 风险与缓解

| 风险 | 缓解方式 |
|---|---|
| Figma 使用 Noto Sans CJK SC，但项目没有字体文件 | 使用系统 Sans Serif；若用户提供合法字体文件再绑定 FontFamily |
| 社区与认证没有后端 | 使用 Demo Repository，UI 明确为演示，不虚构接口 |
| Figma 相机静态照片与真实 Preview 不可逐像素相同 | 只比较控制区、Overlay、比例和 Insets |
| Figma 粗虚线与产品“轻量克制”原则冲突 | 保留轴向与颜色语义，优化线宽、渐隐和透视关系，并记录有意偏差 |
| 人像目标轮廓没有真实关键点输入 | Debug 使用归一化 Path；生产接口等待模型输出 |
| 导航导致 CameraX 重复绑定或资源泄漏 | CameraRoute 仅存在于 Camera destination；用设备测试验证离开/返回循环 |
| 本地图片增大 APK | 只导出必要画板素材，使用 WebP/合理压缩，不加入完整 Figma 截图 |

## 7. 实施顺序与质量门禁

```text
Figma 基线
  → 品牌 Token
  → 导航骨架
  → 启动/认证
  → 相机外壳
  → 构图 Overlay
  → 社区/主页
  → Preview/可访问性
  → 模拟器逐屏校准
  → 测试与 APK
```

每个阶段必须满足：

1. 本阶段测试先失败再通过。
2. `compileDebugKotlin` 通过。
3. 不修改无关业务文件。
4. 不用静态 Figma 截图伪装真实相机。
5. 计划清单同步更新。
6. 视觉问题在进入下一阶段前修复，而不是累计到最后。

## 8. 预计最终交付

- 基于真实 Figma 的 11 画板 Compose 实现。
- 真实 CameraX 预览、切镜、拍照与 MediaStore 保存。
- 完整构图引导状态和统一动效。
- 启动、登录、注册、信息流、主页可导航演示。
- Figma nodeId—Compose 文件映射文档。
- 412×917 与 360×640 模拟器截图。
- JVM、Lint、Compose UI 测试报告。
- 可安装 Debug APK。
- 未签名 Release APK。
- 准确 APK 路径、大小、版本和 SDK 元数据。
