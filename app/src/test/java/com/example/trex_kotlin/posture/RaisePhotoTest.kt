package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** 사진 기준의 방향 구분, 허용 사선, 상대 움직임, 가림을 실제 피처→반복 평가 경로로 확인한다. */
class RaisePhotoTest {
    private val front="프런트 레이즈"
    private val lateral="사이드 레터럴 레이즈"
    private val wave=listOf(180f,180f,180f,180f,165f,145f,120f,95f,90f,95f,120f,145f,165f,180f,180f)
    private fun view(yaw:Float)=mapOf(ViewEstimator.FEAT_COS to cos(Math.toRadians(yaw.toDouble())).toFloat(),
        ViewEstimator.FEAT_SIN to sin(Math.toRadians(yaw.toDouble())).toFloat())
    private data class Pose(val xy:FloatArray,val vis:FloatArray,val world:FloatArray)
    // 사람 좌표: 왼쪽 +x, 위 +y, 앞 +z. 카메라 회전 뒤 MediaPipe 원부호로 바꾼다.
    private fun pose(signal:Float=90f,plane:Float=90f,camera:Float=30f,shrug:Float=0f,fwd:Float=0f,scale:Float=1f):Pose {
        val xy=FloatArray(66);val vis=FloatArray(33);val raw=FloatArray(99)
        val yaw=Math.toRadians(camera.toDouble());val c=cos(yaw).toFloat();val s=sin(yaw).toFloat()
        fun put(i:Int,p:Vec3) {
            val q=Vec3(c*p.x+s*p.z,p.y,-s*p.x+c*p.z)
            xy[2*i]=.5f+q.x*.65f;xy[2*i+1]=.8f-q.y*.65f;vis[i]=1f
            raw[3*i]=q.x*scale;raw[3*i+1]=-q.y*scale;raw[3*i+2]=-q.z*scale
        }
        put(0,Vec3(0f,.78f,.12f))
        val elev=Math.toRadians((180f-signal).toDouble()); val a=Math.toRadians(plane.toDouble())
        for(n in 0..1) {
            val sign=if(n==0)1f else -1f
            val shoulder=Vec3(sign*.2f,.55f+shrug,fwd)
            val dir=Vec3(sign*cos(a).toFloat()*sin(elev).toFloat(),-cos(elev).toFloat(),sin(a).toFloat()*sin(elev).toFloat())
            put(23+n,Vec3(sign*.14f,0f,0f));put(11+n,shoulder)
            put(13+n,shoulder+dir*.30f);put(15+n,shoulder+dir*.52f)
            put(7+n,Vec3(sign*.2f,.78f,0f))
        }
        return Pose(xy,vis,raw)
    }
    private fun features(ex:String,signal:Float,plane:Float,shrug:Float=0f,fwd:Float=0f,yaw:Float=-30f,
                         alter:(Pose)->Unit = {}):Map<String,Float> {
        val p=pose(signal,plane,shrug=shrug,fwd=fwd);alter(p)
        val src=view(yaw)+mapOf("forearm_vert_mean" to signal,"upperarm_vert_L" to signal,"upperarm_vert_R" to signal,
            "elbow_L" to 160f,"elbow_R" to 160f,"torso_pitch" to 0f,"head_pitch" to 0f)
        return RequestedFormFeatures.augment(ex,src,p.xy,p.vis,1000,1000,p.world)
    }
    private fun rep(ex:String,plane:Float=if(ex==front)90f else 30f,
                    change:(Int,Map<String,Float>)->Map<String,Float> = {_,f->f}):RepFormRep {
        val ev=RepFormEvaluator(RepFormSpecs.byExercise.getValue(ex),"forearm_vert_mean",35f)
        wave.forEachIndexed { i,v -> ev.onFrame(i*300L,change(i,features(ex,v,plane))) }
        return ev.onCycle(4200L,90f,180f,900L)
    }
    private fun RepFormRep.named(name:String)=outcomes.first { it.check.id.endsWith("|$name") }

