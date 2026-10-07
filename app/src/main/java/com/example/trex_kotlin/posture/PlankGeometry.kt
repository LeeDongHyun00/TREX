package com.example.trex_kotlin.posture

import kotlin.math.*

/**
 * §39: 측면에서 실제로 보이는 한쪽 관절로 직접 정렬을 잰다. 정상 라벨/초기 자세를 정답으로 고정하지 않는다.
 *
 * 순수 기하라 `PlankAlignment.kt`(규칙 `PostureRule` 에 묶인 추적기)에서 떼어 냈다(spec §99, 설계 §8) — 재생기(replay-jvm `engineFiles`)가 컴파일한다.
 * 동작은 분리 전과 같다(`PlankAlignmentTest`·`plank_replay_fixture.tsv` 파리티). 바닥 계열의 새 관측 층 `FloorChain` 의 `fc_hip_off` 는 화면이 서 있을 때 [HIP] 와 같은 값이다.
 */
object PlankGeometry {
    const val HIP = "plank_hip_offset"
    const val HEAD = "plank_head_pitch"
    const val NECK = "plank_neck_pitch"
    const val READY = "plank_side_ready"
    const val SIDE = "plank_visible_side"

    fun features(xy: FloatArray, vis: FloatArray, width: Int, height: Int): Map<String, Float> {
        if (xy.size != 66 || vis.size != 33 || width <= 0 || height <= 0) return emptyMap()
        fun visible(i: Int, cut: Float = .5f) = vis[i].isFinite() && vis[i] >= cut &&
            xy[i*2].isFinite() && xy[i*2+1].isFinite() && xy[i*2] in 0f..1f && xy[i*2+1] in 0f..1f
        fun p(i: Int) = doubleArrayOf(xy[i*2].toDouble()*width, xy[i*2+1].toDouble()*height)
        fun dist(a: DoubleArray, b: DoubleArray) = hypot(a[0]-b[0], a[1]-b[1])
        // 뒤쪽 팔다리의 외삽 좌표를 평균내지 않는다. 머리 가림은 골반 판정을 막지 않는다.
        val sides = listOf(intArrayOf(7,11,23,25,27), intArrayOf(8,12,24,26,28))
        val side = sides.filter { s -> s.drop(1).all { visible(it) } }
            .maxByOrNull { s -> s.drop(1).minOf { vis[it] } } ?: return emptyMap()
        val (earId, shoulderId, hipId, kneeId, ankleId) = side.toList()
        val shoulder = p(shoulderId); val hip = p(hipId); val ankle = p(ankleId); val knee = p(kneeId)
        val torso = dist(shoulder, hip); val length = dist(shoulder, ankle)
        if (torso < 20 || length < torso*1.6 || length < min(width,height)*.25) return emptyMap()
        if (!visible(11,.2f) || !visible(12,.2f)) return emptyMap()
        val ratio = length / max(dist(p(11),p(12)), 1.0)
        // 몸 축 방향 촬영과 서기/무릎 꿇기를 준비 자세로 구분. 이 값들은 정오 임계값이 아닌 관측 조건이다.
        val kneeAngle = Floor2d.ang(hip, knee, ankle)
        val ready = ratio >= 8 && abs(ankle[0]-shoulder[0])/length >= .7 && kneeAngle >= 145
        val result = mutableMapOf(READY to if (ready) 1f else 0f, SIDE to if (shoulderId == 11) 0f else 1f)
        if (!ready) return result
        result[HIP] = Floor2d.devUp(hip, shoulder, ankle).toFloat()
        if (visible(earId) && visible(0)) {
            val ear = p(earId); val nose = p(0)
            val faceLength = dist(ear,nose)
            if (faceLength >= torso*.03 && faceLength <= torso*.65) {
                val ux = (shoulder[0]-hip[0])/torso; val uy = (shoulder[1]-hip[1])/torso
                var nx = -uy; var ny = ux
                if (ny > 0) { nx = -nx; ny = -ny }
                val fx = nose[0]-ear[0]; val fy = nose[1]-ear[1]
                // 얼굴이 몸통에 수직으로 바닥을 향하면 0°. 몸통 앞쪽으로 들면 양수. 화면 좌우 반전에 불변.
                result[HEAD] = Math.toDegrees(atan2(fx*ux+fy*uy, -(fx*nx+fy*ny))).toFloat()
                val ex = ear[0]-shoulder[0]; val ey = ear[1]-shoulder[1]
                if (dist(ear,shoulder) >= torso*.08) result[NECK] = Math.toDegrees(atan2(ex*nx+ey*ny,ex*ux+ey*uy)).toFloat()
            }
        }
        return result
    }
}

/** 정렬 띠(원시값) — 규칙 JSON 의 alignment 설정이 이 형태로 들어온다(`PostureRule.alignmentConfig`). 순수 값이라 기하와 함께 둔다. */
data class AlignmentConfig(val lower: Float, val upper: Float, val sustainMs: Long = 1000) {
    init { require(lower.isFinite() && upper.isFinite() && lower < upper && sustainMs >= 500) }
}
