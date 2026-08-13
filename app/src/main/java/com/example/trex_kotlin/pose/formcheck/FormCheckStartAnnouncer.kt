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

    fun onCount(repCount: Int, muted: Boolean = false): String? {
        if (repCount <= lastAnnounced) {
            // The host resets the session per set; a smaller count is a fresh set, not a repeat.
            if (repCount < lastAnnounced) lastAnnounced = repCount
            return null
        }
        lastAnnounced = repCount
        return if (muted) null else FormCheckStartAnnouncer.countPhrase(repCount)
    }
}
