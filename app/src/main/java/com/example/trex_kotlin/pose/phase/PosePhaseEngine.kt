package com.example.trex_kotlin.pose.phase

import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import java.util.Collections

/** Device-safety ceiling independent of any remotely supplied exercise policy. */
internal const val MAXIMUM_SUPPORTED_PHASE_DURATION_MS: Long = 120_000L
internal const val MAXIMUM_SUPPORTED_CYCLE_DURATION_MS: Long = 300_000L
internal const val MAXIMUM_TRACKED_CYCLE_PHASE_WINDOWS: Int = 64

/** Stable, model-independent identifier supplied by an offline phase specification. */
data class PosePhaseStateId(val value: String) {
    init {
        require(value.isNotBlank()) { "A phase state id must not be blank" }
    }

    override fun toString(): String = value
}

/** Inclusive scalar interval in the unit declared by the phase feature contract. */
data class PhaseScalarInterval(
    val lower: Double,
    val upper: Double,
) {
    init {
        require(lower.isFinite() && upper.isFinite()) {
            "Phase interval bounds must be finite"
        }
        require(lower <= upper) {
            "Phase interval lower bound must not exceed its upper bound"
        }
    }

    operator fun contains(value: Double): Boolean = value in lower..upper

    internal fun contains(other: PhaseScalarInterval): Boolean =
        lower <= other.lower && upper >= other.upper
}

enum class PhaseScalarDirection {
    ANY,
    INCREASING,
    DECREASING,
}

/**
 * Evidence contract for entering one phase.
 *
 * [enterInterval] is deliberately narrower than [holdInterval]. Once entry evidence starts,
 * values may move inside the wider hold interval while the minimum dwell is collected. This is
 * the phase-level hysteresis that prevents threshold jitter from repeatedly starting candidates.
 * [directionTolerance] also permits a small reversal while a candidate is being sustained, but a
 * transition can only start after motion in the requested direction has actually been observed.
 */
data class PosePhaseEnterPredicate(
    val enterInterval: PhaseScalarInterval,
    val holdInterval: PhaseScalarInterval = enterInterval,
    val direction: PhaseScalarDirection = PhaseScalarDirection.ANY,
    val directionTolerance: Double = 0.0,
    val minimumDwellMs: Long = 0L,
) {
    init {
        require(holdInterval.contains(enterInterval)) {
            "The phase hold interval must contain its enter interval"
        }
        require(directionTolerance.isFinite() && directionTolerance >= 0.0) {
            "directionTolerance must be finite and non-negative"
        }
        require(minimumDwellMs >= 0L) { "minimumDwellMs must be non-negative" }
    }

    internal fun canStart(value: Double, delta: Double?): Boolean {
        if (value !in enterInterval) return false
        return when (direction) {
            PhaseScalarDirection.ANY -> true
            PhaseScalarDirection.INCREASING -> delta != null && delta > directionTolerance
            PhaseScalarDirection.DECREASING -> delta != null && delta < -directionTolerance
        }
    }

    internal fun canSustain(value: Double, delta: Double?): Boolean {
        if (value !in holdInterval) return false
        return when (direction) {
            PhaseScalarDirection.ANY -> true
            PhaseScalarDirection.INCREASING -> delta == null || delta >= -directionTolerance
            PhaseScalarDirection.DECREASING -> delta == null || delta <= directionTolerance
        }
    }
}

data class PosePhaseState(
    val id: PosePhaseStateId,
    val enterPredicate: PosePhaseEnterPredicate,
)

/** Only edges leaving the active state are eligible, so an observation cannot skip phases. */
data class PosePhaseTransition(
    val from: PosePhaseStateId,
    val to: PosePhaseStateId,
    val completesCycle: Boolean = false,
) {
    init {
        require(from != to) { "A phase transition must change state" }
    }
}

/**
 * Immutable ordered phase graph. Branches are permitted, but an observation that matches more
 * than one outgoing phase fails closed and starts no candidate.
 */
