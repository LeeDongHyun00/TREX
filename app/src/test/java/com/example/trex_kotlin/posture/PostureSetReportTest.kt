package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 세트 리포트: 랭킹, verdict, 정직성(판정 없으면 점수·"깨끗" 금지), TRACK 정책, 문장 분리. */
class PostureSetReportTest {

    private fun rule(
        id: String, condition: String, feature: String, op: String, thr: Float,
        auc: Float = 0.9f, subtype: String? = null, status: RuleStatus = RuleStatus.SHIP,
    ): PostureRule {
        val i = feature.lastIndexOf("__")
        return PostureRule(
            id = id, exercise = "바벨 스쿼트", condition = condition, subtype = subtype, status = status, reason = null,
            feature = feature, baseFeature = feature.substring(0, i), stat = feature.substring(i + 2), family = "x", op = op, threshold = thr,
            view = "C", viewDesc = "", cvAuc = auc, cvBalacc = 0.8f, sampleN = 60, mirrorSafe = true, cautions = emptyList(),
        )
    }

    private val kneeRule = rule("스쿼트|무릎", "발과 무릎의 방향 일치", "knee_out_mean__mean", "<", 0.01f, auc = 0.97f)
    private val torsoRule = rule("스쿼트|상체", "상체 과도한 젖힘 없음", "torso_pitch__min", "<", -20f, auc = 0.90f)
    private val heelRule = rule("스쿼트|발", "발바닥 지면 고정", "heel_lift__max", ">", 0.58f, auc = 0.85f)
    private val gazeBeta = rule("스쿼트|시선", "고개 정면", "face_vs_torso__min", "<", 0f, auc = 0.80f, status = RuleStatus.BETA)

    private fun res(rule: PostureRule, v: Verdict, dir: Direction? = if (v == Verdict.VIOLATION) Direction.PRIMARY else null) =
        RuleResult(rule, v, 0f, 16, direction = dir)

    private fun onset(rule: PostureRule, kind: OnsetKind?, early: Verdict = Verdict.OK, recent: Verdict = Verdict.OK, dir: Direction? = null) =
        OnsetState(rule, early, recent, 0f, 0f, kind, direction = dir)

    private fun build(
        results: List<RuleResult>, onset: List<OnsetState> = emptyList(), mode: CoachMode = CoachMode.COACH,
        repsValid: Int? = null, repsPartial: Int? = null, tempoMs: Long? = null, repRom: RepRomTier? = null,
    ) = PostureSetReport.build("set-1", "바벨 스쿼트", "스쿼트", mode, 40, false, results, onset, repsValid, repsPartial, tempoMs, repRom = repRom)

    // a. 랭킹
    @Test
    fun rankingOrdersOverallViolationThenDriftThenHabit() {
        val r = build(
            results = listOf(res(heelRule, Verdict.OK), res(torsoRule, Verdict.OK), res(kneeRule, Verdict.VIOLATION)),
            onset = listOf(
                onset(heelRule, OnsetKind.HABIT, Verdict.VIOLATION, Verdict.VIOLATION),   // 전체 OK + HABIT → rank 2
                onset(torsoRule, OnsetKind.DRIFT, Verdict.OK, Verdict.VIOLATION),         // 전체 OK + DRIFT → rank 1
                onset(kneeRule, OnsetKind.HABIT, Verdict.VIOLATION, Verdict.VIOLATION),   // 전체 위반 + HABIT → rank 0
            ),
        )
        assertEquals(listOf(kneeRule.id, torsoRule.id, heelRule.id), r.items.map { it.ruleId })
        assertEquals(listOf(0, 1, 2), r.items.map { it.rank })
        assertEquals(kneeRule.id, r.headline!!.ruleId)
        assertEquals(SetVerdict.ISSUE, r.verdict)
    }

