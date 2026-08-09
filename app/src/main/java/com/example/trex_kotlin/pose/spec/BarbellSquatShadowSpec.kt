package com.example.trex_kotlin.pose.spec

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyCatalog

/**
 * Fail-closed AI Hub linkage for prospective barbell-squat shadow research.
 *
 * The repository content pin detects review drift only. It is not a signature, authenticity
 * proof, Gold validation, calibration approval, runtime authorization, or product release. Every
 * current binding is deliberately [AiHubShadowBindingPlan.Unavailable], so this object installs no
 * feature extractor, measurement plan, evaluator, score, verdict, cue, or user session.
 */
internal object BarbellSquatShadowSpec {
    private const val REPOSITORY_DRIFT_PIN_SHA256 =
        "0720f954a434f0a41a92174d21c9823fb6e153699b0bc6d8cde95134c2d989b9"

    val manifest: AiHubShadowExerciseSpec by lazy {
        AiHubShadowExerciseSpec(
            exercise = AiHubExercise.BARBELL_SQUAT,
            policySnapshot = AiHubShadowPolicySnapshot.CURRENT,
            // Deliberately follows source truth-vector order, not generated policy order. The
            // manifest joins and canonicalizes by exact identifiers, making source list position
            // non-semantic.
            plans = listOf(
                unavailableProxy(
                    pin = SPINE_NEUTRAL,
                    missingCapabilityIds = setOf(ANATOMICAL_SEGMENT_FRAME_CAPABILITY_ID),
                    blockingUnavailableViewContractIds = emptySet(),
                ),
                unavailableProxy(
                    pin = HEAD_FORWARD,
                    missingCapabilityIds = setOf(FACE_ORIENTATION_CAPABILITY_ID),
                    blockingUnavailableViewContractIds = setOf(FRONT_VIEW_CONTRACT_ID),
                ),
                unavailableProxy(
                    pin = KNEE_FOOT_DIRECTION,
                    missingCapabilityIds = setOf(ANATOMICAL_SEGMENT_FRAME_CAPABILITY_ID),
                    blockingUnavailableViewContractIds = setOf(
                        FRONT_VIEW_CONTRACT_ID,
                        FRONT_OBLIQUE_VIEW_CONTRACT_ID,
                    ),
                ),
                unavailablePlantarContact(),
            ),
            repositoryDriftPinSha256 = REPOSITORY_DRIFT_PIN_SHA256,
        )
    }

    private fun unavailableProxy(
        pin: ExactBindingPin,
        missingCapabilityIds: Set<String>,
        blockingUnavailableViewContractIds: Set<String>,
    ): AiHubShadowBindingPlan.Unavailable {
        val provenance = exactProvenance(pin)
        return AiHubShadowBindingPlan.Unavailable(
            provenance = provenance,
            expectedWindowScope = CriterionWindowScope.CompletedCycle,
            reasons = buildSet {
                add(ShadowBindingUnavailableReason.NO_RUNTIME_RELEASE_AUTHORIZATION)
                add(ShadowBindingUnavailableReason.NO_APPROVED_CALIBRATION_ARTIFACT)
                add(ShadowBindingUnavailableReason.NO_BLIND_GOLD_VALIDATION)
                add(ShadowBindingUnavailableReason.REQUIRED_PHASE_ROLE_PROVIDER_UNAVAILABLE)
                add(ShadowBindingUnavailableReason.REQUIRED_CAPABILITY_PROVIDER_UNAVAILABLE)
                if (blockingUnavailableViewContractIds.isNotEmpty()) {
                    add(ShadowBindingUnavailableReason.REQUIRED_VIEW_PROVIDER_UNAVAILABLE)
                }
            },
            missingCapabilityIds = missingCapabilityIds,
            unavailableViewContractIds = blockingUnavailableViewContractIds,
            unresolvedPhaseRoleIds = setOf(FULL_CYCLE_PHASE_ROLE_ID),
        )
    }

