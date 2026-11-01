package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** 새 반복 검사와 앱의 규칙·촬영 안내·로그 경계가 같은 정책을 쓰는지 확인한다(§123). */
class FourExercisePolicyTest {
    @Test fun raiseSessionCounterAndFormExcludeOnlyLowRepsThenResumeCounting() {
        for (ex in listOf("프런트 레이즈", "사이드 레터럴 레이즈")) {
            val rc = RepCounter.forSession(ex, floor = false)!!
            val ev = RepFormSpecs.evaluatorFor(ex, rc)!!
            val records = ArrayList<RepRecord>()
            val units = RepUnitAccumulator(RepUnit.CYCLE)
            val reps = ArrayList<RepFormRep>()
            var t = 0L
            fun frame(angle: Float) {
                val f = mapOf(FourExerciseForm.RAISE_SIGNAL to angle, "upperarm_vert_L" to angle, "upperarm_vert_R" to angle,
                    "view_cos" to .866f, "view_sin" to .5f)
                ev.onFrame(t, f)
                if (rc.onFrameFeatures(t, f)) {
                    val tally = RepUnitAccumulator.onCounterFrame(rc, t, records, units)
                    reps += tally.records.map { ev.onCycle(it.tMs, it.cycleMin, it.cycleMax, it.cycleStartMs, it.side) }
                }
                t += 300L
            }
            repeat(6) { frame(175f) }
            for (peak in listOf(90f, 140f, 90f)) {
                for (i in 1..4) frame(175f + (peak - 175f) * i / 4)
                repeat(5) { frame(peak) }
                for (i in 1..4) frame(peak + (175f - peak) * i / 4)
                repeat(6) { frame(175f) }
            }
            assertEquals(3, rc.reps)
            assertEquals(listOf(true, false, true), reps.map { it.correct })
            assertEquals(2, ev.summary().correct)
            // TRACK 원장에는 감지한 3회가 그대로 남는다. COACH만 correct=false인 한 회를 표시 횟수에서 뺀다.
            assertEquals(3, units.completed)
        }
    }

    @Test fun registryIncludesProvisionalChecksAndBlindSpots() {
        val rs = PostureRuleSet("test", "", emptyList()).plusRepForm()
        for ((ex, checks) in FourExerciseForm.byExercise) {
            assertEquals(checks.size, rs.rules.count { it.exercise == ex && it.kind == "rep_form" && it.status == RuleStatus.BETA })
            assertTrue(rs.rules.any { it.exercise == ex && it.kind == "scope" && it.status == RuleStatus.EXCLUDE })
            val scope = PostureScope.of(rs, ex)
            assertEquals(FourExerciseForm.scopeLines[ex], scope.startLine)
            assertFalse(scope.hasAnyJudgement)
        }
    }

    @Test fun pushupPlacementFailureIsAnObservationAndDoesNotSpeakFormCorrection() {
        val ctrl = FloorFeedbackController("푸시업", emptyList())
        val result = ctrl.update(1000L, mapOf(FourExerciseForm.PUSHUP_POSTURE to 0f), null, emptyList(), anchored = true)
        assertEquals(FloorFeedbackPhase.UNAVAILABLE, result.phase)
        assertNull(result.speech)
        assertTrue(result.message.contains("엎드린 자세를 확인하지 못했어요"))
    }

    @Test fun logNamesTheActualCountingSignalAndPlacementPrerequisite() {
        val e = RepEngineLog.of(RepCounter.forSession("푸시업", "min", .71f, floor = true)!!)
        assertEquals(FourExerciseForm.PUSHUP_SIGNAL, e.feature)
        assertEquals(FourExerciseForm.PUSHUP_POSTURE, e.requiredPostureFeature)
        assertNull(e.romThreshold)
    }

    @Test fun legacyPushupDepthCannotLeakIntoLiveFeedback() {
        val rule = PostureRule("legacy", "푸시업", "가슴 이동", null, RuleStatus.BETA, null,
            "wrist_shoulder_d", "wrist_shoulder_d", "rep", "floor2d", ">", .71f, "C", "", Float.NaN, Float.NaN, 0, true,
            emptyList(), kind = "rep", repConfig = RepRuleConfig("min", .71f, .34f, 2))
        val controller = FloorFeedbackController("푸시업", listOf(rule))
        repeat(8) { i ->
            val t = i * 3000L
            val r = controller.update(t, mapOf(FourExerciseForm.PUSHUP_POSTURE to 1f, "wrist_shoulder_d" to .9f),
                null, emptyList(), RepRecord(t, .9f, 1.4f, null))
            assertNotEquals(FloorFeedbackPhase.ATTENTION, r.phase); assertNull(r.speech)
        }
    }

    @Test fun missingMeasurementsCannotProduceCleanReport() {
        val rs = PostureRuleSet("test", "", emptyList()).plusRepForm()
        val ex = "프런트 레이즈"
        val results = rs.rules.filter { it.exercise == ex && it.kind == "rep_form" }.map {
            RuleResult(it, Verdict.ABSTAIN, null, 0, abstainReason = "촬영 방향 확인 필요")
        }
        val report = PostureSetReport.build("test", ex, ex, CoachMode.COACH, 20, false, results, emptyList(), 3, 0, 3000)
        assertEquals(SetVerdict.UNJUDGED, report.verdict); assertNull(report.accuracy)
        assertFalse(report.voiceLine.contains("깨끗"))
    }
}
