package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "말하는 모든 문장 원천 ⇒ 화면 단서"(spec §101, 10-08 '교정 화살표가 일부 종목에만') — 판별 기각(한 다리·바닥·컬 짝)·플랭크 멈춤·시선·창 규칙(ship)이 문장을 가지면 화살표(또는 점)도 가져야 한다.
 * 예외는 **촬영 안내**(자세가 아니다): 컬 `arm_unseen_*`, 플랭크 `out_of_view`.
 */
class CueMotionCoverageTest {
    private val legReasons = listOf("shallow", "torso_bent", "shallow_straight", "knee_straight", "no_abduct", "knee_over_toe", "no_lift", "no_crunch", "no_cross",
        "no_descent", "squat_like", "no_shift", "blip", "edge", "foot_lifted", "no_direction", "both_legs", "no_elbow", "timeout")
    private val floorReasons = listOf("sit_up", "neck_only", "arms_only", "shallow", "trunk_up", "knee_bent", "one_leg", "feet_touch", "timeout", "exit")

    @Test fun everySpokenLegRejectionHasAMotion() {
        for (p in LegProfile.values()) for (r in legReasons) {
            val cue = LegCycleTracker.cueFor(p, r)
            if (cue != null) assertNotNull("$p $r: 문장은 있는데 그림이 없다", LegCycleTracker.motionFor(p, r))
        }
        // 무음 사유(관측 붕괴)는 문장도 그림도 없다
        assertNull(LegCycleTracker.motionFor(LegProfile.SIDE, "blip")); assertNull(LegCycleTracker.cueFor(LegProfile.SIDE, "edge"))
        // 방향 근거(10-08 쌍 분석): 니업 얕음 = 움직인 무릎 위, 사이드 런지 squat_like = 골반 → 굽힌 발목(골반 아래는 2/5 로 틀렸다)
        val kneeUp = LegCycleTracker.motionFor(LegProfile.KNEE_UP, "shallow")!!
        assertEquals(MotionAnchor.KNEES, kneeUp.anchor); assertEquals(MotionKind.UP, kneeUp.high); assertEquals(MotionPick.MOVING, kneeUp.pick)
        val squatLike = LegCycleTracker.motionFor(LegProfile.SIDE, "squat_like")!!
        assertEquals(MotionKind.TOWARD_TARGET, squatLike.high); assertEquals(MotionAnchor.ANKLES, squatLike.target)
        // 앞뒤(무릎 발끝 넘김)·굽힘(편 다리)은 점
        assertEquals(MotionKind.MARK, LegCycleTracker.motionFor(LegProfile.SIDE, "knee_over_toe")!!.high)
        assertEquals(MotionKind.MARK, LegCycleTracker.motionFor(LegProfile.KNEE_UP, "knee_straight")!!.high)
    }

    @Test fun everySpokenFloorRejectionHasAGravityFrameMotion() {
        for (p in FloorProfile.values()) for (r in floorReasons) {
            val cue = FloorCycleTracker.cueFor(p, r)
            if (cue != null) {
                val m = FloorCycleTracker.motionFor(p, r)
                assertNotNull("$p $r: 문장은 있는데 그림이 없다", m)
                assertEquals("$p $r: 바닥은 중력 위 기준", MotionFrame.GRAVITY, m!!.frame)
            }
        }
        // '덜 하라' 는 지적은 점 — 쉬는 자세에서 방향 화살표를 그리면 반대로 읽힌다
        assertEquals(MotionKind.MARK, FloorCycleTracker.motionFor(FloorProfile.CRUNCH, "sit_up")!!.high)
        assertEquals(MotionKind.MARK, FloorCycleTracker.motionFor(FloorProfile.LEG_RAISE, "feet_touch")!!.high)
    }

    @Test fun plankStopsAndGazeHaveMotionsExceptTheCameraOnes() {
        for (r in PlankHoldClock.REASONS) {
            val cue = PlankHoldClock.cueFor(r)
            if (cue != null && r != PlankHoldClock.OUT_OF_VIEW) assertNotNull("$r", PlankHoldClock.motionFor(r))
        }
        assertNull("화면 밖은 자세가 아니다", PlankHoldClock.motionFor(PlankHoldClock.OUT_OF_VIEW))
        assertEquals(MotionKind.DOWN, PlankGazeVoice.motionFor(1).high); assertEquals(MotionKind.UP, PlankGazeVoice.motionFor(-1).high)
        assertEquals(MotionAnchor.HEAD, FloorGazeVoice.motionFor(FloorProfile.CRUNCH).anchor); assertEquals("가야 할 곳 — 무릎", MotionAnchor.KNEES, FloorGazeVoice.motionFor(FloorProfile.CRUNCH).target)
        assertEquals(MotionKind.UP, FloorGazeVoice.motionFor(FloorProfile.LEG_RAISE).high)
        assertEquals(MotionAnchor.WRISTS, PlankGazeVoice.motionFor(0).target)
    }

