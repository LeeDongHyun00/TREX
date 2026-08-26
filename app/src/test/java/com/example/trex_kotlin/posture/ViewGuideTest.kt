package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 촬영 뷰 코드(A~E) → 사람 말 (spec §26). 근거: research/aihub_fitness/view_geometry.py */
class ViewGuideTest {

    private fun rule(view: String, mirrorSafe: Boolean = true) = PostureRule(
        id = "x|$view", exercise = "x", condition = "c", subtype = null, status = RuleStatus.SHIP, reason = null,
        feature = "f__mean", baseFeature = "f", stat = "mean", family = "x", op = "<", threshold = 0f,
        view = view, viewDesc = "", cvAuc = 0.9f, cvBalacc = 0.8f, sampleN = 60,
        mirrorSafe = mirrorSafe, cautions = emptyList(),
    )

    @Test
    fun standingCodesMapToMeasuredGeometry() {
        // front_ratio C=0.920 & sh_ratio 0.650(최대) → 정면 / B=0.833, D=0.919 → 전방 사선 / A=0.085, E=0.170 → 후방
        assertEquals("정면", ViewGuide.shortName("C", floor = false))
        assertEquals("앞 비스듬히", ViewGuide.shortName("B", floor = false))
        assertEquals("앞 비스듬히", ViewGuide.shortName("D", floor = false))
        assertEquals("뒤 비스듬히", ViewGuide.shortName("A", floor = false))
        assertEquals("뒤 비스듬히", ViewGuide.shortName("E", floor = false))
    }

    @Test
    fun floorCodesInvertRelativeToStanding() {
        // body_sh: C=15.77(측면), A=2.96(몸 축), B/D/E=4.2~5.7(사선)
        assertEquals("측면", ViewGuide.shortName("C", floor = true))
        assertEquals("머리·발 쪽", ViewGuide.shortName("A", floor = true))
        assertEquals("측면 비스듬히", ViewGuide.shortName("B", floor = true))
        assertEquals("측면 비스듬히", ViewGuide.shortName("E", floor = true))
        // 회귀 방지: 같은 코드 C 가 서서는 '정면', 바닥은 '측면' — 이걸 섞으면 정반대로 안내된다
        assertNotEquals(ViewGuide.shortName("C", floor = false), ViewGuide.shortName("C", floor = true))
    }

    @Test
    fun placementTellsWhereToPutThePhone() {
        val floorC = ViewGuide.placement("C", floor = true)
        assertTrue(floorC, floorC.contains("바닥"))
        assertTrue(floorC, floorC.contains("몸 옆"))
        assertFalse("바닥인데 허리 높이로 안내하면 안 됨", floorC.contains("허리 높이"))

        val standC = ViewGuide.placement("C", floor = false)
        assertTrue(standC, standC.contains("허리 높이"))
        assertTrue(standC, standC.contains("정면"))
        assertFalse("서 있는데 바닥 배치로 안내하면 안 됨", standC.contains("바닥"))

        // 미러 안전하면 좌우를 강요하지 않는다
        assertTrue(ViewGuide.placement("B", floor = false, mirrorSafe = true).contains("좌우 어느 쪽이든"))
        assertTrue(ViewGuide.placement("B", floor = false, mirrorSafe = false).contains("왼쪽"))
        assertTrue(ViewGuide.placement("D", floor = false, mirrorSafe = false).contains("오른쪽"))
    }

    @Test
    fun summaryCountsByHumanNameAndDominantPicksMost() {
        val rules = listOf(rule("C"), rule("C"), rule("B"), rule("A"))
        val s = ViewGuide.summary(rules, floor = false)
        assertTrue(s, s.startsWith("정면 2개"))
        assertTrue(s, s.contains("앞 비스듬히 1개") && s.contains("뒤 비스듬히 1개"))
        assertFalse("코드가 노출되면 안 됨", Regex("\\b[A-E]\\b").containsMatchIn(s))
        assertEquals("C", ViewGuide.dominantView(rules))
        assertNull(ViewGuide.dominantView(listOf(rule(""))))
        assertEquals("", ViewGuide.summary(listOf(rule("")), floor = false))
    }

    @Test
    fun shippedRulesAllGetHumanNames() {
        // 실제 자산의 모든 뷰 코드가 매핑되는지 (빈 문자열 포함) — 코드가 화면에 새는 걸 막는다
        for (v in listOf("A", "B", "C", "D", "E", "")) {
            for (floor in listOf(true, false)) {
                val n = ViewGuide.shortName(v, floor)
                assertTrue("빈 이름: v=$v floor=$floor", n.isNotBlank())
                assertFalse("코드 노출: $n", n.length == 1 && n[0] in 'A'..'E')
                assertTrue(ViewGuide.placement(v, floor).isNotBlank())
            }
        }
    }
}
