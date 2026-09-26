package com.example.trex_kotlin.posture

import kotlin.math.abs

/**
 * 반복별 자세 검사 (설계 §21, spec §62a) — "횟수와 자세는 다른 질문" 의 자세 쪽.
 *
 * 세트 평균·범위 규칙(`PostureRuleSet.evaluate`)이 2026-09-25 실기기 세트에서 서 있는 프레임에 끌려가 오탐을 냈다
 * (무릎 규칙: 서 있는 118 프레임이 평균을 정해 바닥이 정상인데 "무릎 안쪽" 4번). 이 평가기는 **카운터가 낸 사이클 창** 안에서만 잰다:
 *   START  — 첫 반복의 하강 직전 상단(서 있을 때) = 그 세트의 **시작 자세**. 한 번 잡아 개인 기준으로 쓴다.
 *   TOP    — 각 반복의 하강 직전 상단(≤ 5 프레임 ≈ 1.5 s). 발 너비·발끝처럼 서 있을 때 정확한 정적 기하량.
 *   BOTTOM — 사이클 최소값 + 진폭/3 아래 프레임. 무릎 방향처럼 바닥에서 드러나는 것.
 *   CYCLE  — 하강 시작부터 복귀까지. 상체 숙임의 최대처럼 반복 안 극값.
 * 기준은 셋이다: 절대 띠(모집단), 시작 자세 대비 비율, 시작 자세 대비 차 — 상대 기준은 체형·카메라 편향을 상쇄한다(발끝 각의 사람별 상수 편향,
 * 발 너비 비의 원근). 판별 게이트가 기각한 사이클은 자기 창을 소비한다([onRejected]) — 무릎 들기 구간이 다음 스쿼트의 바닥으로 읽히지 않게.
 *
 * 정확(correct) = ship 검사 위반이 없는 반복. beta 는 정확을 깎지 못하고(원칙 #2), 판정 못 한 검사(ABSTAIN)는 위반이 아니다(원칙 #1).
 * 목표 진행은 정확한 회로 간다(사용자 결정 2026-09-25) — 반복 수 자체는 줄지 않는다.
 */
enum class RepPhase { START, TOP, BOTTOM, CYCLE,
    /** 반복 앞뒤로 서 있던 프레임(하강 직전 상단 + 복귀 뒤 서 있음) — 발 너비·발끝처럼 서 있을 때만 정확하고, 반복 중에 바뀌면 복귀 뒤에 드러나는 것. */
    STANDING,
    /** 바닥(사이클 최소)부터 끝까지 — 올라오는 구간. 엉덩이가 먼저 올라오는(hip rise) 시간적 오류의 자리. */
    ASCENT }

/** 기준 — NONE: 절대값(모집단 띠), START_RATIO: 시작 자세 대비 비율, START_DELTA: 시작 자세 대비 차. */
/**
 * 기준: NONE = 원값, START_RATIO/START_DELTA = 세트 시작 자세(첫 상단 창) 대비, REP_DELTA = **그 반복의** 상단 창(하강 직전) 중앙값 대비,
 * SET_MIN_DELTA = **세트에서 지금까지 가장 작은** 상단 창 중앙값(이 반복 포함) 대비 — 값이 클수록 나쁜 피처(몸통 기울기)용.
 * SET_MIN_DELTA 는 두 실패를 함께 피한다(§62c 후속 6): 첫 상단 창이 덤벨 집기에 오염돼도 다음 반복의 곧은 상단이 기준을 끌어내리고(START_DELTA 의 실패),
 * 숙인 채 반복해도 그 반복 시작이 아니라 세트에서 가장 곧았던 자세와 견준다(REP_DELTA 의 실패 — 11:14 세트 숙인 반복 3·5·12~14회가 −2~+3° 로 읽혀 전부 통과).
 * REP_DELTA 는 준비 동작에 오염되지 않는다 — 컬 세트의 첫 상단 창에는 덤벨을 집으려 숙인 프레임이 들어가 몸통 기울기 기준이 30~45° 가 됐고
 * (폰 15:33·09:59 세트), 그 뒤 모든 회가 −20~−47° 로 읽혀 '허리 굽힘' 이 영영 못 걸렸다(§62c 후속 4).
 */
enum class RepFormRef { NONE, START_RATIO, START_DELTA, REP_DELTA, SET_MIN_DELTA }

enum class RepFormStat { MEDIAN, MEAN, MAX, MIN,
    /** 시작 기준에서 **가장 멀리 벗어난** 프레임 값(비율은 1, 차는 0 에서) — 상대 기준 검사 전용. 앞뒤 어느 쪽에서 벗어났든 잡는다. */
    EXTREME,
    /** 구간 최대 − 구간 첫 값. ASCENT 에 쓰면 "바닥에서 올라오며 얼마나 더 커졌나"(상체가 바닥보다 더 숙여지면 엉덩이가 먼저 올라온 것). */
    RISE }

/** 위반 방향 — 허용 띠의 아래(LOW)·위(HIGH). */
enum class FormDirection { LOW, HIGH }

/**
 * 검사 하나. 허용 띠 [lo, hi] 밖이면 위반(한쪽만 있어도 된다). 문장은 방향별 관찰문 + 교정문 하나.
 * @property unit 값의 단위 표기("°", "" = 정규화 비율). 비율 기준(START_RATIO)은 "×1.49" 로 쓴다.
 * @property lowLabel/highLabel 리포트 요약의 짧은 꼬리표("넓음"·"바깥").
 */
data class RepFormCheck(
    val id: String,
    val exercise: String,
    val condition: String,
    val bodyPart: String,
    val status: RuleStatus,
    val feature: String,
    val phase: RepPhase,
    val stat: RepFormStat,
    val ref: RepFormRef,
    val lo: Float?,
    val hi: Float?,
    val lowText: String?,
    val highText: String?,
    val lowLabel: String?,
    val highLabel: String?,
    val fix: String,
    val reason: String,
    val unit: String = "",
    val cautions: List<String> = emptyList(),
    /**
     * 같은 반복에서 위반이면 이 검사의 **원인**으로 보는 검사 id — 발화 문장이 원인을 먼저 말한다(§21.5 실측: 발끝을 안으로 모으면
     * 무릎이 따라 들어와 `knee_out` 이 떨어진다. "무릎" 만 말하면 사용자가 바꾼 것(발)을 못 짚는다). 판정·정확 계산에는 영향 없다.
     */
    val causes: List<String> = emptyList(),
    /**
     * 같은 반복에서 위반이면 이 검사의 **측정 자체가 무효**가 되는 검사 id — 결과는 유보(§21.8 실측: 발끝을 안으로 돌리면 앞발을 축으로
     * 뒤꿈치·발목이 벌어져 발목 간격이 ×1.6~1.8 로 읽힌다. 발을 디딘 자리는 그대로인데 "넓어졌어요" 는 방향이 틀린 말이다).
     */
    val invalidatedBy: List<String> = emptyList(),
    /**
     * 창의 프레임 수 상한 — TOP 이면 하강 직전 마지막 N 프레임만(§21.12: 발끝은 0.6~0.9 s = 300 ms 에서 2~3 프레임이 적정,
     * 복귀 직후 프레임은 과도 상태라 넣지 않는다). null = 위상 창 전체.
     */
    val windowFrames: Int? = null,
    /**
     * HIGH 위반의 추가 조건(AND) — 창 통계 원값 ÷ 시작 자세의 [absRefFeature] 가 [absMin] 이상일 때만 위반. 발 너비: 시작 대비 ×1.4(바뀌었다)
     * **이고** 시작 어깨 대비 1.5(어깨 너비를 넘었다). 상대만 쓰면 시작 기준이 어긋난 세트(16:13)가 통째로 위반이 되고, 절대만 쓰면 카메라 높이에
     * 따라 오탐 6~16 %(A7b). 시작 자세에 그 피처가 없으면 조건을 못 재므로 유보.
     */
    val absRefFeature: String? = null,
    val absMin: Float? = null,
    /**
     * 2단 검사(spec §62c 팔꿈치 앞 이탈): 위반(코칭 문장·화면)은 [hi] 로, **횟수 차단**은 더 엄한 [gateHi](값) 와 [gateAbsMin](원값) 을 둘 다 넘을 때만.
     * null 이면 위반 = 차단(스쿼트 검사). 이탈 신호는 오탐 5 % 운영점에서 검출 0.8 이라 한 임계로 코칭과 차단을 다 만족시킬 수 없다(B1).
     */
    val gateHi: Float? = null,
    val gateAbsMin: Float? = null,
    /** 이 검사가 전제하는 촬영 뷰 등급(`PostureView`). 스쿼트는 정면 C, 컬의 앞 이탈·몸통 기울기는 사선 B/D(정면에서는 깊이 축이라 못 본다). */
    val views: Set<String> = setOf("C"),
    /**
     * 반복 없이도 말하는 '유지 자세' 판정(§62c 후속 6) — 마지막 사이클 뒤 최근 이만큼의 프레임이 모두 쉬는 자세(카운트 신호가 서 있음 띠 안 = 팔을 내림)이고
     * 그 중앙값이 기준 대비 띠를 넘으면 [RepFormEvaluator.liveEvent] 가 사건을 낸다. 스쿼트의 창 규칙 '척추의 중립' 이 반복과 무관하게 말하는 것과 같은 역할 —
     * 숙인 채 멈춰 있으면 반복이 끝나지 않아 반복 판정이 영영 오지 않는다(11:14 세트 58~73 s, 16초). null = 반복 끝에서만 판정.
     */
    val liveHoldFrames: Int? = null,
    /** 유지 자세 사건의 문장(지금 상태를 말한다) — null 이면 [highText]. */
    val liveText: String? = null,
) {
    init { require(lo != null || hi != null) { "$id: 허용 띠가 없다" } }

    val ship: Boolean get() = status == RuleStatus.SHIP

    fun judge(value: Float): FormDirection? = when {
        lo != null && value < lo -> FormDirection.LOW
        hi != null && value > hi -> FormDirection.HIGH
        else -> null
    }

    fun text(direction: FormDirection): String =
        (if (direction == FormDirection.LOW) lowText else highText) ?: "$condition 조건을 벗어났어요"

    fun label(direction: FormDirection): String =
        (if (direction == FormDirection.LOW) lowLabel else highLabel) ?: (if (direction == FormDirection.LOW) "낮음" else "높음")

    /** 판정값 표기 — 비율 "×1.49", 차 "+18°", 절대 "0.02". */
    fun format(value: Float): String = when (ref) {
        RepFormRef.START_RATIO -> String.format(java.util.Locale.US, "×%.2f", value)
        RepFormRef.START_DELTA, RepFormRef.REP_DELTA, RepFormRef.SET_MIN_DELTA -> String.format(java.util.Locale.US, "%+.0f%s", value, unit)
        RepFormRef.NONE -> if (unit == "°") String.format(java.util.Locale.US, "%.0f°", value) else String.format(java.util.Locale.US, "%.2f", value)
    }
}

