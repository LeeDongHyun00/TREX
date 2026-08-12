package com.example.trex_kotlin.pose.formcheck

import com.example.trex_kotlin.devcapture.PoseCaptureCodec
import com.example.trex_kotlin.devcapture.PoseCaptureFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the capture format and the replay harness together: a capture is only useful if what it
 * encodes is what the session later evaluates.
 */
class LandmarkReplayTest {

    @Test
    fun aFrameSurvivesTheRoundTripIncludingItsDroppedJoints() {
        val original = captureFrame(
            timestampMs = 1_234L,
            kneeAngleDegrees = 97.5,
            confidence = 0.83,
            hasLock = true,
            lateral = true,
            dropped = setOf(PoseJoint.LEFT_ANKLE),
        )

        val decoded = PoseCaptureCodec.decode(PoseCaptureCodec.encode(original))

        assertNotNull(decoded)
        assertEquals(original.timestampMs, decoded!!.timestampMs)
        assertEquals(original.hasPrimaryPersonLock, decoded.hasPrimaryPersonLock)
        assertEquals(original.lateralViewQualified, decoded.lateralViewQualified)
        assertEquals(
            "A dropped joint must stay dropped rather than becoming a coordinate",
            original.worldLandmarks.keys,
            decoded.worldLandmarks.keys,
        )
        for ((joint, landmark) in original.worldLandmarks) {
            val replayed = decoded.worldLandmarks.getValue(joint)
            assertEquals(landmark.x, replayed.x, 1e-4)
            assertEquals(landmark.y, replayed.y, 1e-4)
            assertEquals(landmark.z, replayed.z, 1e-4)
            assertEquals(landmark.confidence, replayed.confidence, 1e-4)
        }
    }

    @Test
    fun theHeaderNamesTheExerciseAndNonFrameLinesAreIgnored() {
        val capture = PoseCaptureCodec.header("STEP_FORWARD_DYNAMIC_LUNGE") + "\n\n"

        assertEquals("STEP_FORWARD_DYNAMIC_LUNGE", LandmarkReplay.exerciseOf(capture))
        assertTrue(LandmarkReplay.parse(capture).isEmpty())
    }

    @Test
    fun malformedFramesDecodeToNullRatherThanAGuess() {
        assertNull(PoseCaptureCodec.decode("F\tnot-a-timestamp\t1\t1"))
        assertNull(PoseCaptureCodec.decode("F\t10\t1\t1\t25:1.0,2.0"))
        assertNull(PoseCaptureCodec.decode("F\t10\t1\t1\t99:1,2,3,1,1"))
    }

    @Test
    fun replayingACapturedRepReproducesTheCountItProducedLive() {
        val spec = FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE
        // One full excursion below the 134-degree rep line and back to the top.
        val angles = listOf(175.0, 175.0, 128.0, 120.0, 120.0, 120.0, 160.0, 175.0, 175.0)
        val capture = synthesiseCapture(spec, angles, stepMs = 200L)

        val result = LandmarkReplay.replay(spec, capture)

        assertEquals(spec.name, result.exerciseId)
        assertEquals(angles.size, result.frameCount)
        assertEquals(1, result.finalState.repCount)
        assertTrue(result.finalState.started)
    }

    @Test
    fun aCaptureThatLosesTheLockMidRepReplaysAsNoCount() {
        val spec = FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE
        val frames = mutableListOf<PoseCaptureFrame>()
        var t = 0L
        for ((index, angle) in listOf(175.0, 175.0, 120.0, 120.0, 175.0, 175.0).withIndex()) {
            frames += captureFrame(
                timestampMs = t,
                kneeAngleDegrees = angle,
                confidence = 0.9,
                // The lock drops at the bottom of the excursion.
                hasLock = index != 3,
                lateral = true,
            )
            t += 200L
        }

        val result = LandmarkReplay.replay(spec, render(spec, frames))

        assertEquals(0, result.finalState.repCount)
    }

    @Test
    fun aRecordedCaptureFixtureReplaysWhenPresent() {
        // Real recordings are dropped into src/test/resources/formcheck by a developer; the
        // harness must load them without a code change. Absent one, this test is a no-op rather
        // than a failure, so the repository does not require committing body data.
        val capture = LandmarkReplay.load("step-forward-lunge-sample.trexcap") ?: return
        val exercise = LandmarkReplay.exerciseOf(capture)
        assertNotNull("A recorded capture must carry its header", exercise)
        val spec = FormCheckExercise.entries.first { it.name == exercise }

        val result = LandmarkReplay.replay(spec, capture)

        assertTrue("A recorded capture must contain frames", result.frameCount > 0)
    }

    // ---- fixtures ----

    private fun render(spec: FormCheckExercise, frames: List<PoseCaptureFrame>): String =
        buildString {
            append(PoseCaptureCodec.header(spec.name)).append('\n')
            for (frame in frames) append(PoseCaptureCodec.encode(frame)).append('\n')
        }

    /** Builds a capture whose knee traces [angles] at a fixed cadence. */
    private fun synthesiseCapture(
        spec: FormCheckExercise,
        angles: List<Double>,
        stepMs: Long,
    ): String {
        var t = 0L
        val frames = angles.map { angle ->
            captureFrame(t, angle, confidence = 0.9, hasLock = true, lateral = true)
                .also { t += stepMs }
        }
        return render(spec, frames)
    }

    /** A leg chain on both sides bent to [kneeAngleDegrees], minus any [dropped] joints. */
    private fun captureFrame(
        timestampMs: Long,
        kneeAngleDegrees: Double,
        confidence: Double,
        hasLock: Boolean,
        lateral: Boolean,
        dropped: Set<PoseJoint> = emptySet(),
    ): PoseCaptureFrame {
        val radians = Math.toRadians(kneeAngleDegrees)
        fun chain(
            hip: PoseJoint,
            knee: PoseJoint,
            ankle: PoseJoint,
            offsetX: Double,
        ): Map<PoseJoint, PoseLandmark> = mapOf(
            knee to PoseLandmark(offsetX, 0.0, 0.0, confidence, confidence),
            hip to PoseLandmark(offsetX, 0.4, 0.0, confidence, confidence),
            ankle to PoseLandmark(
                offsetX + 0.4 * sin(radians),
                0.4 * cos(radians),
                0.0,
                confidence,
                confidence,
            ),
        )

        val world = chain(
            PoseJoint.LEFT_HIP, PoseJoint.LEFT_KNEE, PoseJoint.LEFT_ANKLE, -0.1,
        ) + chain(
            PoseJoint.RIGHT_HIP, PoseJoint.RIGHT_KNEE, PoseJoint.RIGHT_ANKLE, 0.1,
        )
        return PoseCaptureFrame(
            timestampMs = timestampMs,
            hasPrimaryPersonLock = hasLock,
            lateralViewQualified = lateral,
            worldLandmarks = world.filterKeys { it !in dropped },
        )
    }
}
