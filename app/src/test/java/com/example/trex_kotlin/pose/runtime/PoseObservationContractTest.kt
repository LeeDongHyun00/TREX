package com.example.trex_kotlin.pose.runtime

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
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
            contract(preprocessingContractId = "camerax.viewport-rotation-no-mirror.v2"),
            contract(preprocessingArtifactSha256 = SHA_C),
            contract(landmarkSchemaId = "mediapipe.pose-33.v2"),
            contract(landmarkSchemaArtifactSha256 = SHA_D),
            contract(supportedCoordinateSpaces = setOf(PoseCoordinateSpace.WORLD)),
            contract(phaseViewContractId = "front-view.body-yaw-window.v1"),
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
    }

    @Test
    fun sourceAndDynamicEvidenceAreOpaqueAndBoundByReferenceIdentity() {
        val artifact = contract()
        val firstSource = PoseObservationSource(artifact)
        val secondSource = PoseObservationSource(artifact)
        val firstPersonEpoch = firstSource.newPersonTrackEpoch()
        val sideView = firstSource.qualifyView(SIDE_VIEW)
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
                viewQualifications = listOf(secondSource.qualifyView(SIDE_VIEW)),
            )
        }
    }

    @Test
    fun attestationSnapshotsTheRawFrameAndRejectsDuplicateViewTokens() {
        val source = PoseObservationSource(contract())
        val mutableWorld = linkedMapOf(
            PoseJoint.LEFT_HIP to PoseLandmark(0.0, 0.0, 0.0),
        )
        val sideView = source.qualifyView(SIDE_VIEW)
        val mutableViews = mutableListOf(sideView)
        val rawFrame = frame(timestampMs = 42L).copy(worldLandmarks = mutableWorld)
        val observation = source.attest(
            frame = rawFrame,
            personTrackEpoch = null,
            viewQualifications = mutableViews,
        )
        mutableWorld.clear()
        mutableViews.clear()

        assertFalse(observation.hasPrimaryPersonLock)
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

    private fun contract(
        runtimeDomainId: String = "mediapipe-full.world.side-view.v1",
        modelArtifactId: String = "mediapipe.pose-landmarker.full.v1",
        modelArtifactSha256: String = SHA_A,
        preprocessingContractId: String = "camerax.viewport-rotation-no-mirror.v1",
        preprocessingArtifactSha256: String = SHA_B,
        landmarkSchemaId: String = "mediapipe.pose-33.v1",
        landmarkSchemaArtifactSha256: String = SHA_C,
        supportedCoordinateSpaces: Set<PoseCoordinateSpace> = setOf(
            PoseCoordinateSpace.NORMALIZED_IMAGE,
            PoseCoordinateSpace.WORLD,
        ),
        phaseViewContractId: String = SIDE_VIEW,
        personLockArtifactId: String = "primary-person.temporal-lock.v1",
        personLockArtifactSha256: String = SHA_B,
        viewQualifierArtifactId: String = "body-yaw.qualifier.v1",
        viewQualifierArtifactSha256: String = SHA_C,
    ): PoseObservationContract = PoseObservationContract(
        runtimeDomainId = runtimeDomainId,
        modelArtifactId = modelArtifactId,
        modelArtifactSha256 = modelArtifactSha256,
        preprocessingContractId = preprocessingContractId,
        preprocessingArtifactSha256 = preprocessingArtifactSha256,
        landmarkSchemaId = landmarkSchemaId,
        landmarkSchemaArtifactSha256 = landmarkSchemaArtifactSha256,
        supportedCoordinateSpaces = supportedCoordinateSpaces,
        phaseViewContractId = phaseViewContractId,
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

    private companion object {
        const val SIDE_VIEW = "side-view.body-yaw-window.v1"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SHA_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
