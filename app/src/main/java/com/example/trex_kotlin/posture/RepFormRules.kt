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
fun RepFormSummary.ruleResult(rule: PostureRule, viewOk: Boolean = true): RuleResult? {
    val c = checks.firstOrNull { it.id == rule.id } ?: return null
    if (!viewOk) return RuleResult(rule, Verdict.ABSTAIN, null, 0, abstainReason = "촬영 방향 · 정면 아님")
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
    val extreme = bad.mapNotNull { it.value }.let { vs -> if (vs.isEmpty()) null else if (majority == FormDirection.LOW) vs.min() else vs.max() }
    val body = if (k == 0) "${n}회 모두 정상" else "${n}회 중 ${k}회 ${c.label(majority ?: FormDirection.HIGH)}" + (extreme?.let { "(최대 ${c.format(it)})" } ?: "")
    return RuleResult(rule, verdict, k.toFloat() / n, n, direction = if (verdict == Verdict.VIOLATION) Direction.PRIMARY else null,
        measurement = (if (c.ship) "" else "참고 · ") + "${c.bodyPart} · $body")
}


/** 규칙셋에 붙일 rep_form 규칙 — 범위(`PostureScope`)·리포트 행·상태 표시용. 판정은 [RepFormSummary.ruleResult] 가 채운다. */
fun RepFormSpecs.asRules(): List<PostureRule> = byExercise.values.flatten().map { c ->
    PostureRule(
        id = c.id, exercise = c.exercise, condition = c.condition, subtype = null, status = c.status, reason = c.reason,
        feature = "${c.feature}__${c.stat.name.lowercase()}", baseFeature = c.feature, stat = c.stat.name.lowercase(), family = "repform",
        op = if (c.hi != null) ">" else "<", threshold = c.hi ?: c.lo!!, view = "C", viewDesc = "정면",
        cvAuc = Float.NaN, cvBalacc = Float.NaN, sampleN = 0, mirrorSafe = true, cautions = c.cautions,
        viewsOk = setOf("C"), kind = "rep_form",
    )
}

