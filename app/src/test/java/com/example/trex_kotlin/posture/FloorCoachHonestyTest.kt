package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 바닥 규칙 감사(FLOOR_RULE_AUDIT) 반영 검증:
 *  B. 플랭크 '몸통과 엉덩이의 정렬' — 위반이 솟음 69%/처짐 31% 로 갈리므로 부호 있는
 *     hip_dev_ankle 로 문구를 가른다 (처방이 정반대: 내려라/올려라).
 *  A/C/D. 조건명과 판정 근거가 다른 규칙은 measurementNote 로 근거를 정직하게 밝힌다.
 */
class FloorCoachHonestyTest {

    private fun plankRule() = PostureRule(
        id = "floor|플랭크|몸통과 엉덩이의 정렬 유지", exercise = "플랭크", condition = "몸통과 엉덩이의 정렬 유지",
        subtype = null, status = RuleStatus.BETA, reason = null,
        feature = "trunk_ankle_ang__mean", baseFeature = "trunk_ankle_ang", stat = "mean",
        family = "floor2d", op = "<", threshold = 133.3f, view = "B", viewDesc = "측면", cvAuc = 0.79f,
        cvBalacc = 0.7f, sampleN = 200, mirrorSafe = true, cautions = emptyList(),
    )

    private fun rule(ex: String, cond: String, feature: String = "head_trunk_ang__std") = PostureRule(
        id = "floor|$ex|$cond", exercise = ex, condition = cond, subtype = null,
        status = RuleStatus.BETA, reason = null, feature = feature,
        baseFeature = feature.substringBeforeLast("__"), stat = feature.substringAfterLast("__"),
        family = "floor2d", op = ">", threshold = 1f, view = "B", viewDesc = "측면", cvAuc = 0.8f,
        cvBalacc = 0.7f, sampleN = 200, mirrorSafe = true, cautions = emptyList(),
    )

    private fun coach(vararg frames: Map<String, Float>): LiveCoach {
        val c = LiveCoach(
            PostureRuleSet("floor_v0.2", "t", listOf(plankRule())), "플랭크",
            windowFrames = 8, minFrames = 8, persistence = 1, ruleCooldownMs = 0, globalGapMs = 0,
        )
        frames.forEach { c.onFrame(it) }
        return c
    }

    @Test
    fun plankViolationWithHipHighSaysLower() {
        // trunk_ankle_ang 120 < 133.3 = 위반, hip_dev_ankle 양수 = 화면 위 = 엉덩이 솟음
        val frames = Array(10) { mapOf("trunk_ankle_ang" to 120f, "hip_dev_ankle" to 0.15f) }
        val ev = coach(*frames).evaluate(1_000L)
        assertNotNull(ev)
        assertTrue(ev!!.message, ev.message.contains("솟아"))
        assertTrue(ev.message, ev.message.contains("내려"))
    }

    @Test
    fun plankViolationWithHipLowSaysRaise() {
        val frames = Array(10) { mapOf("trunk_ankle_ang" to 120f, "hip_dev_ankle" to -0.15f) }
        val ev = coach(*frames).evaluate(1_000L)
        assertNotNull(ev)
        assertTrue(ev!!.message, ev.message.contains("처져"))
        assertTrue(ev.message, ev.message.contains("올려"))
    }

    @Test
    fun plankWithoutHipDevFallsBackToMergedCue() {
        // 발목 가림 등으로 hip_dev_ankle 이 없으면 기존 병합 문구로 폴백
        val frames = Array(10) { mapOf("trunk_ankle_ang" to 120f) }
        val ev = coach(*frames).evaluate(1_000L)
        assertNotNull(ev)
        assertTrue(ev!!.message, ev.message.contains("처지거나 솟아"))
    }

    @Test
    fun measurementNotesDiscloseWhatIsActuallyMeasured() {
        // 감사 A: 크런치 견갑골 = 머리 높이 근사
        val a = CoachCues.measurementNote(rule("크런치", "견갑골이 지면으로부터 충분히 올라옴", "head_ground__max"))
        assertNotNull(a)
        assertTrue(a!!, a.contains("머리"))
        // 감사 C: 힙쓰러스트 고개 = 흔들림(std)
        val c = CoachCues.measurementNote(rule("힙쓰러스트", "고개 들지 않기"))
        assertNotNull(c)
        assertTrue(c!!, c.contains("흔들림"))
        // 감사 D: Y 경추 = 몸통-골반 라인
        val d = CoachCues.measurementNote(rule("Y - Exercise", "경추 중립 또는 후인(retraction) 유지", "hip_dev_knee__min"))
        assertNotNull(d)
        assertTrue(d!!, d.contains("몸통"))
        // 근거가 일치하는 규칙에는 주석이 없다
        assertNull(CoachCues.measurementNote(rule("푸시업", "고개 젖힘/숙임 여부", "head_trunk_ang__mean")))
    }

    @Test
    fun hipThrustHeadCueSpeaksAboutStability() {
        // 감사 C: 판정 근거(흔들림)와 문구가 일치해야 한다 — '들고 있다'는 단정은 금지
        val cue = CoachCues.cueFor(rule("힙쓰러스트", "고개 들지 않기"))
        assertTrue(cue.habit, cue.habit.contains("흔들"))
        assertEquals("고개", cue.bodyPart)
    }
}
