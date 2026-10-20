package com.example.aicamera.replay

import com.example.aicamera.guidance.AdaptiveSubjectPlanner
import com.example.aicamera.guidance.ObjectObservation
import com.example.aicamera.pipeline.MultiScenePolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real ONNX/pixel regression: no synthetic subject substitution. Not a physical-device test. */
class AutomaticDetectionSmokeTest {
    @Test fun `bundled photos pass real detections to the automatic planner without template annotations`() {
        val root = if (File("src/main/assets").isDirectory) File(".") else File("app")
        val model = File(root, "src/main/assets/ai/v9/object_detector.onnx")
        val images = File(root, "src/main/res/drawable-nodpi").listFiles()!!
            .filter { it.name.startsWith("gallery_") && it.extension == "png" }.sortedBy { it.name }
        var targets = 0
        NanoDetJvm.create(model)!!.use { detector ->
            for (file in images) {
                val pixels = PngReader.read(file)
                val detections = detector.detect(pixels.rgba, pixels.width, pixels.height)
                val primary = MultiScenePolicy.primaryForGuidance(detections)
                val observation = primary?.let { ObjectObservation(1, 1000, 1, 1, it.classId, it.box,
                    it.confidence, 2, pixels.width.toFloat() / pixels.height, minimumConfidence = .4f) }
                val target = AdaptiveSubjectPlanner().objectTarget(observation, 1000)
                println("REAL_ONNX ${file.name}: detections=${detections.size}, primary=${primary?.classId}, goal=${target?.center}")
                println(detections.take(4).joinToString { "class=${it.classId}, confidence=${it.confidence}, box=${it.box}" })
                if (target != null) {
                    targets++
                    assertTrue(target.accepts(observation!!))
                    assertEquals("live_object_rule", target.source)
                }
            }
        }
        assertTrue("No real photo produced a detector-backed target", targets > 0)
    }
}
