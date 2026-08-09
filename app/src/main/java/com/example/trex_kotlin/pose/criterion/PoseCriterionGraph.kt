package com.example.trex_kotlin.pose.criterion

import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import java.util.Collections

/**
 * Product-level importance of a correction candidate.
 *
 * The explicit priority avoids coupling safety behavior to enum declaration order.
 */
enum class CriterionSeverity(internal val priority: Int) {
    ADVISORY(0),
    CORRECTION(100),
    SAFETY(200),
}

/**
 * A criterion's causal prerequisites and directional feedback contract.
 *
 * Cue codes are opaque policy identifiers. This layer deliberately does not contain localized
 * text, audio, or UI behavior. A null directional code means that the corresponding failure can
 * gate descendants but is not itself user-facing.
 */
class CriterionNodeSpec(
    val id: String,
    val severity: CriterionSeverity,
    dependencies: Set<String> = emptySet(),
    val lowSideCueCode: String? = null,
    val highSideCueCode: String? = null,
    suppresses: Set<String> = emptySet(),
) {
    val dependencies: Set<String> = Collections.unmodifiableSet(LinkedHashSet(dependencies))
    val suppresses: Set<String> = Collections.unmodifiableSet(LinkedHashSet(suppresses))

    init {
        require(id.isNotBlank()) { "Criterion node id must not be blank" }
        require(lowSideCueCode == null || lowSideCueCode.isNotBlank()) {
            "Low-side cue code must be null or non-blank"
        }
        require(highSideCueCode == null || highSideCueCode.isNotBlank()) {
            "High-side cue code must be null or non-blank"
        }
        require(id !in this.dependencies) { "A criterion cannot depend on itself: $id" }
        require(id !in this.suppresses) { "A criterion cannot suppress itself: $id" }
        require((this.dependencies intersect this.suppresses).isEmpty()) {
            "A criterion cannot both depend on and suppress the same node: $id"
        }
        require(this.dependencies.none(String::isBlank)) {
            "Dependency ids must not be blank"
        }
        require(this.suppresses.none(String::isBlank)) {
            "Suppressed criterion ids must not be blank"
        }
    }

    internal fun cueCodeFor(failRegion: CriterionFailRegion): String? = when (failRegion) {
        CriterionFailRegion.LOW_SIDE -> lowSideCueCode
        CriterionFailRegion.HIGH_SIDE -> highSideCueCode
    }
}

/** Graph-level truth state; atomic results remain available without mutation. */
enum class CriterionGraphStatus {
    PASS,
    FAIL,
    UNKNOWN,

    /** A prerequisite was unknown, so the dependent result is not actionable. */
    UNKNOWN_CONFOUNDED,
}

enum class CriterionCueSuppressionReason {
    NOT_RELEASED,
    UNKNOWN_DEPENDENCY,
    FAILED_DEPENDENCY,
    EXPLICIT_SUPPRESSION,
    NO_DIRECTIONAL_CUE,
}

data class CriterionNodeEvaluation(
    val spec: CriterionNodeSpec,
    /** The exact engine result supplied by the caller; it is never rewritten by the graph. */
    val atomicResult: PoseCriterionResult,
    val graphStatus: CriterionGraphStatus,
    val isRootCause: Boolean,
    val cueCode: String?,
    val cueEligible: Boolean,
    val dependencyBlockers: Set<String>,
    val suppressingCriterionIds: Set<String>,
    val cueSuppressionReasons: Set<CriterionCueSuppressionReason>,
)

data class CriterionCueCandidate(
    val criterionId: String,
    val cueCode: String,
    val severity: CriterionSeverity,
    val failRegion: CriterionFailRegion,
)

data class PoseCriterionGraphEvaluation(
    val status: CriterionGraphStatus,
    val nodes: List<CriterionNodeEvaluation>,
    /** At most one correction is emitted for a graph evaluation. */
    val selectedCue: CriterionCueCandidate?,
)

/**
 * Deterministic dependency and cue policy over already-calibrated atomic criterion results.
 *
 * Dependency edges and explicit suppression edges are validated as separate DAGs. Keeping both
 * acyclic prevents circular causal explanations and mutual cue cancellation. Runtime evaluation
 * uses strong Kleene behavior for unknown prerequisites: a dependent node becomes
 * [CriterionGraphStatus.UNKNOWN_CONFOUNDED], never PASS. A failed prerequisite remains the known
 * root cause and suppresses every descendant cue.
 *
 * [evaluate] requires an explicit released-criterion set. Non-released (for example SHADOW)
 * results remain visible in [PoseCriterionGraphEvaluation.nodes] for comparison, but form no
 * dependency or suppression edge into a released node, are never root causes or cue candidates,
 * and do not participate in the released aggregate status.
 */