/**
 * 한 반복에서 검사 하나의 결과.
 * @property value 판정에 쓴 값(상대 기준이면 비율·차), @property raw 창 통계 원값, @property reference 시작 자세 값(상대 기준일 때).
 */
data class RepFormOutcome(
    val check: RepFormCheck,
    val verdict: Verdict,
    val value: Float?,
    val raw: Float?,
    val reference: Float?,
    val direction: FormDirection?,
    val samples: Int,
    val abstainReason: String? = null,
    /** 위반이 **횟수 차단** 단계인가(§62c 2단 검사). 1단 검사에서는 위반 = 차단. OK·유보는 false. */
    val gate: Boolean = false,
)

data class RepFormRep(
    val index: Int,
    val tMs: Long,
    val outcomes: List<RepFormOutcome>,
    /** 이 반복과 직전 반복에서 연속으로 위반한 ship 검사 id — 음성은 이것에만 붙는다(한 번 튐은 화면만, 원칙 #6). 평가기가 사이클마다 채운다. */
    val consecutiveShip: Set<String> = emptySet(),
    /** 이 반복 창의 촬영 뷰 글자(`ViewEstimator`) — 방향 피처가 부족하거나 흩어져 모르면 null(그 반복은 뷰로 거르지 않았다). */
    val view: String? = null,
) {
    val flagged: List<RepFormOutcome> get() = outcomes.filter { it.verdict == Verdict.VIOLATION }

    /** ship 검사의 차단 단계 위반이 없다. ABSTAIN 은 위반이 아니고(원칙 #1), beta 는 정확을 깎지 못하며(원칙 #2), 2단 검사의 코칭 단계 위반도 깎지 않는다(§62c). */
    val correct: Boolean get() = flagged.none { it.check.ship && it.gate }
}

/** 유지 자세 사건 하나(§62c 후속 6) — 반복 밖에서 말했다. [value] = 기준 대비 값. */
data class RepFormLiveMark(val tMs: Long, val id: String, val value: Float)

/** 반복 하나에서 말할(또는 화면에 남길) 사건 하나 — 우선순위 = 검사 순서. ship 만 음성. [gated] = 이 위반으로 회가 빠졌다(COACH). */
data class RepFormEvent(val check: RepFormCheck, val direction: FormDirection, val message: String, val ship: Boolean, val gated: Boolean = ship)

