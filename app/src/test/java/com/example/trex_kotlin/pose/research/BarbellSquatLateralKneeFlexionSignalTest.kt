package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.PoseSide
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryEpoch
import com.example.trex_kotlin.pose.runtime.PoseObservationContract
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import com.example.trex_kotlin.pose.runtime.PosePersonTrackEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BarbellSquatLateralKneeFlexionSignalTest {
    @Test
    fun worldSupplementaryAnglesProduceExactBilateralEvenMedian() {
        val fixture = fixture()
        val measurement = fixture.extractor.measure(fixture.observation(timestampMs = 10L))

        val left = measurement.measured(PoseSide.LEFT)
        val right = measurement.measured(PoseSide.RIGHT)
        assertEquals(180.0, left.includedAngleDegrees, 1e-12)
        assertEquals(0.0, left.flexionDegrees, 1e-12)
        assertEquals(90.0, right.includedAngleDegrees, 1e-12)
        assertEquals(90.0, right.flexionDegrees, 1e-12)
        assertEquals(45.0, measurement.bilateralMedianDegrees!!, 1e-12)
        assertEquals(fixture.geometry.contextArtifactSha256, measurement.cameraGeometryContextSha256)
        assertTrue(measurement.sampleProvenanceSha256!!.matches(SHA_PATTERN))
    }

    @Test
    fun normalizedCoordinatesCannotSubstituteForWorldGeometry() {
        val fixture = fixture()
        val contradictoryNormalized = bilateralWorldLandmarks().mapValues {
            landmark(0.0, 0.0, 0.0)
        }
        val complete = fixture.extractor.measure(
            fixture.observation(timestampMs = 1L, normalized = contradictoryNormalized),
        )
        val missingWorld = fixture.extractor.measure(
            fixture.observation(
                timestampMs = 2L,
                world = bilateralWorldLandmarks() - PoseJoint.LEFT_KNEE,
                normalized = contradictoryNormalized,
            ),
        )

        assertEquals(45.0, complete.bilateralMedianDegrees!!, 1e-12)
        assertNull(missingWorld.bilateralMedianDegrees)
        assertEquals(
            setOf(PoseJoint.LEFT_KNEE),
            missingWorld.unavailable(PoseSide.LEFT).missingJoints,
        )
        assertTrue(missingWorld.sideSamples[PoseSide.RIGHT] is LateralKneeFlexionSideSample.Measured)
    }

    @Test
    fun lowConfidenceOnEitherSideSuppressesOnlyTheBilateralScalar() {
        val fixture = fixture(minimumConfidence = 0.8)
        val affected = bilateralWorldLandmarks().toMutableMap().apply {
            put(PoseJoint.RIGHT_KNEE, landmark(0.2, 0.0, confidence = 0.79))
        }

        val measurement = fixture.extractor.measure(
            fixture.observation(timestampMs = 3L, world = affected),
        )

        assertTrue(measurement.sideSamples[PoseSide.LEFT] is LateralKneeFlexionSideSample.Measured)
        assertEquals(
            setOf(PoseJoint.RIGHT_KNEE),
            measurement.unavailable(PoseSide.RIGHT).lowConfidenceJoints,
        )
        assertNull(measurement.bilateralMedianDegrees)
        assertNull(measurement.sampleProvenanceSha256)
    }

    @Test
    fun degenerateWorldVectorFailsClosedWithoutNormalizedFallback() {
        val fixture = fixture()
        val degenerate = bilateralWorldLandmarks().toMutableMap().apply {
            put(PoseJoint.LEFT_HIP, getValue(PoseJoint.LEFT_KNEE))
        }

        val measurement = fixture.extractor.measure(
            fixture.observation(timestampMs = 4L, world = degenerate),
        )

        assertEquals(
            setOf(LateralKneeFlexionUnavailabilityReason.DEGENERATE_WORLD_VECTOR),
            measurement.unavailable(PoseSide.LEFT).reasons,
        )
        assertNull(measurement.bilateralMedianDegrees)
        assertNull(measurement.sampleProvenanceSha256)
    }

    @Test
    fun sampleProvenanceChangesWithWorldValueConfidenceAndGeometry() {
        val fixture = fixture()
        val baseline = fixture.extractor.measure(fixture.observation(timestampMs = 7L))
        val changedAngle = bilateralWorldLandmarks().toMutableMap().apply {
            put(PoseJoint.RIGHT_ANKLE, landmark(0.2, -1.0))
        }
        val changedConfidence = bilateralWorldLandmarks().toMutableMap().apply {
            put(PoseJoint.RIGHT_KNEE, landmark(0.2, 0.0, confidence = 0.98))
        }
        val shiftedGeometry = fixture.source.newCameraGeometryEpoch(
            geometryContext(cropLeft = 10, cropRightExclusive = 650),
        )
        val variants = listOf(
            fixture.extractor.measure(
                fixture.observation(timestampMs = 7L, world = changedAngle),
            ),
            fixture.extractor.measure(
                fixture.observation(timestampMs = 7L, world = changedConfidence),
            ),
            fixture.extractor.measure(
                fixture.observation(timestampMs = 7L, geometryEpoch = shiftedGeometry),
            ),
        )

        variants.forEach { changed ->
            assertNotEquals(baseline.sampleProvenanceSha256, changed.sampleProvenanceSha256)
        }
    }

    @Test
    fun sourcePersonViewGeometryAndClosedSourceGatesFailClosed() {
        val fixture = fixture()
        val foreign = fixture(contract = fixture.source.contract)
        val wrongSource = fixture.extractor.measure(foreign.observation(timestampMs = 1L))
        val noPerson = fixture.extractor.measure(
            fixture.observation(timestampMs = 2L, person = null),
        )
        val noView = fixture.extractor.measure(
            fixture.observation(timestampMs = 3L, lateralQualified = false),
        )
        val noGeometry = fixture.extractor.measure(
            fixture.observation(timestampMs = 4L, geometryEpoch = null),
        )
        val beforeClose = fixture.observation(timestampMs = 5L)
        fixture.source.close()
        val afterClose = fixture.extractor.measure(beforeClose)

        assertGlobalReason(
            wrongSource,
            LateralKneeFlexionUnavailabilityReason.OBSERVATION_SOURCE_MISMATCH,
        )
        assertGlobalReason(
            noPerson,
            LateralKneeFlexionUnavailabilityReason.PRIMARY_PERSON_LOCK_MISSING,
        )
        assertGlobalReason(
            noView,
            LateralKneeFlexionUnavailabilityReason.LATERAL_VIEW_QUALIFICATION_MISSING,
        )
        assertGlobalReason(
            noGeometry,
            LateralKneeFlexionUnavailabilityReason.CAMERA_GEOMETRY_RECEIPT_MISSING,
        )
        assertGlobalReason(
            afterClose,
            LateralKneeFlexionUnavailabilityReason.EXPECTED_SOURCE_CLOSED,
        )
    }

    @Test
    fun contractPinsM4ObservationGeometryPersonAndViewArtifacts() {
        val fixture = fixture()
        val contract = fixture.extractor.contract
        val lateralCandidate = BarbellSquatResearchPhaseContract.CURRENT.scalarCandidates.single {
            it.candidateId == BarbellSquatResearchPhaseContract.LATERAL_CANDIDATE_ID
        }

        assertEquals(BarbellSquatResearchPhaseContract.CURRENT.artifactSha256,
            contract.researchContractSha256)
        assertEquals(lateralCandidate.contentSha256, contract.researchCandidateSha256)
        assertEquals(fixture.source.contract.artifactSha256, contract.observationContractSha256)
        assertEquals(fixture.source.contract.preprocessingArtifactSha256,
            contract.preprocessingArtifactSha256)
        assertEquals(fixture.source.contract.personLockArtifactSha256,
            contract.personLockArtifactSha256)
        assertEquals(fixture.source.contract.viewQualifierArtifactSha256,
            contract.viewQualifierArtifactSha256)
        assertEquals(BarbellSquatResearchView.LATERAL.contractId, contract.lateralViewContractId)
        assertEquals(250L, contract.maximumObservationGapMs)

        assertNotEquals(
            contract.artifactSha256,
            BarbellSquatLateralKneeFlexionSignalExtractor(
                fixture.source,
                maximumObservationGapMs = 250L,
                minimumWorldLandmarkConfidence = 0.56,
            ).contract.artifactSha256,
        )
        assertNotEquals(
            contract.artifactSha256,
            BarbellSquatLateralKneeFlexionSignalExtractor(
                fixture.source,
                maximumObservationGapMs = 251L,
            ).contract.artifactSha256,
        )
    }

    @Test
    fun stableStreamContinuesButPersonEpochAndEqualGeometryEpochReset() {
        val fixture = fixture()
        val stream = BarbellSquatResearchSignalContinuityStream(fixture.extractor)
        val first = stream.accept(fixture.observation(timestampMs = 0L))
        val second = stream.accept(fixture.observation(timestampMs = 10L))
        val newPerson = fixture.source.newPersonTrackEpoch()
        val personReset = stream.accept(
            fixture.observation(timestampMs = 20L, person = newPerson),
        )
        val equalContextNewEpoch = fixture.source.newCameraGeometryEpoch(fixture.geometry.context)
        val geometryReset = stream.accept(
            fixture.observation(
                timestampMs = 30L,
                person = newPerson,
                geometryEpoch = equalContextNewEpoch,
            ),
        )

        assertEquals(1L, first.continuitySegmentId)
        assertTrue(first.acceptedForContinuity)
        assertEquals(1L, second.continuitySegmentId)
        assertTrue(second.acceptedForContinuity)
        assertTrue(second.resetCauses.isEmpty())
        assertEquals(2L, personReset.continuitySegmentId)
        assertTrue(personReset.acceptedForContinuity)
        assertEquals(
            setOf(BarbellSquatResearchSignalResetCause.PERSON_TRACK_EPOCH_DISCONTINUITY),
            personReset.resetCauses,
        )
        assertEquals(3L, geometryReset.continuitySegmentId)
        assertTrue(geometryReset.acceptedForContinuity)
        assertEquals(
            setOf(BarbellSquatResearchSignalResetCause.CAMERA_GEOMETRY_EPOCH_DISCONTINUITY),
            geometryReset.resetCauses,
        )
    }

    @Test
    fun timestampGapAndUnavailableSignalStrictlyBreakContinuity() {
        val fixture = fixture(maximumGapMs = 20L)
        val stream = BarbellSquatResearchSignalContinuityStream(fixture.extractor)
        stream.accept(fixture.observation(timestampMs = 10L))
        val duplicate = stream.accept(fixture.observation(timestampMs = 10L))
        val regression = stream.accept(fixture.observation(timestampMs = 9L))
        val resumed = stream.accept(fixture.observation(timestampMs = 11L))
        val gap = stream.accept(fixture.observation(timestampMs = 40L))
        val missing = stream.accept(
            fixture.observation(
                timestampMs = 41L,
                world = bilateralWorldLandmarks() - PoseJoint.RIGHT_ANKLE,
            ),
        )
        val afterMissing = stream.accept(fixture.observation(timestampMs = 42L))

        assertNull(duplicate.continuitySegmentId)
        assertFalse(duplicate.acceptedForContinuity)
        assertEquals(
            setOf(BarbellSquatResearchSignalResetCause.TIMESTAMP_NOT_STRICTLY_INCREASING),
            duplicate.resetCauses,
        )
        assertNull(regression.continuitySegmentId)
        assertFalse(regression.acceptedForContinuity)
        assertEquals(
            setOf(BarbellSquatResearchSignalResetCause.TIMESTAMP_NOT_STRICTLY_INCREASING),
            regression.resetCauses,
        )
        assertEquals(2L, resumed.continuitySegmentId)
        assertTrue(resumed.acceptedForContinuity)
        assertEquals(3L, gap.continuitySegmentId)
        assertTrue(gap.acceptedForContinuity)
        assertEquals(
            setOf(BarbellSquatResearchSignalResetCause.OBSERVATION_GAP_EXCEEDED),
            gap.resetCauses,
        )
        assertNull(missing.continuitySegmentId)
        assertFalse(missing.acceptedForContinuity)
        assertEquals(
            setOf(BarbellSquatResearchSignalResetCause.SIGNAL_UNAVAILABLE),
            missing.resetCauses,
        )
        assertEquals(4L, afterMissing.continuitySegmentId)
        assertTrue(afterMissing.acceptedForContinuity)
    }

    @Test
    fun sourceViewAndUnattestedGeometryBreakAndDropTheCurrentSample() {
        val fixture = fixture()
        val foreign = fixture(contract = fixture.source.contract)
        val stream = BarbellSquatResearchSignalContinuityStream(fixture.extractor)
        stream.accept(fixture.observation(timestampMs = 1L))

        val wrongSource = stream.accept(foreign.observation(timestampMs = 2L))
        val noView = stream.accept(
            fixture.observation(timestampMs = 3L, lateralQualified = false),
        )
        val noGeometry = stream.accept(
            fixture.observation(timestampMs = 4L, geometryEpoch = null),
        )

        assertEquals(
            setOf(BarbellSquatResearchSignalResetCause.OBSERVATION_SOURCE_DISCONTINUITY),
            wrongSource.resetCauses,
        )
        assertEquals(
            setOf(BarbellSquatResearchSignalResetCause.LATERAL_VIEW_UNQUALIFIED),
            noView.resetCauses,
        )
        assertEquals(
            setOf(BarbellSquatResearchSignalResetCause.CAMERA_GEOMETRY_UNATTESTED),
            noGeometry.resetCauses,
        )
        assertNull(wrongSource.continuitySegmentId)
        assertNull(noView.continuitySegmentId)
        assertNull(noGeometry.continuitySegmentId)
        assertFalse(wrongSource.acceptedForContinuity)
        assertFalse(noView.acceptedForContinuity)
        assertFalse(noGeometry.acceptedForContinuity)
    }

    @Test
    fun geometryMetadataDriftStartsNewSegmentsWithSpecificCauses() {
        val fixture = fixture()
        val stream = BarbellSquatResearchSignalContinuityStream(fixture.extractor)
        stream.accept(fixture.observation(timestampMs = 1L))

        val cropEpoch = fixture.source.newCameraGeometryEpoch(
            geometryContext(cropLeft = 10, cropRightExclusive = 650),
        )
        val crop = stream.accept(
            fixture.observation(timestampMs = 2L, geometryEpoch = cropEpoch),
        )
        val dimensionEpoch = fixture.source.newCameraGeometryEpoch(
            geometryContext(cropRightExclusive = 320, cropBottomExclusive = 240),
        )
        val dimensions = stream.accept(
            fixture.observation(timestampMs = 3L, geometryEpoch = dimensionEpoch),
        )
        val rotationEpoch = fixture.source.newCameraGeometryEpoch(
            geometryContext(
                sourceImageWidth = 800,
                sourceImageHeight = 800,
                cropRightExclusive = 480,
                cropBottomExclusive = 640,
                inputRotationDegrees = 90,
            ),
        )
        val rotation = stream.accept(
            fixture.observation(timestampMs = 4L, geometryEpoch = rotationEpoch),
        )
        val mirrorEpoch = fixture.source.newCameraGeometryEpoch(
            geometryContext(displayMirrored = true),
        )
        val mirror = stream.accept(
            fixture.observation(timestampMs = 5L, geometryEpoch = mirrorEpoch),
        )

        assertTrue(BarbellSquatResearchSignalResetCause.CROP_RECT_DISCONTINUITY in crop.resetCauses)
        assertTrue(
            BarbellSquatResearchSignalResetCause.IMAGE_DIMENSION_DISCONTINUITY in
                dimensions.resetCauses,
        )
        assertTrue(BarbellSquatResearchSignalResetCause.ROTATION_DISCONTINUITY in rotation.resetCauses)
        assertTrue(
            BarbellSquatResearchSignalResetCause.MIRROR_METADATA_DISCONTINUITY in
                mirror.resetCauses,
        )
        assertTrue(listOf(crop, dimensions, rotation, mirror).all { it.continuitySegmentId != null })
    }

    @Test
    fun exposedSurfaceHasNoDecoderOrProductDecisionVocabularyAndCollectionsAreImmutable() {
        val fixture = fixture()
        val measurement = fixture.extractor.measure(fixture.observation(timestampMs = 1L))
        val update = BarbellSquatResearchSignalContinuityStream(fixture.extractor)
            .accept(fixture.observation(timestampMs = 2L))
        val forbidden = listOf(
            "phase", "window", "cycle", "rep", "pass", "fail", "unknown", "score",
            "cue", "feedback",
        )
        val surfaces = listOf(
            BarbellSquatLateralKneeFlexionSignalContract::class.java,
            BarbellSquatLateralKneeFlexionSignalExtractor::class.java,
            BarbellSquatResearchSignalContinuityStream::class.java,
            BarbellSquatLateralKneeFlexionSignalMeasurement::class.java,
            LateralKneeFlexionSideSample::class.java,
            BarbellSquatResearchSignalStreamUpdate::class.java,
        )

        surfaces.flatMap { type ->
            listOf(type.simpleName) +
                type.declaredMethods.map { it.name } +
                type.declaredFields.map { it.name }
        }.forEach { name ->
            val tokens = name
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .split(Regex("[^A-Za-z0-9]+"))
                .filter(String::isNotEmpty)
                .map(String::lowercase)
            assertFalse(forbidden.any { it in tokens })
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (measurement.sideSamples as MutableMap).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (update.resetCauses as MutableSet).clear()
        }
    }

    @Test
    fun closingStreamClearsItAndRejectsFurtherInput() {
        val fixture = fixture()
        val stream = BarbellSquatResearchSignalContinuityStream(fixture.extractor)
        stream.accept(fixture.observation(timestampMs = 1L))
        stream.close()

        assertThrows(IllegalStateException::class.java) {
            stream.accept(fixture.observation(timestampMs = 2L))
        }
    }
}

