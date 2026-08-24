package com.example.trex_kotlin.posture

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 실시간 코칭 — "어디가, 처음부터인지 점점인지" 를 말로 알려 준다 (연구: ERROR_ONSET.md).
 *
 * 원리: 세트 **초반 창**(첫 W프레임)과 **최근 창**(마지막 W프레임)을 같은 규칙으로 따로 평가한다.
 *  - 둘 다 위반            → HABIT   "처음부터 …"            (AIHub 연기 위반과 같은 유형 — 임계값이 검증된 영역)
 *  - 초반 정상 → 최근 위반  → DRIFT   "점점 … 흐트러지고 있어요"
 *  - 위반이었다가 최근 정상 → RECOVERED "좋아요, … 교정됐어요"
 * 8프레임 창으로도 규칙 AUC 가 유지된다(AIHub GT: 첫 8프레임 0.912 vs 전체 16프레임 0.903).
 *
 * 발화 억제: 같은 규칙은 persistence 회 연속 위반일 때만, 규칙당 쿨다운, 전체 최소 간격 — 한 번에 한 문장만 고른다.
 * 임계값은 습관형(AIHub)으로 보정된 값이므로 DRIFT 도 같은 임계값을 쓴다. 피로형 전용 임계값은 실측 로그 후 재보정 대상.
 */

enum class OnsetKind { HABIT, DRIFT, RECOVERED }

data class CoachCue(val bodyPart: String, val habit: String, val drift: String) {
    val recovered: String get() = "좋아요, $bodyPart 자세가 교정됐어요."
}

/** 조건명(+하위유형) → 한국어 코칭 문구. 라벨명이 아니라 **실제 연기된 편차**(DEFINITION_QUALITY 요건 3) 기준으로 쓴다. */
object CoachCues {

    private data class Entry(val pattern: Regex, val cue: CoachCue)

    private fun e(pattern: String, part: String, habit: String, drift: String) = Entry(Regex(pattern), CoachCue(part, habit, drift))

    // 척추 하위유형 (subtype 우선)
    private val spineBySubtype: Map<String, CoachCue> = mapOf(
        "flexion" to CoachCue("등·허리", "처음부터 등이 말려 있어요. 가슴을 펴고 허리를 중립으로 세우세요.", "등이 점점 말리고 있어요. 가슴을 펴고 허리를 다시 세우세요."),
        "lateral" to CoachCue("몸통", "처음부터 몸통이 옆으로 기울어 있어요. 양 어깨 높이를 맞추세요.", "몸통이 점점 옆으로 기울고 있어요. 양 어깨 높이를 맞추세요."),
        "extension" to CoachCue("허리", "처음부터 허리가 과하게 젖혀져 있어요. 갈비뼈를 내리고 배에 힘을 주세요.", "허리가 점점 젖혀지고 있어요. 갈비뼈를 내리고 배에 힘을 주세요."),
        "cervical" to CoachCue("고개", "처음부터 고개 방향이 틀어져 있어요. 시선을 정면에 두세요.", "고개 방향이 점점 틀어져요. 시선을 정면에 두세요."),
        "forward_lean" to CoachCue("상체", "처음부터 상체가 앞으로 숙여져 있어요. 몸통을 세우세요.", "상체가 점점 앞으로 숙여져요. 몸통을 다시 세우세요."),
        "lumbar_swing" to CoachCue("허리", "처음부터 허리 반동을 쓰고 있어요. 허리를 고정하고 팔로만 움직이세요.", "허리 반동이 점점 커져요. 허리를 고정하세요."),
        "all" to CoachCue("척추", "처음부터 척추 중립이 무너져 있어요. 가슴을 펴고 몸통을 세우세요.", "척추 중립이 점점 무너지고 있어요. 가슴을 펴고 몸통을 다시 세우세요."),
    )

