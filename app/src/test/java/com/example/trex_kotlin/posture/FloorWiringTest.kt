package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 바닥 계열 앱 배선(spec §99, `docs/FLOOR_FAMILY_DESIGN.md` §8 — 5단계)의 통합 계약.
 *  - 판별 기각의 틱·이유 음성은 반복 검사 평가기(`RepFormSpecs`)가 0개인 바닥 종목에서도 나간다(설계 §4.4 — 전에는 평가기 조건 안이라 무음).
 *  - 바닥 beta 판정은 말하지 않는다(사용자 결정 Q1) — 화면 문장·부위만.
 *  - 플랭크 정렬은 유지 시계의 HOLD 칸만, 측면에서만, 고개 |값| > 90° 는 버린다. 세트 마감도 같은 칸.
 *  - 세트 로그·리포트의 바닥 블록, rules_floor v0.5.
 */
class FloorWiringTest {
    private fun crunch(lift: Float, yaw: Float? = 0.05f): Map<String, Float> = buildMap {
        put(FloorChain.TRUNK_LIFT, lift); put(FloorChain.EAR_LIFT, 10f + lift); put(FloorChain.AXIS_H, lift); put(FloorChain.TORSO_ELEV, lift)
        put(FloorChain.CHAIN, 1f); yaw?.let { put(FloorChain.YAW, it) }
    }

    private class Feed(val rc: RepCounter) {
        var t = 0L
        fun f(m: Map<String, Float>, n: Int = 1) { repeat(n) { rc.onFrameFeatures(t, m); t += 300L } }
    }
    private fun Feed.sitUp(f: (Float) -> Map<String, Float>) = listOf(10f, 30f, 55f, 85f, 55f, 30f, 10f, 0f, 0f).forEach { f(f(it)) }
    private fun Feed.crunchRep(f: (Float) -> Map<String, Float>) = listOf(6f, 12f, 18f, 12f, 6f, 0f, 0f).forEach { f(f(it)) }

    @Test fun floorRejectionTicksAndSpeaksWithoutARepFormEvaluator() {
        val rc = RepCounter.forSession("크런치", floor = true)!!
        // 바닥 종목에는 반복 검사가 없다 — 그래서 기각 블록이 평가기 조건 밖이어야 한다
        assertNull(RepFormSpecs.evaluatorFor("크런치", rc))
        assertNull(RepFormSpecs.evaluatorFor("라잉 레그 레이즈", RepCounter.forSession("라잉 레그 레이즈", floor = true)!!))
        val fd = Feed(rc); fd.f(crunch(0f), 4); fd.crunchRep(::crunch); fd.sitUp(::crunch)
        assertEquals(listOf("sit_up"), rc.rejectedReps.map { it.feature })
        val gate = RejectCueGate()
        val r = RejectionCues.handle(rc, null, 0, fd.t, gate, silent = false)
        assertEquals(1, r.seen); assertTrue("세지 않은 동작 = 낮은 틱", r.cue!!.tick)
        assertEquals(FloorCycleTracker.cueFor(FloorProfile.CRUNCH, "sit_up"), r.cue!!.speech)
        assertEquals("sit_up", r.last!!.feature)
        assertNull("새 기각이 없으면 아무것도", RejectionCues.handle(rc, null, r.seen, fd.t, gate, silent = false).cue)
        // 같은 사유를 6 s 안에 다시 — 틱만
        val t1 = fd.t; fd.sitUp(::crunch)
        val r2 = RejectionCues.handle(rc, null, r.seen, fd.t, gate, silent = false)
        assertTrue(fd.t - t1 < RejectCueGate.GAP_MS); assertTrue(r2.cue!!.tick); assertNull(r2.cue!!.speech)
        // 6 s 지나면 다시 이유
        fd.f(crunch(0f), 20); fd.sitUp(::crunch)
        val r3 = RejectionCues.handle(rc, null, r2.seen, fd.t, gate, silent = false)
        assertNotNull(r3.cue!!.speech)
        // 검증 모드·일시정지·음소거 — 틱도 이유도 없지만 처리한 수는 넘어간다
        fd.f(crunch(0f), 4); fd.sitUp(::crunch)
        val r4 = RejectionCues.handle(rc, null, r3.seen, fd.t + 60_000, gate, silent = true)
        assertEquals(rc.rejectedReps.size, r4.seen); assertFalse(r4.cue!!.tick); assertNull(r4.cue!!.speech)
    }

    @Test fun legFamilyKeepsOneGapAcrossReasonsWhileFloorGapsPerReason() {
        val src = object : IdentityCueSource { override fun cueFor(reason: String) = "이유 $reason" }
        val leg = RejectCueGate()
        assertEquals("이유 a", leg.next(10_000, "a", src, silent = false, perReason = false).speech)
        assertNull("한 다리 계열은 사유와 무관하게 6 s 에 한 번(종전 동작)", leg.next(12_000, "b", src, silent = false, perReason = false).speech)
        val floor = RejectCueGate()
        assertEquals("이유 a", floor.next(10_000, "a", src, silent = false, perReason = true).speech)
        assertEquals("바닥은 사유별 6 s", "이유 b", floor.next(12_000, "b", src, silent = false, perReason = true).speech)
        assertNull(floor.next(15_000, "a", src, silent = false, perReason = true).speech)
        assertEquals("이유 a", floor.next(16_500, "a", src, silent = false, perReason = true).speech)
        assertFalse("원천이 없으면(스쿼트 판별·컬 기각) 틱도 없다 — 종전", RejectCueGate().next(0, "a", null, silent = false, perReason = true).tick)
    }

