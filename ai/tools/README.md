# AI 模型与数据工具

本目录维护离线模型、模板库、评估和交付校验源码。Android 应用在仓库根目录 `aicamera-app/`，已打包的 V9 模型与索引在 `aicamera-app/app/src/main/assets/ai/v9/`。

- `build_template_db/`：模板数据处理、蒸馏数据构建、模型导出和量化、embedding 生成、索引校验与评估，以及对应测试。
- `validate_android_v9_assets.py`：检查已打包模型与索引的契约，并从实际向量重新计算库内 leave-one-out top5 同场景指标。
- `benchmark_android_v9_engine.sh`：通过 `adb` 收集已经运行的相机预览进程的内存与渲染证据。脚本不安装或启动应用；模型延迟仍需结合端侧采样统计。

本次恢复的是工具源码及测试，不包含训练图片、教师 checkpoint、原始标注或未跟踪的大模型。执行合成数据测试和打包资产 gate 不需要本地训练图库，也不会下载模型或调用云服务。训练和重建操作必须另外提供数据路径与对应依赖。

## 校验已打包资产

从仓库根目录执行，只需 Python 标准库：

```sh
python -B ai/tools/validate_android_v9_assets.py --asset-root aicamera-app/app/src/main/assets/ai/v9
```

此指标用于检查当前索引，不代表独立新照片泛化性能。历史 embedding 缺少生成模型 hash 时，脚本会明确报告，不能由文件名推断完整模型来源。2026-10-15 已完成 MobileNetV3-Small 迁移检索模型的真实训练/验证，结果和局限见 [识别模型报告](../../docs/AI识别模型优化与验证-2026-10-15.md)；复现入口见 [训练工具 README](build_template_db/README.md)。

## 运行模型与数据契约测试

```sh
python -m pip install numpy Pillow
cd ai/tools/build_template_db
python -B -m unittest discover -s tests -p 'test_validate_template_index.py'
python -B -m unittest discover -s tests -p 'test_evaluate_template_index_quality.py'
python -B -m unittest discover -s tests -p 'test_validate_android_v9_assets.py'
python -B -m unittest discover -s tests -p 'test_generate_onnx_embeddings.py'
python -B -m unittest discover -s tests -p 'test_build_distillation_dataset.py'
```

这些测试使用临时文件和替代推理会话，不需要 torch、onnxruntime、教师模型或真实图片库。运行完整测试或实际训练/推理时，请按 `build_template_db/requirements.txt` 安装依赖；人体筛选另需 `requirements-screening.txt`。

## 数据与缓存契约

- 新生成 embedding 的缓存绑定 ONNX 文件、源图、预处理配置和向量 SHA-256；缺少来源记录或任一项变化时，必须显式指定 `--overwrite` 才会重建。图像缓存采用无损 PNG，保证首次与后续读取的像素一致。
- 蒸馏数据构建拒绝重复身份、相同来源 hash/path、无效 teacher 向量和混用 teacher 空间；验证所有分割后才写出文件。按拍摄组隔离的泛化测试仍需另建。
- 评估报告包含输入文件 hash、指标分母及 leave-one-out 评估口径。未知标签不能计为匹配，零查询 ANN 检查不会标为通过。

`build_template_db/README.md` 和 `KNOWN_ISSUES.md` 保留历史批次操作记录；其中素材数量、路径和历史质量数字不是当前 checkout 的数据清单。历史汇总工具默认查找仓库根目录下的私有 `tools/raw_photos/` 与 `tools/preprocessed/`；可用 `--root` 指定其他数据根目录。这些本地数据不属于源码恢复范围。
