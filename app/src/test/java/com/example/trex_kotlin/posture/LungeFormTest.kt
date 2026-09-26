package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** 런지 걸음 기하·걸음 검사(spec §63). */
class LungeFormTest {

    // ---- Lunge2d: 3D 앞다리·앞무릎·무릎 쏠림
    private fun stance(leftFront: Boolean, shinLeanDeg: Float = 10f): PoseFrame {
        // 골반: 왼 +x, 오른 −x → 몸 앞 = +z(up = +y). 앞다리 발목은 +z 40, 뒷다리 −z 50
        val s = if (leftFront) 1f else -1f
        val fx = if (leftFront) 10f else -10f; val bx = -fx
        val fA = Vec3(fx, 5f, 40f)
        val rad = Math.toRadians(shinLeanDeg.toDouble())
        val fK = Vec3(fx, 5f + 45f * cos(rad).toFloat(), 40f + 45f * sin(rad).toFloat())
        val bA = Vec3(bx, 5f, -50f); val bK = Vec3(bx, 20f, -30f)
        val j = mutableMapOf<String, Vec3?>(Joints.L_HIP to Vec3(10f, 90f, 0f), Joints.R_HIP to Vec3(-10f, 90f, 0f))
        if (leftFront) { j[Joints.L_KNEE] = fK; j[Joints.L_ANKLE] = fA; j[Joints.R_KNEE] = bK; j[Joints.R_ANKLE] = bA }
        else { j[Joints.R_KNEE] = fK; j[Joints.R_ANKLE] = fA; j[Joints.L_KNEE] = bK; j[Joints.L_ANKLE] = bA }
        return PoseFrame(j, Vec3(0f, 1f, 0f)).also { assertTrue(s != 0f) }
    }

    @Test
    fun frontLegAndKneeShiftComeFrom3dGeometry() {
        val l = Lunge2d.features(stance(true, shinLeanDeg = 50f), FloatArray(0), FloatArray(0), 0.5f, yawShDeg = null)
        assertTrue(l.getValue(Lunge2d.FWD_D) >= Lunge2d.FRONT_MIN)
        assertEquals("무릎이 발목보다 앞으로 50°", 50f, l.getValue(Lunge2d.FRONT_SHIN), 0.5f)
        assertTrue(l.containsKey(Lunge2d.FRONT_KNEE))
        val r = Lunge2d.features(stance(false, shinLeanDeg = 10f), FloatArray(0), FloatArray(0), 0.5f, yawShDeg = null)
        assertTrue(r.getValue(Lunge2d.FWD_D) <= -Lunge2d.FRONT_MIN)
        assertEquals(10f, r.getValue(Lunge2d.FRONT_SHIN), 0.5f)
        // 두 발을 모으면 앞다리가 없다 — 앞다리 피처를 내지 않는다
        val j = mapOf(Joints.L_HIP to Vec3(10f, 90f, 0f), Joints.R_HIP to Vec3(-10f, 90f, 0f), Joints.L_KNEE to Vec3(10f, 50f, 2f), Joints.R_KNEE to Vec3(-10f, 50f, 2f),
            Joints.L_ANKLE to Vec3(10f, 5f, 0f), Joints.R_ANKLE to Vec3(-10f, 5f, 0f))
        val together = Lunge2d.features(PoseFrame(j, Vec3(0f, 1f, 0f)), FloatArray(0), FloatArray(0), 0.5f, yawShDeg = null)
        assertFalse(together.containsKey(Lunge2d.FRONT_KNEE))
    }

    @Test
    fun stepSideNeedsA3dMarginAndIsVetoedByContradictingCues() {
        fun f(fwd: Float, kh: Float? = null, names: Float? = null) = buildMap { put(Lunge2d.FWD_D, fwd); kh?.let { put(Lunge2d.KNEE_H2D, it) }; names?.let { put(Lunge2d.NAMES_OK, it) } }
        assertEquals(StepSide.LEFT, Lunge2d.stepSide(listOf(f(0.6f), f(0.5f)), "B"))
        assertEquals(StepSide.RIGHT, Lunge2d.stepSide(listOf(f(-0.7f)), "SIDE_B"))
        assertNull("앞뒤 차가 작다", Lunge2d.stepSide(listOf(f(0.1f)), "B"))
        assertNull("정면은 앞뒤가 깊이축", Lunge2d.stepSide(listOf(f(0.6f)), "C"))
        assertNull("2D 무릎 높이가 반대로 뚜렷", Lunge2d.stepSide(listOf(f(0.6f, kh = -0.2f)), "B"))
        assertEquals("2D 가 약하면 3D 를 믿는다", StepSide.LEFT, Lunge2d.stepSide(listOf(f(0.6f, kh = -0.05f)), "B"))
        assertNull("얼굴 방향 점검 실패 = 이름이 뒤바뀜", Lunge2d.stepSide(listOf(f(0.6f, names = 0f)), "B"))
    }

