package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** §132: 같은 하강량을 요구하지 않는 동반 이동과 관측 유보를 검사한다. */
class PushupCycleTest {
    companion object {
        /** 발목을 지지점으로 회전하는 강체와 두 분절 팔의 합성 관절. 양 플랫폼 연결 검사에 쓴다. */
        fun landmarks(degrees: Double): Pair<FloatArray,FloatArray> {
            val a=Math.toRadians(degrees); val top=Math.toRadians(25.0)
            val sx=.85-.65*kotlin.math.cos(a); val sy=.7-.65*kotlin.math.sin(a)
            val wx=.85-.65*kotlin.math.cos(top); val wy=.7
            val dx=wx-sx; val dy=wy-sy; val d=kotlin.math.hypot(dx,dy)
            val arm=.65*kotlin.math.sin(top)/2; val h=kotlin.math.sqrt((arm*arm-d*d/4).coerceAtLeast(0.0))
            val xy=FloatArray(66); val vis=FloatArray(33)
            for(side in 0..1) {
                fun p(i:Int,x:Double,y:Double) { xy[(i+side)*2]=(x+side*.003).toFloat(); xy[(i+side)*2+1]=y.toFloat();vis[i+side]=1f }
                p(11,sx,sy);p(23,.85-.39*kotlin.math.cos(a),.7-.39*kotlin.math.sin(a))
                p(25,.85-.18*kotlin.math.cos(a),.7-.18*kotlin.math.sin(a));p(27,.85,.7)
                p(13,(sx+wx)/2+dy/d*h,(sy+wy)/2-dx/d*h);p(15,wx,wy)
            }
            return xy to vis
        }
    }
    private fun frame(h: Float, ratio: Float=.6f, hip: Float=h*ratio, offset: Float=0f, elbow: Float=if(h>=.43f)175f else 90f) =
        mapOf(FourExerciseForm.PUSHUP_POSTURE to 1f,FourExerciseForm.PUSHUP_SIGNAL to h,
            PushupCycleTracker.HIP_HEIGHT to hip,PushupCycleTracker.HIP_RATIO to ratio,
            FourExerciseForm.PUSHUP_HIP to offset,FourExerciseForm.PUSHUP_KNEE to 175f,
            FourExerciseForm.PUSHUP_ELBOW to elbow,FloorChain.SIDE to 0f)
    private val path=listOf(.45f,.45f,.4f,.3f,.2f,.15f,.2f,.3f,.4f,.45f,.45f)
    private fun run(rc: RepCounter, frames: List<Map<String,Float>>, start: Long=0): Int {
        frames.forEachIndexed { i,f -> rc.onFrameFeatures(start+i*200L,f) }
        return rc.reps
    }
    private fun counter()=RepCounter.forSession("푸시업",floor=true)!!

