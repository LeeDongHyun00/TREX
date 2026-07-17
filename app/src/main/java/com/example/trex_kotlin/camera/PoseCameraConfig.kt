package com.example.trex_kotlin.camera

/** Runtime settings for the on-device pose camera pipeline. */
data class PoseCameraConfig(
    val modelAssetName: String = DEFAULT_POSE_MODEL_ASSET,
    val delegate: PoseCameraDelegate = PoseCameraDelegate.GpuWithCpuFallback,
    val minPoseDetectionConfidence: Float = 0.5f,
    val minPosePresenceConfidence: Float = 0.5f,
    val minTrackingConfidence: Float = 0.5f,
    val numberOfPoses: Int = 1,
) {
    init {
        require(modelAssetName.isNotBlank()) { "modelAssetName must not be blank." }
        require(minPoseDetectionConfidence in 0f..1f) {
            "minPoseDetectionConfidence must be between 0 and 1."
        }
        require(minPosePresenceConfidence in 0f..1f) {
            "minPosePresenceConfidence must be between 0 and 1."
        }
        require(minTrackingConfidence in 0f..1f) {
            "minTrackingConfidence must be between 0 and 1."
        }
        require(numberOfPoses == 1) {
            "This camera pipeline emits one PoseFrame and therefore supports exactly one pose."
        }
    }

    companion object {
        const val DEFAULT_POSE_MODEL_ASSET = "pose_landmarker_full.task"
    }
}

/** MediaPipe execution preference. GPU creation and inference stay on the analysis thread. */
enum class PoseCameraDelegate {
    Cpu,
    Gpu,
    GpuWithCpuFallback,
}

const val DEFAULT_POSE_MODEL_ASSET: String = PoseCameraConfig.DEFAULT_POSE_MODEL_ASSET

/** Failures that callers can surface or use to select a non-camera fallback. */
sealed interface PoseCameraError {
    data object CameraPermissionMissing : PoseCameraError

    data object FrontCameraUnavailable : PoseCameraError

    data class MissingModelAsset(
        val assetName: String,
    ) : PoseCameraError

    data class CameraInitializationFailed(
        val cause: Throwable,
    ) : PoseCameraError

    data class LandmarkerInitializationFailed(
        val cause: Throwable,
    ) : PoseCameraError

    data class FrameAnalysisFailed(
        val cause: Throwable,
    ) : PoseCameraError
}

enum class PoseCameraStatus {
    Initializing,
    Ready,
    Stopped,
}
