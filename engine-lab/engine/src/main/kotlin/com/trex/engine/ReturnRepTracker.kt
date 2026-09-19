package com.trex.engine

import kotlin.math.abs

/**
 * 관측된 출발 구간 → 충분한 이탈 → 방향 반전 → 출발 구간 복귀를 확정한다.
 * 마지막 복귀 프레임에서 완료하므로 끝점 정지나 다음 반복의 출발을 요구하지 않는다.
 *
 * 초기 기준은 연속 3개 이상·300ms 이상의 안정된 관측에서만 만든다. 정자세 인증은 아니다.
 * 단일 큰 잡값을 반복으로 세지 않도록 최소 진폭을 넘은 관측 2개와 복귀 방향 관측 2개를 요구한다.
 * 300ms 간격에서 양 끝/왕복 경로가 빠진 빠른 반복은 복원하지 않는다. 아래 비율과 시간은
 * 기존 최소 진폭·불응기를 이용한 검출 정책이며 다양한 사람의 정확도를 검증한 수치가 아니다.
 * 입력은 단조 증가하는 같은 시계의 ms. 중복·역행 프레임은 버리고 진행 중 반복을 무효화한다.
 */
class ReturnRepTracker(
    private val minAmp: Float,
    private val refractoryMs: Long = 1200L,
    private val maxGapMs: Long = 1500L,
    private val startMin: Float? = null,
    private val startMax: Float? = null,
    private val outboundSign: Int = 0,
) {
    data class Cycle(val min: Float, val max: Float, val startMs: Long = 0)
    private enum class Phase { PREPARING, READY, OUTBOUND, RETURNING }
    val moving: Boolean get() = phase == Phase.OUTBOUND || phase == Phase.RETURNING

    init {
        require(minAmp.isFinite() && minAmp > 0f)
        require(refractoryMs >= 0 && maxGapMs > 0)
    }

    private var phase = Phase.PREPARING
    private var anchor = 0f
    private var stableAt: Long? = null
    private var stableMin = 0f
    private var stableMax = 0f
    private var stableCount = 0
    private var lastTime: Long? = null
    private var lastHomeAt = 0L
    private var startAt = 0L
    private var direction = 0f
    private var peakDistance = 0f
    private var excursionSamples = 0
    private var returnSamples = 0
    private var lastReturnDistance = 0f
    private var low = 0f
    private var high = 0f
    private var lastCountAt: Long? = null

    /** 관측 손실·일시정지 후에는 출발 기준부터 다시 확인한다. 시계와 완료 이력은 보존한다. */
    fun resetCycle() {
        phase = Phase.PREPARING
        stableAt = null; stableCount = 0
        direction = 0f; peakDistance = 0f; excursionSamples = 0; returnSamples = 0
    }

    fun onObservationLost() = resetCycle()

    /** 새 세션만 시계의 원점을 바꿀 수 있다. */
    fun reset() { resetCycle(); lastTime = null; lastCountAt = null }

    fun onFrame(tMs: Long, value: Float): Cycle? {
        val previous = lastTime
        if (previous != null && tMs <= previous) {
            onObservationLost()
            return null // 오래된 프레임으로 단조 시계의 최댓값을 되감지 않는다.
        }
        if (previous != null && tMs - previous > maxGapMs) onObservationLost()
        lastTime = tMs
        if (!value.isFinite()) { onObservationLost(); return null }

        val homeBand = minAmp * .22f
        val turnBand = minAmp * .25f
        if (phase == Phase.PREPARING) {
            // 출발 구간의 넓은 관측 조건이다. 정자세 각도나 사용자의 목표 깊이가 아니다.
            if (startMin?.let { value < it } == true || startMax?.let { value > it } == true) {
                resetCycle(); return null
            }
            if (stableAt == null || maxOf(stableMax, value) - minOf(stableMin, value) > homeBand) {
                stableAt = tMs; stableMin = value; stableMax = value; stableCount = 1
            } else {
                stableMin = minOf(stableMin, value); stableMax = maxOf(stableMax, value)
                stableCount++
                if (stableCount >= 3 && tMs - stableAt!! >= 300L) {
                    anchor = stableMin + (stableMax - stableMin) / 2f
                    ready(tMs)
                }
            }
            return null
        }

        val offset = value - anchor
        if (phase == Phase.READY) {
            if (abs(offset) <= homeBand) { lastHomeAt = tMs; return null }
            direction = if (offset > 0f) 1f else -1f
            if (outboundSign != 0 && direction.toInt() != outboundSign) {
                onObservationLost(); return null
            }
            startAt = lastHomeAt
            low = minOf(anchor, value); high = maxOf(anchor, value)
            peakDistance = 0f; excursionSamples = 0; returnSamples = 0
            phase = Phase.OUTBOUND
        }

        val distance = offset * direction
        low = minOf(low, value); high = maxOf(high, value)
        peakDistance = maxOf(peakDistance, distance)
        if (distance >= minAmp) excursionSamples++

        if (phase == Phase.OUTBOUND && peakDistance >= minAmp && peakDistance - distance >= turnBand) {
            phase = Phase.RETURNING
            returnSamples = 1; lastReturnDistance = distance
        } else if (phase == Phase.RETURNING) {
            if (distance > lastReturnDistance + turnBand) {
                phase = Phase.OUTBOUND; returnSamples = 0
            } else if (distance < lastReturnDistance - minAmp * .02f) {
                returnSamples++
                lastReturnDistance = distance
            }
        }

        if (abs(offset) <= homeBand) {
            val complete = phase == Phase.RETURNING && excursionSamples >= 2 && returnSamples >= 2 &&
                tMs - startAt >= refractoryMs && lastCountAt?.let { tMs - it >= refractoryMs } != false
            val cycle = if (complete) Cycle(low, high, startAt) else null
            // 너무 짧거나 덜 관측된 왕복은 여기서 폐기한다. 이후 가만히 선 시간을 더해 살리지 않는다.
            ready(tMs)
            if (complete) lastCountAt = tMs
            return cycle
        }
        if (distance < -homeBand) onObservationLost() // 복귀 구간을 건너뛴 좌표 점프는 완료로 보간하지 않는다.
        return null
    }

    private fun ready(tMs: Long) {
        phase = Phase.READY; lastHomeAt = tMs
        direction = 0f; peakDistance = 0f; excursionSamples = 0; returnSamples = 0
        low = anchor; high = anchor
    }
}
