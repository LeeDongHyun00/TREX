package com.example.trex_kotlin.pose.runtime

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseObservationContractTest {
    @Test
    fun canonicalArtifactIsOrderIndependentAndOwnsImmutableCoordinateSpaces() {
        val mutableSpaces = linkedSetOf(
            PoseCoordinateSpace.WORLD,
            PoseCoordinateSpace.NORMALIZED_IMAGE,
        )
        val artifact = contract(supportedCoordinateSpaces = mutableSpaces)
        val canonicalOrder = contract(
            supportedCoordinateSpaces = linkedSetOf(
                PoseCoordinateSpace.NORMALIZED_IMAGE,
                PoseCoordinateSpace.WORLD,
            ),
        )
        mutableSpaces.clear()

        assertEquals(canonicalOrder.artifactSha256, artifact.artifactSha256)
        assertEquals(
            setOf(PoseCoordinateSpace.NORMALIZED_IMAGE, PoseCoordinateSpace.WORLD),
            artifact.supportedCoordinateSpaces,
        )
        assertTrue(artifact.artifactSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (artifact.supportedCoordinateSpaces as MutableSet<PoseCoordinateSpace>).clear()
        }
    }

    @Test
    fun everyRuntimeProvenanceDimensionChangesTheContentHash() {
        val baseline = contract()

        val changes = listOf(
            contract(runtimeDomainId = "mediapipe-full.world.side-view.v2"),
            contract(modelArtifactId = "mediapipe.pose-landmarker.full.v2"),
            contract(modelArtifactSha256 = SHA_B),
            contract(inferenceOptionsContractId = "mediapipe.video-options.v2"),
            contract(inferenceOptionsArtifactSha256 = SHA_A),
            contract(preprocessingContractId = "camerax.viewport-rotation-no-mirror.v2"),
            contract(preprocessingArtifactSha256 = SHA_C),
            contract(landmarkSchemaId = "mediapipe.pose-33.v2"),
            contract(landmarkSchemaArtifactSha256 = SHA_D),
            contract(supportedCoordinateSpaces = setOf(PoseCoordinateSpace.WORLD)),
            contract(
                phaseViewContractId = "front-view.body-yaw-window.v1",
                allowedViewContractIds = setOf("front-view.body-yaw-window.v1"),
            ),
            contract(allowedViewContractIds = setOf(SIDE_VIEW, "front-view.body-yaw-window.v1")),
            contract(personLockArtifactId = "primary-person.temporal-lock.v2"),
            contract(personLockArtifactSha256 = SHA_C),
            contract(viewQualifierArtifactId = "body-yaw.qualifier.v2"),
            contract(viewQualifierArtifactSha256 = SHA_D),
        )

        changes.forEach { changed ->
            assertNotEquals(baseline.artifactSha256, changed.artifactSha256)
        }
    }

    @Test
    fun malformedOrIncompleteContractsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            contract(runtimeDomainId = "MediaPipe World V1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(modelArtifactSha256 = "not-a-sha")
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(supportedCoordinateSpaces = emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(viewQualifierArtifactSha256 = SHA_A.uppercase())
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(allowedViewContractIds = setOf("front-view.body-yaw-window.v1"))
        }
    }

    @Test
    fun sourceAndDynamicEvidenceAreOpaqueAndBoundByReferenceIdentity() {
        val artifact = contract()
        val firstSource = PoseObservationSource(artifact)
        val secondSource = PoseObservationSource(artifact)
        val firstPersonEpoch = firstSource.newPersonTrackEpoch()
        val sideView = firstSource.qualifyView(SIDE_VIEW, firstPersonEpoch, 10L)
        val observation = firstSource.attest(
            frame = frame(timestampMs = 10L),
            personTrackEpoch = firstPersonEpoch,
            viewQualifications = listOf(sideView),
        )

        assertNotSame(firstSource, secondSource)
        assertTrue(observation.isFrom(firstSource))
        assertFalse(observation.isFrom(secondSource))
        assertTrue(observation.hasPrimaryPersonLock)
        assertTrue(observation.personTrackEpoch === firstPersonEpoch)
        assertTrue(observation.isViewQualified(SIDE_VIEW))
        assertFalse(observation.isViewQualified("front-view.body-yaw-window.v1"))
        assertThrows(IllegalArgumentException::class.java) {
            firstSource.qualifyView(
                "front-view.body-yaw-window.v1",
                firstPersonEpoch,
                10L,
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            firstSource.attest(
                frame = frame(timestampMs = 20L),
                personTrackEpoch = secondSource.newPersonTrackEpoch(),
                viewQualifications = listOf(sideView),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            firstSource.attest(
                frame = frame(timestampMs = 20L),
                personTrackEpoch = firstPersonEpoch,
                viewQualifications = listOf(
                    secondSource.qualifyView(
                        SIDE_VIEW,
                        secondSource.newPersonTrackEpoch(),
                        20L,
                    ),
                ),
            )
        }
    }

    @Test
    fun viewQualificationCannotReplayAcrossTimestampOrPersonEpoch() {
        val source = PoseObservationSource(contract())
        val firstEpoch = source.newPersonTrackEpoch()
        val secondEpoch = source.newPersonTrackEpoch()
        val viewAtTen = source.qualifyView(SIDE_VIEW, firstEpoch, 10L)

        assertThrows(IllegalArgumentException::class.java) {
            source.attest(
                frame = frame(11L),
                personTrackEpoch = firstEpoch,
                viewQualifications = listOf(viewAtTen),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            source.attest(
                frame = frame(10L),
                personTrackEpoch = secondEpoch,
                viewQualifications = listOf(viewAtTen),
            )
        }
    }

    @Test
    fun attestationSnapshotsTheRawFrameAndRejectsDuplicateViewTokens() {
        val source = PoseObservationSource(contract())
        val mutableWorld = linkedMapOf(
            PoseJoint.LEFT_HIP to PoseLandmark(0.0, 0.0, 0.0),
        )
        val personEpoch = source.newPersonTrackEpoch()
        val sideView = source.qualifyView(SIDE_VIEW, personEpoch, 42L)
        val mutableViews = mutableListOf(sideView)
        val rawFrame = frame(timestampMs = 42L).copy(worldLandmarks = mutableWorld)
        val observation = source.attest(
            frame = rawFrame,
            personTrackEpoch = personEpoch,
            viewQualifications = mutableViews,
        )
        mutableWorld.clear()
        mutableViews.clear()

        assertTrue(observation.hasPrimaryPersonLock)
        assertTrue(PoseJoint.LEFT_HIP in observation.frame.worldLandmarks)
        assertTrue(observation.isViewQualified(SIDE_VIEW))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (observation.frame.worldLandmarks as MutableMap<PoseJoint, PoseLandmark>).clear()
        }

        assertThrows(IllegalArgumentException::class.java) {
            source.attest(
                frame = frame(timestampMs = 43L),
                personTrackEpoch = null,
                viewQualifications = listOf(sideView, sideView),
            )
        }
    }

    @Test
    fun geometryAwareAttestationBindsSourceEpochTimestampFrameAndViewReceipt() {
        val source = PoseObservationSource(contract())
        val geometry = geometryContext()
        val geometryEpoch = source.newCameraGeometryEpoch(geometry)
        val personEpoch = source.newPersonTrackEpoch()
        val view = source.qualifyView(SIDE_VIEW, personEpoch, 42L)

        val observation = source.attest(
            frame = geometryFrame(timestampMs = 42L),
            personTrackEpoch = personEpoch,
            viewQualifications = listOf(view),
            cameraGeometryEpoch = geometryEpoch,
        )
        val receipt = checkNotNull(observation.cameraGeometryReceipt)

        assertSame(source, receipt.source)
        assertSame(geometryEpoch, receipt.epoch)
        assertSame(geometryEpoch, observation.cameraGeometryEpoch)
        assertEquals(42L, receipt.frameTimestampMs)
        assertEquals(geometry.artifactSha256, receipt.contextArtifactSha256)
        assertSame(view, observation.viewQualification(SIDE_VIEW))

        val legacy = source.attest(
            frame = frame(timestampMs = 43L),
            personTrackEpoch = null,
            viewQualifications = emptyList(),
        )
        assertNull(legacy.cameraGeometryReceipt)
        assertNull(legacy.cameraGeometryEpoch)
    }

    @Test
    fun geometryEpochRejectsForeignSourceAndPreprocessingArtifact() {
        val firstSource = PoseObservationSource(contract())
        val secondSource = PoseObservationSource(contract())
        val secondEpoch = secondSource.newCameraGeometryEpoch(geometryContext())

        assertThrows(IllegalArgumentException::class.java) {
            firstSource.newCameraGeometryEpoch(
                geometryContext(preprocessingArtifactSha256 = SHA_C),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            firstSource.attest(
                frame = geometryFrame(10L),
                personTrackEpoch = null,
                viewQualifications = emptyList(),
                cameraGeometryEpoch = secondEpoch,
            )
        }
    }

    @Test
    fun geometryReceiptRejectsOutputFrameDimensionRotationOrMirrorDrift() {
        val source = PoseObservationSource(contract())
        val epoch = source.newCameraGeometryEpoch(geometryContext())
        val mismatchedFrames = listOf(
            geometryFrame(10L, imageWidth = 999),
            geometryFrame(10L, imageHeight = 1_599),
            geometryFrame(10L, rotationDegrees = 90),
            geometryFrame(10L, isMirrored = false),
        )

        mismatchedFrames.forEach { mismatchedFrame ->
            assertThrows(IllegalArgumentException::class.java) {
                source.attest(
                    frame = mismatchedFrame,
                    personTrackEpoch = null,
                    viewQualifications = emptyList(),
                    cameraGeometryEpoch = epoch,
                )
            }
        }
    }

    @Test
    fun geometryMintingStopsWithSourceAndRawTokenConstructorsArePrivate() {
        assertOnlyPrivateSourceConstructors(PoseCameraGeometryEpoch::class.java)
        assertOnlyPrivateSourceConstructors(PoseCameraGeometryReceipt::class.java)

        val source = PoseObservationSource(contract())
        val epoch = source.newCameraGeometryEpoch(geometryContext())
        source.close()

        assertThrows(IllegalStateException::class.java) {
            source.newCameraGeometryEpoch(geometryContext())
        }
        assertThrows(IllegalStateException::class.java) {
            source.attest(
                frame = geometryFrame(10L),
                personTrackEpoch = null,
                viewQualifications = emptyList(),
                cameraGeometryEpoch = epoch,
            )
        }
    }

    private fun assertOnlyPrivateSourceConstructors(type: Class<*>) {
        val sourceConstructors = type.declaredConstructors.filterNot { it.isSynthetic }
        assertTrue(sourceConstructors.isNotEmpty())
        assertTrue(sourceConstructors.all { constructor ->
            Modifier.isPrivate(constructor.modifiers)
        })
        assertTrue(type.declaredConstructors.all { constructor ->
            Modifier.isPrivate(constructor.modifiers) || constructor.isSynthetic
        })
    }

    private fun contract(
        runtimeDomainId: String = "mediapipe-full.world.side-view.v1",
        modelArtifactId: String = "mediapipe.pose-landmarker.full.v1",
        modelArtifactSha256: String = SHA_A,
        inferenceOptionsContractId: String = "mediapipe.video-options.v1",
        inferenceOptionsArtifactSha256: String = SHA_D,
        preprocessingContractId: String = "camerax.viewport-rotation-no-mirror.v1",
        preprocessingArtifactSha256: String = SHA_B,
        landmarkSchemaId: String = "mediapipe.pose-33.v1",
        landmarkSchemaArtifactSha256: String = SHA_C,
        supportedCoordinateSpaces: Set<PoseCoordinateSpace> = setOf(
            PoseCoordinateSpace.NORMALIZED_IMAGE,
            PoseCoordinateSpace.WORLD,
        ),
        phaseViewContractId: String = SIDE_VIEW,
        allowedViewContractIds: Set<String> = setOf(SIDE_VIEW),
        personLockArtifactId: String = "primary-person.temporal-lock.v1",
        personLockArtifactSha256: String = SHA_B,
        viewQualifierArtifactId: String = "body-yaw.qualifier.v1",
        viewQualifierArtifactSha256: String = SHA_C,
    ): PoseObservationContract = PoseObservationContract(
        runtimeDomainId = runtimeDomainId,
        modelArtifactId = modelArtifactId,
        modelArtifactSha256 = modelArtifactSha256,
        inferenceOptionsContractId = inferenceOptionsContractId,
        inferenceOptionsArtifactSha256 = inferenceOptionsArtifactSha256,
        preprocessingContractId = preprocessingContractId,
        preprocessingArtifactSha256 = preprocessingArtifactSha256,
        landmarkSchemaId = landmarkSchemaId,
        landmarkSchemaArtifactSha256 = landmarkSchemaArtifactSha256,
        supportedCoordinateSpaces = supportedCoordinateSpaces,
        phaseViewContractId = phaseViewContractId,
        allowedViewContractIds = allowedViewContractIds,
        personLockArtifactId = personLockArtifactId,
        personLockArtifactSha256 = personLockArtifactSha256,
        viewQualifierArtifactId = viewQualifierArtifactId,
        viewQualifierArtifactSha256 = viewQualifierArtifactSha256,
    )

    private fun frame(timestampMs: Long): PoseFrame = PoseFrame(
        timestampMs = timestampMs,
        landmarks = mapOf(PoseJoint.NOSE to PoseLandmark(0.5, 0.2)),
        worldLandmarks = mapOf(PoseJoint.NOSE to PoseLandmark(0.0, 0.2, -0.1)),
    )

    private fun geometryFrame(
        timestampMs: Long,
        imageWidth: Int = 1_000,
        imageHeight: Int = 1_600,
        rotationDegrees: Int = 0,
        isMirrored: Boolean = true,
    ): PoseFrame = frame(timestampMs).copy(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        rotationDegrees = rotationDegrees,
        isMirrored = isMirrored,
    )

    private fun geometryContext(
        preprocessingArtifactSha256: String = SHA_B,
    ): PoseCameraGeometryContext = PoseCameraGeometryContext(
        sourceImageWidth = 1_920,
        sourceImageHeight = 1_080,
        cropLeft = 100,
        cropTop = 20,
        cropRightExclusive = 1_700,
        cropBottomExclusive = 1_020,
        inputRotationDegrees = 90,
        outputImageWidth = 1_000,
        outputImageHeight = 1_600,
        inferencePixelsMirrored = false,
        displayMirrored = true,
        preprocessingArtifactSha256 = preprocessingArtifactSha256,
    )

    private companion object {
        const val SIDE_VIEW = "side-view.body-yaw-window.v1"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SHA_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
