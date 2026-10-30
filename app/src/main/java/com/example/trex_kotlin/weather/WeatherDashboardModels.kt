package com.example.trex_kotlin.weather

data class WeatherDashboardItem(
    val region: KoreanRegion,
    val recommendation: WeatherWorkoutRecommendation? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class WeatherDashboardState(
    val items: List<WeatherDashboardItem> = emptyList(),
    val isRefreshing: Boolean = false
)
