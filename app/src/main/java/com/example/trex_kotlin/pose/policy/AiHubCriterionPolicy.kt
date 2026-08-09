package com.example.trex_kotlin.pose.policy

import com.example.trex_kotlin.catalog.AiHubCriterionSourceCatalog
import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import java.util.Collections

private val POLICY_SHA256 = Regex("^[0-9a-f]{64}$")
private val SOURCE_CONDITION_ID = Regex("^aihub-exact-sha256-[0-9a-f]{64}$")
private val BINDING_ID = Regex("^aihub-binding-sha256-[0-9a-f]{64}$")
private val VERSIONED_POLICY_ID = Regex("^[a-z0-9][a-z0-9._:/-]*\\.v[1-9][0-9]*$")
private val REASON_CODE = Regex("^[A-Z][A-Z0-9_]*$")
private val EVIDENCE_REF = Regex("^[a-z0-9][a-z0-9._/-]*@sha256:[0-9a-f]{64}$")

private const val PERSON_LOCK_CAPABILITY = "trex.capability.primary-person-lock.v1"
private const val VIEW_QUALIFIED_CAPABILITY = "trex.capability.view-qualified.v1"

/** Engineering taxonomy review only; never expert, clinical, Gold, or release approval. */
enum class AiHubCriterionReviewState {
    REVIEWED_ENGINEERING_V1,
    SOURCE_AMBIGUOUS_REQUIRES_ADJUDICATION,
    UNREVIEWED,
}

/** Policy inventory cannot authorize an evaluator or user-facing cue. */
enum class AiHubCriterionReleaseState {
    CATALOG_ONLY,
}

/** Construct observability is independent from statistical calibration quality. */
enum class AiHubCriterionObservability {
    DIRECT,
    PROXY_UNVALIDATED,
    PROXY_GOLD_VALIDATED,
    NOT_OBSERVABLE,
}

enum class AiHubCriterionPhaseApplicabilityState {
    BOUND,
    NOT_APPLICABLE,
}

class AiHubCriterionPhaseApplicability(
    val state: AiHubCriterionPhaseApplicabilityState,
    phaseRoleIds: Collection<String>,
) {
    val phaseRoleIds: List<String> = immutablePolicyList(phaseRoleIds)

    init {
        requireSortedVersionedIds(this.phaseRoleIds, "phaseRoleIds")
        when (state) {
            AiHubCriterionPhaseApplicabilityState.BOUND ->
                require(this.phaseRoleIds.isNotEmpty()) {
                    "A bound criterion must declare at least one generic phase role"
                }
            AiHubCriterionPhaseApplicabilityState.NOT_APPLICABLE ->
                require(this.phaseRoleIds.isEmpty()) {
                    "A non-applicable phase policy cannot declare phase roles"
                }
        }
    }
}

enum class AiHubCriterionSidePolicyKind {
    MIDLINE,
    GLOBAL_BODY,
    BILATERAL_COUPLED,
    BILATERAL_INDEPENDENT,
    ACTIVE_LIMB,
    LEAD_LIMB,
    TRAIL_LIMB,
    ALTERNATING_PAIR,
    CONTRALATERAL_PAIR,
    NOT_APPLICABLE,
}

class AiHubCriterionSidePolicy(
    val kind: AiHubCriterionSidePolicyKind,
    val roleResolverContractId: String?,
) {
    init {
        val requiresResolver = kind in setOf(
            AiHubCriterionSidePolicyKind.ACTIVE_LIMB,
            AiHubCriterionSidePolicyKind.LEAD_LIMB,
            AiHubCriterionSidePolicyKind.TRAIL_LIMB,
            AiHubCriterionSidePolicyKind.ALTERNATING_PAIR,
            AiHubCriterionSidePolicyKind.CONTRALATERAL_PAIR,
        )
        if (requiresResolver) {
            require(roleResolverContractId != null &&
                VERSIONED_POLICY_ID.matches(roleResolverContractId)) {
                "$kind requires a versioned roleResolverContractId"
            }
        } else {
            require(roleResolverContractId == null) {
                "$kind must not declare a roleResolverContractId"
            }
        }
    }
}

