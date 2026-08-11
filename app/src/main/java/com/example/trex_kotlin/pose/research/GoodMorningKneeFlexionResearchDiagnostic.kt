package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseSide
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.feature.FeatureMeasurement
import com.example.trex_kotlin.pose.feature.FeatureUnknownReason
import com.example.trex_kotlin.pose.feature.PoseFeatureEngine
import com.example.trex_kotlin.pose.feature.PoseFeaturePrimitiveContract
import com.example.trex_kotlin.pose.feature.PoseScalarFeatureSpec
import com.example.trex_kotlin.pose.feature.measure
import com.example.trex_kotlin.pose.policy.AiHubCriterionCalibrationProvenanceState
import com.example.trex_kotlin.pose.policy.AiHubCriterionObservability
import com.example.trex_kotlin.pose.policy.AiHubCriterionPhaseApplicabilityState
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyBinding
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyCatalog
import com.example.trex_kotlin.pose.policy.AiHubCriterionReleaseState
import com.example.trex_kotlin.pose.policy.AiHubCriterionReviewState
import com.example.trex_kotlin.pose.policy.AiHubCriterionSidePolicyKind
import com.example.trex_kotlin.pose.policy.AiHubCriterionViewApplicabilityState
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import java.util.Collections
import java.util.EnumMap

internal enum class GoodMorningKneeFlexionResearchState {
    UNKNOWN,
}

internal enum class GoodMorningKneeFlexionResearchBlocker {
    CAPABILITY_RECEIPT_UNAVAILABLE,
    PHASE_SCOPE_UNAVAILABLE,
    CALIBRATION_ARTIFACT_UNAVAILABLE,
    REFERENCE_EVIDENCE_UNAVAILABLE,
    TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE,
    SHADOW_AUTHORIZATION_UNAVAILABLE,
    RELEASE_AUTHORIZATION_UNAVAILABLE,
}

/** Frame-local availability only; it is not a criterion decision. */
internal class GoodMorningKneeFlexionSideDiagnostic internal constructor(
    val side: PoseSide,
    val includedAngleDegrees: Double?,
    val rawConfidence: Double,
    val featureUnknownReason: FeatureUnknownReason?,
) {
    val flexionDegrees: Double? = includedAngleDegrees?.let { 180.0 - it }
    val isFrameLocalValueAvailable: Boolean
        get() = includedAngleDegrees != null

    init {
        require(rawConfidence.isFinite() && rawConfidence in 0.0..1.0)
        require(includedAngleDegrees == null || includedAngleDegrees in 0.0..180.0)
        require(flexionDegrees == null || flexionDegrees in 0.0..180.0)
        require((includedAngleDegrees == null) || featureUnknownReason == null)
    }
}

/** Zero authority is the only constructible research authority state. */
internal class GoodMorningKneeFlexionResearchAuthority private constructor(
    val measurementAuthority: Int,
    val shadowAuthority: Int,
    val verdictAuthority: Int,
    val scoreAuthority: Int,
    val cueAuthority: Int,
    val releaseAuthority: Int,
) {
    val totalAuthority: Int =
        measurementAuthority + shadowAuthority + verdictAuthority + scoreAuthority +
            cueAuthority + releaseAuthority

    init {
        require(measurementAuthority == 0)
        require(shadowAuthority == 0)
        require(verdictAuthority == 0)
        require(scoreAuthority == 0)
        require(cueAuthority == 0)
        require(releaseAuthority == 0)
    }

    companion object {
        val NONE = GoodMorningKneeFlexionResearchAuthority(0, 0, 0, 0, 0, 0)
    }
}

