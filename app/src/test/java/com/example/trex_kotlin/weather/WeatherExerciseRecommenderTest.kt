package com.example.trex_kotlin.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherExerciseRecommenderTest {

    @Test
    fun snowAboveZeroCelsius_isClassifiedAsSnowyColdInsteadOfRainy() {
        val now = 1_700_000_000_000L
        val weather = WeatherInfo(temperature = 0.5, precipitation = 1.2, weatherCode = 71, windSpeed = 5.0, fetchedAtMillis = now)
        val result = WeatherExerciseRecommender.recommend(weather, "서울", nowMillis = now)

        assertEquals(WeatherThemeMode.SNOWY_COLD, result.theme)
        assertEquals(WorkoutEnvironment.INDOOR, result.environment)
        assertEquals("서울 · 눈 · 1°C", result.weatherSummary)
        assertEquals("최근 1시간 강수량 1.2mm", result.precipitationSummary)
    }

    @Test
    fun temperatureAndWeatherCodeProduceExpectedLabels() {
        val now = 1_700_000_000_000L
        fun recommendation(temp: Double, code: Int) = WeatherExerciseRecommender.recommend(
            WeatherInfo(temp, 0.0, code, 3.0, now), "서울", now
        )

        assertTrue(recommendation(18.0, 0).weatherSummary.contains("맑음"))
        assertTrue(recommendation(7.0, 0).weatherSummary.contains("쌀쌀함"))
        assertTrue(recommendation(-2.0, 0).weatherSummary.contains("추움"))
        assertTrue(recommendation(28.0, 0).weatherSummary.contains("더움"))
        assertTrue(recommendation(34.0, 0).weatherSummary.contains("폭염"))
        assertTrue(recommendation(7.0, 61).weatherSummary.contains("비"))
        assertTrue(recommendation(18.0, 71).weatherSummary.contains("눈"))
    }

    @Test
    fun precipitationIsDisplayedWithUnitAndZeroIsExplicit() {
        val now = 1_700_000_000_000L
        val rainy = WeatherExerciseRecommender.recommend(
            WeatherInfo(18.4, 1.2, 61, 5.0, now), "서울", now
        )
        val dry = WeatherExerciseRecommender.recommend(
            WeatherInfo(18.4, 0.0, 0, 5.0, now), "서울", now
        )

        assertEquals("최근 1시간 강수량 1.2mm", rainy.precipitationSummary)
        assertEquals("최근 1시간 강수량 0.0mm", dry.precipitationSummary)
    }

    @Test
    fun temperatureBoundariesFollowRoundedDisplayValue() {
        val now = 1_700_000_000_000L
        fun label(temp: Double) = WeatherExerciseRecommender.recommend(
            WeatherInfo(temp, 0.0, 0, 3.0, now), "서울", now
        ).weatherSummary

        assertTrue(label(-0.6).contains("추움"))
        assertTrue(label(-0.4).contains("맑음"))
        assertTrue(label(7.0).contains("쌀쌀함"))
        assertTrue(label(18.0).contains("맑음"))
        assertTrue(label(28.0).contains("더움"))
        assertTrue(label(32.4).contains("더움"))
        assertTrue(label(32.8).contains("폭염"))
    }

    @Test
    fun partlyCloudyAndOvercast_areClassifiedAsCloudyOutdoor() {
        val now = 1_700_000_000_000L
        val partlyCloudy = WeatherInfo(17.0, 0.0, 2, 8.0, now)
        val overcast = partlyCloudy.copy(weatherCode = 3)
        val result2 = WeatherExerciseRecommender.recommend(partlyCloudy, "서울", nowMillis = now)
        val result3 = WeatherExerciseRecommender.recommend(overcast, "서울", nowMillis = now)

        assertEquals(WeatherThemeMode.CLOUDY, result2.theme)
        assertEquals(WorkoutEnvironment.OUTDOOR, result2.environment)
        assertEquals("서울 · 구름 조금 · 17°C", result2.weatherSummary)
        assertEquals(WeatherThemeMode.CLOUDY, result3.theme)
        assertEquals(WorkoutEnvironment.OUTDOOR, result3.environment)
        assertEquals("서울 · 흐림 · 17°C", result3.weatherSummary)
    }

    @Test
    fun updateAgeText_formatsMinutesAndHours() {
        val base = 1_700_000_000_000L
        val weather = WeatherInfo(20.0, 0.0, 0, 4.0, base)
        val justNow = WeatherExerciseRecommender.recommend(weather, "서울", nowMillis = base + 30_000L)
        val minsAgo = WeatherExerciseRecommender.recommend(weather, "서울", nowMillis = base + 15 * 60_000L)
        val hoursAgo = WeatherExerciseRecommender.recommend(weather, "서울", nowMillis = base + 125 * 60_000L)

        assertEquals("방금 업데이트", justNow.updateAgeText)
        assertEquals("15분 전 업데이트", minsAgo.updateAgeText)
        assertEquals("2시간 전 업데이트", hoursAgo.updateAgeText)
    }
}
