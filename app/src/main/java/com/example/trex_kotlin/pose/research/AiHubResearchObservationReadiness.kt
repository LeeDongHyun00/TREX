package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyCatalog
import com.example.trex_kotlin.pose.policy.AiHubCriterionObservability
import com.example.trex_kotlin.pose.policy.AiHubCriterionReviewState
import com.example.trex_kotlin.pose.policy.AiHubCriterionSidePolicyKind
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import java.util.Collections

internal enum class AiHubResearchObservationPolicySideClass {
    FIXED_NO_ROLE_RESOLVER,
    ROLE_RESOLVER_REQUIRED,
}

internal enum class AiHubResearchObservationBlocker {
    CAPABILITY_RECEIPT_UNAVAILABLE,
    CALIBRATION_ARTIFACT_UNAVAILABLE,
    PHASE_SCOPE_UNAVAILABLE,
    REFERENCE_EVIDENCE_UNAVAILABLE,
    ROLE_RESOLVER_UNAVAILABLE,
    SHADOW_AUTHORIZATION_UNAVAILABLE,
    TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE,
    RELEASE_AUTHORIZATION_UNAVAILABLE,
}

/** Static projection of one exact generated catalog binding; it contains no evaluator. */
internal class AiHubResearchObservationPolicyProfile internal constructor(
    val bindingId: String,
    val bindingPolicySha256: String,
    val exercise: AiHubExercise,
    val sourceConditionId: String,
    phaseRoleIds: Collection<String>,
    val sidePolicyKind: AiHubCriterionSidePolicyKind,
    val roleResolverContractId: String?,
    val sideClass: AiHubResearchObservationPolicySideClass,
) {
    val phaseRoleIds: List<String> =
        Collections.unmodifiableList(ArrayList(phaseRoleIds))
    val requiredCapabilityIds: List<String> =
        PoseObservationResearchCapabilities.EXACT_CAPABILITY_IDS
    val requiredViewContractId: String =
        PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID

    init {
        require(phaseRoleIds.isNotEmpty())
    }
}

/** Per-binding research readiness. Every product-authority flag is permanently false. */
internal class AiHubResearchObservationPolicyAssessment internal constructor(
    val profile: AiHubResearchObservationPolicyProfile,
    val capabilityEvidenceSatisfied: Boolean,
    blockers: Set<AiHubResearchObservationBlocker>,
) {
    val blockers: Set<AiHubResearchObservationBlocker> =
        Collections.unmodifiableSet(LinkedHashSet(blockers.sortedBy { it.name }))
    val phaseScopeSatisfied: Boolean = false
    val calibrationArtifactSatisfied: Boolean = false
    val referenceEvidenceSatisfied: Boolean = false
    val roleResolverSatisfied: Boolean =
        profile.sideClass == AiHubResearchObservationPolicySideClass.FIXED_NO_ROLE_RESOLVER
    val shadowAuthorizationSatisfied: Boolean = false
    val trustedEvidenceIntakeSatisfied: Boolean = false
    val releaseAuthorizationSatisfied: Boolean = false
    val measurementEnabled: Boolean = false
    val shadowEnabled: Boolean = false
    val verdictEnabled: Boolean = false
    val scoreEnabled: Boolean = false
    val cueEnabled: Boolean = false
    val releaseEnabled: Boolean = false

    init {
        require(AiHubResearchObservationBlocker.PHASE_SCOPE_UNAVAILABLE in this.blockers)
        require(
            AiHubResearchObservationBlocker.CALIBRATION_ARTIFACT_UNAVAILABLE in this.blockers,
        )
        require(
            AiHubResearchObservationBlocker.SHADOW_AUTHORIZATION_UNAVAILABLE in this.blockers,
        )
        require(
            AiHubResearchObservationBlocker.REFERENCE_EVIDENCE_UNAVAILABLE in this.blockers,
        )
        require(
            AiHubResearchObservationBlocker.TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE in this.blockers,
        )
        require(
            AiHubResearchObservationBlocker.RELEASE_AUTHORIZATION_UNAVAILABLE in this.blockers,
        )
        require(
            capabilityEvidenceSatisfied ==
                (AiHubResearchObservationBlocker.CAPABILITY_RECEIPT_UNAVAILABLE !in this.blockers),
        )
        require(
            roleResolverSatisfied ==
                (AiHubResearchObservationBlocker.ROLE_RESOLVER_UNAVAILABLE !in this.blockers),
        )
    }
}

/**
 * Immutable aggregate over the exact generated policy subset compatible with base+lateral
 * observation evidence. It is diagnostic research output and grants no runtime authority.
 */
