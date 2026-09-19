package com.trex.engine

import org.junit.Assert.*
import org.junit.Test

class LabEngineTest {
    private fun profile(name: String) = ExerciseRepProfiles.forExercise(name)!!
    private val front = mapOf("view_cos" to 1f,"view_sin" to 0f)
    @Test fun confirmedFrontDoesNotOscillateBackToUnknownAndLastRepIsCounted() {
        val e = LabEngine(profile("바벨 스쿼트")); var t = 0L
        fun feed(angle: Float) = e.process(t,front+mapOf("knee_mean" to angle)).also { t+=300 }
        repeat(20) { feed(170f) }
        var o: EngineOutput? = null
        repeat(5) { for (v in listOf(150f,120f,100f,120f,150f,170f)) o=feed(v) }
        assertEquals(5,o!!.counts.total); assertEquals("C",o!!.view)
        assertEquals("RESEARCH_MEASUREMENTS_ONLY",o!!.formStatus)
        assertTrue(o!!.events.all { event -> event.cycles.values.all { it.valid == null } })
    }
    @Test fun missingViewAndEmptyFramesNeverCountOrCallFormNormal() {
        val e = LabEngine(profile("바벨 스쿼트"))
        repeat(40) {
            val o = e.process(it*300L,if(it%2==0) emptyMap() else mapOf("knee_mean" to 90f))
            assertEquals(0,o.counts.total); assertEquals("UNKNOWN",o.view)
            assertEquals("UNOBSERVABLE",o.phase)
        }
    }
    @Test fun floorConfirmationIsRequiredAndPlankNeverCountsRepsOrTimeAcrossLoss() {
        val e = LabEngine(profile("플랭크"))
        val f = mapOf("body_horizontal" to .9f,"hip_dev_ankle" to 0f,"knee_ang" to 175f)
        repeat(10) { assertEquals(0L,e.process(it*200L,f).observedHoldMs) }
        var last = e.process(2000,f,floorSideConfirmed=true)
        for (t in 2200L..5000L step 200) last=e.process(t,f,floorSideConfirmed=true)
        assertTrue(last.observedHoldMs>0); val before=last.observedHoldMs
        assertEquals(0,last.counts.total)
        e.process(5200,emptyMap(),floorSideConfirmed=true)
        assertEquals(before,e.process(10000,f,floorSideConfirmed=true).observedHoldMs)
        assertEquals(before,e.process(10200,f+mapOf("body_horizontal" to .1f),floorSideConfirmed=true).observedHoldMs)
    }
    @Test fun eachOf26ExercisesHasMetricsAndNoAutomaticCorrectionClaim() {
        for (p in ExerciseRepProfiles.all) {
            assertTrue(p.exercise,FormMetrics.forExercise(p).isNotEmpty())
            val e=LabEngine(p)
            val o=e.process(0,emptyMap())
            assertTrue(o.measurements.values.all { it == null })
            assertEquals("RESEARCH_MEASUREMENTS_ONLY",o.formStatus)
        }
    }
    @Test fun invalidLandmarkArraysAndOutOfFrameJointsAreNotZeroFilledMeasurements() {
        val f = LandmarkFeatures()
        assertTrue(f.extract(LandmarkFrame(floatArrayOf(),floatArrayOf(),floatArrayOf(),100,100),false).isEmpty())
        val invisible = LandmarkFrame(FloatArray(66){-1f},FloatArray(99){1f},FloatArray(33){1f},100,100)
        assertTrue(f.extract(invisible,false).isEmpty())
        assertTrue(f.extract(invisible,true).isEmpty())
    }
    @Test fun interruptionAndWrongViewCannotFinishHalfCycle() {
        val e=LabEngine(profile("바벨 스쿼트")); var t=0L
        fun feed(v:Float,view:Map<String,Float> = front):EngineOutput = e.process(t,view+mapOf("knee_mean" to v)).also{t+=300}
        repeat(15){feed(170f)}
        feed(140f);feed(100f);feed(90f)
        feed(120f,mapOf("view_cos" to -1f,"view_sin" to 0f))
        repeat(16){feed(170f)}
        assertEquals(0,feed(170f).counts.total)
        var o=feed(170f)
        for(v in listOf(150f,120f,100f,120f,150f,170f))o=feed(v)
        assertEquals(1,o.counts.total)
    }
}
