package com.example.trex_kotlin.pose.readiness

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.policy.AiHubCriterionCalibrationProvenanceState
import com.example.trex_kotlin.pose.policy.AiHubCriterionObservability
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyCatalog
import com.example.trex_kotlin.pose.policy.AiHubCriterionReviewState
import com.example.trex_kotlin.pose.policy.AiHubCriterionSidePolicyKind
import com.example.trex_kotlin.pose.policy.AiHubCriterionViewApplicabilityState
import com.example.trex_kotlin.pose.research.PoseObservationResearchCapabilities
import com.example.trex_kotlin.pose.research.PoseObservationResearchCapabilityReceipt
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

/** Symbolic decision slots from the catalog-wide Gold annotation contract. */
internal enum class PoseCriterionSymbolicSideSlot {
    MIDLINE,
    GLOBAL_BODY,
    BILATERAL_PAIR,
    LEFT,
    RIGHT,
    ACTIVE_LIMB,
    LEAD_LIMB,
    TRAIL_LIMB,
    ALTERNATING_PAIR,
    CONTRALATERAL_PAIR,
}

/** Every reason is an abstention cause; none grants measurement or product authority. */
internal enum class PoseCriterionRuntimeReadinessBlocker {
    SOURCE_INTERPRETATION_UNRESOLVED,
    PHASE_SCOPE_CONTRACT_UNAVAILABLE,
    SIDE_ROLE_RESOLVER_UNAVAILABLE,
    QUALIFIED_VIEW_EVIDENCE_UNAVAILABLE,
    CAMERA_VIEW_NOT_SUFFICIENT,
    REQUIRED_CAPABILITY_EVIDENCE_UNAVAILABLE,
    MEASUREMENT_CONSTRUCT_PROVIDER_UNAVAILABLE,
    PROXY_GOLD_VALIDATION_UNAVAILABLE,
    CALIBRATION_ARTIFACT_UNAVAILABLE,
    REFERENCE_EVIDENCE_UNAVAILABLE,
    TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE,
    SHADOW_AUTHORIZATION_UNAVAILABLE,
    RELEASE_AUTHORIZATION_UNAVAILABLE,
}

internal enum class PoseCriterionRuntimeDecisionState {
    UNKNOWN,
}

/** Immutable exact projection of one M10 annotation template. */
internal class PoseCriterionRuntimeTemplateProfile internal constructor(
    val annotationTemplateId: String,
    val bindingId: String,
    val bindingPolicySha256: String,
    val exercise: AiHubExercise,
    val sourceConditionId: String,
    val phaseRoleId: String,
    val sidePolicyKind: AiHubCriterionSidePolicyKind,
    val symbolicSideSlot: PoseCriterionSymbolicSideSlot,
    val roleResolverContractId: String?,
    val measurementConstructId: String,
    val observability: AiHubCriterionObservability,
    requiredViewContractIds: Collection<String>,
    requiredCapabilityIds: Collection<String>,
    val calibrationProvenanceState: AiHubCriterionCalibrationProvenanceState,
) {
    val requiredViewContractIds: List<String> =
        Collections.unmodifiableList(ArrayList(requiredViewContractIds))
    val requiredCapabilityIds: List<String> =
        Collections.unmodifiableList(ArrayList(requiredCapabilityIds))

    init {
        require(annotationTemplateId.matches(ANNOTATION_TEMPLATE_ID))
        require(phaseRoleId.isNotBlank())
        require(this.requiredCapabilityIds.isNotEmpty())
        require(
            (roleResolverContractId != null) == sidePolicyKind.requiresRoleResolver(),
        )
        require(symbolicSideSlot in sidePolicyKind.symbolicSlots())
    }

    private companion object {
        val ANNOTATION_TEMPLATE_ID =
            Regex("^trex\\.annotation-template-sha256-[0-9a-f]{64}$")
    }
}

