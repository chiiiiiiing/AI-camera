package com.example.aicamera.guidance.geometry

import com.example.aicamera.guidance.GuidanceCue
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

/** Independent pinhole-camera simulation, including the front optical-axis reversal.
 * Commands describe screen-relative PHONE TRANSLATION, not yaw or the subject moving.
 */
class PhoneTranslationProjectionTest {
    private fun rotate(p: Pair<Double,Double>, turns: Int): Pair<Double,Double> = when ((turns%4+4)%4) {
        0 -> p
        1 -> -p.second to p.first
        2 -> -p.first to -p.second
        else -> p.second to -p.first
    }
    private fun project(x:Double,y:Double,z:Double,front:Boolean,rotation:Int): Pair<Float,Float> {
        // Front and rear lenses look along opposite device Z axes. Image Y is down.
        val optical = -x/z to -y/kotlin.math.abs(z)
        val sensor = rotate(optical,-rotation)
        val upright = rotate(sensor,rotation)
        return (.5+(if(front) -upright.first else upright.first)).toFloat() to (.5+upright.second).toFloat()
    }
    @Test fun `screen relative phone translation reduces error for both lenses and all rotations`() {
        for(front in listOf(false,true)) for(rotation in 0..3)
            for(world in listOf(.2 to 0.0,-.2 to 0.0,0.0 to .2,0.0 to -.2,
                .2 to .2, .2 to -.2, -.2 to .2, -.2 to -.2)) {
                val z=if(front) 1.0 else -1.0
                val before=project(world.first,world.second,z,front,rotation)
                val cue=GuidanceGeometry.guidanceCue(before.first,before.second,true,.5f,.5f,.06f)
                val movement=when(cue) {
                    GuidanceCue.MOVE_RIGHT -> .04 to 0.0
                    GuidanceCue.MOVE_LEFT -> -.04 to 0.0
                    GuidanceCue.MOVE_UP -> 0.0 to .04
                    GuidanceCue.MOVE_DOWN -> 0.0 to -.04
                    GuidanceCue.MOVE_UP_LEFT -> -.04 to .04
                    GuidanceCue.MOVE_UP_RIGHT -> .04 to .04
                    GuidanceCue.MOVE_DOWN_LEFT -> -.04 to -.04
                    GuidanceCue.MOVE_DOWN_RIGHT -> .04 to -.04
                    else -> error("No translation for displaced subject: $cue")
                }
                val after=project(world.first-movement.first,world.second-movement.second,z,front,rotation)
                assertTrue("front=$front rotation=$rotation cue=$cue",hypot(after.first-.5f,after.second-.5f)<hypot(before.first-.5f,before.second-.5f))
            }
    }
}
