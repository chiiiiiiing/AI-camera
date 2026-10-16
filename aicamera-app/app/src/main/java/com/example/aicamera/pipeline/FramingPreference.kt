package com.example.aicamera.pipeline

import android.graphics.Bitmap
import com.example.aicamera.guidance.SceneFamily
import com.example.aicamera.guidance.geometry.NormalizedBox
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/** Spatial same-photo preference, never a probability and never a physical movement predictor. */
internal class FramingPreferenceModel private constructor(
    private val weights:FloatArray, private val mean:FloatArray, private val std:FloatArray,
    val scenes:Set<String>, val modelId:String, val minimumMargin:Float
) {
    fun score(embedding:FloatArray,spatial:FloatArray):Float? {
        if(embedding.size!=512 || spatial.size!=93)return null
        val features=embedding+spatial
        if(features.any{!it.isFinite()})return null
        val normalized=FloatArray(605){(features[it]-mean[it])/std[it]}
        var result=weights.last()
        for(h in 0 until 64) {
            var value=weights[64*605+h]
            for(i in 0 until 605)value+=weights[h*605+i]*normalized[i]
            result+=maxOf(0f,value)*weights[64*605+64+h]
        }
        return result.takeIf{it.isFinite()}
    }
    companion object {
        const val PROTOCOL="embedding512_rgb64_grid4_subject_scene_v1"
        const val CROP_PROTOCOL="viewport_native416_halfpixel_round_crop_rgb224_halfpixel_round_v2"
        fun scene(family:SceneFamily):String?=when(family) {
            SceneFamily.PORTRAIT->"portrait"
            SceneFamily.NATURAL_LANDSCAPE,SceneFamily.URBAN_LANDSCAPE->"landscape"
            SceneFamily.FOOD->"food"
            SceneFamily.STILL_LIFE->"still_life"
            else->null
        }
        fun load(directory:File):FramingPreferenceModel?=try {
            val policyFile=File(directory,"framing_preference_policy.json")
            require(policyFile.length() in 1..65_536)
            fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val config=JSONObject(File(directory,"android_runtime_config.json").readText()).getJSONObject("framing_preference")
            require(config.getInt("schema_version")==1 && config.getJSONObject("policy").getString("file")==policyFile.name &&
                config.getJSONObject("policy").getString("sha256")==sha(policyFile.readBytes()))
            val policy=JSONObject(policyFile.readText())
            require(policy.getInt("schema_version")==1 && policy.getBoolean("enabled") && policy.getString("feature_protocol")==PROTOCOL &&
                policy.getString("crop_preprocessing")==CROP_PROTOCOL)
            require(policy.getBoolean("weak_teacher_only") && !policy.getBoolean("crop_advice_enabled") &&
                !policy.getBoolean("physical_movement_prediction_enabled") && policy.getBoolean("historical_holdout_excluded") &&
                policy.getBoolean("previous_quality_acceptance_excluded"))
            val protocolSha=policy.getString("training_protocol_sha256")
            require(protocolSha.matches(Regex("[a-f0-9]{64}")) && policy.getString("acceptance_report_sha256").matches(Regex("[a-f0-9]{64}")))
            val encoderSha=sha(File(directory,"student_model.onnx").readBytes())
            require(policy.getString("encoder_sha256")==encoderSha)
            val metrics=policy.getJSONObject("metrics")
            require(metrics.getBoolean("preprocessing_parity_passed"))
            val agreement=metrics.getDouble("pairwise_agreement")
            require(metrics.getInt("acceptance_cluster_count")>=30 && agreement.isFinite() && agreement in .7..1.0 &&
                metrics.getDouble("bootstrap_lower95") in .50000001..1.0 && metrics.getDouble("baseline_pairwise_agreement") in 0.0..1.0 &&
                agreement>metrics.getDouble("baseline_pairwise_agreement") && metrics.getBoolean("hard_constraints_passed") &&
                metrics.getBoolean("severe_defect_non_regression"))
            require(metrics.getDouble("keep_current_pairwise_agreement") in 0.0..1.0 && agreement>metrics.getDouble("keep_current_pairwise_agreement"))
            require(metrics.getDouble("coverage") in 0.0..1.0 && metrics.getDouble("rejection_rate") in 0.0..1.0)
            require(abs(metrics.getDouble("coverage")+metrics.getDouble("rejection_rate")-1)<.000001)
            val array=policy.getJSONArray("enabled_scenes")
            val scenes=(0 until array.length()).map{array.getString(it)}.toSet()
            require(scenes.isNotEmpty() && scenes.size==array.length() && scenes.all { it in setOf("portrait","landscape","food","still_life") &&
                metrics.getJSONObject("scene_cluster_counts").optInt(it)>=10 })
            require(scenes.all {
                val row=policy.getJSONObject("per_scene").getJSONObject(it)
                val value=row.getDouble("pairwise_agreement")
                row.getInt("acceptance_cluster_count")>=10 && value in .7..1.0 && row.getDouble("bootstrap_lower95") in .50000001..1.0 &&
                    row.getDouble("baseline_pairwise_agreement") in 0.0..1.0 && row.getDouble("keep_current_pairwise_agreement") in 0.0..1.0 &&
                    value>row.getDouble("baseline_pairwise_agreement") && value>row.getDouble("keep_current_pairwise_agreement")
            })
            val margin=policy.getDouble("minimum_score_margin").toFloat()
            require(margin.isFinite() && margin>=.10f)
            val file=File(directory,"framing_preference_head.json")
            require(file.length() in 1..262_144)
            val bytes=file.readBytes()
            val hash=sha(bytes)
            require(policy.getString("model_sha256")==hash && config.getJSONObject("head").getString("file")==file.name &&
                config.getJSONObject("head").getString("sha256")==hash)
            val head=JSONObject(bytes.toString(Charsets.UTF_8))
            require(head.getInt("schema_version")==1 && head.getInt("input_dim")==605 && head.getInt("hidden_dim")==64 &&
                head.getString("activation")=="relu" && head.getString("feature_protocol")==PROTOCOL &&
                head.getString("weight_encoding")=="float32-le-base64" && head.getString("encoder_sha256")==encoderSha &&
                head.getString("crop_preprocessing")==CROP_PROTOCOL && head.getString("training_protocol_sha256")==protocolSha)
            fun values(name:String):FloatArray {
                val a=head.getJSONArray(name);require(a.length()==605)
                return FloatArray(605){a.getDouble(it).toFloat()}.also{require(it.all{v->v.isFinite()})}
            }
            val mean=values("feature_mean");val std=values("feature_std");require(std.all{it>=.05f})
            val encoded=head.getString("weights_f32_base64")
            val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            require(encoded.length%4==0 && encoded.length<220_000 && encoded.takeLastWhile{it=='='}.length<=2 &&
                encoded.trimEnd('=').all{it in alphabet})
            val raw=ByteArray(encoded.length/4*3-encoded.takeLastWhile{it=='='}.length)
            var offset=0
            for(i in encoded.indices step 4) {
                var word=0
                for(j in 0..3)word=(word shl 6) or if(encoded[i+j]=='=')0 else alphabet.indexOf(encoded[i+j])
                for(shift in listOf(16,8,0))if(offset<raw.size)raw[offset++]=(word ushr shift).toByte()
            }
            require(raw.size==(64*605+64+64+1)*4)
            val buffer=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val weights=FloatArray(buffer.remaining()).also{buffer.get(it)};require(weights.all{it.isFinite()})
            FramingPreferenceModel(weights,mean,std,scenes,hash,margin)
        }catch(_:Exception){null}

        /** Exact portable nearest-centre kernel, shared with frozen Python training fixtures. */
        fun features(pixels:IntArray,width:Int,height:Int,subject:NormalizedBox?,family:SceneFamily):FloatArray {
            require(width>0 && height>0 && pixels.size==width*height)
            val rgb=Array(3){FloatArray(4096)};val luma=FloatArray(4096)
            for(y in 0..63)for(x in 0..63) {
                val p=pixels[(((y+.5)*height/64).toInt().coerceAtMost(height-1))*width+((x+.5)*width/64).toInt().coerceAtMost(width-1)]
                val i=y*64+x
                rgb[0][i]=((p ushr 16)and 255)/255f;rgb[1][i]=((p ushr 8)and 255)/255f;rgb[2][i]=(p and 255)/255f
                luma[i]=.299f*rgb[0][i]+.587f*rgb[1][i]+.114f*rgb[2][i]
            }
            val out=FloatArray(93)
            for(gy in 0..3)for(gx in 0..3) {
                val base=(gy*4+gx)*5
                for(y in gy*16 until (gy+1)*16)for(x in gx*16 until (gx+1)*16) {
                    val i=y*64+x
                    for(c in 0..2)out[base+c]+=rgb[c][i]/256
                    if(x%16<15)out[base+3]+=abs(luma[i+1]-luma[i])/240
                    if(y%16<15)out[base+4]+=abs(luma[i+64]-luma[i])/240
                }
            }
            val average=luma.average().toFloat()
            out[80]=average;out[81]=sqrt(luma.sumOf{((it-average)*(it-average)).toDouble()}/4096).toFloat()
            out[82]=luma.count{it<.04f}/4096f;out[83]=luma.count{it>.96f}/4096f
            subject?.takeIf{it.left>=0 && it.top>=0 && it.right<=1 && it.bottom<=1 && it.width>0 && it.height>0}?.let{
                out[84]=1f;out[85]=it.left;out[86]=it.top;out[87]=it.right;out[88]=it.bottom
            }
            val scene=listOf("portrait","landscape","food","still_life").indexOf(scene(family))
            if(scene>=0)out[89+scene]=1f
            return out
        }
        /** Canonical preference encoder input; retrieval's original preprocessing stays frozen. */
        fun nchw224(pixels:IntArray,width:Int,height:Int):FloatArray {
            val resized=NativeViewportPixels.resize(pixels,width,height,DetectionViewportTransform(width,height,224,224,0,0))
            return FloatArray(3*224*224){i ->
                val channel=i/(224*224);val pixel=resized[i%(224*224)]
                ((pixel ushr (16-channel*8))and 255)/127.5f-1f
            }
        }
        fun nchw224(bitmap:Bitmap):FloatArray {
            val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            return nchw224(pixels,bitmap.width,bitmap.height)
        }
    }
}