    private val entries: List<Entry> = listOf(
        e("발과 무릎의 방향|발 무릎 방향|몸통 발 무릎|몸통 앞발 앞무릎|무릎.*방향", "무릎", "처음부터 무릎이 안쪽으로 모여 있어요. 무릎을 발끝 방향으로 벌리세요.", "무릎이 점점 안쪽으로 모여요. 무릎을 발끝 방향으로 벌리세요."),
        e("발바닥 지면|뒤꿈치", "발", "처음부터 뒤꿈치가 들려 있어요. 발바닥 전체로 바닥을 누르세요.", "뒤꿈치가 점점 들려요. 발바닥 전체로 바닥을 누르세요."),
        e("고개 정면|시선 정면|시선 방향|고개 젖힘", "시선", "처음부터 시선이 정면을 벗어나 있어요. 정면을 보세요.", "시선이 점점 흔들려요. 정면을 보세요."),
        e("시선 위쪽", "시선", "처음부터 시선이 아래예요. 시선을 위로 두세요.", "시선이 점점 내려가요. 시선을 위로 두세요."),
        e("고개 안 젖힘", "고개", "처음부터 고개를 젖히고 있어요. 턱을 당기세요.", "고개가 점점 젖혀져요. 턱을 당기세요."),
        e("무릎 반동", "무릎", "처음부터 무릎 반동을 쓰고 있어요. 다리를 고정하고 팔로만 움직이세요.", "무릎 반동이 점점 커져요. 다리를 고정하세요."),
        e("상체 반동|몸통 흔들림|몸통 벤치", "몸통", "처음부터 몸통이 흔들리고 있어요. 몸통을 고정하세요.", "몸통이 점점 흔들려요. 몸통을 고정하세요."),
        e("궤적.*밀착|상체 밀착|몸 밀착", "바", "처음부터 바가 몸에서 떨어져 있어요. 바를 몸에 붙여서 움직이세요.", "바가 점점 몸에서 멀어져요. 바를 몸에 붙이세요."),
        e("손목의 중립|손목의 각도", "손목", "처음부터 손목이 꺾여 있어요. 손목을 곧게 펴세요.", "손목이 점점 꺾여요. 손목을 곧게 펴세요."),
        e("동시에 펴짐", "엉덩이·무릎", "처음부터 무릎이 먼저 펴지고 있어요. 엉덩이와 무릎을 같이 펴세요.", "무릎이 점점 먼저 펴져요. 엉덩이와 무릎을 같이 펴세요."),
        e("전완 지면과 수직", "팔꿈치", "처음부터 팔꿈치가 앞으로 벌어져 있어요. 전완을 수직으로 세우세요.", "팔꿈치가 점점 앞으로 벌어져요. 전완을 수직으로 세우세요."),
        e("견갑대 고정|견갑골 하강|어깨 으쓱|승모근|어깨와 귀 사이", "어깨", "처음부터 어깨가 올라가 있어요. 어깨를 내리고 귀에서 멀어지게 하세요.", "어깨가 점점 올라가요. 어깨를 내리세요."),
        e("숄더패킹", "어깨", "처음부터 어깨가 풀려 있어요. 견갑골을 아래로 고정하세요.", "어깨가 점점 풀려요. 견갑골을 아래로 고정하세요."),
        e("앞다리 무릎 각도", "앞무릎", "처음부터 앞무릎 각도가 안 나와요. 앞다리를 90도까지 굽히세요.", "앞무릎 각도가 점점 얕아져요. 앞다리를 90도까지 굽히세요."),
        e("뒤다리 무릎 각도|뒷다리", "뒷무릎", "처음부터 뒷다리가 펴져 있어요. 뒷무릎을 바닥 쪽으로 더 굽히세요.", "뒷무릎이 점점 펴져요. 뒷무릎을 더 굽히세요."),
        e("상체 살짝 숙임", "상체", "처음부터 상체가 너무 서 있어요. 상체를 살짝 앞으로 숙이세요.", "상체가 점점 서요. 상체를 살짝 앞으로 숙이세요."),
        e("상체 과도한 숙임|상체.*숙임/젖힘|상체 정면 균형|체스트 업", "상체", "처음부터 상체 각도가 무너져 있어요. 몸통을 세우고 가슴을 펴세요.", "상체가 점점 숙여지거나 젖혀져요. 몸통을 다시 세우세요."),
        e("상체 뒤로 숙이지|상체 과도한 젖힘|적당한 상체 젖힘", "상체", "처음부터 상체가 뒤로 젖혀져 있어요. 몸통을 세우세요.", "상체가 점점 뒤로 젖혀져요. 몸통을 세우세요."),
        e("팔꿈치 각도 90도|팔꿈치 90도", "팔꿈치", "처음부터 팔꿈치 각도가 얕아요. 팔꿈치를 90도까지 굽히세요.", "팔꿈치 각도가 점점 얕아져요. 90도까지 굽히세요."),
        e("팔꿈치 살짝 구부린채|팔꿈치 각도 유지|상.?과 전완의 각도", "팔꿈치", "처음부터 팔꿈치 각도가 고정되지 않았어요. 팔꿈치를 살짝 굽힌 채 고정하세요.", "팔꿈치 각도가 점점 풀려요. 살짝 굽힌 채 고정하세요."),
        e("팔꿈치 위치 고정|팔꿈치.*몸통|몸통-팔꿈치|양 팔꿈치 모아", "팔꿈치", "처음부터 팔꿈치가 몸통에서 벌어져 있어요. 팔꿈치를 몸 옆에 붙이세요.", "팔꿈치가 점점 벌어져요. 팔꿈치를 몸 옆에 붙이세요."),
        e("상완의 외회전", "팔꿈치", "처음부터 팔꿈치가 모여 있어요. 팔꿈치를 바깥으로 벌리세요.", "팔꿈치가 점점 모여요. 팔꿈치를 바깥으로 벌리세요."),
        e("팔꿈치가 손목 리드", "팔꿈치", "처음부터 손목이 팔꿈치보다 높아요. 팔꿈치가 먼저 올라가게 하세요.", "팔꿈치가 점점 처져요. 팔꿈치를 손목보다 높게 드세요."),
        e("양 손이 머리 뒤", "손", "처음부터 손이 머리 뒤에 없어요. 양손을 머리 뒤에 두세요.", "손이 점점 머리에서 떨어져요. 양손을 머리 뒤에 두세요."),
        e("무릎 충분히 올라", "무릎", "처음부터 무릎이 낮아요. 무릎을 더 높이 올리세요.", "무릎이 점점 낮아져요. 무릎을 더 높이 올리세요."),
        e("무릎이 몸통 측면", "무릎", "처음부터 무릎이 앞으로 올라와요. 무릎을 몸통 옆으로 올리세요.", "무릎이 점점 앞으로 와요. 무릎을 옆으로 올리세요."),
        e("무릎과 팔꿈치가 충분히", "무릎·팔꿈치", "처음부터 무릎과 팔꿈치가 멀어요. 둘을 더 가까이 붙이세요.", "무릎과 팔꿈치가 점점 멀어져요. 더 가까이 붙이세요."),
        e("두 다리 사이 모아|양무릎 교차", "다리", "처음부터 다리가 벌어져 있어요. 두 다리를 모으세요.", "다리가 점점 벌어져요. 두 다리를 모으세요."),
        e("무릎 구부린채 고정|무릎 살짝 구부린|무릎 너무 굽히지", "무릎", "처음부터 무릎 각도가 유지되지 않아요. 무릎을 살짝 굽힌 채 고정하세요.", "무릎이 점점 펴지거나 굽어요. 살짝 굽힌 채 고정하세요."),
        e("손과 이마 동일선상|손목 어깨 또는 턱선", "손", "처음부터 손이 낮아요. 손을 이마 높이까지 올리세요.", "손 높이가 점점 내려가요. 이마 높이까지 올리세요."),
        e("덤벨 어깨높이|덤벨 가슴높이", "덤벨", "처음부터 덤벨 높이가 맞지 않아요. 가슴에서 어깨 높이 사이로 움직이세요.", "덤벨 높이가 점점 벗어나요. 가슴과 어깨 높이 사이로 움직이세요."),
        e("등 아치", "등", "처음부터 등의 아치가 없어요. 가슴을 들고 등을 살짝 젖히세요.", "등의 아치가 점점 무너져요. 가슴을 들어 아치를 유지하세요."),
        e("손의 위치 가슴 중앙", "손", "처음부터 손 위치가 가슴 중앙에서 벗어나 있어요.", "손 위치가 점점 벗어나요. 가슴 중앙에 두세요."),
        e("가슴의 충분한 이동", "가슴", "처음부터 가슴이 충분히 내려가지 않아요. 가슴을 바닥 가까이 내리세요.", "가슴이 점점 덜 내려가요. 바닥 가까이 내리세요."),
        e("팔 긴장|다리 긴장|긴장 유지", "팔·다리", "처음부터 이완 때 힘이 풀려요. 내려놓을 때도 긴장을 유지하세요.", "이완 때 점점 힘이 풀려요. 천천히 내려놓으세요."),
        e("팔 당김보다 다리|팔 펴짐이 다리", "순서", "처음부터 팔과 다리 순서가 바뀌어 있어요. 다리로 밀고 나서 팔을 당기세요.", "순서가 점점 흐트러져요. 다리 먼저, 그다음 팔이에요."),
        e("허리 휨", "허리", "처음부터 허리가 휘어 있어요. 배에 힘을 주고 허리를 곧게.", "허리가 점점 휘어요. 배에 힘을 주세요."),
    )

