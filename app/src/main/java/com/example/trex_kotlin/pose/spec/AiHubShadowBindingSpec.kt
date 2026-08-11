package com.example.trex_kotlin.pose.spec

import com.example.trex_kotlin.catalog.AiHubCriterionSourceCatalog
import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.policy.AiHubCriterionCalibrationProvenanceState
import com.example.trex_kotlin.pose.policy.AiHubCriterionObservability
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyBinding
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyCatalog
import com.example.trex_kotlin.pose.policy.AiHubCriterionReleaseState
import com.example.trex_kotlin.pose.policy.AiHubCriterionReviewState
import com.example.trex_kotlin.pose.policy.AiHubCriterionSidePolicyKind
import com.example.trex_kotlin.pose.policy.AiHubCriterionViewApplicabilityState
import java.util.Collections

private val SHADOW_SHA256 = Regex("^[0-9a-f]{64}$")
private val SHADOW_VERSIONED_ID = Regex("^[a-z0-9][a-z0-9._:/-]*\\.v[1-9][0-9]*$")
private const val FULL_CYCLE_PHASE_ROLE_ID = "trex.phase-role.full-cycle.v1"

/**
 * Repository-pinned identity of the generated AI Hub policy used by a shadow manifest.
 *
 * These values detect source-review drift. They are not a signature, issuer authentication,
 * calibration approval, runtime authorization, or product release approval.
 */
internal class AiHubShadowPolicySnapshot private constructor(
    val sourceCatalogSha256: String,
    val sourceCoverageArtifactSha256: String,
    val sourceMetadataSetSha256: String,
    val policySha256: String,
    val approvalArtifactSha256: String,
    val policyRegistrySha256: String,
) {
    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "shadowPolicySnapshotSchemaVersion" to "1",
            "trustMode" to "REPOSITORY_DRIFT_PIN_ONLY",
            "sourceCatalogSha256" to sourceCatalogSha256,
            "sourceCoverageArtifactSha256" to sourceCoverageArtifactSha256,
            "sourceMetadataSetSha256" to sourceMetadataSetSha256,
            "policySha256" to policySha256,
            "approvalArtifactSha256" to approvalArtifactSha256,
            "policyRegistrySha256" to policyRegistrySha256,
        ),
    )

    companion object {
        val CURRENT: AiHubShadowPolicySnapshot = AiHubShadowPolicySnapshot(
            sourceCatalogSha256 = AiHubCriterionPolicyCatalog.SOURCE_CATALOG_SHA256,
            sourceCoverageArtifactSha256 =
                AiHubCriterionPolicyCatalog.SOURCE_COVERAGE_ARTIFACT_SHA256,
            sourceMetadataSetSha256 = AiHubCriterionPolicyCatalog.SOURCE_METADATA_SET_SHA256,
            policySha256 = AiHubCriterionPolicyCatalog.POLICY_SHA256,
            approvalArtifactSha256 = AiHubCriterionPolicyCatalog.APPROVAL_ARTIFACT_SHA256,
            policyRegistrySha256 = AiHubCriterionPolicyCatalog.REGISTRY_SHA256,
        )
    }
}

/**
 * Exact catalog lookup receipt for one `(exercise, sourceConditionId)` policy binding.
 *
 * [bindingPolicySha256] already commits the interpretation's phase role, side policy, candidate
 * views, required capabilities, observability, calibration state, and catalog release state. The
 * five lookup fields below are still retained explicitly so an exercise manifest cannot join the
 * generated catalogs by list position, semantic family, or a shortened identifier.
 */
