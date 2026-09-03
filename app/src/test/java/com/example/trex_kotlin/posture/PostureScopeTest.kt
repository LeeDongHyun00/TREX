package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 평가 범위 — "이 앱이 무엇을 보고 무엇을 못 보는가".
 * 데드리프트처럼 '척추의 중립'이 전부 exclude 인 종목에서 ship 2규칙만 통과했다고 "깨끗했어요" 라고 말하면
 * 거짓 안심이다. 조건 등급을 부위명으로 압축해 시작 안내·카드 부제에 그대로 쓴다.
 */
class PostureScopeTest {

    private fun rule(
        exercise: String,
        condition: String,
        status: RuleStatus,
        feature: String = "knee_out_mean__mean",
        subtype: String? = null,
        idSuffix: String = "",
    ): PostureRule {
        val i = feature.lastIndexOf("__")
        return PostureRule(
            id = "$exercise|$condition|$feature$idSuffix", exercise = exercise, condition = condition,
            subtype = subtype, status = status, reason = null, feature = feature,
            baseFeature = feature.substring(0, i), stat = feature.substring(i + 2), family = "x",
            op = "<", threshold = 0.01f, view = "C", viewDesc = "", cvAuc = 0.9f, cvBalacc = 0.8f,
            sampleN = 60, mirrorSafe = true, cautions = emptyList(),
        )
    }

    /** 데드리프트 축소판: ship 2 · beta 1 · exclude 2. */
    private val deadlift = PostureRuleSet(
        "t", "d",
        listOf(
            rule(DL, "발과 무릎의 방향 일치", RuleStatus.SHIP),                                  // 무릎
            rule(DL, "상체 과도한 젖힘 없음", RuleStatus.SHIP, "torso_pitch__min"),               // 상체
            rule(DL, "손목의 중립", RuleStatus.BETA, "wrist_dev__mean"),                          // 손목
            rule(DL, "척추의 중립", RuleStatus.EXCLUDE, "spine_flex__mean", subtype = "flexion"),  // 등·허리
            rule(DL, "고개 정면 유지", RuleStatus.EXCLUDE, "head_yaw__std"),                      // 시선
        ),
    )

    @Test
    fun conditionsAreGradedByBestStatusAndCompressedToBodyParts() {
        val s = PostureScope.of(deadlift, DL)
        assertEquals(listOf("무릎", "상체"), s.watched)
        assertEquals(listOf("손목"), s.provisional)
        assertEquals(listOf("등·허리", "시선"), s.blind)     // 규칙 JSON 등장 순서 유지
        assertTrue(s.hasAnyJudgement)
        assertFalse(s.provisionalOnly)
    }

    @Test
    fun startLineNamesWhatIsWatchedAndWhatIsBlind() {
        val s = PostureScope.of(deadlift, DL)
        assertEquals("무릎·상체를 봐요. 등·허리는 못 봐요.", s.startLine)
        assertEquals("평가 2 · 검증 중 1 · 못 봄 2", s.cardLine)
        assertEquals(3, s.introLines.size)
        assertEquals(s.startLine, s.introLines[1])
        assertTrue(s.introLines[0].contains("영상은 저장하지 않아요"))
        assertTrue(s.introLines[2].contains("아니었어요"))
    }

    @Test
    fun shipInsideAConditionOutranksExclude() {
        // 같은 조건에 exclude 규칙과 ship 규칙이 섞이면 그 조건은 '판정한다'
        val rs = PostureRuleSet(
            "t", "d",
            listOf(
                rule(DL, "발바닥 지면 고정", RuleStatus.EXCLUDE, "heel_lift__mean", idSuffix = "#1"),
                rule(DL, "발바닥 지면 고정", RuleStatus.SHIP, "heel_lift__max", idSuffix = "#2"),
            ),
        )
        val s = PostureScope.of(rs, DL)
        assertEquals(listOf("발"), s.watched)
        assertTrue(s.blind.isEmpty())
        assertEquals("발을 봐요.", s.startLine)              // 받침 있는 부위는 '을'
    }

