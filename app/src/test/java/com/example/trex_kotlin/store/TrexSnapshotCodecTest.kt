package com.example.trex_kotlin.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec's contract is narrower than "it round-trips": it must round-trip *and* it must refuse
 * to guess. A persistence layer that silently reconstructs a plausible-looking partial plan from a
 * damaged file is worse than one that starts fresh, because the user cannot tell the two apart.
 */
class TrexSnapshotCodecTest {

    private fun populated() = TrexSnapshot(
        guideDone = true,
        loggedIn = true,
        onboarded = true,
        onboarding = OnboardingAnswers(
            goalId = "lower",
            dayMask = 42,
            placeId = "gym",
            bodyweightOnly = false,
            equipmentMask = 7,
            gender = "male",
            height = "178.5",
            weight = "72.0",
            age = "29",
        ),
        plan = listOf(
            PersistedWorkout(
                instanceId = "BARBELL_SQUAT",
                exerciseId = "BARBELL_SQUAT",
                reps = "12회 x 3세트",
                duration = "8분",
                cameraMode = PersistedCameraMode.None,
                altExerciseId = "GOOD_MORNING",
                altReps = "10회 x 3세트",
            ),
            PersistedWorkout(
                instanceId = "push-up:1755000000000",
                exerciseId = "PUSH_UP",
                reps = "8회 x 3세트",
                duration = "6분",
                cameraMode = PersistedCameraMode.FormCheck,
                altExerciseId = null,
                altReps = null,
            ),
        ),
        history = listOf(
            PersistedHistoryDay(
                epochDay = 20_677L,
                averageMinutes = 14,
                averageCalories = 96,
                items = listOf(
                    PersistedHistoryItem("BARBELL_SQUAT", "12회 x 3세트", 8, 64),
                    PersistedHistoryItem("PLANK", "60초 x 3세트", 5, 30),
                ),
            ),
            PersistedHistoryDay(
                epochDay = 20_678L,
                averageMinutes = 18,
                averageCalories = 140,
                items = listOf(PersistedHistoryItem("PUSH_UP", "8회 x 3세트", 6, 36)),
            ),
        ),
    )

