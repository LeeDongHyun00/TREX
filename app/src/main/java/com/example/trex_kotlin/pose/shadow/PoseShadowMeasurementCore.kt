package com.example.trex_kotlin.pose.shadow

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.policy.AiHubCriterionObservability
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyCatalog
import java.util.Collections

private val SHADOW_CORE_SHA256 = Regex("^[0-9a-f]{64}$")
private val SHADOW_CORE_VERSIONED_ID =
    Regex("^[a-z0-9][a-z0-9._:/-]*\\.v[1-9][0-9]*$")
private val SHADOW_CORE_SOURCE_CONDITION_ID = Regex("^aihub-exact-sha256-[0-9a-f]{64}$")
private val SHADOW_CORE_BINDING_ID = Regex("^aihub-binding-sha256-[0-9a-f]{64}$")

/** Content identities do not confer permission to execute a shadow measurement. */
internal class ShadowMeasurementContentIdentity(
    val exerciseManifestId: String,
    val exerciseManifestSha256: String,
    val bindingPlanId: String,
    val bindingPlanSha256: String,
    val measurementConstructId: String,
    val measurementConstructSha256: String,
) {
    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "shadowMeasurementContentIdentitySchemaVersion" to "1",
            "exerciseManifestId" to exerciseManifestId,
            "exerciseManifestSha256" to exerciseManifestSha256,
            "bindingPlanId" to bindingPlanId,
            "bindingPlanSha256" to bindingPlanSha256,
            "measurementConstructId" to measurementConstructId,
            "measurementConstructSha256" to measurementConstructSha256,
        ),
    )

    init {
        requireVersionedId("exerciseManifestId", exerciseManifestId)
        requireSha256("exerciseManifestSha256", exerciseManifestSha256)
        requireVersionedId("bindingPlanId", bindingPlanId)
        requireSha256("bindingPlanSha256", bindingPlanSha256)
        requireVersionedId("measurementConstructId", measurementConstructId)
        requireSha256("measurementConstructSha256", measurementConstructSha256)
    }
}

internal class ShadowFeatureRuntimeIdentity(
    val featureContractId: String,
    val featureSpecSha256: String,
    val featureRuntimeContractSha256: String,
) {
    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "shadowFeatureRuntimeIdentitySchemaVersion" to "1",
            "featureContractId" to featureContractId,
            "featureSpecSha256" to featureSpecSha256,
            "featureRuntimeContractSha256" to featureRuntimeContractSha256,
        ),
    )

    init {
        requireVersionedId("featureContractId", featureContractId)
        requireSha256("featureSpecSha256", featureSpecSha256)
        requireSha256("featureRuntimeContractSha256", featureRuntimeContractSha256)
    }
}

internal class ShadowObservationRuntimeIdentity(
    val runtimeDomainId: String,
    val observationContractArtifactSha256: String,
) {
    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "shadowObservationRuntimeIdentitySchemaVersion" to "1",
            "runtimeDomainId" to runtimeDomainId,
            "observationContractArtifactSha256" to observationContractArtifactSha256,
        ),
    )

    init {
        requireVersionedId("runtimeDomainId", runtimeDomainId)
        requireSha256(
            "observationContractArtifactSha256",
            observationContractArtifactSha256,
        )
    }
}

internal class ShadowPhaseRuntimeIdentity(
    val phaseRoleId: String,
    val phaseArtifactSha256: String,
) {
    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "shadowPhaseRuntimeIdentitySchemaVersion" to "1",
            "phaseRoleId" to phaseRoleId,
            "phaseArtifactSha256" to phaseArtifactSha256,
        ),
    )

    init {
        requireVersionedId("phaseRoleId", phaseRoleId)
        requireSha256("phaseArtifactSha256", phaseArtifactSha256)
    }
}

internal class ShadowViewRuntimeIdentity(
    val selectedViewContractId: String,
    val viewQualifierArtifactId: String,
    val viewQualifierArtifactSha256: String,
) {
    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "shadowViewRuntimeIdentitySchemaVersion" to "1",
            "selectedViewContractId" to selectedViewContractId,
            "viewQualifierArtifactId" to viewQualifierArtifactId,
            "viewQualifierArtifactSha256" to viewQualifierArtifactSha256,
        ),
    )

    init {
        requireVersionedId("selectedViewContractId", selectedViewContractId)
        requireVersionedId("viewQualifierArtifactId", viewQualifierArtifactId)
        requireSha256("viewQualifierArtifactSha256", viewQualifierArtifactSha256)
    }
}

