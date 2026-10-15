# 本地摄影质量教师与评分头

本工具在已审核原图上构造同源比较，冻结现有 512 维检索编码器，训练 `520 → 64 ReLU → 1` 的可选评分头。输出是弱监督排序分数，不是用户喜欢照片的概率。

## 自动运行

在项目根目录运行。Python 应已有项目匹配的 `torch`、`torchvision`、`onnxruntime`、`numpy` 和 `Pillow`；不要重新安装或覆盖已有 CUDA 环境。

```powershell
$qualityRoot = Join-Path (Get-Location) 'local-output/core-recommendation-2026-10-15/quality'
$qualityPython = 'local-output/model-optimization-2026-10-14/.gpu-venv/Scripts/python.exe'
New-Item -ItemType Directory -Force "$qualityRoot/cache" | Out-Null
$env:TEMP = "$qualityRoot/cache"
$env:TMP = $env:TEMP
& $qualityPython -m pip install --no-deps --target "$qualityRoot/deps" --cache-dir "$qualityRoot/cache/pip" -r ai/tools/build_template_db/requirements-quality.txt
& $qualityPython ai/tools/build_template_db/prepare_quality_teacher.py --output-dir "$qualityRoot/vendor"
& $qualityPython ai/tools/build_template_db/train_guidance_quality.py --source-manifest local-output/core-recommendation-2026-10-15/data/source_manifest.jsonl --encoder aicamera-app/app/src/main/assets/ai/v9/student_model.onnx --output-dir "$qualityRoot/experiment" --vendor-dir "$qualityRoot/vendor" --deps-dir "$qualityRoot/deps" --device auto
```

下载每文件最多尝试两次，教师只在本地推理。采用作者的 `sac+logos+ava1-l14-linearMSE.pth`（Apache-2.0）和 OpenAI CLIP ViT-L/14（MIT）；固定 Git 提交、下载地址、SHA256 和许可证副本保存在 `teacher-provenance.json`。模型许可不等同于图片许可。

中断后可添加 `--resume-features`，但输入清单、编码器、教师和冻结协议必须不变。已有 `acceptance.json` 的运行禁止重复验收，不能删除它刷同一测试集。依赖、缓存、权重、图片与实验全部留在 `local-output`，不提交原图。

## 数据、训练和验收

- 只用 `original_split=train`、`reference_eligible=true`、非合成素材；任何触及旧 val/test 的来源簇整体排除。按来源、父子和近重复归组后，固定约 55%/20%/25% 的 train/val/acceptance，再产生派生图。
- 当前画面始终保留。仅可靠单脸生成最多两个完整保脸裁剪，其他主体不假设可观测。明显模糊和过曝有独立的适用条件，不能把所有原图默认标为更美。
- 教师对原始与 JPEG90 版本分别真实编码，两次分差同号且至少 0.20 才保留偏好。教师偏好与已知缺陷标签单独记录，冲突不隐藏。
- 固定三个权重衰减候选 `0.0001/0.001/0.01`、相同随机种子及 300 轮。验证集选型并冻结头，随后一次验收；最终指标未通过时 `quality_policy.enabled=false`。
- 首发候选场景固定 `portrait`。门槛为至少 30 个适用验收来源簇、每启用场景至少 10 簇、教师排序一致率至少 70%、按来源簇 bootstrap 95% 下限大于 0.5、严格优于结构/颜色基线、完整保脸、严重缺陷不退化、导出文件不超过 0.25 MiB。
- 编码器曾见过这些原图，报告显式标记 `encoder_seen_source=true`。这是留组评估评分头，不是整个系统面对全新图片的泛化证明。历史 53 张检索测试不重新用于选择。

## 手机契约

输入顺序是 512 维 L2 embedding，然后 8 个特征：

`mean_luma,luma_std,dark_fraction,light_fraction,face_area,face_cx,face_cy,face_valid`

色彩统计遍历 224×224 RGB，`Y=(.299R+.587G+.114B)/255`，暗部 `Y<=.05`，高光 `Y>=.95`，标准差使用总体方差。单脸无效时后四项为零。没有额外标准化。编码器输入与 Android 一致为 RGB/NCHW/224、`[-1,1]`；整帧和每个裁剪独立编码。

`quality_head.json` 使用 `float32-le-base64`，权重顺序为 `w1[64,520],b1[64],w2[64],b2[1]`，行优先。`quality_fixture.json` 提供真实 520 维输入及期望分数，跨语言容差为 `1e-4`。策略含编码器/头哈希、启用场景、布尔 gate 及可重新核算的 `metrics`；应用不得只相信 gate 布尔。

需区分两种范围：离线裁剪发生在原分辨率，再缩放到 224；现有手机路径对已经缩放的 224 图裁剪，会有再次采样差异。离线 MediaPipe 分数与手机 ML Kit 的有效性判断也不是同一置信度标尺。数值 fixture 验证头的算术，不代表整个手机预处理或检测已逐像素一致。

## 本次结果与交付

2026-10-15 运行处理 404 张图、341 个来源簇，生成 1,086 个真实图像版本和 1,203 对监督；选中权重衰减 0.01，导出 JSON 178,774 字节。人像验收只有 12 个有效来源簇；22 个稳定教师对全部为明显缺陷比较，评分头与简单基线均正确，验收没有稳定裁剪偏好对。

因此正式策略关闭评分头：数据覆盖不足，且没有优于简单基线。这里的 100% 不能解释为审美或构图推荐准确率。`coverage/rejection_rate` 仅表示非平局教师对中学生分差是否达到 0.05，不能解释为应用建议覆盖率；被关闭的审美支路实际建议覆盖为 0。报告、失败证据与实验权重保留，结构指导不依赖该头。

主要输出：`frozen-protocol.json`、`selection.json`、`acceptance.json`、`quality_policy.json`、`quality_head.json`、`quality_fixture.json`、`run-summary.json`。训练原始观测和派生图留在本地 `views.json`、`pairs.json`、`views/`。

单元检查无需网络和教师大权重：

```powershell
$env:PYTHONPATH = (Resolve-Path ai/tools/build_template_db).Path
& $qualityPython -m unittest discover -s ai/tools/build_template_db/tests -p test_train_guidance_quality.py -v
```
