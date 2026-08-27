package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 촬영 커버리지 진단 (spec §25b): 규칙이 요구하는 부위를 역산하고,
 * 못 잡는 이유(프레임 밖 vs 가림)에 따라 다른 해결책을 안내하는지.
 */
class FloorCoverageTest {

    private fun rule(condition: String, baseFeature: String) = PostureRule(
        id = "floor|x|$condition", exercise = "푸시업", condition = condition, subtype = null,
        status = RuleStatus.BETA, reason = null, feature = "${baseFeature}__mean", baseFeature = baseFeature,
        stat = "mean", family = "floor2d", op = ">", threshold = 0f, view = "C", viewDesc = "",
        cvAuc = 0.8f, cvBalacc = 0.7f, sampleN = 100, mirrorSafe = true, cautions = emptyList(),
    )

    // 실제 rules_floor_v0.1 의 푸시업 규칙 3개 — 어느 것도 발목을 쓰지 않는다
    private val pushupRules = listOf(
        rule("고개 젖힘/숙임 여부", "head_trunk_ang"),
        rule("가슴의 충분한 이동", "wrist_shoulder_d"),
        rule("손의 위치 가슴 중앙 여부", "shoulder_dev"),
    )
    private val plankRules = listOf(rule("몸통과 엉덩이의 정렬 유지", "trunk_ankle_ang"))

    /** 전신이 화면 중앙에 잘 보이는 좌표 */
    private fun goodXy(): FloatArray = FloatArray(MP_LANDMARK_COUNT * 2) { 0.5f }

    private fun visAll(v: Float = 0.9f) = FloatArray(MP_LANDMARK_COUNT) { v }

    @Test
    fun requiredPartsAreDerivedFromRulesNotHardcoded() {
        // 푸시업 규칙은 발목을 요구하지 않는다 — 이게 §25a 에서 85% 프레임을 버린 원인이었다
        val pushup = FloorCoverage.requiredParts(pushupRules)
        assertFalse("푸시업 규칙은 발목을 쓰지 않는다", BodyPart.ANKLE in pushup)
        assertTrue(BodyPart.HEAD in pushup)
        assertTrue(BodyPart.SHOULDER in pushup)
        assertTrue(BodyPart.HIP in pushup)
        assertTrue(BodyPart.WRIST in pushup)

        // 플랭크 규칙(trunk_ankle_ang)은 실제로 발목이 필요하다
        val plank = FloorCoverage.requiredParts(plankRules)
        assertTrue("플랭크 규칙은 발목이 필요", BodyPart.ANKLE in plank)
    }

    @Test
    fun everyFloorFeatureHasPartMapping() {
        // PostureFloor 가 내는 피처와 매핑 테이블이 어긋나면 진단이 조용히 비게 된다
        val produced = FloorFeatureExtractor().compute(goodXy(), null, 1000, 800).keys
        val unmapped = produced.filter { it !in FLOOR_FEATURE_PARTS }
        assertTrue("매핑 누락 피처: $unmapped", unmapped.isEmpty())
    }

    @Test
    fun visibleBodyReportsOk() {
        val rep = FloorCoverage.analyze(goodXy(), visAll(), pushupRules)
        assertTrue(rep.ok)
        assertTrue(rep.missing.isEmpty())
    }

    @Test
    fun ankleMissingDoesNotBlockPushupButBlocksPlank() {
        val vis = visAll().also { it[27] = 0.05f; it[28] = 0.05f }
        assertTrue("발목이 없어도 푸시업은 판정 가능해야 한다", FloorCoverage.analyze(goodXy(), vis, pushupRules).ok)

        val plank = FloorCoverage.analyze(goodXy(), vis, plankRules)
        assertFalse(plank.ok)
        assertEquals(listOf(BodyPart.ANKLE), plank.missing.map { it.part })
        assertTrue(plank.message.contains("발목"))
        assertTrue("막힌 규칙이 명시돼야 함", "몸통과 엉덩이의 정렬 유지" in plank.blocked)
    }

    @Test
    fun outOfFrameSuggestsMovingCameraWithDirection() {
        // 발목이 화면 오른쪽 밖으로 잘림 (x > 1)
        val xy = goodXy()
        for (i in intArrayOf(27, 28)) xy[i * 2] = 1.35f
        val vis = visAll().also { it[27] = 0.05f; it[28] = 0.05f }
        val rep = FloorCoverage.analyze(xy, vis, plankRules)
        assertFalse(rep.ok)
        assertEquals(OutDirection.RIGHT, rep.missing.first().outDirection)
        assertTrue(rep.fix, rep.fix.contains("오른쪽으로 벗어났"))
        assertTrue(rep.fix, rep.fix.contains("폰을 오른쪽으로") || rep.fix.contains("왼쪽으로 이동"))
    }

    @Test
    fun frontCameraMirrorsTheDirection() {
        val xy = goodXy()
        for (i in intArrayOf(27, 28)) xy[i * 2] = 1.35f      // 이미지 기준 오른쪽 밖
        val vis = visAll().also { it[27] = 0.05f; it[28] = 0.05f }
        val mirrored = FloorCoverage.analyze(xy, vis, plankRules, mirror = true)
        // 전면 카메라는 좌우 반전으로 보여주므로 사용자 화면에서는 왼쪽이다
        assertEquals(OutDirection.LEFT, mirrored.missing.first().outDirection)
    }

    @Test
    fun occludedInsideFrameSuggestsAngleChangeNotDistance() {
        // 좌표는 화면 안인데 가시성만 낮음 = 몸에 가려진 것
        val vis = visAll().also { it[27] = 0.05f; it[28] = 0.05f }
        val rep = FloorCoverage.analyze(goodXy(), vis, plankRules)
        assertFalse(rep.ok)
        assertEquals(null, rep.missing.first().outDirection)
        assertTrue(rep.fix, rep.fix.contains("가려졌"))
        assertTrue(rep.fix, rep.fix.contains("몸 옆"))
        assertFalse("가림인데 거리 조정을 권하면 안 된다", rep.fix.contains("멀리"))
    }

    @Test
    fun wholeBodyOutOfFrameSuggestsMovingCameraBack() {
        // 머리는 위로, 발목은 아래로, 손목은 옆으로 — 전신이 안 들어옴
        val xy = goodXy()
        for (i in intArrayOf(0, 7, 8)) xy[i * 2 + 1] = -0.4f
        for (i in intArrayOf(27, 28)) xy[i * 2 + 1] = 1.4f
        for (i in intArrayOf(15, 16)) xy[i * 2] = -0.3f
        val vis = visAll().also {
            for (i in intArrayOf(0, 7, 8, 15, 16, 27, 28)) it[i] = 0.05f
        }
        val rules = pushupRules + plankRules
        val rep = FloorCoverage.analyze(xy, vis, rules)
        assertFalse(rep.ok)
        assertTrue(rep.fix, rep.fix.contains("멀리"))
        assertTrue("여러 규칙이 막혀야 함", rep.blocked.size >= 2)
    }

    @Test
    fun unknownFeaturesAreIgnored() {
        // 서서 하는 종목 규칙이 섞여 들어와도 바닥 진단이 오작동하지 않는다
        val standing = listOf(rule("발과 무릎의 방향 일치", "knee_out_mean"))
        assertTrue(FloorCoverage.requiredParts(standing).isEmpty())
        assertTrue(FloorCoverage.analyze(goodXy(), visAll(0.01f), standing).ok)
    }
}
