package com.example.trex_kotlin.pose.formcheck

/**
 * Decides what to say out loud about the exercise's start conditions.
 *
 * A pure state machine so the wording is unit-testable: it returns the phrase to speak, or null
 * to stay quiet. The host session owns the actual speech, which keeps audio out of this track
 * and lets the user's mute switch work unchanged.
 *
 * Repetition is suppressed — the same phrase is not repeated inside [repeatIntervalMs], and a
 * phrase only interrupts silence when the situation actually changed.
 */
internal class FormCheckStartAnnouncer(
    private val repeatIntervalMs: Long = DEFAULT_REPEAT_INTERVAL_MS,
) {
    init {
        require(repeatIntervalMs > 0L) { "Repeat interval must be positive" }
    }

    private var lastPhrase: String? = null
    private var lastSpokenAtMs: Long = Long.MIN_VALUE

    fun reset() {
        lastPhrase = null
        lastSpokenAtMs = Long.MIN_VALUE
    }

    fun onState(
        timestampMs: Long,
        spec: FormCheckExercise,
        state: FormCheckUiState,
    ): String? {
        val phrase = phraseFor(spec, state) ?: return null
        val repeated = phrase == lastPhrase
        if (repeated && timestampMs - lastSpokenAtMs < repeatIntervalMs) {
            return null
        }
        lastPhrase = phrase
        lastSpokenAtMs = timestampMs
        return phrase
    }

    private fun phraseFor(spec: FormCheckExercise, state: FormCheckUiState): String? =
        when (state.startState) {
            FormCheckStartState.WAITING_FOR_CAMERA -> null

            FormCheckStartState.WAITING_FOR_PERSON ->
                "화면에 한 사람만 보이게 서 주세요"

            FormCheckStartState.WAITING_FOR_JOINTS -> {
                val missing = state.missingJoints
                if (missing.isEmpty()) {
                    "${spec.setupHint.removeSuffix(".")}"
                } else {
                    val names = missing.joinToString(", ") { it.label }
                    "${names}${subjectParticle(names)} 화면에 보이게 서 주세요"
                }
            }

            FormCheckStartState.STARTED ->
                if (state.sideViewPreferred) {
                    // Names the joint this exercise actually measures: telling a push-up about a
                    // knee would describe something the track never looked at.
                    "자세 체크를 시작할게요. 옆모습으로 서면 ${sideViewSubject(spec)} 더 잘 보여요"
                } else {
                    "자세 체크를 시작할게요"
                }
        }

    companion object {
        const val DEFAULT_REPEAT_INTERVAL_MS: Long = 8_000L

        /** Spoken once per counted repetition: "1회", "2회", … */
        internal fun countPhrase(repCount: Int): String = "${repCount}회"

        /**
         * The count with the set's own comparison attached, when there is one worth speaking.
         *
         * A user standing side-on to a phone on the floor cannot read the screen, so speech is
         * the only channel that reaches them mid-set — and the comparison against their own
         * opening repetitions is the one thing this track can say that is both actionable and
         * free of any norm. [FormCheckBaselineRelation.SAME] covers "no baseline yet" as well as
         * "inside the fifteen-degree floor", and both are silences the wording already owes: the
         * count goes out alone rather than claiming a similarity nobody measured.
         */
        internal fun countPhrase(
            repCount: Int,
            relation: FormCheckBaselineRelation,
            vocabulary: FormCheckVocabulary,
        ): String {
            val clause = when (relation) {
                FormCheckBaselineRelation.SAME -> return countPhrase(repCount)
                FormCheckBaselineRelation.BELOW -> vocabulary.belowBaselinePhrase
                FormCheckBaselineRelation.BEYOND -> vocabulary.beyondBaselinePhrase
            }
            return "${countPhrase(repCount)} · 첫 반복보다 $clause"
        }

        /** "무릎이", "팔꿈치가", "엉덩이가" — the joint the side view would read more directly. */
        internal fun sideViewSubject(spec: FormCheckExercise): String =
            spec.driver.vertex.label.let { label -> label + subjectParticle(label) }

        /** Korean subject particle: 이 after a final consonant, 가 otherwise. */
        internal fun subjectParticle(word: String): String {
            val last = word.lastOrNull() ?: return "가"
            if (last !in HANGUL_FIRST..HANGUL_LAST) return "가"
            val hasFinalConsonant = (last - HANGUL_FIRST) % HANGUL_FINAL_COUNT != 0
            return if (hasFinalConsonant) "이" else "가"
        }

        private const val HANGUL_FIRST = '가'
        private const val HANGUL_LAST = '힣'
        private const val HANGUL_FINAL_COUNT = 28
    }
}

/**
 * Decides when the running repetition count is spoken.
 *
 * A count is announced exactly once, when it first appears. The dedup key is the count itself
 * rather than a clock, because each phrase is distinct and a repetition can legitimately follow
 * the previous one inside any fixed interval. While muted or paused a new count is consumed
 * silently — announcing it later would attribute the number to the wrong moment.
 */
internal class FormCheckCountAnnouncer {
    private var lastAnnounced = 0

    fun onCount(repCount: Int, muted: Boolean = false): String? =
        onCount(repCount, FormCheckBaselineRelation.SAME, null, muted)

    /**
     * The count, with the set's own comparison when the latest repetition earned one.
     *
     * [vocabulary] is null for a caller that has no comparison to make, which keeps the bare
     * count path identical to what it was.
     */
    fun onCount(
        repCount: Int,
        relation: FormCheckBaselineRelation,
        vocabulary: FormCheckVocabulary?,
        muted: Boolean,
    ): String? {
        if (repCount <= lastAnnounced) {
            // The host resets the session per set; a smaller count is a fresh set, not a repeat.
            if (repCount < lastAnnounced) lastAnnounced = repCount
            return null
        }
        lastAnnounced = repCount
        if (muted) return null
        return if (vocabulary == null) {
            FormCheckStartAnnouncer.countPhrase(repCount)
        } else {
            FormCheckStartAnnouncer.countPhrase(repCount, relation, vocabulary)
        }
    }
}

/**
 * Speaks why an excursion was not counted — once per set, on the first one.
 *
 * Silence is not a usable signal here: the phone may be muted, the room is loud, and an
 * uncounted repetition then feels identical to a crash. Saying it once tells a beginner what
 * happened; saying it every time turns an observation into nagging, which is how a track that
 * makes no judgement starts to feel like one. The wording is the headline the engine already
 * built, so nothing new is put in anyone's mouth.
 */
internal class FormCheckUncountedAnnouncer {
    private var lastSeen = 0
    private var spoken = false

    fun onUncounted(uncountedCount: Int, phrase: String?, muted: Boolean = false): String? {
        if (uncountedCount < lastSeen) {
            // A fresh set: the host rebuilds the session, so the once-per-set budget resets too.
            lastSeen = uncountedCount
            spoken = false
        }
        if (uncountedCount <= lastSeen) return null
        lastSeen = uncountedCount
        if (spoken || muted || phrase.isNullOrBlank()) return null
        spoken = true
        return phrase
    }
}