internal class ShadowCapabilityProviderArtifacts(
    artifactSha256ByCapabilityId: Map<String, String>,
) {
    val artifactSha256ByCapabilityId: Map<String, String> = immutableSortedStringMap(
        artifactSha256ByCapabilityId,
    )

    val artifactSha256: String = canonicalFieldsSha256(
        buildList {
            add("shadowCapabilityProviderSetSchemaVersion" to "1")
            add(
                "capabilityProviderCount" to
                    this@ShadowCapabilityProviderArtifacts.artifactSha256ByCapabilityId.size
                        .toString(),
            )
            this@ShadowCapabilityProviderArtifacts.artifactSha256ByCapabilityId.entries
                .forEachIndexed { index, entry ->
                    add("capabilityProvider[$index].capabilityId" to entry.key)
                    add("capabilityProvider[$index].artifactSha256" to entry.value)
                }
        },
    )

    init {
        this.artifactSha256ByCapabilityId.forEach { (capabilityId, artifactSha256) ->
            requireVersionedId("capabilityId", capabilityId)
            requireSha256("capabilityProviderArtifactSha256", artifactSha256)
        }
    }
}

internal enum class ShadowScalarSide {
    MIDLINE,
    GLOBAL,
    LEFT,
    RIGHT,
}

internal enum class ShadowSidePolicyKind {
    MIDLINE,
    GLOBAL_BODY,
    BILATERAL_COUPLED,
    BILATERAL_INDEPENDENT,
    ACTIVE_LIMB,
    LEAD_LIMB,
    TRAIL_LIMB,
    ALTERNATING_PAIR,
    CONTRALATERAL_PAIR,
}

internal class ShadowSideRuntimePolicy(
    val kind: ShadowSidePolicyKind,
    sideChannels: Set<ShadowScalarSide>,
    val roleResolverContractId: String? = null,
    val roleResolverArtifactSha256: String? = null,
) {
    val sideChannels: Set<ShadowScalarSide> = immutableSortedSet(sideChannels)

    val artifactSha256: String = canonicalFieldsSha256(
        buildList {
            add("shadowSideRuntimePolicySchemaVersion" to "1")
            add("kind" to kind.name)
            add("roleResolverContractId" to roleResolverContractId.orEmpty())
            add("roleResolverArtifactSha256" to roleResolverArtifactSha256.orEmpty())
            add("sideChannelCount" to this@ShadowSideRuntimePolicy.sideChannels.size.toString())
            this@ShadowSideRuntimePolicy.sideChannels.forEachIndexed { index, side ->
                add("sideChannel[$index]" to side.name)
            }
        },
    )

    init {
        require(this.sideChannels == expectedSides(kind)) {
            "sideChannels must exactly implement the selected side policy"
        }
        val requiresResolver = kind in setOf(
            ShadowSidePolicyKind.ACTIVE_LIMB,
            ShadowSidePolicyKind.LEAD_LIMB,
            ShadowSidePolicyKind.TRAIL_LIMB,
            ShadowSidePolicyKind.ALTERNATING_PAIR,
            ShadowSidePolicyKind.CONTRALATERAL_PAIR,
        )
        if (requiresResolver) {
            requireNotNull(roleResolverContractId) {
                "A role-dependent side policy requires a resolver contract"
            }
            requireNotNull(roleResolverArtifactSha256) {
                "A role-dependent side policy requires a resolver artifact"
            }
            requireVersionedId("roleResolverContractId", roleResolverContractId)
            requireSha256("roleResolverArtifactSha256", roleResolverArtifactSha256)
        } else {
            require(roleResolverContractId == null && roleResolverArtifactSha256 == null) {
                "A fixed side policy cannot carry a role resolver"
            }
        }
    }
}

internal enum class ShadowOutputRetention {
    IN_MEMORY_ONLY,
}

internal enum class ShadowOutputDetail {
    AGGREGATES_ONLY,
}

internal class ShadowOutputPrivacyContract(
    val contractId: String,
    val repositoryDriftPinSha256: String,
    val retention: ShadowOutputRetention,
    val detail: ShadowOutputDetail,
    val rawPoseRetentionAllowed: Boolean,
    val timestampSeriesAllowed: Boolean,
    val persistentStorageAllowed: Boolean,
    val networkExportAllowed: Boolean,
) {
    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "shadowOutputPrivacyContractSchemaVersion" to "1",
            "contractId" to contractId,
            "retention" to retention.name,
            "detail" to detail.name,
            "rawPoseRetentionAllowed" to rawPoseRetentionAllowed.toString(),
            "timestampSeriesAllowed" to timestampSeriesAllowed.toString(),
            "persistentStorageAllowed" to persistentStorageAllowed.toString(),
            "networkExportAllowed" to networkExportAllowed.toString(),
        ),
    )

    init {
        requireVersionedId("outputPrivacyContractId", contractId)
        requireSha256("outputPrivacyRepositoryDriftPinSha256", repositoryDriftPinSha256)
        require(retention == ShadowOutputRetention.IN_MEMORY_ONLY)
        require(detail == ShadowOutputDetail.AGGREGATES_ONLY)
        require(!rawPoseRetentionAllowed)
        require(!timestampSeriesAllowed)
        require(!persistentStorageAllowed)
        require(!networkExportAllowed)
        require(repositoryDriftPinSha256 == artifactSha256) {
            "Output privacy policy does not match its pinned artifact"
        }
    }

    companion object {
        fun contentSha256(contractId: String): String {
            requireVersionedId("outputPrivacyContractId", contractId)
            return canonicalFieldsSha256(
                listOf(
                    "shadowOutputPrivacyContractSchemaVersion" to "1",
                    "contractId" to contractId,
                    "retention" to ShadowOutputRetention.IN_MEMORY_ONLY.name,
                    "detail" to ShadowOutputDetail.AGGREGATES_ONLY.name,
                    "rawPoseRetentionAllowed" to "false",
                    "timestampSeriesAllowed" to "false",
                    "persistentStorageAllowed" to "false",
                    "networkExportAllowed" to "false",
                ),
            )
        }
    }
}

