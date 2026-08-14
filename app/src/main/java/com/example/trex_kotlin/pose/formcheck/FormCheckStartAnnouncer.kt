package com.example.trex_kotlin.pose.formcheck

/**
 * Decides what to say out loud about the exercise's start conditions.
 *
 * A pure state machine so the wording is unit-testable: it returns the phrase to speak, or null
 * to stay quiet. The host session owns the actual speech, which keeps audio out of this track
 * and lets the user's mute switch work unchanged.
 *
 * Three suppressions, learned in that order on a real phone:
 *
 * 1. The same phrase is not repeated inside [repeatIntervalMs].
 * 2. A situation must persist for [stabilityMs] before it is spoken at all. The first cut
 *    deduplicated only against the immediately-previous phrase, and a lateral stance made the
 *    person lock flap once a second — every flip produced a "different" phrase, so the throttle
 *    never engaged and the guidance became a metronome. A flapping observation is measurement
 *    noise, not a situation, and noise is not announced.
 * 3. No two utterances land inside [minimumGapMs], whatever their wording. Even genuinely
 *    changing situations must not machine-gun the voice channel.
 *
 * The first transition into a started exercise is exempt from 2 and 3: it happens once, and it
 * is the confirmation the user is waiting for.
 *
 * Because a pending situation becomes speakable by time passing rather than by changing, the
 * caller polls: [onState] on every state change, and again after [retryDelayMs] when it returned
 * silence with something still pending.
 */