/** Immutable research output. No observation, frame, landmark, or opaque runtime token is kept. */
internal class GoodMorningKneeFlexionResearchOutput internal constructor(
    val observationTimestampMs: Long?,
    val capabilityReceiptSha256: String?,
    val featureRuntimeContractSha256: String,
    val featurePrimitiveContractSha256: String,
    val flexionFormulaId: String,
    val researchUseId: String,
    val diagnosticContractSha256: String,
    val coordinateSpace: PoseCoordinateSpace,
    val leftFeatureSpecSha256: String,
    val rightFeatureSpecSha256: String,
    sideDiagnostics: Map<PoseSide, GoodMorningKneeFlexionSideDiagnostic>,
    blockers: Set<GoodMorningKneeFlexionResearchBlocker>,
) {
    val state: GoodMorningKneeFlexionResearchState =
        GoodMorningKneeFlexionResearchState.UNKNOWN
    val authority: GoodMorningKneeFlexionResearchAuthority =
        GoodMorningKneeFlexionResearchAuthority.NONE
    val sideDiagnostics: Map<PoseSide, GoodMorningKneeFlexionSideDiagnostic> =
        Collections.unmodifiableMap(EnumMap(sideDiagnostics))
    val blockers: Set<GoodMorningKneeFlexionResearchBlocker> =
        Collections.unmodifiableSet(LinkedHashSet(blockers.sortedBy { it.name }))
    val diagnosticProvenanceSha256: String = canonicalFieldsSha256(
        buildList {
            add("goodMorningKneeFlexionResearchProvenanceSchemaVersion" to "1")
            add("diagnosticContractSha256" to diagnosticContractSha256)
            add("observationTimestampMs" to (observationTimestampMs?.toString() ?: ""))
            add("capabilityReceiptSha256" to (capabilityReceiptSha256 ?: ""))
            add("coordinateSpace" to coordinateSpace.name)
            add("state" to state.name)
            PoseSide.entries.forEach { side ->
                val sample = this@GoodMorningKneeFlexionResearchOutput
                    .sideDiagnostics.getValue(side)
                add("${side.name}.includedAngleDegrees" to
                    (sample.includedAngleDegrees?.let(java.lang.Double::toHexString) ?: ""))
                add("${side.name}.flexionDegrees" to
                    (sample.flexionDegrees?.let(java.lang.Double::toHexString) ?: ""))
                add("${side.name}.rawConfidence" to
                    java.lang.Double.toHexString(sample.rawConfidence))
                add("${side.name}.featureUnknownReason" to
                    (sample.featureUnknownReason?.name ?: ""))
            }
            add("blockerCount" to this@GoodMorningKneeFlexionResearchOutput.blockers.size.toString())
            this@GoodMorningKneeFlexionResearchOutput.blockers.forEachIndexed { index, blocker ->
                add("blocker[$index]" to blocker.name)
            }
            add("totalAuthority" to authority.totalAuthority.toString())
        },
    )

    init {
        require(observationTimestampMs == null || observationTimestampMs >= 0L)
        require((observationTimestampMs == null) == (capabilityReceiptSha256 == null))
        require(coordinateSpace == PoseCoordinateSpace.WORLD)
        require(featurePrimitiveContractSha256 == PoseFeaturePrimitiveContract.sha256)
        require(flexionFormulaId == GoodMorningKneeFlexionResearchDiagnostic.FLEXION_FORMULA_ID)
        require(researchUseId == GoodMorningKneeFlexionResearchDiagnostic.RESEARCH_USE_ID)
        require(this.sideDiagnostics.keys == PoseSide.entries.toSet())
        require(ALWAYS_BLOCKERS.all(this.blockers::contains))
        require(authority.totalAuthority == 0)
    }

    private companion object {
        val ALWAYS_BLOCKERS = setOf(
            GoodMorningKneeFlexionResearchBlocker.PHASE_SCOPE_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.CALIBRATION_ARTIFACT_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.REFERENCE_EVIDENCE_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.SHADOW_AUTHORIZATION_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.RELEASE_AUTHORIZATION_UNAVAILABLE,
        )
    }
}

/**
 * One-frame, research-only Good Morning knee diagnostic.
 *
 * A complete 33+33 capability frame is mandatory. Consequently a missing landmark key blocks the
 * whole receipt. Left/right independence applies after that gate: low-confidence or degenerate
 * geometry on one side never discards an available value on the other side.
 */
