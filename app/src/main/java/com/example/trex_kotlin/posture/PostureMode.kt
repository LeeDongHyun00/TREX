package com.example.trex_kotlin.posture

import android.content.Context

/**
 * 세션 모드 (spec §29) — 초보자와 숙련자는 같은 엔진을 반대 정책으로 쓴다.
 *
 * 근거(§29 설계): 관측 시스템은 **스타일과 오류를 구분할 수 없다** — 실측에서 사용자의 깊은
 * 스쿼트가 AIHub 표준(더 얕음)에 위반 판정된 사건이 그 증거. 숙련자의 습관 폼은 의도된 폼이므로
 * 모집단 기준 실시간 지적은 범주 오류가 된다. 그래서:
 *
 *  - COACH(코치, 기본): 모집단(AIHub)이 기준, 앱이 가르친다. 기존 동작 전부 유지.
 *  - TRACK(기록, 숙련): 본인이 기준, 앱은 기록한다.
 *      · 음성 = 렙 카운트만. HABIT("처음부터")은 스타일일 수 있으므로 침묵 — 판정은 로그에만.
 *      · DRIFT("점점")만 발화 — 세트 내 변화(피로)는 숙련자에게도 환영받는 정보.
 *      · 무효 렙 → "파셜"로 개명·집계만: 숙련자의 파셜은 훈련 기법이지 잘못이 아니다.
 *      · 위반 부위 붉은 강조 없음, 자세 점수 대신 템포(렙 간격 중앙값) 표시.
 *
 * 모드는 **정책 레이어만** 바꾼다 — 임계값·판정·로그는 두 모드에서 동일하게 계산·기록된다
 * (모든 사용자 원칙: 특정 사용자군을 위해 모집단 파라미터를 바꾸지 않는다).
 * 종목별로 저장한다 — 스쿼트는 숙련, 처음 배우는 종목은 코치일 수 있다.
 */
enum class CoachMode { COACH, TRACK }

class ModeStore(context: Context) {
    private val prefs = context.getSharedPreferences("trex_posture", Context.MODE_PRIVATE)

    fun get(exercise: String): CoachMode =
        if (prefs.getString("mode_$exercise", null) == "track") CoachMode.TRACK else CoachMode.COACH

    fun set(exercise: String, mode: CoachMode) {
        prefs.edit().putString("mode_$exercise", if (mode == CoachMode.TRACK) "track" else "coach").apply()
    }
}
