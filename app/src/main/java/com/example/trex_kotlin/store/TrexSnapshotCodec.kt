package com.example.trex_kotlin.store

/**
 * Turns a [TrexSnapshot] into a line-delimited text file and back.
 *
 * Why hand-rolled rather than a serialization library: the payload is a plan of a few rows, at most
 * a week of history and one onboarding answer set — single-digit kilobytes with one writer, one
 * reader, no query and no partial read. A serialization dependency would add a compiler plugin and
 * a transitive tree to a project whose strongest property is that it ships with no network stack at
 * all. The format below is the whole cost of avoiding that.
 *
 * ```
 * trex-store<TAB>1
 * flags<TAB>true<TAB>true<TAB>true
 * onboarding<TAB>lower<TAB>42<TAB>gym<TAB>false<TAB>7<TAB>male<TAB>178<TAB>72<TAB>29
 * plan<TAB>BARBELL_SQUAT<TAB>BARBELL_SQUAT<TAB>12회 x 3세트<TAB>8분<TAB>none<TAB>GOOD_MORNING<TAB>10회 x 3세트
 * day<TAB>수<TAB>8/13<TAB>18<TAB>140
 * item<TAB>BARBELL_SQUAT<TAB>12회 x 3세트<TAB>8<TAB>64
 * ```
 *
 * Two properties matter more than the shape:
 *
 *  - **[decode] never throws.** Every failure — a truncated write, a byte flip, a file from a
 *    future build, a field that is no longer a number — returns null, and the caller falls back to
 *    a fresh app. A persistence layer that can crash the launch path is worse than none.
 *  - **Unknown line prefixes are skipped, not rejected.** Diet records can be added later without
 *    a schema break, and a build that knew about them can be rolled back without stranding the
 *    file.
 *
 * A *known* row that does not parse fails the whole file rather than being dropped. Salvaging what
 * parsed would quietly lose a workout the user recorded and give no sign that anything went
 * missing; the store's atomic write is what makes a half-written file unreachable in the first
 * place, so this path is a backstop, not a routine.
 */
internal object TrexSnapshotCodec {

    private const val HEADER = "trex-store"
    private const val FLAGS = "flags"
    private const val ONBOARDING = "onboarding"
    private const val PLAN = "plan"
    private const val DAY = "day"
    private const val ITEM = "item"

    /**
     * The absent-value token. Unambiguous against real content because a literal backslash is
     * always written doubled, so no escaped user string can ever produce exactly `\-`.
     */
    private const val NULL_TOKEN = "\\-"

    private const val SEPARATOR = '\t'

    private const val MODE_NONE = "none"
    private const val MODE_GUIDE = "guide"
    private const val MODE_CHECK = "check"

    fun encode(snapshot: TrexSnapshot): String = buildString {
        appendRow(HEADER, TREX_SNAPSHOT_SCHEMA_VERSION.toString())
        appendRow(
            FLAGS,
            snapshot.guideDone.toString(),
            snapshot.loggedIn.toString(),
            snapshot.onboarded.toString(),
        )
        snapshot.onboarding?.let { answers ->
            appendRow(
                ONBOARDING,
                escape(answers.goalId),
                answers.dayMask.toString(),
                escape(answers.placeId),
                answers.bodyweightOnly.toString(),
                answers.equipmentMask.toString(),
                escape(answers.gender),
                escape(answers.height),
                escape(answers.weight),
                escape(answers.age),
            )
        }
        for (workout in snapshot.plan) {
            appendRow(
                PLAN,
                escape(workout.instanceId),
                escape(workout.exerciseId),
                escape(workout.reps),
                escape(workout.duration),
                encodeMode(workout.cameraMode),
                escape(workout.altExerciseId),
                escape(workout.altReps),
            )
        }
        for (day in snapshot.history) {
            appendRow(
                DAY,
                escape(day.dayLabel),
                escape(day.dateLabel),
                day.averageMinutes.toString(),
                day.averageCalories.toString(),
            )
            for (item in day.items) {
                appendRow(
                    ITEM,
                    escape(item.exerciseId),
                    escape(item.reps),
                    item.durationMinutes.toString(),
                    item.calories.toString(),
                )
            }
        }
    }

    /** Returns null for anything this build cannot faithfully reconstruct. Never throws. */
    fun decode(text: String): TrexSnapshot? = try {
        decodeOrThrow(text)
    } catch (_: Exception) {
        // Deliberately broad. The contract this upholds is "a damaged file degrades to a fresh
        // app", and every distinction between one corruption and another leads to the same place.
        null
    }