class OrderedPosePhaseGraph(
    states: Collection<PosePhaseState>,
    val initialStateId: PosePhaseStateId,
    transitions: Collection<PosePhaseTransition>,
) {
    val states: Map<PosePhaseStateId, PosePhaseState>
    val transitions: List<PosePhaseTransition>
    internal val outgoingTransitions: Map<PosePhaseStateId, List<PosePhaseTransition>>

    init {
        require(states.isNotEmpty()) { "A phase graph must contain at least one state" }
        val stateMap = states.associateBy(PosePhaseState::id)
        require(stateMap.size == states.size) { "Phase state ids must be unique" }
        require(initialStateId in stateMap) { "The initial phase must exist in the graph" }

        val transitionList = transitions.toList()
        require(transitionList.distinctBy { it.from to it.to }.size == transitionList.size) {
            "Duplicate directed phase transitions are not allowed"
        }
        transitionList.forEach { transition ->
            require(transition.from in stateMap && transition.to in stateMap) {
                "Every phase transition endpoint must exist in the graph"
            }
            if (transition.completesCycle) {
                require(transition.to == initialStateId) {
                    "A cycle-completing transition must return to the initial phase"
                }
            }
        }
        require(transitionList.count(PosePhaseTransition::completesCycle) <= 1) {
            "A phase graph may declare at most one cycle-completing transition"
        }

        val reachable = mutableSetOf(initialStateId)
        var changed: Boolean
        do {
            changed = false
            transitionList.forEach { transition ->
                if (transition.from in reachable && reachable.add(transition.to)) changed = true
            }
        } while (changed)
        require(reachable.size == stateMap.size) {
            "Every phase state must be reachable from the initial phase"
        }

        this.states = Collections.unmodifiableMap(LinkedHashMap(stateMap))
        this.transitions = Collections.unmodifiableList(ArrayList(transitionList))
        this.outgoingTransitions = Collections.unmodifiableMap(
            transitionList.groupBy(PosePhaseTransition::from)
                .mapValuesTo(linkedMapOf()) { (_, value) ->
                    Collections.unmodifiableList(ArrayList(value))
                },
        )
    }
}

data class PosePhaseEngineConfig(
    val graph: OrderedPosePhaseGraph,
    /**
     * Contract-specific lower bound for [PosePhaseObservation.qualitySignal]. This is a gate,
     * not a probability and not criterion evidence weight.
     */
    val minimumQualitySignal: Double,
    val maximumObservationGapMs: Long,
    val unusableObservationGraceMs: Long,
    /** Hard bound for one open phase window and its runtime evidence buffer. */
    val maximumPhaseDurationMs: Long,
    /** Hard bound for one complete movement cycle, including its backdated initial window. */
    val maximumCycleDurationMs: Long,
) {
    init {
        require(
            minimumQualitySignal.isFinite() &&
                minimumQualitySignal > 0.0 &&
                minimumQualitySignal <= 1.0,
        ) {
            "minimumQualitySignal must be finite and in (0, 1]"
        }
        require(maximumObservationGapMs > 0L) {
            "maximumObservationGapMs must be positive"
        }
        require(unusableObservationGraceMs >= 0L) {
            "unusableObservationGraceMs must be non-negative"
        }
        require(maximumPhaseDurationMs > 0L) {
            "maximumPhaseDurationMs must be positive"
        }
        require(maximumPhaseDurationMs <= MAXIMUM_SUPPORTED_PHASE_DURATION_MS) {
            "maximumPhaseDurationMs exceeds the on-device safety ceiling"
        }
        require(maximumCycleDurationMs >= maximumPhaseDurationMs) {
            "maximumCycleDurationMs must cover the maximum single phase duration"
        }
        require(maximumCycleDurationMs <= MAXIMUM_SUPPORTED_CYCLE_DURATION_MS) {
            "maximumCycleDurationMs exceeds the on-device safety ceiling"
        }
        require(graph.states.size <= MAXIMUM_TRACKED_CYCLE_PHASE_WINDOWS) {
            "Phase graph exceeds the on-device cycle-scope window ceiling"
        }
        val excessiveDwellStates = graph.states.values.filter { state ->
            state.enterPredicate.minimumDwellMs > maximumPhaseDurationMs
        }
        require(excessiveDwellStates.isEmpty()) {
            "Phase dwell cannot exceed maximumPhaseDurationMs: " +
                excessiveDwellStates.map { it.id.value }.sorted().joinToString()
        }
    }
}

/**
 * Scalar phase-driver observation.
 *
 * [qualitySignal] is a unitless, contract-specific gating signal. It is neither a calibrated
 * correctness probability nor interchangeable with criterion evidence quality/mass.
 */
data class PosePhaseObservation(
    val timestampMs: Long,
    val scalar: Double?,
    val qualitySignal: Double,
) {
    init {
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(scalar == null || scalar.isFinite()) { "A phase scalar must be finite" }
        require(qualitySignal.isFinite() && qualitySignal in 0.0..1.0) {
            "qualitySignal must be finite and in [0, 1]"
        }
    }
}

