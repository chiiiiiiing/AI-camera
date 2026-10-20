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

        // 认证后端地址（构建期可覆盖：./gradlew -PAUTH_BASE_URL=http://192.168.x.x:8080/）
        buildConfigField("String", "AUTH_BASE_URL", "\"${project.findProperty("AUTH_BASE_URL") ?: "http://192.168.0.100:8080/"}\"")

        // — NDK ABI 过滤 —
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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
        // BuildConfig.DEBUG 用于 HUD 调试信息门控
        buildConfig = true
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

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
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

    // ── 数据库 (Room) ──
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── 网络（认证后端 API） ──
    implementation(libs.okhttp)

    // ── 测试 ──
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // ── 桌面功能回放台（JVM）：真实 ONNX 模型在电脑上跑 ──
    // 与 App 的 onnxruntime-android 同版本同 API（ai.onnxruntime 包），仅加在测试 classpath。
    // App 打包（implementation）仍用 onnxruntime-android，不受影响。
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
}

// 桌面回放台：测试 classpath 用桌面版 onnxruntime（含主机 natives），
// 排除 App 的 Android AAR（classes.jar 同包冲突）。androidTest 不受影响。
configurations.matching { it.name.contains("Test") && it.name.contains("Classpath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

// ── 功能回放台入口 ──
// 用法：./gradlew.bat :app:replayEngine
// 在电脑 JVM 上跑真实 student_model.onnx + 406 模板索引 + 构图状态机，输出功能回放 JSON。
tasks.register<JavaExec>("replayEngine") {
    group = "verification"
    description = "桌面功能回放台：真实 ONNX + 模板索引 + 构图状态机 → 回放 JSON"
    // AGP 8 不再暴露 Java 插件的 sourceSets。复用 testDebugUnitTest 任务的 classpath
    // （含 AGP 转换过的 mockable android.jar，让 android.util.Log 等返回默认值），
    // 再补上 Kotlin 测试类的输出目录。
    val unitTestTask = tasks.named<Test>("testDebugUnitTest")
    dependsOn("compileDebugUnitTestKotlin")
    classpath = files(Callable { unitTestTask.get().classpath }) +
        files("$buildDir/tmp/kotlin-classes/debugUnitTest")
    mainClass.set("com.example.aicamera.replay.ReplayMainKt")
    // 默认相对路径（app/src/main/...、../ui-review/...）以 aicamera-app 工程根为基准
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")
}
