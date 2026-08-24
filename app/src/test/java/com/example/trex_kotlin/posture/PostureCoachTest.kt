package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실시간 코칭: 처음부터(HABIT) / 점점(DRIFT) / 교정됨(RECOVERED) 분류, 발화 억제, 문구 카탈로그. */
class PostureCoachTest {

    private fun rule(id: String, condition: String, feature: String, op: String, thr: Float, auc: Float = 0.9f, subtype: String? = null): PostureRule {
        val i = feature.lastIndexOf("__")
        return PostureRule(
            id = id, exercise = "바벨 스쿼트", condition = condition, subtype = subtype, status = RuleStatus.SHIP, reason = null,
            feature = feature, baseFeature = feature.substring(0, i), stat = feature.substring(i + 2), family = "x", op = op, threshold = thr,
            view = "C", viewDesc = "", cvAuc = auc, cvBalacc = 0.8f, sampleN = 60, mirrorSafe = true, cautions = emptyList(),
        )
    }

    // 발과 무릎 방향: knee_out_mean__mean < 0.01 이면 위반(무릎 모임). 몸통 피치: torso_pitch__min < -20 이면 위반(뒤로 젖힘)
    private val kneeRule = rule("스쿼트|무릎", "발과 무릎의 방향 일치", "knee_out_mean__mean", "<", 0.01f, auc = 0.97f)
    private val torsoRule = rule("스쿼트|상체", "상체 과도한 젖힘 없음", "torso_pitch__min", "<", -20f, auc = 0.9f)
    private val rs = PostureRuleSet("t", "d", listOf(kneeRule, torsoRule))

    private fun frame(kneeOut: Float, torsoPitch: Float) = mapOf("knee_out_mean" to kneeOut, "torso_pitch" to torsoPitch)

    @Test
    fun habitFromTheStartIsSpokenOncePersistent() {
        val c = LiveCoach(rs, "바벨 스쿼트", windowFrames = 8, minFrames = 8, persistence = 2, ruleCooldownMs = 12_000, globalGapMs = 4_000)
        var t = 0L
        // 8프레임 전에는 평가 없음
        repeat(7) { c.onFrame(frame(-0.05f, 0f)); t += 300; assertNull(c.evaluate(t)) }
        c.onFrame(frame(-0.05f, 0f)); t += 300
        assertNull(c.evaluate(t))                      // streak 1 < persistence 2
        c.onFrame(frame(-0.05f, 0f)); t += 300
        val ev = c.evaluate(t)
        assertNotNull(ev)
        assertEquals(OnsetKind.HABIT, ev!!.kind)
        assertEquals("발과 무릎의 방향 일치", ev.rule.condition)
        assertTrue(ev.message.startsWith("처음부터"))
        assertTrue(ev.message.contains("무릎"))
        // 쿨다운: 곧바로 다시는 안 말함
        c.onFrame(frame(-0.05f, 0f)); t += 300
        assertNull(c.evaluate(t))
        // 상태 스냅샷은 유지
        assertEquals(OnsetKind.HABIT, c.lastStates.first { it.rule == kneeRule }.kind)
        assertNull(c.lastStates.first { it.rule == torsoRule }.kind)
    }

    @Test
    fun driftIsDetectedWhenEarlyWindowWasFine() {
        val c = LiveCoach(rs, "바벨 스쿼트", windowFrames = 8, minFrames = 8, persistence = 2, ruleCooldownMs = 12_000, globalGapMs = 0)
        var t = 0L
        repeat(8) { c.onFrame(frame(0.05f, 0f)); t += 300 }     // 초반 8프레임 정상
        assertNull(c.evaluate(t))
        // 점점 무릎이 모임 (최근 창이 위반으로 넘어감)
        var ev: CoachEvent? = null
        repeat(12) {
            c.onFrame(frame(-0.06f, 0f)); t += 300
            ev = c.evaluate(t) ?: ev
        }
        assertNotNull(ev)
        assertEquals(OnsetKind.DRIFT, ev!!.kind)
        assertTrue(ev!!.message.contains("점점"))
        val st = c.lastStates.first { it.rule == kneeRule }
        assertEquals(Verdict.OK, st.early)
        assertEquals(Verdict.VIOLATION, st.recent)
        assertEquals("점점 흐트러짐", st.label)
        // 요약도 DRIFT
        val sum = c.summarize().first { it.rule == kneeRule }
        assertEquals(OnsetKind.DRIFT, sum.kind)
    }

