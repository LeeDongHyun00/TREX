package com.example.trex_kotlin.pose.spec

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.criterion.CriterionAggregateCalibration
import com.example.trex_kotlin.pose.criterion.CriterionCapability
import com.example.trex_kotlin.pose.criterion.CriterionEvidenceSample
import com.example.trex_kotlin.pose.criterion.CriterionPhaseWindow
import com.example.trex_kotlin.pose.criterion.CriterionState
import com.example.trex_kotlin.pose.criterion.PoseCriterionEngine
import com.example.trex_kotlin.pose.criterion.PoseCriterionFeatureBinding
import com.example.trex_kotlin.pose.criterion.PoseCriterionGraph
import com.example.trex_kotlin.pose.criterion.PoseCriterionGraphEvaluation
import com.example.trex_kotlin.pose.criterion.PoseCriterionResult
import com.example.trex_kotlin.pose.criterion.PoseCriterionSampler
import com.example.trex_kotlin.pose.feature.PoseFeatureEngine
import com.example.trex_kotlin.pose.feature.SIGNED_FEATURE_MINIMUM_CONFIDENCE
import com.example.trex_kotlin.pose.feature.SIGNED_FEATURE_RUNTIME_CONTRACT_SHA256
import com.example.trex_kotlin.pose.phase.PosePhaseDriverBinding
import com.example.trex_kotlin.pose.phase.PosePhaseEngine
import com.example.trex_kotlin.pose.phase.PosePhaseObservation
import com.example.trex_kotlin.pose.phase.PosePhaseStateId
import com.example.trex_kotlin.pose.runtime.PoseObservationContract
import com.example.trex_kotlin.pose.runtime.PosePersonTrackEpoch
import java.util.Collections

private val SPEC_IDENTIFIER = Regex("^[a-z0-9][a-z0-9._:/-]*$")
private val SPEC_SHA256 = Regex("^[0-9a-f]{64}$")

/** Construct observability is explicit and separate from statistical confidence. */
enum class CriterionObservability {
    DIRECT,
    PROXY_UNVALIDATED,
    PROXY_GOLD_VALIDATED,
    NOT_OBSERVABLE,
}

enum class CriterionRuntimeMode {
    /** Measurements are retained for evaluation but never alter released status or cues. */
    SHADOW,

    /** A graph-selected directional failure may proceed to the feedback persistence policy. */
    CUE_ELIGIBLE,
}

/** One criterion's feature, phase, view, calibration, and release contract. */
class ExerciseCriterionSpec(
    val featureBinding: PoseCriterionFeatureBinding,
    eligiblePhaseIds: Set<PosePhaseStateId>,
    val viewContractId: String,
    val observability: CriterionObservability,
    val runtimeMode: CriterionRuntimeMode,
) {
    val eligiblePhaseIds: Set<PosePhaseStateId> =
        Collections.unmodifiableSet(LinkedHashSet(eligiblePhaseIds))

    val criterionId: String
        get() = featureBinding.criterionSpec.criterionId

    init {
        require(this.eligiblePhaseIds.size == 1) {
            "A scalar criterion must bind exactly one contiguous phase window"
        }
        require(SPEC_IDENTIFIER.matches(viewContractId)) {
            "viewContractId must be a lowercase, versioned identifier"
        }
        require(observability != CriterionObservability.NOT_OBSERVABLE) {
            "A coordinate runtime spec cannot bind a non-observable criterion"
        }
        if (runtimeMode == CriterionRuntimeMode.CUE_ELIGIBLE) {
            require(
                observability == CriterionObservability.DIRECT ||
                    observability == CriterionObservability.PROXY_GOLD_VALIDATED,
            ) {
                "An unvalidated proxy criterion must remain shadow-only"
            }
        }
    }
}

/**
 * Provenance envelope produced only after the exact exercise-owned criterion evaluator runs.
 * Graph composition validates every field again; matching a criterion id alone is insufficient.
 */
class BoundPoseCriterionResult internal constructor(
    val exerciseSpecSha256: String,
    val phaseArtifactSha256: String,
    val cycleEpoch: Long,
    val phaseId: PosePhaseStateId,
    val phaseWindow: CriterionPhaseWindow,
    val viewContractId: String,
    val viewQualified: Boolean,
    val featureSpecSha256: String,
    val runtimeDomainId: String,
    val qualityCalibrationArtifactSha256: String,
    val observationContractArtifactSha256: String,
    internal val personTrackEpoch: PosePersonTrackEpoch,
    val atomicResult: PoseCriterionResult,
)

