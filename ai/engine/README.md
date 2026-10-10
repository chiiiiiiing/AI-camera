# C++ 引擎原型

本目录保留独立的 C++17 引擎接口、portable exact cosine 检索和回归测试。**Android App 当前使用 Kotlin + ONNX Runtime Mobile；没有调用本目录的 C++ 模型推理。** 这里的检索优化不能算作已经测得的手机端加速。

从仓库根目录运行：

```sh
cmake -S ai/engine -B build/ai-engine -DCMAKE_BUILD_TYPE=Release
cmake --build build/ai-engine --config Release
ctest --test-dir build/ai-engine -C Release --output-on-failure
```

基础回归采用临时合成索引，不依赖私有数据、Catch2 或大模型。若同时安装 Catch2 >= 3.3 和 nlohmann/json，则启用附加的历史 fixture 测试；缺少本地 fixture 的场景会跳过。

当前改进包括有限数/零向量校验、稳健归一化、确定性 top-k 有界堆检索、损坏索引拒绝、API 资源释放与错误路径检查。接入真实 Android runtime 前仍需实现模型推理、严格 JSON 解析，以及能表达 CameraX YUV plane、stride、buffer 长度的输入 ABI。
