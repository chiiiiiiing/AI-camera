# 新 AI 接手提示词 —— AI 智能摄影相机项目

> **文档目的**: 将本项目的完整背景、已有产出、当前进度、技术决策等全部上下文打包成一个 Prompt，交给新的 AI（如 ChatGPT、Claude、Cursor AI 等）继续工作。新 AI 阅读本文档后应能无缝接手。

---

## 一、项目是什么

### 产品定位
一款 **Android 优先、纯端侧运行**的 AI 智能摄影指导 App。用户打开相机对准拍摄对象，App 实时分析画面，以**旋转箭头、虚线人形、构图辅助线**等视觉元素叠加在取景器上，给出直观的拍摄建议。

### 拍摄类型覆盖
- **人像**: 引导模特站哪、摆什么姿势
- **风景**: 引导构图和机位
- **静物**: 引导角度和光线

### 目标用户
不会拍照的普通人（"拍照小白"）

---

## 二、职责分工

- **我（AI 引擎开发）**: 负责 C++ AI 引擎部分——模型推理、姿态估计、场景匹配、差值计算、LLM 语义翻译、JNI 接口
- **App 团队（其他人）**: 负责 Android App 主体——相机控制（Camera2 API）、UI 渲染（引导叠加层）、IMU 传感器读取、系统集成

---

## 三、项目目录结构

```
/Volumes/桌面/AI-camera/
├── AI引擎设计/           ← 你写的 AI 引擎文档
│   ├── 项目了解prompt.md              # 给 AI 助手的角色设定
│   ├── 技术设计与工作流程.md           # ★ 最核心文档（2000+ 行）
│   ├── 产品需求理解.md                 # 产品需求理解与确认
│   ├── AI部分详细工作流程.md           # AI 引擎 25 周开发计划
│   ├── 工作进度.md                     # 实际进度记录
│   ├── 协作工作流.md                   # Git/团队协作规范
│   ├── Mac远程连接Win本开发方案.md     # 开发环境方案
│   └── 新AI接手提示词.md               # ◄ 本文档
│
├── App技术设计/          ← GitHub 仓库已有的 App 端文档
│   ├── 01-相机链路.md
│   ├── 02-帧处理管线.md
│   ├── 03-AI模型接入.md
│   ├── 04-辅助线渲染引擎.md
│   ├── 05-构图引导交互.md
│   ├── 06-性能优化.md
│   ├── 07-上线工程.md
│   ├── 08-MVP方案与迭代.md
│   ├── 移动端开发核心指引.md
│   ├── 附录A-队友协作接口.md
│   ├── 附录B-技术选型速查.md
│   └── README.md
│
├── engine/               ← C++ AI 引擎源码（已搭建骨架）
│   ├── CMakeLists.txt
│   ├── include/
│   │   └── engine.h                   # ★ C 接口定义（完整）
│   ├── src/
│   │   ├── core/engine.cpp            # 引擎骨架实现（返回零值）
│   │   └── utils/usearch.hpp          # 向量搜索引擎（单头文件库）
│   └── tests/
│       └── test_engine_basic.cpp      # 基础单元测试
│
├── tools/                ← Python 工具链目录（空骨架）
│   ├── build_template_db/
│   └── quantize/
│
├── .github/workflows/
│   └── engine-ci.yml
│
└── .gitignore
```

---

## 四、系统架构核心设计

### 双圈层异步架构（最核心概念）

#### 外圈：高层语义检索（低频触发，场景切换时运行）
- 技术栈：MobileCLIP-S0（INT8 ~15MB）+ 本地向量数据库 USearch
- 逻辑：计算当前背景画面的 256d Embedding → 在 3000-5000 张"满分摄影模板库"中 ANN 检索 → 锁定最佳参考模板 → 抽离 Target Vector（目标特征锚点）
- 单次耗时：5-10ms

