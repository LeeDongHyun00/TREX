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
enum class RepPhase { START, TOP, BOTTOM, CYCLE }

/** 기준 — NONE: 절대값(모집단 띠), START_RATIO: 시작 자세 대비 비율, START_DELTA: 시작 자세 대비 차. */
enum class RepFormRef { NONE, START_RATIO, START_DELTA }

enum class RepFormStat { MEDIAN, MEAN, MAX, MIN }

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

data class RepFormRep(val index: Int, val tMs: Long, val outcomes: List<RepFormOutcome>) {
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
    private val repList = ArrayList<RepFormRep>()
    private val startList = ArrayList<RepFormOutcome>()
    private val lastSpokenAt = HashMap<String, Long>()
    private val lastViolatedRep = HashMap<String, Int>()
    private var rejectedCount = 0
    private var noTopCount = 0

    /** 시작 자세 — 첫 상단 창의 피처별 중앙값. 잡히기 전엔 null(상대 기준 검사는 유보). */
    var baseline: Map<String, Float>? = null
        private set
    var baselineAtMs: Long? = null
        private set

    val reps: List<RepFormRep> get() = repList
    val startOutcomes: List<RepFormOutcome> get() = startList

    fun reset() {
        buf.clear(); repList.clear(); startList.clear(); lastSpokenAt.clear(); lastViolatedRep.clear()
        baseline = null; baselineAtMs = null; rejectedCount = 0; noTopCount = 0
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
        rejectedCount++
    }

    /**
     * 카운터가 센 사이클 하나(끝 [endMs], 극값 [cycleMin]/[cycleMax]). 창 = 직전 사이클 끝 이후 ~ endMs.
     * 첫 상단 창이 잡히면 시작 자세를 세우고 START 검사를 한 번 한다.
     */
    fun onCycle(endMs: Long, cycleMin: Float, cycleMax: Float): RepFormRep {
        val window = buf.filter { it.first <= endMs }
        buf.removeAll { it.first <= endMs }
        val phases = segment(window, cycleMin, cycleMax)
        if (phases.top.isEmpty()) noTopCount++
        if (baseline == null && phases.top.size >= 2) {
            baseline = medians(phases.top)
            baselineAtMs = phases.top.last().first
            for (c in checks) if (c.phase == RepPhase.START) startList += evaluateStart(c)
        }
        val outcomes = checks.filter { it.phase != RepPhase.START }.map { evaluate(it, phases) }
        val rep = RepFormRep(repList.size + 1, endMs, outcomes)
        repList += rep
        return rep
    }

    /**
     * 이 반복에서 말할 사건. ship 은 **같은 검사가 2회 연속** 위반이고 쿨다운이 지났을 때만(한 번 튐은 화면만) — 음성은 즉시 몸을 바꾸는 채널이다(원칙 #6).
     * beta 는 화면 '참고' 용이라 반복마다 돌려준다(음성 아님). 우선순위 = 검사 순서.
     */
    fun eventFor(rep: RepFormRep, nowMs: Long): RepFormEvent? {
        val flagged = rep.flagged
        val consecutive = HashSet<String>()
        for (o in flagged) if (o.check.ship) {
            if (lastViolatedRep[o.check.id] == rep.index - 1) consecutive += o.check.id
            lastViolatedRep[o.check.id] = rep.index
        }
        for (o in flagged) {
            val d = o.direction ?: continue
            val c = o.check
            if (c.ship) {
                if (c.id !in consecutive) continue
                val last = lastSpokenAt[c.id]
                if (last != null && nowMs - last < cooldownMs) continue
                lastSpokenAt[c.id] = nowMs
                return RepFormEvent(c, d, "${c.text(d)}. ${c.fix}.", ship = true)
            }
            return RepFormEvent(c, d, c.text(d), ship = false)
        }
        return null
    }

    fun summary(): RepFormSummary =
        RepFormSummary(checks, baseline, baselineAtMs, startList.toList(), repList.toList(), rejectedCount, noTopCount)

    private class Phases(val top: List<Pair<Long, Map<String, Float>>>, val bottom: List<Pair<Long, Map<String, Float>>>, val cycle: List<Pair<Long, Map<String, Float>>>)

