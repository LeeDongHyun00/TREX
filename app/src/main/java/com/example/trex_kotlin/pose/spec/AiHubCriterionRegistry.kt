package com.example.trex_kotlin.pose.spec

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.criterion.CriterionCapability
import java.util.Collections

private val AI_HUB_SEMANTIC_ID = Regex("^aihub\\.condition\\.[a-z0-9][a-z0-9.-]*\\.v[1-9][0-9]*$")
private val AI_HUB_TYPE_CODE = Regex("^[0-9]{3}$")
private val AI_HUB_SHA256 = Regex("^[0-9a-f]{64}$")

/**
 * A catalog entry cannot become user-facing merely by being added here.
 *
 * New release states must be introduced deliberately together with the signed evaluator and
 * calibration release policy. The first AI Hub vertical slice is research-only.
 */
enum class AiHubCriterionReleaseState {
    CATALOG_ONLY,
}

/** Exercise-relative phase applicability; it does not claim that AI Hub labels the phases. */
enum class AiHubCriterionPhase {
    SETUP,
    DESCENT,
    BOTTOM,
    ASCENT,
    TOP,
}

enum class AiHubCriterionSideScope {
    MIDLINE,
    BILATERAL,
}

/** A view is usable only after the observer contract has qualified it. */
enum class AiHubCriterionView {
    FRONT_FULL_BODY,
    FRONT_OBLIQUE_FULL_BODY,
    LATERAL_FULL_BODY,
}

/** Immutable source identity of the audited, APK-external AI Hub catalog artifact. */
class AiHubCriterionSourceProvenance(
    val catalogArtifactPath: String,
    val catalogSha256: String,
    val catalogSchemaVersion: Int,
    val exerciseId: String,
    val sourceExerciseName: String,
    val sourceTypeInfoType: String,
    val sourceRecordCount: Int,
) {
    init {
        require(catalogArtifactPath.isNotBlank()) { "catalogArtifactPath must not be blank" }
        require(AI_HUB_SHA256.matches(catalogSha256)) {
            "catalogSha256 must be a lowercase SHA-256"
        }
        require(catalogSchemaVersion > 0) { "catalogSchemaVersion must be positive" }
        require(exerciseId.isNotBlank()) { "exerciseId must not be blank" }
        require(sourceExerciseName.isNotBlank()) { "sourceExerciseName must not be blank" }
        require(sourceTypeInfoType.isNotBlank()) { "sourceTypeInfoType must not be blank" }
        require(sourceRecordCount > 0) { "sourceRecordCount must be positive" }
    }
}

/**
 * One stable semantic binding for an exact AI Hub condition string.
 *
 * [unsupportedReason] explains why the binding cannot emit a production cue. It is mandatory
 * while the only permitted release state is [AiHubCriterionReleaseState.CATALOG_ONLY], preventing a
 * catalog-coverage declaration from being mistaken for a validated evaluator.
 */
class AiHubCriterionDefinition(
    val semanticId: String,
    val sourceCondition: String,
    val observability: CriterionObservability,
    requiredCapabilities: Set<CriterionCapability>,
    eligiblePhases: Set<AiHubCriterionPhase>,
    val sideScope: AiHubCriterionSideScope,
    eligibleViews: Set<AiHubCriterionView>,
    val releaseState: AiHubCriterionReleaseState,
    val unsupportedReason: String,
) {
    val requiredCapabilities: Set<CriterionCapability> = immutableSet(requiredCapabilities)
    val eligiblePhases: Set<AiHubCriterionPhase> = immutableSet(eligiblePhases)
    val eligibleViews: Set<AiHubCriterionView> = immutableSet(eligibleViews)

    init {
        require(AI_HUB_SEMANTIC_ID.matches(semanticId)) {
            "semanticId must be stable, versioned, and namespaced under aihub.condition"
        }
        require(sourceCondition.isNotBlank()) { "sourceCondition must not be blank" }
        require(this.requiredCapabilities.isNotEmpty()) {
            "requiredCapabilities must not be empty"
        }
        require(CriterionCapability.PRIMARY_PERSON_LOCK in this.requiredCapabilities) {
            "Every camera criterion requires PRIMARY_PERSON_LOCK"
        }
        require(CriterionCapability.VIEW_QUALIFIED in this.requiredCapabilities) {
            "Every camera criterion requires VIEW_QUALIFIED"
        }
        require(this.eligiblePhases.isNotEmpty()) { "eligiblePhases must not be empty" }
        require(this.eligibleViews.isNotEmpty()) { "eligibleViews must not be empty" }
        require(releaseState == AiHubCriterionReleaseState.CATALOG_ONLY) {
            "AI Hub catalog coverage is not a cue release authorization"
        }
        require(unsupportedReason.isNotBlank()) {
            "A catalog-only criterion must state why it is not production-supported"
        }
    }
}

