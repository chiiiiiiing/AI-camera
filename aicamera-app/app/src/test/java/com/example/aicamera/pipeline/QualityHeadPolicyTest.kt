package com.example.aicamera.pipeline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

class QualityHeadPolicyTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun withBundle(block: (File,JSONObject,JSONObject)->Unit) {
        val parent=File(System.getProperty("user.dir"),"build/quality-policy-tests").apply { mkdirs() }
        val dir=Files.createTempDirectory(parent.toPath(),"case-").toFile()
        try {
            val encoder=byteArrayOf(1,2,3,4)
            File(dir,"student_model.onnx").writeBytes(encoder)
            val buffer=ByteBuffer.allocate((64*520+64+64+1)*4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(64*520+64+64) { buffer.putFloat(0f) }; buffer.putFloat(.75f)
            val head=JSONObject().put("schema_version",1).put("enabled",true).put("embedding_dim",512).put("hidden_dim",64)
                .put("feature_names",JSONArray(QualityHead.FEATURE_NAMES)).put("activation","relu").put("feature_normalization","none")
                .put("weight_encoding","float32-le-base64").put("weights_f32_base64",java.util.Base64.getEncoder().encodeToString(buffer.array()))
                .put("encoder_sha256",sha(encoder)).put("enabled_scenes",JSONArray(listOf("portrait")))
            val metrics=JSONObject().put("acceptance_cluster_count",30).put("pairwise_agreement",.8).put("bootstrap_lower95",.7)
                .put("baseline_pairwise_agreement",.6).put("hard_constraints_passed",true).put("severe_defect_non_regression",true)
                .put("scene_cluster_counts",JSONObject().put("portrait",30)).put("coverage",.8).put("rejection_rate",.2)
            val policy=JSONObject().put("schema_version",1).put("enabled",true).put("encoder_sha256",sha(encoder))
                .put("enabled_scenes",JSONArray(listOf("portrait"))).put("metrics",metrics)
            save(dir,policy,head)
            block(dir,policy,head)
        } finally { dir.deleteRecursively() }
    }
    private fun save(dir:File,policy:JSONObject,head:JSONObject) {
        val bytes=head.toString().toByteArray()
        File(dir,"quality_head.json").writeBytes(bytes)
        policy.put("head_sha256",sha(bytes)); File(dir,"quality_policy.json").writeText(policy.toString())
    }
    @Test fun `valid verified contract loads raw score and keeps crop advice disabled`() = withBundle { dir,_,_ ->
        val head=QualityHead.load(dir)
        assertNotNull(head)
        assertEquals(.75f,head!!.score(FloatArray(512),FloatArray(8))!!,0f)
        assertFalse(head.cropDiagnosticsEnabled)
    }
    @Test fun `disabled policy and failed actual metrics reject even with trusted booleans`() {
        for(kind in 0..3) withBundle { dir,policy,head ->
            when(kind) {
                0 -> policy.put("enabled",false)
                1 -> policy.getJSONObject("metrics").put("acceptance_cluster_count",29)
                2 -> policy.getJSONObject("metrics").put("baseline_pairwise_agreement",.8)
                else -> policy.getJSONObject("metrics").put("hard_constraints_passed",false)
            }
            save(dir,policy,head); assertNull(QualityHead.load(dir))
        }
    }
    @Test fun `corrupt hash feature order and activation cannot silently change inference`() {
        for(kind in 0..4) withBundle { dir,policy,head ->
            when(kind) {
                0 -> head.put("encoder_sha256","b".repeat(64))
                1 -> head.put("feature_names",JSONArray(QualityHead.FEATURE_NAMES.reversed()))
                2 -> head.put("activation","sigmoid")
                3 -> head.put("enabled",false)
                else -> head.put("enabled_scenes",JSONArray(listOf("food")))
            }
            save(dir,policy,head); assertNull(QualityHead.load(dir))
        }
    }
    @Test fun `crop advice cannot be enabled without a validated consumer`() = withBundle { dir,policy,head ->
        policy.put("crop_advice_enabled",true); save(dir,policy,head)
        assertNull(QualityHead.load(dir))
    }
}
