package com.example.trex_kotlin.posture

import kotlin.math.sqrt

/** 캡처 좌표/시각의 원자료. world는 MP 원본 m/y-down이며 규칙용 cm/y-up과 다르다. */
data class PosePacketV2(
    val captureMs: Long,
    val captureNs: Long,
    val epoch: Int,
    val width: Int,
    val height: Int,
    val xy: FloatArray,
    val world: FloatArray?,
    val visibility: FloatArray,
    val presence: FloatArray,
    val up: Vec3,
    val upVerified: Boolean,
    val frontCamera: Boolean,
) {
    companion object {
        const val SCHEMA = "trex.pose.packet/2"
        fun from(sample: PoseSample, tMs: Long, tNs: Long, epoch: Int, front: Boolean) = PosePacketV2(
            tMs, tNs, epoch, sample.imageWidth, sample.imageHeight, sample.normalizedXy,
            sample.rawWorld, sample.rawVisibility ?: sample.visibility,
            sample.rawPresence ?: sample.visibility, sample.up, sample.upVerified, front,
        )
    }
}

/** 선택 종목/기존 카운터 결과를 입력하지 않는 순수 2D 정규화. Python action_input과 정본이 같다. */
object ActionInputBuilder {
    const val SCHEMA = "trex.action.pose2d/1"
    const val FEATURES = 132
    const val FRAMES = 16

    fun encode(packet: PosePacketV2): FloatArray = encode(packet.xy, packet.visibility, packet.presence, packet.width, packet.height)

    fun encode(xy: FloatArray, v: FloatArray, p: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(FEATURES)
        if (width <= 0 || height <= 0 || xy.size != 66 || v.size != 33 || p.size != 33) return out
        val aspect = width.toFloat() / height
        val ok = BooleanArray(33) { j -> xy[j*2].isFinite() && xy[j*2+1].isFinite() &&
            v[j].isFinite() && p[j].isFinite() && minOf(v[j],p[j]) >= .5f }
        val hips = listOf(23,24).filter { ok[it] }
        val shoulders = listOf(11,12).filter { ok[it] }
        if (hips.isEmpty() || shoulders.isEmpty() || ok.count { it } < 12) return out
        fun mean(indices: List<Int>, axis: Int) = indices.sumOf { (xy[it*2+axis] * if(axis == 0) aspect else 1f).toDouble() }.toFloat()/indices.size
        val cx=mean(hips,0); val cy=mean(hips,1)
        val dx=mean(shoulders,0)-cx; val dy=mean(shoulders,1)-cy
        val scale=sqrt(dx*dx+dy*dy)
        if (!scale.isFinite() || scale < .02f) return out
        for (j in 0..32) if (ok[j]) {
            out[j*4]=((xy[j*2]*aspect-cx)/scale).coerceIn(-4f,4f)
            out[j*4+1]=((xy[j*2+1]-cy)/scale).coerceIn(-4f,4f)
            out[j*4+2]=minOf(v[j],p[j]).coerceIn(0f,1f)
            out[j*4+3]=1f
        }
        return out
    }
}

/** 관측되지 않은 프레임은 보간하지 않는다. 촬영 세대/역순/긴 누락에서 과거를 분리. */
class ActionWindow(private val gapMs: Long = 750L) {
    private val ring=Array(ActionInputBuilder.FRAMES) { FloatArray(ActionInputBuilder.FEATURES) }
    private var cursor=0
    private var epoch: Int?=null
    private var lastMs: Long?=null
    var size=0; private set
    fun reset() { ring.forEach { it.fill(0f) }; cursor=0; size=0; lastMs=null; epoch=null }
    fun add(tMs: Long, inputEpoch: Int, features: FloatArray): Boolean {
        require(features.size == ActionInputBuilder.FEATURES)
        val last=lastMs
        if (epoch != inputEpoch) reset()
        else if (last != null && tMs <= last) return false
        else if (last != null && tMs-last > gapMs) reset()
        epoch=inputEpoch; lastMs=tMs
        if (features.any { !it.isFinite() } || features.none { it != 0f }) { reset(); return false }
        features.copyInto(ring[cursor]); cursor=(cursor+1)%ring.size
        size=minOf(size+1,ring.size)
        return true
    }
    fun copyInto(target: FloatArray) {
        require(target.size == ActionInputBuilder.FRAMES*ActionInputBuilder.FEATURES)
        for (i in ring.indices) ring[(cursor+i)%ring.size].copyInto(target,i*ActionInputBuilder.FEATURES)
    }
}
