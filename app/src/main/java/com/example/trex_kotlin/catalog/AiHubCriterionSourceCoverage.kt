package com.example.trex_kotlin.catalog

import java.security.MessageDigest
import java.util.Collections

private val AI_HUB_EXACT_CONDITION_ID = Regex("^aihub-exact-sha256-[0-9a-f]{64}$")
private val AI_HUB_SOURCE_TYPE_CODE = Regex("^[0-9]{3}$")
private val AI_HUB_SOURCE_SHA256 = Regex("^[0-9a-f]{64}$")

/** Source-level identity only. A collision never authorizes merging two AI Hub type codes. */
enum class AiHubTruthVectorIdentity {
    UNIQUE,
    COLLISION_REVIEW_REQUIRED,
}

/** A quarantined source row may be inventoried but cannot calibrate a criterion automatically. */
enum class AiHubSourceLabelState {
    CLEAR,
    QUARANTINED_PENDING_BLIND_GOLD,
}

/** Exact normalized condition text and every raw spelling observed in authoritative 2D metadata. */
class AiHubExactSourceCondition internal constructor(
    val id: String,
    val normalizedExactText: String,
    rawTextAliases: Collection<String>,
) {
    val rawTextAliases: List<String> = immutableSourceList(rawTextAliases)

    init {
        require(AI_HUB_EXACT_CONDITION_ID.matches(id)) {
            "AI Hub exact condition id must contain the full normalized-text SHA-256"
        }
        require(normalizedExactText.isNotBlank()) { "normalizedExactText must not be blank" }
        require(id == "aihub-exact-sha256-${sourceTextSha256(normalizedExactText)}") {
            "AI Hub exact condition id must match normalizedExactText"
        }
        require(this.rawTextAliases.isNotEmpty()) { "rawTextAliases must not be empty" }
        require(this.rawTextAliases.all(String::isNotBlank)) {
            "rawTextAliases must not contain blank text"
        }
        require(this.rawTextAliases == this.rawTextAliases.distinct().sorted()) {
            "rawTextAliases must be sorted and unique"
        }
    }
}

/** Compact truth row retained on-device for source coverage and audit, never for direct cue release. */
class AiHubSourceTypeTruth internal constructor(
    val typeCode: String,
    val recordCount: Int,
    val truthVector: String,
    val truthVectorIdentity: AiHubTruthVectorIdentity,
    collidingTypeCodes: Collection<String> = emptyList(),
    val labelState: AiHubSourceLabelState = AiHubSourceLabelState.CLEAR,
    quarantineReasonCodes: Collection<String> = emptyList(),
) {
    val collidingTypeCodes: List<String> = immutableSourceList(collidingTypeCodes)
    val quarantineReasonCodes: List<String> = immutableSourceList(quarantineReasonCodes)

    init {
        require(AI_HUB_SOURCE_TYPE_CODE.matches(typeCode)) {
            "typeCode must be a three-digit string"
        }
        require(recordCount > 0) { "recordCount must be positive" }
        require(truthVector.isNotEmpty() && truthVector.all { it == '0' || it == '1' }) {
            "truthVector must be a non-empty binary string"
        }
        require(this.collidingTypeCodes == this.collidingTypeCodes.distinct().sortedBy(String::toInt)) {
            "collidingTypeCodes must be numerically sorted and unique"
        }
        when (truthVectorIdentity) {
            AiHubTruthVectorIdentity.UNIQUE -> require(this.collidingTypeCodes.isEmpty()) {
                "A unique truth vector cannot declare colliding type codes"
            }
            AiHubTruthVectorIdentity.COLLISION_REVIEW_REQUIRED -> {
                require(this.collidingTypeCodes.size >= 2 && typeCode in this.collidingTypeCodes) {
                    "A collision group must contain this type and at least one peer"
                }
            }
        }
        require(this.quarantineReasonCodes == this.quarantineReasonCodes.distinct().sorted()) {
            "quarantineReasonCodes must be sorted and unique"
        }
        when (labelState) {
            AiHubSourceLabelState.CLEAR -> require(this.quarantineReasonCodes.isEmpty()) {
                "A clear source row cannot declare quarantine reasons"
            }
            AiHubSourceLabelState.QUARANTINED_PENDING_BLIND_GOLD ->
                require(this.quarantineReasonCodes.isNotEmpty()) {
                    "A quarantined source row must declare at least one reason"
                }
        }
    }
}

