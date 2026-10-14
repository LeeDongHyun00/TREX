package com.example.trex_kotlin.weather

object WeatherExerciseRecommender {

    fun recommend(weather: WeatherInfo, locationLabel: String): WeatherWorkoutRecommendation {
        val isRaining = weather.weatherCode in 51..67 ||
            weather.weatherCode in 80..82 ||
            weather.weatherCode in 95..99 ||
            (weather.precipitation > 0.0 && weather.temperature >= 0.0)
        val isSnowOrCold = weather.weatherCode in 71..77 ||
            weather.weatherCode in 85..86 ||
            weather.temperature < 0.0
        val isHeatwave = weather.temperature > 32.0
        val isFogOrWind = weather.weatherCode in 45..48 || weather.windSpeed >= 35.0

        val theme = when {
            isRaining -> WeatherThemeMode.RAINY
            isSnowOrCold -> WeatherThemeMode.SNOWY_COLD
            isHeatwave -> WeatherThemeMode.HEATWAVE
            isFogOrWind -> WeatherThemeMode.CLOUDY
            else -> WeatherThemeMode.SUNNY
        }

        val isBadWeather = isRaining || isSnowOrCold || isHeatwave || isFogOrWind
        val weatherText = getWeatherDescription(weather.weatherCode)
        val summary = locationLabel + " · " + weatherText + " · " + weather.temperature.toInt() + "°C"

        val minutesAgo = ((System.currentTimeMillis() - weather.fetchedAtMillis) / 60000L).coerceAtLeast(0L)
        val updateAgeText = if (minutesAgo == 0L) "방금 업데이트" else minutesAgo.toString() + "분 전 업데이트"

        val message = when {
            isRaining -> "비 소식이 있어룡! 실내에서 안전하게 근력 운동을 진행해 보세룡."
            isSnowOrCold -> "기온이 낮거나 눈 소식이 있어룡. 부상 방지를 위해 실내 운동을 권장해룡."
            isHeatwave -> "무더운 날씨예룡. 시원한 실내에서 수분을 보충하며 운동해 보세룡."
            isFogOrWind -> "바람이나 안개가 있으니 실내 위주로 안전하게 운동해 보세룡."
            else -> "야외 활동하기 좋은 날씨예룡! 가벼운 러닝이나 근력 운동을 시작해 볼까룡?"
        }

        return if (isBadWeather) {
            WeatherWorkoutRecommendation(
                environment = WorkoutEnvironment.INDOOR,
                theme = theme,
                locationLabel = locationLabel,
                weatherSummary = summary,
                updateAgeText = updateAgeText,
                message = message,
                exercises = listOf(
                    RecommendedExercise("기본 스쿼트", "기본 스쿼트", "하체와 코어를 동시에 단련하는 대표적인 실내 운동", WorkoutEnvironment.INDOOR),
                    RecommendedExercise("런지", "런지", "하체 밸런스와 코어 안정성을 높이는 실내 운동", WorkoutEnvironment.INDOOR),
                    RecommendedExercise("덤벨 컬", "덤벨 컬", "실내에서 간편하게 수행하는 상체 이두 집중 운동", WorkoutEnvironment.INDOOR)
                )
            )
        } else {
            WeatherWorkoutRecommendation(
                environment = WorkoutEnvironment.OUTDOOR,
                theme = theme,
                locationLabel = locationLabel,
                weatherSummary = summary,
                updateAgeText = updateAgeText,
                message = message,
                exercises = listOf(
                    RecommendedExercise("기본 스쿼트", "야외 웜업 · 기본 스쿼트", "야외 유산소 전후 하체 근력을 보강하는 대표 운동", WorkoutEnvironment.OUTDOOR),
                    RecommendedExercise("런지", "야외 러닝 보강 · 런지", "러닝 시 무릎 안정성과 하체 지지력을 높이는 운동", WorkoutEnvironment.OUTDOOR),
                    RecommendedExercise("덤벨 컬", "덤벨 컬", "유산소 운동 후 상체 근력을 함께 챙기는 운동", WorkoutEnvironment.OUTDOOR)
                )
            )
        }
    }

    private fun getWeatherDescription(code: Int): String = when (code) {
        0 -> "맑음"
        1, 2 -> "구름 조금"
        3 -> "흐림"
        in 45..48 -> "안개"
        in 51..67 -> "비"
        in 71..77 -> "눈"
        in 80..82 -> "소나기"
        in 95..99 -> "뇌우"
        else -> "흐림"
    }
}