internal class AiHubShadowBindingProvenance private constructor(
    private val binding: AiHubCriterionPolicyBinding,
    val sourceConditionExactText: String,
    val policyRegistrySha256: String,
) {
    val exercise: AiHubExercise = binding.exercise
    val sourceConditionId: String = binding.sourceConditionId
    val bindingId: String = binding.bindingId
    val bindingPolicySha256: String = binding.bindingPolicySha256
    val reviewState: AiHubCriterionReviewState = binding.reviewState
    val releaseState: AiHubCriterionReleaseState = binding.releaseState

    private val interpretation = requireNotNull(binding.interpretation) {
        "A shadow binding requires a reviewed policy interpretation"
    }

    val observability: AiHubCriterionObservability = interpretation.observability
    val phaseRoleIds: List<String> = interpretation.phaseApplicability.phaseRoleIds
    val sidePolicyKind: AiHubCriterionSidePolicyKind = interpretation.sidePolicy.kind
    val roleResolverContractId: String? = interpretation.sidePolicy.roleResolverContractId
    val viewApplicabilityState: AiHubCriterionViewApplicabilityState =
        interpretation.viewApplicability.state
    val viewContractIds: List<String> = interpretation.viewApplicability.viewContractIds
    val requiredCapabilityIds: List<String> = interpretation.requiredCapabilityIds
    val calibrationState: AiHubCriterionCalibrationProvenanceState =
        interpretation.calibrationProvenance.state
    val catalogUnsupportedReasonCodes: List<String> = interpretation.unsupportedReasonCodes

    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "shadowBindingProvenanceSchemaVersion" to "1",
            "exerciseId" to exercise.id,
            "sourceConditionId" to sourceConditionId,
            "sourceConditionExactText" to sourceConditionExactText,
            "bindingId" to bindingId,
            "bindingPolicySha256" to bindingPolicySha256,
            "policyRegistrySha256" to policyRegistrySha256,
        ),
    )

    init {
        require(reviewState == AiHubCriterionReviewState.REVIEWED_ENGINEERING_V1) {
            "A shadow binding cannot guess an unreviewed source interpretation"
        }
        require(releaseState == AiHubCriterionReleaseState.CATALOG_ONLY) {
            "A catalog linkage cannot grant runtime release authority"
        }
    }

    companion object {
        /** Resolves and verifies the complete five-field catalog key, failing closed on drift. */
        fun exactCatalogLookup(
            exercise: AiHubExercise,
            sourceConditionId: String,
            bindingId: String,
            bindingPolicySha256: String,
            policyRegistrySha256: String,
        ): AiHubShadowBindingProvenance {
            require(policyRegistrySha256 == AiHubCriterionPolicyCatalog.REGISTRY_SHA256) {
                "Shadow binding policy registry does not match the generated catalog"
            }
            val coverage = AiHubCriterionSourceCatalog.requireCoverage(exercise)
            require(sourceConditionId in coverage.conditionIds) {
                "Source condition is not assigned to ${exercise.id}"
            }
            val sourceCondition = requireNotNull(
                AiHubCriterionSourceCatalog.registry.condition(sourceConditionId),
            ) { "Source condition is absent from the exact source registry" }
            val catalogBinding = requireNotNull(
                AiHubCriterionPolicyCatalog.binding(exercise, sourceConditionId),
            ) { "Policy binding is absent for the exact exercise-condition pair" }
            require(catalogBinding.bindingId == bindingId) {
                "Shadow binding id does not match the exact catalog binding"
            }
            require(catalogBinding.bindingPolicySha256 == bindingPolicySha256) {
                "Shadow binding policy SHA does not match the exact catalog binding"
            }
            return AiHubShadowBindingProvenance(
                binding = catalogBinding,
                sourceConditionExactText = sourceCondition.normalizedExactText,
                policyRegistrySha256 = policyRegistrySha256,
            )
        }
    }
}

internal enum class ShadowMeasurementSideChannel {
    MIDLINE,
    GLOBAL,
    BILATERAL_PAIR,
    LEFT,
    RIGHT,
}

internal enum class ShadowBindingUnavailableReason {
    NO_RUNTIME_RELEASE_AUTHORIZATION,
    NO_APPROVED_CALIBRATION_ARTIFACT,
    NO_BLIND_GOLD_VALIDATION,
    REQUIRED_PHASE_ROLE_PROVIDER_UNAVAILABLE,
    REQUIRED_CAPABILITY_PROVIDER_UNAVAILABLE,
    REQUIRED_VIEW_PROVIDER_UNAVAILABLE,
    NOT_OBSERVABLE_FROM_CAMERA_POSE,
}

/**
 * Internal shadow planning only. Neither variant can evaluate a user or expose a verdict, score,
 * cue, or product-session decision.
 */
internal sealed interface AiHubShadowBindingPlan {
    val provenance: AiHubShadowBindingProvenance
    val expectedWindowScope: CriterionWindowScope
    val artifactSha256: String

