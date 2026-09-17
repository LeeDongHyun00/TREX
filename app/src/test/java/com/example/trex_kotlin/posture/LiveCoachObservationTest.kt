package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** 실제 시간·관측 경계·안내 전달을 함께 검증하는 스트림 회귀 테스트. */
class LiveCoachObservationTest {
    private val rule = PostureRule(
        id = "x", exercise = "운동", condition = "관측 항목", subtype = null,
        status = RuleStatus.SHIP, reason = null, feature = "x__mean", baseFeature = "x", stat = "mean",
        family = "test", op = "<", threshold = .5f, view = "", viewDesc = "", cvAuc = .9f,
        cvBalacc = .9f, sampleN = 10, mirrorSafe = true, cautions = emptyList(),
    )

    private inner class Feed {
        val coach = LiveCoach(PostureRuleSet("t", "d", listOf(rule)), "운동", globalGapMs = 0)
        var now = 0L
        fun tick(value: Float, dt: Long = 300): CoachEvent? {
            now += dt
            coach.onFrame(now, mapOf("x" to value))
            return coach.evaluate(now)
        }
        fun issue(): CoachEvent {
            var event: CoachEvent? = null
            repeat(9) { event = tick(0f) ?: event }
            return checkNotNull(event)
        }
    }

    @Test fun longGapStartsNewWindowAndKeepsHistoryWithoutReportingRecovery() {
        val f = Feed()
        val issue = f.issue()
        assertTrue(f.coach.onFeedbackDelivered(issue, f.now))
        assertNull(f.tick(1f, 2_000))
        assertEquals(10, f.coach.frameCount)
        assertEquals(1, f.coach.currentFrameCount)
        assertTrue(f.coach.lastStates.isEmpty())
        assertTrue(f.coach.summarize().isEmpty())
        repeat(8) { assertNull(f.tick(1f)) }
        assertEquals(Verdict.OK, f.coach.lastStates.single().recent)
        assertNull(f.coach.summarize().single().kind)
    }

    @Test fun duplicateAndBackwardFramesAreRejectedWithoutMovingTimestampWatermark() {
        val f = Feed()
        f.issue()
        assertFalse(f.coach.onFrame(f.now, mapOf("x" to 1f)))
        assertFalse(f.coach.onFrame(f.now - 300, mapOf("x" to 1f)))
        assertEquals(9, f.coach.frameCount)
        assertEquals(0, f.coach.currentFrameCount)
        assertNull(f.tick(1f))
        assertEquals(1, f.coach.currentFrameCount)
    }

    @Test fun explicitLossCanPreserveOrClearStoredSessionSamples() {
        val f = Feed()
        val old = f.issue()
        f.coach.onObservationLost()
        assertEquals(9, f.coach.frameCount)
        assertEquals(0, f.coach.currentFrameCount)
        assertFalse(f.coach.onFeedbackDelivered(old, f.now))
        f.coach.onObservationLost(preserveSessionHistory = false)
        assertEquals(0, f.coach.frameCount)
        assertTrue(f.coach.isAnchored)
    }

    @Test fun emptyNonFiniteAndViewOnlyFramesDoNotCountAsExerciseObservation() {
        for (missing in listOf(emptyMap(), mapOf("x" to Float.NaN), mapOf("view_cos" to 1f, "view_sin" to 0f))) {
            val f = Feed()
            f.issue()
            assertFalse(f.coach.onFrame(f.now + 300, missing))
            assertEquals(0, f.coach.currentFrameCount)
            assertTrue(f.coach.lastStates.isEmpty())
        }
    }

    @Test fun staleEvaluationInvalidatesPreviousObservation() {
        val f = Feed()
        f.issue()
        assertNull(f.coach.evaluate(f.now + 1_501))
        assertTrue(f.coach.lastStates.isEmpty())
        assertTrue(f.coach.summarize().isEmpty())
    }

