package com.example.trex_kotlin.posture

import android.content.Context
import com.trex.engine.EngineOutput
import com.trex.engine.ENGINE_VERSION
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** 기본은 요약만 저장한다. 원본 좌표는 세트마다 사용자가 켠 진단에 한해 로컬 7일/20MB 한도로 보관한다. */
class V2SessionStore(context: Context) {
    private val folder = File(context.noBackupFilesDir, "pose_v2").apply { mkdirs() }
    private val modelHash = context.assets.open(PoseModel.FULL.asset).use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun frame(id: String, sample: PoseSample, features: Map<String, Float>, output: EngineOutput) {
        val row = JSONObject().put("schema", 2).put("engine", ENGINE_VERSION).put("model_sha256", modelHash)
            .put("t_ms", output.timeMs).put("epoch", output.epoch).put("width", sample.imageWidth).put("height", sample.imageHeight)
            .put("xy", sample.normalizedXy.json()).put("world", sample.rawWorld.json()).put("visibility", sample.visibility.json())
            .put("features", JSONObject(features)).put("count", output.counts.total).put("range_met", output.rangeMet)
            .put("status", output.status).put("view", output.view)
            .put("events", JSONArray().also { events -> output.events.forEach { event ->
                events.put(JSONObject().put("side",event.side.name).put("t_ms",event.timeMs)
                    .put("cycles",JSONArray().also { cycles -> event.cycles.forEach { (side, cycle) ->
                        cycles.put(JSONObject().put("side",side.name).put("feature",cycle.signalFeature).put("min",cycle.min).put("max",cycle.max))
                    } }))
            } })
        executor.execute { runCatching { File(folder, "$id.frames.jsonl").appendText(row.toString()+"\n"); prune() } }
    }
    fun summary(id: String, exercise: String, pattern: String, fromFloor: Boolean, goal: Float?, actual: Int,
        corrected: Boolean, output: EngineOutput?, profile: String, elapsedMs: Long) {
        val hash = MessageDigest.getInstance("SHA-256").digest(profile.toByteArray()).joinToString("") { "%02x".format(it) }
        val row = JSONObject().put("schema",2).put("engine",ENGINE_VERSION).put("model_sha256",modelHash).put("profile_sha256",hash)
            .put("exercise",exercise).put("pattern",pattern).put("deadlift_from_floor",fromFloor).put("range_goal",goal)
            .put("actual_reps",if(corrected) actual else JSONObject.NULL).put("actual_source",if(corrected) "USER_ENTERED" else "NOT_ENTERED")
            .put("observed_reps",output?.counts?.total ?: 0).put("left",output?.counts?.left ?: 0).put("right",output?.counts?.right ?: 0)
            .put("range_met",output?.rangeMet).put("observed_hold_ms",output?.observedHoldMs ?: 0)
            .put("elapsed_ms",elapsedMs).put("accepted_frames",output?.acceptedFrames ?: 0).put("frames",output?.totalFrames ?: 0)
            .put("form_verdict","UNJUDGED").put("corrective_voice",false).put("baseline_applied",false)
        executor.execute { runCatching { File(folder,"$id.summary.json").writeText(row.toString()) } }
    }
    private fun prune() {
        val files = folder.listFiles { f -> f.name.endsWith(".frames.jsonl") }.orEmpty().sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        for (file in files) if (System.currentTimeMillis()-file.lastModified()>7*86400000L || bytes>20*1024*1024) {
            val length=file.length(); if(file.delete()) bytes-=length
        }
    }
    init { executor.execute { runCatching { prune() } } }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}

private fun FloatArray.json() = JSONArray().also { out -> forEach { out.put(if(it.isFinite()) it else JSONObject.NULL) } }
