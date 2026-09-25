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
enum class RepFormRef { NONE, START_RATIO, START_DELTA }

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
        RepFormRef.START_DELTA -> String.format(java.util.Locale.US, "%+.0f%s", value, unit)
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
)

data class RepFormRep(
    val index: Int,
    val tMs: Long,
    val outcomes: List<RepFormOutcome>,
    /** 이 반복과 직전 반복에서 연속으로 위반한 ship 검사 id — 음성은 이것에만 붙는다(한 번 튐은 화면만, 원칙 #6). 평가기가 사이클마다 채운다. */
    val consecutiveShip: Set<String> = emptySet(),
) {
    val flagged: List<RepFormOutcome> get() = outcomes.filter { it.verdict == Verdict.VIOLATION }

    /** ship 검사 위반이 없다. ABSTAIN 은 위반이 아니고(원칙 #1), beta 는 정확을 깎지 못한다(원칙 #2). */
    val correct: Boolean get() = flagged.none { it.check.ship }
}

/** 반복 하나에서 말할(또는 화면에 남길) 사건 하나 — 우선순위 = 검사 순서. ship 만 음성. */
data class RepFormEvent(val check: RepFormCheck, val direction: FormDirection, val message: String, val ship: Boolean)

class RepFormEvaluator(
    val checks: List<RepFormCheck>,
    private val signalFeature: String,
    private val minAmp: Float,
    private val cooldownMs: Long = 12_000L,
) {
    private var buf = ArrayList<Pair<Long, Map<String, Float>>>()
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
        buf.clear(); carry = emptyList(); repList.clear(); startList.clear(); lastSpokenAt.clear()
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
        val raw = checks.filter { it.phase != RepPhase.START }.map { evaluate(it, phases) }
        // 측정을 무효로 만드는 검사가 같은 반복에서 위반이면 유보 — 틀린 방향의 말보다 침묵이 낫다(원칙 #6)
        val violated = raw.filter { it.verdict == Verdict.VIOLATION }.map { it.check.id }.toSet()
        val outcomes = raw.map { o ->
            val by = o.check.invalidatedBy.firstOrNull { it in violated }
            if (by == null || o.verdict == Verdict.ABSTAIN) o
            else o.copy(verdict = Verdict.ABSTAIN, direction = null, abstainReason = "${checks.first { it.id == by }.bodyPart} 위반으로 측정 무효")
        }
        val prev = repList.lastOrNull()
        val consecutive = outcomes.filter { o -> o.check.ship && o.verdict == Verdict.VIOLATION && prev?.flagged?.any { it.check.id == o.check.id } == true }
            .map { it.check.id }.toSet()
        val rep = RepFormRep(repList.size + 1, endMs, outcomes, consecutive)
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
                return RepFormEvent(c, d, msg, ship = true)
            }
            return RepFormEvent(c, d, c.text(d), ship = false)
        }
        return null
    }

    fun summary(): RepFormSummary =
        RepFormSummary(checks, baseline, baselineAtMs, startList.toList(), repList.toList(), rejectedCount, noTopCount, baselineFromFirstBottom)

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
        return RepFormOutcome(c, if (d == null) Verdict.OK else Verdict.VIOLATION, v, v, null, d, 1)
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
        val ref = if (c.ref == RepFormRef.NONE) null else baseline?.get(c.feature)
        if (c.stat == RepFormStat.EXTREME) {
            // 상대 기준에서 가장 멀리 벗어난 프레임 — 반복 중에 발을 옮기면 복귀 뒤 서 있는 프레임에서 드러난다(11:37 세트 3회)
            if (ref == null || (c.ref == RepFormRef.START_RATIO && abs(ref) < 1e-6f))
                return RepFormOutcome(c, Verdict.ABSTAIN, null, null, ref, null, values.size, "시작 자세 기준 없음")
            val rels = values.map { if (c.ref == RepFormRef.START_RATIO) it / ref else it - ref }
            val neutral = if (c.ref == RepFormRef.START_RATIO) 1f else 0f
            var best = 0
            for (i in rels.indices) if (abs(rels[i] - neutral) > abs(rels[best] - neutral)) best = i
            val d = c.judge(rels[best])
            return RepFormOutcome(c, if (d == null) Verdict.OK else Verdict.VIOLATION, rels[best], values[best], ref, d, values.size)
        }
        val raw = stat(values, c.stat)
        val value = when (c.ref) {
            RepFormRef.NONE -> raw
            RepFormRef.START_RATIO -> if (ref == null || abs(ref) < 1e-6f) return RepFormOutcome(c, Verdict.ABSTAIN, null, raw, ref, null, values.size, "시작 자세 기준 없음") else raw / ref
            RepFormRef.START_DELTA -> if (ref == null) return RepFormOutcome(c, Verdict.ABSTAIN, null, raw, null, null, values.size, "시작 자세 기준 없음") else raw - ref
        }
        var d = c.judge(value)
        if (d == FormDirection.HIGH && c.absRefFeature != null && c.absMin != null) {
            // 절대 척도(시작 어깨 등)로 한 번 더 — 상대 변화만으로는 '스타일이 바뀜' 과 '어깨 너비를 넘음' 을 못 가른다
            val absRef = baseline?.get(c.absRefFeature)
            if (absRef == null || abs(absRef) < 1e-6f) return RepFormOutcome(c, Verdict.ABSTAIN, value, raw, ref, null, values.size, "시작 자세에 ${c.absRefFeature} 없음")
            if (raw / absRef < c.absMin) d = null
        }
        return RepFormOutcome(c, if (d == null) Verdict.OK else Verdict.VIOLATION, value, raw, ref, d, values.size)
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
        start = start.map { RepFormLog.Check(it.check.id, it.verdict.name, it.value, it.raw, it.reference, it.direction?.name) },
        reps = reps.map { r -> RepFormLog.Rep(r.tMs - t0, r.correct, r.outcomes.map { RepFormLog.Check(it.check.id, it.verdict.name, it.value, it.raw, it.reference, it.direction?.name) }) },
        rejected = rejected, noTop = noTop, baselineFromFirstBottom = baselineFromFirstBottom,
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
) {
    data class Check(val id: String, val verdict: String, val value: Float?, val raw: Float?, val reference: Float?, val direction: String?)
    data class Rep(val tMs: Long, val correct: Boolean, val checks: List<Check>)

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
            sb.append(",\"dir\":").append(c.direction?.let(::str) ?: "null").append('}')
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
            sb.append("{\"t_ms\":").append(r.tMs).append(",\"correct\":").append(r.correct).append(",\"checks\":[")
            r.checks.forEachIndexed { j, c -> if (j > 0) sb.append(','); check(c) }
            sb.append("]}")
        }
        sb.append("],\"rejected\":").append(rejected).append(",\"no_top\":").append(noTop)
        sb.append(",\"baseline_from_first_bottom\":").append(baselineFromFirstBottom).append('}')
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

    /** 이 검사가 대체하는 창 규칙 id — 세션 규칙셋에서 beta 로 낮춘다(음성·점수·헤드라인에서 빠지고 리포트엔 '참고'로 남는다). */
    val supersedes: Map<String, String> = mapOf("바벨 스쿼트|발과 무릎의 방향 일치" to "repform|바벨 스쿼트|무릎 안쪽 모임")

    val byExercise: Map<String, List<RepFormCheck>> = mapOf("바벨 스쿼트" to squat())

    fun evaluatorFor(exercise: String, counter: RepCounter): RepFormEvaluator? =
        byExercise[exercise]?.let { RepFormEvaluator(it, counter.signal.feature, counter.signal.minAmp) }

    fun checkOf(ruleId: String): RepFormCheck? = byExercise.values.flatten().firstOrNull { it.id == ruleId }

    private val PROVISIONAL = "임계값 잠정(실기기 한 사람 두 세트) — 지정 오류 세트 3명 이후 확정. 그동안 beta"

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