class RepFormEvaluator(
    val checks: List<RepFormCheck>,
    private val signalFeature: String,
    private val minAmp: Float,
    private val cooldownMs: Long = 12_000L,
) {
    private var buf = ArrayList<Pair<Long, Map<String, Float>>>()
    /**
     * [RepFormRef.SET_MIN_DELTA] 기준 — 피처별로 지금까지 반복 상단 창 중앙값의 최솟값. 반복마다 그 반복의 상단으로 갱신한 뒤 판정한다.
     */
    private val setMin = HashMap<String, Float>()
    /** 유지 자세 사건 기록(로그 `rep_form.live`) — (시각, 검사 id, 기준 대비 값). */
    private val liveList = ArrayList<RepFormLiveMark>()
    /** 직전 사이클 창의 끝에서 서 있던 프레임(≤ TOP_FRAMES) — 다음 반복의 '하강 직전 상단' 에 이월한다(§21.5: 쉬지 않고 이어 하면 상단이 1프레임뿐). */
    private var carry: List<Pair<Long, Map<String, Float>>> = emptyList()
    private val repList = ArrayList<RepFormRep>()
    private val startList = ArrayList<RepFormOutcome>()
    private val lastSpokenAt = HashMap<String, Long>()
    private var rejectedCount = 0
    private var noTopCount = 0

    /** 시작 자세 — 첫 상단 창의 피처별 중앙값. 잡히기 전엔 null(상대 기준 검사는 유보). */
    var baseline: Map<String, Float>? = null
        private set
    var baselineAtMs: Long? = null
        private set
    /**
     * 시작 발 너비 기준을 첫 반복의 바닥에서 잡았다(§21.12) — 첫 상단 창 안에서 발목 간격이 10 % 넘게 움직였을 때(발을 옮기고 바로 앉음, 16:13 세트:
     * 60 → 97 px 로 21회 전부 '넓음'). 사용자에게 "첫 반복을 기준으로 잡았어요" 라고 밝힌다.
     */
    var baselineFromFirstBottom: Boolean = false
        private set

    val reps: List<RepFormRep> get() = repList
    val startOutcomes: List<RepFormOutcome> get() = startList

    fun reset() {
        buf.clear(); carry = emptyList(); repList.clear(); startList.clear(); lastSpokenAt.clear(); setMin.clear(); liveList.clear()
        baseline = null; baselineAtMs = null; baselineFromFirstBottom = false; rejectedCount = 0; noTopCount = 0
    }

    /** 검출된 프레임마다(카운터 onFrame **앞에서**). 피처 없는 프레임은 창에 들어가지 않는다. */
    fun onFrame(tMs: Long, features: Map<String, Float>) {
        if (features.isEmpty()) return
        buf += tMs to features
        if (buf.size > CAP) buf.subList(0, CAP / 2).clear()
    }

    /** 판별 게이트가 기각한 사이클 — 그 창의 프레임을 버린다(평가하지 않는다). */
    fun onRejected(endMs: Long) {
        buf.removeAll { it.first <= endMs }
        carry = emptyList()   // 기각 창의 끝은 서 있던 프레임인지 모른다(극값을 받지 않는다) — 이월하지 않는다
        rejectedCount++
    }

    /**
     * 카운터가 센 사이클 하나(끝 [endMs], 극값 [cycleMin]/[cycleMax]). 창 = 직전 사이클 끝 이후 ~ endMs.
     * 첫 상단 창이 잡히면 시작 자세를 세우고 START 검사를 한 번 한다.
     */
    fun onCycle(endMs: Long, cycleMin: Float, cycleMax: Float): RepFormRep {
        val window = buf.filter { it.first <= endMs }
        buf.removeAll { it.first <= endMs }
        // 서 있음 판정(§21.10): 시작 자세가 있으면 그 무릎각 − 0.3h(≈ 10°) — 사이클 최대 − 0.22h 는 반복 뒤 서 있는 프레임(최대보다 몇 도 낮다)을 놓쳐
        // 발끝 검사 창이 앞쪽(옛 자세)에 치우쳤다(12:19 세트 3·6회). 첫 반복은 시작 자세가 아직 없어 사이클 최대 기준.
        val standingLevel = baseline?.get(signalFeature)?.let { it - STANDING_BAND_REF * minAmp } ?: (cycleMax - STANDING_BAND * minAmp)
        val phases = segment(carry, window, cycleMin, cycleMax, standingLevel)
        carry = phases.trailingStanding
        if (phases.top.isEmpty()) noTopCount++
        if (baseline == null && phases.top.size >= 2) {
            val b = HashMap(medians(phases.top))
            // 시작 발 너비 안정성(§21.12): 상단 창 안에서 발목 간격이 10 % 넘게 움직였으면 발을 옮기던 중 — 그 중앙값은 옛 자세다.
            // 이 반복의 바닥 발목 간격을 기준으로 삼는다(첫 반복은 비율 1 → 정상). 안정 규칙은 발 너비에만 — 발끝은 상단 그대로.
            val seps = phases.top.mapNotNull { it.second[Stance2d.ANKLE_SEP] }
            if (seps.size >= 2 && seps.min() > 1e-6f && seps.max() / seps.min() > START_STABLE_RATIO) {
                val bottomSeps = phases.bottom.mapNotNull { it.second[Stance2d.ANKLE_SEP] }
                if (bottomSeps.isNotEmpty()) { b[Stance2d.ANKLE_SEP] = stat(bottomSeps, RepFormStat.MEDIAN); baselineFromFirstBottom = true }
                else b.remove(Stance2d.ANKLE_SEP)
            }
            baseline = b
            baselineAtMs = phases.top.last().first
            for (c in checks) if (c.phase == RepPhase.START) startList += evaluateStart(c)
        }
        // 세트 최소 기준(SET_MIN_DELTA) — 이 반복의 상단 중앙값으로 먼저 갱신한다(이 반복이 세트에서 가장 곧으면 자기 자신이 기준)
        for (c in checks) if (c.ref == RepFormRef.SET_MIN_DELTA) {
            val vs = phases.top.mapNotNull { it.second[c.feature] }
            if (vs.isNotEmpty()) { val m = stat(vs, RepFormStat.MEDIAN); setMin[c.feature] = setMin[c.feature]?.let { minOf(it, m) } ?: m }
        }
        val raw = checks.filter { it.phase != RepPhase.START }.map { evaluate(it, phases) }
        // 이 반복의 촬영 뷰 — 반복 창(상단 + 사이클) 프레임의 방향 피처 원형 평균(§62c 후속 6). 세트 누적 뷰는 옆으로 돌아선 구간 하나에 끌려가
        // 그 뒤 정면 반복까지 유보시켰다(11:14 세트: 누적 19.8° = B → 15~17회 정면 검사 전부 유보). 모르면(프레임 부족·흩어짐) 거르지 않는다
        val repView = ViewEstimator.estimate((phases.top + phases.cycle).map { it.second }, REP_VIEW_MIN_FRAMES)
            ?.takeIf { it.cls != ViewEstimator.ViewClass.UNKNOWN }?.letter
        // 측정을 무효로 만드는 검사가 같은 반복에서 위반이면 유보 — 틀린 방향의 말보다 침묵이 낫다(원칙 #6)
        val violated = raw.filter { it.verdict == Verdict.VIOLATION }.map { it.check.id }.toSet()
        val outcomes = raw.map { o ->
            val by = o.check.invalidatedBy.firstOrNull { it in violated }
            if (by == null || o.verdict == Verdict.ABSTAIN) o
            else o.copy(verdict = Verdict.ABSTAIN, direction = null, abstainReason = "${checks.first { it.id == by }.bodyPart} 위반으로 측정 무효")
        }.map { o ->
            if (repView == null || repView in o.check.views || o.verdict == Verdict.ABSTAIN) o
            else o.copy(verdict = Verdict.ABSTAIN, direction = null, gate = false, abstainReason = "촬영 방향 · $repView")
        }
        val prev = repList.lastOrNull()
        val consecutive = outcomes.filter { o -> o.check.ship && o.verdict == Verdict.VIOLATION && prev?.flagged?.any { it.check.id == o.check.id } == true }
            .map { it.check.id }.toSet()
        val rep = RepFormRep(repList.size + 1, endMs, outcomes, consecutive, repView)
        repList += rep
        return rep
    }

    /**
     * 이 반복에서 말할 사건. ship 은 **같은 검사가 2회 연속** 위반이고 쿨다운이 지났을 때만(한 번 튐은 화면만) — 음성은 즉시 몸을 바꾸는 채널이다(원칙 #6).
     * beta 는 화면 '참고' 용이라 반복마다 돌려준다(음성 아님). 우선순위 = 검사 순서.
     * [gate] = 이 회가 횟수에서 빠지는 모드(COACH, §62b) — 첫 위반부터 말한다(쿨다운은 그대로). 빠진 회를 침묵하면 사용자는 카운트가 죽은 줄 안다.
     */
    fun eventFor(rep: RepFormRep, nowMs: Long, gate: Boolean = false): RepFormEvent? {
        val flagged = rep.flagged
        for (o in flagged) {
            val d = o.direction ?: continue
            val c = o.check
            if (c.ship) {
                if (!gate && c.id !in rep.consecutiveShip) continue
                val last = lastSpokenAt[c.id]
                if (last != null && nowMs - last < cooldownMs) continue
                lastSpokenAt[c.id] = nowMs
                // 같은 반복에서 원인 검사가 위반이면 원인을 먼저 말한다 — 사용자가 바꾼 것을 짚어야 교정이 된다
                val cause = c.causes.firstNotNullOfOrNull { id -> flagged.firstOrNull { it.check.id == id && it.direction != null } }
                val msg = if (cause == null) "${c.text(d)}. ${c.fix}."
                    else "${cause.check.text(cause.direction!!)} — ${c.bodyPart}이 따라 움직였어요. ${cause.check.fix}."
                return RepFormEvent(c, d, msg, ship = true, gated = o.gate)
            }
            return RepFormEvent(c, d, c.text(d), ship = false, gated = false)
        }
        return null
    }

    /**
     * 반복과 무관한 유지 자세 사건(§62c 후속 6) — [RepFormCheck.liveHoldFrames] 가 있는 ship 검사만. 마지막 사이클 뒤 최근 N 프레임이 모두 쉬는 자세(팔을 내림)이고
     * 그 중앙값 − 기준이 띠를 넘으면 사건을 낸다. 팔을 굽히는 중이면 내지 않는다 — 그 회는 반복 끝 판정이 말한다(한 동작에 한 번).
     * 그 프레임들의 뷰가 검사의 뷰 밖이면 내지 않는다. 쿨다운: 유지 사건끼리 [LIVE_COOLDOWN_MS], 같은 검사의 반복 사건 직후 [LIVE_AFTER_REP_MS].
     * 기준(세트 최소·시작 자세)은 첫 반복에서 생기므로 첫 반복 전에는 말하지 않는다 — 덤벨을 집으려 숙인 자세를 지적하지 않는다.
     */
    fun liveEvent(nowMs: Long): RepFormEvent? {
        val standing = baseline?.get(signalFeature)?.let { it - STANDING_BAND_REF * minAmp } ?: return null
        for (c in checks) {
            val n = c.liveHoldFrames ?: continue
            if (!c.ship || c.hi == null) continue
            val ref = when (c.ref) {
                RepFormRef.SET_MIN_DELTA -> setMin[c.feature]
                RepFormRef.START_DELTA -> baseline?.get(c.feature)
                else -> null
            } ?: continue
            val recent = buf.filter { it.second.containsKey(c.feature) && it.second.containsKey(signalFeature) }.takeLast(n)
            if (recent.size < n) continue
            if (recent.last().first - recent.first().first > n * LIVE_FRAME_GAP_MS) continue      // 끊긴 프레임을 한 자세로 잇지 않는다
            if (recent.any { it.second.getValue(signalFeature) < standing }) continue              // 팔을 굽히는 중
            val view = ViewEstimator.estimate(recent.map { it.second }, REP_VIEW_MIN_FRAMES)?.takeIf { it.cls != ViewEstimator.ViewClass.UNKNOWN }?.letter
            if (view != null && view !in c.views) continue
            val value = stat(recent.map { it.second.getValue(c.feature) }, RepFormStat.MEDIAN) - ref
            if (c.judge(value) != FormDirection.HIGH) continue
            if (value > LIVE_MAX_DELTA) continue      // 팔을 내린 채 60° 넘게 숙임 = 덤벨을 집거나 내려놓는 중 — 컬 자세 지적이 아니다
            val liveKey = "${c.id}#live"
            if (lastSpokenAt[liveKey]?.let { nowMs - it < LIVE_COOLDOWN_MS } == true) continue
            if (lastSpokenAt[c.id]?.let { nowMs - it < LIVE_AFTER_REP_MS } == true) continue
            lastSpokenAt[liveKey] = nowMs
            liveList += RepFormLiveMark(nowMs, c.id, value)
            return RepFormEvent(c, FormDirection.HIGH, "${c.liveText ?: c.text(FormDirection.HIGH)}. ${c.fix}.", ship = true, gated = false)
        }
        return null
    }

    fun summary(): RepFormSummary =
        RepFormSummary(checks, baseline, baselineAtMs, startList.toList(), repList.toList(), rejectedCount, noTopCount, baselineFromFirstBottom, liveList.toList())

    private class Phases(
        val top: List<Pair<Long, Map<String, Float>>>,
        val bottom: List<Pair<Long, Map<String, Float>>>,
        val cycle: List<Pair<Long, Map<String, Float>>>,
        /** 이 창의 끝에서 서 있던 프레임 — 다음 반복의 상단 창에 이월. */
        val trailingStanding: List<Pair<Long, Map<String, Float>>>,
        /** 바닥부터 끝까지(올라오는 구간). */
        val ascent: List<Pair<Long, Map<String, Float>>>,
    )

    /**
     * 창을 위상으로 나눈다. 상단 = 사이클 최소값 앞에서 마지막으로 서 있던 프레임(신호 ≥ 최대 − 0.22h)부터 거꾸로 ≤ 5개 —
     * 직전 창의 끝(복귀해 서 있던 프레임, [prefix])까지 거슬러 본다. 준비 동작이 길어도 하강 직전만 본다.
     * 바닥 = 최소 + 진폭/3 아래(이 창만). 사이클 = 상단 끝 다음부터 끝까지(이 창만).
     */
    private fun segment(prefix: List<Pair<Long, Map<String, Float>>>, window: List<Pair<Long, Map<String, Float>>>, cycleMin: Float, cycleMax: Float, standing: Float): Phases {
        val pre = prefix.filter { it.second.containsKey(signalFeature) }
        val own = window.filter { it.second.containsKey(signalFeature) }
        if (own.isEmpty()) return Phases(emptyList(), emptyList(), window, emptyList(), emptyList())
        val sig = pre + own
        val v = sig.map { it.second.getValue(signalFeature) }
        val n0 = pre.size
        var idxMin = n0
        for (i in n0 until v.size) if (v[i] < v[idxMin]) idxMin = i
        var topEnd = -1
        for (i in idxMin downTo 0) if (v[i] >= standing) { topEnd = i; break }
        val topAll = if (topEnd < 0) emptyList() else (maxOf(0, topEnd - TOP_FRAMES + 1)..topEnd).filter { v[it] >= standing }
        // 이 창 자체에 서 있던 프레임이 2개 이상이면 이월분은 버린다 — 반복 사이에 발을 옮겼으면 이월분은 옛 자세라 "돌아온 첫 반복" 을 헛경보로 만든다
        val topOwn = topAll.filter { it >= n0 }
        val top = (if (topOwn.size >= 2) topOwn else topAll).map { sig[it] }
        val bottomLevel = cycleMin + (cycleMax - cycleMin) / 3f
        val bottom = (n0 until v.size).filter { v[it] <= bottomLevel }.map { sig[it] }
        val cycle = sig.subList(maxOf(topEnd + 1, n0), sig.size)
        var tailStart = v.size
        while (tailStart > n0 && v[tailStart - 1] >= standing && v.size - tailStart < TOP_FRAMES) tailStart--
        return Phases(top, bottom, cycle, sig.subList(tailStart, v.size), sig.subList(idxMin, sig.size))
    }

    private fun medians(frames: List<Pair<Long, Map<String, Float>>>): Map<String, Float> {
        val keys = frames.flatMap { it.second.keys }.toSet()
        val out = HashMap<String, Float>()
        for (k in keys) {
            val vs = frames.mapNotNull { it.second[k] }
            if (vs.size >= 2) out[k] = stat(vs, RepFormStat.MEDIAN)
        }
        return out
    }

    private fun evaluateStart(c: RepFormCheck): RepFormOutcome {
        val v = baseline?.get(c.feature) ?: return RepFormOutcome(c, Verdict.ABSTAIN, null, null, null, null, 0, "시작 자세에 ${c.feature} 없음")
        val d = c.judge(v)
        return RepFormOutcome(c, if (d == null) Verdict.OK else Verdict.VIOLATION, v, v, null, d, 1, gate = d != null)
    }

    private fun evaluate(c: RepFormCheck, p: Phases): RepFormOutcome {
        val all = when (c.phase) {
            RepPhase.TOP -> p.top; RepPhase.BOTTOM -> p.bottom; RepPhase.CYCLE -> p.cycle; RepPhase.START -> emptyList()
            RepPhase.STANDING -> p.top + p.trailingStanding
            RepPhase.ASCENT -> p.ascent
        }
        val frames = c.windowFrames?.let { n -> if (all.size > n) all.subList(all.size - n, all.size) else all } ?: all
        val values = frames.mapNotNull { it.second[c.feature] }
        val need = if (c.stat == RepFormStat.MAX || c.stat == RepFormStat.MIN || c.stat == RepFormStat.EXTREME) 1 else 2
        if (values.size < need) return RepFormOutcome(c, Verdict.ABSTAIN, null, null, null, null, values.size,
            if ((c.phase == RepPhase.TOP || c.phase == RepPhase.STANDING) && frames.isEmpty()) "서 있는 프레임 없음" else "창에 ${c.feature} 부족")
        val ref = when (c.ref) {
            RepFormRef.NONE -> null
            RepFormRef.REP_DELTA -> p.top.mapNotNull { it.second[c.feature] }.takeIf { it.isNotEmpty() }?.let { stat(it, RepFormStat.MEDIAN) }
            RepFormRef.SET_MIN_DELTA -> setMin[c.feature]
            else -> baseline?.get(c.feature)
        }
        if (c.stat == RepFormStat.EXTREME) {
            // 상대 기준에서 가장 멀리 벗어난 프레임 — 반복 중에 발을 옮기면 복귀 뒤 서 있는 프레임에서 드러난다(11:37 세트 3회)
            if (ref == null || (c.ref == RepFormRef.START_RATIO && abs(ref) < 1e-6f))
                return RepFormOutcome(c, Verdict.ABSTAIN, null, null, ref, null, values.size, "시작 자세 기준 없음")
            val rels = values.map { if (c.ref == RepFormRef.START_RATIO) it / ref else it - ref }
            val neutral = if (c.ref == RepFormRef.START_RATIO) 1f else 0f
            var best = 0
            for (i in rels.indices) if (abs(rels[i] - neutral) > abs(rels[best] - neutral)) best = i
            val d = c.judge(rels[best])
            return RepFormOutcome(c, if (d == null) Verdict.OK else Verdict.VIOLATION, rels[best], values[best], ref, d, values.size, gate = d != null)
        }
        val raw = stat(values, c.stat)
        val value = when (c.ref) {
            RepFormRef.NONE -> raw
            RepFormRef.START_RATIO -> if (ref == null || abs(ref) < 1e-6f) return RepFormOutcome(c, Verdict.ABSTAIN, null, raw, ref, null, values.size, "시작 자세 기준 없음") else raw / ref
            RepFormRef.START_DELTA -> if (ref == null) return RepFormOutcome(c, Verdict.ABSTAIN, null, raw, null, null, values.size, "시작 자세 기준 없음") else raw - ref
            RepFormRef.REP_DELTA -> if (ref == null) return RepFormOutcome(c, Verdict.ABSTAIN, null, raw, null, null, values.size, "이 반복의 시작 자세 없음") else raw - ref
            RepFormRef.SET_MIN_DELTA -> if (ref == null) return RepFormOutcome(c, Verdict.ABSTAIN, null, raw, null, null, values.size, "세트 기준 자세 없음") else raw - ref
        }
        var d = c.judge(value)
        var absRatio: Float? = null
        if (d == FormDirection.HIGH && c.absMin != null) {
            // 절대 척도로 한 번 더 — 상대 변화만으로는 '스타일이 바뀜' 과 '어깨 너비를 넘음' 을 못 가른다. absRefFeature 가 있으면 시작 자세의 그 값으로
            // 나눈 비, 없으면 원값 자체가 절대 척도(컬의 2D 이탈 비는 이미 몸통 길이로 정규화돼 있다)
            if (c.absRefFeature != null) {
                val absRef = baseline?.get(c.absRefFeature)
                if (absRef == null || abs(absRef) < 1e-6f) return RepFormOutcome(c, Verdict.ABSTAIN, value, raw, ref, null, values.size, "시작 자세에 ${c.absRefFeature} 없음")
                absRatio = raw / absRef
            } else absRatio = raw
            if (absRatio < c.absMin) d = null
        }
        // 2단 검사: 차단은 값·원값 모두 더 엄한 임계를 넘을 때만(HIGH). 1단(gateHi 없음)은 위반 = 차단
        val gate = d != null && (c.gateHi == null || (d == FormDirection.HIGH && value >= c.gateHi && (c.gateAbsMin == null || (absRatio ?: raw) >= c.gateAbsMin)))
        return RepFormOutcome(c, if (d == null) Verdict.OK else Verdict.VIOLATION, value, raw, ref, d, values.size, gate = gate)
    }

    companion object {
        /** 서 있음 판정 띠(시작 자세 전) — `ReturnRepTracker` 의 복귀 띠(0.22h)와 같은 비율. */
        const val STANDING_BAND = 0.22f
        /** 서 있음 판정 띠(시작 자세 뒤) — 시작 무릎각 − 0.3h(35° 면 10.5°). 반복 뒤 서 있는 프레임이 최대보다 몇 도 낮아도 잡는다(§21.10). */
        const val STANDING_BAND_REF = 0.3f
        /** 상단 창 길이(프레임) — 300 ms 샘플링 ≈ 1.5 s. */
        const val TOP_FRAMES = 5
        /** 시작 상단 창 안 발목 간격 최대÷최소가 이보다 크면 '발을 옮기던 중' — 발 너비 기준을 첫 반복 바닥에서 잡는다(§21.12). */
        const val START_STABLE_RATIO = 1.10f
        /** 반복 창 뷰 추정의 최소 프레임 — 컬 한 회 창은 300 ms 에서 4~10프레임이라 세트용 8 보다 낮다. 흩어진 방향은 결과 벡터 길이(0.7)가 거른다. */
        const val REP_VIEW_MIN_FRAMES = 4
        /** 유지 자세 사건 사이 최소 간격(ms). */
        const val LIVE_COOLDOWN_MS = 15_000L
        /** 같은 검사의 반복 사건을 말한 직후 유지 사건을 쉬는 시간(ms) — 방금 말한 것을 곧바로 다시 말하지 않는다. */
        const val LIVE_AFTER_REP_MS = 5_000L
        /** 유지 창 프레임 사이 허용 간격(ms, 프레임당) — 300 ms 샘플링의 약 1.7배. 넘으면 끊긴 창. */
        const val LIVE_FRAME_GAP_MS = 500L
        /**
         * 유지 사건의 상한(기준 대비 °) — 넘으면 말하지 않는다. 팔을 내린 채 깊이 숙이는 것은 덤벨을 집거나 내려놓는 동작이다(세트 끝 MM-Fit 재생에서
         * 유지 사건이 세트 끝 정리 동작에 걸렸다). 월드 기울기가 80~110° 로 튀는 측정 붕괴(MM-Fit w16 — 중력 up 없음)도 여기서 걸러진다.
         */
        const val LIVE_MAX_DELTA = 60f
        private const val CAP = 2_000

        fun stat(values: List<Float>, stat: RepFormStat): Float = when (stat) {
            RepFormStat.MEAN -> values.average().toFloat()
            RepFormStat.MAX -> values.max()
            RepFormStat.MIN -> values.min()
            RepFormStat.MEDIAN -> values.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f }
            RepFormStat.EXTREME -> error("EXTREME 은 상대 기준 검사에서 evaluate 가 직접 고른다")
            RepFormStat.RISE -> values.max() - values.first()
        }
    }
}