    /**
     * 반대측(OPPOSITE) 문구 — opposite_guard 가 붙은 조건들 (spec §23).
     * 기본 방향과 반대의 편차를 말한다: 무릎 '안쪽'(기본) ↔ '바깥'(반대), 고개 '숙임' ↔ '젖힘' 등.
     */
    private val oppositeEntries: List<Entry> = listOf(
        e("발과 무릎의 방향|무릎.*방향", "무릎", "처음부터 무릎이 발끝보다 바깥으로 벌어져 있어요. 무릎을 발끝 방향에 맞추세요.", "무릎이 점점 바깥으로 벌어져요. 무릎을 발끝 방향에 맞추세요."),
        e("고개 정면", "고개", "처음부터 고개가 뒤로 젖혀져 있어요. 턱을 살짝 당기고 정면을 보세요.", "고개가 점점 젖혀져요. 턱을 당기고 정면을 보세요."),
        e("시선 정면", "시선", "처음부터 시선이 아래로 떨어져 있어요. 고개를 들어 정면을 보세요.", "시선이 점점 아래로 떨어져요. 고개를 들어 정면을 보세요."),
        e("고개 안 젖힘", "고개", "처음부터 고개가 푹 숙여져 있어요. 시선을 자연스럽게 앞에 두세요.", "고개가 점점 숙여져요. 시선을 앞에 두세요."),
        e("상체.*숙임/젖힘|상체 과도한 숙임", "상체", "처음부터 상체가 과하게 앞으로 숙여져 있어요. 가슴을 들고 몸통을 세우세요.", "상체가 점점 앞으로 숙여져요. 가슴을 들고 몸통을 세우세요."),
    )

