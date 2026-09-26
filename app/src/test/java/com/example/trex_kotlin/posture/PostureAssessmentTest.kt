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
}