internal class ShadowMeasurementRuntimeContract(
    val contractId: String,
    val maximumSampleGapMs: Long,
    val maximumCycleDurationMs: Long,
    val maximumSamplesPerCycle: Int,
) {
    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "shadowMeasurementRuntimeContractSchemaVersion" to "1",
            "contractId" to contractId,
            "maximumSampleGapMs" to maximumSampleGapMs.toString(),
            "maximumCycleDurationMs" to maximumCycleDurationMs.toString(),
            "maximumSamplesPerCycle" to maximumSamplesPerCycle.toString(),
            "duplicateTimestampPolicy" to "FIRST_INPUT_WINS",
            "completedCycleInterval" to "START_INCLUSIVE_END_EXCLUSIVE",
        ),
    )

    init {
        requireVersionedId("measurementRuntimeContractId", contractId)
        require(maximumSampleGapMs in 1L..10_000L)
        require(maximumCycleDurationMs in maximumSampleGapMs..300_000L)
        require(maximumSamplesPerCycle in 1..MAXIMUM_ON_DEVICE_SHADOW_SAMPLES)
    }
}

/** Immutable, content-addressed request. Its hash is not an execution credential. */
internal class ShadowMeasurementExecutionRequest(
    val exercise: AiHubExercise,
    val sourceConditionId: String,
    val bindingId: String,
    val bindingPolicySha256: String,
    val policyRegistrySha256: String,
    val contentIdentity: ShadowMeasurementContentIdentity,
    val featureRuntimeIdentity: ShadowFeatureRuntimeIdentity,
    val observationRuntimeIdentity: ShadowObservationRuntimeIdentity,
    val phaseRuntimeIdentity: ShadowPhaseRuntimeIdentity,
    val viewRuntimeIdentity: ShadowViewRuntimeIdentity,
    val capabilityProviderArtifacts: ShadowCapabilityProviderArtifacts,
    val sideRuntimePolicy: ShadowSideRuntimePolicy,
    val measurementRuntimeContract: ShadowMeasurementRuntimeContract,
    val outputPrivacyContract: ShadowOutputPrivacyContract,
) {
    val contentSha256: String = canonicalFieldsSha256(
        listOf(
            "shadowMeasurementExecutionRequestSchemaVersion" to "1",
            "exerciseId" to exercise.id,
            "sourceConditionId" to sourceConditionId,
            "bindingId" to bindingId,
            "bindingPolicySha256" to bindingPolicySha256,
            "policyRegistrySha256" to policyRegistrySha256,
            "contentIdentitySha256" to contentIdentity.artifactSha256,
            "featureRuntimeIdentitySha256" to featureRuntimeIdentity.artifactSha256,
            "observationRuntimeIdentitySha256" to observationRuntimeIdentity.artifactSha256,
            "phaseRuntimeIdentitySha256" to phaseRuntimeIdentity.artifactSha256,
            "viewRuntimeIdentitySha256" to viewRuntimeIdentity.artifactSha256,
            "capabilityProviderSetSha256" to capabilityProviderArtifacts.artifactSha256,
            "sideRuntimePolicySha256" to sideRuntimePolicy.artifactSha256,
            "measurementRuntimeContractSha256" to measurementRuntimeContract.artifactSha256,
            "outputPrivacyContractSha256" to outputPrivacyContract.artifactSha256,
        ),
    )

    init {
        require(SHADOW_CORE_SOURCE_CONDITION_ID.matches(sourceConditionId)) {
            "sourceConditionId must be an exact AI Hub source identity"
        }
        require(SHADOW_CORE_BINDING_ID.matches(bindingId)) {
            "bindingId must be an exact AI Hub binding identity"
        }
        requireSha256("bindingPolicySha256", bindingPolicySha256)
        requireSha256("policyRegistrySha256", policyRegistrySha256)
        require(policyRegistrySha256 == AiHubCriterionPolicyCatalog.REGISTRY_SHA256) {
            "policyRegistrySha256 does not match the generated policy catalog"
        }
        val binding = requireNotNull(
            AiHubCriterionPolicyCatalog.binding(exercise, sourceConditionId),
        ) { "The exact exercise-source binding is absent from the generated policy catalog" }
        require(binding.bindingId == bindingId) {
            "bindingId does not match the exact generated policy binding"
        }
        require(binding.bindingPolicySha256 == bindingPolicySha256) {
            "bindingPolicySha256 does not match the exact generated policy binding"
        }
        val interpretation = requireNotNull(binding.interpretation) {
            "The exact generated policy binding has no reviewed interpretation"
        }
        require(interpretation.observability != AiHubCriterionObservability.NOT_OBSERVABLE) {
            "A non-observable source construct cannot create a scalar shadow request"
        }
        require(
            contentIdentity.measurementConstructId == interpretation.measurementConstructId,
        ) { "measurementConstructId does not match the exact generated policy binding" }
        require(
            phaseRuntimeIdentity.phaseRoleId == SHADOW_COMPLETED_CYCLE_PHASE_ROLE_ID &&
                interpretation.phaseApplicability.phaseRoleIds ==
                listOf(SHADOW_COMPLETED_CYCLE_PHASE_ROLE_ID),
        ) {
            "The completed-cycle reducer requires an exact full-cycle policy binding"
        }
        require(
            viewRuntimeIdentity.selectedViewContractId in
                interpretation.viewApplicability.viewContractIds,
        ) { "selectedViewContractId is absent from the exact generated policy binding" }
        require(
            capabilityProviderArtifacts.artifactSha256ByCapabilityId.keys ==
                interpretation.requiredCapabilityIds.toSet(),
        ) { "Capability providers must exactly cover the generated policy binding" }
        require(sideRuntimePolicy.kind.name == interpretation.sidePolicy.kind.name) {
            "Side policy kind does not match the exact generated policy binding"
        }
        require(
            sideRuntimePolicy.roleResolverContractId ==
                interpretation.sidePolicy.roleResolverContractId,
        ) { "Side role resolver does not match the exact generated policy binding" }
    }
}