/** One unresolved source binding is retained explicitly and never expanded into a template. */
internal class PoseCriterionUnresolvedBindingProfile internal constructor(
    val bindingId: String,
    val bindingPolicySha256: String,
    val exercise: AiHubExercise,
    val sourceConditionId: String,
    val reviewState: AiHubCriterionReviewState,
    reasonCodes: Collection<String>,
) {
    val reasonCodes: List<String> = Collections.unmodifiableList(ArrayList(reasonCodes))
    val decisionState: PoseCriterionRuntimeDecisionState =
        PoseCriterionRuntimeDecisionState.UNKNOWN
    val blockers: Set<PoseCriterionRuntimeReadinessBlocker> =
        Collections.singleton(PoseCriterionRuntimeReadinessBlocker.SOURCE_INTERPRETATION_UNRESOLVED)
    val measurementEnabled: Boolean = false
    val verdictEnabled: Boolean = false
    val scoreEnabled: Boolean = false
    val cueEnabled: Boolean = false
    val shadowEnabled: Boolean = false
    val releaseEnabled: Boolean = false

    init {
        require(reviewState != AiHubCriterionReviewState.REVIEWED_ENGINEERING_V1)
        require(this.reasonCodes.isNotEmpty())
    }
}

/** Per-template evidence join. A satisfied camera receipt still cannot authorize evaluation. */
internal class PoseCriterionRuntimeTemplateAssessment internal constructor(
    val profile: PoseCriterionRuntimeTemplateProfile,
    val capabilityEvidenceSatisfied: Boolean,
    val qualifiedViewEvidenceSatisfied: Boolean,
    blockers: Collection<PoseCriterionRuntimeReadinessBlocker>,
) {
    val blockers: Set<PoseCriterionRuntimeReadinessBlocker> =
        Collections.unmodifiableSet(LinkedHashSet(blockers.sortedBy { it.name }))
    val observationEvidenceSatisfied: Boolean =
        capabilityEvidenceSatisfied && qualifiedViewEvidenceSatisfied
    val phaseScopeSatisfied: Boolean = false
    val sideRoleResolverSatisfied: Boolean = !profile.sidePolicyKind.requiresRoleResolver()
    val measurementConstructProviderSatisfied: Boolean = false
    val calibrationArtifactSatisfied: Boolean = false
    val referenceEvidenceSatisfied: Boolean = false
    val trustedEvidenceIntakeSatisfied: Boolean = false
    val shadowAuthorizationSatisfied: Boolean = false
    val releaseAuthorizationSatisfied: Boolean = false
    val decisionState: PoseCriterionRuntimeDecisionState =
        PoseCriterionRuntimeDecisionState.UNKNOWN
    val measurementEnabled: Boolean = false
    val verdictEnabled: Boolean = false
    val scoreEnabled: Boolean = false
    val cueEnabled: Boolean = false
    val shadowEnabled: Boolean = false
    val releaseEnabled: Boolean = false

    init {
        require(PoseCriterionRuntimeReadinessBlocker.PHASE_SCOPE_CONTRACT_UNAVAILABLE in blockers)
        require(
            PoseCriterionRuntimeReadinessBlocker.MEASUREMENT_CONSTRUCT_PROVIDER_UNAVAILABLE in
                blockers,
        )
        require(PoseCriterionRuntimeReadinessBlocker.CALIBRATION_ARTIFACT_UNAVAILABLE in blockers)
        require(PoseCriterionRuntimeReadinessBlocker.REFERENCE_EVIDENCE_UNAVAILABLE in blockers)
        require(PoseCriterionRuntimeReadinessBlocker.TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE in blockers)
        require(PoseCriterionRuntimeReadinessBlocker.SHADOW_AUTHORIZATION_UNAVAILABLE in blockers)
        require(PoseCriterionRuntimeReadinessBlocker.RELEASE_AUTHORIZATION_UNAVAILABLE in blockers)
        require(
            capabilityEvidenceSatisfied ==
                (PoseCriterionRuntimeReadinessBlocker.REQUIRED_CAPABILITY_EVIDENCE_UNAVAILABLE !in
                    blockers),
        )
        require(
            qualifiedViewEvidenceSatisfied ==
                (PoseCriterionRuntimeReadinessBlocker.QUALIFIED_VIEW_EVIDENCE_UNAVAILABLE !in
                    blockers &&
                    PoseCriterionRuntimeReadinessBlocker.CAMERA_VIEW_NOT_SUFFICIENT !in blockers),
        )
        require(
            sideRoleResolverSatisfied ==
                (PoseCriterionRuntimeReadinessBlocker.SIDE_ROLE_RESOLVER_UNAVAILABLE !in blockers),
        )
        require(
            (profile.observability == AiHubCriterionObservability.PROXY_UNVALIDATED) ==
                (PoseCriterionRuntimeReadinessBlocker.PROXY_GOLD_VALIDATION_UNAVAILABLE in blockers),
        )
    }
}

