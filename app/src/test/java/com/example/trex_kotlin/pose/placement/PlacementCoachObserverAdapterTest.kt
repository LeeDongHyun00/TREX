package com.example.trex_kotlin.pose.placement

import com.example.trex_kotlin.camera.FRONTAL_AXIS_VIEW_CONTRACT_ID
import com.example.trex_kotlin.camera.FULL_BODY_LATERAL_VIEW_CONTRACT_ID
import com.example.trex_kotlin.camera.FULL_BODY_PHASE_VIEW_CONTRACT_ID
import com.example.trex_kotlin.camera.MediaPipePoseObserver
import com.example.trex_kotlin.camera.PoseCandidateBatch
import com.example.trex_kotlin.camera.PoseObserverTrackingStatus
import com.example.trex_kotlin.camera.PoseObserverUnknownReason
import com.example.trex_kotlin.camera.PoseObserverUpdate
import com.example.trex_kotlin.camera.PosePersonLockConfig
import com.example.trex_kotlin.camera.PoseViewQualifierConfig
import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import com.example.trex_kotlin.pose.runtime.PoseObservationContract
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives a real observer so the coach's assumptions about its diagnostics stay true, rather than
 * only true of the hand-written signals the policy test uses.
 */
class PlacementCoachObserverAdapterTest {

