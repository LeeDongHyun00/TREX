package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** §128~§130: 고개 관측 → 반복 위반 → COACH 표시 수를 연결한다. 실카메라 정확도 시험은 아니다. */
class PushupHeadCountTest {
    private fun good() = mapOf(FourExerciseForm.PUSHUP_POSTURE to 1f, FourExerciseForm.PUSHUP_HIP to 0f,
        FourExerciseForm.PUSHUP_KNEE to 175f, FourExerciseForm.PUSHUP_HEAD to 30f,
        FourExerciseForm.PUSHUP_NECK to 0f, FloorGaze.FOUND to 1f, FloorGaze.YAW to 0f)

    private val faults = listOf(
        mapOf(FourExerciseForm.PUSHUP_HEAD to 75f),
        mapOf(FourExerciseForm.PUSHUP_HEAD to -50f),
        mapOf(FourExerciseForm.PUSHUP_NECK to -40f),
        mapOf(FloorGaze.YAW to 60f),
    )

    private fun form(frameMs: Long = 200L, extra: (Int) -> Map<String, Float>): RepFormRep {
        val ev = RepFormSpecs.evaluatorFor("푸시업", RepCounter.forSession("푸시업", floor = true)!!)!!
        val shape = listOf(1f,1f,1f,.8f,.5f,.3f,.2f,.2f,.3f,.5f,.8f,1f,1f)
        shape.forEachIndexed { i, s -> ev.onFrame(i * frameMs, FourExerciseForm.filterPushupHead(good() + mapOf(
            FourExerciseForm.PUSHUP_SIGNAL to s, FourExerciseForm.PUSHUP_ELBOW to if (i <= 2 || i >= 11) 175f else 90f) + extra(i))) }
        return ev.onCycle(12 * frameMs, .2f, 1f)
    }

    @Test fun eachHeadFaultExcludesOneCycleAndNextGoodCycleCountsInCoachOnly() {
        for (fault in faults + listOf(mapOf(FourExerciseForm.PUSHUP_HEAD to -50f,
            FourExerciseForm.PUSHUP_NECK to -40f, FourExerciseForm.PUSHUP_HIP to -.2f))) {
            val rc = RepCounter.forSession("푸시업", floor = true)!!
            val ev = RepFormSpecs.evaluatorFor("푸시업", rc)!!
            val records = ArrayList<RepRecord>()
            val units = RepUnitAccumulator(RepUnit.CYCLE)
            val reps = ArrayList<RepFormRep>()
            var t = 0L
            fun frame(height: Float, extra: Map<String, Float> = emptyMap()) {
                val f = FourExerciseForm.filterPushupHead(good() + mapOf(FourExerciseForm.PUSHUP_SIGNAL to height,
                    PushupCycleTracker.HIP_HEIGHT to height*.6f, PushupCycleTracker.HIP_RATIO to .6f, FloorChain.SIDE to 0f,
                    FourExerciseForm.PUSHUP_ELBOW to if (height > .85f) 175f else 90f) + extra)
                ev.onFrame(t, f)
                if (rc.onFrameFeatures(t, f)) {
                    val tally = RepUnitAccumulator.onCounterFrame(rc, t, records, units)
                    reps += tally.records.map { ev.onCycle(it.tMs, it.cycleMin, it.cycleMax, it.cycleStartMs, it.side) }
                }
                t += 200L
            }
            repeat(8) { frame(1f) }
            for (extra in listOf(emptyMap(), fault, emptyMap())) {
                for (h in listOf(.8f,.6f,.4f,.2f)) frame(h, extra)
                repeat(5) { frame(.2f, extra) }
                for (h in listOf(.4f,.6f,.8f,1f)) frame(h, extra)
                repeat(8) { frame(1f) }
            }
            if (fault.containsKey(FourExerciseForm.PUSHUP_HIP)) {
                assertEquals(2,rc.reps); assertEquals(listOf(true,true),reps.map { it.correct })
                assertEquals(PushupCycleTracker.ALIGNMENT,rc.rejectedReps.single().feature)
                continue
            }
            assertEquals(3, rc.reps)
            assertEquals(fault.toString(), listOf(true,false,true), reps.map { it.correct })
            for (coach in listOf(true,false)) {
                val gate = FourExerciseForm.usesFormCountGate("푸시업", coach, floor = true)
                assertEquals(if (coach) 2 else 3, units.completed - if (gate) reps.count { !it.correct } else 0)
            }
            val bad = reps[1]
            assertTrue(bad.flagged.all { it.countOnlyGate && !it.check.ship })
            assertFalse(bad.judged); assertFalse(ev.summary().hasShip)
            val message = ev.eventFor(bad, t, gate = true)!!
            assertTrue(message.gated); assertFalse(message.ship); assertTrue(message.message.contains("세지 않았어요"))
        }
    }