#### 内圈：底层物理对齐（高频实时，30fps）
- 技术栈：YOLOv8-Pose Nano（INT8 ~4MB）+ 手机 IMU 传感器
- 逻辑：提取实时相机骨骼关键点 + 手机俯仰/横滚角 → 与 Target Vector 做纯数学差值运算（Δ）→ 驱动前端 UI 引导
- 单帧推理：< 20ms

---

## 五、关键技术决策（已经确认）

| # | 决策 | 说明 |
|---|------|------|
| 1 | **放弃 20MB 模型限制** | 改为 ~227MB（标准模式），物理上不可能在 20MB 内装 CLIP+Pose+SAM+LLM |
| 2 | **LLM: SmolLM2-360M Q4** | 约 200MB，替代原文档的 Qwen-1.5-0.5B |
| 3 | **人像分割: MediaPipe Selfie Seg** | 约 200KB，替代 MobileSAM（~6MB） |
| 4 | **模板库: 3000-5000 张** | 覆盖 10+ 场景类别 |
| 5 | **模板标注: GPT-4V/Claude Vision API** | 批量处理，输出结构化 JSON |
| 6 | **AI 模块交付形式: .so 库** | 通过 JNI 暴露 Kotlin 接口给 App 团队 |
| 7 | **Android 优先** | 暂不考虑 iOS，使用 Camera2 API |
| 8 | **相机引导: 发光圆点方式** | 替代旋转箭头方案——画面显示目标圆点，用户将相机中心对准即得模板构图 |
| 9 | **模型分发: 方案 A** | APK < 50MB，标准模型通过 Play Asset Delivery 按需下载 |
| 10 | **分档方案** | 标准模式 ~227MB（6G+ RAM）、轻量模式 ~107MB（4-6G RAM）、极简模式 ~27MB（<4G RAM，无 LLM） |

### 模型预算详情

| 组件 | 模型/数据 | 量化后体积 | 加载位置 |
|------|----------|-----------|---------|
| 场景理解 | MobileCLIP-S0 INT8 | 15MB | GPU/NPU |
| 姿态估计 | YOLOv8-Pose Nano INT8 | 4MB | NPU |
| 人像分割 | MediaPipe Selfie Seg FP16 | 0.2MB | GPU |
| 端侧 LLM | SmolLM2-360M Q4_K_M | 200MB | CPU (mmap) |
| 向量索引 | USearch (5000 条) | ~5MB | CPU (mmap) |
| 模板元数据 | JSON | ~3MB | CPU |
| **合计** | | **~227MB** | |

---

## 六、三大核心工程痛点与解决方案

### 1. 视觉无关特征剥离
- **问题**: CLIP 向量检索会被人物服装颜色干扰（穿红衣服 → 检索到红背景照片）
- **方案**: CLIP 提取前用 MediaPipe Selfie Seg 生成人体 Mask → 人物区域像素强制置黑 → 仅对背景几何透视和空间氛围进行纯粹检索

### 2. 肢体形变归一化
- **问题**: 不同身材比例的人，绝对像素坐标比对会失效
- **方案**: 废弃绝对坐标比对 → 以"左肩至右髋"欧氏距离为基准做 Root-centered Scaling → 将位置匹配升维为关节夹角（Joint Angles）比对，容差 ±10°

### 3. 语义翻译层
- **问题**: 多维物理差值直接输出会非常机械（"Δx=50px, Δθ=15°"）
- **方案**: 差值打包为结构化 JSON/Prompt → 端侧 1B 级 LLM 实时推理 → 输出自然语言（"请模特向左边挪一步，下巴稍微抬高一点"）

---

## 七、AI 引擎 KPI 目标