/** Exact condition truth values stored on one AI Hub type code. */
class AiHubTypeConditionTruth(
    val typeCode: String,
    val recordCount: Int,
    conditionTruthBySemanticId: Map<String, Boolean>,
) {
    val conditionTruthBySemanticId: Map<String, Boolean> =
        immutableMap(conditionTruthBySemanticId)

    init {
        require(AI_HUB_TYPE_CODE.matches(typeCode)) { "typeCode must be a three-digit string" }
        require(recordCount > 0) { "recordCount must be positive" }
        require(this.conditionTruthBySemanticId.isNotEmpty()) {
            "conditionTruthBySemanticId must not be empty"
        }
        require(this.conditionTruthBySemanticId.keys.all(AI_HUB_SEMANTIC_ID::matches)) {
            "Every condition truth key must be a stable semantic id"
        }
    }

    operator fun get(semanticId: String): Boolean? = conditionTruthBySemanticId[semanticId]
}

/**
 * Validated, immutable coverage package for one exercise.
 *
 * Construction fails closed on duplicate conditions/type codes, missing catalog type codes,
 * incomplete truth rows, or source provenance drift. This is intentionally separate from
 * [PoseExerciseSpec]: catalog coverage contains no threshold and cannot evaluate or cue a user.
 */
class AiHubExerciseCriterionCoverage internal constructor(
    val exercise: AiHubExercise,
    val provenance: AiHubCriterionSourceProvenance,
    criteria: List<AiHubCriterionDefinition>,
    typeTruthRows: List<AiHubTypeConditionTruth>,
    approvedCoverageSha256: String,
) {
    val criteria: List<AiHubCriterionDefinition> = immutableList(criteria)
    val typeTruthRows: List<AiHubTypeConditionTruth> = immutableList(typeTruthRows)

    private val criteriaBySemanticId: Map<String, AiHubCriterionDefinition>
    private val typeTruthByCode: Map<String, AiHubTypeConditionTruth>
    val coverageSha256: String

    val semanticIds: Set<String>
        get() = criteriaBySemanticId.keys

    init {
        require(provenance.catalogSha256 == AiHubExercise.CATALOG_SHA256) {
            "Criterion provenance must match the generated AI Hub catalog SHA-256"
        }
        require(provenance.exerciseId == exercise.id) {
            "Criterion provenance exercise id does not match the catalog exercise"
        }
        require(provenance.sourceExerciseName == exercise.displayName) {
            "Criterion provenance exercise name does not match the catalog exercise"
        }
        require(provenance.sourceTypeInfoType == exercise.typeInfoType) {
            "Criterion provenance type_info.type does not match the catalog exercise"
        }
        require(provenance.sourceRecordCount == exercise.recordCount) {
            "Criterion provenance record count does not match the catalog exercise"
        }
        require(this.criteria.isNotEmpty()) { "Exercise criterion coverage must not be empty" }

        criteriaBySemanticId = immutableMap(
            this.criteria.associateBy(AiHubCriterionDefinition::semanticId),
        )
        require(criteriaBySemanticId.size == this.criteria.size) {
            "Semantic criterion ids must be unique within an exercise"
        }
        require(this.criteria.map(AiHubCriterionDefinition::sourceCondition).toSet().size ==
            this.criteria.size) {
            "Source condition strings must be unique within an exercise"
        }
        require(this.criteria.all { it.releaseState == AiHubCriterionReleaseState.CATALOG_ONLY }) {
            "The AI Hub catalog registry must remain non-executable catalog coverage"
        }

        typeTruthByCode = immutableMap(
            this.typeTruthRows.associateBy(AiHubTypeConditionTruth::typeCode),
        )
        require(typeTruthByCode.size == this.typeTruthRows.size) {
            "AI Hub type codes must be unique within an exercise"
        }
        require(typeTruthByCode.keys == exercise.typeCodes.toSet()) {
            val missing = exercise.typeCodes.toSet() - typeTruthByCode.keys
            val unexpected = typeTruthByCode.keys - exercise.typeCodes.toSet()
            "AI Hub type coverage must exactly match the generated catalog; " +
                "missing=${missing.sorted()}, unexpected=${unexpected.sorted()}"
        }
        require(this.typeTruthRows.all { it.conditionTruthBySemanticId.keys == semanticIds }) {
            "Every AI Hub type row must contain exactly every registered semantic criterion"
        }
        require(this.typeTruthRows.sumOf(AiHubTypeConditionTruth::recordCount) ==
            provenance.sourceRecordCount) {
            "AI Hub type record counts must sum to the provenance record count"
        }
        coverageSha256 = aiHubCoverageSha256(
            exercise = exercise,
            provenance = provenance,
            criteria = this.criteria,
            typeTruthRows = this.typeTruthRows,
        )
        require(AI_HUB_SHA256.matches(approvedCoverageSha256)) {
            "approvedCoverageSha256 must be a lowercase SHA-256"
        }
        require(coverageSha256 == approvedCoverageSha256) {
            "AI Hub criterion truth coverage differs from the independently approved source pin"
        }
    }

    fun criterion(semanticId: String): AiHubCriterionDefinition? =
        criteriaBySemanticId[semanticId]

    fun typeTruth(typeCode: String): AiHubTypeConditionTruth? = typeTruthByCode[typeCode]

    fun sourceCondition(semanticId: String): String? = criterion(semanticId)?.sourceCondition
}

