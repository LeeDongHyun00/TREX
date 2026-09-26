package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * up 축 부호 규약과 자가검증 테스트.
 * 배경: 실기기 로그(2026-08-23)에서 up 이 180° 뒤집혀 기록됨 — Android TYPE_GRAVITY 는 반작용(위) 벡터라
 * 아래 방향으로 가정한 초기 구현이 틀렸다. 여기서 (1) 센서→아래 변환 (2) 관절 배치 자가검증을 고정한다.
 */
class PostureOrientationTest {

    private fun assertVec(msg: String, e: Vec3, a: Vec3?) {
        assertNotNull("$msg: null", a)
        assertEquals("$msg x", e.x, a!!.x, 1e-4f)
        assertEquals("$msg y", e.y, a.y, 1e-4f)
        assertEquals("$msg z", e.z, a.z, 1e-4f)
    }

    @Test
    fun sensorReactionVectorBecomesDownwardGravity() {
        // 화면 위로 평평히 놓음: 센서 (0,0,+9.81) → 아래 방향 (0,0,-9.81)
        assertVec("flat", Vec3(0f, 0f, -9.81f), sensorGravityToDown(Vec3(0f, 0f, 9.81f)))
        // 세로로 세움: 센서 (0,+9.81,0) → 아래 (0,-9.81,0)
        assertVec("upright", Vec3(0f, -9.81f, 0f), sensorGravityToDown(Vec3(0f, 9.81f, 0f)))
    }

    @Test
    fun uprightPhoneGivesScreenUp_afterSensorFix() {
        // 실기기 시나리오: 세로로 세운 폰, 전면 카메라, 센서 값 (0,+9.81,0)
        val down = sensorGravityToDown(Vec3(0f, 9.81f, 0f))
        val up = gravityUpInWorld(down, android.view.Surface.ROTATION_0, true)
        assertVec("upright/front", Vec3(0f, 1f, 0f), up)
        assertEquals(0f, tiltFromScreenUpDegrees(up!!), 1e-3f)
        // 수정 전(센서 값을 그대로 아래로 간주)이라면 tilt 180° 였다 — 회귀 방지
        val wrong = gravityUpInWorld(Vec3(0f, 9.81f, 0f), android.view.Surface.ROTATION_0, true)
        assertEquals(180f, tiltFromScreenUpDegrees(wrong!!), 1e-3f)
    }

    @Test
    fun leaningBackPhoneGivesTiltMatchingFieldLog() {
        // 실기기 로그: 스쿼트 세트 tilt 110.7° (뒤집힌 값) → 실제 폰은 약 69° 뒤로 젖혀짐.
        // 69° 젖힌 폰(화면이 위를 향함)의 센서 값 = (0, cos69°, sin69°)·9.81
        val deg = 69.3
        val s = Vec3(0f, (Math.cos(Math.toRadians(deg)) * 9.81).toFloat(), (Math.sin(Math.toRadians(deg)) * 9.81).toFloat())
        val up = gravityUpInWorld(sensorGravityToDown(s), android.view.Surface.ROTATION_0, true)!!
        assertEquals(deg.toFloat(), tiltFromScreenUpDegrees(up), 0.5f)
        val wrong = gravityUpInWorld(s, android.view.Surface.ROTATION_0, true)!!
        assertEquals(180f - deg.toFloat(), tiltFromScreenUpDegrees(wrong), 0.5f)
    }

    private fun standingJoints(): Map<String, Vec3?> = mapOf(
        Joints.L_EAR to Vec3(5f, 70f, 0f), Joints.R_EAR to Vec3(-5f, 70f, 0f),
        Joints.L_SHOULDER to Vec3(18f, 50f, 0f), Joints.R_SHOULDER to Vec3(-18f, 50f, 0f),
        Joints.L_HIP to Vec3(10f, 0f, 0f), Joints.R_HIP to Vec3(-10f, 0f, 0f),
        Joints.L_KNEE to Vec3(10f, -45f, 3f), Joints.R_KNEE to Vec3(-10f, -45f, 3f),
        Joints.L_ANKLE to Vec3(10f, -88f, 0f), Joints.R_ANKLE to Vec3(-10f, -88f, 0f),
    )

