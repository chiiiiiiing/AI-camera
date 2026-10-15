package com.example.aicamera.pipeline

import com.example.aicamera.guidance.*
import com.example.aicamera.guidance.geometry.NormalizedBox
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MultiSceneAnalysisTest {
    private fun detected(id: Int = 41, b: NormalizedBox = NormalizedBox(.25f,.20f,.65f,.70f), score: Float = .9f) = ObjectDetection(id,score,b)
    private fun observed() = ObjectObservation(1,1000,3,7,41,detected().box,.9f,2,1f)

    @Test fun `single real cup is eligible but people and competing objects are ambiguous`() {
        assertNotNull(MultiScenePolicy.dominant(listOf(detected())))
        assertNull(MultiScenePolicy.dominant(listOf(detected(),detected(0,NormalizedBox(.80f,.2f,.95f,.6f)))))
        assertNull(MultiScenePolicy.dominant(listOf(detected(),detected(47,NormalizedBox(.7f,.2f,.95f,.6f)))))
        // An unsupported large object still competes with the selected cup.
        assertNull(MultiScenePolicy.dominant(listOf(detected(),detected(56,NormalizedBox(.6f,.4f,.95f,.9f)))))
    }

    @Test fun `clipped large uncertain and plant boxes never authorize an object target`() {
        assertNull(MultiScenePolicy.dominant(listOf(detected(b=NormalizedBox(.0f,.2f,.4f,.8f)))))
        assertNull(MultiScenePolicy.dominant(listOf(detected(b=NormalizedBox(.02f,.02f,.98f,.98f)))))
        assertNull(MultiScenePolicy.dominant(listOf(detected(score=Float.NaN))))
        assertNull(MultiScenePolicy.dominant(listOf(detected(score=.49f))))
        assertNull(MultiScenePolicy.dominant(listOf(detected(58))))
        assertNull(MultiScenePolicy.dominant(listOf(detected(75))))
    }

    @Test fun `layout measures visual distribution without declaring a horizon or aesthetic score`() {
        val white=MultiScenePolicy.layout(IntArray(36){-1},6,6)
        assertEquals(1f,white.meanLuma,.00001f); assertEquals(1f,white.lightFraction,0f)
        assertTrue(white.edgeGrid.all{it==0f})
        val black=MultiScenePolicy.layout(IntArray(36){0xff000000.toInt()},6,6)
        assertEquals(1f,black.darkFraction,0f)
        val split=MultiScenePolicy.layout(IntArray(36){if(it<18) -1 else 0xff000000.toInt()},6,6)
        assertEquals(.5f,split.meanLuma,.00001f)
        assertTrue(split.edgeGrid.any{it>0f})
        assertTrue(MultiScenePolicy.layoutBonus(split,split)>MultiScenePolicy.layoutBonus(split,white))
    }

    @Test fun `reference geometry must preserve the box and match class and scale`() {
        val live=observed()
        val ref=MultiSceneReference("cup",1f,null,41,live.box)
        assertTrue(MultiScenePolicy.compatible(live,ref))
        assertFalse(MultiScenePolicy.compatible(live,ref.copy(objectClass=47)))
        assertNull(MultiScenePolicy.mappedBox(ref.copy(aspect=2f),.5f))
        assertFalse(MultiScenePolicy.compatible(live,ref.copy(objectBox=NormalizedBox(.4f,.4f,.5f,.5f))))
    }

    @Test fun `object target identity and timing cannot be substituted by a face or cached frame`() {
        val live=observed()
        val target=ObjectCompositionTarget("object:cup",NormalizedPoint(.5f,.5f),3,7,41,live.box)
        assertTrue(target.accepts(live)); assertTrue(live.isFresh(2000))
        assertFalse(live.isFresh(2501)); assertFalse(live.isFresh(999))
        assertFalse(live.copy(stableSamples=1).isFresh(1000))
        assertFalse(target.accepts(live.copy(viewportVersion=4)))
        assertFalse(target.accepts(live.copy(trackingId=8)))
        assertFalse(target.accepts(live.copy(classId=47)))
        assertEquals(0f,MultiScenePolicy.overlap(live.box,NormalizedBox(.8f,.8f,.9f,.9f)),0f)
    }

    @Test fun `scene routing retains portrait plant food and landscape distinctions`() {
        assertEquals(SceneFamily.FOOD,MultiScenePolicy.family(SceneContext(photoType="still_life",sceneCategory="food_still_life")))
        assertEquals(SceneFamily.STILL_LIFE,MultiScenePolicy.family(SceneContext(photoType="still_life",sceneCategory="lifestyle_still_life")))
        assertEquals(SceneFamily.PLANT,MultiScenePolicy.family(SceneContext(photoType="still_life",sceneCategory="plant_still_life")))
        assertEquals(SceneFamily.NATURAL_LANDSCAPE,MultiScenePolicy.family(SceneContext(photoType="landscape",sceneCategory="nature_landscape")))
        assertEquals(SceneFamily.URBAN_LANDSCAPE,MultiScenePolicy.family(SceneContext(photoType="landscape",sceneCategory="city_landscape")))
        assertEquals(SceneFamily.UNKNOWN,MultiScenePolicy.family(SceneContext()))
    }

    @Test fun `same position white cup replaced by black cup invalidates appearance association`() {
        val white=MultiScenePolicy.appearance(IntArray(64){-1},8,8,detected().box)
        val black=MultiScenePolicy.appearance(IntArray(64){0xff000000.toInt()},8,8,detected().box)
        assertTrue(MultiScenePolicy.sameAppearance(white,white))
        assertFalse(MultiScenePolicy.sameAppearance(white,black))
        assertFalse(MultiScenePolicy.sameAppearance(null,white))
    }

    @Test fun `uncertain large second object vetoes movement without being promoted to a subject`() {
        val cup=detected()
        val uncertain=detected(47,NormalizedBox(.7f,.2f,.95f,.7f),.4f)
        assertNotNull(MultiScenePolicy.dominant(listOf(cup,uncertain)))
        assertTrue(MultiScenePolicy.hasUncertainCompetitor(cup,listOf(cup,uncertain)))
        assertFalse(MultiScenePolicy.hasUncertainCompetitor(cup,listOf(cup,cup.copy(confidence=.4f))))
    }

    @Test fun `deployed optional bundle loads only reviewed object references and fails closed when absent`() {
        val root=generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it,"aicamera-app/app/src/main/assets/ai/v9").isDirectory }
        val assets=File(root,"aicamera-app/app/src/main/assets/ai/v9")
        val index=V9TemplateIndex(File(assets,"template_index"))
        assertTrue(index.load())
        val refs=MultiSceneReferences.load(assets,index::getMetadata)
        assertNotNull(refs)
        assertEquals(setOf("new_batch_01010","new_batch_01291","new_batch_01207"),
            refs!!.records.values.filter { it.objectBox!=null }.map { it.id }.toSet())
        assertTrue(refs.records.values.filter { it.objectBox!=null }.all { it.sceneCategory=="food_still_life" })
        assertNull(MultiSceneReferences.load(File(assets,"missing-optional-bundle"),index::getMetadata))
    }
}
