package com.example.aicamera.guidance

import com.example.aicamera.guidance.CompositionSessionController.GuidanceAction
import com.example.aicamera.guidance.CompositionSessionController.State
import com.example.aicamera.guidance.geometry.NormalizedBox
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Controlled screen-space sequences, not a claim about physical camera motion or human taste. */
class GuidanceEpisodeReplayTest {
    @Test fun `replay correct wrong cached and degraded execution across scene families`() {
        val episodes=JSONArray()
        for(family in listOf(SceneFamily.PORTRAIT,SceneFamily.FOOD,SceneFamily.STILL_LIFE)) {
            for(mode in listOf("follow","opposite","stationary","cached","lost","blur_after_ready")) {
                val c=CompositionSessionController().apply{startAnalysis();onAnalysisComplete()}
                var x=.7f;var reachedReady=false;val steps=JSONArray()
                for(i in 1L..24L) {
                    val before=c.action.value
                    if(mode in listOf("follow","blur_after_ready","lost")) {
                        if(before==GuidanceAction.MoveRight)x-=.035f
                        if(before==GuidanceAction.MoveLeft)x+=.035f
                    } else if(mode=="opposite" && before==GuidanceAction.MoveRight)x=(x+.04f).coerceAtMost(.9f)
                    val time=i*1000;val box=NormalizedBox(x-.06f,.4f,x+.06f,.6f)
                    val lost=mode=="lost" && i>6
                    val quality=ExecutionQualityEvidence(i,time,1,family,7,box,.5f,.1f,
                        if(mode=="blur_after_ready" && reachedReady) .01f else .2f,.01f,.04f,
                        backgroundDx=if(before==GuidanceAction.MoveRight) -.035f else 0f,
                        backgroundDy=0f,comparedObservationId=i-1)
                    val obs=CompositionSessionController.FrameObservation(frameId=i,timestampMs=time,rollDegrees=0f,
                        rawRollDegrees=0f,isUpright=true,subjectCx=x,subjectCy=.5f,targetCx=.5f,targetCy=.5f,
                        targetTolerance=.06f,expectsSubject=true,hasRealSubject=!lost,modelConfidence=.9f,
                        subjectObservationId=if(mode=="cached")1 else i,subjectTrackingId=7,
                        subjectSampledAtMs=if(mode=="cached")1000 else time,imuObservationId=i,imuSampledAtMs=time,
                        viewportVersion=1,targetViewportVersion=1,targetId="test-target",subjectBox=box,
                        observedAnchorKind=if(family==SceneFamily.PORTRAIT)ObservedAnchorKind.FACE else ObservedAnchorKind.OBJECT,
                        executionQuality=quality,executionQualityRequired=true)
                    c.observe(obs)
                    reachedReady=reachedReady || c.state.value==State.Ready
                    val d=c.decision.value
                    val visible=GuidancePresentation.from(c.state.value,d,time)
                    assertEquals(c.state.value==State.Ready,visible.ready)
                    if(d.feedback in listOf(GuidanceFeedback.WORSENING,GuidanceFeedback.NO_PROGRESS,GuidanceFeedback.SCENE_CHANGED))
                        assertEquals(GuidanceCue.NONE,visible.cue)
                    steps.put(JSONObject().put("frame_id",i).put("sampled_at_ms",time).put("subject_x",x)
                        .put("error",if (lost || (mode=="cached" && i>1)) JSONObject.NULL else d.error ?: JSONObject.NULL)
                        .put("action",d.action.javaClass.simpleName)
                        .put("feedback",d.feedback.name).put("ready",visible.ready)
                        .put("reasons",JSONArray(d.reasons)))
                }
                val expected=mode=="follow"
                assertEquals("$family/$mode",expected,c.state.value==State.Ready)
                if(mode=="blur_after_ready")assertTrue(reachedReady)
                if(mode in listOf("opposite","stationary","cached","lost"))assertFalse(reachedReady)
                episodes.put(JSONObject().put("scene",family.name).put("scenario",mode)
                    .put("expected_final_ready",expected).put("final_ready",c.state.value==State.Ready)
                    .put("ever_ready",reachedReady).put("steps",steps))
            }
        }
        for(family in listOf(SceneFamily.NATURAL_LANDSCAPE,SceneFamily.URBAN_LANDSCAPE,SceneFamily.PLANT)) {
            val c=CompositionSessionController().apply{startAnalysis();onAnalysisComplete()}
            for(i in 1L..24)c.observe(CompositionSessionController.FrameObservation(frameId=i,timestampMs=i*1000,
                rollDegrees=0f,isUpright=true,modelConfidence=.9f,observedAnchorKind=ObservedAnchorKind.NONE))
            assertNotEquals(State.Ready,c.state.value)
            episodes.put(JSONObject().put("scene",family.name).put("scenario","no_target")
                .put("expected_final_ready",false).put("final_ready",false).put("steps",JSONArray()))
        }
        System.getProperty("guidance.episode.output")?.let { path ->
            val file=File(path);file.parentFile.mkdirs()
            file.writeText(JSONObject().put("schema_version",1).put("evidence_kind","controlled_geometry_replay")
                .put("human_aesthetic_improvement_verified",false).put("physical_camera_motion_verified",false)
                .put("episodes",episodes).toString(2)+"\n")
        }
    }
}
