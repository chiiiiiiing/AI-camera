# GenWow / 帧好 — Figma 设计 vs 当前实现差距分析

> 基于: `GenWow_Claude_Code_Retrofit_Package/GenWow_Figma_UI_and_AI_Workflow_Retrofit_for_Claude_Code.md`
> 设计参考: `design_refs/`
> 生成日期: 2026-08-11

---

## A. 设计页面对应当前 Screen 映射

| 设计页面 | 当前 Screen | 状态 |
|---------|------------|------|
| Tab Bar.png | `MainScreen.kt` → `BottomTabBar` | **不一致**: 当前有文字标签"相机/社区/我的"，设计稿 icon-only |
| 社区-4.png (关注Feed) | `CommunityScreen.kt` tab 0 | 存在但需对齐卡片样式 |
| 社区-1.png (为你推荐) | `CommunityScreen.kt` tab 1 | 存在但需对齐 |
| 社区-2.png (消息) | `CommunityScreen.kt` tab 2 | 存在但无实际消息数据 |
| 社区-3.png (帖子详情) | `PostDetailScreen.kt` → `PostDetailDialog` | **缺少**: 关注按钮、收藏、分享 |
| 个人主页-1.png (作品Tab) | `ProfileScreen.kt` | **不一致**: 当前无Tab，单一作品网格 |
| 个人主页-4.png (点赞Tab) | ❌ 不存在 | **缺失** |
| 个人主页-3.png (收藏Tab) | ❌ 不存在 | **缺失** |
| 个人主页-2.png (评论Tab) | ❌ 不存在 | **缺失** |
| 人像-前/后.png | `ComposeGuidanceOverlay.kt` | **不一致**: 当前为人脸框+箭头，非人体轮廓虚线 |
| 平移-前/后.png | `CaptureWorkflowOverlay.kt` CenterPointGuidance | 基本一致但有 current/target 混淆需修 |
| 纸面内转动-前/后.png | `CaptureWorkflowOverlay.kt` RotatingOverlay | **不一致**: 当前用静态PNG+文字，设计有橙色弧线箭头 |
| 俯仰/偏航-*.png | ❌ 不存在 | **完全缺失** |
| 初始页.png | `SplashScreen.kt` | 待对齐视觉 |
| 登录页.png | `AuthComponents.kt` | 待对齐视觉 |
| 注册页.png + 创建新账号.png | `AuthComponents.kt` (Register) | 待对齐视觉 |

## B. 当前 BottomNav 结构

```
┌──────────┬──────────┬──────────┐
│ 相机图标  │ 社区图标  │ 人物图标  │
│ "相机"   │ "社区"   │ "我的"   │
└──────────┴──────────┴──────────┘
背景: 相机Tab黑底, 其他浅粉
选中: BrandPink
```

**目标**: icon-only, 无文字标签, 选中粉色强调, 浅粉底栏。

## C. 当前 Camera Workflow

```
IDLE → ANALYZING → ADJUSTING(action) → VERIFYING → READY → CAPTURING → SAVED
```

action 由 `CompositionSessionController.computeAction()` 动态选择最大误差维度:
- HoldStill / RotateClockwise / RotateCounterClockwise
- MoveLeft/Right/Up/Down
- DeviceUpsideDown
- Ready

**目标**: 增加 HumanPose / PitchYaw 模态, 不固定顺序。

## D. 当前 Guidance Overlay

| 组件 | 文件 | 状态 |
|------|------|------|
| 旋转引导 | `CaptureWorkflowOverlay.kt` RotatingOverlay | 静态PNG + 方向文字 |
| 平移引导 | `CaptureWorkflowOverlay.kt` CenterPointGuidance | Canvas箭头从主体→目标 |
| Fallback | `CaptureWorkflowOverlay.kt` FallbackGuidance | 简单文字提示 |
| Ready | `CaptureWorkflowOverlay.kt` ReadyOverlay | 脉冲圆点+文字 |
| 网格线 | `ComposeGuidanceOverlay.kt` | 基础网格+引导线 |

**目标**: HumanPoseOverlay (白色虚线轮廓), TranslationOverlay (锚点+虚线), RollOverlay (橙色弧线箭头), PitchYawOverlay (俯仰指示)

## E. 当前传感器来源

- `ImuHorizonSensor.kt` → Gravity/Accelerometer → `HorizonMath.compute()` → `HorizonObservation(rollDegrees, isUpright, confidence)`
- `CircularAngleSmoother` (sin/cos vector EMA, alpha=0.35)
- `HorizonPolicy` (enter=2.5°, exit=4.5°, stableDurationMs=500)
- **缺失**: Pitch / Yaw 观测、`DevicePoseProvider` 接口、Rotation Vector 融合传感器

## F. 当前 V9 输出

- `V9Inference.kt` → ONNX Runtime → 512-dim embedding
- `FakeInference.kt` → 假推理 (亮度 → 场景类型 → 固定引导参数)
- V9 实际: embedding + template retrieval + 元数据 → 客户端规则
- **不直接输出**: roll/pitch/yaw correction, zoom recommendation, pose landmarks

## G. 当前 Zoom 控制链

- `CameraControls.kt` → `setZoomRatio(ratio)` (async)
- `CameraViewModel.actualZoomRatio()` → polling `zoomState`
- `CompositionSessionController` → READY 前确认 zoom 完成
- 已修复: 不再强制 minZoom, 不通过 placeholder 驱动 zoom

## H. 当前 Community Repository

- `CommunityRepository.kt` → Room DAO
- `CommunityDao.kt` → `CommunityPost`, `CommunityComment`, `CommunityLike`
- Seed data: 7 posts (Helena/Daniel), matching comments
- **缺失**: Favorite entity/DAO, Follow entity/DAO, Share action

## I. 当前 Profile Repository

- `ProfileScreen.kt` 通过 `CommunityViewModel.getPostsByAuthor()` 获取作品
- `AuthRepository` → 用户名/简介/头像
- **缺失**: LikedPosts, FavoritePosts, CommentActivities 查询

## J. 当前 Auth Route

- `SplashScreen.kt` → `MainScreen.kt` / `AuthScreen.kt`
- `AuthComponents.kt` → Login / Register forms
- `AuthRepository.kt` → 本地 SharedPreferences + Room

---

## 修改文件列表 (预估)

### 必须修改
1. `MainScreen.kt` — 底部导航 icon-only
2. `ProfileScreen.kt` — 四 Tab 重构
3. `CommunityScreen.kt` — 卡片样式对齐
4. `PostDetailScreen.kt` — 收藏/关注/分享
5. `CaptureWorkflowOverlay.kt` — 旋转/平移 Overlay 对齐设计

### 需要新增
6. `guidance/overlay/HumanPoseOverlay.kt`
7. `guidance/overlay/PitchYawOverlay.kt`
8. `data/db/CommunityEntities.kt` — CommunityFavorite, UserFollow 实体
9. `data/db/CommunityDao.kt` — favorite/follow 查询

### 保持不变 (已对齐)
- `guidance/geometry/HorizonMath.kt` ✅
- `guidance/CompositionSessionController.kt` ✅
- `camera/CameraControls.kt` ✅
