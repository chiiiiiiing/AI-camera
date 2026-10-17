package com.example.aicamera.pipeline

import com.example.aicamera.guidance.*
import com.example.aicamera.guidance.geometry.NormalizedBox
import org.junit.Assert.*
import org.junit.Test

class PhotographyTechniquePolicyTest {
    private val layout = SceneLayout(List(9) { .5f }, List(9) { .02f }, .5f, 0f, 0f)
    private fun scene(t: Long = 1000, family: SceneFamily = SceneFamily.NATURAL_LANDSCAPE,
        classId: Int? = null, tracking: Int = 4, viewport: Long = 2) = SceneAnalysisObservation(
        t, t, viewport, family, layout, classId?.let {
            ObjectObservation(t, t, viewport, tracking, it, NormalizedBox(.3f, .3f, .6f, .6f), .9f, 3, .75f)
        })
    private fun visual(edges: Float = 0f, bright: Float = 0f, symmetry: Float? = null,
        difference: Float? = null, upper: Float? = null, lower: Float? = null) = TechniqueVisualEvidence(
        TechniqueRegionEvidence(200, edges, bright, .5f), TechniqueRegionEvidence(200, edges, bright, .5f),
        .5f, .5f, difference, symmetry, upper, lower, 72, 96)
    private fun stable(p: PhotographyTechniquePolicy, s: SceneAnalysisObservation,
        v: TechniqueVisualEvidence? = null): PhotographyTechniqueAdvice? {
        p.recommend(s, null, v, s.sampledAtMs)
        val next = s.copy(observationId=s.observationId+500, sampledAtMs=s.sampledAtMs+500,
            objectObservation=s.objectObservation?.copy(observationId=s.observationId+500, sampledAtMs=s.sampledAtMs+500))
        return p.recommend(next, null, v, next.sampledAtMs)
    }

    @Test fun `first suggestion requires two independent samples spanning half a second`() {
        val p=PhotographyTechniquePolicy()
        assertNull(p.recommend(scene(),null,null,1000))
        repeat(5) { assertNull(p.recommend(scene(),null,null,1200)) }
        assertNull(p.recommend(scene(1400),null,null,1400))
        assertEquals("landscape_depth",p.recommend(scene(1500),null,null,1500)!!.id)
    }

    @Test fun `duplicate samples never renew tip expiry and old samples do not advance it`() {
        val p=PhotographyTechniquePolicy()
        val tip=stable(p,scene())!!
        assertNull(p.recommend(scene(1200),null,null,1600))
        assertEquals(tip.sampledAtMs,p.recommend(scene(1500),null,null,2000)!!.sampledAtMs)
        assertNull(p.recommend(scene(1500),null,null,3001))
    }

    @Test fun `unknown future stale and changed viewport immediately withdraw tips`() {
        for(invalid in listOf(scene(2000,SceneFamily.UNKNOWN),scene(4000),scene(1),scene(2000,viewport=3))) {
            val p=PhotographyTechniquePolicy()
            assertNotNull(stable(p,scene()))
            assertNull(p.recommend(invalid,null,null,2000))
        }
    }

    @Test fun `scene or tracked food change cannot inherit previous recipe`() {
        val p=PhotographyTechniquePolicy()
        assertEquals("food_surface_angle",stable(p,scene(family=SceneFamily.FOOD,classId=53))!!.id)
        assertNull(p.recommend(scene(2000,SceneFamily.FOOD,53,tracking=9),null,null,2000))
        assertNull(p.recommend(scene(2500,SceneFamily.URBAN_LANDSCAPE),null,null,2500))
    }

    @Test fun `food angles need actual stable detected class rather than semantic food label`() {
        assertEquals("still_life_corners",stable(PhotographyTechniquePolicy(),scene(family=SceneFamily.FOOD))!!.id)
        assertEquals("food_surface_angle",stable(PhotographyTechniquePolicy(),scene(family=SceneFamily.FOOD,classId=53))!!.id)
        for(c in listOf(48,52,55)) {
            val a=stable(PhotographyTechniquePolicy(),scene(family=SceneFamily.FOOD,classId=c))!!
            assertEquals("food_layers_angle",a.id)
            assertTrue(a.suggestion.startsWith("如果"))
            assertTrue("layers_not_verified" in a.reasonCodes)
        }
    }

    @Test fun `expired detector cannot authorize a food angle with a newer scene timestamp`() {
        val p=PhotographyTechniquePolicy()
        stable(p,scene(family=SceneFamily.FOOD,classId=53))
        val noLongerDetected=scene(3100,SceneFamily.FOOD,classId=53).copy(objectObservation=scene(classId=53).objectObservation)
        assertNull(p.recommend(noLongerDetected,null,null,3100))
        assertEquals("still_life_corners",p.recommend(noLongerDetected.copy(observationId=3600,sampledAtMs=3600),null,null,3600)!!.id)
    }

    @Test fun `measured bright region is conditional attention advice not exposure or movement`() {
        val a=stable(PhotographyTechniquePolicy(),scene(family=SceneFamily.STILL_LIFE,classId=41),visual(bright=.3f))!!
        assertEquals("background_bright_attention",a.id)
        assertTrue(a.suggestion.startsWith("如果"))
        assertTrue("brightness_not_a_defect" in a.reasonCodes)
        assertEquals(TechniqueEvidenceKind.MEASURED_IMAGE,a.evidenceKind)
    }