private class SignalFixture(
    val source: PoseObservationSource,
    val extractor: BarbellSquatLateralKneeFlexionSignalExtractor,
    val person: PosePersonTrackEpoch,
    val geometry: PoseCameraGeometryEpoch,
) {
    fun observation(
        timestampMs: Long,
        world: Map<PoseJoint, PoseLandmark> = bilateralWorldLandmarks(),
        normalized: Map<PoseJoint, PoseLandmark> = world,
        person: PosePersonTrackEpoch? = this.person,
        lateralQualified: Boolean = true,
        geometryEpoch: PoseCameraGeometryEpoch? = geometry,
    ): AttestedPoseObservation {
        val context = geometryEpoch?.context ?: geometry.context
        val frame = PoseFrame(
            timestampMs = timestampMs,
            landmarks = normalized,
            worldLandmarks = world,
            imageWidth = context.outputImageWidth,
            imageHeight = context.outputImageHeight,
            rotationDegrees = context.outputRotationDegrees,
            isMirrored = context.displayMirrored,
        )
        val qualifications = if (person != null && lateralQualified) {
            listOf(
                source.qualifyView(
                    BarbellSquatResearchView.LATERAL.contractId,
                    person,
                    timestampMs,
                ),
            )
        } else {
            emptyList()
        }
        return if (geometryEpoch == null) {
            source.attest(frame, person, qualifications)
        } else {
            source.attest(frame, person, qualifications, geometryEpoch)
        }
    }
}

