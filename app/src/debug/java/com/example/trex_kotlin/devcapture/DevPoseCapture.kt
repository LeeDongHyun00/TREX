package com.example.trex_kotlin.devcapture

import android.content.Context
import com.example.trex_kotlin.pose.PoseFrame
import java.io.File
import java.util.concurrent.Executors

/**
 * Developer-only landmark recorder. The debug implementation.
 *
 * It exists so threshold and detector changes can be regression-tested against real movement
 * without a backend: a captured session replays into [com.example.trex_kotlin.pose.formcheck]
 * on the JVM and must reproduce the counts it produced live.
 *
 * Three properties keep it from becoming a data-collection surface:
 *
 *  * it lives in the debug source set, so the release build links the no-op twin instead;
 *  * [ENABLED] is off by default, so even a debug build records nothing until a developer
 *    deliberately flips it and rebuilds;
 *  * it stores world landmarks only. No image, no video, no frame buffer ever reaches disk.
 *
 * Encoding happens on [end], not per frame, because observations arrive on the main thread.
 */
internal object DevPoseCapture {

    /**
     * Flip to true locally to capture a session, then rebuild the debug variant. Committed as
     * false so that installing a debug build never starts writing body coordinates.
     */
    const val ENABLED: Boolean = false

    /** Bounds a runaway session at roughly ten minutes of 30fps observation. */
    private const val MAXIMUM_BUFFERED_FRAMES = 20_000

    private const val OUTPUT_DIRECTORY = "pose-capture"

    private val writer = Executors.newSingleThreadExecutor()

    private var outputDirectory: File? = null
    private var exerciseId: String? = null
    private val buffer = ArrayList<PoseCaptureFrame>()

    val isEnabled: Boolean get() = ENABLED

    /** Starts a fresh capture, discarding anything buffered but not yet flushed. */
    @Synchronized
    fun begin(context: Context, exerciseId: String) {
        if (!ENABLED) return
        val directory = context.getExternalFilesDir(OUTPUT_DIRECTORY) ?: context.filesDir
        outputDirectory = directory
        this.exerciseId = exerciseId
        buffer.clear()
    }

    /**
     * Buffers one observation. Called on the main thread, so this only copies references —
     * the pose frame's landmark map is already an immutable snapshot.
     */
    @Synchronized
    fun record(
        timestampMs: Long,
        hasPrimaryPersonLock: Boolean,
        lateralViewQualified: Boolean,
        frame: PoseFrame,
    ) {
        if (!ENABLED || exerciseId == null) return
        if (buffer.size >= MAXIMUM_BUFFERED_FRAMES) return
        buffer.add(
            PoseCaptureFrame(
                timestampMs = timestampMs,
                hasPrimaryPersonLock = hasPrimaryPersonLock,
                lateralViewQualified = lateralViewQualified,
                worldLandmarks = frame.worldLandmarks,
            ),
        )
    }

    /** Flushes the buffered capture to disk off the main thread and clears the session. */
    @Synchronized
    fun end() {
        if (!ENABLED) return
        val exercise = exerciseId ?: return
        val directory = outputDirectory
        val frames = ArrayList(buffer)
        buffer.clear()
        exerciseId = null
        outputDirectory = null
        if (directory == null || frames.isEmpty()) return

        // The first frame timestamp names the file: monotonic within a session and carrying no
        // wall-clock, location or identity.
        val name = "$exercise-${frames.first().timestampMs}.trexcap"
        writer.execute {
            runCatching {
                directory.mkdirs()
                File(directory, name).bufferedWriter().use { sink ->
                    sink.append(PoseCaptureCodec.header(exercise))
                    sink.append('\n')
                    for (frame in frames) {
                        sink.append(PoseCaptureCodec.encode(frame))
                        sink.append('\n')
                    }
                }
            }
        }
    }
}
