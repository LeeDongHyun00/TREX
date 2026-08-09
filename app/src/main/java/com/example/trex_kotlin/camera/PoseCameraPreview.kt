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

        fun stopPoseRuntime() {
            latestDeliveryRef.set(null)
            val observer = observerRef.getAndSet(null)
            val landmarker = landmarkerRef.getAndSet(null)
            if (observer == null && landmarker == null) return
            try {
                analysisExecutor.execute {
                    observer?.close()
                    landmarker?.close()
                }
            } catch (_: RejectedExecutionException) {
                observer?.close()
                landmarker?.close()
            }
        }

        fun dispatchError(error: PoseCameraError) {
            mainExecutor.execute {
                if (!disposed.get()) onErrorState.value(error)
            }
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

        val rotationListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            previewUseCaseRef.get()?.targetRotation = targetRotation
            analysisUseCaseRef.get()?.targetRotation = targetRotation
        }
        previewView.addOnLayoutChangeListener(rotationListener)

        analysisExecutor.execute initializeLandmarker@{
            val verifiedProfile = try {
                VerifiedMediaPipePoseObserverProfile.verify(context, config)
            } catch (_: java.io.FileNotFoundException) {
                dispatchError(PoseCameraError.MissingModelAsset(config.modelAssetName))
                mainExecutor.execute {
                    if (!disposed.get()) onStatusChangedState.value(PoseCameraStatus.Stopped)
                }
                return@initializeLandmarker
            } catch (error: PoseObserverArtifactVerificationException) {
                dispatchError(PoseCameraError.ObserverArtifactVerificationFailed(error.failure))
                mainExecutor.execute {
                    if (!disposed.get()) onStatusChangedState.value(PoseCameraStatus.Stopped)
                }
                return@initializeLandmarker
            }
            val landmarker = MediaPipePoseLandmarker(
                context = context,
                verifiedProfile = verifiedProfile,
                onResult = { result, captureTimestampMs, width, height, rotationDegrees,
                    isMirrored, inferenceTimeMs ->
                    observerRef.get()?.let { observer ->
                        try {
                            val update = observer.accept(
                                result.toCandidateBatch(
                                    captureTimestampMs = captureTimestampMs,
                                    imageWidth = width,
                                    imageHeight = height,
                                    rotationDegrees = rotationDegrees,
                                    isMirrored = isMirrored,
                                ),
                            )
                            dispatchLatestObservation(update, inferenceTimeMs)
                        } catch (error: Exception) {
                            stopPoseRuntime()
                            dispatchError(PoseCameraError.FrameAnalysisFailed(error))
                            mainExecutor.execute {
                                analysisUseCaseRef.get()?.clearAnalyzer()
                                if (!disposed.get()) {
                                    onStatusChangedState.value(PoseCameraStatus.Stopped)
                                }
                            }
                        }
                    }
                },
                onError = { error ->
                    if (error is PoseCameraError.FrameAnalysisFailed) {
                        stopPoseRuntime()
                        mainExecutor.execute {
                            analysisUseCaseRef.get()?.clearAnalyzer()
                            if (!disposed.get()) {
                                onStatusChangedState.value(PoseCameraStatus.Stopped)
                            }
                        }
                    }
                    dispatchError(error)
                },
            )
            landmarkerRef.set(landmarker)
            if (disposed.get()) {
                landmarkerRef.compareAndSet(landmarker, null)
                landmarker.close()
                return@initializeLandmarker
            }
            val observer = landmarker.initialize()
            if (observer == null) {
                landmarkerRef.compareAndSet(landmarker, null)
                landmarker.close()
                mainExecutor.execute {
                    if (!disposed.get()) onStatusChangedState.value(PoseCameraStatus.Stopped)
                }
                return@initializeLandmarker
            }
            if (disposed.get() || landmarkerRef.get() !== landmarker) {
                observer.close()
                landmarkerRef.compareAndSet(landmarker, null)
                landmarker.close()
                return@initializeLandmarker
            }
            observerRef.set(observer)
            if (disposed.get()) {
                observerRef.compareAndSet(observer, null)
                observer.close()
                landmarkerRef.compareAndSet(landmarker, null)
                landmarker.close()
                return@initializeLandmarker
            }

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
                                    stopPoseRuntime()
                                    dispatchError(PoseCameraError.FrontCameraUnavailable)
                                    onStatusChangedState.value(PoseCameraStatus.Stopped)
                                    return@providerReady
                                }

                                val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
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
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
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
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                    .build()
                                    .also { useCase ->
                                        useCase.setAnalyzer(analysisExecutor) { imageProxy ->
                                            landmarker.analyze(imageProxy, previewIsMirrored = true)
                                        }
                                    }

                                previewUseCaseRef.set(previewUseCase)
                                analysisUseCaseRef.set(analysisUseCase)
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
                                onStatusChangedState.value(PoseCameraStatus.Ready)
                            } catch (error: Exception) {
                                stopPoseRuntime()
                                dispatchError(PoseCameraError.CameraInitializationFailed(error))
                                onStatusChangedState.value(PoseCameraStatus.Stopped)
                            }
                        },
                        mainExecutor,
                    )
                } catch (error: Exception) {
                    stopPoseRuntime()
                    dispatchError(PoseCameraError.CameraInitializationFailed(error))
                    onStatusChangedState.value(PoseCameraStatus.Stopped)
                }
            }
        }

        onDispose {
            disposed.set(true)
            previewView.removeOnLayoutChangeListener(rotationListener)
            analysisUseCaseRef.getAndSet(null)?.clearAnalyzer()
            val observer = observerRef.getAndSet(null)
            latestDeliveryRef.set(null)
            val previewUseCase = previewUseCaseRef.getAndSet(null)
            val analysisUseCase = analysisUseCaseRef.getAndSet(null)
            cameraProviderRef.getAndSet(null)?.let { provider ->
                val ownedUseCases = listOfNotNull(previewUseCase, analysisUseCase).toTypedArray()
                if (ownedUseCases.isNotEmpty()) provider.unbind(*ownedUseCases)
            }
            analysisExecutor.closeAfterPendingWork(
                landmarker = landmarkerRef.getAndSet(null),
                observer = observer,
            )
            onStatusChangedState.value(PoseCameraStatus.Stopped)
        }
    }
}

private fun PoseLandmarkerResult.toCandidateBatch(
    captureTimestampMs: Long,
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int,
    isMirrored: Boolean,
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
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        rotationDegrees = rotationDegrees,
        isMirrored = isMirrored,
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
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int,
    isMirrored: Boolean,
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
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                rotationDegrees = rotationDegrees,
                isMirrored = isMirrored,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    return PoseCandidateBatch(
        timestampMs = captureTimestampMs,
        candidates = candidates,
        rawCandidateCount = normalizedCandidates.size,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        rotationDegrees = rotationDegrees,
        isMirrored = isMirrored,
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

private fun ExecutorService.closeAfterPendingWork(
    landmarker: MediaPipePoseLandmarker?,
    observer: MediaPipePoseObserver?,
) {
    try {
        if (landmarker != null || observer != null) {
            execute {
                observer?.close()
                landmarker?.close()
            }
        }
    } catch (_: RejectedExecutionException) {
        observer?.close()
        landmarker?.close()
    } finally {
        shutdown()
    }
}
