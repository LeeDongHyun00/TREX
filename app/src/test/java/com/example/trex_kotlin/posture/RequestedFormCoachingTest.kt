package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

class RequestedFormCoachingTest {
    private val wave = listOf(1.4f,1.4f,1.4f,1.4f,1.2f,1f,.8f,.6f,.6f,.8f,1f,1.2f,1.4f,1.4f,1.4f)
    private fun base() = mapOf("pushup_side_ready" to 1f, "pushup_knee_angle_L" to 175f,
        "pushup_knee_clearance_L" to .35f, "pushup_hip_offset_L" to 0f,
        "pushup_head_pitch_L" to 0f, "pushup_neck_pitch_L" to 0f)
    private fun features(extra: Map<String,Float> = emptyMap()) = PushupFormSpecs.withSupport(base() + extra)
    private fun evaluator() = RepFormEvaluator(PushupFormSpecs.checks(), "wrist_shoulder_d", .3f)
    private fun rep(frame: (Int)->Map<String,Float>, offset: Long = 0, evaluator: RepFormEvaluator = evaluator()): RepFormRep {
        wave.forEachIndexed { i, v -> evaluator.onFrame(offset+i*300L, frame(i)+("wrist_shoulder_d" to v)) }
        return evaluator.onCycle(offset+4200L,.6f,1.4f,offset+900L)
    }

    @Test fun kneeSupportNeedsBentKneeAndLowClearanceOnSameSide() {
        val bentHigh = rep({ features(mapOf("pushup_knee_angle_L" to 110f)) })
        val straightLow = rep({ features(mapOf("pushup_knee_clearance_L" to .02f)) })
        val kneeDown = rep({ features(mapOf("pushup_knee_angle_L" to 110f,"pushup_knee_clearance_L" to .02f)) })
        assertFalse(RequestedFormCoaching.excluded(bentHigh))
        assertFalse(RequestedFormCoaching.excluded(straightLow))
        assertTrue(RequestedFormCoaching.excluded(kneeDown))
        assertTrue(RequestedFormCoaching.message(kneeDown)!!.contains("무릎"))
        assertTrue(kneeDown.correct) // 검증 완료 검사 점수는 임의로 바뀌지 않는다.
        assertFalse(kneeDown.judged)
    }

    @Test fun missingClearanceDoesNotCreateProofOfKneeSupport() {
        val r = rep({ PushupFormSpecs.withSupport(base().minus("pushup_knee_clearance_L") + ("pushup_knee_angle_L" to 90f)) })
        assertEquals(Verdict.ABSTAIN,r.outcomes.first { it.check.feature == "pushup_knee_support_L" }.verdict)
        assertFalse(RequestedFormCoaching.excluded(r))
    }

    @Test fun aSingleBadFrameDoesNotExcludeNormalRepetition() {
        val r = rep({ i -> features(if (i == 8) mapOf("pushup_knee_angle_L" to 90f,"pushup_knee_clearance_L" to 0f) else emptyMap()) })
        assertFalse(RequestedFormCoaching.excluded(r))
    }

    @Test fun cameraFacingAndHeadPitchAreSeparateChecks() {
        val camera = rep({ features(mapOf("pushup_face_yaw" to 70f)) })
        assertTrue(RequestedFormCoaching.excluded(camera))
        assertTrue(RequestedFormCoaching.message(camera)!!.contains("카메라"))
        assertEquals(Verdict.OK,camera.outcomes.first { it.check.feature == "pushup_head_pitch_L" }.verdict)
        val pitch = rep({ features(mapOf("pushup_head_pitch_L" to 50f)) })
        assertEquals(Verdict.VIOLATION,pitch.outcomes.first { it.check.feature == "pushup_head_pitch_L" }.verdict)
        assertFalse(RequestedFormCoaching.excluded(pitch))
        assertEquals(Verdict.ABSTAIN,pitch.outcomes.first { it.check.feature == "pushup_face_yaw" }.verdict)
    }

    @Test fun unknownFaceAndSideHandWidthAreNotMarkedNormal() {
        val r = rep({ features() })
        for (feature in listOf("pushup_face_yaw","pushup_hand_width"))
            assertEquals(Verdict.ABSTAIN,r.outcomes.first { it.check.feature == feature }.verdict)
        val state = RequestedFormState("푸시업").onFrame(4200,features()).onReps(listOf(r),emptyList(),false,4200)
        assertTrue(state.scopeNote!!.contains("손 간격"))
        assertTrue(state.scopeNote!!.contains("얼굴 방향 미확인"))
    }