    /**
     * Raw, non-verdict measurement telemetry after every policy contract is satisfied.
     *
     * Construction is intentionally closed in the current bundle. A future change must add a
     * repository-reviewed factory that resolves real provider artifacts; an arbitrary SHA-shaped
     * string must not become a shadow-runtime authorization seam.
     */
    class MeasureOnly private constructor(
        override val provenance: AiHubShadowBindingProvenance,
        val phaseRoleId: String,
        override val expectedWindowScope: CriterionWindowScope,
        sideChannels: Set<ShadowMeasurementSideChannel>,
        val viewContractId: String,
        capabilityProviderArtifactSha256ById: Map<String, String>,
        val measurementContractSha256: String,
    ) : AiHubShadowBindingPlan {
        val sideChannels: Set<ShadowMeasurementSideChannel> = immutableSet(sideChannels)
        val capabilityProviderArtifactSha256ById: Map<String, String> =
            immutableMap(capabilityProviderArtifactSha256ById.toSortedMap())

        override val artifactSha256: String = canonicalFieldsSha256(
            buildList {
                add("shadowBindingPlanSchemaVersion" to "1")
                add("planKind" to "MEASURE_ONLY")
                add("bindingProvenanceSha256" to provenance.artifactSha256)
                add("phaseRoleId" to phaseRoleId)
                add("expectedWindowScopeSha256" to expectedWindowScope.shadowArtifactSha256())
                add("viewContractId" to viewContractId)
                add("measurementContractSha256" to measurementContractSha256)
                val channels = this@MeasureOnly.sideChannels.map { channel -> channel.name }.sorted()
                add("sideChannelCount" to channels.size.toString())
                channels.forEachIndexed { index, channel ->
                    add("sideChannel[$index]" to channel)
                }
                val providers = this@MeasureOnly.capabilityProviderArtifactSha256ById.entries
                    .sortedBy { provider -> provider.key }
                add("capabilityProviderCount" to providers.size.toString())
                providers.forEachIndexed { index, provider ->
                    add("capabilityProvider[$index].id" to provider.key)
                    add("capabilityProvider[$index].artifactSha256" to provider.value)
                }
            },
        )

        init {
            require(provenance.observability != AiHubCriterionObservability.NOT_OBSERVABLE) {
                "A non-observable source construct cannot have a camera measurement plan"
            }
            require(phaseRoleId in provenance.phaseRoleIds) {
                "Measurement phase role is absent from the exact source policy"
            }
            requireExactWindowScope(provenance, expectedWindowScope)
            require(SHADOW_VERSIONED_ID.matches(viewContractId)) {
                "viewContractId must be a lowercase versioned identifier"
            }
            require(viewContractId in provenance.viewContractIds) {
                "Measurement view is absent from the exact source policy"
            }
            require(
                this.sideChannels ==
                    expectedShadowSideChannelsForPolicy(provenance.sidePolicyKind),
            ) {
                "Measurement side channels do not implement the exact source side policy"
            }
            require(
                this.capabilityProviderArtifactSha256ById.keys ==
                    provenance.requiredCapabilityIds.toSet(),
            ) {
                "Capability providers must exactly cover the source policy requirements"
            }
            require(
                this.capabilityProviderArtifactSha256ById.values.all(SHADOW_SHA256::matches),
            ) { "Capability provider artifacts must be lowercase SHA-256 values" }
            require(SHADOW_SHA256.matches(measurementContractSha256)) {
                "measurementContractSha256 must be a lowercase SHA-256"
            }
        }
    }