/**
 * Reserved opaque type for a future execution grant. The current bundle has no key and can mint
 * no instance. Its repository drift pin is neither a signature nor evidence of authenticity.
 */
internal class VerifiedShadowExecutionAuthorization private constructor() {
    companion object {
        // Nothing cannot represent a positive entry. A non-empty authority requires a new schema.
        private val BUNDLED_ENTRIES_BY_REQUEST_SHA256: Map<String, Nothing> = emptyMap()

        val bundledEntryCount: Int
            get() = BUNDLED_ENTRIES_BY_REQUEST_SHA256.size

        val bundledAllowlistSha256: String = canonicalFieldsSha256(
            listOf(
                "shadowExecutionAllowlistSchemaVersion" to "1",
                "artifactKind" to "SHADOW_EXECUTION_AUTHORITY",
                "authorityMode" to "NO_SHADOW_KEY_CONFIGURED",
                "policyRegistrySha256" to AiHubCriterionPolicyCatalog.REGISTRY_SHA256,
                "entryCount" to BUNDLED_ENTRIES_BY_REQUEST_SHA256.size.toString(),
            ),
        )

        init {
            require(BUNDLED_ENTRIES_BY_REQUEST_SHA256.isEmpty())
            require(bundledAllowlistSha256 == EMPTY_ALLOWLIST_DRIFT_PIN_SHA256) {
                "Bundled shadow authority identity drifted"
            }
        }

        fun resolve(
            @Suppress("UNUSED_PARAMETER")
            request: ShadowMeasurementExecutionRequest,
        ): VerifiedShadowExecutionAuthorization? = null

        internal fun authorizes(
            @Suppress("UNUSED_PARAMETER")
            request: ShadowMeasurementExecutionRequest,
            @Suppress("UNUSED_PARAMETER")
            authorization: VerifiedShadowExecutionAuthorization?,
        ): Boolean = false

        const val EMPTY_ALLOWLIST_DRIFT_PIN_SHA256: String =
            "7339aa3aa9f47841089298d38d190839786f4097d9d32a1e74b9508e791e1dfe"
    }
}

/** Session-local reference identity for one already-attested observation source. */
internal class ShadowSourceContinuityToken private constructor(
    val runtimeDomainId: String,
    val observationContractArtifactSha256: String,
) {
    init {
        requireVersionedId("runtimeDomainId", runtimeDomainId)
        requireSha256("observationContractArtifactSha256", observationContractArtifactSha256)
    }
}

