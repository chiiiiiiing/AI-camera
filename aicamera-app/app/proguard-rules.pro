# ────────────────────────────────────────────────────────────
# AI 构图辅助相机 — ProGuard 混淆规则
# ────────────────────────────────────────────────────────────

# — ONNX Runtime（JNI 层按名字查找类/方法，必须 keep） —
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# — CameraX —
# 说明：CameraX 官方无需额外 keep 规则；保留防御性 keep，
# 后续包体优化时可收紧为仅 keep 使用到的类。
-keep class androidx.camera.** { *; }

# — Hilt / Dagger —
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.**

# — Kotlin serialization (后续模型下发用) —
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# — 通用规则 —
-keepattributes SourceFile,LineNumberTable
# TODO(包体优化): 收窄为仅 keep Hilt 入口与反射使用的类，放开自身代码混淆
-keep class com.example.aicamera.** { *; }