enum class AiHubCriterionViewApplicabilityState {
    QUALIFIED_VIEW_REQUIRED,
    NO_CAMERA_VIEW_SUFFICIENT,
    NOT_APPLICABLE,
}

class AiHubCriterionViewApplicability(
    val state: AiHubCriterionViewApplicabilityState,
    viewContractIds: Collection<String>,
) {
    val viewContractIds: List<String> = immutablePolicyList(viewContractIds)

    init {
        requireSortedVersionedIds(this.viewContractIds, "viewContractIds")
        when (state) {
            AiHubCriterionViewApplicabilityState.QUALIFIED_VIEW_REQUIRED ->
                require(this.viewContractIds.isNotEmpty()) {
                    "A camera criterion must declare at least one candidate view contract"
                }
            AiHubCriterionViewApplicabilityState.NO_CAMERA_VIEW_SUFFICIENT,
            AiHubCriterionViewApplicabilityState.NOT_APPLICABLE,
            -> require(this.viewContractIds.isEmpty()) {
                "$state must not declare camera view contracts"
            }
        }
    }
}

enum class AiHubCriterionCalibrationProvenanceState {
    NO_APPROVED_ARTIFACT,
}

class AiHubCriterionCalibrationProvenance(
    val state: AiHubCriterionCalibrationProvenanceState,
    val artifactSha256: String?,
    val runtimeDomainId: String?,
    evidenceRefs: Collection<String>,
) {
    val evidenceRefs: List<String> = immutablePolicyList(evidenceRefs)

    init {
        requireSortedEvidenceRefs(this.evidenceRefs, "calibration evidenceRefs")
        require(this.evidenceRefs.isNotEmpty()) {
            "Calibration provenance must state the evidence for its current state"
        }
        when (state) {
            AiHubCriterionCalibrationProvenanceState.NO_APPROVED_ARTIFACT -> {
                require(artifactSha256 == null && runtimeDomainId == null) {
                    "NO_APPROVED_ARTIFACT cannot carry an artifact or runtime domain"
                }
            }
        }
    }
}

/** Complete engineering interpretation for one reviewed exercise-condition binding. */
class AiHubCriterionInterpretation(
    val semanticId: String,
    val semanticFamilyId: String,
    val measurementConstructId: String,
    val claimBoundary: String,
    val observability: AiHubCriterionObservability,
    val phaseApplicability: AiHubCriterionPhaseApplicability,
    val sidePolicy: AiHubCriterionSidePolicy,
    val viewApplicability: AiHubCriterionViewApplicability,
    requiredCapabilityIds: Collection<String>,
    val calibrationProvenance: AiHubCriterionCalibrationProvenance,
    unsupportedReasonCodes: Collection<String>,
    reviewEvidenceRefs: Collection<String>,
) {
    val requiredCapabilityIds: List<String> = immutablePolicyList(requiredCapabilityIds)
    val unsupportedReasonCodes: List<String> = immutablePolicyList(unsupportedReasonCodes)
    val reviewEvidenceRefs: List<String> = immutablePolicyList(reviewEvidenceRefs)

    init {
        require(VERSIONED_POLICY_ID.matches(semanticId)) {
            "semanticId must be a lowercase versioned identifier"
        }
        require(VERSIONED_POLICY_ID.matches(semanticFamilyId)) {
            "semanticFamilyId must be a lowercase versioned identifier"
        }
        require(VERSIONED_POLICY_ID.matches(measurementConstructId)) {
            "measurementConstructId must be a lowercase versioned identifier"
        }
        require(claimBoundary.isNotBlank()) { "claimBoundary must not be blank" }
        require(phaseApplicability.state == AiHubCriterionPhaseApplicabilityState.BOUND) {
            "A reviewed interpretation must bind a generic phase role"
        }
        require(sidePolicy.kind != AiHubCriterionSidePolicyKind.NOT_APPLICABLE) {
            "A reviewed interpretation must bind an explicit side policy"
        }
        requireSortedVersionedIds(this.requiredCapabilityIds, "requiredCapabilityIds")
        requireSortedReasonCodes(this.unsupportedReasonCodes, "unsupportedReasonCodes")
        require(this.unsupportedReasonCodes.isNotEmpty()) {
            "CATALOG_ONLY interpretation must explain why it cannot be released"
        }
        requireSortedEvidenceRefs(this.reviewEvidenceRefs, "reviewEvidenceRefs")
        require(this.reviewEvidenceRefs.isNotEmpty()) {
            "A reviewed interpretation must retain review provenance"
        }

        when (observability) {
            AiHubCriterionObservability.DIRECT,
            AiHubCriterionObservability.PROXY_UNVALIDATED,
            -> {
                require(
                    viewApplicability.state ==
                        AiHubCriterionViewApplicabilityState.QUALIFIED_VIEW_REQUIRED,
                ) {
                    "A camera measurement requires a qualified candidate view"
                }
                require(PERSON_LOCK_CAPABILITY in this.requiredCapabilityIds) {
                    "A camera measurement requires primary-person lock"
                }
                require(VIEW_QUALIFIED_CAPABILITY in this.requiredCapabilityIds) {
                    "A camera measurement requires view qualification"
                }
            }
            AiHubCriterionObservability.PROXY_GOLD_VALIDATED ->
                require(false) {
                    "A Gold-validated proxy cannot use NO_APPROVED_ARTIFACT"
                }
            AiHubCriterionObservability.NOT_OBSERVABLE ->
                require(
                    viewApplicability.state ==
                        AiHubCriterionViewApplicabilityState.NO_CAMERA_VIEW_SUFFICIENT,
                ) {
                    "A non-observable construct must declare that no camera view is sufficient"
                }
        }
    }
}

