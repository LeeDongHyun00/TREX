package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** 실제 좌표에서 관측값·반복 판정·표시 횟수까지 이어지는 프런트 어깨 들림 회귀 검사. */
class FrontRaiseShoulderTest {
    private var ex="프런트 레이즈"
    private val wave=listOf(175f,175f,175f,175f,160f,140f,115f,95f,90f,95f,115f,140f,160f,175f,175f,175f)
    private data class Pose(val xy:FloatArray,val vis:FloatArray,val world:FloatArray)
    private fun pose(angle:Float,shrug:Float,camera:Float=30f,rightShrug:Float=shrug,earHeight:Float=.78f):Pose {
        val xy=FloatArray(66);val vis=FloatArray(33);val raw=FloatArray(99)
        val rad=Math.toRadians(camera.toDouble());val c=cos(rad).toFloat();val s=sin(rad).toFloat()
        fun put(i:Int,p:Vec3) {
            val q=Vec3(c*p.x+s*p.z,p.y,-s*p.x+c*p.z)
            xy[2*i]=.5f+q.x*.6f;xy[2*i+1]=.85f-q.y*.6f;vis[i]=1f
            raw[3*i]=q.x;raw[3*i+1]=-q.y;raw[3*i+2]=-q.z
        }
        put(0,Vec3(0f,earHeight,.14f))
        for(n in 0..1) {
            val sign=if(n==0)1f else -1f
            val shoulder=Vec3(sign*.2f,.55f+if(n==0)shrug else rightShrug,0f)
            val elev=Math.toRadians((180f-angle).toDouble())
            val arm=Vec3(0f,-cos(elev).toFloat(),sin(elev).toFloat())
            put(11+n,shoulder);put(23+n,Vec3(sign*.14f,0f,0f))
            put(13+n,shoulder+arm*.3f);put(15+n,shoulder+arm*.52f)
            put(7+n,Vec3(sign*.15f,earHeight,0f))
        }
        return Pose(xy,vis,raw)
    }
    private fun f(angle:Float,shrug:Float,yaw:Float=-30f,rightShrug:Float=shrug,earHeight:Float=.78f,
                  pitch:Float?=0f,alter:(Pose)->Unit={}):Map<String,Float> {
        val p=pose(angle,shrug,-yaw,rightShrug,earHeight);alter(p)
        val r=Math.toRadians(yaw.toDouble())
        val src=mapOf("upperarm_vert_L" to angle,"upperarm_vert_R" to angle,"upperarm_vert_mean" to angle,
            "elbow_L" to 160f,"elbow_R" to 160f,"torso_pitch" to 0f,"head_yaw" to 0f,
            ViewEstimator.FEAT_COS to cos(r).toFloat(),ViewEstimator.FEAT_SIN to sin(r).toFloat())+
            (pitch?.let { mapOf("head_pitch" to it) } ?: emptyMap())
        return RequestedFormFeatures.augment(ex,src,p.xy,p.vis,1000,1000,p.world)
    }
    private fun rep(frame:(Int)->Map<String,Float>):RepFormRep {
        val ev=RepFormSpecs.evaluatorFor(ex,RepCounter.forSession(ex,floor=false)!!)!!
        wave.forEachIndexed { i,_ -> ev.onFrame(i*300L,frame(i)) }
        return ev.onCycle(wave.lastIndex*300L,90f,175f,900L)
    }
    private fun RepFormRep.feature(key:String)=outcomes.first { it.check.feature==key }

