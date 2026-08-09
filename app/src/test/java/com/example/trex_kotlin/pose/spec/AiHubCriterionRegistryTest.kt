package com.example.trex_kotlin.pose.spec

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.criterion.CriterionCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiHubCriterionRegistryTest {
    private val coverage: AiHubExerciseCriterionCoverage
        get() = requireNotNull(AiHubCriterionRegistry.coverage(AiHubExercise.BARBELL_SQUAT))

    @Test
    fun barbellSquatPreservesAuditedSourceConditionsAndProvenance() {
        assertEquals(setOf(AiHubExercise.BARBELL_SQUAT), AiHubCriterionRegistry.registeredExercises)
        assertNull(AiHubCriterionRegistry.coverage(AiHubExercise.PLANK))
        assertEquals(
            listOf("척추의 중립", "고개 정면", "발과 무릎의 방향 일치", "발바닥 지면 고정"),
            coverage.criteria.map(AiHubCriterionDefinition::sourceCondition),
        )

        with(coverage.provenance) {
            assertEquals("docs/aihub-exercise-catalog.json", catalogArtifactPath)
            assertEquals(
                "fe4e3075a00212293c9ffd3df8f007bc3666e17af2526de3a8d570d052a4e29c",
                AiHubCriterionRegistry.BARBELL_SQUAT_APPROVED_CATALOG_SHA256,
            )
            assertEquals(AiHubCriterionRegistry.BARBELL_SQUAT_APPROVED_CATALOG_SHA256, catalogSha256)
            assertEquals(AiHubExercise.CATALOG_SHA256, catalogSha256)
            assertEquals(1, catalogSchemaVersion)
            assertEquals("barbell-squat", exerciseId)
            assertEquals("바벨 스쿼트", sourceExerciseName)
            assertEquals("바벨/덤벨", sourceTypeInfoType)
            assertEquals(720, sourceRecordCount)
        }
        assertEquals(
            "1f6ab0ea0981c6d1ef693ace7e72608a2e9af363b4b52f789a1749f92dae9cb5",
            AiHubCriterionRegistry.BARBELL_SQUAT_APPROVED_COVERAGE_SHA256,
        )
        assertEquals(
            AiHubCriterionRegistry.BARBELL_SQUAT_APPROVED_COVERAGE_SHA256,
            coverage.coverageSha256,
        )
        assertEquals(
            AiHubCriterionRegistry.BARBELL_SQUAT_APPROVED_POLICY_SHA256,
            coverage.policySha256,
        )
        assertEquals(
            "e959ea6731c59a81b06e009a044d18f95417c62d142dfef40c2f21b911f0f1c0",
            AiHubCriterionRegistry.BARBELL_SQUAT_APPROVED_POLICY_SHA256,
        )
    }

    @Test
    fun barbellSquatPreservesAllSixteenTypeTruthVectorsWithoutMissingCodes() {
        val expectedVectors = linkedMapOf(
            "313" to "1111",
            "314" to "0111",
            "315" to "1011",
            "316" to "1101",
            "317" to "1110",
            "318" to "0011",
            "319" to "0101",
            "320" to "0110",
            "321" to "1001",
            "322" to "1010",
            "323" to "1100",
            "324" to "1000",
            "325" to "0100",
            "326" to "0010",
            "327" to "0001",
            "328" to "0000",
        )
        val semanticOrder = listOf(
            AiHubCriterionRegistry.SPINE_NEUTRAL_ID,
            AiHubCriterionRegistry.HEAD_FACING_FORWARD_ID,
            AiHubCriterionRegistry.KNEE_FOOT_DIRECTION_ALIGNED_ID,
            AiHubCriterionRegistry.PLANTAR_GROUND_CONTACT_FIXED_ID,
        )

        assertEquals(AiHubExercise.BARBELL_SQUAT.typeCodes, coverage.typeTruthRows.map { it.typeCode })
        assertEquals(720, coverage.typeTruthRows.sumOf(AiHubTypeConditionTruth::recordCount))
        expectedVectors.forEach { (typeCode, vector) ->
            val actual = requireNotNull(coverage.typeTruth(typeCode))
            assertEquals(
                vector,
                semanticOrder.joinToString(separator = "") { semanticId ->
                    if (actual[semanticId] == true) "1" else "0"
                },
            )
        }
    }

    @Test
    fun everyCriterionHasStableSemanticsCapabilityAndCatalogOnlyRelease() {
        assertEquals(4, coverage.criteria.size)
        assertEquals(4, coverage.semanticIds.size)

        coverage.criteria.forEach { criterion ->
            assertTrue(criterion.semanticId.startsWith("aihub.condition."))
            assertTrue(criterion.semanticId.endsWith(".v1"))
            assertEquals(AiHubCriterionReleaseState.CATALOG_ONLY, criterion.releaseState)
            assertTrue(criterion.unsupportedReason.isNotBlank())
            assertTrue(CriterionCapability.PRIMARY_PERSON_LOCK in criterion.requiredCapabilities)
            assertTrue(CriterionCapability.VIEW_QUALIFIED in criterion.requiredCapabilities)
            assertFalse(criterion.eligiblePhases.isEmpty())
            assertFalse(criterion.eligibleViews.isEmpty())
            assertEquals(AiHubCriterionPhase.entries.toSet(), criterion.eligiblePhases)
        }

        val footContact = requireNotNull(
            coverage.criterion(AiHubCriterionRegistry.PLANTAR_GROUND_CONTACT_FIXED_ID),
        )
        assertEquals(CriterionObservability.NOT_OBSERVABLE, footContact.observability)
        assertTrue(CriterionCapability.GROUND_PROXY in footContact.requiredCapabilities)
        assertEquals(AiHubCriterionSideScope.BILATERAL, footContact.sideScope)
    }

    @Test
    fun exposedCollectionsAreImmutable() {
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (coverage.criteria as MutableList<AiHubCriterionDefinition>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (coverage.semanticIds as MutableSet<String>).clear()
        }
        val criterion = coverage.criteria.first()
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (criterion.requiredCapabilities as MutableSet<CriterionCapability>).clear()
        }
        val truth = coverage.typeTruthRows.first()
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (truth.conditionTruthBySemanticId as MutableMap<String, Boolean>).clear()
        }
    }

    @Test
    fun policyFingerprintChangesWithoutChangingSourceTruthCoverage() {
        val criterion = sampleCriterion()
        val rows = AiHubExercise.BARBELL_SQUAT.typeCodes.map { code ->
            truth(code, mapOf(criterion.semanticId to true))
        }
        val provenance = provenance()
        val sourceSha = aiHubCoverageSha256(
            exercise = AiHubExercise.BARBELL_SQUAT,
            provenance = provenance,
            criteria = listOf(criterion),
            typeTruthRows = rows,
        )
        val changedPolicyCriterion = AiHubCriterionDefinition(
            semanticId = criterion.semanticId,
            sourceCondition = criterion.sourceCondition,
            observability = CriterionObservability.NOT_OBSERVABLE,
            requiredCapabilities = criterion.requiredCapabilities,
            eligiblePhases = criterion.eligiblePhases,
            sideScope = criterion.sideScope,
            eligibleViews = criterion.eligibleViews,
            releaseState = criterion.releaseState,
            unsupportedReason = "카메라 좌표로 직접 관측할 수 없는 테스트 조건",
        )

        assertEquals(
            sourceSha,
            aiHubCoverageSha256(
                exercise = AiHubExercise.BARBELL_SQUAT,
                provenance = provenance,
                criteria = listOf(changedPolicyCriterion),
                typeTruthRows = rows,
            ),
        )
        assertTrue(
            aiHubPolicySha256(
                exercise = AiHubExercise.BARBELL_SQUAT,
                provenance = provenance,
                sourceCoverageSha256 = sourceSha,
                criteria = listOf(criterion),
            ) != aiHubPolicySha256(
                exercise = AiHubExercise.BARBELL_SQUAT,
                provenance = provenance,
                sourceCoverageSha256 = sourceSha,
                criteria = listOf(changedPolicyCriterion),
            ),
        )
    }

    @Test
    fun validationRejectsDuplicateOrMissingCatalogCoverage() {
        val criterion = sampleCriterion()
        val duplicateCriterion = sampleCriterion()
        val allCodes = AiHubExercise.BARBELL_SQUAT.typeCodes.map { code ->
            truth(code, mapOf(criterion.semanticId to true))
        }

        assertThrows(IllegalArgumentException::class.java) {
            coverage(
                criteria = listOf(criterion, duplicateCriterion),
                rows = allCodes.map { row ->
                    truth(
                        row.typeCode,
                        mapOf(criterion.semanticId to true),
                    )
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            coverage(criteria = listOf(criterion), rows = allCodes.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            coverage(
                criteria = listOf(criterion),
                rows = allCodes.dropLast(1) + allCodes.first(),
            )
        }
    }

    @Test
    fun validationRejectsIncompleteTruthRowsAndProvenanceDrift() {
        val criterion = sampleCriterion()
        val second = AiHubCriterionDefinition(
            semanticId = "aihub.condition.second-test.v1",
            sourceCondition = "테스트 두 번째 조건",
            observability = CriterionObservability.PROXY_UNVALIDATED,
            requiredCapabilities = requiredCapabilities(),
            eligiblePhases = setOf(AiHubCriterionPhase.BOTTOM),
            sideScope = AiHubCriterionSideScope.MIDLINE,
            eligibleViews = setOf(AiHubCriterionView.FRONT_FULL_BODY),
            releaseState = AiHubCriterionReleaseState.CATALOG_ONLY,
            unsupportedReason = "테스트 Gold 보정 없음",
        )
        val incompleteRows = AiHubExercise.BARBELL_SQUAT.typeCodes.map { code ->
            truth(code, mapOf(criterion.semanticId to true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            coverage(criteria = listOf(criterion, second), rows = incompleteRows)
        }

        val completeRows = AiHubExercise.BARBELL_SQUAT.typeCodes.map { code ->
            truth(code, mapOf(criterion.semanticId to true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            coverage(
                criteria = listOf(criterion),
                rows = completeRows,
                provenance = provenance(catalogSha256 = "0".repeat(64)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            coverage(
                criteria = listOf(criterion),
                rows = completeRows,
                provenance = provenance(sourceRecordCount = 719),
            )
        }
    }

    private fun sampleCriterion() = AiHubCriterionDefinition(
        semanticId = "aihub.condition.test-criterion.v1",
        sourceCondition = "테스트 조건",
        observability = CriterionObservability.PROXY_UNVALIDATED,
        requiredCapabilities = requiredCapabilities(),
        eligiblePhases = setOf(AiHubCriterionPhase.BOTTOM),
        sideScope = AiHubCriterionSideScope.MIDLINE,
        eligibleViews = setOf(AiHubCriterionView.FRONT_FULL_BODY),
        releaseState = AiHubCriterionReleaseState.CATALOG_ONLY,
        unsupportedReason = "테스트 Gold 보정 없음",
    )

    private fun requiredCapabilities() = setOf(
        CriterionCapability.POSE_2D,
        CriterionCapability.PRIMARY_PERSON_LOCK,
        CriterionCapability.VIEW_QUALIFIED,
    )

    private fun truth(
        typeCode: String,
        values: Map<String, Boolean>,
    ) = AiHubTypeConditionTruth(
        typeCode = typeCode,
        recordCount = 45,
        conditionTruthBySemanticId = values,
    )

    private fun provenance(
        catalogSha256: String = AiHubExercise.CATALOG_SHA256,
        sourceRecordCount: Int = 720,
    ) = AiHubCriterionSourceProvenance(
        catalogArtifactPath = "docs/aihub-exercise-catalog.json",
        catalogSha256 = catalogSha256,
        catalogSchemaVersion = 1,
        exerciseId = AiHubExercise.BARBELL_SQUAT.id,
        sourceExerciseName = AiHubExercise.BARBELL_SQUAT.displayName,
        sourceTypeInfoType = AiHubExercise.BARBELL_SQUAT.typeInfoType,
        sourceRecordCount = sourceRecordCount,
    )

    private fun coverage(
        criteria: List<AiHubCriterionDefinition>,
        rows: List<AiHubTypeConditionTruth>,
        provenance: AiHubCriterionSourceProvenance = provenance(),
    ) = AiHubExerciseCriterionCoverage(
        exercise = AiHubExercise.BARBELL_SQUAT,
        provenance = provenance,
        criteria = criteria,
        typeTruthRows = rows,
        approvedCoverageSha256 = aiHubCoverageSha256(
            exercise = AiHubExercise.BARBELL_SQUAT,
            provenance = provenance,
            criteria = criteria,
            typeTruthRows = rows,
        ),
        approvedPolicySha256 = aiHubPolicySha256(
            exercise = AiHubExercise.BARBELL_SQUAT,
            provenance = provenance,
            sourceCoverageSha256 = aiHubCoverageSha256(
                exercise = AiHubExercise.BARBELL_SQUAT,
                provenance = provenance,
                criteria = criteria,
                typeTruthRows = rows,
            ),
            criteria = criteria,
        ),
    )
}