private fun fixture(
    contract: PoseObservationContract = observationContract(),
    minimumConfidence: Double = 0.55,
    maximumGapMs: Long = 250L,
): SignalFixture {
    val source = PoseObservationSource(contract)
    val person = source.newPersonTrackEpoch()
    val geometry = source.newCameraGeometryEpoch(geometryContext(
        preprocessingArtifactSha256 = contract.preprocessingArtifactSha256,
    ))
    return SignalFixture(
        source = source,
        extractor = BarbellSquatLateralKneeFlexionSignalExtractor(
            expectedSource = source,
            minimumWorldLandmarkConfidence = minimumConfidence,
            maximumObservationGapMs = maximumGapMs,
        ),
        person = person,
        geometry = geometry,
    )
}

private fun bilateralWorldLandmarks(): Map<PoseJoint, PoseLandmark> = linkedMapOf(
    PoseJoint.LEFT_HIP to landmark(-0.2, 1.0),
    PoseJoint.LEFT_KNEE to landmark(-0.2, 0.0),
    PoseJoint.LEFT_ANKLE to landmark(-0.2, -1.0),
    PoseJoint.RIGHT_HIP to landmark(0.2, 1.0),
    PoseJoint.RIGHT_KNEE to landmark(0.2, 0.0),
    PoseJoint.RIGHT_ANKLE to landmark(1.2, 0.0),
)

