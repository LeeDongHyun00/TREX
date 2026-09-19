package com.trex.engine.lab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.trex.engine.LandmarkFrame

data class CameraResult(val frame: LandmarkFrame?, val inferMs: Long, val delegate: String, val error: String? = null)

/** 생성·추론·해제는 모두 같은 분석 실행기에서 호출한다. 추론 입력을 좌우 반전하지 않는다. */
class PoseCamera(private val context: Context) : AutoCloseable {
    private var model: PoseLandmarker? = null
    private var name = "INITIALIZING"
    private var previousTime = -1L
    fun detect(image: ImageProxy, timeMs: Long): CameraResult {
        val source = image.toBitmap()
        val upright = if (image.imageInfo.rotationDegrees == 0) source else Bitmap.createBitmap(source, 0, 0, source.width, source.height,
            Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, true)
        return try { detectBitmap(upright, timeMs) } finally {
            if (upright !== source) upright.recycle()
            source.recycle()
        }
    }
    fun detectBitmap(bitmap: Bitmap, timeMs: Long): CameraResult {
        val started = System.nanoTime()
        try {
            if (model == null) {
                var last: Exception? = null
                for (delegate in listOf(Delegate.GPU, Delegate.CPU)) {
                    try {
                        model = PoseLandmarker.createFromOptions(context, PoseLandmarker.PoseLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").setDelegate(delegate).build())
                            .setRunningMode(RunningMode.VIDEO).setNumPoses(1).setMinPoseDetectionConfidence(.5f)
                            .setMinPosePresenceConfidence(.5f).setMinTrackingConfidence(.5f).build())
                        name = delegate.name; break
                    } catch (e: Exception) { last = e }
                }
                if (model == null) throw last ?: IllegalStateException("모델 초기화 실패")
            }
            val timestamp = maxOf(timeMs, previousTime+1); previousTime = timestamp
            val mp = BitmapImageBuilder(bitmap).build()
            val result = try { model!!.detectForVideo(mp, timestamp) } finally { mp.close() }
            val landmarks = result.landmarks().firstOrNull()
            val world = result.worldLandmarks().firstOrNull()
            val elapsed = (System.nanoTime()-started)/1_000_000
            if (landmarks?.size != 33 || world?.size != 33) return CameraResult(null, elapsed, name)
            val xy = FloatArray(66) { i -> if (i%2 == 0) landmarks[i/2].x() else landmarks[i/2].y() }
            val xyz = FloatArray(99) { i -> val p = world[i/3]; when(i%3) { 0 -> p.x(); 1 -> p.y(); else -> p.z() } }
            val visibility = FloatArray(33) { i -> minOf(landmarks[i].visibility().orElse(0f), landmarks[i].presence().orElse(0f)) }
            return CameraResult(LandmarkFrame(xy, xyz, visibility, bitmap.width, bitmap.height), elapsed, name)
        } catch (e: Exception) {
            return CameraResult(null, (System.nanoTime()-started)/1_000_000, name, e.javaClass.simpleName+": "+e.message)
        }
    }
    override fun close() { model?.close(); model = null }
}
