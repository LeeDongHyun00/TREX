package com.example.trex_kotlin.posture

/** v2의 유효 관측만 받는 교정·점수 정책. 서로 겹치지 않는 두 창에서 확인한 항목만 반영한다. */
class V2FormFeedback(rules: PostureRuleSet, private val exercise: String) {
    private val eligible = rules.rulesFor(exercise, includeBeta = false)
        .filter { it.kind == "window" && labels.containsKey(it.baseFeature) }
        .distinctBy { it.feature }
    private val coach = LiveCoach(PostureRuleSet(POLICY_VERSION, "", eligible), exercise,
        includeBeta = false, requireAnchor = true, speakBeta = false)
    private var frames = 0
    private var anchored = false
    private var lastAt: Long? = null
    private var previous = emptyMap<String, OnsetState>()
    private val history = linkedMapOf<String, RuleOutcome>()
    var current: List<RuleOutcome> = emptyList()
        private set
    val supported: Boolean get() = eligible.isNotEmpty()

    fun interrupt() {
        coach.onObservationLost()
        anchored = false
        lastAt = null
        frames = 0
        previous = emptyMap()
        current = emptyList()
    }

    /** 첫 완결 반복 이후부터 평가한다. 가림 이후에도 새 반복을 확인해야 다시 시작한다. */
    fun accept(now: Long, features: Map<String, Float>, movementConfirmed: Boolean): String? {
        if (lastAt?.let { now <= it || now-it>1000 } == true) interrupt()
        lastAt = now
        val missing = eligible.filter { features[it.baseFeature]?.isFinite() != true }.map { it.id }.toSet()
        if (missing.isNotEmpty()) {
            previous = previous.filterKeys { it !in missing }
            current = current.map { if(it.ruleId in missing) it.copy(overall=Verdict.ABSTAIN, abstainReason="관절을 확인할 수 없음") else it }
        }
        if (!anchored) {
            if (movementConfirmed) { coach.anchor(); anchored = true }
            return null
        }
        if (!coach.onFrame(now, features)) { interrupt(); return null }
        if (++frames % 8 != 0) return null
        val event = coach.evaluate(now)
        val states = coach.lastStates
        current = states.map { state ->
            val stable = state.recent != Verdict.ABSTAIN && previous[state.rule.id]?.let {
                it.recent == state.recent && it.direction == state.direction
            } == true
            val label = labels.getValue(state.rule.baseFeature)
            RuleOutcome(state.rule.id, label, label, false,
                if (stable) state.recent else Verdict.ABSTAIN, null, state.direction,
                "$label 측정값이 참고 범위를 벗어났습니다", "움직임을 천천히 하고 $label 항목을 확인해 주세요",
                "AIHub 참고 범위 · 전신 자세의 정확도가 아닙니다", state.rule.cvAuc,
                if (stable) null else state.abstainReason ?: "연속 관측 확인 중")
        }
        previous = states.associateBy { it.rule.id }
        current.filter { it.overall != Verdict.ABSTAIN }.forEach { item ->
            // 사라진 항목 또는 이후 정상 창 때문에 이미 관측한 위반이 지워지지 않는다.
            if (history[item.ruleId]?.overall != Verdict.VIOLATION) history[item.ruleId] = item
        }
        return event?.takeIf { e -> current.any { it.ruleId == e.rule.id && it.overall == Verdict.VIOLATION } }
            ?.let { e -> current.first { it.ruleId == e.rule.id }.let { "${it.observation}. ${it.fix}." } }
    }

    fun summary(): List<RuleOutcome> = eligible.map { rule ->
        history[rule.id] ?: RuleOutcome(rule.id, labels.getValue(rule.baseFeature), labels.getValue(rule.baseFeature),
            false, Verdict.ABSTAIN, null, null, "", "", null, rule.cvAuc, "충분한 연속 관측 없음")
    }

    companion object {
        const val POLICY_VERSION = "v2-feedback/1"
        // 발끝 방향·바벨·척추 모양을 대리 피처로 단정하던 규칙은 연결하지 않는다.
        // 조건명이 실제 측정과 달랐던 나머지 규칙도 측정한 관절·통계 범위로만 안내한다.
        private val labels = mapOf(
            "shoulder_asym" to "양쪽 어깨 높이 차이", "knee_minside" to "무릎 굽힘 각도",
            "knee_mean" to "무릎 굽힘 각도", "knee_maxside" to "무릎 굽힘 각도",
            "head_pitch" to "고개 기울기", "elbow_torso_R" to "오른팔꿈치와 몸통 사이 거리",
            "torso_incl" to "몸통 기울기", "torso_pitch" to "몸통 앞뒤 기울기",
            "face_vs_torso" to "고개와 몸통 사이 각도", "shoulder_R" to "오른팔 들림 각도",
            "elbow_mean" to "팔꿈치 굽힘 각도", "hip_below_knee" to "골반과 무릎 사이 높이",
            "sh_over_hip_fwd" to "골반 대비 어깨의 앞뒤 위치", "palm_head_dist" to "손과 머리 사이 거리",
            "shoulder_h_R" to "오른쪽 어깨 높이", "hand_h_asym" to "양손 높이 차이", "grip_w" to "양손 사이 거리",
        )

        fun score(items: List<RuleOutcome>, mode: CoachMode): Int? {
            val judged = items.filter { !it.beta && it.overall != Verdict.ABSTAIN }
            return if (mode != CoachMode.COACH || judged.isEmpty()) null
            else Math.round(100f * judged.count { it.overall == Verdict.OK } / judged.size)
        }
    }
}