/** Immutable exercise-scoped readiness output. It contains hashes and reasons, never pose data. */
internal class PoseCriterionRuntimeReadinessAssessment internal constructor(
    val exercise: AiHubExercise,
    val policySha256: String,
    val registrySha256: String,
    val annotationContractSha256: String,
    val scopeResolverRequirementsSha256: String,
    val capabilityEvidenceContractSha256: String?,
    val capabilityReceiptSha256: String?,
    templateAssessments: Collection<PoseCriterionRuntimeTemplateAssessment>,
    unresolvedBindings: Collection<PoseCriterionUnresolvedBindingProfile>,
) {
    val templateAssessments: List<PoseCriterionRuntimeTemplateAssessment> =
        Collections.unmodifiableList(ArrayList(templateAssessments))
    val unresolvedBindings: List<PoseCriterionUnresolvedBindingProfile> =
        Collections.unmodifiableList(ArrayList(unresolvedBindings))
    val observationEvidenceSatisfiedTemplateCount: Int =
        this.templateAssessments.count { it.observationEvidenceSatisfied }
    val unknownTemplateCount: Int = this.templateAssessments.size
    val measurementEnabled: Boolean = false
    val verdictEnabled: Boolean = false
    val scoreEnabled: Boolean = false
    val cueEnabled: Boolean = false
    val shadowEnabled: Boolean = false
    val releaseEnabled: Boolean = false

    init {
        require(this.templateAssessments.all { it.profile.exercise == exercise })
        require(this.unresolvedBindings.all { it.exercise == exercise })
        require(this.templateAssessments.all {
            it.decisionState == PoseCriterionRuntimeDecisionState.UNKNOWN
        })
        require((capabilityEvidenceContractSha256 == null) == (capabilityReceiptSha256 == null))
    }
}

/**
 * Catalog-wide, O(1)-indexed fail-closed readiness gate.
 *
 * The catalog is expanded once into the exact 203 M10 template identities. Per-frame assessment
 * only joins the selected exercise with an already source/person/view/geometry-bound research
 * receipt. No phase, calibration, Gold, shadow, or release transition exists here.
 */
internal object PoseCriterionRuntimeReadinessCatalog {
    const val ANNOTATION_CONTRACT_SHA256 =
        "5d52c5408187a24e50c0017fb086675aadef8be757aa1091e6abac8ed64a57b7"
    const val SCOPE_RESOLVER_REQUIREMENTS_SHA256 =
        "5700926bb5aa13e38aa118599d4353691f202090792432a7a539473ad1e0074a"

    private const val EXPECTED_EXERCISE_COUNT = 41
    private const val EXPECTED_REVIEWED_BINDING_COUNT = 148
    private const val EXPECTED_UNRESOLVED_BINDING_COUNT = 19
    private const val EXPECTED_TEMPLATE_COUNT = 203
    private const val EXPECTED_PHASE_SCOPE_REQUIREMENT_COUNT = 78
    private const val EXPECTED_SIDE_RESOLVER_REQUIREMENT_COUNT = 13
    private const val EXPECTED_RESOLVER_TEMPLATE_COUNT = 18
    private const val EXPECTED_DIRECT_TEMPLATE_COUNT = 119
    private const val EXPECTED_PROXY_TEMPLATE_COUNT = 62
    private const val EXPECTED_NOT_OBSERVABLE_TEMPLATE_COUNT = 22

