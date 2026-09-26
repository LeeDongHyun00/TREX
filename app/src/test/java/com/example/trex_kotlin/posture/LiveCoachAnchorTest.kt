package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 라이브 코치의 두 가지 신뢰성 장치:
 *  1) **앵커** — 초반 창(첫 8프레임)이 세트의 '정상 기준'인데, 준비 구간(폰 놓고 걸어와 자세 잡기)이 섞이면
 *     첫 코칭이 "처음부터 …" 오탐이 된다. 첫 렙 뒤 anchor() 로 창을 옮긴다.
 *  2) **베타 침묵** — 미보정 규칙은 판정·화면에는 남기되 음성으로 말하지 않는다(§28 오탐 3건이 전부 베타).
 */
class LiveCoachAnchorTest {

    private fun rule(
        id: String,
        condition: String,
        feature: String,
        op: String,
        thr: Float,
        auc: Float = 0.9f,
        status: RuleStatus = RuleStatus.SHIP,
    ): PostureRule {
        val i = feature.lastIndexOf("__")
        return PostureRule(
            id = id, exercise = EX, condition = condition, subtype = null, status = status, reason = null,
            feature = feature, baseFeature = feature.substring(0, i), stat = feature.substring(i + 2), family = "x",
            op = op, threshold = thr, view = "C", viewDesc = "", cvAuc = auc, cvBalacc = 0.8f, sampleN = 60,
            mirrorSafe = true, cautions = emptyList(),
        )
    }

    private val kneeRule = rule("스쿼트|무릎", "발과 무릎의 방향 일치", "knee_out_mean__mean", "<", 0.01f, auc = 0.97f)
    private val torsoRule = rule("스쿼트|상체", "상체 과도한 젖힘 없음", "torso_pitch__min", "<", -20f)
    private val wristBeta = rule("스쿼트|손목", "손목의 중립", "wrist_dev__mean", ">", 0.10f, auc = 0.72f, status = RuleStatus.BETA)

    private val rs = PostureRuleSet("t", "d", listOf(kneeRule, torsoRule))
    private val rsBeta = PostureRuleSet("t", "d", listOf(kneeRule, torsoRule, wristBeta))

    /** 세 규칙의 base feature 를 모두 담는다 — 하나라도 빠지면 그 규칙만 프레임 수 미달로 유보된다. */
    private fun frame(kneeOut: Float, torsoPitch: Float = 0f, wristDev: Float = 0f) =
        mapOf("knee_out_mean" to kneeOut, "torso_pitch" to torsoPitch, "wrist_dev" to wristDev)

    private fun coach(
        ruleSet: PostureRuleSet = rs,
        requireAnchor: Boolean = true,
        speakBeta: Boolean = !requireAnchor,
        persistence: Int = 2,
        ruleCooldownMs: Long = 12_000L,
        globalGapMs: Long = 0L,
    ) = LiveCoach(
        ruleSet, EX, requireAnchor = requireAnchor, speakBeta = speakBeta,
        windowFrames = 8, minFrames = 8, persistence = persistence,
        ruleCooldownMs = ruleCooldownMs, globalGapMs = globalGapMs,
    )

    // ---- 앵커

    @Test
    fun beforeAnchorNothingIsJudgedAndFramesAreDropped() {
        val c = coach()
        var t = 0L
        assertFalse(c.isAnchored)
        repeat(20) {
            c.onFrame(frame(-0.05f))            // 계속 위반이어도
            t += 300
            assertNull(c.evaluate(t))           // 판정 없음
        }
        assertTrue(c.lastStates.isEmpty())      // 화면의 붉은 강조도 없음
        assertEquals(0, c.frameCount)           // 준비 구간 프레임은 버린다
    }

