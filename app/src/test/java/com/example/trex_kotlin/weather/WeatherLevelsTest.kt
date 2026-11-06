package com.example.trex_kotlin.weather

import org.junit.Assert.assertEquals
import org.junit.Test

/** 홈 날씨 줄의 강수량·미세먼지 단계 말(#64) — 띠 경계가 기상청·환경부 기준에서 움직이지 않게 잠근다. */
class WeatherLevelsTest {

    @Test
    fun precipitationLevel_followsKmaHourlyIntensityBands() {
        assertEquals("없음", precipitationLevel(0.0))
        assertEquals("없음", precipitationLevel(-0.1))
        assertEquals("없음", precipitationLevel(Double.NaN))
        assertEquals("조금", precipitationLevel(0.1))
        assertEquals("조금", precipitationLevel(2.9))
        assertEquals("보통", precipitationLevel(3.0))
        assertEquals("보통", precipitationLevel(14.9))
        assertEquals("많음", precipitationLevel(15.0))
        assertEquals("많음", precipitationLevel(29.9))
        assertEquals("상당히 많음", precipitationLevel(30.0))
        assertEquals("상당히 많음", precipitationLevel(49.9))
        assertEquals("매우 많음", precipitationLevel(50.0))
        assertEquals("매우 많음", precipitationLevel(120.0))
    }

    @Test
    fun dustLevel_followsMinistryPm10Grades() {
        assertEquals("조금", dustLevel(0.0))
        assertEquals("조금", dustLevel(30.0))
        assertEquals("보통", dustLevel(30.1))
        assertEquals("보통", dustLevel(80.0))
        assertEquals("많음", dustLevel(80.1))
        assertEquals("많음", dustLevel(150.0))
        assertEquals("상당히 많음", dustLevel(150.1))
        assertEquals("상당히 많음", dustLevel(300.0))
        assertEquals("매우 많음", dustLevel(300.1))
        assertEquals("매우 많음", dustLevel(600.0))
    }
}
