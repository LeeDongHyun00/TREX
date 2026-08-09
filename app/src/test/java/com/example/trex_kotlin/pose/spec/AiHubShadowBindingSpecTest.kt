package com.example.trex_kotlin.pose.spec

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.phase.PosePhaseStateId
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyCatalog
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiHubShadowBindingSpecTest {
    @Test
    fun exactCatalogLookupRequiresTheCompleteFiveTuple() {
        val provenance = spineProvenance()

        assertEquals(AiHubExercise.BARBELL_SQUAT, provenance.exercise)
        assertEquals(SPINE_SOURCE_ID, provenance.sourceConditionId)
        assertEquals(SPINE_BINDING_ID, provenance.bindingId)
        assertEquals(SPINE_BINDING_POLICY_SHA256, provenance.bindingPolicySha256)
        assertEquals(AiHubCriterionPolicyCatalog.REGISTRY_SHA256, provenance.policyRegistrySha256)

        assertThrows(IllegalArgumentException::class.java) {
            exactLookup(exercise = AiHubExercise.PUSH_UP)
        }
        assertThrows(IllegalArgumentException::class.java) {
            exactLookup(sourceConditionId = KNEE_FOOT_SOURCE_ID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            exactLookup(bindingId = KNEE_FOOT_BINDING_ID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            exactLookup(bindingPolicySha256 = "0".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            exactLookup(policyRegistrySha256 = "0".repeat(64))
        }
    }

    @Test
    fun unavailablePlanBindsCompletedCycleAndExposesNoVerdictApi() {
        val provenance = spineProvenance()
        val plan = AiHubShadowBindingPlan.Unavailable(
            provenance = provenance,
            expectedWindowScope = CriterionWindowScope.CompletedCycle,
            reasons = setOf(
                ShadowBindingUnavailableReason.NO_RUNTIME_RELEASE_AUTHORIZATION,
                ShadowBindingUnavailableReason.NO_APPROVED_CALIBRATION_ARTIFACT,
                ShadowBindingUnavailableReason.NO_BLIND_GOLD_VALIDATION,
                ShadowBindingUnavailableReason.REQUIRED_PHASE_ROLE_PROVIDER_UNAVAILABLE,
                ShadowBindingUnavailableReason.REQUIRED_CAPABILITY_PROVIDER_UNAVAILABLE,
            ),
            missingCapabilityIds = setOf(ANATOMICAL_SEGMENT_FRAME_CAPABILITY_ID),
            unresolvedPhaseRoleIds = setOf(FULL_CYCLE_PHASE_ROLE_ID),
        )

        assertSame(CriterionWindowScope.CompletedCycle, plan.expectedWindowScope)
        assertEquals(
            setOf(ANATOMICAL_SEGMENT_FRAME_CAPABILITY_ID),
            plan.missingCapabilityIds,
        )
        assertEquals(setOf(FULL_CYCLE_PHASE_ROLE_ID), plan.unresolvedPhaseRoleIds)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (plan.reasons as MutableSet<ShadowBindingUnavailableReason>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (plan.missingCapabilityIds as MutableSet<String>).clear()
        }

        assertThrows(IllegalArgumentException::class.java) {
            AiHubShadowBindingPlan.Unavailable(
                provenance = provenance,
                expectedWindowScope = CriterionWindowScope.Phase(PosePhaseStateId("bottom")),
                reasons = setOf(
                    ShadowBindingUnavailableReason.NO_RUNTIME_RELEASE_AUTHORIZATION,
                    ShadowBindingUnavailableReason.NO_APPROVED_CALIBRATION_ARTIFACT,
                    ShadowBindingUnavailableReason.NO_BLIND_GOLD_VALIDATION,
                ),
            )
        }

        val forbiddenTokens = setOf("evaluate", "score", "cue", "pass", "fail", "verdict")
        assertTrue(AiHubShadowBindingPlan.MeasureOnly::class.java.declaredConstructors.all {
            constructor -> Modifier.isPrivate(constructor.modifiers)
        })
        val publicMethodNames = listOf(
            AiHubShadowBindingPlan::class.java,
            AiHubShadowBindingPlan.MeasureOnly::class.java,
            AiHubShadowBindingPlan.Unavailable::class.java,
            AiHubShadowExerciseSpec::class.java,
        ).flatMap { type ->
            type.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
                .map { method -> method.name.lowercase() }
        }
        assertTrue(publicMethodNames.none { name ->
            forbiddenTokens.any { token -> token in name }
        })
    }

    private fun spineProvenance(): AiHubShadowBindingProvenance = exactLookup()

    private fun exactLookup(
        exercise: AiHubExercise = AiHubExercise.BARBELL_SQUAT,
        sourceConditionId: String = SPINE_SOURCE_ID,
        bindingId: String = SPINE_BINDING_ID,
        bindingPolicySha256: String = SPINE_BINDING_POLICY_SHA256,
        policyRegistrySha256: String = AiHubCriterionPolicyCatalog.REGISTRY_SHA256,
    ): AiHubShadowBindingProvenance = AiHubShadowBindingProvenance.exactCatalogLookup(
        exercise = exercise,
        sourceConditionId = sourceConditionId,
        bindingId = bindingId,
        bindingPolicySha256 = bindingPolicySha256,
        policyRegistrySha256 = policyRegistrySha256,
    )

    private companion object {
        const val FULL_CYCLE_PHASE_ROLE_ID = "trex.phase-role.full-cycle.v1"
        const val ANATOMICAL_SEGMENT_FRAME_CAPABILITY_ID =
            "trex.capability.anatomical-segment-frame.v1"
        const val SPINE_SOURCE_ID =
            "aihub-exact-sha256-6a879c85bade13509383fa19b50e1e80dcc2b6aa9b29d9c38ce7e92d7ef1aa65"
        const val SPINE_BINDING_ID =
            "aihub-binding-sha256-94b2a01fea997c10f92d021467a34ccd64dd1bf1f3e6dcaf30ea3b612af9a54d"
        const val SPINE_BINDING_POLICY_SHA256 =
            "34287da867e041e10e4b9a14de6980f5e4f0c8c0174a895bce80713f03e71b14"
        const val KNEE_FOOT_SOURCE_ID =
            "aihub-exact-sha256-48ecac06f2184af84c3a7f6885ecdbd53fb1a025887dde6fe52686a878862bc6"
        const val KNEE_FOOT_BINDING_ID =
            "aihub-binding-sha256-4d64a50373e5da088b53e2f71324aad49d6311eb793867ce89588d13a6b98d84"
    }
}
