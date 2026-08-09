package com.example.trex_kotlin.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Size
import android.view.Surface
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A lifecycle-bound front-camera preview that emits only source-attested observer updates.
 *
 * The normalized landmarks have already been rotated upright, but retain anatomical left/right.
 * For this mirrored front preview an overlay should draw x as `1 - x` when
 * [PoseFrame.isMirrored] is true. No further rotation is needed when rotationDegrees is zero.
 * Raw MediaPipe candidate ordering is never treated as primary-person identity.
 * Callbacks are delivered on the main thread. The caller remains responsible for requesting the
 * CAMERA runtime permission before setting [active] to true.
 */
@SuppressLint("MissingPermission")
@Composable
fun PoseCameraPreview(
    modifier: Modifier = Modifier,
    config: PoseCameraConfig = PoseCameraConfig(),
    active: Boolean = true,
    onPoseObservation: (PoseObserverUpdate) -> Unit,
    onError: (PoseCameraError) -> Unit = {},
    onStatusChanged: (PoseCameraStatus) -> Unit = {},
    onInferenceTime: (milliseconds: Long) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onPoseObservationState = rememberUpdatedState(onPoseObservation)
    val onErrorState = rememberUpdatedState(onError)
    val onStatusChangedState = rememberUpdatedState(onStatusChanged)
    val onInferenceTimeState = rememberUpdatedState(onInferenceTime)
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
        update = { view ->
            view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            view.scaleType = PreviewView.ScaleType.FIT_CENTER
        },
    )

    DisposableEffect(lifecycleOwner, previewView, config, active) {
        if (!active) {
            onStatusChangedState.value(PoseCameraStatus.Stopped)
            return@DisposableEffect onDispose { }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onErrorState.value(PoseCameraError.CameraPermissionMissing)
            onStatusChangedState.value(PoseCameraStatus.Stopped)
            return@DisposableEffect onDispose { }
        }

        onStatusChangedState.value(PoseCameraStatus.Initializing)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val disposed = AtomicBoolean(false)
        val cameraProviderRef = AtomicReference<ProcessCameraProvider?>(null)
        val previewUseCaseRef = AtomicReference<Preview?>(null)
        val analysisUseCaseRef = AtomicReference<ImageAnalysis?>(null)
        val landmarkerRef = AtomicReference<MediaPipePoseLandmarker?>(null)
        val observerRef = AtomicReference<MediaPipePoseObserver?>(null)
        val latestDeliveryRef = AtomicReference<PendingPoseObservationDelivery?>(null)
        val observationDispatchScheduled = AtomicBoolean(false)
        val terminalCleanup = PoseCameraTerminalCleanup()

        val rotationListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            previewUseCaseRef.get()?.targetRotation = targetRotation
            analysisUseCaseRef.get()?.targetRotation = targetRotation
        }

        /**
         * Claims every owned resource exactly once. CameraX teardown is posted to the main
         * executor, while analysis-owned native resources close behind any in-flight frame before
         * the executor shuts down. Both branches are best-effort so one throwing callback or
         * cleanup operation cannot strand the remaining resources.
         */
        fun terminatePoseCamera(error: PoseCameraError? = null) {
            terminalCleanup.terminate(
                { disposed.set(true) },
                { latestDeliveryRef.set(null) },
                // Stop accepting new frames as soon as a terminal failure is observed. The main
                // cleanup repeats this after atomically detaching the use case to cover races.
                { analysisUseCaseRef.get()?.clearAnalyzer() },
                {
                    analysisExecutor.closeAfterPendingWork(
                        landmarker = landmarkerRef.getAndSet(null),
                        observer = observerRef.getAndSet(null),
                    )
                },
                {
                    val mainCleanup = {
                        runPoseCameraCleanupSteps(
                            { previewView.removeOnLayoutChangeListener(rotationListener) },
                            {
                                val analysisUseCase = analysisUseCaseRef.getAndSet(null)
                                analysisUseCase?.clearAnalyzer()
                                val previewUseCase = previewUseCaseRef.getAndSet(null)
                                val cameraProvider = cameraProviderRef.getAndSet(null)
                                val ownedUseCases = listOfNotNull(
                                    previewUseCase,
                                    analysisUseCase,
                                ).toTypedArray()
                                if (cameraProvider != null && ownedUseCases.isNotEmpty()) {
                                    cameraProvider.unbind(*ownedUseCases)
                                }
                            },
                            { error?.let { onErrorState.value(it) } },
                            { onStatusChangedState.value(PoseCameraStatus.Stopped) },
                        )
                    }
                    try {
                        mainExecutor.execute(mainCleanup)
                    } catch (_: RejectedExecutionException) {
                        mainCleanup()
                    }
                },
            )
        }

        try {
            previewView.addOnLayoutChangeListener(rotationListener)
        } catch (error: Exception) {
            terminatePoseCamera(PoseCameraError.CameraInitializationFailed(error))
            return@DisposableEffect onDispose { terminatePoseCamera() }
        }

        fun dispatchLatestObservation(update: PoseObserverUpdate, inferenceTimeMs: Long) {
            latestDeliveryRef.set(PendingPoseObservationDelivery(update, inferenceTimeMs))
            if (!observationDispatchScheduled.compareAndSet(false, true)) return
            mainExecutor.execute {
                val latest = latestDeliveryRef.getAndSet(null)
                if (!disposed.get() && latest != null) {
                    onInferenceTimeState.value(latest.inferenceTimeMs)
                    onPoseObservationState.value(latest.update)
                }
                observationDispatchScheduled.set(false)
                val pending = latestDeliveryRef.get()
                if (pending != null) {
                    dispatchLatestObservation(pending.update, pending.inferenceTimeMs)
                }
            }
        }

        try {
            analysisExecutor.execute initializeLandmarker@{
            val verifiedProfile = try {
                VerifiedMediaPipePoseObserverProfile.verify(context, config)
            } catch (_: java.io.FileNotFoundException) {
                terminatePoseCamera(PoseCameraError.MissingModelAsset(config.modelAssetName))
                return@initializeLandmarker
            } catch (error: PoseObserverArtifactVerificationException) {
                terminatePoseCamera(PoseCameraError.ObserverArtifactVerificationFailed(error.failure))
                return@initializeLandmarker
            }
            val landmarker = MediaPipePoseLandmarker(
                context = context,
                verifiedProfile = verifiedProfile,
                onResult = { result, captureTimestampMs, geometryContext, inferenceTimeMs ->
                    observerRef.get()?.let { observer ->
                        try {
                            val update = observer.accept(
                                result.toCandidateBatch(
                                    captureTimestampMs = captureTimestampMs,
                                    geometryContext = geometryContext,
                                ),
                            )
                            dispatchLatestObservation(update, inferenceTimeMs)
                        } catch (error: Exception) {
                            terminatePoseCamera(PoseCameraError.FrameAnalysisFailed(error))
                        }
                    }
                },
                onError = ::terminatePoseCamera,
            )
            landmarkerRef.set(landmarker)
            if (disposed.get()) {
                if (landmarkerRef.compareAndSet(landmarker, null)) landmarker.close()
                return@initializeLandmarker
            }
            val observer = landmarker.initialize()
            if (observer == null) {
                // MediaPipe reports the concrete initialization error through onError. This call
                // is the fallback for a closed landmarker and is idempotent with that callback.
                terminatePoseCamera()
                return@initializeLandmarker
            }
            if (disposed.get() || landmarkerRef.get() !== landmarker) {
                observer.close()
                if (landmarkerRef.compareAndSet(landmarker, null)) landmarker.close()
                return@initializeLandmarker
            }
            observerRef.set(observer)
            if (disposed.get()) {
                if (observerRef.compareAndSet(observer, null)) observer.close()
                if (landmarkerRef.compareAndSet(landmarker, null)) landmarker.close()
                return@initializeLandmarker
            }

            try {
                mainExecutor.execute bindCamera@{
                    if (disposed.get()) return@bindCamera
                    try {
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                        cameraProviderFuture.addListener(
                            providerReady@{
                                if (disposed.get()) return@providerReady
                                try {
                                    val cameraProvider = cameraProviderFuture.get()
                                    cameraProviderRef.set(cameraProvider)
                                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                                    if (!cameraProvider.hasCamera(cameraSelector)) {
                                        terminatePoseCamera(PoseCameraError.FrontCameraUnavailable)
                                        return@providerReady
                                    }

                                    val targetRotation =
                                        previewView.display?.rotation ?: Surface.ROTATION_0
                                    val previewResolutionSelector = ResolutionSelector.Builder()
                                        .setAspectRatioStrategy(
                                            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                                        )
                                        .build()
                                    val analysisResolutionSelector = ResolutionSelector.Builder()
                                        .setAspectRatioStrategy(
                                            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                                        )
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(640, 480),
                                                ResolutionStrategy
                                                    .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                            ),
                                        )
                                        .build()
                                    val previewUseCase = Preview.Builder()
                                        .setResolutionSelector(previewResolutionSelector)
                                        .setTargetRotation(targetRotation)
                                        .build()
                                    val analysisUseCase = ImageAnalysis.Builder()
                                        .setResolutionSelector(analysisResolutionSelector)
                                        .setTargetRotation(targetRotation)
                                        .setBackpressureStrategy(
                                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST,
                                        )
                                        .setOutputImageFormat(
                                            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888,
                                        )
                                        .build()

                                    previewUseCaseRef.set(previewUseCase)
                                    analysisUseCaseRef.set(analysisUseCase)
                                    if (disposed.get()) return@providerReady
                                    analysisUseCase.setAnalyzer(analysisExecutor) { imageProxy ->
                                        landmarker.analyze(
                                            imageProxy,
                                            previewIsMirrored = true,
                                        )
                                    }
                                    previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
                                    val viewPort = checkNotNull(previewView.viewPort) {
                                        "Preview and analysis require one shared CameraX ViewPort"
                                    }
                                    val useCaseGroup = UseCaseGroup.Builder()
                                        .addUseCase(previewUseCase)
                                        .addUseCase(analysisUseCase)
                                        .setViewPort(viewPort)
                                        .build()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        useCaseGroup,
                                    )
                                    terminalCleanup.runIfActive {
                                        onStatusChangedState.value(PoseCameraStatus.Ready)
                                    }
                                } catch (error: Exception) {
                                    terminatePoseCamera(
                                        PoseCameraError.CameraInitializationFailed(error),
                                    )
                                }
                            },
                            mainExecutor,
                        )
                    } catch (error: Exception) {
                        terminatePoseCamera(PoseCameraError.CameraInitializationFailed(error))
                    }
                }
            } catch (error: Exception) {
                terminatePoseCamera(PoseCameraError.CameraInitializationFailed(error))
            }
            }
        } catch (error: Exception) {
            terminatePoseCamera(PoseCameraError.CameraInitializationFailed(error))
        }

        onDispose {
            terminatePoseCamera()
        }
    }
}

