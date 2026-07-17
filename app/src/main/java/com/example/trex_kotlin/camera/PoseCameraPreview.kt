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
 * A lifecycle-bound front-camera preview that emits MediaPipe's latest single-person pose.
 *
 * The normalized landmarks have already been rotated upright, but retain anatomical left/right.
 * For this mirrored front preview an overlay should draw x as `1 - x` when
 * [PoseFrame.isMirrored] is true. No further rotation is needed when rotationDegrees is zero.
 * Callbacks are delivered on the main thread. The caller remains responsible for requesting the
 * CAMERA runtime permission before setting [active] to true.
 */
@SuppressLint("MissingPermission")
@Composable
fun PoseCameraPreview(
    modifier: Modifier = Modifier,
    config: PoseCameraConfig = PoseCameraConfig(),
    active: Boolean = true,
    onPoseFrame: (PoseFrame) -> Unit,
    onError: (PoseCameraError) -> Unit = {},
    onStatusChanged: (PoseCameraStatus) -> Unit = {},
    onInferenceTime: (milliseconds: Long) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onPoseFrameState = rememberUpdatedState(onPoseFrame)
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

        fun dispatchError(error: PoseCameraError) {
            mainExecutor.execute {
                if (!disposed.get()) onErrorState.value(error)
            }
        }

        val landmarker = MediaPipePoseLandmarker(
            context = context,
            config = config,
            onResult = { result, width, height, rotationDegrees, isMirrored, inferenceTimeMs ->
                val poseFrame = result.toPoseFrame(
                    imageWidth = width,
                    imageHeight = height,
                    rotationDegrees = rotationDegrees,
                    isMirrored = isMirrored,
                )
                mainExecutor.execute {
                    if (!disposed.get()) {
                        onInferenceTimeState.value(inferenceTimeMs)
                        onPoseFrameState.value(poseFrame)
                    }
                }
            },
            onError = ::dispatchError,
        )

        val rotationListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            previewUseCaseRef.get()?.targetRotation = targetRotation
            analysisUseCaseRef.get()?.targetRotation = targetRotation
        }
        previewView.addOnLayoutChangeListener(rotationListener)

        analysisExecutor.execute initializeLandmarker@{
            if (!landmarker.initialize()) {
                mainExecutor.execute {
                    if (!disposed.get()) onStatusChangedState.value(PoseCameraStatus.Stopped)
                }
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
                                val viewPort = previewView.viewPort
                                if (viewPort != null) {
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
                                } else {
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        previewUseCase,
                                        analysisUseCase,
                                    )
                                }
                                onStatusChangedState.value(PoseCameraStatus.Ready)
                            } catch (error: Throwable) {
                                dispatchError(PoseCameraError.CameraInitializationFailed(error))
                                onStatusChangedState.value(PoseCameraStatus.Stopped)
                            }
                        },
                        mainExecutor,
                    )
                } catch (error: Throwable) {
                    dispatchError(PoseCameraError.CameraInitializationFailed(error))
                    onStatusChangedState.value(PoseCameraStatus.Stopped)
                }
            }
        }

        onDispose {
            disposed.set(true)
            previewView.removeOnLayoutChangeListener(rotationListener)
            analysisUseCaseRef.getAndSet(null)?.clearAnalyzer()
            val previewUseCase = previewUseCaseRef.getAndSet(null)
            val analysisUseCase = analysisUseCaseRef.getAndSet(null)
            cameraProviderRef.getAndSet(null)?.let { provider ->
                val ownedUseCases = listOfNotNull(previewUseCase, analysisUseCase).toTypedArray()
                if (ownedUseCases.isNotEmpty()) provider.unbind(*ownedUseCases)
            }
            analysisExecutor.closeAfterPendingWork(landmarker)
            onStatusChangedState.value(PoseCameraStatus.Stopped)
        }
    }
}

private fun PoseLandmarkerResult.toPoseFrame(
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int,
    isMirrored: Boolean,
): PoseFrame {
    val normalized = landmarks().firstOrNull().orEmpty()
    val world = worldLandmarks().firstOrNull().orEmpty()
    return PoseFrame(
        timestampMs = timestampMs(),
        landmarks = PoseJoint.entries.toLandmarkMap(normalized),
        worldLandmarks = PoseJoint.entries.toWorldLandmarkMap(world),
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        rotationDegrees = rotationDegrees,
        isMirrored = isMirrored,
    )
}

private fun Iterable<PoseJoint>.toLandmarkMap(
    landmarks: List<NormalizedLandmark>,
): Map<PoseJoint, PoseLandmark> = buildMap {
    for (joint in this@toLandmarkMap) {
        val landmark = landmarks.getOrNull(joint.mediaPipeIndex) ?: continue
        put(
            joint,
            PoseLandmark(
                x = landmark.x().toDouble(),
                y = landmark.y().toDouble(),
                z = landmark.z().toDouble(),
                visibility = landmark.visibility().orElse(1f).toDouble(),
                presence = landmark.presence().orElse(1f).toDouble(),
            ),
        )
    }
}

private fun Iterable<PoseJoint>.toWorldLandmarkMap(
    landmarks: List<Landmark>,
): Map<PoseJoint, PoseLandmark> = buildMap {
    for (joint in this@toWorldLandmarkMap) {
        val landmark = landmarks.getOrNull(joint.mediaPipeIndex) ?: continue
        put(
            joint,
            PoseLandmark(
                x = landmark.x().toDouble(),
                y = landmark.y().toDouble(),
                z = landmark.z().toDouble(),
                visibility = landmark.visibility().orElse(1f).toDouble(),
                presence = landmark.presence().orElse(1f).toDouble(),
            ),
        )
    }
}

private fun ExecutorService.closeAfterPendingWork(landmarker: MediaPipePoseLandmarker) {
    try {
        execute(landmarker::close)
    } catch (_: RejectedExecutionException) {
        landmarker.close()
    } finally {
        shutdown()
    }
}