    private val templateProfiles: List<PoseCriterionRuntimeTemplateProfile>
    private val unresolvedProfiles: List<PoseCriterionUnresolvedBindingProfile>
    private val templatesByExercise: Map<AiHubExercise, List<PoseCriterionRuntimeTemplateProfile>>
    private val unresolvedByExercise: Map<AiHubExercise, List<PoseCriterionUnresolvedBindingProfile>>

    val exerciseCount: Int
    val reviewedBindingCount: Int
    val unresolvedBindingCount: Int
    val annotationTemplateCount: Int
    val annotationTemplateIdentitySetSha256: String
    val phaseScopeRequirementCount: Int
    val sideResolverRequirementCount: Int
    val resolverRequiredTemplateCount: Int

    init {
        val built = buildProfiles()
        templateProfiles = Collections.unmodifiableList(built.first)
        unresolvedProfiles = Collections.unmodifiableList(built.second)
        templatesByExercise = immutableIndex(templateProfiles) { it.exercise }
        unresolvedByExercise = immutableIndex(unresolvedProfiles) { it.exercise }
        exerciseCount = AiHubCriterionPolicyCatalog.registry.registeredExercises.size
        reviewedBindingCount = templateProfiles.map { it.bindingId }.toSet().size
        unresolvedBindingCount = unresolvedProfiles.size
        annotationTemplateCount = templateProfiles.size
        annotationTemplateIdentitySetSha256 = sha256Hex(
            templateProfiles.joinToString(separator = "\n", postfix = "\n") {
                it.annotationTemplateId
            },
        )
        phaseScopeRequirementCount = templateProfiles
            .map { it.exercise to it.phaseRoleId }
            .toSet()
            .size
        sideResolverRequirementCount = templateProfiles
            .filter { it.roleResolverContractId != null }
            .map { Triple(it.exercise, it.sidePolicyKind, it.roleResolverContractId) }
            .toSet()
            .size
        resolverRequiredTemplateCount = templateProfiles.count {
            it.roleResolverContractId != null
        }

        require(exerciseCount == EXPECTED_EXERCISE_COUNT)
        require(reviewedBindingCount == EXPECTED_REVIEWED_BINDING_COUNT)
        require(unresolvedBindingCount == EXPECTED_UNRESOLVED_BINDING_COUNT)
        require(annotationTemplateCount == EXPECTED_TEMPLATE_COUNT)
        require(
            annotationTemplateIdentitySetSha256 ==
                "e26dabd9fed10049df3909d19561e82bb4b950ffcabe99367b6673141c050f77",
        )
        require(phaseScopeRequirementCount == EXPECTED_PHASE_SCOPE_REQUIREMENT_COUNT)
        require(sideResolverRequirementCount == EXPECTED_SIDE_RESOLVER_REQUIREMENT_COUNT)
        require(resolverRequiredTemplateCount == EXPECTED_RESOLVER_TEMPLATE_COUNT)
        require(templateProfiles.map { it.annotationTemplateId }.toSet().size ==
            EXPECTED_TEMPLATE_COUNT)
        require(templateProfiles.count { it.observability == AiHubCriterionObservability.DIRECT } ==
            EXPECTED_DIRECT_TEMPLATE_COUNT)
        require(templateProfiles.count {
            it.observability == AiHubCriterionObservability.PROXY_UNVALIDATED
        } == EXPECTED_PROXY_TEMPLATE_COUNT)
        require(templateProfiles.count {
            it.observability == AiHubCriterionObservability.NOT_OBSERVABLE
        } == EXPECTED_NOT_OBSERVABLE_TEMPLATE_COUNT)
        require(templateProfiles.all {
            it.calibrationProvenanceState ==
                AiHubCriterionCalibrationProvenanceState.NO_APPROVED_ARTIFACT
        })
    }