    @Test fun singleObservedHeadErrorExcludesWithoutElapsedTime() {
        for (fault in faults) {
            // 같은 관측 배열은 0.6초든 2.4초든 같은 결과여야 한다.
            assertFalse(form(50L) { fault }.correct)
            assertFalse(form(200L) { fault }.correct)
            assertFalse(form { i -> if (i == 6) fault else emptyMap() }.correct)
        }
        for (head in listOf(-35f,0f,30f,60f)) {
            assertTrue(form { mapOf(FourExerciseForm.PUSHUP_HEAD to head,
                FourExerciseForm.PUSHUP_NECK to -25f, FloorGaze.YAW to 25f) }.correct)
        }
    }

    @Test fun noFaceStaleYawInvalidYawAndAwayOcclusionAbstainWithoutExcluding() {
        for (face in listOf(mapOf(FloorGaze.FOUND to 0f, FloorGaze.YAW to 60f),
            mapOf(FloorGaze.FOUND to Float.NaN, FloorGaze.YAW to 60f),
            mapOf(FloorGaze.YAW to Float.NaN), mapOf(FloorGaze.YAW to Float.POSITIVE_INFINITY),
            mapOf(FloorGaze.YAW to -60f), mapOf(FloorGaze.YAW to 120f))) {
            val rep = form { face + (FourExerciseForm.PUSHUP_TURN to 60f) }
            assertTrue(rep.correct)
            assertEquals(Verdict.ABSTAIN, rep.outcomes.single { it.check.feature == FourExerciseForm.PUSHUP_TURN }.verdict)
        }
        val noModel = FourExerciseForm.filterPushupHead(good() - setOf(FloorGaze.FOUND, FloorGaze.YAW))
        assertFalse(noModel.containsKey(FourExerciseForm.PUSHUP_TURN))
        assertTrue(noModel.containsKey(FourExerciseForm.PUSHUP_HEAD))
    }

    @Test fun missingHeadEvidenceDoesNotDisableBodyCountingAndCannotBeCalledNormal() {
        val missing = mapOf(FourExerciseForm.PUSHUP_HEAD to Float.NaN, FourExerciseForm.PUSHUP_NECK to Float.NaN,
            FloorGaze.FOUND to 0f)
        val rep = form { missing }
        assertTrue(rep.correct)
        assertTrue(rep.outcomes.filter { it.check.feature in FourExerciseForm.pushupOptionalFeatures }
            .all { it.verdict == Verdict.ABSTAIN })
        val ctrl = FloorFeedbackController("푸시업", emptyList())
        val state = ctrl.update(0L, good() + missing, null, emptyList())
        assertEquals(FloorFeedbackPhase.MEASURING, state.phase)
        assertTrue(state.message.contains("확인하지 못했어요")); assertNull(state.speech)
        val withoutPosture = FourExerciseForm.filterPushupHead(good() + mapOf(FourExerciseForm.PUSHUP_POSTURE to 0f,
            FloorGaze.YAW to 60f, FourExerciseForm.PUSHUP_TURN to 60f))
        assertTrue(FourExerciseForm.pushupOptionalFeatures.none { it in withoutPosture })
    }

    @Test fun oneValidObservationUsesTheSameRepresentativeValuePolicyAsExistingExercises() {
        for (feature in listOf(FourExerciseForm.PUSHUP_HEAD, FourExerciseForm.PUSHUP_NECK)) {
            val rep = form { i -> mapOf(feature to if (i == 6)
                (if (feature == FourExerciseForm.PUSHUP_HEAD) 75f else -40f) else Float.NaN) }
            assertFalse(rep.correct)
            assertEquals(feature, rep.flagged.single().check.feature)
            assertEquals(1, rep.flagged.single().samples)
        }
        val rep = form { i -> mapOf(FloorGaze.FOUND to if (i == 6) 1f else 0f, FloorGaze.YAW to 60f) }
        assertFalse(rep.correct)
        assertEquals(FourExerciseForm.PUSHUP_TURN, rep.flagged.single().check.feature)
    }

    @Test fun missingOrNormalFrameDoesNotEraseTheRestOfTheRep() {
        for (key in listOf(FourExerciseForm.PUSHUP_HEAD, FourExerciseForm.PUSHUP_NECK, FloorGaze.YAW)) {
            val bad = if (key == FourExerciseForm.PUSHUP_NECK) -40f else 75f
            for (gap in listOf(Float.NaN, good().getValue(key))) {
                assertFalse(form { i -> mapOf(key to if (i == 6) gap else bad) }.correct)
            }
        }
    }

    @Test fun turnedFaceWithOccludedPoseHeadStillExcludesOnlyTheObservedTurn() {
        val rep = form { mapOf(FloorGaze.YAW to 60f, FourExerciseForm.PUSHUP_HEAD to Float.NaN,
            FourExerciseForm.PUSHUP_NECK to Float.NaN) }
        assertFalse(rep.correct)
        assertEquals(FourExerciseForm.PUSHUP_TURN, rep.flagged.single().check.feature)
        val (red, amber) = RuleHighlight.forRepForm(rep)
        assertTrue(red.isEmpty()); assertTrue(amber.contains(0))
    }
}