    @Test
    fun rankingTieBreaksShipBeforeBetaThenHigherAuc() {
        val shipLow = rule("a", "발과 무릎의 방향 일치", "knee_out_mean__mean", "<", 0f, auc = 0.85f)
        val shipHigh = rule("b", "발바닥 지면 고정", "heel_lift__max", ">", 0f, auc = 0.95f)
        val beta = rule("c", "고개 정면", "face_vs_torso__min", "<", 0f, auc = 0.99f, status = RuleStatus.BETA)
        // 셋 다 rank 3 (세트 중간 위반). 입력 순서는 beta, shipLow, shipHigh
        val r = build(results = listOf(res(beta, Verdict.VIOLATION), res(shipLow, Verdict.VIOLATION), res(shipHigh, Verdict.VIOLATION)))
        assertEquals(listOf("b", "a", "c"), r.items.map { it.ruleId })
        assertEquals("b", r.headline!!.ruleId)
    }

    // b. 베타만 위반 → REFERENCE
    @Test
    fun betaOnlyViolationIsReferenceNotHeadline() {
        val r = build(
            results = listOf(res(kneeRule, Verdict.OK), res(gazeBeta, Verdict.VIOLATION)),
            onset = listOf(onset(gazeBeta, OnsetKind.HABIT, Verdict.VIOLATION, Verdict.VIOLATION)),
        )
        assertEquals(SetVerdict.REFERENCE, r.verdict)
        assertNull(r.headline)
        assertEquals(1, r.highlights.size)
        assertEquals(gazeBeta.id, r.highlights.first().ruleId)
        assertTrue(r.highlights.first().beta)
        assertTrue(r.voiceLine.contains("참고만 하세요"))
        assertTrue(r.voiceLine.startsWith("처음부터 시선이 정면을 벗어나 있어요."))
        assertEquals("참고 1건", r.summaryLine)
        assertEquals(100, r.accuracy)   // 점수는 검증된 규칙만 — "참고만 하세요" 라던 베타 위반이 점수를 깎으면 자기모순
        assertEquals(1, r.shipJudged)
        assertEquals(1, r.betaJudged)
        assertFalse(r.betaOnly)
    }

    // c. 전부 ABSTAIN → UNJUDGED
    @Test
    fun allAbstainIsUnjudgedWithoutScore() {
        val r = build(results = listOf(res(kneeRule, Verdict.ABSTAIN), res(torsoRule, Verdict.ABSTAIN)))
        assertEquals(SetVerdict.UNJUDGED, r.verdict)
        assertEquals(0, r.judged)
        assertEquals(2, r.abstained)
        assertNull(r.accuracy)
        assertTrue(r.candidates.isEmpty())
        assertTrue(r.voiceLine.contains("판정하지 못했어요"))
        assertFalse(r.voiceLine.contains("깨끗"))
        assertEquals("자세 판정 없음", r.summaryLine)
        assertEquals("유보", r.items.first().label)
    }

    // d. 위반 없음 → CLEAN
    @Test
    fun noViolationIsClean() {
        val r = build(
            results = listOf(res(kneeRule, Verdict.OK), res(torsoRule, Verdict.OK)),
            onset = listOf(onset(kneeRule, null), onset(torsoRule, null)),
        )
        assertEquals(SetVerdict.CLEAN, r.verdict)
        assertEquals(100, r.accuracy)
        assertEquals("이번 세트 깨끗했어요.", r.voiceLine)
        assertEquals("자세 깨끗", r.summaryLine)
        assertNull(r.headline)
        assertEquals("정상", r.items.first().label)
    }

