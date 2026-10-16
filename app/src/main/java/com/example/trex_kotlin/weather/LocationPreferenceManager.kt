package com.example.trex_kotlin.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.util.Consumer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class LocationPreferenceManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSelectedRegion(region: KoreanRegion) {
        prefs.edit()
            .putString(KEY_SELECTED_REGION, region.displayName)
            .putBoolean(KEY_USE_GPS, false)
            .apply()
    }

    fun enableGpsMode() {
        prefs.edit().putBoolean(KEY_USE_GPS, true).apply()
    }

    fun isGpsModeEnabled(): Boolean = prefs.getBoolean(KEY_USE_GPS, false)

    fun getSavedRegion(): KoreanRegion {
        val name = prefs.getString(KEY_SELECTED_REGION, KoreanRegion.SEOUL.displayName)
        return KoreanRegion.fromName(name)
    }

    fun hasLocationPermission(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return coarse || fine
    }

    fun hasCoarseLocationPermission(): Boolean = hasLocationPermission()

    @SuppressLint("MissingPermission")
    suspend fun resolveCoordinates(): Triple<Double, Double, String> {
        if (isGpsModeEnabled() && hasLocationPermission()) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                val now = System.currentTimeMillis()
                val providers = listOf(
                    "fused",
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )

                val recentCached = providers.mapNotNull { provider ->
                    runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
                }.filter { loc ->
                    loc.time > 0L && (now - loc.time) in 0L..MAX_LAST_KNOWN_AGE_MILLIS
                }.maxByOrNull { it.time }

                if (recentCached != null) {
                    return Triple(recentCached.latitude, recentCached.longitude, "현재 위치")
                }

                val activeProviders = listOf(
                    "fused",
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER
                )
                for (provider in activeProviders) {
                    val enabled = runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
                    if (enabled) {
                        val fresh = requestCurrentLocationCompat(lm, provider)
                        if (fresh != null) {
                            return Triple(fresh.latitude, fresh.longitude, "현재 위치")
                        }
                    }
                }
            }

            val fallback = getSavedRegion()
            return Triple(fallback.latitude, fallback.longitude, fallback.displayName + " (위치 확인 불가)")
        }

        val region = getSavedRegion()
        return Triple(region.latitude, region.longitude, region.displayName)
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCurrentLocationCompat(
        lm: LocationManager,
        provider: String
    ): Location? = withTimeoutOrNull(2500L) {
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }

            runCatching {
                LocationManagerCompat.getCurrentLocation(
                    lm,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context),
                    Consumer { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                )
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "trex_weather_location_prefs"
        private const val KEY_SELECTED_REGION = "selected_region"
        private const val KEY_USE_GPS = "use_gps"
        private const val MAX_LAST_KNOWN_AGE_MILLIS = 30 * 60 * 1000L
    }
}