    /**
     * 창을 위상으로 나눈다. 상단 = 사이클 최소값 앞에서 마지막으로 서 있던 프레임(신호 ≥ 최대 − 0.22h)부터 거꾸로 ≤ 5개 —
     * 준비 동작이 길어도 하강 직전만 본다. 바닥 = 최소 + 진폭/3 아래. 사이클 = 상단 끝 다음부터 끝까지.
     */
    private fun segment(window: List<Pair<Long, Map<String, Float>>>, cycleMin: Float, cycleMax: Float): Phases {
        val sig = window.filter { it.second.containsKey(signalFeature) }
        if (sig.isEmpty()) return Phases(emptyList(), emptyList(), window)
        val v = sig.map { it.second.getValue(signalFeature) }
        var idxMin = 0
        for (i in v.indices) if (v[i] < v[idxMin]) idxMin = i
        val standing = cycleMax - STANDING_BAND * minAmp
        var topEnd = -1
        for (i in idxMin downTo 0) if (v[i] >= standing) { topEnd = i; break }
        val top = if (topEnd < 0) emptyList() else (maxOf(0, topEnd - TOP_FRAMES + 1)..topEnd).filter { v[it] >= standing }.map { sig[it] }
        val bottomLevel = cycleMin + (cycleMax - cycleMin) / 3f
        val bottom = sig.indices.filter { v[it] <= bottomLevel }.map { sig[it] }
        return Phases(top, bottom, sig.drop(topEnd + 1))
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
        val frames = when (c.phase) { RepPhase.TOP -> p.top; RepPhase.BOTTOM -> p.bottom; RepPhase.CYCLE -> p.cycle; RepPhase.START -> emptyList() }
        val values = frames.mapNotNull { it.second[c.feature] }
        val need = if (c.stat == RepFormStat.MAX || c.stat == RepFormStat.MIN) 1 else 2
        if (values.size < need) return RepFormOutcome(c, Verdict.ABSTAIN, null, null, null, null, values.size,
            if (c.phase == RepPhase.TOP && p.top.isEmpty()) "하강 직전 상단 없음" else "창에 ${c.feature} 부족")
        val raw = stat(values, c.stat)
        val ref = if (c.ref == RepFormRef.NONE) null else baseline?.get(c.feature)
        val value = when (c.ref) {
            RepFormRef.NONE -> raw
            RepFormRef.START_RATIO -> if (ref == null || abs(ref) < 1e-6f) return RepFormOutcome(c, Verdict.ABSTAIN, null, raw, ref, null, values.size, "시작 자세 기준 없음") else raw / ref
            RepFormRef.START_DELTA -> if (ref == null) return RepFormOutcome(c, Verdict.ABSTAIN, null, raw, null, null, values.size, "시작 자세 기준 없음") else raw - ref
        }
        val d = c.judge(value)
        return RepFormOutcome(c, if (d == null) Verdict.OK else Verdict.VIOLATION, value, raw, ref, d, values.size)
    }

