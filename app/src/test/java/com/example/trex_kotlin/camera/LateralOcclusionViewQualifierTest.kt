package com.example.trex_kotlin.camera

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import com.example.trex_kotlin.pose.runtime.PoseObservationContract
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A person standing sideways self-occludes their far half. These tests pin the v2 qualifier and
 * person-lock behaviour that makes a real side pose reachable: best-side visibility gates, axis
 * yaws that tolerate a weakly seen far side, and a lateral latch with an exit threshold so the
 * token set stops flapping at the entry boundary.
 */
class LateralOcclusionViewQualifierTest {

    @Test
    fun sidePoseWithWeaklySeenFarSideStillLocksAndMintsLateral() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)

        fixture.accept(0L, yawDegrees = 75.0, farSideConfidence = 0.40)
        val locked = fixture.accept(100L, yawDegrees = 75.0, farSideConfidence = 0.40)
        assertEquals(PoseObserverTrackingStatus.TRACKED, locked.trackingStatus)
        assertTrue(locked.observation.hasPrimaryPersonLock)

        val qualified = fixture.accept(180L, yawDegrees = 75.0, farSideConfidence = 0.40)
        assertTrue(qualified.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))
        assertTrue(qualified.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))
        assertEquals(PoseObserverView.LATERAL, qualified.view)
        assertFalse(
            PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE in qualified.unknownReasons,
        )
    }

    @Test
    fun occludedFarSideCoordinatesAreNotHeldToTheFrameBounds() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)

        // The far foot is hallucinated below the frame, but at 0.40 confidence it is a guess and
        // must not turn into BODY_OUT_OF_FRAME.
        fixture.accept(0L, yawDegrees = 75.0, farSideConfidence = 0.40, farFootY = 1.05)
        fixture.accept(100L, yawDegrees = 75.0, farSideConfidence = 0.40, farFootY = 1.05)
        val qualified = fixture.accept(180L, yawDegrees = 75.0, farSideConfidence = 0.40, farFootY = 1.05)

        assertTrue(qualified.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))
        assertFalse(PoseObserverUnknownReason.BODY_OUT_OF_FRAME in qualified.unknownReasons)
    }

    @Test
    fun fullyOccludedFarSideKeepsBaseTokenButAbstainsFromYaw() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)

        // Below the axis floor both torso axes abstain: the framing token survives, but the
        // qualifier refuses to guess an orientation from one-sided world data.
        fixture.accept(0L, yawDegrees = 75.0, farSideConfidence = 0.20)
        fixture.accept(100L, yawDegrees = 75.0, farSideConfidence = 0.20)
        val qualified = fixture.accept(180L, yawDegrees = 75.0, farSideConfidence = 0.20)

        assertTrue(qualified.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))
        assertFalse(qualified.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))
        assertTrue(
            PoseObserverUnknownReason.WORLD_LANDMARKS_UNAVAILABLE in qualified.unknownReasons,
        )
    }

    @Test
    fun lowConfidenceNoseStillRevokesEveryToken() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)

        fixture.accept(0L, yawDegrees = 75.0, farSideConfidence = 0.40)
        fixture.accept(100L, yawDegrees = 75.0, farSideConfidence = 0.40)
        fixture.accept(180L, yawDegrees = 75.0, farSideConfidence = 0.40)

        val degraded = fixture.accept(
            220L,
            yawDegrees = 75.0,
            farSideConfidence = 0.40,
            noseConfidence = 0.10,
        )

        assertFalse(degraded.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))
        assertFalse(degraded.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))
        assertTrue(
            PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE in degraded.unknownReasons,
        )
    }

    @Test
    fun frontFacingFullyVisiblePoseIsUnchangedByTheOcclusionPolicy() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)

        fixture.accept(0L, yawDegrees = 0.0)
        fixture.accept(100L, yawDegrees = 0.0)
        val qualified = fixture.accept(180L, yawDegrees = 0.0)

        assertEquals(PoseObserverView.FRONTAL_AXIS, qualified.view)
        assertTrue(qualified.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))
        assertFalse(qualified.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))
        assertTrue(PoseObserverUnknownReason.FRONT_REAR_UNRESOLVED in qualified.unknownReasons)
    }

    @Test
    fun lateralLatchHoldsThroughABoundaryDipInsteadOfFlapping() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)
        fixture.accept(0L, yawDegrees = 75.0)
        fixture.accept(100L, yawDegrees = 75.0)
        assertTrue(
            fixture.accept(180L, yawDegrees = 75.0)
                .observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID),
        )

        // The measured yaw falls to 50, between the 45-degree exit and 60-degree entry
        // thresholds. Without the latch every one of these frames would drop the lateral token
        // and restart the identity-based dwell.
        var timestamp = 180L
        repeat(10) {
            timestamp += 40L
            val update = fixture.accept(timestamp, yawDegrees = 50.0)
            assertTrue(
                "Lateral token lost at ${timestamp}ms during a boundary dip",
                update.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID),
            )
            assertEquals(PoseObserverView.LATERAL, update.view)
        }
    }

    @Test
    fun turningAwayReleasesTheLatchAndFallsBackToTheBaseToken() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)
        fixture.accept(0L, yawDegrees = 75.0)
        fixture.accept(100L, yawDegrees = 75.0)
        fixture.accept(180L, yawDegrees = 75.0)

        // Settle the smoothed yaw near 50 first, then turn decisively toward the camera.
        var timestamp = 180L
        repeat(10) {
            timestamp += 40L
            fixture.accept(timestamp, yawDegrees = 50.0)
        }

        timestamp += 40L
        val released = fixture.accept(timestamp, yawDegrees = 20.0)
        assertFalse(released.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))

        // The token set changed, so the base token honestly re-dwells before returning.
        timestamp += 40L
        fixture.accept(timestamp, yawDegrees = 20.0)
        timestamp += 80L
        val settled = fixture.accept(timestamp, yawDegrees = 20.0)
        assertTrue(settled.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))
        assertFalse(settled.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))
    }

    @Test
    fun aOneFrameVisibilityBlipDoesNotDestroyTheLatchOrTheSmoothing() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)
        fixture.accept(0L, yawDegrees = 75.0)
        fixture.accept(100L, yawDegrees = 75.0)
        fixture.accept(180L, yawDegrees = 75.0)

        // One frame with a low-confidence nose abstains from every token, but the person lock
        // never broke, so the latch and the smoothed yaw must survive the blip.
        val blip = fixture.accept(220L, yawDegrees = 50.0, noseConfidence = 0.10)
        assertFalse(blip.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))
        assertTrue(blip.observation.hasPrimaryPersonLock)

        // At a measured 50 degrees — inside the hysteresis band — the surviving latch keeps the
        // view lateral, and the token set re-stabilises after one honest dwell.
        fixture.accept(260L, yawDegrees = 50.0)
        val recovered = fixture.accept(360L, yawDegrees = 50.0)
        assertEquals(PoseObserverView.LATERAL, recovered.view)
        assertTrue(recovered.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))
    }

    @Test
    fun theLatchDoesNotSurviveALockReset() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)
        fixture.accept(0L, yawDegrees = 75.0)
        fixture.accept(100L, yawDegrees = 75.0)
        assertTrue(
            fixture.accept(180L, yawDegrees = 75.0)
                .observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID),
        )

        // A frame gap beyond the observer tolerance clears the lock, the view state and the
        // latch. After reacquisition a 50-degree pose is oblique, not remembered-lateral.
        val gap = fixture.accept(800L, yawDegrees = 50.0)
        assertEquals(PoseObserverTrackingStatus.TRACK_DISCONTINUITY, gap.trackingStatus)

        fixture.accept(840L, yawDegrees = 50.0)
        fixture.accept(940L, yawDegrees = 50.0)
        val reacquired = fixture.accept(1_020L, yawDegrees = 50.0)
        assertEquals(PoseObserverTrackingStatus.TRACKED, reacquired.trackingStatus)
        assertTrue(reacquired.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))
        assertFalse(reacquired.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))
        assertEquals(PoseObserverView.OBLIQUE, reacquired.view)
    }

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
            yawDegrees: Double,
            farSideConfidence: Double = 1.0,
            farFootY: Double? = null,
            noseConfidence: Double? = null,
        ): PoseObserverUpdate = observer.accept(
            PoseCandidateBatch(
                timestampMs = timestampMs,
                candidates = listOf(
                    frame(
                        timestampMs = timestampMs,
                        yawDegrees = yawDegrees,
                        farSideConfidence = farSideConfidence,
                        farFootY = farFootY,
                        noseConfidence = noseConfidence,
                    ),
                ),
                rawCandidateCount = 1,
                geometryContext = geometryContext(),
            ),
        )
    }

    /** The far (self-occluded) side is the LEFT side throughout these fixtures. */
    private fun frame(
        timestampMs: Long,
        yawDegrees: Double,
        farSideConfidence: Double,
        farFootY: Double?,
        noseConfidence: Double?,
    ): PoseFrame {
        val geometry = geometryContext()
        val normalized = normalizedSkeleton(farSideConfidence, farFootY, noseConfidence)
        return PoseFrame(
            timestampMs = timestampMs,
            landmarks = normalized,
            worldLandmarks = worldSkeleton(yawDegrees, farSideConfidence),
            imageWidth = geometry.outputImageWidth,
            imageHeight = geometry.outputImageHeight,
            rotationDegrees = geometry.outputRotationDegrees,
            isMirrored = geometry.displayMirrored,
        )
    }

    private fun normalizedSkeleton(
        farSideConfidence: Double,
        farFootY: Double?,
        noseConfidence: Double?,
    ): Map<PoseJoint, PoseLandmark> {
        val centerX = 0.50
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
        bilateral(PoseJoint.LEFT_HEEL, PoseJoint.RIGHT_HEEL, 0.075, 0.94)
        bilateral(PoseJoint.LEFT_FOOT_INDEX, PoseJoint.RIGHT_FOOT_INDEX, 0.09, 0.96)
        if (farFootY != null) {
            points[PoseJoint.LEFT_HEEL] = centerX - 0.075 to farFootY
            points[PoseJoint.LEFT_FOOT_INDEX] = centerX - 0.09 to farFootY
        }
        return PoseJoint.entries.associateWith { joint ->
            val (x, y) = points[joint] ?: (centerX to 0.20)
            val confidence = when {
                joint == PoseJoint.NOSE && noseConfidence != null -> noseConfidence
                joint.name.startsWith("LEFT_") -> farSideConfidence
                else -> 1.0
            }
            PoseLandmark(x, y, 0.0, confidence, confidence)
        }
    }

    private fun worldSkeleton(
        yawDegrees: Double,
        farSideConfidence: Double,
    ): Map<PoseJoint, PoseLandmark> {
        val yaw = Math.toRadians(yawDegrees)
        val axisX = cos(yaw)
        val axisZ = sin(yaw)
        val normalized = normalizedSkeleton(farSideConfidence = 1.0, farFootY = null, noseConfidence = null)
        return PoseJoint.entries.associateWith { joint ->
            val source = normalized.getValue(joint)
            val side = when {
                joint.name.startsWith("LEFT_") -> -1.0
                joint.name.startsWith("RIGHT_") -> 1.0
                else -> 0.0
            }
            val lateralDistance = abs(source.x - 0.5)
            val confidence = if (joint.name.startsWith("LEFT_")) farSideConfidence else 1.0
            PoseLandmark(
                x = side * lateralDistance * axisX,
                y = source.y - 0.5,
                z = side * lateralDistance * axisZ,
                visibility = confidence,
                presence = confidence,
            )
        }
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
            ),
            personLockArtifactId = "trex.primary-person.temporal-lock.v1",
            personLockArtifactSha256 = personLockSha,
            viewQualifierArtifactId = "trex.body-view.qualifier.v1",
            viewQualifierArtifactSha256 = viewQualifierSha,
        )

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

    private companion object {
        const val IMAGE_WIDTH = 640
        const val IMAGE_HEIGHT = 480
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
