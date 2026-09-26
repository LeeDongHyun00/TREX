package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 반복별 자세 검사(spec §62a·§62b, 설계 §21) — 2026-09-25 실기기 세트의 재현.
 * 사이클 창을 위상(시작·상단·바닥·사이클)으로 자르고, 발 너비(바닥 2D 발목 간격 ÷ 시작, AND 시작 어깨 대비)·발끝(하강 직전 2D 각 시작 대비)이
 * ship 으로 '정확' 을 깎는지, 시작 기준이 흔들린 세트(16:13)·어깨 한 프레임 오탐(16:16 2회)·직전 창 이월 오탐(16:16 6회)이 사라졌는지를 잠근다.
 */
class RepFormTest {

    private val squat = RepFormSpecs.byExercise.getValue("바벨 스쿼트")
    private fun evaluator() = RepFormEvaluator(squat, "knee_mean", 35f)

    private class Frames(private val ev: RepFormEvaluator) {
        var t = 0L
        /** [stance] = 발목 간격 ÷ 어깨 간격(어깨 0.2 고정 → 발목 0.2·stance). [shoulder] 로 어깨만 흔들 수 있다(16:16 2회 재현). */
        fun frame(knee: Float, stance: Float = 1.0f, toe: Float = 20f, kneeOut: Float = 0.15f, torso: Float = 5f, maxside: Float = knee + 3f,
                  asym: Float = -5f, roll: Float = 2f, shoulder: Float = 0.2f, ankle: Float = 0.2f * stance) {
            ev.onFrame(t, mapOf("knee_mean" to knee, "knee_maxside" to maxside, Stance2d.ANKLE_SEP to ankle, Stance2d.SHOULDER_SEP to shoulder,
                Stance2d.FEATURE to ankle / shoulder, Stance2d.TOE_MAXSIDE to toe, "toe_out_maxside" to toe,
                "knee_out_mean" to kneeOut, "torso_incl" to torso, "knee_asym" to asym, "torso_roll" to roll))
            t += 300
        }
        /** 서 있음 n프레임 → 스쿼트 한 사이클 → 복귀. 바닥 3프레임에 bottomKneeOut, 최대 상체 기울기 torsoMax. */
        fun rep(standFrames: Int = 5, stance: Float = 1.0f, toe: Float = 20f, bottomKneeOut: Float = 0.15f, torsoMax: Float = 25f, ev: RepFormEvaluator): RepFormRep {
            repeat(standFrames) { frame(163f, stance, toe, -0.02f, 5f) }
            frame(140f, stance, toe, 0.05f, 12f)
            // 110° 프레임은 바닥 창(최소 + 진폭/3 = 115.7° 아래)에 들어간다 — 무릎 방향은 굽힘과 함께 드러나므로 바닥값 쪽으로 절반 간 값
            frame(110f, stance, toe, (0.10f + bottomKneeOut) / 2f, 20f)
            frame(92f, stance, toe, bottomKneeOut, torsoMax)
            frame(95f, stance, toe, bottomKneeOut, torsoMax - 3f)
            frame(100f, stance, toe, bottomKneeOut, 18f)
            frame(130f, stance, toe, 0.08f, 12f)
            frame(158f, stance, toe, 0.0f, 6f)
            frame(163f, stance, toe, -0.02f, 5f)
            return ev.onCycle(t - 300, 92f, 163f)
        }
    }

    @Test
    fun startPostureComesFromTheFirstTopWindowAndRelativeChecksUseIt() {
        val ev = evaluator(); val f = Frames(ev)
        val r1 = f.rep(ev = ev)
        assertNotNull(ev.baseline)
        assertEquals(0.2f, ev.baseline!!.getValue(Stance2d.ANKLE_SEP), 1e-4f)
        assertEquals(0.2f, ev.baseline!!.getValue(Stance2d.SHOULDER_SEP), 1e-4f)
        assertEquals(20f, ev.baseline!!.getValue(Stance2d.TOE_MAXSIDE), 1e-4f)
        // 시작 자세 검사(절대 띠) 두 개가 한 번 판정됐다 — 둘 다 정상
        assertEquals(2, ev.startOutcomes.size)
        assertTrue(ev.startOutcomes.all { it.verdict == Verdict.OK })
        // 첫 반복: 자기 자신이 기준이라 상대 검사는 정상, 바닥 무릎 0.15 정상 → 정확
        assertTrue(r1.correct); assertTrue(r1.flagged.isEmpty())
        // 발을 넓힌(×1.6) 반복 — 발 너비(ship)만 걸리고 무릎은 걸리지 않는다. 바닥 구간 발목 간격 중앙값 ÷ 시작
        val r2 = f.rep(stance = 1.6f, ev = ev)
        assertEquals(listOf("repform|바벨 스쿼트|발 간격"), r2.flagged.map { it.check.id })
        assertEquals(FormDirection.HIGH, r2.flagged[0].direction)
        assertEquals(1.6f, r2.flagged[0].value!!, 1e-3f)
        assertFalse(r2.outcomes.any { it.check.id.contains("무릎") && it.verdict == Verdict.VIOLATION })
        assertFalse("ship — 정확을 깎는다(§62b)", r2.correct)
        // 발을 넓히고 발끝 값도 +18° 인 반복 — 발 너비만 걸리고 발끝은 **유보**(넓게 서면 발끝 그대로여도 +14~30° 로 읽힌다, A6·A7b)
        val r3 = f.rep(stance = 1.6f, toe = 38f, ev = ev)
        assertEquals(listOf("repform|바벨 스쿼트|발 간격"), r3.flagged.map { it.check.id })
        val toeAb = r3.outcomes.first { it.check.id == "repform|바벨 스쿼트|발끝 방향" }
        assertEquals(Verdict.ABSTAIN, toeAb.verdict); assertEquals("발 너비 위반으로 측정 무효", toeAb.abstainReason)
        // 좁힘은 beta(참고), 발끝 모임(−20°)은 ship
        val r4 = f.rep(stance = 0.5f, ev = ev)
        assertEquals(listOf("repform|바벨 스쿼트|발 간격|좁음"), r4.flagged.map { it.check.id })
        assertTrue(r4.correct)
        val r5 = f.rep(toe = 0f, ev = ev)
        assertEquals(FormDirection.LOW, r5.flagged.single().direction)
        assertTrue(r5.flagged.single().check.ship); assertFalse(r5.correct)
    }