private fun landmark(
    x: Double,
    y: Double,
    z: Double = 0.0,
    confidence: Double = 0.99,
) = PoseLandmark(x, y, z, visibility = confidence, presence = confidence)

private fun geometryContext(
    sourceImageWidth: Int = 800,
    sourceImageHeight: Int = 600,
    cropLeft: Int = 0,
    cropTop: Int = 0,
    cropRightExclusive: Int = 640,
    cropBottomExclusive: Int = 480,
    inputRotationDegrees: Int = 0,
    displayMirrored: Boolean = false,
    preprocessingArtifactSha256: String = SHA_C,
): PoseCameraGeometryContext {
    val cropWidth = cropRightExclusive - cropLeft
    val cropHeight = cropBottomExclusive - cropTop
    val outputWidth = if (inputRotationDegrees == 90 || inputRotationDegrees == 270) {
        cropHeight
    } else {
        cropWidth
    }
    val outputHeight = if (inputRotationDegrees == 90 || inputRotationDegrees == 270) {
        cropWidth
    } else {
        cropHeight
    }
    return PoseCameraGeometryContext(
        sourceImageWidth = sourceImageWidth,
        sourceImageHeight = sourceImageHeight,
        cropLeft = cropLeft,
        cropTop = cropTop,
        cropRightExclusive = cropRightExclusive,
        cropBottomExclusive = cropBottomExclusive,
        inputRotationDegrees = inputRotationDegrees,
        outputImageWidth = outputWidth,
        outputImageHeight = outputHeight,
        inferencePixelsMirrored = false,
        displayMirrored = displayMirrored,
        preprocessingArtifactSha256 = preprocessingArtifactSha256,
    )
}