class PoseCriterionGraph(nodeSpecs: List<CriterionNodeSpec>) {
    private val specs: List<CriterionNodeSpec> =
        Collections.unmodifiableList(ArrayList(nodeSpecs))
    private val specsById: Map<String, CriterionNodeSpec>
    private val declarationOrder: Map<String, Int>
    private val topologicalOrder: List<String>

    /** Stable criterion identity exposed for ExerciseSpec/result-contract validation. */
    val nodeIds: Set<String>
        get() = Collections.unmodifiableSet(LinkedHashSet(specsById.keys))

    /**
     * Canonical identity of graph topology, release ordering, severity, and directional cue codes.
     * Declaration order is intentionally included because it is the deterministic cue tie-breaker.
     */
    val graphSpecSha256: String

    init {
        require(specs.isNotEmpty()) { "A criterion graph must contain at least one node" }
        val duplicateIds = specs
            .groupingBy(CriterionNodeSpec::id)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "Duplicate criterion node ids: ${duplicateIds.sorted().joinToString()}"
        }

        specsById = specs.associateBy(CriterionNodeSpec::id)
        declarationOrder = specs.mapIndexed { index, spec -> spec.id to index }.toMap()
        validateReferences()
        topologicalOrder = topologicalSort(
            edges = specsById.mapValues { (_, spec) -> spec.dependencies },
            graphName = "dependency",
        )
        topologicalSort(
            edges = specsById.mapValues { (_, spec) -> spec.suppresses },
            graphName = "suppression",
        )
        graphSpecSha256 = canonicalFieldsSha256(
            buildList {
                add("criterionGraphSchemaVersion" to "1")
                specs.forEachIndexed { index, spec ->
                    add(
                        "node[$index]Sha256" to canonicalFieldsSha256(
                            listOf(
                                "nodeSchemaVersion" to "1",
                                "id" to spec.id,
                                "severity" to spec.severity.name,
                                "severityPriority" to spec.severity.priority.toString(),
                                "dependenciesSha256" to canonicalStringSetSha256(
                                    spec.dependencies,
                                ),
                                "hasLowCue" to (spec.lowSideCueCode != null).toString(),
                                "lowCue" to spec.lowSideCueCode.orEmpty(),
                                "hasHighCue" to (spec.highSideCueCode != null).toString(),
                                "highCue" to spec.highSideCueCode.orEmpty(),
                                "suppressesSha256" to canonicalStringSetSha256(spec.suppresses),
                            ),
                        ),
                    )
                }
            },
        )
    }

    internal fun evaluate(
        results: Collection<PoseCriterionResult>,
        cueEligibleCriterionIds: Set<String>,
    ): PoseCriterionGraphEvaluation {
        // The explicit set is the release-policy boundary, not merely a final cue filter. Copy it
        // before evaluation so caller mutation cannot change policy partway through a decision.
        val releasedCriterionIds = cueEligibleCriterionIds.toSet()
        val unknownCueIds = releasedCriterionIds - nodeIds
        require(unknownCueIds.isEmpty()) {
            "Cue eligibility contains unknown criterion ids: ${unknownCueIds.sorted().joinToString()}"
        }
        validateReleaseClosure(releasedCriterionIds)
        val resultsById = validateAndIndexResults(results)
        val statusById = linkedMapOf<String, CriterionGraphStatus>()
        val dependencyBlockersById = mutableMapOf<String, Set<String>>()
        val failedDependencyBlockersById = mutableMapOf<String, Set<String>>()

        topologicalOrder.forEach { id ->
            val spec = requireNotNull(specsById[id])
            val result = requireNotNull(resultsById[id])
            val policyDependencies = spec.dependencies
            val unknownDependencies = buildSet {
                policyDependencies.forEach { dependencyId ->
                    when (statusById[dependencyId]) {
                        CriterionGraphStatus.UNKNOWN -> add(dependencyId)
                        CriterionGraphStatus.UNKNOWN_CONFOUNDED -> {
                            addAll(dependencyBlockersById.getValue(dependencyId))
                        }
                        CriterionGraphStatus.PASS,
                        CriterionGraphStatus.FAIL -> Unit
                        null -> error("Dependency must be evaluated before its child")
                    }
                }
            }
            val failedDependencies = buildSet {
                policyDependencies.forEach { dependencyId ->
                    val inheritedFailures = failedDependencyBlockersById.getValue(dependencyId)
                    if (
                        statusById[dependencyId] == CriterionGraphStatus.FAIL &&
                        inheritedFailures.isEmpty()
                    ) {
                        add(dependencyId)
                    }
                    addAll(inheritedFailures)
                }
            }
            dependencyBlockersById[id] = unknownDependencies
            failedDependencyBlockersById[id] = failedDependencies
            statusById[id] = if (unknownDependencies.isNotEmpty()) {
                CriterionGraphStatus.UNKNOWN_CONFOUNDED
            } else if (failedDependencies.isNotEmpty()) {
                CriterionGraphStatus.FAIL
            } else {
                result.state.toGraphStatus()
            }
        }

        val rootFailureIds = specs
            .asSequence()
            .filter { spec -> spec.id in releasedCriterionIds }
            .filter { spec -> resultsById.getValue(spec.id).state == CriterionState.FAIL }
            .filter { spec -> dependencyBlockersById.getValue(spec.id).isEmpty() }
            .filter { spec -> failedDependencyBlockersById.getValue(spec.id).isEmpty() }
            .map(CriterionNodeSpec::id)
            .toSet()

        val explicitSuppressorsByTarget = mutableMapOf<String, MutableSet<String>>()
        rootFailureIds.forEach { rootId ->
            specsById.getValue(rootId).suppresses.forEach { targetId ->
                explicitSuppressorsByTarget.getOrPut(targetId, ::linkedSetOf).add(rootId)
            }
        }

        val nodeEvaluations = specs.map { spec ->
            val result = resultsById.getValue(spec.id)
            val unknownDependencies = dependencyBlockersById.getValue(spec.id)
            val failedDependencies = failedDependencyBlockersById.getValue(spec.id)
            val explicitSuppressors = explicitSuppressorsByTarget[spec.id].orEmpty().toSet()
            val cueCode = result.failRegion?.let(spec::cueCodeFor)
            val suppressionReasons = buildSet {
                if (spec.id !in releasedCriterionIds) {
                    add(CriterionCueSuppressionReason.NOT_RELEASED)
                }
                if (unknownDependencies.isNotEmpty()) {
                    add(CriterionCueSuppressionReason.UNKNOWN_DEPENDENCY)
                }
                if (failedDependencies.isNotEmpty()) {
                    add(CriterionCueSuppressionReason.FAILED_DEPENDENCY)
                }
                if (explicitSuppressors.isNotEmpty()) {
                    add(CriterionCueSuppressionReason.EXPLICIT_SUPPRESSION)
                }
                if (result.state == CriterionState.FAIL && cueCode == null) {
                    add(CriterionCueSuppressionReason.NO_DIRECTIONAL_CUE)
                }
            }
            val isRootCause = spec.id in releasedCriterionIds &&
                result.state == CriterionState.FAIL &&
                unknownDependencies.isEmpty() &&
                failedDependencies.isEmpty()
            CriterionNodeEvaluation(
                spec = spec,
                atomicResult = result,
                graphStatus = statusById.getValue(spec.id),
                isRootCause = isRootCause,
                cueCode = cueCode,
                cueEligible = isRootCause &&
                    cueCode != null &&
                    explicitSuppressors.isEmpty(),
                dependencyBlockers = unknownDependencies + failedDependencies,
                suppressingCriterionIds = explicitSuppressors,
                cueSuppressionReasons = suppressionReasons,
            )
        }

        val selectedNode = nodeEvaluations
            .asSequence()
            .filter(CriterionNodeEvaluation::cueEligible)
            .sortedWith(
                compareByDescending<CriterionNodeEvaluation> { it.spec.severity.priority }
                    .thenBy { declarationOrder.getValue(it.spec.id) },
            )
            .firstOrNull()
        val selectedCue = selectedNode?.let { node ->
            CriterionCueCandidate(
                criterionId = node.spec.id,
                cueCode = requireNotNull(node.cueCode),
                severity = node.spec.severity,
                failRegion = requireNotNull(node.atomicResult.failRegion),
            )
        }

        return PoseCriterionGraphEvaluation(
            status = aggregateStatus(
                nodeEvaluations.filter { node -> node.spec.id in releasedCriterionIds },
            ),
            nodes = nodeEvaluations,
            selectedCue = selectedCue,
        )
    }

    /**
     * A released decision may not depend on a shadow node whose truth is intentionally excluded.
     * Likewise, shadow suppression of a released node is rejected instead of being silently
     * ignored. This makes the release subgraph causally closed.
     */
    internal fun validateReleaseClosure(releasedCriterionIds: Set<String>) {
        val released = releasedCriterionIds.toSet()
        val unknownIds = released - nodeIds
        require(unknownIds.isEmpty()) {
            "Release policy contains unknown criterion ids: ${unknownIds.sorted().joinToString()}"
        }
        specs.filter { it.id in released }.forEach { spec ->
            val shadowDependencies = spec.dependencies - released
            require(shadowDependencies.isEmpty()) {
                "Released criterion ${spec.id} depends on shadow criteria: " +
                    shadowDependencies.sorted().joinToString()
            }
        }
        specs.filter { it.id !in released }.forEach { spec ->
            val releasedTargets = spec.suppresses intersect released
            require(releasedTargets.isEmpty()) {
                "Shadow criterion ${spec.id} suppresses released criteria: " +
                    releasedTargets.sorted().joinToString()
            }
        }
    }

    private fun validateReferences() {
        val knownIds = specsById.keys
        specs.forEach { spec ->
            val missingDependencies = spec.dependencies - knownIds
            require(missingDependencies.isEmpty()) {
                "Criterion ${spec.id} has missing dependencies: " +
                    missingDependencies.sorted().joinToString()
            }
            val missingSuppressionTargets = spec.suppresses - knownIds
            require(missingSuppressionTargets.isEmpty()) {
                "Criterion ${spec.id} has missing suppression targets: " +
                    missingSuppressionTargets.sorted().joinToString()
            }
        }
    }

    private fun validateAndIndexResults(
        results: Collection<PoseCriterionResult>,
    ): Map<String, PoseCriterionResult> {
        val duplicateIds = results
            .groupingBy(PoseCriterionResult::criterionId)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "Duplicate criterion results: ${duplicateIds.sorted().joinToString()}"
        }
        val resultsById = results.associateBy(PoseCriterionResult::criterionId)
        val missingResults = specsById.keys - resultsById.keys
        val unexpectedResults = resultsById.keys - specsById.keys
        require(missingResults.isEmpty() && unexpectedResults.isEmpty()) {
            buildString {
                append("Criterion result ids must exactly match graph ids")
                if (missingResults.isNotEmpty()) {
                    append("; missing=").append(missingResults.sorted().joinToString())
                }
                if (unexpectedResults.isNotEmpty()) {
                    append("; unexpected=").append(unexpectedResults.sorted().joinToString())
                }
            }
        }
        return resultsById
    }

    private fun topologicalSort(
        edges: Map<String, Set<String>>,
        graphName: String,
    ): List<String> {
        val state = mutableMapOf<String, VisitState>()
        val output = mutableListOf<String>()

        fun visit(id: String, path: MutableList<String>) {
            when (state[id]) {
                VisitState.VISITED -> return
                VisitState.VISITING -> {
                    val cycleStart = path.indexOf(id).coerceAtLeast(0)
                    val cycle = (path.subList(cycleStart, path.size) + id).joinToString(" -> ")
                    throw IllegalArgumentException("$graphName graph contains a cycle: $cycle")
                }
                null -> Unit
            }
            state[id] = VisitState.VISITING
            path.add(id)
            edges.getValue(id)
                .sortedBy(declarationOrder::getValue)
                .forEach { dependencyId -> visit(dependencyId, path) }
            path.removeAt(path.lastIndex)
            state[id] = VisitState.VISITED
            output.add(id)
        }

        specs.forEach { spec -> visit(spec.id, mutableListOf()) }
        return output
    }

    private fun aggregateStatus(nodes: List<CriterionNodeEvaluation>): CriterionGraphStatus = when {
        nodes.isEmpty() -> CriterionGraphStatus.UNKNOWN
        nodes.any { it.graphStatus == CriterionGraphStatus.FAIL } -> CriterionGraphStatus.FAIL
        nodes.any { it.graphStatus == CriterionGraphStatus.UNKNOWN_CONFOUNDED } -> {
            CriterionGraphStatus.UNKNOWN_CONFOUNDED
        }
        nodes.any { it.graphStatus == CriterionGraphStatus.UNKNOWN } -> CriterionGraphStatus.UNKNOWN
        else -> CriterionGraphStatus.PASS
    }

    private enum class VisitState {
        VISITING,
        VISITED,
    }
}

private fun CriterionState.toGraphStatus(): CriterionGraphStatus = when (this) {
    CriterionState.PASS -> CriterionGraphStatus.PASS
    CriterionState.FAIL -> CriterionGraphStatus.FAIL
    CriterionState.UNKNOWN -> CriterionGraphStatus.UNKNOWN
}

private fun canonicalStringSetSha256(values: Set<String>): String = canonicalFieldsSha256(
    buildList {
        add("stringSetSchemaVersion" to "1")
        add("size" to values.size.toString())
        values.sorted().forEachIndexed { index, value -> add("item[$index]" to value) }
    },
)