    fun cueFor(rule: PostureRule, direction: Direction? = Direction.PRIMARY): CoachCue {
        if (direction == Direction.OPPOSITE) {
            for (en in oppositeEntries) if (en.pattern.containsMatchIn(rule.condition)) return en.cue
            val desc = rule.oppositeGuard?.desc?.takeIf { it.isNotBlank() } ?: "반대 방향"
            return CoachCue(rule.condition, "처음부터 반대 방향으로 벗어나 있어요 ($desc). 자세를 확인하세요.", "반대 방향으로 점점 벗어나요 ($desc). 자세를 확인하세요.")
        }
        val st = rule.subtype
        if (rule.condition.contains("척추")) {
            spineBySubtype[st ?: "all"]?.let { return it }
            return spineBySubtype.getValue("all")
        }
        for (en in entries) if (en.pattern.containsMatchIn(rule.condition)) return en.cue
        return CoachCue(rule.condition, "처음부터 '${rule.condition}' 조건을 벗어나 있어요.", "'${rule.condition}' 조건에서 점점 벗어나고 있어요.")
    }
}

data class CoachEvent(
    val rule: PostureRule,
    val kind: OnsetKind,
    val message: String,
    val atMs: Long,
    val earlyValue: Float?,
    val recentValue: Float?,
    /** 위반 방향 (RECOVERED 이벤트는 null). */
    val direction: Direction? = null,
)

