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
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
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
/** 얼굴 메시 모델(§101d) — float16, 3.8 MB. `noCompress += "task"`. */
private const val FACE_MODEL_ASSET = "posture/face_landmarker.task"
/** 얼굴 추론 간격 — 판정 격자(§96, 300 ms)와 같다. */
private const val FACE_INTERVAL_MS = 300L
/** 이 시간 안의 샘플에는 마지막 얼굴 결과를 얹는다(판정 칸의 첫 프레임이 얼굴을 돌린 프레임과 85 ms 어긋날 수 있다). */
private const val FACE_STALE_MS = 450L
/** 머리 크롭의 출력 한 변(px) — 검출기 입력 128 에서 얼굴이 40 px 넘게. */
private const val FACE_CROP_PX = 256
/** 크롭 한 변 = 머리 높이 × 이 배수(머리가 크롭의 1/3). */
private const val FACE_CROP_SCALE = 3.0f
/** 얼굴 검출·존재 신뢰도 하한 — 옆얼굴은 점수가 낮다(기본 0.5 → 0.3). */
private const val FACE_MIN_CONFIDENCE = 0.3f

/**
 * 얼굴 자세 행렬(4×4, MediaPipe facial transformation matrix) → 얼굴 앞 방향과 화면 평면이 이루는 각(°, §101d `FloorGaze.YAW`).
 * 정준 얼굴 모델의 +z 가 얼굴 앞(보는 쪽)이고 카메라 공간은 카메라가 −z 를 바라보므로, 카메라를 보는 얼굴의 앞 방향은 +z(카메라 쪽)다.
 * 요 = asin(앞 방향의 z 성분): **0 = 옆얼굴(올바른 시선), +90 = 카메라를 봄, −90 = 뒤통수**. 행렬 배치(열 우선/행 우선)는 마지막 행·열의 0 으로 가른다 —
 * 열 우선이면 m[3]·m[7]·m[11] 이 0(마지막 행), 행 우선이면 m[12..14] 가 평행이동. 폰 1차 확인 전 부호는 가정이다([FACE_YAW_SIGN] 으로 뒤집는다).
 */
internal fun faceYawDeg(m: FloatArray): Float {
    if (m.size < 16) return Float.NaN
    val colMajor = kotlin.math.abs(m[3]) < 1e-4f && kotlin.math.abs(m[7]) < 1e-4f && kotlin.math.abs(m[11]) < 1e-4f
    val zx = if (colMajor) m[8] else m[2]
    val zy = if (colMajor) m[9] else m[6]
    val zz = m[10]
    val n = kotlin.math.sqrt(zx * zx + zy * zy + zz * zz)
    if (!(n > 1e-6f) || !n.isFinite()) return Float.NaN
    return FACE_YAW_SIGN * Math.toDegrees(kotlin.math.asin((zz / n).coerceIn(-1f, 1f).toDouble())).toFloat()
}
private const val FACE_YAW_SIGN = 1f

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
    /** 관절 배치 자가검증(checkUpSanity)으로 up 을 뒤집어 보정했는지. */
    val upFlipped: Boolean = false,
    /** 자가검증으로 up 방향을 확인할 수 있었는지 (false = 누운 자세/관절 부족 등으로 미검증). */
    val upVerified: Boolean = false,
    /**
     * MediaPipe 월드 랜드마크 원값(m, MediaPipe 부호 그대로 — y 아래·z 카메라 쪽 음수), 33×3 = x0,y0,z0,…. 검출 프레임만, 아니면 null.
     * 피처 계산에는 쓰지 않는다(피처는 가시성 거른 cm·부호 반전 좌표). 검증 모드 세트 로그가 이 값을 남겨 오프라인에서 같은 후처리를
     * 다시 돌리고(재생 파리티) 좌우 판별·화면 잘림 같은 새 분석을 폰 데이터로 할 수 있게 한다 (spec §61).
     */
    val world: FloatArray? = null,
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