/**
 * Immutable, signed runtime package for one exercise from the canonical AI Hub catalog.
 *
 * No exercise-name branch or target default exists here. The top-level SHA binds the phase
 * artifact, every criterion/release/view choice, and the graph policy. New user-facing cues can
 * only be selected from provenance envelopes evaluated by this exact package.
 */
internal class PoseExerciseSpec(
    val specId: String,
    val specVersion: Int,
    val exercise: AiHubExercise,
    val observationContract: PoseObservationContract,
    val phaseDriver: PosePhaseDriverBinding,
    criteria: List<ExerciseCriterionSpec>,
    criterionGraph: PoseCriterionGraph,
    val approvedExerciseSpecSha256: String,
) {
    val criteria: List<ExerciseCriterionSpec> =
        Collections.unmodifiableList(ArrayList(criteria))
    private val criterionGraph: PoseCriterionGraph = criterionGraph
    private val criteriaById: Map<String, ExerciseCriterionSpec>
    private val cueEligibleCriterionIds: Set<String>
    private val featureEngine = PoseFeatureEngine(
        minimumConfidence = SIGNED_FEATURE_MINIMUM_CONFIDENCE,
    )
    private val criterionSampler = PoseCriterionSampler(featureEngine)
    private val criterionEngine = PoseCriterionEngine()

    val exerciseSpecSha256: String = exerciseSpecSha256(
        specId = specId,
        specVersion = specVersion,
        exercise = exercise,
        observationContract = observationContract,
        phaseDriver = phaseDriver,
        criteria = this.criteria,
        criterionGraph = criterionGraph,
    )

    init {
        require(SPEC_IDENTIFIER.matches(specId)) {
            "specId must be a lowercase, versioned identifier"
        }
        require(specVersion > 0) { "specVersion must be positive" }
        require(SPEC_SHA256.matches(approvedExerciseSpecSha256)) {
            "approvedExerciseSpecSha256 must be a lowercase SHA-256"
        }
        require(approvedExerciseSpecSha256 == exerciseSpecSha256) {
            "Exercise phase, criterion, view, release, or graph policy is not approved"
        }
        require(this.criteria.isNotEmpty()) { "An exercise spec must contain criteria" }

        criteriaById = this.criteria.associateBy(ExerciseCriterionSpec::criterionId)
        require(criteriaById.size == this.criteria.size) {
            "Criterion ids must be unique within an exercise spec"
        }
        require(criteriaById.keys == criterionGraph.nodeIds) {
            "Exercise criterion ids must exactly match criterion graph ids"
        }

        val phaseIds = phaseDriver.engineConfig.graph.states.keys
        validateDeterministicCycle(phaseDriver)
        this.criteria.forEach { criterion ->
            val unknownPhases = criterion.eligiblePhaseIds - phaseIds
            require(unknownPhases.isEmpty()) {
                "Criterion ${criterion.criterionId} references unknown phases: " +
                    unknownPhases.sortedBy(PosePhaseStateId::value).joinToString()
            }
        }

        cueEligibleCriterionIds = this.criteria
            .filter { it.runtimeMode == CriterionRuntimeMode.CUE_ELIGIBLE }
            .map(ExerciseCriterionSpec::criterionId)
            .let { Collections.unmodifiableSet(LinkedHashSet(it)) }
        criterionGraph.validateReleaseClosure(cueEligibleCriterionIds)
        val phaseRuntimeDomainId = phaseDriver.qualityCalibration.runtimeDomainId
        require(phaseRuntimeDomainId == observationContract.runtimeDomainId) {
            "Phase runtime domain must match the observation contract"
        }
        require(
            phaseDriver.featureSpec.coordinateSpace in
                observationContract.supportedCoordinateSpaces,
        ) {
            "Observation contract does not support the phase feature coordinate space"
        }
        val incompatibleCriterionDomains = this.criteria
            .filter {
                it.featureBinding.criterionSpec.calibrationContract.runtimeDomainId !=
                    observationContract.runtimeDomainId
            }
            .map(ExerciseCriterionSpec::criterionId)
        require(incompatibleCriterionDomains.isEmpty()) {
            "Every criterion must share the attested observation runtime domain: " +
                incompatibleCriterionDomains.sorted().joinToString()
        }
        val unsupportedCriterionSpaces = this.criteria.filter { criterion ->
            criterion.featureBinding.featureSpec.coordinateSpace !in
                observationContract.supportedCoordinateSpaces
        }
        require(unsupportedCriterionSpaces.isEmpty()) {
            "Observation contract does not support criterion coordinate spaces: " +
                unsupportedCriterionSpaces.map(ExerciseCriterionSpec::criterionId)
                    .sorted().joinToString()
        }
    }

    fun criterion(id: String): ExerciseCriterionSpec? = criteriaById[id]

    /** Creates isolated phase state for one camera/session lifecycle. */
    internal fun newPhaseEngine(): PosePhaseEngine = PosePhaseEngine(phaseDriver.engineConfig)

    /** Measures the signed phase feature; no arbitrary scalar can enter through this API. */
    internal fun phaseObservation(frame: PoseFrame): PosePhaseObservation =
        phaseDriver.observation(frame, featureEngine)

    /**
     * Samples frames with the exact bound feature/quality adapter and evaluates one phase window.
     * [com.example.trex_kotlin.pose.runtime.PoseExerciseEvaluationSession] owns backdated-boundary
     * replay; this module-internal seam does not expose raw evidence or a foreign evaluator result.
     */
    internal fun evaluateCriterion(
        criterionId: String,
        cycleEpoch: Long,
        phaseId: PosePhaseStateId,
        phaseWindow: CriterionPhaseWindow,
        frames: List<PoseFrame>,
        personTrackEpoch: PosePersonTrackEpoch,
        viewQualified: Boolean,
        calibration: CriterionAggregateCalibration?,
    ): BoundPoseCriterionResult {
        require(cycleEpoch >= 0L) { "cycleEpoch must be non-negative" }
        val criterion = requireNotNull(criteriaById[criterionId]) {
            "Unknown exercise criterion: $criterionId"
        }
        require(phaseId in criterion.eligiblePhaseIds) {
            "Criterion $criterionId is not eligible in phase $phaseId"
        }
        require(
            personTrackEpoch.source.contract.artifactSha256 ==
                observationContract.artifactSha256,
        ) {
            "Person-track epoch was minted outside the signed observation contract"
        }
        val effectiveCapabilities = buildSet {
            if (com.example.trex_kotlin.pose.PoseCoordinateSpace.NORMALIZED_IMAGE in
                observationContract.supportedCoordinateSpaces
            ) {
                add(CriterionCapability.POSE_2D)
            }
            if (com.example.trex_kotlin.pose.PoseCoordinateSpace.WORLD in
                observationContract.supportedCoordinateSpaces
            ) {
                add(CriterionCapability.POSE_WORLD_RELATIVE)
            }
            add(CriterionCapability.TEMPORAL_POSE)
            add(CriterionCapability.PRIMARY_PERSON_LOCK)
            if (viewQualified) add(CriterionCapability.VIEW_QUALIFIED)
        }
        val samples: List<CriterionEvidenceSample> = deduplicateMonotonicFrames(frames).map { frame ->
            criterionSampler.sample(frame, criterion.featureBinding).evidence
        }
        val result = criterionEngine.evaluate(
            spec = criterion.featureBinding.criterionSpec,
            phaseWindow = phaseWindow,
            samples = samples,
            availableCapabilities = effectiveCapabilities,
            calibration = calibration,
        )
        val contract = criterion.featureBinding.criterionSpec.calibrationContract
        return BoundPoseCriterionResult(
            exerciseSpecSha256 = exerciseSpecSha256,
            phaseArtifactSha256 = phaseDriver.phaseArtifactSha256,
            cycleEpoch = cycleEpoch,
            phaseId = phaseId,
            phaseWindow = phaseWindow,
            viewContractId = criterion.viewContractId,
            viewQualified = viewQualified,
            featureSpecSha256 = criterion.featureBinding.featureSpec.featureSpecSha256,
            runtimeDomainId = contract.runtimeDomainId,
            qualityCalibrationArtifactSha256 = contract.qualityCalibrationArtifactSha256,
            observationContractArtifactSha256 = observationContract.artifactSha256,
            personTrackEpoch = personTrackEpoch,
            atomicResult = result,
        )
    }

    /** Validates signed provenance before applying shadow-safe graph/cue policy. */
    internal fun evaluateCriterionGraph(
        results: Collection<BoundPoseCriterionResult>,
    ): PoseCriterionGraphEvaluation {
        val boundResults = results.toList()
        val duplicateIds = boundResults
            .groupingBy { it.atomicResult.criterionId }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "Duplicate bound criterion results: ${duplicateIds.sorted().joinToString()}"
        }
        require(boundResults.map { it.atomicResult.criterionId }.toSet() == criteriaById.keys) {
            "Bound criterion result ids must exactly match the exercise spec"
        }
        require(boundResults.map(BoundPoseCriterionResult::cycleEpoch).distinct().size == 1) {
            "All criterion results must belong to the same cycle epoch"
        }
        val firstPersonEpoch = boundResults.first().personTrackEpoch
        require(boundResults.all { bound -> bound.personTrackEpoch === firstPersonEpoch }) {
            "All criterion results must belong to the same person-track epoch"
        }

        boundResults.forEach { bound -> validateBoundResult(bound) }
        return criterionGraph.evaluate(
            results = boundResults.map(BoundPoseCriterionResult::atomicResult),
            cueEligibleCriterionIds = cueEligibleCriterionIds,
        )
    }

    private fun validateBoundResult(bound: BoundPoseCriterionResult) {
        val result = bound.atomicResult
        val criterion = requireNotNull(criteriaById[result.criterionId])
        val criterionSpec = criterion.featureBinding.criterionSpec
        val contract = criterionSpec.calibrationContract
        require(bound.exerciseSpecSha256 == exerciseSpecSha256) {
            "Criterion result belongs to another exercise spec"
        }
        require(bound.phaseArtifactSha256 == phaseDriver.phaseArtifactSha256) {
            "Criterion result belongs to another phase artifact"
        }
        require(bound.phaseId in criterion.eligiblePhaseIds) {
            "Criterion result was evaluated in an ineligible phase"
        }
        require(bound.phaseWindow.durationMs == result.windowDurationMs) {
            "Criterion result window duration does not match its provenance"
        }
        require(bound.viewContractId == criterion.viewContractId) {
            "Criterion result view contract does not match"
        }
        if (!bound.viewQualified) {
            require(
                result.state == CriterionState.UNKNOWN &&
                    CriterionCapability.VIEW_QUALIFIED in result.missingCapabilities,
            ) {
                "A mismatched view must remain UNKNOWN with missing view capability"
            }
        }
        require(bound.featureSpecSha256 == criterion.featureBinding.featureSpec.featureSpecSha256) {
            "Criterion result feature AST does not match"
        }
        require(bound.runtimeDomainId == contract.runtimeDomainId) {
            "Criterion result runtime domain does not match"
        }
        require(
            bound.observationContractArtifactSha256 == observationContract.artifactSha256,
        ) {
            "Criterion result observation contract does not match"
        }
        require(
            bound.qualityCalibrationArtifactSha256 == contract.qualityCalibrationArtifactSha256,
        ) {
            "Criterion result quality calibration artifact does not match"
        }
        require(result.evaluatorSpecSha256 == criterionSpec.evaluatorSpecSha256) {
            "Criterion result evaluator spec does not match"
        }
        if (result.state != CriterionState.UNKNOWN) {
            require(
                result.calibrationArtifactSha256 == criterionSpec.approvedCalibrationArtifactSha256,
            ) {
                "Determinate criterion result calibration artifact does not match"
            }
        }
    }

    /** Mirrors phase-engine timestamp semantics: later callbacks with the same timestamp vanish. */
    private fun deduplicateMonotonicFrames(frames: List<PoseFrame>): List<PoseFrame> = buildList {
        var previousTimestampMs: Long? = null
        frames.forEach { frame ->
            val previous = previousTimestampMs
            require(previous == null || frame.timestampMs >= previous) {
                "Criterion frame timestamps must be monotonic"
            }
            if (frame.timestampMs != previous) add(frame)
            previousTimestampMs = frame.timestampMs
        }
    }
}