/** One exact `(exercise, sourceConditionId)` decision; never an executable evaluator. */
class AiHubCriterionPolicyBinding internal constructor(
    val bindingId: String,
    val exercise: AiHubExercise,
    val sourceConditionId: String,
    val reviewState: AiHubCriterionReviewState,
    val releaseState: AiHubCriterionReleaseState,
    val interpretation: AiHubCriterionInterpretation?,
    reasonCodes: Collection<String>,
    decisionEvidenceRefs: Collection<String>,
    approvedBindingPolicySha256: String,
) {
    val reasonCodes: List<String> = immutablePolicyList(reasonCodes)
    val decisionEvidenceRefs: List<String> = immutablePolicyList(decisionEvidenceRefs)
    val bindingPolicySha256: String

    init {
        require(BINDING_ID.matches(bindingId)) { "bindingId must contain a full SHA-256" }
        require(bindingId == aiHubCriterionBindingId(exercise, sourceConditionId)) {
            "bindingId must be derived from the exact exercise-condition pair"
        }
        require(SOURCE_CONDITION_ID.matches(sourceConditionId)) {
            "sourceConditionId must use exact source identity"
        }
        require(releaseState == AiHubCriterionReleaseState.CATALOG_ONLY) {
            "Policy inventory cannot authorize runtime release"
        }
        requireSortedReasonCodes(this.reasonCodes, "reasonCodes")
        requireSortedEvidenceRefs(this.decisionEvidenceRefs, "decisionEvidenceRefs")
        require(this.decisionEvidenceRefs.isNotEmpty()) {
            "Every binding decision must retain evidence provenance"
        }
        when (reviewState) {
            AiHubCriterionReviewState.REVIEWED_ENGINEERING_V1 -> {
                require(interpretation != null) {
                    "A reviewed binding must contain a complete interpretation"
                }
                require(this.reasonCodes.isEmpty()) {
                    "A reviewed binding cannot carry unresolved top-level reasons"
                }
                val conditionDigest = sourceConditionId.removePrefix("aihub-exact-sha256-")
                require(interpretation.semanticId == "aihub.condition.exact.$conditionDigest.v1") {
                    "A semantic id cannot silently merge different exact source conditions"
                }
            }
            AiHubCriterionReviewState.SOURCE_AMBIGUOUS_REQUIRES_ADJUDICATION,
            AiHubCriterionReviewState.UNREVIEWED,
            -> {
                require(interpretation == null) {
                    "$reviewState cannot carry a guessed interpretation"
                }
                require(this.reasonCodes.isNotEmpty()) {
                    "$reviewState must explain why interpretation is unavailable"
                }
            }
        }
        bindingPolicySha256 = aiHubBindingPolicySha256(
            bindingId = bindingId,
            exercise = exercise,
            sourceConditionId = sourceConditionId,
            reviewState = reviewState,
            releaseState = releaseState,
            interpretation = interpretation,
            reasonCodes = this.reasonCodes,
            decisionEvidenceRefs = this.decisionEvidenceRefs,
        )
        require(POLICY_SHA256.matches(approvedBindingPolicySha256)) {
            "approvedBindingPolicySha256 must be a lowercase SHA-256"
        }
        require(bindingPolicySha256 == approvedBindingPolicySha256) {
            "Binding policy differs from its repository drift-detection pin"
        }
    }
}

