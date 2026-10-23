package com.example.trex_kotlin.weather

import com.example.trex_kotlin.nowMillis

/**
 * 지금 날씨(Open-Meteo `current`). 기온·강수·WMO 날씨 코드·풍속은 운동 추천 판정에 쓰고([WeatherExerciseRecommender]),
 * 홈의 날씨 한 줄은 기온·날씨·강수량을 보인다(§108f). [isDay] 는 맑은 밤을 달 아이콘으로 그리는 데만 쓴다.
 */
data class WeatherInfo(
    val temperature: Double,
    val precipitation: Double,
    val weatherCode: Int,
    val windSpeed: Double,
    val fetchedAtMillis: Long = nowMillis(),
    val isDay: Boolean = true,
)

enum class WorkoutEnvironment(val labelKorean: String) {
    INDOOR("실내 운동 추천"),
    OUTDOOR("실외 운동 추천")
}

enum class WeatherThemeMode(
    val titleKorean: String,
    val emoji: String
) {
    SUNNY("맑은 날 실외 운동", "☀️"),
    CLOUDY("선선한 날 운동", "🌤️"),
    RAINY("비 오는 날 실내 집중", "🌧️"),
    SNOWY_COLD("추운 날 실내 웜업", "❄️"),
    HEATWAVE("폭염 날 실내 운동", "🔥")
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
    val updateAgeText: String,
    val message: String,
    val exercises: List<RecommendedExercise>,
    /** 판정에 쓴 날씨 — 카드가 큰 기온·하늘색·시간별을 그린다. */
    val weather: WeatherInfo,
)

enum class KoreanRegion(
    val displayName: String,
    val latitude: Double,
    val longitude: Double
) {
    SEOUL("서울", 37.5665, 126.9780),
    GYEONGGI("경기(수원)", 37.2636, 127.0286),
    INCHEON("인천", 37.4563, 126.7052),
    BUSAN("부산", 35.1796, 129.0756),
    DAEGU("대구", 35.8714, 128.6014),
    DAEJEON("대전", 36.3504, 127.3845),
    GWANGJU("광주", 35.1595, 126.8526),
    ULSAN("울산", 35.5384, 129.3114),
    GANGWON("강원(춘천)", 37.8813, 127.7298),
    CHUNGCHEONG("충청(청주)", 36.6424, 127.4890),
    JEOLLA("전라(전주)", 35.8242, 127.1480),
    GYEONGSANG("경남(창원)", 35.2280, 128.6811),
    JEJU("제주", 33.4996, 126.5312);

    companion object {
        fun fromName(name: String?): KoreanRegion =
            entries.find { it.displayName == name } ?: SEOUL
    }
}