/** 세트 마감 요약 — 리포트 문장·규칙 결과·로그의 원자재. */
data class RepFormSummary(
    val checks: List<RepFormCheck>,
    val baseline: Map<String, Float>?,
    val baselineAtMs: Long?,
    val start: List<RepFormOutcome>,
    val reps: List<RepFormRep>,
    val rejected: Int,
    val noTop: Int,
    val baselineFromFirstBottom: Boolean = false,
    /** 유지 자세 사건(§62c 후속 6) — 반복 밖에서 말한 것. */
    val live: List<RepFormLiveMark> = emptyList(),
) {
    val hasShip: Boolean get() = checks.any { it.ship && it.phase != RepPhase.START }
    val correct: Int get() = reps.count { it.correct }

    /** 검사 하나의 반복별 결과(반복 순서). */
    fun outcomesOf(check: RepFormCheck): List<RepFormOutcome> = reps.mapNotNull { r -> r.outcomes.firstOrNull { it.check.id == check.id } }

    /** 위반 목록의 방향별 요약 — "넓음 3회" 또는 섞이면 "바깥 2회 · 안쪽 2회". */
    fun directionCounts(check: RepFormCheck, bad: List<RepFormOutcome>): String =
        bad.groupingBy { it.direction ?: FormDirection.HIGH }.eachCount().entries
            .sortedByDescending { it.value }.joinToString(" · ") { (dir, k) -> "${check.label(dir)} ${k}회" }

    /** 완료 화면·기록의 요약 줄. 정확 수는 ship 검사가 있을 때만(없으면 '정확' 이라는 말을 쓰지 않는다). 시작 자세는 규칙 행에 있어 여기선 없을 때만 말한다. */
    fun lines(): List<String> = buildList {
        if (reps.isEmpty()) return@buildList
        if (baseline == null) add("시작 자세를 잡지 못해 시작 기준 검사(발 너비·발끝)는 못 했어요")
        else if (baselineFromFirstBottom) add("시작할 때 발을 옮기고 있어서 발 너비 기준은 첫 반복으로 잡았어요")
        // 검사별 위반 수는 규칙 행(ruleResult 의 measurement)에 있다 — 여기서는 행에 없는 것만: 시작 자세, 정확 수, 못 잰 반복
        if (hasShip) add("정확 $correct / ${reps.size}회")
        if (noTop > 0) add("하강 직전 상단을 못 잡은 반복 ${noTop}회 — 그 회는 발 너비·발끝 비교를 못 했어요")
    }

    /** 검사별 위반 요약 한 줄("발 너비 넓음 4회 · 발끝 바깥 1회") — HUD·재생 출력용. 위반이 없으면 null. */
    fun flagLine(): String? {
        val parts = checks.filter { it.phase != RepPhase.START }.mapNotNull { c ->
            val bad = outcomesOf(c).filter { it.verdict == Verdict.VIOLATION }
            if (bad.isEmpty()) null else "${c.bodyPart} ${directionCounts(c, bad)}"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun toLog(t0: Long): RepFormLog = RepFormLog(
        version = RepFormSpecs.VERSION,
        baselineTMs = baselineAtMs?.let { it - t0 },
        baseline = baseline?.filterKeys { k -> checks.any { it.feature == k || it.absRefFeature == k } }.orEmpty(),
        start = start.map { RepFormLog.Check(it.check.id, it.verdict.name, it.value, it.raw, it.reference, it.direction?.name, it.gate) },
        reps = reps.map { r -> RepFormLog.Rep(r.tMs - t0, r.correct, r.outcomes.map { RepFormLog.Check(it.check.id, it.verdict.name, it.value, it.raw, it.reference, it.direction?.name, it.gate) }, r.view) },
        rejected = rejected, noTop = noTop, baselineFromFirstBottom = baselineFromFirstBottom,
        live = live.map { it.copy(tMs = it.tMs - t0) },
    )
}

/** 세트 로그 `rep_form` 블록의 원시 형태(직렬화는 `SetLogJson.repForm`). 시각은 세트 상대 ms. */
data class RepFormLog(
    val version: String,
    val baselineTMs: Long?,
    val baseline: Map<String, Float>,
    val start: List<Check>,
    val reps: List<Rep>,
    val rejected: Int,
    val noTop: Int,
    /** 발 너비 기준을 첫 반복 바닥에서 잡았다(§62b) — 로그 키 `baseline_from_first_bottom`. */
    val baselineFromFirstBottom: Boolean = false,
    /** 유지 자세 사건 — 있을 때만 키 `live`. */
    val live: List<RepFormLiveMark> = emptyList(),
) {
    /** [gate] = 위반이 횟수 차단 단계(§62c 2단 검사). 위반이 아니면 false — JSON 에는 true 일 때만 `"gate":true` 를 적는다. */
    data class Check(val id: String, val verdict: String, val value: Float?, val raw: Float?, val reference: Float?, val direction: String?, val gate: Boolean = false)
    /** [view] = 그 반복 창의 뷰 글자 — 있을 때만 키 `view`. */
    data class Rep(val tMs: Long, val correct: Boolean, val checks: List<Check>, val view: String? = null)

    /**
     * `rep_form` 블록의 JSON — 세트 로그(`SetLogJson`)와 재생기가 같은 문자열을 낸다(재생기는 org.json 도 SetLogJson 도 컴파일하지 않는다).
     * 숫자 형식은 `SetLogJson.num`(고정 소수 5자리, 끝 0·점 제거, NaN → null) 과 같다.
     * `{"version","baseline_t_ms","baseline":{feature:value},"start":[check],"reps":[{"t_ms","correct","checks":[check]}],"rejected","no_top","baseline_from_first_bottom"}`,
     * check = `{"id","v","value","raw","ref","dir"}` (없는 값은 null).
     */
    fun toJson(): String {
        val sb = StringBuilder(256)
        fun check(c: Check) {
            sb.append("{\"id\":").append(str(c.id)).append(",\"v\":").append(str(c.verdict))
            sb.append(",\"value\":").append(num(c.value)).append(",\"raw\":").append(num(c.raw)).append(",\"ref\":").append(num(c.reference))
            sb.append(",\"dir\":").append(c.direction?.let(::str) ?: "null")
            if (c.gate) sb.append(",\"gate\":true")
            sb.append('}')
        }
        sb.append("{\"version\":").append(str(version))
        sb.append(",\"baseline_t_ms\":").append(baselineTMs?.toString() ?: "null")
        sb.append(",\"baseline\":{")
        baseline.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) -> if (i > 0) sb.append(','); sb.append(str(k)).append(':').append(num(v)) }
        sb.append("},\"start\":[")
        start.forEachIndexed { i, c -> if (i > 0) sb.append(','); check(c) }
        sb.append("],\"reps\":[")
        reps.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{\"t_ms\":").append(r.tMs).append(",\"correct\":").append(r.correct)
            r.view?.let { sb.append(",\"view\":").append(str(it)) }
            sb.append(",\"checks\":[")
            r.checks.forEachIndexed { j, c -> if (j > 0) sb.append(','); check(c) }
            sb.append("]}")
        }
        sb.append("],\"rejected\":").append(rejected).append(",\"no_top\":").append(noTop)
        sb.append(",\"baseline_from_first_bottom\":").append(baselineFromFirstBottom)
        if (live.isNotEmpty()) {
            sb.append(",\"live\":[")
            live.forEachIndexed { i, m -> if (i > 0) sb.append(','); sb.append("{\"t_ms\":").append(m.tMs).append(",\"id\":").append(str(m.id)).append(",\"value\":").append(num(m.value)).append('}') }
            sb.append(']')
        }
        sb.append('}')
        return sb.toString()
    }

    companion object {
        private fun num(v: Float?): String {
            if (v == null || v.isNaN() || v.isInfinite()) return "null"
            val s = String.format(java.util.Locale.US, "%.5f", v)
            return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }.let { if (it == "-0") "0" else it }
        }
        private fun str(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (ch in s) when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
            return sb.append('"').toString()
        }
    }
}

