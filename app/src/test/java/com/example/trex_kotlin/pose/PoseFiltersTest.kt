package com.example.trex_kotlin.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseFiltersTest {
    @Test
    fun emaSmoothsCoordinatesButKeepsCurrentConfidence() {
        val smoother = EmaPoseSmoother(alpha = 0.5)
        smoother.smooth(frame(timestampMs = 0L, x = 0.0, confidence = 1.0))

        val result = smoother.smooth(frame(timestampMs = 100L, x = 1.0, confidence = 0.8))
        val landmark = result.landmarks.getValue(PoseJoint.LEFT_KNEE)

        assertEquals(0.5, landmark.x, 1e-9)
        assertEquals(0.8, landmark.visibility, 1e-9)
        assertEquals(0.8, landmark.presence, 1e-9)
    }

    @Test
    fun visibilityGateRejectsLowConfidenceRequiredJoint() {
        val gate = PoseVisibilityGate(minimumVisibility = 0.6, minimumPresence = 0.6)
        val result = gate.check(
            frame(timestampMs = 0L, x = 0.0, confidence = 0.4),
            requiredJoints = setOf(PoseJoint.LEFT_KNEE),
            coordinateSpace = PoseCoordinateSpace.NORMALIZED_IMAGE,
        )

        assertFalse(result.accepted)
        assertEquals(setOf(PoseJoint.LEFT_KNEE), result.lowConfidenceJoints)
        assertTrue(result.missingJoints.isEmpty())
    }

    @Test
    fun visibilityGateNeverFallsBackToAnotherCoordinateDomain() {
        val gate = PoseVisibilityGate()
        val normalizedOnly = frame(timestampMs = 0L, x = 0.4, confidence = 1.0)

        val worldResult = gate.check(
            frame = normalizedOnly,
            requiredJoints = setOf(PoseJoint.LEFT_KNEE),
            coordinateSpace = PoseCoordinateSpace.WORLD,
        )

        assertFalse(worldResult.accepted)
        assertEquals(PoseCoordinateSpace.WORLD, worldResult.coordinateSpace)
        assertEquals(setOf(PoseJoint.LEFT_KNEE), worldResult.missingJoints)
    }

    @Test
    fun feedbackRequiresPersistenceAndRespectsPerCodeCooldown() {
        val debouncer = PoseFeedbackDebouncer(persistenceMs = 600L, cooldownMs = 5_000L)
        val cue = PoseFeedback(PoseFeedbackCode.KEEP_CHEST_UP, "상체를 세워 주세요.")

        assertEquals(null, debouncer.update(cue, 0L))
        assertEquals(null, debouncer.update(cue, 599L))
        assertEquals(cue, debouncer.update(cue, 600L))
        assertEquals(null, debouncer.update(cue, 2_000L))

        // 오류가 잠시 해소됐다 재발해도 같은 코드의 cooldown은 유지한다.
        assertEquals(null, debouncer.update(null, 2_100L))
        assertEquals(null, debouncer.update(cue, 2_200L))
        assertEquals(null, debouncer.update(cue, 2_800L))
        assertEquals(cue, debouncer.update(cue, 5_600L))
    }

    private fun frame(timestampMs: Long, x: Double, confidence: Double): PoseFrame = PoseFrame(
        timestampMs = timestampMs,
        landmarks = mapOf(
            PoseJoint.LEFT_KNEE to PoseLandmark(
                x = x,
                y = 0.5,
                visibility = confidence,
                presence = confidence,
            ),
        ),
    )
}