    @Test fun evaluatingOneFrameRepeatedlyCannotSatisfyPersistence() {
        val f = Feed()
        repeat(8) { f.now += 300; f.coach.onFrame(f.now, mapOf("x" to 0f)) }
        repeat(10) { assertNull(f.coach.evaluate(f.now)) }
        assertNotNull(f.tick(0f))
    }

    @Test fun proposedButUndeliveredCueNeverReportsRecovery() {
        val f = Feed()
        f.issue() // 음소거·거부·큐 취소를 모델링: 전달 콜백 없음
        repeat(15) { assertNull(f.tick(1f)) }
        assertNull(f.coach.lastStates.single().kind)
        assertNull(f.coach.summarize().single().kind)
    }

    @Test fun deliveredCueRequiresEntireNewWindowAndFreshRecoveryPersistence() {
        val f = Feed()
        val issue = f.issue()
        // 전달 전에 이미 좋아 보인 프레임은 이후 교정 근거가 될 수 없다.
        repeat(8) { f.now += 300; f.coach.onFrame(f.now, mapOf("x" to 1f)) }
        assertTrue(f.coach.onFeedbackDelivered(issue, f.now))
        assertFalse(f.coach.onFeedbackDelivered(issue, f.now))
        assertNull(f.coach.evaluate(f.now))
        repeat(8) { assertNull(f.tick(1f)) }
        val recovery = f.tick(1f)
        assertEquals(OnsetKind.RECOVERED, recovery!!.kind)
        assertTrue(recovery.message.contains("최근 관측 구간"))
        assertFalse(recovery.message.contains("교정됐"))
        repeat(5) { assertNull(f.tick(1f)) }
    }

    @Test fun anotherCoachOrPastDeliveryTimeCannotConfirmCue() {
        val f = Feed()
        val issue = f.issue()
        assertFalse(f.coach.onFeedbackDelivered(issue, issue.atMs - 1))
        val other = Feed()
        other.issue()
        assertFalse(other.coach.onFeedbackDelivered(issue, other.now))
    }

    @Test fun timestampFreeCompatibilityInputDoesNotCertifyFeedbackDelivery() {
        val f = Feed()
        repeat(8) { f.coach.onFrame(mapOf("x" to 0f)) }
        f.coach.evaluate(2_400)
        f.coach.onFrame(mapOf("x" to 0f))
        val issue = f.coach.evaluate(2_700)!!
        assertFalse(f.coach.onFeedbackDelivered(issue, 2_700))
    }

    @Test fun unvalidatedOppositeIsNeitherOkayNorViolationAndCannotSpeak() {
        val guarded = rule.copy(oppositeGuard = OppositeGuard(">", 2f, "반대 편차", false, 10))
        val rules = PostureRuleSet("t", "d", listOf(guarded, guarded.copy(id = "excluded", status = RuleStatus.EXCLUDE)))
        fun result(value: Float): RuleResult {
            val agg = FeatureAggregator()
            repeat(8) { agg.add(mapOf("x" to value)) }
            return rules.evaluate("운동", agg).single() // EXCLUDE는 여전히 평가 대상 밖
        }
        val abstained = result(3f)
        assertEquals(Verdict.ABSTAIN, abstained.verdict)
        assertEquals(Direction.OPPOSITE, abstained.direction)
        assertTrue(abstained.abstainReason!!.contains("미검증"))
        assertEquals(Verdict.VIOLATION, result(0f).verdict)
        assertEquals(Verdict.OK, result(1f).verdict)
        val coach = LiveCoach(rules, "운동", speakBeta = false)
        repeat(12) { i -> coach.onFrame(i * 300L, mapOf("x" to 3f)); assertNull(coach.evaluate(i * 300L)) }
        assertEquals(Verdict.ABSTAIN, coach.lastStates.single().recent)
        assertTrue(coach.lastStates.single().label.contains("미검증"))
    }
}
