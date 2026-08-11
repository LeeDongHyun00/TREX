package com.example.trex_kotlin.pose.phase

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.contract.PoseQualityCalibrationArtifact
import com.example.trex_kotlin.pose.contract.PoseQualityCalibrationKnot
import com.example.trex_kotlin.pose.contract.PoseQualitySignalKind
import com.example.trex_kotlin.pose.feature.PoseFeatureEngine
import com.example.trex_kotlin.pose.feature.PoseScalarFeatureSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PosePhaseDriverBindingTest {
    private val ready = PosePhaseStateId("ready")
    private val bottom = PosePhaseStateId("bottom")
    private val feature = PoseScalarFeatureSpec.JointAngle(
        featureContractId = "squat.knee-flexion.world.v1",
        coordinateSpace = PoseCoordinateSpace.WORLD,
        first = PoseJoint.LEFT_HIP,
        vertex = PoseJoint.LEFT_KNEE,
        third = PoseJoint.LEFT_ANKLE,
    )

    @Test
    fun exactSignedCompositionUsesCalibratedPhaseSignalInsteadOfRawConfidence() {
        val config = config()
        val calibration = qualityCalibration(feature, signal = 0.4)
        val approved = phaseDriverArtifactSha256(feature, config, calibration)
        val binding = PosePhaseDriverBinding(feature, config, calibration, approved)

        assertEquals(approved, binding.phaseArtifactSha256)
        val observation = binding.observation(frame(timestampMs = 100L), PoseFeatureEngine(0.0))
        assertEquals(0.4, observation.qualitySignal, 0.0)
        assertFalse(observation.qualitySignal >= config.minimumQualitySignal)

        val engine = PosePhaseEngine(config)
        val update = engine.accept(observation)
        assertEquals(null, update.activeStateId)
        assertTrue(update.events.isEmpty())

        assertThrows(IllegalArgumentException::class.java) {
            binding.observation(frame(timestampMs = 200L), PoseFeatureEngine(0.6))
        }
    }

    @Test
    fun featureAstGraphTimingAndQualityDriftChangeThePhaseArtifact() {
        val baseConfig = config()
        val baseCalibration = qualityCalibration(feature)
        val baseline = phaseDriverArtifactSha256(feature, baseConfig, baseCalibration)

        val changedAstWithSameId = feature.copy(third = PoseJoint.RIGHT_ANKLE)
        assertFalse(
            baseline == phaseDriverArtifactSha256(
                changedAstWithSameId,
                baseConfig,
                qualityCalibration(changedAstWithSameId),
            ),
        )
        assertFalse(
            baseline == phaseDriverArtifactSha256(
                feature,
                config(bottomUpper = 120.0),
                baseCalibration,
            ),
        )
        assertFalse(
            baseline == phaseDriverArtifactSha256(
                feature,
                config(graceMs = 301L),
                baseCalibration,
            ),
        )
        assertFalse(
            baseline == phaseDriverArtifactSha256(
                feature,
                config(maximumPhaseDurationMs = 9_999L),
                baseCalibration,
            ),
        )
        assertFalse(
            baseline == phaseDriverArtifactSha256(
                feature,
                config(
                    cycleScopeStartPolicy =
                        PoseCycleScopeStartPolicy.FIRST_TRANSITION_BOUNDARY,
                ),
                baseCalibration,
            ),
        )
        assertFalse(
            baseline == phaseDriverArtifactSha256(
                feature,
                baseConfig,
                qualityCalibration(
                    feature,
                    runtimeDomainId = "mediapipe-full.world.front-view.v1",
                ),
            ),
        )
    }

    @Test
    fun bindingRejectsWrongApprovalFeatureAndSignalKindWhileArtifactRejectsInvalidOutput() {
        val config = config()
        val calibration = qualityCalibration(feature)
        val approved = phaseDriverArtifactSha256(feature, config, calibration)

        assertThrows(IllegalArgumentException::class.java) {
            PosePhaseDriverBinding(feature, config, calibration, "0".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PosePhaseDriverBinding(
                feature,
                config,
                qualityCalibration(feature.copy(third = PoseJoint.RIGHT_ANKLE)),
                approved,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PoseQualityCalibrationKnot(0.0, 1.1)
        }
        val wrongKind = qualityCalibration(
            feature,
            signalKind = PoseQualitySignalKind.CRITERION_EVIDENCE_WEIGHT,
        )
        assertThrows(IllegalArgumentException::class.java) {
            PosePhaseDriverBinding(
                feature,
                config,
                wrongKind,
                phaseDriverArtifactSha256(feature, config, wrongKind),
            )
        }
    }

    @Test
    fun calibrationAbstentionAndMissingScalarProduceUnusableObservation() {
        val config = config()
        val calibration = qualityCalibration(feature, minimumRawConfidence = 0.95)
        val binding = PosePhaseDriverBinding(
            feature,
            config,
            calibration,
            phaseDriverArtifactSha256(feature, config, calibration),
        )

        val abstained = binding.observation(0L, scalar = 175.0, rawConfidence = 0.9)
        assertEquals(null, abstained.scalar)
        assertEquals(0.0, abstained.qualitySignal, 0.0)
        val missing = binding.observation(1L, scalar = null, rawConfidence = 0.9)
        assertEquals(null, missing.scalar)
        assertEquals(0.0, missing.qualitySignal, 0.0)
    }

    private fun config(
        bottomUpper: Double = 115.0,
        graceMs: Long = 300L,
        maximumPhaseDurationMs: Long = 10_000L,
        maximumCycleDurationMs: Long = maxOf(maximumPhaseDurationMs, 30_000L),
        cycleScopeStartPolicy: PoseCycleScopeStartPolicy =
            PoseCycleScopeStartPolicy.INITIAL_PHASE_WINDOW_START,
    ): PosePhaseEngineConfig = PosePhaseEngineConfig(
        graph = OrderedPosePhaseGraph(
            states = listOf(
                PosePhaseState(
                    ready,
                    PosePhaseEnterPredicate(
                        enterInterval = PhaseScalarInterval(160.0, 180.0),
                        holdInterval = PhaseScalarInterval(155.0, 180.0),
                        minimumDwellMs = 100L,
                    ),
                ),
                PosePhaseState(
                    bottom,
                    PosePhaseEnterPredicate(
                        enterInterval = PhaseScalarInterval(70.0, bottomUpper),
                        holdInterval = PhaseScalarInterval(65.0, bottomUpper + 5.0),
                        direction = PhaseScalarDirection.DECREASING,
                        directionTolerance = 1.0,
                        minimumDwellMs = 100L,
                    ),
                ),
            ),
            initialStateId = ready,
            transitions = listOf(
                PosePhaseTransition(ready, bottom),
                PosePhaseTransition(bottom, ready, completesCycle = true),
            ),
        ),
        minimumQualitySignal = 0.5,
        maximumObservationGapMs = 500L,
        unusableObservationGraceMs = graceMs,
        maximumPhaseDurationMs = maximumPhaseDurationMs,
        maximumCycleDurationMs = maximumCycleDurationMs,
        cycleScopeStartPolicy = cycleScopeStartPolicy,
    )

    private fun qualityCalibration(
        spec: PoseScalarFeatureSpec,
        signal: Double = 0.8,
        minimumRawConfidence: Double = 0.0,
        runtimeDomainId: String = "mediapipe-full.world.side-view.v1",
        signalKind: PoseQualitySignalKind = PoseQualitySignalKind.PHASE_GATE_SIGNAL,
    ): PoseQualityCalibrationArtifact = PoseQualityCalibrationArtifact(
        signalKind = signalKind,
        featureSpecSha256 = spec.featureSpecSha256,
        qualityContractId = "phase-landmark-quality.v1",
        runtimeDomainId = runtimeDomainId,
        knots = listOf(PoseQualityCalibrationKnot(minimumRawConfidence, signal)),
    )

    private fun frame(timestampMs: Long = 0L): PoseFrame = PoseFrame(
        timestampMs = timestampMs,
        landmarks = emptyMap(),
        worldLandmarks = mapOf(
            PoseJoint.LEFT_HIP to PoseLandmark(0.0, 1.0, visibility = 0.99, presence = 0.99),
            PoseJoint.LEFT_KNEE to PoseLandmark(0.0, 0.0, visibility = 0.99, presence = 0.99),
            PoseJoint.LEFT_ANKLE to PoseLandmark(0.0, -1.0, visibility = 0.99, presence = 0.99),
        ),
    )
}