    // e. TRACK: HABIT 강등, DRIFT 는 헤드라인, 점수 없음
    @Test
    fun trackModeDemotesHabitKeepsDriftAndHidesScore() {
        val r = build(
            results = listOf(res(kneeRule, Verdict.VIOLATION), res(torsoRule, Verdict.OK)),
            onset = listOf(
                onset(kneeRule, OnsetKind.HABIT, Verdict.VIOLATION, Verdict.VIOLATION),
                onset(torsoRule, OnsetKind.DRIFT, Verdict.OK, Verdict.VIOLATION),
            ),
            // 파셜 표기·발화는 검증된 ROM 기준에서만 (spec §58) — 이 테스트는 그 경로의 문구를 고정한다
            mode = CoachMode.TRACK, repsValid = 8, repsPartial = 2, tempoMs = 1500L, repRom = RepRomTier.VALIDATED,
        )
        assertEquals(listOf(kneeRule.id), r.demoted.map { it.ruleId })
        assertEquals(listOf(torsoRule.id), r.candidates.map { it.ruleId })
        assertEquals(torsoRule.id, r.headline!!.ruleId)
        assertEquals(OnsetKind.DRIFT, r.headline!!.kind)
        assertNull(r.accuracy)
        assertEquals(2, r.judged)
        assertTrue(r.voiceLine.startsWith("10렙"))
        assertEquals("10렙 파셜 2, 템포 1.5초, 상체가 점점 뒤로 젖혀져요.", r.voiceLine)
        assertFalse(r.voiceLine.contains("처음부터"))
        assertFalse(r.voiceLine.contains("깨끗"))
        assertEquals("10렙 · 파셜 2 · 템포 1.5초 · 상체 점점", r.summaryLine)
    }

    @Test
    fun trackModeWithoutRepsOrDriftSaysRecorded() {
        val r = build(
            results = listOf(res(kneeRule, Verdict.VIOLATION)),
            onset = listOf(onset(kneeRule, OnsetKind.HABIT, Verdict.VIOLATION, Verdict.VIOLATION)),
            mode = CoachMode.TRACK,
        )
        assertEquals("기록됐어요.", r.voiceLine)
        assertEquals("기록됨", r.summaryLine)
        assertTrue(r.candidates.isEmpty())
        assertEquals(1, r.demoted.size)
        assertNull(r.accuracy)
        // 파셜 0 은 표기하지 않는다
        val r2 = build(results = listOf(res(kneeRule, Verdict.OK)), mode = CoachMode.TRACK, repsValid = 5, repsPartial = 0, tempoMs = 2040L,
            repRom = RepRomTier.VALIDATED)
        assertEquals("5렙, 템포 2.0초.", r2.voiceLine)
        assertEquals("5렙 · 템포 2.0초", r2.summaryLine)
    }

    // e2. TRACK: 창에서 안 잡힌 세트 전체 위반(kind null)도 강등 — HABIT 과 같은 모집단 판정이다
    @Test
    fun trackModeDemotesMidSetViolationToo() {
        val r = build(
            results = listOf(res(kneeRule, Verdict.VIOLATION), res(torsoRule, Verdict.OK)),
            onset = listOf(onset(kneeRule, null), onset(torsoRule, null)),
            mode = CoachMode.TRACK, repsValid = 6, repsPartial = 0, tempoMs = 2000L, repRom = RepRomTier.VALIDATED,
        )
        assertTrue(r.candidates.isEmpty())
        assertNull(r.headline)
        assertEquals(listOf(kneeRule.id), r.demoted.map { it.ruleId })
        assertEquals(SetVerdict.CLEAN, r.verdict)
        assertNull(r.accuracy)
        assertEquals("6렙 · 템포 2.0초", r.summaryLine)
        assertFalse(r.voiceLine.contains("무릎"))
    }

    // d2. 베타 규칙만 판정된 세트(바닥 종목) — "깨끗" 대신 검증 중 기준임을 밝히고 점수 없음
    @Test
    fun betaOnlyCleanSetHasNoScoreAndSaysReference() {
        val r = build(results = listOf(res(gazeBeta, Verdict.OK)))
        assertEquals(SetVerdict.CLEAN, r.verdict)
        assertTrue(r.betaOnly)
        assertNull(r.accuracy)
        assertEquals(0, r.shipJudged)
        assertEquals(1, r.betaJudged)
        assertEquals("참고 기준 이상 없음", r.summaryLine)
        assertTrue(r.voiceLine.contains("검증 중"))
        assertFalse(r.voiceLine.contains("깨끗"))
    }