    fun assess(
        exercise: AiHubExercise,
        currentObservation: AttestedPoseObservation?,
        capabilityReceipt: PoseObservationResearchCapabilityReceipt?,
    ): PoseCriterionRuntimeReadinessAssessment {
        val canonicalReceipt = currentObservation != null &&
            capabilityReceipt?.hasCanonicalProvenance(currentObservation) == true
        val capabilityIds = if (canonicalReceipt) {
            capabilityReceipt.capabilityIds.toSet()
        } else {
            emptySet()
        }
        val qualifiedViews = if (canonicalReceipt) {
            setOf(capabilityReceipt.lateralViewContractId)
        } else {
            emptySet()
        }
        val assessments = templatesByExercise[exercise].orEmpty().map { profile ->
            assessTemplate(profile, capabilityIds, qualifiedViews)
        }
        return PoseCriterionRuntimeReadinessAssessment(
            exercise = exercise,
            policySha256 = AiHubCriterionPolicyCatalog.POLICY_SHA256,
            registrySha256 = AiHubCriterionPolicyCatalog.REGISTRY_SHA256,
            annotationContractSha256 = ANNOTATION_CONTRACT_SHA256,
            scopeResolverRequirementsSha256 = SCOPE_RESOLVER_REQUIREMENTS_SHA256,
            capabilityEvidenceContractSha256 = capabilityReceipt
                ?.evidenceContractSha256
                ?.takeIf { canonicalReceipt },
            capabilityReceiptSha256 = capabilityReceipt
                ?.receiptSha256
                ?.takeIf { canonicalReceipt },
            templateAssessments = assessments,
            unresolvedBindings = unresolvedByExercise[exercise].orEmpty(),
        )
    }

    internal fun templateProfilesForTest(): List<PoseCriterionRuntimeTemplateProfile> =
        templateProfiles

    internal fun unresolvedProfilesForTest(): List<PoseCriterionUnresolvedBindingProfile> =
        unresolvedProfiles

    private fun assessTemplate(
        profile: PoseCriterionRuntimeTemplateProfile,
        capabilityIds: Set<String>,
        qualifiedViewIds: Set<String>,
    ): PoseCriterionRuntimeTemplateAssessment {
        val capabilitySatisfied = profile.requiredCapabilityIds.all(capabilityIds::contains)
        val viewSatisfied = profile.requiredViewContractIds.any(qualifiedViewIds::contains)
        val blockers = linkedSetOf(
            PoseCriterionRuntimeReadinessBlocker.PHASE_SCOPE_CONTRACT_UNAVAILABLE,
            PoseCriterionRuntimeReadinessBlocker.MEASUREMENT_CONSTRUCT_PROVIDER_UNAVAILABLE,
            PoseCriterionRuntimeReadinessBlocker.CALIBRATION_ARTIFACT_UNAVAILABLE,
            PoseCriterionRuntimeReadinessBlocker.REFERENCE_EVIDENCE_UNAVAILABLE,
            PoseCriterionRuntimeReadinessBlocker.TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE,
            PoseCriterionRuntimeReadinessBlocker.SHADOW_AUTHORIZATION_UNAVAILABLE,
            PoseCriterionRuntimeReadinessBlocker.RELEASE_AUTHORIZATION_UNAVAILABLE,
        )
        if (!capabilitySatisfied) {
            blockers += PoseCriterionRuntimeReadinessBlocker.REQUIRED_CAPABILITY_EVIDENCE_UNAVAILABLE
        }
        if (profile.requiredViewContractIds.isEmpty()) {
            blockers += PoseCriterionRuntimeReadinessBlocker.CAMERA_VIEW_NOT_SUFFICIENT
        } else if (!viewSatisfied) {
            blockers += PoseCriterionRuntimeReadinessBlocker.QUALIFIED_VIEW_EVIDENCE_UNAVAILABLE
        }
        if (profile.sidePolicyKind.requiresRoleResolver()) {
            blockers += PoseCriterionRuntimeReadinessBlocker.SIDE_ROLE_RESOLVER_UNAVAILABLE
        }
        if (profile.observability == AiHubCriterionObservability.PROXY_UNVALIDATED) {
            blockers += PoseCriterionRuntimeReadinessBlocker.PROXY_GOLD_VALIDATION_UNAVAILABLE
        }
        return PoseCriterionRuntimeTemplateAssessment(
            profile = profile,
            capabilityEvidenceSatisfied = capabilitySatisfied,
            qualifiedViewEvidenceSatisfied = viewSatisfied,
            blockers = blockers,
        )
    }

