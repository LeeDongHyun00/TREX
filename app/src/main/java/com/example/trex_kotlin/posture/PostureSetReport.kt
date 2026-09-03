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
) {
    /** 화면 라벨 — OnsetState.label 과 같은 어휘, 세트 중간 위반(kind null + VIOLATION)만 "위반" 추가. */
    val label: String
        get() = when (kind) {
            OnsetKind.HABIT -> "처음부터$dirSuffix"
            OnsetKind.DRIFT -> "점점 흐트러짐$dirSuffix"
            OnsetKind.RECOVERED -> "교정됨"
            null -> when (overall) {
                Verdict.VIOLATION -> "위반$dirSuffix"
                Verdict.ABSTAIN -> "유보"
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
    /** 렙 카운터 미적용 종목이면 null. */
    val repsValid: Int?,
    val repsPartial: Int?,
    val tempoMs: Long?,
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

    /** 기록 화면 한 줄. */
    val summaryLine: String = when (mode) {
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
                if ((repsPartial ?: 0) > 0) add("파셜 $repsPartial")
            } else {
                add("기록됨")
            }
            tempoText?.let { add("템포 $it") }
            driftHeadline?.let { add("${it.bodyPart} 점점") }
        }.joinToString(" · ")
    }

    /** 세트 종료 발화 한두 문장. */
    val voiceLine: String = when (mode) {
        CoachMode.COACH -> when (verdict) {
            SetVerdict.UNJUDGED -> "이번 세트는 화면에 충분히 잡히지 않아 자세를 판정하지 못했어요."
            SetVerdict.CLEAN -> if (betaOnly) "이번 세트, 검증 중인 항목 기준으로는 이상 없었어요." else "이번 세트 깨끗했어요."
            SetVerdict.RECOVERED -> "좋아요, ${headline!!.bodyPart} 자세가 세트 후반에 교정됐어요."
            SetVerdict.ISSUE -> headline!!.let { h -> if (h.fix.isBlank()) "${h.observation}." else "${h.observation}. 다음엔 ${h.fix}." }
            SetVerdict.REFERENCE -> "${candidates.first().observation}. 아직 검증 중인 항목이라 참고만 하세요."
        }
        CoachMode.TRACK -> buildList {
            if (reps != null) add("${reps}렙" + if ((repsPartial ?: 0) > 0) " 파셜 $repsPartial" else "")
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
        ): PostureSetReport {
            val onsetById = onset.associateBy { it.rule.id }
            val outcomes = results.map { rr ->
                val st = onsetById[rr.rule.id]
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
                    observation = observation,
                    fix = fix,
                    note = CoachCues.measurementNote(rr.rule),
                    cvAuc = rr.rule.cvAuc,
                )
            }
            // 안정 정렬: 랭크 → ship 우선 → AUC 높은 순. 같은 키면 규칙셋 순서(status, -auc) 그대로.
            val sorted = outcomes.sortedWith(compareBy<RuleOutcome>({ it.rank }, { it.beta }, { -it.cvAuc }))
            return PostureSetReport(
                setId = setId, exercise = exercise, workoutName = workoutName, mode = mode, frames = frames,
                baselineActive = baselineActive, items = sorted, repsValid = repsValid, repsPartial = repsPartial, tempoMs = tempoMs,
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
