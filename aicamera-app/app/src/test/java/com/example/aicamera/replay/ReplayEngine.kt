package com.example.aicamera.replay

import com.example.aicamera.filter.FrameColorStatsExtractor
import com.example.aicamera.guidance.SceneLayout
import com.example.aicamera.pipeline.DetectionViewportTransform
import com.example.aicamera.pipeline.MultiScenePolicy
import com.example.aicamera.pipeline.NativeViewportPixels
import com.example.aicamera.pipeline.SceneContext
import com.example.aicamera.pipeline.SearchHit
import com.example.aicamera.pipeline.V9EmbeddingRuntime
import com.example.aicamera.pipeline.V9FaceAnchor
import com.example.aicamera.pipeline.V9ReferencePolicy
import com.example.aicamera.pipeline.V9SceneContextEstimator
import com.example.aicamera.pipeline.V9TemplateIndex
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/**
 * 桌面回放引擎 — 在电脑 JVM 上复现手机端 V9 链路的纯 Kotlin 部分：
 *
 * ```
 * 图片 → NCHW 224 预处理（半像素双线性，与 Python PIL 对齐）
 *      → 真 student_model.onnx embedding → 406 模板检索 top32
 *      → 场景上下文估计 → 规则重排 top5
 * 并行：NanoDet 416 letterbox 物体检测 + 3×3 明度/边缘场景布局
 * ```
 *
 * Android 专属部分（Bitmap/Skia 缩放、SystemClock）按资产包注释的等价数学复现。
 * 桌面预处理与训练/评估的 PIL 双线性一致，与真机 Bitmap 路径像素级不保证逐位相同，
 * 因此检索结果用于「功能行为」回放，不宣称与真机逐位一致。
 */
class ReplayEngine(assetsDir: File) : Closeable {

    private val runtime = V9EmbeddingRuntime(File(assetsDir, "student_model.onnx").absolutePath)
    private val index = V9TemplateIndex(File(assetsDir, "template_index")).also {
        require(it.load()) { "模板索引加载失败: ${assetsDir.absolutePath}/template_index" }
    }
    private val sceneEstimator = V9SceneContextEstimator()
    private val detector: NanoDetJvm? = NanoDetJvm.create(File(assetsDir, "object_detector.onnx"))

    val templateCount: Int get() = index.templateCount
    val detectorAvailable: Boolean get() = detector != null

    /** 检索命中的对外摘要（回放 JSON 用） */
    data class HitSummary(
        val templateId: String,
        val cosine: Float,
        val rerankScore: Float,
        val photoType: String?,
        val sceneCategory: String?,
        val compositionType: String?,
        val visualSummary: String?,
        val referenceEligible: Boolean,
    ) {
        companion object {
            fun of(hit: SearchHit) = HitSummary(
                templateId = hit.templateId,
                cosine = hit.cosine,
                rerankScore = hit.rerankScore,
                photoType = hit.metadata?.photoType,
                sceneCategory = hit.metadata?.sceneCategory,
                compositionType = hit.metadata?.compositionType,
                visualSummary = hit.metadata?.visualSummary,
                referenceEligible = hit.metadata?.referenceEligible == true,
            )
        }
    }

