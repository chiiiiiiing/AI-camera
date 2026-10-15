package com.example.aicamera.pipeline

import android.graphics.Bitmap
import com.example.aicamera.guidance.*
import com.example.aicamera.guidance.geometry.NormalizedBox
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln

internal data class MultiSceneReference(val id: String, val aspect: Float,
    val layout: SceneLayout?, val objectClass: Int?, val objectBox: NormalizedBox?, val sceneCategory: String = "")

/** Optional, hash-bound assets. A failed optional module never disables portrait retrieval. */
internal class MultiSceneReferences private constructor(
    val modelFile: File, val records: Map<String, MultiSceneReference>
) {
    companion object {
        fun load(directory: File, metadata: (String) -> TemplateMeta?): MultiSceneReferences? = try {
            fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            val config = JSONObject(File(directory, "android_runtime_config.json").readText()).getJSONObject("multi_scene_recommendation")
            require(config.getInt("schema_version") == 1 && config.getBoolean("enabled"))
            fun asset(key: String, expected: String, max: Long): File {
                val item = config.getJSONObject(key)
                require(item.getString("file") == expected)
                val f = File(directory, expected)
                require(f.isFile && f.length() in 1..max && sha(f) == item.getString("sha256"))
                return f
            }
            val model = asset("detector", "object_detector.onnx", 4_194_304)
            asset("detector_manifest", "object_detector_manifest.json", 65_536)
            val refs = asset("references", "multi_scene_references.json", 8_000_000)
            val root = JSONObject(refs.readText())
            require(root.getInt("schema_version") == 1 && root.getString("coordinate_space") == "exif_upright_full_frame")
            require(root.getString("model_sha256") == sha(model))
            require(root.getString("preprocessing") == NanoDetRuntime.PREPROCESSING &&
                root.getString("layout_algorithm") == "rgb224_rec601_grid3_forward_edges_v1" &&
                root.getString("object_policy") == "single_complete_coco_still_life_v1" &&
                root.getString("object_review_policy") == "hash_bound_ai_visual_object_review_v1" &&
                !root.getBoolean("human_reviewed") && !root.getBoolean("aesthetic_ground_truth"))
            val rows = root.getJSONArray("records")
            val records = buildMap {
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    val id = r.getString("template_id")
                    val meta = metadata(id) ?: continue
                    if (!meta.referenceEligible || r.optBoolean("synthetic", true) || r.optString("image_sha256") != meta.imageSha256 ||
                        r.optString("coordinate_space") != "exif_upright_full_frame" || r.optString("photo_type") != meta.photoType ||
                        r.optString("scene_category") != meta.sceneCategory) continue
                    val aspect = r.getDouble("image_width").toFloat()/r.getDouble("image_height").toFloat()
                    if (!aspect.isFinite() || aspect <= 0) continue
                    val l = r.optJSONObject("layout")
                    fun grid(key: String): List<Float>? {
                        val a = l?.optJSONArray(key) ?: return null
                        if (a.length() != 9) return null
                        return (0 until 9).map { a.getDouble(it).toFloat() }.takeIf { v -> v.all { it.isFinite() && it in 0f..1f } }
                    }
                    val lg = grid("luma_grid"); val eg = grid("edge_grid")
                    val mean = l?.optDouble("mean_luma", Double.NaN)?.toFloat() ?: Float.NaN
                    val dark = l?.optDouble("dark_fraction", Double.NaN)?.toFloat() ?: Float.NaN
                    val light = l?.optDouble("light_fraction", Double.NaN)?.toFloat() ?: Float.NaN
                    val layout = if (lg != null && eg != null && listOf(mean,dark,light).all { it.isFinite() && it in 0f..1f })
                        SceneLayout(lg,eg,mean,dark,light) else null
                    val a = r.optJSONArray("objects")
                    val objects = if (a == null) emptyList() else (0 until a.length()).mapNotNull { n ->
                        val obj = a.getJSONObject(n); val box = obj.optJSONArray("bbox") ?: return@mapNotNull null
                        if (box.length() != 4) return@mapNotNull null
                        val x=box.getDouble(0).toFloat(); val y=box.getDouble(1).toFloat()
                        ObjectDetection(obj.getInt("class_id"),obj.getDouble("confidence").toFloat(),
                            NormalizedBox(x,y,x+box.getDouble(2).toFloat(),y+box.getDouble(3).toFloat()))
                    }
                    val review=r.optJSONObject("object_review")
                    val reviewed=review!=null && review.optString("template_id")==id && review.optString("image_sha256")==meta.imageSha256 &&
                        review.optString("model_sha256")==root.getString("model_sha256") && review.optString("method")=="ai_visual_review" &&
                        !review.optBoolean("human_reviewed",true) && review.optString("status")=="accepted"
                    val primary = if (reviewed && r.optBoolean("object_eligible", false) && meta.sceneCategory in setOf("food_still_life","lifestyle_still_life"))
                        MultiScenePolicy.dominant(objects) else null
                    put(id, MultiSceneReference(id,aspect,layout,primary?.classId,primary?.box,meta.sceneCategory))
                }
            }
            MultiSceneReferences(model, records)
        } catch (_: Exception) { null }
    }
}

