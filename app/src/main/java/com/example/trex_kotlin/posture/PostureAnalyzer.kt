package com.example.trex_kotlin.posture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * MediaPipe Pose Landmarker 래퍼 (spec §2~§3).
 * ImageProxy → 회전 보정 비트맵 → detectForVideo → 화면 오버레이용 정규화 좌표 + 판정용 월드 피처.
 */

const val MP_LANDMARK_COUNT = 33
private const val MIN_VISIBILITY = 0.5f

/** 한 프레임 추론 결과. */
class PoseSample(
    val detected: Boolean,
    /** 정규화 좌표 (회전 보정된 이미지 기준, 0..1). size = 33*2 (x,y 반복) */
    val normalizedXy: FloatArray,
    val visibility: FloatArray,
    val features: Map<String, Float>,
    val visibleJointCount: Int,
    val inferMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    companion object {
        fun empty(inferMs: Long = 0L, w: Int = 0, h: Int = 0) = PoseSample(
            detected = false,
            normalizedXy = FloatArray(MP_LANDMARK_COUNT * 2),
            visibility = FloatArray(MP_LANDMARK_COUNT),
            features = emptyMap(),
            visibleJointCount = 0,
            inferMs = inferMs,
            imageWidth = w,
            imageHeight = h,
        )
    }
}

class PostureAnalyzer(context: Context, modelAsset: String = "posture/pose_landmarker_full.task") {

    private val landmarker: PoseLandmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAsset).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputSegmentationMasks(false)
            .build(),
    )

    private var lastTimestampMs = 0L

    /** ImageProxy 를 소비하지 않는다 — 호출 측에서 close() 할 것. */
    fun analyze(image: ImageProxy, timestampMs: Long): PoseSample {
        val bitmap = try {
            image.toBitmap()
        } catch (t: Throwable) {
            return PoseSample.empty()
        }
        val rotation = image.imageInfo.rotationDegrees
        val upright = if (rotation == 0) bitmap else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
        val ts = maxOf(timestampMs, lastTimestampMs + 1)
        lastTimestampMs = ts
        val started = System.nanoTime()
        val result: PoseLandmarkerResult? = try {
            landmarker.detectForVideo(BitmapImageBuilder(upright).build(), ts)
        } catch (t: Throwable) {
            null
        }
        val inferMs = (System.nanoTime() - started) / 1_000_000
        val w = upright.width
        val h = upright.height
        if (upright !== bitmap) upright.recycle()

        val landmarks = result?.landmarks()?.firstOrNull()
        val world = result?.worldLandmarks()?.firstOrNull()
        if (landmarks == null || world == null || landmarks.size < MP_LANDMARK_COUNT) {
            return PoseSample.empty(inferMs, w, h)
        }

        val xy = FloatArray(MP_LANDMARK_COUNT * 2)
        val vis = FloatArray(MP_LANDMARK_COUNT)
        for (i in 0 until MP_LANDMARK_COUNT) {
            val lm = landmarks[i]
            xy[i * 2] = lm.x()
            xy[i * 2 + 1] = lm.y()
            val v = lm.visibility().orElse(1f)
            val p = lm.presence().orElse(1f)
            vis[i] = minOf(v, p)
        }

        // 월드 좌표: m → cm, y/z 부호 반전 (spec §3)
        val pts = arrayOfNulls<Vec3>(MP_LANDMARK_COUNT)
        for (i in 0 until MP_LANDMARK_COUNT) {
            if (vis[i] < MIN_VISIBILITY) continue
            val p = world[i]
            pts[i] = Vec3(p.x() * 100f, -p.y() * 100f, -p.z() * 100f)
        }

        val joints = HashMap<String, Vec3?>(24)
        for ((name, idx) in Joints.SINGLE) joints[name] = pts.getOrNull(idx)
        for ((name, pair) in Joints.PAIR) {
            val a = pts.getOrNull(pair.first)
            val b = pts.getOrNull(pair.second)
            joints[name] = if (a != null && b != null) mid(a, b) else a ?: b
        }
        val frame = PoseFrame(joints)
        val features = frame.features()
        val visibleCount = vis.count { it >= MIN_VISIBILITY }
        return PoseSample(
            detected = true,
            normalizedXy = xy,
            visibility = vis,
            features = features,
            visibleJointCount = visibleCount,
            inferMs = inferMs,
            imageWidth = w,
            imageHeight = h,
        )
    }

    fun close() {
        try {
            landmarker.close()
        } catch (_: Throwable) {
        }
    }
}

/** 오버레이용 골격 연결 (MediaPipe 33점). */
val POSE_CONNECTIONS: List<Pair<Int, Int>> = listOf(
    // 얼굴
    0 to 1, 1 to 2, 2 to 3, 3 to 7, 0 to 4, 4 to 5, 5 to 6, 6 to 8,
    9 to 10,
    // 몸통
    11 to 12, 11 to 23, 12 to 24, 23 to 24,
    // 왼팔
    11 to 13, 13 to 15, 15 to 17, 15 to 19, 15 to 21, 17 to 19,
    // 오른팔
    12 to 14, 14 to 16, 16 to 18, 16 to 20, 16 to 22, 18 to 20,
    // 왼다리
    23 to 25, 25 to 27, 27 to 29, 27 to 31, 29 to 31,
    // 오른다리
    24 to 26, 26 to 28, 28 to 30, 28 to 32, 30 to 32,
)
