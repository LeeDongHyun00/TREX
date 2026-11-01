// Android 정본 테스트 — tools/sync_ios_core.py
package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** §127: 영상의 동작 설명을 구조로 재현한다. 합성 좌표의 통과가 실기기 정확도 검증은 아니다. */
class PushupHeadTest {
    private fun geometry(head: Double = 30.0, neck: Double = 0.0, faceSize: Double = .025,
                         neckSize: Double = .07, hidden: Int? = null, rotated: Boolean = false,
                         mirrored: Boolean = false, gravity: Boolean = true): Map<String, Float> {
        val xy = FloatArray(66); val vis = FloatArray(33)
        val n = Math.toRadians(neck); val h = Math.toRadians(head)
        for (o in 0..1) {
            fun p(i: Int, x: Double, y: Double) { xy[(i+o)*2] = (x+o*.002).toFloat(); xy[(i+o)*2+1] = y.toFloat(); vis[i+o] = 1f }
            p(11,.35,.35); p(23,.55,.35); p(25,.73,.35); p(27,.9,.35)
            p(13,.35,.525); p(15,.35,.7)
            p(7,.35-neckSize*cos(n),.35-neckSize*sin(n))
        }
        xy[0] = (.35-neckSize*cos(n)-faceSize*sin(h)).toFloat()
        xy[1] = (.35-neckSize*sin(n)+faceSize*cos(h)).toFloat(); vis[0] = 1f
        hidden?.let { vis[it] = .1f }
        for (i in 0..32) {
            if (mirrored) xy[2*i] = 1f-xy[2*i]
            if (rotated) { val x=xy[2*i]; xy[2*i]=1f-xy[2*i+1]; xy[2*i+1]=x }
        }
        return FloorFeatureExtractor().computeForExercise("푸시업",xy,vis,1000,1000,
            if (!gravity) null else if (rotated) Vec3(1f,0f,0f) else Vec3(0f,1f,0f),0L)
    }

    private fun good() = mapOf(FourExerciseForm.PUSHUP_POSTURE to 1f, FourExerciseForm.PUSHUP_HIP to 0f,
        FourExerciseForm.PUSHUP_KNEE to 175f, FourExerciseForm.PUSHUP_HEAD to 30f, FourExerciseForm.PUSHUP_NECK to 0f)

    private fun form(extra: (Int)->Map<String,Float>): Pair<RepFormEvaluator, RepFormRep> {
        val ev=RepFormSpecs.evaluatorFor("푸시업",RepCounter.forSession("푸시업",floor=true)!!)!!
        val shape=listOf(1f,1f,1f,.8f,.5f,.3f,.2f,.2f,.3f,.5f,.8f,1f,1f)
        shape.forEachIndexed { i,s -> ev.onFrame(i*200L,FourExerciseForm.filterPushupHead(good()+mapOf(
            FourExerciseForm.PUSHUP_SIGNAL to s, FourExerciseForm.PUSHUP_ELBOW to if (i<=2 || i>=11) 175f else 90f)+extra(i))) }
        return ev to ev.onCycle(2400L,.2f,1f)
    }

    @Test fun geometryMeasuresHeadDirectionAndDroppedHeadSeparately() {
        for ((head,neck) in listOf(30.0 to 0.0,70.0 to 10.0,-50.0 to 0.0,0.0 to -40.0)) {
            val f=geometry(head,neck)
            assertEquals(head.toFloat(),f.getValue(FourExerciseForm.PUSHUP_HEAD),.01f)
            assertEquals(neck.toFloat(),f.getValue(FourExerciseForm.PUSHUP_NECK),.01f)
        }
    }

    @Test fun headCoordinatesSurviveMirroringAndPhoneRoll() {
        for (mirror in listOf(false,true)) for (roll in listOf(false,true)) {
            val f=geometry(30.0,-40.0,mirrored=mirror,rotated=roll)
            assertEquals(30f,f.getValue(FourExerciseForm.PUSHUP_HEAD),.01f)
            assertEquals(-40f,f.getValue(FourExerciseForm.PUSHUP_NECK),.01f)
        }
    }

