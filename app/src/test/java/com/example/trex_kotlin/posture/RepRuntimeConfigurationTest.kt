package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class RepRuntimeConfigurationTest {
    private val cfg = RepRuleConfig("min", .7101f, .34f, 2)
    private fun profile(name: String) = requireNotNull(ExerciseRepProfiles.forExercise(name))

    private fun runCycle(profile: ExerciseRepProfile, bottom: Float): ExerciseRepTracker {
        val tracker = requireNotNull(profile.createTracker())
        val feature = requireNotNull(profile.commonSignal).feature
        val values = listOf(1.4f, 1.4f, 1.4f, 1.1f, .7f, bottom, .7f, 1.1f, 1.4f)
        for ((i, value) in values.withIndex()) {
            val observed = profile.observe(mapOf(feature to value), view = null, qualityOk = true)
            tracker.onFrame(i * 300L, observed.features)
        }
        return tracker
    }

    @Test fun collapsedWristFrameDiscardsPushupAndKneePushupCycles() {
        for (name in listOf("푸시업", "니푸쉬업")) {
            val original = profile(name)
            // 붕괴 방어가 없으면 이 궤적을 얕지 않은 1회로 잘못 완성하는 회귀 입력이다.
            assertEquals(name, 1, runCycle(original, .01f).counts.total)
            val configured = original.withLegacySignalConstraints(RepSignals.byExercise[name], cfg)
            assertEquals(name, .1f, configured.commonSignal!!.plausibleMin!!, 0f)
            assertEquals(name, 0, runCycle(configured, .01f).counts.total)
        }
    }

    @Test fun visiblePushupRoundTripStillCountsOnceWithOriginalRawExtrema() {
        val configured = profile("푸시업").withLegacySignalConstraints(RepSignals.byExercise["푸시업"], cfg)
        val tracker = runCycle(configured, .4f)
        assertEquals(1, tracker.counts.total)
        val event = tracker.events.single()
        assertEquals(RepSide.UNKNOWN, event.side)
        assertEquals(.4f, event.common!!.cycleMin, 0f)
        assertEquals(1.4f, event.common!!.cycleMax, 0f)
        assertEquals(true, event.common!!.valid)
    }

    @Test fun onlySameCommonFeatureReceivesConstraintsAndExplicitConfigRom() {
        val original = profile("힙쓰러스트")
        val legacy = RepSignal("hip_dev_ankle", .7f, validated = true, plausibleMin = -2f, plausibleMax = 2f,
            romDirection = "min", romThreshold = -.9f, romValidated = true, romCue = "과거 문구")
        val config = RepRuleConfig("max", .134f, .34f, 2)
        val configured = original.withLegacySignalConstraints(legacy, config)
        val actual = requireNotNull(configured.commonSignal)
        assertEquals(original.commonSignal!!.minAmp, actual.minAmp, 0f)
        assertEquals(-2f, actual.plausibleMin!!, 0f)
        assertEquals(2f, actual.plausibleMax!!, 0f)
        assertEquals("max", actual.romDirection)
        assertEquals(.134f, actual.romThreshold!!, 0f)
        assertFalse(actual.validated)
        assertFalse(actual.romValidated)
        assertNull(actual.romCue)
        assertNull(original.commonSignal!!.plausibleMin)
    }

    @Test fun absentRepConfigDoesNotImportHistoricalRom() {
        val original = profile("바벨 스쿼트")
        val configured = original.withLegacySignalConstraints(RepSignals.byExercise["바벨 스쿼트"], null)
        assertNull(configured.commonSignal!!.romThreshold)
        assertNull(configured.commonSignal!!.romDirection)
        assertFalse(configured.commonSignal!!.romValidated)
    }

    @Test fun changedOrIndependentFeaturesNeverReceiveLegacyMeanConstraints() {
        val curl = profile("덤벨 컬")
        val legacy = RepSignal("elbow_mean", 60f, plausibleMin = 50f, plausibleMax = 150f, romThreshold = 60f)
        assertSame(curl, curl.withLegacySignalConstraints(legacy, cfg))
        assertNull(curl.leftSignal!!.plausibleMin)
        assertNull(curl.rightSignal!!.romThreshold)
        val pulldown = profile("랫풀 다운")
        assertEquals("elbow_mean", pulldown.commonSignal!!.feature)
        assertSame(pulldown, pulldown.withLegacySignalConstraints(RepSignals.byExercise["랫풀 다운"], cfg))
        assertSame(pulldown, pulldown.withLegacySignalConstraints(null, cfg))
    }

    @Test fun lungeCommonCompatibilityDoesNotModifyItsUserSelectedSideChannels() {
        val original = profile("바벨 런지")
        val legacy = RepSignal("knee_minside", 90f, plausibleMin = 10f, plausibleMax = 180f)
        val configured = original.withLegacySignalConstraints(legacy, RepRuleConfig("min", 112f, .34f, 2))
        assertEquals(original.leftSignal, configured.leftSignal)
        assertEquals(original.rightSignal, configured.rightSignal)
        assertEquals(10f, configured.commonSignal!!.plausibleMin!!, 0f)
        assertNull(configured.leftSignal!!.romThreshold)
        assertNull(configured.rightSignal!!.plausibleMin)
    }

    @Test fun ExistingProfileBoundsAreNotRelaxedByLegacyBounds() {
        val original = profile("푸시업")
        val narrower = original.copy(commonSignal = original.commonSignal!!.copy(plausibleMin = .2f, plausibleMax = 1.8f))
        val configured = narrower.withLegacySignalConstraints(RepSignal("wrist_shoulder_d", .9f, plausibleMin = .1f, plausibleMax = 2f), null)
        assertEquals(.2f, configured.commonSignal!!.plausibleMin!!, 0f)
        assertEquals(1.8f, configured.commonSignal!!.plausibleMax!!, 0f)
        assertEquals(.3f, configured.commonSignal!!.minAmp, 0f)
    }
}