    @Test
    fun recoveredIsAnnouncedAfterCorrection() {
        val c = LiveCoach(rs, "바벨 스쿼트", windowFrames = 8, minFrames = 8, persistence = 1, ruleCooldownMs = 0, globalGapMs = 0)
        var t = 0L
        repeat(8) { c.onFrame(frame(-0.05f, 0f)); t += 300 }
        val first = c.evaluate(t)
        assertEquals(OnsetKind.HABIT, first!!.kind)
        // 교정: 최근 창이 정상으로
        var rec: CoachEvent? = null
        repeat(9) {
            c.onFrame(frame(0.06f, 0f)); t += 300
            val e = c.evaluate(t)
            if (e?.kind == OnsetKind.RECOVERED) rec = e
        }
        assertNotNull(rec)
        assertTrue(rec!!.message.contains("교정"))
        // 교정됨은 한 번만
        var again = 0
        repeat(5) { c.onFrame(frame(0.06f, 0f)); t += 300; if (c.evaluate(t)?.kind == OnsetKind.RECOVERED) again++ }
        assertEquals(0, again)
    }

    @Test
    fun picksMostPersistentViolationAndRespectsGlobalGap() {
        val c = LiveCoach(rs, "바벨 스쿼트", windowFrames = 8, minFrames = 8, persistence = 1, ruleCooldownMs = 100_000, globalGapMs = 4_000)
        var t = 0L
        // 무릎은 처음부터 위반, 상체는 나중에 위반
        repeat(8) { c.onFrame(frame(-0.05f, 0f)); t += 300 }
        val e1 = c.evaluate(t)
        assertEquals(kneeRule, e1!!.rule)
        // 상체도 위반으로 — 전역 간격 4초 안에는 말하지 않음
        repeat(8) { c.onFrame(frame(-0.05f, -40f)); t += 300 }
        // 마지막 evaluate 시점은 e1 + 2.4s → 전역 간격 미충족
        assertNull(c.evaluate(t))
        t += 4_000
        c.onFrame(frame(-0.05f, -40f))
        val e2 = c.evaluate(t)
        assertNotNull(e2)
        assertEquals(torsoRule, e2!!.rule)         // 무릎은 쿨다운 중이라 상체가 선택됨
        assertEquals(OnsetKind.DRIFT, e2.kind)     // 초반 창에서는 상체 정상이었음
    }

    @Test
    fun cueCatalogCoversKnownConditionsAndFallsBack() {
        val known = listOf("발과 무릎의 방향 일치", "발바닥 지면 고정", "고개 정면", "무릎 반동 없음", "상체 반동 없음", "바벨 궤적과 몸 밀착",
            "손목의 중립", "무릎과 골반이 동시에 펴짐", "전완 지면과 수직", "견갑대 고정", "앞다리 무릎 각도 90도", "뒤다리 무릎 각도 90도",
            "상체 살짝 숙임 유지", "수축 시 고개 안 젖힘", "이완 시 팔꿈치 각도 90도", "팔꿈치 위치 고정", "팔꿈치 살짝 구부린채 고정",
            "양 손이 머리 뒤에 위치", "무릎 충분히 올라오고", "두 다리 사이 모아줌 유지", "어깨와 귀 사이 적당한 거리 유지", "상완의 외회전",
            "수축시 양 손과 이마 동일선상 위치", "팔꿈치가 손목 리드", "시선 위쪽 유지", "상체 과도한 젖힘 없음", "상체의 과조한 숙임/젖힘 여부")
        for (cnd in known) {
            val cue = CoachCues.cueFor(rule("x", cnd, "knee_mean__mean", "<", 0f))
            assertFalse("카탈로그 누락: $cnd", cue.habit.contains("조건을 벗어나"))
            assertTrue(cue.habit.startsWith("처음부터"))
            assertTrue(cue.drift.contains("점점"))
        }
        // 척추 하위유형
        val flex = CoachCues.cueFor(rule("x", "척추의 중립", "head_pitch__mean", "<", 0f, subtype = "flexion"))
        assertTrue(flex.habit.contains("등이 말려"))
        val lat = CoachCues.cueFor(rule("x", "척추의 중립", "shoulder_asym__std", ">", 0f, subtype = "lateral"))
        assertTrue(lat.habit.contains("옆으로"))
        val all = CoachCues.cueFor(rule("x", "척추 중립", "sh_over_hip_fwd__mean", ">", 0f, subtype = "all"))
        assertTrue(all.habit.contains("척추"))
        // 폴백
        val fb = CoachCues.cueFor(rule("x", "알 수 없는 조건", "knee_mean__mean", "<", 0f))
        assertTrue(fb.habit.contains("알 수 없는 조건"))
        assertTrue(fb.recovered.contains("교정"))
    }

