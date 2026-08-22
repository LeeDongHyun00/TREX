package com.example.trex_kotlin.posture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * MediaPipe Pose Landmarker 래퍼 (spec §2~§3).
 *
 * 발열 대책 (PostureLabScreen 의 스케줄러와 짝):
 *  - 델리게이트: GPU 우선, 실패 시 CPU 폴백. 랜드마커는 호출 스레드(분석 스레드)에서 지연 생성한다 — GPU 는 GL 컨텍스트가
 *    생성 스레드에 묶이므로 같은 단일 스레드에서 생성·추론해야 한다.
 *  - 메모리: 회전 비트맵을 재사용하고(Canvas+Matrix) ImageProxy→Bitmap 변환은 실제로 추론하는 프레임에서만 한다.
 *  - 관측: 최근 추론 시간 EMA/평균, 총 횟수를 노출해 UI 와 로그에서 듀티를 볼 수 있게 한다.
 */

const val MP_LANDMARK_COUNT = 33
private const val MIN_VISIBILITY = 0.5f
private const val TAG = "PostureAnalyzer"

enum class PoseModel(val asset: String, val label: String) {
    FULL("posture/pose_landmarker_full.task", "full"),
    LITE("posture/pose_landmarker_lite.task", "lite"),
}

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
    /** 이 프레임 계산에 쓴 up 벡터 (IMU 중력축 또는 화면 세로축 폴백). */
    val up: Vec3 = SCREEN_UP,
    /** up 이 IMU 에서 온 것인지 (false = 화면 세로축 가정). */
    val upFromGravity: Boolean = false,
) {
    companion object {
        fun empty(inferMs: Long = 0L, w: Int = 0, h: Int = 0, up: Vec3 = SCREEN_UP, fromGravity: Boolean = false) = PoseSample(
            detected = false,
            normalizedXy = FloatArray(MP_LANDMARK_COUNT * 2),
            visibility = FloatArray(MP_LANDMARK_COUNT),
            features = emptyMap(),
            visibleJointCount = 0,
            inferMs = inferMs,
            imageWidth = w,
            imageHeight = h,
            up = up,
            upFromGravity = fromGravity,
        )
    }
}

/** 추론 통계 (UI 표시용 스냅샷). */
data class AnalyzerStats(
    val delegate: String,
    val model: String,
    val ready: Boolean,
    val inferCount: Long,
    val emaInferMs: Float,
    val lastInferMs: Long,
    val error: String?,
)