    @Test fun hiddenTinyCollapsedOrImpossibleHeadDoesNotStopBodyMeasurement() {
        for (f in listOf(geometry(hidden=0),geometry(hidden=7),geometry(faceSize=.003),geometry(faceSize=0.0),
            geometry(neckSize=.001),geometry(neckSize=.3),geometry(head=120.0))) {
            assertTrue(f.containsKey(FourExerciseForm.PUSHUP_SIGNAL))
            assertTrue(FourExerciseForm.pushupHeadFeatures.none { it in f })
        }
        assertTrue(FourExerciseForm.pushupHeadFeatures.none { it in geometry(gravity=false) })
    }

    @Test fun observedFaceTurnSuppressesSideHeadAnglesWithoutRemovingBodySignal() {
        val f=geometry(70.0)
        for (face in listOf(mapOf(FloorGaze.FOUND to 1f,FloorGaze.YAW to 50f),
            mapOf(FloorGaze.FOUND to 1f,FloorGaze.YAW to -50f),mapOf(FloorGaze.FOUND to 1f),
            mapOf(FloorGaze.FOUND to Float.NaN))) {
            val filtered=FourExerciseForm.filterPushupHead(f+face)
            assertTrue(FourExerciseForm.pushupHeadFeatures.none { it in filtered })
            assertEquals(f.getValue(FourExerciseForm.PUSHUP_SIGNAL),filtered.getValue(FourExerciseForm.PUSHUP_SIGNAL),0f)
        }
        assertTrue(FourExerciseForm.filterPushupHead(f+mapOf(FloorGaze.FOUND to 1f,FloorGaze.YAW to 10f)).containsKey(FourExerciseForm.PUSHUP_HEAD))
        assertTrue(FourExerciseForm.filterPushupHead(f+mapOf(FloorGaze.FOUND to 0f)).containsKey(FourExerciseForm.PUSHUP_HEAD))
    }

    @Test fun mildForwardHeadAndBoundaryAnglesAreAllowed() {
        for (head in listOf(-35f,0f,30f,60f)) {
            val (_,rep)=form { mapOf(FourExerciseForm.PUSHUP_HEAD to head,FourExerciseForm.PUSHUP_NECK to -25f) }
            assertTrue(rep.flagged.isEmpty()); assertTrue(rep.correct)
        }
    }

    @Test fun sustainedHeadIssuesExcludeCountsWithoutShipPromotionOrScore() {
        for ((key,value) in listOf(FourExerciseForm.PUSHUP_HEAD to 70f,FourExerciseForm.PUSHUP_HEAD to -50f,FourExerciseForm.PUSHUP_NECK to -40f)) {
            val (ev,rep)=form { mapOf(key to value) }
            assertEquals(1,rep.flagged.size); assertFalse(rep.correct); assertFalse(rep.judged)
            assertTrue(rep.flagged.single().countOnlyGate)
            val event=ev.eventFor(rep,2400L,gate=true)!!
            assertFalse(event.ship); assertTrue(event.gated); assertTrue(event.message.contains("세지 않았어요"))
            assertFalse(ev.eventFor(rep,2400L,gate=false)!!.gated)
            assertFalse(ev.summary().hasShip)
            val (red,amber)=RuleHighlight.forRepForm(rep)
            assertTrue(red.isEmpty()); assertTrue(amber.contains(0)); assertFalse(amber.contains(25))
        }
    }

    @Test fun briefObservedHeadErrorExcludesAndCameraTurnIsSeparateFromPitch() {
        assertFalse(form { i -> if (i==6) mapOf(FourExerciseForm.PUSHUP_HEAD to 85f) else emptyMap() }.second.correct)
        val (_,turned)=form { mapOf(FourExerciseForm.PUSHUP_HEAD to 85f,FloorGaze.FOUND to 1f,FloorGaze.YAW to 60f) }
        assertTrue(turned.outcomes.filter { it.check.feature in FourExerciseForm.pushupHeadFeatures }.all { it.verdict==Verdict.ABSTAIN })
        assertFalse(turned.correct)
        assertEquals(FourExerciseForm.PUSHUP_TURN,turned.flagged.single().check.feature)
    }

