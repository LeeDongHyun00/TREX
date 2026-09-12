package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** 사용자에게 실제로 전달할 음성·색상 입력을 함께 검증한다. */
class FloorFeedbackTest {
    private fun rule(ex: String = "푸시업", feature: String = "head_trunk_ang", kind: String = "window") = PostureRule(
        "floor|$ex|$feature", ex, "고개 젖힘/숙임 여부", null, RuleStatus.BETA, null,
        feature, feature, "mean", "floor", ">", 100f, "C", "", Float.NaN, Float.NaN, 0, true, emptyList(),
        kind = kind, holdConfig = if (kind == "hold") HoldConfig(.06f, .06f, 5000, 2000) else null,
        repConfig = if (kind == "rep") RepRuleConfig("min", .71f, .34f, 2) else null,
    )
    private val plank = rule("플랭크", "hip_dev_ankle", "hold")
    private val push = rule("푸시업", "wrist_shoulder_d", "rep")
    private fun hold(side: Int?, duration: Long = 2000) = HoldSnapshot(0f, 10000, 5000, 3000, "화면 위쪽", 30, side, duration)
    private fun window(r: PostureRule, verdict: Verdict) = OnsetState(r, Verdict.OK, verdict, 80f, 110f, null)
    private val hipFeatures = mapOf("hip_dev_ankle" to .13f)
    private val pushFeatures = mapOf("wrist_shoulder_d" to .8f)

    @Test fun sustainedHipChangeProducesLocalizedRedAndSpeechTogether() {
        val c = FloorFeedbackController("플랭크", listOf(plank))
        val result = c.update(10000, hipFeatures, hold(1), emptyList())
        assertEquals(FloorFeedbackPhase.ATTENTION, result.phase)
        assertEquals(setOf(11, 12, 23, 24), result.landmarks)
        assertTrue(result.speech!!.contains("골반이 위"))
        assertTrue(result.title.contains("참고"))
    }

    @Test fun historicalBreakDoesNotKeepRecoveredBodyRed() {
        val c = FloorFeedbackController("플랭크", listOf(plank))
        val result = c.update(10000, hipFeatures, hold(0), emptyList())
        assertEquals(FloorFeedbackPhase.MEASURING, result.phase)
        assertTrue(result.landmarks.isEmpty())
    }

    @Test fun shortNoiseDoesNotTriggerWarning() {
        val c = FloorFeedbackController("플랭크", listOf(plank))
        assertEquals(FloorFeedbackPhase.MEASURING, c.update(10000, hipFeatures, hold(1, 500), emptyList()).phase)
    }

    @Test fun muteDisablesSpeechButNotVisualAndUnmuteCanAnnounceCurrentIssue() {
        val c = FloorFeedbackController("플랭크", listOf(plank))
        val quiet = c.update(10000, hipFeatures, hold(-1), emptyList(), voiceEnabled = false)
        assertEquals(FloorFeedbackPhase.ATTENTION, quiet.phase)
        assertNull(quiet.speech)
        assertNotNull(c.update(10250, hipFeatures, hold(-1), emptyList()).speech)
        assertNull(c.update(10500, hipFeatures, hold(-1), emptyList()).speech)
    }

    @Test fun repeatedWarningHasCooldown() {
        val c = FloorFeedbackController("플랭크", listOf(plank))
        var voices = 0
        for (t in 10000L..21999L step 250) if (c.update(t, hipFeatures, hold(1), emptyList()).speech != null) voices++
        assertEquals(1, voices)
        assertNotNull(c.update(22000, hipFeatures, hold(1), emptyList()).speech)
    }

    @Test fun recoveryRequiresObservedReturnAndClearsRedImmediately() {
        val c = FloorFeedbackController("플랭크", listOf(plank))
        c.update(10000, hipFeatures, hold(1), emptyList())
        var result = c.update(10250, hipFeatures, hold(0), emptyList())
        assertTrue(result.landmarks.isEmpty())
        for (t in 10500L..14000L step 250) result = c.update(t, hipFeatures, hold(0), emptyList())
        assertEquals(FloorFeedbackPhase.RECOVERED, result.phase)
        assertTrue(result.speech!!.contains("돌아왔어요"))
    }

    @Test fun occlusionAndPauseNeverCountAsRecovery() {
        val c = FloorFeedbackController("플랭크", listOf(plank))
        c.update(10000, hipFeatures, hold(1), emptyList())
        val lost = c.update(10250, emptyMap(), hold(1), emptyList())
        assertEquals(FloorFeedbackPhase.UNAVAILABLE, lost.phase)
        assertTrue(lost.landmarks.isEmpty())
        val paused = c.update(10500, hipFeatures, hold(1), emptyList(), paused = true)
        assertEquals(FloorFeedbackPhase.PAUSED, paused.phase)
        assertNull(paused.speech)
    }