/** Pure policy and pixel kernel are shared by live analysis and deterministic regression tests. */
internal object MultiScenePolicy {
    // COCO boxes for plants/vases do not reliably contain an entire plant/bouquet.
    val objectClasses = setOf(39,40,41,45,46,47,48,49,50,51,52,53,54,55,73,74,77)
    fun dominant(detections: List<ObjectDetection>): ObjectDetection? {
        val candidates = detections.filter { d ->
            val b=d.box
            d.confidence.isFinite() && d.confidence in .5f..1f &&
                listOf(b.left,b.top,b.right,b.bottom).all { it.isFinite() } &&
                b.left >= 0 && b.top >= 0 && b.right <= 1 && b.bottom <= 1 &&
                b.width > 0 && b.height > 0 && b.width*b.height >= .02f
        }.sortedByDescending { it.box.width*it.box.height }
        if (candidates.any { it.classId == 0 }) return null
        val first = candidates.firstOrNull() ?: return null
        val b=first.box; val area=b.width*b.height
        if (first.classId !in objectClasses || area > .65f || b.left < .02f || b.top < .02f || b.right > .98f || b.bottom > .98f) return null
        if (candidates.drop(1).any { it.box.width*it.box.height >= area*.35f }) return null
        return first
    }
    fun family(context: SceneContext): SceneFamily {
        val scene = context.sceneCategory.orEmpty(); val photo = context.photoType.orEmpty()
        return when {
            photo == "portrait" -> SceneFamily.PORTRAIT
            "plant" in scene || photo == "plant" -> SceneFamily.PLANT
            "food" in scene || photo == "food" -> SceneFamily.FOOD
            "still_life" in scene || photo == "lifestyle" || photo == "still_life" -> SceneFamily.STILL_LIFE
            "urban" in scene || "city" in scene || photo == "city" -> SceneFamily.URBAN_LANDSCAPE
            "landscape" in scene || "nature" in scene || photo == "nature" || photo == "landscape" -> SceneFamily.NATURAL_LANDSCAPE
            else -> SceneFamily.UNKNOWN
        }
    }
    fun layout(pixels: IntArray, width: Int, height: Int): SceneLayout {
        require(width >= 3 && height >= 3 && pixels.size == width*height)
        val y = FloatArray(pixels.size) { n -> val p=pixels[n]; (.299f*((p ushr 16) and 255)+.587f*((p ushr 8) and 255)+.114f*(p and 255))/255f }
        val luma=MutableList(9){0f}; val edge=MutableList(9){0f}
        for (gy in 0..2) for (gx in 0..2) {
            var sum=0f; var energy=0f; var count=0
            for (row in gy*height/3 until (gy+1)*height/3) for (col in gx*width/3 until (gx+1)*width/3) {
                val n=row*width+col; sum+=y[n]; count++
                energy+=((if(col+1<width) abs(y[n+1]-y[n]) else 0f)+(if(row+1<height) abs(y[n+width]-y[n]) else 0f))/2f
            }
            luma[gy*3+gx]=sum/count; edge[gy*3+gx]=energy/count
        }
        return SceneLayout(luma,edge,y.average().toFloat(),y.count{it<=.02f}.toFloat()/y.size,y.count{it>=.98f}.toFloat()/y.size)
    }
    fun layoutBonus(live: SceneLayout, ref: SceneLayout): Float {
        val color = live.lumaGrid.indices.sumOf { abs((live.lumaGrid[it]-live.meanLuma)-(ref.lumaGrid[it]-ref.meanLuma)).toDouble() }/9
        val edges = live.edgeGrid.indices.sumOf { abs(live.edgeGrid[it]-ref.edgeGrid[it]).toDouble() }/9
        return (.05-.10*color-.15*edges).toFloat().coerceIn(-.04f,.05f)
    }
    fun mappedBox(ref: MultiSceneReference, aspect: Float): NormalizedBox? {
        val b=ref.objectBox ?: return null
        if (!aspect.isFinite() || aspect <= 0) return null
        val w=minOf(1f,aspect/ref.aspect); val h=minOf(1f,ref.aspect/aspect)
        if(w<.7f || h<.7f) return null
        return NormalizedBox((b.left-(1-w)/2)/w,(b.top-(1-h)/2)/h,(b.right-(1-w)/2)/w,(b.bottom-(1-h)/2)/h)
            .takeIf { it.left>=.03f && it.top>=.03f && it.right<=.97f && it.bottom<=.97f }
    }
    fun compatible(live: ObjectObservation, ref: MultiSceneReference): Boolean {
        if(live.classId != ref.objectClass) return false
        val b=mappedBox(ref,live.aspectRatio) ?: return false
        return live.box.width/b.width in .65f..1.55f && live.box.height/b.height in .65f..1.55f &&
            abs(ln((live.box.width/live.box.height/(b.width/b.height)).toDouble())) <= .35
    }
    fun overlap(a: NormalizedBox, b: NormalizedBox): Float {
        val intersection=maxOf(0f,minOf(a.right,b.right)-maxOf(a.left,b.left))*maxOf(0f,minOf(a.bottom,b.bottom)-maxOf(a.top,b.top))
        val union=a.width*a.height+b.width*b.height-intersection
        return if(union>0f) intersection/union else 0f
    }
    fun appearance(pixels: IntArray, width: Int, height: Int, box: NormalizedBox): List<Float> {
        val left=(box.left*width).toInt().coerceIn(0,width-1); val top=(box.top*height).toInt().coerceIn(0,height-1)
        val right=(box.right*width).toInt().coerceIn(left+1,width); val bottom=(box.bottom*height).toInt().coerceIn(top+1,height)
        val signature=MutableList(12){0f}; val counts=IntArray(9); var total=0
        for(y in top until bottom) for(x in left until right) {
            val p=pixels[y*width+x]; val r=((p ushr 16) and 255)/255f;val g=((p ushr 8) and 255)/255f;val b=(p and 255)/255f
            val cell=((y-top)*3/(bottom-top)).coerceAtMost(2)*3+((x-left)*3/(right-left)).coerceAtMost(2)
            signature[cell]+=.299f*r+.587f*g+.114f*b;counts[cell]++
            signature[9]+=r;signature[10]+=g;signature[11]+=b;total++
        }
        for(i in 0..8) signature[i]/=maxOf(1,counts[i])
        for(i in 9..11) signature[i]/=maxOf(1,total)
        return signature
    }
    fun sameAppearance(a: List<Float>?, b: List<Float>): Boolean = a!=null && a.size==12 && b.size==12 &&
        a.indices.all{a[it].isFinite() && b[it].isFinite() && abs(a[it]-b[it])<=.25f} &&
        a.indices.sumOf{abs(a[it]-b[it]).toDouble()}/12<=.12
    /** Uncertain second objects are rejection evidence, never positive semantic evidence. */
    fun hasUncertainCompetitor(primary: ObjectDetection, detections: List<ObjectDetection>): Boolean = detections.any {
        it.confidence.isFinite() && it.confidence in .35f.. .499999f &&
            it.box.width*it.box.height >= primary.box.width*primary.box.height*.35f && overlap(it.box,primary.box)<.60f
    }
}

