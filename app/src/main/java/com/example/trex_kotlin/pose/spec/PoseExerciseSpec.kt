package com.example.trex_kotlin.pose.spec

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.criterion.ATTESTED_CRITERION_SAMPLING_CONTRACT_SHA256
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
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
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

/** Temporal extent over which one atomic criterion aggregate is evaluated. */
sealed interface CriterionWindowScope {
    /** One contiguous, backdated phase window. */
    data class Phase(val phaseId: PosePhaseStateId) : CriterionWindowScope

    /** The half-open interval from the initial phase start to the cycle-completing boundary. */
    data object CompletedCycle : CriterionWindowScope
}

/** One criterion's feature, phase, view, calibration, and release contract. */
class ExerciseCriterionSpec(
    val featureBinding: PoseCriterionFeatureBinding,
    val windowScope: CriterionWindowScope,
    val viewContractId: String,
    val observability: CriterionObservability,
    val runtimeMode: CriterionRuntimeMode,
) {
    /** Compatibility projection for phase-bound callers; completed-cycle criteria have no phase. */
    val eligiblePhaseIds: Set<PosePhaseStateId> = Collections.unmodifiableSet(
        when (windowScope) {
            is CriterionWindowScope.Phase -> linkedSetOf(windowScope.phaseId)
            CriterionWindowScope.CompletedCycle -> linkedSetOf()
        },
    )

    val criterionId: String
        get() = featureBinding.criterionSpec.criterionId

    init {
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

    constructor(
        featureBinding: PoseCriterionFeatureBinding,
        eligiblePhaseIds: Set<PosePhaseStateId>,
        viewContractId: String,
        observability: CriterionObservability,
        runtimeMode: CriterionRuntimeMode,
    ) : this(
        featureBinding = featureBinding,
        windowScope = CriterionWindowScope.Phase(
            eligiblePhaseIds.singleOrNull()
                ?: throw IllegalArgumentException(
                    "A phase criterion must bind exactly one contiguous phase window",
                ),
        ),
        viewContractId = viewContractId,
        observability = observability,
        runtimeMode = runtimeMode,
    )
}

/**
 * Provenance envelope produced only after the exact exercise-owned criterion evaluator runs.
 * Graph composition validates every field again; matching a criterion id alone is insufficient.
 */
class BoundPoseCriterionResult internal constructor(
    val exerciseSpecSha256: String,
    val phaseArtifactSha256: String,
    val cycleEpoch: Long,
    val windowScope: CriterionWindowScope,
    val phaseWindow: CriterionPhaseWindow,
    val viewContractId: String,
    val qualifiedViewFrameCount: Int,
    val totalFrameCount: Int,
    val sampledEvidenceSha256: String,
    val featureSpecSha256: String,
    val runtimeDomainId: String,
    val qualityCalibrationArtifactSha256: String,
    val observationContractArtifactSha256: String,
    internal val personTrackEpoch: PosePersonTrackEpoch,
    val atomicResult: PoseCriterionResult,
) {
    val phaseId: PosePhaseStateId?
        get() = (windowScope as? CriterionWindowScope.Phase)?.phaseId

    val viewQualified: Boolean
        get() = totalFrameCount > 0 && qualifiedViewFrameCount == totalFrameCount

    init {
        require(totalFrameCount >= 0)
        require(qualifiedViewFrameCount in 0..totalFrameCount)
        require(SPEC_SHA256.matches(sampledEvidenceSha256))
    }
}

/**
 * Immutable, content-addressed runtime package for one exercise from the canonical AI Hub catalog.
 *
 * No exercise-name branch or target default exists here. The top-level SHA binds the phase
 * artifact, every criterion/release/view choice, and the graph policy. New user-facing cues can
 * only be selected from provenance envelopes evaluated by this exact package. The content hash is
 * a drift-detection identity, not a detached signature or release authorization.
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
            val phaseId = (criterion.windowScope as? CriterionWindowScope.Phase)?.phaseId
            require(phaseId == null || phaseId in phaseIds) {
                "Criterion ${criterion.criterionId} references unknown phase: $phaseId"
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
        val unsupportedCriterionViews = this.criteria.filter { criterion ->
            criterion.viewContractId !in observationContract.allowedViewContractIds
        }
        require(unsupportedCriterionViews.isEmpty()) {
            "Observation contract cannot attest criterion views: " +
                unsupportedCriterionViews.map(ExerciseCriterionSpec::criterionId)
                    .sorted().joinToString()
        }
        val incompatibleSamplingContracts = this.criteria.filter { criterion ->
            criterion.featureBinding.criterionSpec.calibrationContract.samplingContractSha256 !=
                ATTESTED_CRITERION_SAMPLING_CONTRACT_SHA256
        }
        require(incompatibleSamplingContracts.isEmpty()) {
            "Every criterion calibration must match the attested sampling contract: " +
                incompatibleSamplingContracts.map(ExerciseCriterionSpec::criterionId)
                    .sorted().joinToString()
        }
    }

    fun criterion(id: String): ExerciseCriterionSpec? = criteriaById[id]

    /** Creates isolated phase state for one camera/session lifecycle. */
    internal fun newPhaseEngine(): PosePhaseEngine = PosePhaseEngine(phaseDriver.engineConfig)

    /** Measures the hash-pinned phase feature; no arbitrary scalar can enter through this API. */
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
        windowScope: CriterionWindowScope,
        phaseWindow: CriterionPhaseWindow,
        observations: List<AttestedPoseObservation>,
        personTrackEpoch: PosePersonTrackEpoch,
        calibration: CriterionAggregateCalibration?,
    ): BoundPoseCriterionResult {
        require(cycleEpoch >= 0L) { "cycleEpoch must be non-negative" }
        val criterion = requireNotNull(criteriaById[criterionId]) {
            "Unknown exercise criterion: $criterionId"
        }
        require(windowScope == criterion.windowScope) {
            "Criterion $criterionId is not eligible for window scope $windowScope"
        }
        require(
            personTrackEpoch.source.contract.artifactSha256 ==
                observationContract.artifactSha256,
        ) {
            "Person-track epoch was minted outside the hash-pinned observation contract"
        }
        val distinctObservations = deduplicateMonotonicObservations(observations)
        require(distinctObservations.all { observation ->
            observation.isFrom(personTrackEpoch.source) &&
                observation.personTrackEpoch === personTrackEpoch
        }) {
            "Criterion observations must share the bound source and person-track epoch"
        }
        val qualifiedViewFrameCount = distinctObservations.count { observation ->
            observation.isViewQualified(criterion.viewContractId)
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
            if (qualifiedViewFrameCount > 0) add(CriterionCapability.VIEW_QUALIFIED)
        }
        val sampledFrames = distinctObservations.map { observation ->
            if (observation.isViewQualified(criterion.viewContractId)) {
                val sample = criterionSampler.sample(observation.frame, criterion.featureBinding)
                SampledCriterionFrame(
                    evidence = sample.evidence,
                    abstentionReason = sample.featureUnknownReason?.name
                        ?: sample.qualityUnknownReason?.name,
                )
            } else {
                SampledCriterionFrame(
                    evidence = CriterionEvidenceSample(
                        timestampMs = observation.frame.timestampMs,
                        measurement = null,
                        qualityWeight = 0.0,
                    ),
                    abstentionReason = "VIEW_UNQUALIFIED",
                )
            }
        }
        val samples = sampledFrames.map(SampledCriterionFrame::evidence)
        val sampledEvidenceSha256 = canonicalFieldsSha256(
            buildList {
                add("criterionSampledEvidenceSchemaVersion" to "1")
                add("viewContractId" to criterion.viewContractId)
                add("sampleCount" to sampledFrames.size.toString())
                sampledFrames.forEachIndexed { index, sampled ->
                    add("sample[$index].timestampMs" to sampled.evidence.timestampMs.toString())
                    add(
                        "sample[$index].measurement" to
                            sampled.evidence.measurement?.let(java.lang.Double::toHexString)
                            .orEmpty(),
                    )
                    add(
                        "sample[$index].qualityWeight" to
                            java.lang.Double.toHexString(sampled.evidence.qualityWeight),
                    )
                    add("sample[$index].abstentionReason" to sampled.abstentionReason.orEmpty())
                }
            },
        )
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
            windowScope = windowScope,
            phaseWindow = phaseWindow,
            viewContractId = criterion.viewContractId,
            qualifiedViewFrameCount = qualifiedViewFrameCount,
            totalFrameCount = distinctObservations.size,
            sampledEvidenceSha256 = sampledEvidenceSha256,
            featureSpecSha256 = criterion.featureBinding.featureSpec.featureSpecSha256,
            runtimeDomainId = contract.runtimeDomainId,
            qualityCalibrationArtifactSha256 = contract.qualityCalibrationArtifactSha256,
            observationContractArtifactSha256 = observationContract.artifactSha256,
            personTrackEpoch = personTrackEpoch,
            atomicResult = result,
        )
    }

    /** Validates content-addressed provenance before applying shadow-safe graph/cue policy. */
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
        require(bound.windowScope == criterion.windowScope) {
            "Criterion result was evaluated in an ineligible window scope"
        }
        require(bound.phaseWindow.durationMs == result.windowDurationMs) {
            "Criterion result window duration does not match its provenance"
        }
        require(bound.viewContractId == criterion.viewContractId) {
            "Criterion result view contract does not match"
        }
        if (bound.qualifiedViewFrameCount == 0) {
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
    private fun deduplicateMonotonicObservations(
        observations: List<AttestedPoseObservation>,
    ): List<AttestedPoseObservation> = buildList {
        var previousTimestampMs: Long? = null
        observations.forEach { observation ->
            val frame = observation.frame
            val previous = previousTimestampMs
            require(previous == null || frame.timestampMs >= previous) {
                "Criterion frame timestamps must be monotonic"
            }
            if (frame.timestampMs != previous) add(observation)
            previousTimestampMs = frame.timestampMs
        }
    }
}

