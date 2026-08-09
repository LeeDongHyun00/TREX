package com.example.trex_kotlin.pose.runtime

import com.example.trex_kotlin.pose.PoseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseCameraGeometryContextTest {
    @Test
    fun canonicalFingerprintIsDeterministicAndBindsEveryGeometryDimension() {
        val baseline = context()
        val duplicate = context()

        assertEquals(EXPECTED_BASELINE_SHA256, baseline.artifactSha256)
        assertTrue(baseline.artifactSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(baseline.artifactSha256 == duplicate.artifactSha256)

        val changes = listOf(
            context(sourceImageWidth = 1_921),
            context(sourceImageHeight = 1_081),
            context(cropLeft = 101, outputImageHeight = 1_599),
            context(cropTop = 21, outputImageWidth = 999),
            context(cropRightExclusive = 1_699, outputImageHeight = 1_599),
            context(cropBottomExclusive = 1_019, outputImageWidth = 999),
            context(inputRotationDegrees = 270),
            context(displayMirrored = false),
            context(preprocessingArtifactSha256 = SHA_B),
        )

        changes.forEach { changed ->
            assertNotEquals(baseline.artifactSha256, changed.artifactSha256)
        }
    }

    @Test
    fun cropUsesHalfOpenAndroidRectBoundsAndMustRemainInsideSource() {
        assertThrows(IllegalArgumentException::class.java) {
            context(cropLeft = -1, outputImageHeight = 1_601)
        }
        assertThrows(IllegalArgumentException::class.java) {
            context(cropTop = -1, outputImageWidth = 1_001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            context(cropRightExclusive = 100, cropLeft = 100, outputImageHeight = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            context(cropBottomExclusive = 20, cropTop = 20, outputImageWidth = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            context(sourceImageWidth = 1_699)
        }
        assertThrows(IllegalArgumentException::class.java) {
            context(sourceImageHeight = 1_019)
        }

        val fullFrame = context(
            cropLeft = 0,
            cropTop = 0,
            cropRightExclusive = 1_920,
            cropBottomExclusive = 1_080,
            outputImageWidth = 1_080,
            outputImageHeight = 1_920,
        )
        assertTrue(fullFrame.cropWidth == 1_920)
        assertTrue(fullFrame.cropHeight == 1_080)
    }

    @Test
    fun outputDimensionsMustExactlyApplyTheInputRotation() {
        val upright = context(
            inputRotationDegrees = 0,
            outputImageWidth = 1_600,
            outputImageHeight = 1_000,
        )
        val upsideDown = context(
            inputRotationDegrees = 180,
            outputImageWidth = 1_600,
            outputImageHeight = 1_000,
        )
        val clockwise = context()
        val counterClockwise = context(inputRotationDegrees = 270)

        assertTrue(upright.outputRotationDegrees == 0)
        assertTrue(upsideDown.outputRotationDegrees == 0)
        assertTrue(clockwise.outputImageWidth == 1_000)
        assertTrue(clockwise.outputImageHeight == 1_600)
        assertTrue(counterClockwise.outputImageWidth == 1_000)
        assertTrue(counterClockwise.outputImageHeight == 1_600)

        assertThrows(IllegalArgumentException::class.java) {
            context(inputRotationDegrees = 45)
        }
        assertThrows(IllegalArgumentException::class.java) {
            context(outputImageWidth = 1_600, outputImageHeight = 1_000)
        }
    }

    @Test
    fun verifiedInferencePixelsCannotBeMirroredAndPreprocessingMustBePinned() {
        assertThrows(IllegalArgumentException::class.java) {
            context(inferencePixelsMirrored = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            context(preprocessingArtifactSha256 = "not-a-sha")
        }
        assertThrows(IllegalArgumentException::class.java) {
            context(preprocessingArtifactSha256 = SHA_A.uppercase())
        }
    }

    @Test
    fun outputFrameMatchUsesOrientedDimensionsRotationAndDisplayMirror() {
        val geometry = context()

        assertTrue(
            geometry.matchesOutputFrame(
                PoseFrame(
                    timestampMs = 1L,
                    landmarks = emptyMap(),
                    imageWidth = 1_000,
                    imageHeight = 1_600,
                    rotationDegrees = 0,
                    isMirrored = true,
                ),
            ),
        )
        assertFalse(
            geometry.matchesOutputFrame(
                PoseFrame(
                    timestampMs = 1L,
                    landmarks = emptyMap(),
                    imageWidth = 1_000,
                    imageHeight = 1_600,
                    rotationDegrees = 0,
                    isMirrored = false,
                ),
            ),
        )
    }

    private fun context(
        sourceImageWidth: Int = 1_920,
        sourceImageHeight: Int = 1_080,
        cropLeft: Int = 100,
        cropTop: Int = 20,
        cropRightExclusive: Int = 1_700,
        cropBottomExclusive: Int = 1_020,
        inputRotationDegrees: Int = 90,
        outputImageWidth: Int = 1_000,
        outputImageHeight: Int = 1_600,
        inferencePixelsMirrored: Boolean = false,
        displayMirrored: Boolean = true,
        preprocessingArtifactSha256: String = SHA_A,
    ): PoseCameraGeometryContext = PoseCameraGeometryContext(
        sourceImageWidth = sourceImageWidth,
        sourceImageHeight = sourceImageHeight,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropRightExclusive = cropRightExclusive,
        cropBottomExclusive = cropBottomExclusive,
        inputRotationDegrees = inputRotationDegrees,
        outputImageWidth = outputImageWidth,
        outputImageHeight = outputImageHeight,
        inferencePixelsMirrored = inferencePixelsMirrored,
        displayMirrored = displayMirrored,
        preprocessingArtifactSha256 = preprocessingArtifactSha256,
    )

    private companion object {
        const val EXPECTED_BASELINE_SHA256 =
            "0d125314431b98e8075d7ecfa089b3310d76d3de313d4d399907d26fc3e04d6d"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
