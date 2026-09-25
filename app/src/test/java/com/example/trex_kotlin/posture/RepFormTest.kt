package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 반복별 자세 검사(spec §62a, 설계 §21) — 2026-09-25 실기기 두 세트의 재현.
 * 사이클 창을 위상(시작·상단·바닥·사이클)으로 자르고, 시작 자세 대비 상대 검사가 발 너비·발끝을 잡되 무릎(바닥 정상)은 지적하지 않는지,
 * ship 위반만 '정확' 을 깎는지, 기각된 사이클이 다음 창을 더럽히지 않는지를 잠근다.
 */
class RepFormTest {

    private val squat = RepFormSpecs.byExercise.getValue("바벨 스쿼트")
    private fun evaluator() = RepFormEvaluator(squat, "knee_mean", 35f)

    private class Frames(private val ev: RepFormEvaluator) {
        var t = 0L
        fun frame(knee: Float, stance: Float = 1.0f, toe: Float = 20f, kneeOut: Float = 0.15f, torso: Float = 5f, maxside: Float = knee + 3f) {
            ev.onFrame(t, mapOf("knee_mean" to knee, "knee_maxside" to maxside, "stance_2d" to stance, "toe_out_maxside" to toe,
                "knee_out_mean" to kneeOut, "torso_incl" to torso))
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
        assertEquals(1.0f, ev.baseline!!.getValue("stance_2d"), 1e-4f)
        assertEquals(20f, ev.baseline!!.getValue("toe_out_maxside"), 1e-4f)
        // 시작 자세 검사(절대 띠) 두 개가 한 번 판정됐다 — 둘 다 정상
        assertEquals(2, ev.startOutcomes.size)
        assertTrue(ev.startOutcomes.all { it.verdict == Verdict.OK })
        // 첫 반복: 자기 자신이 기준이라 상대 검사는 정상, 바닥 무릎 0.15 정상 → 정확
        assertTrue(r1.correct); assertTrue(r1.flagged.isEmpty())
        // 발을 넓힌(×1.5) 반복 — 발 너비만 걸리고 무릎은 걸리지 않는다(서 있는 프레임 중 가장 벗어난 값 — 픽스처는 반복 내내 같은 값)
        val r2 = f.rep(stance = 1.6f, ev = ev)
        assertEquals(listOf("repform|바벨 스쿼트|발 간격"), r2.flagged.map { it.check.id })
        assertEquals(FormDirection.HIGH, r2.flagged[0].direction)
        assertEquals(1.6f, r2.flagged[0].value!!, 1e-3f)
        assertFalse(r2.outcomes.any { it.check.id.contains("무릎") && it.verdict == Verdict.VIOLATION })
        assertTrue(r2.correct)   // beta — 정확은 깎이지 않는다(원칙 #2)
        // 발을 넓히고 발끝 값도 +18° 인 반복 — 발 너비만 걸리고 발끝은 **유보**(§21.9: 넓게 서면 발끝 그대로여도 +20~28° 로 읽힌다, 사용자 확인)
        val r3 = f.rep(stance = 1.6f, toe = 38f, ev = ev)
        assertEquals(listOf("repform|바벨 스쿼트|발 간격"), r3.flagged.map { it.check.id })
        val toeAb = r3.outcomes.first { it.check.id == "repform|바벨 스쿼트|발끝 방향" }
        assertEquals(Verdict.ABSTAIN, toeAb.verdict); assertEquals("발 너비 위반으로 측정 무효", toeAb.abstainReason)
        // 좁힘·안쪽도 방향을 갖는다 (띠 0.6~1.5 · ±15°)
        val r4 = f.rep(stance = 0.5f, ev = ev)
        assertEquals(listOf(FormDirection.LOW), r4.flagged.map { it.direction })
        assertEquals(FormDirection.LOW, f.rep(toe = 0f, ev = ev).flagged.single().direction)
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
        val deep = f.rep(torsoMax = 39f, ev = ev)    // 깊은 정상 반복(09:52 세트 2회) — 34° 는 띠 안
        assertTrue(deep.flagged.isEmpty())
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
        f.rep(stance = 2.0f, toe = 50f, ev = ev)   // 시작 띠 0.5~1.8 · −5~45°
        val start = ev.startOutcomes.associateBy { it.check.id }
        assertEquals(FormDirection.HIGH, start.getValue("repform|바벨 스쿼트|발 간격|시작").direction)
        assertEquals(FormDirection.HIGH, start.getValue("repform|바벨 스쿼트|발끝 방향|시작").direction)
        assertTrue(ev.summary().ruleResult(RepFormSpecs.asRules().first { it.id == "repform|바벨 스쿼트|발 간격|시작" })!!.measurement!!.contains("발이 어깨보다 많이 넓어요"))   // 시작 자세 문장은 규칙 행에
    }

