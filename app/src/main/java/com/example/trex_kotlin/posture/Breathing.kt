package com.example.trex_kotlin.posture

/**
 * 호흡 표시(docs/LIVE_SCREEN_REDESIGN.md §5) — 메트로놈이 아니라 카운터의 **위상을 따라가는** 표시. 표준: 신장성(내려가기·늘리기)에 들숨,
 * 단축성(힘쓰기)에 날숨(`ExerciseGuides.breathing` 문장과 같다). 소리는 내지 않는다 — 음성과 겹치고, 앞서 지시하면 재촉이 된다(원칙 #6).
 * 카운터는 beta 라 위상도 beta 다 — 작게, 큰 숫자 옆에만.
 */
object Breathing {
    const val INHALE = "들이마시기"
    const val EXHALE = "내쉬기"
    const val NATURAL = "자연스럽게 호흡"

    /**
     * 카운터의 '바닥'(신호 극값)이 힘쓰는 끝인가. 스쿼트·런지·푸시업은 바닥이 내려간 자세(신장성) → 복귀에 날숨.
     * 컬·로우·풀다운·크런치·니업은 바닥이 수축(팔꿈치 각 최소 등) → 바닥으로 가며 날숨.
     */
    fun exhaleTowardBottom(exercise: String): Boolean =
        listOf("컬", "로우", "풀", "크런치", "니업", "레이즈").any { exercise.contains(it) }

    /** [direction] = `RepCounter.motionDirection`(-1 바닥 쪽, +1 복귀, 0 모름). 모르면 null — 없는 것을 말하지 않는다. */
    fun word(exercise: String, direction: Int): String? = when {
        direction == 0 -> null
        (direction < 0) == exhaleTowardBottom(exercise) -> EXHALE
        else -> INHALE
    }
}