internal data class MultiSceneResult(val observation: SceneAnalysisObservation, val target: ObjectCompositionTarget?, val hits: List<SearchHit>)

/** One serial detector call per second. No worker timeout can leave an orphaned inference running. */
internal class MultiSceneAnalyzer(directory: File, metadata: (String) -> TemplateMeta?) : Closeable {
    private val refs=MultiSceneReferences.load(directory,metadata)
    private var detector: NanoDetRuntime?=null
    private var detectorAttempted=false
    private var lastDetectionAt=Long.MIN_VALUE
    private var cooldownUntil=0L
    private var observationId=0L
    private var objectId=0L
    private var trackingId=0
    private var previous: ObjectObservation?=null
    private var previousAppearance: List<Float>?=null
    private var locked: ObjectCompositionTarget?=null
    private var excludedId: String?=null
    private var scene=SceneFamily.UNKNOWN
    private var pending=SceneFamily.UNKNOWN
    private var sceneSamples=0
    private var viewport=Long.MIN_VALUE

    fun analyze(bitmap: Bitmap, context: SceneContext, hits: List<SearchHit>, viewportVersion: Long, aspect: Float, nowMs: Long, sampledAtMs: Long = nowMs): MultiSceneResult {
        if(viewport!=viewportVersion) { reset(); viewport=viewportVersion }
        val candidate=MultiScenePolicy.family(context)
        if(candidate==pending) sceneSamples++ else { pending=candidate; sceneSamples=1 }
        if(candidate!=scene) {
            previous=null; locked=null
            scene=if(sceneSamples>=2) candidate else SceneFamily.UNKNOWN
        }
        val pixels=IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val layout=MultiScenePolicy.layout(pixels,bitmap.width,bitmap.height)
        val objectScene=scene==SceneFamily.FOOD || scene==SceneFamily.STILL_LIFE
        if(objectScene && refs!=null && nowMs>=cooldownUntil && (lastDetectionAt==Long.MIN_VALUE || nowMs-lastDetectionAt>=1000)) {
            lastDetectionAt=nowMs
            try {
                if(!detectorAttempted) { detectorAttempted=true; detector=NanoDetRuntime(refs.modelFile) }
                val detections=detector?.detect(bitmap).orEmpty()
                val finished=android.os.SystemClock.elapsedRealtime()
                val primary=if(finished-nowMs<=250 && finished-sampledAtMs<=750) MultiScenePolicy.dominant(detections)
                    ?.takeUnless{MultiScenePolicy.hasUncertainCompetitor(it,detections)} else null
                if(finished-nowMs>250) cooldownUntil=finished+5000
                val before=previous
                if(primary==null) { previous=null; locked=null } else {
                    val signature=MultiScenePolicy.appearance(pixels,bitmap.width,bitmap.height,primary.box)
                    val same=before!=null && before.classId==primary.classId && nowMs-before.sampledAtMs<=2500 &&
                        MultiScenePolicy.overlap(before.box,primary.box)>=.50f &&
                        MultiScenePolicy.sameAppearance(previousAppearance,signature) &&
                        abs(before.box.centerX-primary.box.centerX)<.15f && abs(before.box.centerY-primary.box.centerY)<.15f &&
                        primary.box.width/before.box.width in .7f..1.4f && primary.box.height/before.box.height in .7f..1.4f
                    if(!same) { trackingId++; locked=null; excludedId=null }
                    previousAppearance=signature
                    previous=ObjectObservation(++objectId,sampledAtMs,viewportVersion,trackingId,primary.classId,primary.box,primary.confidence,
                        if(same) before!!.stableSamples+1 else 1,aspect)
                }
            } catch (_: Exception) { previous=null; locked=null; cooldownUntil=nowMs+5000 }
        }
        if(!objectScene) { previous=null; locked=null }
        val live=previous?.takeIf{it.isFresh(nowMs)}
        if(live==null) locked=null
        val ranked=hits.map { hit ->
            val ref=refs?.records?.get(hit.templateId)
            val layoutGain=if(ref?.layout!=null && abs(ln((ref.aspect/aspect).toDouble()))<=.20 && scene!=SceneFamily.UNKNOWN && scene!=SceneFamily.PORTRAIT)
                MultiScenePolicy.layoutBonus(layout,ref.layout) else 0f
            val objectGain=if(live!=null && ref!=null) (if(ref.sceneCategory==context.sceneCategory && MultiScenePolicy.compatible(live,ref)) .10f else -.03f) else 0f
            hit.copy(rerankScore=hit.rerankScore+layoutGain+objectGain)
        }.sortedWith(compareByDescending<SearchHit>{it.rerankScore}.thenBy{it.templateId})
        if(live!=null) {
            locked?.let { target ->
                val id=target.id.removePrefix("object:")
                if(!target.accepts(live) || refs?.records?.get(id)?.sceneCategory!=context.sceneCategory ||
                    ranked.none{it.templateId==id && it.cosine>=.70f}) locked=null
            }
            if(locked==null) {
                for(hit in ranked) {
                    if(hit.cosine<.75f || "object:${hit.templateId}"==excludedId) continue
                    val ref=refs?.records?.get(hit.templateId) ?: continue
                    if(ref.sceneCategory!=context.sceneCategory || !MultiScenePolicy.compatible(live,ref)) continue
                    val b=MultiScenePolicy.mappedBox(ref,aspect) ?: continue
                    val keep=abs(live.box.centerX-b.centerX)<=.06f && abs(live.box.centerY-b.centerY)<=.06f
                    val target=ObjectCompositionTarget("object:${hit.templateId}",NormalizedPoint(if(keep) live.box.centerX else b.centerX,
                        if(keep) live.box.centerY else b.centerY),viewportVersion,live.trackingId,live.classId,b,
                        source=if(keep) "current_compatible_object" else "reference_object")
                    if(target.accepts(live)) { locked=target; break }
                }
            }
        }
        return MultiSceneResult(SceneAnalysisObservation(++observationId,sampledAtMs,viewportVersion,scene,layout,live,detector!=null),locked,ranked)
    }
    fun replan() { excludedId=locked?.id; locked=null }
    fun reset() { previous=null; previousAppearance=null; locked=null; excludedId=null; scene=SceneFamily.UNKNOWN; pending=SceneFamily.UNKNOWN; sceneSamples=0; viewport=Long.MIN_VALUE }
    override fun close() { detector?.close(); detector=null; reset() }
}
