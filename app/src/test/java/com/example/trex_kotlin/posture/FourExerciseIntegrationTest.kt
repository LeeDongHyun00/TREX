package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class FourExerciseIntegrationTest {
    @Test fun strictUnitAccumulatorKeepsSameSideAndUnknownOutOfPairs() {
        val acc=RepUnitAccumulator(RepUnit.SIDE_EACH)
        assertNull(acc.offerSide(1,null,StepSide.LEFT)); assertNull(acc.offerSide(2,null,StepSide.LEFT))
        assertNull(acc.offerSide(3,null,null)); assertEquals(0,acc.completed)
        assertTrue(acc.pendingHalf); assertEquals(1L,acc.pendingHalfAtMs)
        assertNotNull(acc.offerSide(4,null,StepSide.RIGHT)); assertEquals(1,acc.completed)
        assertEquals(2L,acc.pendingHalfAtMs); acc.onCounterCycleReset()
        assertNotNull(acc.offerSide(5,null,StepSide.RIGHT)); assertEquals(2,acc.completed); assertFalse(acc.pendingHalf)
        assertTrue(acc.reps.all { it.valid==null })
    }
    @Test fun legacyProxyCannotRemainShipOrScoreAlongsideNewBeta() {
        val old=PostureRule("스탠딩 사이드 크런치|척추의 중립", "스탠딩 사이드 크런치", "척추의 중립", null, RuleStatus.SHIP, "기존 대리 피처",
            "shoulder_h_R__mean", "shoulder_h_R", "mean", "world", "<", .98f, "C", "정면", .8f,.8f,60,true,emptyList())
        val rs=PostureRuleSet("test","",listOf(old)).plusRepForm()
        assertEquals(RuleStatus.EXCLUDE,rs.rules.first { it.id==old.id }.status)
        assertTrue(rs.rules.filter { it.exercise==old.exercise && it.kind=="rep_form" }.all { it.status==RuleStatus.BETA })
        assertFalse(rs.rules.any { it.exercise==old.exercise && it.status==RuleStatus.SHIP })
        val scope=PostureScope.of(rs,old.exercise)
        assertFalse(scope.hasAnyJudgement); assertTrue(scope.provisionalOnly)
        assertTrue(scope.startLine!!.contains("검증 중"))
    }
    @Test fun unjudgedBetaIsNotSerializedAsCorrect() {
        val checks=FourExerciseForm.checks(FourExercise.KNEE_UP)
        val rc=RepCounter.forSession("스탠딩 니업",floor=false)!!
        val rf=RepFormSpecs.evaluatorFor("스탠딩 니업",rc)!!
        rf.onCycle(1000,80f,175f,0,StepSide.LEFT)
        val summary=rf.summary()
        assertEquals(0,summary.correct)
        assertEquals("UNJUDGED",summary.toLog(0).reps.single().formState)
        assertFalse(summary.toLog(0).reps.single().correct)
        assertTrue(checks.all { it.status==RuleStatus.BETA })
    }
    @Test fun sessionEngineLogNamesNewEngineAndKeepsSeed() {
        val rc=RepCounter.forSession("스탠딩 니업",floor=false)!!
        val log=RepEngineLog.of(rc)
        assertEquals(FourExerciseTracker.VERSION,log.engine)
        assertEquals(FourExerciseTracker.MAX_GAP_MS,log.maxGapMs)
        assertNull(log.romThreshold)
    }
}