    @Test
    fun wideningNeedsBothTheRelativeAndTheShoulderScaledCondition() {
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)                                  // 시작: 발목 0.2 = 어깨 0.2 (절대 1.0)
        // 시작 대비 ×1.45 이지만 어깨 대비 1.45 < 1.5 — 스타일이 조금 바뀐 것이지 어깨 너비를 넘지 않았다 → 정상
        val mild = f.rep(stance = 1.45f, ev = ev)
        val o = mild.outcomes.first { it.check.id == "repform|바벨 스쿼트|발 간격" }
        assertEquals(Verdict.OK, o.verdict); assertEquals(1.45f, o.value!!, 1e-3f)
        assertTrue(mild.correct)
        // ×1.6(어깨 대비 1.6) 은 위반
        assertFalse(f.rep(stance = 1.6f, ev = ev).correct)
    }

    @Test
    fun shoulderJitterInOneFrameNoLongerFakesAWideStance() {
        // 16:16 세트 2회: 어깨 x 간격이 한 프레임 26 px(정상 90)로 떨어져 옛 stance_2d 극값이 ×3.21 — 발목 간격만 쓰면 사라진다
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)
        repeat(4) { f.frame(163f) }
        f.frame(163f, shoulder = 0.06f)                  // 어깨만 무너진 한 프레임(발목 0.2 그대로)
        for (knee in listOf(140f, 110f, 92f, 95f, 100f, 130f, 158f, 163f)) f.frame(knee)
        val r = ev.onCycle(f.t - 300, 92f, 163f)
        assertEquals(Verdict.OK, r.outcomes.first { it.check.id == "repform|바벨 스쿼트|발 간격" }.verdict)
        assertTrue(r.correct)
    }

    @Test
    fun feetReturningAfterAWideRepDoNotLeakIntoTheNextRep() {
        // 16:16 세트 6회: 5회(넓게) 뒤 발을 모으던 프레임이 옛 '서 있는 극값 + 이월' 창에 들어가 ×1.90 오탐 — 바닥 구간은 이월을 받지 않는다
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)
        f.rep(stance = 2.0f, ev = ev)                    // 넓게(끝 프레임까지 넓은 채)
        f.frame(163f, stance = 1.6f); f.frame(163f, stance = 1.2f)   // 발을 모으는 중
        val back = f.rep(standFrames = 2, stance = 1.0f, toe = 40f, ev = ev)   // 되돌린 뒤 발끝을 벌린 반복
        val st = back.outcomes.first { it.check.id == "repform|바벨 스쿼트|발 간격" }
        assertEquals(Verdict.OK, st.verdict); assertEquals(1.0f, st.value!!, 1e-3f)
        // 발 너비 오탐이 사라지니 발끝 위반이 유보되지 않고 잡힌다
        val toe = back.outcomes.first { it.check.id == "repform|바벨 스쿼트|발끝 방향" }
        assertEquals(Verdict.VIOLATION, toe.verdict); assertEquals(20f, toe.value!!, 1e-3f)
        assertFalse(back.correct)
    }

    @Test
    fun unstableStartTakesTheFootWidthReferenceFromTheFirstBottom() {
        // 16:13 세트: 발을 모은 채 서 있다가(발목 60 px) 넓히며 바로 앉음(97 px) — 상단 창 중앙값을 기준으로 쓰면 21회 전부 '넓음'
        val ev = evaluator(); val f = Frames(ev)
        for (a in listOf(0.12f, 0.12f, 0.13f, 0.16f, 0.19f)) f.frame(163f, ankle = a)   // 옮기는 중(최대÷최소 1.58 > 1.1)
        for (knee in listOf(140f, 110f, 92f, 95f, 100f, 130f, 158f, 163f)) f.frame(knee, ankle = 0.19f)
        val first = ev.onCycle(f.t - 300, 92f, 163f)
        assertEquals(0.19f, ev.baseline!!.getValue(Stance2d.ANKLE_SEP), 1e-4f)   // 첫 반복 바닥
        assertTrue(ev.baselineFromFirstBottom)
        assertEquals(Verdict.OK, first.outcomes.first { it.check.id == "repform|바벨 스쿼트|발 간격" }.verdict)
        val same = f.rep(stance = 0.95f, ev = ev)
        assertTrue(same.correct)
        assertTrue(ev.summary().lines().any { it.contains("첫 반복으로 잡았어요") })
        // 그 뒤 진짜로 넓히면(×1.6 · 어깨 대비 1.52) 잡힌다
        assertFalse(f.rep(stance = 1.52f, ev = ev).correct)
    }

    @Test
    fun shipKneeCheckUsesTheBottomWindowAndOnlyItReducesCorrect() {
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)
        // 바닥에서 무릎이 엉덩이–발목 선 안쪽(−0.04) — 서 있는 프레임(−0.02)이 아니라 바닥 구간이 판정한다
        val bad = f.rep(bottomKneeOut = -0.04f, ev = ev)
        val knee = bad.flagged.single()
        assertEquals("repform|바벨 스쿼트|무릎 안쪽 모임", knee.check.id)
        assertTrue(knee.check.ship)
        assertTrue("bottom mean=${knee.value}", knee.value!! < 0f)   // 바닥 창 평균(110° 프레임 포함) −0.0225
        assertFalse(bad.correct)
        // 바닥이 정상(0.20)이면 서 있는 프레임이 −0.02 라도 무릎은 정상 — 세트 평균 규칙의 오탐이 여기서 사라진다
        val good = f.rep(bottomKneeOut = 0.20f, ev = ev)
        assertTrue(good.correct)
        // 과도 벌림(0.50)은 beta — 화면·리포트만, 정확은 그대로
        val wide = f.rep(bottomKneeOut = 0.50f, ev = ev)
        assertEquals("repform|바벨 스쿼트|무릎 과도 벌림", wide.flagged.single().check.id)
        assertTrue(wide.correct)
        val s = ev.summary()
        assertEquals(3, s.correct)
        assertEquals(4, s.reps.size)
    }

    @Test
    fun torsoCheckIsCycleMaxRelativeToStandingBaseline() {
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)
        val round = f.rep(torsoMax = 65f, ev = ev)   // 실기기 허리 굽힘 63~67°, 서 있는 기준 5°
        val o = round.flagged.single()
        assertEquals("repform|바벨 스쿼트|상체 숙임", o.check.id)
        assertEquals(60f, o.value!!, 1e-3f)
        assertFalse("ship — 허리 굽힘 회는 횟수에서 빠진다(사용자 결정)", round.correct)
        val deep = f.rep(torsoMax = 39f, ev = ev)    // 깊은 정상 반복(09:52 세트 2회) — 34° 는 띠 안
        assertTrue(deep.flagged.isEmpty()); assertTrue(deep.correct)
    }

    @Test
    fun rejectedCycleConsumesItsWindowSoTheNextSquatBottomIsClean() {
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)
        // 무릎 들기(판별 게이트가 기각): 한쪽 무릎만 굽어 knee_out 이 크게 음수 — 이 창은 버려져야 한다
        repeat(3) { f.frame(163f) }
        f.frame(110f, kneeOut = -0.20f, maxside = 160f); f.frame(95f, kneeOut = -0.24f, maxside = 158f); f.frame(150f, kneeOut = -0.05f, maxside = 161f)
        f.frame(163f); f.frame(163f)
        ev.onRejected(f.t - 300)
        // 다음 정상 스쿼트의 바닥은 0.15 — 기각 창의 −0.24 가 섞이면 ship 위반이 됐을 것
        val next = f.rep(ev = ev)
        assertTrue(next.correct)
        assertEquals(1, ev.summary().rejected)
    }

    @Test
    fun missingTopWindowAbstainsRelativeChecksWithoutCountingThemAsViolations() {
        val ev = evaluator(); val f = Frames(ev)
        // 서 있는 프레임 없이 바로 하강 — 시작 자세를 못 잡는다
        val r = f.rep(standFrames = 0, ev = ev)
        assertNull(ev.baseline)
        val stance = r.outcomes.first { it.check.id == "repform|바벨 스쿼트|발 간격" }
        assertEquals(Verdict.ABSTAIN, stance.verdict)
        assertTrue(r.correct)   // 판정 못 한 검사는 위반이 아니다(원칙 #1)
        assertEquals(1, ev.summary().noTop)
        // 다음 반복에서 상단이 잡히면 그때 시작 자세가 선다
        f.rep(ev = ev)
        assertNotNull(ev.baseline)
        assertEquals(2, ev.startOutcomes.size)
    }

    @Test
    fun startPostureBandFlagsWideStanceBeforeTheSet() {
        val ev = evaluator(); val f = Frames(ev)
        f.rep(stance = 2.0f, toe = 55f, ev = ev)   // 시작 띠 0.5~1.8 · −5~50°
        val start = ev.startOutcomes.associateBy { it.check.id }
        assertEquals(FormDirection.HIGH, start.getValue("repform|바벨 스쿼트|발 간격|시작").direction)
        assertEquals(FormDirection.HIGH, start.getValue("repform|바벨 스쿼트|발끝 방향|시작").direction)
        assertTrue(ev.summary().ruleResult(RepFormSpecs.asRules().first { it.id == "repform|바벨 스쿼트|발 간격|시작" })!!.measurement!!.contains("발이 어깨보다 많이 넓어요"))   // 시작 자세 문장은 규칙 행에
    }

    @Test
    fun shipEventsNeedTwoConsecutiveViolationsUnlessGatedAndBetaEventsAreScreenOnly() {
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)
        val one = f.rep(bottomKneeOut = -0.04f, ev = ev)
        assertNull("한 번 튐은 말하지 않는다", ev.eventFor(one, 10_000L))
        val two = f.rep(bottomKneeOut = -0.04f, ev = ev)
        val ev2 = ev.eventFor(two, 20_000L)
        assertNotNull(ev2); assertTrue(ev2!!.ship)
        assertEquals("바닥에서 무릎이 안쪽으로 모였어요. 무릎을 발끝 방향으로 두세요.", ev2.message)
        val three = f.rep(bottomKneeOut = -0.04f, ev = ev)
        assertNull("쿨다운 안에서는 다시 말하지 않는다", ev.eventFor(three, 25_000L))
        // beta 는 반복마다 화면용 사건(ship 아님)
        val narrow = f.rep(stance = 0.5f, ev = ev)
        val evB = ev.eventFor(narrow, 60_000L)
        assertNotNull(evB); assertFalse(evB!!.ship)
        assertEquals("발 너비가 시작보다 좁아졌어요", evB.message)
        // 횟수 게이트(COACH): 빠진 회는 첫 위반부터 말한다 — 침묵하면 카운트가 죽은 줄 안다(§62b)
        val g = evaluator(); val fg = Frames(g)
        fg.rep(ev = g)
        val first = fg.rep(stance = 1.6f, ev = g)
        val evG = g.eventFor(first, 10_000L, gate = true)
        assertNotNull(evG); assertTrue(evG!!.ship)
        assertEquals("발 너비가 시작보다 넓어졌어요. 발을 어깨 너비로 다시 두세요.", evG.message)
        assertNull("쿨다운은 게이트에서도 건다", g.eventFor(fg.rep(stance = 1.6f, ev = g), 15_000L, gate = true))
    }

    @Test
    fun setLevelRuleResultsFollowTheFloorRepConvention() {
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)
        repeat(4) { f.rep(stance = 1.6f, ev = ev) }   // 5회 중 4회 넓음
        f.rep(toe = 38f, ev = ev)                      // 6회 중 1회 발끝
        val s = ev.summary()
        val rules = RepFormSpecs.asRules().associateBy { it.id }
        val stance = s.ruleResult(rules.getValue("repform|바벨 스쿼트|발 간격"))!!
        assertEquals(Verdict.VIOLATION, stance.verdict)
        assertTrue(stance.measurement!!, stance.measurement!!.contains("6회 중 넓음 4회"))
        assertTrue("ship 이라 '참고' 가 붙지 않는다", stance.measurement!!.startsWith("발 너비"))
        val toe = s.ruleResult(rules.getValue("repform|바벨 스쿼트|발끝 방향"))!!
        assertEquals(Verdict.OK, toe.verdict)   // 1회는 max(2, 34%) 미만
        assertTrue(toe.measurement!!, toe.measurement!!.contains("2회 중 바깥 1회"))   // 넓게 선 4회는 발끝 유보 → 판정 2회
        // 정면이 아니면 유보
        assertEquals(Verdict.ABSTAIN, s.ruleResult(rules.getValue("repform|바벨 스쿼트|발 간격"), viewOk = false)!!.verdict)
        // 시작 자세 행
        val start = s.ruleResult(rules.getValue("repform|바벨 스쿼트|발 간격|시작"))!!
        assertEquals(Verdict.OK, start.verdict)
        assertTrue(start.measurement!!.startsWith("시작 자세 · 발 너비 1.00"))
        assertEquals("발 너비 넓음 4회 · 발끝 바깥 1회", s.flagLine())
        assertTrue(s.lines().any { it == "정확 1 / 6회" })   // 넓음 4 + 발끝 1 은 ship → 정확 1
        assertFalse("시작 자세는 규칙 행에 있다 — 요약 줄에 중복하지 않는다", s.lines().any { it.startsWith("시작 자세") })
    }

    @Test
    fun standingFramesAtTheEndOfARepCarryOverToTheNextTopWindow() {
        // 11:37 세트: 쉬지 않고 이어 하면 직전 반복의 복귀 뒤 서 있던 프레임이 1개뿐 — 그 프레임들은 직전 창에 있었다. 이월해야 상단이 선다
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)                                   // 끝에 158·163 두 프레임이 서 있음(≥ 시작 − 10.5)
        val next = f.rep(standFrames = 0, ev = ev)       // 바로 하강 — 상단은 직전 창의 꼬리에서
        assertEquals(Verdict.OK, next.outcomes.first { it.check.id == "repform|바벨 스쿼트|발끝 방향" }.verdict)
        assertEquals(0, ev.summary().noTop)
        // 기각 뒤에는 이월하지 않는다(기각 창의 끝이 서 있던 프레임인지 모른다) — 상단이 없으니 발끝은 유보(복귀 뒤 프레임은 과도 상태라 쓰지 않는다, A7b)
        f.frame(163f); ev.onRejected(f.t - 300)
        val after = f.rep(standFrames = 0, ev = ev)
        assertEquals(1, ev.summary().noTop)
        assertEquals(Verdict.ABSTAIN, after.outcomes.first { it.check.id == "repform|바벨 스쿼트|발끝 방향" }.verdict)
        assertTrue(after.correct)
        // 발 너비는 바닥 구간이라 상단이 없어도 판정한다
        assertEquals(Verdict.OK, after.outcomes.first { it.check.id == "repform|바벨 스쿼트|발 간격" }.verdict)
    }

    @Test
    fun wideningDuringTheDescentShowsInTheBottomWindow() {
        // 11:37 세트 3회: 하강을 시작하며 발을 벌림 — 하강 직전 상단은 ×1.12 지만 바닥은 ×1.9
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)
        repeat(5) { f.frame(163f, stance = 1.0f) }
        for ((knee, st) in listOf(150f to 1.3f, 120f to 1.8f, 92f to 1.9f, 95f to 1.9f, 100f to 1.85f, 130f to 1.8f, 158f to 1.7f, 163f to 1.6f)) f.frame(knee, stance = st)
        val r = ev.onCycle(f.t - 300, 92f, 163f)
        val st = r.outcomes.first { it.check.id == "repform|바벨 스쿼트|발 간격" }
        assertEquals(FormDirection.HIGH, st.direction)
        assertTrue("ratio=${st.value}", st.value!! > 1.5f)
    }

    @Test
    fun shipKneeEventNamesTheFootCauseWhenFeetAreAlsoOff() {
        // 11:37 세트 7~8회: 발끝 −21°·발 너비 ×1.8 → 무릎이 따라 들어와 knee_out ≈ 0. 무릎만 말하면 사용자가 바꾼 것(발)을 못 짚는다
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)
        f.rep(toe = 0f, bottomKneeOut = -0.04f, ev = ev)          // 12:19 세트 3·4회: 발끝 안쪽만(발 너비 그대로)
        val two = f.rep(toe = 0f, bottomKneeOut = -0.04f, ev = ev)
        assertFalse(two.correct)
        val e = ev.eventFor(two, 30_000L)!!
        assertTrue(e.ship)
        assertEquals("repform|바벨 스쿼트|무릎 안쪽 모임", e.check.id)
        assertEquals("발끝이 시작보다 안으로 모였어요 — 무릎이 따라 움직였어요. 발끝을 시작 자세로 되돌리세요.", e.message)
        // 방향이 섞인 검사는 방향별로 센다
        f.rep(toe = 38f, ev = ev)
        assertEquals("무릎 안쪽 2회 · 발끝 안쪽 2회 · 바깥 1회", ev.summary().flagLine())
    }

    @Test
    fun ruleSetMergeAddsRepFormRulesAndDemotesTheSupersededWindowRule() {
        val knee = PostureRule("바벨 스쿼트|발과 무릎의 방향 일치", "바벨 스쿼트", "발과 무릎의 방향 일치", null, RuleStatus.SHIP, null,
            "knee_out_mean__mean", "knee_out_mean", "mean", "world", "<", 0.02388f, "C", "정면", .96f, .94f, 56, true, emptyList())
        val spine = knee.copy(id = "바벨 스쿼트|척추의 중립[flexion]", condition = "척추의 중립", subtype = "flexion", feature = "torso_incl__range", baseFeature = "torso_incl", stat = "range", op = ">", threshold = 30.85f)
        val merged = PostureRuleSet("mp_v0.1", "", listOf(knee, spine)).plusRepForm()
        assertEquals("mp_v0.1+repform_v0.2", merged.version)
        assertEquals(RuleStatus.BETA, merged.rules.first { it.id == knee.id }.status)
        assertEquals(RuleStatus.SHIP, merged.rules.first { it.id == spine.id }.status)
        val added = merged.rules.filter { it.kind == "rep_form" }
        assertEquals(RepFormSpecs.byExercise.values.flatten().size, added.size)   // 등록부 전체(스쿼트 + 컬, §62c)
        assertTrue(added.filter { it.exercise == "바벨 스쿼트" }.all { it.viewsOk == setOf("C") })
        assertEquals(9, added.count { it.status == RuleStatus.SHIP })   // 스쿼트 4(상체·무릎·발 간격·발끝) + 컬 5(상체 숙임·뜸·옆 벌림·앞 이탈·몸에서 떨어짐, §62c)
        // 범위 문장: 무릎·발 너비·발끝·상체는 '봄'(반복 검사 ship), 엉덩이(hip rise)는 '검증 중'
        val scope = PostureScope.of(merged, "바벨 스쿼트")
        assertTrue(scope.watched.containsAll(listOf("등·허리", "무릎")))
        assertTrue(scope.provisional.contains("엉덩이"))
        assertFalse(scope.provisional.contains("무릎"))
        // 창 규칙 평가는 rep_form 을 유보로 두고(시간·반복 측정 필요), 세트 평가가 요약으로 채운다
        val agg = FeatureAggregator()
        repeat(10) { agg.add(mapOf("knee_out_mean" to 0.1f, "torso_incl" to 5f, "stance_sh" to 1f)) }
        val res = merged.evaluate("바벨 스쿼트", agg, true, 8, null).associateBy { it.rule.id }
        assertEquals(Verdict.ABSTAIN, res.getValue("repform|바벨 스쿼트|발 간격").verdict)
    }

    @Test
    fun toeIsReadFromTheLastStandingFramesBeforeTheDescentAndCarriesOver() {
        // 발끝은 하강 직전 ≤3프레임 중앙값(A7b: 복귀 직후 프레임은 과도 상태). 반복 뒤 서서 발끝을 돌리면 그 프레임은 다음 반복의 상단으로 이월돼 거기서 잡힌다
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)
        repeat(2) { f.frame(163f) }   // 하강 직전 서 있는 프레임 2개(발끝 20)
        for ((knee, toe) in listOf(140f to 20f, 110f to 20f, 92f to 20f, 95f to 20f, 100f to 20f, 130f to 20f, 156f to 50f, 156f to 50f, 157f to 50f)) f.frame(knee, toe = toe)
        val r = ev.onCycle(f.t - 300, 92f, 163f)
        val toeO = r.outcomes.first { it.check.id == "repform|바벨 스쿼트|발끝 방향" }
        assertEquals(Verdict.OK, toeO.verdict)                 // 이 반복의 하강 직전은 20 — 정상
        assertEquals(0f, toeO.value!!, 1e-3f)
        val next = f.rep(standFrames = 0, toe = 50f, ev = ev)  // 바로 이어서 — 상단 = 이월된 156·156·157(발끝 50)
        val toeN = next.outcomes.first { it.check.id == "repform|바벨 스쿼트|발끝 방향" }
        assertEquals(Verdict.VIOLATION, toeN.verdict)
        assertEquals(30f, toeN.value!!, 1e-3f)
        assertFalse(next.correct)
    }

    @Test
    fun hipRiseAndLateralChecksReadTheirOwnWindows() {
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)
        // hip rise: 바닥 상체 25° → 올라오며 50° 로 더 숙여짐(RISE 25 > 20)
        repeat(5) { f.frame(163f) }
        for ((knee, torso) in listOf(140f to 12f, 110f to 20f, 92f to 25f, 100f to 40f, 120f to 50f, 140f to 30f, 158f to 8f, 163f to 5f)) f.frame(knee, torso = torso)
        val rise = ev.onCycle(f.t - 300, 92f, 163f)
        val h = rise.flagged.single()
        assertEquals("repform|바벨 스쿼트|엉덩이 먼저 상승", h.check.id)
        assertEquals(25f, h.value!!, 1e-3f)
        assertTrue(rise.correct)   // beta
        // 좌우: 바닥에서 왼 무릎이 40° 더 굽음(asym −40) → LOW(왼쪽 쏠림); 몸통 좌우 기울기 +20 → 위반
        repeat(5) { f.frame(163f) }
        for (knee in listOf(140f, 110f, 92f, 95f, 100f, 130f, 158f, 163f)) f.frame(knee, asym = if (knee < 116f) -40f else -5f, roll = if (knee < 130f) 26f else 2f)
        val lat = ev.onCycle(f.t - 300, 92f, 163f)
        val ids = lat.flagged.map { it.check.id to it.direction }
        assertTrue(ids.contains("repform|바벨 스쿼트|좌우 무릎 비대칭" to FormDirection.LOW))
        assertTrue(ids.contains("repform|바벨 스쿼트|몸통 좌우 기울기" to FormDirection.HIGH))
    }

    @Test
    fun evaluatorFactoryFollowsTheRegistry() {
        val squatCounter = RepCounter.forSession("바벨 스쿼트", floor = false)!!
        assertNotNull(RepFormSpecs.evaluatorFor("바벨 스쿼트", squatCounter))
        val lunge = RepCounter.forSession("바벨 런지", floor = false)!!
        assertNull(RepFormSpecs.evaluatorFor("바벨 런지", lunge))
        assertNotNull(RepFormSpecs.checkOf("repform|바벨 스쿼트|발끝 방향"))
        assertNull(RepFormSpecs.checkOf("바벨 스쿼트|발과 무릎의 방향 일치"))
    }
}