/**
 * 반복별 검사 등록부 — 코드가 정본이다(`RepSignals` 와 같은 자리). 규칙 JSON 이 아닌 이유: 재생기(replay-jvm)가 org.json 없이 같은 검사를
 * 돌려야 하고(Gate A 가 이 검사를 잰다), 상태(ship/beta)·문장·띠가 한 곳에 있어야 한다. 범위 문장·리포트 행은 [asRules] 가 규칙셋에 붙인다.
 *
 * 임계값의 출처(spec §62a·§62b): 잠정값은 2026-09-25 실기기 세트(한 사람)와 모집단 재생(REHAB·MM-Fit, `research/camera_observability/A7b`)에서
 * 골랐고 **확정이 아니다** — 지정 오류 세트 3명 이후 확정. ship 은 셋 — 무릎 안쪽 모임(바닥, AIHub 임계를 바닥 구간에), 발 간격(반복, 바닥 2D 발목
 * 간격 ÷ 시작 ×1.4 AND ÷ 시작 어깨 1.5: 정면 정상 반복 오탐 0/243·폰 넓힘 7/7), 발끝 방향(반복, 2D 발목→발끝 각 시작 대비 ±15°: 정면 오탐 0~4 %·
 * 폰 검출 5/6). ship 은 COACH 에서 그 회를 횟수에서 뺀다(사용자 결정 2026-09-25, `docs/SQUAT_FOOT_RULES_RESEARCH.md`).
 */
object RepFormSpecs {
    const val VERSION = "repform_v0.2"

    /**
     * 이 검사가 대체하는 창 규칙 id — 세션 규칙셋에서 beta 로 낮춘다(음성·점수·헤드라인에서 빠지고 리포트엔 '참고'로 남는다).
     * 덤벨 컬(§62c): 월드 `팔꿈치 위치 고정` 세트 평균은 정상 세트 오탐 16 %(B1), `척추의 중립` 두 규칙은 고개 각(head_pitch) 대리라 폰을 내려다보면
     * "처음부터 등이 말려" 를 반복한다(2026-09-26 실기기 4회) — 반복 검사(팔꿈치 뜸·옆 벌림·앞 이탈)가 그 자리를 맡는다.
     */
    val supersedes: Map<String, String> = mapOf(
        "바벨 스쿼트|발과 무릎의 방향 일치" to "repform|바벨 스쿼트|무릎 안쪽 모임",
        "덤벨 컬|팔꿈치 위치 고정" to "repform|덤벨 컬|팔꿈치 앞 이탈",
        "덤벨 컬|척추의 중립[all]" to "repform|덤벨 컬|상체 숙임",
        "덤벨 컬|척추의 중립[flexion]" to "repform|덤벨 컬|상체 숙임",
    )