    private fun rule(ex: String, kind: String, feature: String, status: RuleStatus = RuleStatus.BETA, repConfig: RepRuleConfig? = null,
                     alignment: AlignmentConfig? = null) =
        PostureRule("floor|$ex|$feature", ex, feature, null, status, null, feature, feature, kind, "floor2d", ">", 1f, "C", "", Float.NaN, Float.NaN, 0, true,
            emptyList(), kind = kind, repConfig = repConfig, alignmentConfig = alignment)

    @Test fun betaFloorJudgementsAreNeverSpoken() {
        // 반복 범위 참고(푸시업 rep 규칙) — 두 번 연속 미달이면 확인 필요, 다시 두 번 통과하면 복귀. 둘 다 화면에만(Q1)
        val r = rule("푸시업", "rep", "wrist_shoulder_d", repConfig = RepRuleConfig("min", .71f, .34f, 2))
        val fb = FloorFeedbackController("푸시업", listOf(r))
        val f = mapOf("wrist_shoulder_d" to .9f)
        val out = ArrayList<FloorFeedback>()
        var t = 1_000L
        for (min in listOf(.9f, .9f, .5f, .5f)) {
            out += fb.update(t, f, null, emptyList(), RepRecord(t, min, 1.4f, null), voiceEnabled = true); t += 300
            repeat(4) { out += fb.update(t, f, null, emptyList(), voiceEnabled = true); t += 300 }
        }
        assertTrue(out.any { it.phase == FloorFeedbackPhase.ATTENTION }); assertTrue(out.any { it.phase == FloorFeedbackPhase.RECOVERED })
        assertTrue("beta 바닥 음성 0", out.all { it.speech == null })
        // 플랭크 정렬 이탈·복귀·배치 — 전부 무음(배치·멈춤은 유지 시계가 말한다)
        val hip = rule("플랭크", "alignment", PlankGeometry.HIP, alignment = AlignmentConfig(-.08f, .16f))
        val pf = FloorFeedbackController("플랭크", listOf(hip))
        val issue = AlignmentSnapshot(listOf(AlignmentItem(hip, .3f, Verdict.VIOLATION, 1)), placementReady = true)
        val rec = AlignmentSnapshot(listOf(AlignmentItem(hip, .02f, Verdict.OK, 0, recovered = true)), placementReady = true)
        val off = AlignmentSnapshot(emptyList(), placementReady = false)
        for ((i, a) in listOf(issue, rec, off).withIndex()) assertNull(pf.update(100_000L * (i + 1), emptyMap(), null, emptyList(), alignment = a, voiceEnabled = true).speech)
        assertEquals(FloorFeedbackPhase.ATTENTION, pf.update(500_000, emptyMap(), null, emptyList(), alignment = issue).phase)
        // 시계가 확인하지 않은 칸은 정렬을 말하지 않는다
        assertEquals(FloorFeedbackPhase.MEASURING, pf.update(600_000, emptyMap(), null, emptyList(),
            alignment = AlignmentSnapshot(emptyList(), placementReady = true, held = false)).phase)
        // 범위 문장도 음성 지적을 약속하지 않는다
        val scope = PostureScope.of(PostureRuleSet("t", "d", listOf(r)), "푸시업")
        assertTrue(scope.startLine!!.contains("음성으로 지적하지 않아요")); assertFalse(scope.startLine!!.contains("빨간"))
    }

    private fun plankHip(): PostureRule = rule("플랭크", "alignment", PlankGeometry.HIP, alignment = AlignmentConfig(-.08f, .16f))
    private fun plankHead(): PostureRule = rule("플랭크", "alignment", PlankGeometry.HEAD, alignment = AlignmentConfig(-42.748f, 25f))
    private fun pf(hip: Float, head: Float = 0f, yaw: Float? = 0.05f, knee: Float = 175f) = buildMap {
        put(PlankGeometry.READY, 1f); put(PlankGeometry.SIDE, 0f); put(PlankGeometry.HIP, hip); put(PlankGeometry.HEAD, head)
        put(FloorChain.CHAIN, 1f); put(FloorChain.KNEE, knee); put(FloorChain.HIP_FLOOR, .4f); put(FloorChain.HIP_OFF, hip); put(FloorChain.AXIS_H, 5f)
        yaw?.let { put(FloorChain.YAW, it) }
    }

    @Test fun plankAlignmentOnlyJudgesHoldSideFramesAndDropsFlippedHeads() {
        val t = PlankAlignmentTracker(listOf(plankHip(), plankHead()))
        for (ms in 0L..2000L step 250) t.add(ms, pf(.3f), gate = false)       // 무릎을 대거나 시계가 확인 전 — 이탈로 세지 않는다
        assertTrue(t.results().all { it.verdict == Verdict.ABSTAIN }); assertFalse(t.snapshot.held)
        for (ms in 2250L..4250L step 250) t.add(ms, pf(.3f, yaw = 0.4f))     // 측면이 아니면 유보
        assertTrue(t.results().all { it.verdict == Verdict.ABSTAIN })
        for (ms in 4500L..6500L step 250) t.add(ms, pf(.3f, head = -154.88f)) // HOLD·측면 — 골반 솟음은 이탈, 뒤집힌 고개는 버린다
        val res = t.results()
        assertEquals(Verdict.VIOLATION, res[0].verdict)
        assertEquals(Verdict.ABSTAIN, res[1].verdict); assertNull("|고개| > 90° 는 worst 에 남지 않는다", res[1].value)
    }

