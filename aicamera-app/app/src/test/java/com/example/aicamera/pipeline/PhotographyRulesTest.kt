package com.example.aicamera.pipeline

import com.example.aicamera.guidance.*
import com.example.aicamera.guidance.geometry.NormalizedBox
import org.junit.Assert.*
import org.junit.Test

class PhotographyRulesTest {
    private val layout=SceneLayout(List(9){.5f},List(9){.02f},.5f,0f,0f)
    @Test fun `invalid geometry and incomplete layout cannot propose a crop`() {
        assertFalse(PhotographyRules.preserves(NormalizedBox(Float.NaN,0f,1f,1f),null))
        assertEquals(1,PhotographyRules.candidates(SceneFamily.FOOD,NormalizedBox(.3f,.3f,.7f,.7f),
            layout.copy(edgeGrid=emptyList())).size)
    }
    @Test fun `scene limitations do not invent a landscape or complete plant target`() {
        assertEquals(listOf("no_verified_landscape_target"),MultiScenePolicy.guidanceLimitations(SceneFamily.NATURAL_LANDSCAPE,null,false,true))
        assertEquals(listOf("unverified_complete_plant"),MultiScenePolicy.guidanceLimitations(SceneFamily.PLANT,null,false,true))
        assertEquals(listOf("object_unstable_or_ambiguous"),MultiScenePolicy.guidanceLimitations(SceneFamily.FOOD,null,false,true))
    }
    @Test fun `current framing is always an option and candidate budget is bounded`() {
        for(scene in SceneFamily.entries) {
            val candidates=PhotographyRules.candidates(scene,NormalizedBox(.3f,.3f,.7f,.7f),layout)
            assertEquals("current",candidates.first().id)
            assertTrue(candidates.size<=3)
        }
    }
    @Test fun `face or vase box cannot authorize cropping a body or flowers`() {
        for(scene in listOf(SceneFamily.PORTRAIT,SceneFamily.PLANT,SceneFamily.UNKNOWN))
            assertEquals(1,PhotographyRules.candidates(scene,NormalizedBox(.4f,.4f,.6f,.6f),layout).size)
    }
    @Test fun `cup handle and food boundaries are protected by padding`() {
        val box=NormalizedBox(.02f,.2f,.9f,.8f)
        assertEquals(1,PhotographyRules.candidates(SceneFamily.FOOD,box,layout).size)
        assertFalse(PhotographyRules.preserves(NormalizedBox(.04f,.04f,.96f,.96f),box))
    }
    @Test fun `still life crop requires a complete measured subject`() {
        assertEquals(1,PhotographyRules.candidates(SceneFamily.STILL_LIFE,null,layout).size)
        val candidates=PhotographyRules.candidates(SceneFamily.STILL_LIFE,NormalizedBox(.3f,.25f,.7f,.75f),layout)
        assertEquals(2,candidates.size)
        assertEquals("centered",candidates[1].id)
    }
    @Test fun `quiet side permits an optional off center candidate without forcing thirds`() {
        val quiet=layout.copy(edgeGrid=listOf(.001f,.03f,.09f,.001f,.03f,.09f,.001f,.03f,.09f))
        val candidates=PhotographyRules.candidates(SceneFamily.FOOD,NormalizedBox(.4f,.3f,.6f,.7f),quiet)
        assertTrue(candidates.any{it.id=="negative_space"})
        assertTrue(candidates.all{it.id!="rule_of_thirds"})
    }
    @Test fun `landscape diagnostic trims retain current center and disclose missing semantic protection`() {
        val candidates=PhotographyRules.candidates(SceneFamily.NATURAL_LANDSCAPE,null,layout)
        for(c in candidates.drop(1)) {
            assertEquals(.5f,c.crop.centerX,.0001f);assertEquals(.5f,c.crop.centerY,.0001f)
            assertTrue("unverified_semantic_boundaries" in c.reasons)
        }
    }
    @Test fun `quality distinguishes subject highlights from a white background`() {
        val pixels=IntArray(100*100){-1}
        for(y in 30..69)for(x in 30..69)pixels[y*100+x]=0xff777777.toInt()
        val q=PhotographyRules.quality(pixels,100,100,NormalizedBox(.3f,.3f,.7f,.7f))
        assertEquals(0f,q[3],.0001f)
        assertTrue(q[0]>.8f)
    }
    @Test fun `spatial features keep position and scene separate from global brightness`() {
        val pixels=IntArray(64*64){i->if(i%64<32)0xffff0000.toInt() else 0xff0000ff.toInt()}
        val f=FramingPreferenceModel.features(pixels,64,64,NormalizedBox(.2f,.3f,.4f,.5f),SceneFamily.FOOD)
        assertEquals(93,f.size);assertEquals(1f,f[0],.0001f);assertEquals(0f,f[2],.0001f)
        assertEquals(0f,f[15],.0001f);assertEquals(1f,f[17],.0001f)
        assertEquals(1f,f[84],.0001f);assertEquals(.2f,f[85],.0001f);assertEquals(1f,f[91],.0001f)
    }
    @Test fun `background translation is established from image evidence`() {
        val before=FloatArray(2304){i->((i*137+i/48*31)%251)/250f}
        val after=FloatArray(2304){i->before[(i/48)*48+(i%48-2).coerceIn(0,47)]}
        val shift=ExecutionImageAnalyzer.translation(before,after,null,null)
        assertNotNull(shift);assertEquals(2/48f,shift!!.first,.0001f);assertEquals(0f,shift.second,.0001f)
        assertNull(ExecutionImageAnalyzer.translation(FloatArray(2304){.5f},FloatArray(2304){.5f},null,null))
    }
    @Test fun `motion pixels align with detector timestamps at different analysis rates`() {
        val analyzer=ExecutionImageAnalyzer();val guard=ExecutionQualityGuard()
        val raw=IntArray(2304){i->val value=(i*137+i/48*31)%251;0xff000000.toInt() or (value shl 16) or (value shl 8) or value}
        fun shifted(x:Int)=IntArray(2304){i->raw[i/48*48+(i%48-x).coerceIn(0,47)]}
        val first=analyzer.observe(raw,48,48,500,1,SceneFamily.FOOD,1,NormalizedBox(.3f,.3f,.6f,.6f),null,null,1)
        assertEquals(ExecutionComparison.INITIAL,guard.compare(first))
        val mid=analyzer.observe(shifted(1),48,48,1000,1,SceneFamily.FOOD,1,NormalizedBox(.3f,.3f,.6f,.6f),null,null,1)
        assertEquals(ExecutionComparison.COMPARABLE,guard.compare(mid))
        val last=analyzer.observe(shifted(2),48,48,1500,1,SceneFamily.FOOD,1,
            NormalizedBox(.3f+2/48f,.3f,.6f+2/48f,.6f),null,null,2)
        assertEquals(first.observationId,last.comparedObservationId)
        assertEquals(ExecutionComparison.COMPARABLE,guard.compare(last))
    }
}