data class PosePhaseWindow(
    val stateId: PosePhaseStateId,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
) {
    init {
        require(startTimestampMs >= 0L) { "A phase window start must be non-negative" }
        require(endTimestampMs > startTimestampMs) {
            "A phase window end must be after its start"
        }
    }
}

sealed interface PosePhaseEvent {
    /** Timestamp at which enough evidence existed to publish this event. */
    val confirmedAtTimestampMs: Long
}

data class PosePhaseWindowStarted(
    val stateId: PosePhaseStateId,
    /** First timestamp satisfying the phase predicate, before dwell confirmation. */
    val startTimestampMs: Long,
    override val confirmedAtTimestampMs: Long,
) : PosePhaseEvent

data class PosePhaseWindowEnded(
    val window: PosePhaseWindow,
    override val confirmedAtTimestampMs: Long,
) : PosePhaseEvent

class PosePhaseCycleCompleted(
    val cycleStartTimestampMs: Long,
    val cycleEndTimestampMs: Long,
    val completedCycleCount: Int,
    phaseWindows: List<PosePhaseWindow>,
    override val confirmedAtTimestampMs: Long,
) : PosePhaseEvent {
    val phaseWindows: List<PosePhaseWindow> =
        Collections.unmodifiableList(ArrayList(phaseWindows))
    val scopeSha256: String = canonicalFieldsSha256(
        buildList {
            add("completedCycleScopeSchemaVersion" to "1")
            add("cycleStartTimestampMs" to cycleStartTimestampMs.toString())
            add("cycleEndTimestampMs" to cycleEndTimestampMs.toString())
            add("phaseWindowCount" to this@PosePhaseCycleCompleted.phaseWindows.size.toString())
            this@PosePhaseCycleCompleted.phaseWindows.forEachIndexed { index, window ->
                add("phaseWindow[$index].stateId" to window.stateId.value)
                add("phaseWindow[$index].startTimestampMs" to window.startTimestampMs.toString())
                add("phaseWindow[$index].endTimestampMs" to window.endTimestampMs.toString())
            }
        },
    )

    init {
        require(cycleStartTimestampMs >= 0L)
        require(cycleEndTimestampMs > cycleStartTimestampMs)
        require(completedCycleCount > 0)
        require(this.phaseWindows.isNotEmpty())
        require(this.phaseWindows.first().startTimestampMs == cycleStartTimestampMs)
        require(this.phaseWindows.last().endTimestampMs == cycleEndTimestampMs)
        require(
            this.phaseWindows.zipWithNext().all { (left, right) ->
                left.endTimestampMs == right.startTimestampMs
            },
        ) { "Completed cycle phase windows must be contiguous and ordered" }
    }
}

enum class PosePhaseResetReason {
    MAXIMUM_OBSERVATION_GAP,
    UNUSABLE_OBSERVATION_TIMEOUT,
    PHASE_DURATION_TIMEOUT,
    CYCLE_DURATION_TIMEOUT,
    CYCLE_SCOPE_OVERFLOW,
    PERSON_LOCK_LOST,
    PERSON_CHANGED,
}

data class PosePhaseTrackingReset(
    val reason: PosePhaseResetReason,
    override val confirmedAtTimestampMs: Long,
) : PosePhaseEvent

data class PosePhaseUpdate(
    val timestampMs: Long,
    val activeStateId: PosePhaseStateId?,
    val completedCycleCount: Int,
    val isHoldingUnusableObservation: Boolean,
    val events: List<PosePhaseEvent>,
)

/**
 * Causal explicit-duration phase decoder for one scalar phase driver.
 *
 * The graph and predicates contain all exercise-specific knowledge. This engine has no workout or
 * phase-name branches. Transitions are confirmed with usable elapsed evidence, not frame counts;
 * duplicate timestamps and missing intervals never satisfy minimum dwell.
 */
class PosePhaseEngine(private val config: PosePhaseEngineConfig) {
    private data class EntryCandidate(
        val targetStateId: PosePhaseStateId,
        val transition: PosePhaseTransition?,
        val firstMatchTimestampMs: Long,
        var evidenceDurationMs: Long,
        var lastEvidenceTimestampMs: Long,
    )