    private fun buildProfiles(): Pair<
        List<PoseCriterionRuntimeTemplateProfile>,
        List<PoseCriterionUnresolvedBindingProfile>,
    > {
        val templates = ArrayList<PoseCriterionRuntimeTemplateProfile>()
        val unresolved = ArrayList<PoseCriterionUnresolvedBindingProfile>()
        AiHubCriterionPolicyCatalog.registry.bindings.forEach { binding ->
            val interpretation = binding.interpretation
            if (interpretation == null) {
                unresolved += PoseCriterionUnresolvedBindingProfile(
                    bindingId = binding.bindingId,
                    bindingPolicySha256 = binding.bindingPolicySha256,
                    exercise = binding.exercise,
                    sourceConditionId = binding.sourceConditionId,
                    reviewState = binding.reviewState,
                    reasonCodes = binding.reasonCodes,
                )
                return@forEach
            }
            interpretation.phaseApplicability.phaseRoleIds.forEach { phaseRoleId ->
                interpretation.sidePolicy.kind.symbolicSlots().forEach { slot ->
                    templates += PoseCriterionRuntimeTemplateProfile(
                        annotationTemplateId = annotationTemplateId(
                            bindingId = binding.bindingId,
                            bindingPolicySha256 = binding.bindingPolicySha256,
                            exerciseId = binding.exercise.id,
                            sourceConditionId = binding.sourceConditionId,
                            phaseRoleId = phaseRoleId,
                            sidePolicyKind = interpretation.sidePolicy.kind,
                            symbolicSideSlot = slot,
                            roleResolverContractId =
                                interpretation.sidePolicy.roleResolverContractId,
                        ),
                        bindingId = binding.bindingId,
                        bindingPolicySha256 = binding.bindingPolicySha256,
                        exercise = binding.exercise,
                        sourceConditionId = binding.sourceConditionId,
                        phaseRoleId = phaseRoleId,
                        sidePolicyKind = interpretation.sidePolicy.kind,
                        symbolicSideSlot = slot,
                        roleResolverContractId = interpretation.sidePolicy.roleResolverContractId,
                        measurementConstructId = interpretation.measurementConstructId,
                        observability = interpretation.observability,
                        requiredViewContractIds =
                            interpretation.viewApplicability.viewContractIds,
                        requiredCapabilityIds = interpretation.requiredCapabilityIds,
                        calibrationProvenanceState =
                            interpretation.calibrationProvenance.state,
                    )
                }
            }
        }
        templates.sortBy(PoseCriterionRuntimeTemplateProfile::annotationTemplateId)
        unresolved.sortBy(PoseCriterionUnresolvedBindingProfile::bindingId)
        return templates to unresolved
    }

