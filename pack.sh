#!/bin/bash
set -e
TMP="/c/Users/19900/Desktop/Ai-camera-codex-filter-recommendation/.temp_package"
ZIPOUT="C:/Users/19900/Desktop/Ai-camera-features.zip"
rm -rf "$TMP" 2>/dev/null || true
mkdir -p "$TMP/app" "$TMP/ai/engine" "$TMP/ai/tools" "$TMP/ai/docs" "$TMP/ai/output" "$TMP/app-arch"

APP="C:/Users/19900/Desktop/Ai-camera/aicamera-app/app/src/main/java/com/example/aicamera"
ATST="C:/Users/19900/Desktop/Ai-camera/aicamera-app/app/src/test/java/com/example/aicamera"
BLD="C:/Users/19900/Desktop/Ai-camera/aicamera-app"
AIDIR="C:/Users/19900/Desktop/ai_camera/AI-camera-main"

# ---- APP LAYER ----
cp "$APP/ui/screen/CameraScreen.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/MainActivity.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/viewmodel/CameraViewModel.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/viewmodel/PipelineViewModel.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/viewmodel/RenderViewModel.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/viewmodel/CommunityViewModel.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/camera/CameraManager.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/camera/CameraControls.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/camera/CameraConfig.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/camera/CameraGestureDispatcher.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/camera/CameraProControls.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/camera/CameraCapture.kt" "$TMP/app/" 2>/dev/null || true

cp "$APP/pipeline/AiEngineManager.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/pipeline/AiModelAssets.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/pipeline/V9EmbeddingRuntime.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/pipeline/V9Inference.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/pipeline/V9TemplateIndex.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/pipeline/FakeInference.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/pipeline/FramePipeline.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/pipeline/FramePreprocessor.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/pipeline/FrameScheduler.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/pipeline/PipelineState.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/pipeline/ResilientInference.kt" "$TMP/app/" 2>/dev/null || true

cp "$APP/guidance/geometry/ImuHorizonSensor.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/guidance/geometry/HorizonMath.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/guidance/geometry/GuidanceGeometry.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/guidance/geometry/GeometryGuidanceStage.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/guidance/geometry/FaceSubjectDetector.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/guidance/CompositionSessionController.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/guidance/GuidanceContract.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/guidance/GuidanceStateMachine.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/guidance/GuidanceStepSequencer.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/guidance/ComposeGuidanceOverlay.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/ui/components/CaptureWorkflowOverlay.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/ui/components/DebugHud.kt" "$TMP/app/" 2>/dev/null || true

cp "$APP/overlay/CompositionOverlayView.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/overlay/CoordinateMapper.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/overlay/GuideLineRenderer.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/overlay/AnimationController.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/overlay/model/GuideLineModels.kt" "$TMP/app/" 2>/dev/null || true

cp "$APP/data/AuthRepository.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/data/SettingsRepository.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/data/CommunityRepository.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/data/api/AuthApi.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/data/db/LocalUser.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/data/db/UserDao.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/data/db/AppDatabase.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/data/db/CommunityEntities.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/data/db/CommunityDao.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/di/AppModule.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/ui/navigation/AppNavGraph.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/AiCameraApp.kt" "$TMP/app/" 2>/dev/null || true

cp "$APP/ui/screen/CommunityScreen.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/ui/screen/PostDetailScreen.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/ui/screen/ProfileScreen.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/ui/screen/MainScreen.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/ui/auth/AuthComponents.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/ui/components/GenWowGlyphs.kt" "$TMP/app/" 2>/dev/null || true
cp "$APP/ui/state/SettingsState.kt" "$TMP/app/" 2>/dev/null || true

cp "$ATST/pipeline/ResilientInferenceTest.kt" "$TMP/app/" 2>/dev/null || true
cp "$ATST/pipeline/V9TemplateIndexSliceTest.kt" "$TMP/app/" 2>/dev/null || true
cp "$ATST/guidance/GuidanceStateMachineTest.kt" "$TMP/app/" 2>/dev/null || true
cp "$ATST/guidance/GuidanceStepSequencerTest.kt" "$TMP/app/" 2>/dev/null || true
cp "$ATST/guidance/geometry/GuidanceGeometryTest.kt" "$TMP/app/" 2>/dev/null || true
cp "$ATST/guidance/geometry/HorizonMathTest.kt" "$TMP/app/" 2>/dev/null || true
cp "$ATST/guidance/geometry/CircularAngleSmootherTest.kt" "$TMP/app/" 2>/dev/null || true
cp "$ATST/guidance/geometry/HorizonJudgeTest.kt" "$TMP/app/" 2>/dev/null || true

