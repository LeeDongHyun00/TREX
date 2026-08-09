package com.example.trex_kotlin.pose.spec

import com.example.trex_kotlin.catalog.AiHubCriterionSourceCatalog
import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.policy.AiHubCriterionCalibrationProvenanceState
import com.example.trex_kotlin.pose.policy.AiHubCriterionObservability
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyCatalog
import com.example.trex_kotlin.pose.policy.AiHubCriterionReleaseState
import com.example.trex_kotlin.pose.policy.AiHubCriterionReviewState
import com.example.trex_kotlin.pose.policy.AiHubCriterionSidePolicyKind
import com.example.trex_kotlin.pose.policy.AiHubCriterionViewApplicabilityState
import com.example.trex_kotlin.pose.release.PostureCorrectionLifecycle
import com.example.trex_kotlin.pose.release.PostureCorrectionRuntimeFacade
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BarbellSquatShadowSpecTest {
    @Test
    fun manifestPinsAllFourExactAiHubBindingsAndTheirPolicyContracts() {
        val manifest = BarbellSquatShadowSpec.manifest

        assertEquals(AiHubExercise.BARBELL_SQUAT, manifest.exercise)
        assertEquals(4, manifest.plans.size)
        assertEquals(EXPECTED_BINDINGS.keys, manifest.plans.map {
            it.provenance.sourceConditionId
        }.toSet())
        EXPECTED_BINDINGS.forEach { (sourceConditionId, expected) ->
            val plan = manifest.plans.single {
                it.provenance.sourceConditionId == sourceConditionId
            } as AiHubShadowBindingPlan.Unavailable
            val provenance = plan.provenance

            assertEquals(expected.exactText, provenance.sourceConditionExactText)
            assertEquals(expected.bindingId, provenance.bindingId)
            assertEquals(expected.bindingPolicySha256, provenance.bindingPolicySha256)
            assertEquals(AiHubCriterionReviewState.REVIEWED_ENGINEERING_V1, provenance.reviewState)
            assertEquals(AiHubCriterionReleaseState.CATALOG_ONLY, provenance.releaseState)
            assertEquals(expected.observability, provenance.observability)
            assertEquals(listOf(FULL_CYCLE_PHASE_ROLE_ID), provenance.phaseRoleIds)
            assertEquals(expected.sidePolicyKind, provenance.sidePolicyKind)
            assertEquals(expected.viewState, provenance.viewApplicabilityState)
            assertEquals(expected.viewContractIds, provenance.viewContractIds)
            assertEquals(expected.requiredCapabilityIds, provenance.requiredCapabilityIds)
            assertEquals(
                AiHubCriterionCalibrationProvenanceState.NO_APPROVED_ARTIFACT,
                provenance.calibrationState,
            )
            assertSame(CriterionWindowScope.CompletedCycle, plan.expectedWindowScope)
            assertEquals(setOf(FULL_CYCLE_PHASE_ROLE_ID), plan.unresolvedPhaseRoleIds)
            assertEquals(expected.missingCapabilityIds, plan.missingCapabilityIds)
            assertEquals(expected.unavailableViewContractIds, plan.unavailableViewContractIds)
            assertEquals(expected.reasons, plan.reasons)
        }
    }

    @Test
    fun currentManifestHasThreeTemporaryProxyGapsAndOnePermanentCameraBoundary() {
        val plans = BarbellSquatShadowSpec.manifest.plans
        val unavailable = plans.filterIsInstance<AiHubShadowBindingPlan.Unavailable>()

        assertEquals(4, unavailable.size)
        assertTrue(plans.filterIsInstance<AiHubShadowBindingPlan.MeasureOnly>().isEmpty())
        assertEquals(
            3,
            unavailable.count {
                it.provenance.observability == AiHubCriterionObservability.PROXY_UNVALIDATED
            },
        )
        assertEquals(
            1,
            unavailable.count {
                it.provenance.observability == AiHubCriterionObservability.NOT_OBSERVABLE
            },
        )
        assertTrue(unavailable.filter {
            it.provenance.observability == AiHubCriterionObservability.PROXY_UNVALIDATED
        }.all { ShadowBindingUnavailableReason.NO_BLIND_GOLD_VALIDATION in it.reasons })

        val plantar = unavailable.single {
            it.provenance.sourceConditionId == PLANTAR_SOURCE_ID
        }
        assertEquals(setOf(CONTACT_SENSOR_CAPABILITY_ID), plantar.missingCapabilityIds)
        assertEquals(
            setOf(
                ShadowBindingUnavailableReason.NO_RUNTIME_RELEASE_AUTHORIZATION,
                ShadowBindingUnavailableReason.NO_APPROVED_CALIBRATION_ARTIFACT,
                ShadowBindingUnavailableReason.REQUIRED_PHASE_ROLE_PROVIDER_UNAVAILABLE,
                ShadowBindingUnavailableReason.NOT_OBSERVABLE_FROM_CAMERA_POSE,
            ),
            plantar.reasons,
        )
    }

    @Test
    fun proxyCandidateViewsHaveNoCommonSingleViewAndMatrixIsExact() {
        val proxyViews = EXPECTED_BINDINGS
            .filterValues { expected ->
                expected.observability == AiHubCriterionObservability.PROXY_UNVALIDATED
            }
            .mapValues { (_, expected) -> expected.viewContractIds.toSet() }

        assertTrue(
            proxyViews.values.reduce { commonViews, criterionViews ->
                commonViews intersect criterionViews
            }.isEmpty(),
        )
        assertEquals(
            mapOf(
                FRONT_VIEW_CONTRACT_ID to setOf(KNEE_FOOT_SOURCE_ID, HEAD_SOURCE_ID),
                FRONT_OBLIQUE_VIEW_CONTRACT_ID to
                    setOf(KNEE_FOOT_SOURCE_ID, SPINE_SOURCE_ID),
                LATERAL_VIEW_CONTRACT_ID to setOf(SPINE_SOURCE_ID),
            ),
            listOf(
                FRONT_VIEW_CONTRACT_ID,
                FRONT_OBLIQUE_VIEW_CONTRACT_ID,
                LATERAL_VIEW_CONTRACT_ID,
            ).associateWith { viewId ->
                proxyViews.filterValues { viewId in it }.keys
            },
        )
    }

    @Test
    fun exactSetJoinIsIndependentFromSourceAndConstructorInputOrder() {
        val manifest = BarbellSquatShadowSpec.manifest
        val sourceOrder = AiHubCriterionSourceCatalog
            .requireCoverage(AiHubExercise.BARBELL_SQUAT)
            .conditionIds
        val canonicalManifestOrder = manifest.plans.map { it.provenance.sourceConditionId }

        assertEquals(listOf(SPINE_SOURCE_ID, HEAD_SOURCE_ID, KNEE_FOOT_SOURCE_ID, PLANTAR_SOURCE_ID), sourceOrder)
        assertNotEquals(sourceOrder, canonicalManifestOrder)
        assertEquals(sourceOrder.toSet(), canonicalManifestOrder.toSet())

        val reversed = AiHubShadowExerciseSpec(
            exercise = AiHubExercise.BARBELL_SQUAT,
            policySnapshot = manifest.policySnapshot,
            plans = manifest.plans.reversed(),
            repositoryDriftPinSha256 = manifest.contentSha256,
        )
        assertEquals(manifest.contentSha256, reversed.contentSha256)
        assertEquals(canonicalManifestOrder, reversed.plans.map { it.provenance.sourceConditionId })

        assertThrows(IllegalArgumentException::class.java) {
            AiHubShadowExerciseSpec(
                exercise = AiHubExercise.BARBELL_SQUAT,
                policySnapshot = manifest.policySnapshot,
                plans = manifest.plans.dropLast(1),
                repositoryDriftPinSha256 = manifest.contentSha256,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiHubShadowExerciseSpec(
                exercise = AiHubExercise.BARBELL_SQUAT,
                policySnapshot = manifest.policySnapshot,
                plans = manifest.plans + manifest.plans.first(),
                repositoryDriftPinSha256 = manifest.contentSha256,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiHubShadowExerciseSpec(
                exercise = AiHubExercise.BARBELL_SQUAT,
                policySnapshot = manifest.policySnapshot,
                plans = manifest.plans,
                repositoryDriftPinSha256 = "0".repeat(64),
            )
        }
        val spine = manifest.plans.single { plan ->
            plan.provenance.sourceConditionId == SPINE_SOURCE_ID
        } as AiHubShadowBindingPlan.Unavailable
        val mutatedSpine = AiHubShadowBindingPlan.Unavailable(
            provenance = spine.provenance,
            expectedWindowScope = spine.expectedWindowScope,
            reasons = spine.reasons +
                ShadowBindingUnavailableReason.REQUIRED_VIEW_PROVIDER_UNAVAILABLE,
            missingCapabilityIds = spine.missingCapabilityIds,
            unavailableViewContractIds = spine.unavailableViewContractIds,
            unresolvedPhaseRoleIds = spine.unresolvedPhaseRoleIds,
        )
        assertThrows(IllegalArgumentException::class.java) {
            AiHubShadowExerciseSpec(
                exercise = AiHubExercise.BARBELL_SQUAT,
                policySnapshot = manifest.policySnapshot,
                plans = manifest.plans.map { plan ->
                    if (plan.provenance.sourceConditionId == SPINE_SOURCE_ID) mutatedSpine else plan
                },
                repositoryDriftPinSha256 = manifest.contentSha256,
            )
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (manifest.plans as MutableList<AiHubShadowBindingPlan>).clear()
        }
    }

    @Test
    fun driftPinAndGeneratedCatalogProvenanceMakeNoAuthenticityOrReleaseClaim() {
        val manifest = BarbellSquatShadowSpec.manifest
        val snapshot = manifest.policySnapshot

        assertEquals(manifest.repositoryDriftPinSha256, manifest.contentSha256)
        assertTrue(manifest.contentSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertNotEquals("0".repeat(64), manifest.contentSha256)
        assertEquals(AiHubCriterionPolicyCatalog.SOURCE_CATALOG_SHA256, snapshot.sourceCatalogSha256)
        assertEquals(
            AiHubCriterionPolicyCatalog.SOURCE_COVERAGE_ARTIFACT_SHA256,
            snapshot.sourceCoverageArtifactSha256,
        )
        assertEquals(
            AiHubCriterionPolicyCatalog.SOURCE_METADATA_SET_SHA256,
            snapshot.sourceMetadataSetSha256,
        )
        assertEquals(AiHubCriterionPolicyCatalog.POLICY_SHA256, snapshot.policySha256)
        assertEquals(AiHubCriterionPolicyCatalog.REGISTRY_SHA256, snapshot.policyRegistrySha256)

        val forbiddenTokens = setOf("evaluate", "score", "cue", "pass", "fail", "session")
        val publicMethods = BarbellSquatShadowSpec::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) }
        val publicMethodNames = publicMethods.map { method -> method.name.lowercase() }
        assertTrue(publicMethodNames.none { name -> forbiddenTokens.any { token -> token in name } })
        val forbiddenTypeTokens = setOf(
            "posecriterionresult",
            "posecriteriongraphevaluation",
            "criterioncuecandidate",
            "score",
            "verdict",
        )
        val publicSignatureTypes = publicMethods.flatMap { method ->
            listOf(method.returnType) + method.parameterTypes
        }.map { type -> type.name.lowercase() }
        assertTrue(publicSignatureTypes.none { typeName ->
            forbiddenTypeTokens.any { token -> token in typeName }
        })
    }

    @Test
    fun shadowResearchManifestDoesNotChangeTheZeroReleaseProductFacade() {
        // Initialize the internal manifest first so this is an actual integration regression check.
        assertEquals(4, BarbellSquatShadowSpec.manifest.plans.size)
        val availability = PostureCorrectionRuntimeFacade.availability(AiHubExercise.BARBELL_SQUAT)

        assertEquals(PostureCorrectionLifecycle.CATALOG_ONLY, availability.lifecycle)
        assertEquals(4, availability.catalogCriterionCount)
        assertEquals(4, availability.reviewedCriterionCount)
        assertEquals(0, availability.releasedCriterionCount)
        assertFalse(availability.userSelectable)
        assertFalse(availability.sessionOpenAllowed)
        assertTrue(PostureCorrectionRuntimeFacade.userSelectableExercises.isEmpty())
    }

    private data class ExpectedBinding(
        val exactText: String,
        val bindingId: String,
        val bindingPolicySha256: String,
        val observability: AiHubCriterionObservability,
        val sidePolicyKind: AiHubCriterionSidePolicyKind,
        val viewState: AiHubCriterionViewApplicabilityState,
        val viewContractIds: List<String>,
        val requiredCapabilityIds: List<String>,
        val missingCapabilityIds: Set<String>,
        val unavailableViewContractIds: Set<String>,
        val reasons: Set<ShadowBindingUnavailableReason>,
    )

    private companion object {
        const val FULL_CYCLE_PHASE_ROLE_ID = "trex.phase-role.full-cycle.v1"
        const val ANATOMICAL_SEGMENT_FRAME_CAPABILITY_ID =
            "trex.capability.anatomical-segment-frame.v1"
        const val FACE_ORIENTATION_CAPABILITY_ID = "trex.capability.face-orientation.v1"
        const val CONTACT_SENSOR_CAPABILITY_ID = "trex.capability.contact-sensor.v1"
        const val FRONT_VIEW_CONTRACT_ID = "trex.view.front-full-body.v1"
        const val FRONT_OBLIQUE_VIEW_CONTRACT_ID = "trex.view.front-oblique-full-body.v1"
        const val LATERAL_VIEW_CONTRACT_ID = "trex.view.lateral-full-body.v1"
        const val SPINE_SOURCE_ID =
            "aihub-exact-sha256-6a879c85bade13509383fa19b50e1e80dcc2b6aa9b29d9c38ce7e92d7ef1aa65"
        const val HEAD_SOURCE_ID =
            "aihub-exact-sha256-6fae2ac689be78ee72206bde87af8021dddcc5e15a5c55ab7b9144c0e22da181"
        const val KNEE_FOOT_SOURCE_ID =
            "aihub-exact-sha256-48ecac06f2184af84c3a7f6885ecdbd53fb1a025887dde6fe52686a878862bc6"
        const val PLANTAR_SOURCE_ID =
            "aihub-exact-sha256-80e11956abe253a707f2ff0fcee11fa775f41eba64a2c314472dd189ac503e2d"

        val BASE_POSE_CAPABILITIES = listOf(
            "trex.capability.pose-2d.v1",
            "trex.capability.pose-world-relative.v1",
            "trex.capability.primary-person-lock.v1",
            "trex.capability.temporal-pose.v1",
            "trex.capability.view-qualified.v1",
        )
        val PROXY_REASONS = setOf(
            ShadowBindingUnavailableReason.NO_RUNTIME_RELEASE_AUTHORIZATION,
            ShadowBindingUnavailableReason.NO_APPROVED_CALIBRATION_ARTIFACT,
            ShadowBindingUnavailableReason.NO_BLIND_GOLD_VALIDATION,
            ShadowBindingUnavailableReason.REQUIRED_PHASE_ROLE_PROVIDER_UNAVAILABLE,
            ShadowBindingUnavailableReason.REQUIRED_CAPABILITY_PROVIDER_UNAVAILABLE,
        )
        val EXPECTED_BINDINGS = mapOf(
            KNEE_FOOT_SOURCE_ID to ExpectedBinding(
                exactText = "발과 무릎의 방향 일치",
                bindingId =
                    "aihub-binding-sha256-4d64a50373e5da088b53e2f71324aad49d6311eb793867ce89588d13a6b98d84",
                bindingPolicySha256 =
                    "54382de2a077b889d08cba249c381313027802fa4f3096984174ae66eca687ea",
                observability = AiHubCriterionObservability.PROXY_UNVALIDATED,
                sidePolicyKind = AiHubCriterionSidePolicyKind.BILATERAL_INDEPENDENT,
                viewState = AiHubCriterionViewApplicabilityState.QUALIFIED_VIEW_REQUIRED,
                viewContractIds = listOf(FRONT_VIEW_CONTRACT_ID, FRONT_OBLIQUE_VIEW_CONTRACT_ID),
                requiredCapabilityIds =
                    listOf(ANATOMICAL_SEGMENT_FRAME_CAPABILITY_ID) + BASE_POSE_CAPABILITIES,
                missingCapabilityIds = setOf(ANATOMICAL_SEGMENT_FRAME_CAPABILITY_ID),
                unavailableViewContractIds =
                    setOf(FRONT_VIEW_CONTRACT_ID, FRONT_OBLIQUE_VIEW_CONTRACT_ID),
                reasons = PROXY_REASONS +
                    ShadowBindingUnavailableReason.REQUIRED_VIEW_PROVIDER_UNAVAILABLE,
            ),
            SPINE_SOURCE_ID to ExpectedBinding(
                exactText = "척추의 중립",
                bindingId =
                    "aihub-binding-sha256-94b2a01fea997c10f92d021467a34ccd64dd1bf1f3e6dcaf30ea3b612af9a54d",
                bindingPolicySha256 =
                    "34287da867e041e10e4b9a14de6980f5e4f0c8c0174a895bce80713f03e71b14",
                observability = AiHubCriterionObservability.PROXY_UNVALIDATED,
                sidePolicyKind = AiHubCriterionSidePolicyKind.MIDLINE,
                viewState = AiHubCriterionViewApplicabilityState.QUALIFIED_VIEW_REQUIRED,
                viewContractIds = listOf(FRONT_OBLIQUE_VIEW_CONTRACT_ID, LATERAL_VIEW_CONTRACT_ID),
                requiredCapabilityIds =
                    listOf(ANATOMICAL_SEGMENT_FRAME_CAPABILITY_ID) + BASE_POSE_CAPABILITIES,
                missingCapabilityIds = setOf(ANATOMICAL_SEGMENT_FRAME_CAPABILITY_ID),
                unavailableViewContractIds = emptySet(),
                reasons = PROXY_REASONS,
            ),
            HEAD_SOURCE_ID to ExpectedBinding(
                exactText = "고개 정면",
                bindingId =
                    "aihub-binding-sha256-9ddf6abe72e68295d2a684f59f2d6721bcd663722716b66c83aede1cdaf51d4e",
                bindingPolicySha256 =
                    "784f776e5733c8a02cd3062871ba411ccea5c5a82d84124d39d9078eb6b822a7",
                observability = AiHubCriterionObservability.PROXY_UNVALIDATED,
                sidePolicyKind = AiHubCriterionSidePolicyKind.MIDLINE,
                viewState = AiHubCriterionViewApplicabilityState.QUALIFIED_VIEW_REQUIRED,
                viewContractIds = listOf(FRONT_VIEW_CONTRACT_ID),
                requiredCapabilityIds = listOf(FACE_ORIENTATION_CAPABILITY_ID) + BASE_POSE_CAPABILITIES,
                missingCapabilityIds = setOf(FACE_ORIENTATION_CAPABILITY_ID),
                unavailableViewContractIds = setOf(FRONT_VIEW_CONTRACT_ID),
                reasons = PROXY_REASONS +
                    ShadowBindingUnavailableReason.REQUIRED_VIEW_PROVIDER_UNAVAILABLE,
            ),
            PLANTAR_SOURCE_ID to ExpectedBinding(
                exactText = "발바닥 지면 고정",
                bindingId =
                    "aihub-binding-sha256-e285f11c7ac5ebc41f79058eb47d378c37f66f356696c0473890809bcf68147d",
                bindingPolicySha256 =
                    "844ef872132b7cac2fec8e31ad16b41eed3224285fa090d2ff221569cd89a3d8",
                observability = AiHubCriterionObservability.NOT_OBSERVABLE,
                sidePolicyKind = AiHubCriterionSidePolicyKind.BILATERAL_INDEPENDENT,
                viewState = AiHubCriterionViewApplicabilityState.NO_CAMERA_VIEW_SUFFICIENT,
                viewContractIds = emptyList(),
                requiredCapabilityIds = listOf(CONTACT_SENSOR_CAPABILITY_ID),
                missingCapabilityIds = setOf(CONTACT_SENSOR_CAPABILITY_ID),
                unavailableViewContractIds = emptySet(),
                reasons = setOf(
                    ShadowBindingUnavailableReason.NO_RUNTIME_RELEASE_AUTHORIZATION,
                    ShadowBindingUnavailableReason.NO_APPROVED_CALIBRATION_ARTIFACT,
                    ShadowBindingUnavailableReason.REQUIRED_PHASE_ROLE_PROVIDER_UNAVAILABLE,
                    ShadowBindingUnavailableReason.NOT_OBSERVABLE_FROM_CAMERA_POSE,
                ),
            ),
        )
    }
}
