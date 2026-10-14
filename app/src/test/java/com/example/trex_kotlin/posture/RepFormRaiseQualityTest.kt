package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** 레이즈 관측 구간·기준 자세·양쪽 무릎 검사. */
class RepFormRaiseQualityTest {
    private val front = "프런트 레이즈"
    private val lateral = "사이드 레터럴 레이즈"
    // 전체 25프레임 중 팔을 든 BOTTOM은 10~12번 세 프레임이다.
    private val signals = listOf(180f, 180f, 180f, 180f, 180f, 170f, 160f, 150f, 140f, 130f,
        110f, 90f, 110f, 130f, 140f, 150f, 160f, 170f, 180f, 180f, 180f, 180f, 180f, 180f, 180f)

    private fun evaluator(ex: String = front) = RepFormEvaluator(
        RepFormSpecs.byExercise.getValue(ex), "forearm_vert_mean", 35f, priors = { _, _ -> null })

    private fun direction(f: Map<String, Float>, yaw: Float?): Map<String, Float> {
        val out = (f - ViewEstimator.FEAT_COS - ViewEstimator.FEAT_SIN).toMutableMap()
        if (yaw != null) {
            val r = Math.toRadians(yaw.toDouble())
            out[ViewEstimator.FEAT_COS] = cos(r).toFloat()
            out[ViewEstimator.FEAT_SIN] = sin(r).toFloat()
        }
        return out
    }

    private fun frame(signal: Float, yaw: Float = 30f): Map<String, Float> = direction(mapOf(
        "forearm_vert_mean" to signal, "elbow_L" to 160f, "elbow_R" to 160f,
        "torso_pitch" to 0f, "knee_L" to 175f, "knee_R" to 175f), yaw)

    private fun rep(ev: RepFormEvaluator = evaluator(), offset: Long = 0L, yaw: Float = 30f,
                    delay: (Int) -> Long = { 0L },
                    modify: (Int, Map<String, Float>) -> Map<String, Float> = { _, f -> f }): RepFormRep {
        signals.forEachIndexed { i, s -> ev.onFrame(offset + i * 300L + delay(i), modify(i, frame(s, yaw))) }
        return ev.onCycle(offset + signals.lastIndex * 300L + delay(signals.lastIndex), 90f, 180f, offset + 1200L)
    }

    private fun RepFormRep.named(name: String) = outcomes.first { it.check.id.endsWith("|$name") }
    private fun assertElbowsAbstain(r: RepFormRep) {
        assertEquals(Verdict.ABSTAIN, r.named("왼팔 굽힘 변화").verdict)
        assertEquals(Verdict.ABSTAIN, r.named("오른팔 굽힘 변화").verdict)
    }

    @Test fun unknownRaisedPhaseDoesNotBorrowTheRestOfTheRepetitionsView() {
        val r = rep { i, f -> if (i in 10..12) direction(f + ("torso_pitch" to -30f), null) else f }
        assertEquals(Verdict.ABSTAIN, r.named("상체 뒤로 기울기 변화").verdict)
    }

    @Test fun frontFacingRaisedPhaseDoesNotBorrowAnObliqueRestView() {
        val r = rep { i, f -> if (i in 10..12) direction(f + ("torso_pitch" to -30f), 0f) else f }
        assertEquals(Verdict.ABSTAIN, r.named("상체 뒤로 기울기 변화").verdict)
    }

    @Test fun unknownRestReferenceDoesNotBorrowTheRaisedPhasesView() {
        val r = rep { i, f -> if (i <= 4) direction(f, null) else f + ("torso_pitch" to -30f) }
        assertEquals(Verdict.ABSTAIN, r.named("상체 뒤로 기울기 변화").verdict)
    }

    @Test fun frontFacingRestReferenceDoesNotBorrowAnObliqueRaisedView() {
        val r = rep { i, f -> if (i <= 4) direction(f, 0f) else f + ("torso_pitch" to -30f) }
        assertEquals(Verdict.ABSTAIN, r.named("상체 뒤로 기울기 변화").verdict)
    }

    @Test fun aDisconnectedRestReferenceCannotProduceALeanVerdict() {
        val r = rep(delay = { i -> if (i >= 5) 1500L else 0L }) { i, f ->
            if (i >= 5) f + ("torso_pitch" to -30f) else f
        }
        assertEquals(Verdict.ABSTAIN, r.named("상체 뒤로 기울기 변화").verdict)
    }

