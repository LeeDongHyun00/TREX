package com.example.trex_kotlin.pose.formcheck

import com.example.trex_kotlin.pose.runtime.PoseGravityReading
import java.io.File
import org.junit.Test

/**
 * Replays a corpus of external captures through the shipped session and writes what it counted.
 *
 * The repetition counter has never been measured. AI Hub cannot measure it -- sixteen sparse
 * keyframes and a clip-level scripted label leave "how many repetitions did it count" permanently
 * unanswerable there -- so the only counts anyone has seen are the ones a developer watched happen
 * on a phone. This harness is the other half of the answer: `tools/extract_mmfit_captures.py`
 * turns a labelled continuous workout into captures, and this replays them through
 * [HeuristicFormCheckSession] so the number under test is the shipped detector's, not a
 * reimplementation's.
 *
 * It is a test rather than a `main` because it must link the same internal engine the unit tests
 * link, and it is silent when the corpus is absent for the same reason
 * [LandmarkReplayTest.aRecordedCaptureFixtureReplaysWhenPresent] is: the repository does not
 * require body coordinates to be committed in order to build. The corpus lives under `data/`,
 * which is git-ignored, and nothing here writes into the source tree.
 *
 * Every capture is replayed against every mapped exercise, not only against the one it is a set
 * of, and the difference is where the interesting numbers live:
 *
 *  * a set replayed **as itself** yields the count to compare against the labelled repetitions;
 *  * a set replayed **as another exercise** says whether curls are counted as push-ups -- the
 *    confusion the definition gates exist to prevent, and which the policy already admits it
 *    cannot always prevent;
 *  * the **rest** captures -- the frames no labelled set claims, one file per contiguous gap --
 *    separate firing during standing about and walking between stations from firing on somebody
 *    else's exercise. A session-wide total confounds the two, and only one of them is a
 *    hysteresis fault. Each gap replays as its own session because a single concatenated file
 *    would put minutes between two adjacent frames, and the detector would rightly treat that
 *    jump as movement.
 *
 * None of this is available from a scripted dataset, and the roadmap requires it before a count
 * may stop being called "detected".
 */
class ExternalCaptureCorpusReplay {

    @Test
    fun replayExternalCaptureCorpusWhenPresent() {
        for (root in corpusRoots()) {
            replayCorpus(root)
        }
    }

    private fun replayCorpus(root: File) {
        val results = StringBuilder()
        results.append(
            "workout\tcapture\tspec\trole\tgravity\tframes\treps\tuncounted\tholdSeconds\t" +
                "countedAtMs\tuncountedAtMs\n"
        )

        val specs = mappedSpecs(root)
        val workouts = root.listFiles { file -> file.isDirectory }?.sortedBy(File::getName).orEmpty()
        for (workout in workouts) {
            val captures = workout.listFiles { file -> file.name.endsWith(".trexcap") }
                ?.sortedBy(File::getName).orEmpty()
            for (capture in captures) {
                // Only the header is read eagerly. The frames are streamed per replay, because a
                // full workout is a hundred megabytes of text and every spec replays it again.
                val declared = capture.useLines { it.firstOrNull() }
                    ?.let(LandmarkReplay::exerciseOf)
                val role = when {
                    capture.name.endsWith("_full.trexcap") -> "session"
                    REST_CAPTURE.containsMatchIn(capture.name) -> "rest"
                    else -> "set"
                }
                for (spec in specs) {
                    val specRole = if (role == "set" && spec.name != declared) "cross" else role
                    results.appendRow(
                        workout.name,
                        capture.name,
                        spec.name,
                        specRole,
                        "none",
                        LandmarkReplay.replay(spec, capture),
                    )
                    // The same replay with the sensor a fixed studio tripod would have had. Only
                    // the exercises carrying a posture clause can differ, and for those the gap
                    // between the two rows is the whole value of the device sensor, measured.
                    if (spec.posture != null) {
                        results.appendRow(
                            workout.name,
                            capture.name,
                            spec.name,
                            specRole,
                            "level-camera",
                            LandmarkReplay.replay(spec, capture, LEVEL_CAMERA_GRAVITY),
                        )
                    }
                }
            }
        }

        File(root, "replay-counts.tsv").writeText(results.toString())
    }

    private fun StringBuilder.appendRow(
        workout: String,
        capture: String,
        spec: String,
        role: String,
        gravity: String,
        result: LandmarkReplay.Result,
    ) {
        val state = result.finalState
        append(workout).append('\t')
            .append(capture).append('\t')
            .append(spec).append('\t')
            .append(role).append('\t')
            .append(gravity).append('\t')
            .append(result.frameCount).append('\t')
            .append(state.repCount).append('\t')
            .append(state.uncountedAttemptCount).append('\t')
            .append(state.holdSeconds).append('\t')
            // The moments, not just the tally: a corpus that says where each repetition was can
            // only score these against it if the replay says where it thought each one was.
            .append(result.countedAtMs.joinToString(",")).append('\t')
            .append(result.uncountedAtMs.joinToString(",")).append('\n')
    }

    /**
     * The specs the extractor mapped an activity onto, read back from its own index rather than
     * restated here -- one list, in one file, so the two halves cannot disagree about what was
     * measured.
     */
    private fun mappedSpecs(root: File): List<FormCheckExercise> {
        val names = root.walkTopDown()
            .filter { it.name.endsWith("_index.json") }
            .flatMap { file -> EXERCISE_FIELD.findAll(file.readText()).map { it.groupValues[1] } }
            .filter(String::isNotBlank)
            .toSortedSet()
        return FormCheckExercise.entries.filter { it.name in names }
    }

    /**
     * Every capture corpus present, one directory per dataset.
     *
     * Found by shape rather than named one by one, so adding a corpus is a matter of extracting
     * one — `data/<dataset>/captures` — with no code change here. `data/` is git-ignored, which is
     * why body coordinates may live there and why this returns nothing on a fresh clone.
     */
    private fun corpusRoots(): List<File> = listOf(File("../data"), File("data"))
        .firstOrNull(File::isDirectory)
        ?.listFiles { file -> file.isDirectory }
        ?.map { File(it, "captures") }
        ?.filter(File::isDirectory)
        ?.sortedBy(File::getPath)
        .orEmpty()

    private companion object {
        val EXERCISE_FIELD = Regex("\"exercise\"\\s*:\\s*\"([A-Z_]*)\"")

        /** One file per rest gap, so a replay never steps across a cut. */
        val REST_CAPTURE = Regex("_rest\\d+\\.trexcap$")

        /**
         * Gravity as a camera standing level on a tripod would report it: straight down the image,
         * wholly in the image plane.
         *
         * This is a claim about the recording, not about the dataset, and it is a modest one --
         * MM-Fit filmed from a fixed studio camera whose horizon does not move, and the landmark
         * frame is y-down. It is supplied on a second pass rather than in place of the sensorless
         * one because the sensorless pass is what a phone without the sensor actually does, and
         * the app discloses that it fails open there. Reporting only the favourable pass would
         * hide the path the policy warns about; reporting only the unfavourable one would blame
         * the detector for a signal the corpus withheld.
         */
        val LEVEL_CAMERA_GRAVITY: (Long) -> PoseGravityReading? = { timestampMs ->
            PoseGravityReading.of(x = 0.0, y = 1.0, outOfPlane = 0.0, timestampMs = timestampMs)
        }
    }
}
