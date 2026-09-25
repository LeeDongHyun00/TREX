package com.example.trex_kotlin.posture

import java.util.Locale

/**
 * 세트 종료 리포트 (spec §30) — 라이브 코칭이 남긴 두 결과(세트 전체 집계 판정 + 초반/후반 창 onset 분류)를
 * 한 번 합쳐서 "무엇을, 어떤 순서로, 어떤 말로" 보여 줄지 정한다. 완료 화면·기록 화면·세트 종료 발화가 전부 이 객체 하나를 읽는다.
 *
 * 설계 근거:
 *  - **베타 규칙은 헤드라인이 될 수 없다.** 실기기 검증(spec §28)에서 나온 오탐 3건이 전부 베타/미보정 규칙이었다.
 *    베타 위반은 후보(highlights)에는 남기되 verdict 는 REFERENCE("참고") 로 낮추고, 발화도 "검증 중" 이라고 밝힌다.
 *  - **TRACK(기록) 모드는 accuracy 가 항상 null.** 모집단(AIHub) 기준 판정을 숙련자에게 점수로 보이면 스타일을 오류로
 *    채점하는 범주 오류가 된다(spec §29). HABIT("처음부터")은 demoted 로 접어 두고, 세트 내 변화(DRIFT)만 헤드라인으로 남긴다.
 *  - **판정한 것만 센다.** accuracy 의 분모는 shipJudged(검증된 규칙의 OK+VIOLATION) 이지 전체 규칙 수가 아니다. 유보(ABSTAIN)된
 *    규칙을 "정상"으로 세면 화면에 덜 잡힌 세트일수록 점수가 올라가는 거짓 신호가 되고, 베타 규칙을 세면 "참고만 하세요" 라고 말한
 *    항목이 점수를 깎는 자기모순이 된다. 그래서 UI 는 "shipOk/shipJudged" 분수 표기로 분모를 드러내고, 베타는 "참고 n건" 으로 따로
 *    세며, judged==0 이면 점수·"깨끗" 모두 금지(UNJUDGED). 베타만 판정된 세트(바닥 종목 전부)는 점수 없이 "검증 중 기준" 이라고 밝힌다.
 */

enum class SetVerdict { CLEAN, ISSUE, RECOVERED, REFERENCE, UNJUDGED }

/**
 * 렙 ROM(가동범위) 판정을 **어떤 확신으로** 말할 수 있는가 (spec §58, 원칙 #1·#2). 신호의 ROM 설정은 세트 안에서 바뀌지 않으므로 세트 단위다.
 *  - [NONE]      ROM 기준 없음(예: 카운트 신호를 knee_mean 으로 바꾸며 기준을 뗀 런지). 판정하지 않았으므로 '범위 미판정' —
 *                유효로 세지도, 유효라고 보이지도 않는다.
 *  - [REFERENCE] 기준은 있으나 판별력 미검증(`romValidated = false`). 화면에 '참고 · 범위 미달 N회' 로만 보이고,
 *                '무효'·'파셜' 이라는 말도, 음성도 없다(베타 규칙과 같은 정책 — §28 오탐이 전부 미보정 기준에서 나왔다).
 *  - [VALIDATED] AIHub ROM 조건으로 판별력이 검증된 기준. 지금까지의 유효/무효(COACH)·파셜(TRACK) 표기와 발화를 유지한다.
 * 어느 단계든 수행 횟수는 줄이지 않는다 — ROM 은 표시 정책이지 카운트가 아니다(진행·자동 넘김은 검출 전체, spec §42).
 * 좌우 짝 단위([RepUnit.SIDE_PAIR], 런지류)에서 한 회의 판정은 두 걸음(사이클)의 판정을 합친 것이다(`RepUnitAccumulator.combine` — 한쪽이라도 미달이면 미달).
 * [VALIDATED] 는 걸음 하나에서 검증된 기준이라 짝 단위의 오판정률은 따로 재지 않았다(걸음마다 p 면 짝은 약 2p) — 짝의 '무효' 는
 * "두 걸음 중 하나라도 검증 기준에 못 미쳤다" 는 뜻이다(spec §59).
 */
enum class RepRomTier {
    NONE, REFERENCE, VALIDATED;

    /** 세트 로그 값. */
    val key: String get() = name.lowercase()

    companion object {
        /** `RepSignal.isValidRep` 가 null 을 돌려주는 설정(임계값 없음·방향 불명)은 NONE. */
        fun of(signal: RepSignal): RepRomTier = when {
            signal.romThreshold == null || (signal.romDirection != "min" && signal.romDirection != "max") -> NONE
            signal.romValidated -> VALIDATED
            else -> REFERENCE
        }
    }
}