/** 바닥 종목용: 같은 샘플에 features 만 바닥 2D 피처로 바꾼 사본 (세트 로그·집계에 그대로 흘림). PostureFloor.kt 에서 옮겼다 — 그 파일은 재생기가 컴파일한다(spec §99). */
fun PoseSample.withFeatures(newFeatures: Map<String, Float>): PoseSample = PoseSample(
    detected = detected,
    normalizedXy = normalizedXy,
    visibility = visibility,
    features = newFeatures,
    visibleJointCount = visibleJointCount,
    inferMs = inferMs,
    imageWidth = imageWidth,
    imageHeight = imageHeight,
    up = up,
    upFromGravity = upFromGravity,
    upFlipped = upFlipped,
    upVerified = upVerified,
    world = world,
)

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
    /**
     * 얼굴 메시 모델(spec §101d, 바닥 세 종목의 시선 — `FloorGaze`). [faceEnabled] 일 때 [FACE_INTERVAL_MS] 마다 한 번(판정 격자 300 ms 와 같다 — 85 ms 추론 루프마다 돌리지 않는다)
     * 같은 업라이트 비트맵으로 추론하고, 결과(검출·요)를 [FACE_STALE_MS] 안의 샘플 피처에 얹는다. 생성·추론·닫기는 Pose 와 같은 락(this) 안이다(§63 SIGSEGV 함정).
     */
    private var faceLandmarker: FaceLandmarker? = null
    private var faceInitError: String? = null
    @Volatile var faceEnabled: Boolean = false
    private var lastFaceMs = Long.MIN_VALUE / 2
    /** 마지막 얼굴 결과 — [검출 1/0, 요 °(NaN = 없음), 추론 ms, 시각]. */
    private var lastFace: FloatArray? = null
    @Volatile private var faceInferCount = 0L
    @Volatile private var faceEmaMs = 0f
    private var lastFaceRotation = 0f
    private var lastHeadPx = 0f
    private var faceBitmap: Bitmap? = null
    private val faceMatrix = Matrix()
    private val faceCanvas = Canvas()
    private val facePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    /**
     * 닫힌 뒤에는 모델을 다시 만들지 않고 빈 샘플만 돌려준다. 추론(analyze·analyzeBitmap)과 [close] 는 같은 락(this)을 잡는다 —
     * 화면이 사라질 때 메인 스레드의 close 가 분석 스레드에서 도는 detectForVideo 의 네이티브 그래프를 해제해
     * SIGSEGV(PacketCreator.nativeCreateProto, 폰 2026-09-25 20:06·09-26 13:57 — 자동 진행 직후)로 앱이 죽고 그 세트 로그가 사라졌다.
     */
    @Volatile private var closed = false
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
    @Volatile private var flippedCount = 0L

    /** up 자가검증으로 보정한 프레임 수 (진단용). */
    val upFlippedCount: Long get() = flippedCount

    val isReady: Boolean get() = landmarker != null

    fun stats() = AnalyzerStats(delegateName, model.label, landmarker != null, inferCount, emaMs, lastMs, initError)

    /** 분석 스레드에서 호출. GPU → CPU 순으로 시도. */
    @Synchronized
    fun ensureReady(): Boolean {
        if (closed) return false
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

    /** 얼굴 모델 준비(락 안에서) — Pose 와 같은 순서(GPU → CPU). 실패하면 이 세션에서는 다시 시도하지 않고 얼굴 피처 없이 간다(시선은 '측정 중'). */
    private fun ensureFaceReady(): Boolean {
        if (closed) return false
        if (faceLandmarker != null) return true
        if (faceInitError != null) return false
        val order = if (preferGpu) listOf(Delegate.GPU, Delegate.CPU) else listOf(Delegate.CPU)
        for (d in order) {
            try {
                val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(FACE_MODEL_ASSET).setDelegate(d).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(FACE_MIN_CONFIDENCE)
                    .setMinFacePresenceConfidence(FACE_MIN_CONFIDENCE)
                    .setMinTrackingConfidence(0.5f)
                    .setOutputFaceBlendshapes(false)
                    .setOutputFacialTransformationMatrixes(true)
                    .build()
                faceLandmarker = FaceLandmarker.createFromOptions(context, opts)
                Log.i(TAG, "FaceLandmarker ready: delegate=${if (d == Delegate.GPU) "GPU" else "CPU"}")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "face delegate $d 생성 실패: ${t.message}")
                if (d == order.last()) faceInitError = "얼굴 모델 로드 실패: ${t.message}"
            }
        }
        return false
    }

    /**
     * 얼굴 피처(§101d) — [FACE_INTERVAL_MS] 마다 추론하고 그 결과를 [FACE_STALE_MS] 안의 샘플에 얹는다. 키는 [FloorGaze.FOUND]·[FloorGaze.YAW]·[FloorGaze.INFER_MS]·[FloorGaze.ROTATION]·[FloorGaze.SIZE].
     * 얼굴이 없으면 요 키는 없다. 모델이 없으면(실패·미지원) 키를 넣지 않는다 — 시선은 '측정 중'.
     *
     * **머리 영역만 잘라 넣는다**(10-09 22:32 크런치): 분석 영상이 640×480 이고 폰이 1.6 m 거리라 얼굴이 60 px(검출기 입력 128 에서 16 px)여서 전체 영상으로는 누운 동안 검출 0/130 이었다
     * (일어나 앉아 얼굴이 140 px 이 되자 검출). Pose 의 코·귀로 머리 중심과 크기를 잡고, 어깨 중점 → 귀 중점(목 방향)이 위를 향하게 돌린 뒤 [FACE_CROP_PX] 로 확대한 정사각형을
     * 얼굴 모델(IMAGE 모드 — 잘라낸 영역이 프레임마다 달라 VIDEO 추적을 쓰지 않는다)에 준다. 요(얼굴 앞 방향의 카메라 축 성분)는 영상을 카메라 축 둘레로 돌려도 변하지 않고,
     * 잘라낸 영역의 가상 카메라가 '렌즈를 향함' 을 재므로 사용자 정의(화면을 본다)와 맞는다.
     */
    private fun faceFeatures(upright: Bitmap, xy: FloatArray, vis: FloatArray, ts: Long, out: HashMap<String, Float>) {
        if (!faceEnabled) return
        if (ts - lastFaceMs >= FACE_INTERVAL_MS && ensureFaceReady()) {
            val fl = faceLandmarker ?: return
            val crop = faceCrop(upright, xy, vis)
            if (crop != null) {
                val started = System.nanoTime()
                val r: FaceLandmarkerResult? = try {
                    fl.detect(BitmapImageBuilder(crop).build())
                } catch (t: Throwable) {
                    Log.w(TAG, "face detect 실패: ${t.message}")
                    null
                }
                val ms = (System.nanoTime() - started) / 1_000_000
                lastFaceMs = ts
                faceInferCount += 1
                faceEmaMs = if (faceInferCount == 1L) ms.toFloat() else faceEmaMs * 0.8f + ms * 0.2f
                if (faceInferCount % 50L == 0L) Log.d(TAG, "face infer #$faceInferCount ema=${"%.0f".format(faceEmaMs)}ms last=${ms}ms head=${"%.0f".format(lastHeadPx)}px rot=${"%.0f".format(lastFaceRotation)}")
                val found = r != null && r.faceLandmarks().isNotEmpty()
                val yaw = if (found) r!!.facialTransformationMatrixes().orElse(null)?.firstOrNull()?.let { faceYawDeg(it) } ?: Float.NaN else Float.NaN
                lastFace = floatArrayOf(if (found) 1f else 0f, yaw, ms.toFloat(), ts.toFloat(), lastFaceRotation, lastHeadPx)
            } else {
                // 머리가 안 보이면 이번 칸은 건너뛴다 — 마지막 결과는 [FACE_STALE_MS] 뒤 사라진다(모름)
                lastFaceMs = ts
            }
        }
        val f = lastFace ?: return
        if (ts - f[3] > FACE_STALE_MS) return
        out[FloorGaze.FOUND] = f[0]
        if (f[1].isFinite()) out[FloorGaze.YAW] = f[1]
        out[FloorGaze.INFER_MS] = f[2]
        out[FloorGaze.ROTATION] = f[4]
        out[FloorGaze.SIZE] = f[5]
    }

    /**
     * 머리 영역 크롭([FACE_CROP_PX] 정사각, 전용 버퍼). 중심 = 코·귀 중 보이는 점의 평균, 크기 = max(코–귀 거리 × 2.2, 어깨 중점 → 귀 중점 길이, 40 px) ≈ 머리 높이,
     * 한 변 = 크기 × [FACE_CROP_SCALE]. 회전 = 어깨 중점 → 귀 중점(목 방향)이 위를 향하게 — 누워 턱을 당긴 크런치(머리가 몸통 축과 70° 꺾임)에서 몸통 축(골반→어깨)으로 돌리면 얼굴이 옆으로 눕는다.
     * 코·귀가 둘 다 없거나 어깨가 없으면 null.
     */
    private fun faceCrop(src: Bitmap, xy: FloatArray, vis: FloatArray): Bitmap? {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        fun ok(i: Int) = vis.getOrNull(i)?.let { it >= MIN_VISIBILITY } == true && xy[i * 2].isFinite() && xy[i * 2 + 1].isFinite()
        fun px(i: Int) = xy[i * 2] * w
        fun py(i: Int) = xy[i * 2 + 1] * h
        val head = listOf(0, 7, 8).filter { ok(it) }
        if (head.size < 2 || !(ok(11) || ok(12))) return null
        val cx = head.map { px(it) }.average().toFloat(); val cy = head.map { py(it) }.average().toFloat()
        val ears = listOf(7, 8).filter { ok(it) }
        val noseEar = if (ok(0) && ears.isNotEmpty()) ears.maxOf { kotlin.math.hypot(px(0) - px(it), py(0) - py(it)) } else 0f
        val shoulders = listOf(11, 12).filter { ok(it) }
        val sx = shoulders.map { px(it) }.average().toFloat(); val sy = shoulders.map { py(it) }.average().toFloat()
        val ex = if (ears.isNotEmpty()) ears.map { px(it) }.average().toFloat() else cx
        val ey = if (ears.isNotEmpty()) ears.map { py(it) }.average().toFloat() else cy
        val neck = kotlin.math.hypot(ex - sx, ey - sy)
        val headPx = maxOf(noseEar * 2.2f, neck, 40f)
        lastHeadPx = headPx
        // 회전: 목 방향(어깨 중점 → 귀 중점)을 위(−y)로. Android Matrix 의 양의 각은 화면에서 시계 방향 — 벡터가 위에서 시계 방향으로 φ 만큼 돌아 있으면 −φ 돌린다
        val vx = ex - sx; val vy = ey - sy
        val phi = if (kotlin.math.hypot(vx, vy) > 1f) Math.toDegrees(kotlin.math.atan2(vx.toDouble(), (-vy).toDouble())).toFloat() else 0f
        lastFaceRotation = -phi
        val side = headPx * FACE_CROP_SCALE
        val scale = FACE_CROP_PX / side
        var dst = faceBitmap
        if (dst == null || dst.isRecycled) { dst = Bitmap.createBitmap(FACE_CROP_PX, FACE_CROP_PX, Bitmap.Config.ARGB_8888); faceBitmap = dst }
        faceMatrix.reset()
        faceMatrix.postTranslate(-cx, -cy)
        faceMatrix.postRotate(-phi)
        faceMatrix.postScale(scale, scale)
        faceMatrix.postTranslate(FACE_CROP_PX / 2f, FACE_CROP_PX / 2f)
        faceCanvas.setBitmap(dst)
        faceCanvas.drawColor(android.graphics.Color.BLACK)
        faceCanvas.drawBitmap(src, faceMatrix, facePaint)
        faceCanvas.setBitmap(null)
        return dst
    }


    /**
     * ImageProxy 를 소비하지 않는다 — 호출 측에서 close() 할 것.
     * @param up 중력 반대 방향(world 좌표계 단위벡터). IMU 를 못 쓰면 [SCREEN_UP].
     */
    fun analyze(image: ImageProxy, timestampMs: Long, up: Vec3 = SCREEN_UP): PoseSample = synchronized(this) { analyzeImageLocked(image, timestampMs, up) }

    private fun analyzeImageLocked(image: ImageProxy, timestampMs: Long, up: Vec3): PoseSample {
        val fromGravity = up !== SCREEN_UP
        if (!ensureReady()) return PoseSample.empty(up = up, fromGravity = fromGravity)
        // YUV → Bitmap 변환은 실제 추론 프레임에서만 (스킵된 프레임은 비용 0)
        val src = try {
            image.toBitmap()
        } catch (t: Throwable) {
            return PoseSample.empty(up = up, fromGravity = fromGravity)
        }
        val rotation = image.imageInfo.rotationDegrees
        val upright = rotateInto(src, rotation)

        return try {
            analyzeLocked(upright, timestampMs, up)
        } finally {
            if (upright !== src) src.recycle()
        }
    }

    /** 회전 보정된 이미지 재생 입력. 카메라와 동일한 VIDEO 추론·관절 변환 경로를 사용한다.
     * 비트맵 소유권은 호출자에게 있다. 저장 이미지에는 IMU가 없으므로 기본값은 SCREEN_UP이다. */
    fun analyzeBitmap(upright: Bitmap, timestampMs: Long, up: Vec3 = SCREEN_UP): PoseSample = synchronized(this) { analyzeLocked(upright, timestampMs, up) }

    private fun analyzeLocked(upright: Bitmap, timestampMs: Long, up: Vec3): PoseSample {
        val fromGravity = up !== SCREEN_UP
        if (!ensureReady()) return PoseSample.empty(up = up, fromGravity = fromGravity)
        val lm = landmarker ?: return PoseSample.empty(up = up, fromGravity = fromGravity)

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
        val landmarks = result?.landmarks()?.firstOrNull()
        val world = result?.worldLandmarks()?.firstOrNull()
        if (landmarks == null || world == null || landmarks.size < MP_LANDMARK_COUNT) {
            return PoseSample.empty(inferMs, w, h, up, fromGravity)
        }

        val xy = FloatArray(MP_LANDMARK_COUNT * 2)
        val vis = FloatArray(MP_LANDMARK_COUNT)
        val rawWorld = FloatArray(MP_LANDMARK_COUNT * 3)
        for (i in 0 until MP_LANDMARK_COUNT) {
            val p = landmarks[i]
            xy[i * 2] = p.x()
            xy[i * 2 + 1] = p.y()
            val v = p.visibility().orElse(1f)
            val pr = p.presence().orElse(1f)
            vis[i] = minOf(v, pr)
            val wp = world[i]
            rawWorld[i * 3] = wp.x(); rawWorld[i * 3 + 1] = wp.y(); rawWorld[i * 3 + 2] = wp.z()
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
        // up 자가검증: IMU 부호/회전 매핑이 틀려 up 이 뒤집혔으면(골반이 발목 아래로 계산됨) −up 으로 보정 (spec §4)
        val sanity = checkUpSanity(joints, up)
        val upUsed = if (sanity.flipped) (up * -1f).unit() ?: up else up
        if (sanity.flipped) {
            flippedCount += 1
            if (flippedCount == 1L || flippedCount % 50L == 0L) {
                Log.w(TAG, "up 반전 감지·보정 (#$flippedCount): hip-ankle=${sanity.hipAboveAnkleCm} ear-shoulder=${sanity.earAboveShoulderCm}")
            }
        }
        val frame = PoseFrame(joints, upUsed)
        // §33: 촬영 방향 피처(view_cos/view_sin)도 같은 프레임 피처로 — 집계·로그·규칙 게이팅이 추가 배선 없이 받는다
        // §62a 후속 3: 이미지 2D 발 너비 — 월드 발목 간격은 발끝 회전에 흔들린다(Stance2d 주석). 재생기 frameFeatures 도 같은 함수
        // §62c: 컬의 팔꿈치 앞 이탈·몸통 기울기는 이미지 2D + 촬영 방위 부호(Arm2d). 재생기 frameFeatures 도 같은 순서·같은 함수
        val viewF = ViewEstimator.frameFeatures(joints)
        val aspect = w.toFloat() / h
        // §63: 런지 걸음 기하(앞다리·깊이·무릎 쏠림·어깨 높이) — 어깨선 요로. 재생기 frameFeatures 도 같은 순서·같은 함수
        val features = frame.features() + viewF + Stance2d.features(xy, vis, MIN_VISIBILITY, aspect) +
            Arm2d.features(xy, vis, MIN_VISIBILITY, aspect, Arm2d.yawOf(viewF)) +
            Lunge2d.features(frame, xy, vis, MIN_VISIBILITY, aspect, ViewEstimator.shoulderYawOf(viewF), gravityUp = fromGravity) +
            // §96: 한 발 떠남 계열의 기하(허벅지각·비틀림·롤 보정 2D 발목/무릎/골반·손–귀·팔꿈치–무릎) — 재생기 frameFeatures 도 같은 순서·같은 함수
            LegGeometry.features(frame, xy, vis, MIN_VISIBILITY, aspect, LegGeometry.rollDeg(upUsed))
        // §101d: 얼굴 메시 피처(바닥 세 종목의 시선) — 판정 격자마다 한 번, 같은 비트맵. 재생기 .cap 에는 없다(로그 전용)
        val featuresOut = if (faceEnabled) HashMap(features).also { faceFeatures(upright, xy, vis, ts, it) } else features
        val visibleCount = vis.count { it >= MIN_VISIBILITY }
        return PoseSample(
            detected = true,
            normalizedXy = xy,
            visibility = vis,
            features = featuresOut,
            visibleJointCount = visibleCount,
            inferMs = inferMs,
            imageWidth = w,
            imageHeight = h,
            up = upUsed,
            upFromGravity = fromGravity,
            upFlipped = sanity.flipped,
            upVerified = sanity.verified,
            world = rawWorld,
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

    /**
     * 닫힘 표시만 — 락 없이 바로 돌아온다. 이 뒤로 들어오는 분석은 모델을 만들지 않고 돌아간다([ensureReady]).
     * 메인 스레드는 이것만 부르고 실제 [close] 는 분석 스레드에 맡긴다 — 첫 프레임의 모델 준비(GPU, 수 초)를 메인이 기다리지 않게.
     */
    fun markClosed() { closed = true }

    /** 진행 중인 추론이 끝날 때까지 기다린 뒤 닫는다(추론 한 번 ≈ 100 ms, 첫 프레임은 모델 준비까지). 여러 번 불러도 된다. 메인 스레드에서 부르지 않는다. */
    fun close() {
        closed = true      // 락 밖에서 먼저 — 락을 기다리는 분석은 들어오자마자 돌아간다
        synchronized(this) { closeLocked() }
    }

    private fun closeLocked() {
        try {
            landmarker?.close()
        } catch (_: Throwable) {
        }
        landmarker = null
        try {
            faceLandmarker?.close()
        } catch (_: Throwable) {
        }
        faceLandmarker = null
        lastFace = null
        faceBitmap?.recycle()
        faceBitmap = null
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