/** Immutable, non-executable registry covering all 167 AI Hub exercise-condition bindings. */
class AiHubCriterionPolicyRegistry internal constructor(
    val schemaVersion: Int,
    val sourceCatalogSha256: String,
    val sourceCoverageArtifactSha256: String,
    val sourceMetadataSetSha256: String,
    approvedPolicySha256: String,
    val approvalArtifactSha256: String,
    approvedRegistrySha256: String,
    bindings: Collection<AiHubCriterionPolicyBinding>,
    expectedExerciseCount: Int,
    expectedConditionCount: Int,
    expectedBindingCount: Int,
    expectedReviewedBindingCount: Int,
) {
    internal val bindings: List<AiHubCriterionPolicyBinding> = immutablePolicyList(bindings)
    private val bindingByKey: Map<Pair<AiHubExercise, String>, AiHubCriterionPolicyBinding>
    private val bindingsByExercise: Map<AiHubExercise, List<AiHubCriterionPolicyBinding>>
    val registeredExercises: Set<AiHubExercise>
    val policySha256: String
    val registrySha256: String
    val reviewedBindingCount: Int
        get() = bindings.count { it.reviewState == AiHubCriterionReviewState.REVIEWED_ENGINEERING_V1 }

    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(sourceCatalogSha256 == AiHubCriterionSourceCatalog.CATALOG_SHA256) {
            "Policy catalog SHA must match source coverage"
        }
        require(sourceCoverageArtifactSha256 ==
            AiHubCriterionSourceCatalog.COVERAGE_ARTIFACT_SHA256) {
            "Policy source coverage SHA must match the generated source registry"
        }
        require(sourceMetadataSetSha256 == AiHubCriterionSourceCatalog.METADATA_SET_SHA256) {
            "Policy metadata SHA must match the authoritative metadata audit"
        }
        require(POLICY_SHA256.matches(approvalArtifactSha256)) {
            "approvalArtifactSha256 must be a lowercase SHA-256"
        }
        require(
            this.bindings == this.bindings.sortedWith(
                compareBy<AiHubCriterionPolicyBinding>({ it.exercise.id }, { it.sourceConditionId }),
            ),
        ) { "Policy bindings must use deterministic exercise-condition order" }

        bindingByKey = immutablePolicyMap(
            this.bindings.associateBy { binding -> binding.exercise to binding.sourceConditionId },
        )
        require(bindingByKey.size == this.bindings.size) {
            "Policy bindings must have unique exercise-condition keys"
        }
        val sourceRegistry = AiHubCriterionSourceCatalog.registry
        val expectedKeys = sourceRegistry.coverages.flatMap { coverage ->
            coverage.conditionIds.map { conditionId -> coverage.exercise to conditionId }
        }.toSet()
        require(bindingByKey.keys == expectedKeys) {
            val missing = expectedKeys - bindingByKey.keys
            val unexpected = bindingByKey.keys - expectedKeys
            "Policy bindings must exactly cover source assignments; " +
                "missing=${missing.size}, unexpected=${unexpected.size}"
        }

        val inconsistentSemanticIdentities = this.bindings
            .filter { it.interpretation != null }
            .groupBy(AiHubCriterionPolicyBinding::sourceConditionId)
            .filterValues { sameConditionBindings ->
                sameConditionBindings.map { binding ->
                    val interpretation = requireNotNull(binding.interpretation)
                    listOf(
                        interpretation.semanticId,
                        interpretation.semanticFamilyId,
                        interpretation.measurementConstructId,
                    )
                }.distinct().size != 1
            }
        require(inconsistentSemanticIdentities.isEmpty()) {
            "One exact source condition cannot change semantic identity across exercises"
        }

        bindingsByExercise = immutablePolicyMap(
            AiHubExercise.entries.associateWith { exercise ->
                immutablePolicyList(this.bindings.filter { it.exercise == exercise })
            },
        )
        registeredExercises = Collections.unmodifiableSet(LinkedHashSet(bindingsByExercise.keys))

        require(registeredExercises.size == expectedExerciseCount)
        require(sourceRegistry.sourceConditions.size == expectedConditionCount)
        require(this.bindings.size == expectedBindingCount)
        require(reviewedBindingCount == expectedReviewedBindingCount)

        policySha256 = aiHubPolicyDecisionSha256(
            schemaVersion = schemaVersion,
            sourceCatalogSha256 = sourceCatalogSha256,
            sourceCoverageArtifactSha256 = sourceCoverageArtifactSha256,
            sourceMetadataSetSha256 = sourceMetadataSetSha256,
            bindings = this.bindings,
        )
        require(POLICY_SHA256.matches(approvedPolicySha256))
        require(policySha256 == approvedPolicySha256) {
            "Curated policy differs from the repository drift-detection pin"
        }
        registrySha256 = aiHubPolicyRegistrySha256(
            schemaVersion = schemaVersion,
            sourceCatalogSha256 = sourceCatalogSha256,
            sourceCoverageArtifactSha256 = sourceCoverageArtifactSha256,
            sourceMetadataSetSha256 = sourceMetadataSetSha256,
            policySha256 = policySha256,
            approvalArtifactSha256 = approvalArtifactSha256,
        )
        require(POLICY_SHA256.matches(approvedRegistrySha256))
        require(registrySha256 == approvedRegistrySha256) {
            "Combined policy registry provenance differs from its generated pin"
        }
    }

    fun binding(
        exercise: AiHubExercise,
        sourceConditionId: String,
    ): AiHubCriterionPolicyBinding? = bindingByKey[exercise to sourceConditionId]

    fun bindings(exercise: AiHubExercise): List<AiHubCriterionPolicyBinding> =
        requireNotNull(bindingsByExercise[exercise])
}