    companion object {
        /** 서 있음 판정 띠 — `ReturnRepTracker` 의 복귀 띠(0.22h)와 같은 비율. */
        const val STANDING_BAND = 0.22f
        /** 상단 창 길이(프레임) — 300 ms 샘플링 ≈ 1.5 s. */
        const val TOP_FRAMES = 5
        private const val CAP = 2_000

        fun stat(values: List<Float>, stat: RepFormStat): Float = when (stat) {
            RepFormStat.MEAN -> values.average().toFloat()
            RepFormStat.MAX -> values.max()
            RepFormStat.MIN -> values.min()
            RepFormStat.MEDIAN -> values.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f }
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
) {
    val hasShip: Boolean get() = checks.any { it.ship && it.phase != RepPhase.START }
    val correct: Int get() = reps.count { it.correct }

    /** 검사 하나의 반복별 결과(반복 순서). */
    fun outcomesOf(check: RepFormCheck): List<RepFormOutcome> = reps.mapNotNull { r -> r.outcomes.firstOrNull { it.check.id == check.id } }

    /** 완료 화면·기록의 요약 줄. 정확 수는 ship 검사가 있을 때만(없으면 '정확' 이라는 말을 쓰지 않는다). */
    fun lines(): List<String> = buildList {
        if (reps.isEmpty()) return@buildList
        if (baseline == null) add("시작 자세를 잡지 못해 시작 기준 검사(발 너비·발끝)는 못 했어요")
        else {
            val startText = start.filter { it.value != null }.joinToString(" · ") { o -> "${o.check.bodyPart} ${o.check.format(o.value!!)}" }
            val startBad = start.filter { it.verdict == Verdict.VIOLATION }
            if (startText.isNotEmpty()) add("시작 자세 · $startText" + if (startBad.isEmpty()) " · 정상 범위" else " — " + startBad.joinToString(", ") { it.check.text(it.direction!!) })
        }
        // 검사별 위반 수는 규칙 행(ruleResult 의 measurement)에 있다 — 여기서는 행에 없는 것만: 시작 자세, 정확 수, 못 잰 반복
        if (hasShip) add("정확 $correct / ${reps.size}회")
        if (noTop > 0) add("하강 직전 상단을 못 잡은 반복 ${noTop}회 — 그 회는 발 너비·발끝 비교를 못 했어요")
    }

    /** 검사별 위반 요약 한 줄("발 너비 넓음 4회 · 발끝 바깥 1회") — HUD·재생 출력용. 위반이 없으면 null. */
    fun flagLine(): String? {
        val parts = checks.filter { it.phase != RepPhase.START }.mapNotNull { c ->
            val bad = outcomesOf(c).filter { it.verdict == Verdict.VIOLATION }
            if (bad.isEmpty()) null else {
                val dir = bad.groupingBy { it.direction }.eachCount().maxByOrNull { it.value }?.key ?: FormDirection.HIGH
                "${c.bodyPart} ${c.label(dir)} ${bad.size}회"
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun toLog(t0: Long): RepFormLog = RepFormLog(
        version = RepFormSpecs.VERSION,
        baselineTMs = baselineAtMs?.let { it - t0 },
        baseline = baseline?.filterKeys { k -> checks.any { it.feature == k } }.orEmpty(),
        start = start.map { RepFormLog.Check(it.check.id, it.verdict.name, it.value, it.raw, it.reference, it.direction?.name) },
        reps = reps.map { r -> RepFormLog.Rep(r.tMs - t0, r.correct, r.outcomes.map { RepFormLog.Check(it.check.id, it.verdict.name, it.value, it.raw, it.reference, it.direction?.name) }) },
        rejected = rejected, noTop = noTop,
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
) {
    data class Check(val id: String, val verdict: String, val value: Float?, val raw: Float?, val reference: Float?, val direction: String?)
    data class Rep(val tMs: Long, val correct: Boolean, val checks: List<Check>)

    /**
     * `rep_form` 블록의 JSON — 세트 로그(`SetLogJson`)와 재생기가 같은 문자열을 낸다(재생기는 org.json 도 SetLogJson 도 컴파일하지 않는다).
     * 숫자 형식은 `SetLogJson.num`(고정 소수 5자리, 끝 0·점 제거, NaN → null) 과 같다.
     * `{"version","baseline_t_ms","baseline":{feature:value},"start":[check],"reps":[{"t_ms","correct","checks":[check]}],"rejected","no_top"}`,
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
        sb.append("],\"rejected\":").append(rejected).append(",\"no_top\":").append(noTop).append('}')
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
 * 임계값의 출처(spec §62a): 잠정값은 2026-09-25 실기기 두 세트(한 사람)에서 골랐고 **확정이 아니다** — 지정 오류 세트 3명 이후 확정.
 * 그동안 beta(화면·리포트 '참고'). ship 은 하나 — 무릎 안쪽 모임(바닥): AIHub 검증 임계(0.02388)를 그 조건이 물리적으로 드러나는
 * 바닥 구간에 적용한 것이라, 세트 평균 규칙(서 있는 프레임이 결정)을 대체한다([supersedes]).
 */
object RepFormSpecs {
    const val VERSION = "repform_v0.1"

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
            RepFormCheck("repform|$ex|상체 숙임", ex, "상체 숙임(반복)", "상체", RuleStatus.BETA, "torso_incl", RepPhase.CYCLE, RepFormStat.MAX, RepFormRef.START_DELTA,
                lo = null, hi = 45f, lowText = null, highText = "상체가 시작보다 많이 숙여졌어요", lowLabel = null, highLabel = "숙임",
                fix = "가슴을 들고 몸통을 세우세요", unit = "°",
                reason = "정면 폰은 척추 굴곡을 못 본다 — 기울기 최대(반복 안)로 큰 숙임만 잡는다. 실기기 허리 굽힘 2회 63~67° vs 정상 14~39°",
                cautions = listOf(PROVISIONAL, "정면에서는 '말림' 이 아니라 '숙임' 이다 — 미세한 말림은 못 본다")),
            RepFormCheck("repform|$ex|무릎 안쪽 모임", ex, "무릎 안쪽 모임(반복)", "무릎", RuleStatus.SHIP, "knee_out_mean", RepPhase.BOTTOM, RepFormStat.MEAN, RepFormRef.NONE,
                lo = 0.02388f, hi = null, lowText = "바닥에서 무릎이 안쪽으로 모였어요", highText = null, lowLabel = "안쪽", highLabel = null,
                fix = "무릎을 발끝 방향으로 두세요",
                reason = "AIHub 검증 임계(0.02388, 정상 오탐 0.11)를 조건이 드러나는 바닥 구간에 적용. 세트 평균 규칙은 서 있는 프레임(−0.02)이 결정해 바닥이 정상인 세트에 '무릎 안쪽' 4번(실기기 2026-09-25)",
                cautions = listOf("정면(C)에서만", "바닥 구간 평균 — 실기기 정상 바닥 0.08~0.30 대비 여유 큼")),
            RepFormCheck("repform|$ex|무릎 과도 벌림", ex, "무릎 과도 벌림(반복)", "무릎", RuleStatus.BETA, "knee_out_mean", RepPhase.BOTTOM, RepFormStat.MEAN, RepFormRef.NONE,
                lo = null, hi = 0.40f, lowText = null, highText = "바닥에서 무릎이 과하게 벌어졌어요", lowLabel = null, highLabel = "벌림",
                fix = "무릎을 발끝 방향에 맞추세요",
                reason = "AIHub 에 '과도 벌림' 클립이 없어 위쪽 경계가 없었다 — 일부러 벌린 반복이 '교정됐어요' 로 읽힘. 재생(2026-09-25): 바닥 창 평균은 정상 반복도 0.34~0.35(09:51 세트)까지 가고 일부러 벌린 반복(0.26~0.30 프레임 최대)과 겹친다 — 이 피처·창으로는 갈라지지 않는다. 0.40 은 정상 위쪽 여유일 뿐 검출 근거가 없다",
                cautions = listOf(PROVISIONAL, "knee_out 바닥 평균은 일부러 벌린 반복과 무릎을 넓게 쓰는 정상 반복을 구분하지 못했다(재생) — 무릎이 발보다 바깥인지(knee_gap ÷ stance) 같은 다른 피처 후보")),
            RepFormCheck("repform|$ex|발 간격|시작", ex, "발 간격(시작)", "발 너비", RuleStatus.BETA, "stance_sh", RepPhase.START, RepFormStat.MEDIAN, RepFormRef.NONE,
                lo = 0.8f, hi = 1.6f, lowText = "발이 어깨보다 좁아요", highText = "발이 어깨보다 많이 넓어요", lowLabel = "좁음", highLabel = "넓음",
                fix = "발을 어깨 너비로 벌려 주세요",
                reason = "사용자 결정: 스쿼트 발 간격은 어깨 너비. 발목 간격 ÷ 어깨 너비(수평, 월드) — 서 있을 때 재므로 깊이에 흔들리지 않는다",
                cautions = listOf(PROVISIONAL, "넓은 스탠스(스모)는 정당한 변형일 수 있다 — 세트 전 안내로만")),
            RepFormCheck("repform|$ex|발 간격", ex, "발 간격(반복)", "발 너비", RuleStatus.BETA, "stance_sh", RepPhase.TOP, RepFormStat.MEDIAN, RepFormRef.START_RATIO,
                lo = 0.8f, hi = 1.25f, lowText = "발 너비가 시작보다 좁아졌어요", highText = "발 너비가 시작보다 넓어졌어요", lowLabel = "좁음", highLabel = "넓음",
                fix = "발을 어깨 너비로 다시 두세요",
                reason = "실기기 10:52 세트: 일부러 넓힌 반복 ×1.28~1.49, 정상 ×0.99~1.13. 골반 정규화(stance_w)는 ×0.78 헛경보 — 어깨 정규화로 교체",
                cautions = listOf(PROVISIONAL)),
            RepFormCheck("repform|$ex|발끝 방향|시작", ex, "발끝 방향(시작)", "발끝", RuleStatus.BETA, "toe_out_maxside", RepPhase.START, RepFormStat.MEDIAN, RepFormRef.NONE,
                lo = -5f, hi = 35f, lowText = "발끝이 안으로 모여 있어요", highText = "발끝이 바깥으로 많이 벌어져 있어요", lowLabel = "안쪽", highLabel = "바깥",
                fix = "발끝을 살짝만 바깥으로 두세요", unit = "°",
                reason = "관용 발끝 각 5~30°. 발목→발끝 수평각(뒤꿈치는 정면·낮은 폰에서 91% 안 보임)",
                cautions = listOf(PROVISIONAL, "발끝(31/32)·발목이 화면 안이어야 한다 — 잘리면 유보")),
            RepFormCheck("repform|$ex|발끝 방향", ex, "발끝 방향(반복)", "발끝", RuleStatus.BETA, "toe_out_maxside", RepPhase.TOP, RepFormStat.MEDIAN, RepFormRef.START_DELTA,
                lo = -8f, hi = 8f, lowText = "발끝이 시작보다 안으로 모였어요", highText = "발끝이 시작보다 바깥으로 벌어졌어요", lowLabel = "안쪽", highLabel = "바깥",
                fix = "발끝을 시작 자세로 되돌리세요", unit = "°",
                reason = "실기기 10:52 세트: 일부러 벌린 반복 +18°, 정상 −3~+6°. 절대 임계 40° 는 놓쳤다(기준 22° 인 사람) — 시작 자세 대비로",
                cautions = listOf(PROVISIONAL)),
        )
    }
}
