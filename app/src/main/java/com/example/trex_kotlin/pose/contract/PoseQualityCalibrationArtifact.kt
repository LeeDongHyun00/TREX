package com.example.trex_kotlin.pose.contract

import java.util.Collections

private val QUALITY_SHA256 = Regex("^[0-9a-f]{64}$")

/** A calibrated signal cannot be reused across criterion weighting and phase gating. */
enum class PoseQualitySignalKind {
    CRITERION_EVIDENCE_WEIGHT,
    PHASE_GATE_SIGNAL,
}

/** One inclusive lower-bound cell in a monotone raw-confidence lookup table. */
data class PoseQualityCalibrationKnot(
    val minimumRawConfidence: Double,
    val calibratedSignal: Double,
) {
    init {
        require(minimumRawConfidence.isFinite() && minimumRawConfidence in 0.0..1.0) {
            "minimumRawConfidence must be finite and in [0, 1]"
        }
        require(calibratedSignal.isFinite() && calibratedSignal in 0.0..1.0) {
            "calibratedSignal must be finite and in [0, 1]"
        }
    }
}

/**
 * Immutable, canonical quality-calibration artifact evaluated as a lower-bound step table.
 *
 * Input order is irrelevant: knots are copied and sorted by [PoseQualityCalibrationKnot.minimumRawConfidence].
 * For a supported raw confidence, the value from the greatest lower-bound knot is returned.
 * Values below the first knot (and invalid runtime values) deterministically abstain with `null`.
 */
class PoseQualityCalibrationArtifact(
    val signalKind: PoseQualitySignalKind,
    val featureSpecSha256: String,
    val qualityContractId: String,
    val runtimeDomainId: String,
    knots: Collection<PoseQualityCalibrationKnot>,
) {
    val knots: List<PoseQualityCalibrationKnot> = Collections.unmodifiableList(
        ArrayList(knots.sortedBy(PoseQualityCalibrationKnot::minimumRawConfidence)),
    )

    init {
        require(QUALITY_SHA256.matches(featureSpecSha256)) {
            "featureSpecSha256 must be a lowercase SHA-256 value"
        }
        require(qualityContractId.isNotBlank()) { "qualityContractId must not be blank" }
        require(runtimeDomainId.isNotBlank()) { "runtimeDomainId must not be blank" }
        require(this.knots.isNotEmpty()) { "A quality calibration requires at least one knot" }
        require(
            this.knots.zipWithNext().all { (left, right) ->
                left.minimumRawConfidence < right.minimumRawConfidence
            },
        ) {
            "Quality calibration knot thresholds must be unique"
        }
        require(
            this.knots.zipWithNext().all { (left, right) ->
                left.calibratedSignal <= right.calibratedSignal
            },
        ) {
            "Calibrated signal must not decrease as raw confidence increases"
        }
    }

    val artifactSha256: String = canonicalFieldsSha256(
        buildList {
            add("qualityCalibrationArtifactSchemaVersion" to "1")
            add("signalKind" to signalKind.name)
            add("featureSpecSha256" to featureSpecSha256)
            add("qualityContractId" to qualityContractId)
            add("runtimeDomainId" to runtimeDomainId)
            add("knotCount" to this@PoseQualityCalibrationArtifact.knots.size.toString())
            this@PoseQualityCalibrationArtifact.knots.forEachIndexed { index, knot ->
                add(
                    "knot[$index].minimumRawConfidence" to
                        java.lang.Double.toHexString(knot.minimumRawConfidence),
                )
                add(
                    "knot[$index].calibratedSignal" to
                        java.lang.Double.toHexString(knot.calibratedSignal),
                )
            }
        },
    )

    fun calibratedSignal(rawConfidence: Double): Double? {
        if (!rawConfidence.isFinite() || rawConfidence !in 0.0..1.0) return null
        if (rawConfidence < knots.first().minimumRawConfidence) return null

        var lower = 0
        var upper = knots.lastIndex
        while (lower < upper) {
            val middle = (lower + upper + 1) ushr 1
            if (knots[middle].minimumRawConfidence <= rawConfidence) {
                lower = middle
            } else {
                upper = middle - 1
            }
        }
        return knots[lower].calibratedSignal
    }
}