/** 사용자 자가 라벨 — 좋았음 / 의도적 변형(스타일) / 무너짐. 직렬화 값은 [key]. */
enum class FormLabel(val key: String, val displayName: String) {
    GOOD("good", "좋았음"), INTENDED("intended", "의도적 변형"), BROKE("broke", "무너짐");

    companion object {
        fun from(s: String?): FormLabel? = values().firstOrNull { it.key == s }
    }
}

/**
 * 규칙 하나의 세트 결과. [overall] 은 세트 전체 집계 판정, [kind] 는 초반 8프레임 vs 후반 8프레임 onset 분류(없으면 null).
 * [observation]/[fix] 는 끝 마침표 없는 문장 조각 — 발화·요약 줄이 각자 문장부호를 붙인다.
 */
data class RuleOutcome(
    val ruleId: String,
    val condition: String,
    val bodyPart: String,
    val beta: Boolean,
    val overall: Verdict,
    val kind: OnsetKind?,
    val direction: Direction?,
    val observation: String,
    val fix: String,
    val note: String?,
    val cvAuc: Float,
    /** 유보 이유 (spec §33 촬영 방향 / §28e 기준선). 유보가 아니거나 피처 부재면 null. */
    val abstainReason: String? = null,
) {
    /** 화면 라벨 — OnsetState.label 과 같은 어휘, 세트 중간 위반(kind null + VIOLATION)만 "위반" 추가. */
    val label: String
        get() = when (kind) {
            OnsetKind.HABIT -> "처음부터$dirSuffix"
            OnsetKind.DRIFT -> "점점 흐트러짐$dirSuffix"
            OnsetKind.RECOVERED -> "교정됨"
            null -> when (overall) {
                Verdict.VIOLATION -> "위반$dirSuffix"
                Verdict.ABSTAIN -> abstainReason?.let { "유보 · $it" } ?: "유보"
                Verdict.OK -> "정상"
            }
        }

    private val dirSuffix: String get() = if (direction == Direction.OPPOSITE) " (반대측)" else ""

    /**
     * 랭킹 — 낮을수록 먼저. 0: 세트 전체도 위반이고 onset 도 잡힘(가장 확실) / 1: 점점(피로형, 두 모드 모두 가치)
     * / 2: 처음부터(습관형) / 3: 전체 위반이지만 창에서는 안 잡힘(세트 중간 위반) / 4: 교정됨 / 9: 후보 아님.
     */
    val rank: Int
        get() = when {
            overall == Verdict.VIOLATION && (kind == OnsetKind.HABIT || kind == OnsetKind.DRIFT) -> 0
            kind == OnsetKind.DRIFT -> 1
            kind == OnsetKind.HABIT -> 2
            overall == Verdict.VIOLATION && kind == null -> 3
            kind == OnsetKind.RECOVERED -> 4
            else -> 9
        }
}

