package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** 굿모닝의 반복 구간, 관측 부족, 방향별 판정 검사. */
class RepFormGoodMorningTest {
    private val exercise = "굿모닝"
    private val knees = "무릎 펴짐"
    private val shoulders = "어깨 높이 흔들림"
    private val signals = listOf(175f, 175f, 175f, 175f, 160f, 140f, 115f, 95f, 90f, 95f, 115f, 140f, 160f, 175f, 175f)

    private fun evaluator(): RepFormEvaluator =
        RepFormSpecs.evaluatorFor(exercise, RepCounter.forExercise(exercise)!!)!!

    private fun frame(signal: Float, yaw: Float? = -30f): Map<String, Float> {
        val values = hashMapOf("hip_mean" to signal, "goodmorning_knee_oblique_L" to 155f,
            "goodmorning_shoulder_asym" to 0.02f, "torso_pitch" to 175f - signal)
        if (yaw != null) {
            val rad = Math.toRadians(yaw.toDouble())
            values[ViewEstimator.FEAT_COS] = cos(rad).toFloat()
            values[ViewEstimator.FEAT_SIN] = sin(rad).toFloat()
        }
        return values
    }

    private fun rep(ev: RepFormEvaluator, offset: Long = 0L, yaw: Float? = -30f, gapAt: Int? = null,
                    modify: (Int, Map<String, Float>) -> Map<String, Float> = { _, values -> values }): RepFormRep {
        fun time(i: Int) = offset + i * 300L + if (gapAt != null && i >= gapAt) 1500L else 0L
        signals.forEachIndexed { i, signal -> ev.onFrame(time(i), modify(i, frame(signal, yaw))) }
        return ev.onCycle(time(signals.lastIndex), 90f, 175f, offset + 900L)
    }

    private fun RepFormRep.named(name: String) = outcomes.first { it.check.id == "repform|$exercise|$name" }

    @Test fun hipHingeAndLargeTorsoInclinationAreNotTreatedAsKneeErrors() {
        val r = rep(evaluator())
        assertEquals(Verdict.OK, r.named(knees).verdict)
        assertEquals(155f, r.named(knees).value!!, 0.001f)
        assertEquals(Verdict.ABSTAIN, r.named(shoulders).verdict)
        assertFalse(r.judged)
    }

    @Test fun kneeCheckUsesBentPhaseAndAllowsBothExistingValidatedDirections() {
        for ((yaw, view) in listOf(-30f to "D", -130f to "E")) {
            val normal = rep(evaluator(), yaw = yaw) { i, values ->
                values + ("goodmorning_knee_oblique_L" to if (i in 6..10) 155f else 175f)
            }
            assertEquals(view, normal.view)
            assertEquals(Verdict.OK, normal.named(knees).verdict)
            val straight = rep(evaluator(), yaw = yaw) { i, values ->
                values + ("goodmorning_knee_oblique_L" to if (i in 6..10) 179f else 155f)
            }
            assertEquals(Verdict.VIOLATION, straight.named(knees).verdict)
            assertEquals(179f, straight.named(knees).value!!, 0.001f)
        }
    }

    @Test fun frontViewMeasuresShoulderSwayAndAbstainsFromKneeAngle() {
        val r = rep(evaluator(), yaw = 0f) { i, values ->
            values + mapOf("goodmorning_knee_oblique_L" to 175f, "goodmorning_shoulder_asym" to if (i % 2 == 0) 0.12f else -0.12f)
        }
        assertEquals(Verdict.VIOLATION, r.named(shoulders).verdict)
        assertEquals(Verdict.ABSTAIN, r.named(knees).verdict)
    }

    @Test fun constantShoulderHeightOffsetIsNotCalledSway() {
        val r = rep(evaluator(), yaw = 0f) { _, values -> values + ("goodmorning_shoulder_asym" to 0.2f) }
        assertEquals(Verdict.OK, r.named(shoulders).verdict)
        assertEquals(0f, r.named(shoulders).value!!, 0.00001f)
    }

    @Test fun preparationMovementIsExcludedFromTheRepetition() {
        val ev = evaluator()
        for (i in 0..8) ev.onFrame(i * 300L, frame(175f, yaw = 0f) +
            ("goodmorning_shoulder_asym" to if (i % 2 == 0) 0.8f else -0.8f))
        val r = rep(ev, offset = 10000L, yaw = 0f)
        assertEquals(Verdict.OK, r.named(shoulders).verdict)
        assertEquals(0f, r.named(shoulders).value!!, 0.00001f)
    }

