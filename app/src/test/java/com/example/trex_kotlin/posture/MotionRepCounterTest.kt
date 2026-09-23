package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** 신호/상태기의 회귀시험이다. 합성 움직임의 통과를 사람의 계수 정확도로 보고하지 않는다. */
class MotionRepCounterTest {
    private fun counter(point: CountPoint = CountPoint.RETURN) = MotionRepCounter(
        MotionContract("test",listOf(MotionChannel("x","aux",14f,5f,-1)),point,countHint="test"),
        RepSignal("x",14f,romDirection="min",romThreshold=100f))
    private fun frame(x: Float) = mapOf("x" to x,"aux" to x * .5f)
    private fun cycle(c: MotionRepCounter, start: Long, period: Long = 800, depth: Float = 90f): List<MotionCompletion> = buildList {
        for (i in 0..8) {
            val progress = if (i <= 4) i / 4f else (8-i) / 4f
            addAll(c.onFrame(start+i*period/8,frame(170f-depth*progress)))
        }
    }
    @Test fun lastReturnCountsWithoutWaitingForNextDescent() {
        val c = counter(); val events = cycle(c,0)
        assertEquals(1,c.reps); assertEquals(800L,events.single().atMs)
    }
    @Test fun sixHundredMsRepsAreNotBlockedByFixedRefractory() {
        val c=counter()
        repeat(5) { cycle(c,it*675L,600) }
        assertEquals(5,c.reps)
    }
    @Test fun shallowCyclesCountButRemainBelowRomReference() {
        val c=counter()
        val events = cycle(c,0,1200,90f)+cycle(c,1350,1200,35f)+cycle(c,2700,1200,90f)
        assertEquals(listOf(true,false,true),events.map { c.signal.isValidRep(it.min,it.max) })
    }
    @Test fun turnCountsAtEndpointAndHoldCannotRepeat() {
        val c=counter(CountPoint.TURN)
        for (i in 0..4) c.onFrame(i*100L,frame(170f-i*20f))
        for (i in 5..30) c.onFrame(i*100L,frame(90f))
        assertEquals(1,c.reps)
        for (i in 1..4) c.onFrame(3000+i*100L,frame(90f+i*20f))
        assertEquals(1,c.reps)
    }
    @Test fun gapPauseAndMissingFeaturesDoNotJoinHalfCycles() {
        for (pause in listOf(false,true)) {
            val c=counter(); for (i in 0..4) c.onFrame(i*100L,frame(170f-i*20f))
            if (pause) c.resetCycle() else c.onFrame(500,emptyMap())
            for (i in 0..4) c.onFrame(1000+i*100L,frame(90f+i*20f))
            assertEquals(0,c.reps)
            cycle(c,1600,1200); assertEquals(1,c.reps)
        }
    }
    @Test fun staticNoiseAndOneSignalMovementDoNotCount() {
        val c=counter()
        repeat(200) { c.onFrame(it*100L,frame(170f+(it%3-1)*2)) }; assertEquals(0,c.reps)
        for (i in 0..16) c.onFrame(21000+i*100L,mapOf("x" to (170f-10*(if(i<=8)i else 16-i)),"aux" to 0f))
        assertEquals(0,c.reps)
    }
    @Test fun lungeRearKneeIsNotASecondRep() {
        val c=MotionRepCounter.forExercise("바벨 런지")!!
        for (i in 0..8) {
            val p=if(i<=4)i/4f else (8-i)/4f
            c.onFrame(i*150L,mapOf("knee_L" to 170f-80*p,"hip_L" to 170f-60*p,
                "knee_R" to 170f-50*p,"hip_R" to 170f-30*p,"knee_mean" to 170f-65*p))
        }
        assertEquals(1,c.reps)
    }
    @Test fun all26ContractsAreUniqueAndExecuteTheirDeclaredCycle() {
        assertEquals(26,MotionContracts.all.size)
        assertEquals(26,MotionContracts.all.map{it.exercise}.distinct().size)
        for (contract in MotionContracts.all.filterNot { it.hold }) {
            val c=MotionRepCounter.forExercise(contract.exercise)!!
            for(i in 0..14) {
                val p=when { i<4 -> i/4f; i<7 -> 1f; i<=11 -> (11-i)/4f; else -> 0f }
                val features=buildMap<String,Float> {
                    // 대칭 종목은 동시, 비대칭 종목은 한쪽을 움직인다.
                    contract.channels.forEachIndexed { j,ch ->
                        val q=if(j==0||contract.bilateral||contract.singleLegCycle)p else 0f
                        if(ch.primary !in this) put(ch.primary,100f+(if(ch.direction==0)1 else ch.direction)*ch.travel*4*q)
                        put(ch.support,2f+ch.supportTravel*4*q)
                    }
                    if(c.signal.feature !in this) put(c.signal.feature, 1f)
                }
                c.onFrame(i*100L,features)
            }
            assertEquals(contract.exercise,1,c.reps)
        }
    }
    @Test fun invalidRomMeasurementsStayUnknown() {
        assertNull(counter().signal.isValidRep(Float.NaN,170f))
    }
    @Test fun repeatedLeftSideCrunchDoesNotInventRightSideRep() {
        val c=MotionRepCounter.forExercise("스탠딩 사이드 크런치")!!
        val events=ArrayList<MotionCompletion>()
        repeat(3) { cycle -> for(i in 0..8) {
            val p=if(i<=4)i/4f else (8-i)/4f
            events+=c.onFrame(cycle*1350L+i*150L,mapOf("torso_roll" to 30*p,"shoulder_h_L" to 1f-.3f*p,"shoulder_h_R" to 1f+.3f*p))
        } }
        assertEquals(3,events.size); assertTrue(events.all { it.side==MotionSide.LEFT })
    }
    @Test fun perArmCycleCannotUseTwoArmRomThreshold() {
        assertNull(MotionRepCounter.forExercise("덤벨 컬")!!.signal.romThreshold)
        assertNull(MotionRepCounter.forExercise("스탠딩 니업")!!.signal.romThreshold)
        assertNotNull(MotionRepCounter.forExercise("바벨 컬")!!.signal.romThreshold)
    }
}