/** Data-driven entry point; adding an exercise means adding another validated coverage package. */
object AiHubCriterionRegistry {
    /**
     * Independently reviewed catalog snapshot for this vertical slice.
     *
     * Do not derive this value from [AiHubExercise.CATALOG_SHA256]. A catalog regeneration must
     * fail coverage initialization until the squat source conditions and truth table are audited
     * again and this pin is explicitly approved.
     */
    const val BARBELL_SQUAT_APPROVED_CATALOG_SHA256: String =
        "fe4e3075a00212293c9ffd3df8f007bc3666e17af2526de3a8d570d052a4e29c"
    const val BARBELL_SQUAT_APPROVED_COVERAGE_SHA256: String =
        "1f6ab0ea0981c6d1ef693ace7e72608a2e9af363b4b52f789a1749f92dae9cb5"

    const val SPINE_NEUTRAL_ID: String = "aihub.condition.spine-neutral.v1"
    const val HEAD_FACING_FORWARD_ID: String = "aihub.condition.head-facing-forward.v1"
    const val KNEE_FOOT_DIRECTION_ALIGNED_ID: String =
        "aihub.condition.knee-foot-direction-aligned.v1"
    const val PLANTAR_GROUND_CONTACT_FIXED_ID: String =
        "aihub.condition.plantar-ground-contact-fixed.v1"

    private val activeSquatPhases = setOf(
        AiHubCriterionPhase.SETUP,
        AiHubCriterionPhase.DESCENT,
        AiHubCriterionPhase.BOTTOM,
        AiHubCriterionPhase.ASCENT,
        AiHubCriterionPhase.TOP,
    )
    private val cameraPoseCapabilities = setOf(
        CriterionCapability.POSE_2D,
        CriterionCapability.POSE_WORLD_RELATIVE,
        CriterionCapability.TEMPORAL_POSE,
        CriterionCapability.PRIMARY_PERSON_LOCK,
        CriterionCapability.VIEW_QUALIFIED,
    )

