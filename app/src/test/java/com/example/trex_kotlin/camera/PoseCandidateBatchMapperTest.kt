package com.example.trex_kotlin.camera

import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseCandidateBatchMapperTest {
    @Test
    fun normalizedAndWorldCandidatesStayPairedByTheSameResultIndex() {
        val batch = poseCandidateBatch(
            captureTimestampMs = 123L,
            normalizedCandidates = listOf(candidate(xBase = 0.10), candidate(xBase = 0.60)),
            worldCandidates = listOf(candidate(xBase = 10.0), candidate(xBase = 60.0)),
            geometryContext = geometryContext(),
        )

        assertEquals(2, batch.rawCandidateCount)
        assertEquals(2, batch.candidates.size)
        assertEquals(0.10, batch.candidates[0].landmarks.getValue(PoseJoint.NOSE).x, 0.0)
        assertEquals(10.0, batch.candidates[0].worldLandmarks.getValue(PoseJoint.NOSE).x, 0.0)
        assertEquals(0.60, batch.candidates[1].landmarks.getValue(PoseJoint.NOSE).x, 0.0)
        assertEquals(60.0, batch.candidates[1].worldLandmarks.getValue(PoseJoint.NOSE).x, 0.0)
    }

    @Test
    fun candidateCountOrLandmarkCardinalityDriftFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            poseCandidateBatch(
                captureTimestampMs = 1L,
                normalizedCandidates = listOf(candidate()),
                worldCandidates = emptyList(),
                geometryContext = geometryContext(),
            )
        }

        val batch = poseCandidateBatch(
            captureTimestampMs = 2L,
            normalizedCandidates = listOf(candidate().dropLast(1), candidate() + landmark()),
            worldCandidates = listOf(candidate(), candidate()),
            geometryContext = geometryContext(),
        )
        assertEquals(2, batch.rawCandidateCount)
        assertEquals(0, batch.candidates.size)
        assertEquals(2, batch.rejectedCandidateCount)
    }

    @Test
    fun absentConfidenceIsZeroAndNonFiniteCandidateIsRejected() {
        val missingConfidence = candidate().toMutableList().also { items ->
            items[PoseJoint.NOSE.mediaPipeIndex] = landmark(visibility = null, presence = null)
        }
        val nonFinite = candidate().toMutableList().also { items ->
            items[PoseJoint.NOSE.mediaPipeIndex] = landmark(x = Double.NaN)
        }
        val batch = poseCandidateBatch(
            captureTimestampMs = 3L,
            normalizedCandidates = listOf(missingConfidence, nonFinite),
            worldCandidates = listOf(candidate(), candidate()),
            geometryContext = geometryContext(),
        )

        assertEquals(2, batch.rawCandidateCount)
        assertEquals(1, batch.candidates.size)
        val nose = batch.candidates.single().landmarks.getValue(PoseJoint.NOSE)
        assertEquals(0.0, nose.visibility, 0.0)
        assertEquals(0.0, nose.presence, 0.0)
        assertTrue(batch.rejectedCandidateCount == 1)
    }

    @Test
    fun exactCropRotationAndMirrorGeometrySurvivesCandidateMapping() {
        val geometry = geometryContext(
            sourceImageWidth = 1_280,
            sourceImageHeight = 720,
            cropLeft = 100,
            cropTop = 20,
            cropRightExclusive = 1_100,
            cropBottomExclusive = 620,
            inputRotationDegrees = 90,
            outputImageWidth = 600,
            outputImageHeight = 1_000,
            displayMirrored = false,
        )

        val batch = poseCandidateBatch(
            captureTimestampMs = 4L,
            normalizedCandidates = listOf(candidate()),
            worldCandidates = listOf(candidate()),
            geometryContext = geometry,
        )

        assertTrue(batch.geometryContext === geometry)
        assertEquals(1_280, batch.geometryContext.sourceImageWidth)
        assertEquals(720, batch.geometryContext.sourceImageHeight)
        assertEquals(100, batch.geometryContext.cropLeft)
        assertEquals(20, batch.geometryContext.cropTop)
        assertEquals(1_100, batch.geometryContext.cropRightExclusive)
        assertEquals(620, batch.geometryContext.cropBottomExclusive)
        assertEquals(90, batch.geometryContext.inputRotationDegrees)
        assertEquals(600, batch.candidates.single().imageWidth)
        assertEquals(1_000, batch.candidates.single().imageHeight)
        assertEquals(0, batch.candidates.single().rotationDegrees)
        assertTrue(!batch.candidates.single().isMirrored)
        assertTrue(!batch.geometryContext.inferencePixelsMirrored)
        assertTrue(!batch.geometryContext.displayMirrored)
    }

    private fun candidate(xBase: Double = 0.25): List<RawPoseLandmark> =
        List(PoseJoint.entries.size) { index -> landmark(x = xBase + index * 0.001) }

    private fun landmark(
        x: Double = 0.25,
        visibility: Double? = 1.0,
        presence: Double? = 1.0,
    ): RawPoseLandmark = RawPoseLandmark(
        x = x,
        y = 0.50,
        z = 0.0,
        visibility = visibility,
        presence = presence,
    )

    private fun geometryContext(
        sourceImageWidth: Int = 640,
        sourceImageHeight: Int = 480,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        cropRightExclusive: Int = 640,
        cropBottomExclusive: Int = 480,
        inputRotationDegrees: Int = 0,
        outputImageWidth: Int = 640,
        outputImageHeight: Int = 480,
        displayMirrored: Boolean = true,
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
        inferencePixelsMirrored = false,
        displayMirrored = displayMirrored,
        preprocessingArtifactSha256 = SHA_A,
    )

    private companion object {
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