private data class SampledCriterionFrame(
    val evidence: CriterionEvidenceSample,
    val abstentionReason: String?,
)

/**
 * The current session contract evaluates every criterion exactly once per completed cycle.
 * Branch-dependent applicability needs an explicit NOT_APPLICABLE/provenance contract, so a
 * hash-pinned exercise spec fails fast until that contract exists instead of silently dropping cues.
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

/** Canonical identity expected to be pinned by an independently authorized exercise manifest. */
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
        add("exerciseSpecSchemaVersion" to "2")
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
                        "criterionReleaseSchemaVersion" to "2",
                        "criterionId" to criterion.criterionId,
                        "evaluatorSpecSha256" to spec.evaluatorSpecSha256,
                        "calibrationArtifactSha256" to spec.approvedCalibrationArtifactSha256,
                        "featureSpecSha256" to binding.featureSpec.featureSpecSha256,
                        "qualityCalibrationArtifactSha256" to
                            contract.qualityCalibrationArtifactSha256,
                        "samplingContractSha256" to contract.samplingContractSha256,
                        "runtimeDomainId" to contract.runtimeDomainId,
                        "windowScopeSha256" to criterion.windowScope.canonicalSha256(),
                        "viewContractId" to criterion.viewContractId,
                        "observability" to criterion.observability.name,
                        "runtimeMode" to criterion.runtimeMode.name,
                    ),
                ),
            )
        }
    },
)

private fun CriterionWindowScope.canonicalSha256(): String = canonicalFieldsSha256(
    when (this) {
        is CriterionWindowScope.Phase -> listOf(
            "criterionWindowScopeSchemaVersion" to "1",
            "kind" to "PHASE",
            "phaseId" to phaseId.value,
        )
        CriterionWindowScope.CompletedCycle -> listOf(
            "criterionWindowScopeSchemaVersion" to "1",
            "kind" to "COMPLETED_CYCLE",
        )
    },
)