    // f. splitCue
    @Test
    fun splitCueSeparatesObservationAndFix() {
        assertEquals(
            "처음부터 등이 말려 있어요" to "가슴을 펴고 허리를 중립으로 세우세요",
            PostureSetReport.splitCue("처음부터 등이 말려 있어요. 가슴을 펴고 허리를 중립으로 세우세요."),
        )
        assertEquals("처음부터 손 위치가 가슴 중앙에서 벗어나 있어요" to "", PostureSetReport.splitCue("처음부터 손 위치가 가슴 중앙에서 벗어나 있어요."))
        assertEquals("마침표 없음" to "", PostureSetReport.splitCue("마침표 없음"))
    }

    // g. kind==null 전체위반 → "위반", "처음부터" 없음
    @Test
    fun midSetViolationDropsFromTheStartPrefix() {
        val r = build(results = listOf(res(kneeRule, Verdict.VIOLATION)), onset = listOf(onset(kneeRule, null, Verdict.OK, Verdict.OK)))
        val h = r.headline!!
        assertEquals(3, h.rank)
        assertEquals("위반", h.label)
        assertEquals("무릎이 안쪽으로 모여 있어요", h.observation)
        assertEquals("무릎을 발끝 방향으로 벌리세요", h.fix)
        assertEquals("무릎", h.bodyPart)
        assertEquals(SetVerdict.ISSUE, r.verdict)
        assertEquals("무릎이 안쪽으로 모여 있어요. 다음엔 무릎을 발끝 방향으로 벌리세요.", r.voiceLine)
        assertEquals("무릎 · 위반", r.summaryLine)
    }

    // h. accuracy 반올림
    @Test
    fun accuracyRoundsOkOverJudged() {
        val r = build(results = listOf(res(kneeRule, Verdict.VIOLATION), res(torsoRule, Verdict.OK), res(heelRule, Verdict.OK), res(gazeBeta, Verdict.ABSTAIN)))
        assertEquals(3, r.judged)
        assertEquals(1, r.abstained)
        assertEquals(2, r.okCount)
        assertEquals(67, r.accuracy)
    }

    // i. RECOVERED 만
    @Test
    fun recoveredOnlyIsRecoveredVerdict() {
        val r = build(
            results = listOf(res(kneeRule, Verdict.OK), res(torsoRule, Verdict.OK)),
            onset = listOf(onset(kneeRule, OnsetKind.RECOVERED, Verdict.VIOLATION, Verdict.OK)),
        )
        assertEquals(SetVerdict.RECOVERED, r.verdict)
        val h = r.headline!!
        assertEquals(4, h.rank)
        assertEquals("교정됨", h.label)
        assertEquals("좋아요, 무릎 자세가 교정됐어요", h.observation)
        assertEquals("무릎을 발끝 방향으로 벌리세요", h.fix)
        assertTrue(r.voiceLine.contains("교정됐어요"))
        assertEquals("좋아요, 무릎 자세가 세트 후반에 교정됐어요.", r.voiceLine)
        assertEquals("무릎 교정됨", r.summaryLine)
        assertEquals(100, r.accuracy)
    }

