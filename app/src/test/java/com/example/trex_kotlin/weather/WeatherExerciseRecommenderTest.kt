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
    fun displayedTemperatureAtThreshold_isNotClassifiedAsColdOrHeatwave() {
        val now = 1_700_000_000_000L
        // -0.4°C 는 "0°C" 로 표시되므로 추운 날로 판정하면 안 된다.
        val nearZero = WeatherInfo(
            temperature = -0.4,
            precipitation = 0.0,
            weatherCode = 0,
            windSpeed = 3.0,
            fetchedAtMillis = now
        )
        // 32.4°C 는 "32°C" 로 표시되므로 폭염(32°C 초과)으로 판정하면 안 된다.
        val near32 = nearZero.copy(temperature = 32.4)

        val zeroResult = WeatherExerciseRecommender.recommend(nearZero, "서울", nowMillis = now)
        val near32Result = WeatherExerciseRecommender.recommend(near32, "서울", nowMillis = now)

        assertEquals("서울 · 맑음 · 0°C", zeroResult.weatherSummary)
        assertEquals(WeatherThemeMode.SUNNY, zeroResult.theme)
        assertEquals(WorkoutEnvironment.OUTDOOR, zeroResult.environment)

        assertEquals("서울 · 맑음 · 32°C", near32Result.weatherSummary)
        assertEquals(WeatherThemeMode.SUNNY, near32Result.theme)
        assertEquals(WorkoutEnvironment.OUTDOOR, near32Result.environment)
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
        // 날씨 시트는 그릴 때 같은 함수로 다시 센다 — 추천에 굳힌 글과 같은 결과여야 한다
        assertEquals(hoursAgo.updateAgeText, WeatherExerciseRecommender.updateAgeText(base, base + 125 * 60_000L))
    }

    /** 날씨 시트의 '목록에 추가' 는 카탈로그 이름으로 운동을 찾는다 — 없는 이름이면 눌러도 아무것도 안 된다. */
    @Test
    fun everyRecommendedExercise_existsInWorkoutCatalog() {
        val catalog = com.example.trex_kotlin.workoutCatalog.values.flatten().map { it.name }.toSet()
        val now = 1_700_000_000_000L
        val samples = listOf(
            WeatherInfo(1.0, 1.0, 71, 5.0, now),    // 눈
            WeatherInfo(12.0, 3.0, 61, 5.0, now),   // 비
            WeatherInfo(35.0, 0.0, 0, 5.0, now),    // 폭염
            WeatherInfo(18.0, 0.0, 3, 5.0, now),    // 흐림
            WeatherInfo(18.0, 0.0, 0, 5.0, now),    // 맑음
        )
        val themes = mutableSetOf<WeatherThemeMode>()
        for (weather in samples) {
            val rec = WeatherExerciseRecommender.recommend(weather, "서울", nowMillis = now)
            themes += rec.theme
            for (exercise in rec.exercises) {
                assertTrue("${rec.theme} 의 ${exercise.catalogName} 이(가) 카탈로그에 없다", exercise.catalogName in catalog)
            }
        }
        assertEquals("모든 테마를 거쳤는지", WeatherThemeMode.entries.toSet(), themes)
    }
}