    private var lastInputTimestampMs: Long? = null
    private var lastUsableTimestampMs: Long? = null
    private var lastUsableScalar: Double? = null
    private var unusableSinceTimestampMs: Long? = null
    private var activeWindowStartTimestampMs: Long? = null
    private var entryCandidate: EntryCandidate? = null
    private var cycleStartTimestampMs: Long? = null
    private val cyclePhaseWindows = mutableListOf<PosePhaseWindow>()

    var activeStateId: PosePhaseStateId? = null
        private set

    var completedCycleCount: Int = 0
        private set

    /** Identity discontinuities bypass confidence grace and discard phase state immediately. */
    internal fun resetForIdentityDiscontinuity(
        reason: PosePhaseResetReason,
        timestampMs: Long,
    ): PosePhaseUpdate {
        require(
            reason == PosePhaseResetReason.PERSON_LOCK_LOST ||
                reason == PosePhaseResetReason.PERSON_CHANGED,
        ) {
            "Only person-identity discontinuities may use the external reset boundary"
        }
        val previousInputTimestampMs = lastInputTimestampMs
        require(previousInputTimestampMs == null || timestampMs >= previousInputTimestampMs) {
            "Identity reset timestamps must be monotonic"
        }
        val events = mutableListOf<PosePhaseEvent>()
        resetTracking(
            reason = reason,
            confirmedAtTimestampMs = timestampMs,
            events = events,
            forceEvent = true,
        )
        lastInputTimestampMs = timestampMs
        return update(timestampMs, events)
    }

    fun accept(observation: PosePhaseObservation): PosePhaseUpdate {
        val previousInputTimestamp = lastInputTimestampMs
        require(previousInputTimestamp == null || observation.timestampMs >= previousInputTimestamp) {
            "Phase observation timestamps must be monotonic"
        }

        if (previousInputTimestamp != null && observation.timestampMs == previousInputTimestamp) {
            return update(observation.timestampMs, events = emptyList())
        }

        val events = mutableListOf<PosePhaseEvent>()
        if (
            previousInputTimestamp != null &&
            observation.timestampMs - previousInputTimestamp > config.maximumObservationGapMs
        ) {
            resetTracking(
                reason = PosePhaseResetReason.MAXIMUM_OBSERVATION_GAP,
                confirmedAtTimestampMs = observation.timestampMs,
                events = events,
            )
        }
        val openCycleStart = cycleStartTimestampMs
        if (
            openCycleStart != null &&
            observation.timestampMs - openCycleStart > config.maximumCycleDurationMs
        ) {
            resetTracking(
                reason = PosePhaseResetReason.CYCLE_DURATION_TIMEOUT,
                confirmedAtTimestampMs = observation.timestampMs,
                events = events,
            )
        }
        val openWindowStart = activeWindowStartTimestampMs
        if (
            openWindowStart != null &&
            observation.timestampMs - openWindowStart > config.maximumPhaseDurationMs
        ) {
            resetTracking(
                reason = PosePhaseResetReason.PHASE_DURATION_TIMEOUT,
                confirmedAtTimestampMs = observation.timestampMs,
                events = events,
            )
        }
        lastInputTimestampMs = observation.timestampMs

        val scalar = observation.scalar
        val usable = scalar != null && observation.qualitySignal >= config.minimumQualitySignal
        if (!usable) {
            handleUnusableObservation(
                timestampMs = observation.timestampMs,
                previousInputTimestampMs = previousInputTimestamp,
                events = events,
            )
            return update(observation.timestampMs, events)
        }

        unusableSinceTimestampMs = null
        val previousUsableTimestamp = lastUsableTimestampMs
        val previousScalar = lastUsableScalar
        val delta = previousScalar?.let { scalar - it }

        if (activeStateId == null) {
            collectInitialStateEvidence(
                timestampMs = observation.timestampMs,
                scalar = scalar,
                delta = delta,
                previousUsableTimestampMs = previousUsableTimestamp,
                events = events,
            )
        } else {
            collectTransitionEvidence(
                timestampMs = observation.timestampMs,
                scalar = scalar,
                delta = delta,
                previousUsableTimestampMs = previousUsableTimestamp,
                events = events,
            )
        }

        lastUsableTimestampMs = observation.timestampMs
        lastUsableScalar = scalar
        return update(observation.timestampMs, events)
    }