internal fun aiHubCriterionBindingId(
    exercise: AiHubExercise,
    sourceConditionId: String,
): String = "aihub-binding-sha256-" + canonicalFieldsSha256(
    listOf(
        "bindingIdSchemaVersion" to "1",
        "exerciseId" to exercise.id,
        "sourceConditionId" to sourceConditionId,
    ),
)

internal fun aiHubBindingPolicySha256(
    bindingId: String,
    exercise: AiHubExercise,
    sourceConditionId: String,
    reviewState: AiHubCriterionReviewState,
    releaseState: AiHubCriterionReleaseState,
    interpretation: AiHubCriterionInterpretation?,
    reasonCodes: List<String>,
    decisionEvidenceRefs: List<String>,
): String {
    val fields = mutableListOf(
        "bindingPolicySchemaVersion" to "1",
        "bindingId" to bindingId,
        "exerciseId" to exercise.id,
        "sourceConditionId" to sourceConditionId,
        "reviewState" to reviewState.name,
        "releaseState" to releaseState.name,
    )
    appendStringList(fields, "reasonCode", reasonCodes)
    appendStringList(fields, "decisionEvidenceRef", decisionEvidenceRefs)
    fields += "interpretationPresent" to (interpretation != null).toString()
    if (interpretation != null) {
        fields += "semanticId" to interpretation.semanticId
        fields += "semanticFamilyId" to interpretation.semanticFamilyId
        fields += "measurementConstructId" to interpretation.measurementConstructId
        fields += "claimBoundary" to interpretation.claimBoundary
        fields += "observability" to interpretation.observability.name
        fields += "phaseApplicabilityState" to interpretation.phaseApplicability.state.name
        appendStringList(fields, "phaseRoleId", interpretation.phaseApplicability.phaseRoleIds)
        fields += "sidePolicyKind" to interpretation.sidePolicy.kind.name
        fields += "roleResolverContractId" to
            (interpretation.sidePolicy.roleResolverContractId ?: "")
        fields += "viewApplicabilityState" to interpretation.viewApplicability.state.name
        appendStringList(fields, "viewContractId", interpretation.viewApplicability.viewContractIds)
        appendStringList(fields, "requiredCapabilityId", interpretation.requiredCapabilityIds)
        fields += "calibrationState" to interpretation.calibrationProvenance.state.name
        fields += "calibrationArtifactSha256" to
            (interpretation.calibrationProvenance.artifactSha256 ?: "")
        fields += "calibrationRuntimeDomainId" to
            (interpretation.calibrationProvenance.runtimeDomainId ?: "")
        appendStringList(
            fields,
            "calibrationEvidenceRef",
            interpretation.calibrationProvenance.evidenceRefs,
        )
        appendStringList(fields, "unsupportedReasonCode", interpretation.unsupportedReasonCodes)
        appendStringList(fields, "reviewEvidenceRef", interpretation.reviewEvidenceRefs)
    }
    return canonicalFieldsSha256(fields)
}

