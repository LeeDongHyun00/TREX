package com.example.trex_kotlin.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.abs

class LocationPreferenceManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val memoryCache = ConcurrentHashMap<String, String>()

    val useGpsMode: Boolean
        get() = prefs.getBoolean(KEY_USE_GPS, false)

    fun enableGpsMode() {
        prefs.edit().putBoolean(KEY_USE_GPS, true).apply()
    }

    fun saveSelectedRegion(region: KoreanRegion) {
        prefs.edit()
            .putString(KEY_SELECTED_REGION_ID, region.id)
            .remove(KEY_SELECTED_REGION_NAME)
            .putBoolean(KEY_USE_GPS, false)
            .apply()
    }

    fun getSavedRegion(): KoreanRegion {
        val savedId = prefs.getString(KEY_SELECTED_REGION_ID, null)
        if (savedId != null) return KoreanRegions.fromId(savedId)

        val oldName = prefs.getString(KEY_SELECTED_REGION_NAME, null)
        val migrated = KoreanRegions.fromName(oldName)
        prefs.edit()
            .putString(KEY_SELECTED_REGION_ID, migrated.id)
            .remove(KEY_SELECTED_REGION_NAME)
            .apply()
        return migrated
    }

    suspend fun resolveCoordinates(): Triple<Double, Double, String> {
        val saved = getSavedRegion()
        if (!useGpsMode) return Triple(saved.latitude, saved.longitude, saved.displayName)

        val hasPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return Triple(saved.latitude, saved.longitude, "위치 권한 없음 · ${saved.displayName}")

        val recent = getMostRecentLocation()
        if (recent != null) return Triple(recent.latitude, recent.longitude, "현재 위치")

        val current = requestCurrentLocationOrNull()
        return if (current != null) {
            Triple(current.latitude, current.longitude, "현재 위치")
        } else {
            Triple(saved.latitude, saved.longitude, "현재 위치 확인 불가 · ${saved.displayName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun getMostRecentLocation(): Location? {
        return try {
            val providers = locationManager.getProviders(true)
            var best: Location? = null
            for (provider in providers) {
                val location = locationManager.getLastKnownLocation(provider) ?: continue
                if (System.currentTimeMillis() - location.time > LOCATION_MAX_AGE_MILLIS) continue
                if (best == null || location.accuracy < best.accuracy) best = location
            }
            best
        } catch (_: SecurityException) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCurrentLocationOrNull(): Location? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (continuation.isActive) continuation.resume(location)
                    try { locationManager.removeUpdates(this) } catch (_: Exception) { }
                }

                @Deprecated("Deprecated by Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }

            val providers = try {
                locationManager.getProviders(true)
            } catch (_: SecurityException) {
                emptyList()
            }

            if (providers.isEmpty()) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val bestProvider = providers.minByOrNull { provider ->
                when (provider) {
                    LocationManager.GPS_PROVIDER -> 0
                    LocationManager.NETWORK_PROVIDER -> 1
                    else -> 2
                }
            } ?: run {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            try {
                locationManager.requestLocationUpdates(
                    bestProvider,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
                continuation.invokeOnCancellation {
                    try { locationManager.removeUpdates(listener) } catch (_: Exception) { }
                }
                continuation.context[kotlinx.coroutines.Job]?.let { job ->
                    kotlinx.coroutines.GlobalScope.launch(job) {
                        kotlinx.coroutines.delay(LOCATION_TIMEOUT_MILLIS)
                        if (continuation.isActive) {
                            try { locationManager.removeUpdates(listener) } catch (_: Exception) { }
                            continuation.resume(null)
                        }
                    }
                }
            } catch (_: SecurityException) {
                continuation.resume(null)
            } catch (_: Exception) {
                continuation.resume(null)
            }
        }

    companion object {
        private const val PREFS_NAME = "weather_preferences"
        private const val KEY_USE_GPS = "use_gps_mode"
        private const val KEY_SELECTED_REGION_ID = "selected_region_id"
        private const val KEY_SELECTED_REGION_NAME = "selected_region"
        private const val LOCATION_MAX_AGE_MILLIS = 30 * 60 * 1000L
        private const val LOCATION_TIMEOUT_MILLIS = 7_500L
    }
}
