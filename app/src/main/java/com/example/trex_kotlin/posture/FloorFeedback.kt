package com.example.trex_kotlin.posture

enum class FloorFeedbackPhase { PREPARING, MEASURING, ATTENTION, RECOVERED, UNAVAILABLE, PAUSED }

/** 화면·골격·음성이 같은 순간의 관측을 사용한다. 빨강은 확인할 부위이며 정답 인증이 아니다. */
data class FloorFeedback(
    val phase: FloorFeedbackPhase,
    val message: String,
    val landmarks: Set<Int> = emptySet(),
    val speech: String? = null,
    val ruleId: String? = null,
) {
    val title: String get() = when (phase) {
        FloorFeedbackPhase.PREPARING -> "측정 준비"
        FloorFeedbackPhase.MEASURING -> "측정 중 · 참고"
        FloorFeedbackPhase.ATTENTION -> "확인 필요 · 참고"
        FloorFeedbackPhase.RECOVERED -> "범위 복귀 · 참고"
        FloorFeedbackPhase.UNAVAILABLE -> "측정 일시 중지"
        FloorFeedbackPhase.PAUSED -> "일시정지"
    }
}

/** §36: 바닥 참고 피드백 전용 정책. 규칙 등급·점수·서서 종목의 음성 정책은 바꾸지 않는다. */
class FloorFeedbackController(private val exercise: String, rules: List<PostureRule>) {
    private val activeRules = rules.filter { it.exercise == exercise && it.status != RuleStatus.EXCLUDE }
    private val repRule = activeRules.firstOrNull { it.repConfig != null }
    private val holdRule = activeRules.firstOrNull { it.holdConfig != null }
    private data class Issue(val id: String, val message: String, val points: Set<Int>, val recovery: String)
    private val since = HashMap<String, Long>()
    private val voiced = HashMap<String, Long>()
    private var lastVoice: Long? = null
    private var lastFrame: Long? = null
    private var lastRep: Long? = null
    private var badReps = 0
    private var goodReps = 0
    private var repIssueUntil = 0L
    private var active: Issue? = null
    private var goodSince: Long? = null
    private var recoveryUntil = 0L
    private var recoveryMessage = ""
    private var measuredSince: Long? = null
    private var readyAnnounced = false
    private var lastMode: CoachMode? = null

