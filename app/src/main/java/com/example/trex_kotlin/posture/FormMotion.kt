package com.example.trex_kotlin.posture

/**
 * 교정 화살표 어휘(docs/LIVE_SCREEN_REDESIGN.md §3) — 검사 하나가 틀렸을 때 **어느 관절에서 어느 쪽으로** 움직이라고 그릴지.
 * 새 판정이 아니다: 음성 문장을 만든 그 사건이 화살표도 만든다(COACH·ship 만 — 음성과 같은 문턱). 방향 벡터는 화면에서
 * 계산하므로(정중선 = 엉덩이 중점) 미러·촬영 뷰에 자동으로 맞는다. 재생기 소스 목록에 들어가므로 안드로이드에 기대지 않는다.
 */
enum class MotionAnchor(val landmarks: Set<Int>) {
    SHOULDERS(setOf(11, 12)), ELBOWS(setOf(13, 14)), HIPS(setOf(23, 24)),
    KNEES(setOf(25, 26)), ANKLES(setOf(27, 28)), FEET(setOf(31, 32));
}

enum class MotionKind {
    /** 정중선(엉덩이 중점 x) 쪽으로 — 발·무릎 모으기. */
    TOWARD_MIDLINE,
    /** 정중선에서 바깥으로 — 무릎을 발끝 방향으로. */
    AWAY_MIDLINE,
    UP, DOWN,
    /** 엉덩이 중점 쪽으로 — 팔꿈치를 옆구리에. */
    TOWARD_TORSO,
    /** 관절을 축으로 안쪽 회전 — 발끝을 안으로. */
    ROTATE_IN,
    /** 바깥 회전 — 발끝을 바깥으로. */
    ROTATE_OUT,
}

/** 위반 방향별 화살표. 한쪽 띠만 있는 검사는 그 방향만 둔다. */
data class FormMotion(val anchor: MotionAnchor, val high: MotionKind? = null, val low: MotionKind? = null) {
    fun kindFor(direction: FormDirection): MotionKind? = if (direction == FormDirection.HIGH) high else low

    companion object {
        /**
         * 세트 창 규칙(`CoachCues`, 규칙 JSON)의 화살표 — 반복 검사와 달리 등록부가 없어 기준 피처 이름으로 고른다.
         * 몸통 기울기·척추 계열은 "가슴을 들고" = 어깨에서 위로. 그 밖의 창 규칙은 화살표 없이 문장만(원칙 #5: 모르는 것을 그리지 않는다).
         */
        fun forWindowRule(baseFeature: String): FormMotion? = when {
            baseFeature.startsWith("torso_incl") || baseFeature.startsWith("torso_pitch") ||
                baseFeature.startsWith("spine") || baseFeature.startsWith("sh_over_hip_fwd") -> FormMotion(MotionAnchor.SHOULDERS, high = MotionKind.UP, low = MotionKind.UP)
            else -> null
        }
    }
}