/** Session-local reference identity for one person track. */
internal class ShadowPersonContinuityToken private constructor(
    val source: ShadowSourceContinuityToken,
)

/** Timestamp-bound view qualification for one sampled scalar input. */
internal class ShadowQualifiedViewToken private constructor(
    val source: ShadowSourceContinuityToken,
    val person: ShadowPersonContinuityToken,
    val timestampMs: Long,
    val viewContractId: String,
    val qualifierArtifactId: String,
    val qualifierArtifactSha256: String,
) {
    init {
        require(person.source === source)
        require(timestampMs >= 0L)
        requireVersionedId("viewContractId", viewContractId)
        requireVersionedId("qualifierArtifactId", qualifierArtifactId)
        requireSha256("qualifierArtifactSha256", qualifierArtifactSha256)
    }
}

/**
 * Opaque proof reserved for a future verified phase provider. No issuer exists in this slice.
 * A future issuer may mint this only after it has verified the pinned phase artifact, strict
 * capture-time ordering, camera-geometry continuity, source/person continuity, and one qualified
 * view for the complete half-open cycle.
 */
internal class VerifiedShadowCompletedCycleScope private constructor(
    val phaseArtifactSha256: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val source: ShadowSourceContinuityToken,
    val person: ShadowPersonContinuityToken,
) {
    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "verifiedShadowCompletedCycleScopeSchemaVersion" to "1",
            "phaseArtifactSha256" to phaseArtifactSha256,
            "startTimestampMs" to startTimestampMs.toString(),
            "endTimestampMs" to endTimestampMs.toString(),
            "interval" to "START_INCLUSIVE_END_EXCLUSIVE",
        ),
    )

    init {
        requireSha256("phaseArtifactSha256", phaseArtifactSha256)
        require(startTimestampMs >= 0L)
        require(endTimestampMs >= 0L)
        require(person.source === source)
    }
}

internal enum class ShadowScalarAbstention {
    FEATURE_UNAVAILABLE,
    QUALITY_UNAVAILABLE,
    MEASUREMENT_UNAVAILABLE,
}

internal class ShadowScalarChannelInput private constructor(
    val value: Double?,
    val abstention: ShadowScalarAbstention?,
) {
    init {
        require((value == null) != (abstention == null))
        require(value == null || value.isFinite())
    }

    companion object {
        fun measured(value: Double): ShadowScalarChannelInput =
            ShadowScalarChannelInput(value = value, abstention = null)

        fun abstained(reason: ShadowScalarAbstention): ShadowScalarChannelInput =
            ShadowScalarChannelInput(value = null, abstention = reason)
    }
}

/** Already-sampled scalar channels; this type cannot carry pose coordinates or media. */
internal class ShadowSampledScalarInput(
    val timestampMs: Long,
    val source: ShadowSourceContinuityToken,
    val person: ShadowPersonContinuityToken?,
    val qualifiedView: ShadowQualifiedViewToken?,
    val capabilityProviderArtifacts: ShadowCapabilityProviderArtifacts,
    channels: Map<ShadowScalarSide, ShadowScalarChannelInput>,
) {
    val channels: Map<ShadowScalarSide, ShadowScalarChannelInput> = immutableSortedMap(channels)

    init {
        require(timestampMs >= 0L)
        require(person == null || person.source === source)
        require(
            qualifiedView == null ||
                qualifiedView.source === source &&
                qualifiedView.person === person &&
                qualifiedView.timestampMs == timestampMs,
        )
        require(this.channels.isNotEmpty())
    }
}

internal class ShadowScalarAggregate(
    val inputCount: Int,
    val measuredCount: Int,
    val abstentionCount: Int,
    val coverage: Double,
    val minimum: Double?,
    val maximum: Double?,
    val mean: Double?,
    abstentionCounts: Map<ShadowScalarAbstention, Int>,
) {
    val abstentionCounts: Map<ShadowScalarAbstention, Int> = immutableSortedMap(abstentionCounts)

    init {
        require(inputCount >= 0)
        require(measuredCount >= 0)
        require(abstentionCount >= 0)
        require(measuredCount + abstentionCount == inputCount)
        require(coverage.isFinite() && coverage in 0.0..1.0)
        require((measuredCount == 0) == (minimum == null && maximum == null && mean == null))
        require(minimum == null || minimum.isFinite())
        require(maximum == null || maximum.isFinite())
        require(mean == null || mean.isFinite())
        require(this.abstentionCounts.values.all { count -> count > 0 })
        require(this.abstentionCounts.values.sum() == abstentionCount)
    }
}

