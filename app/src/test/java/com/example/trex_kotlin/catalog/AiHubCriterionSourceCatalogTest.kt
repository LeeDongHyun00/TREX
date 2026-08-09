package com.example.trex_kotlin.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiHubCriterionSourceCatalogTest {
    private val registry: AiHubCriterionSourceRegistry
        get() = AiHubCriterionSourceCatalog.registry

    @Test
    fun generatedRegistryPreservesCompleteServiceScaleSourceCoverage() {
        assertEquals(
            "fe4e3075a00212293c9ffd3df8f007bc3666e17af2526de3a8d570d052a4e29c",
            AiHubCriterionSourceCatalog.CATALOG_SHA256,
        )
        assertEquals(
            "9240aa2c9a429cce8f4c47314f7797bea6ebf39b276d3563f5d420a9d3a34eda",
            AiHubCriterionSourceCatalog.COVERAGE_ARTIFACT_SHA256,
        )
        assertEquals(
            "518513cf2c627b4cff0b4a458b4048aa4a2eb38dfe5211fea06eb8b5c68f6ed8",
            AiHubCriterionSourceCatalog.METADATA_SET_SHA256,
        )
        assertEquals(AiHubExercise.entries.toSet(), registry.registeredExercises)
        assertEquals(41, registry.coverages.size)
        assertEquals(97, registry.sourceConditions.size)
        assertEquals(167, registry.exerciseConditionAssignmentCount)
        assertEquals(816, registry.typeCount)
        assertEquals(34_468, registry.recordCount)
        assertEquals(15, registry.coverages.count { it.collisionGroupCount > 0 })
        assertEquals(55, registry.coverages.sumOf(AiHubExerciseSourceCoverage::collisionGroupCount))
        assertEquals(159, registry.coverages.sumOf(AiHubExerciseSourceCoverage::collisionTypeCount))
        assertEquals(
            104,
            registry.coverages.sumOf(AiHubExerciseSourceCoverage::collisionExcessTypeCount),
        )
        assertEquals(3, registry.coverages.sumOf(AiHubExerciseSourceCoverage::quarantinedTypeCount))
        assertEquals(
            153,
            registry.coverages.sumOf(AiHubExerciseSourceCoverage::quarantinedRecordCount),
        )
        AiHubExercise.entries.forEach { exercise ->
            assertNotNull(AiHubCriterionSourceCatalog.coverage(exercise))
            assertEquals(exercise, AiHubCriterionSourceCatalog.requireCoverage(exercise).exercise)
        }
    }

    @Test
    fun barbellSquatTruthRowsMatchTheAuditedSixteenVectors() {
        val coverage = AiHubCriterionSourceCatalog.requireCoverage(AiHubExercise.BARBELL_SQUAT)
        assertEquals(
            listOf("척추의 중립", "고개 정면", "발과 무릎의 방향 일치", "발바닥 지면 고정"),
            coverage.conditionIds.map { conditionId ->
                requireNotNull(registry.condition(conditionId)).normalizedExactText
            },
        )
        assertEquals(
            linkedMapOf(
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
            ),
            coverage.typeTruthRows.associate { it.typeCode to it.truthVector },
        )
        assertTrue(coverage.typeTruthRows.all { it.truthVectorIdentity == AiHubTruthVectorIdentity.UNIQUE })
        assertEquals(0, coverage.quarantinedTypeCount)
    }

    @Test
    fun knownConflictsRemainPresentButCannotCalibrateAutomatically() {
        val expected = setOf(
            AiHubExercise.BURPEE_TEST to "062",
            AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE to "101",
            AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE to "109",
        )
        val actual = registry.coverages.flatMap { coverage ->
            coverage.typeTruthRows
                .filter { it.labelState == AiHubSourceLabelState.QUARANTINED_PENDING_BLIND_GOLD }
                .map { row -> coverage.exercise to row.typeCode }
        }.toSet()

        assertEquals(expected, actual)
        actual.forEach { (exercise, typeCode) ->
            val row = requireNotNull(registry.requireCoverage(exercise).truth(typeCode))
            assertFalse(row.quarantineReasonCodes.isEmpty())
        }
    }

    @Test
    fun generatedCoverageCollectionsCannotBeMutatedByCallers() {
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (registry.sourceConditions as MutableList<AiHubExactSourceCondition>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (registry.coverages as MutableList<AiHubExerciseSourceCoverage>).clear()
        }
        val condition = registry.sourceConditions.first()
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (condition.rawTextAliases as MutableList<String>).clear()
        }
        val collision = registry.coverages
            .flatMap(AiHubExerciseSourceCoverage::typeTruthRows)
            .first { it.truthVectorIdentity == AiHubTruthVectorIdentity.COLLISION_REVIEW_REQUIRED }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (collision.collidingTypeCodes as MutableList<String>).clear()
        }
    }

    @Test
    fun exerciseCoverageRejectsMissingTypesAndMalformedTruthVectors() {
        val exercise = AiHubExercise.BARBELL_SQUAT
        val oneConditionId =
            "aihub-exact-sha256-" + "0".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            AiHubExerciseSourceCoverage(
                exercise = exercise,
                conditionIds = listOf(oneConditionId),
                typeTruthRows = emptyList(),
            )
        }
        val malformedRows = exercise.typeCodes.map { typeCode ->
            AiHubSourceTypeTruth(
                typeCode = typeCode,
                recordCount = 45,
                truthVector = "10",
                truthVectorIdentity = AiHubTruthVectorIdentity.UNIQUE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiHubExerciseSourceCoverage(
                exercise = exercise,
                conditionIds = listOf(oneConditionId),
                typeTruthRows = malformedRows,
            )
        }
    }

    @Test
    fun exactConditionIdentityMustMatchItsNormalizedText() {
        assertThrows(IllegalArgumentException::class.java) {
            AiHubExactSourceCondition(
                id = "aihub-exact-sha256-" + "0".repeat(64),
                normalizedExactText = "척추의 중립",
                rawTextAliases = listOf("척추의 중립"),
            )
        }
    }

    @Test
    fun duplicatedTruthVectorCannotBeDeclaredUnique() {
        val exercise = AiHubExercise.BARBELL_SQUAT
        val conditionId = registry.requireCoverage(exercise).conditionIds.first()
        val rows = exercise.typeCodes.map { typeCode ->
            AiHubSourceTypeTruth(
                typeCode = typeCode,
                recordCount = 45,
                truthVector = "1",
                truthVectorIdentity = AiHubTruthVectorIdentity.UNIQUE,
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            AiHubExerciseSourceCoverage(
                exercise = exercise,
                conditionIds = listOf(conditionId),
                typeTruthRows = rows,
            )
        }
    }
}
