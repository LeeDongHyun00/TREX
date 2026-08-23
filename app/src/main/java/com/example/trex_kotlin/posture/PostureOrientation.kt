package com.example.trex_kotlin.posture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * IMU 중력축 → 신체 좌표계의 위 방향(up) 산출.
 *
 * 기존에는 "폰을 세로로 세워 거치"한다고 가정하고 화면 세로축을 up 으로 썼다.
 * 폰이 기울거나(피치) 회전하면(롤) 화면 세로축과 중력축이 어긋나 모든 높이·수직 피처가 틀어지므로,
 * TYPE_GRAVITY 센서로 얻은 중력 벡터를 MediaPipe world 좌표계로 옮겨 up = -중력 으로 쓴다.
 *
 * ## 센서 부호 규약 (버그 이력)
 * Android 의 TYPE_ACCELEROMETER / TYPE_GRAVITY 는 정지 상태에서 **반작용 벡터**를 보고한다 — 기기를 화면 위로 평평히 놓으면
 * z = +9.81 (지면 반대 방향). 즉 센서 값은 "아래"가 아니라 "위"를 가리킨다. 초기 구현은 이를 아래 방향으로 가정해
 * up 이 180° 뒤집혔고(세운 폰에서 tilt 175°, 실기기 로그로 확인), 높이·수직 피처 전부의 부호가 반전됐다.
 * [sensorGravityToDown] 으로 부호를 뒤집어 "아래 방향" 중력 벡터로 만든 뒤 사용한다.
 * 추가 안전장치로 PostureCore.checkUpSanity 가 관절 배치(귀>어깨, 골반>발목)로 up 방향을 검증한다.
 *
 * ## 좌표계 유도
 * - 센서(디바이스 자연좌표): x 오른쪽, y 위(기기 상단), z 화면 바깥
 * - 분석 이미지: CameraX 가 targetRotation(기본=디스플레이 회전)에 맞춰 회전시킨 뒤 우리가 다시 회전 보정하므로
 *   이미지 위 = 디스플레이 위, 이미지 오른쪽 = 카메라 시선 방향에 따라 결정된다.
 *   카메라 right = viewDir × up 이므로 후면(viewDir=-z)은 디스플레이 right, 전면(viewDir=+z)은 그 반대.
 * - world 좌표계(PostureAnalyzer 에서 y·z 부호 반전 후): X = 이미지 오른쪽, Y = 이미지 위, Z = 카메라 쪽
 *   (MediaPipe world z 는 카메라에서 멀어질수록 크므로 부호를 뒤집으면 카메라 방향이 +)
 */

/** 디스플레이 회전별 (디스플레이 right, 디스플레이 up) 을 디바이스 자연좌표로 표현. */
internal fun displayAxes(displayRotation: Int): Pair<Vec3, Vec3> = when (displayRotation) {
    Surface.ROTATION_90 -> Vec3(0f, -1f, 0f) to Vec3(1f, 0f, 0f)
    Surface.ROTATION_180 -> Vec3(-1f, 0f, 0f) to Vec3(0f, -1f, 0f)
    Surface.ROTATION_270 -> Vec3(0f, 1f, 0f) to Vec3(-1f, 0f, 0f)
    else -> Vec3(1f, 0f, 0f) to Vec3(0f, 1f, 0f)
}

/** 화면 세로축을 중력으로 가정할 때의 up (센서를 못 쓸 때의 폴백). */
val SCREEN_UP: Vec3 = Vec3(0f, 1f, 0f)

/**
 * Android 센서 값(TYPE_GRAVITY / 정지 상태 가속도계; 지면 **반대** 방향, 평평히 놓으면 (0,0,+9.81))을
 * "아래 방향" 중력 벡터로 변환한다. [gravityUpInWorld] 는 아래 방향 벡터를 받는다.
 */
fun sensorGravityToDown(sensorValues: Vec3): Vec3 = sensorValues * -1f

/**
 * 디바이스 자연좌표의 중력 벡터를 world 좌표계의 up(= -중력, 단위벡터)으로 변환.
 * @param gravityDevice TYPE_GRAVITY 값 (아래를 향함)
 * @param displayRotation Surface.ROTATION_*
 * @param isFrontCamera 전면 카메라 여부
 */
fun gravityUpInWorld(gravityDevice: Vec3, displayRotation: Int, isFrontCamera: Boolean): Vec3? {
    val g = gravityDevice.unit() ?: return null
    val (displayRight, displayUp) = displayAxes(displayRotation)
    val imageRight = if (isFrontCamera) displayRight * -1f else displayRight
    val towardCamera = if (isFrontCamera) Vec3(0f, 0f, -1f) else Vec3(0f, 0f, 1f)
    val gWorld = Vec3(g dot imageRight, g dot displayUp, g dot towardCamera)
    return (gWorld * -1f).unit()
}

/** up 이 화면 세로축에서 얼마나 벗어났는지(도) — UI 표시용. */
fun tiltFromScreenUpDegrees(up: Vec3): Float = angleVec(up, SCREEN_UP) ?: 0f

/**
 * TYPE_GRAVITY(없으면 가속도계 저역통과) 를 구독해 최신 중력 벡터를 보관한다.
 * 화면이 열려 있는 동안만 [start]/[stop].
 */
class GravityTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val usingAccelFallback = gravitySensor == null && accelSensor != null

    /** 디바이스 자연좌표 기준 중력 벡터(아래 방향). 아직 값이 없으면 null. */
    @Volatile
    var gravityDevice: Vec3? = null
        private set

    val available: Boolean get() = gravitySensor != null || accelSensor != null

    fun start() {
        val sm = sensorManager ?: return
        val sensor = gravitySensor ?: accelSensor ?: return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // 센서는 반작용(위) 방향을 보고하므로 아래 방향으로 뒤집는다 (파일 상단 '센서 부호 규약' 참고)
        val v = sensorGravityToDown(Vec3(event.values[0], event.values[1], event.values[2]))
        gravityDevice = if (usingAccelFallback) {
            // 가속도계 폴백: 저역통과로 중력 성분만 남긴다
            val prev = gravityDevice
            if (prev == null) v else prev * 0.9f + v * 0.1f
        } else {
            v
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
