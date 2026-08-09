package com.example.trex_kotlin.pose.policy

import com.example.trex_kotlin.catalog.AiHubCriterionSourceCatalog
import com.example.trex_kotlin.catalog.AiHubExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiHubCriterionPolicyCatalogTest {
    private val registry: AiHubCriterionPolicyRegistry
        get() = AiHubCriterionPolicyCatalog.registry

    @Test
    fun generatedPolicyExactlyCoversTheAuditedSourceWithoutRuntimeAuthority() {
        assertEquals(
            AiHubCriterionSourceCatalog.CATALOG_SHA256,
            AiHubCriterionPolicyCatalog.SOURCE_CATALOG_SHA256,
        )
        assertEquals(
            AiHubCriterionSourceCatalog.COVERAGE_ARTIFACT_SHA256,
            AiHubCriterionPolicyCatalog.SOURCE_COVERAGE_ARTIFACT_SHA256,
        )
        assertEquals(
            AiHubCriterionSourceCatalog.METADATA_SET_SHA256,
            AiHubCriterionPolicyCatalog.SOURCE_METADATA_SET_SHA256,
        )
        assertEquals(AiHubExercise.entries.toSet(), registry.registeredExercises)
        assertEquals(167, registry.bindings.size)
        assertEquals(148, registry.reviewedBindingCount)
        assertEquals(19, registry.bindings.count {
            it.reviewState ==
                AiHubCriterionReviewState.SOURCE_AMBIGUOUS_REQUIRES_ADJUDICATION
        })
        assertEquals(0, registry.bindings.count {
            it.reviewState == AiHubCriterionReviewState.UNREVIEWED
        })
        assertTrue(registry.bindings.all {
            it.releaseState == AiHubCriterionReleaseState.CATALOG_ONLY
        })

        val exposedMethodNames = AiHubCriterionPolicyCatalog::class.java.methods.map { it.name }
        assertFalse(exposedMethodNames.any { it.contains("evaluate", ignoreCase = true) })
        assertFalse(exposedMethodNames.any { it.contains("cue", ignoreCase = true) })
    }

    @Test
    fun reviewedInterpretationsPreserveExpectedObservabilityPhaseAndSideDistributions() {
        val interpretations = registry.bindings.mapNotNull(AiHubCriterionPolicyBinding::interpretation)
        assertEquals(
            mapOf(
                AiHubCriterionObservability.DIRECT to 80,
                AiHubCriterionObservability.PROXY_UNVALIDATED to 52,
                AiHubCriterionObservability.NOT_OBSERVABLE to 16,
            ),
            interpretations.groupingBy(AiHubCriterionInterpretation::observability).eachCount(),
        )
        assertEquals(
            mapOf(
                "trex.phase-role.full-cycle.v1" to 99,
                "trex.phase-role.lengthened-endpoint.v1" to 22,
                "trex.phase-role.contracted-endpoint.v1" to 20,
                "trex.phase-role.compound-transition.v1" to 3,
                "trex.phase-role.static-hold.v1" to 3,
                "trex.phase-role.concentric.v1" to 1,
            ),
            interpretations
                .flatMap { it.phaseApplicability.phaseRoleIds }
                .groupingBy(String::toString)
                .eachCount(),
        )
        assertEquals(
            mapOf(
                AiHubCriterionSidePolicyKind.BILATERAL_INDEPENDENT to 55,
                AiHubCriterionSidePolicyKind.MIDLINE to 51,
                AiHubCriterionSidePolicyKind.BILATERAL_COUPLED to 16,
                AiHubCriterionSidePolicyKind.LEAD_LIMB to 10,
                AiHubCriterionSidePolicyKind.GLOBAL_BODY to 8,
                AiHubCriterionSidePolicyKind.ACTIVE_LIMB to 3,
                AiHubCriterionSidePolicyKind.TRAIL_LIMB to 3,
                AiHubCriterionSidePolicyKind.ALTERNATING_PAIR to 1,
                AiHubCriterionSidePolicyKind.CONTRALATERAL_PAIR to 1,
            ),
            interpretations.groupingBy { it.sidePolicy.kind }.eachCount(),
        )
        assertEquals(
            19,
            interpretations.count { interpretation ->
                interpretation.unsupportedReasonCodes.any {
                    it.startsWith("REQUIRES_ATTESTED_")
                }
            },
        )
        assertTrue(interpretations.all {
            it.calibrationProvenance.state ==
                AiHubCriterionCalibrationProvenanceState.NO_APPROVED_ARTIFACT &&
                it.calibrationProvenance.artifactSha256 == null &&
                it.calibrationProvenance.runtimeDomainId == null
        })
    }

    @Test
    fun ambiguousSourceBindingsRemainExplicitlyNonExecutable() {
        val ambiguous = registry.bindings.filter {
            it.reviewState ==
                AiHubCriterionReviewState.SOURCE_AMBIGUOUS_REQUIRES_ADJUDICATION
        }
        assertEquals(19, ambiguous.size)
        assertTrue(ambiguous.all { it.interpretation == null && it.reasonCodes.isNotEmpty() })

        val sideLungeRearKnee = AiHubCriterionSourceCatalog
            .requireCoverage(AiHubExercise.SIDE_LUNGE)
            .conditionIds
            .first { conditionId ->
                AiHubCriterionSourceCatalog.registry
                    .condition(conditionId)
                    ?.normalizedExactText == "뒤다리 무릎 각도 90도"
            }
        val binding = AiHubCriterionPolicyCatalog.binding(
            AiHubExercise.SIDE_LUNGE,
            sideLungeRearKnee,
        )
        assertNotNull(binding)
        assertNull(binding?.interpretation)
        assertEquals(
            AiHubCriterionReviewState.SOURCE_AMBIGUOUS_REQUIRES_ADJUDICATION,
            binding?.reviewState,
        )
    }

    @Test
    fun registryCollectionsAndApprovalPinsFailClosed() {
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (registry.bindings as MutableList<AiHubCriterionPolicyBinding>).clear()
        }
        val reviewed = registry.bindings.first { it.interpretation != null }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (requireNotNull(reviewed.interpretation).requiredCapabilityIds as MutableList<String>)
                .clear()
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiHubCriterionPolicyRegistry(
                schemaVersion = registry.schemaVersion,
                sourceCatalogSha256 = registry.sourceCatalogSha256,
                sourceCoverageArtifactSha256 = registry.sourceCoverageArtifactSha256,
                sourceMetadataSetSha256 = registry.sourceMetadataSetSha256,
                approvedPolicySha256 = "0".repeat(64),
                approvalArtifactSha256 = registry.approvalArtifactSha256,
                approvedRegistrySha256 = AiHubCriterionPolicyCatalog.REGISTRY_SHA256,
                bindings = registry.bindings,
                expectedExerciseCount = 41,
                expectedConditionCount = 97,
                expectedBindingCount = 167,
                expectedReviewedBindingCount = 148,
            )
        }
    }
}
