package com.example.trex_kotlin.camera

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import com.example.trex_kotlin.pose.runtime.PoseObservationContract
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AttestedPoseObserverTest {
    @Test
    fun stableSingleCandidateRequiresPersonAndViewDwells() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)

        assertStatus(fixture.accept(0L), PoseObserverTrackingStatus.ACQUIRING, locked = false)
        val locked = fixture.accept(100L)
        assertStatus(locked, PoseObserverTrackingStatus.TRACKED, locked = true)
        assertFalse(locked.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))
        assertTrue(
            PoseObserverUnknownReason.VIEW_QUALIFICATION_STABILIZING in locked.unknownReasons,
        )

        val qualified = fixture.accept(180L)
        assertStatus(qualified, PoseObserverTrackingStatus.TRACKED, locked = true)
        assertTrue(qualified.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))
        assertFalse(qualified.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))
        assertTrue(PoseObserverUnknownReason.FRONT_REAR_UNRESOLVED in qualified.unknownReasons)
        assertSame(locked.observation.personTrackEpoch, qualified.observation.personTrackEpoch)
    }

    @Test
    fun anySecondRawCandidateImmediatelyDropsEpochAndRequiresFreshDwell() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 50L)
        fixture.accept(0L)
        val firstLock = fixture.accept(100L)
        val firstEpoch = firstLock.observation.personTrackEpoch

        val ambiguous = fixture.observer.accept(
            batch(
                timestampMs = 120L,
                frames = listOf(frame(120L), frame(120L, centerX = 0.72)),
            ),
        )
        assertStatus(ambiguous, PoseObserverTrackingStatus.AMBIGUOUS, locked = false)
        assertTrue(PoseObserverUnknownReason.PERSON_AMBIGUOUS in ambiguous.unknownReasons)

        assertStatus(fixture.accept(140L), PoseObserverTrackingStatus.ACQUIRING, locked = false)
        val secondLock = fixture.accept(240L)
        assertStatus(secondLock, PoseObserverTrackingStatus.TRACKED, locked = true)
        assertNotSame(firstEpoch, secondLock.observation.personTrackEpoch)
    }

    @Test
    fun distantSceneryDoesNotDissolveTheLock() {
        // A bystander crossing the far side of a gym used to end the set. Person-lock v3 excludes
        // a candidate whose landmark envelope is well under the subject's before judging
        // multiplicity, so the subject keeps the same epoch throughout.
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 50L)
        fixture.accept(0L)
        val locked = fixture.accept(100L)
        val epoch = locked.observation.personTrackEpoch

        val withScenery = fixture.observer.accept(
            batch(
                timestampMs = 120L,
                frames = listOf(frame(120L), frame(120L, centerX = 0.85, scale = 0.35)),
            ),
        )

        assertStatus(withScenery, PoseObserverTrackingStatus.TRACKED, locked = true)
        assertSame(epoch, withScenery.observation.personTrackEpoch)
    }

    @Test
    fun aComparablySizedBystanderStillAbstains() {
        // The attribution rule is unchanged: two people who could each be the subject means the
        // measurement cannot be attributed, so the lock still drops.
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 50L)
        fixture.accept(0L)
        fixture.accept(100L)

        val update = fixture.observer.accept(
            batch(
                timestampMs = 120L,
                frames = listOf(frame(120L), frame(120L, centerX = 0.80, scale = 0.80)),
            ),
        )

        assertStatus(update, PoseObserverTrackingStatus.AMBIGUOUS, locked = false)
        assertTrue(PoseObserverUnknownReason.PERSON_AMBIGUOUS in update.unknownReasons)
    }

    @Test
    fun sceneryThatWalksCloserBecomesAmbiguousAtTheCeiling() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 50L)
        fixture.accept(0L)
        fixture.accept(100L)

        val far = fixture.observer.accept(
            batch(
                timestampMs = 120L,
                frames = listOf(frame(120L), frame(120L, centerX = 0.85, scale = 0.40)),
            ),
        )
        assertStatus(far, PoseObserverTrackingStatus.TRACKED, locked = true)

        val near = fixture.observer.accept(
            batch(
                timestampMs = 140L,
                frames = listOf(frame(140L), frame(140L, centerX = 0.80, scale = 0.70)),
            ),
        )
        assertStatus(near, PoseObserverTrackingStatus.AMBIGUOUS, locked = false)
    }

    @Test
    fun acquisitionStartsBesideSceneryButNotBesideASecondSubject() {
        val withScenery = fixture(acquisitionDwellMs = 100L, viewDwellMs = 50L)
        assertStatus(
            withScenery.observer.accept(
                batch(
                    timestampMs = 0L,
                    frames = listOf(frame(0L), frame(0L, centerX = 0.88, scale = 0.30)),
                ),
            ),
            PoseObserverTrackingStatus.ACQUIRING,
            locked = false,
        )

        val withPeer = fixture(acquisitionDwellMs = 100L, viewDwellMs = 50L)
        assertStatus(
            withPeer.observer.accept(
                batch(
                    timestampMs = 0L,
                    frames = listOf(frame(0L), frame(0L, centerX = 0.80, scale = 0.90)),
                ),
            ),
            PoseObserverTrackingStatus.AMBIGUOUS,
            locked = false,
        )
    }

    @Test
    fun rejectedSchemaCandidateStillCountsAsMultiPersonSentinel() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 50L)
        fixture.accept(0L)
        fixture.accept(100L)

        val update = fixture.observer.accept(
            batch(
                timestampMs = 120L,
                frames = listOf(frame(120L)),
                rawCandidateCount = 2,
            ),
        )

        assertStatus(update, PoseObserverTrackingStatus.AMBIGUOUS, locked = false)
        assertTrue(update.candidateCount == 2)
    }

    @Test
    fun implausibleJumpOrFrameGapCannotReusePersonEpoch() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 50L)
        fixture.accept(0L)
        val locked = fixture.accept(100L)
        val epoch = locked.observation.personTrackEpoch

        val jump = fixture.accept(120L, centerX = 0.90)
        assertStatus(jump, PoseObserverTrackingStatus.TRACK_DISCONTINUITY, locked = false)
        assertTrue(PoseObserverUnknownReason.PERSON_TRACK_DISCONTINUITY in jump.unknownReasons)

        fixture.accept(140L, centerX = 0.90)
        val relocked = fixture.accept(240L, centerX = 0.90)
        assertStatus(relocked, PoseObserverTrackingStatus.TRACKED, locked = true)
        assertNotSame(epoch, relocked.observation.personTrackEpoch)

        val gap = fixture.accept(600L, centerX = 0.90)
        assertStatus(gap, PoseObserverTrackingStatus.TRACK_DISCONTINUITY, locked = false)
    }

    @Test
    fun lateralViewRequiresDwellAndCropLossRemovesAllViewTokensImmediately() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)
        fixture.accept(0L, yawDegrees = 75.0)
        fixture.accept(100L, yawDegrees = 75.0)
        val lateral = fixture.accept(180L, yawDegrees = 75.0)

        assertTrue(lateral.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))
        assertTrue(lateral.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))

        val cropped = fixture.accept(200L, yawDegrees = 75.0, cropFeet = true)
        assertTrue(cropped.observation.hasPrimaryPersonLock)
        assertFalse(cropped.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))
        assertFalse(cropped.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))
        assertTrue(PoseObserverUnknownReason.BODY_OUT_OF_FRAME in cropped.unknownReasons)
    }

    @Test
    fun lowConfidenceViewJointRevokesTokensWithoutReusingStaleQualification() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)
        fixture.accept(0L, yawDegrees = 75.0)
        fixture.accept(100L, yawDegrees = 75.0)
        val qualified = fixture.accept(180L, yawDegrees = 75.0)
        assertTrue(qualified.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))

        val obscured = fixture.accept(
            timestampMs = 200L,
            yawDegrees = 75.0,
            noseConfidence = 0.0,
        )
        assertStatus(obscured, PoseObserverTrackingStatus.TRACKED, locked = true)
        assertFalse(obscured.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))
        assertFalse(obscured.observation.isViewQualified(FULL_BODY_LATERAL_VIEW_CONTRACT_ID))
        assertTrue(
            PoseObserverUnknownReason.REQUIRED_LANDMARK_LOW_CONFIDENCE in
                obscured.unknownReasons,
        )
    }

    @Test
    fun duplicateOrOutOfOrderTimestampIsRejectedBeforeObserverStateChanges() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 50L)
        fixture.accept(0L)
        val locked = fixture.accept(100L)
        val epoch = locked.observation.personTrackEpoch

        assertThrows(IllegalArgumentException::class.java) { fixture.accept(100L) }
        assertThrows(IllegalArgumentException::class.java) { fixture.accept(90L) }

        val next = fixture.accept(120L)
        assertStatus(next, PoseObserverTrackingStatus.TRACKED, locked = true)
        assertSame(epoch, next.observation.personTrackEpoch)
    }

    @Test
    fun cameraGeometryDriftRevokesPersonAndViewAndRequiresFreshDwells() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 80L)
        fixture.accept(0L)
        fixture.accept(100L)
        val firstQualified = fixture.accept(180L)
        val firstGeometryEpoch = checkNotNull(firstQualified.observation.cameraGeometryEpoch)
        assertTrue(firstQualified.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))

        val sameGeometry = fixture.accept(200L)
        assertSame(firstGeometryEpoch, sameGeometry.observation.cameraGeometryEpoch)

        val changedGeometry = geometryContext(
            sourceImageWidth = 800,
            sourceImageHeight = 600,
            cropLeft = 80,
            cropTop = 60,
            cropRightExclusive = 720,
            cropBottomExclusive = 540,
        )
        val discontinuity = fixture.accept(220L, geometryContext = changedGeometry)
        assertStatus(
            discontinuity,
            PoseObserverTrackingStatus.TRACK_DISCONTINUITY,
            locked = false,
        )
        assertTrue(
            PoseObserverUnknownReason.CAMERA_GEOMETRY_DISCONTINUITY in
                discontinuity.unknownReasons,
        )
        assertFalse(
            discontinuity.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID),
        )
        val changedGeometryEpoch = checkNotNull(discontinuity.observation.cameraGeometryEpoch)
        assertNotSame(firstGeometryEpoch, changedGeometryEpoch)
        assertTrue(
            discontinuity.observation.cameraGeometryReceipt?.contextArtifactSha256 ==
                changedGeometry.artifactSha256,
        )

        assertStatus(
            fixture.accept(260L, geometryContext = changedGeometry),
            PoseObserverTrackingStatus.ACQUIRING,
            locked = false,
        )
        val relocked = fixture.accept(320L, geometryContext = changedGeometry)
        assertStatus(relocked, PoseObserverTrackingStatus.TRACKED, locked = true)
        assertFalse(relocked.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))
        val requalified = fixture.accept(400L, geometryContext = changedGeometry)
        assertTrue(requalified.observation.isViewQualified(FULL_BODY_PHASE_VIEW_CONTRACT_ID))
        assertSame(changedGeometryEpoch, requalified.observation.cameraGeometryEpoch)
    }

    @Test
    fun preprocessingMismatchIsRejectedBeforeTimestampOrGeometryStateChanges() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 50L)
        val mismatched = geometryContext(preprocessingArtifactSha256 = SHA_B)

        assertThrows(IllegalArgumentException::class.java) {
            fixture.accept(0L, geometryContext = mismatched)
        }
        assertStatus(fixture.accept(0L), PoseObserverTrackingStatus.ACQUIRING, locked = false)
        assertTrue(fixture.source.isOpen)
    }

    @Test
    fun acquisitionCandidateReplacementRestartsTheFullDwell() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 50L)
        assertStatus(fixture.accept(0L, centerX = 0.30), PoseObserverTrackingStatus.ACQUIRING, false)
        assertStatus(fixture.accept(50L, centerX = 0.80), PoseObserverTrackingStatus.ACQUIRING, false)
        assertStatus(fixture.accept(100L, centerX = 0.80), PoseObserverTrackingStatus.ACQUIRING, false)
        assertStatus(fixture.accept(150L, centerX = 0.80), PoseObserverTrackingStatus.TRACKED, true)
    }

    @Test
    fun missingConfidenceCannotAcquireAndClosedObserverCannotMint() {
        val fixture = fixture(acquisitionDwellMs = 100L, viewDwellMs = 50L)
        val missing = fixture.accept(0L, confidence = 0.0)
        assertStatus(
            missing,
            PoseObserverTrackingStatus.INSUFFICIENT_LANDMARKS,
            locked = false,
        )

        fixture.observer.close()
        assertFalse(fixture.source.isOpen)
        assertThrows(IllegalStateException::class.java) {
            fixture.accept(100L)
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.source.newPersonTrackEpoch()
        }
    }

    @Test
    fun sourceContractMustPinExactLockViewAlgorithmsAndAllowedViews() {
        val lockConfig = PosePersonLockConfig(acquisitionDwellMs = 100L)
        val viewConfig = PoseViewQualifierConfig(qualificationDwellMs = 50L)
        val wrongContract = contract(
            personLockSha = SHA_B,
            viewQualifierSha = viewConfig.artifactSha256,
        )

        assertThrows(IllegalArgumentException::class.java) {
            MediaPipePoseObserver(
                PoseObservationSource(wrongContract),
                lockConfig,
                viewConfig,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(
                personLockSha = lockConfig.artifactSha256,
                viewQualifierSha = viewConfig.artifactSha256,
                allowedViews = setOf(FULL_BODY_LATERAL_VIEW_CONTRACT_ID),
            )
        }
    }

    private fun fixture(
        acquisitionDwellMs: Long,
        viewDwellMs: Long,
    ): Fixture {
        val lock = PosePersonLockConfig(acquisitionDwellMs = acquisitionDwellMs)
        val view = PoseViewQualifierConfig(qualificationDwellMs = viewDwellMs)
        val source = PoseObservationSource(
            contract(
                personLockSha = lock.artifactSha256,
                viewQualifierSha = view.artifactSha256,
            ),
        )
        return Fixture(
            observer = MediaPipePoseObserver(source, lock, view),
            source = source,
        )
    }

    private inner class Fixture(
        val observer: MediaPipePoseObserver,
        val source: PoseObservationSource,
    ) {
        fun accept(
            timestampMs: Long,
            centerX: Double = 0.50,
            yawDegrees: Double = 0.0,
            confidence: Double = 1.0,
            cropFeet: Boolean = false,
            noseConfidence: Double? = null,
            geometryContext: PoseCameraGeometryContext = geometryContext(),
        ): PoseObserverUpdate = observer.accept(
            batch(
                timestampMs = timestampMs,
                frames = listOf(
                    frame(
                        timestampMs = timestampMs,
                        centerX = centerX,
                        yawDegrees = yawDegrees,
                        confidence = confidence,
                        cropFeet = cropFeet,
                        noseConfidence = noseConfidence,
                        geometryContext = geometryContext,
                    ),
                ),
                geometryContext = geometryContext,
            ),
        )
    }

    private fun contract(
        personLockSha: String,
        viewQualifierSha: String,
        allowedViews: Set<String> = setOf(
            FULL_BODY_PHASE_VIEW_CONTRACT_ID,
            FULL_BODY_LATERAL_VIEW_CONTRACT_ID,
        ),
    ): PoseObservationContract = PoseObservationContract(
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
        allowedViewContractIds = allowedViews,
        personLockArtifactId = "trex.primary-person.temporal-lock.v1",
        personLockArtifactSha256 = personLockSha,
        viewQualifierArtifactId = "trex.body-view.qualifier.v1",
        viewQualifierArtifactSha256 = viewQualifierSha,
    )

    private fun batch(
        timestampMs: Long,
        frames: List<PoseFrame>,
        rawCandidateCount: Int = frames.size,
        geometryContext: PoseCameraGeometryContext = geometryContext(),
    ): PoseCandidateBatch = PoseCandidateBatch(
        timestampMs = timestampMs,
        candidates = frames,
        rawCandidateCount = rawCandidateCount,
        geometryContext = geometryContext,
    )

    private fun frame(
        timestampMs: Long,
        centerX: Double = 0.50,
        yawDegrees: Double = 0.0,
        confidence: Double = 1.0,
        cropFeet: Boolean = false,
        noseConfidence: Double? = null,
        scale: Double = 1.0,
        geometryContext: PoseCameraGeometryContext = geometryContext(),
    ): PoseFrame {
        val normalized = normalizedSkeleton(centerX, confidence, cropFeet, scale).let { skeleton ->
            if (noseConfidence == null) {
                skeleton
            } else {
                skeleton + (
                    PoseJoint.NOSE to skeleton.getValue(PoseJoint.NOSE).copy(
                        visibility = noseConfidence,
                        presence = noseConfidence,
                    )
                )
            }
        }
        val world = worldSkeleton(yawDegrees, confidence)
        return PoseFrame(
            timestampMs = timestampMs,
            landmarks = normalized,
            worldLandmarks = world,
            imageWidth = geometryContext.outputImageWidth,
            imageHeight = geometryContext.outputImageHeight,
            rotationDegrees = geometryContext.outputRotationDegrees,
            isMirrored = geometryContext.displayMirrored,
        )
    }

    private fun geometryContext(
        sourceImageWidth: Int = IMAGE_WIDTH,
        sourceImageHeight: Int = IMAGE_HEIGHT,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        cropRightExclusive: Int = IMAGE_WIDTH,
        cropBottomExclusive: Int = IMAGE_HEIGHT,
        inputRotationDegrees: Int = 0,
        outputImageWidth: Int = IMAGE_WIDTH,
        outputImageHeight: Int = IMAGE_HEIGHT,
        preprocessingArtifactSha256: String = SHA_A,
    ): PoseCameraGeometryContext = PoseCameraGeometryContext(
        sourceImageWidth = sourceImageWidth,
        sourceImageHeight = sourceImageHeight,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropRightExclusive = cropRightExclusive,
        cropBottomExclusive = cropBottomExclusive,
        inputRotationDegrees = inputRotationDegrees,
        outputImageWidth = outputImageWidth,
        outputImageHeight = outputImageHeight,
        inferencePixelsMirrored = false,
        displayMirrored = true,
        preprocessingArtifactSha256 = preprocessingArtifactSha256,
    )

    /**
     * @param scale shrinks the whole skeleton about its own centre, standing in for a person
     *   farther from the camera. The landmark envelope scales linearly, so [scale] is exactly the
     *   ratio the background gate compares against its ceiling.
     */
    private fun normalizedSkeleton(
        centerX: Double,
        confidence: Double,
        cropFeet: Boolean,
        scale: Double = 1.0,
    ): Map<PoseJoint, PoseLandmark> {
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
            PoseLandmark(
                centerX + (x - centerX) * scale,
                0.5 + (y - 0.5) * scale,
                0.0,
                confidence,
                confidence,
            )
        }
    }

    private fun worldSkeleton(
        yawDegrees: Double,
        confidence: Double,
    ): Map<PoseJoint, PoseLandmark> {
        val yaw = Math.toRadians(yawDegrees)
        val axisX = cos(yaw)
        val axisZ = sin(yaw)
        val normalized = normalizedSkeleton(centerX = 0.5, confidence = confidence, cropFeet = false)
        return PoseJoint.entries.associateWith { joint ->
            val source = normalized.getValue(joint)
            val side = when {
                joint.name.startsWith("LEFT_") -> -1.0
                joint.name.startsWith("RIGHT_") -> 1.0
                else -> 0.0
            }
            val lateralDistance = kotlin.math.abs(source.x - 0.5)
            PoseLandmark(
                x = side * lateralDistance * axisX,
                y = source.y - 0.5,
                z = side * lateralDistance * axisZ,
                visibility = confidence,
                presence = confidence,
            )
        }
    }

    private fun assertStatus(
        update: PoseObserverUpdate,
        expected: PoseObserverTrackingStatus,
        locked: Boolean,
    ) {
        assertTrue(update.trackingStatus == expected)
        assertTrue(update.observation.hasPrimaryPersonLock == locked)
    }

    private companion object {
        const val IMAGE_WIDTH = 640
        const val IMAGE_HEIGHT = 480
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
