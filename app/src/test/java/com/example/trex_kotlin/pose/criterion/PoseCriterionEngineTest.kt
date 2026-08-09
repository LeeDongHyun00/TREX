package com.example.trex_kotlin.pose.criterion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PoseCriterionEngineTest {
    private val engine = PoseCriterionEngine()
    private val capability = CriterionCapability.POSE_2D
    private val defaultWindow = CriterionPhaseWindow(0L, 200L)

    @Test
    fun phaseAggregateCalibrationProducesPassAndDirectionalFailures() {
        assertResult(MeasurementInterval(-3.0, 3.0), CriterionState.PASS, null)
        assertResult(
            MeasurementInterval(-10.0, -6.0),
            CriterionState.FAIL,
            CriterionFailRegion.LOW_SIDE,
        )
        assertResult(
            MeasurementInterval(6.0, 10.0),
            CriterionState.FAIL,
            CriterionFailRegion.HIGH_SIDE,
        )
    }

    @Test
    fun calibrationIsAppliedAfterTheRegisteredPhaseAggregation() {
        val calibrationError = MeasurementInterval(1.0, 3.0)
        val result = evaluate(
            spec = spec(calibrationError = calibrationError),
            samples = listOf(sample(0L, 0.0), sample(100L, 10.0), sample(200L, 20.0)),
            calibration = calibration(calibrationError),
        )

        assertEquals(10.0, result.aggregatedMeasurement!!, 0.0)
        assertEquals(MeasurementInterval(11.0, 13.0), result.calibratedMeasurementInterval)
        assertEquals(CriterionState.FAIL, result.state)
        assertEquals(CriterionFailRegion.HIGH_SIDE, result.failRegion)
    }

    @Test
    fun touchingOrCrossingTargetBoundaryIsUnknownRatherThanDirectionalFailure() {
        listOf(
            MeasurementInterval(-6.0, -5.0),
            MeasurementInterval(5.0, 6.0),
            MeasurementInterval(-6.0, 0.0),
            MeasurementInterval(0.0, 6.0),
        ).forEach { errorInterval ->
            val result = evaluate(
                spec = spec(calibrationError = errorInterval),
                calibration = calibration(errorInterval),
            )
            assertEquals(CriterionState.UNKNOWN, result.state)
            assertEquals(CriterionUnknownReason.BOUNDARY_OVERLAP, result.unknownReason)
            assertNull(result.failRegion)
        }
    }

    @Test
    fun exactTargetIntervalPassesBecauseBoundsAreInclusive() {
        assertResult(MeasurementInterval(-5.0, 5.0), CriterionState.PASS, null)
    }

    @Test
    fun capabilityAndCalibrationGatesFailClosedWithConcreteReasons() {
        val spec = spec(
            requiredCapabilities = setOf(capability, CriterionCapability.TEMPORAL_POSE),
        )
        val missingCapability = evaluate(
            spec = spec,
            availableCapabilities = setOf(capability),
        )

        assertEquals(CriterionState.UNKNOWN, missingCapability.state)
        assertEquals(CriterionUnknownReason.MISSING_CAPABILITY, missingCapability.unknownReason)
        assertEquals(
            setOf(CriterionCapability.TEMPORAL_POSE),
            missingCapability.missingCapabilities,
        )

        val uncalibrated = evaluate(
            spec = spec,
            availableCapabilities = spec.requiredCapabilities,
            calibration = null,
        )
        assertEquals(CriterionState.UNKNOWN, uncalibrated.state)
        assertEquals(CriterionUnknownReason.UNCALIBRATED_DOMAIN, uncalibrated.unknownReason)

        val mismatchedCalibration = evaluate(
            spec = spec,
            availableCapabilities = spec.requiredCapabilities,
            calibration = calibration(
                contract = contract(runtimeDomainId = "different-runtime-domain:v1"),
            ),
        )
        assertEquals(CriterionState.UNKNOWN, mismatchedCalibration.state)
        assertEquals(
            CriterionUnknownReason.CALIBRATION_CONTRACT_MISMATCH,
            mismatchedCalibration.unknownReason,
        )
        assertNull(mismatchedCalibration.calibratedMeasurementInterval)

        val staleArtifact = calibration(
            error = MeasurementInterval(-1.0, 1.0),
            contract = spec.calibrationContract,
        )
        val artifactMismatch = evaluate(
            spec = spec,
            availableCapabilities = spec.requiredCapabilities,
            calibration = staleArtifact,
        )
        assertEquals(
            CriterionUnknownReason.CALIBRATION_ARTIFACT_MISMATCH,
            artifactMismatch.unknownReason,
        )
    }

    @Test
    fun timeCoverageAndQualityEvidenceMassRemainSeparate() {
        val partialObservation = evaluate(
            spec = spec(minimumTimeCoverage = 0.5, minimumEvidenceMass = 0.2),
            phaseWindow = CriterionPhaseWindow(0L, 300L),
            samples = listOf(
                sample(0L, 5.0),
                sample(100L, 5.0),
                CriterionEvidenceSample(200L, null, qualityWeight = 1.0),
                CriterionEvidenceSample(300L, null, qualityWeight = 1.0),
            ),
        )
        assertEquals(1.0 / 3.0, partialObservation.timeCoverage, 1e-12)
        assertEquals(1.0 / 3.0, partialObservation.evidenceMass, 1e-12)
        assertEquals(
            CriterionUnknownReason.INSUFFICIENT_TIME_COVERAGE,
            partialObservation.unknownReason,
        )

        val lowQuality = evaluate(
            spec = spec(minimumTimeCoverage = 1.0, minimumEvidenceMass = 0.5),
            samples = defaultSamples(quality = 0.2),
        )
        assertEquals(1.0, lowQuality.timeCoverage, 1e-12)
        assertEquals(0.2, lowQuality.evidenceMass, 1e-12)
        assertEquals(
            CriterionUnknownReason.INSUFFICIENT_EVIDENCE_MASS,
            lowQuality.unknownReason,
        )
    }

    @Test
    fun explicitPhaseBoundariesExposeLeadingAndTrailingGaps() {
        val result = evaluate(
            spec = spec(
                minimumTimeCoverage = 0.1,
                minimumEvidenceMass = 0.1,
                maximumGapMs = 300L,
            ),
            phaseWindow = CriterionPhaseWindow(0L, 1_000L),
            samples = listOf(sample(400L, 5.0), sample(500L, 5.0), sample(600L, 5.0)),
        )

        assertEquals(0.2, result.timeCoverage, 1e-12)
        assertEquals(400L, result.maximumEvidenceGapMs)
        assertEquals(CriterionUnknownReason.EXCESSIVE_GAP, result.unknownReason)
    }

    @Test
    fun durationAndEffectiveSampleGatesAreIndependent() {
        val short = evaluate(
            spec = spec(
                minimumTimeCoverage = 0.5,
                minimumEvidenceMass = 0.5,
                minimumObservableDurationMs = 250L,
                maximumGapMs = 200L,
            ),
            phaseWindow = CriterionPhaseWindow(0L, 300L),
            samples = listOf(sample(0L, 4.0), sample(100L, 5.0), sample(200L, 6.0)),
        )
        assertEquals(200.0, short.observableDurationMs, 0.0)
        assertEquals(
            CriterionUnknownReason.INSUFFICIENT_OBSERVABLE_DURATION,
            short.unknownReason,
        )

        val timestamps = (0L..2_900L step 100L).toList()
        val repeatedStaticPose = evaluate(
            spec = spec(
                minimumEffectiveSamples = 2.0,
                maximumGapMs = 200L,
                minimumObservableDurationMs = 1_000L,
                correlationHorizonMs = 5_000L,
            ),
            phaseWindow = CriterionPhaseWindow(0L, 2_900L),
            samples = timestamps.map { sample(it, 5.0) },
        )
        assertTrue(repeatedStaticPose.rawEffectiveSamples > 20.0)
        assertEquals(1.0, repeatedStaticPose.effectiveSamples, 0.0)
        assertEquals(5_000L, repeatedStaticPose.correlationHorizonMs)
        assertEquals(
            CriterionUnknownReason.INSUFFICIENT_EFFECTIVE_SAMPLES,
            repeatedStaticPose.unknownReason,
        )
    }

    @Test
    fun aggregationPolicyIsPartOfTheCriterionContract() {
        val samples = listOf(sample(0L, 0.0), sample(100L, 10.0), sample(200L, 20.0))
        val expected = listOf(
            CriterionAggregation.WeightedQuantile(0.5) to 10.0,
            CriterionAggregation.WeightedQuantile(0.9) to 20.0,
            CriterionAggregation.WeightedMean to 10.0,
        )

        expected.forEach { (aggregation, value) ->
            val result = evaluate(
                spec = spec(
                    targetInterval = MeasurementInterval(-100.0, 100.0),
                    aggregation = aggregation,
                ),
                samples = samples,
            )
            assertEquals(value, result.aggregatedMeasurement!!, 1e-12)
            assertEquals(CriterionState.PASS, result.state)
        }
    }

    @Test
    fun artifactFingerprintPinsResidualAndEligibilityPolicy() {
        val baseline = calibration()
        val changedResidual = calibration(MeasurementInterval(-1.0, 1.0))
        val changedEligibility = calibration(contract = contract(maximumGapMs = 201L))
        val changedFeatureAst = calibration(contract = contract(featureSpecSha256 = "1".repeat(64)))
        val changedQualityCalibration = calibration(
            contract = contract(qualityCalibrationArtifactSha256 = "b".repeat(64)),
        )

        assertTrue(baseline.artifactSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertNotEquals(baseline.artifactSha256, changedResidual.artifactSha256)
        assertNotEquals(baseline.artifactSha256, changedEligibility.artifactSha256)
        assertNotEquals(baseline.artifactSha256, changedFeatureAst.artifactSha256)
        assertNotEquals(baseline.artifactSha256, changedQualityCalibration.artifactSha256)
    }

    @Test
    fun belowThresholdQualityCannotHideALongEvidenceGap() {
        val result = evaluate(
            spec = spec(
                minimumSampleQuality = 0.5,
                minimumTimeCoverage = 0.5,
                minimumEvidenceMass = 0.4,
                maximumGapMs = 150L,
            ),
            phaseWindow = CriterionPhaseWindow(0L, 400L),
            samples = listOf(
                sample(0L, 5.0, 1.0),
                sample(100L, 5.0, 1.0),
                sample(200L, 5.0, 1.0),
                sample(300L, 5.0, 1e-12),
                sample(400L, 5.0, 1e-12),
            ),
        )

        assertEquals(1.0, result.timeCoverage, 0.0)
        assertEquals(0.62500000000025, result.evidenceMass, 1e-12)
        assertEquals(400.0, result.observableDurationMs, 0.0)
        assertEquals(200.0, result.eligibleDurationMs, 0.0)
        assertEquals(200L, result.maximumEvidenceGapMs)
        assertEquals(CriterionUnknownReason.EXCESSIVE_GAP, result.unknownReason)
    }

    @Test
    fun wideningCalibrationUncertaintyNeverCreatesAnOppositeCertainDecision() {
        for (lower in -15..15) {
            for (upper in lower..15) {
                val original = evaluate(
                    spec = spec(
                        calibrationError = MeasurementInterval(
                            lower.toDouble(),
                            upper.toDouble(),
                        ),
                    ),
                    calibration = calibration(
                        MeasurementInterval(lower.toDouble(), upper.toDouble()),
                    ),
                    samples = defaultSamples(value = 0.0),
                )
                for (expansion in 1..4) {
                    val widenedError = MeasurementInterval(
                        lower = lower - expansion.toDouble(),
                        upper = upper + expansion.toDouble(),
                    )
                    val widened = evaluate(
                        spec = spec(calibrationError = widenedError),
                        calibration = calibration(widenedError),
                        samples = defaultSamples(value = 0.0),
                    )
                    when (original.state) {
                        CriterionState.PASS -> assertNotEquals(CriterionState.FAIL, widened.state)
                        CriterionState.UNKNOWN -> assertEquals(CriterionState.UNKNOWN, widened.state)
                        CriterionState.FAIL -> when (original.failRegion) {
                            CriterionFailRegion.LOW_SIDE -> {
                                assertNotEquals(CriterionState.PASS, widened.state)
                                assertNotEquals(CriterionFailRegion.HIGH_SIDE, widened.failRegion)
                            }
                            CriterionFailRegion.HIGH_SIDE -> {
                                assertNotEquals(CriterionState.PASS, widened.state)
                                assertNotEquals(CriterionFailRegion.LOW_SIDE, widened.failRegion)
                            }
                            null -> fail("FAIL must preserve a fail region")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun emptyOrSingleSampleHasNoTemporalEvidence() {
        val empty = evaluate(samples = emptyList())
        val single = evaluate(samples = listOf(sample(100L, 5.0)))
        val uncalibratedEmpty = evaluate(samples = emptyList(), calibration = null)

        assertEquals(CriterionUnknownReason.NO_EVIDENCE, empty.unknownReason)
        assertEquals(CriterionUnknownReason.NO_EVIDENCE, single.unknownReason)
        assertEquals(200L, single.windowDurationMs)
        assertEquals(0.0, single.observableDurationMs, 0.0)
        assertEquals(
            CriterionUnknownReason.UNCALIBRATED_DOMAIN,
            uncalibratedEmpty.unknownReason,
        )
    }

    @Test
    fun requiredCapabilityInputIsDefensivelyCopied() {
        val mutableCapabilities = mutableSetOf(capability)
        val spec = spec(requiredCapabilities = mutableCapabilities)
        mutableCapabilities.clear()

        val result = evaluate(spec = spec, availableCapabilities = emptySet())

        assertEquals(setOf(capability), spec.requiredCapabilities)
        assertEquals(CriterionUnknownReason.MISSING_CAPABILITY, result.unknownReason)
    }

    @Test
    fun approvedEvaluatorFingerprintPinsTargetAndCapabilities() {
        val baseline = spec(
            requiredCapabilities = setOf(capability, CriterionCapability.PRIMARY_PERSON_LOCK),
        )

        assertIllegalArgument {
            PoseCriterionSpec(
                calibrationContract = baseline.calibrationContract,
                approvedCalibrationArtifactSha256 = baseline.approvedCalibrationArtifactSha256,
                approvedEvaluatorSpecSha256 = baseline.approvedEvaluatorSpecSha256,
                targetInterval = MeasurementInterval(-100.0, 100.0),
                requiredCapabilities = baseline.requiredCapabilities,
            )
        }
        assertIllegalArgument {
            PoseCriterionSpec(
                calibrationContract = baseline.calibrationContract,
                approvedCalibrationArtifactSha256 = baseline.approvedCalibrationArtifactSha256,
                approvedEvaluatorSpecSha256 = baseline.approvedEvaluatorSpecSha256,
                targetInterval = baseline.targetInterval,
                requiredCapabilities = setOf(capability),
            )
        }
    }

    @Test
    fun outOfContractMeasurementAndArithmeticOverflowFailClosed() {
        val invalidMeasurement = evaluate(
            samples = defaultSamples(value = 2_000.0),
        )
        assertEquals(
            CriterionUnknownReason.INVALID_MEASUREMENT,
            invalidMeasurement.unknownReason,
        )

        val wideRange = MeasurementInterval(-Double.MAX_VALUE, Double.MAX_VALUE)
        val overflowError = MeasurementInterval(Double.MAX_VALUE, Double.MAX_VALUE)
        val overflowSpec = spec(
            targetInterval = wideRange,
            validMeasurementInterval = wideRange,
            calibrationError = overflowError,
        )
        val overflow = evaluate(
            spec = overflowSpec,
            samples = defaultSamples(value = Double.MAX_VALUE),
            calibration = calibration(
                error = overflowError,
                contract = overflowSpec.calibrationContract,
            ),
        )
        assertTrue(overflow.aggregatedMeasurement!!.isFinite())
        assertEquals(CriterionUnknownReason.NUMERIC_ERROR, overflow.unknownReason)
    }

    @Test
    fun invalidSpecsSamplesWindowsAndTimestampOrderFailFast() {
        assertIllegalArgument { MeasurementInterval(Double.NaN, 1.0) }
        assertIllegalArgument { MeasurementInterval(2.0, 1.0) }
        assertIllegalArgument { CriterionPhaseWindow(-1L, 1L) }
        assertIllegalArgument { CriterionPhaseWindow(1L, 1L) }
        assertIllegalArgument { spec(minimumTimeCoverage = Double.NaN) }
        assertIllegalArgument { spec(minimumTimeCoverage = 0.0) }
        assertIllegalArgument { spec(minimumEvidenceMass = 0.0) }
        assertIllegalArgument { spec(minimumObservableDurationMs = 0L) }
        assertIllegalArgument { spec(minimumEffectiveSamples = Double.NaN) }
        assertIllegalArgument { spec(minimumEffectiveSamples = 0.99) }
        assertIllegalArgument { spec(maximumGapMs = 0L) }
        assertIllegalArgument { spec(minimumSampleQuality = 0.0) }
        assertIllegalArgument { spec(correlationHorizonMs = 0L) }
        assertIllegalArgument { CriterionAggregation.WeightedQuantile(0.0) }
        assertIllegalArgument { CriterionAggregation.WeightedQuantile(1.0) }
        assertIllegalArgument { CriterionAggregation.WeightedQuantile(Double.POSITIVE_INFINITY) }
        assertIllegalArgument { CriterionEvidenceSample(0L, Double.NaN, 1.0) }
        assertIllegalArgument { CriterionEvidenceSample(0L, 1.0, Double.NaN) }
        assertIllegalArgument {
            PoseCriterionSpec(
                calibrationContract = contract(),
                approvedCalibrationArtifactSha256 = "not-a-sha256",
                approvedEvaluatorSpecSha256 = "0".repeat(64),
                targetInterval = MeasurementInterval(0.0, 1.0),
                requiredCapabilities = setOf(capability),
            )
        }

        assertIllegalArgument {
            evaluate(samples = listOf(sample(100L, 5.0), sample(99L, 5.0)))
        }
        assertIllegalArgument {
            evaluate(samples = listOf(sample(100L, 5.0), sample(100L, 5.0)))
        }
        assertIllegalArgument {
            evaluate(samples = listOf(sample(0L, 5.0), sample(201L, 5.0)))
        }
    }

    private fun assertResult(
        calibrationError: MeasurementInterval,
        state: CriterionState,
        failRegion: CriterionFailRegion?,
    ) {
        val result = evaluate(
            spec = spec(calibrationError = calibrationError),
            calibration = calibration(calibrationError),
        )
        assertEquals(state, result.state)
        assertEquals(failRegion, result.failRegion)
        assertNull(result.unknownReason)
    }

    private fun evaluate(
        spec: PoseCriterionSpec = spec(),
        phaseWindow: CriterionPhaseWindow = defaultWindow,
        samples: List<CriterionEvidenceSample> = defaultSamples(),
        availableCapabilities: Set<CriterionCapability> = setOf(capability),
        calibration: CriterionAggregateCalibration? = calibration(
            contract = spec.calibrationContract,
        ),
    ): PoseCriterionResult = engine.evaluate(
        spec = spec,
        phaseWindow = phaseWindow,
        samples = samples,
        availableCapabilities = availableCapabilities,
        calibration = calibration,
    )

    private fun spec(
        targetInterval: MeasurementInterval = MeasurementInterval(0.0, 10.0),
        requiredCapabilities: Set<CriterionCapability> = setOf(capability),
        validMeasurementInterval: MeasurementInterval = MeasurementInterval(-1_000.0, 1_000.0),
        minimumSampleQuality: Double = 0.1,
        minimumTimeCoverage: Double = 1.0,
        minimumEvidenceMass: Double = 1.0,
        minimumObservableDurationMs: Long = 100L,
        minimumEffectiveSamples: Double = 1.0,
        maximumGapMs: Long = 200L,
        aggregation: CriterionAggregation = CriterionAggregation.WeightedQuantile(0.5),
        correlationHorizonMs: Long = 100L,
        calibrationError: MeasurementInterval = MeasurementInterval(0.0, 0.0),
    ): PoseCriterionSpec {
        val contract = contract(
            aggregation = aggregation,
            validMeasurementInterval = validMeasurementInterval,
            minimumSampleQuality = minimumSampleQuality,
            minimumTimeCoverage = minimumTimeCoverage,
            minimumEvidenceMass = minimumEvidenceMass,
            minimumObservableDurationMs = minimumObservableDurationMs,
            minimumEffectiveSamples = minimumEffectiveSamples,
            maximumGapMs = maximumGapMs,
            correlationHorizonMs = correlationHorizonMs,
        )
        val approvedCalibration = calibration(calibrationError, contract)
        val immutableCapabilities = requiredCapabilities.toSet()
        val approvedEvaluatorSpecSha256 = evaluatorSpecSha256(
            approvedCalibrationArtifactSha256 = approvedCalibration.artifactSha256,
            targetInterval = targetInterval,
            requiredCapabilities = immutableCapabilities,
        )
        return PoseCriterionSpec(
            calibrationContract = contract,
            approvedCalibrationArtifactSha256 = approvedCalibration.artifactSha256,
            approvedEvaluatorSpecSha256 = approvedEvaluatorSpecSha256,
            targetInterval = targetInterval,
            requiredCapabilities = immutableCapabilities,
        )
    }

    private fun defaultSamples(value: Double = 5.0, quality: Double = 1.0) = listOf(
        sample(0L, value, quality),
        sample(100L, value, quality),
        sample(200L, value, quality),
    )

    private fun calibration(
        error: MeasurementInterval = MeasurementInterval(0.0, 0.0),
        contract: CriterionCalibrationContract = contract(),
    ) = CriterionAggregateCalibration(
        contract = contract,
        additiveErrorInterval = error,
    )

    private fun contract(
        aggregation: CriterionAggregation = CriterionAggregation.WeightedQuantile(0.5),
        featureSpecSha256: String = "0".repeat(64),
        qualityCalibrationArtifactSha256: String = "a".repeat(64),
        runtimeDomainId: String = "mediapipe-full-test-domain:v1",
        validMeasurementInterval: MeasurementInterval = MeasurementInterval(-1_000.0, 1_000.0),
        minimumSampleQuality: Double = 0.1,
        minimumTimeCoverage: Double = 1.0,
        minimumEvidenceMass: Double = 1.0,
        minimumObservableDurationMs: Long = 100L,
        minimumEffectiveSamples: Double = 1.0,
        maximumGapMs: Long = 200L,
        correlationHorizonMs: Long = 100L,
    ) = CriterionCalibrationContract(
        criterionId = "test-criterion:v1",
        featureContractId = "test-feature:v1",
        featureSpecSha256 = featureSpecSha256,
        measurementUnit = "degree",
        aggregation = aggregation,
        qualityContractId = "test-quality:v1",
        qualityCalibrationArtifactSha256 = qualityCalibrationArtifactSha256,
        runtimeDomainId = runtimeDomainId,
        validMeasurementInterval = validMeasurementInterval,
        minimumSampleQuality = minimumSampleQuality,
        minimumTimeCoverage = minimumTimeCoverage,
        minimumEvidenceMass = minimumEvidenceMass,
        minimumObservableDurationMs = minimumObservableDurationMs,
        minimumEffectiveSamples = minimumEffectiveSamples,
        maximumGapMs = maximumGapMs,
        correlationHorizonMs = correlationHorizonMs,
        contractVersion = 1,
    )

    private fun sample(timestampMs: Long, value: Double, quality: Double = 1.0) =
        CriterionEvidenceSample(timestampMs, value, quality)

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