    @Test fun frontForwardAndLateralForwardDiagonalBothPassTheirOwnDirections() {
        for(ex in listOf(front,lateral)) {
            val r=rep(ex)
            assertEquals(ex,Verdict.OK,r.named("왼팔 올림 방향").verdict)
            assertEquals(ex,Verdict.OK,r.named("오른팔 올림 방향").verdict)
            assertEquals(Verdict.OK,r.named("왼팔 올림 높이").verdict)
            assertFalse(ex,RequestedFormCoaching.excluded(r))
        }
    }
    @Test fun directionDifferencesAreAdvisoryWhenVisible() {
        for((ex,plane) in listOf(front to 0f,lateral to 90f,lateral to -42f)) {
            val r=rep(ex,plane)
            assertEquals(Verdict.VIOLATION,r.named("왼팔 올림 방향").verdict)
            assertFalse(RequestedFormCoaching.excluded(r)) // 방향 추정만으로 횟수를 빼지 않는다.
        }
    }
    @Test fun lateralAllowsForwardDiagonalWithoutForcingArmsExactlySideways() {
        for(deg in listOf(0f,20f,40f,55f))
            assertEquals("$deg",Verdict.OK,rep(lateral,deg).named("왼팔 올림 방향").verdict)
    }
    @Test fun raisedHeightHasBothTooLowAndTooHighCorrections() {
        for(ex in listOf(front,lateral)) for(angle in listOf(135f,45f)) {
            val r=rep(ex) {_,f->f+("upperarm_vert_L" to angle)}
            assertEquals(Verdict.VIOLATION,r.named("왼팔 올림 높이").verdict)
            assertEquals(Verdict.OK,r.named("오른팔 올림 높이").verdict)
            assertTrue(RequestedFormCoaching.excluded(r))
        }
    }
    @Test fun ordinaryShoulderMotionIsAllowedButLargeShrugIsExcluded() {
        val normal=rep(front) {i,_->features(front,wave[i],90f,shrug=if(i in 6..10).02f else 0f)}
        val shrug=rep(front) {i,_->features(front,wave[i],90f,shrug=if(i in 6..10).16f else 0f)}
        assertEquals(Verdict.OK,normal.named("왼팔 어깨 들림").verdict)
        assertFalse(RequestedFormCoaching.excluded(normal))
        assertEquals(Verdict.VIOLATION,shrug.named("왼팔 어깨 들림").verdict)
        assertTrue(RequestedFormCoaching.excluded(shrug))
    }
    @Test fun shoulderTravelComparedWithThisRepsRestInsteadOfWholeSet() {
        val moved=rep(front) {i,_->features(front,wave[i],90f,fwd=if(i in 6..10).22f else 0f)}
        assertEquals(Verdict.VIOLATION,moved.named("왼팔 어깨 앞뒤 이동").verdict)
        val offset=rep(front) {i,_->features(front,wave[i],90f,fwd=.12f)}
        assertEquals(Verdict.OK,offset.named("왼팔 어깨 앞뒤 이동").verdict)
    }
    @Test fun missingRestDoesNotBecomeNormalOrShrugViolation() {
        val r=rep(front) {i,f->if(i<=3)f.filterKeys { !it.startsWith("raise_shoulder_") } else f}
        assertEquals(Verdict.ABSTAIN,r.named("왼팔 어깨 들림").verdict)
        assertEquals(Verdict.ABSTAIN,r.named("왼팔 어깨 앞뒤 이동").verdict)
    }
    @Test fun hiddenArmDoesNotEraseVisibleArmsVerdict() {
        val r=rep(front,0f) {i,_->features(front,wave[i],0f) {it.vis[13]=0f}}
        assertEquals(Verdict.ABSTAIN,r.named("왼팔 올림 방향").verdict)
        assertEquals(Verdict.VIOLATION,r.named("오른팔 올림 방향").verdict)
    }
    @Test fun headBendingCannotBeCalledShoulderShrugAndMissingHeadIsUnknown() {
        val p=pose(shrug=.16f)
        for(pitch in listOf(55f,Float.NaN)) {
            val f=RaisePhotoGeometry.features(p.xy,p.vis,1000,1000,view(-30f)+("head_pitch" to pitch),p.world)
            assertFalse(f.keys.any { it.startsWith("raise_shoulder_lift_") })
        }
        val f=RaisePhotoGeometry.features(p.xy,p.vis,1000,1000,view(-30f),p.world)
        assertFalse(f.keys.any { it.startsWith("raise_shoulder_lift_") })
    }
    @Test fun directionIsScaleAndCameraRotationInvariantWhenArmIsVisible() {
        for(camera in listOf(-35f,35f)) for(scale in listOf(.8f,1.3f)) {
            val p=pose(plane=30f,camera=camera,scale=scale)
            val f=RaisePhotoGeometry.features(p.xy,p.vis,1000,1000,view(camera),p.world)
            assertEquals(30f,f.getValue("raise_lateral_plane_L"),.01f)
            assertEquals(30f,f.getValue("raise_lateral_plane_R"),.01f)
        }
    }
    @Test fun missingInvalidOrHiddenWorldPointsCannotCreateDirection() {
        val p=pose()
        for(raw in listOf<FloatArray?>(null,FloatArray(4),p.world.copyOf().also {it[23*3]=Float.NaN})) {
            val f=RaisePhotoGeometry.features(p.xy,p.vis,1000,1000,view(-30f),raw)
            assertFalse(f.keys.any { it.contains("plane") })
        }
        p.xy[13*2]=1.5f
        val f=RaisePhotoGeometry.features(p.xy,p.vis,1000,1000,view(-30f),p.world)
        assertFalse(f.containsKey("raise_front_plane_L"))
        assertTrue(f.containsKey("raise_front_plane_R"))
    }
    @Test fun pureSideAndUnknownViewDoNotPretendToObserveHorizontalDirection() {
        val p=pose()
        for(v in listOf(view(-75f),emptyMap())) {
            val f=RaisePhotoGeometry.features(p.xy,p.vis,1000,1000,v,p.world)
            assertFalse(f.keys.any { it.contains("plane") })
        }
        val side=rep(front) {i,_->features(front,wave[i],90f,yaw=-75f)}
        assertEquals(Verdict.ABSTAIN,side.named("왼팔 올림 방향").verdict)
        assertEquals(Verdict.OK,side.named("왼팔 올림 높이").verdict)
    }
    @Test fun missingGeometryCannotReusePreviouslyComputedDirection() {
        val p=pose();p.vis[23]=0f
        val src=view(-30f)+mapOf("raise_front_plane_L" to 0f,"upperarm_vert_L" to 90f)
        val f=RequestedFormFeatures.augment(front,src,p.xy,p.vis,1000,1000,p.world)
        assertFalse(f.containsKey("raise_front_plane_L"))
        val state=RequestedFormState(front).onFrame(1L,f)
        assertTrue(state.scopeNote!!.contains("미확인"))
    }
    @Test fun oneBadFrameDoesNotRejectOtherwiseStableDirection() {
        val r=rep(front) {i,f->if(i==8)f+("raise_front_plane_L" to 80f) else f}
        assertEquals(Verdict.OK,r.named("왼팔 올림 방향").verdict)
    }
    @Test fun lowCoverageDoesNotTreatAnUnobservedPlaneAsCorrect() {
        val r=rep(front) {i,f->if(i==8)f else f-"raise_front_plane_L"}
        assertEquals(Verdict.ABSTAIN,r.named("왼팔 올림 방향").verdict)
    }
    @Test fun raisesKeepBetaAndDoNotClaimMuscleTensionOrShoulderBladeTracking() {
        for(ex in listOf(front,lateral)) {
            assertTrue(RaisePhotoSpecs.checks(ex).all { it.status==RuleStatus.BETA && it.requireObservedView && it.strictObservationWindow })
            assertTrue(RequestedFormState(ex).scopeNote!!.contains("눈동자"))
        }
    }
}
