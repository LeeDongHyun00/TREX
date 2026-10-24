@file:OptIn(ExperimentalForeignApi::class)

package com.example.trex_kotlin.weather

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.NSObject
import kotlin.coroutines.resume

/*
 * 날씨 위치의 iPhone 짝(§108e) — Android `weather/WeatherLocatorAndroid.kt` 와 같은 이름. Core Location 'When In Use' 권한,
 * 30분 안의 위치는 그대로(Android 와 같은 기준), 없으면 한 번 요청(5 s 제한). 날씨에는 1 km 정확도면 충분하다 — 빨리 잡히고 전력도 적다.
 * 위치 관리자와 사건은 메인 스레드다(카드가 메인 코루틴에서 부른다). Info.plist 의 NSLocationWhenInUseUsageDescription 이 문구다.
 */

@Composable
fun rememberWeatherLocator(): WeatherLocator = remember { IosWeatherLocator() }

private const val MAX_LAST_KNOWN_AGE_S = 30 * 60.0

private class IosWeatherLocator : WeatherLocator {
    private val manager = CLLocationManager()
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private var waiting: CancellableContinuation<Pair<Double, Double>?>? = null

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            val status = manager.authorizationStatus
            if (status == kCLAuthorizationStatusNotDetermined) return
            val callback = permissionCallback ?: return
            permissionCallback = null
            callback(granted(status))
        }

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            finish(location.coordinate.useContents { latitude to longitude })
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) = finish(null)
    }

    init {
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
    }

    private fun granted(status: CLAuthorizationStatus) =
        status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways

    override fun hasPermission(): Boolean = granted(manager.authorizationStatus)

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        if (manager.authorizationStatus != kCLAuthorizationStatusNotDetermined) return onResult(hasPermission())
        permissionCallback = onResult
        manager.requestWhenInUseAuthorization()
    }

    override suspend fun currentCoordinates(): Pair<Double, Double>? {
        if (!hasPermission()) return null
        manager.location?.let { last ->
            if (-last.timestamp.timeIntervalSinceNow <= MAX_LAST_KNOWN_AGE_S) return last.coordinate.useContents { latitude to longitude }
        }
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                waiting?.let { if (it.isActive) it.resume(null) }   // 앞 요청은 위치 없음으로 끝낸다
                waiting = cont
                cont.invokeOnCancellation { if (waiting === cont) waiting = null }
                manager.requestLocation()
            }
        }
    }

    private fun finish(result: Pair<Double, Double>?) {
        val cont = waiting ?: return
        waiting = null
        if (cont.isActive) cont.resume(result)
    }
}
