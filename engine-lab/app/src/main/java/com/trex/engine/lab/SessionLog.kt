package com.trex.engine.lab

import android.content.Context
import android.os.Build
import com.trex.engine.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedWriter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 모든 호출을 카메라 실행기에 직렬화한다. 입력과 출력은 같은 행에 기록해 재생 시 대응을 보존한다. */
class SessionLog(context: Context, profile: ExerciseRepProfile, pattern: RepMovementPattern, floorSideConfirmed: Boolean) : AutoCloseable {
    val id = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
    val file = File(directory(context), "$id.jsonl")
    private val writer: BufferedWriter = file.bufferedWriter()
    private var frames = 0
    init {
        append(JSONObject().put("type","session_start").put("schema",1).put("session_id",id)
            .put("wall_time_ms",System.currentTimeMillis()).put("application_id",BuildConfig.APPLICATION_ID)
            .put("app_version",BuildConfig.VERSION_NAME).put("engine",ENGINE_VERSION).put("engine_sha256",BuildConfig.ENGINE_SHA256)
            .put("rep_engine","return-bilateral/1-standalone").put("form_engine","research-observer/1")
            .put("automatic_correction_enabled",false).put("exercise",profile.exercise).put("pattern",pattern.name)
            .put("count_definition",profile.countDefinition).put("limitations",profile.limitations)
            .put("floor_side_user_confirmed",floorSideConfirmed).put("preview_mirrored",true).put("inference_mirrored",false)
            .put("side_convention","anatomical").put("gravity_source","portrait_screen_up")
            .put("device",Build.MODEL).put("android",Build.VERSION.RELEASE)
            .put("model","mediapipe-pose-landmarker-full/0.10.14")
            .put("model_sha256",BuildConfig.MODEL_SHA256)
            .put("counter_policy",JSONObject().put("refractory_ms",1200).put("max_gap_ms",1500).put("simultaneous_window_ms",600))
            .put("signals",JSONArray(listOfNotNull(profile.commonSignal,profile.leftSignal,profile.rightSignal).map {
                JSONObject().put("feature",it.feature).put("min_amplitude",it.minAmp).put("validated",it.validated)
            }))
            .put("features",JSONArray(profile.requiredFeatures(pattern).toList())))
    }
    fun frame(camera: CameraResult, features: Map<String,Float>, output: EngineOutput, qualityOk: Boolean) {
        frames++
        val f = camera.frame
        append(JSONObject().put("type","frame").put("t_ms",output.timeMs).put("infer_ms",camera.inferMs)
            .put("delegate",camera.delegate).put("error",camera.error ?: JSONObject.NULL).put("quality_ok",qualityOk)
            .put("width",f?.width ?: 0).put("height",f?.height ?: 0)
            .put("xy",array(f?.xy)).put("world",array(f?.world)).put("visibility",array(f?.visibility))
            .put("features",JSONObject(features)).put("counts",counts(output.counts)).put("status",output.status)
            .put("view",output.view).put("phase",output.phase).put("hold_ms",output.observedHoldMs)
            .put("form_status",output.formStatus).put("measurements",JSONObject(output.measurements.mapValues { it.value ?: JSONObject.NULL }))
            .put("events",JSONArray(output.events.map { e -> JSONObject().put("t_ms",e.timeMs).put("side",e.side.name)
                .put("cycles",JSONObject(e.cycles.mapKeys { it.key.name }.mapValues { (_,c) ->
                    JSONObject().put("signal",c.signalFeature).put("min",c.min).put("max",c.max).put("valid",c.valid ?: JSONObject.NULL) })) })))
    }
    fun finish(reason: String, output: EngineOutput?) {
        append(JSONObject().put("type","session_end").put("reason",reason).put("frames",frames)
            .put("counts",counts(output?.counts ?: ExerciseRepCounts())).put("hold_ms",output?.observedHoldMs ?: 0)
            .put("wall_time_ms",System.currentTimeMillis()))
        close()
    }
    private fun append(json: JSONObject) { writer.write(json.toString()); writer.newLine(); writer.flush() }
    override fun close() = writer.close()
    companion object {
        fun directory(context: Context) = File(context.filesDir,"sessions").apply { mkdirs() }
        fun counts(c: ExerciseRepCounts) = JSONObject().put("total",c.total).put("left",c.left).put("right",c.right).put("both",c.both).put("unknown",c.unknown)
        private fun array(values: FloatArray?) = JSONArray(values?.map { if (it.isFinite()) it else JSONObject.NULL }.orEmpty())
        fun truth(file: File, total: Int?, left: Int?, right: Int?, scenario: String, note: String) {
            val json = JSONObject().put("type","human_annotation").put("source","user_entered")
                .put("actual_total",total ?: JSONObject.NULL).put("actual_left",left ?: JSONObject.NULL).put("actual_right",right ?: JSONObject.NULL)
                .put("scenario",scenario).put("note",note).put("wall_time_ms",System.currentTimeMillis())
            file.appendText(json.toString()+"\n")
        }
        fun export(context: Context): File {
            val dir = File(context.cacheDir,"exports").apply { mkdirs() }
            val target = File(dir,"trex-lab-${System.currentTimeMillis()}.zip")
            val files = directory(context).listFiles()?.filter { it.extension == "jsonl" }?.sortedBy { it.name }.orEmpty()
            require(files.isNotEmpty()) { "아직 저장된 실험이 없습니다" }
            ZipOutputStream(FileOutputStream(target)).use { zip ->
                for (file in files) {
                    zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write("TREX 실험실 입력·출력 로그입니다. 사진/영상은 포함하지 않습니다. human_annotation의 null은 실제 횟수 미입력입니다. session_end 없는 파일은 중단된 세션입니다.\n".toByteArray())
                zip.closeEntry()
            }
            return target
        }
    }
}
