# GenWow · AI 构图辅助相机

Android 离线相机：自动识别人像、静物与风景，结合摄影规则、可选参考图以及 IMU、人脸与物体检测提供可验证的单步引导。可靠的主体规则不要求先匹配构图模板。当前优化面向中端 Android 手机。

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

- 相机预览、拍照、滤镜及单步构图工作流可用；俯仰/偏航保留接口与动画，但当前缺少已验证目标来源，不作为已启用的 AI 引导能力。
- V9 默认每 500ms 最多执行一次模型采样，几何观测独立更新；镜头、旋转和生命周期切换清除旧推荐。
- 模型失败时保留手动拍照，零置信度的降级结果不能触发构图达标或自动拍摄；人像缺少真实主体也不能达标。
- 新 embedding 缓存记录模型、源图、预处理与向量 hash；模型交付重新校验实际索引，而非只读历史质量报告。
- 识别模型新增预训练 MobileNetV3-Small + 512 维检索头：53 张冻结测试查询的 Top1 同场景命中由 38 张提升至 46 张，Top5 由 45 张提升至 47 张。FP32 模型为 4.67 MiB，两次失败量化均未采用；生活静物等子类仍有回退，完整结果与资产状态见模型报告。
- 真机耗时、发热与执行引导后的照片美感仍需验证；检索改善不等于美感已验证，人脸框也不等于全身姿态。
- 手动场景选择已移除，改为只读自动模式。健康构图可保持；主体贴边时根据可测留边改善生成有限调整，风景缺少可靠主体时仅提供可验证的端平。审美评分头继续关闭，详见 [自动场景与无模板引导](docs/自动场景与无模板摄影引导-2026-10-17.md)。
- 摄影技巧增加可靠头部朝向下的有限留白调整，以及按场景、对象和图像统计选择的一条可尝试建议。提示只在空闲预览显示，不授予移动或 Ready；具体适用条件、例外及验证范围见 [摄影技巧策略与验证](docs/摄影技巧策略与验证-2026-10-17.md)。
- 多场景扩展为 406 张参考加入明暗与边缘布局；自然风景、城市、静物保留不同布局，不强制三分法。3.8 MB 离线 NanoDet 改用等比例原画幅输入，重新检测与复核后保留四个食物参考；复杂静物和植物保留参考推荐与端平。新图像质量退化可撤销就绪，主体自行移动与自动曝光变化不计作执行成功。
- 新相对取景头完成训练，但教师一致率47.5%、低于保持原构图基线60%，因此正式关闭。摄影规则、质量反馈、失败证据及实际验证范围见 [摄影规则与执行效果验证](docs/摄影规则与执行效果验证-2026-10-16.md)。

当前开发入口见 [Android 开发与验证](docs/Android开发与验证.md)、[AI 接入说明](docs/09-AI接入包说明.md) 和 [引导动画规范](docs/引导动画制作与接入规范.md)。阶段实验与冻结验收统一在 [文档导航](docs/README.md) 按日期查阅；目录归属、私有数据及本地归档规则见 [仓库与本地目录说明](docs/仓库与本地目录说明.md)。