/**
 * The current session contract evaluates every criterion exactly once per completed cycle.
 * Branch-dependent applicability needs an explicit NOT_APPLICABLE/provenance contract, so a
 * signed exercise spec fails fast until that contract exists instead of silently dropping cues.
 */
private fun validateDeterministicCycle(phaseDriver: PosePhaseDriverBinding) {
    val graph = phaseDriver.engineConfig.graph
    val stateIds = graph.states.keys
    val transitions = graph.transitions
    require(transitions.count { it.completesCycle } == 1) {
        "A runtime exercise spec requires exactly one cycle-completing transition"
    }

    val outgoingCounts = transitions.groupingBy { it.from }.eachCount()
    val incomingCounts = transitions.groupingBy { it.to }.eachCount()
    val invalidStates = stateIds.filter { stateId ->
        outgoingCounts[stateId] != 1 || incomingCounts[stateId] != 1
    }
    require(invalidStates.isEmpty()) {
        "A runtime exercise spec requires one deterministic cycle; invalid phase degrees: " +
            invalidStates.map(PosePhaseStateId::value).sorted().joinToString()
    }

    val visited = linkedSetOf<PosePhaseStateId>()
    var cursor = graph.initialStateId
    repeat(stateIds.size) {
        require(visited.add(cursor)) {
            "The deterministic phase cycle repeats before visiting every phase"
        }
        cursor = transitions.single { transition -> transition.from == cursor }.to
    }
    require(cursor == graph.initialStateId && visited == stateIds) {
        "The deterministic phase cycle must visit every phase exactly once"
    }
}

