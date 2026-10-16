package com.example.trex_kotlin.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherExerciseRecommenderTest {

    @Test
    fun snowAboveZeroCelsius_isClassifiedAsSnowyColdInsteadOfRainy() {
        val now = 1_700_000_000_000L
        val weather = WeatherInfo(
            temperature = 0.5,
            precipitation = 1.2,
            weatherCode = 71,
            windSpeed = 5.0,
            fetchedAtMillis = now
        )
        val result = WeatherExerciseRecommender.recommend(weather, "서울", nowMillis = now)

        assertEquals(WeatherThemeMode.SNOWY_COLD, result.theme)
        assertEquals(WorkoutEnvironment.INDOOR, result.environment)
        assertEquals("서울 · 눈 · 1°C", result.weatherSummary)
    }

    @Test
    fun partlyCloudyAndOvercast_areClassifiedAsCloudyOutdoor() {
        val now = 1_700_000_000_000L
        val partlyCloudy = WeatherInfo(
            temperature = 17.0,
            precipitation = 0.0,
            weatherCode = 2,
            windSpeed = 8.0,
            fetchedAtMillis = now
        )
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
    fun temperatureRounding_matchesColdAndHeatwaveThresholds() {
        val now = 1_700_000_000_000L
        val subzero = WeatherInfo(
            temperature = -0.6,
            precipitation = 0.0,
            weatherCode = 0,
            windSpeed = 3.0,
            fetchedAtMillis = now
        )
        val heatwave = WeatherInfo(
            temperature = 32.8,
            precipitation = 0.0,
            weatherCode = 0,
            windSpeed = 3.0,
            fetchedAtMillis = now
        )

        val coldResult = WeatherExerciseRecommender.recommend(subzero, "서울", nowMillis = now)
        val heatResult = WeatherExerciseRecommender.recommend(heatwave, "서울", nowMillis = now)

        assertEquals(WeatherThemeMode.SNOWY_COLD, coldResult.theme)
        assertTrue(coldResult.weatherSummary.endsWith("-1°C"))

        assertEquals(WeatherThemeMode.HEATWAVE, heatResult.theme)
        assertTrue(heatResult.weatherSummary.endsWith("33°C"))
    }

    @Test
    fun updateAgeText_formatsMinutesAndHours() {
        val base = 1_700_000_000_000L
        val weather = WeatherInfo(
            temperature = 20.0,
            precipitation = 0.0,
            weatherCode = 0,
            windSpeed = 4.0,
            fetchedAtMillis = base
        )

        val justNow = WeatherExerciseRecommender.recommend(weather, "서울", nowMillis = base + 30_000L)
        val minsAgo = WeatherExerciseRecommender.recommend(weather, "서울", nowMillis = base + 15 * 60_000L)
        val hoursAgo = WeatherExerciseRecommender.recommend(weather, "서울", nowMillis = base + 125 * 60_000L)

        assertEquals("방금 업데이트", justNow.updateAgeText)
        assertEquals("15분 전 업데이트", minsAgo.updateAgeText)
        assertEquals("2시간 전 업데이트", hoursAgo.updateAgeText)
    }
}