    @Test fun bodyProportionsDoNotRequireEqualShoulderAndHipDisplacements() {
        for (ratio in listOf(.4f,.6f,.8f)) {
            val rc=counter(); assertEquals(1,run(rc,path.map { frame(it,ratio) }))
            assertTrue(rc.rejectedReps.isEmpty())
            assertEquals(PushupCycleTracker.VERSION,RepEngineLog.of(rc).engine)
        }
    }
    @Test fun shoulderOnlyHipOnlyOppositeAndDelayedHipCannotCount() {
        val inputs=listOf(path.map { frame(it,hip=.27f) },path.map { frame(.45f,hip=it*.6f) },
            path.map { frame(it,hip=.27f+(.45f-it)*.6f) },
            path.mapIndexed { i,h -> frame(h,hip=path[(i-3).coerceAtLeast(0)]*.6f) })
        for (frames in inputs) {
            val rc=counter(); assertEquals(0,run(rc,frames))
            assertTrue(rc.pushupTracker!!.note.orEmpty().contains("함께") || rc.pushupTracker!!.note.orEmpty().contains("동반"))
        }
    }
    @Test fun oneAlignmentErrorInvalidatesOnlyThatCycleEvenAfterCorrection() {
        for (offset in listOf(-.2f,.2f)) {
            val rc=counter()
            assertEquals(0,run(rc,path.mapIndexed { i,h -> frame(h,offset=if(i==5)offset else 0f) }))
            assertEquals(PushupCycleTracker.ALIGNMENT,rc.rejectedReps.single().feature)
            assertEquals(1,run(rc,path.map { frame(it) },3000))
        }
    }
    @Test fun earlyAlignmentFaultBeforeMinimumDepartureCannotBeForgotten() {
        val rc=counter()
        val frames=listOf(frame(.45f),frame(.44f,offset=.2f,elbow=155f))+path.drop(2).map { frame(it) }
        assertEquals(0,run(rc,frames))
        assertEquals(PushupCycleTracker.ALIGNMENT,rc.rejectedReps.single().feature)
        assertEquals(1,run(rc,path.map { frame(it) },3000))
    }
    @Test fun missingEachRequiredMeasurementAbstainsAndCannotJoinBeforeAndAfter() {
        val keys=frame(.3f).keys
        for (key in keys) {
            val rc=counter()
            run(rc,path.mapIndexed { i,h -> if(i==5)frame(h)-key else frame(h) })
            assertEquals(key,0,rc.reps)
            assertEquals(key,PushupCycleTracker.UNOBSERVED,rc.rejectedReps.single().feature)
            assertEquals(1,run(rc,path.map { frame(it) },3000))
        }
    }
    @Test fun noPersonAndInvalidNumbersAreUnobservedNotAlignmentErrors() {
        for (missing in listOf(emptyMap(),frame(.15f)+(PushupCycleTracker.HIP_HEIGHT to Float.NaN),
            frame(.15f)+(FourExerciseForm.PUSHUP_ELBOW to Float.POSITIVE_INFINITY))) {
            val rc=counter(); run(rc,path.mapIndexed { i,h -> if(i==5)missing else frame(h) })
            assertEquals(0,rc.reps); assertEquals(PushupCycleTracker.UNOBSERVED,rc.rejectedReps.single().feature)
        }
    }
    @Test fun partiallyReturnedAndBentArmAtTopWaitForFullObservedReturn() {
        val rc=counter()
        run(rc,listOf(.45f,.4f,.3f,.15f,.3f,.35f,.35f).map { frame(it) })
        assertEquals(0,rc.reps); assertTrue(rc.midCycle)
        rc.onFrameFeatures(1500,frame(.45f,elbow=130f)); assertEquals(0,rc.reps)
        rc.onFrameFeatures(1700,frame(.45f)); assertEquals(1,rc.reps)
        assertFalse(rc.midCycle)
    }
    @Test fun startingAtBottomAndUpThenDownCannotBecomeAReverseRep() {
        val rc=counter()
        run(rc,listOf(.15f,.2f,.3f,.45f,.45f).map { frame(it) }); assertEquals(0,rc.reps)
        assertEquals(1,run(rc,path.map { frame(it) },1500))
    }
    @Test fun missingFrameCannotReanchorAtTheBottomEvenIfItPausesThere() {
        val rc=counter(); run(rc,listOf(frame(.45f),frame(.3f),emptyMap()))
        run(rc,List(8) { frame(.15f) },600)
        run(rc,listOf(frame(.3f),frame(.45f)),2300)
        assertEquals(0,rc.reps); assertEquals(1,run(rc,path.map { frame(it) },3000))
    }
    @Test fun timestampGapAndSideSwitchDoNotJoinDifferentObservations() {
        val gap=counter(); run(gap,listOf(frame(.45f),frame(.3f)))
        gap.onFrameFeatures(3000,frame(.15f)); gap.onFrameFeatures(3200,frame(.45f))
        assertEquals(0,gap.reps); assertEquals(PushupCycleTracker.UNOBSERVED,gap.rejectedReps.single().feature)
        val side=counter(); run(side,path.mapIndexed { i,h -> frame(h)+(FloorChain.SIDE to if(i<5)0f else 1f) })
        assertEquals(0,side.reps); assertEquals(PushupCycleTracker.UNOBSERVED,side.rejectedReps.single().feature)
    }
    @Test fun afterOcclusionAVisibleStraightArmStartCanReanchorAtANewHeight() {
        val rc=counter();run(rc,listOf(frame(.45f),frame(.3f),emptyMap()))
        assertEquals(0,rc.reps)
        val newPath=listOf(.38f,.38f,.32f,.24f,.18f,.24f,.32f,.38f)
        run(rc,newPath.map { frame(it,elbow=if(it>=.38f)175f else 90f) },700)
        assertEquals(1,rc.reps)
        assertEquals(PushupCycleTracker.UNOBSERVED,rc.rejectedReps.single().feature)
    }
    @Test fun scalarShoulderInputAndUnknownPoseCannotBypassObservationGate() {
        val rc=counter(); path.forEachIndexed { i,h -> rc.onFrame(i*200L,h) }; assertEquals(0,rc.reps)
        val noPose=counter(); run(noPose,path.map { frame(it)+(FourExerciseForm.PUSHUP_POSTURE to 0f) })
        assertEquals(0,noPose.reps)
    }
    @Test fun pauseResetRetainsCompletedCountButDropsPartialMovement() {
        val rc=counter(); run(rc,path.map { frame(it) }); assertEquals(1,rc.reps)
        run(rc,listOf(frame(.45f),frame(.3f)),2300); assertTrue(rc.midCycle)
        assertNotNull(rc.pendingAtSetEnd().inProgress)
        rc.resetCycle(); rc.onFrameFeatures(2700,frame(.2f)); rc.onFrameFeatures(2900,frame(.45f))
        assertEquals(1,rc.reps); assertEquals(2,run(rc,path.map { frame(it) },3200))
        rc.reset(); assertEquals(0,rc.reps); assertTrue(rc.rejectedReps.isEmpty()); assertNull(rc.pushupTracker!!.note)
    }
    @Test fun optionalHeadAndThreeDimensionalHintsAreNotRequiredToObserveBodyMovement() {
        val rc=counter(); assertEquals(1,run(rc,path.map { frame(it) }))
        assertTrue(frame(.3f).keys.intersect(PushupBalance.keys).isEmpty())
    }
}