    private fun decodeOrThrow(text: String): TrexSnapshot? {
        val lines = text.split('\n').map { it.removeSuffix("\r") }.filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.split(SEPARATOR) ?: return null
        if (header.size != 2 || header[0] != HEADER) return null
        // An unreadable version is not a corruption; it is a newer build's file. Same outcome,
        // but worth keeping distinct from the malformed paths below.
        if (header[1].toIntOrNull() != TREX_SNAPSHOT_SCHEMA_VERSION) return null

        var guideDone = false
        var loggedIn = false
        var onboarded = false
        var onboarding: OnboardingAnswers? = null
        val plan = mutableListOf<PersistedWorkout>()
        val history = mutableListOf<PersistedHistoryDay>()
        val currentItems = mutableListOf<PersistedHistoryItem>()
        var currentDay: PersistedHistoryDay? = null

        fun closeDay() {
            currentDay?.let { history += it.copy(items = currentItems.toList()) }
            currentItems.clear()
            currentDay = null
        }

        for (line in lines.drop(1)) {
            val fields = line.split(SEPARATOR)
            when (fields[0]) {
                FLAGS -> {
                    require(fields.size == 4)
                    guideDone = parseBoolean(fields[1])
                    loggedIn = parseBoolean(fields[2])
                    onboarded = parseBoolean(fields[3])
                }

                ONBOARDING -> {
                    require(fields.size == 10)
                    onboarding = OnboardingAnswers(
                        goalId = required(fields[1]),
                        dayMask = parseInt(fields[2]),
                        placeId = required(fields[3]),
                        bodyweightOnly = parseBoolean(fields[4]),
                        equipmentMask = parseInt(fields[5]),
                        gender = required(fields[6]),
                        height = required(fields[7]),
                        weight = required(fields[8]),
                        age = required(fields[9]),
                    )
                }

                PLAN -> {
                    require(fields.size == 8)
                    plan += PersistedWorkout(
                        instanceId = required(fields[1]),
                        exerciseId = required(fields[2]),
                        reps = required(fields[3]),
                        duration = required(fields[4]),
                        cameraMode = decodeMode(fields[5]),
                        altExerciseId = unescape(fields[6]),
                        altReps = unescape(fields[7]),
                    )
                }

                DAY -> {
                    require(fields.size == 5)
                    closeDay()
                    currentDay = PersistedHistoryDay(
                        dayLabel = required(fields[1]),
                        dateLabel = required(fields[2]),
                        averageMinutes = parseInt(fields[3]),
                        averageCalories = parseInt(fields[4]),
                        items = emptyList(),
                    )
                }

                ITEM -> {
                    require(fields.size == 5)
                    // An item with no day above it means the file was assembled wrongly, not that
                    // the item is orphaned data worth keeping.
                    require(currentDay != null)
                    currentItems += PersistedHistoryItem(
                        exerciseId = required(fields[1]),
                        reps = required(fields[2]),
                        durationMinutes = parseInt(fields[3]),
                        calories = parseInt(fields[4]),
                    )
                }

                // Forward compatibility: a key this build does not know is not an error.
                else -> Unit
            }
        }
        closeDay()

        return TrexSnapshot(
            guideDone = guideDone,
            loggedIn = loggedIn,
            onboarded = onboarded,
            onboarding = onboarding,
            plan = plan.toList(),
            history = history.toList(),
        )
    }

    private fun StringBuilder.appendRow(vararg fields: String) {
        fields.joinTo(this, SEPARATOR.toString())
        append('\n')
    }

    private fun encodeMode(mode: PersistedCameraMode): String = when (mode) {
        PersistedCameraMode.None -> MODE_NONE
        PersistedCameraMode.Guide -> MODE_GUIDE
        PersistedCameraMode.FormCheck -> MODE_CHECK
    }

    private fun decodeMode(token: String): PersistedCameraMode = when (token) {
        MODE_NONE -> PersistedCameraMode.None
        MODE_GUIDE -> PersistedCameraMode.Guide
        MODE_CHECK -> PersistedCameraMode.FormCheck
        else -> throw IllegalArgumentException("unknown camera mode")
    }

    private fun parseBoolean(token: String): Boolean = when (token) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("not a boolean")
    }

    private fun parseInt(token: String): Int =
        token.toIntOrNull() ?: throw IllegalArgumentException("not an integer")

    private fun required(token: String): String =
        unescape(token) ?: throw IllegalArgumentException("required field was absent")

    private fun escape(value: String?): String {
        if (value == null) return NULL_TOKEN
        val out = StringBuilder(value.length)
        for (character in value) {
            when (character) {
                '\\' -> out.append("\\\\")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> out.append(character)
            }
        }
        return out.toString()
    }

    /** Null means the absent-value token; a malformed escape throws. */
    private fun unescape(token: String): String? {
        if (token == NULL_TOKEN) return null
        val out = StringBuilder(token.length)
        var index = 0
        while (index < token.length) {
            val character = token[index]
            if (character != '\\') {
                out.append(character)
                index++
                continue
            }
            index++
            if (index >= token.length) throw IllegalArgumentException("dangling escape")
            when (token[index]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                else -> throw IllegalArgumentException("unknown escape")
            }
            index++
        }
        return out.toString()
    }
}
