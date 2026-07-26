# ────────────────────────────────────────────────────────────
# AI 构图辅助相机 — ProGuard 混淆规则
# ────────────────────────────────────────────────────────────

# — CameraX —
-keep class androidx.camera.** { *; }

# — LiteRT / TensorFlow Lite —
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.**

# — Hilt / Dagger —
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.**

# — Kotlin serialization (后续模型下发用) —
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# — 通用规则 —
-keepattributes SourceFile,LineNumberTable
-keep class com.example.aicamera.** { *; }