    val byExercise: Map<String, List<RepFormCheck>> = mapOf("바벨 스쿼트" to squat(), "덤벨 컬" to curl())

    fun evaluatorFor(exercise: String, counter: RepCounter): RepFormEvaluator? =
        byExercise[exercise]?.let { RepFormEvaluator(it, counter.signal.feature, counter.signal.minAmp) }

    fun checkOf(ruleId: String): RepFormCheck? = byExercise.values.flatten().firstOrNull { it.id == ruleId }

    /** 이 종목의 반복 검사가 전제하는 뷰 등급의 합집합 — 세트 결과의 뷰 게이팅(`PostureLive`)용. 등록부에 없으면 정면. */
    fun viewsFor(exercise: String): Set<String> = byExercise[exercise]?.flatMap { it.views }?.toSet() ?: setOf("C")

    private fun curl(): List<RepFormCheck> {
        val ex = "덤벨 컬"
        val oblique = setOf("B", "D")
        val front = setOf("C")
        return listOf(
            // 우선순위 순 — 발화·화면 사건은 이 순서의 첫 위반. 창 분할 신호는 elbow_minside(더 굽은 팔): 바닥 = 수축, 상단 = 이완(서 있음)
            // 스쿼트 '상체 숙임' 과 같은 검사·같은 정책(사용자 결정 2026-09-26 "스쿼트와 똑같이") — 1단: 위반 = 음성 + COACH 에서 그 회를 세지 않음.
            // 다른 것은 임계와 기준뿐: 스쿼트는 원래 숙이는 동작이라 시작 대비 45°, 컬은 몸통이 거의 안 움직여 세트에서 가장 곧았던 상단 대비 20°
            RepFormCheck("repform|$ex|상체 숙임", ex, "상체 숙임(반복)", "상체", RuleStatus.SHIP, "torso_incl", RepPhase.CYCLE, RepFormStat.MAX, RepFormRef.SET_MIN_DELTA,
                lo = null, hi = LEAN_HI, lowText = null, highText = "상체가 많이 숙여졌어요", lowLabel = null, highLabel = "숙임",
                fix = "가슴을 들고 몸통을 세운 채 팔만 움직이세요", unit = "°", views = setOf("C", "D"),
                liveHoldFrames = 14, liveText = "상체가 숙여져 있어요",
                reason = "§62c 후속 6(사용자 \"숙이는 거 인식 못 해\"): 반복 창 몸통 기울기(목−골반 vs 중력 up) 최대 − **세트에서 가장 곧았던 상단 자세**(SET_MIN_DELTA). 그 반복 시작 대비(후속 4)는 숙인 채 반복하면 차이가 0 이라 11:14 세트 숙인 반복 3·5·12~14회(창 최대 36~45°)를 −2~+3° 로 전부 통과시켰고, 세트 시작 대비(스쿼트 방식)는 첫 상단이 덤벨 집기에 오염되면(09:59·15:33, 45°) 세트 전체가 음수로 읽힌다 — 가장 곧았던 상단은 두 실패를 함께 피한다. 연속 세트 재생: 폰 정상 75회 최대 +23.3°(+20° 초과 1회, 10:41 6회 — 숙임 여부 불명)·MM-Fit 123회 +20° 초과 1회(0.8 %); 11:14 정면 숙임 3·4·5회 +30·+23·+27 검출(2회 +18 은 숙이기 시작한 회). 반복 없이 팔을 내린 채 4초 숙여 있으면 유지 사건으로 말한다(스쿼트 창 규칙 '척추의 중립' 과 같은 역할 — 11:14 세트 58~73 s 는 숙인 채 16초 멈춰 반복 판정이 오지 않았다). 1단(스쿼트와 같은 정책): 반복 위반 = 음성 + COACH 횟수 제외",
                cautions = listOf("정면에서는 앞 숙임과 뒤 젖힘을 못 가른다(깊이 축) — 뒤로 젖혀도 '숙여졌어요' 로 말할 수 있다(스쿼트와 같은 한계)", "세트 첫 반복부터 계속 숙이고 있으면 기준 자체가 숙은 자세다(스쿼트와 같은 한계)", "AIHub 세션(32클립·수십 분)을 한 세트로 본 상한: 정면 +20° 초과 13.9 %(GT 3D 4.0 %) — 긴 시간의 자세 이동이 섞인 과대 추정이지만 지정 오류 세트에서 정상 오탐이 나오면 25° 로 올린다", "옆(SIDE)·B 뷰는 판정하지 않는다 — 옆으로 돌아서 숙인 반복·유지는 유보(반복마다 그 창의 뷰로)", "중력 up 기반 — up 오류 세트에서는 유보되지 않는다(up 방어 작업 대기)", "미세한 척추 말림은 못 본다")),
            // ---- 정면(C) 검사 (B4 — AIHub 정면 정상 170클립·MM-Fit 598회로 오탐, 폰 13회 정답 세트로 검출)
            RepFormCheck("repform|$ex|팔꿈치 뜸", ex, "팔꿈치 뜸(반복)", "팔꿈치", RuleStatus.SHIP, Arm2d.WRIST_H_MAX, RepPhase.CYCLE, RepFormStat.MAX, RepFormRef.NONE,
                lo = null, hi = 0.15f, lowText = null, highText = "들어 올릴 때 팔꿈치가 몸에서 떴어요", lowLabel = null, highLabel = "뜸",
                fix = "반동 없이 팔꿈치를 옆구리에 고정하고 팔만 접으세요", views = front,
                reason = "§62c(B4): 반동(어깨로 들어 올림)·으쓱·앞 내밀기가 정면에서는 모두 '손목이 어깨 위로 넘음' 으로 나타난다 — 사이클 창 손목 최고 높이(어깨 기준 ÷ 몸통) ≥ 0.15. 정상 반복 초과 MM-Fit 1.5 %(1명 습관)·AIHub 정상 1.8 %, 폰 반동 3/3(0.20~0.24)·정상 0/10. 반동 정점은 팔꿈치각 최소보다 1~2프레임 뒤라 수축 프레임 값이 아니라 창 최대",
                cautions = listOf("정면(C)에서만", "반동·으쓱·앞 내밀기를 못 가른다(내밀기 43 % 겹침) → 문구는 '팔꿈치가 몸에서 뜸' 으로 포괄", "임계는 스튜디오·MM-Fit·폰 1명 — 지정 오류 세트 3명 이후 확정")),
            RepFormCheck("repform|$ex|팔꿈치 옆 벌림", ex, "팔꿈치 옆 벌림(반복)", "팔꿈치", RuleStatus.SHIP, Arm2d.ELBOW_LAT_MAX, RepPhase.BOTTOM, RepFormStat.MEDIAN, RepFormRef.START_DELTA,
                lo = null, hi = 0.15f, lowText = null, highText = "팔꿈치가 옆으로 벌어졌어요", lowLabel = null, highLabel = "옆 벌림",
                fix = "팔꿈치를 옆구리에 붙이세요", absMin = 0.30f, gateHi = 0.20f, gateAbsMin = 0.35f, views = front,
                reason = "§62c(B4): 팔꿈치가 같은 쪽 어깨보다 바깥으로 나간 가로 거리 ÷ 어깨 폭, 수축 구간 중앙값(두 팔 중 큰 값). 정상 수축 p95 0.29·시작 대비 변화 p95 0.12~0.16, 폰 벌림 0.44~0.46(시작 대비 +0.33~0.35). 코칭(+0.15 이고 ≥ 0.30): 오탐 MM-Fit 0.7 %·AIHub 2.9 %, 차단(+0.20 이고 ≥ 0.35): 0.0 %·0.6 %. 폰 벌림 4/4·정상 0/9",
                cautions = listOf("정면(C)에서만 — 사선에서는 어깨 x 간격이 줄고 앞 성분이 섞여 부호·크기가 깨진다", "으쓱 연기와 14 % 겹침", "수축 중앙값이어야 한다 — 창 최대는 랜드마크 튐을 먹어 오탐 3~19 %")),
            RepFormCheck("repform|$ex|팔꿈치 높이 상승", ex, "팔꿈치 높이 상승(반복)", "팔꿈치", RuleStatus.BETA, Arm2d.ELBOW_RISE_MAX, RepPhase.CYCLE, RepFormStat.MAX, RepFormRef.START_DELTA,
                lo = null, hi = 0.20f, lowText = null, highText = "들어 올릴 때 팔꿈치가 어깨 쪽으로 올라왔어요", lowLabel = null, highLabel = "상승",
                fix = "팔꿈치 높이를 고정하세요", absMin = -0.30f, views = front,
                reason = "§62c(B4): 창 안 팔꿈치 최고 높이(어깨 기준 ÷ 몸통) − 시작 ≥ 0.20 이고 절대 ≥ −0.30. 정상 초과 MM-Fit 3.2 %(상완 스윙 습관 2명, 제외하면 1.1 %)·AIHub 2.4 %, 폰 반동 3/3. '팔꿈치 뜸' 의 보조 — 두 단서 AND 면 오탐 1.6~1.8 %",
                cautions = listOf(PROVISIONAL, "정면(C)에서만", "옆 벌림 회도 팔꿈치가 0.12~0.17 올라온다(상완이 투영에서 짧아짐) → 띠 0.20 이상")),
            // ---- 사선(B/D) 검사 (B1)
            RepFormCheck("repform|$ex|팔꿈치 앞 이탈", ex, "팔꿈치 앞 이탈(반복)", "팔꿈치", RuleStatus.SHIP, Arm2d.ELBOW_FWD_MEAN, RepPhase.BOTTOM, RepFormStat.MEDIAN, RepFormRef.START_DELTA,
                lo = null, hi = 0.07f, lowText = null, highText = "팔꿈치가 앞으로 나갔어요", lowLabel = null, highLabel = "앞으로",
                fix = "팔꿈치를 옆구리에 고정하세요", absMin = 0.09f, gateHi = 0.12f, gateAbsMin = 0.12f, views = oblique,
                reason = "§62c(B1, AIHub 1,354클립/뷰): '팔꿈치 위치 고정' 위반 = 팔꿈치 내밀기. 사선 2D 앞 성분(골반→어깨 선에서 팔꿈치의 앞쪽 거리 ÷ 몸통) 양팔 평균이 AUC 0.958~0.961(사람 주석 상한 0.963~0.966), 정상 중앙값 0.017·p95 0.089·위반 0.124. 코칭 단계(시작 대비 +0.07 이고 원값 ≥ 0.09)는 오탐 ≈ 5 %·검출 ≈ 0.8, 차단 단계(+0.12 이고 ≥ 0.12)는 오탐 ≤ 2 % 목표. 수축 구간 값이 반복 안 변화량(0.78)보다 낫다 — 기준은 세트 시작 자세",
                cautions = listOf("사선(B/D)에서만 — 정면에서는 앞 성분이 깊이 축이라 어떤 후보도 AUC 0.85 미만, 유보", "임계는 스튜디오(4~6 m·골반 높이) 2D 값 — 폰 좌표 로그로 재보정 전(잠정, 라벨 세트 대기)", "월드 `elbow_torso_R__mean` 세트 규칙(0.193)은 정상 세트 오탐 16 % — 이 검사가 대체한다")),
            RepFormCheck("repform|$ex|몸통 반동", ex, "몸통 반동(반복)", "몸통", RuleStatus.BETA, Arm2d.TORSO_TILT, RepPhase.BOTTOM, RepFormStat.MEDIAN, RepFormRef.START_DELTA,
                lo = -0.10f, hi = 0.10f, lowText = "올릴 때 몸통이 뒤로 젖혀졌어요", highText = "올릴 때 몸통이 앞으로 숙여졌어요", lowLabel = "뒤로 젖힘", highLabel = "앞 숙임",
                fix = "몸통을 세우고 팔만 움직이세요", views = oblique,
                reason = "§62c(B1·B3): 반동의 몸통 성분. 사선 2D 기울기 비(≈ 8° 당 0.10), 수축 − 시작 변화 정상 초과 0~1.2 %. AIHub 연기자도 몸통을 안 써(정상 −4~+2.5°) 검출 근거가 없다 — beta",
                cautions = listOf("검출 근거 없음(라벨 세트 대기)", "월드 몸통 기울기는 정면에서 못 쓴다(정상 세트 64 % 가 10° 넘게 흔들림) — 2D 사선만")),
            RepFormCheck("repform|$ex|팔꿈치 벌어짐", ex, "팔꿈치 벌어짐(반복)", "팔꿈치 간격", RuleStatus.BETA, "elbow_gap_sh", RepPhase.CYCLE, RepFormStat.MAX, RepFormRef.NONE,
                lo = null, hi = 1.8f, lowText = null, highText = "팔꿈치가 옆으로 벌어졌어요", lowLabel = null, highLabel = "벌어짐",
                fix = "팔꿈치를 몸에 붙이세요", views = setOf("B", "C", "D"),
                reason = "§62c(B1·B2): AIHub 라벨은 반대 방향(팔꿈치를 내밀면 간격이 좁아짐 1.13 vs 1.27)이고 벌어짐 라벨은 어디에도 없다. 정상 컬도 수축 시 간격이 +0.1~0.2 어깨폭 늘어난다. 세트 최대 ≥ 1.8 어깨폭은 정상 초과 3~4 % — 참고만",
                cautions = listOf("검출 근거 없음(일부러 벌린 세트 필요)", "2D 간격은 뷰에 따라 p95 0.14↔0.39 로 흔들려 월드 3D 만")),
        )
    }