internal class FormCheckStartAnnouncer(
    private val repeatIntervalMs: Long = DEFAULT_REPEAT_INTERVAL_MS,
    private val stabilityMs: Long = DEFAULT_STABILITY_MS,
    private val minimumGapMs: Long = DEFAULT_MINIMUM_GAP_MS,
) {
    init {
        require(repeatIntervalMs > 0L) { "Repeat interval must be positive" }
        require(stabilityMs >= 0L) { "Stability must not be negative" }
        require(minimumGapMs >= 0L) { "The utterance gap must not be negative" }
    }

    private var lastPhrase: String? = null
    private var lastSpokenAtMs: Long = Long.MIN_VALUE
    private var pendingPhrase: String? = null
    private var pendingSinceMs: Long = 0L
    private var announcedFirstStart = false
    private var pauseAnnounced = false

    fun reset() {
        lastPhrase = null
        lastSpokenAtMs = Long.MIN_VALUE
        pendingPhrase = null
        pendingSinceMs = 0L
        announcedFirstStart = false
        pauseAnnounced = false
    }

    fun onState(
        timestampMs: Long,
        spec: FormCheckExercise,
        state: FormCheckUiState,
    ): String? {
        val phrase = phraseFor(spec, state)
        if (phrase == null) {
            pendingPhrase = null
            return null
        }
        if (phrase != pendingPhrase) {
            pendingPhrase = phrase
            pendingSinceMs = timestampMs
        }
        val firstStart = state.startState == FormCheckStartState.STARTED && !announcedFirstStart
        if (!firstStart) {
            if (timestampMs - pendingSinceMs < stabilityMs) return null
            if (lastSpokenAtMs != Long.MIN_VALUE && timestampMs - lastSpokenAtMs < minimumGapMs) {
                return null
            }
        }
        if (phrase == lastPhrase && timestampMs - lastSpokenAtMs < repeatIntervalMs) {
            return null
        }
        lastPhrase = phrase
        lastSpokenAtMs = timestampMs
        if (state.startState == FormCheckStartState.STARTED) {
            announcedFirstStart = true
            pauseAnnounced = false
        } else if (state.hasEverStarted) {
            pauseAnnounced = true
        }
        return phrase
    }

    /**
     * How long until the pending situation could become speakable by time alone, or null when
     * nothing is pending. The caller re-polls [onState] after this delay; a state change in the
     * meantime simply restarts the wait, which is the debounce doing its job.
     */
    fun retryDelayMs(timestampMs: Long): Long? {
        val phrase = pendingPhrase ?: return null
        var wait = stabilityMs - (timestampMs - pendingSinceMs)
        if (lastSpokenAtMs != Long.MIN_VALUE) {
            wait = maxOf(wait, minimumGapMs - (timestampMs - lastSpokenAtMs))
            if (phrase == lastPhrase) {
                wait = maxOf(wait, repeatIntervalMs - (timestampMs - lastSpokenAtMs))
            }
        }
        return maxOf(wait, MINIMUM_RETRY_MS)
    }

    private fun phraseFor(spec: FormCheckExercise, state: FormCheckUiState): String? =
        when (state.startState) {
            FormCheckStartState.WAITING_FOR_CAMERA -> null

            FormCheckStartState.WAITING_FOR_PERSON ->
                if (state.hasEverStarted) {
                    // Somebody who was being counted has not walked out to set up again: telling
                    // them to stand somewhere repeats an instruction they have already followed.
                    // What happened is that the track stopped counting, so that is what is said.
                    HeuristicFormCheckDeclaration.PAUSED_PERSON
                } else {
                    "화면에 한 사람만 보이게 서 주세요"
                }

            FormCheckStartState.WAITING_FOR_JOINTS -> {
                val missing = state.missingJoints
                val names = missing.joinToString(", ") { it.label }
                when {
                    missing.isEmpty() -> spec.setupHint.removeSuffix(".")
                    state.hasEverStarted ->
                        HeuristicFormCheckDeclaration.PAUSED_JOINT_PREFIX + names +
                            subjectParticle(names) +
                            HeuristicFormCheckDeclaration.PAUSED_JOINT_SUFFIX
                    else -> "${names}${subjectParticle(names)} 화면에 보이게 서 주세요"
                }
            }

            FormCheckStartState.STARTED -> when {
                !announcedFirstStart ->
                    if (state.preferredViewSuggested) {
                        // Names both the placement this exercise reads best and the joint it
                        // actually measures: telling a push-up about a knee would describe
                        // something the track never looked at, and asking a side lunge for a
                        // side view would ask for the one placement it cannot be read from.
                        "자세 체크를 시작할게요. ${spec.view.noteSubject} " +
                            "${viewNoteSubject(spec)} 더 잘 보여요"
                    } else {
                        "자세 체크를 시작할게요"
                    }

                // Only a pause the user actually heard about earns a resume announcement; a blip
                // too short to be spoken resumes as silently as it paused.
                pauseAnnounced -> HeuristicFormCheckDeclaration.RESUMED

                else -> null
            }
        }

    companion object {
        const val DEFAULT_REPEAT_INTERVAL_MS: Long = 8_000L

        /**
         * How long a situation must hold before it is announced. Longer than any one flap of a
         * marginal person lock, shorter than the moment a user starts wondering what is wrong.
         */
        const val DEFAULT_STABILITY_MS: Long = 1_200L

        /** The floor between any two utterances, whatever they say. */
        const val DEFAULT_MINIMUM_GAP_MS: Long = 2_500L

        /** The caller's re-poll is clamped so arithmetic near zero cannot spin it. */
        const val MINIMUM_RETRY_MS: Long = 50L

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

        /** "무릎이", "팔꿈치가", "엉덩이가" — the joint the preferred view would read more directly. */
        internal fun viewNoteSubject(spec: FormCheckExercise): String =
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
 * Speaks why an excursion was not counted — once per *distinct reason* per set.
 *
 * Silence is not a usable signal here: the phone may be muted, the room is loud, and an
 * uncounted repetition then feels identical to a crash. Saying each reason once tells a beginner
 * what happened — and with the definition gates there are genuinely different reasons in one
 * set, each worth its one sentence. Repeating a reason turns an observation into nagging, which
 * is how a track that makes no judgement starts to feel like one.
 *
 * Two reasons are the same when they differ only in the measured number: "무릎이 158도까지만…"
 * and "무릎이 162도까지만…" are one situation, not two. The wording is the headline the engine
 * already built, so nothing new is put in anyone's mouth.
 */
internal class FormCheckUncountedAnnouncer {
    private var lastSeen = 0
    private val spokenReasons = LinkedHashSet<String>()

    fun onUncounted(uncountedCount: Int, phrase: String?, muted: Boolean = false): String? {
        if (uncountedCount < lastSeen) {
            // A fresh set: the host rebuilds the session, so the per-set budget resets too.
            lastSeen = uncountedCount
            spokenReasons.clear()
        }
        if (uncountedCount <= lastSeen) return null
        lastSeen = uncountedCount
        if (muted || phrase.isNullOrBlank()) return null
        val reason = phrase.filterNot(Char::isDigit)
        if (reason in spokenReasons || spokenReasons.size >= MAX_DISTINCT_REASONS) return null
        spokenReasons.add(reason)
        return phrase
    }

    private companion object {
        /** However varied the set, the voice channel is not a lecture. */
        const val MAX_DISTINCT_REASONS = 3
    }
}
