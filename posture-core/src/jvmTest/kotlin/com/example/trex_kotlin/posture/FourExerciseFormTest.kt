// Android 정본 테스트 — tools/sync_ios_core.py
package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** §123: 사용자 보고 동작의 구조적 재현. 합성 좌표 테스트는 실기기 정확도 검증이 아니다. */
class FourExerciseFormTest {
    private fun standing(pitch: Float = 0f, kneeForward: Float = 0f, plane: Float = 30f, raised: Boolean = false): Map<String, Float> {
        val p = Math.toRadians(pitch.toDouble())
        val a = Math.toRadians(plane.toDouble())
        val sh = Vec3(20f, 100f + 40f * cos(p).toFloat(), 40f * sin(p).toFloat())
        val el = if (raised) sh + Vec3(30f * cos(a).toFloat(), 0f, 30f * sin(a).toFloat()) else sh + Vec3(0f, -30f, 0f)
        val joints = mapOf(Joints.L_HIP to Vec3(10f, 100f, 0f), Joints.R_HIP to Vec3(-10f, 100f, 0f),
            Joints.L_SHOULDER to sh, Joints.R_SHOULDER to sh.copy(x = -20f),
            Joints.L_ELBOW to el, Joints.R_ELBOW to el.copy(x = -el.x),
            Joints.L_WRIST to (el + Vec3(10f, -2f, 0f)), Joints.R_WRIST to (el.copy(x = -el.x) + Vec3(-10f, -2f, 0f)),
            Joints.L_KNEE to Vec3(10f, 60f, kneeForward), Joints.R_KNEE to Vec3(-10f, 60f, kneeForward),
            Joints.L_ANKLE to Vec3(10f, 20f, 0f), Joints.R_ANKLE to Vec3(-10f, 20f, 0f))
        return PoseFrame(joints).features()
    }

