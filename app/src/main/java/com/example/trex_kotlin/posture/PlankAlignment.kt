package com.example.trex_kotlin.posture

import kotlin.math.*

// PlankGeometry(순수 기하)·AlignmentConfig 는 PlankGeometry.kt 로 옮겼다(spec §99 — 재생기 컴파일).

data class AlignmentItem(val rule: PostureRule, val value: Float?, val verdict: Verdict, val side: Int = 0, val recovered: Boolean = false) {
    val head get() = rule.baseFeature != PlankGeometry.HIP
    val points get() = if (head) setOf(0,7,8,11,12) else setOf(11,12,23,24,27,28)
    val message get() = when {
        recovered -> if (head) "고개가 몸통 정렬 범위로 돌아왔어요" else "골반이 어깨와 발목 사이의 정렬 범위로 돌아왔어요"
        head && side > 0 -> "고개가 들려 있어요. 시선을 바닥으로 두고 목을 몸통과 나란히 해주세요"
        head -> "고개가 많이 숙여져 있어요. 목을 몸통과 나란히 해주세요"
        side > 0 -> "골반이 올라가 있어요. 어깨와 발목을 잇는 선에 맞춰 조금 내려주세요"
        else -> "골반이 처져 있어요. 어깨와 발목을 잇는 선에 맞춰 조금 올려주세요"
    }
    val detail get() = "${when(rule.baseFeature) { PlankGeometry.HEAD -> "고개 방향"; PlankGeometry.NECK -> "목 기울기"; else -> "골반" }} · " + when (verdict) {
        Verdict.ABSTAIN -> if (value == null) "관측 불가" else "지속 여부 확인 중"
        Verdict.VIOLATION -> "정렬 범위 이탈"
        Verdict.OK -> "관측한 정렬 범위 안"
    } + (value?.let { " (${PostureRule.fmt(it)}${if(head) "°" else ""})" } ?: "")
}

/**
 * @property held 이 프레임이 유지 시계가 확인한 플랭크 칸이었는가(spec §99) — false 면 항목이 전부 유보이고 화면은 정렬 대신 시계 상태를 보인다.
 */
data class AlignmentSnapshot(val items: List<AlignmentItem> = emptyList(), val placementReady: Boolean = false, val visibleSide: Int? = null,
                             val held: Boolean = true) {
    /** 판정한 정렬 항목이 전부 범위 안 — 항목 수는 규칙셋이 정한다(floor_v0.5 에서 목 기울기가 exclude 로 빠져 2개). */
    val referenceEligible get() = items.isNotEmpty() && items.all { it.verdict == Verdict.OK }
    val issue get() = items.firstOrNull { it.verdict == Verdict.VIOLATION }
    val recovery get() = items.firstOrNull { it.recovered }
}

/**
 * 같은 엔진을 실시간·세트 종료·재생에서 쓴다. 초기 자세를 빼지 않는 절대 정렬 검사이며 전부 beta다.
 *
 * 바닥 계열 배선(spec §99, 설계 §5):
 *  - **HOLD 칸만** — [add] 의 `gate` 가 false(유지 시계가 플랭크로 확인하지 않은 칸 — 무릎을 대거나 엎드려 쉬거나 화면 밖)면 그 칸은 관측이 없는 것으로 끊는다.
 *    무릎을 댄 프레임의 골반 오프셋은 양쪽으로 오독된다(AIHub 진입·이탈 +0.18 vs 폰 −0.12~−0.18). 세트 마감은 `PostureAssessment` 가 시계 구간으로 같은 칸을 고른다.
 *  - **측면 게이트** — 프레임에 `fc_yaw` 가 있으면 ≤ [SIDE_YAW_MAX] 일 때만 판정한다(띠는 측면 C 에서만 쟀다). 없으면(이전 로그·`FloorChain` 없는 경로) 종전처럼 준비 조건만.
 *  - **고개 |값| > [HEAD_MAX_DEG]** 는 얼굴 방향이 뒤집힌 관측 실패라(09-23 −154.88° 가 '최악값' 으로 남았다) 값이 없는 것으로 버린다 — worst 에도 남지 않는다.
 */
