package com.example.trex_kotlin.pose.criterion

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.contract.PoseQualityCalibrationArtifact
import com.example.trex_kotlin.pose.contract.PoseQualityCalibrationKnot
import com.example.trex_kotlin.pose.contract.PoseQualitySignalKind
import com.example.trex_kotlin.pose.feature.FeatureUnknownReason
import com.example.trex_kotlin.pose.feature.PoseFeatureEngine
import com.example.trex_kotlin.pose.feature.PoseScalarFeatureSpec
import com.example.trex_kotlin.pose.feature.measure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseCriterionSamplerTest {
    private val feature = PoseScalarFeatureSpec.JointAngle(
        featureContractId = "knee-flexion.world.v1",
        coordinateSpace = PoseCoordinateSpace.WORLD,
        first = PoseJoint.LEFT_HIP,
        vertex = PoseJoint.LEFT_KNEE,
        third = PoseJoint.LEFT_ANKLE,
    )

    @Test
    fun dataDefinedFeatureUsesTheExplicitCoordinateDomain() {
        val normalizedFeature = feature.copy(
            featureContractId = "knee-flexion.image.v1",
            coordinateSpace = PoseCoordinateSpace.NORMALIZED_IMAGE,
        )
        val frame = frame(
            timestampMs = 10L,
            normalized = angleLandmarks(90.0),
            world = angleLandmarks(180.0),
        )
        val engine = PoseFeatureEngine(minimumConfidence = 0.0)

        assertEquals(90.0, engine.measure(frame, normalizedFeature).value!!, 1e-9)
        assertEquals(180.0, engine.measure(frame, feature).value!!, 1e-9)
    }

    @Test
    fun samplerUsesCalibratedWeightInsteadOfRawMediaPipeConfidence() {
        val calibration = qualityCalibration(
            knots = listOf(
                PoseQualityCalibrationKnot(0.0, 0.2),
                PoseQualityCalibrationKnot(0.8, 0.64),
            ),
        )
        val sampler = PoseCriterionSampler(PoseFeatureEngine(minimumConfidence = 0.6))
        val result = sampler.sample(
            frame = frame(100L, world = angleLandmarks(90.0, confidence = 0.8)),
            binding = binding(qualityCalibration = calibration),
        )

        assertEquals(90.0, result.evidence.measurement!!, 1e-9)
        assertEquals(0.64, result.evidence.qualityWeight, 1e-9)
        assertEquals(0.8, result.rawConfidence, 1e-9)
        assertTrue(result.qualityCalibrated)
        assertNull(result.featureUnknownReason)
        assertNull(result.qualityUnknownReason)
    }

    @Test
    fun missingFeatureAndQualityAbstentionProduceNoEvidence() {
        val calibration = qualityCalibration(
            knots = listOf(PoseQualityCalibrationKnot(0.9, 1.0)),
        )
        val sampler = PoseCriterionSampler(PoseFeatureEngine(minimumConfidence = 0.6))

        val missing = sampler.sample(frame(100L), binding(qualityCalibration = calibration))
        assertNull(missing.evidence.measurement)
        assertEquals(0.0, missing.evidence.qualityWeight, 0.0)
        assertEquals(FeatureUnknownReason.MISSING_JOINT, missing.featureUnknownReason)
        assertNull(missing.qualityUnknownReason)

        val abstained = sampler.sample(
            frame(200L, world = angleLandmarks(90.0, confidence = 0.8)),
            binding(qualityCalibration = calibration),
        )
        assertNull(abstained.evidence.measurement)
        assertEquals(0.0, abstained.evidence.qualityWeight, 0.0)
        assertFalse(abstained.qualityCalibrated)
        assertEquals(
            CriterionQualityUnknownReason.CALIBRATOR_ABSTAINED,
            abstained.qualityUnknownReason,
        )
    }

    @Test
    fun bindingRejectsContractDriftAndMissingPersonLock() {
        assertThrows(IllegalArgumentException::class.java) {
            binding(
                feature = feature.copy(featureContractId = "different-feature.v1"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding(
                spec = criterionSpec(
                    capabilities = setOf(CriterionCapability.POSE_WORLD_RELATIVE),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding(
                spec = criterionSpec(),
                qualityCalibration = qualityCalibration(
                    qualityContractId = "different-quality.v1",
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding(
                spec = criterionSpec(),
                qualityCalibration = qualityCalibration(
                    knots = listOf(PoseQualityCalibrationKnot(0.0, 0.5)),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding(
                qualityCalibration = qualityCalibration(
                    signalKind = PoseQualitySignalKind.PHASE_GATE_SIGNAL,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding(
                feature = feature.copy(first = PoseJoint.RIGHT_HIP),
            )
        }
    }

    @Test
    fun calibrationArtifactRejectsInvalidTablesAndDeterministicallyAbstains() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                PoseQualityCalibrationKnot(0.5, invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            qualityCalibration(
                knots = listOf(
                    PoseQualityCalibrationKnot(0.5, 0.4),
                    PoseQualityCalibrationKnot(0.5, 0.8),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            qualityCalibration(
                knots = listOf(
                    PoseQualityCalibrationKnot(0.5, 0.8),
                    PoseQualityCalibrationKnot(0.8, 0.4),
                ),
            )
        }

        val calibration = qualityCalibration(
            knots = listOf(
                PoseQualityCalibrationKnot(0.8, 0.9),
                PoseQualityCalibrationKnot(0.5, 0.4),
            ),
        )
        assertNull(calibration.calibratedSignal(0.49))
        assertEquals(0.4, calibration.calibratedSignal(0.79)!!, 0.0)
        assertEquals(0.9, calibration.calibratedSignal(0.8)!!, 0.0)
        assertNull(calibration.calibratedSignal(Double.NaN))
        val canonicalOrder = qualityCalibration(
            knots = listOf(
                PoseQualityCalibrationKnot(0.5, 0.4),
                PoseQualityCalibrationKnot(0.8, 0.9),
            ),
        )
        assertEquals(calibration.artifactSha256, canonicalOrder.artifactSha256)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (calibration.knots as MutableList<PoseQualityCalibrationKnot>).clear()
        }
    }

    @Test
    fun canonicalFeatureShaPinsTheEntireFeatureAstAndReachesTheCalibrator() {
        assertTrue(feature.featureSpecSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(feature.featureSpecSha256, feature.copy().featureSpecSha256)
        assertFalse(
            feature.featureSpecSha256 ==
                feature.copy(first = PoseJoint.RIGHT_HIP).featureSpecSha256,
        )
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (feature.requiredJoints as MutableSet<PoseJoint>).clear()
        }

        val calibration = qualityCalibration()
        val result = PoseCriterionSampler(PoseFeatureEngine(minimumConfidence = 0.0)).sample(
            frame(100L, world = angleLandmarks(90.0)),
            binding(qualityCalibration = calibration),
        )
        assertTrue(result.qualityCalibrated)
        assertEquals(feature.featureSpecSha256, calibration.featureSpecSha256)
    }

    @Test
    fun featureSpecRejectsAmbiguousGeometryAndMixedDifferenceDomains() {
        assertThrows(IllegalArgumentException::class.java) {
            PoseScalarFeatureSpec.JointAngle(
                featureContractId = "invalid angle",
                coordinateSpace = PoseCoordinateSpace.WORLD,
                first = PoseJoint.LEFT_HIP,
                vertex = PoseJoint.LEFT_KNEE,
                third = PoseJoint.LEFT_ANKLE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PoseScalarFeatureSpec.NormalizedDistance(
                featureContractId = "distance.v1",
                coordinateSpace = PoseCoordinateSpace.WORLD,
                first = PoseJoint.LEFT_HIP,
                second = PoseJoint.LEFT_HIP,
                scaleStart = PoseJoint.LEFT_SHOULDER,
                scaleEnd = PoseJoint.RIGHT_SHOULDER,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PoseScalarFeatureSpec.Difference(
                featureContractId = "mixed-difference.v1",
                first = feature,
                second = feature.copy(
                    featureContractId = "knee-flexion.image.v1",
                    coordinateSpace = PoseCoordinateSpace.NORMALIZED_IMAGE,
                ),
                absolute = true,
            )
        }
    }

    private fun binding(
        spec: PoseCriterionSpec? = null,
        feature: PoseScalarFeatureSpec = this.feature,
        qualityCalibration: PoseQualityCalibrationArtifact = qualityCalibration(
            featureSpecSha256 = feature.featureSpecSha256,
        ),
    ): PoseCriterionFeatureBinding = PoseCriterionFeatureBinding(
        spec ?: criterionSpec(qualityCalibration = qualityCalibration),
        feature,
        qualityCalibration,
    )

    private fun criterionSpec(
        capabilities: Set<CriterionCapability> = setOf(
            CriterionCapability.POSE_WORLD_RELATIVE,
            CriterionCapability.PRIMARY_PERSON_LOCK,
            CriterionCapability.VIEW_QUALIFIED,
        ),
        qualityCalibration: PoseQualityCalibrationArtifact = qualityCalibration(),
    ): PoseCriterionSpec {
        val contract = CriterionCalibrationContract(
            criterionId = "left-knee-flexion",
            featureContractId = feature.featureContractId,
            featureSpecSha256 = feature.featureSpecSha256,
            measurementUnit = "degrees",
            aggregation = CriterionAggregation.WeightedMean,
            qualityContractId = qualityCalibration.qualityContractId,
            qualityCalibrationArtifactSha256 = qualityCalibration.artifactSha256,
            runtimeDomainId = qualityCalibration.runtimeDomainId,
            validMeasurementInterval = MeasurementInterval(0.0, 180.0),
            minimumSampleQuality = 0.5,
            minimumTimeCoverage = 0.5,
            minimumEvidenceMass = 0.5,
            minimumObservableDurationMs = 100L,
            minimumEffectiveSamples = 1.0,
            maximumGapMs = 200L,
            correlationHorizonMs = 100L,
            contractVersion = 1,
        )
        val calibration = CriterionAggregateCalibration(contract, MeasurementInterval(-2.0, 3.0))
        val target = MeasurementInterval(80.0, 110.0)
        return PoseCriterionSpec(
            calibrationContract = contract,
            approvedCalibrationArtifactSha256 = calibration.artifactSha256,
            approvedEvaluatorSpecSha256 = evaluatorSpecSha256(
                approvedCalibrationArtifactSha256 = calibration.artifactSha256,
                targetInterval = target,
                requiredCapabilities = capabilities,
            ),
            targetInterval = target,
            requiredCapabilities = capabilities,
        )
    }

    private fun qualityCalibration(
        qualityContractId: String = "mediapipe-confidence-to-knee-error.v1",
        featureSpecSha256: String = feature.featureSpecSha256,
        runtimeDomainId: String = "mediapipe-full.world.side-view.v1",
        signalKind: PoseQualitySignalKind = PoseQualitySignalKind.CRITERION_EVIDENCE_WEIGHT,
        knots: Collection<PoseQualityCalibrationKnot> = listOf(
            PoseQualityCalibrationKnot(0.0, 1.0),
        ),
    ): PoseQualityCalibrationArtifact = PoseQualityCalibrationArtifact(
        signalKind = signalKind,
        featureSpecSha256 = featureSpecSha256,
        qualityContractId = qualityContractId,
        runtimeDomainId = runtimeDomainId,
        knots = knots,
    )

    private fun frame(
        timestampMs: Long,
        normalized: Map<PoseJoint, PoseLandmark> = emptyMap(),
        world: Map<PoseJoint, PoseLandmark> = emptyMap(),
    ): PoseFrame = PoseFrame(
        timestampMs = timestampMs,
        landmarks = normalized,
        worldLandmarks = world,
        imageWidth = 1_000,
        imageHeight = 1_000,
    )

    private fun angleLandmarks(
        angleDegrees: Double,
        confidence: Double = 1.0,
    ): Map<PoseJoint, PoseLandmark> {
        val radians = Math.toRadians(angleDegrees)
        return mapOf(
            PoseJoint.LEFT_HIP to landmark(1.0, 0.0, confidence),
            PoseJoint.LEFT_KNEE to landmark(0.0, 0.0, confidence),
            PoseJoint.LEFT_ANKLE to landmark(
                kotlin.math.cos(radians),
                kotlin.math.sin(radians),
                confidence,
            ),
        )
    }

    private fun landmark(x: Double, y: Double, confidence: Double): PoseLandmark = PoseLandmark(
        x = x,
        y = y,
        visibility = confidence,
        presence = confidence,
    )
}
