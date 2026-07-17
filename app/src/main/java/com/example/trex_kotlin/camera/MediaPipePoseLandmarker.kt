package com.example.trex_kotlin.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the MediaPipe task and is intentionally confined to CameraX's single analysis thread.
 * VIDEO mode keeps frame ownership synchronous while CameraX drops stale frames upstream.
 */
internal class MediaPipePoseLandmarker(
    context: Context,
    private val config: PoseCameraConfig,
    private val onResult: (
        result: PoseLandmarkerResult,
        inputWidth: Int,
        inputHeight: Int,
        rotationDegrees: Int,
        isMirrored: Boolean,
        inferenceTimeMs: Long,
    ) -> Unit,
    private val onError: (PoseCameraError) -> Unit,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private var landmarker: PoseLandmarker? = null
    private var lastTimestampMs = Long.MIN_VALUE

    fun initialize(): Boolean {
        if (closed.get()) return false
        if (!modelAssetExists(config.modelAssetName)) {
            onError(PoseCameraError.MissingModelAsset(config.modelAssetName))
            return false
        }

        return try {
            landmarker = createLandmarkerWithConfiguredDelegate()
            true
        } catch (error: Throwable) {
            onError(PoseCameraError.LandmarkerInitializationFailed(error))
            false
        }
    }

    fun analyze(imageProxy: ImageProxy, previewIsMirrored: Boolean) {
        val detector = landmarker
        if (closed.get() || detector == null) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val cropRect = Rect(imageProxy.cropRect)
        var orientedBitmap: Bitmap? = null

        val sourceBitmap = try {
            imageProxy.toRgbaBitmap()
        } catch (error: Throwable) {
            onError(PoseCameraError.FrameAnalysisFailed(error))
            return
        } finally {
            // The RGBA pixels have been copied, so the CameraX frame is no longer retained.
            imageProxy.close()
        }

        try {
            val transform = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
            }
            orientedBitmap = Bitmap.createBitmap(
                sourceBitmap,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height(),
                transform,
                true,
            )
            if (orientedBitmap !== sourceBitmap) sourceBitmap.recycle()

            val timestampMs = nextTimestampMs()
            val startedAtMs = SystemClock.uptimeMillis()
            BitmapImageBuilder(orientedBitmap).build().use { image ->
                val result = detector.detectForVideo(image, timestampMs)
                if (!closed.get()) {
                    onResult(
                        result,
                        image.width,
                        image.height,
                        0,
                        previewIsMirrored,
                        (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L),
                    )
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) onError(PoseCameraError.FrameAnalysisFailed(error))
        } finally {
            orientedBitmap?.let { if (!it.isRecycled) it.recycle() }
            if (!sourceBitmap.isRecycled) sourceBitmap.recycle()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        landmarker?.close()
        landmarker = null
    }

    private fun createLandmarkerWithConfiguredDelegate(): PoseLandmarker {
        val delegates = when (config.delegate) {
            PoseCameraDelegate.Cpu -> listOf(Delegate.CPU)
            PoseCameraDelegate.Gpu -> listOf(Delegate.GPU)
            PoseCameraDelegate.GpuWithCpuFallback -> listOf(Delegate.GPU, Delegate.CPU)
        }
        var firstFailure: Throwable? = null
        var lastFailure: Throwable? = null
        for (delegate in delegates) {
            try {
                val baseOptions = BaseOptions.builder()
                    .setDelegate(delegate)
                    .setModelAssetPath(config.modelAssetName)
                    .build()
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(config.numberOfPoses)
                    .setMinPoseDetectionConfidence(config.minPoseDetectionConfidence)
                    .setMinPosePresenceConfidence(config.minPosePresenceConfidence)
                    .setMinTrackingConfidence(config.minTrackingConfidence)
                    .build()
                return PoseLandmarker.createFromOptions(applicationContext, options)
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
                lastFailure = error
            }
        }
        val failure = checkNotNull(lastFailure)
        firstFailure?.takeIf { it !== failure }?.let(failure::addSuppressed)
        throw failure
    }

    private fun nextTimestampMs(): Long {
        val now = SystemClock.uptimeMillis()
        val next = if (now > lastTimestampMs) now else lastTimestampMs + 1L
        lastTimestampMs = next
        return next
    }

    private fun modelAssetExists(assetName: String): Boolean = try {
        applicationContext.assets.open(assetName).use { }
        true
    } catch (_: FileNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    private fun ImageProxy.toRgbaBitmap(): Bitmap {
        check(planes.isNotEmpty()) { "CameraX returned an RGBA frame without a pixel plane." }
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            val pixelBuffer = planes[0].buffer
            pixelBuffer.rewind()
            bitmap.copyPixelsFromBuffer(pixelBuffer)
            bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }
}
