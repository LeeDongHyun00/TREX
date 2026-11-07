package com.example.trex_kotlin.weather

import com.example.trex_kotlin.nowMillis

/**
 * 지금 날씨(Open-Meteo `current`). 기온·강수·WMO 날씨 코드·풍속은 운동 추천 판정에 쓰고([WeatherExerciseRecommender]),
 * 홈의 날씨 한 줄은 기온·날씨·강수량·미세먼지를 단계 말로 보인다(§108f, #64). [isDay] 는 맑은 밤을 달 아이콘으로 그리는 데만 쓴다.
 * [pm10] 은 같은 자리의 Open-Meteo 대기질 API(CAMS) 현재 PM10 농도(µg/m³) — 응답이 없거나 실패하면 null 이고 날씨·추천은 그대로 간다.
 */
data class WeatherInfo(
    val temperature: Double,
    val precipitation: Double,
    val weatherCode: Int,
    val windSpeed: Double,
    val fetchedAtMillis: Long = nowMillis(),
    val isDay: Boolean = true,
    val pm10: Double? = null,
)

/**
 * 지난 한 시간 강수량(mm, Open-Meteo `current.precipitation`) → 단계 말(#64, 사용자 결정 2026-11-07 "조금·보통·많음·상당히 많음·매우 많음").
 * 띠는 기상청 시간당 강수 강도 구분을 따른다 — 약한 비 3 mm 미만 = 조금, 보통 비 3~15 = 보통, 강한 비 15~30 = 많음, 매우 강한 비 30 이상 = 상당히 많음,
 * 50 이상(호우 수준) = 매우 많음. 0 은 "없음" — 비가 안 오는 날을 '조금' 이라 하지 않는다.
 */
fun precipitationLevel(mmPerHour: Double): String = when {
    mmPerHour <= 0.0 || mmPerHour.isNaN() -> "없음"
    mmPerHour < 3.0 -> "조금"
    mmPerHour < 15.0 -> "보통"
    mmPerHour < 30.0 -> "많음"
    mmPerHour < 50.0 -> "상당히 많음"
    else -> "매우 많음"
}

/**
 * 미세먼지(PM10, µg/m³) → 단계 말(#64). 띠는 환경부 예보 등급 — 좋음 0~30 = 조금, 보통 31~80 = 보통, 나쁨 81~150 = 많음,
 * 매우 나쁨 151~(주의보 기준 150) = 상당히 많음, 경보 기준 300 초과 = 매우 많음. 종합 지수(AQI)가 아니라 농도만 쓴다.
 */
fun dustLevel(pm10: Double): String = when {
    pm10 <= 30.0 -> "조금"
    pm10 <= 80.0 -> "보통"
    pm10 <= 150.0 -> "많음"
    pm10 <= 300.0 -> "상당히 많음"
    else -> "매우 많음"
}

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
