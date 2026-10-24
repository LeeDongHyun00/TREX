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

private const val TAG = "PostureAnalyzer"
/** 얼굴 메시 모델(§101d) — float16, 3.8 MB. `noCompress += "task"`. */
private const val FACE_MODEL_ASSET = "posture/face_landmarker.task"

// 추론 이후 처리(PoseSample·피처·얼굴 크롭 기하·얼굴 결과 시간)는 PoseLandmarks.kt — iPhone 카메라와 같은 함수다(§108c).

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
    /** 얼굴 추론 간격·마지막 결과(§101d, 공유 [FaceTrack]). */
    private val faceTrack = FaceTrack()
    @Volatile private var faceInferCount = 0L
    @Volatile private var faceEmaMs = 0f
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
        if (faceTrack.due(ts) && ensureFaceReady()) {
            val fl = faceLandmarker ?: return
            val geometry = FaceCrop.of(xy, vis, upright.width, upright.height)
            val crop = geometry?.let { faceCrop(upright, it) }
            if (geometry != null && crop != null) {
                val started = System.nanoTime()
                val r: FaceLandmarkerResult? = try {
                    fl.detect(BitmapImageBuilder(crop).build())
                } catch (t: Throwable) {
                    Log.w(TAG, "face detect 실패: ${t.message}")
                    null
                }
                val ms = (System.nanoTime() - started) / 1_000_000
                faceInferCount += 1
                faceEmaMs = if (faceInferCount == 1L) ms.toFloat() else faceEmaMs * 0.8f + ms * 0.2f
                if (faceInferCount % 50L == 0L) Log.d(TAG, "face infer #$faceInferCount ema=${"%.0f".format(faceEmaMs)}ms last=${ms}ms head=${"%.0f".format(geometry.headPx)}px rot=${"%.0f".format(geometry.rotationDeg)}")
                val found = r != null && r.faceLandmarks().isNotEmpty()
                val yaw = if (found) r!!.facialTransformationMatrixes().orElse(null)?.firstOrNull()?.let { faceYawDeg(it) } ?: Float.NaN else Float.NaN
                faceTrack.record(ts, found, yaw, ms, geometry)
            } else {
                // 머리가 안 보이면 이번 칸은 건너뛴다 — 마지막 결과는 [FACE_STALE_MS] 뒤 사라진다(모름)
                faceTrack.skip(ts)
            }
        }
        faceTrack.apply(ts, out)
    }

    /** 머리 영역 크롭([FACE_CROP_PX] 정사각, 전용 버퍼) — 기하는 공유 [FaceCrop], 여기서는 같은 순서로 그리기만 한다. */
    private fun faceCrop(src: Bitmap, g: FaceCrop): Bitmap {
        var dst = faceBitmap
        if (dst == null || dst.isRecycled) { dst = Bitmap.createBitmap(FACE_CROP_PX, FACE_CROP_PX, Bitmap.Config.ARGB_8888); faceBitmap = dst }
        faceMatrix.reset()
        faceMatrix.postTranslate(-g.cx, -g.cy)
        faceMatrix.postRotate(g.rotationDeg)
        faceMatrix.postScale(g.scale, g.scale)
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

        // 추론 이후 처리는 공유 함수(PoseLandmarks.kt) — iPhone 카메라와 같은 피처(§108c)
        return PoseLandmarks.sample(xy, vis, rawWorld, w, h, inferMs, up,
            // §101d: 얼굴 메시 피처(바닥 세 종목의 시선) — 판정 격자마다 한 번, 같은 비트맵
            addFace = if (faceEnabled) { out -> faceFeatures(upright, xy, vis, ts, out) } else null,
            onFlip = { sanity ->
                flippedCount += 1
                if (flippedCount == 1L || flippedCount % 50L == 0L) {
                    Log.w(TAG, "up 반전 감지·보정 (#$flippedCount): hip-ankle=${sanity.hipAboveAnkleCm} ear-shoulder=${sanity.earAboveShoulderCm}")
                }
            })
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
        faceTrack.clear()
        faceBitmap?.recycle()
        faceBitmap = null
        uprightBitmap?.recycle()
        uprightBitmap = null
    }
}