    @Test fun finalAssessmentUsesTheSameHoldFrames() {
        val rs = PostureRuleSet("t", "d", listOf(plankHip()))
        val times = (0L..6000L step 300).toList()
        // 앞 3 s 는 무릎을 댄 채(골반 +0.3 으로 오독), 뒤 3 s 는 곧은 플랭크
        val samples = times.map { ms -> PoseSample(true, FloatArray(66), FloatArray(33) { 1f }, if (ms < 3000) pf(.3f, knee = 120f) else pf(.02f), 33, 1, 1000, 1000) }
        val all = PostureAssessment.evaluate(rs, "플랭크", samples, times, Long.MAX_VALUE, 6000, emptyList())
        assertEquals("구간이 없으면 종전(전 프레임)", Verdict.VIOLATION, all.single().verdict)
        val segs = listOf(HoldSegment(0, 3000, PlankHoldClock.KNEES_DOWN), HoldSegment(3000, 6000, PlankHoldClock.HOLD_LABEL))
        val held = PostureAssessment.evaluate(rs, "플랭크", samples, times, Long.MAX_VALUE, 6000, emptyList(), holdSegments = segs)
        assertEquals("HOLD 칸만 — 무릎 댄 칸의 오독은 이탈이 아니다", Verdict.OK, held.single().verdict)
        assertTrue(PostureAssessment.holdGate(null, 0, emptyMap()))
        assertFalse("HOLD 구간이라도 그 칸에 멈춤 사유가 있으면 보지 않는다", PostureAssessment.holdGate(segs, 4000, pf(.02f, knee = 120f)))
    }

    @Test fun plankHoldIsLoggedAndReportedHonestly() {
        val c = PlankHoldClock(); c.start(1_000)
        var t = 1_000L
        fun feed(f: Map<String, Float>?, n: Int) { repeat(n) { c.onFrame(t, f); t += 300 } }
        feed(pf(.02f), 30); feed(pf(.02f, knee = 120f), 10); feed(pf(.02f), 10)
        val snap = c.snapshot()
        val log = SetLogJson.encode(SetLog.build("플랭크", emptyList(), emptyList(), "r", "full", "GPU", false, 300, hold = HoldLog.of(snap, 1_000), floorChainSwitches = 2))
        assertTrue(log.contains("\"hold\":{\"engine\":\"plankhold_v1_beta\",\"source\":\"camera\",\"held_ms\":${snap.heldMs},\"wall_ms\":${snap.wallMs}"))
        assertTrue(log.contains("\"first_stop\":{\"t_ms\":${snap.firstStop!!.tMs - 1_000},\"reason\":\"knees_down\"}"))
        assertTrue(log.contains("\"stop_ms\":{\"knees_down\":")); assertTrue(log.contains("\"segments\":[[0,"))
        assertTrue(log.contains("\"floor_chain\":{\"switches\":2}"))
        // 시계 시작·마지막 판정 칸(재생기가 '사람 없음' 칸을 다시 만든다)과 대기 시간
        assertTrue(log, log.contains("\"wait_ms\":{},\"start_t_ms\":0,\"end_t_ms\":${snap.endAt!! - 1_000}"))
        val s = HoldSummary.of(snap, 1_000)
        // 처음 멈춤은 시계 시작(WORK 시작) 기준 — 첫 검출 프레임 시각을 넘겨도 같다(리뷰 2026-10-07)
        assertEquals(s.firstStopMs, HoldSummary.of(snap, 9_999).firstStopMs)
        // 무릎을 댄 채 기다린 시간은 '확인 전' 으로 남는다
        val kneeWait = HoldSummary(0, 45_000, emptyMap(), null, camera = true, waitMs = mapOf(PlankHoldClock.KNEES_DOWN to 44_700L))
        assertTrue(PostureSetReport.floorLines(kneeWait, null, emptyList()).single().contains("확인 전: 무릎 45초"))
        val lines = PostureSetReport.floorLines(s, null, emptyList())
        assertTrue(lines.single(), lines.single().startsWith("버틴 시간 ${(snap.heldMs + 500) / 1000}초(카메라 확인) · 경과"))
        assertTrue(lines.single().contains("멈춤: 무릎 ")); assertTrue(lines.single().contains("처음 멈춤 "))
        assertTrue("정렬 판정 0건이면 '깨끗' 이 아니라 확인 못 함", lines.single().endsWith("정렬 확인 못 함"))
        val judged = PostureSetReport.floorLines(s, null, listOf(RuleResult(plankHip(), Verdict.VIOLATION, .3f, 8)))
        assertTrue(judged.single().endsWith("(참고) 정렬 이탈 1건"))
        val report = PostureSetReport.build("id", "플랭크", "플랭크", CoachMode.COACH, 50, false, emptyList(), emptyList(), null, null, null,
            measurements = lines, hold = s, scopeLine = PostureScope.floorLine("플랭크"))
        assertTrue(report.summaryLine.startsWith("버틴 시간")); assertNull(report.accuracy); assertNotEquals(SetVerdict.CLEAN, report.verdict)
        assertTrue(report.scopeLine!!.contains("볼 수 없어요"))
        // 폴백 세트 — '카메라 미확인 · 시계 기록'
        val fb = HoldSummary(0, 45_000, emptyMap(), null, camera = false)
        assertTrue(PostureSetReport.floorLines(fb, null, emptyList()).single().startsWith("카메라 미확인 · 시계 기록 · 경과 45초"))
        assertEquals("카메라 미확인 · 시계 기록 45초", PostureSetReport.build("id", "플랭크", "플랭크", CoachMode.TRACK, 50, false, emptyList(), emptyList(),
            null, null, null, hold = fb).summaryLine)
    }

