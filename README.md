# AI 构图辅助相机

> 实时分析相机画面，自动选择合适的构图策略，通过生动的辅助线引导用户拍出构图更好的照片。

## 项目结构

```
Ai-camera/
├── aicamera-app/              # 📱 可运行的 Android 工程（← 开发和构建从这里开始）
│   ├── app/src/main/java/com/example/aicamera/
│   │   ├── camera/            #   模块一：CameraX 相机链路
│   │   ├── pipeline/          #   模块二：帧采集 → 预处理 → AI 推理管线
│   │   ├── overlay/           #   模块四：辅助线渲染引擎（Canvas Overlay）
│   │   ├── viewmodel/         #   MVVM 状态管理（Camera / Pipeline / Render）
│   │   ├── di/                #   Hilt 依赖注入
│   │   ├── ui/theme/          #   Material3 主题
│   │   └── MainActivity.kt    #   ★ Compose UI 全部串联入口
│   └── gradle/                #   Gradle wrapper + 版本目录
│
├── docs/                      # 📖 设计文档（共 12 篇）
│   ├── README.md              #   文档索引
│   ├── 移动端开发核心指引.md    #   角色定位、管线架构、技术选型
│   ├── 01-相机链路.md          #   CameraX 预览/拍照/权限
│   ├── 02-帧处理管线.md        #   跳帧调度/预处理/线程模型
│   ├── 03-AI模型接入.md        #   LiteRT 加载/推理/结果解析
│   ├── 04-辅助线渲染引擎.md    #   Canvas Overlay / 坐标映射 / 动画
│   ├── 05-构图引导交互.md      #   反馈/动画/UI 控件/状态机
│   ├── 06-性能优化.md          #   帧率/延迟/内存/发热/设备分级
│   ├── 07-上线工程.md          #   工程搭建/测试/模型下发
│   ├── 08-MVP方案与迭代.md     #   版本规划与依赖关系
│   ├── 附录A-队友协作接口.md   #   模型输入输出约定/问题清单
│   └── 附录B-技术选型速查.md   #   技术栈速览
│
└── _archive/                  # 🗄️ 历史参考代码（已归档，不再维护）
    ├── modules/               #   早期独立模块参考实现（已合并进 app）
    └── teammate-repo/         #   队友仓库旧副本（被 aicamera-app 取代）
```

## 快速开始

### 环境要求
- **Android Studio** (内置 JDK 21)
- **Android SDK** (compileSdk 35, minSdk 24)
- **Gradle 9.3.0** (wrapper 已包含)

### 构建与运行
```bash
# 1. 打开 Android Studio
File → Open → 选择 aicamera-app/ 目录

# 2. 等待 Gradle Sync 完成

# 3. 连接 Android 设备（USB 调试）或启动模拟器

# 4. 点击 Run 'app'

# 命令行构建
cd aicamera-app
./gradlew assembleDebug
```

## 技术栈

| 模块 | 选型 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.0.21 |
| UI | Jetpack Compose + Material3 | BOM 2024.12 |
| 构建 | Gradle (Kotlin DSL) | AGP 8.7.3 |
| 相机 | CameraX | 1.4.1 |
| AI 推理 | LiteRT (TFLite) + GPU Delegate | 2.16.1 |
| 架构 | MVVM + Hilt DI | Hilt 2.53.1 |

## 团队分工

| 角色 | 负责 |
|------|------|
| AI 模型训练（队友） | 端侧模型：场景识别 → 构图策略 → 辅助线参数 |
| 产品/设计（队友） | 功能范围、构图策略、UI 设计稿 |
| **移动端工程（你）** | **Android 上全部跑通：相机 → 推理 → 渲染 → 上线** |

## 当前状态 (2026-07-26，整改后)

- ✅ 项目骨架可编译运行（assembleDebug BUILD SUCCESSFUL，14/14 单测通过）
- ✅ CameraX 预览 / 拍照 / 切换摄像头（照片经 MediaStore 进系统相册）
- ✅ 帧处理管线 (ImageAnalysis → Scheduler → Preprocessor[转正/镜像/224] → Pipeline)
- ✅ **V9 ONNX 推理链路已接通**（AiEngineManager 后台装配 → 热替换进管线；失败自动 Fake 兜底）
- ✅ 辅助线渲染 (三分线 / 主体框 / 目标区 / 水平仪+参考线 / 箭头 / 引导线)
- ✅ MVVM + Hilt DI（ViewModel 全部真注入）+ Compose UI 全部串联
- ✅ 设置系统 DataStore 持久化，8 项开关全部生效（含 Pro 模式面板）
- ✅ 单元测试起步（索引切片 + 引导状态机滞回）
- 🔶 GuidanceCue 移动/旋转指令：待接 ML Kit 主体检测 + IMU（模型本身不给坐标）
- ❌ 真机性能验收（p50/p90 延迟、10-30 分钟稳定性）

> 2026-07-26 大整改详情见根目录《审查报告-2026-07-26.md》《11-整改记录.md》。
> ⚠️ 注意：本机 Android Studio JBR 已损坏，命令行构建请用 `C:\Users\19900\.aicamera-jdk\jdk-21.0.12+8`（见 11-整改记录 第七节）。
