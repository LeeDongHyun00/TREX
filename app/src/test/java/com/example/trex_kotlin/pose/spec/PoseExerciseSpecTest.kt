package com.example.trex_kotlin.pose.spec

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.contract.PoseQualityCalibrationArtifact
import com.example.trex_kotlin.pose.contract.PoseQualityCalibrationKnot
import com.example.trex_kotlin.pose.contract.PoseQualitySignalKind
import com.example.trex_kotlin.pose.criterion.CriterionAggregateCalibration
import com.example.trex_kotlin.pose.criterion.ATTESTED_CRITERION_SAMPLING_CONTRACT_SHA256
import com.example.trex_kotlin.pose.criterion.CriterionAggregation
import com.example.trex_kotlin.pose.criterion.CriterionCalibrationContract
import com.example.trex_kotlin.pose.criterion.CriterionCapability
import com.example.trex_kotlin.pose.criterion.CriterionNodeSpec
import com.example.trex_kotlin.pose.criterion.CriterionPhaseWindow
import com.example.trex_kotlin.pose.criterion.CriterionSeverity
import com.example.trex_kotlin.pose.criterion.CriterionState
import com.example.trex_kotlin.pose.criterion.MeasurementInterval
import com.example.trex_kotlin.pose.criterion.PoseCriterionFeatureBinding
import com.example.trex_kotlin.pose.criterion.PoseCriterionGraph
import com.example.trex_kotlin.pose.criterion.PoseCriterionSpec
import com.example.trex_kotlin.pose.criterion.evaluatorSpecSha256
import com.example.trex_kotlin.pose.feature.PoseScalarFeatureSpec
import com.example.trex_kotlin.pose.phase.OrderedPosePhaseGraph
import com.example.trex_kotlin.pose.phase.PhaseScalarInterval
import com.example.trex_kotlin.pose.phase.PosePhaseDriverBinding
import com.example.trex_kotlin.pose.phase.PosePhaseEngineConfig
import com.example.trex_kotlin.pose.phase.PosePhaseEnterPredicate
import com.example.trex_kotlin.pose.phase.PosePhaseState
import com.example.trex_kotlin.pose.phase.PosePhaseStateId
import com.example.trex_kotlin.pose.phase.PosePhaseTransition
import com.example.trex_kotlin.pose.phase.phaseDriverArtifactSha256
import com.example.trex_kotlin.pose.runtime.PoseObservationContract
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseExerciseSpecTest {
    private val ready = PosePhaseStateId("ready")
    private val bottom = PosePhaseStateId("bottom")
    private val feature = PoseScalarFeatureSpec.JointAngle(
        featureContractId = "forward-lunge.front-knee-flexion.world.v1",
        coordinateSpace = PoseCoordinateSpace.WORLD,
        first = PoseJoint.LEFT_HIP,
        vertex = PoseJoint.LEFT_KNEE,
        third = PoseJoint.LEFT_ANKLE,
    )
    private val observerContract = poseObservationContract()
    private val observationSource = PoseObservationSource(observerContract)
    private val personTrackEpoch = observationSource.newPersonTrackEpoch()

    @Test
    fun validSpecPinsCanonicalExercisePhaseCriterionAndGraph() {
        val criterion = exerciseCriterion()
        val graph = criterionGraph(criterion.criterionId)
        val spec = exerciseSpec(listOf(criterion), graph)

        assertEquals(AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE, spec.exercise)
        assertEquals(criterion, spec.criterion("front-knee-flexion"))
        assertEquals(64, spec.exerciseSpecSha256.length)
        assertEquals(spec.approvedExerciseSpecSha256, spec.exerciseSpecSha256)
        assertTrue(bottom in criterion.eligiblePhaseIds)
    }

    @Test
    fun signedCollectionsCannotBeMutatedAfterValidation() {
        val criterion = exerciseCriterion()
        val spec = exerciseSpec(listOf(criterion), criterionGraph(criterion.criterionId))
        val identity = spec.exerciseSpecSha256

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (spec.criteria as MutableList<ExerciseCriterionSpec>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (criterion.eligiblePhaseIds as MutableSet<PosePhaseStateId>).clear()
        }

        assertEquals(identity, spec.exerciseSpecSha256)
        assertEquals(criterion, spec.criterion(criterion.criterionId))
    }

    @Test
    fun unvalidatedProxyCannotBecomeUserFacingAndNonObservableCannotBind() {
        assertThrows(IllegalArgumentException::class.java) {
            exerciseCriterion(
                observability = CriterionObservability.PROXY_UNVALIDATED,
                runtimeMode = CriterionRuntimeMode.CUE_ELIGIBLE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            exerciseCriterion(
                observability = CriterionObservability.NOT_OBSERVABLE,
                runtimeMode = CriterionRuntimeMode.SHADOW,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExerciseCriterionSpec(
                featureBinding = featureBinding(),
                eligiblePhaseIds = setOf(ready, bottom),
                viewContractId = "side-view.body-yaw-window.v1",
                observability = CriterionObservability.DIRECT,
                runtimeMode = CriterionRuntimeMode.SHADOW,
            )
        }

        val shadow = exerciseCriterion(
            observability = CriterionObservability.PROXY_UNVALIDATED,
            runtimeMode = CriterionRuntimeMode.SHADOW,
        )
        assertEquals(CriterionRuntimeMode.SHADOW, shadow.runtimeMode)
    }

    @Test
    fun exerciseSpecRejectsUnknownPhaseGraphDriftAndUnsignedPolicyChange() {
        val unknownPhaseCriterion = exerciseCriterion(
            phaseIds = setOf(PosePhaseStateId("missing")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            exerciseSpec(
                criteria = listOf(unknownPhaseCriterion),
                graph = criterionGraph(unknownPhaseCriterion.criterionId),
            )
        }

        val criterion = exerciseCriterion()
        assertThrows(IllegalArgumentException::class.java) {
            exerciseSpec(
                criteria = listOf(criterion),
                graph = criterionGraph("different"),
            )
        }

        val baselineGraph = criterionGraph(criterion.criterionId)
        val driver = phaseDriver()
        val approved = exerciseSpecSha256(
            SPEC_ID,
            1,
            AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE,
            observerContract,
            driver,
            listOf(criterion),
            baselineGraph,
        )
        val changedGraph = PoseCriterionGraph(
            listOf(
                CriterionNodeSpec(
                    criterion.criterionId,
                    CriterionSeverity.SAFETY,
                    lowSideCueCode = "changed.low",
                ),
            ),
        )
        assertNotEquals(baselineGraph.graphSpecSha256, changedGraph.graphSpecSha256)
        assertNotEquals(
            approved,
            exerciseSpecSha256(
                SPEC_ID,
                1,
                AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE,
                poseObservationContract(modelArtifactSha256 = SHA_D),
                driver,
                listOf(criterion),
                baselineGraph,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PoseExerciseSpec(
                specId = SPEC_ID,
                specVersion = 1,
                exercise = AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE,
                observationContract = observerContract,
                phaseDriver = driver,
                criteria = listOf(criterion),
                criterionGraph = changedGraph,
                approvedExerciseSpecSha256 = approved,
            )
        }

        val incompatibleDomain = exerciseCriterion(
            binding = featureBinding(
                runtimeDomainId = "mediapipe-full.world.front-view.v1",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            exerciseSpec(
                listOf(incompatibleDomain),
                criterionGraph(incompatibleDomain.criterionId),
            )
        }

        val unsupportedView = exerciseCriterion(
            viewContractId = "front-view.body-yaw-window.v1",
        )
        assertThrows(IllegalArgumentException::class.java) {
            exerciseSpec(
                listOf(unsupportedView),
                criterionGraph(unsupportedView.criterionId),
            )
        }

        val incompatibleSampling = exerciseCriterion(
            binding = featureBinding(samplingContractSha256 = SHA_D),
        )
        assertThrows(IllegalArgumentException::class.java) {
            exerciseSpec(
                listOf(incompatibleSampling),
                criterionGraph(incompatibleSampling.criterionId),
            )
        }
    }

    @Test
    fun exerciseSpecRejectsBranchingPhaseGraphUntilApplicabilityIsExplicit() {
        val alternate = PosePhaseStateId("alternate")
        val branchedGraph = OrderedPosePhaseGraph(
            states = listOf(
                PosePhaseState(
                    ready,
                    PosePhaseEnterPredicate(PhaseScalarInterval(155.0, 180.0)),
                ),
                PosePhaseState(
                    bottom,
                    PosePhaseEnterPredicate(PhaseScalarInterval(70.0, 115.0)),
                ),
                PosePhaseState(
                    alternate,
                    PosePhaseEnterPredicate(PhaseScalarInterval(30.0, 60.0)),
                ),
            ),
            initialStateId = ready,
            transitions = listOf(
                PosePhaseTransition(ready, bottom),
                PosePhaseTransition(ready, alternate),
                PosePhaseTransition(bottom, ready, completesCycle = true),
            ),
        )
        val driver = phaseDriver(branchedGraph)
        val criterion = exerciseCriterion()
        val graph = criterionGraph(criterion.criterionId)
        val approved = exerciseSpecSha256(
            SPEC_ID,
            1,
            AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE,
            observerContract,
            driver,
            listOf(criterion),
            graph,
        )

        assertThrows(IllegalArgumentException::class.java) {
            PoseExerciseSpec(
                specId = SPEC_ID,
                specVersion = 1,
                exercise = AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE,
                observationContract = observerContract,
                phaseDriver = driver,
                criteria = listOf(criterion),
                criterionGraph = graph,
                approvedExerciseSpecSha256 = approved,
            )
        }
    }

    @Test
    fun exactBoundEvaluationProducesGraphResult() {
        val criterion = exerciseCriterion()
        val spec = exerciseSpec(listOf(criterion), criterionGraph(criterion.criterionId))
        val calibration = calibrationFor(criterion)
        val bound = spec.evaluateCriterion(
            criterionId = criterion.criterionId,
            cycleEpoch = 7L,
            windowScope = CriterionWindowScope.Phase(bottom),
            phaseWindow = CriterionPhaseWindow(0L, 200L),
            observations = listOf(
                angleFrame(0L),
                angleFrame(100L),
                straightFrame(100L),
                angleFrame(199L),
            ).map(::attest),
            personTrackEpoch = personTrackEpoch,
            calibration = calibration,
        )

        val evaluation = spec.evaluateCriterionGraph(listOf(bound))

        assertEquals(CriterionState.PASS, bound.atomicResult.state)
        assertEquals(null, evaluation.selectedCue)
    }

    @Test
    fun runtimeViewMismatchAbstainsInsteadOfThrowingOrCueing() {
        val criterion = exerciseCriterion()
        val spec = exerciseSpec(listOf(criterion), criterionGraph(criterion.criterionId))

        val bound = spec.evaluateCriterion(
            criterionId = criterion.criterionId,
            cycleEpoch = 3L,
            windowScope = CriterionWindowScope.Phase(bottom),
            phaseWindow = CriterionPhaseWindow(0L, 200L),
            observations = listOf(angleFrame(0L), angleFrame(100L))
                .map { frame -> attest(frame, viewQualified = false) },
            personTrackEpoch = personTrackEpoch,
            calibration = calibrationFor(criterion),
        )
        val evaluation = spec.evaluateCriterionGraph(listOf(bound))

        assertEquals(CriterionState.UNKNOWN, bound.atomicResult.state)
        assertTrue(
            CriterionCapability.VIEW_QUALIFIED in bound.atomicResult.missingCapabilities,
        )
        assertEquals(null, evaluation.selectedCue)
    }

    @Test
    fun sampledEvidenceHashCommitsMeasurementsButNotHiddenUnqualifiedCoordinates() {
        val criterion = exerciseCriterion()
        val spec = exerciseSpec(listOf(criterion), criterionGraph(criterion.criterionId))
        val common = listOf(angleFrame(0L), angleFrame(100L))
        val changed = listOf(straightFrame(0L), straightFrame(100L))

        fun evaluate(frames: List<PoseFrame>, viewQualified: Boolean) = spec.evaluateCriterion(
            criterionId = criterion.criterionId,
            cycleEpoch = 1L,
            windowScope = CriterionWindowScope.Phase(bottom),
            phaseWindow = CriterionPhaseWindow(0L, 200L),
            observations = frames.map { frame -> attest(frame, viewQualified) },
            personTrackEpoch = personTrackEpoch,
            calibration = calibrationFor(criterion),
        )

        assertNotEquals(
            evaluate(common, viewQualified = true).sampledEvidenceSha256,
            evaluate(changed, viewQualified = true).sampledEvidenceSha256,
        )
        assertEquals(
            evaluate(common, viewQualified = true).sampledEvidenceSha256,
            evaluate(
                common + straightFrame(100L),
                viewQualified = true,
            ).sampledEvidenceSha256,
        )
        assertEquals(
            evaluate(common, viewQualified = false).sampledEvidenceSha256,
            evaluate(changed, viewQualified = false).sampledEvidenceSha256,
        )

        val qualityCriterion = exerciseCriterion(
            binding = featureBinding(
                qualityKnots = listOf(
                    PoseQualityCalibrationKnot(0.0, 0.25),
                    PoseQualityCalibrationKnot(1.0, 1.0),
                ),
            ),
        )
        val qualitySpec = exerciseSpec(
            listOf(qualityCriterion),
            criterionGraph(qualityCriterion.criterionId),
        )
        fun qualityEvidence(confidence: Double) = qualitySpec.evaluateCriterion(
            criterionId = qualityCriterion.criterionId,
            cycleEpoch = 1L,
            windowScope = CriterionWindowScope.Phase(bottom),
            phaseWindow = CriterionPhaseWindow(0L, 200L),
            observations = listOf(
                angleFrame(0L, confidence),
                angleFrame(100L, confidence),
            ).map(::attest),
            personTrackEpoch = personTrackEpoch,
            calibration = calibrationFor(qualityCriterion),
        )
        assertNotEquals(
            qualityEvidence(1.0).sampledEvidenceSha256,
            qualityEvidence(0.5).sampledEvidenceSha256,
        )
    }

    @Test
    fun foreignEvaluatorAndIneligiblePhaseCannotReachGraphCuePolicy() {
        val criterion = exerciseCriterion()
        val spec = exerciseSpec(listOf(criterion), criterionGraph(criterion.criterionId))
        val bound = spec.evaluateCriterion(
            criterionId = criterion.criterionId,
            cycleEpoch = 1L,
            windowScope = CriterionWindowScope.Phase(bottom),
            phaseWindow = CriterionPhaseWindow(0L, 200L),
            observations = listOf(angleFrame(0L), angleFrame(100L)).map(::attest),
            personTrackEpoch = personTrackEpoch,
            calibration = calibrationFor(criterion),
        )

        val alternateCriterion = exerciseCriterion(
            binding = featureBinding(target = MeasurementInterval(0.0, 10.0)),
        )
        val alternateSpec = exerciseSpec(
            listOf(alternateCriterion),
            criterionGraph(alternateCriterion.criterionId),
        )
        val alternateBound = alternateSpec.evaluateCriterion(
            criterionId = alternateCriterion.criterionId,
            cycleEpoch = 1L,
            windowScope = CriterionWindowScope.Phase(bottom),
            phaseWindow = CriterionPhaseWindow(0L, 200L),
            observations = listOf(angleFrame(0L), angleFrame(100L)).map(::attest),
            personTrackEpoch = personTrackEpoch,
            calibration = calibrationFor(alternateCriterion),
        )
        val foreignEvaluator = copyEnvelope(
            source = bound,
            atomicResult = alternateBound.atomicResult,
        )
        val ineligiblePhase = copyEnvelope(
            source = bound,
            windowScope = CriterionWindowScope.Phase(ready),
        )

        assertThrows(IllegalArgumentException::class.java) {
            spec.evaluateCriterionGraph(listOf(foreignEvaluator))
        }
        assertThrows(IllegalArgumentException::class.java) {
            spec.evaluateCriterionGraph(listOf(ineligiblePhase))
        }
    }

    private fun exerciseSpec(
        criteria: List<ExerciseCriterionSpec>,
        graph: PoseCriterionGraph,
    ): PoseExerciseSpec {
        val driver = phaseDriver()
        val approved = exerciseSpecSha256(
            specId = SPEC_ID,
            specVersion = 1,
            exercise = AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE,
            observationContract = observerContract,
            phaseDriver = driver,
            criteria = criteria,
            criterionGraph = graph,
        )
        return PoseExerciseSpec(
            specId = SPEC_ID,
            specVersion = 1,
            exercise = AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE,
            observationContract = observerContract,
            phaseDriver = driver,
            criteria = criteria,
            criterionGraph = graph,
            approvedExerciseSpecSha256 = approved,
        )
    }

    private fun exerciseCriterion(
        observability: CriterionObservability = CriterionObservability.DIRECT,
        runtimeMode: CriterionRuntimeMode = CriterionRuntimeMode.CUE_ELIGIBLE,
        phaseIds: Set<PosePhaseStateId> = setOf(bottom),
        binding: PoseCriterionFeatureBinding = featureBinding(),
        viewContractId: String = "side-view.body-yaw-window.v1",
    ): ExerciseCriterionSpec = ExerciseCriterionSpec(
        featureBinding = binding,
        eligiblePhaseIds = phaseIds,
        viewContractId = viewContractId,
        observability = observability,
        runtimeMode = runtimeMode,
    )

    private fun featureBinding(
        target: MeasurementInterval = MeasurementInterval(80.0, 110.0),
        runtimeDomainId: String = "mediapipe-full.world.side-view.v1",
        samplingContractSha256: String = ATTESTED_CRITERION_SAMPLING_CONTRACT_SHA256,
        qualityKnots: List<PoseQualityCalibrationKnot> =
            listOf(PoseQualityCalibrationKnot(0.0, 1.0)),
    ): PoseCriterionFeatureBinding {
        val qualityCalibration = PoseQualityCalibrationArtifact(
            signalKind = PoseQualitySignalKind.CRITERION_EVIDENCE_WEIGHT,
            featureSpecSha256 = feature.featureSpecSha256,
            qualityContractId = "forward-lunge.knee-quality.v1",
            runtimeDomainId = runtimeDomainId,
            knots = qualityKnots,
        )
        val contract = CriterionCalibrationContract(
            criterionId = "front-knee-flexion",
            featureContractId = feature.featureContractId,
            featureSpecSha256 = feature.featureSpecSha256,
            samplingContractSha256 = samplingContractSha256,
            measurementUnit = "degrees",
            aggregation = CriterionAggregation.WeightedMean,
            qualityContractId = qualityCalibration.qualityContractId,
            qualityCalibrationArtifactSha256 = qualityCalibration.artifactSha256,
            runtimeDomainId = runtimeDomainId,
            validMeasurementInterval = MeasurementInterval(0.0, 180.0),
            minimumSampleQuality = 0.5,
            minimumTimeCoverage = 0.6,
            minimumEvidenceMass = 0.5,
            minimumObservableDurationMs = 100L,
            minimumEffectiveSamples = 1.0,
            maximumGapMs = 200L,
            correlationHorizonMs = 100L,
            contractVersion = 1,
        )
        val calibration = CriterionAggregateCalibration(contract, MeasurementInterval(-3.0, 4.0))
        val capabilities = setOf(
            CriterionCapability.POSE_WORLD_RELATIVE,
            CriterionCapability.PRIMARY_PERSON_LOCK,
            CriterionCapability.VIEW_QUALIFIED,
        )
        val criterionSpec = PoseCriterionSpec(
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
        return PoseCriterionFeatureBinding(criterionSpec, feature, qualityCalibration)
    }

    private fun calibrationFor(criterion: ExerciseCriterionSpec) = CriterionAggregateCalibration(
        criterion.featureBinding.criterionSpec.calibrationContract,
        MeasurementInterval(-3.0, 4.0),
    )

    private fun phaseDriver(graph: OrderedPosePhaseGraph = phaseGraph()): PosePhaseDriverBinding {
        val config = PosePhaseEngineConfig(
            graph = graph,
            minimumQualitySignal = 0.5,
            maximumObservationGapMs = 500L,
            unusableObservationGraceMs = 250L,
            maximumPhaseDurationMs = 10_000L,
            maximumCycleDurationMs = 30_000L,
        )
        val qualityCalibration = PoseQualityCalibrationArtifact(
            signalKind = PoseQualitySignalKind.PHASE_GATE_SIGNAL,
            featureSpecSha256 = feature.featureSpecSha256,
            qualityContractId = "forward-lunge.phase-quality.v1",
            runtimeDomainId = "mediapipe-full.world.side-view.v1",
            knots = listOf(PoseQualityCalibrationKnot(0.0, 1.0)),
        )
        return PosePhaseDriverBinding(
            featureSpec = feature,
            engineConfig = config,
            qualityCalibration = qualityCalibration,
            approvedPhaseArtifactSha256 = phaseDriverArtifactSha256(
                feature,
                config,
                qualityCalibration,
            ),
        )
    }

    private fun criterionGraph(id: String): PoseCriterionGraph = PoseCriterionGraph(
        listOf(
            CriterionNodeSpec(
                id = id,
                severity = CriterionSeverity.CORRECTION,
                lowSideCueCode = "forward-lunge.front-knee.too-straight.v1",
                highSideCueCode = "forward-lunge.front-knee.too-deep.v1",
            ),
        ),
    )

    private fun poseObservationContract(
        modelArtifactSha256: String = SHA_A,
    ): PoseObservationContract = PoseObservationContract(
        runtimeDomainId = "mediapipe-full.world.side-view.v1",
        modelArtifactId = "mediapipe.pose-landmarker.full.v1",
        modelArtifactSha256 = modelArtifactSha256,
        inferenceOptionsContractId = "mediapipe.video-options.v1",
        inferenceOptionsArtifactSha256 = SHA_A,
        preprocessingContractId = "camerax.viewport-rotation-no-mirror.v1",
        preprocessingArtifactSha256 = SHA_B,
        landmarkSchemaId = "mediapipe.pose-33.v1",
        landmarkSchemaArtifactSha256 = SHA_C,
        supportedCoordinateSpaces = setOf(PoseCoordinateSpace.WORLD),
        phaseViewContractId = "side-view.body-yaw-window.v1",
        allowedViewContractIds = setOf("side-view.body-yaw-window.v1"),
        personLockArtifactId = "primary-person.temporal-lock.v1",
        personLockArtifactSha256 = SHA_B,
        viewQualifierArtifactId = "body-yaw.qualifier.v1",
        viewQualifierArtifactSha256 = SHA_C,
    )

    private fun angleFrame(timestampMs: Long, confidence: Double = 1.0): PoseFrame = PoseFrame(
        timestampMs = timestampMs,
        landmarks = emptyMap(),
        worldLandmarks = mapOf(
            PoseJoint.LEFT_HIP to PoseLandmark(
                0.0,
                1.0,
                0.0,
                visibility = confidence,
                presence = confidence,
            ),
            PoseJoint.LEFT_KNEE to PoseLandmark(
                0.0,
                0.0,
                0.0,
                visibility = confidence,
                presence = confidence,
            ),
            PoseJoint.LEFT_ANKLE to PoseLandmark(
                1.0,
                0.0,
                0.0,
                visibility = confidence,
                presence = confidence,
            ),
        ),
    )

    private fun straightFrame(timestampMs: Long): PoseFrame = PoseFrame(
        timestampMs = timestampMs,
        landmarks = emptyMap(),
        worldLandmarks = mapOf(
            PoseJoint.LEFT_HIP to PoseLandmark(0.0, 1.0, 0.0),
            PoseJoint.LEFT_KNEE to PoseLandmark(0.0, 0.0, 0.0),
            PoseJoint.LEFT_ANKLE to PoseLandmark(0.0, -1.0, 0.0),
        ),
    )

    private fun attest(
        frame: PoseFrame,
        viewQualified: Boolean = true,
    ): AttestedPoseObservation = observationSource.attest(
        frame = frame,
        personTrackEpoch = personTrackEpoch,
        viewQualifications = if (viewQualified) {
            listOf(
                observationSource.qualifyView(
                    viewContractId = "side-view.body-yaw-window.v1",
                    personTrackEpoch = personTrackEpoch,
                    frameTimestampMs = frame.timestampMs,
                ),
            )
        } else {
            emptyList()
        },
    )

    private fun copyEnvelope(
        source: BoundPoseCriterionResult,
        windowScope: CriterionWindowScope = source.windowScope,
        atomicResult: com.example.trex_kotlin.pose.criterion.PoseCriterionResult =
            source.atomicResult,
    ) = BoundPoseCriterionResult(
        exerciseSpecSha256 = source.exerciseSpecSha256,
        phaseArtifactSha256 = source.phaseArtifactSha256,
        cycleEpoch = source.cycleEpoch,
        windowScope = windowScope,
        phaseWindow = source.phaseWindow,
        viewContractId = source.viewContractId,
        qualifiedViewFrameCount = source.qualifiedViewFrameCount,
        totalFrameCount = source.totalFrameCount,
        sampledEvidenceSha256 = source.sampledEvidenceSha256,
        featureSpecSha256 = source.featureSpecSha256,
        runtimeDomainId = source.runtimeDomainId,
        qualityCalibrationArtifactSha256 = source.qualityCalibrationArtifactSha256,
        observationContractArtifactSha256 = source.observationContractArtifactSha256,
        personTrackEpoch = source.personTrackEpoch,
        atomicResult = atomicResult,
    )

    private fun phaseGraph(): OrderedPosePhaseGraph = OrderedPosePhaseGraph(
        states = listOf(
            PosePhaseState(
                ready,
                PosePhaseEnterPredicate(PhaseScalarInterval(155.0, 180.0)),
            ),
            PosePhaseState(
                bottom,
                PosePhaseEnterPredicate(PhaseScalarInterval(70.0, 115.0)),
            ),
        ),
        initialStateId = ready,
        transitions = listOf(
            PosePhaseTransition(ready, bottom),
            PosePhaseTransition(bottom, ready, completesCycle = true),
        ),
    )

    private companion object {
        const val SPEC_ID = "step-forward-dynamic-lunge.side-view.v1"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SHA_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
