package com.example.trex_kotlin.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

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
    fun resolveCoordinates(): Triple<Double, Double, String> {
        if (isGpsModeEnabled() && hasCoarseLocationPermission()) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val loc = runCatching {
                lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }.getOrNull()

            if (loc != null) {
                return Triple(loc.latitude, loc.longitude, "현재 위치")
            }
        }

        val region = getSavedRegion()
        return Triple(region.latitude, region.longitude, region.displayName)
    }

    companion object {
        private const val PREFS_NAME = "trex_weather_location_prefs"
        private const val KEY_SELECTED_REGION = "selected_region"
        private const val KEY_USE_GPS = "use_gps"
    }
}