    @Test fun pairedArmCuesDrawTheNearElbowAndNotTheUnseenArm() {
        assertEquals(MotionPick.NEAR, PairedArmCues.motionFor("upperarm_vert_L")!!.pick)
        assertEquals(MotionKind.MARK, PairedArmCues.motionFor(Arm2d.TORSO_TILT)!!.high)
        assertNotNull(PairedArmCues.cueFor("arm_unseen_R")); assertNull("촬영 안내는 그림이 없다", PairedArmCues.motionFor("arm_unseen_R"))
    }

    @Test fun motionSpecResolvesSidesViewsAndDegradesLateralKindsOnTheSide() {
        val m = LegCycleTracker.motionFor(LegProfile.SIDE_CRUNCH, "no_abduct")!!
        assertEquals(setOf(25), MotionSpec(m, MotionOrigin.REASON, side = StepSide.LEFT).anchors)
        assertEquals(setOf(26), MotionSpec(m, MotionOrigin.REASON, side = StepSide.RIGHT).anchors)
        assertEquals(setOf(25, 26), MotionSpec(m, MotionOrigin.REASON).anchors)
        assertEquals(MotionKind.MARK, MotionSpec(m, MotionOrigin.REASON, lateralOk = false).kind)
        assertEquals(MotionKind.AWAY_MIDLINE, MotionSpec(m, MotionOrigin.REASON, lateralOk = MotionSpec.lateralOk("C")).kind)
        assertEquals(false, MotionSpec.lateralOk("SIDE_B"))
        // 런지 깊이의 뒷무릎(SUPPORT) — 앞다리가 왼쪽이면 오른무릎
        val lunge = RepFormSpecs.byExercise.values.flatten().first { it.id.endsWith("앞무릎 깊이") }.motion!!
        assertEquals(setOf(26), MotionSpec(lunge, MotionOrigin.REPFORM, side = StepSide.LEFT).anchors)
        // 컬 카메라 쪽 팔(B = 사용자 오른쪽 앞 → 오른팔꿈치 14)
        val curl = RepFormSpecs.byExercise.getValue("덤벨 컬").first { it.id.endsWith("팔꿈치 앞 이탈") }.motion!!
        assertEquals(setOf(14), MotionSpec(curl, MotionOrigin.REPFORM, near = FormMotion.nearSide("B")).anchors)
        assertEquals(setOf(13), MotionSpec(curl, MotionOrigin.REPFORM, near = FormMotion.nearSide("D")).anchors)
        // 코는 쪽이 없다
        assertEquals(setOf(0), MotionSpec(FloorGazeVoice.motionFor(FloorProfile.CRUNCH), MotionOrigin.GAZE, chainSide = 1).anchors)
        assertEquals(setOf(26), MotionSpec(FloorGazeVoice.motionFor(FloorProfile.CRUNCH), MotionOrigin.GAZE, chainSide = 1).targets)
    }

    @Test fun everyShipWindowRuleOfACatalogStandingExerciseIsInTheWindowMotionTable() {
        // 규칙 JSON 은 텍스트로 읽는다(org.json 은 유닛 테스트에서 스텁). 자산이 클래스패스에 없으면 건너뛴다
        val text = javaClass.classLoader?.getResourceAsStream("rules_mp_v0.json")?.bufferedReader()?.readText() ?: return
        val standing = ExerciseProfiles.all.filter { !it.floor }.mapNotNull { it.referenceExercise }.toSet()
        val superseded = RepFormSpecs.supersedes.keys
        val idRe = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
        val statusRe = Regex("\"status\"\\s*:\\s*\"([^\"]+)\"")
        val missing = ArrayList<String>()
        for (m in idRe.findAll(text)) {
            val id = m.groupValues[1]
            val status = statusRe.find(text, m.range.last)?.groupValues?.get(1) ?: continue
            if (status != "ship") continue
            val ex = id.substringBefore("|")
            if (ex !in standing || id in superseded) continue
            if (WindowMotions.table[id] == null) missing += id
        }
        assertTrue("창 규칙 화살표 표 누락: $missing", missing.isEmpty())
    }
}
