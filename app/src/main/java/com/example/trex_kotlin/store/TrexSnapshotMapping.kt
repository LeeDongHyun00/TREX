package com.example.trex_kotlin.store

import com.example.trex_kotlin.Workout
import com.example.trex_kotlin.WorkoutAlt
import com.example.trex_kotlin.WorkoutHistoryDay
import com.example.trex_kotlin.WorkoutHistoryItem
import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.withCameraGuide
import com.example.trex_kotlin.withFormCheck

/**
 * Maps between the in-memory model and the persisted one.
 *
 * The load direction is the one with rules. Anything read off disk is treated as a claim to be
 * re-checked against this build, never as state to restore verbatim:
 *
 *  - An exercise whose catalog constant no longer exists is **dropped**, not guessed at. The
 *    catalog is generated, so a renamed constant is a real possibility and a wrong guess would put
 *    a workout the user never chose into their plan.
 *  - A camera mode is applied through the same `withCameraGuide` / `withFormCheck` helpers the
 *    toggles use, so the exercise-support gate and the mutual exclusion are enforced by one code
 *    path rather than re-implemented here. A stored form check for an exercise that has since lost
 *    support comes back off.
 *  - Posture is not read from the file at all, because it is not written to it. Every restored
 *    workout starts with posture off and can only be turned on by the runtime facade, exactly as a
 *    freshly seeded one can.
 */

internal fun List<Workout>.toPersistedPlan(): List<PersistedWorkout> = map { workout ->
    PersistedWorkout(
        instanceId = workout.instanceId,
        exerciseId = workout.exercise.name,
        reps = workout.reps,
        duration = workout.duration,
        cameraMode = when {
            workout.formCheck -> PersistedCameraMode.FormCheck
            workout.cameraGuide -> PersistedCameraMode.Guide
            else -> PersistedCameraMode.None
        },
        altExerciseId = workout.alt?.exercise?.name,
        altReps = workout.alt?.reps,
    )
}

internal fun List<PersistedWorkout>.toWorkoutPlan(): List<Workout> = mapNotNull { persisted ->
    val exercise = findExercise(persisted.exerciseId) ?: return@mapNotNull null
    // An alternative that no longer resolves drops to null rather than dropping the whole row: the
    // workout itself is still exactly what the user planned.
    val alt = persisted.altExerciseId
        ?.let(::findExercise)
        ?.let { altExercise -> persisted.altReps?.let { WorkoutAlt(altExercise, it) } }

    val restored = Workout(
        exercise = exercise,
        reps = persisted.reps,
        duration = persisted.duration,
        posture = false,
        instanceId = persisted.instanceId,
        alt = alt,
    )

    when (persisted.cameraMode) {
        PersistedCameraMode.None -> restored
        PersistedCameraMode.Guide -> restored.withCameraGuide(true)
        PersistedCameraMode.FormCheck -> restored.withFormCheck(true)
    }
}

internal fun List<WorkoutHistoryDay>.toPersistedHistory(): List<PersistedHistoryDay> = map { day ->
    PersistedHistoryDay(
        dayLabel = day.dayLabel,
        dateLabel = day.dateLabel,
        averageMinutes = day.averageMinutes,
        averageCalories = day.averageCalories,
        items = day.items.map { item ->
            PersistedHistoryItem(
                exerciseId = item.exercise.name,
                reps = item.reps,
                durationMinutes = item.durationMinutes,
                calories = item.calories,
            )
        },
    )
}

internal fun List<PersistedHistoryDay>.toWorkoutHistory(): List<WorkoutHistoryDay> = map { day ->
    WorkoutHistoryDay(
        dayLabel = day.dayLabel,
        dateLabel = day.dateLabel,
        averageMinutes = day.averageMinutes,
        averageCalories = day.averageCalories,
        items = day.items.mapNotNull { item ->
            val exercise = findExercise(item.exerciseId) ?: return@mapNotNull null
            WorkoutHistoryItem(
                exercise = exercise,
                reps = item.reps,
                durationMinutes = item.durationMinutes,
                calories = item.calories,
            )
        },
    )
}

/**
 * Resolves a stored catalog identity, or null when this build has no such exercise.
 *
 * `AiHubExercise.valueOf` would throw, and a launch path is the wrong place to learn that the
 * catalog was regenerated.
 */
private fun findExercise(name: String): AiHubExercise? =
    AiHubExercise.entries.firstOrNull { it.name == name }