| 指标 | 目标 | 备注 |
|------|------|------|
| 内圈单帧推理 | < 20ms | 保证 30fps 不掉帧 |
| 外圈场景检索 | < 10ms | 仅在场景切换时触发 |
| LLM 文本生成 | < 500ms | 异步运行，不阻塞帧循环 |
| 总模型体积 | < 250MB（标准模式） | |
| APK 基础体积 | < 50MB | 不含标准模型包 |
| 兼容性 | Android 10+ (API 29) | |
| 内存占用 | < 500MB (运行时) | 含所有模型 + 相机缓冲区 |

---

## 八、当前进度：阶段 2 进行中

### 已完成（2026-07-07）

1. **项目骨架搭建**
   - 目录结构创建完毕
   - CMake 构建系统配置完成（支持 macOS 本地编译 + Android NDK 交叉编译）
   - 本地编译验证通过（C++17, 依赖 Eigen/nlohmann-json/Catch2 通过 Homebrew 管理）

2. **C 接口头文件完整定义** (`engine/include/engine.h`)
   - 所有输入/输出数据结构已定义：
     - `CameraFrame`, `SensorData` (输入)
     - `CameraTarget`（相机目标圆点引导）
     - `PoseArrow`（姿势引导箭头）
     - `PoseOverlay`（虚线人形，17 个 COCO 关键点）
     - `CompGuides`（构图辅助线：九宫格、地平线、主体区域）
     - `TextSuggestion`（LLM 文字建议）
     - `GuidanceOutput`（汇总输出，含 `photo_type` 字段支持人像/风景/静物）
   - 生命周期 API：`engine_init` / `engine_shutdown` / `engine_is_ready` / `engine_process_frame` / `engine_trigger_rescan` / `engine_get_status`

3. **引擎骨架实现** (`engine/src/core/engine.cpp`)
   - 所有函数返回合理的默认值/零值，编译通过
   - 各模型推理模块（CLIP/Pose/Seg/LLM）以空函数占位，等待后续实现

4. **单元测试框架就绪**
   - Catch2 集成完成
   - `test_engine_basic.cpp`：测试 `get_version` 和 `engine_init/shutdown` 生命周期

5. **CI 配置** (`.github/workflows/engine-ci.yml`)
   - macOS 编译 + 测试自动化

6. **文档体系完整**
   - 7 份 AI 引擎文档全部编写完毕
   - 12 份 App 技术设计文档从 GitHub 仓库整理完毕

### 进行中（阶段 2：模板库构建，第 3-5 周）

2026-07-08 已启动：
1. 已完成 Openverse CC0/PDM 照片采集器、14 类搜索配置、数据 Schema、去重与断点续跑
2. 曾形成 37 条 pilot-v1 候选记录，但用户发现分类、比例、画作混入和中国人像覆盖问题，该批次已全部隔离，不能作为有效模板
3. 已修正数据来源策略：Unsplash/Pexels 在取得 AI/ML 或批量用途书面许可前禁用
4. 已重构准入规则：手机 4:3/3:4、媒介负面词、真实人体检测硬门槛；正式预处理任务数为 0
5. 原 YOLOv8-Pose 存在商业授权风险，MVP 优先验证 Apache-2.0 的 MediaPipe Pose Landmarker

2026-07-09 更新：
1. MediaPipe Pose Landmarker 真实筛选已跑通；macOS 沙盒内会因图形/缓存服务不可用崩溃，需在非沙盒环境或设置可写缓存目录后运行
2. `tools/build_template_db` 单元测试 26/26 通过；`validate_manifest.py` 结构校验 223 条通过
3. `audit_dataset.py` 发现 35 条 SHA-256 不匹配，其中 29 条是 `accepted`，当前本地批次不得入库
4. `screen_candidates.py` 和 `preprocess.py` 已新增 SHA-256 硬门槛；新筛选结果为 175 条非 rejected 候选中 146 条通过（人像 9、风景 106、静物 31）
5. `preprocess.py` 当前会在第 23 行哈希不一致处停止，这是预期保护行为