/** Aggregate-only completed-cycle output. No input timestamp series is retained here. */
internal class ShadowCompletedCycleAggregate(
    val inputCount: Int,
    channelAggregates: Map<ShadowScalarSide, ShadowScalarAggregate>,
    val provenanceSha256: String,
) {
    val channelAggregates: Map<ShadowScalarSide, ShadowScalarAggregate> =
        immutableSortedMap(channelAggregates)

    init {
        require(inputCount > 0)
        require(this.channelAggregates.isNotEmpty())
        require(this.channelAggregates.values.all { aggregate ->
            aggregate.inputCount == inputCount
        })
        requireSha256("provenanceSha256", provenanceSha256)
    }
}

/**
 * Pure in-memory reducer for an explicitly delimited completed-cycle interval `[start, end)`.
 * Inputs at `end` are retained for the next adjacent interval.
 */
internal class PoseShadowMeasurementKernel private constructor(
    val request: ShadowMeasurementExecutionRequest,
) {
    private class ActiveCycle(
        val startTimestampMs: Long,
        initialInputs: List<ShadowSampledScalarInput>,
    ) {
        val inputs: MutableList<ShadowSampledScalarInput> = initialInputs.toMutableList()
        var temporalDiscontinuity: Boolean = false
        var expired: Boolean = false
        var overflowed: Boolean = false
        var unassignedOverflow: Boolean = false
    }

    private var activeCycle: ActiveCycle? = null
    private var carriedInputs: List<ShadowSampledScalarInput> = emptyList()
    private var carriedOverflow: Boolean = false

    fun beginCycle(startTimestampMs: Long) {
        require(startTimestampMs >= 0L)
        check(activeCycle == null) { "A cycle is already active" }
        val retained = carriedInputs.filter { input -> input.timestampMs >= startTimestampMs }
        carriedInputs = emptyList()
        activeCycle = ActiveCycle(startTimestampMs, retained).also { cycle ->
            cycle.overflowed = carriedOverflow ||
                retained.size > request.measurementRuntimeContract.maximumSamplesPerCycle
        }
        carriedOverflow = false
    }

    fun accept(input: ShadowSampledScalarInput) {
        val cycle = checkNotNull(activeCycle) { "A completed-cycle scope has not started" }
        if (cycle.inputs.size >= request.measurementRuntimeContract.maximumSamplesPerCycle) {
            cycle.overflowed = true
            cycle.unassignedOverflow = true
            return
        }
        val previousTimestampMs = cycle.inputs.lastOrNull()?.timestampMs
        if (input.timestampMs < cycle.startTimestampMs) cycle.temporalDiscontinuity = true
        if (previousTimestampMs != null && input.timestampMs < previousTimestampMs) {
            cycle.temporalDiscontinuity = true
        }
        if (
            input.timestampMs - cycle.startTimestampMs >
            request.measurementRuntimeContract.maximumCycleDurationMs
        ) {
            cycle.expired = true
        }
        cycle.inputs += input
    }

    fun expireAt(timestampMs: Long): Boolean {
        require(timestampMs >= 0L)
        val cycle = checkNotNull(activeCycle) { "A completed-cycle scope has not started" }
        if (timestampMs < cycle.startTimestampMs) {
            cycle.temporalDiscontinuity = true
            return true
        }
        if (
            timestampMs - cycle.startTimestampMs >
            request.measurementRuntimeContract.maximumCycleDurationMs
        ) {
            cycle.expired = true
        }
        return cycle.expired
    }

    fun abandonCycle() {
        activeCycle = null
        carriedInputs = emptyList()
        carriedOverflow = false
    }

    fun completeCycle(
        completedScope: VerifiedShadowCompletedCycleScope,
    ): ShadowCompletedCycleAggregate? {
        val cycle = checkNotNull(activeCycle) { "A completed-cycle scope has not started" }
        val endTimestampMs = completedScope.endTimestampMs
        if (
            completedScope.phaseArtifactSha256 != request.phaseRuntimeIdentity.phaseArtifactSha256 ||
            completedScope.startTimestampMs != cycle.startTimestampMs ||
            endTimestampMs <= cycle.startTimestampMs
        ) {
            abandonCycle()
            return null
        }
        val completedCycleScopeArtifactSha256 = completedScope.artifactSha256

        val includedInputs = cycle.inputs.filter { input -> input.timestampMs < endTimestampMs }
        val nextInputs = cycle.inputs.filter { input -> input.timestampMs >= endTimestampMs }
        carriedOverflow = cycle.unassignedOverflow ||
            nextInputs.size > request.measurementRuntimeContract.maximumSamplesPerCycle
        carriedInputs = nextInputs.take(request.measurementRuntimeContract.maximumSamplesPerCycle)
        activeCycle = null

        if (cycle.temporalDiscontinuity || cycle.expired || cycle.overflowed) return null
        if (
            endTimestampMs - cycle.startTimestampMs >
            request.measurementRuntimeContract.maximumCycleDurationMs
        ) return null
        if (includedInputs.any { input -> input.timestampMs < cycle.startTimestampMs }) return null

        val distinctInputs = deduplicateFirstInput(includedInputs) ?: return null
        if (distinctInputs.isEmpty()) return null
        if (
            distinctInputs.size > request.measurementRuntimeContract.maximumSamplesPerCycle
        ) return null
        if (
            distinctInputs.zipWithNext().any { (left, right) ->
                right.timestampMs - left.timestampMs >
                    request.measurementRuntimeContract.maximumSampleGapMs
            }
        ) return null
        if (
            distinctInputs.first().timestampMs - cycle.startTimestampMs >
            request.measurementRuntimeContract.maximumSampleGapMs ||
            endTimestampMs - distinctInputs.last().timestampMs >
            request.measurementRuntimeContract.maximumSampleGapMs
        ) return null
        if (!hasContinuousRuntimeIdentity(distinctInputs, completedScope)) return null

        val channelAggregates = linkedMapOf<ShadowScalarSide, ShadowScalarAggregate>()
        request.sideRuntimePolicy.sideChannels.forEach { side ->
            val channelAggregate = aggregate(
                distinctInputs.map { input -> requireNotNull(input.channels[side]) },
            ) ?: return null
            channelAggregates[side] = channelAggregate
        }
        val provenanceSha256 = aggregateProvenanceSha256(
            requestContentSha256 = request.contentSha256,
            completedCycleScopeArtifactSha256 = completedCycleScopeArtifactSha256,
            inputCount = distinctInputs.size,
            channelAggregates = channelAggregates,
        )
        return ShadowCompletedCycleAggregate(
            inputCount = distinctInputs.size,
            channelAggregates = channelAggregates,
            provenanceSha256 = provenanceSha256,
        )
    }

    private fun deduplicateFirstInput(
        inputs: List<ShadowSampledScalarInput>,
    ): List<ShadowSampledScalarInput>? = buildList {
        var previousTimestampMs: Long? = null
        inputs.forEach { input ->
            val previous = previousTimestampMs
            if (previous != null && input.timestampMs < previous) return null
            if (input.timestampMs != previous) add(input)
            previousTimestampMs = input.timestampMs
        }
    }

    private fun hasContinuousRuntimeIdentity(
        inputs: List<ShadowSampledScalarInput>,
        completedScope: VerifiedShadowCompletedCycleScope,
    ): Boolean {
        val expectedSides = request.sideRuntimePolicy.sideChannels
        val first = inputs.first()
        val firstPerson = first.person ?: return false
        val firstView = first.qualifiedView ?: return false
        if (
            first.source !== completedScope.source ||
            firstPerson !== completedScope.person ||
            !matchesPinnedArtifacts(first)
        ) return false
        return inputs.all { input ->
            input.source === first.source &&
                input.person === firstPerson &&
                input.qualifiedView != null &&
                input.qualifiedView.source === first.source &&
                input.qualifiedView.person === firstPerson &&
                matchesPinnedArtifacts(input) &&
                input.channels.keys == expectedSides
        } && firstView.source === first.source && firstView.person === firstPerson
    }

    private fun matchesPinnedArtifacts(input: ShadowSampledScalarInput): Boolean {
        val view = input.qualifiedView ?: return false
        return input.source.observationContractArtifactSha256 ==
            request.observationRuntimeIdentity.observationContractArtifactSha256 &&
            input.source.runtimeDomainId == request.observationRuntimeIdentity.runtimeDomainId &&
            view.viewContractId == request.viewRuntimeIdentity.selectedViewContractId &&
            view.qualifierArtifactId == request.viewRuntimeIdentity.viewQualifierArtifactId &&
            view.qualifierArtifactSha256 ==
            request.viewRuntimeIdentity.viewQualifierArtifactSha256 &&
            input.capabilityProviderArtifacts.artifactSha256 ==
            request.capabilityProviderArtifacts.artifactSha256
    }

    companion object {
        fun open(
            request: ShadowMeasurementExecutionRequest,
            authorization: VerifiedShadowExecutionAuthorization?,
        ): PoseShadowMeasurementKernel? {
            if (!VerifiedShadowExecutionAuthorization.authorizes(request, authorization)) {
                return null
            }
            return PoseShadowMeasurementKernel(request)
        }
    }
}