/** Complete exact-condition and type-truth coverage for one canonical AI Hub exercise. */
class AiHubExerciseSourceCoverage internal constructor(
    val exercise: AiHubExercise,
    conditionIds: Collection<String>,
    typeTruthRows: Collection<AiHubSourceTypeTruth>,
) {
    val conditionIds: List<String> = immutableSourceList(conditionIds)
    val typeTruthRows: List<AiHubSourceTypeTruth> = immutableSourceList(typeTruthRows)
    private val truthByTypeCode: Map<String, AiHubSourceTypeTruth>

    val collisionGroups: List<List<String>>
    val collisionGroupCount: Int
        get() = collisionGroups.size
    val collisionTypeCount: Int
        get() = collisionGroups.sumOf(List<String>::size)
    val collisionExcessTypeCount: Int
        get() = collisionGroups.sumOf { it.size - 1 }
    val quarantinedTypeCount: Int
        get() = typeTruthRows.count { it.labelState != AiHubSourceLabelState.CLEAR }
    val quarantinedRecordCount: Int
        get() = typeTruthRows
            .filter { it.labelState != AiHubSourceLabelState.CLEAR }
            .sumOf(AiHubSourceTypeTruth::recordCount)

    init {
        require(this.conditionIds.isNotEmpty()) { "Exercise condition coverage must not be empty" }
        require(this.conditionIds.size == this.conditionIds.toSet().size) {
            "Exercise condition ids must be unique"
        }
        require(this.conditionIds.all(AI_HUB_EXACT_CONDITION_ID::matches)) {
            "Exercise condition ids must use exact source identities"
        }
        require(this.typeTruthRows.map(AiHubSourceTypeTruth::typeCode) == exercise.typeCodes) {
            "Type truth rows must exactly match canonical numeric type-code order"
        }
        require(this.typeTruthRows.all { it.truthVector.length == this.conditionIds.size }) {
            "Every truth vector must contain exactly every exercise condition"
        }
        require(this.typeTruthRows.sumOf(AiHubSourceTypeTruth::recordCount) == exercise.recordCount) {
            "Type truth record counts must sum to the canonical exercise record count"
        }
        truthByTypeCode = immutableSourceMap(
            this.typeTruthRows.associateBy(AiHubSourceTypeTruth::typeCode),
        )
        require(truthByTypeCode.size == this.typeTruthRows.size) {
            "Type truth rows must have unique type codes"
        }

        val rowsByTruthVector = this.typeTruthRows.groupBy(AiHubSourceTypeTruth::truthVector)
        rowsByTruthVector.values.forEach { rows ->
            if (rows.size == 1) {
                require(rows.single().truthVectorIdentity == AiHubTruthVectorIdentity.UNIQUE) {
                    "A non-colliding truth vector must be declared UNIQUE"
                }
            } else {
                val expectedCodes = rows
                    .map(AiHubSourceTypeTruth::typeCode)
                    .sortedBy(String::toInt)
                require(rows.all {
                    it.truthVectorIdentity == AiHubTruthVectorIdentity.COLLISION_REVIEW_REQUIRED &&
                        it.collidingTypeCodes == expectedCodes
                }) {
                    "Every duplicated truth vector must declare its complete collision group"
                }
            }
        }
        collisionGroups = immutableSourceList(
            rowsByTruthVector.values
                .filter { it.size > 1 }
                .map { rows -> rows.map(AiHubSourceTypeTruth::typeCode).sortedBy(String::toInt) }
                .sortedBy { group -> group.first().toInt() }
                .map(::immutableSourceList),
        )
    }

    fun truth(typeCode: String): AiHubSourceTypeTruth? = truthByTypeCode[typeCode]
}

/**
 * Immutable compiler output covering every AI Hub exercise and exact condition.
 *
 * This registry contains source truth and quarantine metadata only. It has no threshold, feature,
 * calibration, release mode, or cue text, so its presence cannot make a posture correction
 * user-facing. Executable [com.example.trex_kotlin.pose.spec.PoseExerciseSpec] packages remain a
 * separate signed and calibrated release boundary.
 */
