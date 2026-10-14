package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** 반복 구간·가림·방향·준비 동작과 베타의 사용자 영향에 대한 계약 검사(§91). */
class RepFormRaiseTest {
    private val front = "프런트 레이즈"
    private val lateral = "사이드 레터럴 레이즈"

    private fun evaluator(ex: String = front) = RepFormEvaluator(
        RepFormSpecs.byExercise.getValue(ex), "forearm_vert_mean", 35f, priors = { _, _ -> null })

    private fun frame(signal: Float, left: Float = 160f, right: Float = 160f, pitch: Float = 0f, yaw: Float? = -30f): Map<String, Float> {
        val f = hashMapOf("forearm_vert_mean" to signal, "elbow_L" to left, "elbow_R" to right, "torso_pitch" to pitch, "knee_L" to 175f, "knee_R" to 175f, "upperarm_vert_L" to signal, "upperarm_vert_R" to signal)
        if (yaw != null) {
            val rad = Math.toRadians(yaw.toDouble())
            f[ViewEstimator.FEAT_COS] = cos(rad).toFloat()
            f[ViewEstimator.FEAT_SIN] = sin(rad).toFloat()
        }
        return f
    }

    private fun rep(ev: RepFormEvaluator, offset: Long = 0L, yaw: Float? = -30f,
                    modify: (Int, Map<String, Float>) -> Map<String, Float> = { _, f -> f }): RepFormRep {
        // 팔 아래(180) → 팔을 들어 수평(90) → 복귀. 신호가 내려갈 때 실제 팔은 올라간다.
        val signals = listOf(180f, 180f, 180f, 180f, 165f, 145f, 120f, 95f, 90f, 95f, 120f, 145f, 165f, 180f, 180f)
        signals.forEachIndexed { i, s -> ev.onFrame(offset + i * 300L, modify(i, frame(s, yaw = yaw))) }
        return ev.onCycle(offset + signals.lastIndex * 300L, 90f, 180f, offset + 900L)
    }

    private fun RepFormRep.named(name: String) = outcomes.first { it.check.id.endsWith("|$name") }

    @Test fun stableElbowDuringArmElevationIsNotMistakenForElbowBending() {
        for (ex in listOf(front, lateral)) {
            val r = rep(evaluator(ex))
            assertTrue(r.outcomes.filterNot { it.check.feature.startsWith("raise_") }.all { it.verdict == Verdict.OK })
            assertTrue(r.outcomes.filter { it.check.feature.startsWith("raise_") }.all { it.verdict == Verdict.ABSTAIN })
            assertFalse(r.judged) // 참고 검사만으로 '정확한 반복'을 인증하지 않는다.
            assertTrue(r.correct)
        }
    }

    @Test fun oppositeArmChangesDoNotCancelInAnAverage() {
        val ev = evaluator()
        val r = rep(ev) { i, f -> f + mapOf("elbow_L" to if (i in 6..10) 90f else 160f,
            "elbow_R" to if (i in 6..10) 160f else 90f) }
        assertEquals(Verdict.VIOLATION, r.named("왼팔 굽힘 변화").verdict)
        assertEquals(Verdict.VIOLATION, r.named("오른팔 굽힘 변화").verdict)
        val event = ev.eventFor(r, 6000L, gate = true)!!
        assertFalse(event.ship)
        assertFalse(event.gated)
        assertTrue(r.correct)
    }

    @Test fun backwardLeanIsComparedAtRaisedPhaseAgainstThisRepsRest() {
        val r = rep(evaluator()) { i, f -> f + ("torso_pitch" to if (i in 6..10) -30f else 0f) }
        assertEquals(Verdict.VIOLATION, r.named("상체 뒤로 기울기 변화").verdict)
        assertEquals(-30f, r.named("상체 뒤로 기울기 변화").value!!, 0.01f)
    }

    @Test fun constantBodyOffsetIsNotCalledMovement() {
        val r = rep(evaluator()) { _, f -> f + ("torso_pitch" to -25f) }
        assertEquals(Verdict.OK, r.named("상체 뒤로 기울기 변화").verdict)
    }

    @Test fun lateralBodySwayUsesOnlyTheRepetition() {
        val r = rep(evaluator(lateral)) { i, f -> f + ("torso_pitch" to if (i in 6..10) 40f else 0f) }
        assertEquals(Verdict.VIOLATION, r.named("몸통 앞뒤 흔들림").verdict)
    }

    @Test fun preparationFarBeforeTheCycleIsExcluded() {
        val ev = evaluator(lateral)
        for (i in 0..8) ev.onFrame(i * 300L, frame(180f, left = 30f, pitch = -70f))
        assertTrue(rep(ev, offset = 10000L).outcomes.filterNot { it.check.feature.startsWith("raise_") }.all { it.verdict == Verdict.OK })
    }

