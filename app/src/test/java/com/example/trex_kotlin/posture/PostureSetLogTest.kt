package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date

/** 세트 로그 직렬화/저장 테스트 — 재보정 도구(calibrate_from_logs.py)가 읽는 형식과 맞아야 한다. */
class PostureSetLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(detected: Boolean, features: Map<String, Float>, inferMs: Long = 60L, fromGravity: Boolean = true): PoseSample =
        if (!detected) PoseSample.empty(inferMs = inferMs, w = 640, h = 480)
        else PoseSample(
            detected = true,
            normalizedXy = FloatArray(MP_LANDMARK_COUNT * 2),
            visibility = FloatArray(MP_LANDMARK_COUNT) { 0.9f },
            features = features,
            visibleJointCount = 30,
            inferMs = inferMs,
            imageWidth = 640,
            imageHeight = 480,
            up = if (fromGravity) Vec3(0.1f, 1f, 0f) else SCREEN_UP,
            upFromGravity = fromGravity,
        )

    private fun rule(id: String) = PostureRule(
        id = id, exercise = "바벨 스쿼트", condition = "발과 무릎의 방향 일치", subtype = null, status = RuleStatus.SHIP, reason = null,
        feature = "knee_out_mean__mean", baseFeature = "knee_out_mean", stat = "mean", family = "knee_out", op = "<", threshold = 0.0076f,
        view = "C", viewDesc = "정면", cvAuc = 0.97f, cvBalacc = 0.91f, sampleN = 60, mirrorSafe = true, cautions = emptyList(),
    )

    @Test
    fun encodesExpectedSchemaAndValues() {
        val samples = listOf(
            sample(true, mapOf("knee_out_mean" to 0.012345f, "knee_L" to 95.5f)),
            sample(false, emptyMap()),
            sample(true, mapOf("knee_out_mean" to -0.02f, "knee_L" to Float.NaN)),
        )
        val results = listOf(RuleResult(rule("바벨 스쿼트|발과 무릎의 방향 일치"), Verdict.VIOLATION, -0.0038f, 2))
        val log = SetLog.build(
            exercise = "바벨 스쿼트", samples = samples, results = results, rulesVersion = "mp_v0",
            model = "full", delegate = "GPU", frontCamera = true, sampleIntervalMs = 300L,
            subjectId = "user-1", note = "say \"hi\"\n", now = Date(0L),
        )
        val json = SetLogJson.encode(log)

        assertTrue(json.startsWith("{\"schema\":\"trex.posture.setlog/1\""))
        assertTrue(json.contains("\"created_at\":\"1970-01-01T00:00:00Z\""))
        assertTrue(json.contains("\"exercise\":\"바벨 스쿼트\""))
        assertTrue(json.contains("\"subject_id\":\"user-1\""))
        assertTrue(json.contains("\"front_camera\":true"))
        assertTrue(json.contains("\"up_from_gravity\":true"))
        assertTrue(json.contains("\"sample_interval_ms\":300"))
        // 문자열 이스케이프
        assertTrue(json.contains("\"note\":\"say \\\"hi\\\"\\n\""))
        // 프레임: 시간은 샘플 간격으로 합성, 미검출 프레임은 features 비어 있음, NaN 은 null
        assertTrue(json.contains("\"t_ms\":0,"))
        assertTrue(json.contains("\"t_ms\":300,"))
        assertTrue(json.contains("\"t_ms\":600,"))
        assertTrue(json.contains("\"features\":{}"))
        assertTrue(json.contains("\"knee_out_mean\":0.01235"))
        assertTrue(json.contains("\"knee_L\":null"))
        // 가시성 배열 33개
        val visIdx = json.indexOf("\"vis\":[")
        assertTrue(visIdx > 0)
        val visEnd = json.indexOf(']', visIdx)
        assertEquals(MP_LANDMARK_COUNT, json.substring(visIdx + 7, visEnd).split(',').size)
        // 결과
        assertTrue(json.contains("\"rule_id\":\"바벨 스쿼트|발과 무릎의 방향 일치\""))
        assertTrue(json.contains("\"verdict\":\"VIOLATION\""))
        assertTrue(json.contains("\"value\":-0.0038"))
        assertTrue(json.endsWith("]}"))
        // 한 줄(JSON Lines) 이어야 한다
        assertFalse(json.contains('\n'))
        assertFalse(json.contains("NaN"))
        assertTrue(log.setId.startsWith("19700101T000000-"))
        assertEquals(true, log.upFromGravity)
        assertTrue(log.tiltDeg != null && log.tiltDeg!! > 5f && log.tiltDeg!! < 6.5f) // atan(0.1) ≈ 5.7°
    }

    @Test
    fun encodesRepRecordsAndMode() {
        // §29: 렙별 극값·ROM 판정 배열 + 세션 모드 — 후반 드리프트(피로) 오프라인 분석의 원자재
        val samples = List(3) { sample(true, mapOf("knee_L" to 90f)) }
        val log = SetLog.build(
            "푸시업", samples, emptyList(), "floor_v0", "full", "GPU", true, 300L, now = Date(0L),
            repCount = 3, repTimesMs = listOf(1000L, 4000L, 7300L), repSignal = "wrist_shoulder_d", repInvalid = 1,
            repRecords = listOf(
                RepRecord(1000L, 0.45f, 1.40f, true),
                RepRecord(4000L, 0.85f, 1.38f, false),
                RepRecord(7300L, 0.50f, 1.41f, null),   // ROM 기준 없는 종목 = null
            ),
            mode = "track",
        )
        val json = SetLogJson.encode(log)
        assertTrue(json.contains("\"mode\":\"track\""))
        assertTrue(json.contains("\"count\":3"))
        assertTrue(json.contains("\"invalid\":1"))
        assertTrue(json.contains("\"t_ms\":[1000,4000,7300]"))
        assertTrue(json.contains("\"min\":[0.45,0.85,0.5]"))
        assertTrue(json.contains("\"max\":[1.4,1.38,1.41]"))
        assertTrue(json.contains("\"valid\":[true,false,null]"))
        // 구버전 호환: repRecords/mode 를 안 주면 필드 자체가 없다 (부재 = 미적용)
        val old = SetLog.build("푸시업", samples, emptyList(), "floor_v0", "full", "GPU", true, 300L, now = Date(0L), repCount = 2)
        val oj = SetLogJson.encode(old)
        assertFalse(oj.contains("\"min\":"))
        assertFalse(oj.contains("\"mode\":"))
        // §58 단계 0 필드도 안 주면 키 자체가 없다 — "필드 부재 = 이전 로그" 가 그대로 성립
        for (key in listOf("engine", "config", "resets", "pending", "thermal", "app_version")) assertFalse(key, oj.contains("\"$key\":"))
    }

    @Test
    fun encodesStageZeroCounterConfigResetsThermalAndVersion() {
        // spec §58 단계 0: 그 세트를 어떤 카운터가 어떤 구성으로 셌는지, 언제 사이클을 버렸는지, 열 상태가 어떻게 바뀌었는지
        val samples = List(3) { sample(true, mapOf("knee_mean" to 170f)) }
        val counter = RepCounter.forSession("바벨 스쿼트", floor = false)!!
        val engine = RepEngineLog.of(counter)
        // 로그의 구성은 복사한 상수가 아니라 forSession 이 만든 카운터가 실제로 쓰는 값이다 — 세션 구성이 바뀌면 로그도 따라 바뀐다.
        assertEquals(counter.effectiveRefractoryMs, engine.refractoryMs)
        assertEquals(counter.effectiveMaxGapMs, engine.maxGapMs)
        assertEquals(counter.effectiveCompleteOnReturn, engine.completeOnReturn)
        val log = SetLog.build(
            "바벨 스쿼트", samples, emptyList(), "mp_v0", "full", "GPU", true, 300L, now = Date(0L),
            repCount = 2, repTimesMs = listOf(3000L, 6000L), repSignal = "knee_mean", repInvalid = 0,
            repEngine = engine,
            repResets = listOf(RepResetEvent(4200L, "pause", afterTMs = 4050L), RepResetEvent(9100L, "camera_switch", afterTMs = 9120L)),
            thermalStart = 0, thermalChanges = listOf(ThermalEvent(6000L, 2)),
            appVersion = "1.1.0-preview.2",
        )
        val json = SetLogJson.encode(log)
        assertTrue(json.contains("\"app_version\":\"1.1.0-preview.2\""))
        assertTrue(json.contains("\"thermal\":{\"start\":0,\"changes\":[{\"t_ms\":6000,\"status\":2}]}"))
        assertTrue(json.contains("\"engine\":\"return_v1\""))
        // 지금 앱의 복귀형 구성(forSession: 불응기 1.2 s · 끊김 1.5 s · 복귀 완료) — polarity 가 없으면(레거시) 새 코어 키도 없다
        assertTrue(json.contains("\"config\":{\"feature\":\"knee_mean\",\"min_amp\":35,\"refractory_ms\":1200,\"max_gap_ms\":1500," +
            "\"complete_on_return\":true,\"rom_direction\":\"min\",\"rom_threshold\":97.8905,\"rom_tier\":\"reference\"}"))
        // 리셋은 누른 시각(t_ms)과 그 직전에 카운터가 본 마지막 프레임(after_t_ms)을 함께 — 추론 중에 누른 전환은 after 가 t 보다 늦을 수 있다
        assertTrue(json.contains("\"resets\":[{\"t_ms\":4200,\"after_t_ms\":4050,\"reason\":\"pause\"}," +
            "{\"t_ms\":9100,\"after_t_ms\":9120,\"reason\":\"camera_switch\"}]"))
        // 레거시 경로는 미완 후보를 노출하지 않는다 — 없는 정보를 빈 값으로 지어내지 않는다
        assertFalse(json.contains("\"pending\""))
        assertFalse(json.contains("\"polarity\""))
        assertFalse(json.contains('\n'))
        assertTrue(json.endsWith("]}"))

        // 런지: 신호 교체와 함께 ROM 을 뗐다 → rom_direction/threshold 없음, 단계 none. 리셋이 없던 세트는 빈 목록.
        val lunge = SetLog.build("스텝 포워드 다이나믹 런지", samples, emptyList(), "mp_v0", "full", "GPU", true, 300L, now = Date(0L),
            repCount = 0, repEngine = RepEngineLog.of(RepCounter.forSession("스텝 포워드 다이나믹 런지", floor = false)!!),
            repResets = listOf(RepResetEvent(-300L, "pause", afterTMs = null)), thermalStart = 1)
        val lj = SetLogJson.encode(lunge)
        assertTrue(lj.contains("\"config\":{\"feature\":\"knee_mean\",\"min_amp\":35,\"refractory_ms\":1200,\"max_gap_ms\":1500,\"complete_on_return\":true,\"rom_tier\":\"none\"}"))
        // 첫 프레임 전의 일시정지: 음수 t_ms, 카운터가 본 프레임이 없으니 after_t_ms = null(재생은 첫 프레임 앞에서 리셋)
        assertTrue(lj.contains("\"resets\":[{\"t_ms\":-300,\"after_t_ms\":null,\"reason\":\"pause\"}]"))
        val none = SetLogJson.encode(SetLog.build("바벨 스쿼트", samples, emptyList(), "mp_v0", "full", "GPU", true, 300L, now = Date(0L),
            repCount = 0, repEngine = engine, repResets = emptyList()))
        assertTrue(none.contains("\"resets\":[]"))
        assertTrue(lj.contains("\"thermal\":{\"start\":1,\"changes\":[]}"))
    }

    @Test
    fun encodesNewCoreConfigAndUncountedCandidates() {
        // 새 코어(폰 검증 전 비활성)로 셌다면: 설계값과 세지 않은 후보·버린 사이클을 남긴다 (설계 §4.9)
        val samples = List(3) { sample(true, mapOf("knee_mean" to 170f)) }
        val core = RepCounter(RepSignals.byExercise.getValue("바벨 스쿼트").copy(polarity = RepPolarity.DOWN), maxGapMs = 1500L, completeOnReturn = true)
        val engine = RepEngineLog.of(core)
        assertEquals(RepEngineLog.ENGINE_HYSTERESIS, engine.engine)
        // 새 코어는 생성자의 불응기(기본 1.2 s)가 아니라 코어의 0.8 s 를 쓴다 — 로그도 그 값을 적는다
        assertEquals(RepHysteresis.DEFAULT_REFRACTORY_MS, engine.refractoryMs)
        assertEquals(core.effectiveRefractoryMs, engine.refractoryMs)
        val log = SetLog.build(
            "바벨 스쿼트", samples, emptyList(), "mp_v0", "full", "GPU", true, 300L, now = Date(0L),
            repCount = 0, repEngine = engine,
            repPending = RepPendingState(RepCycle(5000L, 3000L, 80f, 170f), RepCandidate(9000L, 90f, 168f)),
            repDropped = listOf(RepCycle(1000L, 200L, 100f, 170f, byRedescent = true)),
        )
        val json = SetLogJson.encode(log)
        assertTrue(json.contains("\"engine\":\"hysteresis_v1\""))
        // 설계 v2 결정값(§4.2·§4.3): 불응기 0.8 s · maxGap 1.5 s · f 0.25 · 첫 쌍 8 s · 이후 진폭 비만(null) · 비 0.5~2
        assertTrue(json.contains("\"refractory_ms\":800,\"max_gap_ms\":1500,\"complete_on_return\":true,\"polarity\":\"down\"," +
            "\"return_fraction\":0.25,\"first_pair_window_ms\":8000,\"later_window_ms\":null,\"min_ratio\":0.5,\"max_ratio\":2"))
        assertTrue(json.contains("\"pending\":{\"unconfirmed\":{\"t_ms\":5000,\"start_t_ms\":3000,\"min\":80,\"max\":170,\"by_redescent\":false}," +
            "\"in_progress\":{\"start_t_ms\":9000,\"min\":90,\"max\":168}," +
            "\"dropped\":[{\"t_ms\":1000,\"start_t_ms\":200,\"min\":100,\"max\":170,\"by_redescent\":true}]}"))
        // 새 코어였는데 남은 후보가 없으면 객체는 남고 안쪽이 null — "후보 없음" 과 "정보 없음(레거시)" 을 가른다
        val empty = SetLogJson.encode(SetLog.build("바벨 스쿼트", samples, emptyList(), "mp_v0", "full", "GPU", true, 300L, now = Date(0L),
            repCount = 0, repEngine = engine, repPending = RepPendingState(null, null)))
        assertTrue(empty.contains("\"pending\":{\"unconfirmed\":null,\"in_progress\":null,\"dropped\":[]}"))
    }

    /**
     * §58 단계 0 골든 줄 — 연구 도구(`research/external_rep_replay/setlog_captures.py --self-test`)가 **이 파일을 그대로** 읽어
     * 변환하고, 파이썬 인코더 사본(`encode_setlog`)이 같은 입력에서 바이트까지 같은 줄을 내는지 본다. 그래서 키 이름·순서·숫자 형식이
     * 한쪽만 바뀌면 두 테스트 중 하나가 깨진다(Gate A 재생 파리티가 이 형식에 기댄다). 인코더를 의도적으로 바꾸면 이 테스트가 내는
     * 실제 줄로 `app/src/test/resources/setlog_s58_fixture.txt` 를 갱신하고, 파이썬 사본도 같이 고친다.
     */
    @Test
    fun stageZeroLinesMatchTheGoldenFixtureTheResearchReaderIsTestedOn() {
        val golden = javaClass.classLoader!!.getResourceAsStream("setlog_s58_fixture.txt")!!
            .bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val lines = goldenLogs().map { SetLogJson.encode(it) }
        assertEquals(golden.size, lines.size)
        lines.forEachIndexed { i, line -> assertEquals("골든 줄 ${i + 1}", golden[i], line) }
    }

    /** 골든 줄의 입력 — 파이썬 `setlog_captures._golden_logs()` 와 같은 값이다(둘 중 하나만 고치면 골든 비교가 깨진다). */
    private fun goldenLogs(): List<SetLog> {
        fun frame(t: Long, knee: Float) = SetLogFrame(t, 55L, 33, null,
            linkedMapOf("knee_L" to knee + 1.2f, "knee_R" to knee - 1.2f, "knee_mean" to knee, "knee_minside" to knee - 1.2f))
        val frames = listOf(frame(0L, 170f), frame(300L, 131.5f), frame(600L, 92.25f), frame(900L, 168f))
        val abstain = listOf(SetLogResult("x|y", "ABSTAIN", null, 0))
        val base = SetLog(
            setId = "20260924T020000-gold0001", createdAtIso = "2026-09-24T02:00:30Z", subjectId = "s-test0001",
            exercise = "바벨 스쿼트", rulesVersion = "mp_v0.1", model = "full", delegate = "GPU", frontCamera = false,
            upFromGravity = true, tiltDeg = 2.4f, sampleIntervalMs = 300L, frames = frames, results = abstain,
            note = "session:기본 스쿼트 assessment_end_ms=900 ", upFlippedFrames = 0, upVerifiedFrames = frames.size,
            repCount = 1, repTimesMs = listOf(900L), repSignal = "knee_mean", repInvalid = 0,
            repMins = listOf(92.25f), repMaxs = listOf(170f), repValid = listOf(true),
            mode = "coach", anchorTMs = 900L, assessmentEndTMs = 900L, measurements = listOf("참고 · 골든"),
            viewYawDeg = 3.1f, viewR = 0.97f, viewClass = "C", viewFrames = frames.size,
            repEngine = RepEngineLog.of(RepCounter.forSession("바벨 스쿼트", floor = false)!!),
            // 첫 프레임 전의 전환(카운터가 본 프레임 없음 → after null)과 추론 중에 누른 일시정지(after 가 누른 시각보다 앞 프레임)
            repResets = listOf(RepResetEvent(-120L, "camera_switch", afterTMs = null), RepResetEvent(410L, "pause", afterTMs = 300L)),
            thermalStart = 0, thermalChanges = listOf(ThermalEvent(600L, 1)), appVersion = "0.9.1-debug",
        )
        val core = RepCounter(RepSignals.byExercise.getValue("바벨 스쿼트").copy(polarity = RepPolarity.DOWN), maxGapMs = 1500L, completeOnReturn = true)
        val newCore = base.copy(
            setId = "20260924T020500-gold0002", createdAtIso = "2026-09-24T02:05:30Z", mode = "track",
            repCount = 0, repTimesMs = emptyList(), repMins = emptyList(), repMaxs = emptyList(), repValid = emptyList(),
            repEngine = RepEngineLog.of(core), repResets = emptyList(),
            repPending = RepPendingState(RepCycle(900L, 300L, 92.25f, 170f), RepCandidate(600L, 90f, 168.5f)),
            repDropped = listOf(RepCycle(250L, 0L, 120f, 170f, byRedescent = true)),
        )
        // 표시 단위 좌우 짝(사용자 결정 2026-09-24): 바벨 런지 3걸음 [충족, 미달, 충족] → 1회(미달 쌍) + 세트 끝에 남은 한쪽.
        // count·t_ms·min/max/valid·invalid 는 사이클 단위 그대로, completed 는 화면에 보인 횟수. 두 쪽 사이의 일시정지 리셋 하나.
        val sidePair = base.copy(
            setId = "20260924T021000-gold0003", createdAtIso = "2026-09-24T02:10:30Z", exercise = "바벨 런지",
            repCount = 3, repTimesMs = listOf(300L, 600L, 900L), repSignal = "knee_minside", repInvalid = 1,
            repMins = listOf(100.5f, 118.75f, 95.5f), repMaxs = listOf(170f, 168f, 169.5f), repValid = listOf(true, false, true),
            repEngine = RepEngineLog.of(RepCounter.forSession("바벨 런지", floor = false)!!),
            repResets = listOf(RepResetEvent(450L, "pause", afterTMs = 300L)),
            repUnit = RepUnit.SIDE_PAIR, repCompleted = 1, repHalfPending = true,
        )
        return listOf(base, newCore, sidePair)
    }

    @Test
    fun encodesDisplayedUnitNextToCycleFields() {
        // 사용자 결정(2026-09-24): 런지류는 좌우 한 번씩 = 1회. 로그의 count·렙별 배열·invalid 는 **사이클** 그대로(재생 파리티가 기댄다),
        // 화면에 보인 수는 completed, 단위는 unit·cycles_per_rep, 세트 끝에 짝 없이 남은 한쪽은 half_pending(true 일 때만).
        val samples = List(3) { sample(true, mapOf("knee_minside" to 170f)) }
        val records = listOf(RepRecord(1000L, 100f, 170f, true), RepRecord(2500L, 118f, 169f, false), RepRecord(4000L, 99f, 170f, true))
        val pair = SetLogJson.encode(SetLog.build("바벨 런지", samples, emptyList(), "mp_v0", "full", "GPU", true, 300L, now = Date(0L),
            repCount = 3, repTimesMs = records.map { it.tMs }, repSignal = "knee_minside", repInvalid = 1, repRecords = records,
            repUnit = RepUnit.SIDE_PAIR, repCompleted = 1, repHalfPending = true))
        assertTrue(pair.contains("\"count\":3,\"invalid\":1,"))
        assertTrue(pair.contains("\"valid\":[true,false,true],\"unit\":\"side_pair\",\"cycles_per_rep\":2,\"completed\":1,\"half_pending\":true}"))
        // 짝을 다 채운 세트는 half_pending 키가 없다
        val even = SetLogJson.encode(SetLog.build("바벨 런지", samples, emptyList(), "mp_v0", "full", "GPU", true, 300L, now = Date(0L),
            repCount = 2, repTimesMs = listOf(1000L, 2500L), repSignal = "knee_minside", repInvalid = 0,
            repUnit = RepUnit.SIDE_PAIR, repCompleted = 1))
        assertTrue(even.contains("\"unit\":\"side_pair\",\"cycles_per_rep\":2,\"completed\":1}"))
        assertFalse(even.contains("half_pending"))
        // 사이클 단위: completed = count
        val cycle = SetLogJson.encode(SetLog.build("바벨 스쿼트", samples, emptyList(), "mp_v0", "full", "GPU", true, 300L, now = Date(0L),
            repCount = 2, repTimesMs = listOf(1000L, 2500L), repSignal = "knee_mean", repInvalid = 0,
            repUnit = RepUnit.CYCLE, repCompleted = 2))
        assertTrue(cycle.contains("\"unit\":\"cycle\",\"cycles_per_rep\":1,\"completed\":2}"))
        assertFalse(cycle.contains("half_pending"))
        // 단위를 주지 않으면(이전 로그) 키 자체가 없다 — 부재 = "그때는 늘 사이클 = 1회"
        val old = SetLogJson.encode(SetLog.build("바벨 스쿼트", samples, emptyList(), "mp_v0", "full", "GPU", true, 300L, now = Date(0L), repCount = 2))
        for (key in listOf("unit", "cycles_per_rep", "completed", "half_pending")) assertFalse(key, old.contains("\"$key\":"))
        // 카운터가 없는 세트(reps 블록 없음)에는 단위도 없다
        val noCounter = SetLogJson.encode(SetLog.build("플랭크", samples, emptyList(), "mp_v0", "full", "GPU", true, 300L, now = Date(0L),
            repUnit = RepUnit.CYCLE, repCompleted = 0))
        assertFalse(noCounter.contains("\"reps\":"))
        assertFalse(noCounter.contains("\"unit\":"))
    }

    @Test
    fun numberFormattingIsLocaleSafeAndCompact() {
        assertEquals("0", SetLogJson.num(0f))
        assertEquals("0", SetLogJson.num(-0.0000001f))
        assertEquals("1.5", SetLogJson.num(1.5f))
        assertEquals("123456", SetLogJson.num(123456f))
        assertEquals("-0.00123", SetLogJson.num(-0.001234f))
        assertEquals("null", SetLogJson.num(Float.NaN))
        assertEquals("null", SetLogJson.num(Float.POSITIVE_INFINITY))
        assertEquals("null", SetLogJson.num(null))
        assertEquals("0.9", SetLogJson.num(0.9f, 3))
    }

    @Test
    fun storeAppendsJsonLinesAndCounts() {
        val store = SetLogStore(tmp.newFolder("posture_logs"))
        val samples = listOf(sample(true, mapOf("knee_L" to 90f)))
        val log1 = SetLog.build("바벨 스쿼트", samples, emptyList(), "mp_v0", "full", "GPU", false, 300L, now = Date(0L))
        val log2 = SetLog.build("오버 헤드 프레스", samples, emptyList(), "mp_v0", "lite", "CPU", true, 300L, now = Date(0L))
        val f1 = store.append(log1, Date(0L))
        val f2 = store.append(log2, Date(0L))
        assertEquals(f1, f2)
        assertEquals("sets-19700101.jsonl", f1.name)
        assertEquals(2, store.totalSets())
        assertEquals(1, store.files().size)
        val lines = f1.readLines(Charsets.UTF_8)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"exercise\":\"바벨 스쿼트\""))
        assertTrue(lines[1].contains("\"model\":\"lite\""))
        assertTrue(store.totalBytes() > 0)
        store.clear()
        assertEquals(0, store.totalSets())
    }
}