internal class AiHubResearchObservationReadinessAssessment internal constructor(
    val exercise: AiHubExercise,
    val generatedPolicySha256: String,
    val generatedRegistrySha256: String,
    val capabilityEvidenceContractSha256: String?,
    val capabilityReceiptSha256: String?,
    policyAssessments: List<AiHubResearchObservationPolicyAssessment>,
) {
    val policyAssessments: List<AiHubResearchObservationPolicyAssessment> =
        Collections.unmodifiableList(ArrayList(policyAssessments))
    val baseAndLateralPolicyCount: Int = policyAssessments.size
    val fixedSidePolicyCount: Int = policyAssessments.count { assessment ->
        assessment.profile.sideClass ==
            AiHubResearchObservationPolicySideClass.FIXED_NO_ROLE_RESOLVER
    }
    val roleResolverBlockedPolicyCount: Int = policyAssessments.count { assessment ->
        AiHubResearchObservationBlocker.ROLE_RESOLVER_UNAVAILABLE in assessment.blockers
    }
    val phaseBlockedPolicyCount: Int = policyAssessments.count { assessment ->
        AiHubResearchObservationBlocker.PHASE_SCOPE_UNAVAILABLE in assessment.blockers
    }
    val calibrationBlockedPolicyCount: Int = policyAssessments.count { assessment ->
        AiHubResearchObservationBlocker.CALIBRATION_ARTIFACT_UNAVAILABLE in assessment.blockers
    }
    val shadowAuthorizationBlockedPolicyCount: Int = policyAssessments.count { assessment ->
        AiHubResearchObservationBlocker.SHADOW_AUTHORIZATION_UNAVAILABLE in assessment.blockers
    }
    val referenceEvidenceBlockedPolicyCount: Int = policyAssessments.count { assessment ->
        AiHubResearchObservationBlocker.REFERENCE_EVIDENCE_UNAVAILABLE in assessment.blockers
    }
    val trustedEvidenceIntakeBlockedPolicyCount: Int = policyAssessments.count { assessment ->
        AiHubResearchObservationBlocker.TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE in assessment.blockers
    }
    val releaseAuthorizationBlockedPolicyCount: Int = policyAssessments.count { assessment ->
        AiHubResearchObservationBlocker.RELEASE_AUTHORIZATION_UNAVAILABLE in assessment.blockers
    }
    val capabilityReadyPolicyCount: Int = policyAssessments.count(
        AiHubResearchObservationPolicyAssessment::capabilityEvidenceSatisfied,
    )
    val measurementEnabled: Boolean = false
    val shadowEnabled: Boolean = false
    val verdictEnabled: Boolean = false
    val scoreEnabled: Boolean = false
    val cueEnabled: Boolean = false
    val releaseEnabled: Boolean = false

    init {
        require(phaseBlockedPolicyCount == baseAndLateralPolicyCount)
        require(calibrationBlockedPolicyCount == baseAndLateralPolicyCount)
        require(shadowAuthorizationBlockedPolicyCount == baseAndLateralPolicyCount)
        require(referenceEvidenceBlockedPolicyCount == baseAndLateralPolicyCount)
        require(trustedEvidenceIntakeBlockedPolicyCount == baseAndLateralPolicyCount)
        require(releaseAuthorizationBlockedPolicyCount == baseAndLateralPolicyCount)
        require(this.policyAssessments.all { it.profile.exercise == exercise })
    }
}

/** Catalog-scale, fail-closed research readiness over a precomputed policy projection. */
internal object AiHubResearchObservationReadiness {
    private const val EXPECTED_BASE_AND_LATERAL_POLICY_COUNT = 24
    private const val EXPECTED_FIXED_SIDE_POLICY_COUNT = 18
    private const val EXPECTED_ROLE_RESOLVER_POLICY_COUNT = 6

    private val staticPolicyProfiles: List<AiHubResearchObservationPolicyProfile> =
        buildStaticPolicyProfiles()
    private val profilesByExercise: Map<AiHubExercise, List<AiHubResearchObservationPolicyProfile>> =
        Collections.unmodifiableMap(
            staticPolicyProfiles
                .groupBy(AiHubResearchObservationPolicyProfile::exercise)
                .mapValuesTo(LinkedHashMap()) { (_, profiles) ->
                    Collections.unmodifiableList(ArrayList(profiles))
                },
        )

    val baseAndLateralPolicyCount: Int = staticPolicyProfiles.size
    val fixedSidePolicyCount: Int = staticPolicyProfiles.count { it.roleResolverContractId == null }
    val roleResolverPolicyCount: Int =
        staticPolicyProfiles.count { it.roleResolverContractId != null }

