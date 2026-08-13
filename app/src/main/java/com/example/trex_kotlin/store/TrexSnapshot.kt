package com.example.trex_kotlin.store

/**
 * The persisted shape of everything the app remembers between launches.
 *
 * This is a deliberately separate model from the in-memory one rather than a set of annotations on
 * it. Two reasons, both of which the in-memory types could not give us:
 *
 *  - **A field that does not exist cannot be restored wrongly.** [PersistedWorkout] carries no
 *    posture flag at all, so no stored byte can ever open a posture-correction session; the runtime
 *    facade stays the only thing that decides that. `Workout`'s own init block throws when a
 *    posture workout is not released, which would turn a stale file into a launch crash.
 *  - **An invalid combination is unrepresentable.** The camera guide and the form check are
 *    mutually exclusive in the UI, enforced by two setters that clear each other. Storing them as
 *    two booleans would let a hand-edited or truncated file assert both; [PersistedCameraMode]
 *    makes that state impossible to write down.
 *
 * Nothing pose-derived appears here. Repetition counts and observations from the heuristic form
 * check stay on the session screen, per §5-2 of `docs/pose-heuristic-form-check.v1.md`, which this
 * change deliberately leaves untouched. The repetition strings below are the *planned* ones the
 * user typed into the plan ("12회 x 3세트"), not anything a detector produced.
 */
internal const val TREX_SNAPSHOT_SCHEMA_VERSION = 2

/** Which camera layer a planned workout opens with, if any. At most one, by construction. */
internal enum class PersistedCameraMode {
    None,
    Guide,
    FormCheck,
}

/**
 * One row of the user's plan.
 *
 * [exerciseId] is the [com.example.trex_kotlin.catalog.AiHubExercise] enum constant name rather
 * than its kebab-case `id`, because the constant name is the catalog's identity: the generator
 * rewrites the file wholesale, and a row whose constant vanished must be dropped rather than
 * guessed at.
 *
 * [instanceId] is stored separately from [exerciseId] on purpose. They diverge in normal use — a
 * workout swapped for its alternative keeps the original instance id, and a catalog-added workout
 * mints `"${exercise.id}:${millis}"` — and the plan's edit paths match on it.
 */
internal data class PersistedWorkout(
    val instanceId: String,
    val exerciseId: String,
    val reps: String,
    val duration: String,
    val cameraMode: PersistedCameraMode,
    val altExerciseId: String?,
    val altReps: String?,
)

/** One finished workout inside a day's record. Duration and calories only; no pose output. */
internal data class PersistedHistoryItem(
    val exerciseId: String,
    val reps: String,
    val durationMinutes: Int,
    val calories: Int,
)

/**
 * One day of history, keyed by its epoch day.
 *
 * Schema 1 stored the rendered labels (`수`, `8/13`) instead. That lost the year, so a record could
 * not be aged, ordered across a year boundary, or told apart from the same calendar date twelve
 * months earlier. Storing the day itself and deriving the labels removes all three, and lets a
 * screen say whether what it is showing is really "this week".
 */
internal data class PersistedHistoryDay(
    val epochDay: Long,
    val averageMinutes: Int,
    val averageCalories: Int,
    val items: List<PersistedHistoryItem>,
)

/**
 * What onboarding asked for. Today every one of these answers is collected and then dropped on the
 * floor at `onDone()`, which is why the diet tab hardcodes a 170cm / 65kg body to compute a goal.
 *
 * Heights, weights and ages are kept as the strings the user typed. Parsing them here would throw
 * away the distinction between "not answered" and "answered zero", and nothing in this layer needs
 * them as numbers.
 */
internal data class OnboardingAnswers(
    val goalId: String,
    val dayMask: Int,
    val placeId: String,
    val bodyweightOnly: Boolean,
    val equipmentMask: Int,
    val gender: String,
    val height: String,
    val weight: String,
    val age: String,
)

/**
 * Everything persisted, in one value.
 *
 * Every field defaults, so a snapshot decoded from a file written by an older build that knew
 * fewer keys still constructs.
 */
internal data class TrexSnapshot(
    val guideDone: Boolean = false,
    val loggedIn: Boolean = false,
    val onboarded: Boolean = false,
    val onboarding: OnboardingAnswers? = null,
    val plan: List<PersistedWorkout> = emptyList(),
    val history: List<PersistedHistoryDay> = emptyList(),
)
