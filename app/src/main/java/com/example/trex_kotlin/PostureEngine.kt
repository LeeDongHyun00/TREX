package com.example.trex_kotlin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 자세 세션의 분석 엔진 계약 — [PostureSessionScreen] 이 아는 유일한 인터페이스.
 *
 * 기존에는 rep 카운트(1초=1회), 자세 점수(수식), 추적 이탈(스크립트), 교정 문구(rep 나머지 연산)가
 * 전부 화면 컴포저블 안에 시뮬레이션으로 박혀 있어 실제 엔진을 넣을 자리가 없었다.
 * 실 구현(전면 카메라 + MediaPipe PoseLandmarker + 규칙 기반 판정/실시간 음성 코칭,
 * `claude/correct-exercise-form` 브랜치의 posture 패키지)은 이 인터페이스의 두 번째 구현으로 들어온다:
 *  - rep 검출 → [awaitNextRep] 반환
 *  - 규칙 위반 문구(LiveCoach cue) → [RepAnalysis.cue]
 *  - 랜드마크 가시성 → [trackingLost] / [detectedJoints]
 */
interface PostureAnalysisEngine {
    /** 관절이 화면을 벗어난 상태 — true 인 동안 화면이 세션을 멈추고 안내한다. */
    val trackingLost: Boolean

    /** 스켈레톤 오버레이용 감지 관절 인덱스 (0..12). */
    val detectedJoints: Set<Int>

    /** 세트 시작 시 호출 — 내부 상태 초기화. */
    fun beginSet(set: Int)

    /**
     * 다음 rep 이 완료될 때까지 대기하고 분석 결과를 돌려준다.
     * [paused] 가 true 를 돌려주는 동안은 진행(카운트)하지 않아야 한다.
     */
    suspend fun awaitNextRep(repIndex: Int, paused: () -> Boolean): RepAnalysis
}

/** 한 rep 의 분석 결과. */
data class RepAnalysis(
    /** 0..100 자세 점수. */
    val score: Int,
    /** 말로 안내할 교정 문구 (없으면 null). */
    val cue: String?,
)

/** 자세 세션 스켈레톤 오버레이가 그리는 관절 인덱스 집합. */
val poseJointIndexes: Set<Int> = (0..12).toSet()

/**
 * 카메라 없는 데모 구현 — 기존 화면 시뮬레이션과 동일하게 동작한다.
 * (1초=1 rep, 점수는 수식, 세트 중반에 추적 이탈을 한 번 연출, 4 rep 마다 교정 문구)
 */
class SimulatedPostureAnalysisEngine(
    private val targetReps: Int,
) : PostureAnalysisEngine {

    override var trackingLost by mutableStateOf(false)
        private set

    override var detectedJoints by mutableStateOf(poseJointIndexes)
        private set

    private var trackingLossShown = false

    override fun beginSet(set: Int) {
        trackingLost = false
        trackingLossShown = false
        detectedJoints = poseJointIndexes
    }

    override suspend fun awaitNextRep(repIndex: Int, paused: () -> Boolean): RepAnalysis {
        if (!trackingLossShown && repIndex >= (targetReps / 2).coerceAtLeast(1)) {
            trackingLost = true
            detectedJoints = poseJointIndexes - setOf(10, 11, 12)
            delay(1_600)
            trackingLost = false
            detectedJoints = poseJointIndexes
            trackingLossShown = true
        }

        waitOneSecondPaused(paused)

        val rep = repIndex + 1
        val score = (96 - (rep % 5) * 2 - if (trackingLossShown) 2 else 0).coerceIn(0, 100)
        val cue = if (rep % 4 == 0) simulatedPostureCue(rep) else null
        return RepAnalysis(score = score, cue = cue)
    }

    private suspend fun waitOneSecondPaused(paused: () -> Boolean) {
        var remaining = 1000
        while (remaining > 0) {
            delay(100)
            if (!paused()) {
                remaining -= 100
            }
        }
    }
}

private fun simulatedPostureCue(rep: Int): String = when (rep % 3) {
    0 -> "무릎이 안쪽으로 모이지 않게 벌려주세요"
    1 -> "허리를 곧게 세우고 시선은 정면을 봐주세요"
    else -> "양쪽 어깨 높이를 맞춰주세요"
}
