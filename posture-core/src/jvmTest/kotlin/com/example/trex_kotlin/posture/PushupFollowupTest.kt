// Android 정본 테스트 — tools/sync_ios_core.py
package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** §126: 보고된 오류의 구조적 회귀 시험. 휴대폰의 자세 인식 정확도 시험은 별도로 필요하다. */
class PushupFollowupTest {
    @Test fun pushupIsIncludedInCoachCountGateOnTheFloorPath() {
        assertTrue(FourExerciseForm.usesFormCountGate("푸시업", coach = true, floor = true))
        assertFalse(FourExerciseForm.usesFormCountGate("푸시업", coach = false, floor = true))
        assertFalse(FourExerciseForm.usesFormCountGate("니푸쉬업", coach = true, floor = true))
        assertTrue(FourExerciseForm.usesFormCountGate("프런트 레이즈", coach = true, floor = false))
    }
    private fun features(hip: Float = 0f, knee: Float = 175f) = mapOf(
        FourExerciseForm.PUSHUP_POSTURE to 1f, FourExerciseForm.PUSHUP_HIP to hip,
        FourExerciseForm.PUSHUP_KNEE to knee,
    )

    private fun form(extra: (Int) -> Map<String, Float>): Pair<RepFormEvaluator, RepFormRep> {
        val ev = RepFormSpecs.evaluatorFor("푸시업", RepCounter.forExercise("푸시업")!!)!!
        val shape = listOf(1f, 1f, 1f, .8f, .5f, .3f, .2f, .2f, .3f, .5f, .8f, 1f, 1f)
        shape.forEachIndexed { i, value ->
            ev.onFrame(i * 200L, features() + mapOf(
                FourExerciseForm.PUSHUP_SIGNAL to value,
                FourExerciseForm.PUSHUP_ELBOW to if (i <= 2 || i >= 11) 175f else 90f,
            ) + extra(i))
        }
        return ev to ev.onCycle(2400L, .2f, 1f)
    }

    @Test fun hipUpDownAndKneesExcludeButStayUnscoredAndSilent() {
        for ((key, value) in listOf(FourExerciseForm.PUSHUP_HIP to -.15f,
            FourExerciseForm.PUSHUP_HIP to .15f, FourExerciseForm.PUSHUP_KNEE to 120f)) {
            val (ev, rep) = form { mapOf(key to value) }
            assertFalse(rep.correct); assertFalse(rep.judged)
            assertEquals(1, rep.flagged.size)
            val event = ev.eventFor(rep, 2400L, gate = true)!!
            assertTrue(event.gated); assertFalse(event.ship)
            assertTrue(event.message.contains("세지 않았어요"))
            assertFalse(ev.summary().hasShip)
            assertFalse(ev.eventFor(rep, 2400L, gate = false)!!.gated)
        }
    }

    @Test fun observedBodyExtremesExcludeWithoutRequiringAStreak() {
        val (_, spike) = form { i -> if (i == 6) features(-.2f, 110f) else emptyMap() }
        assertFalse(spike.correct); assertEquals(2, spike.flagged.size)
        val (_, sustained) = form { i -> if (i in 5..7) features(-.2f, 110f) else emptyMap() }
        assertFalse(sustained.correct); assertEquals(2, sustained.flagged.size)
    }

    @Test fun oppositeHipErrorsDoNotCancelAndMultipleExclusionReasonsAreShown() {
        val (ev, rep) = form { i -> features(if (i in 3..5) -.2f else if (i in 6..8) .2f else 0f, 110f) }
        assertEquals(3, rep.flagged.size)
        val message = ev.eventFor(rep, 2400L, gate = true)!!.message
        assertTrue(message.contains("골반 처짐")); assertTrue(message.contains("골반 들림")); assertTrue(message.contains("다리 펴기"))
    }

    @Test fun boundaryValuesPassAndMissingOrNonFiniteMeasurementsAbstain() {
        for (hip in listOf(-.08f, .08f)) assertTrue(form { features(hip, 150f) }.second.correct)
        val (_, nan) = form { features(Float.NaN, Float.POSITIVE_INFINITY) }
        assertTrue(nan.correct)
        assertTrue(nan.outcomes.filter { it.check.phase == RepPhase.CYCLE }.all { it.verdict == Verdict.ABSTAIN })
        val (_, sparse) = form { i -> features(if (i == 6) -.3f else Float.NaN, Float.NaN) }
        assertFalse(sparse.correct)
        assertEquals("골반 처짐(반복)", sparse.flagged.single().check.condition)
        assertTrue(sparse.outcomes.filter { it.check.feature == FourExerciseForm.PUSHUP_KNEE }.all { it.verdict == Verdict.ABSTAIN })
    }

