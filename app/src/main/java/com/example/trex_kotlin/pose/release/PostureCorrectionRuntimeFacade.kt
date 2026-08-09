package com.example.trex_kotlin.pose.release

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyCatalog
import com.example.trex_kotlin.pose.policy.AiHubCriterionReviewState
import java.util.Collections

/** Product lifecycle of posture correction for one canonical AI Hub exercise. */
enum class PostureCorrectionLifecycle {
    /** The exercise has no source criterion coverage. */
    UNSUPPORTED,

    /** Source criteria are inventoried, but none is authorized for runtime use. */
    CATALOG_ONLY,

    /** Authorized criteria may run only in a non-user-facing shadow pipeline. */
    SHADOW,

    /** Authorized criteria may run for users who explicitly opt in. */
    OPT_IN_BETA,

    /** Authorized criteria may run in the generally available product. */
    GA,
}

/**
 * Immutable provenance for the policy inventory used to calculate availability.
 *
 * These hashes are repository drift pins. They are not signatures, remote attestation, expert
 * approval, clinical approval, or runtime release authorization.
 */
class PostureCorrectionPolicyProvenance internal constructor(
    val sourceCatalogSha256: String,
    val sourceCoverageArtifactSha256: String,
    val sourceMetadataSetSha256: String,
    val policySha256: String,
    val policyRegistrySha256: String,
)

/**
 * Read-only product availability for one exercise.
 *
 * [reviewedCriterionCount] means engineering taxonomy review only. It does not mean that the
 * criterion has been calibrated or authorized to produce PASS/FAIL, a score, or a cue.
 */
class PostureCorrectionAvailability internal constructor(
    val exercise: AiHubExercise,
    val lifecycle: PostureCorrectionLifecycle,
    val catalogCriterionCount: Int,
    val reviewedCriterionCount: Int,
    val releasedCriterionCount: Int,
    val policyProvenance: PostureCorrectionPolicyProvenance,
) {
    /** Whether the normal product UI may offer posture correction for this exercise. */
    val userSelectable: Boolean = lifecycle == PostureCorrectionLifecycle.OPT_IN_BETA ||
        lifecycle == PostureCorrectionLifecycle.GA

    /** Whether a user-facing correction session may be opened. Shadow execution is excluded. */
    val sessionOpenAllowed: Boolean = userSelectable

    init {
        require(catalogCriterionCount >= 0)
        require(reviewedCriterionCount in 0..catalogCriterionCount)
        require(releasedCriterionCount in 0..reviewedCriterionCount)
        when (lifecycle) {
            PostureCorrectionLifecycle.UNSUPPORTED -> require(
                catalogCriterionCount == 0 &&
                    reviewedCriterionCount == 0 &&
                    releasedCriterionCount == 0,
            )

            PostureCorrectionLifecycle.CATALOG_ONLY -> require(
                catalogCriterionCount > 0 && releasedCriterionCount == 0,
            )

            PostureCorrectionLifecycle.SHADOW,
            PostureCorrectionLifecycle.OPT_IN_BETA,
            PostureCorrectionLifecycle.GA,
            -> require(releasedCriterionCount > 0)
        }
    }
}

/**
 * Sole app-facing authority for posture-correction product availability.
 *
 * The current app bundle deliberately contains no runtime release authorization. The empty list
 * below is trusted only through ordinary source review of the app bundle; it does not authenticate
 * any policy or calibration artifact. Its `Nothing` element type is intentional: adding even a
 * shadow release requires first introducing a hash-bound authorization contract that pins the
 * exact source binding, policy, criterion, exercise spec, calibration, observer, view, and cue
 * artifacts. An exercise id or boolean flag alone must never become a release credential.
 *
 * This facade exposes availability only. Evaluation, PASS/FAIL, scoring, and cues belong behind a
 * separately attested runtime boundary and are intentionally absent from this public API.
 */
object PostureCorrectionRuntimeFacade {
    private const val EXPECTED_EXERCISE_COUNT = 41
    private const val EXPECTED_BINDING_COUNT = 167
    private const val EXPECTED_ENGINEERING_REVIEW_COUNT = 148

    /** Drift identity for the empty bundled allowlist; this is explicitly not a signature. */
    val releaseAllowlistArtifactSha256: String = BundledPostureReleaseAllowlist.artifactSha256

    val policyProvenance: PostureCorrectionPolicyProvenance =
        PostureCorrectionPolicyProvenance(
            sourceCatalogSha256 = AiHubCriterionPolicyCatalog.SOURCE_CATALOG_SHA256,
            sourceCoverageArtifactSha256 =
                AiHubCriterionPolicyCatalog.SOURCE_COVERAGE_ARTIFACT_SHA256,
            sourceMetadataSetSha256 = AiHubCriterionPolicyCatalog.SOURCE_METADATA_SET_SHA256,
            policySha256 = AiHubCriterionPolicyCatalog.POLICY_SHA256,
            policyRegistrySha256 = AiHubCriterionPolicyCatalog.REGISTRY_SHA256,
        )

