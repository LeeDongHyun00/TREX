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
    private var moving = false
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