    @Test
    fun aFullyPopulatedSnapshotSurvivesTheRoundTrip() {
        val snapshot = populated()

        assertEquals(snapshot, TrexSnapshotCodec.decode(TrexSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun anEmptySnapshotSurvivesTheRoundTrip() {
        val snapshot = TrexSnapshot()

        assertEquals(snapshot, TrexSnapshotCodec.decode(TrexSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun aDayWithNoItemsIsNotConfusedWithTheDayAfterIt() {
        val snapshot = TrexSnapshot(
            history = listOf(
                PersistedHistoryDay(20_677L, 0, 0, emptyList()),
                PersistedHistoryDay(20_678L, 18, 140, listOf(
                    PersistedHistoryItem("PUSH_UP", "8회", 6, 36),
                )),
            ),
        )

        val decoded = TrexSnapshotCodec.decode(TrexSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
        assertTrue(decoded!!.history.first().items.isEmpty())
    }

    @Test
    fun separatorsAndEscapesInsideUserTextDoNotCorruptTheRow() {
        // The reps field is free text the user typed. If any of these leaked through unescaped they
        // would silently split one field into several, or one row into several.
        val hostile = "12\treps\nline\\slash\r\\-\\t"
        val snapshot = TrexSnapshot(
            plan = listOf(
                PersistedWorkout(
                    instanceId = hostile,
                    exerciseId = "BARBELL_SQUAT",
                    reps = hostile,
                    duration = hostile,
                    cameraMode = PersistedCameraMode.Guide,
                    altExerciseId = null,
                    altReps = hostile,
                ),
            ),
        )

        val decoded = TrexSnapshotCodec.decode(TrexSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
        assertEquals(hostile, decoded!!.plan.single().reps)
    }

    @Test
    fun theAbsentMarkerIsDistinguishableFromTheTextThatLooksLikeIt() {
        // A user string of exactly "\-" must not come back as null. This is the one ambiguity the
        // escaping scheme exists to prevent.
        val snapshot = TrexSnapshot(
            plan = listOf(
                PersistedWorkout(
                    instanceId = "id",
                    exerciseId = "PLANK",
                    reps = "\\-",
                    duration = "5분",
                    cameraMode = PersistedCameraMode.None,
                    altExerciseId = null,
                    altReps = "\\-",
                ),
            ),
        )

        val decoded = TrexSnapshotCodec.decode(TrexSnapshotCodec.encode(snapshot))!!.plan.single()

        assertEquals("\\-", decoded.reps)
        assertEquals("\\-", decoded.altReps)
        assertNull(decoded.altExerciseId)
    }

    @Test
    fun everyCameraModeRoundTrips() {
        for (mode in PersistedCameraMode.entries) {
            val snapshot = TrexSnapshot(
                plan = listOf(
                    PersistedWorkout("id", "PLANK", "r", "d", mode, null, null),
                ),
            )

            assertEquals(
                mode,
                TrexSnapshotCodec.decode(TrexSnapshotCodec.encode(snapshot))!!.plan.single().cameraMode,
            )
        }
    }

    @Test
    fun damagedInputDecodesToNullRatherThanThrowing() {
        val header = "trex-store\t$TREX_SNAPSHOT_SCHEMA_VERSION\n"
        val damaged = listOf(
            "" to "empty file",
            "garbage" to "no header",
            "trex-store\n" to "header with no version",
            "trex-store\t9999\n" to "a newer build's file",
            "trex-store\tx\n" to "non-numeric version",
            header + "flags\ttrue\ttrue\n" to "flags row missing a field",
            header + "flags\tyes\ttrue\ttrue\n" to "flags row with a non-boolean",
            header + "plan\tid\tPLANK\tr\td\tsideways\t\\-\t\\-\n" to "unknown camera mode",
            header + "plan\tid\tPLANK\tr\td\tnone\t\\-\n" to "plan row missing a field",
            header + "day\t20678\tx\t140\n" to "non-numeric average",
            header + "day\tnotaday\t18\t140\n" to "non-numeric epoch day",
            header + "day\t20678\t18\n" to "day row missing a field",
            header + "item\tPLANK\tr\t5\t30\n" to "item with no day above it",
            header + "plan\tid\tPLANK\tr\\\td\tnone\t\\-\t\\-\n" to "dangling escape",
            header + "plan\tid\tPLANK\tr\\qd\td\tnone\t\\-\t\\-\n" to "unknown escape",
            header + "onboarding\tlower\t42\n" to "truncated onboarding row",
            header + "onboarding\tlower\tx\tgym\tfalse\t7\tmale\t1\t1\t1\n" to "non-numeric mask",
        )

        for ((input, why) in damaged) {
            assertNull(why, TrexSnapshotCodec.decode(input))
        }
    }

    @Test
    fun aHeaderWithNoRowsIsAnEmptySnapshotNotAFailure() {
        // Distinct from corruption: this is what a first save of a pristine app would produce.
        assertEquals(
            TrexSnapshot(),
            TrexSnapshotCodec.decode("trex-store\t$TREX_SNAPSHOT_SCHEMA_VERSION"),
        )
    }

    @Test
    fun anUnknownRowKeyIsSkippedSoLaterSchemasCanBeRolledBack() {
        val text = TrexSnapshotCodec.encode(populated()) + "water\t6\nmeal\tbreakfast\t\\-\n"

        val decoded = TrexSnapshotCodec.decode(text)

        assertNotNull(decoded)
        assertEquals(populated(), decoded)
    }

    @Test
    fun carriageReturnsFromAWindowsEditorDoNotBreakParsing() {
        val text = TrexSnapshotCodec.encode(populated()).replace("\n", "\r\n")

        assertEquals(populated(), TrexSnapshotCodec.decode(text))
    }

    @Test
    fun aTruncatedFileIsRefusedRatherThanPartiallyRestored() {
        // The realistic corruption is a process killed mid-write, which the store's temp-file and
        // rename is designed to make unreachable. Should it happen anyway, refusing the whole file
        // is the safe answer: a partial restore would drop a workout the user recorded and give no
        // sign that anything was lost.
        val full = TrexSnapshotCodec.encode(populated())

        assertNull(TrexSnapshotCodec.decode(full.dropLast(12)))
    }
}
