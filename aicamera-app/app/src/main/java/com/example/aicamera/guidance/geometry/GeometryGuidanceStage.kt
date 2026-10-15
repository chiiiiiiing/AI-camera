package com.example.aicamera.guidance.geometry

import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.aicamera.guidance.GuidanceCue
import com.example.aicamera.pipeline.FakeGuideLine
import com.example.aicamera.pipeline.FakeInferenceOutput
import com.example.aicamera.pipeline.analysisCrop
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 几何引导阶段 — 管线 Phase 2.5：把真实几何信号注入推理输出。
 *
 * ## 在管线中的位置
 * ```
 * 预处理 → 推理(V9/Fake) → ★ GeometryGuidanceStage.apply() → PipelineResult
 * ```
 *
 * ## 做的事
 * 1. 从 [ImuHorizonSensor] 读真实水平倾角（EMA 平滑）→
 *    更新 horizon_level 辅助线的 angle_degrees（水平仪从此是真实数据）；
 * 2. 从 [FaceSubjectDetector] 拿真实主体框（限频+缓存）→
 *    替换掉 V9 人像模板里的「占位假框」，画出真实主体框；
 * 3. 用 [GuidanceGeometry] 计算 GuidanceCue（ROLL/MOVE/SUBJECT_*）与
 *    compositionProgress —— GuidanceStateMachine 终于吃到真信号，
 *    方向箭头/对齐反馈不再是恒 NONE。
 *
 * 对非 FakeInferenceOutput 类型的输出原样透传（不耦合未来模型 schema）。
 */