    @Test
    fun coachWalksFromAcquiringToReachedThroughBothDwells() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)

        val acquiring = coach(fixture.accept(0L), PlacementCoachGoal.FULL_BODY)
        assertEquals(PlacementCoachStage.HOLDING, acquiring.stage)
        assertEquals(PlacementCoachGuidance.HOLD_STILL, acquiring.guidance)

        val settlingView = coach(fixture.accept(100L), PlacementCoachGoal.FULL_BODY)
        assertEquals(PlacementCoachStage.HOLDING, settlingView.stage)
        assertEquals(PlacementCoachGuidance.KEEP_BODY_FACING_STEADY, settlingView.guidance)

        val reached = coach(fixture.accept(180L), PlacementCoachGoal.FULL_BODY)
        assertEquals(PlacementCoachStage.REACHED, reached.stage)
        assertEquals(PlacementCoachGuidance.FULL_BODY_REACHED, reached.guidance)
    }

    @Test
    fun aFrontFacingPersonReachesTheFullBodyGoal() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)
        fixture.accept(0L)
        fixture.accept(100L)
        val update = fixture.accept(180L)

        // The observer mints the framing token before it looks at orientation and never takes it
        // back, so a front-facing person always carries this reason while fully qualified.
        assertTrue(PoseObserverUnknownReason.FRONT_REAR_UNRESOLVED in update.unknownReasons)
        assertTrue(update.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))

        val display = coach(update, PlacementCoachGoal.FULL_BODY)
        assertEquals(PlacementCoachStage.REACHED, display.stage)
        assertEquals(
            setOf(PoseObserverUnknownReason.FRONT_REAR_UNRESOLVED),
            display.suppressedReasons,
        )
    }

    @Test
    fun theLateralGoalAsksAFrontFacingPersonToTurn() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)
        fixture.accept(0L)
        fixture.accept(100L)
        val update = fixture.accept(180L)

        val display = coach(update, PlacementCoachGoal.LATERAL)
        assertEquals(PlacementCoachStage.ADJUSTING, display.stage)
        assertEquals(PlacementCoachGuidance.TURN_SIDEWAYS, display.guidance)
    }

    @Test
    fun aSidewaysPersonReachesTheLateralGoal() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)
        fixture.accept(0L, yawDegrees = 75.0)
        fixture.accept(100L, yawDegrees = 75.0)
        val update = fixture.accept(180L, yawDegrees = 75.0)

        assertTrue(update.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))

        val display = coach(update, PlacementCoachGoal.LATERAL)
        assertEquals(PlacementCoachStage.REACHED, display.stage)
        assertEquals(PlacementCoachGuidance.LATERAL_REACHED, display.guidance)
        assertTrue(display.suppressedReasons.isEmpty())
    }

    @Test
    fun aSecondPersonLeavesTheReachedGoalOnTheSameFrame() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)
        fixture.accept(0L)
        fixture.accept(100L)
        assertEquals(PlacementCoachStage.REACHED, coach(fixture.accept(180L), PlacementCoachGoal.FULL_BODY).stage)

        val crowded = fixture.observer.accept(
            batch(
                timestampMs = 200L,
                frames = listOf(frame(200L), frame(200L, centerX = 0.72)),
            ),
        )

        val display = coach(crowded, PlacementCoachGoal.FULL_BODY)
        assertEquals(PlacementCoachStage.ADJUSTING, display.stage)
        assertEquals(PlacementCoachGuidance.ONLY_ONE_PERSON, display.guidance)
    }

    @Test
    fun croppedFeetLeaveTheReachedGoalOnTheSameFrame() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)
        fixture.accept(0L, yawDegrees = 75.0)
        fixture.accept(100L, yawDegrees = 75.0)
        assertEquals(
            PlacementCoachStage.REACHED,
            coach(fixture.accept(180L, yawDegrees = 75.0), PlacementCoachGoal.LATERAL).stage,
        )

        val cropped = fixture.accept(200L, yawDegrees = 75.0, cropFeet = true)
        val display = coach(cropped, PlacementCoachGoal.LATERAL)

        assertEquals(PlacementCoachStage.ADJUSTING, display.stage)
        assertEquals(PlacementCoachGuidance.FIT_WHOLE_BODY, display.guidance)
    }

    @Test
    fun aViewTokenAlwaysImpliesATrackedPersonLock() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)
        val updates = listOf(
            fixture.accept(0L),
            fixture.accept(100L),
            fixture.accept(180L),
            fixture.accept(200L, cropFeet = true),
            fixture.accept(600L),
        )

        for (update in updates) {
            val signal = update.toPlacementObservedSignal()
            if (signal.fullBodyViewQualified) {
                assertTrue(signal.hasPrimaryPersonLock)
                assertEquals(PoseObserverTrackingStatus.TRACKED, signal.trackingStatus)
            }
            if (signal.lateralViewQualified) {
                assertTrue(signal.fullBodyViewQualified)
            }
        }
    }

    @Test
    fun theAdapterMakesNoDecisions() {
        val sources = listOf("src/main/java", "app/src/main/java")
            .map(::File)
            .firstOrNull(File::isDirectory)
            ?: error("Main sources not found from ${File("").absolutePath}")
        val adapter = sources
            .resolve("com/example/trex_kotlin/pose/placement/PlacementCoachObserverAdapter.kt")
        assertTrue("Adapter source not found", adapter.isFile)

        val body = adapter.readText().lineSequence()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .joinToString("\n")

        assertFalse("The adapter must not branch", body.contains("if ("))
        assertFalse("The adapter must not branch", body.contains("when "))
        assertFalse("The adapter must not branch", body.contains("?:"))
    }

    private fun coach(update: PoseObserverUpdate, goal: PlacementCoachGoal) =
        PlacementCoachDisplayPolicy.resolve(
            goal = goal,
            cameraState = PlacementCameraState.RUNNING,
            observed = update.toPlacementObservedSignal(),
        )

    private fun fixture(acquisitionDwellMs: Long, viewDwellMs: Long): Fixture {
        val lock = PosePersonLockConfig(acquisitionDwellMs = acquisitionDwellMs)
        val view = PoseViewQualifierConfig(qualificationDwellMs = viewDwellMs)
        val source = PoseObservationSource(
            contract(personLockSha = lock.artifactSha256, viewQualifierSha = view.artifactSha256),
        )
        return Fixture(MediaPipePoseObserver(source, lock, view))
    }

    private inner class Fixture(val observer: MediaPipePoseObserver) {
        fun accept(
            timestampMs: Long,
            centerX: Double = 0.50,
            yawDegrees: Double = 0.0,
            cropFeet: Boolean = false,
        ): PoseObserverUpdate = observer.accept(
            batch(
                timestampMs = timestampMs,
                frames = listOf(
                    frame(
                        timestampMs = timestampMs,
                        centerX = centerX,
                        yawDegrees = yawDegrees,
                        cropFeet = cropFeet,
                    ),
                ),
            ),
        )
    }

    private fun contract(personLockSha: String, viewQualifierSha: String): PoseObservationContract =
        PoseObservationContract(
            runtimeDomainId = "trex.test-observer.cpu.v1",
            modelArtifactId = "mediapipe.pose-landmarker.full.task.v1",
            modelArtifactSha256 = SHA_A,
            inferenceOptionsContractId = "trex.test-options.v1",
            inferenceOptionsArtifactSha256 = SHA_A,
            preprocessingContractId = "trex.test-preprocess.v1",
            preprocessingArtifactSha256 = SHA_A,
            landmarkSchemaId = "mediapipe.pose-landmarker.33.v1",
            landmarkSchemaArtifactSha256 = SHA_A,
            supportedCoordinateSpaces = setOf(
                PoseCoordinateSpace.NORMALIZED_IMAGE,
                PoseCoordinateSpace.WORLD,
            ),
            phaseViewContractId = FULL_BODY_PHASE_VIEW_CONTRACT_ID,
            allowedViewContractIds = setOf(
                FULL_BODY_PHASE_VIEW_CONTRACT_ID,
                FULL_BODY_LATERAL_VIEW_CONTRACT_ID,
                FRONTAL_AXIS_VIEW_CONTRACT_ID,
            ),
            personLockArtifactId = "trex.primary-person.temporal-lock.v1",
            personLockArtifactSha256 = personLockSha,
            viewQualifierArtifactId = "trex.body-view.qualifier.v1",
            viewQualifierArtifactSha256 = viewQualifierSha,
        )

    private fun batch(timestampMs: Long, frames: List<PoseFrame>): PoseCandidateBatch =
        PoseCandidateBatch(
            timestampMs = timestampMs,
            candidates = frames,
            rawCandidateCount = frames.size,
            geometryContext = geometryContext(),
        )

    private fun frame(
        timestampMs: Long,
        centerX: Double = 0.50,
        yawDegrees: Double = 0.0,
        cropFeet: Boolean = false,
    ): PoseFrame {
        val geometry = geometryContext()
        return PoseFrame(
            timestampMs = timestampMs,
            landmarks = normalizedSkeleton(centerX, cropFeet),
            worldLandmarks = worldSkeleton(yawDegrees),
            imageWidth = geometry.outputImageWidth,
            imageHeight = geometry.outputImageHeight,
            rotationDegrees = geometry.outputRotationDegrees,
            isMirrored = geometry.displayMirrored,
        )
    }

    private fun geometryContext(): PoseCameraGeometryContext = PoseCameraGeometryContext(
        sourceImageWidth = IMAGE_WIDTH,
        sourceImageHeight = IMAGE_HEIGHT,
        cropLeft = 0,
        cropTop = 0,
        cropRightExclusive = IMAGE_WIDTH,
        cropBottomExclusive = IMAGE_HEIGHT,
        inputRotationDegrees = 0,
        outputImageWidth = IMAGE_WIDTH,
        outputImageHeight = IMAGE_HEIGHT,
        inferencePixelsMirrored = false,
        displayMirrored = true,
        preprocessingArtifactSha256 = SHA_A,
    )

    private fun normalizedSkeleton(centerX: Double, cropFeet: Boolean): Map<PoseJoint, PoseLandmark> {
        val points = mutableMapOf<PoseJoint, Pair<Double, Double>>()
        fun bilateral(left: PoseJoint, right: PoseJoint, halfWidth: Double, y: Double) {
            points[left] = centerX - halfWidth to y
            points[right] = centerX + halfWidth to y
        }
        points[PoseJoint.NOSE] = centerX to 0.10
        bilateral(PoseJoint.LEFT_EAR, PoseJoint.RIGHT_EAR, 0.035, 0.11)
        bilateral(PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER, 0.12, 0.25)
        bilateral(PoseJoint.LEFT_ELBOW, PoseJoint.RIGHT_ELBOW, 0.17, 0.40)
        bilateral(PoseJoint.LEFT_WRIST, PoseJoint.RIGHT_WRIST, 0.18, 0.55)
        bilateral(PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP, 0.075, 0.50)
        bilateral(PoseJoint.LEFT_KNEE, PoseJoint.RIGHT_KNEE, 0.075, 0.72)
        bilateral(PoseJoint.LEFT_ANKLE, PoseJoint.RIGHT_ANKLE, 0.075, 0.90)
        bilateral(PoseJoint.LEFT_HEEL, PoseJoint.RIGHT_HEEL, 0.075, if (cropFeet) 1.02 else 0.94)
        bilateral(
            PoseJoint.LEFT_FOOT_INDEX,
            PoseJoint.RIGHT_FOOT_INDEX,
            0.09,
            if (cropFeet) 1.04 else 0.96,
        )
        return PoseJoint.entries.associateWith { joint ->
            val (x, y) = points[joint] ?: (centerX to 0.20)
            PoseLandmark(x, y, 0.0, 1.0, 1.0)
        }
    }

    private fun worldSkeleton(yawDegrees: Double): Map<PoseJoint, PoseLandmark> {
        val yaw = Math.toRadians(yawDegrees)
        val axisX = cos(yaw)
        val axisZ = sin(yaw)
        val normalized = normalizedSkeleton(centerX = 0.5, cropFeet = false)
        return PoseJoint.entries.associateWith { joint ->
            val source = normalized.getValue(joint)
            val side = when {
                joint.name.startsWith("LEFT_") -> -1.0
                joint.name.startsWith("RIGHT_") -> 1.0
                else -> 0.0
            }
            val lateralDistance = abs(source.x - 0.5)
            PoseLandmark(
                x = side * lateralDistance * axisX,
                y = source.y - 0.5,
                z = side * lateralDistance * axisZ,
                visibility = 1.0,
                presence = 1.0,
            )
        }
    }

    private companion object {
        const val IMAGE_WIDTH = 640
        const val IMAGE_HEIGHT = 480
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
