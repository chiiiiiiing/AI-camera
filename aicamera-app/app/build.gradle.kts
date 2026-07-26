plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.aicamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.aicamera"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // — NDK ABI 过滤（如果你按渠道分包可在 buildTypes 里按 flavor 细化） —
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // 调试包也用不同后缀方便区分
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // — 禁止 Compose Compiler 的稳定性检查报错 (Kotlin 2.0+) —
    composeCompiler {
        enableStrongSkippingMode = true
    }

    // — aapt 资源压缩：保留模型文件不被压缩（从 assets 加载时需要） —
    androidResources {
        noCompress += listOf("onnx", "f32")
    }

    // — 单元测试：Android 类（Log 等）返回默认值，便于纯 JVM 测试 —
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// — Kotlin 2.0 新式编译器配置（替代已废弃的 kotlinOptions 块） —
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ── CameraX ──
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // ── AI 推理 (ONNX Runtime Mobile) ──
    // 注：TFLite/LiteRT 依赖已移除——v9 确定走 ONNX 路线（docs/09），
    // 若未来改回 LiteRT 需 AI 侧重新导出模型。
    implementation(libs.onnxruntime)

    // ── ML Kit 人脸主体检测（离线 bundled 模型，构图几何引导用） ──
    implementation(libs.mlkit.face.detection)

    // ── Compose UI ──
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)

    // ── Activity + Lifecycle ──
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // ── DI (Hilt) ──
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ── 导航 ──
    implementation(libs.navigation.compose)

    // ── 图片加载 ──
    implementation(libs.coil.compose)

    // ── 设置持久化 (DataStore) ──
    implementation(libs.datastore.preferences)

    // ── 测试 ──
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