data class FramingComparisonResult(val currentScore:Float?,val bestScore:Float?,val bestCrop:NormalizedBox?,
    val modelId:String?,val candidateCount:Int,val diagnosticOnly:Boolean=true)

/** Serial two-second throttle, no reused full-frame features standing in for crop pixels. */
internal class FramingCandidateEvaluator {
    private var lastAt=Long.MIN_VALUE
    private var cooldownUntil=0L
    private var sceneEmbedding:FloatArray?=null
    private var sceneSubject:NormalizedBox?=null
    private var sceneFamily:SceneFamily?=null
    private var candidateIndex=0
    fun reset(){lastAt=Long.MIN_VALUE;cooldownUntil=0;sceneEmbedding=null;sceneSubject=null;sceneFamily=null;candidateIndex=0}
    fun evaluate(bitmap:Bitmap,subject:NormalizedBox?,family:SceneFamily,layout:com.example.aicamera.guidance.SceneLayout,
        model:FramingPreferenceModel?,now:Long,embedding:FloatArray,encode:(Bitmap)->FloatArray):FramingComparisonResult? {
        if(model==null || now<cooldownUntil || FramingPreferenceModel.scene(family) !in model.scenes ||
            (lastAt!=Long.MIN_VALUE && now-lastAt<2000))return null
        val prior=sceneSubject
        val changed=sceneEmbedding?.let{V9EmbeddingRuntime.cosineSimilarity(it,embedding)<.995f}!=false || sceneFamily!=family ||
            (prior==null)!=(subject==null) || (prior!=null && subject!=null &&
                listOf(abs(prior.left-subject.left),abs(prior.right-subject.right),abs(prior.top-subject.top),abs(prior.bottom-subject.bottom)).any{it>.01f})
        if(changed){sceneEmbedding=embedding.copyOf();sceneSubject=subject;sceneFamily=family;candidateIndex=0}
        val candidates=PhotographyRules.candidates(family,subject,layout).drop(1).take(2)
        if(candidateIndex>=candidates.size)return null
        lastAt=now
        val started=android.os.SystemClock.elapsedRealtime()
        fun score(image:Bitmap,box:NormalizedBox?,vector:FloatArray):Float? {
            val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
            return model.score(vector,FramingPreferenceModel.features(pixels,image.width,image.height,box,family))
        }
        val baseline=score(bitmap,subject,embedding) ?: return null
        var best=baseline;var bestCrop:NormalizedBox?=null;var count=1
        // Only one extra encoder call per invocation; a second candidate needs another fresh
        // observation after two seconds. Slow inference never leaves a worker running behind us.
        for(candidate in listOf(candidates[candidateIndex++])) {
            val b=candidate.crop
            if(!PhotographyRules.preserves(b,subject))continue
            val x=kotlin.math.floor(b.left*bitmap.width+.5f).toInt();val y=kotlin.math.floor(b.top*bitmap.height+.5f).toInt()
            val w=(kotlin.math.floor(b.right*bitmap.width+.5f).toInt()-x).coerceAtLeast(1)
            val h=(kotlin.math.floor(b.bottom*bitmap.height+.5f).toInt()-y).coerceAtLeast(1)
            val actual=NormalizedBox(x.toFloat()/bitmap.width,y.toFloat()/bitmap.height,(x+w).toFloat()/bitmap.width,(y+h).toFloat()/bitmap.height)
            if(!PhotographyRules.preserves(actual,subject))continue
            val image=Bitmap.createBitmap(bitmap,x,y,w,h)
            try {
                val mapped=subject?.let{NormalizedBox((it.left-actual.left)/actual.width,(it.top-actual.top)/actual.height,
                    (it.right-actual.left)/actual.width,(it.bottom-actual.top)/actual.height)}
                val value=score(image,mapped,encode(image)) ?: continue
                count++
                if(value>best+model.minimumMargin){best=value;bestCrop=actual}
            }finally{if(image!==bitmap)image.recycle()}
        }
        val finished=android.os.SystemClock.elapsedRealtime()
        if(finished-started>150 || finished-now>750){cooldownUntil=finished+10_000;return null}
        return FramingComparisonResult(baseline,best,bestCrop,model.modelId,count)
    }
}