    @Test
    fun shipEventsNeedTwoConsecutiveViolationsAndBetaEventsAreScreenOnly() {
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
        val wide = f.rep(stance = 1.6f, ev = ev)
        val evB = ev.eventFor(wide, 60_000L)
        assertNotNull(evB); assertFalse(evB!!.ship)
        assertEquals("발 너비가 시작보다 넓어졌어요", evB.message)
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
        assertTrue(stance.measurement!!.startsWith("참고 · 발 너비"))
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
        assertTrue(s.lines().any { it == "정확 6 / 6회" })
        assertFalse("시작 자세는 규칙 행에 있다 — 요약 줄에 중복하지 않는다", s.lines().any { it.startsWith("시작 자세") })
    }

    @Test
    fun standingFramesAtTheEndOfARepCarryOverToTheNextTopWindow() {
        // 11:37 세트: 쉬지 않고 이어 하면 직전 반복의 복귀 뒤 서 있던 프레임이 1개뿐 — 그 프레임들은 직전 창에 있었다. 이월해야 상단이 선다
        val ev = evaluator(); val f = Frames(ev)
        f.rep(ev = ev)                                   // 끝에 158·163 두 프레임이 서 있음(≥ 최대 − 7.7)
        val next = f.rep(standFrames = 0, ev = ev)       // 바로 하강 — 상단은 직전 창의 꼬리에서
        assertEquals(Verdict.OK, next.outcomes.first { it.check.id == "repform|바벨 스쿼트|발끝 방향" }.verdict)
        assertEquals(0, ev.summary().noTop)
        // 기각 뒤에는 이월하지 않는다(기각 창의 끝이 서 있던 프레임인지 모른다) — 상단은 없지만(noTop) 복귀 뒤 서 있는 프레임으로는 판정한다
        f.frame(163f); ev.onRejected(f.t - 300)
        val after = f.rep(standFrames = 0, ev = ev)
        assertEquals(1, ev.summary().noTop)
        assertEquals(Verdict.OK, after.outcomes.first { it.check.id == "repform|바벨 스쿼트|발끝 방향" }.verdict)
        // 반복 사이에 서서 발을 옮기면 이월분(옛 자세)은 버린다 — 돌아온 첫 반복이 옛 자세로 헛경보가 나면 안 된다
        f.rep(stance = 1.8f, ev = ev)                    // 넓게 한 반복(끝에 넓은 채로 서 있음)
        val back = f.rep(stance = 1.0f, ev = ev)         // 발을 되돌리고 5프레임 서 있다가 반복
        assertEquals(Verdict.OK, back.outcomes.first { it.check.id == "repform|바벨 스쿼트|발 간격" }.verdict)
    }

    @Test
    fun wideningDuringTheDescentShowsInTheStandingFramesAfterTheRep() {
        // 11:37 세트 3회: 하강을 시작하며 발을 벌림 — 하강 직전 상단은 ×1.12, 복귀 뒤 서 있는 프레임은 ×1.6~1.7. 바닥값(부풀림)은 쓰지 않는다
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
        assertFalse(two.correct)   // 정확은 여전히 ship 무릎 검사가 깎는다
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
        assertEquals("mp_v0.1+repform_v0.1", merged.version)
        assertEquals(RuleStatus.BETA, merged.rules.first { it.id == knee.id }.status)
        assertEquals(RuleStatus.SHIP, merged.rules.first { it.id == spine.id }.status)
        val added = merged.rules.filter { it.kind == "rep_form" }
        assertEquals(squat.size, added.size)
        assertTrue(added.all { it.exercise == "바벨 스쿼트" && it.viewsOk == setOf("C") })
        assertEquals(1, added.count { it.status == RuleStatus.SHIP })
        // 범위 문장: 무릎은 계속 '봄'(반복 검사 ship), 발 너비·발끝·상체는 '검증 중'
        val scope = PostureScope.of(merged, "바벨 스쿼트")
        assertTrue(scope.watched.containsAll(listOf("등·허리", "무릎")))
        assertTrue(scope.provisional.containsAll(listOf("발 너비", "발끝", "상체")))
        assertFalse(scope.provisional.contains("무릎"))
        // 창 규칙 평가는 rep_form 을 유보로 두고(시간·반복 측정 필요), 세트 평가가 요약으로 채운다
        val agg = FeatureAggregator()
        repeat(10) { agg.add(mapOf("knee_out_mean" to 0.1f, "torso_incl" to 5f, "stance_sh" to 1f)) }
        val res = merged.evaluate("바벨 스쿼트", agg, true, 8, null).associateBy { it.rule.id }
        assertEquals(Verdict.ABSTAIN, res.getValue("repform|바벨 스쿼트|발 간격").verdict)
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