    @Test fun headMissingOrNonFiniteAbstainsRatherThanBecomingGoodGaze() {
        val (_,rep)=form { mapOf(FourExerciseForm.PUSHUP_HEAD to Float.NaN,FourExerciseForm.PUSHUP_NECK to Float.NaN) }
        assertTrue(rep.outcomes.filter { it.check.feature in FourExerciseForm.pushupHeadFeatures }.all { it.verdict==Verdict.ABSTAIN })
        val ctrl=FloorFeedbackController("푸시업",emptyList())
        val state=ctrl.update(0L,good()-FourExerciseForm.pushupHeadFeatures,null,emptyList())
        assertEquals(FloorFeedbackPhase.MEASURING,state.phase)
        assertTrue(state.message.contains("고개 위치는 확인하지 못했어요")); assertNull(state.speech)
    }

    @Test fun liveHeadShowsCurrentObservationAndClearsOnCorrectionOrOcclusion() {
        val ctrl=FloorFeedbackController("푸시업",emptyList())
        val bad=good()+mapOf(FourExerciseForm.PUSHUP_HEAD to 75f)
        val issue=ctrl.update(0L,bad,null,emptyList())
        assertEquals(FloorFeedbackPhase.ATTENTION,issue.phase); assertTrue(issue.message.contains("고개가 많이 들렸어요")); assertNull(issue.speech)
        assertEquals(FloorFeedbackPhase.MEASURING,ctrl.update(1000L,good(),null,emptyList()).phase)
        for (t in 1200L..2000L step 200) ctrl.update(t,bad,null,emptyList())
        val missing=ctrl.update(2200L,good()-FourExerciseForm.pushupHeadFeatures,null,emptyList())
        assertEquals(FloorFeedbackPhase.MEASURING,missing.phase); assertTrue(missing.landmarks.isEmpty())
        assertEquals(FloorFeedbackPhase.ATTENTION,ctrl.update(2400L,bad,null,emptyList()).phase)
    }

    @Test fun overlappingHeadIssuesUseOneLiveMessageAndCameraTurnTakesItsOwnChannel() {
        val ctrl=FloorFeedbackController("푸시업",emptyList())
        val bad=good()+mapOf(FourExerciseForm.PUSHUP_HEAD to -50f,FourExerciseForm.PUSHUP_NECK to -40f)
        var state=ctrl.update(0,bad,null,emptyList())
        for(t in 200L..1000L step 200) state=ctrl.update(t,bad,null,emptyList())
        assertEquals(1,state.message.lines().size); assertTrue(state.message.contains("머리가 몸통선보다"))
        val toward=bad+mapOf(FloorGaze.FOUND to 1f,FloorGaze.YAW to 60f)
        for(t in 1200L..2200L step 200) state=ctrl.update(t,toward,null,emptyList())
        assertTrue(state.message.contains("얼굴이 화면 쪽")); assertFalse(state.message.contains("머리가 몸통선보다"))
    }

    @Test fun trackAndPauseSuppressCoachingAndCoachResumesFromCurrentObservation() {
        val ctrl=FloorFeedbackController("푸시업",emptyList())
        val bad=good()+mapOf(FourExerciseForm.PUSHUP_HEAD to 75f)
        for(t in 0L..800L step 200) ctrl.update(t,bad,null,emptyList())
        assertTrue(ctrl.update(1000L,bad,null,emptyList(),mode=CoachMode.TRACK).landmarks.isEmpty())
        assertEquals(FloorFeedbackPhase.ATTENTION,ctrl.update(1200L,bad,null,emptyList()).phase)
        assertEquals(FloorFeedbackPhase.PAUSED,ctrl.update(1400L,bad,null,emptyList(),paused=true).phase)
        assertEquals(FloorFeedbackPhase.ATTENTION,ctrl.update(1600L,bad,null,emptyList()).phase)
    }

    @Test fun videoBodyAsynchronyStillExcludesTheRepWhenHeadIsUnobserved() {
        for (hip in listOf(-.2f,.2f)) {
            val (_,rep)=form { i -> mapOf(FourExerciseForm.PUSHUP_HIP to if(i in 8..10) hip else 0f,
                FourExerciseForm.PUSHUP_HEAD to Float.NaN,FourExerciseForm.PUSHUP_NECK to Float.NaN) }
            assertFalse(rep.correct); assertTrue(rep.flagged.any { it.countOnlyGate && it.check.feature==FourExerciseForm.PUSHUP_HIP })
        }
    }
}
