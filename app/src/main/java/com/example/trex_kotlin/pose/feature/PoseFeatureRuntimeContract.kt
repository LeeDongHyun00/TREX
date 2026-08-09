package com.example.trex_kotlin.pose.feature

import com.example.trex_kotlin.pose.contract.canonicalFieldsSha256

/**
 * Decision-relevant semantics shared by every data-defined pose feature.
 *
 * Any implementation change to these policies requires a version/policy change here. Because the
 * resulting hash is embedded in every feature AST, old calibration artifacts then stop matching.
 */
object PoseFeaturePrimitiveContract {
    const val version: Int = 1
    const val degeneracyEpsilon: Double = 1e-9
    const val normalizedImageProjectionPolicyId: String =
        "x-times-image-aspect.y-unchanged.z-zero.v1"
    const val includedAnglePolicyId: String = "atan2-cross-norm-dot.degrees-0-to-180.v1"

    val sha256: String = canonicalFieldsSha256(
        listOf(
            "featurePrimitiveContractSchemaVersion" to "1",
            "primitiveContractVersion" to version.toString(),
            "degeneracyEpsilon" to java.lang.Double.toHexString(degeneracyEpsilon),
            "normalizedImageProjectionPolicyId" to normalizedImageProjectionPolicyId,
            "includedAnglePolicyId" to includedAnglePolicyId,
        ),
    )
}

/** Raw-confidence gating is runtime policy and is signed separately from feature geometry. */
internal fun featureRuntimeContractSha256(minimumConfidence: Double): String =
    canonicalFieldsSha256(
        listOf(
            "featureRuntimeContractSchemaVersion" to "1",
            "featurePrimitiveContractSha256" to PoseFeaturePrimitiveContract.sha256,
            "minimumRawConfidence" to java.lang.Double.toHexString(minimumConfidence),
        ),
    )

/** Signed criterion/phase packages let the immutable quality artifact own confidence abstention. */
internal const val SIGNED_FEATURE_MINIMUM_CONFIDENCE: Double = 0.0

internal val SIGNED_FEATURE_RUNTIME_CONTRACT_SHA256: String =
    featureRuntimeContractSha256(SIGNED_FEATURE_MINIMUM_CONFIDENCE)
