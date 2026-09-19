package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실행 경로가 없는 과거 기록과 런타임에서 주입한 새 기록을 구분한다. */
class EngineProvenanceLogTest {

    private fun build(engine: EngineProvenance? = null): SetLog = SetLog.build(
        exercise = "덤벨 컬",
        samples = emptyList(),
        results = emptyList(),
        rulesVersion = "mp_v0",
        model = "full",
        delegate = "GPU",
        frontCamera = true,
        sampleIntervalMs = 300L,
        engineProvenance = engine,
    )

    @Test
    fun legacyLogDoesNotClaimCurrentEngine() {
        val log = build()

        assertNull(log.engineProvenance)
        assertFalse(SetLogJson.encode(log).contains("\"engine\":"))
    }

    @Test
    fun explicitlySuppliedRuntimeMetadataIsPreserved() {
        val engine = EngineProvenance(
            applicationId = "com.example.trex_kotlin.replay",
            repEngine = EngineVersions.REP,
            repProfile = EngineVersions.PROFILES,
            repPattern = "ALTERNATING_EACH",
            formEngine = EngineVersions.FORM,
            poseEstimator = EngineVersions.POSE,
        )
        val log = build(engine)
        val expected = "\"engine\":{\"application_id\":\"com.example.trex_kotlin.replay\"," +
            "\"rep_engine\":\"return-bilateral/1\",\"rep_profile\":\"exercise-rep-profiles/1\"," +
            "\"rep_pattern\":\"ALTERNATING_EACH\",\"form_engine\":\"rule-coach/1\"," +
            "\"pose_estimator\":\"mediapipe-pose-landmarker/full\"},\"front_camera\":true"

        assertEquals(engine, log.engineProvenance)
        assertTrue(SetLogJson.encode(log).contains(expected))
    }

    @Test
    fun nullableSelectionsAndEscapedStringsRemainValidJsonLines() {
        val engine = EngineProvenance(
            applicationId = "app\"\\\n",
            repEngine = "engine\t\r",
            repProfile = null,
            repPattern = null,
            formEngine = "규칙\u0001",
            poseEstimator = "pose\"\\\n",
        )
        val json = SetLogJson.encode(build(engine))
        val expected = "\"engine\":{\"application_id\":\"app\\\"\\\\\\n\"," +
            "\"rep_engine\":\"engine\\t\\r\",\"rep_profile\":null,\"rep_pattern\":null," +
            "\"form_engine\":\"규칙\\u0001\",\"pose_estimator\":\"pose\\\"\\\\\\n\"},"

        assertTrue(json.contains(expected))
        assertFalse(json.contains('\n'))
        assertFalse(json.contains('\r'))
        assertFalse(json.contains('\t'))
        assertFalse(json.contains('\u0001'))
    }
}