    private val barbellSquatCriteria = listOf(
        AiHubCriterionDefinition(
            semanticId = SPINE_NEUTRAL_ID,
            sourceCondition = "척추의 중립",
            observability = CriterionObservability.PROXY_UNVALIDATED,
            requiredCapabilities = cameraPoseCapabilities,
            eligiblePhases = activeSquatPhases,
            sideScope = AiHubCriterionSideScope.MIDLINE,
            eligibleViews = setOf(
                AiHubCriterionView.FRONT_OBLIQUE_FULL_BODY,
                AiHubCriterionView.LATERAL_FULL_BODY,
            ),
            releaseState = AiHubCriterionReleaseState.CATALOG_ONLY,
            unsupportedReason =
                "MediaPipe 희소 관절은 요추 중립을 직접 관측하지 못하며 " +
                    "AI Hub 바벨 스쿼트 원본 이미지 기반 Gold 보정이 없다.",
        ),
        AiHubCriterionDefinition(
            semanticId = HEAD_FACING_FORWARD_ID,
            sourceCondition = "고개 정면",
            observability = CriterionObservability.PROXY_UNVALIDATED,
            requiredCapabilities = cameraPoseCapabilities,
            eligiblePhases = activeSquatPhases,
            sideScope = AiHubCriterionSideScope.MIDLINE,
            eligibleViews = setOf(AiHubCriterionView.FRONT_FULL_BODY),
            releaseState = AiHubCriterionReleaseState.CATALOG_ONLY,
            unsupportedReason =
                "얼굴 관절 정렬은 머리 방향의 근사값일 뿐 시선을 증명하지 못하며 " +
                    "AI Hub 바벨 스쿼트 원본 이미지 기반 Gold 보정이 없다.",
        ),
        AiHubCriterionDefinition(
            semanticId = KNEE_FOOT_DIRECTION_ALIGNED_ID,
            sourceCondition = "발과 무릎의 방향 일치",
            observability = CriterionObservability.PROXY_UNVALIDATED,
            requiredCapabilities = cameraPoseCapabilities,
            eligiblePhases = activeSquatPhases,
            sideScope = AiHubCriterionSideScope.BILATERAL,
            eligibleViews = setOf(
                AiHubCriterionView.FRONT_FULL_BODY,
                AiHubCriterionView.FRONT_OBLIQUE_FULL_BODY,
            ),
            releaseState = AiHubCriterionReleaseState.CATALOG_ONLY,
            unsupportedReason =
                "화면 투영 정렬은 실제 무릎 관절축과 발 진행각의 3D 관계를 직접 " +
                    "측정하지 못하며 MediaPipe Gold 보정이 없다.",
        ),
        AiHubCriterionDefinition(
            semanticId = PLANTAR_GROUND_CONTACT_FIXED_ID,
            sourceCondition = "발바닥 지면 고정",
            observability = CriterionObservability.NOT_OBSERVABLE,
            requiredCapabilities = cameraPoseCapabilities + CriterionCapability.GROUND_PROXY,
            eligiblePhases = activeSquatPhases,
            sideScope = AiHubCriterionSideScope.BILATERAL,
            eligibleViews = setOf(
                AiHubCriterionView.FRONT_OBLIQUE_FULL_BODY,
                AiHubCriterionView.LATERAL_FULL_BODY,
            ),
            releaseState = AiHubCriterionReleaseState.CATALOG_ONLY,
            unsupportedReason =
                "카메라 관절 좌표로 실제 발바닥 접촉과 압력 분포를 관측할 수 없다. " +
                    "향후 GROUND_PROXY는 들림·미끄러짐만 근사해야 한다.",
        ),
    )

    private val barbellSquatSemanticOrder = listOf(
        SPINE_NEUTRAL_ID,
        HEAD_FACING_FORWARD_ID,
        KNEE_FOOT_DIRECTION_ALIGNED_ID,
        PLANTAR_GROUND_CONTACT_FIXED_ID,
    )