接下来：
1. 修复或废弃 29 条 accepted 但哈希不一致的记录；更推荐重采 pilot-v2-clean
2. 重采后必须依次通过 `validate_manifest.py`、`audit_dataset.py`、`screen_candidates.py`
3. 三项检查全绿后再运行 `preprocess.py` 生成正式 CV jobs
4. 中国人像采用自有拍摄、创作者书面授权或权利明确来源，不直接抓取社交媒体
5. 再进入 GPT-4V/Claude Vision 批量标注脚本、质量校验和 USearch 索引构建

---

## 九、对话中的关键讨论与决策过程

1. **用户最初要求模型预算 20MB** → 经过分析论证后，调整为 ~227MB，用户接受并确认
2. **用户最初提到 MobileSAM** → 改成 MediaPipe Selfie Segmentation，体积从 6MB 降到 200KB
3. **用户最初提到 Qwen-1.5-0.5B** → 改成 SmolLM2-360M（Q4 量化 ~200MB），更合理的体积/性能平衡
4. **引导方式从旋转箭头改为发光圆点** → 相机移动引导采用目标点方式：画面显示发光圆点，用户将相机中心对准该点即获得模板建议的构图
5. **LLM 标注方案** → 用户确认用 GPT-4V/Claude Vision API 批量处理，Claude 给出了完整的 Python 脚本框架
6. **GitHub 上传遇到 SMB 文件系统兼容问题** → 用户决定直接在文件存储的电脑（Windows）上上传
7. **开发环境** → Mac 通过 SMB 远程挂载 Windows 笔记本的硬盘，Mac 编码 + Windows 存储/Git

---

## 十、给新 AI 的工作指令

阅读以上内容后，请确认你已理解：

1. 这个项目的产品目标和技术架构
2. 当前进度（阶段 1 完成，即将进入阶段 2）
3. 关键的技术决策和已确认的方案
4. 文档体系中各文档的位置和作用

然后，请继续执行以下任务之一（由用户指定）：

- **继续阶段 2**: 模板库构建——编写照片收集、预处理、LLM 标注脚本
- **继续阶段 3**: 模型量化与推理框架集成
- **修改/补充文档**: 根据用户反馈调整现有文档
- **其他**: 等待用户给出具体指令

### 重要的协作约定

- 用户负责 AI 引擎部分（C++），App 主体由 App 团队负责
- 所有 AI 引擎的设计文档在 `AI引擎设计/` 目录下
- 接口协议严格遵守 `engine/include/engine.h` 的定义
- 用中文回复，代码注释用英文或中文均可
- 涉及文档修改时，改完要给出改动摘要
- 遇到不确定的技术决策时，先提问再动手

---

## 附录：文档快速索引

| 文档 | 路径 | 用途 |
|------|------|------|
| 项目了解prompt | `AI引擎设计/项目了解prompt.md` | AI 助手的角色设定和初始认知 |
| ★★★ 技术设计与工作流程 | `AI引擎设计/技术设计与工作流程.md` | **最全面**的技术蓝图（2000+ 行），含架构、选型、接口、模板库方案、所有阶段工作流 |
| 产品需求理解 | `AI引擎设计/产品需求理解.md` | 产品定位、目标用户、功能需求、待澄清问题 |
| AI 开发工作流 | `AI引擎设计/AI部分详细工作流程.md` | AI 引擎 25 周详细推进计划，按周拆分 |
| 工作进度 | `AI引擎设计/工作进度.md` | 实际进度记录，每次有进展应更新 |
| 协作工作流 | `AI引擎设计/协作工作流.md` | Git 分支策略、PR 规范、与 App 团队的协作节奏 |
| 远程开发方案 | `AI引擎设计/Mac远程连接Win本开发方案.md` | SMB 挂载方案详解 |
| engine.h | `engine/include/engine.h` | C 接口定义（JNI 边界的协议文件） |
| engine.cpp | `engine/src/core/engine.cpp` | 引擎骨架实现 |