    /** 테스트와 앱이 같은 진입점을 사용한다. voiceEnabled=false인 동안 발화 쿨다운은 소비하지 않는다. */
    fun update(
        now: Long, features: Map<String, Float>, hold: HoldSnapshot?, windows: List<OnsetState>,
        rep: RepRecord? = null, anchored: Boolean = true, observable: Boolean = true,
        paused: Boolean = false, mode: CoachMode = CoachMode.COACH, voiceEnabled: Boolean = true,
        alignment: AlignmentSnapshot? = null,
    ): FloorFeedback {
        if (alignment != null && !paused) {
            if (mode == CoachMode.TRACK) return FloorFeedback(FloorFeedbackPhase.MEASURING,
                "초기 자세와 고개·골반의 변화를 비교하고 있어요")
            return alignmentFeedback(now,alignment,voiceEnabled)
        }
        val gap = lastFrame?.let { now - it }
        if (gap != null && (gap <= 0 || gap > 1000) || lastMode != null && lastMode != mode) clearObservation()
        lastFrame = now; lastMode = mode
        if (paused || !observable || features.isEmpty()) {
            clearObservation()
            val msg = if (paused) "다시 시작하면 측정을 이어가요" else "필요한 관절이 안 보여요. 몸 전체가 보이도록 폰을 옮겨 주세요"
            return FloorFeedback(if (paused) FloorFeedbackPhase.PAUSED else FloorFeedbackPhase.UNAVAILABLE, msg,
                speech = if (paused) null else voice("coverage", msg, now, voiceEnabled, 15000))
        }
        if (!anchored || holdRule != null && hold?.baseline == null) {
            clearObservation()
            val msg = if (holdRule != null) "몸을 옆에서 비추고 처음 자세를 5초간 유지해 주세요" else "몸 전체가 보이면 편하게 동작을 시작해 주세요"
            return FloorFeedback(FloorFeedbackPhase.PREPARING, msg)
        }
        if (measuredSince == null) measuredSince = now
        val candidates = ArrayList<Issue>()
        if (holdRule != null && hold?.currentSide != null && hold.currentSide != 0 && hold.currentSideMs >= hold.breakMs) {
            val direction = if (hold.currentSide > 0) "위" else "아래"
            candidates += Issue(holdRule.id + ":" + hold.currentSide,
                "처음 자세보다 골반이 ${direction}로 이동했어요. 몸통 정렬을 확인해 주세요",
                setOf(11, 12, 23, 24), "골반이 처음 자세의 측정 범위로 돌아왔어요")
        }
        if (rep != null && repRule != null && rep.tMs != lastRep && mode == CoachMode.COACH) {
            if (lastRep?.let { rep.tMs - it > 15000 } == true) { badReps = 0; goodReps = 0 }
            lastRep = rep.tMs
            val cfg = repRule.repConfig!!
            if (!rep.cycleMin.isFinite() || !rep.cycleMax.isFinite()) { badReps = 0; goodReps = 0; repIssueUntil = 0 }
            else {
                val bad = if (cfg.direction == "min") rep.cycleMin > cfg.threshold else rep.cycleMax < cfg.threshold
                if (bad) { badReps++; goodReps = 0; if (badReps >= 2) repIssueUntil = now + 5000 }
                else { goodReps++; badReps = 0; repIssueUntil = 0 }
            }
        }
        if (mode == CoachMode.COACH && repRule != null && now < repIssueUntil && features[repRule.baseFeature]?.isFinite() == true) {
            candidates += Issue(repRule.id,
                if (exercise == "힙쓰러스트") "두 번 연속 골반 상승 범위가 작게 측정됐어요. 상단 정렬을 확인해 주세요"
                else "두 번 연속 가슴 이동 범위가 작게 측정됐어요. 내려가는 범위를 확인해 주세요",
                if (exercise == "힙쓰러스트") setOf(11, 12, 23, 24, 25, 26) else setOf(11, 12, 13, 14, 15, 16),
                "최근 두 번의 동작이 참고 범위 안으로 들어왔어요")
        }
        val live = windows.filter { st -> st.rule.id in activeRules.map { it.id } && st.rule.kind == "window" &&
            features[st.rule.baseFeature]?.isFinite() == true && mode == CoachMode.COACH }
        val violations = live.filter { it.recent == Verdict.VIOLATION }
        since.keys.retainAll(violations.map { it.rule.id }.toSet())
        for (state in violations) {
            val start = since.getOrPut(state.rule.id) { now }
            if (now - start >= 1500) candidates += windowIssue(state.rule)
        }
        val chosen = candidates.firstOrNull { it.id == active?.id } ?: candidates.firstOrNull()
        if (chosen != null) {
            active = chosen; goodSince = null; recoveryUntil = 0
            return FloorFeedback(FloorFeedbackPhase.ATTENTION, chosen.message, chosen.points,
                voice(chosen.id, "참고 안내예요. ${chosen.message}", now, voiceEnabled, 20000), chosen.id)
        }
        val previous = active
        if (previous != null) {
            val recovered = when {
                previous.id.startsWith(holdRule?.id ?: "\u0000") -> hold?.currentSide == 0
                previous.id == repRule?.id -> goodReps >= 2
                else -> live.any { it.rule.id == previous.id && it.recent == Verdict.OK }
            }
            if (recovered) {
                if (goodSince == null) goodSince = now
                if (now - goodSince!! >= 1000) {
                    active = null; goodSince = null; recoveryUntil = now + 3000; recoveryMessage = previous.recovery
                }
            } else {
                goodSince = null
                // 가림·유보·시간 만료는 회복이 아니다. 지나간 빨간 표시를 남기지 않는다.
                val stillObserved = previous.id.startsWith(holdRule?.id ?: "\u0000") && hold?.currentSide != null ||
                    previous.id == repRule?.id && now - (lastRep ?: 0) <= 5000 ||
                    live.any { it.rule.id == previous.id && it.recent != Verdict.ABSTAIN }
                if (!stillObserved) active = null
            }
        }
        if (now < recoveryUntil) return FloorFeedback(FloorFeedbackPhase.RECOVERED, recoveryMessage,
            speech = voice("recovery", recoveryMessage, now, voiceEnabled, 20000))
        val limited = activeRules.isEmpty()
        val msg = when {
            limited && exercise == "크런치" -> "머리 들림의 변화를 측정하고 있어요 · 참고"
            limited -> "팔 들림의 변화를 측정하고 있어요 · 참고"
            mode == CoachMode.TRACK -> "움직임을 기록하고 있어요. 처음 자세와의 변화만 안내해요"
            else -> "움직임을 측정 중이에요. 참고 범위를 벗어나면 알려드려요"
        }
        return FloorFeedback(FloorFeedbackPhase.MEASURING, msg)
    }

