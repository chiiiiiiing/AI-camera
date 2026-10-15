package com.example.aicamera.pipeline

import com.example.aicamera.guidance.StructureObservation
import com.example.aicamera.guidance.geometry.NormalizedBox
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class V9StructureMatcherTest {
    private fun hit(id: String="reference",x: Float=.4f, h: Float=.2f): SearchHit {
        val meta=TemplateMeta(id,"portrait","closeup_portrait","unknown","unknown","unknown",false,
            "unknown","unknown","unknown","unknown","unknown","portrait","",
            referenceEligible=true,imageSha256="a".repeat(64),
            structure=ReferenceStructure(NormalizedBox(x,.3f,x+.15f,.3f+h),.75f,null))
        return SearchHit(id,.9f,.9f,meta)
    }
    private fun observation(x: Float=.4f)=StructureObservation(1,1000,1,1,NormalizedBox(x,.3f,x+.15f,.5f),aspectRatio=.75f)

    @Test fun `structure sorting matches scale rather than rewarding preexisting alignment`() {
        val matcher=V9StructureMatcher()
        val near=hit("wrong-scale",.4f,.5f)
        val farther=hit("compatible",.2f)
        assertEquals("compatible",matcher.rank(listOf(near,farther),observation(),1000).first().templateId)
    }
    @Test fun `compatible current framing remains an explicit target`() {
        val target=V9StructureMatcher().target(listOf(hit()),observation(),1000)!!
        assertEquals("current_compatible",target.source)
        assertEquals(.475f,target.center.x,.0001f)
    }
    @Test fun `target is locked and one replan can select another reference`() {
        val matcher=V9StructureMatcher()
        assertEquals("a",matcher.target(listOf(hit("a"),hit("b",.3f)),observation(),1000)!!.id)
        assertEquals("a",matcher.target(listOf(hit("b",.3f),hit("a")),observation(),1000)!!.id)
        matcher.replan()
        assertEquals("b",matcher.target(listOf(hit("a"),hit("b",.3f)),observation(),1000)!!.id)
    }
    @Test fun `locked target loses authorization when reference disappears or subject scale changes`() {
        for (missing in listOf(true,false)) {
            val matcher=V9StructureMatcher()
            assertNotNull(matcher.target(listOf(hit()),observation(),1000))
            val current=if(missing) observation() else observation().copy(face=NormalizedBox(.3f,.15f,.65f,.75f))
            assertNull(matcher.target(if(missing) listOf(hit("other")) else listOf(hit()),current,1000))
        }
    }
    @Test fun `multiple stale and clipped faces never create a target`() {
        for(obs in listOf(observation().copy(faceCount=2),observation().copy(sampledAtMs=0),
            observation().copy(face=NormalizedBox(0f,0f,.2f,.2f))))
            assertNull(V9StructureMatcher().target(listOf(hit()),obs,1000))
    }
    @Test fun `viewport conversion rejects a clipped reference face`() {
        assertNull(V9StructureMatcher.mapFace(ReferenceStructure(NormalizedBox(.01f,.2f,.2f,.4f),1f,null),.75f))
        assertNull(V9StructureMatcher.mapFace(hit().metadata!!.structure!!,2f))
    }
    @Test fun `reference sidecar is bound to reviewed original hash`() {
        val dir=Files.createTempDirectory("structure-test").toFile()
        try {
            val f=java.io.File(dir,"sidecar.json")
            fun write(hash:String,synthetic:Boolean=false) { f.writeText("""{"schema_version":1,
                "coordinate_space":"exif_upright_full_frame","records":[{"template_id":"reference",
                "image_sha256":"$hash","synthetic":$synthetic,"structure_eligible":true,
                "face_count":1,"image_width":750,"image_height":1000,
                "faces":[{"bbox":[0.4,0.3,0.15,0.2],"confidence":0.9,"clipped":false}]}]}""") }
            val metadata=mapOf("reference" to hit().metadata!!)
            write("a".repeat(64)); assertEquals(1,ReferenceStructures.load(f,metadata).size)
            write("b".repeat(64)); assertTrue(ReferenceStructures.load(f,metadata).isEmpty())
            write("a".repeat(64),true); assertTrue(ReferenceStructures.load(f,metadata).isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun `quality features use exact training luma and explicit missing face mask`() {
        val result=QualityHead.features(intArrayOf(0xff000000.toInt(),0xffffffff.toInt()),null)
        assertArrayEquals(floatArrayOf(.5f,.5f,.5f,.5f,0f,0f,0f,0f),result,.00001f)
        val face=QualityHead.features(intArrayOf(0xffff0000.toInt()),NormalizedBox(.2f,.2f,.4f,.6f))
        assertEquals(.299f,face[0],.00001f); assertEquals(.08f,face[4],.00001f); assertEquals(1f,face[7],0f)
    }
}
