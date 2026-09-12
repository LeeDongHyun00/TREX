package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PostureComparisonTest {
    private val signal = RepSignal("x", .25f)
    private val metric = ComparisonMetric("x", "움직임 범위", "몸통 비율", .06f)
    private val extra = ComparisonMetric("head", "고개각", "°", 8f)
    private val shape = listOf(1f, 1f, .7f, .4f, 0f, 0f, .4f, .7f, 1f, 1f)
    private class Feed(val tracker: PostureComparisonTracker) {
        var time = 1000L
        fun cycle(amplitude: Float = 1f, offset: Float = 0f, headShift: Float = 0f, includeHead: Boolean = true): ComparisonSnapshot {
            val shape = listOf(1f, 1f, .7f, .4f, 0f, 0f, .4f, .7f, 1f, 1f)
            for ((i, v) in shape.withIndex()) {
                time += 300
                val f = mutableMapOf("x" to offset + v * amplitude)
                if (includeHead) f["head"] = 20 + 60 * v + headShift
                tracker.add(time, f, if (i == shape.lastIndex) time else null)
            }
            return tracker.snapshot
        }
        fun learn() { repeat(4) { cycle() } }
    }
    private fun feed() = Feed(PostureComparisonTracker("test", listOf(metric, extra), signal))

    @Test fun samePhaseMotionDoesNotBecomeDrift() {
        val f = feed(); f.learn()
        repeat(4) { assertEquals(ComparisonState.MEASURING, f.cycle().state) }
        val lows = f.tracker.snapshot.values.filter { it.metric.feature == "head" && it.phase == ComparisonPhase.LOW }
        assertEquals(20f, lows.single().initial, .001f)
        assertEquals(20f, lows.single().current, .001f)
    }

    @Test fun setupBeforeFirstCompleteCycleIsExcluded() {
        val f = feed()
        repeat(8) { f.time += 300; f.tracker.add(f.time, mapOf("x" to 50f, "head" to 150f)) }
        f.learn(); f.cycle()
        assertEquals(1f, f.tracker.snapshot.values.single { it.phase == ComparisonPhase.RANGE }.initial, .001f)
    }

    @Test fun smallerRangeNeedsTwoRepetitionsAndKeepsInitialReference() {
        val f = feed(); f.learn()
        assertNotEquals(ComparisonState.CHANGED, f.cycle(.6f).state)
        assertEquals(ComparisonState.CHANGED, f.cycle(.6f).state)
        repeat(5) { f.cycle(.6f) }
        val value = f.tracker.snapshot.values.single { it.phase == ComparisonPhase.RANGE }
        assertEquals(1f, value.initial, .001f)
        assertEquals(-40f, value.relativePercent!!, .01f)
    }

    @Test fun matchingRangeCanStillHavePostureChangeAtSamePhase() {
        val f = feed(); f.learn(); f.cycle(headShift = 20f)
        val result = f.cycle(headShift = 20f)
        assertTrue(result.values.any { it.metric.feature == "head" && it.changed })
        assertFalse(result.values.single { it.phase == ComparisonPhase.RANGE }.changed)
    }

    @Test fun recoveryRequiresTwoObservedRepetitions() {
        val f = feed(); f.learn(); f.cycle(.6f); f.cycle(.6f)
        assertNotEquals(ComparisonState.RECOVERED, f.cycle().state)
        assertEquals(ComparisonState.RECOVERED, f.cycle().state)
    }

    @Test fun gapAndMissingLandmarksCannotClaimRecovery() {
        val f = feed(); f.learn(); f.cycle(.6f); f.cycle(.6f)
        f.tracker.unavailable()
        assertEquals(ComparisonState.UNAVAILABLE, f.tracker.snapshot.state)
        assertTrue(f.tracker.snapshot.values.isEmpty())
        assertTrue(f.tracker.latestSignature.isEmpty())
        assertNotEquals(ComparisonState.RECOVERED, f.cycle().state)
        assertNotEquals(ComparisonState.RECOVERED, f.cycle().state)
    }

    @Test fun missingFeatureCannotReusePreviousChange() {
        val f = feed(); f.learn(); f.cycle(headShift = 20f); f.cycle(headShift = 20f)
        val result = f.cycle(includeHead = false)
        assertFalse(result.values.any { it.metric.feature == "head" })
    }

    @Test fun resetStartsNewReferenceRatherThanKeepingOldNumbers() {
        val f = feed(); f.learn(); f.tracker.reset()
        assertTrue(f.tracker.snapshot.values.isEmpty())
        repeat(4) { f.cycle(.6f) }; f.cycle(.6f)
        assertEquals(.6f, f.tracker.snapshot.values.single { it.phase == ComparisonPhase.RANGE }.initial, .001f)
    }

    @Test fun invalidOrShortCyclesAreNotMeasurements() {
        assertTrue(PhaseSignature.compute(List(4) { ComparisonFrame(it.toLong(), mapOf("x" to it.toFloat())) }, signal, listOf(metric)).isEmpty())
        assertTrue(PhaseSignature.compute(List(10) { ComparisonFrame(it.toLong(), mapOf("x" to Float.NaN)) }, signal, listOf(metric)).isEmpty())
    }

    @Test fun holdUsesStableFiveSecondsAndTwoSecondsOfChange() {
        val m = ComparisonMetric("hip_dev_ankle", "골반 정렬", "몸통 비율", .06f)
        val tracker = PostureComparisonTracker("플랭크", listOf(m))
        for (t in 1000L..7000L step 250L) tracker.add(t, mapOf(m.feature to 0f))
        assertEquals(ComparisonState.MEASURING, tracker.snapshot.state)
        for (t in 7250L..10000L step 250L) tracker.add(t, mapOf(m.feature to .2f))
        assertEquals(ComparisonState.CHANGED, tracker.snapshot.state)
        assertEquals(0f, tracker.snapshot.values.single().initial, .001f)
        assertTrue(tracker.snapshot.message.contains("골반이 위"))
    }

    @Test fun populationRuleVerdictsAreNotInputsToPersonalTracking() {
        val f = feed(); f.learn()
        // 동일 자세가 모집단 범위 밖이어도 개인 변화는 0이다. 규칙셋을 받지 않는 비교 경로다.
        assertEquals(ComparisonState.MEASURING, f.cycle().state)
        assertTrue(f.tracker.snapshot.values.all { it.delta == 0f })
    }

    @Test fun voiceDoesNotConsumeMutedEventOrAnnounceUnspokenRecovery() {
        val voice = ComparisonSpeech()
        val changed = ComparisonValue(metric, ComparisonPhase.RANGE, 1f, .6f, .15f, true)
        val snapshot = ComparisonSnapshot(ComparisonState.CHANGED, values = listOf(changed), revision = 1)
        assertNull(voice.next(10000, snapshot, false))
        assertNotNull(voice.next(10000, snapshot, true))
        assertNull(voice.next(20000, snapshot, true))
        val recovered = snapshot.copy(state = ComparisonState.RECOVERED, values = listOf(changed.copy(changed = false, recovered = true)), revision = 2)
        assertNotNull(voice.next(20000, recovered, true))
        voice.clear()
        assertNull(voice.next(30000, recovered.copy(revision = 3), true))
    }

    @Test fun referenceRequiresMatchingViewAndFiniteObservedFeature() {
        val reference = NormalPoseReference.parse(sequenceOf("test\tB\tx\tRANGE\t0.8\t1.2\t2\t2\tandroid_video_training"))
        assertTrue(reference.compare("test", null, mapOf("x|RANGE" to 1f), listOf(metric)).isEmpty())
        assertTrue(reference.compare("test", "C", mapOf("x|RANGE" to 1f), listOf(metric)).isEmpty())
        assertTrue(reference.compare("test", "B", mapOf("x|RANGE" to Float.NaN), listOf(metric)).isEmpty())
        assertTrue(reference.compare("test", "B", mapOf("x|RANGE" to .5f), listOf(metric)).single().outside)
        assertTrue(NormalPoseReference.parse(sequenceOf("test\tB\tx\tRANGE\tNaN\t1.2\t2\t2\tandroid_video_training")).bands.isEmpty())
    }

    @Test fun androidReplayReferenceSignaturesMatchPythonExport() {
        var exercise = ""
        val frames = ArrayList<ComparisonFrame>()
        val expected = LinkedHashMap<String, Float>()
        var cases = 0
        val exercises = HashSet<String>()
        fun check() {
            if (exercise.isEmpty()) return
            val metrics = expected.keys.map { it.substringBefore('|') }.distinct().map { ComparisonMetric(it, it, "", .01f) }
            val actual = PhaseSignature.compute(frames, RepSignals.byExercise.getValue(exercise), metrics)
            assertEquals(expected.keys, actual.keys)
            expected.forEach { (key, value) -> assertEquals("$exercise/$key", value, actual.getValue(key), .0001f) }
            cases++
            exercises += exercise
        }
        javaClass.getResourceAsStream("/comparison_reference_fixture.tsv")!!.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val c = line.split('\t')
                when (c[0]) {
                    "CASE" -> { check(); exercise = c[1]; frames.clear(); expected.clear() }
                    "FRAME" -> frames += ComparisonFrame(frames.size * 600L, c.drop(1).associate { it.substringBefore('=') to it.substringAfter('=').toFloat() })
                    "EXPECT" -> expected[c[1]] = c[2].toFloat()
                }
            }
        }
        check(); assertTrue(cases >= 8); assertEquals(7, exercises.size)
    }

    @Test fun actualRepCounterFeedsComparableCompletedCycles() {
        val tracker = PostureComparisonTracker("test", listOf(metric), signal)
        val counter = RepCounter(signal, maxGapMs = 1500)
        for (i in 0..224) {
            val amplitude = if (i < 112) 1f else .6f
            val x = amplitude * ((1 + kotlin.math.cos(2 * Math.PI * (i % 16) / 16)) / 2).toFloat()
            val t = 1000L + i * 300L
            val completed = counter.onFrame(t, x)
            tracker.add(t, mapOf("x" to x), if (completed) counter.repTimesMs.last() else null)
        }
        assertTrue(counter.reps >= 10)
        assertEquals(ComparisonState.CHANGED, tracker.snapshot.state)
        val range = tracker.snapshot.values.single { it.phase == ComparisonPhase.RANGE }
        assertTrue(range.changed && range.relativePercent!! < -25f)
    }

    @Test fun reportUsesSetRelativeTimeAndExcludesAfterCutoff() {
        val f = feed(); f.learn(); val cutoff = f.time
        f.cycle(.6f); f.cycle(.6f)
        assertTrue(f.tracker.report(cutoff, 1000).isEmpty())
        val report = f.tracker.report(f.time, 1000).single()
        assertTrue(report.startsWith("18초 · 기준 0 · test · 처음 대비 변화"))
    }

    @Test fun packagedReferencesContainRealFiniteBandsOnly() {
        val file = File("src/main/assets/posture/normal_pose_reference.tsv")
        val refs = file.bufferedReader().use { NormalPoseReference.parse(it.lineSequence()) }
        assertTrue(refs.bands.size > 50)
        assertTrue(refs.bands.all { it.clips >= 2 && it.lower <= it.upper })
    }
}