/** 규칙 하나의 현재 상태 (UI 표시·세트 요약용). */
data class OnsetState(
    val rule: PostureRule,
    val early: Verdict,
    val recent: Verdict,
    val earlyValue: Float?,
    val recentValue: Float?,
    val kind: OnsetKind?,
    /** 최근 창 위반의 방향 (위반이 아니면 null). */
    val direction: Direction? = null,
) {
    val label: String
        get() = when (kind) {
            OnsetKind.HABIT -> "처음부터$dirSuffix"
            OnsetKind.DRIFT -> "점점 흐트러짐$dirSuffix"
            OnsetKind.RECOVERED -> "교정됨"
            null -> if (recent == Verdict.ABSTAIN) "유보" else "정상"
        }

    private val dirSuffix: String get() = if (direction == Direction.OPPOSITE) " (반대측)" else ""
}

class LiveCoach(
    private val ruleSet: PostureRuleSet,
    private val exercise: String,
    private val includeBeta: Boolean = true,
    private val baseline: Map<String, Float>? = null,
    private val windowFrames: Int = 8,
    private val minFrames: Int = 8,
    private val persistence: Int = 2,
    private val ruleCooldownMs: Long = 12_000L,
    private val globalGapMs: Long = 4_000L,
) {
    private val frames = ArrayList<Map<String, Float>>()
    private val earlyAgg = FeatureAggregator()
    private val streak = HashMap<String, Int>()
    private val lastSpokenAt = HashMap<String, Long>()
    private val spokenKind = HashMap<String, OnsetKind>()
    private var lastGlobalAt = 0L

    @Volatile
    var lastStates: List<OnsetState> = emptyList()
        private set

    val frameCount: Int get() = frames.size

    fun reset() {
        frames.clear()
        earlyAgg.reset()
        streak.clear()
        lastSpokenAt.clear()
        spokenKind.clear()
        lastGlobalAt = 0L
        lastStates = emptyList()
    }

    /** 검출된 프레임의 피처를 넣는다 (분석 스레드). */
    @Synchronized
    fun onFrame(features: Map<String, Float>) {
        frames += features
        if (frames.size <= windowFrames) earlyAgg.add(features)
    }

    private fun recentAggregator(): FeatureAggregator {
        val agg = FeatureAggregator()
        val from = maxOf(0, frames.size - windowFrames)
        for (i in from until frames.size) agg.add(frames[i])
        return agg
    }

    private fun aggregatorOf(range: IntRange): FeatureAggregator {
        val agg = FeatureAggregator()
        for (i in range) if (i in frames.indices) agg.add(frames[i])
        return agg
    }

    private fun classify(early: Verdict, recent: Verdict, ruleId: String): OnsetKind? = when {
        recent == Verdict.VIOLATION && early == Verdict.VIOLATION -> OnsetKind.HABIT
        recent == Verdict.VIOLATION && early == Verdict.OK -> OnsetKind.DRIFT
        recent == Verdict.VIOLATION -> OnsetKind.HABIT          // 초반 창이 아직 안 찼으면 = 세트 초반 위반 = 처음부터
        recent == Verdict.OK && spokenKind[ruleId] in setOf(OnsetKind.HABIT, OnsetKind.DRIFT) -> OnsetKind.RECOVERED
        else -> null
    }

    /**
     * 최근 창을 평가하고, 말할 이벤트가 있으면 1개 반환. 분석 스레드에서 프레임마다 호출해도 된다(억제 로직이 빈도를 제어).
     */
    @Synchronized
    fun evaluate(nowMs: Long): CoachEvent? {
        if (frames.size < minFrames) return null
        val recentRes = ruleSet.evaluate(exercise, recentAggregator(), includeBeta, minFrames, baseline)
        val earlyRes = ruleSet.evaluate(exercise, earlyAgg, includeBeta, minFrames, baseline).associateBy { it.rule.id }
        val states = ArrayList<OnsetState>(recentRes.size)
        var candidate: OnsetState? = null
        var candidateStreak = 0
        for (rr in recentRes) {
            val er = earlyRes[rr.rule.id]
            val early = er?.verdict ?: Verdict.ABSTAIN
            val kind = classify(early, rr.verdict, rr.rule.id)
            val st = OnsetState(rr.rule, early, rr.verdict, er?.value, rr.value, kind, direction = rr.direction)
            states += st
            if (rr.verdict == Verdict.VIOLATION) {
                val s = (streak[rr.rule.id] ?: 0) + 1
                streak[rr.rule.id] = s
                if (s >= persistence && kind != null && canSpeak(rr.rule.id, nowMs)) {
                    if (candidate == null || s > candidateStreak || (s == candidateStreak && rr.rule.cvAuc > candidate!!.rule.cvAuc)) {
                        candidate = st
                        candidateStreak = s
                    }
                }
            } else {
                streak[rr.rule.id] = 0
            }
        }
        lastStates = states
        // 위반 후보가 없으면 '교정됨' 한 번
        val pick = candidate ?: states.firstOrNull { it.kind == OnsetKind.RECOVERED && canSpeak(it.rule.id, nowMs, recovered = true) }
        if (pick == null) return null
        val cue = CoachCues.cueFor(pick.rule, pick.direction ?: Direction.PRIMARY)
        val msg = when (pick.kind) {
            OnsetKind.HABIT -> cue.habit
            OnsetKind.DRIFT -> cue.drift
            OnsetKind.RECOVERED -> cue.recovered
            null -> return null
        }
        lastSpokenAt[pick.rule.id] = nowMs
        lastGlobalAt = nowMs
        spokenKind[pick.rule.id] = pick.kind
        return CoachEvent(pick.rule, pick.kind, msg, nowMs, pick.earlyValue, pick.recentValue, direction = pick.direction)
    }

    private fun canSpeak(ruleId: String, nowMs: Long, recovered: Boolean = false): Boolean {
        if (nowMs - lastGlobalAt < globalGapMs && lastGlobalAt != 0L) return false
        val last = lastSpokenAt[ruleId] ?: return true
        // 교정됨은 직전 발화 직후라도 최소 간격만 지키면 허용
        return nowMs - last >= (if (recovered) globalGapMs else ruleCooldownMs)
    }

    /** 세트 종료 후 요약: 전반 창(첫 W) vs 후반 창(마지막 W) 으로 규칙별 onset 분류. */
    @Synchronized
    fun summarize(): List<OnsetState> {
        if (frames.size < minFrames) return emptyList()
        val earlyRes = ruleSet.evaluate(exercise, aggregatorOf(0 until windowFrames), includeBeta, minFrames, baseline).associateBy { it.rule.id }
        val lateRes = ruleSet.evaluate(exercise, aggregatorOf(maxOf(0, frames.size - windowFrames) until frames.size), includeBeta, minFrames, baseline)
        return lateRes.map { lr ->
            val er = earlyRes[lr.rule.id]
            val early = er?.verdict ?: Verdict.ABSTAIN
            val kind = when {
                lr.verdict == Verdict.VIOLATION && early == Verdict.VIOLATION -> OnsetKind.HABIT
                lr.verdict == Verdict.VIOLATION && early == Verdict.OK -> OnsetKind.DRIFT
                lr.verdict == Verdict.VIOLATION -> OnsetKind.HABIT
                lr.verdict == Verdict.OK && early == Verdict.VIOLATION -> OnsetKind.RECOVERED
                else -> null
            }
            OnsetState(lr.rule, early, lr.verdict, er?.value, lr.value, kind, direction = lr.direction)
        }
    }
}

/** Android TTS 래퍼 — 한국어 음성, 초기화 비동기. 음성이 없으면 텍스트만 표시하도록 ready=false. */
class SpeechCoach(context: Context) {
    private var tts: TextToSpeech? = null

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var muted: Boolean = false

    @Volatile
    var lastError: String? = null
        private set

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val r = tts?.setLanguage(Locale.KOREAN)
                    ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!ready) lastError = "한국어 음성 데이터 없음 (기기 TTS 설정에서 한국어 설치)"
                    tts?.setSpeechRate(1.05f)
                } else {
                    lastError = "TTS 초기화 실패"
                }
            }
        } catch (t: Throwable) {
            lastError = "TTS 사용 불가: ${t.message}"
        }
    }

    /** 말한다. 진행 중인 문장은 끊고 최신 안내를 우선한다(flush). */
    fun speak(text: String, flush: Boolean = true) {
        if (!ready || muted) return
        try {
            tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "coach-${System.nanoTime()}")
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        try { tts?.stop() } catch (_: Throwable) {}
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        ready = false
    }
}
