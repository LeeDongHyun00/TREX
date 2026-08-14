package com.example.trex_kotlin.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.trex_kotlin.pose.runtime.PoseGravityReading

/**
 * Samples the device's gravity vector and expresses it the way the pose landmarks are.
 *
 * Two rotations separate the sensor from the picture and both are applied here.
 *
 * The sensor frame is fixed to the device in its natural orientation, x right and y **up**. The
 * display may be rotated within that frame, so a vector is turned by the display rotation to reach
 * screen axes. The pose coordinates then live in an image whose y runs **down**, which is the
 * final flip. A phone held upright in portrait therefore reads gravity as pointing along +y in the
 * landmark frame, which is what "down" should mean to anything drawing on that picture.
 *
 * The camera's own mounting rotation is already spent: the preprocessing rotates the frame upright
 * before inference, so the landmarks arrive in display-upright axes rather than sensor-native
 * ones. Mirroring is not undone, because it is applied for display only and the world landmarks
 * this reading is compared against are never mirrored.
 *
 * What deliberately does not happen here is any attempt to recover the out-of-plane component in
 * the camera's world pose. [PoseGravityReading] keeps a projected direction and refuses to exist
 * when too little of gravity lies in the plane, which is the honest shape for a claim built from a
 * sensor the camera geometry contract does not attest.
 */
@Composable
internal fun rememberDeviceGravity(active: Boolean = true): State<PoseGravityReading?> {
    val context = LocalContext.current
    val reading = remember { mutableStateOf<PoseGravityReading?>(null) }

    DisposableEffect(context, active) {
        if (!active) {
            reading.value = null
            return@DisposableEffect onDispose { }
        }
        val sensors = ContextCompat.getSystemService(context, SensorManager::class.java)
        val gravity = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (sensors == null || gravity == null) {
            // A device without the sensor gets silence, and every clause built on this abstains.
            reading.value = null
            return@DisposableEffect onDispose { }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                val rotation = displayRotation(context)
                val (screenX, screenY) = rotateIntoScreen(
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    rotation,
                )
                reading.value = PoseGravityReading.of(
                    x = screenX,
                    // Sensor y points up, image y points down.
                    y = -screenY,
                    outOfPlane = event.values[2].toDouble(),
                    timestampMs = SystemClock.elapsedRealtime(),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensors.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            sensors.unregisterListener(listener)
            reading.value = null
        }
    }
    return reading
}

/**
 * The display's rotation, by whichever route this API level offers.
 *
 * `Context.getDisplay` arrived in API 30 and this app ships to 26, so the windowed accessor is the
 * fallback. A device that answers neither is treated as unrotated, which is the reading a phone
 * held normally gives anyway.
 */
private fun displayRotation(context: Context): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        ContextCompat.getSystemService(context, WindowManager::class.java)
            ?.defaultDisplay
            ?.rotation
            ?: Surface.ROTATION_0
    }

/** Turns a device-frame vector into screen axes for the current display rotation. */
private fun rotateIntoScreen(x: Double, y: Double, rotation: Int): Pair<Double, Double> =
    when (rotation) {
        Surface.ROTATION_90 -> -y to x
        Surface.ROTATION_180 -> -x to -y
        Surface.ROTATION_270 -> y to -x
        else -> x to y
    }
