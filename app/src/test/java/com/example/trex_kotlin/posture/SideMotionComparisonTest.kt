package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class SideMotionComparisonTest {
    @Test fun alternatingArmRangesHaveSeparateReferences() {
        val c=SideMotionComparison(MotionContracts.forExercise("덤벨 컬")!!,emptyList())
        var time=1000L
        fun cycle(side: MotionSide, amp: Float): ComparisonSnapshot {
            val shape=listOf(0f,0f,.3f,.7f,1f,1f,.7f,.3f,0f,0f)
            var result=ComparisonSnapshot()
            for((i,p) in shape.withIndex()) {
                time+=100
                val features=mapOf("elbow_L" to if(side==MotionSide.LEFT) 170-amp*p else 170f,
                    "elbow_R" to if(side==MotionSide.RIGHT) 170-amp*p else 170f)
                result=c.add(time,features,if(i==shape.lastIndex) listOf(MotionCompletion(time,time,side,0f,0f)) else emptyList(),true)
            }
            return result
        }
        repeat(5) { cycle(MotionSide.LEFT,90f); cycle(MotionSide.RIGHT,45f) }
        val left=cycle(MotionSide.LEFT,90f); val right=cycle(MotionSide.RIGHT,45f)
        assertNotEquals(ComparisonState.CHANGED,left.state); assertNotEquals(ComparisonState.CHANGED,right.state)
        assertTrue(left.values.all { it.metric.feature=="elbow_L" })
        assertTrue(right.values.all { it.metric.feature=="elbow_R" })
        assertEquals(90f,left.values.single { it.phase==ComparisonPhase.RANGE }.initial,.001f)
        assertEquals(45f,right.values.single { it.phase==ComparisonPhase.RANGE }.initial,.001f)
    }
    @Test fun visibleSideIsSelectedByRelevantJointQuality() {
        val counter=MotionRepCounter.forExercise("푸시업")!!
        for(i in 0..8) {
            val p=if(i<=4)i/4f else (8-i)/4f
            counter.onFrame(i*150L,mapOf("elbow_ang_L" to 170f,"wrist_shoulder_d_L" to 1.4f,
                "elbow_ang_R" to 170-80*p,"wrist_shoulder_d_R" to 1.4f-.6f*p,
                "elbow_ang_L_quality" to .51f,"wrist_shoulder_d_L_quality" to .51f,
                "elbow_ang_R_quality" to .95f,"wrist_shoulder_d_R_quality" to .95f,
                "wrist_shoulder_d" to 1.4f-.6f*p))
        }
        assertEquals(1,counter.reps); assertEquals(MotionSide.RIGHT,counter.lastCompletion!!.side)
    }
    @Test fun stillAndUnmatchedMovementAreNotCalledAnExerciseRep() {
        val tracker=MotionActivityTracker(); val contract=MotionContracts.forExercise("바벨 스쿼트")!!
        repeat(20) { tracker.update(it*100L,contract,MotionPhase.READY,mapOf("knee_L" to 170f)) }
        assertEquals(MotionActivity.STILL,tracker.update(2000,contract,MotionPhase.READY,mapOf("knee_L" to 170f)))
        assertEquals(MotionActivity.UNMATCHED,tracker.update(2100,contract,MotionPhase.READY,mapOf("knee_L" to 160f)))
        assertEquals(MotionActivity.UNOBSERVABLE,tracker.update(2200,contract,MotionPhase.UNOBSERVABLE,emptyMap()))
    }
}