    @Test
    fun sanityDetectsInvertedUpOnStandingPose() {
        val j = standingJoints()
        val ok = checkUpSanity(j, Vec3(0f, 1f, 0f))
        assertTrue(ok.verified); assertFalse(ok.flipped)
        assertEquals(88f, ok.hipAboveAnkleCm!!, 1e-3f)
        assertEquals(20f, ok.earAboveShoulderCm!!, 1e-3f)

        val inv = checkUpSanity(j, Vec3(0f, -1f, 0f))
        assertTrue(inv.verified); assertTrue(inv.flipped)
        assertEquals(-88f, inv.hipAboveAnkleCm!!, 1e-3f)
    }

    @Test
    fun sanityFallsBackToEarsWhenLegsMissing_andAbstainsWhenLying() {
        val noLegs = standingJoints().filterKeys { it !in setOf(Joints.L_HIP, Joints.R_HIP, Joints.L_ANKLE, Joints.R_ANKLE, Joints.L_KNEE, Joints.R_KNEE) }
        // 다리 없음: 귀-어깨 로 판단 (+20 → 정상, 뒤집힌 up → 반전)
        assertFalse(checkUpSanity(noLegs, Vec3(0f, 1f, 0f)).flipped)
        assertTrue(checkUpSanity(noLegs, Vec3(0f, -1f, 0f)).flipped)
        // 누운 자세: 골반-발목 높이차가 작음 → 미검증, 보정 없음
        val lying = standingJoints().mapValues { (_, v) -> v?.let { Vec3(it.x, it.z, -it.y) } }  // 몸이 z 축(수평)으로 누움
        val s = checkUpSanity(lying, Vec3(0f, 1f, 0f))
        assertFalse(s.verified); assertFalse(s.flipped)
        // up 이 영벡터면 미검증
        assertFalse(checkUpSanity(standingJoints(), Vec3(0f, 0f, 0f)).verified)
    }

    @Test
    fun poseFrameFeaturesRecoverAfterFlip() {
        // 뒤집힌 up 으로 계산한 높이 피처는 부호가 반대여야 하고, 자가검증 보정(−up) 후 원래 값으로 돌아와야 한다
        val j = standingJoints() + mapOf(
            Joints.NOSE to Vec3(0f, 72f, 8f), Joints.L_EYE to Vec3(3f, 74f, 7f), Joints.R_EYE to Vec3(-3f, 74f, 7f),
            Joints.L_ELBOW to Vec3(25f, 25f, 0f), Joints.R_ELBOW to Vec3(-25f, 25f, 0f),
            Joints.L_WRIST to Vec3(28f, 2f, 5f), Joints.R_WRIST to Vec3(-28f, 2f, 5f),
            Joints.L_PALM to Vec3(29f, -6f, 6f), Joints.R_PALM to Vec3(-29f, -6f, 6f),
            Joints.L_FOOT to Vec3(11f, -92f, 15f), Joints.R_FOOT to Vec3(-11f, -92f, 15f),
        )
        val good = PoseFrame(j, Vec3(0f, 1f, 0f)).features()
        val bad = PoseFrame(j, Vec3(0f, -1f, 0f)).features()
        assertTrue(good.getValue("hip_height_rel") > 0.9f)
        assertTrue(bad.getValue("hip_height_rel") < -0.9f)
        assertEquals(good.getValue("ear_shoulder_gap"), -bad.getValue("ear_shoulder_gap"), 1e-4f)
        val s = checkUpSanity(j, Vec3(0f, -1f, 0f))
        assertTrue(s.flipped)
        val fixed = PoseFrame(j, Vec3(0f, -1f, 0f) * -1f).features()
        assertEquals(good.getValue("hip_height_rel"), fixed.getValue("hip_height_rel"), 1e-5f)
        assertEquals(good.getValue("torso_incl"), fixed.getValue("torso_incl"), 1e-4f)
    }
}
