// tools/sync_ios_core.py 생성본 — Android 정본에서 수정한 뒤 동기화하세요.
package com.example.trex_kotlin.posture

import kotlin.math.abs

/**
 * 준비 위치에서 출발해 같은 위치로 돌아온 사이클을 확정한다.
 * 다음 반복의 반전을 기다리지 않으며, 정지·가림·불완전 복귀를 완료로 만들지 않는다.
 * 최소 움직임과 복귀는 검출 조건일 뿐 올바른 자세 판정이 아니다. 자동 카운트는 계속 참고다.
 */
class ReturnRepTracker(private val minAmp: Float, private val refractoryMs: Long = 1200L) {
    data class Cycle(val min: Float, val max: Float)
    private var anchor: Float? = null
    private var stableAt: Long? = null
    private var stableValue = 0f
    private var stableCount = 0
    /** 준비 자세에서 최소 진폭 이상 벗어나 복귀를 기다리는 중 — 세지 않는 조회용(§63 놓친 얕은 걸음은 이때 알리지 않는다). */
    var moving = false
        private set
    private var startAt = 0L
    private var low = 0f
    private var high = 0f
    private var returnAt: Long? = null
    private var returnCount = 0
    private var lastCountAt: Long? = null

    fun resetCycle() {
        anchor = null; stableAt = null; stableCount = 0; moving = false
        returnAt = null; returnCount = 0
    }
    fun reset() { resetCycle(); lastCountAt = null }

    /**
     * 서 있는 기준을 밖에서 준다(§89 후속 2) — 준비 카운트다운 동안 가만히 선 자세. 기준이 이미 있으면 무시하고 false.
     * 기준은 신호가 3프레임(≥ 0.3 s) 가만히 있어야 잡혀서, 카운트다운이 끝나자마자 내려간 첫 회는 기준이 잡히기 전이라 버려졌다.
     */
    fun seed(tMs: Long, value: Float): Boolean {
        if (anchor != null || !value.isFinite()) return false
        anchor = value; low = value; high = value; startAt = tMs; stableAt = null; stableCount = 0
        return true
    }

    fun onFrame(tMs: Long, value: Float): Cycle? {
        val band = minAmp * .22f
        val origin = anchor
        if (origin == null) {
            if (stableAt == null || abs(value - stableValue) > band) {
                stableAt = tMs; stableValue = value; stableCount = 1
            } else {
                stableCount++
                if (stableCount >= 3 && tMs - stableAt!! >= 300L) {
                    anchor = stableValue; low = stableValue; high = stableValue; startAt = tMs
                }
            }
            return null
        }
        low = minOf(low, value); high = maxOf(high, value)
        if (!moving) {
            if (abs(value - origin) >= minAmp) moving = true
            else return null
        }
        if (abs(value - origin) > band) {
            returnAt = null; returnCount = 0
            return null
        }
        if (returnAt == null) returnAt = tMs
        returnCount++
        if (returnCount < 2 || tMs - returnAt!! < 150L || tMs - startAt < refractoryMs ||
            lastCountAt?.let { tMs - it < refractoryMs } == true) return null
        val cycle = Cycle(low, high)
        lastCountAt = tMs; startAt = tMs; moving = false; returnAt = null; returnCount = 0
        low = origin; high = origin
        return cycle
    }
}
