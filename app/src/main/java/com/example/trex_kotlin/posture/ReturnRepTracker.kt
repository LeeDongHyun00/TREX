package com.example.trex_kotlin.posture

import kotlin.math.abs

/**
 * 준비 위치에서 출발해 같은 위치로 돌아온 사이클을 확정한다.
 * 다음 반복의 반전을 기다리지 않으며, 정지·가림·불완전 복귀를 완료로 만들지 않는다.
 * 최소 움직임과 복귀는 검출 조건일 뿐 올바른 자세 판정이 아니다. 자동 카운트는 계속 참고다.
 *
 * 복귀(v2, spec §99) — 기준은 처음 3샘플 가만히 있던 자세로 고정되는데, 사람의 쉬는 자세는 그 기준과 몇 도씩 다르다.
 * v1 은 기준 ±0.22 × 진폭 안에 다시 들어와야만 복귀로 봐서, 휴식 자세가 그 띠를 벗어나면 세트 끝까지 0회였다:
 *  - 지나친 복귀: 세트 전 느슨하게 선 자세(무릎 154°)가 기준이 되고 세트 중 선 자세는 165° — MM-Fit 스쿼트 w18 set02
 *    (세트 앞 15 s 휴식을 붙인 재생에서 10회 중 1회), FMS 레그 레이즈 s03(시작 171°, 내린 다리 177° — 세 회 모두 0).
 *  - 덜 돌아온 복귀: 세트 중 매번 끝까지 펴지 않는다(띠 7.7° 를 넘게 남는다) — MM-Fit 런지.
 * 그래서 복귀를 방향으로 본다. 이번 회가 기준에서 벗어난 쪽(dir)으로의 이탈 d 가
 *  - 회 깊이의 3분의 2 이상 되돌아왔거나(띠 = max(0.22 × 진폭, [RETURN_DEPTH_FRACTION] × 깊이)),
 *  - 기준을 지나쳤으면(반대쪽으로 진폭 미만) 복귀다. 진폭 이상 지나치면 반대쪽의 새 움직임이지 복귀가 아니다.
 * 띠의 근거는 spec §99 의 재생 A/B(같은 캡처를 v1·v2 로, `research/external_rep_replay/tracker_ab.py`)다. 깊이 비 1/4 → 1/3 → 1/2 로
 * 넓힐수록 MM-Fit(세트 앞뒤 휴식 포함) 정확 일치가 오르고 과다 세트는 0 이었지만, 1/2 은 반만 올라온 반동을 1회로 만든다.
 * 실기기 푸시업 기록(정답 3~4회, `rep_fixture_baseline1.txt`)은 1/4 에서 1회, 1/3 에서 4회 — 1/3 을 고른 이유.
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
    /** 이번 회가 기준에서 벗어난 방향(+1 = 커짐, −1 = 작아짐)과 그 방향으로의 최대 이탈(깊이) — 복귀 띠(§99)의 입력. */
    private var dir = 0
    private var depth = 0f
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
            if (abs(value - origin) >= minAmp) { moving = true; dir = if (value > origin) 1 else -1; depth = 0f }
            else return null
        }
        val d = (value - origin) * dir
        if (d > depth) depth = d
        // §99 복귀: 깊이의 2/3 이상 되돌아왔거나 기준을 진폭 미만으로 지나쳤다(위 KDoc)
        if (d > maxOf(band, depth * RETURN_DEPTH_FRACTION) || d <= -minAmp) {
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

    companion object {
        /** 복귀 띠의 회 깊이 비(§99) — 기준 쪽으로 깊이의 1 − 이 값(= 3분의 2) 이상 되돌아오면 복귀. 0.22 × 진폭보다 좁아지지 않는다. */
        const val RETURN_DEPTH_FRACTION = 1f / 3f
    }
}
