package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Test

class PostureAssessmentTest {
    private val rule = PostureRule("test", "test", "움직임", null, RuleStatus.SHIP, null,
        "x__max", "x", "max", "", ">", 20f, "C", "", .8f, .8f, 20, true, emptyList())
    private val rules = PostureRuleSet("test", "", listOf(rule))
    private fun sample(x: Float) = PoseSample(true, FloatArray(66), FloatArray(33), mapOf("x" to x), 33, 1, 100, 100)

    @Test fun setupAndCleanupDoNotContaminateTheSharedFinalWindow() {
        val frames = List(12) { sample(if (it == 0 || it >= 10) 100f else 10f) }
        val result = PostureAssessment.evaluate(rules, "test", frames, List(12) { it * 300L }, 0, 2700, emptyList()).single()
        assertEquals(Verdict.OK, result.verdict)
        assertEquals(9, result.sampleCount)
        assertEquals(10f, result.value!!, .001f)
    }

    @Test fun missingAnchorCannotProduceSuccessfulAssessment() {
        val result = PostureAssessment.evaluate(rules, "test", List(16) { sample(10f) }, List(16) { it * 300L },
            Long.MAX_VALUE, 4500, emptyList()).single()
        assertEquals(Verdict.ABSTAIN, result.verdict)
        assertEquals(0, result.sampleCount)
    }

    @Test fun finalAssessmentDoesNotMixCameraContexts() {
        val frames = List(24) { sample(if (it < 12) 100f else 10f) }
        val result = PostureAssessment.evaluate(rules, "test", frames, List(24) { it * 300L },
            0, 6900, emptyList(), contextStartAt = 3600).single()
        assertEquals(Verdict.OK, result.verdict)
        assertEquals(12, result.sampleCount)
        assertEquals(10f, result.value!!, .001f)
    }

    @Test fun newCameraContextWithTooLittleEvidenceMustAbstain() {
        val frames = List(16) { sample(10f) }
        val result = PostureAssessment.evaluate(rules, "test", frames, List(16) { it * 300L },
            0, 4500, emptyList(), contextStartAt = 3900).single()
        assertEquals(Verdict.ABSTAIN, result.verdict)
        assertEquals(3, result.sampleCount)
    }

    @Test fun shortObservationLossMarkerPreventsFalseHoldDurationInFinalReplay() {
        val holdRule = rule.copy(kind = "hold", holdConfig = HoldConfig(.06f, .06f, 2100, 2000))
        val holdRules = PostureRuleSet("test", "", listOf(holdRule))
        val frames = List(16) { if (it == 10) PoseSample.empty() else sample(10f) }
        val times = List(16) { it * 300L }
        val marked = PostureAssessment.evaluate(holdRules, "test", frames, times, -1, 4500, emptyList()).single()
        assertEquals(Verdict.ABSTAIN, marked.verdict)
        assertEquals("측정 시간 부족", marked.abstainReason)
        // 결측 행을 없애면 양쪽 유효 표본의 600ms가 유지 시간으로 합쳐지는 기존 오류를 재현한다.
        val lostMarker = PostureAssessment.evaluate(holdRules, "test", frames.filterIndexed { i, _ -> i != 10 },
            times.filterIndexed { i, _ -> i != 10 }, -1, 4500, emptyList()).single()
        assertEquals(Verdict.OK, lostMarker.verdict)
    }
}