    /** Explicit evidence that one source binding cannot currently run even in shadow. */
    class Unavailable internal constructor(
        override val provenance: AiHubShadowBindingProvenance,
        override val expectedWindowScope: CriterionWindowScope,
        reasons: Set<ShadowBindingUnavailableReason>,
        missingCapabilityIds: Set<String> = emptySet(),
        unavailableViewContractIds: Set<String> = emptySet(),
        unresolvedPhaseRoleIds: Set<String> = emptySet(),
    ) : AiHubShadowBindingPlan {
        val reasons: Set<ShadowBindingUnavailableReason> = immutableSet(reasons)
        val missingCapabilityIds: Set<String> = immutableSet(missingCapabilityIds)
        val unavailableViewContractIds: Set<String> = immutableSet(unavailableViewContractIds)
        val unresolvedPhaseRoleIds: Set<String> = immutableSet(unresolvedPhaseRoleIds)

        override val artifactSha256: String = canonicalFieldsSha256(
            buildList {
                add("shadowBindingPlanSchemaVersion" to "1")
                add("planKind" to "UNAVAILABLE")
                add("bindingProvenanceSha256" to provenance.artifactSha256)
                add("expectedWindowScopeSha256" to expectedWindowScope.shadowArtifactSha256())
                appendSortedItems(
                    "reason",
                    this@Unavailable.reasons.map { reason -> reason.name },
                )
                appendSortedItems("missingCapabilityId", this@Unavailable.missingCapabilityIds)
                appendSortedItems(
                    "unavailableViewContractId",
                    this@Unavailable.unavailableViewContractIds,
                )
                appendSortedItems("unresolvedPhaseRoleId", this@Unavailable.unresolvedPhaseRoleIds)
            },
        )

        init {
            require(this.reasons.isNotEmpty()) { "An unavailable plan must explain why" }
            requireExactWindowScope(provenance, expectedWindowScope)
            require(
                ShadowBindingUnavailableReason.NO_RUNTIME_RELEASE_AUTHORIZATION in this.reasons,
            ) { "A catalog-only shadow plan must retain the missing runtime authorization" }
            require(this.missingCapabilityIds.all(SHADOW_VERSIONED_ID::matches)) {
                "Missing capability ids must be lowercase versioned identifiers"
            }
            require(this.missingCapabilityIds.all(provenance.requiredCapabilityIds::contains)) {
                "Missing capability ids must come from the exact source policy"
            }
            require(this.unavailableViewContractIds.all(SHADOW_VERSIONED_ID::matches)) {
                "Unavailable view ids must be lowercase versioned identifiers"
            }
            require(this.unavailableViewContractIds.all(provenance.viewContractIds::contains)) {
                "Unavailable views must come from the exact source policy"
            }
            require(this.unresolvedPhaseRoleIds.all(SHADOW_VERSIONED_ID::matches)) {
                "Unresolved phase roles must be lowercase versioned identifiers"
            }
            require(this.unresolvedPhaseRoleIds.all(provenance.phaseRoleIds::contains)) {
                "Unresolved phase roles must come from the exact source policy"
            }
            if (this.missingCapabilityIds.isNotEmpty()) {
                require(
                    ShadowBindingUnavailableReason.REQUIRED_CAPABILITY_PROVIDER_UNAVAILABLE in
                        this.reasons ||
                        ShadowBindingUnavailableReason.NOT_OBSERVABLE_FROM_CAMERA_POSE in
                        this.reasons,
                ) { "Missing capabilities require an explicit unavailable reason" }
            }
            if (this.unavailableViewContractIds.isNotEmpty()) {
                require(
                    ShadowBindingUnavailableReason.REQUIRED_VIEW_PROVIDER_UNAVAILABLE in
                        this.reasons,
                ) { "Unavailable views require an explicit unavailable reason" }
            }
            if (this.unresolvedPhaseRoleIds.isNotEmpty()) {
                require(
                    ShadowBindingUnavailableReason.REQUIRED_PHASE_ROLE_PROVIDER_UNAVAILABLE in
                        this.reasons ||
                        ShadowBindingUnavailableReason.NOT_OBSERVABLE_FROM_CAMERA_POSE in
                        this.reasons,
                ) { "Unresolved phase roles require an explicit unavailable reason" }
            }
            when (provenance.observability) {
                AiHubCriterionObservability.PROXY_UNVALIDATED -> require(
                    ShadowBindingUnavailableReason.NO_BLIND_GOLD_VALIDATION in this.reasons,
                ) { "An unvalidated proxy must retain its missing blind-Gold reason" }

                AiHubCriterionObservability.NOT_OBSERVABLE -> require(
                    ShadowBindingUnavailableReason.NOT_OBSERVABLE_FROM_CAMERA_POSE in this.reasons,
                ) { "A non-observable construct must remain explicitly unavailable" }

                AiHubCriterionObservability.DIRECT,
                AiHubCriterionObservability.PROXY_GOLD_VALIDATED,
                -> Unit
            }
            if (
                provenance.calibrationState ==
                AiHubCriterionCalibrationProvenanceState.NO_APPROVED_ARTIFACT
            ) {
                require(
                    ShadowBindingUnavailableReason.NO_APPROVED_CALIBRATION_ARTIFACT in
                        this.reasons,
                ) { "Missing approved calibration provenance must remain explicit" }
            }
        }
    }
}

