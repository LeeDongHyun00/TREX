package com.example.trex_kotlin.posture

import kotlin.math.ceil

/**
 * 반복별 검사(`RepForm.kt`)와 규칙셋의 연결 — 앱 전용. `RepForm.kt` 는 재생기가 함께 컴파일하므로 `PostureRule`·`RuleResult`(PostureRules.kt,
 * 안드로이드·org.json 의존)를 쓰는 부분을 여기로 뗐다.
 */

/**
 * 규칙셋의 rep_form 규칙(`RepFormSpecs.asRules`)에 대응하는 세트 결과. 판정한 반복이 2회 미만이면 유보, 위반 반복이 max(2, 34%) 이상이면 위반
 * (바닥 kind=rep 규칙과 같은 규약). 정면(C)이 아니면 유보 — 검사는 정면 기하를 전제한다.
 */
fun RepFormSummary.ruleResult(rule: PostureRule, viewOk: Boolean = true, viewLetter: String? = null): RuleResult? {
    val c = checks.firstOrNull { it.id == rule.id } ?: return null
    // 뷰는 검사마다 다르다(§62c: 컬 앞 이탈·몸통은 사선 B/D, 벌어짐은 B/C/D, 스쿼트는 C). 반복마다 그 반복 창의 뷰로 이미 유보했으면(§62c 후속 6)
    // 세트 뷰로 다시 거르지 않는다 — 방향이 섞인 세트(11:14: 정면 반복 + 옆으로 돌아선 구간)를 통째로 유보하지 않게. 반복 뷰가 없으면(방향 피처 없음) 종전처럼
    // 세트 뷰 등급(viewLetter)으로 검사별, 그것도 모르면 viewOk 로
    val perRepView = c.phase != RepPhase.START && reps.any { it.view != null }
    val ok = perRepView || (if (viewLetter != null) viewLetter in c.views else viewOk)
    if (!ok) return RuleResult(rule, Verdict.ABSTAIN, null, 0, abstainReason = "촬영 방향 · ${viewDescOf(c.views)} 아님")
    if (c.phase == RepPhase.START) {
        val o = start.firstOrNull { it.check.id == c.id }
            ?: return RuleResult(rule, Verdict.ABSTAIN, null, 0, abstainReason = "시작 자세를 못 잡음")
        val text = if (o.direction == null) "정상 범위" else c.text(o.direction)
        return RuleResult(rule, o.verdict, o.value, 1, direction = if (o.verdict == Verdict.VIOLATION) Direction.PRIMARY else null,
            abstainReason = o.abstainReason, measurement = "시작 자세 · ${c.bodyPart} ${o.value?.let(c::format) ?: "-"} · $text")
    }
    val judged = outcomesOf(c).filter { it.verdict == Verdict.OK || it.verdict == Verdict.VIOLATION }
    val n = judged.size
    if (n < 2) return RuleResult(rule, Verdict.ABSTAIN, null, n, abstainReason = "완료 반복 2회 필요")
    val bad = judged.filter { it.verdict == Verdict.VIOLATION }
    val k = bad.size
    val verdict = if (k >= maxOf(2, ceil(0.34 * n).toInt())) Verdict.VIOLATION else Verdict.OK
    val majority = bad.groupingBy { it.direction }.eachCount().maxByOrNull { it.value }?.key
    val extreme = bad.filter { it.direction == majority }.mapNotNull { it.value }.let { vs -> if (vs.isEmpty()) null else if (majority == FormDirection.LOW) vs.min() else vs.max() }
    val body = if (k == 0) "${n}회 모두 정상" else "${n}회 중 " + directionCounts(c, bad) + (extreme?.let { "(최대 ${c.format(it)})" } ?: "")
    return RuleResult(rule, verdict, k.toFloat() / n, n, direction = if (verdict == Verdict.VIOLATION) Direction.PRIMARY else null,
        measurement = (if (c.ship) "" else "참고 · ") + "${c.bodyPart} · $body")
}


/** 검사 뷰 집합의 사람 말 — 정면(C 포함) / 옆(SIDE 만) / 앞 비스듬히(B/D). */
private fun viewDescOf(views: Set<String>): String = when {
    "C" in views -> "정면"
    views.isNotEmpty() && views.all { it.startsWith("SIDE") } -> "옆"
    else -> "앞 비스듬히"
}

/** 규칙셋에 붙일 rep_form 규칙 — 범위(`PostureScope`)·리포트 행·상태 표시용. 판정은 [RepFormSummary.ruleResult] 가 채운다. */
fun RepFormSpecs.asRules(): List<PostureRule> = byExercise.values.flatten().map { c ->
    PostureRule(
        id = c.id, exercise = c.exercise, condition = c.condition, subtype = null, status = c.status, reason = c.reason,
        feature = "${c.feature}__${c.stat.name.lowercase()}", baseFeature = c.feature, stat = c.stat.name.lowercase(), family = "repform",
        op = if (c.hi != null) ">" else "<", threshold = c.hi ?: c.lo!!, view = c.views.first(), viewDesc = viewDescOf(c.views),
        cvAuc = Float.NaN, cvBalacc = Float.NaN, sampleN = 0, mirrorSafe = true, cautions = c.cautions,
        viewsOk = c.views, kind = "rep_form",
    )
}

/** §93: 새 기하로도 직접 판단하지 않는 범위를 범위 카드에 보존한다. */
fun fourExerciseExcludedRules(): List<PostureRule> = FourExercise.entries.flatMap { ex ->
    mapOf(
        "척추 분절 정렬·근육 활성" to "MediaPipe에는 척추 분절점·근전도 정보가 없습니다. 몸통 기울기는 이를 대신하지 않습니다.",
        "지지발 접지·미끄러짐" to "골반 중심 월드 좌표는 지면 좌표가 아닙니다. 발 압력·실제 접촉·카메라 이동과 미끄러짐을 구별하지 않습니다.",
        "무릎과 발의 국소 정렬" to "교차·측방·들기별 국소 발 축과 가림 검증 전이므로 스쿼트 무릎 임계를 적용하지 않습니다.",
        "목 분절 정렬·목 당기는 힘" to "머리와 손의 점 위치만으로 목 분절이나 당기는 힘을 알 수 없습니다.",
    ).map { (condition, reason) ->
        PostureRule("four_scope|${ex.title}|$condition", ex.title, condition, null, RuleStatus.EXCLUDE, reason,
            "unobservable", "unobservable", "none", "scope", ">", Float.NaN, "C", "미관측", Float.NaN, Float.NaN, 0, false,
            listOf(reason), kind = "scope")
    }
}

