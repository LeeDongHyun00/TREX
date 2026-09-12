package com.example.trex_kotlin.posture

/** 실시간 세트 마감과 데이터 재생이 공유하는 최종 평가 경로. 원본 프레임은 수정하지 않는다. */
object PostureAssessment {
    fun evaluate(
        rules: PostureRuleSet, exercise: String, samples: List<PoseSample>, times: List<Long>,
        startAt: Long, endAt: Long, reps: List<RepRecord>, baseline: Map<String, Float>? = null,
        minFrames: Int = 8,
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
                else -> result
            }
        }
    }
}