    // 조인: onset 에만 있는 규칙은 무시, 반대측 방향은 라벨/문구에 반영
    @Test
    fun joinsByRuleIdAndKeepsOppositeDirection() {
        val r = build(
            results = listOf(res(kneeRule, Verdict.VIOLATION, Direction.OPPOSITE)),
            onset = listOf(
                onset(kneeRule, OnsetKind.DRIFT, Verdict.OK, Verdict.VIOLATION, dir = Direction.OPPOSITE),
                onset(torsoRule, OnsetKind.HABIT, Verdict.VIOLATION, Verdict.VIOLATION),   // results 에 없음 → 무시
            ),
        )
        assertEquals(1, r.items.size)
        val h = r.items.first()
        assertEquals("점점 흐트러짐 (반대측)", h.label)
        assertTrue(h.observation.contains("바깥"))
        assertEquals(Direction.OPPOSITE, h.direction)
        assertNotNull(r.headline)
    }

    // ---- spec §58: 렙 ROM 표시 정직성 — 판정하지 않은 것은 '범위 미판정', 미검증 기준은 '참고' 로만, 음성은 검증 기준에서만

    @Test
    fun romTierFollowsSignalConfiguration() {
        assertEquals(RepRomTier.VALIDATED, RepRomTier.of(RepSignals.byExercise.getValue("딥스")))
        assertEquals(RepRomTier.REFERENCE, RepRomTier.of(RepSignals.byExercise.getValue("바벨 스쿼트")))
        // 런지는 카운트 신호를 바꾸며 ROM 을 뗐다 — 판정하지 않는다
        assertEquals(RepRomTier.NONE, RepRomTier.of(RepSignals.byExercise.getValue("스텝 포워드 다이나믹 런지")))
        assertEquals(RepRomTier.NONE, RepRomTier.of(RepSignal("x", 1f)))
        assertEquals(RepRomTier.NONE, RepRomTier.of(RepSignal("x", 1f, romDirection = "mid", romThreshold = 1f, romValidated = true)))
        // 세션 구성(PostureLive 와 같은 forSession): 바닥은 규칙 설정이 없으면 ROM 을 떼고, 규칙 설정은 검증 표시를 끈다
        assertEquals(RepRomTier.NONE, RepRomTier.of(RepCounter.forSession("크런치", floor = true)!!.signal))
        assertEquals(RepRomTier.REFERENCE, RepRomTier.of(RepCounter.forSession("푸시업", "min", 0.71f, floor = true)!!.signal))
        assertEquals(RepRomTier.VALIDATED, RepRomTier.of(RepCounter.forSession("바벨 런지", floor = false)!!.signal))
        assertEquals("reference", RepRomTier.REFERENCE.key)
    }

    @Test
    fun unjudgedRomIsNeverShownOrSpokenAsValid() {
        for (tier in listOf(RepRomTier.NONE, null)) {   // 모름(null)도 판정 안 함으로 다룬다
            val coach = build(results = listOf(res(kneeRule, Verdict.OK)), repsValid = 8, repsPartial = 0, tempoMs = 1500L, repRom = tier)
            assertEquals(RepRomTier.NONE, coach.romTier)
            // 카운트는 beta — 검증 기준이 없는 줄은 '참고' 로 연다(바닥 종목의 예전 표기 '참고 · 검출 …' 과 같다)
            assertEquals("참고 · 검출 8회 · 범위 미판정", coach.repDetailLine)
            assertFalse(coach.repDetailLine!!.contains("유효"))
            assertFalse(coach.voiceLine.contains("유효") || coach.voiceLine.contains("무효"))
            val track = build(results = listOf(res(kneeRule, Verdict.OK)), mode = CoachMode.TRACK, repsValid = 8, repsPartial = 0, tempoMs = 1500L, repRom = tier)
            assertEquals("8렙 · 범위 미판정 · 템포 1.5초", track.summaryLine)
            assertEquals("8렙 · 범위 미판정", track.repDetailLine)
            // 음성은 렙 수만 — 판정하지 않은 범위를 말로 만들지 않는다
            assertEquals("8렙, 템포 1.5초.", track.voiceLine)
        }
    }

