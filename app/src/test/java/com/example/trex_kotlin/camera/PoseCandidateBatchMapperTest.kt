package com.example.trex_kotlin.camera

import com.example.trex_kotlin.pose.PoseJoint
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
            imageWidth = 640,
            imageHeight = 480,
            rotationDegrees = 0,
            isMirrored = true,
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
                imageWidth = 640,
                imageHeight = 480,
                rotationDegrees = 0,
                isMirrored = true,
            )
        }

        val batch = poseCandidateBatch(
            captureTimestampMs = 2L,
            normalizedCandidates = listOf(candidate().dropLast(1), candidate() + landmark()),
            worldCandidates = listOf(candidate(), candidate()),
            imageWidth = 640,
            imageHeight = 480,
            rotationDegrees = 0,
            isMirrored = true,
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
            imageWidth = 640,
            imageHeight = 480,
            rotationDegrees = 0,
            isMirrored = true,
        )

        assertEquals(2, batch.rawCandidateCount)
        assertEquals(1, batch.candidates.size)
        val nose = batch.candidates.single().landmarks.getValue(PoseJoint.NOSE)
        assertEquals(0.0, nose.visibility, 0.0)
        assertEquals(0.0, nose.presence, 0.0)
        assertTrue(batch.rejectedCandidateCount == 1)
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
}