/** 덤벨 컬 반복 검사(spec §62c) — 2단 팔꿈치 앞 이탈(코칭·차단), beta 몸통·벌어짐, 사선 뷰 전제. */
class RepFormCurlTest {
    private val curl = RepFormSpecs.byExercise.getValue("덤벨 컬")
    private fun evaluator() = RepFormEvaluator(curl, "elbow_minside", 35f)

    private class Frames(private val ev: RepFormEvaluator) {
        var t = 0L
        /** [fwdNear] = 카메라 쪽 팔 앞 성분(기본은 [fwd] 와 같다), [latNear] = 카메라 쪽 팔 바깥 가로(없으면 키 없음), [withMean] = 양팔 평균이 있는가(먼 팔꿈치가 보이는가). */
        fun frame(elbow: Float, fwd: Float = 0.02f, tilt: Float = 0.0f, gap: Float = 1.3f, lat: Float = 0.12f, rise: Float = -0.50f, wrist: Float = -0.90f, incl: Float = 5f,
                  yaw: Float? = null, latNear: Float? = null, fwdNear: Float? = fwd, withMean: Boolean = true) {
            val m = hashMapOf("elbow_minside" to elbow, Arm2d.ELBOW_FWD_MEAN to fwd, Arm2d.TORSO_TILT to tilt, "elbow_gap_sh" to gap,
                Arm2d.ELBOW_LAT_MAX to lat, Arm2d.ELBOW_RISE_MAX to rise, Arm2d.WRIST_H_MAX to wrist, "torso_incl" to incl)
            if (!withMean) m.remove(Arm2d.ELBOW_FWD_MEAN)
            fwdNear?.let { m[Arm2d.ELBOW_FWD_NEAR] = it }
            latNear?.let { m[Arm2d.ELBOW_LAT_NEAR] = it }
            // 방향 피처(뷰) — 주면 반복 창 뷰 게이팅이 작동한다(§62c 후속 6). 안 주면 종전처럼 반복 뷰 없음
            yaw?.let { val r = Math.toRadians(it.toDouble()); m[ViewEstimator.FEAT_COS] = kotlin.math.cos(r).toFloat(); m[ViewEstimator.FEAT_SIN] = kotlin.math.sin(r).toFloat() }
            ev.onFrame(t, m); t += 300
        }
        /** 이완(팔 늘어뜨림) 5프레임 → 수축 → 복귀. 수축 구간(바닥)에 fwd·tilt·gap·lat, 창 최대에 rise·wrist. */
        /** [topIncl] = 이완(상단) 프레임의 몸통 기울기 — 숙인 채 시작하는 반복을 만든다. */
        fun rep(fwd: Float = 0.02f, tilt: Float = 0.0f, gap: Float = 1.3f, lat: Float = 0.14f, rise: Float = -0.52f, wrist: Float = -0.10f, incl: Float = 5f,
                topIncl: Float = 5f, yaw: Float? = null, latNear: Float? = null, topLat: Float = 0.12f, withMean: Boolean = true, ev: RepFormEvaluator): RepFormRep {
            repeat(5) { frame(165f, lat = topLat, incl = topIncl, yaw = yaw, withMean = withMean) }
            frame(140f, wrist = -0.60f, incl = topIncl, yaw = yaw, withMean = withMean)
            frame(100f, fwd, tilt, gap, lat, rise, -0.30f, incl, yaw, latNear = latNear, withMean = withMean); frame(80f, fwd, tilt, gap, lat, rise, wrist, incl, yaw, latNear = latNear, withMean = withMean)
            frame(85f, fwd, tilt, gap, lat, rise, wrist, incl, yaw, latNear = latNear, withMean = withMean); frame(100f, fwd, tilt, gap, lat, rise, -0.30f, incl, yaw, latNear = latNear, withMean = withMean)
            frame(140f, wrist = -0.60f, incl = topIncl, yaw = yaw, withMean = withMean)
            frame(163f, lat = topLat, incl = topIncl, yaw = yaw, withMean = withMean); frame(165f, lat = topLat, incl = topIncl, yaw = yaw, withMean = withMean)
            return ev.onCycle(t - 300, 80f, 165f)
        }
    }