    private fun clearObservation() {
        since.clear(); active = null; goodSince = null; repIssueUntil = 0; recoveryUntil = 0
        badReps = 0; goodReps = 0; lastRep = null; measuredSince = null
    }

    private fun alignmentFeedback(now: Long, state: AlignmentSnapshot, enabled: Boolean): FloorFeedback {
        val issue = state.issue
        if (issue != null) return FloorFeedback(FloorFeedbackPhase.ATTENTION,issue.message,issue.points,
            voice(issue.rule.id+":"+issue.side,"참고 안내예요. ${issue.message}",now,enabled,20000),issue.rule.id)
        val recovery = state.recovery
        if (recovery != null) return FloorFeedback(FloorFeedbackPhase.RECOVERED,recovery.message,
            speech=voice("alignment-recovery",recovery.message,now,enabled,20000))
        if (!state.placementReady) return FloorFeedback(FloorFeedbackPhase.UNAVAILABLE,
            "몸 옆에서 어깨부터 발목까지 길게 담아주세요. 다리를 펴고 플랭크 자세를 잡아주세요",
            speech=voice("plank-placement","몸 옆에서 어깨부터 발목까지 보이게 해주세요",now,enabled,15000))
        val missing = state.items.firstOrNull { it.value == null }
        if (missing != null) return FloorFeedback(FloorFeedbackPhase.UNAVAILABLE,
            "${if(missing.head) "고개" else "골반"} 관절이 안 보여요. 보이는 항목만 계속 확인하고 있어요")
        return FloorFeedback(FloorFeedbackPhase.MEASURING,
            if(state.referenceEligible) "관측한 고개와 골반이 정렬 범위 안에 있어요 · 참고" else "고개와 골반 정렬의 지속 여부를 확인하고 있어요")
    }

    private fun voice(key: String, text: String, now: Long, enabled: Boolean, cooldown: Long): String? {
        if (!enabled || lastVoice?.let { now - it < 8000 } == true || voiced[key]?.let { now - it < cooldown } == true) return null
        lastVoice = now; voiced[key] = now
        return text
    }

    private fun windowIssue(rule: PostureRule): Issue {
        val head = rule.baseFeature.startsWith("head")
        val hand = rule.baseFeature.contains("hand") || rule.baseFeature.contains("wrist")
        val message = when {
            head -> "고개 각도가 참고 범위를 벗어났어요. 목 정렬을 확인해 주세요"
            hand -> "손 위치가 참고 범위를 벗어났어요. 손과 어깨 위치를 확인해 주세요"
            exercise == "시저크로스" -> "다리 높이가 참고 범위를 벗어났어요. 허리가 뜨지 않는 범위인지 확인해 주세요"
            else -> "${rule.condition} 항목이 참고 범위를 벗어났어요"
        }
        return Issue(rule.id, message,
            when { head -> setOf(0, 7, 8, 11, 12); hand -> setOf(11, 12, 13, 14, 15, 16)
                else -> RuleHighlight.landmarksFor(rule.baseFeature) },
            if (head) "고개 각도가 참고 범위로 돌아왔어요" else "측정값이 참고 범위로 돌아왔어요")
    }
}