    @Test fun floorRepsAreLoggedWithReasonsAndReported() {
        val rc = RepCounter.forSession("크런치", floor = true)!!
        val fd = Feed(rc); fd.f(crunch(0f), 4); fd.crunchRep(::crunch); fd.crunchRep(::crunch); fd.sitUp(::crunch)
        fd.crunchRep { crunch(it, yaw = 0.4f) }
        val ft = rc.floorTracker!!
        val log = SetLogJson.encode(SetLog.build("크런치", emptyList(), emptyList(), "r", "full", "GPU", false, 300,
            repCount = rc.reps, repTimesMs = rc.repTimesMs, repEngine = RepEngineLog.of(rc), repRejected = rc.rejectedReps,
            repFloorRejected = ft.rejectedDetail, repFloorReps = ft.repDetail, repDiscarded = ft.discarded, repIdentityAbstain = ft.identityAbstain))
        assertTrue(log.contains("\"engine\":\"floorcycle_v1_beta\""))
        assertTrue("누운 기준(재생 파리티) — 세트 중에 잡혔다(prep 0, 재생기는 심지 않는다)", log.contains("\"lying\":{\"fc_ear_lift\":10,\"fc_torso_elev\":0,\"fc_trunk_lift\":0,\"prep\":0}"))
        assertTrue(log.contains("\"feature\":\"sit_up\",\"reason\":\"sit_up\",\"trunk_peak\":85"))
        assertTrue(log.contains("\"floor_reps\":[{\"t_ms\":")); assertTrue(log.contains("\"identity_abstain\":[")); assertTrue(log.contains("\"discarded\":[]"))
        val sum = FloorRepSummary.of(FloorProfile.CRUNCH, rc.reps, ft.rejected.map { it.feature }, ft.identityAbstain.size)
        assertEquals(listOf("sit_up" to 1), sum.rejected); assertEquals(1, sum.abstained)
        val line = PostureSetReport.floorLines(null, sum, emptyList()).single()
        assertEquals("센 회 3 · 세지 않은 동작 1(끝까지 일어남 1) · 측면이 아니라 판별 유보 1회", line)
        val report = PostureSetReport.build("id", "크런치", "크런치", CoachMode.COACH, 50, false, emptyList(), emptyList(), 3, 0, null, floorReps = sum)
        assertEquals("센 회 3 · 세지 않은 동작 1", report.summaryLine)
        assertTrue(report.voiceLine.contains("세지 않은 동작은 1번"))
    }

    @Test fun floorUpIsLoggedAsTheGravityTheChainSaw() {
        // 앱이 up 을 뒤집은 프레임 — 관측 층은 되돌린 중력을 받는다. 로그 floor_up 이 그 값이어야 재생기 U 줄이 앱과 같다
        val xy = FloatArray(66) { .5f }
        val flipped = PoseSample(true, xy, FloatArray(33) { 1f }, emptyMap(), 33, 1, 480, 640, up = Vec3(0f, -1f, 0f), upFromGravity = true, upFlipped = true)
        val noGravity = PoseSample(true, xy, FloatArray(33) { 1f }, emptyMap(), 33, 1, 480, 640)
        val crunchLog = SetLogJson.encode(SetLog.build("크런치", listOf(flipped, noGravity), emptyList(), "r", "full", "GPU", false, 300, coordinates = true))
        assertTrue(crunchLog, crunchLog.contains("\"floor_up\":[0,1,0]")); assertTrue(crunchLog.contains("\"floor_up\":null"))
        val squatLog = SetLogJson.encode(SetLog.build("바벨 스쿼트", listOf(flipped), emptyList(), "r", "full", "GPU", false, 300, coordinates = true))
        assertFalse("서서 하는 종목의 로그는 그대로", squatLog.contains("floor_up"))
    }

    @Test fun holdAnnouncementsEveryTenSecondsAndLastFive() {
        assertEquals("10초", HoldAnnounce.between(9_900, 10_200, 45_000)!!.text)
        assertNull(HoldAnnounce.between(10_200, 10_500, 45_000))
        assertEquals("1분 10초", HoldAnnounce.between(69_800, 70_100, 90_000)!!.text)
        assertEquals(HoldAnnounce.Announcement("5", true), HoldAnnounce.between(39_900, 40_200, 45_000))
        assertEquals("1 s 소급 적립은 마지막 경계 하나", "3", HoldAnnounce.between(40_200, 42_100, 45_000)!!.text)
        assertNull("목표에 닿으면 넘어간다(말하지 않음)", HoldAnnounce.between(44_800, 45_100, 45_000))
        assertNull("남은 5초 안의 10초 경계는 카운트가 맡는다", HoldAnnounce.between(39_900, 40_100, 42_000)?.takeIf { !it.countdown })
    }

    @Test fun floorPreparationSaysWhatIsNeededFirstAndScopeSaysWhatIsNotSeen() {
        val crunch = ExerciseProfiles.forName("크런치")!!
        assertTrue(crunch.preparationInstruction.contains("누워서 무릎을 세우면 세기 시작해요"))
        assertFalse("누워서 시작해야 세는 종목에 '앉거나' 를 안내하지 않는다", crunch.preparationInstruction.contains("앉거나"))
        assertTrue(ExerciseProfiles.forName("레그 레이즈")!!.preparationInstruction.contains("다리를 바닥에 내리면"))
        assertTrue(ExerciseProfiles.forName("플랭크")!!.preparationInstruction.contains("다리를 펴고 골반을 들면 시간을 재기"))
        assertTrue("다른 바닥 종목은 공유 문장 그대로", ExerciseProfiles.forName("푸쉬업")!!.preparationInstruction.contains(CapturePosition.FLOOR_SIDE.voice))
        for (ex in listOf("크런치", "라잉 레그 레이즈", "플랭크")) {
            val line = PostureScope.floorLine(ex)!!
            // §101c: 시험 단계 문장(PostureTrial)은 비었다(사용자 결정 2026-10-09) — 범위 문장만
            assertEquals(line, PostureScope.of(PostureRuleSet("t", "d", emptyList()), ex).startLine)
            assertFalse("과장 금지", line.contains("어깨까지 들린 회만"))
        }
        assertNull(PostureScope.floorLine("푸시업"))
    }