    // ---- 걸음 검사
    private val ex = RepFormSpecs.LUNGE
    private fun evaluator() = RepFormEvaluator(RepFormSpecs.byExercise.getValue(ex), "knee_mean", 35f,
        viewCosKey = ViewEstimator.FEAT_COS_SH, viewSinKey = ViewEstimator.FEAT_SIN_SH, stepSides = true, turnReminderSteps = 3, dipCheckId = "repform|$ex|앞무릎 깊이")

    private class Frames(val ev: RepFormEvaluator) {
        var t = 0L
        fun frame(knee: Float, minside: Float = knee, pitch: Float = 2f, fwd: Float? = null, shin: Float = 30f, sh: Float = 0f, yaw: Float = 40f, lift: Float = 0.2f) {
            val r = Math.toRadians(yaw.toDouble())
            val m = hashMapOf("knee_mean" to knee, "knee_minside" to minside, "torso_pitch" to pitch, Lunge2d.SH_LEVEL_2D to sh, Lunge2d.FOOT_LIFT to lift,
                ViewEstimator.FEAT_COS_SH to cos(r).toFloat(), ViewEstimator.FEAT_SIN_SH to sin(r).toFloat(),
                ViewEstimator.FEAT_COS to cos(r).toFloat(), ViewEstimator.FEAT_SIN to sin(r).toFloat())
            if (fwd != null) { m[Lunge2d.FWD_D] = fwd; m[Lunge2d.FRONT_SHIN] = shin }
            ev.onFrame(t, m); t += 300
        }
        /** 선 자세 4프레임 → 내려감(바닥 [bottom]) → 복귀. [side] +1 = 왼발 앞. */
        fun step(bottom: Float = 75f, side: Int = 1, pitch: Float = 2f, shin: Float = 30f, sh: Float = 0f, yaw: Float = 40f): RepFormRep {
            repeat(4) { frame(171f, yaw = yaw) }
            val fwd = 0.6f * side
            frame(140f, fwd = fwd, pitch = pitch, shin = shin, sh = sh, yaw = yaw)
            frame((bottom + 171f) / 2f - 20f, bottom, pitch, fwd, shin, sh, yaw)
            frame(bottom, bottom, pitch, fwd, shin, sh, yaw)
            frame(bottom + 5f, bottom + 5f, pitch, fwd, shin, sh, yaw)
            frame(150f, fwd = fwd, pitch = pitch, shin = shin, sh = sh, yaw = yaw)
            frame(170f, yaw = yaw); frame(171f, yaw = yaw)
            return ev.onCycle(t - 300, bottom, 171f)
        }
    }

    private fun RepFormRep.o(name: String) = outcomes.first { it.check.id == "repform|$ex|$name" }

    @Test
    fun shallowStepIsGatedWithTheFrontLegMarkedAndDeepStepsPass() {
        val ev = evaluator(); val f = Frames(ev)
        val deep = f.step(bottom = 75f, side = 1)
        assertEquals(StepSide.LEFT, deep.side)
        assertEquals(Verdict.OK, deep.o("앞무릎 깊이").verdict); assertTrue(deep.correct)
        val shallow = f.step(bottom = 115f, side = -1)
        assertEquals(StepSide.RIGHT, shallow.side)
        val d = shallow.o("앞무릎 깊이")
        assertEquals(Verdict.VIOLATION, d.verdict); assertTrue(d.gate); assertFalse(shallow.correct)
        // 강조는 앞다리(오른쪽 = 짝수 인덱스 24·26·28)만
        val (red, _) = RuleHighlight.forRepForm(shallow)
        assertEquals(setOf(24, 26, 28), red)
        val e = ev.eventFor(shallow, 30_000L, gate = true)!!
        assertTrue(e.gated)
        assertEquals("덜 내려갔어요. 앞무릎이 80도 가까이 굽도록 뒷무릎을 바닥 가까이 내려 주세요.", e.message)
        assertEquals("90° 는 통과(80° + MediaPipe 오차)", Verdict.OK, f.step(bottom = 89f).o("앞무릎 깊이").verdict)
    }

