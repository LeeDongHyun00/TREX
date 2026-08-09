package com.example.trex_kotlin.pose.runtime

import com.example.trex_kotlin.pose.criterion.CriterionAggregateCalibration
import com.example.trex_kotlin.pose.criterion.CriterionPhaseWindow
import com.example.trex_kotlin.pose.criterion.PoseCriterionGraphEvaluation
import com.example.trex_kotlin.pose.phase.PosePhaseCycleCompleted
import com.example.trex_kotlin.pose.phase.PosePhaseEvent
import com.example.trex_kotlin.pose.phase.PosePhaseResetReason
import com.example.trex_kotlin.pose.phase.PosePhaseStateId
import com.example.trex_kotlin.pose.phase.PosePhaseTrackingReset
import com.example.trex_kotlin.pose.phase.PosePhaseUpdate
import com.example.trex_kotlin.pose.phase.PosePhaseWindowEnded
import com.example.trex_kotlin.pose.spec.BoundPoseCriterionResult
import com.example.trex_kotlin.pose.spec.CriterionRuntimeMode
import com.example.trex_kotlin.pose.spec.ExerciseCriterionSpec
import com.example.trex_kotlin.pose.spec.PoseExerciseSpec
import java.util.ArrayDeque
import java.util.Collections

/** A hard memory ceiling; a signed duration cannot authorize an unbounded mobile heap. */
internal const val MAX_BUFFERED_POSE_FRAMES: Int = 2_048

/** A graph result whose atomic criterion envelopes all belong to one completed cycle. */
internal class PoseExerciseCycleEvaluation internal constructor(
    val cycleEpoch: Long,
    val cycleStartTimestampMs: Long,
    val cycleEndTimestampMs: Long,
    val completedCycleCount: Int,
    val observationContractArtifactSha256: String,
    internal val personTrackEpoch: PosePersonTrackEpoch,
    criterionResults: Map<String, BoundPoseCriterionResult>,
    val graphEvaluation: PoseCriterionGraphEvaluation,
) {
    val criterionResults: Map<String, BoundPoseCriterionResult> =
        Collections.unmodifiableMap(LinkedHashMap(criterionResults))
}

/** Result of one distinct camera timestamp accepted by [PoseExerciseEvaluationSession]. */
internal class PoseExerciseSessionUpdate internal constructor(
    val timestampMs: Long,
    val activePhaseId: PosePhaseStateId?,
    val completedCycleCount: Int,
    phaseEvents: List<PosePhaseEvent>,
    val cycleEvaluation: PoseExerciseCycleEvaluation?,
) {
    val phaseEvents: List<PosePhaseEvent> =
        Collections.unmodifiableList(ArrayList(phaseEvents))
}

/**
 * Stateful, bounded composition of signed phase, criterion, calibration, view, and graph policy.
 *
 * One instance belongs to one camera/person-lock lifecycle and must receive frames serially. The
 * ring is bounded by both the signed maximum phase duration and a device hard frame ceiling. A
 * transition is published only after its dwell, so ended windows are reconstructed retrospectively
 * from the ring. Window membership is half-open: `[start, end)`. Consequently the first matching
 * transition frame is retained for the new phase and cannot leak into the old phase aggregate.
 *
 * The exact observation source and aggregate calibrations are constructor-pinned. Dynamic person
 * lock and view evidence comes only from source-bound opaque tokens on each observation. Every
 * cue-eligible criterion must have its exact signed aggregate calibration before a session can
 * exist; shadow criteria may deliberately remain uncalibrated and evaluate to UNKNOWN.
 */
