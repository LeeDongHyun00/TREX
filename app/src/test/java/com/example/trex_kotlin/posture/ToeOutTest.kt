package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec §62 발끝 방향 피처: toe_out_* = 뒤꿈치→발끝을 수평면에 눕혀 몸 앞 방향과 이루는 각(°), 바깥쪽 +.
 * AIHub 스쿼트 조건에 없는 항목 — 2026-09-25 실기기 세트에서 발끝만 벌린 반복을 기존 피처가 구분하지 못해 추가했다.
 * 부호 규약은 knee_out 과 같다(L 은 +x_b 가 바깥, R 은 −x_b 가 바깥) — 좌우가 같은 각도로 벌어지면 같은 값이 나와야 한다.
 */
class ToeOutTest {

    /** HeelLiftTest 와 같은 몸통 배치: 왼 엉덩이 x=−15, 오른 엉덩이 x=+15 → 몸 앞 방향은 world −z, 왼쪽 바깥은 −x, 오른쪽 바깥은 +x. */
    private fun frame(lHeel: Vec3?, lFoot: Vec3?, rHeel: Vec3? = null, rFoot: Vec3? = null): PoseFrame = PoseFrame(
        mapOf(
            Joints.L_SHOULDER to Vec3(-20f, 150f, 0f), Joints.R_SHOULDER to Vec3(20f, 150f, 0f),
            Joints.L_HIP to Vec3(-15f, 100f, 0f), Joints.R_HIP to Vec3(15f, 100f, 0f),
            Joints.L_HEEL to lHeel, Joints.L_FOOT to lFoot, Joints.R_HEEL to rHeel, Joints.R_FOOT to rFoot,
        ),
        Vec3(0f, 1f, 0f),
    )

    @Test
    fun straightFootIsZeroAndOutwardIsPositiveOnBothSides() {
        val straight = frame(lHeel = Vec3(-15f, 0f, 0f), lFoot = Vec3(-15f, 0f, -25f), rHeel = Vec3(15f, 0f, 0f), rFoot = Vec3(15f, 0f, -25f)).features()
        assertEquals(0f, straight.getValue("toe_out_L"), 0.5f)
        assertEquals(0f, straight.getValue("toe_out_R"), 0.5f)
        // 양발 30° 바깥: L 은 −x 쪽으로, R 은 +x 쪽으로 — 같은 값이어야 한다(미러 불변)
        val out = frame(
            lHeel = Vec3(-15f, 0f, 0f), lFoot = Vec3(-15f - 12.5f, 0f, -21.65f),
            rHeel = Vec3(15f, 0f, 0f), rFoot = Vec3(15f + 12.5f, 0f, -21.65f),
        ).features()
        assertEquals(30f, out.getValue("toe_out_L"), 0.5f)
        assertEquals(30f, out.getValue("toe_out_R"), 0.5f)
        assertEquals(30f, out.getValue("toe_out_mean"), 0.5f)
        assertEquals(30f, out.getValue("toe_out_maxside"), 0.5f)
        assertEquals(0f, out.getValue("toe_out_asym"), 0.5f)
        // 안쪽(내반)은 음수
        val inward = frame(lHeel = Vec3(-15f, 0f, 0f), lFoot = Vec3(-15f + 12.5f, 0f, -21.65f)).features()
        assertEquals(-30f, inward.getValue("toe_out_L"), 0.5f)
    }

    @Test
    fun maxsideUsesTheVisibleFootAndMeanNeedsBoth() {
        // 한 발만 보이면 maxside 는 그 발, mean·asym 은 없다(한쪽 값을 평균이라 부르지 않는다)
        val one = frame(lHeel = Vec3(-15f, 0f, 0f), lFoot = Vec3(-15f - 12.5f, 0f, -21.65f)).features()
        assertEquals(30f, one.getValue("toe_out_maxside"), 0.5f)
        assertNull(one["toe_out_mean"]); assertNull(one["toe_out_asym"]); assertNull(one["toe_out_R"])
        // 한 발만 벌어져도 maxside 가 잡는다
        val oneOut = frame(
            lHeel = Vec3(-15f, 0f, 0f), lFoot = Vec3(-15f, 0f, -25f),
            rHeel = Vec3(15f, 0f, 0f), rFoot = Vec3(15f + 17.7f, 0f, -17.7f),
        ).features()
        assertEquals(45f, oneOut.getValue("toe_out_maxside"), 0.5f)
        assertEquals(22.5f, oneOut.getValue("toe_out_mean"), 0.5f)
        // 뒤꿈치 위에 발끝이 겹치면(수평 길이 < 3 cm) 방향을 말할 수 없다 — 피처 없음
        val collapsed = frame(lHeel = Vec3(-15f, 0f, 0f), lFoot = Vec3(-15f, 8f, -1f)).features()
        assertNull(collapsed["toe_out_L"])
        assertTrue(collapsed.containsKey("heel_lift_L"))   // 같은 두 점의 다른 피처는 그대로
    }

    @Test
    fun angleIsMeasuredInTheHorizontalPlaneNotAffectedByHeelLift() {
        // 뒤꿈치가 들려도(수직 성분) 수평 방향각은 그대로 — 발바닥 지면 고정과 결합하지 않는다
        val lifted = frame(lHeel = Vec3(-15f, 10f, 0f), lFoot = Vec3(-15f - 12.5f, 0f, -21.65f)).features()
        assertEquals(30f, lifted.getValue("toe_out_L"), 0.5f)
    }
}
