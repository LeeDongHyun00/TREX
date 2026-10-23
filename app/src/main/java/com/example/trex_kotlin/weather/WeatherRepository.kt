package com.example.trex_kotlin.weather

import com.example.trex_kotlin.KeyValueStore
import com.example.trex_kotlin.locked
import com.example.trex_kotlin.nowMillis
import com.example.trex_kotlin.sync.HttpTransport
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Open-Meteo 지금 날씨(§108e — weather 브랜치의 저장소를 두 플랫폼 공유로). 전송은 플랫폼 [HttpTransport](Android HttpURLConnection·iPhone NSURLSession),
 * 캐시는 [KeyValueStore](저장소 이름 "trex_weather_cache_prefs", 좌표 소수 둘째 자리 키 — 브랜치와 같다).
 * 1시간 안의 캐시는 그대로 쓰고, 받기가 두 번 다 실패하면 3시간까지의 묵은 캐시로 물러선다(브랜치와 같은 규칙).
 */
class WeatherRepository(
    private val http: HttpTransport,
    private val cache: KeyValueStore?,
    private val clock: () -> Long = { nowMillis() },
    /** 응답 JSON → 날씨(받은 시각). 테스트는 가짜를 넣는다 — Android 유닛 테스트의 org.json 은 스텁이다. */
    private val parse: (String, Long) -> WeatherInfo = ::parseOpenMeteo,
    private val retryDelayMs: Long = 400L,
) {
    /** 화면을 그리기 전에 쓸 수 있는 캐시(묵은 것까지) — 받지 않는다. */
    fun peekCachedWeather(latitude: Double, longitude: Double): WeatherInfo? {
        val key = cacheKey(latitude, longitude)
        return cached(key) { clock() - it.fetchedAtMillis <= MAX_STALE_CACHE_MILLIS }
    }

    suspend fun fetchCurrentWeather(latitude: Double, longitude: Double, forceRefresh: Boolean = false): Result<WeatherInfo> {
        val key = cacheKey(latitude, longitude)
        if (!forceRefresh) {
            val now = clock()
            cached(key) { now - it.fetchedAtMillis < CACHE_TTL_MILLIS }?.let { return Result.success(it) }
        }
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val info = request(latitude, longitude)
                remember(key, info)
                return Result.success(info)
            } catch (e: CancellationException) {
                throw e   // 지역을 바꿔 앞 요청을 취소한 것 — 실패로 세지 않는다
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt == 0) delay(retryDelayMs)
        }
        val now = clock()
        cached(key) { now - it.fetchedAtMillis <= MAX_STALE_CACHE_MILLIS }?.let { return Result.success(it) }
        return Result.failure(lastError ?: IllegalStateException("날씨 정보를 불러오지 못했습니다."))
    }

    private suspend fun request(latitude: Double, longitude: Double): WeatherInfo {
        val response = http.send("GET", forecastUrl(latitude, longitude), emptyMap(), null)
        check(response.status == 200) { "HTTP " + response.status }
        return parse(response.body, clock())
    }

    /** 메모리 → 저장소 순으로 [fresh] 를 만족하는 캐시. 저장소에서 찾으면 메모리에도 올린다. */
    private fun cached(key: String, fresh: (WeatherInfo) -> Boolean): WeatherInfo? {
        locked(memoryLock) { memoryCache[key] }?.takeIf(fresh)?.let { return it }
        val stored = cache?.getString(key, null)?.let(::decodeCache)?.takeIf(fresh) ?: return null
        locked(memoryLock) { memoryCache[key] = stored }
        return stored
    }

    private fun remember(key: String, info: WeatherInfo) {
        locked(memoryLock) { memoryCache[key] = info }
        cache?.edit()?.putString(key, encodeCache(info))?.apply()
    }

    companion object {
        internal const val CACHE_TTL_MILLIS = 60 * 60 * 1000L
        internal const val MAX_STALE_CACHE_MILLIS = 3 * 60 * 60 * 1000L

        /** 지금 날씨만(브랜치와 같은 네 값 + 낮밤). 풍속은 km/h(판정 기준 35 km/h). */
        fun forecastUrl(latitude: Double, longitude: Double): String =
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,precipitation,weather_code,wind_speed_10m,is_day&timezone=auto"

        // 메인(peek)과 받기 코루틴이 같이 읽고 쓴다 — 앱 수명 하나(브랜치의 ConcurrentHashMap 자리)
        private val memoryLock = SynchronizedObject()
        private val memoryCache = HashMap<String, WeatherInfo>()

        /** 좌표 소수 둘째 자리(브랜치 `String.format(Locale.US, "%.2f_%.2f")` 와 같은 모양). */
        internal fun cacheKey(latitude: Double, longitude: Double): String = twoDecimals(latitude) + "_" + twoDecimals(longitude)

        private fun twoDecimals(value: Double): String {
            val hundredths = (value * 100).roundToLong()
            val whole = abs(hundredths)
            return (if (hundredths < 0) "-" else "") + (whole / 100) + "." + (whole % 100).toString().padStart(2, '0')
        }

        private const val CACHE_VERSION = "3"

        /** 캐시 한 줄 — `3|받은시각|기온|강수|코드|풍속|낮(1/0)`. org.json 없이 쓰고 읽는다. */
        internal fun encodeCache(info: WeatherInfo): String = listOf(
            CACHE_VERSION, info.fetchedAtMillis.toString(), info.temperature.toString(), info.precipitation.toString(),
            info.weatherCode.toString(), info.windSpeed.toString(), if (info.isDay) "1" else "0",
        ).joinToString("|")

        /** [encodeCache] 의 거꾸로 — 모양이 다르면(브랜치의 JSON 캐시·예전 판) null 이라 새로 받는다. */
        internal fun decodeCache(text: String): WeatherInfo? {
            val f = text.split('|')
            if (f.size != 7 || f[0] != CACHE_VERSION) return null
            return WeatherInfo(
                temperature = f[2].toDoubleOrNull() ?: return null,
                precipitation = f[3].toDoubleOrNull() ?: return null,
                weatherCode = f[4].toIntOrNull() ?: return null,
                windSpeed = f[5].toDoubleOrNull() ?: return null,
                fetchedAtMillis = f[1].toLongOrNull() ?: return null,
                isDay = f[6] != "0",
            )
        }
    }
}

/** Open-Meteo 응답 → 날씨. 판정에 쓰는 네 값(기온·강수·코드·풍속)이 없거나 숫자가 아니면 실패로 던진다(브랜치와 같다). 낮밤은 없으면 낮. */
internal fun parseOpenMeteo(responseText: String, fetchedAtMillis: Long): WeatherInfo {
    val root = JSONObject(responseText)
    check(root.has("current") && !root.isNull("current")) { "current 필드가 없습니다." }
    val current = root.getJSONObject("current")
    for (key in listOf("temperature_2m", "precipitation", "weather_code", "wind_speed_10m")) {
        check(current.has(key) && !current.isNull(key)) { "필수 날씨 필드가 누락되었습니다: $key" }
    }
    val temperature = current.getDouble("temperature_2m")
    val precipitation = current.getDouble("precipitation")
    val windSpeed = current.getDouble("wind_speed_10m")
    check(!temperature.isNaN() && !precipitation.isNaN() && !windSpeed.isNaN()) { "유효하지 않은 날씨 수치입니다." }
    return WeatherInfo(
        temperature = temperature,
        precipitation = precipitation,
        weatherCode = current.getInt("weather_code"),
        windSpeed = windSpeed,
        fetchedAtMillis = fetchedAtMillis,
        isDay = current.optInt("is_day", 1) != 0,
    )
}