    @Test
    fun elbowDriftHasACoachingTierAndAStricterCountGate() {
        // §62c 후속 7: 카메라 쪽 팔 하나의 앞 성분, 본인 첫 3회 수축 중앙값 대비. 첫 3회는 기준을 이루므로 판정하지 않는다(유보 — 정상으로 세지 않는다)
        val ev = evaluator(); val f = Frames(ev)
        repeat(3) {
            val r = f.rep(ev = ev)                           // 수축 앞 성분 0.02 — 기준
            assertEquals(Verdict.ABSTAIN, r.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 앞 이탈" }.verdict); assertTrue(r.correct)
        }
        // 코칭 단계: +0.13 — 위반이지만 차단은 아니다(+0.20 미만) → 정확은 유지
        val cue = f.rep(fwd = 0.15f, ev = ev)
        val o = cue.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 앞 이탈" }
        assertEquals(Verdict.VIOLATION, o.verdict); assertFalse(o.gate); assertTrue(cue.correct)
        assertEquals(0.02f, o.reference!!, 1e-4f)
        val e = ev.eventFor(cue, 30_000L, gate = true)!!
        assertTrue(e.ship); assertFalse("코칭 단계 — 회를 빼지 않았다", e.gated)
        assertEquals("팔꿈치가 앞으로 나갔어요. 팔꿈치를 옆구리에 고정하세요.", e.message)
        // 차단 단계: +0.21 → 정확에서 빠진다
        val gated = f.rep(fwd = 0.23f, ev = ev)
        assertTrue(gated.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 앞 이탈" }.gate); assertFalse(gated.correct)
        // 뒤로(−)는 이 검사의 몫이 아니다 — 사선에서는 옆 벌림이 이 축에 '뒤로' 로 샌다
        assertEquals(Verdict.OK, f.rep(fwd = -0.30f, ev = ev).outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 앞 이탈" }.verdict)
    }

    @Test
    fun forwardDriftIsJudgedFromTheNearArmEvenWhenTheFarElbowIsHidden() {
        // 11:54 세트: 먼 팔꿈치가 몸통 뒤에 가려지면 양팔 평균이 없어 앞 이탈이 유보됐다 — 카메라 쪽 팔 하나로 판정한다
        val ev = evaluator(); val f = Frames(ev)
        repeat(3) { f.rep(withMean = false, ev = ev) }
        val r = f.rep(fwd = 0.23f, withMean = false, ev = ev)
        assertEquals(Verdict.VIOLATION, r.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 앞 이탈" }.verdict)
        assertFalse(r.correct)
    }

    @Test
    fun obliqueNearArmAwayFromTheBodyHasTwoTiers() {
        // §62c 후속 7(11:54 D 세트): 카메라 쪽 팔꿈치가 몸에서 떨어짐(옆 또는 뒤 — 사선에서는 못 가른다). 본인 첫 3회 수축 대비 +0.25 코칭, +0.40 차단
        val ev = evaluator(); val f = Frames(ev)
        repeat(3) { f.rep(latNear = 0.18f, ev = ev) }
        assertTrue(f.rep(latNear = 0.24f, ev = ev).correct)                        // +0.06 — 정상 흔들림
        val cue = f.rep(latNear = 0.45f, ev = ev)                                   // +0.27 — 코칭
        val o = cue.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 몸에서 떨어짐" }
        assertEquals(Verdict.VIOLATION, o.verdict); assertFalse(o.gate); assertTrue(cue.correct)
        assertEquals("팔꿈치가 몸에서 떨어졌어요. 팔꿈치를 옆구리에 붙이세요.", ev.eventFor(cue, 30_000L, gate = true)!!.message)
        val wide = f.rep(latNear = 0.62f, ev = ev)                                  // +0.44 — 차단(큰 벌림)
        assertTrue(wide.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 몸에서 떨어짐" }.gate); assertFalse(wide.correct)
    }

    @Test
    fun awayReferenceIsTheSecondLowestContractionSoFar() {
        // MM-Fit w19: 세트 첫 회가 −0.21 로 튀었다 — 그냥 최솟값이면 그 뒤 정상(0.05~0.12)이 전부 +0.26~+0.33 '떨어짐'. 두 번째로 작은 값은 튄 값 하나에 끌리지 않는다
        // (−0.21 은 이제 하한 −0.15 아래라 모음에 들어가지도 않는다 — 하한 위에서 한 번 튄 값(−0.14)도 두 번째 값이 버틴다)
        for (seq in listOf(listOf(-0.21f, 0.12f, 0.05f, 0.07f, 0.10f), listOf(-0.14f, 0.12f, 0.12f, 0.14f, 0.13f))) {
            val ev = evaluator(); val f = Frames(ev)
            for (x in seq) {
                val r = f.rep(latNear = x, ev = ev)
                assertTrue("$seq", r.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 몸에서 떨어짐" }.verdict != Verdict.VIOLATION)
            }
        }
        // 11:54: 초반에 벌렸어도(0.27·0.42) 붙인 반복(0.17·0.16·0.14)이 나오면 기준이 내려가 그 뒤 벌림(0.44)을 잡는다
        val ev2 = evaluator(); val f2 = Frames(ev2)
        for (x in listOf(0.17f, 0.27f, 0.42f, 0.45f, 0.17f, 0.16f, 0.14f)) f2.rep(latNear = x, ev = ev2)
        val flare = f2.rep(latNear = 0.44f, ev = ev2)
        val o = flare.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 몸에서 떨어짐" }
        assertEquals(Verdict.VIOLATION, o.verdict); assertEquals(0.16f, o.reference!!, 1e-4f)
    }

    @Test
    fun forwardDriftDoesNotDragTheAwayReferenceDown() {
        // §62c 후속 9(12:36·12:39 D 세트): 사선에서 팔꿈치가 앞으로 나가면 가까운 팔 가로가 음수로 샌다(화면 가로 = cos 요 × 바깥 − sin 요 × 앞).
        // 그 값이 두 번 나오자 '두 번째로 작은 수축' 기준이 끌려 내려가 그 뒤 정상 반복(0.16~0.29)이 전부 떨어짐·차단이 됐다(12:36 정확 15/30).
        // 같은 반복에서 앞 이탈이 위반이면 그 가로는 기준에 넣지 않는다 — 판정은 한다(앞으로 나간 반복의 가로는 낮게 읽혀 떨어짐으로는 안 걸린다)
        val away = "repform|덤벨 컬|팔꿈치 몸에서 떨어짐"; val fwd = "repform|덤벨 컬|팔꿈치 앞 이탈"
        val ev = evaluator(); val f = Frames(ev)
        for (x in listOf(0.18f, 0.20f, 0.16f)) f.rep(latNear = x, ev = ev)           // 앞 기준 0.02 · 떨어짐 기준 0.18
        repeat(2) {
            val drift = f.rep(fwd = 0.34f, latNear = -0.05f, ev = ev)                // 앞 +0.32 위반 — 가로 −0.05 는 하한 위라 앞 이탈 위반으로만 걸러진다
            assertEquals(Verdict.VIOLATION, drift.outcomes.first { it.check.id == fwd }.verdict)
            assertEquals(Verdict.OK, drift.outcomes.first { it.check.id == away }.verdict)
        }
        for (x in listOf(0.25f, 0.29f, 0.20f)) {
            val o = f.rep(latNear = x, ev = ev).outcomes.first { it.check.id == away }
            assertEquals("$x", Verdict.OK, o.verdict); assertEquals(0.18f, o.reference!!, 1e-4f)
        }
        // 그래도 진짜 떨어짐은 잡는다
        assertEquals(Verdict.VIOLATION, f.rep(latNear = 0.50f, ev = ev).outcomes.first { it.check.id == away }.verdict)
        // 앞 이탈이 기준을 모으는 첫 3회에는 위반이 안 나온다 — 하한 −0.15(AIHub 사선 정상 수축 p5~p10) 아래 원값은 '붙음' 이 아니라 누설·튐이라 넣지 않는다
        val ev2 = evaluator(); val f2 = Frames(ev2)
        for (x in listOf(-0.30f, -0.26f)) assertEquals(Verdict.ABSTAIN, f2.rep(latNear = x, ev = ev2).outcomes.first { it.check.id == away }.verdict)
        for (x in listOf(0.18f, 0.20f, 0.16f)) f2.rep(latNear = x, ev = ev2)
        val o2 = f2.rep(latNear = 0.29f, ev = ev2).outcomes.first { it.check.id == away }
        assertEquals(Verdict.OK, o2.verdict); assertEquals(0.18f, o2.reference!!, 1e-4f)
    }

    @Test
    fun awayRepsDoNotDragTheForwardReferenceBack() {
        // §62c 후속 9(12:41 D 세트) — 거꾸로도 샌다: 벌린 첫 두 회(가로 0.80·0.77, 절대 상한 0.72 위반)는 앞 성분이 '뒤로' 읽혀(−0.44) 첫 3회 중앙값 기준을
        // −0.44 로 끌었다 — 본인 정상(−0.15) 반복이 +0.29 '앞으로'(차단)로 읽힐 자리이고, 벌린 11회는 "앞으로 나갔어요" 로 읽혔다.
        // 같은 반복에서 떨어짐이 위반이면 그 앞 성분은 기준에 넣지 않는다
        val away = "repform|덤벨 컬|팔꿈치 몸에서 떨어짐"; val fwd = "repform|덤벨 컬|팔꿈치 앞 이탈"
        val ev = evaluator(); val f = Frames(ev)
        repeat(2) {
            val r = f.rep(fwd = -0.44f, latNear = 0.80f, ev = ev)
            assertEquals(Verdict.VIOLATION, r.outcomes.first { it.check.id == away }.verdict)
        }
        repeat(4) {
            val o = f.rep(fwd = -0.15f, latNear = 0.18f, ev = ev).outcomes.first { it.check.id == fwd }
            assertTrue("본인 정상 반복은 앞으로 읽히지 않는다", o.verdict != Verdict.VIOLATION)
        }
        val o = f.rep(fwd = -0.14f, latNear = 0.20f, ev = ev).outcomes.first { it.check.id == fwd }
        assertEquals(Verdict.OK, o.verdict); assertEquals(-0.15f, o.reference!!, 1e-4f)
    }

    @Test
    fun flaredFromTheStartIsNotTakenAsNormalFrontal() {
        // §62c 후속 9 — 정면: 팔을 늘어뜨린 자세부터 벌어져 있으면(이완 가로 0.33, AIHub 정상 p99 0.19) 기준을 0.19 로 자르고 한 번 알린다.
        // 수축 원값 0.45 이상은 기준과 무관하게 차단
        val ev = evaluator(); val f = Frames(ev)
        val r1 = f.rep(topLat = 0.33f, lat = 0.40f, ev = ev)                  // 시작부터 벌림: 기준 0.33 이면 +0.07 로 정상이었을 것
        val o1 = r1.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 옆 벌림" }
        assertEquals(0.19f, o1.reference!!, 1e-4f)
        assertEquals(Verdict.VIOLATION, o1.verdict); assertTrue("+0.21 이고 원값 0.40 ≥ 0.35 → 차단", o1.gate)
        assertNull("첫 두 회는 알리지 않는다 — 첫 상단은 덤벨 집기에 오염되기 쉽다", ev.takeNotice(1_000L))
        f.rep(topLat = 0.33f, lat = 0.40f, ev = ev); assertNull(ev.takeNotice(2_000L))
        f.rep(topLat = 0.33f, lat = 0.40f, ev = ev)                           // 셋째에도 이완이 벌어져 있다 → 한 번 알린다
        val n = ev.takeNotice(3_000L)!!
        assertEquals("처음부터 팔꿈치가 옆으로 벌어져 있어요. 팔을 내렸을 때 팔꿈치를 옆구리에 붙이고 해 주세요.", n.message)
        f.rep(topLat = 0.33f, lat = 0.40f, ev = ev)
        assertNull("세트에서 한 번", ev.takeNotice(4_000L))
        // 첫 상단만 오염(09:59 세트 0.50)이면 셋째에는 세트 최소가 내려와 알리지 않는다
        val evP = evaluator(); val fP = Frames(evP)
        fP.rep(topLat = 0.50f, lat = 0.15f, ev = evP); fP.rep(topLat = 0.12f, lat = 0.15f, ev = evP); fP.rep(topLat = 0.12f, lat = 0.15f, ev = evP)
        assertNull(evP.takeNotice(3_000L))
        // 절대 상한: 기준이 어떻든 수축 원값 0.46 은 차단
        val ev2 = evaluator(); val f2 = Frames(ev2)
        val wide = f2.rep(topLat = 0.12f, lat = 0.46f, ev = ev2)
        assertTrue(wide.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 옆 벌림" }.gate)
        // 띠 안의 사람(이완 0.15)은 자르지 않는다 — 알림도 없다
        val ev3 = evaluator(); val f3 = Frames(ev3)
        val ok = f3.rep(topLat = 0.15f, lat = 0.20f, ev = ev3)
        assertEquals(0.15f, ok.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 옆 벌림" }.reference!!, 1e-4f)
        assertNull(ev3.takeNotice(1_000L))
    }

    @Test
    fun flaredFromTheStartIsNotTakenAsNormalOblique() {
        // §62c 후속 9 — 사선 '몸에서 떨어짐': 세트 내내 벌려도(0.62) 기준을 모집단 p90(0.32)으로 잘라 +0.30 → 코칭, 0.75 는 +0.43 → 차단.
        // 기준을 모으는 첫 두 회도 원값 0.72 이상이면 차단
        val ev = evaluator(); val f = Frames(ev)
        val first = f.rep(latNear = 0.75f, ev = ev)
        val o = first.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 몸에서 떨어짐" }
        assertEquals("기준을 모으는 중에도 절대 상한은 판정", Verdict.VIOLATION, o.verdict); assertTrue(o.gate)
        f.rep(latNear = 0.62f, ev = ev)
        val third = f.rep(latNear = 0.62f, ev = ev)                             // 두 번째로 작은 값 0.62 → 0.32 로 자름
        val o3 = third.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 몸에서 떨어짐" }
        assertEquals(0.32f, o3.reference!!, 1e-4f); assertEquals(Verdict.VIOLATION, o3.verdict); assertFalse(o3.gate)
        assertEquals("처음부터 팔꿈치가 몸에서 떨어져 있어요. 옆구리에 붙이고 해 주세요.", ev.takeNotice(9_000L)!!.message)
        assertTrue(ev.summary().live.any { it.id == "repform|덤벨 컬|팔꿈치 몸에서 떨어짐#기준" })
        // 띠 안의 사람(0.18)은 그대로 — 그 뒤 0.45(+0.27)는 코칭
        val ev2 = evaluator(); val f2 = Frames(ev2)
        repeat(3) { f2.rep(latNear = 0.18f, ev = ev2) }
        val r = f2.rep(latNear = 0.45f, ev = ev2)
        assertEquals(0.18f, r.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 몸에서 떨어짐" }.reference!!, 1e-4f)
        assertNull(ev2.takeNotice(1_000L))
    }

    @Test
    fun firstRepsReferenceIsKeptPerView() {
        // 카메라 쪽 팔은 뷰마다 다르다(D = 왼팔, B = 오른팔) — 기준을 뷰별로 따로 세운다. D 에서 세운 기준으로 B 반복을 재면 다른 팔을 견준다
        val ev = evaluator(); val f = Frames(ev)
        repeat(3) { f.rep(latNear = 0.18f, yaw = -30f, ev = ev) }
        val d4 = f.rep(latNear = 0.50f, yaw = -30f, ev = ev)
        assertEquals("D", d4.view)
        assertEquals(Verdict.VIOLATION, d4.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 몸에서 떨어짐" }.verdict)
        val b1 = f.rep(latNear = 0.50f, yaw = 30f, ev = ev)
        assertEquals("B", b1.view)
        assertEquals("B 로 돌아서면 그 뷰의 첫 3회가 다시 기준", Verdict.ABSTAIN, b1.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 몸에서 떨어짐" }.verdict)
    }

    @Test
    fun sideViewReportsForwardAndBackAsBetaNotes() {
        // 옆(SIDE_B, 요 +60°): 앞/뒤가 화면 가로에 거의 그대로 — 따로 말할 수 있다. 미검증 뷰라 beta(화면 '참고' — 정확·음성 영향 없음)
        val ev = evaluator(); val f = Frames(ev)
        repeat(3) { f.rep(yaw = 60f, ev = ev) }
        val fwdRep = f.rep(fwd = 0.18f, yaw = 60f, ev = ev)
        assertEquals("SIDE_B", fwdRep.view)
        assertEquals(FormDirection.HIGH, fwdRep.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 앞뒤(옆)" }.direction)
        assertTrue(fwdRep.correct)
        assertEquals("사선 ship 검사는 옆에서 유보", Verdict.ABSTAIN, fwdRep.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 앞 이탈" }.verdict)
        val back = f.rep(fwd = -0.20f, yaw = 60f, ev = ev)
        assertEquals(FormDirection.LOW, back.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 앞뒤(옆)" }.direction)
        val e = ev.eventFor(back, 30_000L, gate = true)!!
        assertFalse(e.ship); assertEquals("팔꿈치가 뒤로 빠졌어요", e.message)
        val rule = RepFormSpecs.asRules().first { it.id == "repform|덤벨 컬|팔꿈치 앞뒤(옆)" }
        assertEquals("옆", rule.viewDesc)
    }

    @Test
    fun frontalFlareReferenceIgnoresAPickupPollutedFirstTop() {
        // 09:59 세트: 첫 상단 창(덤벨 집기)의 가로가 0.50 이라 시작 기준이면 벌린 반복(0.36~0.42)이 음수로 읽혔다 — 세트에서 가장 붙어 있던 이완 자세 대비로
        val ev = evaluator(); val f = Frames(ev)
        f.rep(topLat = 0.50f, lat = 0.15f, ev = ev)
        f.rep(topLat = 0.12f, lat = 0.15f, ev = ev)
        val flare = f.rep(topLat = 0.12f, lat = 0.42f, ev = ev)
        val o = flare.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 옆 벌림" }
        assertEquals(Verdict.VIOLATION, o.verdict); assertTrue(o.gate); assertEquals(0.12f, o.reference!!, 1e-4f)
    }

    @Test
    fun aShipReasonIsSpokenEvenWhenAnEarlierBetaCheckAlsoFlagged() {
        // 검사 순서상 beta('팔꿈치 높이 상승')가 ship('팔꿈치 앞 이탈')보다 앞이어도 회를 빼는 ship 사유를 말한다 — 빠진 회를 침묵하면 카운트가 죽은 줄 안다
        val ev = evaluator(); val f = Frames(ev)
        repeat(3) { f.rep(ev = ev) }
        val r = f.rep(fwd = 0.23f, rise = -0.25f, ev = ev)
        assertTrue(r.flagged.any { it.check.id == "repform|덤벨 컬|팔꿈치 높이 상승" })
        val e = ev.eventFor(r, 30_000L, gate = true)!!
        assertEquals("repform|덤벨 컬|팔꿈치 앞 이탈", e.check.id); assertTrue(e.gated)
    }

    @Test
    fun forwardLeanIsSpokenAndDropsTheRepLikeTheSquat() {
        // 스쿼트 '상체 숙임' 과 같은 1단 정책: 위반 = 음성 + COACH 에서 그 회를 세지 않음
        val ev = evaluator(); val f = Frames(ev)
        assertTrue(f.rep(ev = ev).correct)                  // 그 반복 시작 기울기 5°
        val lean = f.rep(incl = 32f, ev = ev)              // +27°
        val o = lean.outcomes.first { it.check.id == "repform|덤벨 컬|상체 숙임" }
        assertEquals(Verdict.VIOLATION, o.verdict); assertTrue(o.gate); assertFalse(lean.correct)
        val e = ev.eventFor(lean, 60_000L, gate = true)!!
        assertEquals("repform|덤벨 컬|상체 숙임", e.check.id); assertTrue(e.gated)
        assertEquals("상체가 많이 숙여졌어요. 가슴을 들고 몸통을 세운 채 팔만 움직이세요.", e.message)
        assertTrue(f.rep(incl = 20f, ev = ev).correct)     // +15° — 폰 정상 p95(+13°) 근처는 통과
        val squat = RepFormSpecs.byExercise.getValue("바벨 스쿼트").first { it.id == "repform|바벨 스쿼트|상체 숙임" }
        val curlLean = curl.first { it.id == "repform|덤벨 컬|상체 숙임" }
        assertEquals(squat.feature, curlLean.feature); assertEquals(squat.status, curlLean.status)
        assertNull("스쿼트처럼 1단", curlLean.gateHi)
    }

    @Test
    fun eachRepIsGatedByItsOwnWindowView() {
        // 반복마다 그 창의 방향으로 거른다(§62c 후속 6) — 사선(B, yaw +30°)으로 한 회는 정면 전용 '옆 벌림' 을 유보(회를 빼지도 칠하지도 않음)하고,
        // 정면으로 돌아온 회는 다시 판정한다. 세트 누적 뷰는 옆으로 돌아선 구간에 끌려가 그 뒤 정면 반복까지 유보시켰다(11:14 세트)
        val ev = evaluator(); val f = Frames(ev)
        assertEquals("C", f.rep(yaw = 0f, ev = ev).view)
        val oblique = f.rep(lat = 0.60f, yaw = 30f, ev = ev)
        val o = oblique.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 옆 벌림" }
        assertEquals(Verdict.ABSTAIN, o.verdict); assertEquals("촬영 방향 · B", o.abstainReason); assertTrue(oblique.correct); assertEquals("B", oblique.view)
        val front = f.rep(lat = 0.60f, yaw = 2f, ev = ev)
        assertEquals("C", front.view); assertFalse(front.correct)
        // 세트 결과: 반복 뷰가 있으면 세트 뷰로 다시 거르지 않는다 — 방향이 섞인 세트를 통째로 유보하지 않는다
        val rule = RepFormSpecs.asRules().first { it.id == "repform|덤벨 컬|팔꿈치 옆 벌림" }
        assertTrue(ev.summary().ruleResult(rule, viewLetter = "B")!!.verdict != Verdict.ABSTAIN)
        // 반복 뷰가 없는 세트(방향 피처 없음)는 종전처럼 세트 뷰로 거른다
        val ev2 = evaluator(); val f2 = Frames(ev2)
        repeat(3) { f2.rep(ev = ev2) }
        assertEquals(Verdict.ABSTAIN, ev2.summary().ruleResult(rule, viewLetter = "B")!!.verdict)
    }

    @Test
    fun leaningThroughTheWholeRepIsCaughtAgainstTheMostUprightTopOfTheSet() {
        // 11:14 세트: 숙인 채 컬을 하면 그 반복의 시작부터 숙어 있다 — 그 반복 시작 대비(REP_DELTA)는 −2~+3° 로 통과했다. 세트에서 가장 곧았던 상단 대비로 잰다
        val ev = evaluator(); val f = Frames(ev)
        assertTrue(f.rep(ev = ev).correct)                                   // 상단 5° — 세트 최소
        val lean = f.rep(incl = 36f, topIncl = 38f, ev = ev)                 // 시작부터 숙임: 창 최대 38 − 5 = +33
        val o = lean.outcomes.first { it.check.id == "repform|덤벨 컬|상체 숙임" }
        assertEquals(Verdict.VIOLATION, o.verdict); assertEquals(33f, o.value!!, 0.01f); assertEquals(5f, o.reference!!, 0.01f)
        assertFalse(lean.correct)
        assertTrue("다시 서면 통과", f.rep(ev = ev).correct)
    }

    @Test
    fun aPickupPollutedFirstTopDoesNotBlindTheCheck() {
        // 09:59·15:33 세트: 첫 상단 창에 덤벨을 집으려 숙인 프레임(45°)이 들어갔다 — 세트 시작 기준이면 그 뒤 모든 회가 음수로 읽혀 영영 못 걸린다.
        // 세트 최소는 다음 반복의 곧은 상단(8°)으로 내려간다
        val ev = evaluator(); val f = Frames(ev)
        val first = f.rep(incl = 10f, topIncl = 45f, ev = ev)
        assertEquals(Verdict.OK, first.outcomes.first { it.check.id == "repform|덤벨 컬|상체 숙임" }.verdict)
        assertTrue(f.rep(incl = 10f, topIncl = 8f, ev = ev).correct)
        val lean = f.rep(incl = 38f, topIncl = 35f, ev = ev)                 // 38 − 8 = +30
        assertFalse(lean.correct)
    }

    @Test
    fun holdingALeanWithoutCurlingIsSpokenLikeTheSquatWindowRule() {
        // 11:14 세트 58~73 s: 숙인 채 16초 멈춰 있었다 — 반복이 끝나지 않아 반복 판정이 오지 않았다. 팔을 내린 채 약 4초(14프레임) 숙여 있으면 말한다
        val ev = evaluator(); val f = Frames(ev)
        repeat(14) { f.frame(165f, incl = 40f) }
        assertNull("첫 반복 전(기준 없음) — 덤벨을 집으려 숙인 자세는 지적하지 않는다", ev.liveEvent(f.t))
        f.rep(ev = ev)                                                          // 기준(세트 최소 5°)
        repeat(13) { f.frame(165f, incl = 40f) }
        assertNull("4초가 안 됐다", ev.liveEvent(f.t))
        f.frame(165f, incl = 40f)
        val e = ev.liveEvent(f.t)!!
        assertEquals("repform|덤벨 컬|상체 숙임", e.check.id); assertFalse("반복이 아니다 — 세지 않음과 무관", e.gated)
        assertEquals("상체가 숙여져 있어요. 가슴을 들고 몸통을 세운 채 팔만 움직이세요.", e.message)
        f.frame(165f, incl = 40f)
        assertNull("쿨다운", ev.liveEvent(f.t))
        assertEquals(1, ev.summary().live.size)
        // 팔을 굽히는 중이면(반복 중) 유지 사건이 아니다 — 그 회는 반복 끝 판정이 말한다
        val ev2 = evaluator(); val f2 = Frames(ev2)
        f2.rep(ev = ev2)
        repeat(13) { f2.frame(165f, incl = 40f) }; f2.frame(100f, incl = 40f)
        assertNull(ev2.liveEvent(f2.t))
        // 옆으로 돌아서(SIDE, yaw 60°) 숙이면 검사의 뷰 밖 — 말하지 않는다
        val ev3 = evaluator(); val f3 = Frames(ev3)
        f3.rep(yaw = 0f, ev = ev3)
        repeat(14) { f3.frame(165f, incl = 40f, yaw = 60f) }
        assertNull(ev3.liveEvent(f3.t))
        // 팔을 내린 채 60° 넘게 숙임 = 덤벨을 내려놓는 중 — 말하지 않는다
        val ev4 = evaluator(); val f4 = Frames(ev4)
        f4.rep(ev = ev4)
        repeat(14) { f4.frame(165f, incl = 80f) }
        assertNull(ev4.liveEvent(f4.t))
    }

    @Test
    fun violatedPartsArePaintedRedForShipAndProvisionalForBeta() {
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)
        val (red, prov) = RuleHighlight.forRepForm(f.rep(incl = 40f, gap = 1.9f, ev = ev).outcomes)
        assertEquals("허리 → 어깨·골반", setOf(11, 12, 23, 24), red)
        assertEquals("벌어짐(beta) → 팔꿈치, 참고 색", setOf(13, 14), prov)
        val (red2, _) = RuleHighlight.forRepForm(f.rep(lat = 0.60f, ev = ev).outcomes)
        assertEquals("옆 벌림 → 어깨·팔꿈치", setOf(11, 12, 13, 14), red2)
        val (red3, prov3) = RuleHighlight.forRepForm(f.rep(ev = ev).outcomes)
        assertTrue(red3.isEmpty() && prov3.isEmpty())
    }

    @Test
    fun torsoSwingAndFlareAreBetaScreenOnly() {
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)
        val back = f.rep(tilt = -0.15f, ev = ev)           // 올릴 때 뒤로 젖힘
        val t = back.flagged.single()
        assertEquals("repform|덤벨 컬|몸통 반동", t.check.id); assertEquals(FormDirection.LOW, t.direction); assertTrue(back.correct)
        val flare = f.rep(gap = 1.9f, ev = ev)
        assertEquals("repform|덤벨 컬|팔꿈치 벌어짐", flare.flagged.single().check.id); assertTrue(flare.correct)
        assertEquals("팔꿈치가 옆으로 벌어졌어요", ev.eventFor(flare, 90_000L)!!.message)
    }

    @Test
    fun curlRulesExpectObliqueViewsAndAbstainElsewhere() {
        assertEquals(setOf("B", "C", "D", "SIDE_B", "SIDE_D"), RepFormSpecs.viewsFor("덤벨 컬"))   // 옆은 앞뒤(beta)만 — 규칙별 뷰는 반복·ruleResult 가 가른다
        assertEquals(setOf("C"), RepFormSpecs.viewsFor("바벨 스쿼트"))
        val rules = RepFormSpecs.asRules().filter { it.exercise == "덤벨 컬" }
        assertEquals(setOf("B", "D"), rules.first { it.id == "repform|덤벨 컬|팔꿈치 앞 이탈" }.viewsOk)
        assertEquals(5, rules.count { it.status == RuleStatus.SHIP })   // 상체 숙임·뜸·옆 벌림·앞 이탈·몸에서 떨어짐
        val ev = evaluator(); val f = Frames(ev)
        repeat(3) { f.rep(ev = ev) }; f.rep(fwd = 0.23f, ev = ev); f.rep(ev = ev)
        val drift = rules.first { it.id == "repform|덤벨 컬|팔꿈치 앞 이탈" }
        val res = ev.summary().ruleResult(drift, viewLetter = "C")!!
        assertEquals(Verdict.ABSTAIN, res.verdict)
        assertEquals("촬영 방향 · 앞 비스듬히 아님", res.abstainReason)
        assertEquals(Verdict.OK, ev.summary().ruleResult(drift, viewLetter = "D")!!.verdict)   // 2회 중 1회 < max(2, 34 %) — 세트 규약
    }

    @Test
    fun setLevelCurlResultUsesPerRuleViews() {
        val ev = evaluator(); val f = Frames(ev)
        repeat(3) { f.rep(ev = ev) }; f.rep(ev = ev); f.rep(fwd = 0.23f, ev = ev); f.rep(fwd = 0.23f, gap = 1.9f, ev = ev)
        val rules = RepFormSpecs.asRules().filter { it.exercise == "덤벨 컬" }.associateBy { it.id }
        val s = ev.summary()
        // 정면(C): 앞 이탈·몸통은 유보, 벌어짐(B/C/D)은 판정한다
        assertEquals(Verdict.ABSTAIN, s.ruleResult(rules.getValue("repform|덤벨 컬|팔꿈치 앞 이탈"), viewLetter = "C")!!.verdict)
        assertEquals(Verdict.ABSTAIN, s.ruleResult(rules.getValue("repform|덤벨 컬|몸통 반동"), viewLetter = "C")!!.verdict)
        assertEquals(Verdict.OK, s.ruleResult(rules.getValue("repform|덤벨 컬|팔꿈치 벌어짐"), viewLetter = "C")!!.verdict)   // 6회 중 1회 < max(2, 34 %)
        // 사선(D): 앞 이탈 3회 중 2회 위반 → 세트 위반
        assertEquals(Verdict.VIOLATION, s.ruleResult(rules.getValue("repform|덤벨 컬|팔꿈치 앞 이탈"), viewLetter = "D")!!.verdict)
    }

    @Test
    fun frontalFlareHasTwoTiersAndLiftedElbowIsGated() {
        // 2026-09-26 실기기 13회(정면 C): 벌림 4회 lat 0.44~0.46(시작 0.11~0.13), 반동 3회 손목 최고 +0.20~+0.24, 정상 lat 0.12~0.18·손목 −0.07~−0.12
        val ev = evaluator(); val f = Frames(ev)
        val r1 = f.rep(ev = ev)
        assertTrue(r1.correct)
        // 코칭 단계(+0.15 이고 ≥ 0.30 — 원값 0.31): 위반이지만 차단은 아니다(+0.20·0.35 미만)
        val cue = f.rep(lat = 0.31f, ev = ev)
        val o = cue.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 옆 벌림" }
        assertEquals(Verdict.VIOLATION, o.verdict); assertFalse(o.gate); assertTrue(cue.correct)
        // 차단 단계(실기기 0.45): 정확에서 빠진다
        val wide = f.rep(lat = 0.45f, ev = ev)
        val ow = wide.outcomes.first { it.check.id == "repform|덤벨 컬|팔꿈치 옆 벌림" }
        assertTrue(ow.gate); assertFalse(wide.correct)
        assertEquals("팔꿈치가 옆으로 벌어졌어요. 팔꿈치를 옆구리에 붙이세요.", ev.eventFor(wide, 30_000L, gate = true)!!.message)
        // 반동: 손목이 어깨 위(+0.22) → '팔꿈치 뜸' ship 위반 = 차단(1단). 보조 '높이 상승' 은 beta
        val swing = f.rep(wrist = 0.22f, rise = -0.25f, ev = ev)
        val ids = swing.flagged.map { it.check.id }
        assertTrue(ids.contains("repform|덤벨 컬|팔꿈치 뜸")); assertTrue(ids.contains("repform|덤벨 컬|팔꿈치 높이 상승"))
        assertFalse(swing.correct)
        val e = ev.eventFor(swing, 60_000L, gate = true)!!
        assertEquals("repform|덤벨 컬|팔꿈치 뜸", e.check.id); assertTrue(e.gated)
        // 정면 검사는 사선 뷰에서 유보, 앞 이탈은 정면에서 유보 — 검사별 뷰
        val rules = RepFormSpecs.asRules().filter { it.exercise == "덤벨 컬" }.associateBy { it.id }
        val sm = ev.summary()
        assertEquals(Verdict.ABSTAIN, sm.ruleResult(rules.getValue("repform|덤벨 컬|팔꿈치 옆 벌림"), viewLetter = "D")!!.verdict)
        assertEquals(Verdict.ABSTAIN, sm.ruleResult(rules.getValue("repform|덤벨 컬|팔꿈치 뜸"), viewLetter = "B")!!.verdict)
        assertTrue(sm.ruleResult(rules.getValue("repform|덤벨 컬|팔꿈치 옆 벌림"), viewLetter = "C")!!.verdict != Verdict.ABSTAIN)
    }

    @Test
    fun curlWindowRulesAreDemotedBecauseRepChecksReplaceThem() {
        val elbow = PostureRule("덤벨 컬|팔꿈치 위치 고정", "덤벨 컬", "팔꿈치 위치 고정", null, RuleStatus.SHIP, null,
            "elbow_torso_R__mean", "elbow_torso_R", "mean", "world", ">", 0.193f, "D", "앞 비스듬히", .9f, .8f, 56, false, emptyList())
        val spine = elbow.copy(id = "덤벨 컬|척추의 중립[all]", condition = "척추의 중립", subtype = "all", feature = "head_pitch__mean", baseFeature = "head_pitch", op = "<", threshold = -19.9f)
        val merged = PostureRuleSet("mp_v0.1", "", listOf(elbow, spine)).plusRepForm()
        assertEquals(RuleStatus.BETA, merged.rules.first { it.id == elbow.id }.status)
        assertEquals(RuleStatus.BETA, merged.rules.first { it.id == spine.id }.status)
    }
}