    @Test
    fun sameBodyPartAcrossConditionsIsListedOnce() {
        val rs = PostureRuleSet(
            "t", "d",
            listOf(
                rule(DL, "발과 무릎의 방향 일치", RuleStatus.SHIP),
                rule(DL, "무릎 반동 없음", RuleStatus.SHIP, "knee_swing__std"),
                // 같은 부위가 exclude 쪽에도 있으면 높은 등급만 남는다 — "무릎을 봐요. 무릎은 못 봐요." 방지
                rule(DL, "무릎 충분히 올라옴", RuleStatus.EXCLUDE, "knee_high__max"),
            ),
        )
        val s = PostureScope.of(rs, DL)
        assertEquals(listOf("무릎"), s.watched)
        assertTrue(s.blind.isEmpty())
        assertEquals("평가 1", s.cardLine)
    }

    @Test
    fun betaOnlyExerciseSaysItIsStillBeingValidated() {
        val rs = PostureRuleSet(
            "t", "d",
            listOf(
                rule(FLOOR, "몸통과 엉덩이의 정렬 유지", RuleStatus.BETA, "trunk_ankle_ang__mean"),
                rule(FLOOR, "고개 젖힘/숙임 여부", RuleStatus.BETA, "head_trunk_ang__mean"),
            ),
        )
        val s = PostureScope.of(rs, FLOOR)
        assertFalse(s.hasAnyJudgement)
        assertTrue(s.provisionalOnly)
        assertEquals(listOf("엉덩이", "고개"), s.provisional)
        assertEquals("이 종목은 아직 검증 중이라 자세 지적 없이 횟수와 촬영 상태만 알려드려요.", s.startLine)
        assertEquals("검증 중 2", s.cardLine)
    }

    @Test
    fun allExcludedExerciseSaysNothingIsJudged() {
        val rs = PostureRuleSet("t", "d", listOf(rule(DL, "척추의 중립", RuleStatus.EXCLUDE, "spine_flex__mean", subtype = "flexion")))
        val s = PostureScope.of(rs, DL)
        assertFalse(s.hasAnyJudgement)
        assertFalse(s.provisionalOnly)
        assertEquals(listOf("등·허리"), s.blind)
        assertEquals("이 종목은 자세를 판정할 항목이 없어요. 횟수와 촬영 상태만 알려드려요.", s.startLine)
        assertEquals("못 봄 1", s.cardLine)
    }

    @Test
    fun unknownExerciseHasEmptyScope() {
        val s = PostureScope.of(deadlift, "없는 종목")
        assertTrue(s.watched.isEmpty())
        assertTrue(s.provisional.isEmpty())
        assertTrue(s.blind.isEmpty())
        assertFalse(s.hasAnyJudgement)
        assertFalse(s.provisionalOnly)
        assertNull(s.startLine)
        assertEquals("평가 항목 없음", s.cardLine)
        assertEquals("이 종목에서 볼 수 있는 항목이 없어요.", s.introLines[1])
    }

    @Test
    fun onlyFirstTwoWatchedAndFirstBlindAreSpoken() {
        val rs = PostureRuleSet(
            "t", "d",
            listOf(
                rule(DL, "발과 무릎의 방향 일치", RuleStatus.SHIP),
                rule(DL, "상체 과도한 젖힘 없음", RuleStatus.SHIP, "torso_pitch__min"),
                rule(DL, "손목의 중립", RuleStatus.SHIP, "wrist_dev__mean"),
                rule(DL, "척추의 중립", RuleStatus.EXCLUDE, "spine_flex__mean", subtype = "flexion"),
                rule(DL, "고개 정면 유지", RuleStatus.EXCLUDE, "head_yaw__std"),
            ),
        )
        val s = PostureScope.of(rs, DL)
        assertEquals(listOf("무릎", "상체", "손목"), s.watched)
        assertEquals("무릎·상체를 봐요. 등·허리는 못 봐요.", s.startLine)   // 문장은 짧게 — 앞 2개 + 못 보는 것 1개
        assertEquals("평가 3 · 못 봄 2", s.cardLine)
    }

    private companion object {
        const val DL = "바벨 데드리프트"
        const val FLOOR = "플랭크"
    }
}