    /** 检测结果对外摘要 */
    data class DetectionSummary(val classId: Int, val confidence: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** 回放引导主体（来源明确标注；合成主体只用于无真实主体时的完整流程演示） */
    data class SubjectInfo(
        val source: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val targetX: Float,
        val targetY: Float,
        val tolerance: Float,
        val startX: Float,
        val startY: Float,
    ) {
        companion object {
            const val SOURCE_FACE = "face_anchor"
            const val SOURCE_DETECTION = "detection"
            const val SOURCE_SYNTHETIC = "synthetic"
        }
    }

    /** 单张图的完整回放素材 */
    data class ImageResult(
        val imageName: String,
        val width: Int,
        val height: Int,
        val sha256: String,
        val top5: List<HitSummary>,
        val hits: List<SearchHit>,
        val sceneContext: SceneContext,
        val stats: com.example.aicamera.filter.FrameColorStats,
        val detections: List<DetectionSummary>,
        val layout: SceneLayout,
        val faceTarget: V9FaceAnchor?,
        val subject: SubjectInfo?,
    ) {
        val top1: HitSummary? get() = top5.firstOrNull()
    }

    fun analyze(imageFile: File): ImageResult {
        val decoded = PngReader.read(imageFile)
        val width = decoded.width
        val height = decoded.height
        val pixels = decoded.rgba

        // 1) 真模型 embedding（预处理与 Python PIL 对齐）
        val embedding = runtime.embed(nchw224(pixels, width, height))
        check(V9EmbeddingRuntime.isUsableEmbedding(embedding)) { "embedding 不可用: ${imageFile.name}" }

        // 2) 检索 → 场景上下文 → 重排（与 V9Inference.predict 相同的顺序与参数）
        val candidates = index.search(embedding, topK = 32)
        val stats = FrameColorStatsExtractor.extractPixels(pixels, width, height, sampleStride = 4)
        val context = sceneEstimator.estimate(candidates, stats)
        val top5 = index.rerank(candidates, context, limit = 5)

        // 3) NanoDet 物体检测（等比例 416 letterbox，同 detectNative 协议）
        val rawDetections = detector?.detect(pixels, width, height).orEmpty()
        val detections = rawDetections.map {
            DetectionSummary(it.classId, it.confidence, it.box.left, it.box.top, it.box.right, it.box.bottom)
        }

        // 4) 3×3 场景布局
        val layout = MultiScenePolicy.layout(pixels, width, height)

        // 5) 人像人脸锚点目标（与 V9Inference 相同门槛：portrait + eligible + cosine ≥ 0.75）
        val top = top5.firstOrNull()
        val faceTarget = top?.metadata?.takeIf { it.photoType == "portrait" }?.let { meta ->
            V9ReferencePolicy.faceTarget(meta, top.cosine, width.toFloat() / height.toFloat())
        }

        // 6) 引导主体：人脸锚点 → 主导物体检测 → 合成演示主体（无真实主体时的完整流程演示，明确标注）
        val subject = selectSubject(faceTarget, rawDetections, top?.metadata, width, height)

        return ImageResult(
            imageName = imageFile.name,
            width = width,
            height = height,
            sha256 = sha256(imageFile),
            top5 = top5.map(HitSummary::of),
            hits = top5,
            sceneContext = context,
            stats = stats,
            detections = detections,
            layout = layout,
            faceTarget = faceTarget,
            subject = subject,
        )
    }

    /** subjectPosition 元数据 → 归一化目标位置（仅识别明确的三分/居中构图，其余不编造） */
    private val SUBJECT_POSITION_TARGETS = mapOf(
        "third_left" to Pair(0.33f, 0.5f),
        "third_right" to Pair(0.67f, 0.5f),
        "top_third" to Pair(0.5f, 0.33f),
        "bottom_third" to Pair(0.5f, 0.67f),
        "center" to Pair(0.5f, 0.5f),
    )

    private fun selectSubject(
        faceTarget: V9FaceAnchor?,
        detections: List<com.example.aicamera.pipeline.ObjectDetection>,
        meta: com.example.aicamera.pipeline.TemplateMeta?,
        width: Int,
        height: Int,
    ): SubjectInfo? {
        val aspect = width.toFloat() / height.toFloat()
        // 人像：人脸锚点目标
        if (faceTarget != null) {
            val halfW = 0.09f
            val halfH = 0.09f / aspect
            return SubjectInfo(
                source = SubjectInfo.SOURCE_FACE,
                left = (faceTarget.x - halfW).coerceIn(0.02f, 0.98f),
                top = (faceTarget.y - halfH).coerceIn(0.02f, 0.98f),
                right = (faceTarget.x + halfW).coerceIn(0.02f, 0.98f),
                bottom = (faceTarget.y + halfH).coerceIn(0.02f, 0.98f),
                targetX = faceTarget.x,
                targetY = faceTarget.y,
                tolerance = 0.08f,
                startX = (faceTarget.x - 0.22f).coerceIn(0.1f, 0.9f),
                startY = (faceTarget.y + 0.14f).coerceIn(0.1f, 0.9f),
            )
        }
        // 物体场景：主导检测 + 模板主体位置
        val dominant = MultiScenePolicy.dominant(detections)
        if (dominant != null) {
            val target = SUBJECT_POSITION_TARGETS[meta?.subjectPosition] ?: return syntheticSubject()
            val cx = dominant.box.centerX
            val cy = dominant.box.centerY
            return SubjectInfo(
                source = SubjectInfo.SOURCE_DETECTION,
                left = dominant.box.left,
                top = dominant.box.top,
                right = dominant.box.right,
                bottom = dominant.box.bottom,
                targetX = target.first,
                targetY = target.second,
                tolerance = 0.08f,
                startX = cx,
                startY = cy,
            )
        }
        return syntheticSubject()
    }

    /** 演示主体：无真实主体时合成一个，保证完整拍摄流程可演示；来源在 JSON/网页明确标注 */
    private fun syntheticSubject(): SubjectInfo = SubjectInfo(
        source = SubjectInfo.SOURCE_SYNTHETIC,
        left = 0.30f,
        top = 0.58f,
        right = 0.54f,
        bottom = 0.78f,
        targetX = 0.52f,
        targetY = 0.45f,
        tolerance = 0.08f,
        startX = 0.42f,
        startY = 0.68f,
    )

    override fun close() {
        runtime.close()
        detector?.close()
    }

    /**
     * 与 FramingPreferenceModel.nchw224(pixels,w,h) 相同的数学：
     * NativeViewportPixels.resize（float64 半像素双线性）→ RGB NCHW，归一化 (v/255-0.5)/0.5。
     * 原函数需要 FramingPreferenceModel 实例（本机无 head 权重时 load 返回 null），
     * 因此在此按同参数复现。
     */
    private fun nchw224(pixels: IntArray, width: Int, height: Int): FloatArray {
        val transform = DetectionViewportTransform(width, height, 224, 224, 0, 0)
        val resized = NativeViewportPixels.resize(pixels, width, height, transform)
        return FloatArray(3 * 224 * 224) { i ->
            val channel = i / (224 * 224)
            val pixel = resized[i % (224 * 224)]
            ((pixel ushr (16 - channel * 8)) and 255) / 127.5f - 1f
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
