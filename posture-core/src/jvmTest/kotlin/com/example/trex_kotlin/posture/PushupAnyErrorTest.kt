// Android 정본 테스트 — tools/sync_ios_core.py
package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** §130: 한 번의 유효한 오류 관측을 해당 회에만 반영한다. 실기기 검출 정확도 시험은 아니다. */
class PushupAnyErrorTest {
    private fun good() = mapOf(FourExerciseForm.PUSHUP_POSTURE to 1f, FourExerciseForm.PUSHUP_HIP to 0f,
        FourExerciseForm.PUSHUP_KNEE to 175f, FourExerciseForm.PUSHUP_HEAD to 30f,
        FourExerciseForm.PUSHUP_NECK to 0f, FloorGaze.FOUND to 1f, FloorGaze.YAW to 0f)
    private val faults = listOf(
        mapOf(FourExerciseForm.PUSHUP_HIP to -.2f), mapOf(FourExerciseForm.PUSHUP_HIP to .2f),
        mapOf(FourExerciseForm.PUSHUP_KNEE to 110f), mapOf(FourExerciseForm.PUSHUP_HEAD to 75f),
        mapOf(FourExerciseForm.PUSHUP_HEAD to -50f), mapOf(FourExerciseForm.PUSHUP_NECK to -40f),
        mapOf(FloorGaze.YAW to 60f),
    )

    private fun form(frameMs: Long = 200L, extra: (Int) -> Map<String, Float>): RepFormRep {
        val ev = RepFormSpecs.evaluatorFor("푸시업", RepCounter.forSession("푸시업", floor = true)!!)!!
        val heights = listOf(1f,1f,1f,.8f,.5f,.3f,.2f,.2f,.3f,.5f,.8f,1f,1f)
        val elbows = listOf(175f,175f,175f,160f,125f,100f,90f,90f,100f,125f,160f,175f,175f)
        heights.forEachIndexed { i, h -> ev.onFrame(i * frameMs, FourExerciseForm.filterPushupHead(good() + mapOf(
            FourExerciseForm.PUSHUP_SIGNAL to h, FourExerciseForm.PUSHUP_ELBOW to elbows[i]) + extra(i))) }
        return ev.onCycle(12 * frameMs, .2f, 1f)
    }

    @Test fun singleBodyOrHeadFaultAnywhereInMovementExcludesTheCycle() {
        for (fault in faults) for (i in 3..12) {
            val rep = form { if (it == i) fault else emptyMap() }
            assertFalse("$fault, 프레임 $i", rep.correct)
            assertEquals(1, rep.flagged.size)
            assertTrue(rep.flagged.single().countOnlyGate)
        }
    }

    @Test fun singleFrameNeedsNoHoldTimeOrBadFrameRatio() {
        for (fault in faults) for (ms in listOf(50L,200L)) {
            assertFalse(form(ms) { i -> if (i == 6) fault else emptyMap() }.correct)
        }
    }

    @Test fun correctionOrMissingHeadAfterErrorCannotEraseTheEarlierObservation() {
        for (fault in faults.takeLast(4)) {
            val rep = form { i -> when {
                i == 4 -> fault
                i > 4 -> mapOf(FourExerciseForm.PUSHUP_HEAD to Float.NaN,
                    FourExerciseForm.PUSHUP_NECK to Float.NaN, FloorGaze.FOUND to 0f)
                else -> emptyMap()
            } }
            assertFalse(rep.correct)
            assertEquals(1, rep.flagged.size)
        }
    }

    @Test fun oppositeHeadErrorsInOneCycleCannotCancelEachOther() {
        val rep = form { i -> when (i) {
            4 -> mapOf(FourExerciseForm.PUSHUP_HEAD to 75f)
            9 -> mapOf(FourExerciseForm.PUSHUP_HEAD to -50f)
            else -> emptyMap()
        } }
        assertFalse(rep.correct)
        assertEquals(setOf("고개 과들림(반복)","고개 과숙임(반복)"), rep.flagged.map { it.check.condition }.toSet())
    }