    @Test
    fun anchorDropsWarmupSoFirstCoachingIsHabitNotDrift() {
        val c = coach(persistence = 2)
        var t = 0L
        repeat(12) { c.onFrame(frame(0.05f)); t += 300 }   // 준비 구간: 정상 자세
        assertTrue(c.anchor())
        assertTrue(c.isAnchored)
        assertEquals(0, c.frameCount)
        var ev: CoachEvent? = null
        repeat(12) {
            c.onFrame(frame(-0.06f)); t += 300
            ev = c.evaluate(t) ?: ev
        }
        assertNotNull(ev)
        assertEquals(kneeRule.id, ev!!.rule.id)
        assertEquals(OnsetKind.HABIT, ev!!.kind)           // 준비 구간이 초반 창에 섞였다면 DRIFT 가 됐을 것
        assertTrue(ev!!.message.startsWith("처음부터"))

        // 대조군: 앵커를 쓰지 않으면 같은 입력이 DRIFT 로 잘못 분류된다
        val legacy = coach(requireAnchor = false)
        var t2 = 0L
        repeat(12) { legacy.onFrame(frame(0.05f)); t2 += 300 }
        var ev2: CoachEvent? = null
        repeat(12) {
            legacy.onFrame(frame(-0.06f)); t2 += 300
            ev2 = legacy.evaluate(t2) ?: ev2
        }
        assertEquals(OnsetKind.DRIFT, ev2!!.kind)
    }

    @Test
    fun secondAnchorIsIgnoredAndKeepsTheWindow() {
        val c = coach()
        assertTrue(c.anchor())
        repeat(5) { c.onFrame(frame(-0.05f)) }
        assertFalse(c.anchor())                 // 이미 앵커됨
        assertEquals(5, c.frameCount)           // 창을 리셋하지 않는다
    }

    @Test
    fun summarizeIsEmptyBeforeAnchor() {
        val c = coach()
        repeat(20) { c.onFrame(frame(-0.05f)) }
        assertTrue(c.summarize().isEmpty())
        assertTrue(c.anchor())
        repeat(10) { c.onFrame(frame(-0.05f)) }
        val sum = c.summarize()
        assertTrue(sum.isNotEmpty())
        assertEquals(OnsetKind.HABIT, sum.first { it.rule.id == kneeRule.id }.kind)
    }

    @Test
    fun resetRequiresAnchorAgain() {
        val c = coach(persistence = 1)
        assertTrue(c.anchor())
        var t = 0L
        repeat(10) { c.onFrame(frame(-0.05f)); t += 300 }
        assertNotNull(c.evaluate(t))
        c.reset()
        assertFalse(c.isAnchored)
        repeat(10) { c.onFrame(frame(-0.05f)); t += 300 }
        assertEquals(0, c.frameCount)
        assertNull(c.evaluate(t))
        assertTrue(c.lastStates.isEmpty())
        assertTrue(c.anchor())
        repeat(10) { c.onFrame(frame(-0.05f)); t += 300 }
        assertNotNull(c.evaluate(t))
    }

    // ---- 베타 침묵

    @Test
    fun betaViolationIsJudgedButNotSpoken() {
        val c = coach(ruleSet = rsBeta, persistence = 1, ruleCooldownMs = 0L)
        c.anchor()
        var t = 0L
        repeat(10) { c.onFrame(frame(0.05f, wristDev = 0.5f)); t += 300 }   // 베타만 위반
        assertNull(c.evaluate(t))
        val st = c.lastStates.first { it.rule.id == wristBeta.id }
        assertEquals(Verdict.VIOLATION, st.recent)          // 판정·화면에는 남는다
        assertEquals(OnsetKind.HABIT, st.kind)

        // ship 위반이 같이 나면 ship 을 말한다
        repeat(10) { c.onFrame(frame(-0.06f, wristDev = 0.5f)); t += 300 }
        val ev = c.evaluate(t)
        assertNotNull(ev)
        assertEquals(kneeRule.id, ev!!.rule.id)
    }

    @Test
    fun speakBetaTrueLetsBetaSpeak() {
        val c = coach(ruleSet = rsBeta, speakBeta = true, persistence = 1, ruleCooldownMs = 0L)
        c.anchor()
        var t = 0L
        repeat(10) { c.onFrame(frame(0.05f, wristDev = 0.5f)); t += 300 }
        val ev = c.evaluate(t)
        assertNotNull(ev)
        assertEquals(wristBeta.id, ev!!.rule.id)
        assertTrue(ev.message, ev.message.contains("손목"))
    }

    private companion object {
        const val EX = "바벨 스쿼트"
    }
}
