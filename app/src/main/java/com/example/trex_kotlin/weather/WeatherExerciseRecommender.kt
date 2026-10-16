package com.example.trex_kotlin.weather

import kotlin.math.roundToInt

object WeatherExerciseRecommender {

    fun recommend(
        weather: WeatherInfo,
        locationLabel: String,
        nowMillis: Long = System.currentTimeMillis()
    ): WeatherWorkoutRecommendation {
        val isSnow = weather.weatherCode in 71..77 || weather.weatherCode in 85..86
        val isRaining = weather.weatherCode in 51..67 ||
            weather.weatherCode in 80..82 ||
            weather.weatherCode in 95..99
        val isCold = weather.temperature < 0.0
        val isHeatwave = weather.temperature > 32.0
        val isFogOrWind = weather.weatherCode in 45..48 || weather.windSpeed >= 35.0
        val isCloudy = weather.weatherCode in 2..3

        val theme = when {
            isSnow -> WeatherThemeMode.SNOWY_COLD
            isRaining -> WeatherThemeMode.RAINY
            isCold -> WeatherThemeMode.SNOWY_COLD
            isHeatwave -> WeatherThemeMode.HEATWAVE
            isFogOrWind || isCloudy -> WeatherThemeMode.CLOUDY
            else -> WeatherThemeMode.SUNNY
        }

        val isBadWeather = isSnow || isRaining || isCold || isHeatwave || isFogOrWind
        val environment = if (isBadWeather) WorkoutEnvironment.INDOOR else WorkoutEnvironment.OUTDOOR
        val weatherText = getWeatherDescription(weather.weatherCode)
        val roundedTemp = weather.temperature.roundToInt()
        val summary = locationLabel + " · " + weatherText + " · " + roundedTemp + "°C"

        val minutesAgo = ((nowMillis - weather.fetchedAtMillis) / 60000L).coerceAtLeast(0L)
        val updateAgeText = when {
            minutesAgo == 0L -> "방금 업데이트"
            minutesAgo < 60L -> minutesAgo.toString() + "분 전 업데이트"
            else -> (minutesAgo / 60L).toString() + "시간 전 업데이트"
        }

        val message = when {
            isSnow || isCold -> "기온이 낮거나 눈 소식이 있어룡. 실내에서 체온을 높이는 웜업 운동을 권장해룡."
            isRaining -> "비 소식이 있어룡! 실내에서 안전하게 전신 근력 운동을 진행해 보세룡."
            isHeatwave -> "무더운 날씨예룡. 시원한 실내에서 수분을 보충하며 부위별 운동을 해보세룡."
            isFogOrWind -> "바람이나 안개가 있으니 실내 위주로 안전하게 운동해 보세룡."
            isCloudy -> "구름이 있어 햇볕 부담 없이 운동하기 좋은 날예룡! 컨디션에 맞춰 운동해 보세룡."
            else -> "야외 활동하기 좋은 맑은 날씨예룡! 활기차게 몸을 움직여 볼까룡?"
        }

        val exercises = when (theme) {
            WeatherThemeMode.RAINY -> listOf(
                RecommendedExercise("기본 스쿼트", "기본 스쿼트", "비 오는 날 실내에서 하체와 코어를 단련하는 기본 운동", WorkoutEnvironment.INDOOR),
                RecommendedExercise("푸쉬업", "푸쉬업", "좁은 실내 공간에서도 상체 전반을 강화하는 맨몸 운동", WorkoutEnvironment.INDOOR),
                RecommendedExercise("크런치", "크런치", "매트 위에서 상복부를 집중 자극하는 실내 복근 운동", WorkoutEnvironment.INDOOR)
            )
            WeatherThemeMode.SNOWY_COLD -> listOf(
                RecommendedExercise("스탠딩 니업", "스탠딩 니업", "추운 날 굳은 관절을 풀고 체온을 빠르게 올리는 실내 웜업", WorkoutEnvironment.INDOOR),
                RecommendedExercise("스탠딩 사이드 크런치", "스탠딩 사이드 크런치", "서서 옆구리와 코어 가동성을 깨우는 전신 운동", WorkoutEnvironment.INDOOR),
                RecommendedExercise("니 푸쉬업", "니 푸쉬업", "어깨와 팔꿈치 부담을 줄여 안전하게 수행하는 상체 운동", WorkoutEnvironment.INDOOR)
            )
            WeatherThemeMode.HEATWAVE -> listOf(
                RecommendedExercise("덤벨 컬", "덤벨 컬", "더운 날 호흡 부담 없이 실내에서 집중하는 이두 운동", WorkoutEnvironment.INDOOR),
                RecommendedExercise("사이드 레터럴 레이즈", "사이드 레터럴 레이즈", "가벼운 중량으로 어깨 라인을 다듬는 실내 운동", WorkoutEnvironment.INDOOR),
                RecommendedExercise("레그 레이즈", "레그 레이즈", "실내 바닥에 누워 하복부를 탄탄하게 잡는 코어 운동", WorkoutEnvironment.INDOOR)
            )
            WeatherThemeMode.CLOUDY -> listOf(
                RecommendedExercise("기본 스쿼트", "기본 스쿼트", "하체 지지력과 전신 밸런스를 높이는 대표 운동", environment),
                RecommendedExercise("사이드 런지", "사이드 런지", "고관절 가동성과 하체 측면 근력을 길러주는 운동", environment),
                RecommendedExercise("덤벨 컬", "덤벨 컬", "하체 운동 후 상체 밸런스를 함께 챙기는 팔 운동", environment)
            )
            WeatherThemeMode.SUNNY -> listOf(
                RecommendedExercise("스탠딩 니업", "스탠딩 니업", "야외 유산소 전후 심박수를 끌어올리는 활기찬 웜업 운동", WorkoutEnvironment.OUTDOOR),
                RecommendedExercise("런지", "런지", "야외 활동과 러닝에 필요한 하체 밸런스를 강화하는 운동", WorkoutEnvironment.OUTDOOR),
                RecommendedExercise("푸쉬업", "푸쉬업", "야외 벤치나 매트에서 상체 근력을 함께 채우는 운동", WorkoutEnvironment.OUTDOOR)
            )
        }

        return WeatherWorkoutRecommendation(
            environment = environment,
            theme = theme,
            locationLabel = locationLabel,
            weatherSummary = summary,
            updateAgeText = updateAgeText,
            message = message,
            exercises = exercises
        )
    }

    internal fun getWeatherDescription(code: Int): String = when (code) {
        0 -> "맑음"
        1 -> "대체로 맑음"
        2 -> "구름 조금"
        3 -> "흐림"
        in 45..48 -> "안개"
        in 51..67 -> "비"
        in 71..77 -> "눈"
        in 80..82 -> "소나기"
        in 85..86 -> "눈보라"
        in 95..99 -> "뇌우"
        else -> "흐림"
    }
}
