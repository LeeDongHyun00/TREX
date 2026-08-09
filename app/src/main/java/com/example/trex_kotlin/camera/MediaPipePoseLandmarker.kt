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
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the MediaPipe task and is intentionally confined to CameraX's single analysis thread.
 * VIDEO mode keeps frame ownership synchronous while CameraX drops stale frames upstream.
 */
internal class MediaPipePoseLandmarker(
    context: Context,
    private val verifiedProfile: VerifiedMediaPipePoseObserverProfile,
    private val onResult: (
        result: PoseLandmarkerResult,
        captureTimestampMs: Long,
        inputWidth: Int,
        inputHeight: Int,
        rotationDegrees: Int,
        isMirrored: Boolean,
        inferenceTimeMs: Long,
    ) -> Unit,
    private val onError: (PoseCameraError) -> Unit,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val config = verifiedProfile.config
    private val closed = AtomicBoolean(false)
    private var landmarker: PoseLandmarker? = null
    private val timestampGate = CameraCaptureTimestampGate()

    /** Creates the MediaPipe task and its source-bound observer as one analysis-thread operation. */
    fun initialize(): MediaPipePoseObserver? {
        if (closed.get()) return null
        var initialized: InitializedLandmarker? = null
        return try {
            initialized = createLandmarkerWithConfiguredDelegate()
            val observer = verifiedProfile.createObserver(initialized.resolvedDelegate)
            landmarker = initialized.landmarker
            observer
        } catch (error: Exception) {
            initializationFailed(initialized, error)
        } catch (error: LinkageError) {
            initializationFailed(initialized, error)
        }
    }

    fun analyze(imageProxy: ImageProxy, previewIsMirrored: Boolean) {
        val detector = landmarker
        if (closed.get() || detector == null) {
            imageProxy.close()
            return
        }

        val cameraFrame = try {
            val captureTimestampMs = timestampGate.accept(imageProxy.imageInfo.timestamp)
                ?: return
            val cropRect = Rect(imageProxy.cropRect)
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            require(rotationDegrees in setOf(0, 90, 180, 270)) {
                "CameraX rotation must be 0, 90, 180, or 270 degrees"
            }
            PreparedCameraFrame(
                sourceBitmap = imageProxy.toRgbaBitmap(),
                cropRect = cropRect,
                rotationDegrees = rotationDegrees,
                captureTimestampMs = captureTimestampMs,
            )
        } catch (error: Exception) {
            onError(PoseCameraError.FrameAnalysisFailed(error))
            return
        } finally {
            // Every ImageProxy access and copy is inside this boundary.
            imageProxy.close()
        }
        val sourceBitmap = cameraFrame.sourceBitmap
        var orientedBitmap: Bitmap? = null

        try {
            val transform = Matrix().apply {
                postRotate(cameraFrame.rotationDegrees.toFloat())
            }
            orientedBitmap = Bitmap.createBitmap(
                sourceBitmap,
                cameraFrame.cropRect.left,
                cameraFrame.cropRect.top,
                cameraFrame.cropRect.width(),
                cameraFrame.cropRect.height(),
                transform,
                true,
            )
            if (orientedBitmap !== sourceBitmap) sourceBitmap.recycle()

            val startedAtMs = SystemClock.uptimeMillis()
            BitmapImageBuilder(orientedBitmap).build().use { image ->
                val result = detector.detectForVideo(image, cameraFrame.captureTimestampMs)
                check(result.timestampMs() == cameraFrame.captureTimestampMs) {
                    "MediaPipe returned a different timestamp than the capture frame"
                }
                if (!closed.get()) {
                    onResult(
                        result,
                        cameraFrame.captureTimestampMs,
                        image.width,
                        image.height,
                        0,
                        previewIsMirrored,
                        (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L),
                    )
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) onError(PoseCameraError.FrameAnalysisFailed(error))
        } catch (error: LinkageError) {
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

    private fun createLandmarkerWithConfiguredDelegate(): InitializedLandmarker {
        val delegates = when (config.delegate) {
            PoseCameraDelegate.Cpu -> listOf(Delegate.CPU to ResolvedPoseDelegate.CPU)
            PoseCameraDelegate.Gpu -> listOf(Delegate.GPU to ResolvedPoseDelegate.GPU)
            PoseCameraDelegate.GpuWithCpuFallback -> listOf(
                Delegate.GPU to ResolvedPoseDelegate.GPU,
                Delegate.CPU to ResolvedPoseDelegate.CPU,
            )
        }
        var firstFailure: Throwable? = null
        var lastFailure: Throwable? = null
        for ((delegate, resolvedDelegate) in delegates) {
            try {
                val baseOptions = BaseOptions.builder()
                    .setDelegate(delegate)
                    .setModelAssetBuffer(verifiedProfile.modelBufferForBackend())
                    .build()
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(config.numberOfPoses)
                    .setMinPoseDetectionConfidence(config.minPoseDetectionConfidence)
                    .setMinPosePresenceConfidence(config.minPosePresenceConfidence)
                    .setMinTrackingConfidence(config.minTrackingConfidence)
                    .build()
                return InitializedLandmarker(
                    landmarker = PoseLandmarker.createFromOptions(applicationContext, options),
                    resolvedDelegate = resolvedDelegate,
                )
            } catch (error: Exception) {
                if (firstFailure == null) firstFailure = error
                lastFailure = error
            }
        }
        val failure = checkNotNull(lastFailure)
        firstFailure?.takeIf { it !== failure }?.let(failure::addSuppressed)
        throw failure
    }

    private fun ImageProxy.toRgbaBitmap(): Bitmap {
        check(planes.isNotEmpty()) { "CameraX returned an RGBA frame without a pixel plane." }
        val plane = planes[0]
        check(plane.pixelStride == RGBA_BYTES_PER_PIXEL) {
            "CameraX RGBA frames must use a four-byte pixel stride"
        }
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            val compactPixels = copyVisibleRgbaRows(
                sourceBuffer = plane.buffer,
                width = width,
                height = height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
            )
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(compactPixels))
            bitmap
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        }
    }

    private data class InitializedLandmarker(
        val landmarker: PoseLandmarker,
        val resolvedDelegate: ResolvedPoseDelegate,
    )

    private data class PreparedCameraFrame(
        val sourceBitmap: Bitmap,
        val cropRect: Rect,
        val rotationDegrees: Int,
        val captureTimestampMs: Long,
    )

    private fun initializationFailed(
        initialized: InitializedLandmarker?,
        error: Throwable,
    ): MediaPipePoseObserver? {
        try {
            initialized?.landmarker?.close()
        } catch (closeError: Exception) {
            error.addSuppressed(closeError)
        }
        onError(PoseCameraError.LandmarkerInitializationFailed(error))
        return null
    }

}

internal class CameraCaptureTimestampGate {
    private var lastCaptureTimestampNs = Long.MIN_VALUE
    private var lastMediaPipeTimestampMs = Long.MIN_VALUE

    /**
     * Returns a strictly increasing MediaPipe timestamp or `null` when distinct sensor times
     * collapse into the same millisecond. Sensor duplicates and regressions are fatal because they
     * may indicate a rebound camera session and must not reuse the current person epoch.
     */
    fun accept(captureTimestampNs: Long): Long? {
        require(captureTimestampNs >= 0L) { "Camera capture timestamp must be non-negative" }
        require(
            lastCaptureTimestampNs == Long.MIN_VALUE ||
                captureTimestampNs > lastCaptureTimestampNs,
        ) { "Camera capture timestamps must be strictly increasing" }
        lastCaptureTimestampNs = captureTimestampNs

        val mediaPipeTimestampMs = TimeUnit.NANOSECONDS.toMillis(captureTimestampNs)
        if (mediaPipeTimestampMs <= lastMediaPipeTimestampMs) return null
        lastMediaPipeTimestampMs = mediaPipeTimestampMs
        return mediaPipeTimestampMs
    }
}

internal fun copyVisibleRgbaRows(
    sourceBuffer: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
): ByteArray {
    require(width > 0 && height > 0) { "RGBA dimensions must be positive" }
    require(pixelStride == RGBA_BYTES_PER_PIXEL) {
        "CameraX RGBA frames must use a four-byte pixel stride"
    }
    val visibleRowBytes = Math.multiplyExact(width, RGBA_BYTES_PER_PIXEL)
    require(rowStride >= visibleRowBytes) {
        "CameraX RGBA row stride is shorter than the visible image width"
    }
    val compactPixels = ByteArray(Math.multiplyExact(visibleRowBytes, height))
    val source = sourceBuffer.duplicate()
    val sourceStart = source.position()
    for (row in 0 until height) {
        val sourceOffset = Math.addExact(sourceStart, Math.multiplyExact(row, rowStride))
        val sourceEnd = Math.addExact(sourceOffset, visibleRowBytes)
        require(sourceOffset >= sourceStart && sourceEnd <= source.limit()) {
            "CameraX RGBA plane does not contain every visible row"
        }
        source.position(sourceOffset)
        source.get(compactPixels, row * visibleRowBytes, visibleRowBytes)
    }
    return compactPixels
}

private const val RGBA_BYTES_PER_PIXEL = 4