    // ---- 양방향(반대측 가드) — spec §23, BIDIRECTIONAL.md

    private val guardedKneeRule = kneeRule.copy(
        oppositeGuard = OppositeGuard(op = ">", threshold = 0.20f, desc = "무릎/발이 과도하게 바깥", validated = true, nNorm = 27),
    )
    private val rsGuard = PostureRuleSet("t", "d", listOf(guardedKneeRule, torsoRule))

    @Test
    fun evaluateFlagsBothDirectionsOfKneeError() {
        val agg = FeatureAggregator()
        repeat(8) { agg.add(frame(0.30f, 0f)) }                       // 무릎 과도하게 바깥
        val out = rsGuard.evaluate("바벨 스쿼트", agg).first { it.rule.id == guardedKneeRule.id }
        assertEquals(Verdict.VIOLATION, out.verdict)
        assertEquals(Direction.OPPOSITE, out.direction)

        val aggIn = FeatureAggregator()
        repeat(8) { aggIn.add(frame(-0.05f, 0f)) }                    // 무릎 안쪽 (기본 방향)
        val inRes = rsGuard.evaluate("바벨 스쿼트", aggIn).first { it.rule.id == guardedKneeRule.id }
        assertEquals(Verdict.VIOLATION, inRes.verdict)
        assertEquals(Direction.PRIMARY, inRes.direction)

        val aggOk = FeatureAggregator()
        repeat(8) { aggOk.add(frame(0.05f, 0f)) }                     // 정상 (임계값 사이)
        val okRes = rsGuard.evaluate("바벨 스쿼트", aggOk).first { it.rule.id == guardedKneeRule.id }
        assertEquals(Verdict.OK, okRes.verdict)
        assertNull(okRes.direction)
    }

    @Test
    fun coachSpeaksOutwardKneeCueAndRecovers() {
        val c = LiveCoach(rsGuard, "바벨 스쿼트", windowFrames = 8, minFrames = 8, persistence = 2, ruleCooldownMs = 0, globalGapMs = 0)
        var t = 0L
        repeat(9) { c.onFrame(frame(0.30f, 0f)); t += 300 }
        c.evaluate(t)                                                  // streak 1
        c.onFrame(frame(0.30f, 0f)); t += 300
        val ev = c.evaluate(t)
        assertNotNull(ev)
        assertEquals(OnsetKind.HABIT, ev!!.kind)
        assertEquals(Direction.OPPOSITE, ev.direction)
        assertTrue(ev.message.contains("바깥"))
        val st = c.lastStates.first { it.rule.id == guardedKneeRule.id }
        assertEquals(Direction.OPPOSITE, st.direction)
        assertTrue(st.label.contains("반대측"))
        // 교정 → RECOVERED
        var rec: CoachEvent? = null
        repeat(10) {
            c.onFrame(frame(0.05f, 0f)); t += 300
            val e = c.evaluate(t)
            if (e?.kind == OnsetKind.RECOVERED) rec = e
        }
        assertNotNull(rec)
    }

    @Test
    fun oppositeCueCatalogAndFallback() {
        val g = rule("x", "발과 무릎의 방향 일치", "knee_out_mean__mean", "<", 0f)
        val cue = CoachCues.cueFor(g, Direction.OPPOSITE)
        assertTrue(cue.habit.contains("바깥"))
        assertTrue(cue.drift.contains("점점"))
        // 기본 방향은 기존 문구 그대로
        assertTrue(CoachCues.cueFor(g, Direction.PRIMARY).habit.contains("안쪽"))
        assertTrue(CoachCues.cueFor(g).habit.contains("안쪽"))
        // 고개/상체 반대측
        assertTrue(CoachCues.cueFor(rule("x", "고개 정면", "face_vs_torso__min", "<", 0f), Direction.OPPOSITE).habit.contains("젖혀"))
        assertTrue(CoachCues.cueFor(rule("x", "상체의 과조한 숙임/젖힘 여부", "torso_pitch__min", "<", 0f), Direction.OPPOSITE).habit.contains("숙여"))
        // 카탈로그에 없는 조건은 가드 설명으로 폴백
        val fb = rule("x", "무릎 반동 없음", "knee_mean__mean", "<", 0f)
            .copy(oppositeGuard = OppositeGuard(">", 1f, "반대 테스트", validated = false, nNorm = 10))
        val fbCue = CoachCues.cueFor(fb, Direction.OPPOSITE)
        assertTrue(fbCue.habit.contains("반대 테스트"))
        assertTrue(fbCue.habit.contains("반대 방향"))
    }
}
