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

    fun hasCoarseLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun resolveCoordinates(): Triple<Double, Double, String> {
        if (isGpsModeEnabled() && hasCoarseLocationPermission()) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                val netLoc = runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
                val passiveLoc = runCatching { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
                val cached = netLoc ?: passiveLoc

                if (cached != null) {
                    return Triple(cached.latitude, cached.longitude, "현재 위치")
                }

                val fresh = requestSingleCoarseLocation(lm)
                if (fresh != null) {
                    return Triple(fresh.latitude, fresh.longitude, "현재 위치")
                }
            }

            val fallback = getSavedRegion()
            return Triple(fallback.latitude, fallback.longitude, fallback.displayName + " (위치 확인 불가)")
        }

        val region = getSavedRegion()
        return Triple(region.latitude, region.longitude, region.displayName)
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleCoarseLocation(lm: LocationManager): Location? =
        withTimeoutOrNull(2000L) {
            suspendCancellableCoroutine { cont ->
                val provider = when {
                    runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
                        LocationManager.NETWORK_PROVIDER
                    runCatching { lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) }.getOrDefault(false) ->
                        LocationManager.PASSIVE_PROVIDER
                    else -> null
                }

                if (provider == null) {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }

                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { lm.removeUpdates(this) }
                        if (cont.isActive) cont.resume(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {
                        runCatching { lm.removeUpdates(this) }
                        if (cont.isActive) cont.resume(null)
                    }
                }

                cont.invokeOnCancellation {
                    runCatching { lm.removeUpdates(listener) }
                }

                runCatching {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    companion object {
        private const val PREFS_NAME = "trex_weather_location_prefs"
        private const val KEY_SELECTED_REGION = "selected_region"
        private const val KEY_USE_GPS = "use_gps"
    }
}
