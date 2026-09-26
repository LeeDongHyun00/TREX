package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec §62 발끝 방향 피처: toe_out_* = 발목→발끝을 수평면에 눕혀 몸 앞 방향과 이루는 각(°), 바깥쪽 +.
 * AIHub 스쿼트 조건에 없는 항목 — 2026-09-25 실기기 세트에서 발끝만 벌린 반복을 기존 피처가 구분하지 못해 추가했다.
 * 발목에서 재는 이유: 정면·바닥에 놓은 폰에서 오른 뒤꿈치가 91% 프레임에서 안 보였다(발목 96%).
 * 부호 규약은 knee_out 과 같다(L 은 +x_b 가 바깥, R 은 −x_b 가 바깥) — 좌우가 같은 각도로 벌어지면 같은 값이 나와야 한다.
 */
class ToeOutTest {

    /** HeelLiftTest 와 같은 몸통 배치: 왼 엉덩이 x=−15, 오른 엉덩이 x=+15 → 몸 앞 방향은 world −z, 왼쪽 바깥은 −x, 오른쪽 바깥은 +x. */
    private fun frame(lAnkle: Vec3?, lFoot: Vec3?, rAnkle: Vec3? = null, rFoot: Vec3? = null): PoseFrame = PoseFrame(
        mapOf(
            Joints.L_SHOULDER to Vec3(-20f, 150f, 0f), Joints.R_SHOULDER to Vec3(20f, 150f, 0f),
            Joints.L_HIP to Vec3(-15f, 100f, 0f), Joints.R_HIP to Vec3(15f, 100f, 0f),
            Joints.L_ANKLE to lAnkle, Joints.L_FOOT to lFoot, Joints.R_ANKLE to rAnkle, Joints.R_FOOT to rFoot,
        ),
        Vec3(0f, 1f, 0f),
    )

    @Test
    fun straightFootIsZeroAndOutwardIsPositiveOnBothSides() {
        val straight = frame(lAnkle = Vec3(-15f, 8f, 0f), lFoot = Vec3(-15f, 0f, -20f), rAnkle = Vec3(15f, 8f, 0f), rFoot = Vec3(15f, 0f, -20f)).features()
        assertEquals(0f, straight.getValue("toe_out_L"), 0.5f)
        assertEquals(0f, straight.getValue("toe_out_R"), 0.5f)
        // 양발 30° 바깥: L 은 −x 쪽으로, R 은 +x 쪽으로 — 같은 값이어야 한다(미러 불변). 발목이 발끝보다 8 cm 위여도 수평각은 같다
        val out = frame(
            lAnkle = Vec3(-15f, 8f, 0f), lFoot = Vec3(-15f - 10f, 0f, -17.32f),
            rAnkle = Vec3(15f, 8f, 0f), rFoot = Vec3(15f + 10f, 0f, -17.32f),
        ).features()
        assertEquals(30f, out.getValue("toe_out_L"), 0.5f)
        assertEquals(30f, out.getValue("toe_out_R"), 0.5f)
        assertEquals(30f, out.getValue("toe_out_mean"), 0.5f)
        assertEquals(30f, out.getValue("toe_out_maxside"), 0.5f)
        assertEquals(0f, out.getValue("toe_out_asym"), 0.5f)
        // 안쪽(내반)은 음수
        val inward = frame(lAnkle = Vec3(-15f, 8f, 0f), lFoot = Vec3(-15f + 10f, 0f, -17.32f)).features()
        assertEquals(-30f, inward.getValue("toe_out_L"), 0.5f)
    }

    @Test
    fun maxsideUsesTheVisibleFootAndMeanNeedsBoth() {
        // 한 발만 보이면 maxside 는 그 발, mean·asym 은 없다(한쪽 값을 평균이라 부르지 않는다)
        val one = frame(lAnkle = Vec3(-15f, 8f, 0f), lFoot = Vec3(-15f - 10f, 0f, -17.32f)).features()
        assertEquals(30f, one.getValue("toe_out_maxside"), 0.5f)
        assertNull(one["toe_out_mean"]); assertNull(one["toe_out_asym"]); assertNull(one["toe_out_R"])
        // 한 발만 벌어져도 maxside 가 잡는다
        val oneOut = frame(
            lAnkle = Vec3(-15f, 8f, 0f), lFoot = Vec3(-15f, 0f, -20f),
            rAnkle = Vec3(15f, 8f, 0f), rFoot = Vec3(15f + 14.14f, 0f, -14.14f),
        ).features()
        assertEquals(45f, oneOut.getValue("toe_out_maxside"), 0.5f)
        assertEquals(22.5f, oneOut.getValue("toe_out_mean"), 0.5f)
        // 발끝이 발목 바로 아래(수평 길이 < 3 cm)면 방향을 말할 수 없다 — 피처 없음. 같은 두 점의 발목각(ankle_L)은 무릎이 없어 어차피 없다
        val collapsed = frame(lAnkle = Vec3(-15f, 8f, 0f), lFoot = Vec3(-15f, 0f, -1f)).features()
        assertNull(collapsed["toe_out_L"])
        // 뒤꿈치가 없어도 계산된다 — 이 테스트의 프레임엔 뒤꿈치가 아예 없다
        assertTrue(one.containsKey("toe_out_L"))
    }
}