internal class GoodMorningKneeFlexionResearchDiagnostic(
    private val expectedSource: PoseObservationSource,
    minimumConfidence: Double = 0.6,
) {
    private val featureEngine = PoseFeatureEngine(minimumConfidence)
    private val diagnosticContractSha256: String = canonicalFieldsSha256(
        listOf(
            "goodMorningKneeFlexionResearchDiagnosticSchemaVersion" to "1",
            "bindingId" to BINDING_ID,
            "sourceConditionId" to SOURCE_CONDITION_ID,
            "bindingPolicySha256" to BINDING_POLICY_SHA256,
            "measurementConstructId" to MEASUREMENT_CONSTRUCT_ID,
            "phaseRoleId" to PHASE_ROLE_ID,
            "sidePolicyKind" to AiHubCriterionSidePolicyKind.BILATERAL_INDEPENDENT.name,
            "researchUseId" to RESEARCH_USE_ID,
            "flexionFormulaId" to FLEXION_FORMULA_ID,
            "generatedPolicySha256" to AiHubCriterionPolicyCatalog.POLICY_SHA256,
            "generatedRegistrySha256" to AiHubCriterionPolicyCatalog.REGISTRY_SHA256,
            "observationContractSha256" to expectedSource.contract.artifactSha256,
            "runtimeDomainId" to expectedSource.contract.runtimeDomainId,
            "modelArtifactSha256" to expectedSource.contract.modelArtifactSha256,
            "inferenceOptionsArtifactSha256" to
                expectedSource.contract.inferenceOptionsArtifactSha256,
            "preprocessingArtifactSha256" to
                expectedSource.contract.preprocessingArtifactSha256,
            "landmarkSchemaArtifactSha256" to
                expectedSource.contract.landmarkSchemaArtifactSha256,
            "personLockArtifactSha256" to expectedSource.contract.personLockArtifactSha256,
            "viewQualifierArtifactSha256" to
                expectedSource.contract.viewQualifierArtifactSha256,
            "viewContractId" to PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
            "coordinateSpace" to PoseCoordinateSpace.WORLD.name,
            "featurePrimitiveContractSha256" to PoseFeaturePrimitiveContract.sha256,
            "leftFeatureSpecSha256" to LEFT_KNEE_ANGLE_SPEC.featureSpecSha256,
            "rightFeatureSpecSha256" to RIGHT_KNEE_ANGLE_SPEC.featureSpecSha256,
            "featureRuntimeContractSha256" to featureEngine.runtimeContractSha256,
            "minimumConfidence" to java.lang.Double.toHexString(featureEngine.minimumConfidence),
            "resultState" to GoodMorningKneeFlexionResearchState.UNKNOWN.name,
            "decisionAuthority" to "0",
        ),
    )

    init {
        EXACT_POLICY_BINDING
        require(
            PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID in
                expectedSource.contract.allowedViewContractIds,
        )
        require(
            expectedSource.contract.supportedCoordinateSpaces.containsAll(
                setOf(PoseCoordinateSpace.NORMALIZED_IMAGE, PoseCoordinateSpace.WORLD),
            ),
        )
    }

    fun accept(
        currentObservation: AttestedPoseObservation,
        capabilityReceipt: PoseObservationResearchCapabilityReceipt?,
    ): GoodMorningKneeFlexionResearchOutput {
        val capabilityReady = currentObservation.isFrom(expectedSource) &&
            capabilityReceipt?.hasCanonicalProvenance(currentObservation) == true
        val sideDiagnostics = if (capabilityReady) {
            mapOf(
                PoseSide.LEFT to sideDiagnostic(
                    PoseSide.LEFT,
                    featureEngine.measure(currentObservation.frame, LEFT_KNEE_ANGLE_SPEC),
                ),
                PoseSide.RIGHT to sideDiagnostic(
                    PoseSide.RIGHT,
                    featureEngine.measure(currentObservation.frame, RIGHT_KNEE_ANGLE_SPEC),
                ),
            )
        } else {
            PoseSide.entries.associateWith { side -> unavailableSide(side) }
        }
        val blockers = LinkedHashSet(ALWAYS_BLOCKERS)
        if (!capabilityReady) {
            blockers += GoodMorningKneeFlexionResearchBlocker.CAPABILITY_RECEIPT_UNAVAILABLE
        }
        return GoodMorningKneeFlexionResearchOutput(
            observationTimestampMs = currentObservation.frame.timestampMs
                .takeIf { capabilityReady },
            capabilityReceiptSha256 = capabilityReceipt
                ?.receiptSha256
                ?.takeIf { capabilityReady },
            featureRuntimeContractSha256 = featureEngine.runtimeContractSha256,
            featurePrimitiveContractSha256 = PoseFeaturePrimitiveContract.sha256,
            flexionFormulaId = FLEXION_FORMULA_ID,
            researchUseId = RESEARCH_USE_ID,
            diagnosticContractSha256 = diagnosticContractSha256,
            coordinateSpace = PoseCoordinateSpace.WORLD,
            leftFeatureSpecSha256 = LEFT_KNEE_ANGLE_SPEC.featureSpecSha256,
            rightFeatureSpecSha256 = RIGHT_KNEE_ANGLE_SPEC.featureSpecSha256,
            sideDiagnostics = sideDiagnostics,
            blockers = blockers,
        )
    }

    private fun sideDiagnostic(
        side: PoseSide,
        measurement: FeatureMeasurement,
    ) = GoodMorningKneeFlexionSideDiagnostic(
        side = side,
        includedAngleDegrees = measurement.value,
        rawConfidence = measurement.rawConfidence,
        featureUnknownReason = measurement.unknownReason,
    )

    private fun unavailableSide(side: PoseSide) = GoodMorningKneeFlexionSideDiagnostic(
        side = side,
        includedAngleDegrees = null,
        rawConfidence = 0.0,
        featureUnknownReason = null,
    )

    internal companion object {
        const val BINDING_ID =
            "aihub-binding-sha256-f900f3dc681053ed9b705e020bac0ed27336aa5776885406a3c07a6db67d453d"
        const val SOURCE_CONDITION_ID =
            "aihub-exact-sha256-621f2eb88568c0d247abce9bbdbc763e8e40ae396bd0ba254a77dcd8bbc0394d"
        const val BINDING_POLICY_SHA256 =
            "05125e36ac4ebc448120f9d3cc29cbc8837585cde36bc600231a4f30935080e0"
        const val MEASUREMENT_CONSTRUCT_ID =
            "trex.measurement.knee-flexion-angle-stability.v1"
        const val PHASE_ROLE_ID = "trex.phase-role.full-cycle.v1"
        const val FLEXION_FORMULA_ID =
            "trex.formula.knee-flexion.180-minus-included-angle-degrees.v1"
        const val RESEARCH_USE_ID =
            "trex.research-use.frame-local-no-stability-no-decision.v1"

        private val ALWAYS_BLOCKERS = setOf(
            GoodMorningKneeFlexionResearchBlocker.PHASE_SCOPE_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.CALIBRATION_ARTIFACT_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.REFERENCE_EVIDENCE_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.SHADOW_AUTHORIZATION_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.RELEASE_AUTHORIZATION_UNAVAILABLE,
        )
        private val LEFT_KNEE_ANGLE_SPEC = PoseScalarFeatureSpec.JointAngle(
            featureContractId =
                "trex.research-feature.good-morning.left-knee-included-angle.world.v1",
            coordinateSpace = PoseCoordinateSpace.WORLD,
            first = PoseJoint.LEFT_HIP,
            vertex = PoseJoint.LEFT_KNEE,
            third = PoseJoint.LEFT_ANKLE,
        )
        private val RIGHT_KNEE_ANGLE_SPEC = PoseScalarFeatureSpec.JointAngle(
            featureContractId =
                "trex.research-feature.good-morning.right-knee-included-angle.world.v1",
            coordinateSpace = PoseCoordinateSpace.WORLD,
            first = PoseJoint.RIGHT_HIP,
            vertex = PoseJoint.RIGHT_KNEE,
            third = PoseJoint.RIGHT_ANKLE,
        )
        private val EXACT_POLICY_BINDING: AiHubCriterionPolicyBinding = exactPolicyBinding()

        private fun exactPolicyBinding(): AiHubCriterionPolicyBinding {
            val binding = checkNotNull(
                AiHubCriterionPolicyCatalog.binding(
                    AiHubExercise.GOOD_MORNING,
                    SOURCE_CONDITION_ID,
                ),
            )
            check(binding.bindingId == BINDING_ID)
            check(binding.exercise == AiHubExercise.GOOD_MORNING)
            check(binding.sourceConditionId == SOURCE_CONDITION_ID)
            check(binding.bindingPolicySha256 == BINDING_POLICY_SHA256)
            check(binding.reviewState == AiHubCriterionReviewState.REVIEWED_ENGINEERING_V1)
            check(binding.releaseState == AiHubCriterionReleaseState.CATALOG_ONLY)
            val interpretation = checkNotNull(binding.interpretation)
            check(interpretation.semanticId ==
                "aihub.condition.exact.621f2eb88568c0d247abce9bbdbc763e8e40ae396bd0ba254a77dcd8bbc0394d.v1")
            check(interpretation.semanticFamilyId ==
                "trex.semantic-family.lower-limb-geometry.v1")
            check(interpretation.measurementConstructId == MEASUREMENT_CONSTRUCT_ID)
            check(interpretation.observability == AiHubCriterionObservability.DIRECT)
            check(
                interpretation.phaseApplicability.state ==
                    AiHubCriterionPhaseApplicabilityState.BOUND,
            )
            check(interpretation.phaseApplicability.phaseRoleIds == listOf(PHASE_ROLE_ID))
            check(
                interpretation.sidePolicy.kind ==
                    AiHubCriterionSidePolicyKind.BILATERAL_INDEPENDENT,
            )
            check(interpretation.sidePolicy.roleResolverContractId == null)
            check(
                interpretation.viewApplicability.state ==
                    AiHubCriterionViewApplicabilityState.QUALIFIED_VIEW_REQUIRED,
            )
            check(
                interpretation.viewApplicability.viewContractIds ==
                    listOf(PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID),
            )
            check(
                interpretation.requiredCapabilityIds ==
                    PoseObservationResearchCapabilities.EXACT_CAPABILITY_IDS,
            )
            check(
                interpretation.calibrationProvenance.state ==
                    AiHubCriterionCalibrationProvenanceState.NO_APPROVED_ARTIFACT,
            )
            check(interpretation.calibrationProvenance.artifactSha256 == null)
            check(interpretation.calibrationProvenance.runtimeDomainId == null)
            return binding
        }
    }
}
