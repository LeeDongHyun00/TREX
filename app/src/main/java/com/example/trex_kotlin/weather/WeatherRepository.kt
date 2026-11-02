package com.example.trex_kotlin.weather

import android.content.Context
import com.example.trex_kotlin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class WeatherRepository(context: Context? = null) {

    private val prefs = context?.applicationContext?.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    fun peekCachedWeather(latitude: Double, longitude: Double): WeatherInfo? {
        val cacheKey = formatCacheKey(latitude, longitude)
        val now = System.currentTimeMillis()
        memoryCache[cacheKey]?.let { cached ->
            if (now - cached.fetchedAtMillis <= MAX_STALE_CACHE_MILLIS) return cached
        }
        readPrefsCache(cacheKey)?.let { cached ->
            if (now - cached.fetchedAtMillis <= MAX_STALE_CACHE_MILLIS) {
                memoryCache[cacheKey] = cached
                return cached
            }
        }
        return null
    }

    suspend fun fetchCurrentWeather(
        latitude: Double,
        longitude: Double,
        forceRefresh: Boolean = false
    ): Result<WeatherInfo> = withContext(Dispatchers.IO) {
        val cacheKey = formatCacheKey(latitude, longitude)
        val now = System.currentTimeMillis()

        if (!forceRefresh) {
            memoryCache[cacheKey]?.let { cached ->
                if (now - cached.fetchedAtMillis < CACHE_TTL_MILLIS) {
                    return@withContext Result.success(cached)
                }
            }
            readPrefsCache(cacheKey)?.let { cached ->
                if (now - cached.fetchedAtMillis < CACHE_TTL_MILLIS) {
                    memoryCache[cacheKey] = cached
                    return@withContext Result.success(cached)
                }
            }
        }

        var lastError: Throwable? = null
        repeat(2) { attempt ->
            val result = runCatching {
                coroutineScope {
                    val weatherDeferred = async { requestOpenMeteo(latitude, longitude) }
                    val airDeferred = async { runCatching { requestAqicn(latitude, longitude) }.getOrNull() }

                    val weatherInfo = weatherDeferred.await()
                    val airInfo = airDeferred.await()

                    weatherInfo.copy(
                        aqi = airInfo?.aqi,
                        pm10 = airInfo?.pm10,
                        pm25 = airInfo?.pm25
                    )
                }
            }
            if (result.isSuccess) {
                val info = result.getOrThrow()
                memoryCache[cacheKey] = info
                writePrefsCache(cacheKey, info)
                return@withContext Result.success(info)
            }
            lastError = result.exceptionOrNull()
            if (attempt == 0) delay(400L)
        }

        val fallbackNow = System.currentTimeMillis()
        memoryCache[cacheKey]?.let { cached ->
            if (fallbackNow - cached.fetchedAtMillis <= MAX_STALE_CACHE_MILLIS) {
                return@withContext Result.success(cached)
            }
        }
        readPrefsCache(cacheKey)?.let { cached ->
            if (fallbackNow - cached.fetchedAtMillis <= MAX_STALE_CACHE_MILLIS) {
                memoryCache[cacheKey] = cached
                return@withContext Result.success(cached)
            }
        }

        Result.failure(lastError ?: IllegalStateException("날씨 정보를 불러오지 못했습니다."))
    }

    private fun requestOpenMeteo(latitude: Double, longitude: Double): WeatherInfo {
        val urlString = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=" + latitude +
            "&longitude=" + longitude +
            "&current=temperature_2m,precipitation,weather_code,wind_speed_10m" +
            "&timezone=auto"

        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP " + connection.responseCode)
            }
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            parseCurrentWeatherJson(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private data class AirQualityRaw(
        val aqi: Int?,
        val pm10: Double?,
        val pm25: Double?
    )

    private fun requestAqicn(latitude: Double, longitude: Double): AirQualityRaw? {
        val token = runCatching { BuildConfig.AQICN_API_KEY }.getOrDefault("demo").ifBlank { "demo" }
        val urlString = String.format(
            Locale.US,
            "https://api.waqi.info/feed/geo:%.4f;%.4f/?token=%s",
            latitude,
            longitude,
            token
        )

        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            parseAqicnJson(responseText)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseAqicnJson(responseText: String): AirQualityRaw? {
        val root = JSONObject(responseText)
        if (root.optString("status") != "ok") return null
        val data = root.optJSONObject("data") ?: return null

        val aqi = if (data.has("aqi") && !data.isNull("aqi")) {
            data.optInt("aqi", -1).takeIf { it >= 0 }
        } else null

        val iaqi = data.optJSONObject("iaqi")
        val pm10 = iaqi?.optJSONObject("pm10")?.optDouble("v")?.takeIf { !it.isNaN() }
        val pm25 = iaqi?.optJSONObject("pm25")?.optDouble("v")?.takeIf { !it.isNaN() }

        return AirQualityRaw(aqi = aqi, pm10 = pm10, pm25 = pm25)
    }

    internal fun parseCurrentWeatherJson(
        responseText: String,
        fetchedAtMillis: Long = System.currentTimeMillis()
    ): WeatherInfo {
        val root = JSONObject(responseText)
        if (!root.has("current") || root.isNull("current")) {
            throw IllegalStateException("current 필드가 없습니다.")
        }
        val currentJson = root.getJSONObject("current")
        val requiredKeys = listOf("temperature_2m", "precipitation", "weather_code", "wind_speed_10m")
        for (key in requiredKeys) {
            if (!currentJson.has(key) || currentJson.isNull(key)) {
                throw IllegalStateException("필수 날씨 필드가 누락되었습니다: " + key)
            }
        }

        val temperature = currentJson.getDouble("temperature_2m")
        val precipitation = currentJson.getDouble("precipitation")
        val weatherCode = currentJson.getInt("weather_code")
        val windSpeed = currentJson.getDouble("wind_speed_10m")

        if (temperature.isNaN() || precipitation.isNaN() || windSpeed.isNaN()) {
            throw IllegalStateException("유효하지 않은 날씨 수치입니다.")
        }

        return WeatherInfo(
            temperature = temperature,
            precipitation = precipitation,
            weatherCode = weatherCode,
            windSpeed = windSpeed,
            fetchedAtMillis = fetchedAtMillis
        )
    }

    private fun formatCacheKey(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.2f_%.2f", latitude, longitude)

    private fun readPrefsCache(key: String): WeatherInfo? {
        val raw = prefs?.getString(key, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            WeatherInfo(
                temperature = obj.getDouble("t"),
                precipitation = obj.getDouble("p"),
                weatherCode = obj.getInt("c"),
                windSpeed = obj.getDouble("w"),
                fetchedAtMillis = obj.getLong("ts"),
                aqi = if (obj.has("aqi") && !obj.isNull("aqi")) obj.getInt("aqi") else null,
                pm10 = if (obj.has("pm10") && !obj.isNull("pm10")) obj.getDouble("pm10") else null,
                pm25 = if (obj.has("pm25") && !obj.isNull("pm25")) obj.getDouble("pm25") else null
            )
        }.getOrNull()
    }

    private fun writePrefsCache(key: String, info: WeatherInfo) {
        prefs?.edit()?.putString(
            key,
            JSONObject().apply {
                put("t", info.temperature)
                put("p", info.precipitation)
                put("c", info.weatherCode)
                put("w", info.windSpeed)
                put("ts", info.fetchedAtMillis)
                info.aqi?.let { put("aqi", it) }
                info.pm10?.let { put("pm10", it) }
                info.pm25?.let { put("pm25", it) }
            }.toString()
        )?.apply()
    }

    companion object {
        private const val CACHE_PREFS = "trex_weather_cache_prefs"
        internal const val CACHE_TTL_MILLIS = 60 * 60 * 1000L
        internal const val MAX_STALE_CACHE_MILLIS = 3 * 60 * 60 * 1000L

        private val memoryCache = ConcurrentHashMap<String, WeatherInfo>()
    }
}