    private val availabilityByExercise: Map<AiHubExercise, PostureCorrectionAvailability> =
        immutableMap(
            AiHubExercise.entries.associateWith { exercise ->
                val bindings = AiHubCriterionPolicyCatalog.bindings(exercise)
                val reviewedCount = bindings.count { binding ->
                    binding.reviewState == AiHubCriterionReviewState.REVIEWED_ENGINEERING_V1
                }
                // Release counts are always resolved for the exact exercise key. A future
                // non-empty allowlist must not accidentally advertise one authorized binding on
                // every exercise by reusing a global entry count.
                val releasedCount = BundledPostureReleaseAllowlist.entryCount(exercise)
                val lifecycle = when {
                    bindings.isEmpty() -> PostureCorrectionLifecycle.UNSUPPORTED
                    releasedCount == 0 -> PostureCorrectionLifecycle.CATALOG_ONLY
                    else -> error("A non-empty release requires a hash-bound authorization schema")
                }
                PostureCorrectionAvailability(
                    exercise = exercise,
                    lifecycle = lifecycle,
                    catalogCriterionCount = bindings.size,
                    reviewedCriterionCount = reviewedCount,
                    releasedCriterionCount = releasedCount,
                    policyProvenance = policyProvenance,
                )
            },
        )

    /** Every canonical exercise that has at least one exact AI Hub source criterion binding. */
    val catalogedExercises: Set<AiHubExercise> = immutableSet(
        availabilityByExercise.values
            .filter { it.catalogCriterionCount > 0 }
            .map(PostureCorrectionAvailability::exercise),
    )

    /** Stable exercise-id ordered availability inventory for product UI and diagnostics. */
    val availabilities: List<PostureCorrectionAvailability> = immutableList(
        availabilityByExercise.values.sortedBy { it.exercise.id },
    )

    /** Exercises the normal product UI may currently offer for posture correction. */
    val userSelectableExercises: Set<AiHubExercise> = immutableSet(
        availabilityByExercise.values
            .filter(PostureCorrectionAvailability::userSelectable)
            .map(PostureCorrectionAvailability::exercise),
    )

    init {
        check(BundledPostureReleaseAllowlist.entryCount == 0)
        check(availabilityByExercise.size == EXPECTED_EXERCISE_COUNT)
        check(catalogedExercises.size == EXPECTED_EXERCISE_COUNT)
        check(availabilities.sumOf { it.catalogCriterionCount } == EXPECTED_BINDING_COUNT)
        check(
            availabilities.sumOf { it.reviewedCriterionCount } ==
                EXPECTED_ENGINEERING_REVIEW_COUNT,
        )
        check(availabilities.sumOf { it.releasedCriterionCount } == 0)
        check(userSelectableExercises.isEmpty())
    }

    fun availability(exercise: AiHubExercise): PostureCorrectionAvailability =
        requireNotNull(availabilityByExercise[exercise])
}

/**
 * App-bundled, code-reviewed empty allowlist.
 *
 * The canonical hash detects accidental drift and binds the empty state to the current policy;
 * it does not authenticate an issuer. A non-empty version requires a detached signature and a
 * pinned release public key before its schema may be introduced.
 */
private object BundledPostureReleaseAllowlist {
    private const val APPROVED_EMPTY_ARTIFACT_SHA256 =
        "699912f304933285ec9c832b32e59383b97ddb6ae77b879370e502718b1c4b31"
    private val entries: List<Nothing> = emptyList()

    val entryCount: Int
        get() = entries.size

    fun entryCount(exercise: AiHubExercise): Int {
        check(exercise in AiHubExercise.entries)
        return 0
    }

    val artifactSha256: String = canonicalFieldsSha256(
        listOf(
            "releaseAllowlistSchemaVersion" to "1",
            "artifactKind" to "TREX_POSE_RELEASE_ALLOWLIST",
            "authorityMode" to "NO_RELEASE_KEY_CONFIGURED",
            "policyRegistrySha256" to AiHubCriterionPolicyCatalog.REGISTRY_SHA256,
            "sourceCoverageArtifactSha256" to
                AiHubCriterionPolicyCatalog.SOURCE_COVERAGE_ARTIFACT_SHA256,
            "entryCount" to entryCount.toString(),
        ),
    )

    init {
        check(entries.isEmpty())
        check(artifactSha256 == APPROVED_EMPTY_ARTIFACT_SHA256) {
            "Bundled empty posture release allowlist drifted from its repository pin"
        }
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