    @Test fun floorRulesV05BandsAndCountsComeFromTheRulesArray() {
        val text = File("src/main/assets/posture/rules_floor_v0.json").readText().replace("\r\n", "\n")   // Windows 작업 트리는 CRLF
        assertTrue(text.contains("\"version\": \"floor_v0.5\""))
        // sanity 는 rules 배열 집계가 정본(헤더 counts 는 배열에서 다시 적는다) — 16규칙 = beta 12 + exclude 4
        val statuses = Regex("\"status\": \"(\\w+)\"").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(16, statuses.size)
        assertEquals(12, statuses.count { it == "beta" }); assertEquals(4, statuses.count { it == "exclude" })
        assertTrue(text.contains("\"counts\": {\n  \"beta\": 12,\n  \"exclude\": 4\n }"))
        val hip = text.substringAfter("\"id\": \"floor|플랭크|몸통과 엉덩이의 정렬 유지\"").substringBefore("\"reference\"")
        assertTrue(hip.contains("\"lower\": -0.08")); assertTrue(hip.contains("\"upper\": 0.16"))
        val neck = text.substringAfter("\"id\": \"floor|플랭크|목과 몸통의 정렬 유지\"").substringBefore("\"feature\"")
        assertTrue(neck.contains("\"status\": \"exclude\""))
        // 설계 §5 의 v0.5 정의 — 레그 레이즈 고개 window 규칙(MP 측면 정상 오탐 17.3 %, 방향 Q11 미결)은 exclude(리뷰 2026-10-07: 버전만 v0.5 이고 beta 로 남아 있었다)
        val head = text.substringAfter("\"id\": \"floor|라잉 레그 레이즈|고개 숙임 여부\"").substringBefore("\"feature\"")
        assertTrue(head.contains("\"status\": \"exclude\""))
        // 활성 규칙이 0 인 레그 레이즈의 측정 문장은 그 종목의 말이다(팔 들림 아님)
        val lr = FloorFeedbackController("라잉 레그 레이즈", listOf(rule("라잉 레그 레이즈", "window", "head_trunk_ang", status = RuleStatus.EXCLUDE)))
        assertEquals("다리(허벅지) 들림을 기록하고 있어요 · 참고", lr.update(1_000, mapOf("hip_ang" to 170f), null, emptyList()).message)
    }

    @Test fun plankVoiceAfterClockFallbackIsOnlyTheFallbackLine() {
        // 리뷰 2026-10-07: 폴백 뒤에도 '시간을 재기 시작해요'·'…시간을 멈췄어요' 가 나갔는데 남은 시간은 벽시계로 줄었다
        for (k in PlankHoldEvent.Kind.entries) {
            assertTrue("$k 카메라 시간", HoldVoice.voiced(k, PlankHoldClock.SOURCE_CAMERA))
            assertEquals("$k 시계 폴백 뒤", k == PlankHoldEvent.Kind.FALLBACK, HoldVoice.voiced(k, PlankHoldClock.SOURCE_CLOCK))
        }
        // 폴백 뒤 START 가 와도(시계는 사건을 낸다 — 로그용) 소리는 없다
        val c = PlankHoldClock(); c.start(0); var t = 0L
        val events = ArrayList<PlankHoldEvent>()
        repeat(70) { events += c.onFrame(t, null); t += 300 }
        repeat(10) { events += c.onFrame(t, mapOf(FloorChain.CHAIN to 1f, FloorChain.KNEE to 175f, FloorChain.HIP_FLOOR to .4f, FloorChain.AXIS_H to 5f)); t += 300 }
        val start = events.single { it.kind == PlankHoldEvent.Kind.START }
        assertEquals(PlankHoldClock.SOURCE_CLOCK, c.source); assertFalse(HoldVoice.voiced(start.kind, c.source))
    }

    @Test fun spokenRejectionRecoveryIsOffUntilRecoveryContext() {
        // §100 의 '말한 사유의 다음 센 회 = 교정됐어요' 는 쪽·사유·여유를 보지 않아 10-08 폰에서 틀린 칭찬을 했다 → 사용자 결정 U0 로 F1 RecoveryContext 전까지 끔.
        // 말한 사유는 그대로 말하고(이유 음성·틱은 종전), 교정 대기에만 넣지 않는다
        assertFalse(RejectCueGate.RECOVERY_ENABLED)
        val src = object : IdentityCueSource { override fun cueFor(reason: String) = "이유 $reason" }
        val g = RejectCueGate()
        assertNull("말한 사유가 없으면 교정도 없다", g.takeRecovery())
        assertEquals("이유 a", g.next(10_000, "a", src, silent = false, perReason = true).speech)
        assertNull("말한 사유도 교정 대기에 넣지 않는다", g.awaitingRecovery); assertNull(g.takeRecovery())
        assertNull("간격 안은 틱만", g.next(12_000, "a", src, silent = false, perReason = true).speech)
        assertNull("음소거", g.next(30_000, "a", src, silent = true, perReason = true).speech)
        g.next(40_000, "b", src, silent = false, perReason = true); g.reset(); assertNull("새 세트", g.takeRecovery())
    }