    @Test fun missingFrameDoesNotEraseObservedKneeViolationInTheSameRep() {
        val (_, rep) = form { i -> mapOf(FourExerciseForm.PUSHUP_KNEE to when (i) {
            4, 5, 7, 8 -> 110f
            6 -> Float.NaN
            else -> 175f
        }) }
        assertFalse(rep.correct)
    }

    @Test fun counterFormAndTallyResumeAfterEachObservedError() {
        for (bad in listOf(features(-.15f), features(.15f), features(knee = 110f))) {
            val rc = RepCounter.forSession("푸시업", floor = true)!!
            val ev = RepFormSpecs.evaluatorFor("푸시업", rc)!!
            val records = ArrayList<RepRecord>()
            val units = RepUnitAccumulator(RepUnit.CYCLE)
            val reps = ArrayList<RepFormRep>()
            var t = 0L
            fun frame(height: Float, extra: Map<String, Float> = emptyMap()) {
                val f = features() + mapOf(FourExerciseForm.PUSHUP_SIGNAL to height,
                    PushupCycleTracker.HIP_HEIGHT to height*.6f, PushupCycleTracker.HIP_RATIO to .6f, FloorChain.SIDE to 0f,
                    FourExerciseForm.PUSHUP_ELBOW to if (height > .85f) 175f else 90f) + extra
                ev.onFrame(t, f)
                if (rc.onFrameFeatures(t, f)) {
                    val tally = RepUnitAccumulator.onCounterFrame(rc, t, records, units)
                    reps += tally.records.map { ev.onCycle(it.tMs, it.cycleMin, it.cycleMax, it.cycleStartMs, it.side) }
                }
                t += 200L
            }
            repeat(8) { frame(1f) }
            for (fault in listOf(emptyMap(), bad, emptyMap())) {
                for (h in listOf(.8f, .6f, .4f, .2f)) frame(h, fault)
                repeat(5) { frame(.2f, fault) }
                for (h in listOf(.4f, .6f, .8f, 1f)) frame(h, fault)
                repeat(8) { frame(1f) }
            }
            if (kotlin.math.abs(bad.getValue(FourExerciseForm.PUSHUP_HIP))>.08f) {
                assertEquals(2,rc.reps); assertEquals(2,units.completed)
                assertEquals(listOf(true,true),reps.map { it.correct })
                assertEquals(PushupCycleTracker.ALIGNMENT,rc.rejectedReps.single().feature)
                continue
            }
            assertEquals("센 사이클", 3, rc.reps)
            assertEquals(listOf(true, false, true), reps.map { it.correct })
            val coachGate = FourExerciseForm.usesFormCountGate("푸시업", coach = true, floor = true)
            assertEquals("COACH 표시 수", 2, units.completed - if (coachGate) reps.count { !it.correct } else 0)
            assertEquals("COACH", 2, ev.summary().correct)
            assertEquals("TRACK 원장", 3, units.completed)
        }
    }

