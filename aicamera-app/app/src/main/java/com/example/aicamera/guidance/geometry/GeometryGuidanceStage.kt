package com.example.aicamera.guidance.geometry

import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.aicamera.guidance.GuidanceCue
import com.example.aicamera.pipeline.FakeGuideLine
import com.example.aicamera.pipeline.FakeInferenceOutput
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
    private val horizonEma = EmaSmoother(HORIZON_EMA_ALPHA)
    /** 俯仰角 EMA */
    private val pitchEma = EmaSmoother(PITCH_EMA_ALPHA)
    /** 偏航角 EMA（磁力计噪声更大，平滑更强） */
    private val yawEma = EmaSmoother(YAW_EMA_ALPHA)

    /** 主体框中心 EMA（检测限频 4Hz + 检测器本身有抖动） */
    private val subjectEmaX = EmaSmoother(SUBJECT_EMA_ALPHA)
    private val subjectEmaY = EmaSmoother(SUBJECT_EMA_ALPHA)

    /** ★ 偏航目标：进入引导时记录当前朝向，后续提示用户保持该朝向 */
    private var yawTargetDeg: Float? = null

    /**
     * 把几何信号应用到推理输出。
     *
     * @param output 推理器输出（仅处理 FakeInferenceOutput，其他类型透传）
     * @param imageProxy 当前帧（人脸检测用）
     * @param isFrontCamera 是否前摄
     */
    fun apply(output: Any?, imageProxy: ImageProxy, isFrontCamera: Boolean): Any? {
        if (output !is FakeInferenceOutput) return output

        // ── 1. 真实水平/俯仰/偏航观测（含 isUpright/confidence） ──
        // ★ 传感器已做圆周平滑，此处保留 EMA 做二次去抖
        val rawObs = horizonSensor.latestObservation()
        val rawAngle = rawObs?.rollDegrees
        val smoothAngle = rawAngle?.let { horizonEma.add(it) }
        val smoothPitch = rawObs?.pitchDegrees?.let { pitchEma.add(it) }
        val smoothYaw = rawObs?.yawDegrees?.let { yawEma.add(it) }

        // ── 2. 真实主体框 ──
        val rawBox = faceDetector.detectIfDue(imageProxy, isFrontCamera)
        val smoothBox = rawBox?.let {
            val cx = subjectEmaX.add(it.centerX)
            val cy = subjectEmaY.add(it.centerY)
            NormalizedBox(
                left = (cx - it.width / 2f).coerceIn(0f, 1f),
                top = (cy - it.height / 2f).coerceIn(0f, 1f),
                right = (cx + it.width / 2f).coerceIn(0f, 1f),
                bottom = (cy + it.height / 2f).coerceIn(0f, 1f)
            )
        }
        if (rawBox == null) {
            // 主体丢失时缓慢衰减，避免指令跳变
            subjectEmaX.reset()
            subjectEmaY.reset()
        }

        // ── 3. 提取模板目标区参数（若有） ──
        val targetLine = output.guideLines.firstOrNull { it.type == "target_zone" }
        val targetCx = targetLine?.floatParam("center_x") ?: 0.5f
        val targetCy = targetLine?.floatParam("center_y") ?: 0.5f
        val targetTol = targetLine?.floatParam("radius") ?: 0.12f

        // 模板是否期望主体：有人像类占位框即期望主体
        val expectsSubject = output.guideLines.any { it.type == "subject_box" }

        // ★ 记录偏航目标：首次获得有效偏航时锁定当前朝向
        if (yawTargetDeg == null && smoothYaw != null) {
            yawTargetDeg = smoothYaw
        }

        // ── 4. 计算指令与进度 ──
        val cue = GuidanceGeometry.guidanceCue(
            subjectCx = smoothBox?.centerX,
            subjectCy = smoothBox?.centerY,
            expectsSubject = expectsSubject,
            targetCx = targetCx,
            targetCy = targetCy,
            targetTolerance = targetTol,
            horizonAngleDeg = smoothAngle,
            pitchAngleDeg = smoothPitch,
            yawAngleDeg = smoothYaw,
            yawTargetDeg = yawTargetDeg
        )
        val geometryProgress = GuidanceGeometry.compositionProgress(
            subjectCx = smoothBox?.centerX,
            subjectCy = smoothBox?.centerY,
            targetCx = targetCx,
            targetCy = targetCy,
            horizonAngleDeg = smoothAngle,
            pitchAngleDeg = smoothPitch,
            yawAngleDeg = smoothYaw,
            yawTargetDeg = yawTargetDeg
        )
        // ★ 指令-进度对齐：SUBJECT_ALIGNED 时提升到 ALIGNED 阈值之上，
        //   GuidanceStateMachine 才能进入 ALIGNED 相位（触发达标震动/绿闪）
        val boostedProgress = geometryProgress?.let {
            GuidanceGeometry.progressWithAlignedBoost(cue, it)
        }

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
                        val aligned = kotlin.math.hypot(
                            smoothBox.centerX - targetCx,
                            smoothBox.centerY - targetCy
                        ) <= targetTol
                        line.copy(
                            params = mapOf(
                                "x" to smoothBox.left,
                                "y" to smoothBox.top,
                                "width" to smoothBox.width,
                                "height" to smoothBox.height,
                                "real" to true,
                                "aligned" to aligned
                            )
                        )
                    } else line
                }
                else -> line
            }
        }.toMutableList()

        // 模板没给主体框但检测到了主体（如风景照里有人）→ 也画出来
        if (smoothBox != null && !expectsSubject) {
            val aligned = kotlin.math.hypot(
                smoothBox.centerX - targetCx,
                smoothBox.centerY - targetCy
            ) <= targetTol
            newLines.add(
                FakeGuideLine(
                    type = "subject_box", visible = true,
                    params = mapOf(
                        "x" to smoothBox.left,
                        "y" to smoothBox.top,
                        "width" to smoothBox.width,
                        "height" to smoothBox.height,
                        "real" to true,
                        "aligned" to aligned
                    )
                )
            )
        }

        return output.copy(
            guideLines = newLines,
            guidanceCue = cue,
            // 有几何信号用几何进度（含对齐提升）；两者都缺回退模板分
            compositionProgress = boostedProgress ?: output.compositionProgress,
            // 标注模板主体期望：分步引导序列器据此决定是否需要 SUBJECT 步
            expectsSubject = expectsSubject
        )
    }

    /** 切换摄像头/场景切换后调用：清空检测缓存与平滑状态 */
    fun reset() {
        faceDetector.resetCache()
        horizonEma.reset()
        pitchEma.reset()
        yawEma.reset()
        subjectEmaX.reset()
        subjectEmaY.reset()
        yawTargetDeg = null
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