    @Test
    fun leanUsesOnePoolAcrossViewsAndHasTwoTiers() {
        val ev = evaluator(); val f = Frames(ev)
        f.step(pitch = 2f, yaw = 40f); f.step(pitch = 3f, yaw = 70f)          // 기준 2걸음(뷰가 바뀌어도 한 모음)
        val coach = f.step(pitch = 24f, yaw = 70f)                           // +21 → 코칭 단계(회는 그대로)
        assertEquals(Verdict.VIOLATION, coach.o("상체 숙임").verdict); assertFalse(coach.o("상체 숙임").gate); assertTrue(coach.correct)
        val gated = f.step(pitch = 32f, yaw = 70f)                           // +29 → 차단
        assertTrue(gated.o("상체 숙임").gate); assertFalse(gated.correct)
        assertEquals("정면에서는 판정하지 않는다", Verdict.ABSTAIN, Frames(evaluator()).let { g -> repeat(3) { g.step(pitch = 2f, yaw = 0f) }; g.step(pitch = 40f, yaw = 0f) }.o("상체 숙임").verdict)
    }

    @Test
    fun frontViewStepsDoNotEnterTheSharedLeanReference() {
        // 정면(C) 걸음은 숙임을 판정하지 않는다 — 그 원값이 뷰 구분 없는 기준 모음에 들어가면 기준을 끌어내려 사선 정상 걸음을 '숙임' 으로 만든다
        val ev = evaluator(); val f = Frames(ev)
        repeat(2) { f.step(pitch = 0f, yaw = 0f) }
        f.step(pitch = 22f); f.step(pitch = 22f)
        val r = f.step(pitch = 23f)
        assertEquals(Verdict.OK, r.o("상체 숙임").verdict)
        // 기준 하한(−10°) 아래 원값(좌우 뒤바뀜으로 뒤집힌 숙임)도 넣지 않는다
        val g = Frames(evaluator())
        g.step(pitch = -30f); g.step(pitch = 12f); g.step(pitch = 12f)
        assertEquals(Verdict.OK, g.step(pitch = 13f).o("상체 숙임").verdict)
    }

    @Test
    fun bodyBendsAreNotStepsAndNotCountedAsCorrect() {
        val ev = evaluator(); val f = Frames(ev)
        f.step(); f.step(side = -1)
        // 발을 모은 채(fwd 0.1) 크게 숙인 사이클 = 걸음 아님
        repeat(4) { f.frame(171f) }
        repeat(3) { f.frame(120f, 120f, pitch = 60f, fwd = 0.1f) }
        f.frame(170f); f.frame(171f)
        val bend = ev.onCycle(f.t - 300, 120f, 171f)
        assertTrue(bend.notStep); assertFalse(bend.judged)
        assertTrue(bend.outcomes.all { it.verdict == Verdict.ABSTAIN })
        val s = ev.summary()
        assertEquals("걸음 아님은 정확에 넣지 않는다", 2, s.correct)
        assertTrue(s.lines().toString(), s.lines().contains("정확 2 / 판정 2걸음"))
        assertTrue(s.lines().any { it.startsWith("걸음이 아닌 동작") })
        assertFalse("시작 자세 검사가 없는 종목은 발 너비 문장을 쓰지 않는다", s.lines().any { it.contains("발 너비") })
    }

    @Test
    fun frontViewStepsAreUnjudgedNotCorrect() {
        val ev = evaluator(); val f = Frames(ev)
        repeat(3) { f.step(yaw = 0f) }
        val s = ev.summary()
        assertEquals(0, s.correct)
        assertFalse("판정 0건이면 '정확' 을 쓰지 않는다(원칙 #1)", s.lines().any { it.startsWith("정확") })
        assertTrue(s.lines().any { it.startsWith("자세를 판정 못 한 걸음 3") })
        assertEquals("정면이 3걸음 이어지면 방향 안내", TurnReminder.FRONT, ev.takeTurnReminder())
    }

    @Test
    fun standYawIsOnlyComputedForStepEvaluators() {
        val plain = RepFormEvaluator(RepFormSpecs.byExercise.getValue(ex), "knee_mean", 35f)
        assertNull("스쿼트·컬 로그(stand_yaw)는 그대로", Frames(plain).step().standYawDeg)
        assertNotNull(Frames(evaluator()).step().standYawDeg)
    }