    @Test fun observedWideAndNarrowHandsAreExcludedAndUsualWidthIsRetained() {
        for (width in listOf(.4f,2.5f)) assertTrue(RequestedFormCoaching.excluded(rep({ features(mapOf("pushup_hand_width" to width)) })))
        assertFalse(RequestedFormCoaching.excluded(rep({ features(mapOf("pushup_hand_width" to 1.15f)) })))
    }

    @Test fun sparseFaceNanAndLostPoseCannotBecomeViolationOrNormal() {
        val sparse = rep({ i -> features(if (i == 8) mapOf("pushup_face_yaw" to 80f) else emptyMap()) })
        assertEquals(Verdict.ABSTAIN,sparse.outcomes.first { it.check.feature == "pushup_face_yaw" }.verdict)
        val missing = rep({ mapOf("pushup_face_yaw" to Float.NaN) })
        assertFalse(RequestedFormCoaching.observed(missing))
        assertFalse(RequestedFormCoaching.excluded(missing))
    }

    @Test fun cameraTurnThenNoFaceDoesNotReuseLastAngle() {
        val ev = evaluator()
        val turned = rep({ features(mapOf("pushup_face_yaw" to 70f)) }, evaluator=ev)
        val next = rep({ features() },6000,ev)
        assertTrue(RequestedFormCoaching.excluded(turned))
        assertFalse(RequestedFormCoaching.excluded(next))
    }

    @Test fun coachAndTrackUseSameRawCyclesButDifferentProgress() {
        val rc = RepCounter.forSession("푸시업","min",.7101f,floor=true)!!
        val ev = RepFormSpecs.evaluatorFor("푸시업",rc)!!
        val records = arrayListOf<RepRecord>()
        var state = RequestedFormState("푸시업")
        var notShort = 0; var short = 0
        repeat(3) { cycle -> wave.forEachIndexed { i, signal ->
            val extra = if (cycle==1) mapOf("pushup_knee_angle_L" to 100f,"pushup_knee_clearance_L" to 0f) else emptyMap()
            val f = features(extra) + ("wrist_shoulder_d" to signal)
            val now = (cycle*wave.size+i)*300L
            state=state.onFrame(now,f); ev.onFrame(now,f)
            if (rc.onFrameFeatures(now,f)) {
                val tally=RepUnitAccumulator.onCounterFrame(rc,now,records,null)
                val reps=tally.records.map { ev.onCycle(it.tMs,it.cycleMin,it.cycleMax,it.cycleStartMs) }
                state=state.onReps(reps,tally.records,false,now)
                notShort+=tally.repsNotShort; short+=tally.repsShort
            }
        } }
        assertEquals(3,rc.reps)
        assertEquals(1,state.excluded)
        assertEquals(2,RequestedFormCoaching.counted("푸시업",true,notShort,short,false,0,state.excluded))
        assertEquals(3,RequestedFormCoaching.counted("푸시업",false,notShort,short,false,0,state.excluded))
        assertEquals(1,ev.summary().reps.count(RequestedFormCoaching::excluded))
    }

    @Test fun alreadyExcludedShortRepIsNotDeductedTwice() {
        val r=rep({features(mapOf("pushup_face_yaw" to 70f))})
        val state=RequestedFormState("푸시업").onReps(listOf(r),listOf(RepRecord(r.tMs,.8f,1.4f,false)),true,4200)
        assertEquals(0,state.excluded)
    }

    @Test fun missingFrameAndPauseClearCueButKeepCountAndNewSetClearsCount() {
        val r=rep({features(mapOf("pushup_face_yaw" to 70f))})
        val state=RequestedFormState("푸시업").onFrame(4200,features(mapOf("pushup_face_yaw" to 70f))).onReps(listOf(r),emptyList(),false,4200)
        assertTrue(state.message!!.contains("카메라")); assertNotNull(state.speech(4200)); assertNull(state.speech(4500))
        val missing=state.onFrame(4500,emptyMap())
        assertFalse(missing.message!!.contains("이 회는 세지")); assertTrue(missing.landmarks.isEmpty())
        assertEquals(1,state.clearObservation().excluded); assertNull(state.clearObservation().speech(4200))
        assertEquals(0,RequestedFormState("푸시업").excluded)
    }

    @Test fun otherExercisesAndCompletedTenAreOutsideNewPolicy() {
        for (exercise in listOf("크런치","플랭크","레그레이즈","레그 레이즈","바벨 스쿼트","런지","덤벨 컬","사이드 런지","크로스 런지","스탠딩 사이드 크런치","스탠딩 니업","니푸쉬업")) {
            assertFalse(RequestedFormCoaching.isTarget(exercise))
            assertEquals(8,RequestedFormCoaching.counted(exercise,true,9,1,false,2,7))
            assertNull(RequestedFormState(exercise).message)
        }
    }
}