    @Test fun plankResumeSpeaksOnlyAfterASpokenPostureStop() {
        // §100: 이유를 말한 자세 멈춤(무릎·골반·솟음)의 재개 = "좋아요, 교정됐어요. 다시 재요.", 화면 밖·일어섬·말하지 않은 멈춤은 종전처럼 톤만
        assertEquals(PlankHoldClock.RESUME_CUE, HoldVoice.resumeCue(PlankHoldClock.HIPS_LOW))
        assertEquals(PlankHoldClock.RESUME_CUE, HoldVoice.resumeCue(PlankHoldClock.KNEES_DOWN))
        assertEquals(PlankHoldClock.RESUME_CUE, HoldVoice.resumeCue(PlankHoldClock.PIKE))
        assertNull(HoldVoice.resumeCue(PlankHoldClock.OUT_OF_VIEW)); assertNull(HoldVoice.resumeCue(PlankHoldClock.NOT_PRONE)); assertNull(HoldVoice.resumeCue(null))
    }

    @Test fun plankGazeSpeaksAfterSustainedHeadViolationAndRecoversOnce() {
        // §100: 정렬 고개 항목이 HOLD·측면 칸에서 위반으로 2 s 더 이어지면 말하고(15 s 에 한 번), OK 가 1 s 이어지면 교정 한 번. 유보·HOLD 아님은 지속을 끊는다
        val g = PlankGazeVoice(); val head = plankHead()
        fun snap(verdict: Verdict, side: Int, held: Boolean = true, ready: Boolean = true, value: Float? = 40f) =
            AlignmentSnapshot(listOf(AlignmentItem(head, value, verdict, side)), placementReady = ready, held = held)
        assertNull(g.frame(0, snap(Verdict.VIOLATION, 1))); assertNull(g.frame(1_500, snap(Verdict.VIOLATION, 1)))
        assertEquals(PlankGazeVoice.cueFor(1), g.frame(2_100, snap(Verdict.VIOLATION, 1)))
        assertTrue(PlankGazeVoice.cueFor(1).contains("양손 사이 바닥")); assertTrue(PlankGazeVoice.cueFor(-1).contains("살짝 앞"))
        assertNull("쿨다운 안", g.frame(5_000, snap(Verdict.VIOLATION, 1)))
        assertNull(g.frame(6_000, snap(Verdict.OK, 0)))
        assertEquals(PlankGazeVoice.RECOVERED_CUE, g.frame(7_100, snap(Verdict.OK, 0)))
        assertNull("한 번만", g.frame(8_000, snap(Verdict.OK, 0)))
        assertNull(g.frame(20_000, snap(Verdict.VIOLATION, -1, held = false)))
        assertNull(g.frame(21_000, snap(Verdict.VIOLATION, -1)))
        assertNull(g.frame(22_000, snap(Verdict.ABSTAIN, -1, value = null)))
        assertNull(g.frame(23_500, snap(Verdict.VIOLATION, -1)))
        assertEquals("유보로 끊겨 다시 2 s", PlankGazeVoice.cueFor(-1), g.frame(25_600, snap(Verdict.VIOLATION, -1)))
        assertNull("준비 안 된 칸은 보지 않는다", g.frame(26_000, snap(Verdict.OK, 0, ready = false)))
        assertEquals(2, g.spoken.count { it.second != PlankGazeVoice.RECOVERED_CUE })
    }

    @Test fun floorGazeSpeaksAfterTwoWrongRepsAndNeverBlocks() {
        // §101d(사용자 정의): 센 회의 gaze(얼굴 요 다수결)가 화면 쪽·반대쪽이면 틀린 시선 — 문장은 하나(벗어남 + 정상 시선 안내, 사용자 결정 10-10 오후). 연속 2회까지는 두고 3회째부터 말한다(12 s 에 한 번).
        // 교정은 벗어나지 않은 아는 회(정면 또는 화면 밖 — 측면 폰은 옆얼굴을 못 찾아 정면이 거의 관측되지 않는다). 모름은 연속을 끊지 않되 세지도 않는다
        val g = FloorGazeVoice()
        fun rep(t: Long, gaze: Int) = FloorRep(t, t - 2000, t - 1000, 0f, 20f, 300, 700, false, gaze = gaze)
        assertNull(g.rep(1_000, rep(1_000, FloorGaze.OFF_SCREEN), FloorProfile.CRUNCH))
        assertNull("1회째 — 둔다", g.rep(3_000, rep(3_000, FloorGaze.CAMERA), FloorProfile.CRUNCH))
        assertNull("2회째 — 둔다", g.rep(5_000, rep(5_000, FloorGaze.AWAY), FloorProfile.CRUNCH))
        assertEquals("3회째 — 종목 문장", FloorGazeVoice.CRUNCH_CUE, g.rep(7_000, rep(7_000, FloorGaze.CAMERA), FloorProfile.CRUNCH))
        assertEquals(FloorGaze.CAMERA, g.lastWrong)
        assertNull("같은 회를 두 번 주면 무시", g.rep(7_000, rep(7_000, FloorGaze.CAMERA), FloorProfile.CRUNCH))
        assertNull(g.rep(9_000, rep(9_000, FloorGaze.UNKNOWN), FloorProfile.CRUNCH))
        assertEquals("지적 뒤 화면 밖 회 = 돌아왔다는 말", FloorGazeVoice.RECOVERED_CUE, g.rep(11_000, rep(11_000, FloorGaze.OFF_SCREEN), FloorProfile.CRUNCH))
        assertEquals(FloorGaze.UNKNOWN, g.lastWrong)
        assertNull(g.rep(13_000, rep(13_000, FloorGaze.OFF_SCREEN), FloorProfile.CRUNCH))
        assertNull(g.rep(15_000, rep(15_000, FloorGaze.AWAY), FloorProfile.CRUNCH)); assertNull(g.rep(16_000, rep(16_000, FloorGaze.AWAY), FloorProfile.CRUNCH))
        assertNull("쿨다운 12 s 안", g.rep(17_000, rep(17_000, FloorGaze.AWAY), FloorProfile.CRUNCH))
        assertEquals("레그 레이즈 문장(반대쪽도 같은 문장)", FloorGazeVoice.LEG_RAISE_CUE, g.rep(24_000, rep(24_000, FloorGaze.AWAY), FloorProfile.LEG_RAISE))
        assertEquals(FloorGaze.AWAY, g.lastWrong)
        assertEquals("정면 회가 교정", FloorGazeVoice.RECOVERED_CUE, g.rep(28_000, rep(28_000, FloorGaze.FRONT), FloorProfile.LEG_RAISE))
        assertTrue(FloorGazeVoice.isRecovery(FloorGazeVoice.RECOVERED_CUE)); assertFalse(FloorGazeVoice.isRecovery(FloorGazeVoice.CRUNCH_CUE))
        assertTrue(FloorGazeVoice.CRUNCH_CUE.startsWith("시선이 벗어났어요")); assertTrue(FloorGazeVoice.LEG_RAISE_CUE.contains("천장"))
        assertEquals("화살표는 가야 할 곳 — 크런치는 무릎", MotionAnchor.KNEES, FloorGazeVoice.motionFor(FloorProfile.CRUNCH).target)
        assertEquals("레그 레이즈는 천장(중력 위)", MotionKind.UP, FloorGazeVoice.motionFor(FloorProfile.LEG_RAISE).high)
    }