private fun observationContract(): PoseObservationContract = PoseObservationContract(
    runtimeDomainId = "mediapipe.pose.video.world.v1",
    modelArtifactId = "mediapipe.pose-landmarker.full.v1",
    modelArtifactSha256 = SHA_A,
    inferenceOptionsContractId = "mediapipe.video-options.v1",
    inferenceOptionsArtifactSha256 = SHA_B,
    preprocessingContractId = "camerax.crop-rotate-upright.v1",
    preprocessingArtifactSha256 = SHA_C,
    landmarkSchemaId = "mediapipe.pose-33.v1",
    landmarkSchemaArtifactSha256 = SHA_D,
    supportedCoordinateSpaces = setOf(
        PoseCoordinateSpace.NORMALIZED_IMAGE,
        PoseCoordinateSpace.WORLD,
    ),
    phaseViewContractId = BarbellSquatResearchView.LATERAL.contractId,
    allowedViewContractIds = setOf(BarbellSquatResearchView.LATERAL.contractId),
    personLockArtifactId = "primary-person.temporal-lock.v1",
    personLockArtifactSha256 = SHA_E,
    viewQualifierArtifactId = "full-body-lateral.qualifier.v1",
    viewQualifierArtifactSha256 = SHA_F,
)

private fun BarbellSquatLateralKneeFlexionSignalMeasurement.measured(
    side: PoseSide,
) = sideSamples.getValue(side) as LateralKneeFlexionSideSample.Measured

private fun BarbellSquatLateralKneeFlexionSignalMeasurement.unavailable(
    side: PoseSide,
) = sideSamples.getValue(side) as LateralKneeFlexionSideSample.Unavailable

private fun assertGlobalReason(
    measurement: BarbellSquatLateralKneeFlexionSignalMeasurement,
    reason: LateralKneeFlexionUnavailabilityReason,
) {
    assertNull(measurement.bilateralMedianDegrees)
    PoseSide.entries.forEach { side ->
        assertEquals(setOf(reason), measurement.unavailable(side).reasons)
    }
}

private val SHA_PATTERN = Regex("^[0-9a-f]{64}$")
private const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
private const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
private const val SHA_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
private const val SHA_E = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
private const val SHA_F = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