    private fun handleUnusableObservation(
        timestampMs: Long,
        previousInputTimestampMs: Long?,
        events: MutableList<PosePhaseEvent>,
    ) {
        entryCandidate = null
        // A sparse callback must not start a fresh grace period at its late arrival time. The
        // unobserved interval began at the last usable/input boundary, so stale scalar state is
        // discarded immediately when that interval has already exhausted the grace budget.
        val firstUnusableTimestamp = unusableSinceTimestampMs ?: (
            lastUsableTimestampMs ?: previousInputTimestampMs ?: timestampMs
        ).also {
            unusableSinceTimestampMs = it
        }
        if (timestampMs - firstUnusableTimestamp >= config.unusableObservationGraceMs) {
            resetTracking(
                reason = PosePhaseResetReason.UNUSABLE_OBSERVATION_TIMEOUT,
                confirmedAtTimestampMs = timestampMs,
                events = events,
            )
        }
    }

    private fun collectInitialStateEvidence(
        timestampMs: Long,
        scalar: Double,
        delta: Double?,
        previousUsableTimestampMs: Long?,
        events: MutableList<PosePhaseEvent>,
    ) {
        val initialState = requireNotNull(config.graph.states[config.graph.initialStateId])
        val predicate = initialState.enterPredicate
        val candidate = entryCandidate
        if (candidate != null && candidate.targetStateId == initialState.id) {
            if (predicate.canSustain(scalar, delta)) {
                addContinuousEvidence(candidate, timestampMs, previousUsableTimestampMs)
                if (candidate.evidenceDurationMs >= predicate.minimumDwellMs) {
                    activateInitialState(candidate, timestampMs, events)
                }
                return
            }
            entryCandidate = null
        }

        if (predicate.canStart(scalar, delta)) {
            val newCandidate = EntryCandidate(
                targetStateId = initialState.id,
                transition = null,
                firstMatchTimestampMs = timestampMs,
                evidenceDurationMs = 0L,
                lastEvidenceTimestampMs = timestampMs,
            )
            entryCandidate = newCandidate
            if (predicate.minimumDwellMs == 0L) {
                activateInitialState(newCandidate, timestampMs, events)
            }
        }
    }

    private fun activateInitialState(
        candidate: EntryCandidate,
        confirmedAtTimestampMs: Long,
        events: MutableList<PosePhaseEvent>,
    ) {
        activeStateId = config.graph.initialStateId
        activeWindowStartTimestampMs = candidate.firstMatchTimestampMs
        entryCandidate = null
        cycleStartTimestampMs = null
        cyclePhaseWindows.clear()
        events += PosePhaseWindowStarted(
            stateId = config.graph.initialStateId,
            startTimestampMs = candidate.firstMatchTimestampMs,
            confirmedAtTimestampMs = confirmedAtTimestampMs,
        )
    }

    private fun collectTransitionEvidence(
        timestampMs: Long,
        scalar: Double,
        delta: Double?,
        previousUsableTimestampMs: Long?,
        events: MutableList<PosePhaseEvent>,
    ) {
        val candidate = entryCandidate
        if (candidate != null) {
            val predicate = requireNotNull(config.graph.states[candidate.targetStateId]).enterPredicate
            if (predicate.canSustain(scalar, delta)) {
                addContinuousEvidence(candidate, timestampMs, previousUsableTimestampMs)
                if (candidate.evidenceDurationMs >= predicate.minimumDwellMs) {
                    confirmTransition(candidate, timestampMs, events)
                }
                return
            }
            entryCandidate = null
        }

        val currentStateId = requireNotNull(activeStateId)
        val matchingTransitions = config.graph.outgoingTransitions[currentStateId].orEmpty()
            .filter { transition ->
                requireNotNull(config.graph.states[transition.to]).enterPredicate.canStart(scalar, delta)
            }
        if (matchingTransitions.size != 1) return

        val transition = matchingTransitions.single()
        val predicate = requireNotNull(config.graph.states[transition.to]).enterPredicate
        val newCandidate = EntryCandidate(
            targetStateId = transition.to,
            transition = transition,
            firstMatchTimestampMs = timestampMs,
            evidenceDurationMs = 0L,
            lastEvidenceTimestampMs = timestampMs,
        )
        entryCandidate = newCandidate
        if (predicate.minimumDwellMs == 0L) {
            confirmTransition(newCandidate, timestampMs, events)
        }
    }

    private fun addContinuousEvidence(
        candidate: EntryCandidate,
        timestampMs: Long,
        previousUsableTimestampMs: Long?,
    ) {
        if (
            previousUsableTimestampMs != null &&
            candidate.lastEvidenceTimestampMs == previousUsableTimestampMs
        ) {
            candidate.evidenceDurationMs += timestampMs - previousUsableTimestampMs
        }
        candidate.lastEvidenceTimestampMs = timestampMs
    }