private fun aggregate(inputs: List<ShadowScalarChannelInput>): ShadowScalarAggregate? {
    var measuredCount = 0
    var mean = 0.0
    var minimum: Double? = null
    var maximum: Double? = null
    val abstentions = linkedMapOf<ShadowScalarAbstention, Int>()

    inputs.forEach { input ->
        val value = input.value
        if (value == null) {
            val reason = requireNotNull(input.abstention)
            abstentions[reason] = (abstentions[reason] ?: 0) + 1
        } else {
            val nextCount = measuredCount + 1
            val nextMean = mean + (value - mean) / nextCount
            if (!nextMean.isFinite()) return null
            measuredCount = nextCount
            mean = nextMean
            minimum = minimum?.let { current -> minOf(current, value) } ?: value
            maximum = maximum?.let { current -> maxOf(current, value) } ?: value
        }
    }
    val inputCount = inputs.size
    val abstentionCount = inputCount - measuredCount
    return ShadowScalarAggregate(
        inputCount = inputCount,
        measuredCount = measuredCount,
        abstentionCount = abstentionCount,
        coverage = if (inputCount == 0) 0.0 else measuredCount.toDouble() / inputCount,
        minimum = minimum,
        maximum = maximum,
        mean = mean.takeIf { measuredCount > 0 },
        abstentionCounts = abstentions,
    )
}