    private fun unavailablePlantarContact(): AiHubShadowBindingPlan.Unavailable =
        AiHubShadowBindingPlan.Unavailable(
            provenance = exactProvenance(PLANTAR_CONTACT),
            expectedWindowScope = CriterionWindowScope.CompletedCycle,
            reasons = setOf(
                ShadowBindingUnavailableReason.NO_RUNTIME_RELEASE_AUTHORIZATION,
                ShadowBindingUnavailableReason.NO_APPROVED_CALIBRATION_ARTIFACT,
                ShadowBindingUnavailableReason.REQUIRED_PHASE_ROLE_PROVIDER_UNAVAILABLE,
                ShadowBindingUnavailableReason.NOT_OBSERVABLE_FROM_CAMERA_POSE,
            ),
            missingCapabilityIds = setOf(CONTACT_SENSOR_CAPABILITY_ID),
            unresolvedPhaseRoleIds = setOf(FULL_CYCLE_PHASE_ROLE_ID),
        )

    private fun exactProvenance(pin: ExactBindingPin): AiHubShadowBindingProvenance =
        AiHubShadowBindingProvenance.exactCatalogLookup(
            exercise = AiHubExercise.BARBELL_SQUAT,
            sourceConditionId = pin.sourceConditionId,
            bindingId = pin.bindingId,
            bindingPolicySha256 = pin.bindingPolicySha256,
            policyRegistrySha256 = AiHubCriterionPolicyCatalog.REGISTRY_SHA256,
        )

    private data class ExactBindingPin(
        val sourceConditionId: String,
        val bindingId: String,
        val bindingPolicySha256: String,
    )

    private val KNEE_FOOT_DIRECTION = ExactBindingPin(
        sourceConditionId =
            "aihub-exact-sha256-48ecac06f2184af84c3a7f6885ecdbd53fb1a025887dde6fe52686a878862bc6",
        bindingId =
            "aihub-binding-sha256-4d64a50373e5da088b53e2f71324aad49d6311eb793867ce89588d13a6b98d84",
        bindingPolicySha256 =
            "54382de2a077b889d08cba249c381313027802fa4f3096984174ae66eca687ea",
    )
    private val SPINE_NEUTRAL = ExactBindingPin(
        sourceConditionId =
            "aihub-exact-sha256-6a879c85bade13509383fa19b50e1e80dcc2b6aa9b29d9c38ce7e92d7ef1aa65",
        bindingId =
            "aihub-binding-sha256-94b2a01fea997c10f92d021467a34ccd64dd1bf1f3e6dcaf30ea3b612af9a54d",
        bindingPolicySha256 =
            "34287da867e041e10e4b9a14de6980f5e4f0c8c0174a895bce80713f03e71b14",
    )
    private val HEAD_FORWARD = ExactBindingPin(
        sourceConditionId =
            "aihub-exact-sha256-6fae2ac689be78ee72206bde87af8021dddcc5e15a5c55ab7b9144c0e22da181",
        bindingId =
            "aihub-binding-sha256-9ddf6abe72e68295d2a684f59f2d6721bcd663722716b66c83aede1cdaf51d4e",
        bindingPolicySha256 =
            "784f776e5733c8a02cd3062871ba411ccea5c5a82d84124d39d9078eb6b822a7",
    )
    private val PLANTAR_CONTACT = ExactBindingPin(
        sourceConditionId =
            "aihub-exact-sha256-80e11956abe253a707f2ff0fcee11fa775f41eba64a2c314472dd189ac503e2d",
        bindingId =
            "aihub-binding-sha256-e285f11c7ac5ebc41f79058eb47d378c37f66f356696c0473890809bcf68147d",
        bindingPolicySha256 =
            "844ef872132b7cac2fec8e31ad16b41eed3224285fa090d2ff221569cd89a3d8",
    )

    private const val ANATOMICAL_SEGMENT_FRAME_CAPABILITY_ID =
        "trex.capability.anatomical-segment-frame.v1"
    private const val FACE_ORIENTATION_CAPABILITY_ID =
        "trex.capability.face-orientation.v1"
    private const val CONTACT_SENSOR_CAPABILITY_ID = "trex.capability.contact-sensor.v1"
    private const val FULL_CYCLE_PHASE_ROLE_ID = "trex.phase-role.full-cycle.v1"
    private const val FRONT_VIEW_CONTRACT_ID = "trex.view.front-full-body.v1"
    private const val FRONT_OBLIQUE_VIEW_CONTRACT_ID =
        "trex.view.front-oblique-full-body.v1"
}
