# 附录 B：技术选型速查

---

## 技术栈一览

| 模块 | 选型 | 版本 | 备注 |
|------|------|------|------|
| 语言 | Kotlin | 1.9+ | |
| 最低 SDK | — | 24 (Android 7.0) | CameraX + LiteRT 兼容 |
| 目标 SDK | — | 35 | 最新 |
| 相机 | CameraX | 1.4+ | Google 官方推荐 |
| AI 推理 | LiteRT (TFLite) | 2.16+ | GPU Delegate 加速 |
| 架构 | MVVM | — | ViewModel + StateFlow |
| DI | Hilt | 2.51+ | |
| 图片加载 | Coil | 2.7+ | Kotlin 原生 |
| 崩溃监控 | Firebase Crashlytics | 19+ | 或 Bugly |
| iOS | — | — | V2.0 之前不考虑 |

---

## 依赖速查 (build.gradle.kts)

```kotlin
dependencies {
    // CameraX
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // LiteRT (TFLite)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:2.16.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")

    // Coil
    implementation("io.coil-kt:coil:2.7.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Firebase Crashlytics
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-crashlytics-ktx")
}
```

---

## 参考资源

- [Android CameraX 官方文档](https://developer.android.com/media/camera/camerax)
- [CameraX 官方示例](https://github.com/android/camera-samples)
- [TensorFlow Lite Android 指南](https://www.tensorflow.org/lite/android)
- [TensorFlow Lite GPU Delegate](https://www.tensorflow.org/lite/performance/gpu)
- [Firebase Crashlytics](https://firebase.google.com/docs/crashlytics)
- [Hilt 依赖注入](https://developer.android.com/training/dependency-injection/hilt-android)
- [Coil 图片加载](https://coil-kt.github.io/coil/)

---

> **上一篇**：[附录A-队友协作接口](./附录A-队友协作接口.md)
> **返回**：[README](./README.md)