    private fun annotationTemplateId(
        bindingId: String,
        bindingPolicySha256: String,
        exerciseId: String,
        sourceConditionId: String,
        phaseRoleId: String,
        sidePolicyKind: AiHubCriterionSidePolicyKind,
        symbolicSideSlot: PoseCriterionSymbolicSideSlot,
        roleResolverContractId: String?,
    ): String {
        val resolverJson = roleResolverContractId?.let { "\"$it\"" } ?: "null"
        val canonicalJson = buildString {
            append("{\"bindingKey\":{")
            append("\"bindingId\":\"").append(bindingId).append("\",")
            append("\"bindingPolicySha256\":\"").append(bindingPolicySha256).append("\",")
            append("\"exerciseId\":\"").append(exerciseId).append("\",")
            append("\"policyRegistrySha256\":\"")
                .append(AiHubCriterionPolicyCatalog.REGISTRY_SHA256).append("\",")
            append("\"sourceConditionId\":\"").append(sourceConditionId).append("\"},")
            append("\"phaseRoleId\":\"").append(phaseRoleId).append("\",")
            append("\"roleResolverContractId\":").append(resolverJson).append(',')
            append("\"sidePolicyKind\":\"").append(sidePolicyKind.name).append("\",")
            append("\"symbolicSlot\":\"").append(symbolicSideSlot.name).append("\",")
            append("\"templateIdentitySchemaVersion\":1}")
        }
        return "trex.annotation-template-sha256-${sha256Hex(canonicalJson)}"
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun <T> immutableIndex(
        values: List<T>,
        key: (T) -> AiHubExercise,
    ): Map<AiHubExercise, List<T>> = Collections.unmodifiableMap(
        values.groupBy(key).mapValuesTo(LinkedHashMap()) { (_, group) ->
            Collections.unmodifiableList(ArrayList(group))
        },
    )
}

private fun AiHubCriterionSidePolicyKind.requiresRoleResolver(): Boolean = when (this) {
    AiHubCriterionSidePolicyKind.ACTIVE_LIMB,
    AiHubCriterionSidePolicyKind.LEAD_LIMB,
    AiHubCriterionSidePolicyKind.TRAIL_LIMB,
    AiHubCriterionSidePolicyKind.ALTERNATING_PAIR,
    AiHubCriterionSidePolicyKind.CONTRALATERAL_PAIR,
    -> true
    AiHubCriterionSidePolicyKind.MIDLINE,
    AiHubCriterionSidePolicyKind.GLOBAL_BODY,
    AiHubCriterionSidePolicyKind.BILATERAL_COUPLED,
    AiHubCriterionSidePolicyKind.BILATERAL_INDEPENDENT,
    AiHubCriterionSidePolicyKind.NOT_APPLICABLE,
    -> false
}

private fun AiHubCriterionSidePolicyKind.symbolicSlots(): List<PoseCriterionSymbolicSideSlot> =
    when (this) {
        AiHubCriterionSidePolicyKind.MIDLINE ->
            listOf(PoseCriterionSymbolicSideSlot.MIDLINE)
        AiHubCriterionSidePolicyKind.GLOBAL_BODY ->
            listOf(PoseCriterionSymbolicSideSlot.GLOBAL_BODY)
        AiHubCriterionSidePolicyKind.BILATERAL_COUPLED ->
            listOf(PoseCriterionSymbolicSideSlot.BILATERAL_PAIR)
        AiHubCriterionSidePolicyKind.BILATERAL_INDEPENDENT ->
            listOf(PoseCriterionSymbolicSideSlot.LEFT, PoseCriterionSymbolicSideSlot.RIGHT)
        AiHubCriterionSidePolicyKind.ACTIVE_LIMB ->
            listOf(PoseCriterionSymbolicSideSlot.ACTIVE_LIMB)
        AiHubCriterionSidePolicyKind.LEAD_LIMB ->
            listOf(PoseCriterionSymbolicSideSlot.LEAD_LIMB)
        AiHubCriterionSidePolicyKind.TRAIL_LIMB ->
            listOf(PoseCriterionSymbolicSideSlot.TRAIL_LIMB)
        AiHubCriterionSidePolicyKind.ALTERNATING_PAIR ->
            listOf(PoseCriterionSymbolicSideSlot.ALTERNATING_PAIR)
        AiHubCriterionSidePolicyKind.CONTRALATERAL_PAIR ->
            listOf(PoseCriterionSymbolicSideSlot.CONTRALATERAL_PAIR)
        AiHubCriterionSidePolicyKind.NOT_APPLICABLE -> error(
            "Reviewed bindings cannot use NOT_APPLICABLE side policy",
        )
    }