    @Test fun missingAndNonFiniteFeaturesAreNotReportedAsNormal() {
        for ((yaw, name, feature) in listOf(Triple(-30f, knees, "goodmorning_knee_oblique_L"), Triple(0f, shoulders, "goodmorning_shoulder_asym"))) {
            val missing = rep(evaluator(), yaw = yaw) { _, values -> values - feature }
            assertEquals(Verdict.ABSTAIN, missing.named(name).verdict)
            for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
                val r = rep(evaluator(), yaw = yaw) { _, values -> values + (feature to invalid) }
                assertEquals(Verdict.ABSTAIN, r.named(name).verdict)
            }
        }
    }

    @Test fun isolatedKneeObservationDoesNotRepresentTheWholeBottom() {
        val r = rep(evaluator()) { i, values -> if (i == 8) values else values - "goodmorning_knee_oblique_L" }
        assertEquals(Verdict.ABSTAIN, r.named(knees).verdict)
    }

    @Test fun enoughShoulderSamplesWithLowCoverageStillAbstain() {
        val r = rep(evaluator(), yaw = 0f) { i, values -> if (i in 4..9) values else values - "goodmorning_shoulder_asym" }
        assertEquals(Verdict.ABSTAIN, r.named(shoulders).verdict)
    }

    @Test fun longObservationGapIsNotBridgedForEitherCheck() {
        for ((yaw, name) in listOf(-30f to knees, 0f to shoulders)) {
            val r = rep(evaluator(), yaw = yaw, gapAt = 8)
            assertEquals(Verdict.ABSTAIN, r.named(name).verdict)
            assertEquals("관절 관측이 오래 끊김", r.named(name).abstainReason)
        }
    }

    @Test fun unknownDirectionStillAbstainsAfterViewLock() {
        for (yaw in listOf(0f, -30f)) {
            val ev = evaluator()
            repeat(3) { rep(ev, offset = it * 6000L, yaw = yaw) }
            val r = rep(ev, offset = 18000L, yaw = null)
            assertTrue(r.outcomes.all { it.verdict == Verdict.ABSTAIN })
        }
    }

    @Test fun straightKneeOnlyIsAdvisoryWithoutCertifyingValidatedScores() {
        val ev = evaluator()
        repeat(2) { index ->
            val r = rep(ev, offset = index * 6000L) { _, values -> values + ("goodmorning_knee_oblique_L" to 179f) }
            assertEquals(Verdict.VIOLATION, r.named(knees).verdict)
            assertFalse(r.judged)
            assertTrue(r.correct)
            assertFalse(r.named(knees).gate)
            assertFalse(RequestedFormCoaching.excluded(r))
            assertTrue(r.consecutiveShip.isEmpty())
            val event = ev.eventFor(r, index * 6000L + 4500L, gate = true)!!
            assertFalse(event.ship)
            assertFalse(event.gated)
        }
    }

    @Test fun populationStandardDeviationMatchesExistingWindowAggregation() {
        assertEquals(0.1f, RepFormEvaluator.stat(listOf(-0.1f, 0.1f, -0.1f, 0.1f), RepFormStat.STD), 0.00001f)
        assertEquals(0f, RepFormEvaluator.stat(List(6) { 0.2f }, RepFormStat.STD), 0.00001f)
    }

    @Test fun oldKneeAndBothSpineWindowRulesAreDemotedTogether() {
        val names = listOf("무릎 구부린채 고정", "척추의 중립[all]", "척추의 중립[lateral]")
        val legacy = names.map { name ->
            val knee = name == "무릎 구부린채 고정"
            val feature = if (knee) "knee_minside" else "shoulder_asym"
            val stat = if (knee) "mean" else "std"
            PostureRule("$exercise|$name", exercise, name.substringBefore('['),
                if (knee) null else name.substringAfter('[').removeSuffix("]"), RuleStatus.SHIP, null,
                "${feature}__$stat", feature, stat, "world", ">", if (knee) 160.035034f else 0.052006f,
                if (knee) "D" else "C", "", 0.9f, 0.8f, 60, true, emptyList())
        }
        val merged = PostureRuleSet("mp_v0.1", "", legacy).plusRepForm()
        for (old in legacy) {
            val rule = merged.rules.first { it.id == old.id }
            assertEquals(RuleStatus.BETA, rule.status)
            assertTrue(rule.cautions.isNotEmpty())
            val target = RepFormSpecs.supersedes.getValue(old.id)
            assertEquals(RuleStatus.BETA, merged.rules.first { it.id == target }.status)
        }
    }

    @Test fun bottomBoundarySignalLossStaysInTheCoverageDenominator() {
        // 바닥은 6~10번이다. 가운데 두 값만 남아도 바닥 전체를 관측한 것으로 세지 않는다.
        val missing = setOf(6, 9, 10)
        for (dropEntireFrame in listOf(false, true)) {
            val r = rep(evaluator()) { i, values ->
                if (i !in missing) values else if (dropEntireFrame) emptyMap() else values - "hip_mean"
            }
            assertEquals(Verdict.ABSTAIN, r.named(knees).verdict)
            assertEquals(2, r.named(knees).samples)
        }
    }

    @Test fun nonFiniteBottomBoundarySignalsDoNotHideLostObservations() {
        val missing = setOf(6, 9, 10)
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val r = rep(evaluator()) { i, values ->
                if (i in missing) values + ("hip_mean" to invalid) else values
            }
            assertEquals(Verdict.ABSTAIN, r.named(knees).verdict)
            assertEquals(2, r.named(knees).samples)
        }
    }
}
