package com.example.trex_kotlin.weather

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class WeatherRepository(context: Context? = null) {

    private val prefs = context?.applicationContext?.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    suspend fun fetchCurrentWeather(
        latitude: Double,
        longitude: Double,
        forceRefresh: Boolean = false
    ): Result<WeatherInfo> = withContext(Dispatchers.IO) {
        val cacheKey = String.format(Locale.US, "%.2f_%.2f", latitude, longitude)
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
            val result = runCatching { requestOpenMeteo(latitude, longitude) }
            if (result.isSuccess) {
                val info = result.getOrThrow()
                memoryCache[cacheKey] = info
                writePrefsCache(cacheKey, info)
                return@withContext Result.success(info)
            }
            lastError = result.exceptionOrNull()
            if (attempt == 0) delay(400L)
        }

        memoryCache[cacheKey]?.let { return@withContext Result.success(it) }
        readPrefsCache(cacheKey)?.let { return@withContext Result.success(it) }

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
            val currentJson = JSONObject(responseText).getJSONObject("current")

            WeatherInfo(
                temperature = currentJson.optDouble("temperature_2m", 20.0),
                precipitation = currentJson.optDouble("precipitation", 0.0),
                weatherCode = currentJson.optInt("weather_code", 0),
                windSpeed = currentJson.optDouble("wind_speed_10m", 0.0),
                fetchedAtMillis = System.currentTimeMillis()
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readPrefsCache(key: String): WeatherInfo? {
        val raw = prefs?.getString(key, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            WeatherInfo(
                temperature = obj.getDouble("t"),
                precipitation = obj.getDouble("p"),
                weatherCode = obj.getInt("c"),
                windSpeed = obj.getDouble("w"),
                fetchedAtMillis = obj.getLong("ts")
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
            }.toString()
        )?.apply()
    }

    companion object {
        private const val CACHE_PREFS = "trex_weather_cache_prefs"
        private const val CACHE_TTL_MILLIS = 60 * 60 * 1000L
        private val memoryCache = mutableMapOf<String, WeatherInfo>()
    }
}