internal fun aiHubPolicyDecisionSha256(
    schemaVersion: Int,
    sourceCatalogSha256: String,
    sourceCoverageArtifactSha256: String,
    sourceMetadataSetSha256: String,
    bindings: List<AiHubCriterionPolicyBinding>,
): String {
    val fields = mutableListOf(
        "policySchemaVersion" to schemaVersion.toString(),
        "sourceCatalogSha256" to sourceCatalogSha256,
        "sourceCoverageArtifactSha256" to sourceCoverageArtifactSha256,
        "sourceMetadataSetSha256" to sourceMetadataSetSha256,
        "bindingCount" to bindings.size.toString(),
    )
    bindings.forEachIndexed { index, binding ->
        fields += "binding[$index].id" to binding.bindingId
        fields += "binding[$index].policySha256" to binding.bindingPolicySha256
    }
    return canonicalFieldsSha256(fields)
}

internal fun aiHubPolicyRegistrySha256(
    schemaVersion: Int,
    sourceCatalogSha256: String,
    sourceCoverageArtifactSha256: String,
    sourceMetadataSetSha256: String,
    policySha256: String,
    approvalArtifactSha256: String,
): String = canonicalFieldsSha256(
    listOf(
        "registrySchemaVersion" to schemaVersion.toString(),
        "sourceCatalogSha256" to sourceCatalogSha256,
        "sourceCoverageArtifactSha256" to sourceCoverageArtifactSha256,
        "sourceMetadataSetSha256" to sourceMetadataSetSha256,
        "policySha256" to policySha256,
        "approvalArtifactSha256" to approvalArtifactSha256,
    ),
)

private fun appendStringList(
    fields: MutableList<Pair<String, String>>,
    fieldName: String,
    values: List<String>,
) {
    fields += "${fieldName}Count" to values.size.toString()
    values.forEachIndexed { index, value -> fields += "$fieldName[$index]" to value }
}

private fun requireSortedVersionedIds(values: List<String>, fieldName: String) {
    require(values == values.distinct().sorted()) { "$fieldName must be sorted and unique" }
    require(values.all(VERSIONED_POLICY_ID::matches)) {
        "$fieldName must contain lowercase versioned identifiers"
    }
}

private fun requireSortedReasonCodes(values: List<String>, fieldName: String) {
    require(values == values.distinct().sorted()) { "$fieldName must be sorted and unique" }
    require(values.all(REASON_CODE::matches)) { "$fieldName contains an invalid reason code" }
}

private fun requireSortedEvidenceRefs(values: List<String>, fieldName: String) {
    require(values == values.distinct().sorted()) { "$fieldName must be sorted and unique" }
    require(values.all(EVIDENCE_REF::matches)) { "$fieldName contains an invalid evidence ref" }
}

private fun <T> immutablePolicyList(source: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))

private fun <K, V> immutablePolicyMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))
