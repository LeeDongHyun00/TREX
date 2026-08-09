package com.example.trex_kotlin.pose.runtime

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
import com.example.trex_kotlin.pose.criterion.CriterionGraphStatus
import com.example.trex_kotlin.pose.criterion.CriterionNodeSpec
import com.example.trex_kotlin.pose.criterion.CriterionSeverity
import com.example.trex_kotlin.pose.criterion.CriterionState
import com.example.trex_kotlin.pose.criterion.CriterionUnknownReason
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
import com.example.trex_kotlin.pose.phase.PosePhaseResetReason
import com.example.trex_kotlin.pose.phase.PosePhaseState
import com.example.trex_kotlin.pose.phase.PosePhaseStateId
import com.example.trex_kotlin.pose.phase.PosePhaseTrackingReset
import com.example.trex_kotlin.pose.phase.PosePhaseTransition
import com.example.trex_kotlin.pose.phase.phaseDriverArtifactSha256
import com.example.trex_kotlin.pose.spec.CriterionObservability
import com.example.trex_kotlin.pose.spec.CriterionRuntimeMode
import com.example.trex_kotlin.pose.spec.CriterionWindowScope
import com.example.trex_kotlin.pose.spec.ExerciseCriterionSpec
import com.example.trex_kotlin.pose.spec.PoseExerciseSpec
import com.example.trex_kotlin.pose.spec.exerciseSpecSha256
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseExerciseEvaluationSessionTest {
    @Test
    fun backdatedBoundaryIsRedistributedAndOnlyCompleteCycleIsComposed() {
        val fixture = fixture()
        val session = fixture.session()

        val updates = cycleFrames().map { fixture.attest(it) }.map(session::accept)
        val evaluation = requireNotNull(updates.last().cycleEvaluation)

        assertTrue(updates.dropLast(1).all { it.cycleEvaluation == null })
        assertEquals(0L, evaluation.cycleEpoch)
        assertEquals(0L, evaluation.cycleStartTimestampMs)
        assertEquals(400L, evaluation.cycleEndTimestampMs)
        assertEquals(CriterionGraphStatus.PASS, evaluation.graphEvaluation.status)
        assertNull(evaluation.graphEvaluation.selectedCue)

        val readyResult = evaluation.criterionResults.getValue(READY_CRITERION)
        val bottomResult = evaluation.criterionResults.getValue(BOTTOM_CRITERION)
        assertEquals(CriterionState.PASS, readyResult.atomicResult.state)
        assertEquals(CriterionState.PASS, bottomResult.atomicResult.state)
        assertEquals(0L, readyResult.phaseWindow.startTimestampMs)
        assertEquals(200L, readyResult.phaseWindow.endTimestampMs)
        assertEquals(200L, bottomResult.phaseWindow.startTimestampMs)
        assertEquals(400L, bottomResult.phaseWindow.endTimestampMs)
        assertTrue(
            evaluation.criterionResults.values.all {
                evaluation.cycleStartTimestampMs <= it.phaseWindow.startTimestampMs
            },
        )
        assertEquals(90.0, readyResult.atomicResult.aggregatedMeasurement!!, 1e-9)
        assertEquals(150.0, bottomResult.atomicResult.aggregatedMeasurement!!, 1e-9)
    }

    @Test
    fun timestampsAndConstructorInputsArePinnedAndDuplicateFramesVanish() {
        val fixture = fixture()
        val calibrations = fixture.calibrations.toMutableMap()
        val session = PoseExerciseEvaluationSession(
            exerciseSpec = fixture.spec,
            observationSource = fixture.source,
            calibrations = calibrations,
        )
        calibrations.clear()

        val mutableWorld = LinkedHashMap(frame(0L, 170.0, 90.0).worldLandmarks)
        session.accept(
            fixture.attest(frame(0L, 170.0, 90.0).copy(worldLandmarks = mutableWorld)),
        )
        mutableWorld[PoseJoint.RIGHT_ANKLE] = angledPoint(2.0, 0.0)
        val duplicate = session.accept(fixture.attest(frame(0L, 90.0, 170.0)))
        val remaining = cycleFrames().drop(1).map { fixture.attest(it) }.map(session::accept)

        assertTrue(duplicate.phaseEvents.isEmpty())
        assertNull(duplicate.cycleEvaluation)
        assertEquals(fixture.calibrations.keys, session.calibrations.keys)
        assertNotNull(remaining.last().cycleEvaluation)
        assertThrows(IllegalArgumentException::class.java) {
            session.accept(fixture.attest(frame(499L, 170.0, 90.0)))
        }
    }

    @Test
    fun resetWindowsAreNeverEvaluatedAndCannotMixCycleEpochs() {
        val fixture = fixture(maximumObservationGapMs = 250L)
        val session = fixture.session()

        listOf(
            frame(0L, 170.0, 90.0),
            frame(100L, 170.0, 90.0),
            frame(200L, 90.0, 150.0),
            frame(300L, 90.0, 150.0),
        ).map { fixture.attest(it) }.forEach(session::accept)
        assertEquals(1, session.pendingCriterionCount)

        val reset = session.accept(fixture.attest(frame(1_000L, 170.0, 90.0)))
        assertTrue(reset.phaseEvents.any { it is PosePhaseTrackingReset })
        assertNull(reset.cycleEvaluation)
        assertEquals(0, session.pendingCriterionCount)
        assertEquals(1, session.evaluatedCriterionWindowCount)

        val completed = listOf(
            frame(1_100L, 170.0, 90.0),
            frame(1_200L, 90.0, 150.0),
            frame(1_300L, 90.0, 150.0),
            frame(1_400L, 170.0, 90.0),
            frame(1_500L, 170.0, 90.0),
        ).map { fixture.attest(it) }.map(session::accept).last().cycleEvaluation

        val evaluation = requireNotNull(completed)
        assertEquals(1L, evaluation.cycleEpoch)
        assertEquals(1_000L, evaluation.criterionResults.getValue(READY_CRITERION).phaseWindow.startTimestampMs)
        assertEquals(3, session.evaluatedCriterionWindowCount)
    }

    @Test
    fun branchingCycleIsRejectedUntilPathApplicabilityIsSigned() {
        assertThrows(IllegalArgumentException::class.java) {
            fixture(includeUnvisitedSidePhase = true)
        }
    }

    @Test
    fun cueEligibleCriteriaCannotRunWithoutExactCalibration() {
        val fixture = fixture()
        val missingCalibration = fixture.calibrations - BOTTOM_CRITERION
        assertThrows(IllegalArgumentException::class.java) {
            PoseExerciseEvaluationSession(
                exerciseSpec = fixture.spec,
                observationSource = fixture.source,
                calibrations = missingCalibration,
            )
        }
    }

    @Test
    fun mismatchedPinnedViewBecomesUnknownAndCannotProduceCue() {
        val fixture = fixture()
        val session = fixture.session()

        val evaluation = requireNotNull(
            cycleFrames()
                .map { frame -> fixture.attest(frame, criterionViewQualified = false) }
                .map(session::accept)
                .last().cycleEvaluation,
        )
        val bottomResult = evaluation.criterionResults.getValue(BOTTOM_CRITERION).atomicResult

        assertEquals(CriterionState.UNKNOWN, bottomResult.state)
        assertEquals(CriterionUnknownReason.MISSING_CAPABILITY, bottomResult.unknownReason)
        assertTrue(CriterionCapability.VIEW_QUALIFIED in bottomResult.missingCapabilities)
        assertNull(evaluation.graphEvaluation.selectedCue)
    }

    @Test
    fun completedCycleCriterionUsesOrderedHalfOpenScope() {
        val fixture = fixture(includeCompletedCycleCriterion = true)
        val session = fixture.session()
        val evaluation = requireNotNull(
            cycleFrames().map { frame -> fixture.attest(frame) }
                .map(session::accept).last().cycleEvaluation,
        )
        val cycleResult = evaluation.criterionResults.getValue(CYCLE_CRITERION)

        assertEquals(CriterionWindowScope.CompletedCycle, cycleResult.windowScope)
        assertNull(cycleResult.phaseId)
        assertEquals(0L, cycleResult.phaseWindow.startTimestampMs)
        assertEquals(400L, cycleResult.phaseWindow.endTimestampMs)
        assertEquals(4, cycleResult.totalFrameCount)
        assertEquals(4, cycleResult.qualifiedViewFrameCount)
        assertEquals(120.0, cycleResult.atomicResult.aggregatedMeasurement!!, 1e-9)
        assertEquals(listOf(READY_PHASE, BOTTOM_PHASE), evaluation.phaseWindows.map { it.stateId })
        assertEquals(64, evaluation.cycleScopeSha256.length)
    }

    @Test
    fun transientViewLossIsNullEvidenceAndCannotBeBridged() {
        val fixture = fixture(includeCompletedCycleCriterion = true)
        val session = fixture.session()
        val evaluation = requireNotNull(
            cycleFrames().map { frame ->
                fixture.attest(
                    frame = frame,
                    criterionViewQualified = frame.timestampMs != 200L,
                )
            }.map(session::accept).last().cycleEvaluation,
        )
        val cycleResult = evaluation.criterionResults.getValue(CYCLE_CRITERION)

        assertEquals(4, cycleResult.totalFrameCount)
        assertEquals(3, cycleResult.qualifiedViewFrameCount)
        assertEquals(CriterionState.UNKNOWN, cycleResult.atomicResult.state)
        assertEquals(
            CriterionUnknownReason.INSUFFICIENT_TIME_COVERAGE,
            cycleResult.atomicResult.unknownReason,
        )
        assertEquals(0.25, cycleResult.atomicResult.timeCoverage, 1e-9)
        assertEquals(64, cycleResult.sampledEvidenceSha256.length)
        assertNull(evaluation.graphEvaluation.selectedCue)
    }

    @Test
    fun adjacentCompletedCyclesShareOnlyTheHalfOpenBoundary() {
        val fixture = fixture(includeCompletedCycleCriterion = true)
        val session = fixture.session()
        val first = requireNotNull(
            cycleFrames().map { item -> fixture.attest(item) }
                .map(session::accept)
                .last().cycleEvaluation,
        )
        val second = requireNotNull(
            listOf(
                frame(600L, 90.0, 150.0),
                frame(700L, 90.0, 150.0),
                frame(800L, 170.0, 90.0),
                frame(900L, 170.0, 90.0),
            ).map { item -> fixture.attest(item) }
                .map(session::accept)
                .last().cycleEvaluation,
        )

        assertEquals(0L, first.cycleStartTimestampMs)
        assertEquals(400L, first.cycleEndTimestampMs)
        assertEquals(400L, second.cycleStartTimestampMs)
        assertEquals(800L, second.cycleEndTimestampMs)
        assertEquals(
            4,
            first.criterionResults.getValue(CYCLE_CRITERION).totalFrameCount,
        )
        assertEquals(
            4,
            second.criterionResults.getValue(CYCLE_CRITERION).totalFrameCount,
        )
    }

    @Test
    fun foreignSourceAndPersonEpochChangesCannotContaminateCycleEvidence() {
        val fixture = fixture(includeCompletedCycleCriterion = true)
        val session = fixture.session()
        val foreignSource = PoseObservationSource(fixture.spec.observationContract)
        val foreignEpoch = foreignSource.newPersonTrackEpoch()
        val foreignFrame = frame(0L, 170.0, 90.0)
        val foreignObservation = foreignSource.attest(
            frame = foreignFrame,
            personTrackEpoch = foreignEpoch,
            viewQualifications = listOf(
                foreignSource.qualifyView(PHASE_VIEW_CONTRACT, foreignEpoch, foreignFrame.timestampMs),
                foreignSource.qualifyView(VIEW_CONTRACT, foreignEpoch, foreignFrame.timestampMs),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            session.accept(foreignObservation)
        }
        assertEquals(0, session.bufferedFrameCount)

        cycleFrames().take(4).map { fixture.attest(it) }.forEach(session::accept)
        assertEquals(1, session.pendingCriterionCount)
        val nextPersonEpoch = fixture.source.newPersonTrackEpoch()
        val changed = session.accept(
            fixture.attest(
                frame = frame(400L, 170.0, 90.0),
                personTrackEpoch = nextPersonEpoch,
            ),
        )
        assertEquals(
            PosePhaseResetReason.PERSON_CHANGED,
            changed.phaseEvents.filterIsInstance<PosePhaseTrackingReset>().single().reason,
        )
        assertEquals(0, session.pendingCriterionCount)

        val evaluation = requireNotNull(
            cycleFrames(startTimestampMs = 500L)
                .map { item -> fixture.attest(item, personTrackEpoch = nextPersonEpoch) }
                .map(session::accept)
                .last().cycleEvaluation,
        )
        assertTrue(evaluation.personTrackEpoch === nextPersonEpoch)

        val lost = session.accept(
            fixture.attest(
                frame = frame(1_100L, 170.0, 90.0),
                personTrackEpoch = null,
            ),
        )
        assertEquals(
            PosePhaseResetReason.PERSON_LOCK_LOST,
            lost.phaseEvents.filterIsInstance<PosePhaseTrackingReset>().single().reason,
        )
        assertNull(lost.cycleEvaluation)
    }

    @Test
    fun maximumPhaseDurationBoundsRingAndZeroDurationResetIsDiscarded() {
        val boundedFixture = fixture(
            minimumDwellMs = 0L,
            maximumObservationGapMs = 10L,
            maximumPhaseDurationMs = 250L,
        )
        val boundedSession = boundedFixture.session()
        var maximumBufferedFrames = 0
        (0L..1_000L).forEach { timestamp ->
            boundedSession.accept(boundedFixture.attest(frame(timestamp, 170.0, 90.0)))
            maximumBufferedFrames = maxOf(maximumBufferedFrames, boundedSession.bufferedFrameCount)
        }
        assertTrue(maximumBufferedFrames <= 251)
        assertEquals(0, boundedSession.evaluatedCriterionWindowCount)

        val zeroWindowFixture = fixture(
            minimumDwellMs = 0L,
            maximumObservationGapMs = 100L,
        )
        val zeroWindowSession = zeroWindowFixture.session()
        zeroWindowSession.accept(zeroWindowFixture.attest(frame(0L, 170.0, 90.0)))
        val reset = zeroWindowSession.accept(
            zeroWindowFixture.attest(frame(200L, 170.0, 90.0)),
        )
        assertTrue(reset.phaseEvents.any { it is PosePhaseTrackingReset })
        assertEquals(0, zeroWindowSession.evaluatedCriterionWindowCount)
    }

    @Test
    fun hardFrameCeilingInvalidatesOverflowedCycleInsteadOfReleasingPartialEvidence() {
        val fixture = fixture(
            maximumObservationGapMs = 250L,
            maximumPhaseDurationMs = 100_000L,
            includeCompletedCycleCriterion = true,
        )
        val session = fixture.session()
        (0L..MAX_BUFFERED_POSE_FRAMES.toLong()).forEach { timestamp ->
            session.accept(fixture.attest(frame(timestamp, 170.0, 90.0)))
        }
        assertEquals(MAX_BUFFERED_POSE_FRAMES, session.bufferedFrameCount)

        session.accept(fixture.attest(frame(2_148L, 90.0, 150.0)))
        session.accept(fixture.attest(frame(2_248L, 90.0, 150.0)))
        session.accept(fixture.attest(frame(2_348L, 170.0, 90.0)))
        val completed = session.accept(fixture.attest(frame(2_448L, 170.0, 90.0)))

        assertEquals(1, completed.completedCycleCount)
        assertNull(completed.cycleEvaluation)

        val recovered = listOf(
            frame(2_548L, 90.0, 150.0),
            frame(2_648L, 90.0, 150.0),
            frame(2_748L, 170.0, 90.0),
            frame(2_848L, 170.0, 90.0),
        ).map { item -> fixture.attest(item) }
            .map(session::accept)
            .last()
        assertNotNull(recovered.cycleEvaluation)
    }

    @Test
    fun hardFrameCeilingDuringActiveCycleDiscardsThatCycleAndNextCycleRecovers() {
        val fixture = fixture(
            includeCompletedCycleCriterion = true,
            maximumObservationGapMs = 250L,
            maximumPhaseDurationMs = 100_000L,
            maximumCycleDurationMs = 100_000L,
        )
        val session = fixture.session()

        listOf(
            frame(0L, 170.0, 90.0),
            frame(100L, 170.0, 90.0),
            frame(200L, 90.0, 150.0),
            frame(300L, 90.0, 150.0),
        ).map { item -> fixture.attest(item) }
            .forEach(session::accept)

        (301L..2_348L).forEach { timestamp ->
            session.accept(fixture.attest(frame(timestamp, 90.0, 150.0)))
        }
        assertEquals(MAX_BUFFERED_POSE_FRAMES, session.bufferedFrameCount)

        session.accept(fixture.attest(frame(2_448L, 170.0, 90.0)))
        val completed = session.accept(fixture.attest(frame(2_548L, 170.0, 90.0)))

        assertEquals(1, completed.completedCycleCount)
        assertNull(completed.cycleEvaluation)

        val recovered = listOf(
            frame(2_648L, 90.0, 150.0),
            frame(2_748L, 90.0, 150.0),
            frame(2_848L, 170.0, 90.0),
            frame(2_948L, 170.0, 90.0),
        ).map { item -> fixture.attest(item) }
            .map(session::accept)
            .last()

        assertEquals(2, recovered.completedCycleCount)
        assertNotNull(recovered.cycleEvaluation)
    }

    @Test
    fun cycleDurationTimeoutDiscardsFullCycleEvidenceAndNextCycleRecovers() {
        val fixture = fixture(
            includeCompletedCycleCriterion = true,
            maximumObservationGapMs = 5_000L,
            maximumPhaseDurationMs = 2_000L,
            maximumCycleDurationMs = 2_000L,
        )
        val session = fixture.session()
        cycleFrames().take(4).map { fixture.attest(it) }.forEach(session::accept)

        val timedOut = session.accept(fixture.attest(frame(2_101L, 90.0, 150.0)))

        assertEquals(
            PosePhaseResetReason.CYCLE_DURATION_TIMEOUT,
            timedOut.phaseEvents.filterIsInstance<PosePhaseTrackingReset>().single().reason,
        )
        assertNull(timedOut.cycleEvaluation)
        assertEquals(0, session.pendingCriterionCount)

        val recovered = cycleFrames(startTimestampMs = 2_200L)
            .map { item -> fixture.attest(item) }
            .map(session::accept)
            .last()
        assertNotNull(recovered.cycleEvaluation)
    }

    private fun fixture(
        includeUnvisitedSidePhase: Boolean = false,
        includeCompletedCycleCriterion: Boolean = false,
        minimumDwellMs: Long = 100L,
        maximumObservationGapMs: Long = 250L,
        maximumPhaseDurationMs: Long = 2_000L,
        maximumCycleDurationMs: Long = maxOf(maximumPhaseDurationMs, 10_000L),
    ): Fixture {
        val phaseFeature = PoseScalarFeatureSpec.JointAngle(
            featureContractId = "session.phase.left-knee.world.v1",
            coordinateSpace = PoseCoordinateSpace.WORLD,
            first = PoseJoint.LEFT_HIP,
            vertex = PoseJoint.LEFT_KNEE,
            third = PoseJoint.LEFT_ANKLE,
        )
        val criterionFeature = PoseScalarFeatureSpec.JointAngle(
            featureContractId = "session.criterion.right-knee.world.v1",
            coordinateSpace = PoseCoordinateSpace.WORLD,
            first = PoseJoint.RIGHT_HIP,
            vertex = PoseJoint.RIGHT_KNEE,
            third = PoseJoint.RIGHT_ANKLE,
        )
        val states = buildList {
            add(phaseState(READY_PHASE, 160.0, 180.0, minimumDwellMs))
            add(phaseState(BOTTOM_PHASE, 80.0, 100.0, minimumDwellMs))
            if (includeUnvisitedSidePhase) {
                add(phaseState(SIDE_PHASE, 40.0, 60.0, minimumDwellMs))
            }
        }
        val transitions = buildList {
            add(PosePhaseTransition(READY_PHASE, BOTTOM_PHASE))
            if (includeUnvisitedSidePhase) {
                add(PosePhaseTransition(READY_PHASE, SIDE_PHASE))
                add(PosePhaseTransition(SIDE_PHASE, BOTTOM_PHASE))
            }
            add(PosePhaseTransition(BOTTOM_PHASE, READY_PHASE, completesCycle = true))
        }
        val graph = OrderedPosePhaseGraph(states, READY_PHASE, transitions)
        val config = PosePhaseEngineConfig(
            graph = graph,
            minimumQualitySignal = 0.5,
            maximumObservationGapMs = maximumObservationGapMs,
            unusableObservationGraceMs = 100L,
            maximumPhaseDurationMs = maximumPhaseDurationMs,
            maximumCycleDurationMs = maximumCycleDurationMs,
        )
        val phaseCalibration = PoseQualityCalibrationArtifact(
            signalKind = PoseQualitySignalKind.PHASE_GATE_SIGNAL,
            featureSpecSha256 = phaseFeature.featureSpecSha256,
            qualityContractId = "session.phase-quality.v1",
            runtimeDomainId = RUNTIME_DOMAIN,
            knots = listOf(PoseQualityCalibrationKnot(0.0, 1.0)),
        )
        val phaseDriver = PosePhaseDriverBinding(
            featureSpec = phaseFeature,
            engineConfig = config,
            qualityCalibration = phaseCalibration,
            approvedPhaseArtifactSha256 = phaseDriverArtifactSha256(
                phaseFeature,
                config,
                phaseCalibration,
            ),
        )
        val observationContract = observationContract()
        val observationSource = PoseObservationSource(observationContract)

        val bundles = buildList {
            add(
                criterion(
                    READY_CRITERION,
                    CriterionWindowScope.Phase(READY_PHASE),
                    85.0,
                    95.0,
                    criterionFeature,
                ),
            )
            add(
                criterion(
                    BOTTOM_CRITERION,
                    CriterionWindowScope.Phase(BOTTOM_PHASE),
                    145.0,
                    155.0,
                    criterionFeature,
                ),
            )
            if (includeUnvisitedSidePhase) {
                add(
                    criterion(
                        SIDE_CRITERION,
                        CriterionWindowScope.Phase(SIDE_PHASE),
                        40.0,
                        60.0,
                        criterionFeature,
                    ),
                )
            }
            if (includeCompletedCycleCriterion) {
                add(
                    criterion(
                        CYCLE_CRITERION,
                        CriterionWindowScope.CompletedCycle,
                        115.0,
                        125.0,
                        criterionFeature,
                    ),
                )
            }
        }
        val criteria = bundles.map(CriterionBundle::criterion)
        val criterionGraph = PoseCriterionGraph(
            criteria.map { criterion ->
                CriterionNodeSpec(
                    id = criterion.criterionId,
                    severity = CriterionSeverity.CORRECTION,
                    lowSideCueCode = "${criterion.criterionId}.low.v1",
                    highSideCueCode = "${criterion.criterionId}.high.v1",
                )
            },
        )
        val approvedSpec = exerciseSpecSha256(
            specId = SPEC_ID,
            specVersion = 1,
            exercise = AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE,
            observationContract = observationContract,
            phaseDriver = phaseDriver,
            criteria = criteria,
            criterionGraph = criterionGraph,
        )
        val spec = PoseExerciseSpec(
            specId = SPEC_ID,
            specVersion = 1,
            exercise = AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE,
            observationContract = observationContract,
            phaseDriver = phaseDriver,
            criteria = criteria,
            criterionGraph = criterionGraph,
            approvedExerciseSpecSha256 = approvedSpec,
        )
        return Fixture(
            spec = spec,
            source = observationSource,
            personTrackEpoch = observationSource.newPersonTrackEpoch(),
            calibrations = bundles.associate { it.criterion.criterionId to it.calibration },
        )
    }

    private fun criterion(
        id: String,
        windowScope: CriterionWindowScope,
        targetLower: Double,
        targetUpper: Double,
        feature: PoseScalarFeatureSpec,
    ): CriterionBundle {
        val qualityCalibration = PoseQualityCalibrationArtifact(
            signalKind = PoseQualitySignalKind.CRITERION_EVIDENCE_WEIGHT,
            featureSpecSha256 = feature.featureSpecSha256,
            qualityContractId = "$id.quality.v1",
            runtimeDomainId = RUNTIME_DOMAIN,
            knots = listOf(PoseQualityCalibrationKnot(0.0, 1.0)),
        )
        val contract = CriterionCalibrationContract(
            criterionId = id,
            featureContractId = feature.featureContractId,
            featureSpecSha256 = feature.featureSpecSha256,
            samplingContractSha256 = ATTESTED_CRITERION_SAMPLING_CONTRACT_SHA256,
            measurementUnit = "degrees",
            aggregation = CriterionAggregation.WeightedMean,
            qualityContractId = qualityCalibration.qualityContractId,
            qualityCalibrationArtifactSha256 = qualityCalibration.artifactSha256,
            runtimeDomainId = RUNTIME_DOMAIN,
            validMeasurementInterval = MeasurementInterval(0.0, 180.0),
            minimumSampleQuality = 0.5,
            minimumTimeCoverage = 0.4,
            minimumEvidenceMass = 0.4,
            minimumObservableDurationMs = 50L,
            minimumEffectiveSamples = 1.0,
            maximumGapMs = 300L,
            correlationHorizonMs = 100L,
            contractVersion = 1,
        )
        val calibration = CriterionAggregateCalibration(
            contract = contract,
            additiveErrorInterval = MeasurementInterval(0.0, 0.0),
        )
        val target = MeasurementInterval(targetLower, targetUpper)
        val criterionSpec = PoseCriterionSpec(
            calibrationContract = contract,
            approvedCalibrationArtifactSha256 = calibration.artifactSha256,
            approvedEvaluatorSpecSha256 = evaluatorSpecSha256(
                approvedCalibrationArtifactSha256 = calibration.artifactSha256,
                targetInterval = target,
                requiredCapabilities = CAPABILITIES,
            ),
            targetInterval = target,
            requiredCapabilities = CAPABILITIES,
        )
        val binding = PoseCriterionFeatureBinding(criterionSpec, feature, qualityCalibration)
        return CriterionBundle(
            criterion = ExerciseCriterionSpec(
                featureBinding = binding,
                windowScope = windowScope,
                viewContractId = VIEW_CONTRACT,
                observability = CriterionObservability.DIRECT,
                runtimeMode = CriterionRuntimeMode.CUE_ELIGIBLE,
            ),
            calibration = calibration,
        )
    }

    private fun observationContract(): PoseObservationContract = PoseObservationContract(
        runtimeDomainId = RUNTIME_DOMAIN,
        modelArtifactId = "mediapipe.pose-landmarker.full.v1",
        modelArtifactSha256 = SHA_A,
        inferenceOptionsContractId = "mediapipe.video-options.v1",
        inferenceOptionsArtifactSha256 = SHA_A,
        preprocessingContractId = "camerax.viewport-rotation-no-mirror.v1",
        preprocessingArtifactSha256 = SHA_B,
        landmarkSchemaId = "mediapipe.pose-33.v1",
        landmarkSchemaArtifactSha256 = SHA_C,
        supportedCoordinateSpaces = setOf(PoseCoordinateSpace.WORLD),
        phaseViewContractId = PHASE_VIEW_CONTRACT,
        allowedViewContractIds = setOf(PHASE_VIEW_CONTRACT, VIEW_CONTRACT),
        personLockArtifactId = "primary-person.temporal-lock.v1",
        personLockArtifactSha256 = SHA_B,
        viewQualifierArtifactId = "body-yaw.qualifier.v1",
        viewQualifierArtifactSha256 = SHA_C,
    )

    private fun phaseState(
        id: PosePhaseStateId,
        lower: Double,
        upper: Double,
        dwellMs: Long,
    ) = PosePhaseState(
        id = id,
        enterPredicate = PosePhaseEnterPredicate(
            enterInterval = PhaseScalarInterval(lower, upper),
            minimumDwellMs = dwellMs,
        ),
    )

    private fun cycleFrames(startTimestampMs: Long = 0L): List<PoseFrame> = listOf(
        frame(startTimestampMs, 170.0, 90.0),
        frame(startTimestampMs + 100L, 170.0, 90.0),
        frame(startTimestampMs + 200L, 90.0, 150.0),
        frame(startTimestampMs + 300L, 90.0, 150.0),
        frame(startTimestampMs + 400L, 170.0, 90.0),
        frame(startTimestampMs + 500L, 170.0, 90.0),
    )

    private fun frame(timestampMs: Long, leftAngle: Double, rightAngle: Double): PoseFrame =
        PoseFrame(
            timestampMs = timestampMs,
            landmarks = emptyMap(),
            worldLandmarks = linkedMapOf(
                PoseJoint.LEFT_HIP to PoseLandmark(0.0, 1.0, 0.0),
                PoseJoint.LEFT_KNEE to PoseLandmark(0.0, 0.0, 0.0),
                PoseJoint.LEFT_ANKLE to angledPoint(0.0, leftAngle),
                PoseJoint.RIGHT_HIP to PoseLandmark(2.0, 1.0, 0.0),
                PoseJoint.RIGHT_KNEE to PoseLandmark(2.0, 0.0, 0.0),
                PoseJoint.RIGHT_ANKLE to angledPoint(2.0, rightAngle),
            ),
        )

    private fun angledPoint(offsetX: Double, angleDegrees: Double): PoseLandmark {
        val radians = angleDegrees * PI / 180.0
        return PoseLandmark(offsetX + sin(radians), cos(radians), 0.0)
    }

    private data class Fixture(
        val spec: PoseExerciseSpec,
        val source: PoseObservationSource,
        val personTrackEpoch: PosePersonTrackEpoch,
        val calibrations: Map<String, CriterionAggregateCalibration>,
    ) {
        fun session() = PoseExerciseEvaluationSession(
            exerciseSpec = spec,
            observationSource = source,
            calibrations = calibrations,
        )

        fun attest(
            frame: PoseFrame,
            personTrackEpoch: PosePersonTrackEpoch? = this.personTrackEpoch,
            phaseViewQualified: Boolean = true,
            criterionViewQualified: Boolean = true,
        ): AttestedPoseObservation = source.attest(
            frame = frame,
            personTrackEpoch = personTrackEpoch,
            viewQualifications = buildList {
                if (personTrackEpoch != null && phaseViewQualified) {
                    add(source.qualifyView(PHASE_VIEW_CONTRACT, personTrackEpoch, frame.timestampMs))
                }
                if (personTrackEpoch != null && criterionViewQualified) {
                    add(source.qualifyView(VIEW_CONTRACT, personTrackEpoch, frame.timestampMs))
                }
            },
        )
    }

    private data class CriterionBundle(
        val criterion: ExerciseCriterionSpec,
        val calibration: CriterionAggregateCalibration,
    )

    private companion object {
        val READY_PHASE = PosePhaseStateId("ready")
        val BOTTOM_PHASE = PosePhaseStateId("bottom")
        val SIDE_PHASE = PosePhaseStateId("side")
        const val READY_CRITERION = "ready-knee"
        const val BOTTOM_CRITERION = "bottom-knee"
        const val SIDE_CRITERION = "side-knee"
        const val CYCLE_CRITERION = "cycle-knee"
        const val SPEC_ID = "runtime-session.forward-lunge.side.v1"
        const val PHASE_VIEW_CONTRACT = "phase-side-view.body-yaw-window.v1"
        const val VIEW_CONTRACT = "side-view.body-yaw-window.v1"
        const val RUNTIME_DOMAIN = "mediapipe-full.world.side-view.v1"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        val CAPABILITIES = setOf(
            CriterionCapability.POSE_WORLD_RELATIVE,
            CriterionCapability.PRIMARY_PERSON_LOCK,
            CriterionCapability.VIEW_QUALIFIED,
        )
    }
}
