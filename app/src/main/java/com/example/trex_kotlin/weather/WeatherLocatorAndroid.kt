package com.example.trex_kotlin.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.util.Consumer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/*
 * 날씨 위치의 Android 짝(§108e) — weather 브랜치 `LocationPreferenceManager` 의 권한·LocationManager 코드를 그대로 옮겼다.
 * iPhone 짝은 ios-ui iosMain `weather/WeatherLocatorIos.kt`(Core Location). 이 파일은 공유 목록에 넣지 않는다.
 */

@Composable
fun rememberWeatherLocator(): WeatherLocator {
    val context = LocalContext.current
    val locator = remember(context) { AndroidWeatherLocator(context.applicationContext) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        locator.onPermissionResult(
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
        )
    }
    locator.launch = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    return locator
}

private class AndroidWeatherLocator(private val context: Context) : WeatherLocator {
    var launch: () -> Unit = {}
    private var pending: ((Boolean) -> Unit)? = null

    fun onPermissionResult(granted: Boolean) {
        val callback = pending ?: return
        pending = null
        callback(granted)
    }

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        if (hasPermission()) return onResult(true)
        pending = onResult
        launch()
    }

    @SuppressLint("MissingPermission")
    override suspend fun currentCoordinates(): Pair<Double, Double>? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val now = System.currentTimeMillis()
        val providers = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val recentCached = providers.mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .filter { loc -> loc.time > 0L && (now - loc.time) in 0L..MAX_LAST_KNOWN_AGE_MILLIS }
            .maxByOrNull { it.time }
        if (recentCached != null) return recentCached.latitude to recentCached.longitude
        for (provider in listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val enabled = runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
            if (enabled) requestCurrentLocationCompat(lm, provider)?.let { return it.latitude to it.longitude }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCurrentLocationCompat(lm: LocationManager, provider: String): Location? = withTimeoutOrNull(2500L) {
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            runCatching {
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(context),
                    Consumer { location -> if (cont.isActive) cont.resume(location) })
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private companion object {
        const val MAX_LAST_KNOWN_AGE_MILLIS = 30 * 60 * 1000L
    }
}