    @Test fun twoConsecutivePartialRepsAreRequiredAndExpiryIsNotRecovery() {
        val c = FloorFeedbackController("푸시업", listOf(push))
        var result: FloorFeedback? = null
        for (t in 10000L..12000L step 250) {
            result = c.update(t, pushFeatures, null, emptyList(),
                rep = if (t == 10000L || t == 12000L) RepRecord(t, .9f, 1.3f, false) else null, voiceEnabled = false)
            if (t < 12000) assertNotEquals(FloorFeedbackPhase.ATTENTION, result.phase)
        }
        assertEquals(FloorFeedbackPhase.ATTENTION, result!!.phase)
        assertTrue(result.landmarks.containsAll(setOf(11, 12, 13, 14, 15, 16)))
        for (t in 12250L..18000L step 250) result = c.update(t, pushFeatures, null, emptyList(), voiceEnabled = false)
        assertEquals(FloorFeedbackPhase.MEASURING, result!!.phase)
        assertTrue(result.landmarks.isEmpty())
    }

    @Test fun repeatedRepRecordDoesNotCountTwice() {
        val c = FloorFeedbackController("푸시업", listOf(push))
        val record = RepRecord(10000, .9f, 1.3f, false)
        for (t in 10000L..13000L step 250)
            assertNotEquals(FloorFeedbackPhase.ATTENTION, c.update(t, pushFeatures, null, emptyList(), rep = record).phase)
    }

    @Test fun missingAndExcludedRulesCannotTriggerWarning() {
        val r = rule()
        val excluded = r.copy(status = RuleStatus.EXCLUDE)
        val c = FloorFeedbackController("푸시업", listOf(excluded))
        for (t in 10000L..15000L step 250) {
            val f = c.update(t, mapOf("head_trunk_ang" to 150f), null, listOf(window(excluded, Verdict.VIOLATION)))
            assertTrue(f.landmarks.isEmpty())
            assertNotEquals(FloorFeedbackPhase.ATTENTION, f.phase)
        }
        val c2 = FloorFeedbackController("푸시업", listOf(r))
        for (t in 10000L..13000L step 250)
            assertNotEquals(FloorFeedbackPhase.ATTENTION, c2.update(t, pushFeatures, null, listOf(window(r, Verdict.VIOLATION))).phase)
    }

    @Test fun windowFeedbackPersistsBeforeSpeakingAndTargetsHeadNotHip() {
        val r = rule()
        val c = FloorFeedbackController("푸시업", listOf(r))
        var f: FloorFeedback? = null
        for (t in 10000L..11500L step 250) f = c.update(t, mapOf("head_trunk_ang" to 150f), null, listOf(window(r, Verdict.VIOLATION)))
        assertEquals(FloorFeedbackPhase.ATTENTION, f!!.phase)
        assertNotNull(f.speech)
        assertTrue(f.landmarks.contains(0))
        assertFalse(f.landmarks.contains(23))
        assertTrue(c.update(11750, mapOf("head_trunk_ang" to 80f), null, listOf(window(r, Verdict.ABSTAIN))).landmarks.isEmpty())
    }

    @Test fun trackModeDoesNotJudgePopulationRangeButStillReportsHoldChange() {
        val c = FloorFeedbackController("푸시업", listOf(push))
        for (t in 10000L..14000L step 250)
            assertNotEquals(FloorFeedbackPhase.ATTENTION, c.update(t, pushFeatures, null, emptyList(), RepRecord(t, .9f, 1.3f, false), mode = CoachMode.TRACK).phase)
        assertEquals(FloorFeedbackPhase.ATTENTION, FloorFeedbackController("플랭크", listOf(plank))
            .update(10000, hipFeatures, hold(-1), emptyList(), mode = CoachMode.TRACK).phase)
    }

    @Test fun unobservableExercisesExplainLimitsAndAnnounceMeasurement() {
        for (ex in listOf("크런치", "Y - Exercise")) {
            val c = FloorFeedbackController(ex, emptyList())
            var f: FloorFeedback? = null
            for (t in 10000L..12000L step 250) f = c.update(t, mapOf("head_ground" to .2f), null, emptyList())
            assertNotNull(f!!.speech)
            assertTrue(f.message.contains("판정하지 못해요"))
            assertTrue(f.landmarks.isEmpty())
        }
    }

    @Test fun preparationAndLongGapsResetConsecutiveWindowEvidence() {
        val r = rule(); val c = FloorFeedbackController("푸시업", listOf(r)); val fs = mapOf("head_trunk_ang" to 150f)
        assertEquals(FloorFeedbackPhase.PREPARING, c.update(10000, fs, null, emptyList(), anchored = false).phase)
        c.update(10250, fs, null, listOf(window(r, Verdict.VIOLATION)))
        c.update(11000, fs, null, listOf(window(r, Verdict.VIOLATION)))
        assertEquals(FloorFeedbackPhase.MEASURING, c.update(14000, fs, null, listOf(window(r, Verdict.VIOLATION))).phase)
    }

    @Test fun specificWristFeatureDoesNotLoseShoulderHighlights() {
        assertEquals(setOf(11, 12, 15, 16), RuleHighlight.landmarksFor("wrist_shoulder_d"))
    }
}