    @Test
    fun referenceRomIsScreenOnlyAndNeverInvalidOrPartial() {
        val coach = build(results = listOf(res(kneeRule, Verdict.OK)), repsValid = 8, repsPartial = 2, repRom = RepRomTier.REFERENCE)
        assertEquals("참고 · 검출 10회 · 범위 미달 2회", coach.repDetailLine)
        assertFalse(coach.repDetailLine!!.contains("무효"))
        assertFalse(coach.voiceLine.contains("무효") || coach.voiceLine.contains("파셜") || coach.voiceLine.contains("범위"))
        val track = build(results = listOf(res(kneeRule, Verdict.OK)), mode = CoachMode.TRACK, repsValid = 8, repsPartial = 2, tempoMs = 1500L,
            repRom = RepRomTier.REFERENCE)
        assertEquals("10렙 · 참고 · 범위 미달 2회 · 템포 1.5초", track.summaryLine)
        assertEquals("10렙 · 참고 · 범위 미달 2회", track.repDetailLine)
        assertEquals("10렙, 템포 1.5초.", track.voiceLine)
        assertFalse(track.summaryLine.contains("파셜") || track.voiceLine.contains("파셜"))
        // 미달 0 은 표기하지 않는다 (검증 기준의 파셜 0 과 같다)
        val none = build(results = listOf(res(kneeRule, Verdict.OK)), mode = CoachMode.TRACK, repsValid = 6, repsPartial = 0, repRom = RepRomTier.REFERENCE)
        assertEquals("6렙", none.summaryLine)
    }

    @Test
    fun validatedRomKeepsValidInvalidAndPartialWording() {
        val coach = build(results = listOf(res(kneeRule, Verdict.OK)), repsValid = 8, repsPartial = 2, repRom = RepRomTier.VALIDATED)
        assertEquals("렙 유효 8 · 무효 2", coach.repDetailLine)
        val track = build(results = listOf(res(kneeRule, Verdict.OK)), mode = CoachMode.TRACK, repsValid = 8, repsPartial = 2, repRom = RepRomTier.VALIDATED)
        assertEquals("10렙 · 파셜 2", track.repDetailLine)
        assertEquals("10렙 파셜 2.", track.voiceLine)
    }

    @Test
    fun romNeverChangesTheDetectedCountAndFloorStaysReference() {
        // 수는 검출 전체 — ROM 단계와 무관하게 valid + partial (진행·자동 넘김과 같은 수, spec §42)
        for (tier in RepRomTier.values()) {
            val r = build(results = emptyList(), mode = CoachMode.TRACK, repsValid = 7, repsPartial = 3, repRom = tier)
            assertTrue(r.repDetailLine!!.startsWith("10렙"))
        }
        assertNull(build(results = emptyList()).repDetailLine)   // 렙 카운터 미적용
        // 바닥 종목은 규칙이 전부 beta — 검증 기준이 붙어 와도 참고로 낮춘다
        val floor = PostureSetReport.build("set-2", "푸시업", "푸쉬업", CoachMode.COACH, 40, false, emptyList(), emptyList(), 5, 1, null,
            repRom = RepRomTier.VALIDATED)
        assertEquals(RepRomTier.REFERENCE, floor.romTier)
        assertEquals("참고 · 검출 6회 · 범위 미달 1회", floor.repDetailLine)
        // rep 규칙 설정이 없는 바닥 종목(크런치 등)은 ROM 을 떼므로 NONE — 그래도 '참고' 표기는 남는다
        val crunch = PostureSetReport.build("set-3", "크런치", "크런치", CoachMode.COACH, 40, false, emptyList(), emptyList(), 6, 0, null,
            repRom = RepRomTier.of(RepCounter.forSession("크런치", floor = true)!!.signal))
        assertEquals("참고 · 검출 6회 · 범위 미판정", crunch.repDetailLine)
    }

    // ---- 사용자 결정(2026-09-24): 런지류는 좌우 한 번씩 = 1회 — 리포트의 수는 표시 단위(짝), 단위는 렙 줄에서만 밝힌다

