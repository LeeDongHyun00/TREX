package com.example.trex_kotlin.weather

import com.example.trex_kotlin.KeyValueStore

/**
 * 날씨 기준 위치 설정(§108e — weather 브랜치 `LocationPreferenceManager` 의 저장 부분을 두 플랫폼 공유로). 고른 지역 또는 현재 위치(GPS).
 * 저장소 이름("trex_weather_location_prefs")·키는 브랜치와 같다.
 */
class WeatherLocationPrefs(private val store: KeyValueStore) {
    fun saveSelectedRegion(region: KoreanRegion) {
        store.edit().putString(KEY_SELECTED_REGION, region.displayName).putBoolean(KEY_USE_GPS, false).apply()
    }

    fun enableGpsMode() {
        store.edit().putBoolean(KEY_USE_GPS, true).apply()
    }

    fun isGpsModeEnabled(): Boolean = store.getBoolean(KEY_USE_GPS, false)

    fun getSavedRegion(): KoreanRegion = KoreanRegion.fromName(store.getString(KEY_SELECTED_REGION, KoreanRegion.SEOUL.displayName))

    private companion object {
        const val KEY_SELECTED_REGION = "selected_region"
        const val KEY_USE_GPS = "use_gps"
    }
}

/** 현재 위치 — 플랫폼 짝(`rememberWeatherLocator`): Android LocationManager / iPhone Core Location. */
interface WeatherLocator {
    fun hasPermission(): Boolean
    /** 위치 권한을 묻는다 — 결과를 [onResult](메인)로. 이미 정해졌으면 묻지 않고 바로(iPhone 은 한 번 거절하면 앱이 다시 물을 수 없다). */
    fun requestPermission(onResult: (Boolean) -> Unit)
    /** 현재 좌표(위도, 경도) — 최근 위치를 먼저, 없으면 새로 한 번(짧은 제한 시간). 못 얻으면 null. */
    suspend fun currentCoordinates(): Pair<Double, Double>?
}

/** 날씨를 볼 곳 — [label] 은 카드의 위치 이름. */
data class WeatherPlace(val latitude: Double, val longitude: Double, val label: String)

/** 날씨를 볼 좌표와 이름 — GPS 모드에 권한이 있으면 현재 위치, 위치를 못 얻으면 저장 지역 "(위치 확인 불가)", 아니면 저장 지역(브랜치와 같은 규칙). */
suspend fun resolveWeatherPlace(prefs: WeatherLocationPrefs, locator: WeatherLocator): WeatherPlace {
    if (prefs.isGpsModeEnabled() && locator.hasPermission()) {
        locator.currentCoordinates()?.let { (latitude, longitude) -> return WeatherPlace(latitude, longitude, "현재 위치") }
        val fallback = prefs.getSavedRegion()
        return WeatherPlace(fallback.latitude, fallback.longitude, fallback.displayName + " (위치 확인 불가)")
    }
    val region = prefs.getSavedRegion()
    return WeatherPlace(region.latitude, region.longitude, region.displayName)
}

/** 날씨 카드가 쓰는 앱 수명 객체 — [com.example.trex_kotlin.AppViewModel.weather]. */
class WeatherServices(val repository: WeatherRepository, val locationPrefs: WeatherLocationPrefs)