    private val barbellSquatCoverage = AiHubExerciseCriterionCoverage(
        exercise = AiHubExercise.BARBELL_SQUAT,
        provenance = AiHubCriterionSourceProvenance(
            catalogArtifactPath = "docs/aihub-exercise-catalog.json",
            catalogSha256 = BARBELL_SQUAT_APPROVED_CATALOG_SHA256,
            catalogSchemaVersion = 1,
            exerciseId = "barbell-squat",
            sourceExerciseName = "바벨 스쿼트",
            sourceTypeInfoType = "바벨/덤벨",
            sourceRecordCount = 720,
        ),
        criteria = barbellSquatCriteria,
        typeTruthRows = listOf(
            barbellSquatTypeTruth("313", "1111"),
            barbellSquatTypeTruth("314", "0111"),
            barbellSquatTypeTruth("315", "1011"),
            barbellSquatTypeTruth("316", "1101"),
            barbellSquatTypeTruth("317", "1110"),
            barbellSquatTypeTruth("318", "0011"),
            barbellSquatTypeTruth("319", "0101"),
            barbellSquatTypeTruth("320", "0110"),
            barbellSquatTypeTruth("321", "1001"),
            barbellSquatTypeTruth("322", "1010"),
            barbellSquatTypeTruth("323", "1100"),
            barbellSquatTypeTruth("324", "1000"),
            barbellSquatTypeTruth("325", "0100"),
            barbellSquatTypeTruth("326", "0010"),
            barbellSquatTypeTruth("327", "0001"),
            barbellSquatTypeTruth("328", "0000"),
        ),
        approvedCoverageSha256 = BARBELL_SQUAT_APPROVED_COVERAGE_SHA256,
    )

    private val coverages: List<AiHubExerciseCriterionCoverage> =
        immutableList(listOf(barbellSquatCoverage))
    private val coverageByExercise: Map<AiHubExercise, AiHubExerciseCriterionCoverage> =
        immutableMap(coverages.associateBy(AiHubExerciseCriterionCoverage::exercise))

    val registeredExercises: Set<AiHubExercise> = immutableSet(coverageByExercise.keys)

    init {
        require(coverageByExercise.size == coverages.size) {
            "Each exercise may have only one AI Hub criterion coverage package"
        }
    }

    fun coverage(exercise: AiHubExercise): AiHubExerciseCriterionCoverage? =
        coverageByExercise[exercise]

    private fun barbellSquatTypeTruth(
        typeCode: String,
        truthBits: String,
    ): AiHubTypeConditionTruth {
        require(truthBits.length == barbellSquatSemanticOrder.size &&
            truthBits.all { it == '0' || it == '1' }) {
            "Barbell squat truth vector must contain exactly four binary values"
        }
        return AiHubTypeConditionTruth(
            typeCode = typeCode,
            recordCount = 45,
            conditionTruthBySemanticId = barbellSquatSemanticOrder
                .zip(truthBits.map { it == '1' })
                .toMap(LinkedHashMap()),
        )
    }
}

internal fun aiHubCoverageSha256(
    exercise: AiHubExercise,
    provenance: AiHubCriterionSourceProvenance,
    criteria: List<AiHubCriterionDefinition>,
    typeTruthRows: List<AiHubTypeConditionTruth>,
): String {
    val fields = mutableListOf(
        "schemaVersion" to "1",
        "exerciseId" to exercise.id,
        "catalogSha256" to provenance.catalogSha256,
        "criterionCount" to criteria.size.toString(),
    )
    criteria.forEachIndexed { index, criterion ->
        fields += "criterion[$index].semanticId" to criterion.semanticId
        fields += "criterion[$index].sourceCondition" to criterion.sourceCondition
    }
    fields += "typeCount" to typeTruthRows.size.toString()
    typeTruthRows.forEachIndexed { index, row ->
        fields += "type[$index].code" to row.typeCode
        fields += "type[$index].recordCount" to row.recordCount.toString()
        fields += "type[$index].truthBits" to criteria.joinToString(separator = "") { criterion ->
            when (row.conditionTruthBySemanticId[criterion.semanticId]) {
                true -> "1"
                false -> "0"
                null -> "?"
            }
        }
    }
    return canonicalFieldsSha256(fields)
}

private fun <T> immutableList(source: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))

private fun <T> immutableSet(source: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(source))

private fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))