    private fun floor(seated: Boolean = false, hipY: Float = .35f, hidden: Int? = null, rotate: Boolean = false,
                      mirror: Boolean = false, up: Boolean = true, descent: Float = 0f,
                      bentArm: Boolean = false, collapsedElbow: Boolean = false, farShift: Float = .01f): Map<String, Float> {
        val xy = FloatArray(66); val vis = FloatArray(33)
        for (o in 0..1) {
            fun point(i: Int, x: Float, y: Float) { xy[2*(i+o)] = x + farShift * o; xy[2*(i+o)+1] = y; vis[i+o] = 1f }
            point(11, if (seated) .4f else .2f, if (seated) .2f else .3f + descent)
            point(23, .4f, if (seated) .55f else hipY + descent * .7f)
            point(25, .65f, if (seated) .55f else .4f + descent * .3f); point(27, .85f, if (seated) .8f else .45f)
            point(13, .2f, .45f); point(15, .2f, .6f)
            // 팔 길이 0.15를 유지하는 평면 기하. 내려가면 팔꿈치가 옆으로 접힌다.
            if (bentArm) {
                val half = (.3f - descent) / 2
                point(13, .2f + sqrt((.15f * .15f - half * half).coerceAtLeast(0f)), .45f + descent / 2)
            }
            if (collapsedElbow) point(13, .2f, .3f + descent)
        }
        hidden?.let { vis[it] = 0f; vis[it + 1] = 0f }
        for (i in 0..32) {
            if (mirror) xy[2*i] = 1f - xy[2*i]
            if (rotate) { val x = xy[2*i]; xy[2*i] = 1f - xy[2*i+1]; xy[2*i+1] = x }
        }
        return FloorFeatureExtractor().computeForExercise("푸시업", xy, vis, 1000, 1000,
            if (!up) null else if (rotate) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f), 0L)
    }

    private fun form(ex: String, featureValues: (Int) -> Map<String, Float>, view: Boolean = true): Pair<RepFormEvaluator, RepFormRep> {
        val signal = RepSignals.byExercise.getValue(ex)
        val ev = RepFormSpecs.evaluatorFor(ex, RepCounter.forExercise(ex)!!)!!
        val shape = listOf(175f,175f,175f,160f,130f,95f,90f,90f,95f,130f,160f,175f,175f)
        shape.forEachIndexed { i, s ->
            val v = if (view && ex != "푸시업") mapOf("view_cos" to .866f, "view_sin" to .5f) else emptyMap()
            val prerequisite = signal.requiredPostureFeature?.let { mapOf(it to 1f) }.orEmpty()
            ev.onFrame(i * 300L, featureValues(i) + v + prerequisite + (signal.feature to s))
        }
        return ev to ev.onCycle(3600L, 90f, 175f)
    }

    @Test fun goodMorningCountsTrunkMotionButNotKneesAlone() {
        fun count(kneesOnly: Boolean): Int {
            val rc = RepCounter.forSession("굿모닝", floor = false)!!
            for (i in 0..150) {
                val wave = ((1 - cos(i * Math.PI / 6)) / 2).toFloat()
                rc.onFrameFeatures(i * 300L, standing(pitch = if (kneesOnly) 55f else wave * 65f, kneeForward = if (kneesOnly) wave * 45f else 5f))
            }
            return rc.reps
        }
        assertEquals(0, count(true)); assertTrue(count(false) >= 5)
    }

    @Test fun newSignalsDoNotReuseOldRomCalibrationOrChangeOtherExercises() {
        for (ex in FourExerciseForm.changedSignalExercises) {
            val s = RepSignals.byExercise.getValue(ex)
            assertNull(s.romThreshold); assertFalse(s.validated)
            assertNull(RepCounter.forSession(ex, "min", .71f, floor = ex == "푸시업")!!.signal.romThreshold)
        }
        assertEquals("hip_mean", RepSignals.byExercise.getValue("바벨 데드리프트").feature)
        assertEquals("wrist_shoulder_d", RepSignals.byExercise.getValue("니푸쉬업").feature)
        assertEquals("hip_mean", RepSignals.byExercise.getValue("굿모닝").comparisonSignal().feature)
        assertNull(RepSignals.byExercise.getValue("푸시업").comparisonSignal().requiredPostureFeature)
    }

    @Test fun raisesCountUpperArmMotionEvenWithAStationaryForearm() {
        for (ex in listOf("프런트 레이즈", "사이드 레터럴 레이즈")) {
            val rc = RepCounter.forSession(ex, floor = false)!!
            assertEquals("forearm_vert_mean", rc.signal.comparisonSignal().feature)
            for (i in 0..150) {
                val upperArm = 175f - 85f * ((1 - cos(i * Math.PI / 6)) / 2).toFloat()
                rc.onFrameFeatures(i * 300L, mapOf(FourExerciseForm.RAISE_SIGNAL to upperArm, "forearm_vert_mean" to 90f))
            }
            assertTrue(rc.reps >= 5)
            val curlOnly = RepCounter.forSession(ex, floor = false)!!
            for (i in 0..150) curlOnly.onFrameFeatures(i * 300L, mapOf(FourExerciseForm.RAISE_SIGNAL to 175f,
                "forearm_vert_mean" to (130f + 45f * sin(i * Math.PI / 6).toFloat())))
            assertEquals(0, curlOnly.reps)
        }
    }

    @Test fun raiseHeightBlocksEitherLowArmWithoutPromotingBetaToSpeechOrScore() {
        for (ex in listOf("프런트 레이즈", "사이드 레터럴 레이즈")) {
            for (lowSide in listOf("L", "R")) {
                val (ev, rep) = form(ex, { mapOf("upperarm_vert_L" to if (lowSide == "L") 140f else 90f,
                    "upperarm_vert_R" to if (lowSide == "R") 140f else 90f, "torso_incl" to 40f) })
                assertEquals(1, rep.flagged.count { it.countOnlyGate })
                assertFalse(rep.correct)
                val event = ev.eventFor(rep, 3600L, gate = true)!!
                assertTrue(event.check.feature.endsWith(lowSide)); assertTrue(event.gated); assertFalse(event.ship)
                assertTrue(event.message.contains("이 회는 세지 않았어요"))
                assertFalse(ev.summary().hasShip); assertFalse(rep.judged)
                val trackEvent = ev.eventFor(rep, 3600L, gate = false)!!
                assertFalse(trackEvent.gated); assertFalse(trackEvent.message.contains("이 회는 세지 않았어요"))
            }
        }
    }

    @Test fun raiseHeightKeepsTheLegacyBoundaryAndDoesNotBlockOverheadOrMissingEvidence() {
        for (ex in listOf("프런트 레이즈", "사이드 레터럴 레이즈")) {
            for (height in listOf(90f, 125f, 45f)) {
                val (_, rep) = form(ex, { mapOf("upperarm_vert_L" to height, "upperarm_vert_R" to height) })
                assertTrue("$ex / $height", rep.correct)
                assertFalse(rep.flagged.any { it.countOnlyGate })
            }
            val (_, below) = form(ex, { mapOf("upperarm_vert_L" to 125.1f, "upperarm_vert_R" to 125.1f) })
            assertFalse(below.correct)
            for (hasView in listOf(true, false)) {
                val (_, missing) = form(ex, { if (hasView) emptyMap() else mapOf("upperarm_vert_L" to 140f, "upperarm_vert_R" to 140f) }, view = hasView)
                assertTrue(missing.outcomes.all { it.verdict == Verdict.ABSTAIN }); assertTrue(missing.correct)
            }
            val (_, sparse) = form(ex, { i -> if (i == 6) mapOf("upperarm_vert_L" to 140f, "upperarm_vert_R" to 140f) else emptyMap() })
            assertTrue(sparse.outcomes.all { it.verdict == Verdict.ABSTAIN }); assertTrue(sparse.correct)
        }
    }


    @Test fun seatedArmsCannotStartAPushupCounter() {
        val rc = RepCounter.forSession("푸시업", floor = true)!!
        val seated = floor(seated = true)
        assertEquals(0f, seated[FourExerciseForm.PUSHUP_POSTURE]!!, 0f)
        for (i in 0..150) rc.onFrameFeatures(i * 300L, seated + (FourExerciseForm.PUSHUP_SIGNAL to (1f + .6f * sin(i * Math.PI / 6).toFloat())))
        assertEquals(0, rc.reps)
    }

    @Test fun proneMotionCountsAndUnavailablePostureDoesNotBridgeACycle() {
        val rc = RepCounter.forSession("푸시업", floor = true)!!
        val prone = floor()
        assertEquals(1f, prone[FourExerciseForm.PUSHUP_POSTURE]!!, 0f)
        // 실제 좌표 → 보이는 사슬 → 세션 카운터까지 같은 경로. 손목·발목은 고정하고 가슴을 내린다.
        for (i in 0..150) rc.onFrameFeatures(i * 300L, floor(descent = .13f * (1f - cos(i * Math.PI / 6).toFloat())))
        assertTrue(rc.reps >= 5)
        val before = rc.reps
        for (i in 151..180) rc.onFrameFeatures(i * 300L, mapOf(FourExerciseForm.PUSHUP_SIGNAL to .2f))
        assertEquals(before, rc.reps); assertFalse(rc.midCycle)
    }

    @Test fun pushupRequiresVisibleLegsAndKnownGravity() {
        assertFalse(floor(hidden = 27).containsKey(FourExerciseForm.PUSHUP_SIGNAL))
        assertFalse(floor(up = false).containsKey(FourExerciseForm.PUSHUP_SIGNAL))
    }

    @Test fun pushupSignedAlignmentIsMirrorAndRollInvariant() {
        val normal = floor(); val high = floor(hipY = .2f); val low = floor(hipY = .5f)
        val key = FourExerciseForm.PUSHUP_HIP
        assertTrue(high.getValue(key) > .08f); assertTrue(low.getValue(key) < -.08f)
        assertEquals(normal.getValue(key), floor(mirror = true).getValue(key), 1e-5f)
        assertEquals(high.getValue(key), floor(hipY = .2f, rotate = true).getValue(key), 1e-5f)
    }

    @Test fun lateralPlaneUsesBodyAxesAndOnlyRaisedArms() {
        assertFalse(standing().containsKey("raise_plane_L"))
        val f = standing(raised = true)
        assertEquals(30f, f.getValue("raise_plane_L"), .001f)
        assertEquals(30f, f.getValue("raise_plane_R"), .001f)
        assertEquals(-20f, standing(plane = -20f, raised = true).getValue("raise_plane_L"), .001f)
    }

    @Test fun frontRaiseDetectsLeanFromFirstRepAndKeepsBetaSilent() {
        val (ev, rep) = form("프런트 레이즈", { mapOf("torso_incl" to 40f, "torso_pitch" to 40f) })
        assertTrue(rep.flagged.any { it.check.condition.startsWith("상체 기울기") })
        val event = ev.eventFor(rep, 3600L, gate = true)!!
        assertFalse(event.ship); assertFalse(event.gated); assertTrue(event.message.contains("몸통을 세우고"))
        assertTrue(rep.correct); assertFalse(ev.summary().hasShip)
    }

    @Test fun frontRaiseDetectsBodySwingWithinTheRep() {
        val (_, r) = form("프런트 레이즈", { i -> mapOf("torso_incl" to 5f, "torso_pitch" to if (i % 2 == 0) 30f else -10f) })
        assertTrue(r.flagged.any { it.check.condition.startsWith("몸통 흔들림") })
    }

    @Test fun unknownViewAndSparseMeasurementsAbstain() {
        val (_, unknown) = form("프런트 레이즈", { mapOf("torso_incl" to 40f) }, view = false)
        assertTrue(unknown.outcomes.all { it.verdict == Verdict.ABSTAIN })
        val (_, sparse) = form("프런트 레이즈", { i -> if (i == 6) mapOf("torso_incl" to 40f) else emptyMap() })
        assertTrue(sparse.outcomes.all { it.verdict == Verdict.ABSTAIN })
        val (_, nan) = form("프런트 레이즈", { mapOf("torso_incl" to Float.NaN, "torso_pitch" to Float.POSITIVE_INFINITY) })
        assertTrue(nan.outcomes.all { it.verdict == Verdict.ABSTAIN })
    }

    @Test fun goodMorningAllowsSoftFixedKneesAndObservesOneSideMoving() {
        val (_, normal) = form("굿모닝", { mapOf("knee_L" to 165f, "knee_R" to 165f) })
        assertTrue(normal.outcomes.all { it.verdict == Verdict.OK })
        val (_, bad) = form("굿모닝", { i -> mapOf("knee_L" to if (i % 2 == 0) 120f else 170f, "knee_R" to 165f) })
        assertTrue(bad.flagged.any { it.check.id.contains("왼쪽 무릎 움직임") })
        assertFalse(bad.flagged.any { it.check.id.contains("오른쪽") })
    }

    @Test fun lateralBentElbowsAndForwardPlaneAreSeparateChecks() {
        val (_, normal) = form("사이드 레터럴 레이즈", { mapOf("elbow_L" to 160f, "elbow_R" to 160f, "raise_plane_L" to 30f, "raise_plane_R" to 30f,
            "upperarm_vert_L" to 90f, "upperarm_vert_R" to 90f) })
        assertTrue(normal.outcomes.all { it.verdict == Verdict.OK })
        val (_, bad) = form("사이드 레터럴 레이즈", { mapOf("elbow_L" to 180f, "elbow_R" to 160f, "raise_plane_L" to -20f, "raise_plane_R" to 30f) })
        assertEquals(2, bad.flagged.size)
        assertTrue(bad.flagged.all { it.check.id.contains("왼쪽") })
    }

    @Test fun pushupBodyAlignmentIsJudgedWithoutStandingViewFeatures() {
        val (_, rep) = form("푸시업", { mapOf(FourExerciseForm.PUSHUP_HIP to -.15f, FourExerciseForm.PUSHUP_KNEE to 120f) }, view = false)
        assertEquals(2, rep.flagged.size); assertTrue(rep.correct)
        assertTrue(rep.flagged.all { !it.gate && it.check.status == RuleStatus.BETA })
    }

    @Test fun pushupElbowGeometryNeedsObservedSideAndSurvivesMirrorAndPhoneRoll() {
        val straight = floor(bentArm = true).getValue(FourExerciseForm.PUSHUP_ELBOW)
        val lowered = floor(bentArm = true, descent = .1f).getValue(FourExerciseForm.PUSHUP_ELBOW)
        assertEquals(180f, straight, .1f)
        assertTrue(lowered in 80f..90f)
        assertEquals(lowered, floor(bentArm = true, descent = .1f, mirror = true).getValue(FourExerciseForm.PUSHUP_ELBOW), .01f)
        assertEquals(lowered, floor(bentArm = true, descent = .1f, rotate = true).getValue(FourExerciseForm.PUSHUP_ELBOW), .01f)
        for (f in listOf(floor(up = false), floor(hidden = 13), floor(hidden = 27), floor(seated = true),
            floor(collapsedElbow = true), floor(farShift = .12f))) assertFalse(f.containsKey(FourExerciseForm.PUSHUP_ELBOW))
    }

    @Test fun pushupDepthAndStartingExtensionUseSeparatePhases() {
        fun evaluate(top: Float, bottom: Float) = form("푸시업", { i ->
            mapOf(FourExerciseForm.PUSHUP_HIP to 0f, FourExerciseForm.PUSHUP_KNEE to 175f,
                FourExerciseForm.PUSHUP_ELBOW to if (i <= 2 || i >= 11) top else bottom)
        })
        val (_, normal) = evaluate(170f, 90f)
        assertTrue(normal.outcomes.all { it.verdict == Verdict.OK })
        val (ev, shallow) = evaluate(170f, 140f)
        assertEquals(listOf("팔 굽힘 범위(반복)"), shallow.flagged.map { it.check.condition })
        assertTrue(shallow.correct); assertFalse(ev.eventFor(shallow, 3600L, gate = true)!!.ship)
        val (_, bentStart) = evaluate(130f, 90f)
        assertEquals(listOf("시작 팔 펴기(반복)"), bentStart.flagged.map { it.check.condition })
        assertTrue(bentStart.correct)
        assertTrue(evaluate(150f, 120f).second.flagged.isEmpty())
        assertTrue(ev.checks.all { !it.gates && it.countOnlyDirection == null })
    }

    @Test fun pushupArmChecksAbstainForMissingPhaseAndSparseOrNonFiniteValues() {
        for (values in listOf<(Int) -> Map<String, Float>>(
            { emptyMap() },
            { i -> if (i == 6) mapOf(FourExerciseForm.PUSHUP_ELBOW to 140f) else emptyMap() },
            { mapOf(FourExerciseForm.PUSHUP_ELBOW to Float.NaN) },
        )) {
            val (_, rep) = form("푸시업", values)
            assertTrue(rep.outcomes.filter { it.check.feature == FourExerciseForm.PUSHUP_ELBOW }.all { it.verdict == Verdict.ABSTAIN })
            assertTrue(rep.correct)
        }
        // 하강 직전 관측 없이 바닥에서 시작한 영상에 시작 팔 폄을 추정하지 않는다.
        val (_, bottomOnly) = form("푸시업", { i -> if (i in 4..8) mapOf(FourExerciseForm.PUSHUP_ELBOW to 90f) else emptyMap() })
        assertEquals(Verdict.ABSTAIN, bottomOnly.outcomes.first { it.check.phase == RepPhase.TOP }.verdict)
        assertEquals(Verdict.OK, bottomOnly.outcomes.first { it.check.phase == RepPhase.BOTTOM }.verdict)
    }

    @Test fun allNewChecksHaveConservativePolicyAndScope() {
        assertEquals(4, FourExerciseForm.byExercise.size)
        for ((ex, checks) in FourExerciseForm.byExercise) {
            assertNotNull(RepFormSpecs.evaluatorFor(ex, RepCounter.forExercise(ex)!!))
            assertTrue(checks.all { it.status == RuleStatus.BETA && !it.prior })
            for (c in checks) {
                if (c.countOnlyDirection != null) {
                    assertTrue(ex in listOf("프런트 레이즈", "사이드 레터럴 레이즈"))
                    assertTrue(c.feature.startsWith("upperarm_vert_")); assertTrue(c.gates)
                    assertEquals(FormDirection.HIGH, c.countOnlyDirection)
                } else assertFalse(c.gates)
            }
            assertTrue(FourExerciseForm.scopeLines.getValue(ex).contains("참고"))
        }
        assertEquals(4, FourExerciseForm.byExercise.values.flatten().count { it.countOnlyDirection != null })
    }

    @Test fun invalidPushupPlacementClearsTheFormWindow() {
        val rc = RepCounter.forExercise("푸시업")!!
        val ev = RepFormSpecs.evaluatorFor("푸시업", rc)!!
        repeat(5) { ev.onFrame(it * 300L, mapOf(FourExerciseForm.PUSHUP_POSTURE to 1f, FourExerciseForm.PUSHUP_HIP to .2f, rc.signal.feature to 1f)) }
        ev.onFrame(1500L, mapOf(FourExerciseForm.PUSHUP_POSTURE to 0f))
        val rep = ev.onCycle(1800L, .3f, 1f)
        assertTrue(rep.outcomes.all { it.verdict == Verdict.ABSTAIN })
    }
}