    @Test
    fun sidePairRepLineNamesTheUnitAndTheUncountedHalf() {
        fun lunge(mode: CoachMode, valid: Int, partial: Int, tier: RepRomTier, half: Boolean = false, unit: RepUnit? = RepUnit.SIDE_PAIR) =
            PostureSetReport.build("set-4", "바벨 런지", "바벨 런지", mode, 40, false, listOf(res(kneeRule, Verdict.OK)), emptyList(),
                valid, partial, 8000L, repRom = tier, repUnit = unit, repHalfPending = half)
        // 검증 ROM(바벨 런지): '무효 2' 는 걸음이 아니라 짝의 수 — 단위를 붙인다. 판정 말(유효·무효)은 그대로(RepRomTier)
        assertEquals("렙 유효 8 · 무효 2 · 좌우 한 번씩 = 1회", lunge(CoachMode.COACH, 8, 2, RepRomTier.VALIDATED).repDetailLine)
        // 기준 없음(런지)·미검증(크로스 런지·사이드 런지): 같은 말 + 단위
        assertEquals("참고 · 검출 10회 · 범위 미판정 · 좌우 한 번씩 = 1회", lunge(CoachMode.COACH, 10, 0, RepRomTier.NONE).repDetailLine)
        assertEquals("참고 · 검출 10회 · 범위 미달 1회 · 좌우 한 번씩 = 1회", lunge(CoachMode.COACH, 9, 1, RepRomTier.REFERENCE).repDetailLine)
        // 세트 끝에 짝 없이 남은 한쪽: 세지 않았다고 화면에 밝힌다
        assertEquals("참고 · 검출 9회 · 범위 미판정 · 좌우 한 번씩 = 1회 · 반대쪽 없이 끝난 한쪽은 세지 않음",
            lunge(CoachMode.COACH, 9, 0, RepRomTier.NONE, half = true).repDetailLine)
        val track = lunge(CoachMode.TRACK, 8, 2, RepRomTier.VALIDATED, half = true)
        assertEquals("10렙 · 파셜 2 · 좌우 한 번씩 = 1회 · 반대쪽 없이 끝난 한쪽은 세지 않음", track.repDetailLine)
        // 음성·기록 한 줄은 그대로 — 수는 HUD 와 같은 단위이고, 단위·반쪽 안내는 화면 전용이다(원칙 #6)
        assertEquals("10렙 · 파셜 2 · 템포 8.0초", track.summaryLine)
        assertEquals("10렙 파셜 2, 템포 8.0초.", track.voiceLine)
        val coach = lunge(CoachMode.COACH, 9, 0, RepRomTier.NONE, half = true)
        assertFalse(coach.voiceLine.contains("좌우") || coach.voiceLine.contains("한쪽") || coach.voiceLine.contains("반대쪽"))
        // 사이클 단위·모름(null)은 지금과 같은 줄 — 단위 말이 붙지 않고, 반쪽 플래그도 짝 단위가 아니면 쓰지 않는다
        for (unit in listOf(RepUnit.CYCLE, null)) {
            assertEquals("참고 · 검출 10회 · 범위 미판정", lunge(CoachMode.COACH, 10, 0, RepRomTier.NONE, half = true, unit = unit).repDetailLine)
        }
        // 렙 카운터 미적용이면 단위가 있어도 렙 줄이 없다
        assertNull(PostureSetReport.build("set-5", "바벨 런지", "바벨 런지", CoachMode.COACH, 40, false, emptyList(), emptyList(),
            null, null, null, repUnit = RepUnit.SIDE_PAIR, repHalfPending = true).repDetailLine)
    }

    @Test
    fun formLabelRoundTrips() {
        for (f in FormLabel.values()) assertEquals(f, FormLabel.from(f.key))
        assertNull(FormLabel.from(null))
        assertNull(FormLabel.from("zzz"))
    }
}
