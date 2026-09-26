package com.example.trex_kotlin.posture

/** 실시간 세트 마감과 데이터 재생이 공유하는 최종 평가 경로. 원본 프레임은 수정하지 않는다. */
object PostureAssessment {
    fun evaluate(
        rules: PostureRuleSet, exercise: String, samples: List<PoseSample>, times: List<Long>,
        startAt: Long, endAt: Long, reps: List<RepRecord>, baseline: Map<String, Float>? = null,
        minFrames: Int = 8,
        /** 반복별 검사 요약(spec §62a) — kind=rep_form 규칙의 결과를 채운다. null 이면 그 규칙은 유보. */
        repForm: RepFormSummary? = null,
        /** 세트의 촬영 방향이 반복 검사의 전제(정면)에 맞는가. 아니면 rep_form 결과는 유보. */
        repFormViewOk: Boolean = true,
        /** 세트의 추정 뷰 등급 글자(C·B·D…). 알면 반복 검사마다 자기 뷰(§62c)로 가른다 — UNKNOWN·미추정은 null. */
        repFormViewLetter: String? = null,
    ): List<RuleResult> {
        require(samples.size == times.size)
        val agg = FeatureAggregator()
        samples.forEachIndexed { i, s -> if (times[i] > startAt && times[i] <= endAt) agg.add(s.features) }
        val t0 = times.firstOrNull() ?: 0L
        // 정렬 검사는 자체 준비 자세 게이트를 사용한다. 초기 앵커/기준이 만들어지기 전의 오류도 평가한다.
        val alignment = PlankAlignmentTracker(rules.rulesFor(exercise))
        samples.forEachIndexed { i, s -> if (times[i] <= endAt) alignment.add(times[i]-t0,s.features) }
        val alignmentResults = alignment.results().associateBy { it.rule.id }
        return rules.evaluate(exercise, agg, true, minFrames, baseline).map { result ->
            when (result.rule.kind) {
                "alignment" -> alignmentResults[result.rule.id] ?: result
                "hold" -> {
                    val tracker = result.rule.holdConfig?.let { HoldTracker(it) }
                    samples.forEachIndexed { i, s ->
                        if (times[i] > startAt && times[i] <= endAt) tracker?.add(times[i] - t0, s.features[result.rule.baseFeature])
                    }
                    tracker?.snapshot()?.let { FloorTemporal.holdResult(result.rule, it) } ?: result
                }
                "rep" -> FloorTemporal.repResult(result.rule, reps.filter { it.tMs <= endAt })
                // 반복 창 검사(§62a) — 평가기가 없었던 세트(다른 종목·이전 로그)는 유보 그대로
                "rep_form" -> repForm?.ruleResult(result.rule, repFormViewOk, repFormViewLetter) ?: result.copy(abstainReason = "반복 창 측정 없음")
                else -> result
            }
        }
    }
}