    private fun confirmTransition(
        candidate: EntryCandidate,
        confirmedAtTimestampMs: Long,
        events: MutableList<PosePhaseEvent>,
    ) {
        val transition = requireNotNull(candidate.transition)
        val previousStateId = requireNotNull(activeStateId)
        val previousWindowStart = requireNotNull(activeWindowStartTimestampMs)
        val boundaryTimestampMs = candidate.firstMatchTimestampMs
        val startsCycle =
            previousStateId == config.graph.initialStateId && cycleStartTimestampMs == null
        val trackedWindowCount = if (startsCycle) 0 else cyclePhaseWindows.size
        if (
            (startsCycle || cycleStartTimestampMs != null) &&
            trackedWindowCount >= MAXIMUM_TRACKED_CYCLE_PHASE_WINDOWS
        ) {
            resetTracking(
                reason = PosePhaseResetReason.CYCLE_SCOPE_OVERFLOW,
                confirmedAtTimestampMs = confirmedAtTimestampMs,
                events = events,
            )
            return
        }

        val endedWindow = PosePhaseWindow(
            stateId = previousStateId,
            startTimestampMs = previousWindowStart,
            endTimestampMs = boundaryTimestampMs,
        )
        events += PosePhaseWindowEnded(
            window = endedWindow,
            confirmedAtTimestampMs = confirmedAtTimestampMs,
        )

        activeStateId = candidate.targetStateId
        activeWindowStartTimestampMs = boundaryTimestampMs
        entryCandidate = null
        events += PosePhaseWindowStarted(
            stateId = candidate.targetStateId,
            startTimestampMs = boundaryTimestampMs,
            confirmedAtTimestampMs = confirmedAtTimestampMs,
        )

        if (startsCycle) {
            // The initial window is part of the cycle and may own setup criteria. Preserve its
            // backdated start so cycle provenance never begins after included evidence.
            cycleStartTimestampMs = previousWindowStart
            cyclePhaseWindows.clear()
        }
        if (cycleStartTimestampMs != null) cyclePhaseWindows += endedWindow
        if (transition.completesCycle) {
            cycleStartTimestampMs?.let { cycleStart ->
                completedCycleCount += 1
                events += PosePhaseCycleCompleted(
                    cycleStartTimestampMs = cycleStart,
                    cycleEndTimestampMs = boundaryTimestampMs,
                    completedCycleCount = completedCycleCount,
                    phaseWindows = cyclePhaseWindows,
                    confirmedAtTimestampMs = confirmedAtTimestampMs,
                )
            }
            cycleStartTimestampMs = null
            cyclePhaseWindows.clear()
        }
    }

    private fun resetTracking(
        reason: PosePhaseResetReason,
        confirmedAtTimestampMs: Long,
        events: MutableList<PosePhaseEvent>,
        forceEvent: Boolean = false,
    ) {
        val stateId = activeStateId
        val windowStart = activeWindowStartTimestampMs
        if (stateId != null && windowStart != null) {
            val lastEvidenceTimestamp = lastUsableTimestampMs ?: windowStart
            if (lastEvidenceTimestamp > windowStart) {
                events += PosePhaseWindowEnded(
                    window = PosePhaseWindow(
                        stateId = stateId,
                        startTimestampMs = windowStart,
                        endTimestampMs = lastEvidenceTimestamp,
                    ),
                    confirmedAtTimestampMs = confirmedAtTimestampMs,
                )
            }
        }
        if (forceEvent || stateId != null || entryCandidate != null) {
            events += PosePhaseTrackingReset(
                reason = reason,
                confirmedAtTimestampMs = confirmedAtTimestampMs,
            )
        }

        activeStateId = null
        activeWindowStartTimestampMs = null
        entryCandidate = null
        cycleStartTimestampMs = null
        cyclePhaseWindows.clear()
        lastUsableTimestampMs = null
        lastUsableScalar = null
        unusableSinceTimestampMs = null
    }

    private fun update(
        timestampMs: Long,
        events: List<PosePhaseEvent>,
    ) = PosePhaseUpdate(
        timestampMs = timestampMs,
        activeStateId = activeStateId,
        completedCycleCount = completedCycleCount,
        isHoldingUnusableObservation = unusableSinceTimestampMs != null && activeStateId != null,
        events = events.toList(),
    )
}
