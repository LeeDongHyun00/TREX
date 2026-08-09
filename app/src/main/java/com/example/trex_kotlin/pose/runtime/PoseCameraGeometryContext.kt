package com.example.trex_kotlin.pose.runtime

import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256

private val CAMERA_GEOMETRY_SHA256 = Regex("^[0-9a-f]{64}$")

/**
 * Immutable description of the exact CameraX crop-to-inference image transform for one frame.
 *
 * This content hash is a continuity and drift fingerprint, not a signature or proof of camera
 * authenticity. Source ownership and capture-time binding are added separately by
 * [PoseObservationSource].
 */
internal class PoseCameraGeometryContext(
    val sourceImageWidth: Int,
    val sourceImageHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRightExclusive: Int,
    val cropBottomExclusive: Int,
    val inputRotationDegrees: Int,
    val outputImageWidth: Int,
    val outputImageHeight: Int,
    val inferencePixelsMirrored: Boolean,
    val displayMirrored: Boolean,
    val preprocessingArtifactSha256: String,
) {
    val cropWidth: Int = cropRightExclusive - cropLeft
    val cropHeight: Int = cropBottomExclusive - cropTop

    /** Pose coordinates are emitted in the already-upright cropped output image. */
    val outputRotationDegrees: Int = 0

    init {
        require(sourceImageWidth > 0 && sourceImageHeight > 0) {
            "Source image dimensions must be positive"
        }
        require(cropLeft >= 0 && cropTop >= 0) {
            "Crop origin must be inside the source image"
        }
        require(cropRightExclusive > cropLeft && cropBottomExclusive > cropTop) {
            "Crop rectangle must be non-empty"
        }
        require(
            cropRightExclusive <= sourceImageWidth &&
                cropBottomExclusive <= sourceImageHeight,
        ) {
            "Crop rectangle must be contained by the source image"
        }
        require(inputRotationDegrees in SUPPORTED_ROTATIONS) {
            "Input rotation must be 0, 90, 180, or 270 degrees"
        }
        require(outputImageWidth > 0 && outputImageHeight > 0) {
            "Output image dimensions must be positive"
        }
        val expectedOutputWidth = if (inputRotationDegrees in AXIS_SWAPPING_ROTATIONS) {
            cropHeight
        } else {
            cropWidth
        }
        val expectedOutputHeight = if (inputRotationDegrees in AXIS_SWAPPING_ROTATIONS) {
            cropWidth
        } else {
            cropHeight
        }
        require(
            outputImageWidth == expectedOutputWidth && outputImageHeight == expectedOutputHeight,
        ) {
            "Output image dimensions must match the cropped image after input rotation"
        }
        require(!inferencePixelsMirrored) {
            "The verified preprocessing contract does not permit mirrored inference pixels"
        }
        require(CAMERA_GEOMETRY_SHA256.matches(preprocessingArtifactSha256)) {
            "preprocessingArtifactSha256 must be a lowercase SHA-256"
        }
    }

    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "poseCameraGeometryContextSchemaVersion" to SCHEMA_VERSION.toString(),
            "coordinateDomain" to "UPRIGHT_CROPPED_NORMALIZED_IMAGE",
            "sourceImageWidth" to sourceImageWidth.toString(),
            "sourceImageHeight" to sourceImageHeight.toString(),
            "cropLeft" to cropLeft.toString(),
            "cropTop" to cropTop.toString(),
            "cropRightExclusive" to cropRightExclusive.toString(),
            "cropBottomExclusive" to cropBottomExclusive.toString(),
            "inputRotationDegrees" to inputRotationDegrees.toString(),
            "outputImageWidth" to outputImageWidth.toString(),
            "outputImageHeight" to outputImageHeight.toString(),
            "outputRotationDegrees" to outputRotationDegrees.toString(),
            "inferencePixelsMirrored" to inferencePixelsMirrored.toString(),
            "displayMirrored" to displayMirrored.toString(),
            "preprocessingArtifactSha256" to preprocessingArtifactSha256,
        ),
    )

    internal fun matchesOutputFrame(frame: PoseFrame): Boolean =
        frame.imageWidth == outputImageWidth &&
            frame.imageHeight == outputImageHeight &&
            frame.rotationDegrees == outputRotationDegrees &&
            frame.isMirrored == displayMirrored

    internal companion object {
        const val SCHEMA_VERSION = 1
        val SUPPORTED_ROTATIONS = setOf(0, 90, 180, 270)
        val AXIS_SWAPPING_ROTATIONS = setOf(90, 270)
    }
}