private fun PoseLandmarkerResult.toCandidateBatch(
    captureTimestampMs: Long,
    geometryContext: PoseCameraGeometryContext,
): PoseCandidateBatch {
    check(timestampMs() == captureTimestampMs)
    return poseCandidateBatch(
        captureTimestampMs = captureTimestampMs,
        normalizedCandidates = landmarks().map { candidate ->
            candidate.map(NormalizedLandmark::toRawPoseLandmark)
        },
        worldCandidates = worldLandmarks().map { candidate ->
            candidate.map(Landmark::toRawPoseLandmark)
        },
        geometryContext = geometryContext,
    )
}

internal data class RawPoseLandmark(
    val x: Double,
    val y: Double,
    val z: Double,
    val visibility: Double?,
    val presence: Double?,
)

internal fun poseCandidateBatch(
    captureTimestampMs: Long,
    normalizedCandidates: List<List<RawPoseLandmark>>,
    worldCandidates: List<List<RawPoseLandmark>>,
    geometryContext: PoseCameraGeometryContext,
): PoseCandidateBatch {
    require(normalizedCandidates.size == worldCandidates.size) {
        "MediaPipe normalized/world candidate counts must match"
    }
    val candidates = normalizedCandidates.indices.mapNotNull { candidateIndex ->
        val normalized = normalizedCandidates[candidateIndex]
        val world = worldCandidates[candidateIndex]
        if (normalized.size != PoseJoint.entries.size || world.size != PoseJoint.entries.size) {
            return@mapNotNull null
        }
        try {
            PoseFrame(
                timestampMs = captureTimestampMs,
                landmarks = PoseJoint.entries.toLandmarkMap(normalized),
                worldLandmarks = PoseJoint.entries.toLandmarkMap(world),
                imageWidth = geometryContext.outputImageWidth,
                imageHeight = geometryContext.outputImageHeight,
                rotationDegrees = geometryContext.outputRotationDegrees,
                isMirrored = geometryContext.displayMirrored,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    return PoseCandidateBatch(
        timestampMs = captureTimestampMs,
        candidates = candidates,
        rawCandidateCount = normalizedCandidates.size,
        geometryContext = geometryContext,
    )
}

private fun Iterable<PoseJoint>.toLandmarkMap(
    landmarks: List<RawPoseLandmark>,
): Map<PoseJoint, PoseLandmark> = buildMap {
    for (joint in this@toLandmarkMap) {
        val landmark = landmarks.getOrNull(joint.mediaPipeIndex) ?: continue
        put(
            joint,
            PoseLandmark(
                x = landmark.x,
                y = landmark.y,
                z = landmark.z,
                visibility = landmark.visibility ?: 0.0,
                presence = landmark.presence ?: 0.0,
            ),
        )
    }
}

private fun NormalizedLandmark.toRawPoseLandmark(): RawPoseLandmark = RawPoseLandmark(
    x = x().toDouble(),
    y = y().toDouble(),
    z = z().toDouble(),
    visibility = visibility().orElse(null)?.toDouble(),
    presence = presence().orElse(null)?.toDouble(),
)

private fun Landmark.toRawPoseLandmark(): RawPoseLandmark = RawPoseLandmark(
    x = x().toDouble(),
    y = y().toDouble(),
    z = z().toDouble(),
    visibility = visibility().orElse(null)?.toDouble(),
    presence = presence().orElse(null)?.toDouble(),
)

private data class PendingPoseObservationDelivery(
    val update: PoseObserverUpdate,
    val inferenceTimeMs: Long,
)

/** Android-free one-shot gate used to make every terminal path share the same resilient cleanup. */
internal class PoseCameraTerminalCleanup {
    private val lock = Any()
    private var terminated = false

    fun terminate(vararg cleanupSteps: () -> Unit): Boolean {
        synchronized(lock) {
            if (terminated) return false
            terminated = true
        }
        runPoseCameraCleanupSteps(*cleanupSteps)
        return true
    }

    fun runIfActive(action: () -> Unit): Boolean = synchronized(lock) {
        if (terminated) return false
        action()
        true
    }
}

internal fun runPoseCameraCleanupSteps(vararg cleanupSteps: () -> Unit) {
    cleanupSteps.forEach { cleanup ->
        try {
            cleanup()
        } catch (_: Throwable) {
            // Terminal cleanup is deliberately best-effort: every remaining resource and callback
            // still gets its cleanup attempt even when one owner violates its close contract.
        }
    }
}

private fun ExecutorService.closeAfterPendingWork(
    landmarker: MediaPipePoseLandmarker?,
    observer: MediaPipePoseObserver?,
) {
    try {
        if (landmarker != null || observer != null) {
            execute {
                runPoseCameraCleanupSteps(
                    { observer?.close() },
                    { landmarker?.close() },
                )
            }
        }
    } catch (_: RejectedExecutionException) {
        runPoseCameraCleanupSteps(
            { observer?.close() },
            { landmarker?.close() },
        )
    } finally {
        shutdown()
    }
}
