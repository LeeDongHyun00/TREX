package com.example.trex_kotlin.pose.formcheck

import com.example.trex_kotlin.devcapture.PoseCaptureCodec
import com.example.trex_kotlin.devcapture.PoseCaptureFrame
import java.io.File

/**
 * Replays a developer capture through a form-check session on the JVM.
 *
 * This is the regression net that stands in for a backend: a captured movement is fed back in
 * through the same four inputs the camera supplies, so a threshold or detector change that would
 * alter what a real user saw shows up as a changed count here rather than in the field.
 *
 * Captures are recorded by `DevPoseCapture` and live under `src/test/resources/formcheck`.
 */
internal object LandmarkReplay {

    /** Outcome of replaying one capture. */
    internal class Result(
        val exerciseId: String?,
        val frameCount: Int,
        val finalState: FormCheckUiState,
    )

    /** Parses a capture, ignoring the header and any blank trailing line. */
    fun parse(capture: String): List<PoseCaptureFrame> =
        capture.lineSequence().mapNotNull(PoseCaptureCodec::decode).toList()

    fun exerciseOf(capture: String): String? =
        capture.lineSequence().firstOrNull()?.let(PoseCaptureCodec::exerciseOf)

    /** Loads a capture from `src/testDebug/resources/formcheck/<name>`, or null when absent. */
    fun load(name: String): String? {
        val candidates = listOf(
            File("src/testDebug/resources/formcheck/$name"),
            File("app/src/testDebug/resources/formcheck/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
    }

    /**
     * Drives a fresh session over [capture]. Frames are delivered in file order; the session's own
     * abstention rules handle lock loss and timestamp regressions exactly as they do live.
     */
    fun replay(spec: FormCheckExercise, capture: String): Result {
        val frames = parse(capture)
        val session = HeuristicFormCheckSession(spec)
        var state = session.initialSnapshot()
        for (frame in frames) {
            state = session.accept(
                timestampMs = frame.timestampMs,
                hasPrimaryPersonLock = frame.hasPrimaryPersonLock,
                preferredViewQualified = frame.lateralViewQualified,
                frame = frame.toPoseFrame(),
            )
        }
        return Result(
            exerciseId = exerciseOf(capture),
            frameCount = frames.size,
            finalState = state,
        )
    }
}