    @Test fun plankGazeSpeaksWhenTheHeadTurnsForThreeSeconds() {
        // §101d: 버티는 칸에서 틀린 시선(화면 쪽·반대쪽)이 3 s 이어지면 그 상태의 문장, 1 s 동안 정면이면 교정 한 번. 고개 위·아래 항목과 쿨다운을 나눠 쓴다
        val g = PlankGazeVoice(); val head = plankHead()
        fun snap(v: Verdict, side: Int, held: Boolean = true) = AlignmentSnapshot(listOf(AlignmentItem(head, 10f * side, v, side)), placementReady = true, held = held)
        assertNull(g.frame(0, snap(Verdict.OK, 0), FloorGaze.CAMERA))
        assertNull(g.frame(2_000, snap(Verdict.OK, 0), FloorGaze.CAMERA))
        assertEquals(PlankGazeVoice.TURN_CUE, g.frame(3_100, snap(Verdict.OK, 0), FloorGaze.CAMERA))
        assertEquals(0, g.lastSide); assertEquals(FloorGaze.CAMERA, g.lastWrong)
        assertEquals("화살표는 코에서 양손 사이로", MotionAnchor.WRISTS, PlankGazeVoice.motionFor(0).target)
        assertNull(g.frame(4_000, snap(Verdict.OK, 0), FloorGaze.FRONT))
        assertEquals(PlankGazeVoice.RECOVERED_CUE, g.frame(5_100, snap(Verdict.OK, 0), FloorGaze.FRONT))
        assertNull("멈춘 칸(held=false)은 보지 않는다", g.frame(6_000, snap(Verdict.OK, 0, held = false), FloorGaze.AWAY))
        assertNull(g.frame(10_000, snap(Verdict.OK, 0, held = false), FloorGaze.AWAY))
        // 쿨다운 15 s 뒤 반대쪽 3 s → 반대쪽 문장. 모름은 지속을 끊지 않는다
        assertNull(g.frame(20_000, snap(Verdict.OK, 0), FloorGaze.AWAY)); assertNull(g.frame(21_500, snap(Verdict.OK, 0), FloorGaze.UNKNOWN))
        assertEquals("반대쪽도 같은 문장(사용자 결정 10-10 오후)", PlankGazeVoice.TURN_CUE, g.frame(23_100, snap(Verdict.OK, 0), FloorGaze.AWAY))
        assertEquals(FloorGaze.AWAY, g.lastWrong)
        // 지적 뒤 화면 밖 1 s → 돌아왔다(측면 폰은 옆얼굴을 못 찾는다, 10-10)
        assertNull(g.frame(24_000, snap(Verdict.OK, 0), FloorGaze.OFF_SCREEN))
        assertEquals(PlankGazeVoice.RECOVERED_CUE, g.frame(25_100, snap(Verdict.OK, 0), FloorGaze.OFF_SCREEN))
        assertTrue(PlankGazeVoice.isRecovery(PlankGazeVoice.RECOVERED_CUE)); assertEquals(FloorGaze.UNKNOWN, g.lastWrong)
    }

    @Test fun notProneStopHasNoTickWhileOtherStopsTickAndSpeak() {
        val c = PlankHoldClock(); val gate = RejectCueGate()
        assertEquals("일어섬·앉음은 5 s 뒤 한 번 말한다 — 이유 없는 낮은 틱을 내지 않는다", RejectCueGate.Cue(false, null), HoldVoice.stopCue(gate, 1_000, PlankHoldClock.NOT_PRONE, c))
        val knees = HoldVoice.stopCue(gate, 2_000, PlankHoldClock.KNEES_DOWN, c)
        assertTrue(knees.tick); assertEquals(PlankHoldClock.cueFor(PlankHoldClock.KNEES_DOWN), knees.speech)
        assertNull("같은 사유 6 s 안 — 틱만", HoldVoice.stopCue(gate, 4_000, PlankHoldClock.KNEES_DOWN, c).speech)
    }

