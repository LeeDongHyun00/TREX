package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class MotionObservationTest {
    @Test fun cameraTimeIgnoresWallClockJumpAndRejectsOldFrames() {
        val clock=CaptureTimeline()
        assertEquals(10000L,clock.map(1000000000,10000))
        assertEquals(10100L,clock.map(1100000000,999999))
        assertNull(clock.map(1000000000,999999))
        assertEquals(10200L,clock.map(1200000000,1))
    }
    @Test fun legacySamplingKeeps300MsWindowWhileMotionIsFast() {
        val cadence=SampleCadence()
        assertEquals(listOf(0L,300L,600L,900L),(0L..900L step 75).filter(cadence::accept))
    }
    @Test fun inwardAndOutwardOnOppositeSidesNeverCancel() {
        val o=KneeDirectionObserver("바벨 스쿼트")
        val f=mapOf("knee_track_L" to -.25f,"knee_track_R" to .25f,"knee_L" to 100f,"knee_R" to 100f)
        o.add(0,f,true); o.add(110,f,true)
        val result=o.add(220,f,true)
        assertEquals(2,result.size)
        assertTrue(result[0].text.contains("왼쪽")&&result[0].text.contains("안쪽"))
        assertTrue(result[1].text.contains("오른쪽")&&result[1].text.contains("바깥쪽"))
        assertEquals(setOf(26,28,30,32),result[1].landmarks)
        assertTrue(o.add(300,emptyMap(),true).isEmpty())
        assertTrue(o.add(500,f,true).isEmpty())
    }
    @Test fun straightKneesOrUnobservedFootDoNotGetDirectionAdvice() {
        val o=KneeDirectionObserver("바벨 스쿼트")
        repeat(10) { assertTrue(o.add(it*100L,mapOf("knee_L" to 175f,"knee_track_L" to -.4f),true).isEmpty()) }
    }
    @Test fun speechDropsStaleBusyAndDuplicateHints() {
        val a=MotionSpeechArbiter()
        assertFalse(a.offer(2000,"knee",0,true))
        assertFalse(a.offer(2000,"knee",2000,false))
        assertTrue(a.offer(2000,"knee",2000,true))
        assertFalse(a.offer(4000,"head",4000,true))
        assertFalse(a.offer(6000,"knee",6000,true))
        assertTrue(a.offer(10000,"knee",10000,true))
    }
    @Test fun traceIsBoundedAndReportsLoss() {
        val t=MotionTrace(2); repeat(3){t.add(MotionTraceFrame(it.toLong(),"READY",emptyMap(),0,0))}
        assertEquals(2,t.snapshot().size); assertEquals(1,t.dropped)
    }
}
