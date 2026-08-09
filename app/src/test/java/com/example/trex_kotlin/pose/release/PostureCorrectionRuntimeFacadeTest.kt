package com.example.trex_kotlin.pose.release

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyCatalog
import com.example.trex_kotlin.pose.policy.AiHubCriterionReviewState
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PostureCorrectionRuntimeFacadeTest {
    @Test
    fun currentBundleCatalogsAll41ExercisesButAuthorizesNoUserRuntime() {
        assertEquals(41, AiHubExercise.entries.size)
        assertEquals(AiHubExercise.entries.toSet(), PostureCorrectionRuntimeFacade.catalogedExercises)
        assertEquals(41, PostureCorrectionRuntimeFacade.availabilities.size)
        assertEquals(
            AiHubExercise.entries.toSet(),
            PostureCorrectionRuntimeFacade.availabilities.map { it.exercise }.toSet(),
        )
        assertEquals(
            167,
            PostureCorrectionRuntimeFacade.availabilities.sumOf { it.catalogCriterionCount },
        )
        assertEquals(
            148,
            PostureCorrectionRuntimeFacade.availabilities.sumOf { it.reviewedCriterionCount },
        )
        assertEquals(
            0,
            PostureCorrectionRuntimeFacade.availabilities.sumOf { it.releasedCriterionCount },
        )
        assertTrue(PostureCorrectionRuntimeFacade.userSelectableExercises.isEmpty())
        assertTrue(PostureCorrectionRuntimeFacade.availabilities.all {
            it.lifecycle == PostureCorrectionLifecycle.CATALOG_ONLY &&
                !it.userSelectable &&
                !it.sessionOpenAllowed
        })
    }

    @Test
    fun barbellSquatReportsFourReviewedBindingsAndZeroReleasedBindings() {
        val availability = PostureCorrectionRuntimeFacade.availability(AiHubExercise.BARBELL_SQUAT)

        assertEquals(PostureCorrectionLifecycle.CATALOG_ONLY, availability.lifecycle)
        assertEquals(4, availability.catalogCriterionCount)
        assertEquals(4, availability.reviewedCriterionCount)
        assertEquals(0, availability.releasedCriterionCount)
        assertFalse(availability.userSelectable)
        assertFalse(availability.sessionOpenAllowed)
        assertSame(PostureCorrectionRuntimeFacade.policyProvenance, availability.policyProvenance)
    }

    @Test
    fun everyExerciseAvailabilityMatchesItsExactGeneratedPolicyBindings() {
        AiHubExercise.entries.forEach { exercise ->
            val bindings = AiHubCriterionPolicyCatalog.bindings(exercise)
            val availability = PostureCorrectionRuntimeFacade.availability(exercise)

            assertEquals(bindings.size, availability.catalogCriterionCount)
            assertEquals(
                bindings.count {
                    it.reviewState == AiHubCriterionReviewState.REVIEWED_ENGINEERING_V1
                },
                availability.reviewedCriterionCount,
            )
            assertEquals(0, availability.releasedCriterionCount)
        }
    }

    @Test
    fun policyProvenanceIsDerivedFromTheGeneratedCatalogWithoutAuthenticityClaims() {
        val provenance = PostureCorrectionRuntimeFacade.policyProvenance

        assertEquals(AiHubCriterionPolicyCatalog.SOURCE_CATALOG_SHA256, provenance.sourceCatalogSha256)
        assertEquals(
            AiHubCriterionPolicyCatalog.SOURCE_COVERAGE_ARTIFACT_SHA256,
            provenance.sourceCoverageArtifactSha256,
        )
        assertEquals(
            AiHubCriterionPolicyCatalog.SOURCE_METADATA_SET_SHA256,
            provenance.sourceMetadataSetSha256,
        )
        assertEquals(AiHubCriterionPolicyCatalog.POLICY_SHA256, provenance.policySha256)
        assertEquals(AiHubCriterionPolicyCatalog.REGISTRY_SHA256, provenance.policyRegistrySha256)
        assertEquals(
            "699912f304933285ec9c832b32e59383b97ddb6ae77b879370e502718b1c4b31",
            PostureCorrectionRuntimeFacade.releaseAllowlistArtifactSha256,
        )
    }

    @Test
    fun exposedCollectionsCannotBeMutated() {
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (PostureCorrectionRuntimeFacade.availabilities as
                MutableList<PostureCorrectionAvailability>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (PostureCorrectionRuntimeFacade.catalogedExercises as MutableSet<AiHubExercise>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (PostureCorrectionRuntimeFacade.userSelectableExercises as MutableSet<AiHubExercise>)
                .add(AiHubExercise.BARBELL_SQUAT)
        }
    }

    @Test
    fun lifecycleVocabularyIsCompleteAndFacadePublicApiIsLookupOnly() {
        assertEquals(
            listOf("UNSUPPORTED", "CATALOG_ONLY", "SHADOW", "OPT_IN_BETA", "GA"),
            PostureCorrectionLifecycle.entries.map { it.name },
        )

        val declaredPublicMethods = PostureCorrectionRuntimeFacade::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf(
                "availability",
                "getAvailabilities",
                "getCatalogedExercises",
                "getPolicyProvenance",
                "getReleaseAllowlistArtifactSha256",
                "getUserSelectableExercises",
            ),
            declaredPublicMethods,
        )
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.example.trex_kotlin.pose.PoseEvaluatorFactory")
        }
    }
}