class AiHubCriterionSourceRegistry internal constructor(
    val schemaVersion: Int,
    val catalogSha256: String,
    val coverageArtifactSha256: String,
    val metadataSetSha256: String,
    sourceConditions: Collection<AiHubExactSourceCondition>,
    coverages: Collection<AiHubExerciseSourceCoverage>,
    expectedExerciseCount: Int,
    expectedTypeCount: Int,
    expectedRecordCount: Int,
    expectedExactConditionCount: Int,
    expectedExerciseConditionAssignmentCount: Int,
    expectedCollisionExerciseCount: Int,
    expectedCollisionGroupCount: Int,
    expectedCollisionTypeCount: Int,
    expectedCollisionExcessTypeCount: Int,
    expectedQuarantinedTypeCount: Int,
    expectedQuarantinedRecordCount: Int,
) {
    val sourceConditions: List<AiHubExactSourceCondition> = immutableSourceList(sourceConditions)
    val coverages: List<AiHubExerciseSourceCoverage> = immutableSourceList(coverages)
    private val conditionById: Map<String, AiHubExactSourceCondition>
    private val coverageByExercise: Map<AiHubExercise, AiHubExerciseSourceCoverage>

    val registeredExercises: Set<AiHubExercise>
        get() = coverageByExercise.keys
    val exerciseConditionAssignmentCount: Int
        get() = coverages.sumOf { it.conditionIds.size }
    val typeCount: Int
        get() = coverages.sumOf { it.typeTruthRows.size }
    val recordCount: Int
        get() = coverages.sumOf { coverage ->
            coverage.typeTruthRows.sumOf(AiHubSourceTypeTruth::recordCount)
        }

    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(AI_HUB_SOURCE_SHA256.matches(catalogSha256)) {
            "catalogSha256 must be a lowercase SHA-256"
        }
        require(catalogSha256 == AiHubExercise.CATALOG_SHA256) {
            "Criterion source coverage must match the generated exercise catalog"
        }
        require(AI_HUB_SOURCE_SHA256.matches(coverageArtifactSha256)) {
            "coverageArtifactSha256 must be a lowercase SHA-256"
        }
        require(AI_HUB_SOURCE_SHA256.matches(metadataSetSha256)) {
            "metadataSetSha256 must be a lowercase SHA-256"
        }

        conditionById = immutableSourceMap(
            this.sourceConditions.associateBy(AiHubExactSourceCondition::id),
        )
        require(conditionById.size == this.sourceConditions.size) {
            "Exact source condition ids must be globally unique"
        }
        require(this.sourceConditions.map(AiHubExactSourceCondition::normalizedExactText).toSet().size ==
            this.sourceConditions.size) {
            "Normalized exact source condition text must be globally unique"
        }
        coverageByExercise = immutableSourceMap(
            this.coverages.associateBy(AiHubExerciseSourceCoverage::exercise),
        )
        require(coverageByExercise.size == this.coverages.size) {
            "Each exercise may have only one source coverage package"
        }
        require(coverageByExercise.keys == AiHubExercise.entries.toSet()) {
            "Source coverage must contain every canonical AI Hub exercise exactly once"
        }
        val referencedConditionIds = this.coverages
            .flatMap(AiHubExerciseSourceCoverage::conditionIds)
            .toSet()
        require(referencedConditionIds == conditionById.keys) {
            "Source condition registry and exercise assignments must have an exact-set match"
        }

        require(this.coverages.size == expectedExerciseCount)
        require(typeCount == expectedTypeCount)
        require(recordCount == expectedRecordCount)
        require(this.sourceConditions.size == expectedExactConditionCount)
        require(exerciseConditionAssignmentCount == expectedExerciseConditionAssignmentCount)
        require(this.coverages.count { it.collisionGroupCount > 0 } == expectedCollisionExerciseCount)
        require(this.coverages.sumOf(AiHubExerciseSourceCoverage::collisionGroupCount) ==
            expectedCollisionGroupCount)
        require(this.coverages.sumOf(AiHubExerciseSourceCoverage::collisionTypeCount) ==
            expectedCollisionTypeCount)
        require(this.coverages.sumOf(AiHubExerciseSourceCoverage::collisionExcessTypeCount) ==
            expectedCollisionExcessTypeCount)
        require(this.coverages.sumOf(AiHubExerciseSourceCoverage::quarantinedTypeCount) ==
            expectedQuarantinedTypeCount)
        require(this.coverages.sumOf(AiHubExerciseSourceCoverage::quarantinedRecordCount) ==
            expectedQuarantinedRecordCount)
    }

    fun condition(id: String): AiHubExactSourceCondition? = conditionById[id]

    fun coverage(exercise: AiHubExercise): AiHubExerciseSourceCoverage? =
        coverageByExercise[exercise]

    fun requireCoverage(exercise: AiHubExercise): AiHubExerciseSourceCoverage =
        requireNotNull(coverage(exercise)) { "Missing AI Hub source coverage for ${exercise.id}" }
}

private fun <T> immutableSourceList(source: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))

private fun <K, V> immutableSourceMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))

private fun sourceTextSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