private fun aggregateProvenanceSha256(
    requestContentSha256: String,
    completedCycleScopeArtifactSha256: String,
    inputCount: Int,
    channelAggregates: Map<ShadowScalarSide, ShadowScalarAggregate>,
): String = canonicalFieldsSha256(
    buildList {
        add("shadowCompletedCycleAggregateSchemaVersion" to "1")
        add("requestContentSha256" to requestContentSha256)
        add("completedCycleScopeArtifactSha256" to completedCycleScopeArtifactSha256)
        add("inputCount" to inputCount.toString())
        channelAggregates.entries.sortedBy { entry -> entry.key.name }
            .forEachIndexed { index, entry ->
                val aggregate = entry.value
                val prefix = "channel[$index]"
                add("$prefix.side" to entry.key.name)
                add("$prefix.inputCount" to aggregate.inputCount.toString())
                add("$prefix.measuredCount" to aggregate.measuredCount.toString())
                add("$prefix.abstentionCount" to aggregate.abstentionCount.toString())
                add("$prefix.coverage" to java.lang.Double.toHexString(aggregate.coverage))
                add("$prefix.minimum" to aggregate.minimum?.let(java.lang.Double::toHexString).orEmpty())
                add("$prefix.maximum" to aggregate.maximum?.let(java.lang.Double::toHexString).orEmpty())
                add("$prefix.mean" to aggregate.mean?.let(java.lang.Double::toHexString).orEmpty())
                aggregate.abstentionCounts.entries.sortedBy { item -> item.key.name }
                    .forEachIndexed { abstentionIndex, abstention ->
                        add("$prefix.abstention[$abstentionIndex].kind" to abstention.key.name)
                        add("$prefix.abstention[$abstentionIndex].count" to abstention.value.toString())
                    }
            }
    },
)

private fun expectedSides(kind: ShadowSidePolicyKind): Set<ShadowScalarSide> = when (kind) {
    ShadowSidePolicyKind.MIDLINE -> setOf(ShadowScalarSide.MIDLINE)
    ShadowSidePolicyKind.GLOBAL_BODY -> setOf(ShadowScalarSide.GLOBAL)
    ShadowSidePolicyKind.BILATERAL_COUPLED,
    ShadowSidePolicyKind.BILATERAL_INDEPENDENT,
    ShadowSidePolicyKind.ACTIVE_LIMB,
    ShadowSidePolicyKind.LEAD_LIMB,
    ShadowSidePolicyKind.TRAIL_LIMB,
    ShadowSidePolicyKind.ALTERNATING_PAIR,
    ShadowSidePolicyKind.CONTRALATERAL_PAIR,
    -> setOf(ShadowScalarSide.LEFT, ShadowScalarSide.RIGHT)
}

private fun requireVersionedId(fieldName: String, value: String) {
    require(SHADOW_CORE_VERSIONED_ID.matches(value)) {
        "$fieldName must be a lowercase versioned identifier"
    }
}

private fun requireSha256(fieldName: String, value: String) {
    require(SHADOW_CORE_SHA256.matches(value)) {
        "$fieldName must be a lowercase SHA-256"
    }
}

private fun <K : Enum<K>, V> immutableSortedMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(
        source.entries.sortedBy { entry -> entry.key.name }
            .associateTo(LinkedHashMap()) { entry -> entry.key to entry.value },
    )

private fun immutableSortedStringMap(source: Map<String, String>): Map<String, String> =
    Collections.unmodifiableMap(LinkedHashMap(source.toSortedMap()))

private fun <E : Enum<E>> immutableSortedSet(source: Set<E>): Set<E> =
    Collections.unmodifiableSet(LinkedHashSet(source.sortedBy { value -> value.name }))

private const val MAXIMUM_ON_DEVICE_SHADOW_SAMPLES = 2_048
private const val SHADOW_COMPLETED_CYCLE_PHASE_ROLE_ID = "trex.phase-role.full-cycle.v1"
