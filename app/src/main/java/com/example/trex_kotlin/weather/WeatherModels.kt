package com.example.trex_kotlin.weather

data class WeatherInfo(
    val temperature: Double,
    val precipitation: Double,
    val weatherCode: Int,
    val windSpeed: Double,
    val fetchedAtMillis: Long = System.currentTimeMillis()
)

enum class WorkoutEnvironment(val labelKorean: String) {
    INDOOR("실내 운동"),
    OUTDOOR("야외 활동")
}

enum class WeatherThemeMode(
    val titleKorean: String,
    val emoji: String
) {
    SUNNY("맑음", "☀️"),
    RAINY("비", "🌧️"),
    SNOWY_COLD("추위", "❄️"),
    HEATWAVE("더위", "🔥"),
    CLOUDY("흐림", "☁️")
}

data class RecommendedExercise(
    val catalogName: String,
    val title: String,
    val description: String,
    val environment: WorkoutEnvironment
)

data class WeatherWorkoutRecommendation(
    val environment: WorkoutEnvironment,
    val theme: WeatherThemeMode,
    val locationLabel: String,
    val weatherSummary: String,
    val precipitationSummary: String,
    val updateAgeText: String,
    val message: String,
    val exercises: List<RecommendedExercise>
)