    @Test fun straightBodyCoordinatesCountThroughExtractorCounterAndForm() {
        for (rolled in listOf(false, true)) {
            val extractor = FloorFeatureExtractor()
            val rc = RepCounter.forSession("푸시업", floor = true)!!
            val ev = RepFormSpecs.evaluatorFor("푸시업", rc)!!
            val records = ArrayList<RepRecord>()
            val units = RepUnitAccumulator(RepUnit.CYCLE)
            val reps = ArrayList<RepFormRep>()
            var t = 0L
            fun frame(degrees: Double) {
                val a = Math.toRadians(degrees); val top = Math.toRadians(25.0)
                val sx = .85 - .65 * cos(a); val sy = .7 - .65 * sin(a)
                val wx = .85 - .65 * cos(top); val wy = .7
                val dx = wx - sx; val dy = wy - sy; val d = hypot(dx, dy)
                val arm = .65 * sin(top) / 2
                val h = sqrt((arm * arm - d * d / 4).coerceAtLeast(0.0))
                val xy = FloatArray(66); val vis = FloatArray(33)
                for (side in 0..1) {
                    fun p(index: Int, x: Double, y: Double) {
                        val px = x + side * .003
                        xy[(index + side) * 2] = (if (rolled) 1 - y else px).toFloat()
                        xy[(index + side) * 2 + 1] = (if (rolled) px else y).toFloat()
                        vis[index + side] = 1f
                    }
                    p(11, sx, sy); p(23, .85 - .39 * cos(a), .7 - .39 * sin(a))
                    p(25, .85 - .18 * cos(a), .7 - .18 * sin(a)); p(27, .85, .7)
                    p(13, (sx + wx) / 2 + dy / d * h, (sy + wy) / 2 - dx / d * h); p(15, wx, wy)
                }
                val f = extractor.computeForExercise("푸시업", xy, vis, 1000, 1000,
                    if (rolled) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f), t)
                assertEquals(1f, f.getValue(FourExerciseForm.PUSHUP_POSTURE), 0f)
                ev.onFrame(t, f)
                if (rc.onFrameFeatures(t, f)) {
                    val tally = RepUnitAccumulator.onCounterFrame(rc, t, records, units)
                    reps += tally.records.map { ev.onCycle(it.tMs, it.cycleMin, it.cycleMax, it.cycleStartMs, it.side) }
                }
                t += 200L
            }
            repeat(8) { frame(25.0) }
            repeat(3) {
                for (a in listOf(22.0, 19.0, 16.0, 13.0, 12.0)) frame(a)
                repeat(5) { frame(12.0) }
                for (a in listOf(13.0, 16.0, 19.0, 22.0, 25.0)) frame(a)
                repeat(8) { frame(25.0) }
            }
            assertEquals(3, rc.reps)
            assertEquals(reps.flatMap { it.flagged }.map { it.check.condition }.toString(), listOf(true, true, true), reps.map { it.correct })
        }
    }

    @Test fun liveFeedbackIncludesHipKneeAndDetectedHeadDirectionWithoutSpeech() {
        val ctrl = FloorFeedbackController("푸시업", emptyList())
        val f = features(-.2f, 110f) + mapOf(FloorGaze.FOUND to 1f, FloorGaze.YAW to 60f)
        var state: FloorFeedback? = null
        for (t in 0L..1000L step 200) state = ctrl.update(t, f, null, emptyList())
        assertEquals(FloorFeedbackPhase.ATTENTION, state!!.phase)
        assertTrue(state.message.contains("골반")); assertTrue(state.message.contains("무릎")); assertTrue(state.message.contains("얼굴"))
        assertTrue(state.landmarks.containsAll(setOf(0, 23, 25, 27))); assertNull(state.speech)
        val corrected = ctrl.update(1200L, features(), null, emptyList())
        assertEquals(FloorFeedbackPhase.MEASURING, corrected.phase)
        assertFalse(corrected.message.contains("정상")); assertTrue(corrected.landmarks.isEmpty())
    }

    @Test fun missingFaceCannotBecomeAGazeViolationOrAClaimOfNormalEyes() {
        for (face in listOf(emptyMap(), mapOf(FloorGaze.FOUND to 0f, FloorGaze.YAW to 60f),
            mapOf(FloorGaze.FOUND to 1f, FloorGaze.YAW to Float.NaN))) {
            val ctrl = FloorFeedbackController("푸시업", emptyList())
            for (t in 0L..1200L step 200) {
                val state = ctrl.update(t, features() + face, null, emptyList())
                assertEquals(FloorFeedbackPhase.MEASURING, state.phase)
                assertFalse(state.message.contains("시선이 정상")); assertTrue(state.landmarks.isEmpty()); assertNull(state.speech)
            }
        }
    }

    @Test fun liveFeedbackClearsOnPauseTrackingModeOrLostPlacement() {
        for (reset in listOf("pause", "track", "lost")) {
            val ctrl = FloorFeedbackController("푸시업", emptyList())
            for (t in 0L..800L step 200) ctrl.update(t, features(-.2f, 110f), null, emptyList())
            val state = when (reset) {
                "pause" -> ctrl.update(1000L, features(-.2f, 110f), null, emptyList(), paused = true)
                "track" -> ctrl.update(1000L, features(-.2f, 110f), null, emptyList(), mode = CoachMode.TRACK)
                else -> ctrl.update(1000L, emptyMap(), null, emptyList(), voiceEnabled = false)
            }
            assertNotEquals(FloorFeedbackPhase.ATTENTION, state.phase); assertTrue(state.landmarks.isEmpty())
        }
    }

    @Test fun currentBodyFeedbackNeedsNoTimerAndNeverRetainsAnOldError() {
        val ctrl = FloorFeedbackController("푸시업", emptyList())
        assertEquals(FloorFeedbackPhase.ATTENTION, ctrl.update(0L, features(-.2f, 110f), null, emptyList()).phase)
        assertEquals(FloorFeedbackPhase.MEASURING, ctrl.update(200L, features(), null, emptyList()).phase)
        assertEquals(FloorFeedbackPhase.MEASURING, ctrl.update(2000L, features(), null, emptyList()).phase)
        assertEquals(FloorFeedbackPhase.ATTENTION, ctrl.update(2200L, features(-.2f, 110f), null, emptyList()).phase)
    }
}