cp "$BLD/app/build.gradle.kts" "$TMP/app/" 2>/dev/null || true
cp "$BLD/build.gradle.kts" "$TMP/app/" 2>/dev/null || true
cp "$BLD/settings.gradle.kts" "$TMP/app/" 2>/dev/null || true
cp "$BLD/gradle/libs.versions.toml" "$TMP/app/" 2>/dev/null || true
cp "$BLD/app/src/main/AndroidManifest.xml" "$TMP/app/" 2>/dev/null || true

# ---- AI LAYER ----
cp "$AIDIR/ai/engine/CMakeLists.txt" "$TMP/ai/engine/" 2>/dev/null || true
cp "$AIDIR/ai/engine/include/engine.h" "$TMP/ai/engine/" 2>/dev/null || true
cp "$AIDIR/ai/engine/src/core/engine.cpp" "$TMP/ai/engine/" 2>/dev/null || true
cp "$AIDIR/ai/engine/src/core/template_index.cpp" "$TMP/ai/engine/" 2>/dev/null || true
cp "$AIDIR/ai/engine/src/core/template_index.h" "$TMP/ai/engine/" 2>/dev/null || true
cp "$AIDIR/ai/engine/tests/test_engine_basic.cpp" "$TMP/ai/engine/" 2>/dev/null || true

cp "$AIDIR/ai/tools/build_template_db/student_embedding_model.py" "$TMP/ai/tools/" 2>/dev/null || true
cp "$AIDIR/ai/tools/build_template_db/train_student_embedding.py" "$TMP/ai/tools/" 2>/dev/null || true
cp "$AIDIR/ai/tools/build_template_db/export_student_model.py" "$TMP/ai/tools/" 2>/dev/null || true
cp "$AIDIR/ai/tools/build_template_db/quantize_onnx_static.py" "$TMP/ai/tools/" 2>/dev/null || true
cp "$AIDIR/ai/tools/build_template_db/generate_onnx_embeddings.py" "$TMP/ai/tools/" 2>/dev/null || true
cp "$AIDIR/ai/tools/build_template_db/generate_student_embeddings.py" "$TMP/ai/tools/" 2>/dev/null || true
cp "$AIDIR/ai/tools/build_template_db/build_template_index.py" "$TMP/ai/tools/" 2>/dev/null || true
cp "$AIDIR/ai/tools/build_template_db/build_template_metadata.py" "$TMP/ai/tools/" 2>/dev/null || true
cp "$AIDIR/ai/tools/build_template_db/build_usearch_index.py" "$TMP/ai/tools/" 2>/dev/null || true
cp "$AIDIR/ai/tools/build_template_db/build_distillation_dataset.py" "$TMP/ai/tools/" 2>/dev/null || true
cp "$AIDIR/ai/tools/build_template_db/evaluate_template_index_quality.py" "$TMP/ai/tools/" 2>/dev/null || true
cp "$AIDIR/ai/tools/build_template_db/preprocess.py" "$TMP/ai/tools/" 2>/dev/null || true
cp "$AIDIR/ai/tools/build_template_db/schema/template_metadata.schema.json" "$TMP/ai/tools/" 2>/dev/null || true

cp "$AIDIR/ai/docs/工作进度.md" "$TMP/ai/docs/" 2>/dev/null || true
cp "$AIDIR/ai/docs/技术设计与工作流程.md" "$TMP/ai/docs/" 2>/dev/null || true
cp "$AIDIR/ai/docs/模板索引端侧接入方案.md" "$TMP/ai/docs/" 2>/dev/null || true
cp "$AIDIR/ai/docs/Embedding模型路线.md" "$TMP/ai/docs/" 2>/dev/null || true
cp "$AIDIR/ai/docs/产品需求理解.md" "$TMP/ai/docs/" 2>/dev/null || true
cp "$AIDIR/ai/docs/提示交互方案.md" "$TMP/ai/docs/" 2>/dev/null || true
cp "$AIDIR/ai/docs/文档总览.md" "$TMP/ai/docs/" 2>/dev/null || true

cp "$AIDIR/docs/09-AI接入包说明.md" "$TMP/app-arch/" 2>/dev/null || true

cp "$AIDIR/output/android_v9_ai_delivery/README.md" "$TMP/ai/output/" 2>/dev/null || true
cp "$AIDIR/output/android_v9_ai_delivery/android_runtime_config.json" "$TMP/ai/output/" 2>/dev/null || true
cp "$AIDIR/output/android_v9_ai_delivery/android_onnx_runtime_manifest.json" "$TMP/ai/output/" 2>/dev/null || true

echo "$(find "$TMP" -type f | wc -l) files collected"

# ZIP
powershell -Command "Compress-Archive -Path '$TMP/*' -DestinationPath '$ZIPOUT' -Force"
echo "Done: $ZIPOUT"
ls -lh "$ZIPOUT"