/** Canonical identity expected to be pinned by a signed exercise manifest. */
internal fun exerciseSpecSha256(
    specId: String,
    specVersion: Int,
    exercise: AiHubExercise,
    observationContract: PoseObservationContract,
    phaseDriver: PosePhaseDriverBinding,
    criteria: List<ExerciseCriterionSpec>,
    criterionGraph: PoseCriterionGraph,
): String = canonicalFieldsSha256(
    buildList {
        add("exerciseSpecSchemaVersion" to "1")
        add("specId" to specId)
        add("specVersion" to specVersion.toString())
        add("aiHubExerciseId" to exercise.id)
        add("observationContractArtifactSha256" to observationContract.artifactSha256)
        add("phaseArtifactSha256" to phaseDriver.phaseArtifactSha256)
        add("featureRuntimeContractSha256" to SIGNED_FEATURE_RUNTIME_CONTRACT_SHA256)
        add("criterionGraphSpecSha256" to criterionGraph.graphSpecSha256)
        criteria.sortedBy(ExerciseCriterionSpec::criterionId).forEachIndexed { index, criterion ->
            val binding = criterion.featureBinding
            val spec = binding.criterionSpec
            val contract = spec.calibrationContract
            add(
                "criterion[$index]Sha256" to canonicalFieldsSha256(
                    listOf(
                        "criterionReleaseSchemaVersion" to "1",
                        "criterionId" to criterion.criterionId,
                        "evaluatorSpecSha256" to spec.evaluatorSpecSha256,
                        "calibrationArtifactSha256" to spec.approvedCalibrationArtifactSha256,
                        "featureSpecSha256" to binding.featureSpec.featureSpecSha256,
                        "qualityCalibrationArtifactSha256" to
                            contract.qualityCalibrationArtifactSha256,
                        "runtimeDomainId" to contract.runtimeDomainId,
                        "eligiblePhaseIdsSha256" to canonicalFieldsSha256(
                            buildList {
                                add("phaseIdSetSchemaVersion" to "1")
                                val phaseIds = criterion.eligiblePhaseIds
                                    .map(PosePhaseStateId::value)
                                    .sorted()
                                add("size" to phaseIds.size.toString())
                                phaseIds.forEachIndexed { phaseIndex, phaseId ->
                                    add("item[$phaseIndex]" to phaseId)
                                }
                            },
                        ),
                        "viewContractId" to criterion.viewContractId,
                        "observability" to criterion.observability.name,
                        "runtimeMode" to criterion.runtimeMode.name,
                    ),
                ),
            )
        }
    },
)