@Singleton
class GeometryGuidanceStage @Inject constructor(
    private val horizonSensor: ImuHorizonSensor,
    private val faceDetector: FaceSubjectDetector
) : Closeable {

    /** 水平角 EMA（传感器 5–10Hz 噪声约 ±1°） */
    private val horizonEma = CircularAngleSmoother(HORIZON_EMA_ALPHA)
    /** 俯仰角 EMA */
    private val pitchEma = CircularAngleSmoother(PITCH_EMA_ALPHA)
    /** 偏航角 EMA（磁力计噪声更大，平滑更强） */
    private val yawEma = CircularAngleSmoother(YAW_EMA_ALPHA)

    /** 主体框中心 EMA（检测限频 4Hz + 检测器本身有抖动） */
    private val subjectEmaX = EmaSmoother(SUBJECT_EMA_ALPHA)
    private val subjectEmaY = EmaSmoother(SUBJECT_EMA_ALPHA)

    private var structure: com.example.aicamera.guidance.StructureObservation? = null
    private var lastFaceId = -1L
    private var cachedSmoothBox: NormalizedBox? = null
    private var lastImuId = -1L
    private var cachedRoll: Float? = null
    private var cachedPitch: Float? = null
    private var cachedYaw: Float? = null

    fun observe(imageProxy: ImageProxy, isFrontCamera: Boolean): com.example.aicamera.guidance.StructureObservation? {
        faceDetector.detectIfDue(imageProxy, isFrontCamera)
        val previous = structure
        structure = faceDetector.latestObservation()?.copy(
            aspectRatio = imageProxy.analysisCrop().displayAspectRatio(imageProxy.imageInfo.rotationDegrees))
        if (previous != null && previous.trackingId != structure?.trackingId) {
            subjectEmaX.reset(); subjectEmaY.reset(); lastFaceId = -1; cachedSmoothBox = null
        }
        return structure
    }

    /**
     * 把几何信号应用到推理输出。
     *
     * @param output 推理器输出（仅处理 FakeInferenceOutput，其他类型透传）
     * @param imageProxy 当前帧（人脸检测用）
     * @param isFrontCamera 是否前摄
     */
    fun apply(input: Any?, imageProxy: ImageProxy, isFrontCamera: Boolean): Any? {
        if (input !is FakeInferenceOutput) return input
        val oldTarget = input.compositionTarget
        val targetFace = structure?.singleFace(android.os.SystemClock.elapsedRealtime())
        val targetValid = oldTarget == null || (targetFace != null && oldTarget.accepts(targetFace, structure?.faceYawDegrees) && oldTarget.subjectTrackingId == structure?.trackingId &&
            oldTarget.viewportVersion == structure?.viewportVersion && targetFace != null)
        val output = if (targetValid) input else input.copy(compositionTarget = null,
            guideLines = input.guideLines.filterNot { it.type == "target_zone" })

        // ── 1. 真实水平/俯仰/偏航观测（含 isUpright/confidence） ──
        // ★ 传感器已做圆周平滑，此处保留 EMA 做二次去抖
        val rawObs = horizonSensor.latestObservation()?.takeIf { it.confidence.isFinite() && it.confidence > 0f }
        if (rawObs == null) {
            horizonEma.reset(); pitchEma.reset(); yawEma.reset()
            cachedRoll = null; cachedPitch = null; cachedYaw = null; lastImuId = -1
        } else if (rawObs.observationId != lastImuId) {
            cachedRoll = rawObs.takeIf { HorizonMath.hasObservableRoll(it) }?.rollDegrees?.let { horizonEma.smooth(it) }
            cachedPitch = rawObs.pitchDegrees?.takeIf { it.isFinite() }?.let { pitchEma.smooth(it) }
            cachedYaw = rawObs.yawDegrees?.takeIf { it.isFinite() }?.let { yawEma.smooth(it) }
            lastImuId = rawObs.observationId
        }
        val smoothAngle = cachedRoll
        val smoothPitch = cachedPitch
        val smoothYaw = cachedYaw
        val coordinateAspectRatio = imageProxy.analysisCrop().displayAspectRatio(imageProxy.imageInfo.rotationDegrees)

        // ── 2. 真实主体框 ──
        val rawBox = structure?.singleFace(android.os.SystemClock.elapsedRealtime())
        val smoothBox = if (rawBox != null && structure?.observationId == lastFaceId) cachedSmoothBox else rawBox?.let {
            val cx = subjectEmaX.add(it.centerX)
            val cy = subjectEmaY.add(it.centerY)
            NormalizedBox(
                left = (cx - it.width / 2f).coerceIn(0f, 1f),
                top = (cy - it.height / 2f).coerceIn(0f, 1f),
                right = (cx + it.width / 2f).coerceIn(0f, 1f),
                bottom = (cy + it.height / 2f).coerceIn(0f, 1f)
            )
        }
        lastFaceId = structure?.observationId ?: -1L
        cachedSmoothBox = smoothBox
        if (rawBox == null) {
            // 主体丢失时缓慢衰减，避免指令跳变
            subjectEmaX.reset()
            subjectEmaY.reset()
        }

        // ── 3. 提取模板目标区参数（若有） ──
        // The live detector returns faces. A generic whole-subject center is not
        // a face anchor and must not drive face-position corrections.
        val targetLine = output.guideLines.firstOrNull {
            it.type == "target_zone" && it.visible && it.params["anchor_kind"] == "face"
        }
        val targetCx = targetLine?.floatParam("center_x") ?: 0.5f
        val targetCy = targetLine?.floatParam("center_y") ?: 0.5f
        val targetTol = targetLine?.floatParam("radius") ?: 0.12f
        val hasTarget = targetLine != null && GuidanceGeometry.isValidTarget(
            targetLine.floatParam("center_x"), targetLine.floatParam("center_y"), targetTol
        )
        val hasRecommendation = output.confidence.isFinite() && output.confidence > 0f
        val guidedSubject = smoothBox?.takeIf { hasTarget && hasRecommendation }

        // 模板是否期望主体：有人像类占位框即期望主体
        val expectsSubject = output.expectsSubject || output.guideLines.any { it.type == "subject_box" }

        // Pose observations describe how the user chose to frame the scene.
        // Only explicit verified targets can authorize changing pitch or yaw.
        val poseTarget = output.guideLines.firstOrNull {
            it.type == "horizon_level" && it.visible && it.params["pose_target_verified"] == true
        }?.takeIf { hasRecommendation }
        val pitchTarget = poseTarget?.floatParam("target_pitch_degrees")?.takeIf { it.isFinite() }
        val yawTarget = poseTarget?.floatParam("target_yaw_degrees")?.takeIf { it.isFinite() }
        val hasRequestedPoseEvidence = (pitchTarget == null || smoothPitch != null) &&
            (yawTarget == null || smoothYaw != null)

        // ── 4. 计算指令与进度 ──
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = guidedSubject?.centerX,
            subjectCy = guidedSubject?.centerY,
            expectsSubject = hasRecommendation && expectsSubject && smoothBox == null,
            targetCx = targetCx,
            targetCy = targetCy,
            targetTolerance = targetTol,
            horizonAngleDeg = smoothAngle,
            pitchAngleDeg = smoothPitch,
            yawAngleDeg = smoothYaw,
            yawTargetDeg = yawTarget,
            pitchTargetDeg = pitchTarget,
            coordinateAspectRatio = coordinateAspectRatio
        )
        val geometryProgress = GuidanceGeometry.compositionProgress(
            subjectCx = guidedSubject?.centerX,
            subjectCy = guidedSubject?.centerY,
            targetCx = targetCx,
            targetCy = targetCy,
            horizonAngleDeg = smoothAngle,
            pitchAngleDeg = smoothPitch,
            yawAngleDeg = smoothYaw,
            yawTargetDeg = yawTarget,
            pitchTargetDeg = pitchTarget,
            targetTolerance = targetTol,
            coordinateAspectRatio = coordinateAspectRatio
        )
        // ★ 指令-进度对齐：SUBJECT_ALIGNED 时提升到 ALIGNED 阈值之上，
        //   GuidanceStateMachine 才能进入 ALIGNED 相位（触发达标震动/绿闪）
        val boostedProgress = GuidanceGeometry.verifiedProgress(
            cue, geometryProgress, expectsSubject, smoothBox != null,
            hasRecommendation = hasRecommendation && (!expectsSubject || hasTarget) && hasRequestedPoseEvidence,
            hasCompositionTarget = guidedSubject != null || pitchTarget != null || yawTarget != null
        )

        // ── 5. 重建辅助线：真实水平角 + 真实主体框 ──
        // ★ 无水平传感器信号时整条移除（修复复审发现：angle ?: 0f 会显示
        //   假的「水平绿色」，比没有这条线更误导用户）
        val newLines = output.guideLines.mapNotNull { line ->
            when (line.type) {
                "horizon_level" -> {
                    if (smoothAngle != null) {
                        val extra = mutableMapOf<String, Any>("angle_degrees" to smoothAngle)
                        // ★ 传递 isUpright 标记（供分步引导序列器做倒置检测）
                        rawObs?.let { extra["is_upright"] = it.isUpright }
                        // ★ 传递俯仰/偏航角（供 Pitch/Yaw 引导使用）
                        smoothPitch?.let { extra["pitch_degrees"] = it }
                        smoothYaw?.let { extra["yaw_degrees"] = it }
                        line.copy(params = line.params + extra)
                    } else null
                }
                "subject_box" -> {
                    if (smoothBox != null) {
                        // 真实检测框替换占位框（real=true 标记：分步引导只信真实检测）
                        val aligned = hasRecommendation && hasTarget && GuidanceGeometry.isSubjectAligned(
                            smoothBox.centerX, smoothBox.centerY, targetCx, targetCy, targetTol, coordinateAspectRatio
                        )
                        line.copy(
                            visible = true,
                            params = mapOf(
                                "x" to smoothBox.left,
                                "y" to smoothBox.top,
                                "width" to smoothBox.width,
                                "height" to smoothBox.height,
                                "real" to true,
                                "anchor_kind" to "face",
                                "aligned" to aligned
                            )
                        )
                    } else null
                }
                "target_zone" -> if (line === targetLine && hasTarget) {
                    line.copy(params = line.params + mapOf(
                        "radius" to GuidanceGeometry.effectiveTargetRadius(targetTol),
                        "coordinate_aspect_ratio" to coordinateAspectRatio
                    ))
                } else line
                else -> line
            }
        }.toMutableList()

        // 模板没给主体框但检测到了主体（如风景照里有人）→ 也画出来
        if (smoothBox != null && newLines.none { it.type == "subject_box" }) {
            val aligned = hasRecommendation && hasTarget && GuidanceGeometry.isSubjectAligned(
                smoothBox.centerX, smoothBox.centerY, targetCx, targetCy, targetTol, coordinateAspectRatio
            )
            newLines.add(
                FakeGuideLine(
                    type = "subject_box", visible = true,
                    params = mapOf(
                        "x" to smoothBox.left,
                        "y" to smoothBox.top,
                        "width" to smoothBox.width,
                        "height" to smoothBox.height,
                        "real" to true,
                        "anchor_kind" to "face",
                        "aligned" to aligned
                    )
                )
            )
        }

        return output.copy(
            guideLines = newLines,
            guidanceCue = cue,
            // Template similarity is not geometric evidence of alignment.
            compositionProgress = boostedProgress,
            // 标注模板主体期望：分步引导序列器据此决定是否需要 SUBJECT 步
            expectsSubject = expectsSubject,
            structureObservation = structure,
            imuObservationId = rawObs?.observationId ?: 0L,
            imuSampledAtMs = rawObs?.sampledAtMs ?: 0L,
            rawRollDegrees = rawObs?.rawRollDegrees
        )
    }

    /** 切换摄像头/场景切换后调用：清空检测缓存与平滑状态 */
    fun reset() {
        faceDetector.resetCache()
        structure = null
        lastFaceId = -1L
        cachedSmoothBox = null
        lastImuId = -1; cachedRoll = null; cachedPitch = null; cachedYaw = null
        horizonEma.reset()
        pitchEma.reset()
        yawEma.reset()
        subjectEmaX.reset()
        subjectEmaY.reset()
    }

    override fun close() {
        faceDetector.close()
    }

    private fun FakeGuideLine.floatParam(key: String): Float? =
        (params[key] as? Number)?.toFloat()

    companion object {
        private const val TAG = "GeometryGuidance"

        /** EMA 平滑系数 */
        private const val HORIZON_EMA_ALPHA = 0.3f
        private const val PITCH_EMA_ALPHA = 0.3f
        private const val YAW_EMA_ALPHA = 0.25f
        private const val SUBJECT_EMA_ALPHA = 0.35f

        /** 判断构图是否「达标」（供震动反馈等使用）：主体对齐且端平 */
        fun isCompositionGood(cue: GuidanceCue, horizonAngleDeg: Float?): Boolean {
            val level = horizonAngleDeg == null || abs(horizonAngleDeg) <= GuidanceGeometry.ROLL_THRESHOLD_DEG
            return cue == GuidanceCue.SUBJECT_ALIGNED && level
        }
    }
}