    @Test fun missingOneArmDoesNotBecomeZeroMotionAndOtherArmStillWorks() {
        val r = rep(evaluator()) { _, f -> f - "elbow_L" }
        assertEquals(Verdict.ABSTAIN, r.named("왼팔 굽힘 변화").verdict)
        assertEquals(Verdict.OK, r.named("오른팔 굽힘 변화").verdict)
    }

    @Test fun enoughSamplesButLowCoverageStillAbstains() {
        val r = rep(evaluator()) { i, f -> if (i in 4..8) f else f - "elbow_L" }
        assertEquals(Verdict.ABSTAIN, r.named("왼팔 굽힘 변화").verdict)
    }

    @Test fun nonFiniteAnglesAreMissingObservations() {
        val r = rep(evaluator()) { _, f -> f + mapOf("elbow_L" to Float.NaN, "elbow_R" to Float.POSITIVE_INFINITY) }
        assertTrue(r.outcomes.filter { it.check.bodyPart == "팔꿈치" }.all { it.verdict == Verdict.ABSTAIN })
    }

    @Test fun longOcclusionIsNotBridgedEvenWhenAllRemainingFramesAreValid() {
        val ev = evaluator()
        val signal = listOf(180f, 180f, 180f, 180f, 165f, 145f, 120f, 90f, 95f, 120f, 145f, 180f)
        signal.forEachIndexed { i, s -> ev.onFrame(i * 300L + if (i >= 8) 1500L else 0L, frame(s)) }
        val r = ev.onCycle(4800L, 90f, 180f, 900L)
        assertEquals(Verdict.ABSTAIN, r.named("왼팔 굽힘 변화").verdict)
        assertEquals("관절 관측이 오래 끊김", r.named("왼팔 굽힘 변화").abstainReason)
    }

    @Test fun missingRestReferenceAbstainsInsteadOfLearningRaisedPose() {
        val r = rep(evaluator()) { i, f -> if (i <= 3) f - "torso_pitch" else f }
        assertEquals(Verdict.ABSTAIN, r.named("상체 뒤로 기울기 변화").verdict)
    }

    @Test fun unknownViewAbstainsEvenAfterViewLock() {
        val ev = evaluator()
        repeat(3) { rep(ev, offset = it * 6000L) }
        assertTrue(rep(ev, offset = 18000L, yaw = null).outcomes.all { it.verdict == Verdict.ABSTAIN })
    }

    @Test fun frontCameraDoesNotJudgeSagittalBodyLean() {
        val r = rep(evaluator(), yaw = 0f)
        assertEquals(Verdict.ABSTAIN, r.named("상체 뒤로 기울기 변화").verdict)
        assertEquals(Verdict.OK, r.named("왼팔 굽힘 변화").verdict)
    }

    @Test fun majorityOfFramesMustHaveAnObservedView() {
        val r = rep(evaluator()) { i, f -> if (i < 8) f - ViewEstimator.FEAT_COS - ViewEstimator.FEAT_SIN else f }
        assertTrue(r.outcomes.all { it.verdict == Verdict.ABSTAIN })
    }

    @Test fun robustRangeSuppressesAnIsolatedOutlier() {
        assertEquals(0f, RepFormEvaluator.stat(List(10) { 160f } + 20f, RepFormStat.ROBUST_RANGE), 0.01f)
    }

    @Test fun raiseChecksRetainBetaStatusAndObservationRequirements() {
        for (ex in listOf(front, lateral)) for (c in RepFormSpecs.byExercise.getValue(ex)) {
            assertEquals(RuleStatus.BETA, c.status)
            assertEquals(c.feature=="upperarm_vert_L" || c.feature=="upperarm_vert_R" || c.feature=="raise_head_turn" || (c.feature.startsWith("raise_shoulder_lift") || c.feature.startsWith("raise_shoulder_gap")) || c.condition=="몸통 기울기(반복)",c.gates)
            assertTrue(c.requireObservedView)
            assertTrue(c.minCoverage >= 0.75f)
            assertTrue(c.minSamples >= 2)
        }
    }

    @Test fun barbellVariantsStillUseTheirOriginalFamilies() {
        for ((variant, original) in listOf("바벨 컬" to "덤벨 컬", "바벨 런지" to RepFormSpecs.LUNGE)) {
            val base = RepFormSpecs.byExercise.getValue(original)
            val copy = RepFormSpecs.byExercise.getValue(variant)
            assertEquals(base.size, copy.size)
            for ((a, b) in base.zip(copy)) {
                assertEquals(a.feature, b.feature)
                assertEquals(a.lo, b.lo)
                assertEquals(a.hi, b.hi)
                assertEquals(a.status, b.status)
                assertEquals(a.gates, b.gates)
            }
        }
    }

}