/**
 * Complete, order-independent source-policy manifest for one exercise's internal shadow work.
 *
 * [contentSha256] is only a repository drift identity. This type performs no signature
 * verification and grants no evaluator, user feedback, product session, or release authority.
 */
internal class AiHubShadowExerciseSpec(
    val exercise: AiHubExercise,
    val policySnapshot: AiHubShadowPolicySnapshot,
    plans: Collection<AiHubShadowBindingPlan>,
    val repositoryDriftPinSha256: String,
) {
    val plans: List<AiHubShadowBindingPlan> = immutableList(
        plans.sortedBy { plan -> plan.provenance.bindingId },
    )
    private val planByBindingId: Map<String, AiHubShadowBindingPlan>

    val contentSha256: String = canonicalFieldsSha256(
        buildList {
            add("aiHubShadowExerciseSpecSchemaVersion" to "1")
            add("trustMode" to "REPOSITORY_DRIFT_PIN_ONLY")
            add("exerciseId" to exercise.id)
            add("policySnapshotSha256" to policySnapshot.artifactSha256)
            add("bindingPlanCount" to this@AiHubShadowExerciseSpec.plans.size.toString())
            this@AiHubShadowExerciseSpec.plans.forEachIndexed { index, plan ->
                add("bindingPlan[$index].bindingId" to plan.provenance.bindingId)
                add("bindingPlan[$index].artifactSha256" to plan.artifactSha256)
            }
        },
    )

    init {
        require(SHADOW_SHA256.matches(repositoryDriftPinSha256)) {
            "repositoryDriftPinSha256 must be a lowercase SHA-256"
        }
        require(policySnapshot.policyRegistrySha256 == AiHubCriterionPolicyCatalog.REGISTRY_SHA256)
        require(policySnapshot.policySha256 == AiHubCriterionPolicyCatalog.POLICY_SHA256)
        require(policySnapshot.sourceCatalogSha256 ==
            AiHubCriterionPolicyCatalog.SOURCE_CATALOG_SHA256)
        require(policySnapshot.sourceCoverageArtifactSha256 ==
            AiHubCriterionPolicyCatalog.SOURCE_COVERAGE_ARTIFACT_SHA256)
        require(policySnapshot.sourceMetadataSetSha256 ==
            AiHubCriterionPolicyCatalog.SOURCE_METADATA_SET_SHA256)
        require(policySnapshot.approvalArtifactSha256 ==
            AiHubCriterionPolicyCatalog.APPROVAL_ARTIFACT_SHA256)
        require(this.plans.isNotEmpty()) { "A shadow exercise spec must retain source bindings" }
        require(this.plans.all { plan -> plan.provenance.exercise == exercise }) {
            "Every shadow plan must belong to the manifest exercise"
        }
        require(this.plans.all { plan ->
            plan.provenance.policyRegistrySha256 == policySnapshot.policyRegistrySha256
        }) { "Every shadow plan must use the manifest policy registry" }

        planByBindingId = immutableMap(
            this.plans.associateBy { plan -> plan.provenance.bindingId },
        )
        require(planByBindingId.size == this.plans.size) {
            "A source binding may appear only once in a shadow exercise spec"
        }
        val actualConditionIds = this.plans.map { it.provenance.sourceConditionId }.toSet()
        require(actualConditionIds.size == this.plans.size) {
            "A source condition may appear only once in a shadow exercise spec"
        }

        val expectedTuples = AiHubCriterionPolicyCatalog.bindings(exercise).map { binding ->
            CatalogBindingTuple(
                sourceConditionId = binding.sourceConditionId,
                bindingId = binding.bindingId,
                bindingPolicySha256 = binding.bindingPolicySha256,
            )
        }.toSet()
        val actualTuples = this.plans.map { plan ->
            CatalogBindingTuple(
                sourceConditionId = plan.provenance.sourceConditionId,
                bindingId = plan.provenance.bindingId,
                bindingPolicySha256 = plan.provenance.bindingPolicySha256,
            )
        }.toSet()
        require(actualTuples == expectedTuples) {
            "Shadow plans must exactly cover every generated exercise-condition policy binding"
        }
        require(contentSha256 == repositoryDriftPinSha256) {
            "Shadow exercise content drifted: expected=$repositoryDriftPinSha256, " +
                "actual=$contentSha256. This pin is repository review evidence, not a signature " +
                "or runtime approval."
        }
    }

    fun plan(bindingId: String): AiHubShadowBindingPlan? = planByBindingId[bindingId]
}