    private val PROVISIONAL = "임계값 잠정(실기기 한 사람 두 세트) — 지정 오류 세트 3명 이후 확정. 그동안 beta"

    /**
     * 컬 '상체 숙임' 띠(°, 세트에서 가장 곧았던 상단 대비, §62c 후속 6). 연속 세트 재생에서 정상 반복 초과 폰 1/75(10:41 6회 +23.3°, 숙임 여부 불명)·
     * MM-Fit 1/123(0.8 %) — 25° 면 11:14 정면 숙임 4회(+23°)를 놓친다.
     */
    const val LEAN_HI = 20f

    private fun squat(): List<RepFormCheck> {
        val ex = "바벨 스쿼트"
        return listOf(
            // 우선순위 순 — 발화·화면 사건은 이 순서의 첫 위반
            RepFormCheck("repform|$ex|상체 숙임", ex, "상체 숙임(반복)", "상체", RuleStatus.SHIP, "torso_incl", RepPhase.CYCLE, RepFormStat.MAX, RepFormRef.START_DELTA,
                lo = null, hi = 45f, lowText = null, highText = "상체가 시작보다 많이 숙여졌어요", lowLabel = null, highLabel = "숙임",
                fix = "가슴을 들고 몸통을 세우세요", unit = "°",
                reason = "정면 폰은 척추 굴곡을 못 본다 — 기울기 최대(반복 안)로 큰 숙임만 잡는다. 실기기 허리 굽힘 2회 63~67° vs 정상 14~39°. ship 승격(사용자 결정 2026-09-25 저녁, 실기기 시험 뒤 \"허리 굽혔을 때도 똑같이 막아\") — 정상 반복 오탐 REHAB 정면 0 %·세로 0 %·MM-Fit 1 %(§21.10), 실기기 검출 2/2(57~60°). COACH 에서 이 회는 횟수에서 빠진다(§62b)",
                cautions = listOf("정면에서는 '말림' 이 아니라 '숙임' 이다 — 미세한 말림은 못 본다", "월드 기울기(중력 up) 기반 — up 오류 세트(16:13)에서는 시작 자세도 같이 틀려 차가 무의미해질 수 있다(up 방어 작업 대기)", "지정 오류 세트 3명 이후 띠 확정")),
            RepFormCheck("repform|$ex|엉덩이 먼저 상승", ex, "엉덩이 먼저 상승(반복)", "엉덩이", RuleStatus.BETA, "torso_incl", RepPhase.ASCENT, RepFormStat.RISE, RepFormRef.NONE,
                lo = null, hi = 20f, lowText = null, highText = "올라올 때 엉덩이가 먼저 올라와 상체가 더 숙여졌어요", lowLabel = null, highLabel = "먼저 상승",
                fix = "무릎과 엉덩이를 같이 펴세요", unit = "°",
                reason = "카탈로그 #13(hip rise, Schoenfeld 2010): 올라오며 무릎보다 엉덩이가 먼저 펴지면 바닥보다 상체가 더 숙여진다 — 올라오는 구간의 상체 기울기 최대 − 바닥 값. 정상 반복 p95: REHAB 정면 15°·세로 5°·MM-Fit 7°(§21.11) → 20°(오탐 REHAB 정면 2 %)",
                cautions = listOf(PROVISIONAL, "정면 기울기 기반 — 옆면이 더 정확하다")),
            RepFormCheck("repform|$ex|무릎 안쪽 모임", ex, "무릎 안쪽 모임(반복)", "무릎", RuleStatus.SHIP, "knee_out_mean", RepPhase.BOTTOM, RepFormStat.MEAN, RepFormRef.NONE,
                lo = 0.0f, hi = null, lowText = "바닥에서 무릎이 안쪽으로 모였어요", highText = null, lowLabel = "안쪽", highLabel = null,
                fix = "무릎을 발끝 방향으로 두세요",
                reason = "무릎이 엉덩이–발목 선 안쪽(값 < 0)이면 valgus. AIHub 세트 평균 임계 0.02388 을 바닥 구간에 그대로 쓰면 정상 반복 오탐 REHAB 정면 8 %·MM-Fit 2 %(§21.10) — 0.0 으로 REHAB 4 %·0 %·0 %. 세트 평균 규칙은 서 있는 프레임(−0.02)이 결정해 바닥이 정상인 세트에 '무릎 안쪽' 4번(실기기 2026-09-25)",
                cautions = listOf("정면(C)에서만", "바닥 구간 평균 — 실기기 정상 바닥 0.08~0.30 대비 여유 큼",
                    "knee_out 은 발 자세를 따른다(11:37 세트: 발끝 −21°·발 너비 ×1.8 인 반복에서 −0.01~0.02) — AIHub 임계는 보통 스탠스 전제. 같은 반복에 발 위반이 있으면 문장이 발을 먼저 말한다"),
                causes = listOf("repform|$ex|발끝 방향", "repform|$ex|발 간격")),
            RepFormCheck("repform|$ex|무릎 과도 벌림", ex, "무릎 과도 벌림(반복)", "무릎", RuleStatus.BETA, "knee_out_mean", RepPhase.BOTTOM, RepFormStat.MEAN, RepFormRef.NONE,
                lo = null, hi = 0.40f, lowText = null, highText = "바닥에서 무릎이 과하게 벌어졌어요", lowLabel = null, highLabel = "벌림",
                fix = "무릎을 발끝 방향에 맞추세요", causes = listOf("repform|$ex|발끝 방향", "repform|$ex|발 간격"),
                reason = "AIHub 에 '과도 벌림' 클립이 없어 위쪽 경계가 없었다 — 일부러 벌린 반복이 '교정됐어요' 로 읽힘. 재생(2026-09-25): 바닥 창 평균은 정상 반복도 0.34~0.35(09:51 세트)까지 가고 일부러 벌린 반복(0.26~0.30 프레임 최대)과 겹친다 — 이 피처·창으로는 갈라지지 않는다. 0.40 은 정상 위쪽 여유일 뿐 검출 근거가 없다",
                cautions = listOf(PROVISIONAL, "knee_out 바닥 평균은 일부러 벌린 반복과 무릎을 넓게 쓰는 정상 반복을 구분하지 못했다(재생) — 무릎이 발보다 바깥인지(knee_gap ÷ stance) 같은 다른 피처 후보")),
            RepFormCheck("repform|$ex|좌우 무릎 비대칭", ex, "좌우 무릎 비대칭(반복)", "좌우 균형", RuleStatus.BETA, "knee_asym", RepPhase.BOTTOM, RepFormStat.MEAN, RepFormRef.NONE,
                lo = -30f, hi = 30f, lowText = "바닥에서 왼쪽 무릎이 더 굽었어요 — 체중이 왼쪽으로 쏠린 것 같아요", highText = "바닥에서 오른쪽 무릎이 더 굽었어요 — 체중이 오른쪽으로 쏠린 것 같아요",
                lowLabel = "왼쪽 쏠림", highLabel = "오른쪽 쏠림", fix = "양발에 체중을 고르게 두세요", unit = "°",
                reason = "카탈로그 #9(좌우 체중 쏠림): 바닥에서 두 무릎각의 차(왼 − 오른, MediaPipe 몸 기준 좌우). 정상 반복 p5~p95 −18~+13°(§21.11), 실기기 무릎 들기 ±73~129°. ±30 오탐 0 %",
                cautions = listOf(PROVISIONAL, "정면에서 무릎각 좌우 차는 카메라 사선에 민감하다 — 정면(C)에서만")),
            RepFormCheck("repform|$ex|몸통 좌우 기울기", ex, "몸통 좌우 기울기(반복)", "몸통", RuleStatus.BETA, "torso_roll", RepPhase.CYCLE, RepFormStat.EXTREME, RepFormRef.START_DELTA,
                lo = -20f, hi = 20f, lowText = "몸통이 옆으로 기울었어요", highText = "몸통이 옆으로 기울었어요", lowLabel = "기울음", highLabel = "기울음",
                fix = "양 어깨 높이를 맞추세요", unit = "°",
                reason = "카탈로그 #9: 반복 중 몸통 좌우 기울기(torso_roll)가 시작 자세에서 가장 멀어진 값. 정상 반복 p5~p95 −12~+14°(§21.11) — ±12 는 오탐 9~15 %, ±20 으로",
                cautions = listOf(PROVISIONAL)),
            RepFormCheck("repform|$ex|발 간격|시작", ex, "발 간격(시작)", "발 너비", RuleStatus.BETA, Stance2d.FEATURE, RepPhase.START, RepFormStat.MEDIAN, RepFormRef.NONE,
                lo = 0.5f, hi = 1.8f, lowText = "발이 어깨보다 좁아요", highText = "발이 어깨보다 많이 넓어요", lowLabel = "좁음", highLabel = "넓음",
                fix = "발을 어깨 너비로 벌려 주세요",
                reason = "사용자 결정: 스쿼트 발 간격은 어깨 너비. 이미지 2D 발목 x 간격 ÷ 어깨 x 간격 — 월드 3D 발목 간격은 발끝 회전에 흔들린다(12:19 검증 세트 좌표: 3D −18 % vs 2D ±5 %). 정상 서기 0.9~1.15(1명), 넓게 2.2",
                cautions = listOf(PROVISIONAL, "넓은 스탠스(스모)는 정당한 변형일 수 있다 — 세트 전 안내로만", "절대 비율은 카메라 높이에 따라 다르다(§21.10: 폰 바닥 0.88~1.16, REHAB 정면 1.07~1.65, MM-Fit 0.45~1.29) — 이 띠는 극단만 걸러낸다")),
            RepFormCheck("repform|$ex|발 간격", ex, "발 간격(반복)", "발 너비", RuleStatus.SHIP, Stance2d.ANKLE_SEP, RepPhase.BOTTOM, RepFormStat.MEDIAN, RepFormRef.START_RATIO,
                lo = null, hi = 1.4f, lowText = null, highText = "발 너비가 시작보다 넓어졌어요", lowLabel = null, highLabel = "넓음",
                fix = "발을 어깨 너비로 다시 두세요", absRefFeature = Stance2d.SHOULDER_SEP, absMin = 1.5f,
                reason = "§21.12(A7b): 반복 바닥 구간의 2D 발목 x 간격 중앙값 ÷ 시작 발목 간격 — 정면 정상 반복 오탐 0/243(REHAB 51·MM-Fit 192, 진짜 300 ms), 폰 넓힘 7/7(×1.53~2.35), 정상·발끝 회전·무릎 모음 반복 오탐 0/27, 유보 0~2 %. 프레임별 어깨로 나누던 옛 방식(stance_2d 극값)은 어깨 한 프레임(26 px)에 ×3.21, 직전 창 이월에 ×1.90 오탐(16:16 세트). 절대 조건(÷ 시작 어깨 ≥ 1.5)은 시작 기준이 어긋난 세트(16:13: 발 모은 채 시작 → 21회 '넓음')를 막는다",
                cautions = listOf("정면(C)에서만 — 사선 뷰(B/D)는 앉을 때 원근으로 발목 간격이 변해 오탐 4 %", "절대 1.5 는 잠정(폰 1명 정상 1.05~1.36, 모집단 p95 1.42~1.65, 카메라 높이 의존) — 폰 세트 3명 이후 확정", "×1.4 는 모집단 C 오탐 0, 폰 넓힘 최소 ×1.53 과의 여유 0.13")),
            RepFormCheck("repform|$ex|발 간격|좁음", ex, "발 간격 좁아짐(반복)", "발 너비", RuleStatus.BETA, Stance2d.ANKLE_SEP, RepPhase.BOTTOM, RepFormStat.MEDIAN, RepFormRef.START_RATIO,
                lo = 0.7f, hi = null, lowText = "발 너비가 시작보다 좁아졌어요", highText = null, lowLabel = "좁음", highLabel = null,
                fix = "발을 어깨 너비로 다시 두세요",
                reason = "좁아짐은 스타일일 수 있어 참고만(횟수 게이트 아님). 바닥 발목 간격 ÷ 시작 < 0.7 — 모집단 정상 반복 오탐 0 %(A7b 1d)",
                cautions = listOf(PROVISIONAL, "검출 근거 없음(일부러 좁힌 세트가 없다)")),
            RepFormCheck("repform|$ex|발끝 방향|시작", ex, "발끝 방향(시작)", "발끝", RuleStatus.BETA, Stance2d.TOE_MAXSIDE, RepPhase.START, RepFormStat.MEDIAN, RepFormRef.NONE,
                lo = -5f, hi = 50f, lowText = "발끝이 안으로 모여 있어요", highText = "발끝이 바깥으로 많이 벌어져 있어요", lowLabel = "안쪽", highLabel = "바깥",
                fix = "발끝을 살짝만 바깥으로 두세요", unit = "°",
                reason = "관용 발끝 각 5~30° 에 이미지 2D 측정 편향을 더한 띠 — 이 사용자 정상 22~34°(0.75 m 폰), 바닥 폰은 +21~24° 더 크게 읽는다(A1). 극단만",
                cautions = listOf(PROVISIONAL, "발끝(31/32)·발목이 화면 안이어야 한다 — 잘리면 유보", "카메라 높이에 따라 영점이 움직여 세트 전 안내로만")),
            RepFormCheck("repform|$ex|발끝 방향", ex, "발끝 방향(반복)", "발끝", RuleStatus.SHIP, Stance2d.TOE_MAXSIDE, RepPhase.TOP, RepFormStat.MEDIAN, RepFormRef.START_DELTA,
                lo = -15f, hi = 15f, lowText = "발끝이 시작보다 안으로 모였어요", highText = "발끝이 시작보다 바깥으로 벌어졌어요", lowLabel = "안쪽", highLabel = "바깥",
                fix = "발끝을 시작 자세로 되돌리세요", unit = "°", windowFrames = 3,
                reason = "§21.12(A7a·A7b): 이미지 2D 발목→발끝 각(더 벌어진 쪽), 하강 직전 서 있는 ≤3프레임(0.6~0.9 s) 중앙값, 시작 대비 ±15°. 정면 정상 반복 오탐 REHAB 0 %·MM-Fit 4 %, 폰 검출 5/6(벌림 +18~+22°, 모음 −45°). 월드 3D 각(옛 피처)은 실제 회전을 2D 의 6할로 반영해(z 가 GHUM 추정치) 같은 회를 +11~+12° 로 읽어 놓쳤다. '한쪽만 넘어도' 는 검출을 못 늘리고 오탐만 두 배라 채택 안 함",
                cautions = listOf("정면(C)에서만 — 사선에서는 기울기 0.26~0.75", "넓게 서면 발끝 그대로여도 모든 판독이 +14~+30°(A6·A7b, MediaPipe 편향 — 원근이 아님) → 발 너비 위반 반복은 유보", "놓친 1건(16:16 3회)은 랜드마크가 움직이지 않았다 — 원인 미확정(테이프 실험 대기)", "300 ms 에서 서 있는 프레임 2개 이상일 때만 — 쉬지 않고 이어 하면 유보"),
                invalidatedBy = listOf("repform|$ex|발 간격")),
        )
    }
}