class PostureAnalyzer(
    private val context: Context,
    private val model: PoseModel = PoseModel.FULL,
    private val preferGpu: Boolean = true,
) {
    private var landmarker: PoseLandmarker? = null
    private var delegateName: String = "-"
    private var initError: String? = null
    private var lastTimestampMs = 0L

    // 회전 보정용 재사용 버퍼
    private var uprightBitmap: Bitmap? = null
    private val rotateMatrix = Matrix()
    private val canvas = Canvas()

    // 통계
    @Volatile private var inferCount = 0L
    @Volatile private var emaMs = 0f
    @Volatile private var lastMs = 0L

    val isReady: Boolean get() = landmarker != null

    fun stats() = AnalyzerStats(delegateName, model.label, landmarker != null, inferCount, emaMs, lastMs, initError)

    /** 분석 스레드에서 호출. GPU → CPU 순으로 시도. */
    @Synchronized
    fun ensureReady(): Boolean {
        if (landmarker != null) return true
        if (initError != null) return false
        val order = if (preferGpu) listOf(Delegate.GPU, Delegate.CPU) else listOf(Delegate.CPU)
        for (d in order) {
            try {
                val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(model.asset).setDelegate(d).build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setOutputSegmentationMasks(false)
                    .build()
                landmarker = PoseLandmarker.createFromOptions(context, opts)
                delegateName = if (d == Delegate.GPU) "GPU" else "CPU"
                Log.i(TAG, "PoseLandmarker ready: model=${model.label} delegate=$delegateName")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "delegate $d 생성 실패: ${t.message}")
                if (d == order.last()) initError = "모델 로드 실패(${model.label}/${d}): ${t.message}"
            }
        }
        return false
    }

    /**
     * ImageProxy 를 소비하지 않는다 — 호출 측에서 close() 할 것.
     * @param up 중력 반대 방향(world 좌표계 단위벡터). IMU 를 못 쓰면 [SCREEN_UP].
     */
    fun analyze(image: ImageProxy, timestampMs: Long, up: Vec3 = SCREEN_UP): PoseSample {
        val fromGravity = up !== SCREEN_UP
        if (!ensureReady()) return PoseSample.empty(up = up, fromGravity = fromGravity)
        val lm = landmarker ?: return PoseSample.empty(up = up, fromGravity = fromGravity)

        // YUV → Bitmap 변환은 실제 추론 프레임에서만 (스킵된 프레임은 비용 0)
        val src = try {
            image.toBitmap()
        } catch (t: Throwable) {
            return PoseSample.empty(up = up, fromGravity = fromGravity)
        }
        val rotation = image.imageInfo.rotationDegrees
        val upright = rotateInto(src, rotation)

        val ts = maxOf(timestampMs, lastTimestampMs + 1)
        lastTimestampMs = ts
        val started = System.nanoTime()
        val result: PoseLandmarkerResult? = try {
            lm.detectForVideo(BitmapImageBuilder(upright).build(), ts)
        } catch (t: Throwable) {
            Log.w(TAG, "detect 실패: ${t.message}")
            null
        }
        val inferMs = (System.nanoTime() - started) / 1_000_000
        recordStat(inferMs)
        val w = upright.width
        val h = upright.height
        if (upright !== src) src.recycle()

        val landmarks = result?.landmarks()?.firstOrNull()
        val world = result?.worldLandmarks()?.firstOrNull()
        if (landmarks == null || world == null || landmarks.size < MP_LANDMARK_COUNT) {
            return PoseSample.empty(inferMs, w, h, up, fromGravity)
        }

        val xy = FloatArray(MP_LANDMARK_COUNT * 2)
        val vis = FloatArray(MP_LANDMARK_COUNT)
        for (i in 0 until MP_LANDMARK_COUNT) {
            val p = landmarks[i]
            xy[i * 2] = p.x()
            xy[i * 2 + 1] = p.y()
            val v = p.visibility().orElse(1f)
            val pr = p.presence().orElse(1f)
            vis[i] = minOf(v, pr)
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
        val frame = PoseFrame(joints, up)
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
            up = up,
            upFromGravity = fromGravity,
        )
    }

    /** 회전이 필요하면 재사용 비트맵에 그려서 반환(무할당), 0 이면 원본 그대로. */
    private fun rotateInto(src: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return src
        val swap = rotation == 90 || rotation == 270
        val w = if (swap) src.height else src.width
        val h = if (swap) src.width else src.height
        var dst = uprightBitmap
        if (dst == null || dst.width != w || dst.height != h || dst.isRecycled) {
            dst?.recycle()
            dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            uprightBitmap = dst
        }
        rotateMatrix.reset()
        rotateMatrix.postTranslate(-src.width / 2f, -src.height / 2f)
        rotateMatrix.postRotate(rotation.toFloat())
        rotateMatrix.postTranslate(w / 2f, h / 2f)
        canvas.setBitmap(dst)
        canvas.drawBitmap(src, rotateMatrix, null)
        canvas.setBitmap(null)
        return dst
    }

    private fun recordStat(ms: Long) {
        inferCount += 1
        lastMs = ms
        emaMs = if (inferCount == 1L) ms.toFloat() else emaMs * 0.8f + ms * 0.2f
        if (inferCount % 25L == 0L) {
            Log.d(TAG, "infer #$inferCount model=${model.label} delegate=$delegateName ema=${"%.0f".format(emaMs)}ms last=${ms}ms")
        }
    }

    fun close() {
        try {
            landmarker?.close()
        } catch (_: Throwable) {
        }
        landmarker = null
        uprightBitmap?.recycle()
        uprightBitmap = null
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