    @Test
    fun kneeShiftCoachesAndShoulderTiltIsOnlyAReference() {
        val ev = evaluator(); val f = Frames(ev)
        repeat(2) { f.step(side = 1, sh = 0f) }
        val shift = f.step(side = 1, shin = 50f)
        assertEquals(Verdict.VIOLATION, shift.o("무릎 쏠림").verdict); assertFalse(shift.o("무릎 쏠림").gate); assertTrue(shift.correct)
        assertNull("코칭 전용은 2걸음 연속일 때만 말한다", ev.eventFor(shift, 60_000L, gate = true))
        val again = f.step(side = 1, shin = 50f)
        assertEquals("repform|$ex|무릎 쏠림", ev.eventFor(again, 90_000L, gate = true)!!.check.id)
        // 어깨: 같은 앞다리의 처음 2걸음이 기준 — 반대 다리는 따로 모은다
        val tilt = f.step(side = 1, sh = 0.2f)
        assertEquals(Verdict.VIOLATION, tilt.o("어깨 기울기").verdict); assertTrue(tilt.correct)
        // beta — 화면 '참고' 사건만(음성 아님). 폰 검출률이 없다(사용자 세트가 옆이라 전부 유보)
        val te = ev.eventFor(tilt, 120_000L, gate = true)!!
        assertEquals("repform|$ex|어깨 기울기", te.check.id); assertFalse(te.ship)
        assertEquals(Verdict.ABSTAIN, f.step(side = -1, sh = 0.2f).o("어깨 기울기").verdict)
        assertEquals("옆에서는 어깨 기울기를 못 본다", Verdict.ABSTAIN, Frames(evaluator()).let { g -> repeat(2) { g.step(yaw = 70f) }; g.step(sh = 0.3f, yaw = 70f) }.o("어깨 기울기").verdict)
        assertFalse("정면은 앞다리 쪽을 몰라 늘 유보 — 뷰에서 뺐다", "C" in RepFormSpecs.byExercise.getValue(ex).first { it.id.endsWith("어깨 기울기") }.views)
    }

    @Test
    fun missedShallowDipIsReportedOnceTheCounterIsNotMidStep() {
        val ev = evaluator(); val f = Frames(ev)
        f.step(bottom = 75f)                                               // 운동 단계(첫 걸음을 셌다)
        // 카운터가 못 센 얕은 걸음 — 무릎 평균 171 → 150 → 171, 두 무릎 중 더 굽은 쪽 118°
        repeat(3) { f.frame(171f) }
        f.frame(160f, 130f, fwd = 0.6f); f.frame(150f, 118f, fwd = 0.6f); f.frame(158f, 125f, fwd = 0.6f)
        f.frame(170f); f.frame(171f)
        assertNull("카운터가 걸음 도중이면 말하지 않는다", ev.missedDipEvent(f.t, counterMidCycle = true))
        val (e, side) = ev.missedDipEvent(f.t, counterMidCycle = false)!!
        assertEquals("repform|$ex|앞무릎 깊이", e.check.id); assertEquals(StepSide.LEFT, side)
        assertNull("같은 구간을 두 번 알리지 않는다", ev.missedDipEvent(f.t, counterMidCycle = false))
        // 무릎 들기(발 들림)는 걸음이 아니다
        repeat(3) { f.frame(171f) }
        f.frame(155f, 120f, fwd = 0.4f, lift = 1.3f); f.frame(150f, 118f, fwd = 0.4f, lift = 1.4f)
        f.frame(170f); f.frame(171f)
        assertNull(ev.missedDipEvent(f.t, counterMidCycle = false))
        // 첫 걸음 전(기준 없음)에는 알리지 않는다
        val ev2 = evaluator(); val g = Frames(ev2)
        repeat(3) { g.frame(171f) }; g.frame(150f, 118f, fwd = 0.6f); g.frame(150f, 118f, fwd = 0.6f); g.frame(171f); g.frame(171f)
        assertNull(ev2.missedDipEvent(g.t, counterMidCycle = false))
    }

