package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** §133: 관절 좌표 → 피처 → 실제 카운터 → 회별 검사 → 표시 원장까지 검증한다. */
class StandingFormCountTest {
    private data class Form(val pitch: Float = 0f, val kneeL: Float = 165f, val kneeR: Float = 165f,
                            val elbowL: Float = 160f, val elbowR: Float = 160f,
                            val planeL: Float = 30f, val planeR: Float = 30f)
    private fun rad(v: Float) = Math.toRadians(v.toDouble()).toFloat()
    private fun coordinates(angle: Float, f: Form): Map<String, Float> {
        val joints = HashMap<String,Vec3>()
        val p=rad(f.pitch); val a=rad(angle)
        for ((side, sign) in listOf("L" to 1f,"R" to -1f)) {
            val plane=rad(if(side=="L") f.planeL else f.planeR)
            val bend=rad(180f-if(side=="L") f.elbowL else f.elbowR)
            val knee=if(side=="L") f.kneeL else f.kneeR
            val sh=Vec3(sign*20f,100f+40f*cos(p),40f*sin(p))
            val u=Vec3(sign*sin(a)*cos(plane),cos(a),sin(a)*sin(plane))
            val v=Vec3(sign*cos(a)*cos(plane),-sin(a),cos(a)*sin(plane))
            val el=sh+u*30f; val wr=el+(u*cos(bend)+v*sin(bend))*25f
            val ids=if(side=="L") listOf(Joints.L_HIP,Joints.L_SHOULDER,Joints.L_ELBOW,Joints.L_WRIST,Joints.L_KNEE,Joints.L_ANKLE)
                    else listOf(Joints.R_HIP,Joints.R_SHOULDER,Joints.R_ELBOW,Joints.R_WRIST,Joints.R_KNEE,Joints.R_ANKLE)
            val ps=listOf(Vec3(sign*10f,100f,0f),sh,el,wr,Vec3(sign*10f,60f,40f*tan(rad((180f-knee)/2f))),Vec3(sign*10f,20f,0f))
            ids.zip(ps).forEach { (key,point)->joints[key]=point }
        }
        val raw=PoseFrame(joints).features()
        val observed=raw.mapKeys { (k,_) -> when(k) {
            "torso_incl"->StandingFormGeometry.TILT;"torso_pitch"->StandingFormGeometry.PITCH
            "knee_L","knee_R"->StandingFormGeometry.knee(k.takeLast(1))
            "elbow_L","elbow_R"->StandingFormGeometry.elbow(k.takeLast(1))
            "raise_plane_L","raise_plane_R"->StandingFormGeometry.plane(k.takeLast(1))
            else->k
        } }
        return raw+observed+mapOf("view_cos" to .866f,"view_sin" to .5f)
    }
    private data class Session(val raw: Int,val reps: List<RepFormRep>,val evaluator: RepFormEvaluator)
    private fun session(ex: String, fault: (Int,Int,Form)->Form, view: Boolean=true): Session {
        val rc=RepCounter.forSession(ex,floor=false)!!
        val ev=RepFormSpecs.evaluatorFor(ex,rc)!!
        val records=ArrayList<RepRecord>();val units=RepUnitAccumulator(RepUnit.CYCLE)
        val reps=ArrayList<RepFormRep>();var t=0L
        fun frame(angle: Float, f: Form) {
            var feats=coordinates(angle,f)
            if(!view) feats=feats.filterKeys { it!="view_cos" && it!="view_sin" && !it.startsWith("sf_") }
            ev.onFrame(t,feats)
            if(rc.onFrameFeatures(t,feats)) {
                val tally=RepUnitAccumulator.onCounterFrame(rc,t,records,units)
                reps+=tally.records.map { ev.onCycle(it.tMs,it.cycleMin,it.cycleMax,it.cycleStartMs,it.side) }
            }
            t+=300L
        }
        repeat(6) {frame(175f,Form())}
        val shape=listOf(155f,135f,115f,90f,90f,90f,90f,90f,115f,135f,155f,175f,175f,175f,175f,175f,175f)
        repeat(3) { rep->shape.forEachIndexed { i,a->
            val normal=if(ex=="굿모닝") Form(pitch=(175f-a)*.75f) else Form()
            frame(a,fault(rep,i,normal))
        }}
        return Session(rc.reps,reps,ev)
    }
    private fun checkMiddle(ex: String, fault: (Int,Form)->Form, reason: String) {
        val result=session(ex,{rep,i,f->if(rep==1) fault(i,f) else f})
        assertEquals(ex,3,result.raw)
        assertEquals(listOf(true,false,true),result.reps.map {it.correct})
        assertTrue(result.reps[1].outcomes.any {it.countOnlyGate && it.check.condition.contains(reason)})
        val event=result.evaluator.eventFor(result.reps[1],99999L,gate=true)!!
        assertTrue(event.gated);assertFalse(event.ship);assertTrue(event.message.contains("세지 않았어요"))
        assertFalse(result.evaluator.eventFor(result.reps[1],100000L,gate=false)!!.gated)
    }
    @Test fun normalAllThreeExercisesKeepEveryRep() {
        for(ex in listOf("프런트 레이즈","굿모닝","사이드 레터럴 레이즈")) {
            val result=session(ex,{_,_,f->f})
            assertEquals(ex,3,result.raw);assertEquals(ex,listOf(true,true,true),result.reps.map {it.correct})
        }
    }
    @Test fun frontLeanSingleObservationRejectsAndNextRepRecovers() = checkMiddle("프런트 레이즈",{i,f->if(i==5) f.copy(pitch=35f) else f},"상체 기울기")
    @Test fun frontSwayRejectsEvenWhenNoAngleExceedsLeanThreshold() = checkMiddle("프런트 레이즈",{i,f->f.copy(pitch=if(i==4) -15f else if(i==5) 15f else 0f)},"몸통 흔들림")
    @Test fun frontKneeBounceWithUprightTorsoRejects() = checkMiddle("프런트 레이즈",{i,f->if(i==5) f.copy(kneeR=135f) else f},"오른쪽 무릎 흔들림")
    @Test fun goodMorningChangingOneKneeRejects() = checkMiddle("굿모닝",{i,f->f.copy(kneeL=if(i==5) 140f else 175f)},"왼쪽 무릎 움직임")
    @Test fun goodMorningFixedDeepKneeBendRejects() = checkMiddle("굿모닝",{_,f->f.copy(kneeR=125f)},"오른쪽 무릎 과굽힘")
    @Test fun lateralStraightElbowAtPeakRejects() = checkMiddle("사이드 레터럴 레이즈",{i,f->if(i==5) f.copy(elbowL=179f) else f},"왼쪽 팔꿈치")
    @Test fun lateralOverBentElbowAtPeakRejects() = checkMiddle("사이드 레터럴 레이즈",{i,f->if(i==5) f.copy(elbowR=110f) else f},"오른쪽 팔꿈치")
    @Test fun lateralBehindBodyRejects() = checkMiddle("사이드 레터럴 레이즈",{i,f->if(i==5) f.copy(planeL=-20f) else f},"왼쪽 팔 경로")
    @Test fun lateralTooFarForwardRejects() = checkMiddle("사이드 레터럴 레이즈",{i,f->if(i==5) f.copy(planeR=80f) else f},"오른쪽 팔 경로")
    @Test fun lateralNormalRestingArmsDoNotTriggerRaisedPhaseChecks() {
        val r=session("사이드 레터럴 레이즈",{_,i,f->if(i>=11) f.copy(elbowL=179f,planeL=-20f) else f})
        assertEquals(3,r.raw);assertTrue(r.reps.all {it.correct})
    }
    @Test fun multipleErrorsRejectOneRepOnly() {
        val r=session("사이드 레터럴 레이즈",{rep,i,f->if(rep==1 && i==5) f.copy(elbowL=179f,planeR=-20f) else f})
        assertEquals(3,r.raw);assertEquals(2,r.reps.count {it.correct});assertEquals(2,r.reps[1].outcomes.count {it.countOnlyGate})
    }
    @Test fun unknownViewIsNotConvertedToAnError() {
        val r=session("사이드 레터럴 레이즈",{_,_,f->f.copy(elbowL=179f,planeL=-20f)},view=false)
        assertEquals(3,r.raw);assertTrue(r.reps.flatMap {it.outcomes}.all {it.verdict==Verdict.ABSTAIN})
        assertTrue(r.reps.flatMap {it.outcomes}.none {it.countOnlyGate})
        assertTrue(FourExerciseForm.unobservedNote(r.reps.first())!!.contains("판단 유보"))
    }
    @Test fun briefOppositeErrorsCannotCancelEachOther() = checkMiddle("사이드 레터럴 레이즈",{i,f->f.copy(elbowL=when(i){4->115f;5->179f;else->160f})},"왼쪽 팔꿈치")
}
