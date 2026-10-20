package com.example.aicamera.replay

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * 桌面功能回放台入口（Gradle 任务 :app:replayEngine）。
 *
 * 在电脑 JVM 上跑真实 ONNX 模型 + 406 模板索引 + 构图状态机，
 * 把每张输入图的检索/检测/引导时间线写成 JSON，供 ui-review 网站「功能回放」页可视化。
 *
 * 用法：
 * ```
 * ./gradlew.bat :app:replayEngine
 * ./gradlew.bat :app:replayEngine --args="--images <图片目录或文件> --out <json路径> --frames 30"
 * ```
 * 默认：images = app/src/main/res/drawable-nodpi 下的 gallery_*.png（随仓库发布的社区示例图），
 * out = ../ui-review/replay-data/replay.json。
 */
fun main(args: Array<String>) {
    val options = parseArgs(args)
    val assetsDir = File(options.assets)
    val imagesInput = File(options.images)
    val outFile = File(options.out)

    require(assetsDir.isDirectory) { "资产目录不存在: ${assetsDir.absolutePath}" }
    require(imagesInput.exists()) { "图片路径不存在: ${imagesInput.absolutePath}" }

    val images = when {
        imagesInput.isFile -> listOf(imagesInput)
        else -> imagesInput.listFiles { f -> f.isFile && f.name.endsWith(".png", ignoreCase = true) }
            ?.filter { it.name.startsWith("gallery_") }
            ?.sortedBy { it.name }
            .orEmpty()
    }
    require(images.isNotEmpty()) { "没有找到输入图片（目录模式只取 gallery_*.png）: ${imagesInput.absolutePath}" }

    ReplayEngine(assetsDir).use { engine ->
        println("回放引擎就绪: 模板 ${engine.templateCount} 条, NanoDet ${if (engine.detectorAvailable) "已加载" else "不可用（object_detector.onnx 缺失）"}")
        val imagesJson = JSONArray()
        for (image in images) {
            println("分析: ${image.name}")
            val analysis = engine.analyze(image)
            val play = ScenePlayer.play(analysis)
            imagesJson.put(
                JSONObject()
                    .put("image", analysis.imageName)
                    .put("width", analysis.width)
                    .put("height", analysis.height)
                    .put("sha256", analysis.sha256)
                    .put("top5", JSONArray(analysis.top5.map { hit ->
                        JSONObject()
                            .put("templateId", hit.templateId)
                            .put("cosine", hit.cosine)
                            .put("rerankScore", hit.rerankScore)
                            .put("photoType", hit.photoType ?: JSONObject.NULL)
                            .put("sceneCategory", hit.sceneCategory ?: JSONObject.NULL)
                            .put("compositionType", hit.compositionType ?: JSONObject.NULL)
                            .put("visualSummary", hit.visualSummary ?: JSONObject.NULL)
                            .put("referenceEligible", hit.referenceEligible)
                    }))
                    .put("sceneContext", JSONObject()
                        .put("photoType", analysis.sceneContext.photoType ?: JSONObject.NULL)
                        .put("sceneCategory", analysis.sceneContext.sceneCategory ?: JSONObject.NULL)
                        .put("sceneConfidence", analysis.sceneContext.sceneConfidence)
                        .put("preferredComposition", analysis.sceneContext.preferredComposition ?: JSONObject.NULL)
                        .put("lightingQuality", analysis.sceneContext.lightingQuality ?: JSONObject.NULL))
                    .put("detections", JSONArray(analysis.detections.map { d ->
                        JSONObject()
                            .put("classId", d.classId)
                            .put("confidence", d.confidence)
                            .put("left", d.left).put("top", d.top)
                            .put("right", d.right).put("bottom", d.bottom)
                    }))
                    .put("layout", JSONObject()
                        .put("lumaGrid", JSONArray(analysis.layout.lumaGrid))
                        .put("edgeGrid", JSONArray(analysis.layout.edgeGrid))
                        .put("meanLuma", analysis.layout.meanLuma)
                        .put("darkFraction", analysis.layout.darkFraction)
                        .put("lightFraction", analysis.layout.lightFraction))
                    .put("faceTarget", analysis.faceTarget?.let { t ->
                        JSONObject().put("x", t.x).put("y", t.y).put("aspectRatio", t.aspectRatio)
                    } ?: JSONObject.NULL)
                    .put("subject", analysis.subject?.let { s ->
                        JSONObject()
                            .put("source", s.source)
                            .put("left", s.left).put("top", s.top)
                            .put("right", s.right).put("bottom", s.bottom)
                            .put("targetX", s.targetX).put("targetY", s.targetY)
                            .put("tolerance", s.tolerance)
                    } ?: JSONObject.NULL)
                    .put("timeline", JSONArray(play.records.map { r ->
                        JSONObject()
                            .put("frame", r.frame)
                            .put("tMs", r.tMs)
                            .put("state", r.state)
                            .put("phase", r.phase)
                            .put("action", r.action ?: JSONObject.NULL)
                            .put("feedback", r.feedback ?: JSONObject.NULL)
                            .put("rollDegrees", r.rollDegrees)
                            .put("pitchDegrees", r.pitchDegrees)
                            .put("yawDegrees", r.yawDegrees)
                            .put("zoomFactor", r.zoomFactor)
                            .put("subjectX", r.subjectX ?: JSONObject.NULL)
                            .put("subjectY", r.subjectY ?: JSONObject.NULL)
                    }))
                    .put("outcome", JSONObject()
                        .put("state", play.finalState)
                        .put("readyAtFrame", play.readyAtFrame ?: JSONObject.NULL)
                        .put("capturedAtFrame", play.capturedAtFrame ?: JSONObject.NULL)
                        .put("savedUri", play.savedUri ?: JSONObject.NULL)
                        .put("zoomRatio", play.zoomRatio ?: JSONObject.NULL)
                        .put("zoomReasons", JSONArray(play.zoomReasons))
                        .put("phasesVisited", JSONArray(play.phasesVisited))
                        .put("statesVisited", JSONArray(play.statesVisited)))
            )
        }

        val doc = JSONObject()
            .put("generatedAt", Instant.now().toString())
            .put("engine", "replay-engine-v1")
            .put("assets", JSONObject()
                .put("model", "student_model.onnx")
                .put("templates", engine.templateCount)
                .put("assetsDir", assetsDir.absolutePath))
            .put("images", imagesJson)

        outFile.parentFile?.mkdirs()
        outFile.writeText(doc.toString(2))
        println("回放 JSON 已写出: ${outFile.absolutePath}（${images.size} 张图 × ${options.frames} 帧）")
    }
}

private data class Options(
    val assets: String = "app/src/main/assets/ai/v9",
    val images: String = "app/src/main/res/drawable-nodpi",
    val out: String = "../ui-review/replay-data/replay.json",
    val frames: Int = 30,
)

private fun parseArgs(args: Array<String>): Options {
    var assets = Options().assets
    var images = Options().images
    var out = Options().out
    var frames = Options().frames
    var i = 0
    while (i < args.size) {
        when (val key = args[i]) {
            "--assets" -> { assets = args.getOrElse(++i) { fail(key) } }
            "--images" -> { images = args.getOrElse(++i) { fail(key) } }
            "--out" -> { out = args.getOrElse(++i) { fail(key) } }
            "--frames" -> { frames = args.getOrElse(++i) { fail(key) }.toIntOrNull() ?: fail(key) }
            "--help", "-h" -> {
                println("用法: --assets <资产目录> --images <图片目录或文件> --out <json路径> --frames <帧数>")
                kotlin.system.exitProcess(0)
            }
            else -> fail(key)
        }
        i++
    }
    return Options(assets, images, out, frames)
}

private fun fail(key: String): Nothing = throw IllegalArgumentException("缺少参数值: $key（--help 查看用法）")
