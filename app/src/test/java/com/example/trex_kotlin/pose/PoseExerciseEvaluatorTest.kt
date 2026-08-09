package com.example.trex_kotlin.pose

import com.example.trex_kotlin.catalog.AiHubExercise
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseExerciseEvaluatorTest {
    private val deterministicConfig = PoseEvaluatorConfig(
        emaAlpha = 1.0,
        stableFrames = 2,
        minimumRepDurationMs = 500L,
    )

    @Test
    fun squatCountsOnlyACompleteDepthCycle() {
        val evaluator = SymmetricSquatMotionEvaluator(deterministicConfig)
        val angles = listOf(
            175.0, 175.0,
            150.0, 140.0, 125.0,
            105.0, 105.0,
            130.0, 140.0, 145.0,
            160.0, 165.0,
        )

        val evaluations = angles.mapIndexed { index, angle ->
            evaluator.accept(poseFrame(index * 100L, angle, angle))
        }
        val result = evaluations.last()

        assertEquals(1, result.repCount)
        assertEquals(PosePhase.READY, result.phase)
        assertEquals(PoseTrackingState.TRACKING, result.trackingState)
        assertNotNull(result.lastRepScore)
        assertTrue(result.lastRepScore!! in 0..100)
        assertTrue(
            evaluations.flatMap(Evaluation::feedback)
                .any { it.code == PoseFeedbackCode.REP_COMPLETE },
        )

        // 선 자세를 유지하거나 임계값 부근에서 흔들려도 같은 반복을 다시 세지 않는다.
        var held = result
        listOf(164.0, 159.0, 165.0, 161.0).forEachIndexed { index, angle ->
            held = evaluator.accept(poseFrame(1_200L + index * 100L, angle, angle))
        }
        assertEquals(1, held.repCount)
    }

    @Test
    fun shallowSquatReturnsCoachingWithoutCounting() {
        val evaluator = SymmetricSquatMotionEvaluator(deterministicConfig)
        val angles = listOf(
            175.0, 175.0,
            150.0, 140.0,
            125.0, 135.0, 150.0,
            165.0, 165.0,
        )

        val evaluations = angles.mapIndexed { index, angle ->
            evaluator.accept(poseFrame(index * 100L, angle, angle))
        }

        assertEquals(0, evaluations.last().repCount)
        assertEquals(PosePhase.READY, evaluations.last().phase)
        assertTrue(
            evaluations.flatMap(Evaluation::feedback)
                .any { it.code == PoseFeedbackCode.GO_DEEPER },
        )
    }

    @Test
    fun lowConfidenceFrameDoesNotAdvanceStateMachine() {
        val evaluator = SymmetricSquatMotionEvaluator(deterministicConfig)
        evaluator.accept(poseFrame(0L, 175.0, 175.0))
        val lowConfidence = evaluator.accept(
            poseFrame(
                timestampMs = 100L,
                leftKneeAngle = 100.0,
                rightKneeAngle = 100.0,
                confidence = 0.2,
            ),
        )

        assertEquals(PoseTrackingState.LOW_CONFIDENCE, lowConfidence.trackingState)
        assertEquals(PosePhase.SEEKING, lowConfidence.phase)
        assertEquals(0, lowConfidence.repCount)
        assertTrue(
            lowConfidence.feedback.any { it.code == PoseFeedbackCode.LOW_LANDMARK_CONFIDENCE },
        )
    }

    @Test
    fun briefTrackingLossPreservesCompatibleBottomPhase() {
        val evaluator = SymmetricSquatMotionEvaluator(deterministicConfig)
        listOf(175.0, 175.0, 150.0, 140.0, 105.0, 105.0).forEachIndexed { index, angle ->
            evaluator.accept(poseFrame(index * 100L, angle, angle))
        }

        val lost = evaluator.accept(
            poseFrame(
                timestampMs = 600L,
                leftKneeAngle = 105.0,
                rightKneeAngle = 105.0,
                confidence = 0.2,
            ),
        )
        assertEquals(PosePhase.BOTTOM, lost.phase)

        val firstRecovery = evaluator.accept(poseFrame(700L, 105.0, 105.0))
        val secondRecovery = evaluator.accept(poseFrame(800L, 105.0, 105.0))
        val reacquired = evaluator.accept(poseFrame(900L, 105.0, 105.0))

        assertEquals(PoseTrackingState.LOW_CONFIDENCE, firstRecovery.trackingState)
        assertEquals(PoseTrackingState.LOW_CONFIDENCE, secondRecovery.trackingState)
        assertEquals(PoseTrackingState.TRACKING, reacquired.trackingState)
        assertEquals(PosePhase.BOTTOM, reacquired.phase)
        assertEquals(0, reacquired.repCount)
    }

    @Test
    fun incompatibleStandingPoseAfterBriefLossCannotCompleteFalseRep() {
        val evaluator = SymmetricSquatMotionEvaluator(deterministicConfig)
        listOf(175.0, 175.0, 150.0, 140.0, 105.0, 105.0).forEachIndexed { index, angle ->
            evaluator.accept(poseFrame(index * 100L, angle, angle))
        }
        evaluator.accept(poseFrame(600L, 105.0, 105.0, confidence = 0.2))

        var result = evaluator.accept(poseFrame(700L, 165.0, 165.0))
        listOf(800L, 900L, 1_000L, 1_100L, 1_200L).forEach { timestampMs ->
            result = evaluator.accept(poseFrame(timestampMs, 165.0, 165.0))
        }

        assertEquals(0, result.repCount)
        assertEquals(PosePhase.READY, result.phase)
    }

    @Test
    fun nearStandingPoseAfterBriefLossCannotContinueFromBottom() {
        val evaluator = SymmetricSquatMotionEvaluator(deterministicConfig)
        listOf(175.0, 175.0, 150.0, 140.0, 105.0, 105.0).forEachIndexed { index, angle ->
            evaluator.accept(poseFrame(index * 100L, angle, angle))
        }
        evaluator.accept(poseFrame(600L, 105.0, 105.0, confidence = 0.2))

        var result = evaluator.accept(poseFrame(700L, 150.0, 150.0))
        result = evaluator.accept(poseFrame(800L, 150.0, 150.0))
        listOf(900L, 1_000L, 1_100L, 1_200L, 1_300L, 1_400L).forEach { timestampMs ->
            result = evaluator.accept(poseFrame(timestampMs, 165.0, 165.0))
        }

        assertEquals(0, result.repCount)
        assertEquals(PosePhase.READY, result.phase)
    }

    @Test
    fun graceExpiryOnFirstRecoveredFrameResetsPartialMotion() {
        val evaluator = SymmetricSquatMotionEvaluator(deterministicConfig)
        listOf(175.0, 175.0, 150.0, 140.0, 105.0, 105.0).forEachIndexed { index, angle ->
            evaluator.accept(poseFrame(index * 100L, angle, angle))
        }
        evaluator.accept(poseFrame(600L, 105.0, 105.0, confidence = 0.2))

        val recovered = evaluator.accept(poseFrame(1_000L, 105.0, 105.0))

        assertEquals(PoseTrackingState.LOW_CONFIDENCE, recovered.trackingState)
        assertEquals(PosePhase.SEEKING, recovered.phase)
        assertEquals(0, recovered.repCount)
    }

    @Test
    fun sustainedTrackingLossResetsPartialMotionAfterGracePeriod() {
        val evaluator = SymmetricSquatMotionEvaluator(deterministicConfig)
        var result = evaluator.accept(poseFrame(0L, 175.0, 175.0))
        listOf(175.0, 150.0, 140.0, 105.0, 105.0).forEachIndexed { index, angle ->
            result = evaluator.accept(poseFrame((index + 1) * 100L, angle, angle))
        }
        assertEquals(PosePhase.BOTTOM, result.phase)

        listOf(600L, 700L, 800L, 1_000L).forEach { timestampMs ->
            result = evaluator.accept(
                poseFrame(
                    timestampMs = timestampMs,
                    leftKneeAngle = 105.0,
                    rightKneeAngle = 105.0,
                    confidence = 0.2,
                ),
            )
        }

        assertEquals(PoseTrackingState.LOW_CONFIDENCE, result.trackingState)
        assertEquals(PosePhase.SEEKING, result.phase)
        assertEquals(0, result.repCount)
    }

    @Test
    fun ankleOutsideImageFailsFullBodyGateEvenWhenWorldLandmarksExist() {
        val evaluator = SymmetricSquatMotionEvaluator(deterministicConfig)
        val source = poseFrame(0L, 175.0, 175.0)
        val result = evaluator.accept(
            source.copy(landmarks = normalizedLandmarks(rightAnkleY = 1.12)),
        )

        assertEquals(PoseTrackingState.LOW_CONFIDENCE, result.trackingState)
        assertEquals(PosePhase.SEEKING, result.phase)
        assertTrue(
            result.feedback.any { it.code == PoseFeedbackCode.MOVE_FULL_BODY_INTO_FRAME },
        )
    }

    @Test
    fun evaluatorUsesItsPinnedCoordinateDomainWhenBothDomainsDisagree() {
        val worldEvaluator = SymmetricSquatMotionEvaluator(
            deterministicConfig.copy(coordinateSpace = PoseCoordinateSpace.WORLD),
        )
        val normalizedEvaluator = SymmetricSquatMotionEvaluator(
            deterministicConfig.copy(coordinateSpace = PoseCoordinateSpace.NORMALIZED_IMAGE),
        )
        val angles = listOf(175.0, 175.0, 105.0, 105.0, 105.0, 105.0)

        var worldResult: Evaluation? = null
        var normalizedResult: Evaluation? = null
        angles.forEachIndexed { index, worldAngle ->
            val frame = poseFrame(index * 100L, worldAngle, worldAngle).copy(
                landmarks = normalizedLandmarks(rightAnkleY = 0.95),
                imageWidth = 1_920,
                imageHeight = 1_080,
            )
            worldResult = worldEvaluator.accept(frame)
            normalizedResult = normalizedEvaluator.accept(frame)
        }

        assertEquals(PosePhase.BOTTOM, worldResult?.phase)
        assertEquals(PosePhase.READY, normalizedResult?.phase)
    }

    @Test
    fun degenerateAlignmentReferenceCannotAdvanceOrScoreAsGoodForm() {
        val evaluator = SymmetricSquatMotionEvaluator(deterministicConfig)
        val source = poseFrame(0L, 175.0, 175.0)
        val leftHip = source.worldLandmarks.getValue(PoseJoint.LEFT_HIP)
        val leftAnkle = source.worldLandmarks.getValue(PoseJoint.LEFT_ANKLE)
        val degenerate = source.copy(
            worldLandmarks = source.worldLandmarks + mapOf(
                PoseJoint.RIGHT_HIP to source.worldLandmarks.getValue(PoseJoint.RIGHT_HIP).copy(
                    x = leftHip.x,
                ),
                PoseJoint.RIGHT_ANKLE to source.worldLandmarks
                    .getValue(PoseJoint.RIGHT_ANKLE)
                    .copy(x = leftAnkle.x),
            ),
        )

        val result = evaluator.accept(degenerate)

        assertEquals(PoseTrackingState.LOW_CONFIDENCE, result.trackingState)
        assertEquals(PosePhase.SEEKING, result.phase)
        assertEquals(0, result.repCount)
        assertEquals(null, result.lastRepScore)
    }

    @Test
    fun unknownSquatOnlyGuardDoesNotBlockLungeProfile() {
        val evaluator = AlternatingLungeMotionEvaluator(deterministicConfig)
        val source = poseFrame(0L, 175.0, 175.0, lungeStance = true)
        val leftHip = source.worldLandmarks.getValue(PoseJoint.LEFT_HIP)
        val leftAnkle = source.worldLandmarks.getValue(PoseJoint.LEFT_ANKLE)
        val withoutHorizontalKneeReference = source.copy(
            worldLandmarks = source.worldLandmarks + mapOf(
                PoseJoint.RIGHT_HIP to source.worldLandmarks.getValue(PoseJoint.RIGHT_HIP).copy(
                    x = leftHip.x,
                ),
                PoseJoint.RIGHT_ANKLE to source.worldLandmarks
                    .getValue(PoseJoint.RIGHT_ANKLE)
                    .copy(x = leftAnkle.x),
            ),
        )

        val result = evaluator.accept(withoutHorizontalKneeReference)

        assertEquals(PoseTrackingState.TRACKING, result.trackingState)
        assertEquals(null, result.metrics?.kneeTrackingRatio)
        assertTrue(requireNotNull(result.metrics?.stanceRatio) > 0.0)
    }

    @Test
    fun unknownLungeOnlyGuardDoesNotBlockSquatProfile() {
        val evaluator = SymmetricSquatMotionEvaluator(deterministicConfig)
        val source = poseFrame(0L, 175.0, 175.0)
        val leftShoulder = source.worldLandmarks.getValue(PoseJoint.LEFT_SHOULDER)
        val withoutShoulderScale = source.copy(
            worldLandmarks = source.worldLandmarks +
                (PoseJoint.RIGHT_SHOULDER to leftShoulder),
        )

        val result = evaluator.accept(withoutShoulderScale)

        assertEquals(PoseTrackingState.TRACKING, result.trackingState)
        assertEquals(null, result.metrics?.stanceRatio)
        assertTrue(requireNotNull(result.metrics?.kneeTrackingRatio) > 0.0)
    }

    @Test
    fun lungeCountsAlternatingSidesIndependently() {
        val evaluator = AlternatingLungeMotionEvaluator(deterministicConfig)
        var timestamp = 0L
        var result = evaluator.accept(poseFrame(timestamp, 175.0, 175.0, lungeStance = true))

        fun send(left: Double, right: Double) {
            timestamp += 100L
            result = evaluator.accept(poseFrame(timestamp, left, right, lungeStance = true))
        }

        // 초기 준비 자세와 왼발 런지.
        send(175.0, 175.0)
        listOf(
            145.0 to 165.0,
            135.0 to 155.0,
            115.0 to 150.0,
            100.0 to 130.0,
            95.0 to 125.0,
            125.0 to 135.0,
            135.0 to 145.0,
            145.0 to 155.0,
            160.0 to 160.0,
            165.0 to 165.0,
        ).forEach { (left, right) -> send(left, right) }

        assertEquals(1, result.repCount)
        assertEquals(1, result.repsBySide[PoseSide.LEFT])
        assertEquals(0, result.repsBySide[PoseSide.RIGHT])

        // 오른발 런지.
        listOf(
            165.0 to 145.0,
            155.0 to 135.0,
            150.0 to 115.0,
            130.0 to 100.0,
            125.0 to 95.0,
            135.0 to 125.0,
            145.0 to 135.0,
            155.0 to 145.0,
            160.0 to 160.0,
            165.0 to 165.0,
        ).forEach { (left, right) -> send(left, right) }

        assertEquals(2, result.repCount)
        assertEquals(1, result.repsBySide[PoseSide.LEFT])
        assertEquals(1, result.repsBySide[PoseSide.RIGHT])
        assertTrue(result.score!! in 0..100)
    }

    @Test
    fun trackingGapAbandonsPartialRepButKeepsCompletedCount() {
        val evaluator = SymmetricSquatMotionEvaluator(deterministicConfig)
        listOf(175.0, 175.0, 150.0, 140.0, 120.0).forEachIndexed { index, angle ->
            evaluator.accept(poseFrame(index * 100L, angle, angle))
        }

        val reacquired = evaluator.accept(poseFrame(2_000L, 100.0, 100.0))
        assertEquals(PosePhase.SEEKING, reacquired.phase)
        assertEquals(0, reacquired.repCount)

        val ready = evaluator.accept(poseFrame(2_100L, 175.0, 175.0))
        assertEquals(PosePhase.SEEKING, ready.phase)
    }

    @Test
    fun interruptKeepsCompletedCountButDropsCurrentMotion() {
        val evaluator = SymmetricSquatMotionEvaluator(deterministicConfig)
        val completeRep = listOf(
            175.0, 175.0,
            150.0, 140.0, 105.0, 105.0,
            135.0, 145.0, 160.0, 165.0,
        )
        var result = completeRep.mapIndexed { index, angle ->
            evaluator.accept(poseFrame(index * 100L, angle, angle))
        }.last()
        assertEquals(1, result.repCount)

        evaluator.accept(poseFrame(1_000L, 145.0, 145.0))
        evaluator.accept(poseFrame(1_100L, 135.0, 135.0))
        evaluator.interrupt()
        result = evaluator.accept(poseFrame(1_200L, 165.0, 165.0))

        assertEquals(1, result.repCount)
        assertEquals(PosePhase.SEEKING, result.phase)
        assertNotNull(result.score)
    }

    @Test
    fun factoryCreatesOnlyExplicitlySupportedAiHubEvaluators() {
        assertEquals(
            setOf(
                AiHubExercise.BARBELL_SQUAT,
                AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE,
                AiHubExercise.STEP_BACKWARD_DYNAMIC_LUNGE,
            ),
            PoseEvaluatorFactory.supportedExercises,
        )
        assertTrue(PoseEvaluatorFactory.create(AiHubExercise.BARBELL_SQUAT) is SymmetricSquatMotionEvaluator)
        assertTrue(
            PoseEvaluatorFactory.create(AiHubExercise.STEP_FORWARD_DYNAMIC_LUNGE) is AlternatingLungeMotionEvaluator,
        )
        assertTrue(
            PoseEvaluatorFactory.create(AiHubExercise.STEP_BACKWARD_DYNAMIC_LUNGE) is AlternatingLungeMotionEvaluator,
        )
        assertEquals(null, PoseEvaluatorFactory.create(AiHubExercise.PLANK))
    }

    private fun poseFrame(
        timestampMs: Long,
        leftKneeAngle: Double,
        rightKneeAngle: Double,
        torsoLean: Double = 0.0,
        confidence: Double = 1.0,
        lungeStance: Boolean = false,
    ): PoseFrame {
        val ankleDepth = if (lungeStance) 0.45 else 0.0
        val leftAnkle = TestPoint(-0.20, 0.0, ankleDepth)
        val rightAnkle = TestPoint(0.20, 0.0, -ankleDepth)
        val leftKnee = TestPoint(-0.20, 0.45, ankleDepth)
        val rightKnee = TestPoint(0.20, 0.45, -ankleDepth)
        val leftHip = hipForKneeAngle(leftKnee, leftKneeAngle)
        val rightHip = hipForKneeAngle(rightKnee, rightKneeAngle)
        val hipCenter = TestPoint(
            (leftHip.x + rightHip.x) / 2.0,
            (leftHip.y + rightHip.y) / 2.0,
            (leftHip.z + rightHip.z) / 2.0,
        )
        val leanDepth = tan(torsoLean * PI / 180.0) * 0.60
        val leftShoulder = TestPoint(-0.20, hipCenter.y + 0.60, hipCenter.z + leanDepth)
        val rightShoulder = TestPoint(0.20, hipCenter.y + 0.60, hipCenter.z + leanDepth)

        fun landmark(point: TestPoint) = PoseLandmark(
            x = point.x,
            y = point.y,
            z = point.z,
            visibility = confidence,
            presence = confidence,
        )

        return PoseFrame(
            timestampMs = timestampMs,
            landmarks = emptyMap(),
            worldLandmarks = mapOf(
                PoseJoint.LEFT_SHOULDER to landmark(leftShoulder),
                PoseJoint.RIGHT_SHOULDER to landmark(rightShoulder),
                PoseJoint.LEFT_HIP to landmark(leftHip),
                PoseJoint.RIGHT_HIP to landmark(rightHip),
                PoseJoint.LEFT_KNEE to landmark(leftKnee),
                PoseJoint.RIGHT_KNEE to landmark(rightKnee),
                PoseJoint.LEFT_ANKLE to landmark(leftAnkle),
                PoseJoint.RIGHT_ANKLE to landmark(rightAnkle),
            ),
        )
    }

    private fun hipForKneeAngle(knee: TestPoint, angleDegrees: Double): TestPoint {
        val angle = angleDegrees * PI / 180.0
        val thighLength = 0.45
        return TestPoint(
            x = knee.x,
            y = knee.y - cos(angle) * thighLength,
            z = knee.z + sin(angle) * thighLength,
        )
    }

    private fun normalizedLandmarks(rightAnkleY: Double): Map<PoseJoint, PoseLandmark> = mapOf(
        PoseJoint.LEFT_SHOULDER to PoseLandmark(0.40, 0.20),
        PoseJoint.RIGHT_SHOULDER to PoseLandmark(0.60, 0.20),
        PoseJoint.LEFT_HIP to PoseLandmark(0.43, 0.45),
        PoseJoint.RIGHT_HIP to PoseLandmark(0.57, 0.45),
        PoseJoint.LEFT_KNEE to PoseLandmark(0.43, 0.70),
        PoseJoint.RIGHT_KNEE to PoseLandmark(0.57, 0.70),
        PoseJoint.LEFT_ANKLE to PoseLandmark(0.43, 0.95),
        PoseJoint.RIGHT_ANKLE to PoseLandmark(0.57, rightAnkleY),
    )

    private data class TestPoint(val x: Double, val y: Double, val z: Double)
}