    @Test fun alreadyShruggedAtStartCannotBecomeItsOwnNormalReference() {
        val r=rep{i->f(wave[i],.15f)}
        assertTrue(RequestedFormCoaching.excluded(r))
        assertTrue(RequestedFormCoaching.message(r)!!.contains("어깨"))
    }
    @Test fun clearShrugDuringRaiseExcludesEvenBelowOldDeltaThreshold() {
        assertTrue(RequestedFormCoaching.excluded(rep{i->f(wave[i],if(i in 6..10).09f else 0f)}))
    }
    @Test fun moreSevereShrugDoesNotDisappearWhenEarShoulderGapCloses() {
        for(shrug in listOf(.15f,.20f,.23f,.25f)) {
            val features=f(90f,shrug)
            assertTrue("$shrug",features.containsKey("raise_shoulder_gap_L"))
            assertTrue("$shrug",RequestedFormCoaching.excluded(rep{i->f(wave[i],shrug)}))
        }
    }
    @Test fun normalSmallMotionAndNaturallyShorterNeckAreAllowed() {
        for(ear in listOf(.65f,.70f,.78f))
            assertFalse(RequestedFormCoaching.excluded(rep{i->f(wave[i],if(i in 6..10).025f else 0f,earHeight=ear)}))
        assertFalse(RequestedFormCoaching.excluded(rep{i->f(wave[i],if(i in 6..10).025f else 0f)}))
    }
    @Test fun bothNominalSideViewsCanObserveOneVisibleShoulder() {
        for(yaw in listOf(-75f,75f,-90f,90f)) {
            val near=if(yaw<0f)0 else 1;val far=1-near
            val r=rep{i->f(wave[i],.15f,yaw=yaw,pitch=null) { p->
                for(index in listOf(7+far,11+far,23+far))p.vis[index]=.1f
            }}
            assertTrue("$yaw",RequestedFormCoaching.excluded(r))
            assertEquals(Verdict.VIOLATION,r.feature("raise_shoulder_gap_${if(near==0)"L" else "R"}").verdict)
        }
    }
    @Test fun oneShruggedShoulderCannotBeAveragedAwayByTheNormalSide() {
        val r=rep{i->f(wave[i],.15f,rightShrug=0f)}
        assertTrue(RequestedFormCoaching.excluded(r))
        assertEquals(Verdict.OK,r.feature("raise_shoulder_gap_R").verdict)
    }
    @Test fun briefNoisyShrugDoesNotExcludeTheWholeRep() {
        assertFalse(RequestedFormCoaching.excluded(rep{i->f(wave[i],if(i==8).20f else .01f)}))
    }
    @Test fun hiddenFaceOrShoulderIsUnknownAndDoesNotReuseOldValue() {
        for(index in listOf(0,7,11,23)) {
            val r=rep{i->f(wave[i],.15f,rightShrug=0f) {p->p.vis[index]=.1f}}
            assertEquals("$index",Verdict.ABSTAIN,r.feature("raise_shoulder_gap_L").verdict)
            assertFalse(RequestedFormCoaching.excluded(r))
        }
    }
    @Test fun bowedHeadAndTiltedHeadAreNotMisreportedAsShrug() {
        assertEquals(Verdict.ABSTAIN,rep{i->f(wave[i],.15f,pitch=60f)}.feature("raise_shoulder_gap_L").verdict)
        val tilted=rep{i->f(wave[i],0f){p->p.xy[7*2+1]+=.10f;p.xy[8*2+1]-=.04f}}
        assertEquals(Verdict.ABSTAIN,tilted.feature("raise_shoulder_gap_L").verdict)
        assertFalse(RequestedFormCoaching.excluded(tilted))
    }
    @Test fun missingRestStillChecksClearlyRaisedShoulderWithoutClaimingRelativeMovement() {
        val r=rep{i->if(i<=3)f(wave[i],.15f).filterKeys{!it.startsWith("raise_shoulder_")} else f(wave[i],.15f)}
        assertEquals(Verdict.ABSTAIN,r.feature("raise_shoulder_lift_L").verdict)
        assertTrue(RequestedFormCoaching.excluded(r))
    }
    @Test fun rawCounterThreeCoachTwoAndTrackThreeUseTheSameDecision() {
        val rc=RepCounter.forSession(ex,floor=false)!!;val ev=RepFormSpecs.evaluatorFor(ex,rc)!!
        var state=RequestedFormState(ex);val records=arrayListOf<RepRecord>();var notShort=0;var short=0
        repeat(3) { cycle->wave.forEachIndexed { i,v ->
            val now=(cycle*wave.size+i)*300L;val features=f(v,if(cycle==1).15f else 0f)
            state=state.onFrame(now,features);ev.onFrame(now,features)
            if(rc.onFrameFeatures(now,features)) {
                val tally=RepUnitAccumulator.onCounterFrame(rc,now,records,null)
                state=state.onReps(tally.records.map{ev.onCycle(it.tMs,it.cycleMin,it.cycleMax,it.cycleStartMs)},tally.records,false,now)
                notShort+=tally.repsNotShort;short+=tally.repsShort
            }
        } }
        assertEquals(3,rc.reps);assertEquals(1,state.excluded)
        assertEquals(2,RequestedFormCoaching.counted(ex,true,notShort,short,false,0,state.excluded))
        assertEquals(3,RequestedFormCoaching.counted(ex,false,notShort,short,false,0,state.excluded))
        assertTrue(ev.summary().toLog(0).toJson().contains("\"requested_coach_excluded\":true"))
    }

    @Test fun lateralRaiseAlsoChecksInitiallyShruggedShouldersFromCoordinates() {
        ex="사이드 레터럴 레이즈"
        for(yaw in listOf(-30f,30f,-75f,75f,-90f,90f)) {
            assertTrue("$yaw",RequestedFormCoaching.excluded(rep{i->f(wave[i],.20f,yaw=yaw)}))
            assertFalse("$yaw",RequestedFormCoaching.excluded(rep{i->f(wave[i],.02f,yaw=yaw)}))
        }
        val one=rep{i->f(wave[i],.20f,rightShrug=0f)}
        assertTrue(RequestedFormCoaching.excluded(one))
        assertEquals(Verdict.OK,one.feature("raise_shoulder_gap_R").verdict)
    }
    @Test fun temporaryShrugBeforePeakIsPreservedFromCoordinates() {
        for(name in listOf("프런트 레이즈","사이드 레터럴 레이즈")) {
            ex=name
            assertTrue(name,RequestedFormCoaching.excluded(rep{i->f(wave[i],if(i in 4..6).20f else 0f)}))
            assertFalse(name,RequestedFormCoaching.excluded(rep{i->f(wave[i],if(i==5).20f else 0f)}))
        }
    }
}