    @Test fun armErrorsUseExtremesOnlyWithinTheirOwnPhase() {
        // 내려가는 동안 160도인 팔이나 올라오는 동안 125도인 팔은 범위 오류가 아니다.
        assertTrue(form { emptyMap() }.correct)
        for (index in listOf(0,1,2)) {
            val rep = form { i -> if (i == index) mapOf(FourExerciseForm.PUSHUP_ELBOW to 140f) else emptyMap() }
            assertEquals("시작 팔 펴기(반복)", rep.flagged.single().check.condition)
            assertFalse(rep.correct)
        }
        for (index in 5..8) {
            val rep = form { i -> if (i == index) mapOf(FourExerciseForm.PUSHUP_ELBOW to 130f) else emptyMap() }
            assertEquals("팔 굽힘 범위(반복)", rep.flagged.single().check.condition)
            assertFalse(rep.correct)
        }
        assertTrue(form { i -> if (i == 3 || i == 10) mapOf(FourExerciseForm.PUSHUP_ELBOW to 140f) else emptyMap() }.correct)
    }

    @Test fun boundariesAndUnobservedOrNonFiniteValuesDoNotBecomeErrors() {
        for (hip in listOf(-.08f,.08f)) for (head in listOf(-35f,60f)) {
            assertTrue(form { mapOf(FourExerciseForm.PUSHUP_HIP to hip, FourExerciseForm.PUSHUP_HEAD to head,
                FourExerciseForm.PUSHUP_KNEE to 150f, FourExerciseForm.PUSHUP_NECK to -25f, FloorGaze.YAW to 25f) }.correct)
        }
        for (bad in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            val rep = form { mapOf(FourExerciseForm.PUSHUP_HEAD to bad, FourExerciseForm.PUSHUP_NECK to bad,
                FourExerciseForm.PUSHUP_HIP to bad, FourExerciseForm.PUSHUP_KNEE to bad,
                FloorGaze.FOUND to 0f, FloorGaze.YAW to 60f) }
            assertTrue(rep.correct)
            assertTrue(rep.outcomes.filter { it.check.phase == RepPhase.CYCLE }.all { it.verdict == Verdict.ABSTAIN })
        }
    }

    @Test fun oneBriefFaultExcludesOnlyItsOwnRepInCounterAndCoachTally() {
        val combined = mapOf(FourExerciseForm.PUSHUP_HIP to .2f, FourExerciseForm.PUSHUP_KNEE to 110f,
            FourExerciseForm.PUSHUP_HEAD to -50f, FourExerciseForm.PUSHUP_NECK to -40f)
        for (fault in faults + listOf(combined)) {
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
            repeat(3) { cycle ->
                for (h in listOf(.8f,.6f,.4f,.2f)) frame(h)
                repeat(5) { j -> frame(.2f, if (cycle == 1 && j == 2) fault else emptyMap()) }
                for (h in listOf(.4f,.6f,.8f,1f)) frame(h)
                repeat(8) { frame(1f) }
            }
            // §132: 몸통 정렬 이탈은 동작 확인 단계에서 제외된다. 다른 오류는 기존 COACH 평가가 제외한다.
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
            val event = ev.eventFor(reps[1], t, gate = true)!!
            assertTrue(event.gated); assertFalse(event.ship)
            assertTrue(event.message.contains("세지 않았어요")); assertFalse(ev.summary().hasShip)
        }
    }

    @Test fun missingFaceCannotEraseAnEarlierTurnAndCannotInventANewOne() {
        val rep = form { i -> mapOf(FloorGaze.FOUND to if (i == 6) 1f else 0f, FloorGaze.YAW to 60f) }
        assertFalse(rep.correct)
        assertEquals(FourExerciseForm.PUSHUP_TURN, rep.flagged.single().check.feature)
        val unknown = form { mapOf(FloorGaze.FOUND to 0f, FloorGaze.YAW to 60f) }
        assertTrue(unknown.correct)
        assertEquals(Verdict.ABSTAIN, unknown.outcomes.single { it.check.feature == FourExerciseForm.PUSHUP_TURN }.verdict)
    }
}