private data class CatalogBindingTuple(
    val sourceConditionId: String,
    val bindingId: String,
    val bindingPolicySha256: String,
)

private fun requireExactWindowScope(
    provenance: AiHubShadowBindingProvenance,
    expectedWindowScope: CriterionWindowScope,
) {
    if (FULL_CYCLE_PHASE_ROLE_ID in provenance.phaseRoleIds) {
        require(provenance.phaseRoleIds == listOf(FULL_CYCLE_PHASE_ROLE_ID)) {
            "A full-cycle source binding cannot silently merge narrower phase roles"
        }
        require(expectedWindowScope == CriterionWindowScope.CompletedCycle) {
            "The AI Hub full-cycle role must bind to CriterionWindowScope.CompletedCycle"
        }
    } else {
        require(expectedWindowScope is CriterionWindowScope.Phase) {
            "A non-full-cycle source role requires an explicit concrete phase window"
        }
    }
}

private fun CriterionWindowScope.shadowArtifactSha256(): String = canonicalFieldsSha256(
    when (this) {
        is CriterionWindowScope.Phase -> listOf(
            "shadowWindowScopeSchemaVersion" to "1",
            "kind" to "PHASE",
            "phaseId" to phaseId.value,
        )
        CriterionWindowScope.CompletedCycle -> listOf(
            "shadowWindowScopeSchemaVersion" to "1",
            "kind" to "COMPLETED_CYCLE",
        )
    },
)

/**
 * Lossless mapping for side policies whose channel identity does not need a role resolver.
 *
 * A coupled pair remains one channel. Treating it as independent LEFT/RIGHT evidence would
 * change the policy meaning. Role-relative policies remain unavailable until a separately
 * attested resolver defines their runtime member assignment.
 */
internal fun expectedShadowSideChannelsForPolicy(
    sidePolicyKind: AiHubCriterionSidePolicyKind,
): Set<ShadowMeasurementSideChannel> = when (sidePolicyKind) {
    AiHubCriterionSidePolicyKind.MIDLINE -> setOf(ShadowMeasurementSideChannel.MIDLINE)
    AiHubCriterionSidePolicyKind.GLOBAL_BODY -> setOf(ShadowMeasurementSideChannel.GLOBAL)
    AiHubCriterionSidePolicyKind.BILATERAL_COUPLED ->
        setOf(ShadowMeasurementSideChannel.BILATERAL_PAIR)
    AiHubCriterionSidePolicyKind.BILATERAL_INDEPENDENT ->
        setOf(ShadowMeasurementSideChannel.LEFT, ShadowMeasurementSideChannel.RIGHT)
    AiHubCriterionSidePolicyKind.ACTIVE_LIMB,
    AiHubCriterionSidePolicyKind.LEAD_LIMB,
    AiHubCriterionSidePolicyKind.TRAIL_LIMB,
    AiHubCriterionSidePolicyKind.ALTERNATING_PAIR,
    AiHubCriterionSidePolicyKind.CONTRALATERAL_PAIR,
    AiHubCriterionSidePolicyKind.NOT_APPLICABLE,
    -> throw IllegalArgumentException(
        "Side policy $sidePolicyKind requires a separately attested role resolver",
    )
}

private fun MutableList<Pair<String, String>>.appendSortedItems(
    fieldName: String,
    values: Collection<String>,
) {
    val sorted = values.sorted()
    add("${fieldName}Count" to sorted.size.toString())
    sorted.forEachIndexed { index, value -> add("$fieldName[$index]" to value) }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