    fun assess(
        exercise: AiHubExercise,
        currentObservation: AttestedPoseObservation,
        receipt: PoseObservationResearchCapabilityReceipt?,
    ): AiHubResearchObservationReadinessAssessment {
        val capabilityEvidenceSatisfied =
            receipt?.hasCanonicalProvenance(currentObservation) == true
        val assessments = profilesByExercise[exercise].orEmpty().map { profile ->
            val blockers = linkedSetOf(
                AiHubResearchObservationBlocker.CALIBRATION_ARTIFACT_UNAVAILABLE,
                AiHubResearchObservationBlocker.PHASE_SCOPE_UNAVAILABLE,
                AiHubResearchObservationBlocker.REFERENCE_EVIDENCE_UNAVAILABLE,
                AiHubResearchObservationBlocker.SHADOW_AUTHORIZATION_UNAVAILABLE,
                AiHubResearchObservationBlocker.TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE,
                AiHubResearchObservationBlocker.RELEASE_AUTHORIZATION_UNAVAILABLE,
            )
            if (!capabilityEvidenceSatisfied) {
                blockers += AiHubResearchObservationBlocker.CAPABILITY_RECEIPT_UNAVAILABLE
            }
            if (profile.sideClass ==
                AiHubResearchObservationPolicySideClass.ROLE_RESOLVER_REQUIRED
            ) {
                blockers += AiHubResearchObservationBlocker.ROLE_RESOLVER_UNAVAILABLE
            }
            AiHubResearchObservationPolicyAssessment(
                profile = profile,
                capabilityEvidenceSatisfied = capabilityEvidenceSatisfied,
                blockers = blockers,
            )
        }
        return AiHubResearchObservationReadinessAssessment(
            exercise = exercise,
            generatedPolicySha256 = AiHubCriterionPolicyCatalog.POLICY_SHA256,
            generatedRegistrySha256 = AiHubCriterionPolicyCatalog.REGISTRY_SHA256,
            capabilityEvidenceContractSha256 = receipt
                ?.evidenceContractSha256
                ?.takeIf { capabilityEvidenceSatisfied },
            capabilityReceiptSha256 = receipt
                ?.receiptSha256
                ?.takeIf { capabilityEvidenceSatisfied },
            policyAssessments = Collections.unmodifiableList(assessments),
        )
    }

    internal fun policyProfilesForTest(): List<AiHubResearchObservationPolicyProfile> =
        staticPolicyProfiles

    private fun buildStaticPolicyProfiles(): List<AiHubResearchObservationPolicyProfile> {
        val profiles = AiHubCriterionPolicyCatalog.registry.bindings.mapNotNull { binding ->
            val interpretation = binding.interpretation ?: return@mapNotNull null
            if (binding.reviewState != AiHubCriterionReviewState.REVIEWED_ENGINEERING_V1 ||
                interpretation.observability != AiHubCriterionObservability.DIRECT ||
                interpretation.requiredCapabilityIds !=
                PoseObservationResearchCapabilities.EXACT_CAPABILITY_IDS ||
                PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID !in
                interpretation.viewApplicability.viewContractIds
            ) {
                return@mapNotNull null
            }
            val roleResolverContractId = interpretation.sidePolicy.roleResolverContractId
            AiHubResearchObservationPolicyProfile(
                bindingId = binding.bindingId,
                bindingPolicySha256 = binding.bindingPolicySha256,
                exercise = binding.exercise,
                sourceConditionId = binding.sourceConditionId,
                phaseRoleIds = interpretation.phaseApplicability.phaseRoleIds,
                sidePolicyKind = interpretation.sidePolicy.kind,
                roleResolverContractId = roleResolverContractId,
                sideClass = if (roleResolverContractId == null) {
                    AiHubResearchObservationPolicySideClass.FIXED_NO_ROLE_RESOLVER
                } else {
                    AiHubResearchObservationPolicySideClass.ROLE_RESOLVER_REQUIRED
                },
            )
        }
        require(profiles.size == EXPECTED_BASE_AND_LATERAL_POLICY_COUNT) {
            "Generated base+lateral policy projection drifted: ${profiles.size}"
        }
        require(profiles.count { it.roleResolverContractId == null } ==
            EXPECTED_FIXED_SIDE_POLICY_COUNT) {
            "Generated fixed-side policy projection drifted"
        }
        require(profiles.count { it.roleResolverContractId != null } ==
            EXPECTED_ROLE_RESOLVER_POLICY_COUNT) {
            "Generated resolver policy projection drifted"
        }
        return Collections.unmodifiableList(profiles)
    }
}
