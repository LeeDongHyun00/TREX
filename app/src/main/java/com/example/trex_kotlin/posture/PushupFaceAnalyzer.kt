package com.example.trex_kotlin.posture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

/** 푸시업 전용 얼굴 방향. 생성·추론·해제는 PostureAnalyzer의 같은 분석 락 안에서 호출한다. */
internal class PushupFaceAnalyzer(private val context: Context, private val preferGpu: Boolean) {
    private var landmarker: FaceLandmarker? = null
    private var initFailed = false
    private var closed = false
    private var bitmap: Bitmap? = null
    private val canvas = Canvas()
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun ensureReady(): Boolean {
        if (closed || initFailed) return false
        if (landmarker != null) return true
        val delegates = if (preferGpu) listOf(Delegate.GPU, Delegate.CPU) else listOf(Delegate.CPU)
        for (delegate in delegates) {
            try {
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("posture/face_landmarker.task").setDelegate(delegate).build())
                    // 머리 영역의 회전·크기가 프레임마다 달라지므로 VIDEO 추적을 쓰지 않는다.
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(.3f)
                    .setMinFacePresenceConfidence(.3f)
                    .setMinTrackingConfidence(.5f)
                    .setOutputFaceBlendshapes(false)
                    .setOutputFacialTransformationMatrixes(true)
                    .build()
                landmarker = FaceLandmarker.createFromOptions(context, options)
                return true
            } catch (error: Throwable) {
                Log.w("PushupFaceAnalyzer", "얼굴 모델 $delegate 준비 실패: ${error.message}")
            }
        }
        initFailed = true
        return false
    }

    /** 매 Pose 추론에서 새로 측정한다. 미검출·오류·가림이면 얼굴 피처를 남기지 않는다. */
    fun features(src: Bitmap, xy: FloatArray, vis: FloatArray): Map<String, Float> {
        if (closed) return emptyMap()
        val crop = PushupFaceGeometry.crop(xy, vis, src.width, src.height) ?: return emptyMap()
        if (!ensureReady()) return emptyMap()
        val detector = landmarker ?: return emptyMap()
        return try {
            var dst = bitmap
            if (dst == null || dst.isRecycled) {
                dst = Bitmap.createBitmap(PushupFaceGeometry.CROP_PX, PushupFaceGeometry.CROP_PX, Bitmap.Config.ARGB_8888)
                bitmap = dst
            }
            matrix.reset()
            matrix.postTranslate(-crop.centerX, -crop.centerY)
            matrix.postRotate(crop.rotationDegrees)
            matrix.postScale(crop.scale, crop.scale)
            matrix.postTranslate(PushupFaceGeometry.CROP_PX / 2f, PushupFaceGeometry.CROP_PX / 2f)
            canvas.setBitmap(dst)
            try {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(src, matrix, paint)
            } finally {
                canvas.setBitmap(null)
            }
            val image = BitmapImageBuilder(dst).build()
            val result = try { detector.detect(image) } finally { image.close() }
            PushupFaceGeometry.observedFeatures(result.faceLandmarks().isNotEmpty(),
                result.facialTransformationMatrixes().orElse(null)?.firstOrNull())
        } catch (error: Throwable) {
            Log.w("PushupFaceAnalyzer", "얼굴 방향 측정 실패: ${error.message}")
            emptyMap()
        }
    }

    /** 호출자의 분석 락 안에서 닫는다. 진행 중인 추론과 동시에 해제하지 않는다. */
    fun close() {
        closed = true
        try { landmarker?.close() } catch (_: Throwable) { }
        landmarker = null
        canvas.setBitmap(null)
        bitmap?.recycle()
        bitmap = null
    }
}
