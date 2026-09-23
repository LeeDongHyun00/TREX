package com.example.trex_kotlin.posture

import kotlin.math.exp
import kotlin.math.ln

enum class ActionState { WARMUP, TARGET_CANDIDATE, OTHER_EXERCISE, AMBIGUOUS, NOVEL, UNOBSERVABLE, UNAVAILABLE }

/** 학습된 것은 운동 종류뿐이다. 상태와 무관하게 이 버전은 계수/음성 권한이 없다. */
data class ActionAssessment(
    val tMs: Long,
    val epoch: Int,
    val state: ActionState,
    val predicted: String? = null,
    val confidence: Float? = null,
    val energy: Float? = null,
    val inferMs: Float = 0f,
    val reason: String? = null,
) {
    val canAffectCount: Boolean get() = false
    val canSpeak: Boolean get() = false
}

data class ActionCalibration(val labels: List<String>, val temperature: Float,
    val confidenceThreshold: Float, val energyThreshold: Float) {
    init {
        require(labels.size == 27 && labels.distinct().size == 27)
        require(temperature.isFinite() && temperature > 0f)
        require(confidenceThreshold.isFinite() && confidenceThreshold in 0f..1f && energyThreshold.isFinite())
    }
    fun assess(tMs: Long, epoch: Int, logits: FloatArray, selected: String, inferMs: Float): ActionAssessment {
        if (logits.size != labels.size || logits.any { !it.isFinite() })
            return ActionAssessment(tMs,epoch,ActionState.UNAVAILABLE,reason="invalid_output")
        val scaled=logits.map { it.toDouble()/temperature.toDouble() }
        val max=scaled.max(); val sum=scaled.sumOf { exp(it-max) }
        val best=scaled.indices.maxBy { scaled[it] }
        val confidence=(1.0/sum).toFloat()
        val energy=(-(max+ln(sum))).toFloat()
        if(!confidence.isFinite() || !energy.isFinite())
            return ActionAssessment(tMs,epoch,ActionState.UNAVAILABLE,reason="invalid_scaled_output")
        val state=when {
            energy > energyThreshold -> ActionState.NOVEL
            confidence < confidenceThreshold -> ActionState.AMBIGUOUS
            labels[best] == selected -> ActionState.TARGET_CANDIDATE
            else -> ActionState.OTHER_EXERCISE
        }
        return ActionAssessment(tMs,epoch,state,labels[best],confidence,energy,inferMs)
    }
}

data class ActionSessionLog(
    val bundleId: String?, val modelHash: String?,
    val observations: List<ActionAssessment>, val rawFrames: List<PosePacketV2>,
    val observationsDropped: Int, val rawDropped: Int, val busyDropped: Int,
    val captureEnabled: Boolean,
)

/** 원관절 기록은 명시적으로 켰을 때만. 호출자가 중재하지 않은 모델 결과임을 JSON에 고정한다. */
object ActionLogJson {
    fun encode(log: ActionSessionLog): String = buildString {
        append("{\"schema\":\"trex.action.shadow/1\",\"mode\":\"shadow\",\"can_affect_count\":false,\"can_speak\":false,")
        append("\"bundle_id\":"); string(log.bundleId); append(",\"model_sha256\":"); string(log.modelHash)
        append(",\"capture_enabled\":${log.captureEnabled},\"busy_dropped\":${log.busyDropped},\"observations_dropped\":${log.observationsDropped},\"raw_dropped\":${log.rawDropped},\"observations\":[")
        log.observations.forEachIndexed { i,a ->
            if(i>0) append(',')
            append("{\"t_ms\":${a.tMs},\"epoch\":${a.epoch},\"state\":"); string(a.state.name)
            append(",\"predicted\":"); string(a.predicted)
            append(",\"confidence\":"); number(a.confidence)
            append(",\"energy\":"); number(a.energy)
            append(",\"infer_ms\":"); number(a.inferMs)
            append(",\"reason\":"); string(a.reason); append('}')
        }
        append("],\"raw_schema\":\"${PosePacketV2.SCHEMA}\",\"world_units\":\"metres_mediapipe_y_down\",\"raw_frames\":[")
        log.rawFrames.forEachIndexed { i,f ->
            if(i>0) append(',')
            append("{\"t_ms\":${f.captureMs},\"capture_ns\":${f.captureNs},\"epoch\":${f.epoch},\"width\":${f.width},\"height\":${f.height},\"front_camera\":${f.frontCamera},\"up_verified\":${f.upVerified},\"xy\":")
            array(f.xy); append(",\"world\":"); array(f.world)
            append(",\"visibility\":"); array(f.visibility); append(",\"presence\":"); array(f.presence)
            append(",\"up\":"); array(floatArrayOf(f.up.x,f.up.y,f.up.z)); append('}')
        }
        append("]}")
    }
    private fun StringBuilder.number(v: Float?) { append(if(v != null && v.isFinite()) v.toString() else "null") }
    private fun StringBuilder.array(a: FloatArray?) {
        if(a==null) { append("null"); return }
        append('['); a.forEachIndexed { i,v -> if(i>0) append(','); number(v) }; append(']')
    }
    private fun StringBuilder.string(s: String?) {
        if(s==null) { append("null"); return }
        append('"'); s.forEach { c -> when(c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if(c.code<32) append("\\u%04x".format(c.code)) else append(c)
        } }; append('"')
    }
}
