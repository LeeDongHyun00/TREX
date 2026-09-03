package com.example.trex_kotlin.posture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
        // ---- 바닥 종목 (rules_floor_v0, spec §25) — 아래 일반 패턴보다 먼저 매칭돼야 하는 조건들
        e("고개 젖힘/숙임", "고개", "처음부터 고개가 몸통과 일직선이 아니에요. 고개를 척추 연장선에 두세요.", "고개가 점점 떨어지거나 젖혀져요. 고개를 척추 연장선에 두세요."),
        e("고개 들지 않기", "고개", "처음부터 고개가 흔들리고 있어요. 시선을 한 점에 고정하고 고개를 바닥에 두세요.", "고개 움직임이 점점 커져요. 시선을 고정하세요."),   // 감사 C: 판정 근거 = 흔들림(std)
        e("고개 숙임 여부", "고개", "처음부터 고개를 당겨 올리고 있어요. 머리를 바닥에 편하게 두세요.", "고개가 점점 올라와요. 머리를 바닥에 두세요."),
        e("시선 배꼽", "시선", "처음부터 시선이 배꼽을 벗어나 있어요. 턱을 당겨 배꼽을 보세요.", "시선이 점점 흐트러져요. 턱을 당겨 배꼽을 보세요."),
        e("견갑골이 지면", "상체", "처음부터 상체가 충분히 올라오지 않아요. 머리와 어깨를 함께 말아 올리세요.", "상체가 점점 덜 올라와요. 머리와 어깨를 함께 말아 올리세요."),   // 감사 A: 판정 근거 = 머리 높이(어깨 측정 불가)
        e("무릎부터 어깨까지 일자", "엉덩이", "처음부터 엉덩이가 낮아요. 엉덩이를 들어 무릎-골반-어깨를 일직선으로 만드세요.", "엉덩이가 점점 내려가요. 무릎-골반-어깨 일직선까지 올리세요."),
        e("몸통과 엉덩이의 정렬", "엉덩이", "처음부터 엉덩이가 처지거나 솟아 있어요. 어깨-골반-발목을 일직선으로 유지하세요.", "엉덩이가 점점 처지거나 솟아요. 몸을 일직선으로 되돌리세요."),
        e("허벅지와 종아리 각도", "무릎", "처음부터 무릎 각도가 흔들려요. 무릎 각도를 고정한 채 다리를 올리세요.", "무릎 각도가 점점 풀려요. 각도를 고정한 채 움직이세요."),
        e("다리와 지면 사이", "다리", "처음부터 다리 높이가 맞지 않아요. 다리를 지면에서 한 뼘 높이로 유지하세요.", "다리 높이가 점점 흐트러져요. 지면에서 한 뼘 높이를 유지하세요."),
        e("경추 중립|후인", "몸통", "처음부터 몸 라인이 무너져 있어요. 가슴을 살짝 들어 몸 전체를 일직선으로 유지하세요.", "몸 라인이 점점 무너져요. 가슴을 들어 일직선을 되찾으세요."),   // 감사 D: 판정 근거 = 몸통-골반 라인(목 각도 측정 불가)
        // ---- 서서 하는 종목
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

    /**
     * 감사 B (FLOOR_RULE_AUDIT): 플랭크 '몸통과 엉덩이의 정렬'의 채택 피처(trunk_ankle_ang)는 꺾인 정도만
     * 재고 방향이 없는데, 실측 위반은 엉덩이 솟음 69% / 처짐 31% 로 갈리고 처방이 정반대다(내려라/올려라).
     * 부호 있는 hip_dev_ankle(화면 위 = 양수 = 솟음)을 최근 창에서 읽어 문구를 가른다 — 판정은 그대로,
     * 말만 방향을 얻는다. 값이 없으면(발목 가림) null → 병합 문구 폴백.
     */
    fun directional(rule: PostureRule, recent: FeatureAggregator): CoachCue? {
        if (!rule.condition.contains("몸통과 엉덩이의 정렬")) return null
        val hipDev = recent.stat("hip_dev_ankle", "mean") ?: return null
        return if (hipDev > 0f) {
            CoachCue("엉덩이", "처음부터 엉덩이가 솟아 있어요. 엉덩이를 내려 어깨-골반-발목을 일직선으로 만드세요.", "엉덩이가 점점 솟아요. 엉덩이를 내려 일직선으로 되돌리세요.")
        } else {
            CoachCue("엉덩이", "처음부터 엉덩이가 처져 있어요. 배에 힘을 주고 엉덩이를 올려 일직선으로 만드세요.", "엉덩이가 점점 처져요. 배에 힘을 주고 엉덩이를 올리세요.")
        }
    }

    /**
     * 감사 A/C/D: 규칙이 조건명이 약속하는 것과 **다른 것을 잴 때**, 판정 근거를 정직하게 밝히는
     * 한 줄 주석 (화면 표시용 — TTS 로는 읽지 않는다).
     */
    fun measurementNote(rule: PostureRule): String? = when {
        rule.exercise == "크런치" && rule.condition.contains("견갑골") ->
            "머리 높이로 근사 판정 — 목만 당겨 올리는 동작은 구분하지 못해요"
        rule.exercise == "힙쓰러스트" && rule.condition.contains("고개") ->
            "고개 '흔들림'으로 판정 — 계속 든 채 고정된 고개는 놓칠 수 있어요"
        rule.condition.contains("경추 중립") ->
            "목 각도가 아니라 몸통-골반 라인으로 근사 판정해요"
        else -> null
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

/**
 * 실시간 코칭 엔진 (문구 카탈로그는 [CoachCues]).
 *
 * **앵커**: 초반 창은 세트의 '정상 기준'이다 — 준비 동작이 섞이면 첫 코칭이 오탐이 된다(사용자가 폰을 놓고
 * 걸어와 자세를 잡는 구간이 "처음부터 …" 로 둔갑한다). 그래서 호출부(PostureLive)가 첫 렙 완료 또는 시간
 * 폴백으로 [anchor] 를 부르고, 그 전 프레임은 버린다. [requireAnchor] = true 일 때만 이 문(gate)이 걸린다 —
 * 기본값이 false 인 이유는 기존 호출부(자세 랩 화면)와 기존 테스트가 앵커 없이 즉시 판정하기 때문이다.
 *
 * **베타 침묵**: 베타(미보정) 규칙은 라이브에서 말하지 않는다 — 화면·리포트에는 '참고'로 남지만 음성은
 * 검증된(ship) 규칙만 낸다(§28 실기기 오탐 3건이 전부 베타/미보정 규칙이었다). [speakBeta] 의 기본값은
 * [requireAnchor] 를 따라간다: 라이브 경로(앵커 사용)는 침묵, 기존 호출부는 종전대로 발화.
 * 판정 자체는 그대로다 — [lastStates]·[summarize] 에는 베타 규칙도 전부 들어간다.
 */
class LiveCoach(
    private val ruleSet: PostureRuleSet,
    private val exercise: String,
    private val includeBeta: Boolean = true,
    private val baseline: Map<String, Float>? = null,
    /** true 면 [anchor] 호출 전까지 프레임을 버리고 판정하지 않는다. */
    private val requireAnchor: Boolean = false,
    /**
     * false 면 베타(미보정) 규칙은 **발화 후보에서만** 빠진다 — 판정·`lastStates`·`summarize` 는 그대로라
     * 화면·리포트에는 '참고'로 남는다. 기본값은 종전 동작(true) — 랩 화면처럼 베타를 일부러 듣는 자리가 있다.
     * 실사용 세션은 false 로 준다: §28 실기기 오탐 3건이 전부 베타/미보정 규칙이었다.
     * [requireAnchor] 와는 독립이다 — 한쪽만 켜도 된다.
     */
    private val speakBeta: Boolean = true,
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
    private var anchored = !requireAnchor

    @Volatile
    var lastStates: List<OnsetState> = emptyList()
        private set

    val frameCount: Int get() = frames.size

    /** 초반 창이 실제 운동 구간에 놓였는지. false 면 판정도 발화도 없다. */
    val isAnchored: Boolean get() = anchored

    /**
     * 초반 창을 지금부터 다시 모은다 — 첫 렙이 끝난 시점(또는 시간 폴백)에 호출한다.
     * 준비 동작 프레임을 버려야 "처음부터 …" 가 진짜 세트 초반을 가리킨다.
     * @return 이번 호출로 앵커가 잡혔으면 true, 이미 앵커돼 있었으면 false(아무것도 하지 않는다).
     */
    @Synchronized
    fun anchor(): Boolean {
        if (anchored) return false
        frames.clear()
        earlyAgg.reset()
        streak.clear()
        lastStates = emptyList()
        anchored = true
        return true
        // spokenKind/lastSpokenAt/lastGlobalAt 은 유지 — 앵커 전엔 말한 적이 없고, 억제 상태를 되돌릴 이유도 없다
    }

    fun reset() {
        frames.clear()
        earlyAgg.reset()
        streak.clear()
        lastSpokenAt.clear()
        spokenKind.clear()
        lastGlobalAt = 0L
        lastStates = emptyList()
        anchored = !requireAnchor
    }

    /** 검출된 프레임의 피처를 넣는다 (분석 스레드). 앵커 전 프레임은 준비 동작이라 버린다. */
    @Synchronized
    fun onFrame(features: Map<String, Float>) {
        if (!anchored) return
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
        if (!anchored) return null       // 준비 동작 구간 — lastStates 도 비워 둬야 화면의 붉은 강조가 안 뜬다
        if (frames.size < minFrames) return null
        val recentAgg = recentAggregator()
        val recentRes = ruleSet.evaluate(exercise, recentAgg, includeBeta, minFrames, baseline)
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
                if (s >= persistence && kind != null && speakable(rr.rule) && canSpeak(rr.rule.id, nowMs)) {
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
        val pick = candidate ?: states.firstOrNull {
            it.kind == OnsetKind.RECOVERED && speakable(it.rule) && canSpeak(it.rule.id, nowMs, recovered = true)
        }
        if (pick == null) return null
        val base = CoachCues.cueFor(pick.rule, pick.direction ?: Direction.PRIMARY)
        // 방향 있는 조건(플랭크 정렬)은 최근 창의 부호로 문구를 가른다 — 교정됨 문구는 방향 불필요
        val cue = if (pick.kind != OnsetKind.RECOVERED) CoachCues.directional(pick.rule, recentAgg) ?: base else base
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

    /** 발화 후보 자격 — 베타는 화면·리포트엔 남기고 음성만 막는다. */
    private fun speakable(rule: PostureRule): Boolean = speakBeta || rule.status != RuleStatus.BETA

    private fun canSpeak(ruleId: String, nowMs: Long, recovered: Boolean = false): Boolean {
        if (nowMs - lastGlobalAt < globalGapMs && lastGlobalAt != 0L) return false
        val last = lastSpokenAt[ruleId] ?: return true
        // 교정됨은 직전 발화 직후라도 최소 간격만 지키면 허용
        return nowMs - last >= (if (recovered) globalGapMs else ruleCooldownMs)
    }

    /** 세트 종료 후 요약: 전반 창(첫 W) vs 후반 창(마지막 W) 으로 규칙별 onset 분류. */
    @Synchronized
    fun summarize(): List<OnsetState> {
        if (!anchored) return emptyList()
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

/**
 * Android TTS 래퍼 — 한국어 음성, 초기화 비동기.
 *
 * 세 가지를 함께 다룬다(§31 라이브 신뢰성):
 *  - **준비 전 큐**: 초기화가 비동기라 세트 시작 안내가 통째로 버려졌다. ready 전 요청은 최대 [MAX_PENDING] 건
 *    담아 두고, 초기화 직후 **[PENDING_TTL_MS] 이내 요청만** 순서대로 말한다 — 더 오래된 안내는 이미 지난 상황이라
 *    지금 말하면 오히려 방해다.
 *  - **오디오 포커스**: 헬스장 음악 위로 들려야 한다. 발화 동안만 DUCK 포커스를 잡고 마지막 발화가 끝나면 놓는다.
 *  - **상태 노출**: 한국어 음성이 없으면 영원히 무음인데 화면이 이유를 말하지 못했다 → [unavailableReason].
 */
class SpeechCoach(context: Context) {

    private data class Pending(val text: String, val atMs: Long)

    private val appContext = context.applicationContext
    private val audioManager = runCatching {
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }.getOrNull()

    private var tts: TextToSpeech? = null

    private val lock = Any()
    /** ready 전 대기 큐 (오래된 것부터). */
    private val pending = ArrayList<Pending>()
    /** 아직 끝나지 않은 발화 id — 비면 오디오 포커스를 놓는다. */
    private val speaking = LinkedHashSet<String>()
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var muted: Boolean = false

    @Volatile
    var lastError: String? = null
        private set

    /** 초기화 콜백이 한 번이라도 돌았는지 — "아직 준비 중" 과 "못 씀" 을 가르는 유일한 근거. */
    @Volatile
    private var initialized = false

    @Volatile
    private var unavailable: String? = null

    /** 초기화가 끝났는데 음성을 못 쓰는 이유(한국어). 아직 초기화 중이거나 정상이면 null — 화면 배너용. */
    val unavailableReason: String? get() = if (initialized) unavailable else null

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = finished(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        @Deprecated("API 21 이전 시그니처 — 추상 메서드라 구현은 필요하다", ReplaceWith("onError(utteranceId, errorCode)"))
        override fun onError(utteranceId: String?) = finished(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
    }

    init {
        try {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val r = runCatching { tts?.setLanguage(Locale.KOREAN) }.getOrNull()
                    ready = r != null && r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!ready) {
                        lastError = "한국어 음성 데이터 없음 (기기 TTS 설정에서 한국어 설치)"
                        unavailable = "음성 안내를 쓸 수 없어요 — 기기 설정에서 한국어 음성을 설치하세요"
                    }
                    runCatching { tts?.setSpeechRate(1.05f) }
                    runCatching { tts?.setOnUtteranceProgressListener(progress) }
                } else {
                    lastError = "TTS 초기화 실패"
                    unavailable = "음성 안내를 쓸 수 없어요 — 기기 TTS 엔진을 확인하세요"
                }
                initialized = true
                flushPending()
            }
        } catch (t: Throwable) {
            lastError = "TTS 사용 불가: ${t.message}"
            unavailable = "음성 안내를 쓸 수 없어요 — 기기 TTS 엔진을 확인하세요"
            initialized = true
        }
    }

    /**
     * 말한다. 진행 중인 문장은 끊고 최신 안내를 우선한다(flush).
     * 아직 준비 전이면 큐에 담아 뒀다가 초기화 직후 말한다 — 세트 시작 안내가 통째로 사라지지 않도록.
     */
    fun speak(text: String, flush: Boolean = true) {
        if (muted) return
        if (!ready) {
            // 초기화가 끝났는데도 못 쓰는 상태면 영원히 못 말한다 — 담아 둘 이유가 없다
            if (initialized) return
            synchronized(lock) {
                if (flush) pending.clear()          // flush = "지금 이것만" 이라는 뜻
                pending += Pending(text, System.currentTimeMillis())
                while (pending.size > MAX_PENDING) pending.removeAt(0)
            }
            return
        }
        speakNow(text, flush)
    }

    fun stop() {
        synchronized(lock) {
            pending.clear()
            speaking.clear()
        }
        runCatching { tts?.stop() }
        abandonFocus()
    }

    fun shutdown() {
        synchronized(lock) {
            pending.clear()
            speaking.clear()
        }
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        abandonFocus()
        tts = null
        ready = false
    }

    private fun speakNow(text: String, flush: Boolean) {
        val id = "coach-${System.nanoTime()}"
        synchronized(lock) {
            if (flush) speaking.clear()             // 끊긴 발화는 onDone 이 오지 않는다
            speaking += id
        }
        requestFocus()
        val rc = runCatching {
            tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }.getOrNull()
        // 발화가 시작조차 못 하면 리스너가 안 오므로 여기서 포커스를 정리한다
        if (rc != TextToSpeech.SUCCESS) finished(id)
    }

    /** 초기화 직후 대기 큐를 흘려보낸다. 오래된 요청은 이미 지난 상황이라 버린다. */
    private fun flushPending() {
        val now = System.currentTimeMillis()
        val due = synchronized(lock) {
            val out = pending.filter { now - it.atMs <= PENDING_TTL_MS }
            pending.clear()
            out
        }
        if (!ready || muted) return
        for (p in due) speakNow(p.text, flush = false)
    }

    private fun finished(utteranceId: String?) {
        val id = utteranceId ?: return
        val idle = synchronized(lock) {
            speaking.remove(id)
            speaking.isEmpty()
        }
        if (idle) abandonFocus()
    }

    /** 발화 동안만 DUCK 포커스 — 음악을 끄지 않고 낮춘다. 실패해도 발화는 그대로 진행한다. */
    private fun requestFocus() {
        val am = audioManager ?: return
        val req = synchronized(lock) {
            if (focusRequest != null) return
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .build()
                .also { focusRequest = it }
        }
        runCatching { am.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        val req = synchronized(lock) { focusRequest.also { focusRequest = null } } ?: return
        runCatching { am.abandonAudioFocusRequest(req) }
    }

    companion object {
        /** 대기 큐 최대 건수 — 밀린 안내를 몰아서 읽으면 오히려 방해다. */
        const val MAX_PENDING = 3
        /** 대기 요청 유효 시간(ms) — 이보다 오래된 안내는 이미 지난 상황. */
        const val PENDING_TTL_MS = 10_000L
    }
}