class PlankAlignmentTracker(rules: List<PostureRule>) {
    private class State {
        var previous: Long? = null; var side = 0; var since = 0L; var active = 0
        var samples = 0; var ok = 0; var violations = 0; var first: Long? = null; var worst: Float? = null
        fun interrupt() { previous = null; side = 0; active = 0 }
    }
    private val rules = rules.filter { it.kind == "alignment" && it.alignmentConfig != null && it.status != RuleStatus.EXCLUDE }
    private val states = this.rules.associate { it.id to State() }
    private var previousSide: Float? = null
    var snapshot = AlignmentSnapshot(); private set

    @Synchronized fun add(now: Long, features: Map<String, Float>, gate: Boolean = true): AlignmentSnapshot {
        val cameraSide = features[PlankGeometry.SIDE]
        if (cameraSide != previousSide) states.values.forEach { it.interrupt() }
        previousSide = cameraSide
        val yaw = features[FloorChain.YAW]
        val side = yaw == null || (yaw.isFinite() && yaw <= SIDE_YAW_MAX)
        val placed = features[PlankGeometry.READY] == 1f && side
        val ready = placed && gate
        snapshot = AlignmentSnapshot(rules.map { rule ->
            val s = states.getValue(rule.id); val c = rule.alignmentConfig!!
            val value = features[rule.baseFeature]?.takeIf { ready && it.isFinite() && !(rule.baseFeature == PlankGeometry.HEAD && abs(it) > HEAD_MAX_DEG) }
            if (value == null) {
                s.interrupt(); return@map AlignmentItem(rule,null,Verdict.ABSTAIN)
            }
            if (s.previous?.let { now <= it || now-it > 750 } == true) s.interrupt()
            // 복귀 시에는 경계의 80% 안까지 들어와야 한다. 문턱 근처의 흔들림을 복귀로 말하지 않는다.
            val direction = when { value > c.upper -> 1; value < c.lower -> -1
                s.active > 0 && value > c.upper*.8f -> 1
                s.active < 0 && value < c.lower*.8f -> -1; else -> 0 }
            if (s.previous == null || direction != s.side) s.since = now
            s.previous = now; s.side = direction; s.samples++
            if (s.worst == null || abs(value) > abs(s.worst!!)) s.worst = value
            if (now-s.since < c.sustainMs) return@map AlignmentItem(rule,value,Verdict.ABSTAIN,direction)
            val recovered = direction == 0 && s.active != 0
            if (direction != 0) {
                if (s.active != direction) { s.violations++; if(s.first == null) s.first = s.since }
                s.active = direction
            } else { s.ok++; s.active = 0 }
            AlignmentItem(rule,value,if(direction == 0) Verdict.OK else Verdict.VIOLATION,direction,recovered)
        },placed,cameraSide?.toInt(),held = gate)
        return snapshot
    }

    @Synchronized fun results(): List<RuleResult> = rules.map { r ->
        val s = states.getValue(r.id)
        val verdict = when { s.violations > 0 -> Verdict.VIOLATION; s.ok > 0 -> Verdict.OK; else -> Verdict.ABSTAIN }
        RuleResult(r,verdict,s.worst,s.samples,
            abstainReason = if(verdict == Verdict.ABSTAIN) "측면 관측과 지속 정렬 확인 필요" else null,
            measurement = "참고 · ${r.condition}: 지속 이탈 ${s.violations}회" +
                (s.first?.let { " · 첫 이탈 ${it/1000}초" } ?: "") +
                if(verdict == Verdict.ABSTAIN) " · 판정 없음" else " · 초기 자세와 무관한 정렬 기준")
    }

    companion object {
        /** 측면 게이트(`FloorChain.YAW`) — 판별·자세 띠는 AIHub 측면(C)에서만 쟀다(설계 §2 #8). */
        const val SIDE_YAW_MAX = 0.15f
        /** 이보다 큰 |고개각| 은 관측 실패(`FloorChain.HEAD_MAX_DEG` 와 같다). */
        const val HEAD_MAX_DEG = 90f
    }
}