    @Test
    fun missedDipIgnoresFrontViewAndWalkingButCountsSplitSquats() {
        // 정면(C): 잠금 전이라도 구간 자기 방향으로 거른다 — 깊이 검사는 정면을 판정하지 않는다
        val ev = evaluator(); val f = Frames(ev)
        f.step(bottom = 75f)
        repeat(3) { f.frame(171f, yaw = 0f) }
        f.frame(155f, 120f, fwd = 0.6f, yaw = 0f); f.frame(150f, 118f, fwd = 0.6f, yaw = 0f); f.frame(158f, 125f, fwd = 0.6f, yaw = 0f)
        f.frame(170f, yaw = 0f); f.frame(171f, yaw = 0f)
        assertNull(ev.missedDipEvent(f.t, counterMidCycle = false))
        // 걷기: 앞다리가 구간 안에서 바뀐다(벌림 부호가 뒤집힘)
        repeat(3) { f.frame(171f) }
        f.frame(155f, 120f, fwd = 0.6f); f.frame(150f, 118f, fwd = -0.6f); f.frame(156f, 124f, fwd = 0.5f)
        f.frame(170f); f.frame(171f)
        assertNull(ev.missedDipEvent(f.t, counterMidCycle = false))
        // 제자리 스플릿(발을 앞뒤로 둔 채 오르내림)도 같은 걸음이다 — 사용자 세트(045533)는 선 자세에서도 앞뒤 벌림 −0.8 이었다
        repeat(3) { f.frame(171f, fwd = -0.8f) }
        f.frame(155f, 120f, fwd = -0.8f); f.frame(150f, 118f, fwd = -0.8f)
        f.frame(170f, fwd = -0.8f); f.frame(171f, fwd = -0.8f)
        assertEquals(StepSide.RIGHT, ev.missedDipEvent(f.t, counterMidCycle = false)!!.second)
    }

    @Test
    fun missedDipCooldownIsOnlySpentWhenSpoken() {
        // TRACK(말하지 않음)에서 쓴 쿨다운 때문에 COACH 로 바꾼 뒤 첫 교정이 짧은 단서로 나가면 안 된다
        val ev = evaluator(); val f = Frames(ev)
        f.step(bottom = 75f)
        fun dip() { repeat(3) { f.frame(171f) }; f.frame(155f, 120f, fwd = 0.6f); f.frame(150f, 118f, fwd = 0.6f); f.frame(170f); f.frame(171f) }
        dip(); val silent = ev.missedDipEvent(f.t, counterMidCycle = false, speak = false)!!.first
        dip(); val spoken = ev.missedDipEvent(f.t, counterMidCycle = false, speak = true)!!.first
        assertFalse(silent.brief); assertFalse("첫 교정은 문장 전체", spoken.brief)
        dip(); assertTrue("말한 뒤 쿨다운 안은 짧은 단서", ev.missedDipEvent(f.t, counterMidCycle = false)!!.first.brief)
    }

    @Test
    fun turnReminderFiresOnceAfterThreeSideOnSteps() {
        val ev = evaluator(); val f = Frames(ev)
        f.step(yaw = 70f); f.step(yaw = 70f); assertNull(ev.takeTurnReminder())
        f.step(yaw = 70f); assertEquals(TurnReminder.SIDE, ev.takeTurnReminder()); assertNull(ev.takeTurnReminder())
        repeat(3) { f.step(yaw = 70f) }; assertNull("세트에서 한 번", ev.takeTurnReminder())
        val ev2 = evaluator(); val g = Frames(ev2)
        g.step(yaw = 70f); g.step(yaw = 70f); g.step(yaw = 40f); g.step(yaw = 70f); g.step(yaw = 70f)
        assertNull("45° 로 돌아온 걸음이 끊는다", ev2.takeTurnReminder())
    }

    @Test
    fun lungeWindowRulesAreDemotedWithTheirOwnReasons() {
        val pitch = PostureRule("$ex|상체의 과조한 숙임/젖힘 여부", ex, "상체의 과조한 숙임/젖힘 여부", null, RuleStatus.SHIP, null,
            "torso_pitch__min", "torso_pitch", "min", "world", "<", -3.97f, "B", "사선", .86f, .74f, 60, true, emptyList())
        val merged = PostureRuleSet("mp_v0.1", "", listOf(pitch)).plusRepForm()
        val r = merged.rules.first { it.id == pitch.id }
        assertEquals(RuleStatus.BETA, r.status)
        assertTrue(r.cautions.last().contains("뒤로 젖힘만"))
    }
}
