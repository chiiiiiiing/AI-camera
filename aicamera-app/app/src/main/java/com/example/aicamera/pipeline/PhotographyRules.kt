package com.example.aicamera.pipeline

import com.example.aicamera.guidance.*
import com.example.aicamera.guidance.geometry.NormalizedBox
import kotlin.math.abs
import kotlin.math.sqrt

internal data class FramingCandidate(val id: String, val crop: NormalizedBox, val reasons: List<String>)

/** Rules generate alternatives, not universal definitions of beauty or invented horizon labels. */
internal object PhotographyRules {
    val FULL = NormalizedBox(0f,0f,1f,1f)
    private fun valid(box: NormalizedBox) = listOf(box.left,box.top,box.right,box.bottom).all { it.isFinite() && it in 0f..1f } &&
        box.width>0 && box.height>0
    fun applicable(family:SceneFamily,subject:NormalizedBox?):List<String> = buildList {
        add("preserve_current_lighting_style")
        if(subject!=null) { add("protect_observed_subject_margin");add("compare_local_subject_sharpness");add("compare_local_subject_highlights") }
        when(family) {
            SceneFamily.FOOD,SceneFamily.STILL_LIFE -> if(subject!=null)add("background_detail_is_soft_evidence")
            SceneFamily.PORTRAIT -> add("face_box_does_not_certify_complete_person")
            SceneFamily.PLANT -> add("vase_box_does_not_certify_complete_foliage")
            SceneFamily.NATURAL_LANDSCAPE,SceneFamily.URBAN_LANDSCAPE -> {
                add("preserve_centered_and_symmetric_options");add("no_semantic_horizon_from_brightness");add("detail_is_not_clutter")
            }
            else -> add("insufficient_scene_evidence")
        }
    }
    fun preserves(crop: NormalizedBox, subject: NormalizedBox?): Boolean {
        if (!valid(crop) || (subject != null && !valid(subject))) return false
        if(crop.left<0 || crop.top<0 || crop.right>1 || crop.bottom>1 || crop.width<.82f || crop.height<.82f) return false
        return subject==null || (subject.left>=crop.left+.025f && subject.top>=crop.top+.025f &&
            subject.right<=crop.right-.025f && subject.bottom<=crop.bottom-.025f)
    }
    fun candidates(family: SceneFamily, subject: NormalizedBox?, layout: SceneLayout): List<FramingCandidate> {
        val current=FramingCandidate("current",FULL,listOf("preserve_current"))
        if ((subject!=null && !valid(subject)) || layout.edgeGrid.size!=9 || layout.lumaGrid.size!=9 ||
            (layout.edgeGrid+layout.lumaGrid).any { !it.isFinite() || it !in 0f..1f }) return listOf(current)
        // A face box does not protect hair/body, nor a vase box a whole bouquet.
        if(family==SceneFamily.UNKNOWN || family==SceneFamily.PORTRAIT || family==SceneFamily.PLANT) return listOf(current)
        if(family==SceneFamily.FOOD || family==SceneFamily.STILL_LIFE) {
            if(subject==null) return listOf(current)
            fun crop(size:Float,cx:Float,cy:Float): NormalizedBox {
                val x=(subject.centerX-size*cx).coerceIn(0f,1-size)
                val y=(subject.centerY-size*cy).coerceIn(0f,1-size)
                return NormalizedBox(x,y,x+size,y+size)
            }
            val alternatives= mutableListOf(FramingCandidate("centered",crop(.90f,.5f,.5f),listOf("single_complete_subject","centered_option")))
            // A quiet side supplies measurable negative space. It does not imply gaze direction.
            val left=(layout.edgeGrid[0]+layout.edgeGrid[3]+layout.edgeGrid[6])/3
            val right=(layout.edgeGrid[2]+layout.edgeGrid[5]+layout.edgeGrid[8])/3
            if(abs(left-right)>.012f) alternatives.add(FramingCandidate("negative_space",
                crop(.90f,if(left<right) .62f else .38f,.5f),listOf("measured_quieter_side","optional_off_center")))
            return listOf(current)+alternatives.filter { preserves(it.crop,subject) }.distinctBy { it.crop }.take(2)
        }
        // Landscape alternatives remain diagnostic: without segmentation they cannot authorize
        // a move/zoom or claim to preserve unseen semantic boundaries. Symmetric trims retain
        // the current centre; no brightness boundary is called a horizon or reflection.
        return listOf(current,
            FramingCandidate("gentle_center",NormalizedBox(.03f,.03f,.97f,.97f),listOf("retain_center","unverified_semantic_boundaries")),
            FramingCandidate("center",NormalizedBox(.06f,.06f,.94f,.94f),listOf("retain_center","unverified_semantic_boundaries")))
    }

