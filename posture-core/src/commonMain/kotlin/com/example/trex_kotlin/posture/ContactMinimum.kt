// tools/sync_ios_core.py 생성본 — Android 정본에서 수정한 뒤 동기화하세요.
package com.example.trex_kotlin.posture

import kotlin.math.abs

/**
 * 접촉 최소 거리(spec §98a) — 85 ms 추론 프레임마다 거리를 받아, 판정 프레임(300 ms 격자, §96) 사이의 **최솟값**을 모아 판정 프레임에 얹는다.
 * 판정 격자의 유일한 예외다: 팔꿈치–무릎처럼 순간적으로 닿았다 떨어지는 거리는 300 ms 표본으로는 닿은 프레임을 놓친다(사용자 지적 2026-10-06 밤 —
 * "순간적으로 닿을 수 있어 프레임에는 안 잡혔지만 닿을 수도 있다").
 *
 * 표본 사이에서 닿았을 가능성도 본다 — 연속 세 표본의 가운데가 국소 최소이면 대칭 V(내려가는 속도 = 올라가는 속도)로 표본 사이의 꼭짓점을 추정한다:
 * `m = y1 − |y0 − y2| / 2` (y0 = y2 면 표본이 곧 꼭짓점이라 보정 0). 0 아래로는 내리지 않는다. 표본 간격이 [MAX_GAP_MS] 를 넘으면 이웃을 버린다(끊김·일시정지).
 * 값이 없는 프레임(관절 가려짐)은 세 표본의 연속을 끊는다 — 가려짐을 닿음으로 읽지 않는다.
 *
 * 앱 전용(`PostureLive`) — 재생기는 300 ms 로그 프레임만 있어 이 값을 다시 만들 수 없고, 로그 피처(`elbow_knee_min_L/R`)로 받는다(`.fcap` 경로는 파리티, `.cap` 경로는 300 ms 값으로 후퇴).
 *
 * @property keys (원본 피처, 내보낼 피처) 쌍.
 */
class ContactMinimum(private val keys: List<Pair<String, String>>) {
    private val binMin = FloatArray(keys.size) { Float.POSITIVE_INFINITY }
    private val prev1 = FloatArray(keys.size) { Float.NaN }
    private val prev2 = FloatArray(keys.size) { Float.NaN }
    private var lastAt: Long? = null

    /** 추론 프레임마다(85 ms). 없는 값은 건너뛰고 연속을 끊는다. */
    fun offer(t: Long, f: Map<String, Float>) {
        val last = lastAt
        if (last != null && (t <= last || t - last > MAX_GAP_MS)) { prev1.fill(Float.NaN); prev2.fill(Float.NaN) }
        lastAt = t
        for (i in keys.indices) {
            val cur = f[keys[i].first]?.takeIf { it.isFinite() }
            if (cur == null) { prev2[i] = Float.NaN; prev1[i] = Float.NaN; continue }
            binMin[i] = minOf(binMin[i], cur)
            val y1 = prev1[i]; val y0 = prev2[i]
            if (!y1.isNaN() && !y0.isNaN() && y1 < y0 && y1 < cur) binMin[i] = minOf(binMin[i], maxOf(0f, y1 - abs(y0 - cur) / 2f))
            prev2[i] = y1; prev1[i] = cur
        }
    }

    /** 판정 프레임에서: 모은 최솟값을 내보낼 피처로 내고 비운다(이웃 표본은 유지 — 칸 경계를 넘는 꼭짓점 추정은 다음 칸에 들어간다). 없으면 빈 맵. */
    fun drain(): Map<String, Float> {
        var out: HashMap<String, Float>? = null
        for (i in keys.indices) {
            val v = binMin[i]; binMin[i] = Float.POSITIVE_INFINITY
            if (v.isFinite()) (out ?: HashMap<String, Float>().also { out = it })[keys[i].second] = v
        }
        return out ?: emptyMap()
    }

    /** 준비·일시정지·사람 없음 — 모은 값과 이웃을 버린다. */
    fun clear() { binMin.fill(Float.POSITIVE_INFINITY); prev1.fill(Float.NaN); prev2.fill(Float.NaN); lastAt = null }

    companion object {
        /** 이웃 표본으로 인정하는 최대 간격 — 85 ms 추론이 열·지연으로 늘어나도 두 프레임 안. */
        const val MAX_GAP_MS = 250L
        /** 스탠딩 사이드 크런치의 같은 쪽 팔꿈치–무릎(`LegGeometry.elbowKnee`) → `elbow_knee_min_L/R`. */
        fun elbowKnee(): ContactMinimum = ContactMinimum(StepSide.entries.map { LegGeometry.elbowKnee(it) to LegGeometry.elbowKneeMin(it) })
    }
}