    @Test fun floorBigLineNeverFallsBackToPlacementWhileMeasuring() {
        // 리뷰 2026-10-07: beta 확인 필요·범위 복귀 동안 큰 줄이 '옆모습을 화면에 담아 주세요.' 로 바뀌어 제대로 찍힌 사용자가 폰을 옮겼다
        for (p in listOf(FloorFeedbackPhase.ATTENTION, FloorFeedbackPhase.RECOVERED))
            assertEquals(FloorReasons.MEASURING_LINE, FloorReasons.liveMessage(null, FloorFeedback(p, "골반 정렬이 참고 범위를 벗어났어요")))
        assertEquals(FloorReasons.PLACEMENT_LINE, FloorReasons.liveMessage(null, null))
        assertEquals("관절이 안 보여요", FloorReasons.liveMessage(null, FloorFeedback(FloorFeedbackPhase.UNAVAILABLE, "관절이 안 보여요")))
        assertEquals("안내가 먼저", "발목까지", FloorReasons.liveMessage("발목까지", FloorFeedback(FloorFeedbackPhase.ATTENTION, "x")))
        // 플랭크 대기 상태 줄은 사유에 맞는 재개 조건 — 엉덩이를 높이 든 사용자에게 '골반을 들면' 이라고 하지 않는다
        assertEquals("대기 · 엉덩이를 내리면 재기 시작해요", FloorReasons.holdStatus(PlankHoldClock.Phase.WAIT, PlankHoldClock.PIKE))
        assertEquals("대기 · 무릎을 펴면 재기 시작해요", FloorReasons.holdStatus(PlankHoldClock.Phase.WAIT, PlankHoldClock.KNEES_DOWN))
        assertEquals("대기 · 플랭크 자세가 보이면 재요", FloorReasons.holdStatus(PlankHoldClock.Phase.WAIT, PlankHoldClock.NOT_PRONE))
        // 시계가 재는 칸인데 정렬 준비가 안 됨 — 정렬만 유보, '자세를 잡으라' 고 하지 않는다. 시계가 확인 전이면 정렬 대신 시계 상태
        val pf = FloorFeedbackController("플랭크", listOf(plankHip()))
        val heldNotPlaced = pf.update(1_000, emptyMap(), null, emptyList(), alignment = AlignmentSnapshot(emptyList(), placementReady = false, held = true))
        assertEquals(FloorFeedbackPhase.MEASURING, heldNotPlaced.phase); assertTrue(heldNotPlaced.message.contains("시간은 계속 재요"))
        assertFalse(heldNotPlaced.message.contains("자세를 잡아"))
        val notHeld = pf.update(2_000, emptyMap(), null, emptyList(), alignment = AlignmentSnapshot(emptyList(), placementReady = false, held = false))
        assertTrue(notHeld.message.startsWith("플랭크가 확인되면"))
    }

    @Test fun retractedFirstRepLeavesTheUnitLedgerAndKeepsSameFrameRejections() {
        // 리뷰 2026-10-07: 거둘 때 repRecords·rc.reps 는 지우면서 표시 단위 원장은 남겨 리포트 '센 회'·로그 completed 가 거둔 회까지 셌다
        val rc = RepCounter.forSession("크런치", floor = true)!!
        val acc = RepUnitAccumulator(RepUnit.CYCLE)
        val records = ArrayList<RepRecord>()
        var seen = 0; var t = 0L
        fun f(m: Map<String, Float>) {
            if (rc.onFrameFeatures(t, m)) RepUnitAccumulator.onCounterFrame(rc, t, records, acc)
            if (rc.newlyRetracted) seen = RepUnitAccumulator.onCounterRetracted(rc, records, acc, seen)
            seen = RejectionCues.handle(rc, null, seen, t, RejectCueGate(), silent = false).seen
            t += 300
        }
        repeat(4) { f(crunch(0f)) }
        listOf(6f, 12f, 18f, 12f, 6f, 0f, 0f).forEach { f(crunch(it)) }
        assertEquals(1, acc.completed)
        repeat(30) { f(crunch(0f)) }                                 // 9 s — 첫 회를 거둔다
        assertEquals(0, rc.reps); assertEquals("원장에서도 뺀다", 0, acc.completed); assertTrue(records.isEmpty())
        repeat(2) { listOf(6f, 12f, 18f, 12f, 6f, 0f, 0f).forEach { f(crunch(it)) } }
        assertEquals(2, rc.reps); assertEquals("리포트 수 = 화면 수", rc.reps, acc.completed)
        // 거둠과 같은 칸의 새 기각은 처리 수가 넘어가지 않는다 — 그 틱·이유가 빠지지 않게
        val probe = RepCounter.forSession("크런치", floor = true)!!
        probe.rejectedReps.clear()
        assertEquals("바닥은 처리 수를 그대로 돌려준다", 3, RepUnitAccumulator.onCounterRetracted(probe, ArrayList(), RepUnitAccumulator(RepUnit.CYCLE), 3))
    }

    @Test fun silentLegReasonsGetNoTickOrSpeechButRealReasonsDo() {
        // §101 사이드 런지 blip·edge: 관측이 무너진 사이클은 틱도 말도 없다 — 유령에 틱을 내면 '세지 않은 동작을 했다' 는 거짓 신호
        val g = RejectCueGate(); val src = LegCycleTracker(LegProfile.SIDE)
        assertNull(g.next(0, "blip", src, silent = false, perReason = false).speech); assertFalse(g.next(0, "blip", src, silent = false, perReason = false).tick)
        assertNotNull(g.next(10_000, "shallow", src, silent = false, perReason = false).speech)
    }
}
