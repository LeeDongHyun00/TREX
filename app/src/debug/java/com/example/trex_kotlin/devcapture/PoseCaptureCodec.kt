package com.example.trex_kotlin.devcapture

import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import kotlin.math.roundToLong

/**
 * Line format for developer pose captures. Debug-only by construction: this file lives in the
 * debug source set, so neither the codec nor the recorder exists in a release build.
 *
 * A capture is one header line followed by one line per observed frame:
 *
 * ```
 * TREXCAP1\texercise=BARBELL_SQUAT\tjoints=33
 * F\t<timestampMs>\t<lock 0|1>\t<lateral 0|1>\t<index>:<x>,<y>,<z>,<vis>,<pres>\t...
 * ```
 *
 * Joints are written sparsely by MediaPipe index, so a frame that dropped a joint round-trips as
 * a frame that dropped a joint rather than as a fabricated coordinate. Only world landmarks are
 * recorded: they are the only coordinates the form-check track evaluates, and omitting the
 * normalised set halves the file for no loss of replay fidelity.
 *
 * A deliberately plain delimited format rather than JSON — the parser has to be obviously correct
 * and the project carries no JSON dependency.
 */
internal object PoseCaptureCodec {

    const val MAGIC: String = "TREXCAP1"

    /** Five decimals of a metre is 10 micrometres; far below any landmark's real precision. */
    private const val SCALE = 100_000.0

    private const val SEPARATOR = '\t'

    fun header(exerciseId: String): String {
        require(exerciseId.isNotBlank()) { "A capture must name its exercise" }
        require(exerciseId.none { it == SEPARATOR || it == '\n' }) {
            "Exercise id must not contain the field separator"
        }
        return "$MAGIC${SEPARATOR}exercise=$exerciseId${SEPARATOR}joints=${PoseJoint.entries.size}"
    }

    fun exerciseOf(headerLine: String): String? {
        val fields = headerLine.split(SEPARATOR)
        if (fields.firstOrNull() != MAGIC) return null
        return fields.drop(1)
            .firstOrNull { it.startsWith("exercise=") }
            ?.removePrefix("exercise=")
            ?.takeIf(String::isNotBlank)
    }

    fun encode(frame: PoseCaptureFrame): String = buildString {
        append('F').append(SEPARATOR)
        append(frame.timestampMs).append(SEPARATOR)
        append(if (frame.hasPrimaryPersonLock) '1' else '0').append(SEPARATOR)
        append(if (frame.lateralViewQualified) '1' else '0')
        for (joint in PoseJoint.entries) {
            val landmark = frame.worldLandmarks[joint] ?: continue
            append(SEPARATOR).append(joint.mediaPipeIndex).append(':')
            append(round(landmark.x)).append(',')
            append(round(landmark.y)).append(',')
            append(round(landmark.z)).append(',')
            append(round(landmark.visibility)).append(',')
            append(round(landmark.presence))
        }
    }

    /** Returns null for blank lines and for anything that is not a frame record. */
    fun decode(line: String): PoseCaptureFrame? {
        val fields = line.split(SEPARATOR)
        if (fields.size < 4 || fields[0] != "F") return null
        val timestampMs = fields[1].toLongOrNull() ?: return null
        val landmarks = LinkedHashMap<PoseJoint, PoseLandmark>()
        for (index in 4 until fields.size) {
            val field = fields[index]
            if (field.isBlank()) continue
            val separator = field.indexOf(':')
            if (separator <= 0) return null
            val joint = field.substring(0, separator).toIntOrNull()
                ?.let(PoseJoint::fromMediaPipeIndex) ?: return null
            val parts = field.substring(separator + 1).split(',')
            if (parts.size != 5) return null
            val values = parts.map { it.toDoubleOrNull() ?: return null }
            landmarks[joint] = PoseLandmark(
                x = values[0],
                y = values[1],
                z = values[2],
                visibility = values[3].coerceIn(0.0, 1.0),
                presence = values[4].coerceIn(0.0, 1.0),
            )
        }
        return PoseCaptureFrame(
            timestampMs = timestampMs,
            hasPrimaryPersonLock = fields[2] == "1",
            lateralViewQualified = fields[3] == "1",
            worldLandmarks = landmarks,
        )
    }

    private fun round(value: Double): Double = (value * SCALE).roundToLong() / SCALE
}

/**
 * One recorded observation: exactly the four inputs the form-check session consumes, so a replay
 * drives the session through the same door the camera does.
 */
internal data class PoseCaptureFrame(
    val timestampMs: Long,
    val hasPrimaryPersonLock: Boolean,
    val lateralViewQualified: Boolean,
    val worldLandmarks: Map<PoseJoint, PoseLandmark>,
) {
    /** The pose frame the session evaluates. Image coordinates are never part of a capture. */
    fun toPoseFrame(): PoseFrame = PoseFrame(
        timestampMs = timestampMs,
        landmarks = emptyMap(),
        worldLandmarks = worldLandmarks,
    )
}