    @Test fun missingTimeBetweenRestAndMovementAlsoInvalidatesCycleChecks() {
        val r = rep(delay = { i -> if (i >= 5) 1500L else 0L })
        assertElbowsAbstain(r)
        val lateralRep = rep(evaluator(lateral), yaw = -30f, delay = { i -> if (i >= 5) 1500L else 0L })
        for (name in listOf("몸통 앞뒤 흔들림", "왼무릎 굽힘 변화", "오른무릎 굽힘 변화"))
            assertEquals(Verdict.ABSTAIN, lateralRep.named(name).verdict)
    }

    @Test fun gapsInsideTheRestReferenceAlsoAbstain() {
        val r = rep(delay = { i -> if (i >= 2) 900L else 0L }) { i, f ->
            if (i >= 5) f + ("torso_pitch" to -30f) else f
        }
        assertEquals(Verdict.ABSTAIN, r.named("상체 뒤로 기울기 변화").verdict)
    }

    @Test fun oneMissingArmAndItsMissingCounterSignalStayInTheCoverageDenominator() {
        // 각 단절은 600ms 이내다. 연속성 검사가 아니라 관측 비율 검사가 이 반복을 유보해야 한다.
        val missing = setOf(6, 8, 10, 12, 14, 16)
        val r = rep { i, f -> if (i in missing) f - "elbow_L" - "forearm_vert_mean" else f }
        assertElbowsAbstain(r)
    }

    @Test fun completelyMissingFramesStayInTheCoverageDenominator() {
        val missing = setOf(6, 8, 10, 12, 14, 16)
        val r = rep { i, f -> if (i in missing) emptyMap() else f }
        assertElbowsAbstain(r)
    }

    @Test fun nonFiniteCounterValuesAreNotUsableObservations() {
        val invalid = setOf(6, 8, 10, 12, 14, 16)
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val r = rep { i, f -> if (i in invalid) f + ("forearm_vert_mean" to value) else f }
            assertElbowsAbstain(r)
        }
    }

    @Test fun oppositeKneeChangesCannotCancelInTheirMean() {
        val ev = evaluator(lateral)
        val r = rep(ev, yaw = -30f) { i, f ->
            f + mapOf("knee_L" to if (i in 10..12) 145f else 175f,
                "knee_R" to if (i in 10..12) 175f else 145f)
        }
        assertEquals(Verdict.VIOLATION, r.named("왼무릎 굽힘 변화").verdict)
        assertEquals(Verdict.VIOLATION, r.named("오른무릎 굽힘 변화").verdict)
        assertTrue(r.correct)
        assertFalse(r.judged)
        val event = ev.eventFor(r, 10000L, gate = true)!!
        assertFalse(event.ship)
        assertFalse(event.gated)
    }

    @Test fun stableKneesRemainOkayAndAHiddenKneeIsNotZeroMovement() {
        val r = rep(evaluator(lateral), yaw = -30f)
        assertEquals(Verdict.OK, r.named("왼무릎 굽힘 변화").verdict)
        assertEquals(Verdict.OK, r.named("오른무릎 굽힘 변화").verdict)
        val hidden = rep(evaluator(lateral), yaw = -30f) { _, f -> f - "knee_L" }
        assertEquals(Verdict.ABSTAIN, hidden.named("왼무릎 굽힘 변화").verdict)
        assertEquals(Verdict.OK, hidden.named("오른무릎 굽힘 변화").verdict)
    }

    @Test fun preparationDoesNotBecomeBodyOrKneeMovementInTheNextRepetition() {
        val ev = evaluator(lateral)
        for (i in 0..8) ev.onFrame(i * 300L, frame(180f, -30f) + mapOf(
            "elbow_L" to 30f, "torso_pitch" to -70f, "knee_L" to 80f, "knee_R" to 80f))
        val r = rep(ev, offset = 10000L, yaw = -30f)
        for (name in listOf("왼팔 굽힘 변화", "오른팔 굽힘 변화", "몸통 앞뒤 흔들림", "왼무릎 굽힘 변화", "오른무릎 굽힘 변화"))
            assertEquals(name, Verdict.OK, r.named(name).verdict)
    }
}