data class PostureSetReport(
    val setId: String,
    val exercise: String,
    val workoutName: String,
    val mode: CoachMode,
    val frames: Int,
    val baselineActive: Boolean,
    /** 랭킹순 정렬된 전체 규칙 (ABSTAIN 포함). */
    val items: List<RuleOutcome>,
    /**
     * 렙 카운터 미적용 종목이면 null. [repsValid] + [repsPartial] = 검출 전체(진행·자동 넘김에 쓰는 수, spec §42) — **표시 단위**([repUnit])다.
     * [repsValid] 는 "ROM 미달로 판정되지 않은 렙" 이다 — ROM 을 판정하지 않은 렙도 여기 들어가므로 이름만 보고 '유효' 라고 말하지 않는다.
     * 좌우 짝 단위에서 1회의 판정은 두 쪽을 합친 것이다(한쪽이라도 미달이면 미달, 아니고 한쪽이라도 미판정이면 미판정 — `RepUnitAccumulator.combine`).
     */
    val repsValid: Int?,
    /** ROM 기준 미달로 판정된 렙 수. 어떤 말로 보일지는 [repRom] 이 정한다(NONE 이면 항상 0). */
    val repsPartial: Int?,
    val tempoMs: Long?,
    val measurements: List<String> = emptyList(),
    /** 이 세트 카운터 신호의 ROM 판정 단계 (spec §58). null = 모름 → [RepRomTier.NONE] 으로 다룬다. */
    val repRom: RepRomTier? = null,
    /**
     * 표시 횟수 단위(사용자 결정 2026-09-24). null = 모름 → 사이클 단위로 다룬다. [repsValid]·[repsPartial]·[tempoMs] 가 이 단위다
     * (템포 = 1회 완료 시각의 간격 — 좌우 짝이면 두 걸음).
     */
    val repUnit: RepUnit? = null,
    /** 세트 끝에 반대쪽을 못 채운 한쪽이 남았다 — 세지 않았다. [RepUnit.SIDE_PAIR] 일 때만 의미가 있다. */
    val repHalfPending: Boolean = false,
) {
    /** 실제로 판정한 규칙 수(OK+VIOLATION). accuracy 의 분모 — 유보를 정상으로 세지 않는다. */
    val judged: Int = items.count { it.overall == Verdict.OK || it.overall == Verdict.VIOLATION }
    val abstained: Int = items.count { it.overall == Verdict.ABSTAIN }
    val okCount: Int = items.count { it.overall == Verdict.OK }

    /** 검증된(ship) 규칙만의 판정 수·정상 수 — 점수의 분모/분자. 베타는 [betaJudged] 로 따로 센다. */
    val shipJudged: Int = items.count { !it.beta && (it.overall == Verdict.OK || it.overall == Verdict.VIOLATION) }
    val shipOk: Int = items.count { !it.beta && it.overall == Verdict.OK }
    val betaJudged: Int = judged - shipJudged

    /** 판정은 있는데 전부 베타(바닥 종목처럼 규칙이 전부 미보정) — 점수 없이 "검증 중 기준" 이라고 밝혀야 하는 세트. */
    val betaOnly: Boolean = judged > 0 && shipJudged == 0

    /**
     * 후보(랭크<9), 랭킹순. TRACK 이면 **세트 내 변화(DRIFT/RECOVERED)만** 후보 — HABIT 도, 창에서 안 잡힌 세트 전체 위반(kind null)도
     * 모집단 임계 기준 판정이라 본인 스타일일 수 있다(§29). 그 둘은 [demoted] 로.
     */
    val candidates: List<RuleOutcome> = items.filter { it.rank < 9 && !(mode == CoachMode.TRACK && it.kind != OnsetKind.DRIFT && it.kind != OnsetKind.RECOVERED) }

    /** TRACK 에서 후보에서 뺀 항목 — "측정 기록" 으로 접어 보여 주되 점수·지적으로 쓰지 않는다. */
    val demoted: List<RuleOutcome> = items.filter { it.rank < 9 && mode == CoachMode.TRACK && it.kind != OnsetKind.DRIFT && it.kind != OnsetKind.RECOVERED }

    /** 첫 non-beta 후보. 베타는 헤드라인 불가(§28 오탐 전부 베타/미보정). */
    val headline: RuleOutcome? = candidates.firstOrNull { !it.beta }

    val highlights: List<RuleOutcome> = candidates.take(3)

    val verdict: SetVerdict = run {
        val nonBeta = candidates.filter { !it.beta }
        when {
            judged == 0 -> SetVerdict.UNJUDGED
            exercise in FloorTemporal.exercises -> SetVerdict.REFERENCE
            nonBeta.any { it.rank <= 3 } -> SetVerdict.ISSUE
            nonBeta.isNotEmpty() -> SetVerdict.RECOVERED
            candidates.isNotEmpty() -> SetVerdict.REFERENCE
            else -> SetVerdict.CLEAN
        }
    }

    /** COACH 이고 검증된 규칙 판정이 하나라도 있을 때만 점수. TRACK 은 모집단 판정을 점수로 보이지 않는다(§29). 베타는 분모에 안 들어간다. */
    val accuracy: Int? = if (mode == CoachMode.COACH && shipJudged > 0) Math.round(100f * shipOk / shipJudged) else null

    private val reps: Int? = repsValid?.let { it + (repsPartial ?: 0) }
    private val tempoText: String? = tempoMs?.let { String.format(Locale.US, "%.1f초", it / 1000f) }
    private val driftHeadline: RuleOutcome? = headline?.takeIf { it.kind == OnsetKind.DRIFT }

    /**
     * 표시·발화에 쓰는 ROM 단계. 구성을 모르면(null) NONE — 모르는 판정을 유효로 말하지 않는다.
     * 바닥 종목은 규칙이 전부 beta 라 검증 기준이 붙어 와도 참고 이상으로 말하지 않는다(세션 구성에서는 생기지 않는 조합 — 방어).
     */
    val romTier: RepRomTier = (repRom ?: RepRomTier.NONE).let {
        if (it == RepRomTier.VALIDATED && exercise in FloorTemporal.exercises) RepRomTier.REFERENCE else it
    }
    private val romShort: Int = repsPartial ?: 0

    /** TRACK 요약·펼침에서 렙 수 뒤에 붙는 ROM 조각(없으면 null). 음성에는 쓰지 않는다 — 파셜 발화는 검증 기준에서만([voiceLine]). */
    private val trackRomText: String? = when (romTier) {
        RepRomTier.VALIDATED -> if (romShort > 0) "파셜 $romShort" else null
        RepRomTier.REFERENCE -> if (romShort > 0) "참고 · 범위 미달 ${romShort}회" else null
        RepRomTier.NONE -> "범위 미판정"
    }

    /**
     * 좌우 짝 단위의 렙 줄 꼬리 — 맨 숫자가 걸음 수로 읽히지 않게 단위를 밝히고("좌우 한 번씩 = 1회"), 세트 끝에 남은 한쪽이 있으면 세지 않았다고 적는다.
     * '무효 2'·'범위 미달 2회' 도 걸음이 아니라 짝의 수다. 화면 전용 — 음성([voiceLine])·기록 한 줄([summaryLine])에는 붙이지 않는다(수는 HUD 와 같은 단위).
     */
    private val unitTail: List<String> = if (repUnit == RepUnit.SIDE_PAIR)
        listOfNotNull(SIDE_PAIR_UNIT_HINT, SIDE_PAIR_HALF_UNCOUNTED.takeIf { repHalfPending }) else emptyList()

    /**
     * 완료 화면 펼침의 렙 한 줄(렙 카운터 미적용이면 null). 수는 검출 전체이고, ROM 은 [romTier] 가 허락하는 말로만 붙인다.
     * COACH 는 검증 기준에서만 "렙 유효 n · 무효 m"(미달 0 도 적는다 — 기존 형식), 미검증 기준은 '참고 · 범위 미달 m회'(0 도 적는다),
     * 기준 없음은 '참고 · 검출 n회 · 범위 미판정' — 카운트 자체가 beta 라(HUD '자동 횟수 · 참고') 검증 기준이 없는 줄은 '참고' 로 연다.
     * TRACK 은 "n렙" + ROM 조각(미달 0 은 적지 않는다).
     */
    val repDetailLine: String? = reps?.let { n ->
        val head = when (mode) {
            CoachMode.COACH -> when (romTier) {
                RepRomTier.VALIDATED -> "렙 유효 ${n - romShort} · 무효 $romShort"
                RepRomTier.REFERENCE -> "참고 · 검출 ${n}회 · 범위 미달 ${romShort}회"
                RepRomTier.NONE -> "참고 · 검출 ${n}회 · 범위 미판정"
            }
            CoachMode.TRACK -> listOfNotNull("${n}렙", trackRomText).joinToString(" · ")
        }
        (listOf(head) + unitTail).joinToString(" · ")
    }

    /** 기록 화면 한 줄. */
    val summaryLine: String = if (exercise in FloorTemporal.exercises || judged == 0 && measurements.isNotEmpty()) "참고 측정 · 자세 확정 판정 없음" else when (mode) {
        CoachMode.COACH -> when (verdict) {
            SetVerdict.UNJUDGED -> "자세 판정 없음"
            SetVerdict.CLEAN -> if (betaOnly) "참고 기준 이상 없음" else "자세 깨끗"
            SetVerdict.RECOVERED -> "${headline!!.bodyPart} 교정됨"
            SetVerdict.ISSUE -> "${headline!!.bodyPart} · ${headline.label}"
            SetVerdict.REFERENCE -> "참고 ${candidates.size}건"
        }
        CoachMode.TRACK -> buildList {
            if (reps != null) {
                add("${reps}렙")
                trackRomText?.let { add(it) }
            } else {
                add("기록됨")
            }
            tempoText?.let { add("템포 $it") }
            driftHeadline?.let { add("${it.bodyPart} 점점") }
        }.joinToString(" · ")
    }

    /** 세트 종료 발화 한두 문장. */
    val voiceLine: String = if (exercise in FloorTemporal.exercises) "세트를 기록했어요. 참고 측정은 화면에서 확인해 주세요." else when (mode) {
        CoachMode.COACH -> when (verdict) {
            SetVerdict.UNJUDGED -> "이번 세트는 화면에 충분히 잡히지 않아 자세를 판정하지 못했어요."
            SetVerdict.CLEAN -> if (betaOnly) "이번 세트, 검증 중인 항목 기준으로는 이상 없었어요." else "이번 세트 깨끗했어요."
            SetVerdict.RECOVERED -> "좋아요, ${headline!!.bodyPart} 자세가 세트 후반에 교정됐어요."
            SetVerdict.ISSUE -> headline!!.let { h -> if (h.fix.isBlank()) "${h.observation}." else "${h.observation}. 다음엔 ${h.fix}." }
            SetVerdict.REFERENCE -> "${candidates.first().observation}. 아직 검증 중인 항목이라 참고만 하세요."
        }
        CoachMode.TRACK -> buildList {
            // 파셜은 검증된 ROM 기준에서만 말한다 — 미검증 기준·기준 없음은 화면에만(참고·미판정) 남기고 음성으로는 렙 수만.
            if (reps != null) add("${reps}렙" + if (romTier == RepRomTier.VALIDATED && romShort > 0) " 파셜 $romShort" else "")
            tempoText?.let { add("템포 $it") }
            driftHeadline?.let { add(it.observation) }
        }.let { if (it.isEmpty()) "기록됐어요." else it.joinToString(", ") + "." }
    }

    companion object {
        /**
         * results 와 onset 을 rule.id 로 조인. onset 에 없는 규칙은 kind=null, results 에 없는 onset 은 무시
         * (판정의 정본은 세트 전체 집계 — 창 분류는 그 위에 얹는 부가 정보다).
         */
        fun build(
            setId: String,
            exercise: String,
            workoutName: String,
            mode: CoachMode,
            frames: Int,
            baselineActive: Boolean,
            results: List<RuleResult>,
            onset: List<OnsetState>,
            repsValid: Int?,
            repsPartial: Int?,
            tempoMs: Long?,
            measurements: List<String> = emptyList(),
            repRom: RepRomTier? = null,
            repUnit: RepUnit? = null,
            repHalfPending: Boolean = false,
        ): PostureSetReport {
            val onsetById = onset.associateBy { it.rule.id }
            val outcomes = results.map { rr ->
                val st = if (rr.rule.kind == "window") onsetById[rr.rule.id] else null
                val kind = st?.kind
                val direction = rr.direction ?: st?.direction
                val cue = CoachCues.cueFor(rr.rule, direction ?: Direction.PRIMARY)
                val (observation, fix) = when (kind) {
                    OnsetKind.DRIFT -> splitCue(cue.drift)
                    OnsetKind.RECOVERED -> cue.recovered.trimEnd().removeSuffix(".") to splitCue(cue.habit).second
                    OnsetKind.HABIT -> splitCue(cue.habit)
                    null -> splitCue(cue.habit).let { (o, f) -> o.removePrefix("처음부터 ") to f }
                }
                RuleOutcome(
                    ruleId = rr.rule.id,
                    condition = rr.rule.condition,
                    bodyPart = cue.bodyPart,
                    beta = rr.rule.status == RuleStatus.BETA,
                    overall = rr.verdict,
                    kind = kind,
                    direction = direction,
                    observation = rr.measurement ?: observation,
                    // 반복 창 검사(§62a)는 검사가 가진 교정문 — 관찰문은 measurement 에 이미 방향이 들어 있다
                    fix = when (rr.rule.kind) { "window" -> fix; "rep_form" -> RepFormSpecs.checkOf(rr.rule.id)?.fix ?: fix; else -> "" },
                    note = rr.measurement ?: CoachCues.measurementNote(rr.rule),
                    cvAuc = rr.rule.cvAuc,
                    abstainReason = rr.abstainReason,
                )
            }
            // 안정 정렬: 랭크 → ship 우선 → AUC 높은 순. 같은 키면 규칙셋 순서(status, -auc) 그대로.
            val sorted = outcomes.sortedWith(compareBy<RuleOutcome>({ it.rank }, { it.beta }, { -it.cvAuc }))
            return PostureSetReport(
                setId = setId, exercise = exercise, workoutName = workoutName, mode = mode, frames = frames,
                baselineActive = baselineActive, items = sorted, measurements = measurements, repsValid = repsValid, repsPartial = repsPartial, tempoMs = tempoMs,
                repRom = repRom, repUnit = repUnit, repHalfPending = repHalfPending,
            )
        }

        /**
         * "A. B." → ("A", "B"). 분리 불가면 (문장, "") — 두 경우 모두 끝 마침표는 뗀다(호출부가 문장부호를 붙이므로).
         * 첫 ". " 에서만 가른다: 교정문 안의 마침표는 문장 끝 하나뿐이다.
         */
        fun splitCue(sentence: String): Pair<String, String> {
            val s = sentence.trim()
            val i = s.indexOf(". ")
            if (i < 0) return s.removeSuffix(".") to ""
            val first = s.substring(0, i).trim()
            val second = s.substring(i + 2).trim().removeSuffix(".")
            return first to second
        }
    }
}
