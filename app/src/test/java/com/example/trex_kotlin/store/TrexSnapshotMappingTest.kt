package com.example.trex_kotlin.store

import com.example.trex_kotlin.Workout
import com.example.trex_kotlin.WorkoutAlt
import com.example.trex_kotlin.WorkoutHistoryDay
import com.example.trex_kotlin.WorkoutHistoryItem
import com.example.trex_kotlin.canUsePostureSession
import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.createWorkoutHistoryDay
import com.example.trex_kotlin.estimatedCalories
import com.example.trex_kotlin.pose.formcheck.FormCheckExercise
import com.example.trex_kotlin.todayPlan
import com.example.trex_kotlin.withCameraGuide
import com.example.trex_kotlin.withFormCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load direction is the one that needs sealing. Anything read off disk is a claim about a
 * previous build, and these tests pin the rules that stop such a claim from becoming authority the
 * store was never entitled to grant.
 */
class TrexSnapshotMappingTest {

    @Test
    fun theSeedPlanSurvivesTheRoundTrip() {
        val restored = todayPlan.toPersistedPlan().toWorkoutPlan()

        assertEquals(todayPlan.size, restored.size)
        for ((original, round) in todayPlan.zip(restored)) {
            assertEquals(original.exercise, round.exercise)
            assertEquals(original.reps, round.reps)
            assertEquals(original.duration, round.duration)
            assertEquals(original.instanceId, round.instanceId)
            assertEquals(original.alt?.exercise, round.alt?.exercise)
            assertEquals(original.alt?.reps, round.alt?.reps)
        }
    }

    @Test
    fun aCatalogAddedInstanceIdSurvivesUnchanged() {
        // Catalog-added workouts mint "${exercise.id}:${millis}", and the plan's edit paths match
        // on that string. Regenerating it from the exercise on load would orphan every edit.
        val minted = "push-up:1755000000000"
        val plan = listOf(
            Workout(
                exercise = AiHubExercise.PUSH_UP,
                reps = "8회 x 3세트",
                duration = "6분",
                posture = false,
                instanceId = minted,
            ),
        )

        assertEquals(minted, plan.toPersistedPlan().toWorkoutPlan().single().instanceId)
    }

    @Test
    fun aSwappedAlternativeKeepsItsOriginalInstanceId() {
        // After an alt swap the instance id and the exercise deliberately diverge. A round trip
        // that re-derived one from the other would undo the swap's identity.
        val original = todayPlan.first { it.alt != null }
        val alternative = checkNotNull(original.alt)
        val swapped = original.copy(
            exercise = alternative.exercise,
            reps = alternative.reps,
            alt = null,
        )

        val restored = listOf(swapped).toPersistedPlan().toWorkoutPlan().single()

        assertEquals(original.instanceId, restored.instanceId)
        assertEquals(swapped.exercise, restored.exercise)
        assertNull(restored.alt)
    }

    @Test
    fun noStoredPlanCanEverRestorePostureAuthority() {
        val plan = todayPlan.toPersistedPlan().toWorkoutPlan()

        for (workout in plan) {
            assertFalse("${workout.exercise} came back with posture on", workout.posture)
            assertFalse(workout.canUsePostureSession())
        }
    }

    @Test
    fun thePersistedShapeHasNoPostureFieldToRestoreFrom() {
        // The strongest form of the rule above: it is not that we set the flag false on load, it is
        // that the file has nowhere to write it. Pinned by reflection so that adding the field back
        // is a test failure rather than a review miss.
        val fields = PersistedWorkout::class.java.declaredFields
            .map { it.name }
            // The Compose compiler adds a static `$stable` to every class in the module.
            .filterNot { it.startsWith("$") }
            .toSet()

        assertEquals(
            setOf(
                "instanceId",
                "exerciseId",
                "reps",
                "duration",
                "cameraMode",
                "altExerciseId",
                "altReps",
            ),
            fields,
        )
    }

    @Test
    fun thePersistedHistoryItemCarriesNoPoseOutput() {
        // Policy §5-2 of docs/pose-heuristic-form-check.v1.md: repetition counts and observations
        // from the heuristic track stay on the session screen. `reps` here is the *planned* string
        // the user typed into the plan, which is what the history has always shown.
        val fields = PersistedHistoryItem::class.java.declaredFields
            .map { it.name }
            // The Compose compiler adds a static `$stable` to every class in the module.
            .filterNot { it.startsWith("$") }
            .toSet()

        assertEquals(setOf("exerciseId", "reps", "durationMinutes", "calories"), fields)
    }

    @Test
    fun bothCameraModesCannotBeRestoredAtOnce() {
        val supported = todayPlan.first { FormCheckExercise.supports(it.exercise) }

        val guided = listOf(supported.withCameraGuide(true)).toPersistedPlan().toWorkoutPlan().single()
        val checked = listOf(supported.withFormCheck(true)).toPersistedPlan().toWorkoutPlan().single()

        assertTrue(guided.cameraGuide)
        assertFalse(guided.formCheck)
        assertTrue(checked.formCheck)
        assertFalse(checked.cameraGuide)
    }