internal class PoseExerciseEvaluationSession(
    val exerciseSpec: PoseExerciseSpec,
    internal val observationSource: PoseObservationSource,
    calibrations: Map<String, CriterionAggregateCalibration>,
) {
    val calibrations: Map<String, CriterionAggregateCalibration> =
        Collections.unmodifiableMap(LinkedHashMap(calibrations))

    private val phaseEngine = exerciseSpec.newPhaseEngine()
    private val initialPhaseId = exerciseSpec.phaseDriver.engineConfig.graph.initialStateId
    private val maximumPhaseDurationMs =
        exerciseSpec.phaseDriver.engineConfig.maximumPhaseDurationMs
    private val criterionIds = exerciseSpec.criteria
        .map(ExerciseCriterionSpec::criterionId)
        .toCollection(LinkedHashSet())
    private val criteriaByPhase = exerciseSpec.criteria
        .flatMap { criterion -> criterion.eligiblePhaseIds.map { phase -> phase to criterion } }
        .groupBy(
            keySelector = Pair<PosePhaseStateId, ExerciseCriterionSpec>::first,
            valueTransform = Pair<PosePhaseStateId, ExerciseCriterionSpec>::second,
        )
    private val frameBuffer = ArrayDeque<AttestedPoseObservation>()
    private val pendingResults = linkedMapOf<String, BoundPoseCriterionResult>()

    private var lastInputTimestampMs: Long? = null
    private var nextCycleEpoch = 0L
    private var activeCycleEpoch: Long? = null
    private var activeCycleStartTimestampMs: Long? = null
    private var activeCycleInvalid = false
    private var bufferedOverflowInvalidatesNextCycle = false
    private var activePersonTrackEpoch: PosePersonTrackEpoch? = null
    private var evaluatedCriterionWindows = 0

    internal val bufferedFrameCount: Int
        get() = frameBuffer.size

    internal val pendingCriterionCount: Int
        get() = pendingResults.size

    internal val evaluatedCriterionWindowCount: Int
        get() = evaluatedCriterionWindows

    init {
        require(
            observationSource.contract.artifactSha256 ==
                exerciseSpec.observationContract.artifactSha256,
        ) {
            "Observation source does not match the signed exercise observation contract"
        }

        val unknownCalibrationIds = this.calibrations.keys - criterionIds
        require(unknownCalibrationIds.isEmpty()) {
            "Calibrations contain unknown criterion ids: ${unknownCalibrationIds.sorted().joinToString()}"
        }
        this.calibrations.forEach { (criterionId, calibration) ->
            val criterion = requireNotNull(exerciseSpec.criterion(criterionId))
            val criterionSpec = criterion.featureBinding.criterionSpec
            require(calibration.contract.criterionId == criterionId) {
                "Calibration key and contract criterion id do not match"
            }
            require(calibration.contract == criterionSpec.calibrationContract) {
                "Calibration contract does not match criterion $criterionId"
            }
            require(calibration.artifactSha256 == criterionSpec.approvedCalibrationArtifactSha256) {
                "Calibration artifact does not match criterion $criterionId"
            }
        }
        val uncalibratedCueIds = exerciseSpec.criteria
            .filter { it.runtimeMode == CriterionRuntimeMode.CUE_ELIGIBLE }
            .map(ExerciseCriterionSpec::criterionId)
            .filterNot(this.calibrations::containsKey)
        require(uncalibratedCueIds.isEmpty()) {
            "Cue-eligible criteria require pinned calibration: " +
                uncalibratedCueIds.sorted().joinToString()
        }
    }

    /**
     * Accepts one frame. Earlier timestamps fail fast and later callbacks at the same timestamp
     * are ignored before phase measurement, buffering, or criterion sampling.
     */
    fun accept(observation: AttestedPoseObservation): PoseExerciseSessionUpdate {
        require(observation.isFrom(observationSource)) {
            "Observation was minted by a foreign source"
        }
        val frame = observation.frame
        val previousTimestamp = lastInputTimestampMs
        require(previousTimestamp == null || frame.timestampMs >= previousTimestamp) {
            "Pose session frame timestamps must be monotonic"
        }

        val observedPersonEpoch = observation.personTrackEpoch
        val identityResetReason = when {
            observedPersonEpoch == null -> PosePhaseResetReason.PERSON_LOCK_LOST
            activePersonTrackEpoch != null &&
                observedPersonEpoch !== activePersonTrackEpoch -> PosePhaseResetReason.PERSON_CHANGED
            else -> null
        }
        if (identityResetReason != null) {
            val phaseUpdate = phaseEngine.resetForIdentityDiscontinuity(
                reason = identityResetReason,
                timestampMs = frame.timestampMs,
            )
            lastInputTimestampMs = frame.timestampMs
            discardActiveCycle()
            frameBuffer.clear()
            bufferedOverflowInvalidatesNextCycle = false
            activePersonTrackEpoch = observedPersonEpoch
            return update(frame.timestampMs, phaseUpdate, cycleEvaluation = null)
        }
        if (activePersonTrackEpoch == null) activePersonTrackEpoch = observedPersonEpoch

        if (frame.timestampMs == previousTimestamp) {
            return update(
                timestampMs = frame.timestampMs,
                phaseUpdate = null,
                cycleEvaluation = null,
            )
        }

        val phaseObservation = if (
            observation.isViewQualified(exerciseSpec.observationContract.phaseViewContractId)
        ) {
            exerciseSpec.phaseObservation(frame)
        } else {
            com.example.trex_kotlin.pose.phase.PosePhaseObservation(
                timestampMs = frame.timestampMs,
                scalar = null,
                qualitySignal = 0.0,
            )
        }
        val phaseUpdate = phaseEngine.accept(phaseObservation)
        lastInputTimestampMs = frame.timestampMs
        frameBuffer.addLast(observation)
        trimFrameBuffer(frame.timestampMs)
        enforceFrameBufferLimit()

        if (phaseUpdate.events.any { it is PosePhaseTrackingReset }) {
            discardActiveCycle()
            frameBuffer.clear()
            bufferedOverflowInvalidatesNextCycle = false
            // A gap/duration reset can immediately start a fresh initial candidate with this same
            // frame. Retaining it preserves that future backdated initial boundary.
            frameBuffer.addLast(observation)
            return update(frame.timestampMs, phaseUpdate, cycleEvaluation = null)
        }

        val endedWindows = phaseUpdate.events.filterIsInstance<PosePhaseWindowEnded>()
        if (
            activeCycleEpoch == null &&
            endedWindows.any { it.window.stateId == initialPhaseId }
        ) {
            val initialWindow = endedWindows.single { it.window.stateId == initialPhaseId }.window
            beginCycle(initialWindow.startTimestampMs)
        }

        endedWindows.forEach { event -> evaluateEndedWindow(event) }

        val completedEvent = phaseUpdate.events
            .filterIsInstance<PosePhaseCycleCompleted>()
            .singleOrNull()
        val cycleEvaluation = completedEvent?.let(::completeCycle)
        return update(frame.timestampMs, phaseUpdate, cycleEvaluation)
    }

    private fun beginCycle(startTimestampMs: Long) {
        check(activeCycleEpoch == null)
        check(activeCycleStartTimestampMs == null)
        check(pendingResults.isEmpty())
        check(nextCycleEpoch < Long.MAX_VALUE) { "Pose session cycle epoch exhausted" }
        activeCycleEpoch = nextCycleEpoch
        activeCycleStartTimestampMs = startTimestampMs
        nextCycleEpoch += 1L
        activeCycleInvalid = bufferedOverflowInvalidatesNextCycle
        bufferedOverflowInvalidatesNextCycle = false
    }

    private fun evaluateEndedWindow(event: PosePhaseWindowEnded) {
        val window = event.window
        if (window.endTimestampMs <= window.startTimestampMs) return

        val criteria = criteriaByPhase[window.stateId].orEmpty()
        val cycleEpoch = activeCycleEpoch
        if (criteria.isNotEmpty() && cycleEpoch == null) {
            activeCycleInvalid = true
            trimBefore(window.endTimestampMs)
            return
        }

        val observations = frameBuffer
            .asSequence()
            .filter { bufferedObservation ->
                bufferedObservation.frame.timestampMs >= window.startTimestampMs &&
                    bufferedObservation.frame.timestampMs < window.endTimestampMs
            }
            .toList()
        criteria.forEach { criterion ->
            if (pendingResults.containsKey(criterion.criterionId)) {
                activeCycleInvalid = true
                return@forEach
            }
            val personTrackEpoch = requireNotNull(activePersonTrackEpoch)
            val viewQualified = observations.isNotEmpty() && observations.all { item ->
                item.personTrackEpoch === personTrackEpoch &&
                    item.isViewQualified(criterion.viewContractId)
            }
            pendingResults[criterion.criterionId] = exerciseSpec.evaluateCriterion(
                criterionId = criterion.criterionId,
                cycleEpoch = requireNotNull(cycleEpoch),
                phaseId = window.stateId,
                phaseWindow = CriterionPhaseWindow(
                    startTimestampMs = window.startTimestampMs,
                    endTimestampMs = window.endTimestampMs,
                ),
                frames = observations.map(AttestedPoseObservation::frame),
                personTrackEpoch = personTrackEpoch,
                viewQualified = viewQualified,
                calibration = calibrations[criterion.criterionId],
            )
            evaluatedCriterionWindows += 1
        }
        trimBefore(window.endTimestampMs)
    }

    private fun completeCycle(event: PosePhaseCycleCompleted): PoseExerciseCycleEvaluation? {
        val epoch = activeCycleEpoch
        val cycleStartTimestampMs = activeCycleStartTimestampMs
        val personTrackEpoch = activePersonTrackEpoch
        val complete = epoch != null &&
            cycleStartTimestampMs != null &&
            personTrackEpoch != null &&
            !activeCycleInvalid &&
            pendingResults.keys == criterionIds
        val result = if (complete) {
            val orderedResults = exerciseSpec.criteria.associate { criterion ->
                criterion.criterionId to pendingResults.getValue(criterion.criterionId)
            }
            PoseExerciseCycleEvaluation(
                cycleEpoch = requireNotNull(epoch),
                cycleStartTimestampMs = requireNotNull(cycleStartTimestampMs),
                cycleEndTimestampMs = event.cycleEndTimestampMs,
                completedCycleCount = event.completedCycleCount,
                observationContractArtifactSha256 =
                    exerciseSpec.observationContract.artifactSha256,
                personTrackEpoch = requireNotNull(personTrackEpoch),
                criterionResults = orderedResults,
                graphEvaluation = exerciseSpec.evaluateCriterionGraph(orderedResults.values),
            )
        } else {
            null
        }
        discardActiveCycle()
        return result
    }

    private fun discardActiveCycle() {
        pendingResults.clear()
        activeCycleEpoch = null
        activeCycleStartTimestampMs = null
        activeCycleInvalid = false
    }

    private fun trimFrameBuffer(timestampMs: Long) {
        val cutoff = if (timestampMs > maximumPhaseDurationMs) {
            timestampMs - maximumPhaseDurationMs
        } else {
            0L
        }
        trimBefore(cutoff)
    }

    private fun enforceFrameBufferLimit() {
        if (frameBuffer.size <= MAX_BUFFERED_POSE_FRAMES) return
        while (frameBuffer.size > MAX_BUFFERED_POSE_FRAMES) frameBuffer.removeFirst()
        if (activeCycleEpoch == null) {
            bufferedOverflowInvalidatesNextCycle = true
        } else {
            activeCycleInvalid = true
        }
    }

    /** Strict comparison retains a transition boundary for its new half-open phase window. */
    private fun trimBefore(timestampMs: Long) {
        while (
            frameBuffer.peekFirst()?.frame?.timestampMs?.let { it < timestampMs } == true
        ) {
            frameBuffer.removeFirst()
        }
    }

    private fun update(
        timestampMs: Long,
        phaseUpdate: PosePhaseUpdate?,
        cycleEvaluation: PoseExerciseCycleEvaluation?,
    ): PoseExerciseSessionUpdate = PoseExerciseSessionUpdate(
        timestampMs = timestampMs,
        activePhaseId = phaseUpdate?.activeStateId ?: phaseEngine.activeStateId,
        completedCycleCount = phaseUpdate?.completedCycleCount ?: phaseEngine.completedCycleCount,
        phaseEvents = phaseUpdate?.events.orEmpty(),
        cycleEvaluation = cycleEvaluation,
    )
}