    fun quality(pixels: IntArray,width:Int,height:Int,box:NormalizedBox?): FloatArray {
        require(width>=3 && height>=3 && pixels.size==width*height)
        val luma=FloatArray(pixels.size) { val p=pixels[it]; (.299f*((p ushr 16)and 255)+.587f*((p ushr 8)and 255)+.114f*(p and 255))/255f }
        val mean=luma.average().toFloat(); val std=sqrt(luma.sumOf{((it-mean)*(it-mean)).toDouble()}/luma.size).toFloat()
        var edge=0f; var bgCount=0; var sharp=0f; var subjectMean=0f; var subjectSquare=0f; var subCount=0; var clipped=0
        for(y in 1 until height-1) for(x in 1 until width-1) {
            val n=y*width+x; val value=luma[n]
            val subject=box!=null && (x+.5f)/width in box.left..box.right && (y+.5f)/height in box.top..box.bottom
            if(subject) {
                sharp+=abs(luma[n-1]+luma[n+1]+luma[n-width]+luma[n+width]-4*value)
                subjectMean+=value; subjectSquare+=value*value;subCount++;if(value>=.98f)clipped++
            } else { edge+=(abs(luma[n+1]-value)+abs(luma[n+width]-value))/2;bgCount++ }
        }
        val localStd=if(subCount>0) sqrt(maxOf(0f,subjectSquare/subCount-(subjectMean/subCount)*(subjectMean/subCount))) else 0f
        return floatArrayOf(mean,std,if(subCount>0) sharp/subCount/maxOf(.05f,localStd) else Float.NaN,
            if(subCount>0) clipped.toFloat()/subCount else Float.NaN,edge/maxOf(1,bgCount))
    }
}

/** Only keeps a 48x48 luminance observation. No camera Bitmap escapes its frame lifecycle. */
internal class ExecutionImageAnalyzer {
    private data class Sample(val id:Long,val pixels:FloatArray,val box:NormalizedBox?,val viewport:Long,val subjectId:Long)
    private var previous:Sample?=null
    private var id=0L
    fun reset() { previous=null }
    fun observe(pixels:IntArray,width:Int,height:Int,time:Long,viewport:Long,family:SceneFamily,
        trackingId:Int?,box:NormalizedBox?,preference:Float?,model:String?,subjectObservationId:Long?=null): ExecutionQualityEvidence {
        val q=PhotographyRules.quality(pixels,width,height,box)
        val grid=FloatArray(48*48) { n ->
            val p=pixels[((n/48+.5f)*height/48).toInt().coerceAtMost(height-1)*width+((n%48+.5f)*width/48).toInt().coerceAtMost(width-1)]
            (.299f*((p ushr 16)and 255)+.587f*((p ushr 8)and 255)+.114f*(p and 255))/255f
        }
        val old=previous?.takeIf { it.viewport==viewport }
        val shift=old?.let { translation(it.pixels,grid,it.box,box) }
        val next=++id
        val subjectId=subjectObservationId ?: next
        // Motion is measured over the same interval as geometry, even when the detector runs
        // at 1Hz while image-quality observations arrive at 2Hz.
        if(old==null || old.subjectId!=subjectId)previous=Sample(next,grid,box,viewport,subjectId)
        return ExecutionQualityEvidence(next,time,viewport,family,trackingId,box,q[0],q[1],q[2].takeIf{it.isFinite()},
            q[3].takeIf{it.isFinite()},q[4],preference,model,shift?.first,shift?.second,old?.id,subjectId,PhotographyRules.applicable(family,box))
    }
    companion object {
        internal fun translation(before:FloatArray,after:FloatArray,oldBox:NormalizedBox?,box:NormalizedBox?):Pair<Float,Float>? {
            require(before.size==2304 && after.size==2304)
            fun inside(b:NormalizedBox?,x:Int,y:Int)=b!=null && (x+.5f)/48 in (b.left-.03f)..(b.right+.03f) &&
                (y+.5f)/48 in (b.top-.03f)..(b.bottom+.03f)
            var best=Float.POSITIVE_INFINITY;var second=best;var bestX=0;var bestY=0
            for(dy in -4..4) for(dx in -4..4) {
                var error=0f;var count=0;var texture=0f
                for(y in 5..42) for(x in 5..42) {
                    if(inside(oldBox,x,y)||inside(box,x+dx,y+dy))continue
                    val a=before[y*48+x];error+=abs(a-after[(y+dy)*48+x+dx]);texture+=abs(a-before[y*48+x+1]);count++
                }
                if(count<400 || texture/count<.012f)continue
                error/=count
                if(error<best){second=best;best=error;bestX=dx;bestY=dy}else if(error<second)second=error
            }
            return if(best<.06f && second-best>.002f) Pair(bestX/48f,bestY/48f) else null
        }
    }
}