    @Test
    fun aStoredFormCheckForAnUnsupportedExerciseComesBackOff() {
        val unsupported = AiHubExercise.entries.first { !FormCheckExercise.supports(it) }
        val persisted = PersistedWorkout(
            instanceId = "id",
            exerciseId = unsupported.name,
            reps = "10회",
            duration = "5분",
            cameraMode = PersistedCameraMode.FormCheck,
            altExerciseId = null,
            altReps = null,
        )

        val restored = listOf(persisted).toWorkoutPlan().single()

        assertEquals(unsupported, restored.exercise)
        assertFalse("Support is re-checked on load, not trusted from disk", restored.formCheck)
    }

    @Test
    fun anExerciseThisBuildDoesNotKnowIsDroppedRatherThanGuessedAt() {
        val rows = listOf(
            PersistedWorkout("a", "AN_EXERCISE_THAT_WAS_RENAMED", "1", "1분", PersistedCameraMode.None, null, null),
            PersistedWorkout("b", AiHubExercise.PLANK.name, "60초", "5분", PersistedCameraMode.None, null, null),
        )

        val restored = rows.toWorkoutPlan()

        assertEquals(1, restored.size)
        assertEquals(AiHubExercise.PLANK, restored.single().exercise)
    }

    @Test
    fun anUnresolvableAlternativeDropsTheAlternativeNotTheWorkout() {
        val row = PersistedWorkout(
            instanceId = "a",
            exerciseId = AiHubExercise.PLANK.name,
            reps = "60초",
            duration = "5분",
            cameraMode = PersistedCameraMode.None,
            altExerciseId = "AN_EXERCISE_THAT_WAS_RENAMED",
            altReps = "12회",
        )

        val restored = listOf(row).toWorkoutPlan().single()

        assertEquals(AiHubExercise.PLANK, restored.exercise)
        assertNull(restored.alt)
    }

    @Test
    fun everyRestoredExerciseStillPricesItsCalories() {
        // estimatedCalories() calls error() on an unknown type_info.type. If the enum identity did
        // not round-trip exactly, this is where it would surface.
        val restored = todayPlan.toPersistedPlan().toWorkoutPlan()

        for (workout in restored) {
            assertTrue(workout.estimatedCalories() > 0)
        }
    }

    @Test
    fun aRecordedDaySurvivesTheRoundTrip() {
        val day = createWorkoutHistoryDay(todayPlan, elapsedSeconds = 1_200)

        val restored = listOf(day).toPersistedHistory().toWorkoutHistory().single()

        assertEquals(day.epochDay, restored.epochDay)
        assertEquals(day.dayLabel, restored.dayLabel)
        assertEquals(day.dateLabel, restored.dateLabel)
        assertEquals(day.averageMinutes, restored.averageMinutes)
        assertEquals(day.averageCalories, restored.averageCalories)
        assertEquals(day.items.size, restored.items.size)
        for ((original, round) in day.items.zip(restored.items)) {
            assertEquals(original.exercise, round.exercise)
            assertEquals(original.reps, round.reps)
            assertEquals(original.durationMinutes, round.durationMinutes)
            assertEquals(original.calories, round.calories)
        }
    }

    @Test
    fun noRestoredHistoryItemCarriesAPostureClaim() {
        val day = WorkoutHistoryDay(
            epochDay = 20_678L,
            items = listOf(WorkoutHistoryItem(AiHubExercise.PLANK, "60초", 5, 30)),
            averageMinutes = 5,
            averageCalories = 30,
        )

        val restored = listOf(day).toPersistedHistory().toWorkoutHistory().single()

        for (item in restored.items) {
            assertNull(item.postureCorrection)
        }
    }

    @Test
    fun aWorkoutAltIsOnlyRestoredWhenBothHalvesSurvived() {
        // Reps and exercise are stored as two nullable columns, so a file could carry one without
        // the other. WorkoutAlt requires both; a half-row must not become a half-built alternative.
        val row = PersistedWorkout(
            instanceId = "a",
            exerciseId = AiHubExercise.PLANK.name,
            reps = "60초",
            duration = "5분",
            cameraMode = PersistedCameraMode.None,
            altExerciseId = AiHubExercise.PUSH_UP.name,
            altReps = null,
        )

        assertNull(listOf(row).toWorkoutPlan().single().alt)
    }

    @Test
    fun anAlternativeThatSurvivedIntactIsRestored() {
        val expected = WorkoutAlt(AiHubExercise.PUSH_UP, "12회 x 3세트")
        val row = PersistedWorkout(
            instanceId = "a",
            exerciseId = AiHubExercise.PLANK.name,
            reps = "60초",
            duration = "5분",
            cameraMode = PersistedCameraMode.None,
            altExerciseId = expected.exercise.name,
            altReps = expected.reps,
        )

        val restored = listOf(row).toWorkoutPlan().single().alt

        assertEquals(expected.exercise, restored?.exercise)
        assertEquals(expected.reps, restored?.reps)
    }
}