    @Test fun `landscape detail is not treated as clutter and semantic symmetry remains conditional`() {
        val a=stable(PhotographyTechniquePolicy(),scene(),visual(edges=.5f,bright=.4f))!!
        assertEquals("landscape_depth",a.id)
        val symmetry=stable(PhotographyTechniquePolicy(),scene(),visual(symmetry=.95f))!!
        assertEquals("landscape_symmetry",symmetry.id)
        assertTrue("reflection_not_detected" in symmetry.reasonCodes)
        assertTrue(symmetry.suggestion.startsWith("如果"))
    }

    @Test fun `texture difference never claims detected sky or a directional target`() {
        val a=stable(PhotographyTechniquePolicy(),scene(),visual(upper=.005f,lower=.08f))!!
        assertEquals("landscape_emphasis",a.id)
        assertTrue(a.suggestion.startsWith("如果天空"))
        assertTrue("sky_and_horizon_not_detected" in a.reasonCodes)
    }

    @Test fun `lost measured condition withdraws immediately while replacement is stabilized`() {
        val p=PhotographyTechniquePolicy()
        assertEquals("landscape_symmetry",stable(p,scene(),visual(symmetry=.95f))!!.id)
        assertNull(p.recommend(scene(2000),null,null,2000))
        assertEquals("landscape_depth",p.recommend(scene(2500),null,null,2500)!!.id)
    }

    @Test fun `higher priority technique requires persistent evidence and four second minimum hold`() {
        val p=PhotographyTechniquePolicy()
        stable(p,scene())
        for(t in listOf(2000L,2500L,3500L,4500L)) {
            assertEquals("landscape_depth",p.recommend(scene(t),null,visual(symmetry=.95f),t)!!.id)
        }
        assertEquals("landscape_symmetry",p.recommend(scene(5500),null,visual(symmetry=.95f),5500)!!.id)
    }

    @Test fun `same valid recipe refreshes evidence timestamp without cycling content`() {
        val p=PhotographyTechniquePolicy()
        val first=stable(p,scene(family=SceneFamily.FOOD,classId=53))!!
        for(t in 2000L..8000L step 500) {
            val next=p.recommend(scene(t,SceneFamily.FOOD,53),null,null,t)!!
            assertEquals(first.id,next.id)
            assertEquals(t,next.sampledAtMs)
        }
    }

    @Test fun `portrait heading tip needs fresh same viewport observation and does not imply eye gaze`() {
        val p=PhotographyTechniquePolicy()
        fun face(t:Long,v:Long=2)=StructureObservation(t,t,v,1,NormalizedBox(.4f,.2f,.6f,.5f),trackingId=4,facingDirectionX=1f)
        p.recommend(scene(family=SceneFamily.PORTRAIT),face(1000),null,1000)
        val a=p.recommend(scene(1500,SceneFamily.PORTRAIT),face(1500),null,1500)!!
        assertEquals("portrait_facing_room",a.id)
        assertTrue("head_direction_not_eye_gaze" in a.reasonCodes)
        assertNull(p.recommend(scene(2000,SceneFamily.PORTRAIT),face(2000,3),null,2000))
    }

    @Test fun `coaching metadata cannot create composition target or ready`() {
        val s=scene()
        val advice=stable(PhotographyTechniquePolicy(),s)!!
        val planner=AdaptiveCompositionPlanner()
        val result=planner.select(s.copy(techniqueAdvice=advice),null,null,null,1500)
        assertNull(result.portrait)
        assertNull(result.objectTarget)
        assertEquals(RecommendationRoute.OBSERVATION_ONLY,result.route)
    }

    @Test fun `new image cannot renew the sampling lease of cached face or object evidence`() {
        val p=PhotographyTechniquePolicy()
        val face=StructureObservation(1,1000,2,1,NormalizedBox(.4f,.2f,.6f,.5f),trackingId=4,facingDirectionX=1f)
        p.recommend(scene(1000,SceneFamily.PORTRAIT),face,null,1000)
        val advice=p.recommend(scene(1700,SceneFamily.PORTRAIT),face,null,1700)!!
        assertEquals(1750L,advice.expiresAtMs)
        assertNull(p.recommend(scene(1700,SceneFamily.PORTRAIT),face,null,1751))
        val food=PhotographyTechniquePolicy()
        val cached=scene(1000,SceneFamily.FOOD,53).objectObservation
        food.recommend(scene(1000,SceneFamily.FOOD,53),null,null,1000)
        val result=food.recommend(scene(2400,SceneFamily.FOOD,53).copy(objectObservation=cached),null,null,2400)!!
        assertEquals(2500L,result.expiresAtMs)
    }

    @Test fun `cached scene can revoke heading advice from new geometry without waiting for inference`() {
        for(direction in listOf<Float?>(null,-1f)) {
            val p=PhotographyTechniquePolicy()
            val face=StructureObservation(1,1000,2,1,NormalizedBox(.4f,.2f,.6f,.5f),trackingId=4,facingDirectionX=1f)
            p.recommend(scene(1000,SceneFamily.PORTRAIT),face,null,1000)
            val fresh=face.copy(observationId=2,sampledAtMs=1500)
            val cachedScene=scene(1500,SceneFamily.PORTRAIT)
            val advice=p.recommend(cachedScene,fresh,null,1500)!!
            val changed=fresh.copy(observationId=3,sampledAtMs=1600,facingDirectionX=direction)
            assertFalse(advice.isValidFor(cachedScene,changed,1600))
            assertNull(p.recommend(cachedScene,changed,null,1600))
            assertFalse(advice.isValidFor(cachedScene,fresh.copy(trackingId=9),1600))
        }
    }
}
