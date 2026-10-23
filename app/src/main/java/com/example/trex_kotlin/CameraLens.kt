package com.example.trex_kotlin

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import com.example.trex_kotlin.posture.LensInfo

/**
 * 바인딩된 카메라의 렌즈 사양을 세트 로그의 배치 지문(§91, `CameraPlacement`)용으로 읽는다.
 * 초점거리·센서 크기가 없으면 거리·높이 추정이 빠질 뿐 세션은 그대로 간다 — 기기가 특성을 안 주거나 Camera2 가 아닌 구현이면 null.
 * 줌은 바인딩 직후 값이다(앱은 줌을 바꾸지 않는다).
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
fun readLensInfo(camera: Camera): LensInfo? = try {
    val info = Camera2CameraInfo.from(camera.cameraInfo)
    val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
    val physical = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    LensInfo(
        focalMm = focal,
        sensorWMm = physical?.width, sensorHMm = physical?.height,
        activeW = active?.width(), activeH = active?.height(),
        zoom = camera.cameraInfo.zoomState.value?.zoomRatio,
    )
} catch (_: Throwable) {
    null
}
