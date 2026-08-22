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
