package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryEpoch
import com.example.trex_kotlin.pose.runtime.PoseObservationContract
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import com.example.trex_kotlin.pose.runtime.PosePersonTrackEpoch
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseObservationResearchCapabilityEvidenceTest {
    @Test
    fun mintsAggregateResearchReceiptOnlyAfterTwoContinuousCompleteFrames() {
        val fixture = Fixture()
        val evidence = PoseObservationResearchCapabilityEvidence(fixture.source, 100L)

        val firstObservation = fixture.observation(1_000L)
        val secondObservation = fixture.observation(1_040L)
        val first = evidence.accept(firstObservation)
        val second = evidence.accept(secondObservation)

        assertEquals(PoseObservationResearchCapabilityEvidenceStatus.STABILIZING, first.status)
        assertNull(first.receipt)
        assertEquals(
            PoseObservationResearchCapabilityEvidenceStatus.RECEIPT_READY,
            second.status,
        )
        val receipt = requireNotNull(second.receipt)
        assertEquals(PoseObservationResearchCapabilities.EXACT_CAPABILITY_IDS, receipt.capabilityIds)
        assertEquals(2, receipt.observationCount)
        assertEquals(33, receipt.normalizedLandmarkCount)
        assertEquals(33, receipt.worldLandmarkCount)
        assertEquals(40L, receipt.frameGapMs)
        assertEquals(100L, receipt.maximumFrameGapMs)
        assertEquals(fixture.source.contract.runtimeDomainId, receipt.runtimeDomainId)
        assertEquals(fixture.source.contract.artifactSha256, receipt.observationContractSha256)
        assertEquals(fixture.geometry.contextArtifactSha256, receipt.cameraGeometryContextSha256)
        assertTrue(receipt.hasCanonicalProvenance(secondObservation))
        assertTrue(receipt.evidenceContractSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(receipt.firstObservationEvidenceSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(receipt.secondObservationEvidenceSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(receipt.receiptSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertFalse(receipt.hasCanonicalProvenance(firstObservation))
        assertFalse(receipt.grantsMeasurementAuthority)
        assertFalse(receipt.grantsShadowAuthority)
        assertFalse(receipt.grantsVerdictAuthority)
        assertFalse(receipt.grantsScoreAuthority)
        assertFalse(receipt.grantsCueAuthority)
        assertFalse(receipt.grantsReleaseAuthority)
        val fields = PoseObservationResearchCapabilityReceipt::class.java.declaredFields
        assertTrue(fields.none { field ->
            field.type in setOf(AttestedPoseObservation::class.java, PoseFrame::class.java)
        })
        listOf(
            PoseObservationSource::class.java,
            PosePersonTrackEpoch::class.java,
            PoseCameraGeometryEpoch::class.java,
        ).forEach { opaqueType ->
            assertTrue(fields.single { it.type == opaqueType }.let { field ->
                Modifier.isPrivate(field.modifiers)
            })
        }
    }

    @Test
    fun rejectsAndResetsForeignClosedAndMissingObservationEvidence() {
        val fixture = Fixture()
        val evidence = PoseObservationResearchCapabilityEvidence(fixture.source, 100L)
        evidence.accept(fixture.observation(1_000L))

        val missing = evidence.accept(
            fixture.observation(
                timestampMs = 1_020L,
                includePerson = false,
                includeLateral = false,
                includeGeometry = false,
                normalizedJoints = PoseJoint.entries.dropLast(1).toSet(),
                worldJoints = PoseJoint.entries.dropLast(1).toSet(),
            ),
        )
        assertEquals(PoseObservationResearchCapabilityEvidenceStatus.REJECTED_RESET, missing.status)
        assertEquals(
            setOf(
                PoseObservationResearchCapabilityRejectionReason.PRIMARY_PERSON_LOCK_MISSING,
                PoseObservationResearchCapabilityRejectionReason
                    .LATERAL_VIEW_QUALIFICATION_MISSING,
                PoseObservationResearchCapabilityRejectionReason.CAMERA_GEOMETRY_MISSING,
                PoseObservationResearchCapabilityRejectionReason.NORMALIZED_LANDMARKS_INCOMPLETE,
                PoseObservationResearchCapabilityRejectionReason.WORLD_LANDMARKS_INCOMPLETE,
            ),
            missing.rejectionReasons,
        )
        assertEquals(
            PoseObservationResearchCapabilityEvidenceStatus.STABILIZING,
            evidence.accept(fixture.observation(1_040L)).status,
        )

        val foreign = Fixture()
        val foreignResult = evidence.accept(foreign.observation(1_050L))
        assertTrue(
            PoseObservationResearchCapabilityRejectionReason.FOREIGN_SOURCE in
                foreignResult.rejectionReasons,
        )

        val beforeClose = fixture.observation(1_060L)
        fixture.source.close()
        val closed = evidence.accept(beforeClose)
        assertTrue(
            PoseObservationResearchCapabilityRejectionReason.SOURCE_CLOSED in
                closed.rejectionReasons,
        )
    }

    @Test
    fun temporalPersonAndGeometryDiscontinuitiesCannotBridgeAReceipt() {
        val fixture = Fixture()
        val evidence = PoseObservationResearchCapabilityEvidence(fixture.source, 50L)

        evidence.accept(fixture.observation(100L))
        assertRejected(
            evidence.accept(fixture.observation(100L)),
            PoseObservationResearchCapabilityRejectionReason.TIMESTAMP_NOT_STRICTLY_INCREASING,
        )
        assertEquals(
            PoseObservationResearchCapabilityEvidenceStatus.STABILIZING,
            evidence.accept(fixture.observation(110L)).status,
        )
        assertRejected(
            evidence.accept(fixture.observation(200L)),
            PoseObservationResearchCapabilityRejectionReason.MAXIMUM_FRAME_GAP_EXCEEDED,
        )

        evidence.accept(fixture.observation(210L))
        assertRejected(
            evidence.accept(
                fixture.observation(220L, personEpoch = fixture.source.newPersonTrackEpoch()),
            ),
            PoseObservationResearchCapabilityRejectionReason.PERSON_TRACK_EPOCH_DRIFT,
        )

        evidence.accept(fixture.observation(230L))
        assertRejected(
            evidence.accept(
                fixture.observation(
                    240L,
                    geometryEpoch = fixture.source.newCameraGeometryEpoch(fixture.context),
                ),
            ),
            PoseObservationResearchCapabilityRejectionReason.CAMERA_GEOMETRY_EPOCH_DRIFT,
        )

        evidence.accept(fixture.observation(250L))
        val changedContext = geometryContext(cropLeft = 0, cropRightExclusive = 1_600)
        assertRejected(
            evidence.accept(
                fixture.observation(
                    260L,
                    geometryEpoch = fixture.source.newCameraGeometryEpoch(changedContext),
                ),
            ),
            PoseObservationResearchCapabilityRejectionReason.CAMERA_GEOMETRY_CONTEXT_DRIFT,
        )
    }

    @Test
    fun sourceContractAndReceiptMintingFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            PoseObservationResearchCapabilityEvidence(Fixture(coordinateSpaces =
                setOf(PoseCoordinateSpace.NORMALIZED_IMAGE)).source, 100L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PoseObservationResearchCapabilityEvidence(Fixture().source, 0L)
        }
        val fixture = Fixture()
        assertThrows(IllegalStateException::class.java) {
            PoseObservationResearchCapabilityReceipt(
                source = fixture.source,
                personTrackEpoch = fixture.person,
                cameraGeometryEpoch = fixture.geometry,
                providerSchemaVersion = 1,
                runtimeDomainId = "runtime.research.v1",
                observationContractSha256 = SHA_A,
                modelArtifactSha256 = SHA_A,
                inferenceOptionsArtifactSha256 = SHA_A,
                preprocessingArtifactSha256 = SHA_A,
                landmarkSchemaArtifactSha256 = SHA_A,
                personLockArtifactSha256 = SHA_A,
                viewQualifierArtifactSha256 = SHA_A,
                lateralViewContractId =
                    PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
                maximumFrameGapMs = 100L,
                cameraGeometryContextSha256 = SHA_A,
                firstTimestampMs = 1L,
                secondTimestampMs = 2L,
                frameGapMs = 1L,
                firstObservationEvidenceSha256 = SHA_A,
                secondObservationEvidenceSha256 = SHA_B,
                mintAuthority = Any(),
            )
        }
    }

    private fun assertRejected(
        update: PoseObservationResearchCapabilityEvidenceUpdate,
        reason: PoseObservationResearchCapabilityRejectionReason,
    ) {
        assertEquals(PoseObservationResearchCapabilityEvidenceStatus.REJECTED_RESET, update.status)
        assertTrue(reason in update.rejectionReasons)
        assertNull(update.receipt)
    }

    private class Fixture(
        coordinateSpaces: Set<PoseCoordinateSpace> = PoseCoordinateSpace.entries.toSet(),
    ) {
        val source = PoseObservationSource(contract(coordinateSpaces))
        val context = geometryContext()
        val geometry = source.newCameraGeometryEpoch(context)
        val person = source.newPersonTrackEpoch()

        fun observation(
            timestampMs: Long,
            personEpoch: PosePersonTrackEpoch = person,
            geometryEpoch: PoseCameraGeometryEpoch = geometry,
            includePerson: Boolean = true,
            includeLateral: Boolean = true,
            includeGeometry: Boolean = true,
            normalizedJoints: Set<PoseJoint> = PoseJoint.entries.toSet(),
            worldJoints: Set<PoseJoint> = PoseJoint.entries.toSet(),
        ): AttestedPoseObservation {
            val epoch = personEpoch.takeIf { includePerson }
            val views = if (epoch != null && includeLateral) {
                listOf(
                    source.qualifyView(
                        PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
                        epoch,
                        timestampMs,
                    ),
                )
            } else {
                emptyList()
            }
            val frame = PoseFrame(
                timestampMs = timestampMs,
                landmarks = normalizedJoints.associateWith { landmark(it.mediaPipeIndex) },
                worldLandmarks = worldJoints.associateWith { landmark(it.mediaPipeIndex) },
                imageWidth = 1_000,
                imageHeight = 1_600,
                isMirrored = true,
            )
            return if (includeGeometry) {
                source.attest(frame, epoch, views, geometryEpoch)
            } else {
                source.attest(frame, epoch, views)
            }
        }
    }

    private companion object {
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SHA_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"

        fun contract(coordinateSpaces: Set<PoseCoordinateSpace>) = PoseObservationContract(
            runtimeDomainId = "mediapipe-full.research.v1",
            modelArtifactId = "mediapipe.pose-landmarker.full.v1",
            modelArtifactSha256 = SHA_A,
            inferenceOptionsContractId = "mediapipe.video-options.v1",
            inferenceOptionsArtifactSha256 = SHA_D,
            preprocessingContractId = "camerax.geometry-described.v1",
            preprocessingArtifactSha256 = SHA_B,
            landmarkSchemaId = "mediapipe.pose-33.v1",
            landmarkSchemaArtifactSha256 = SHA_C,
            supportedCoordinateSpaces = coordinateSpaces,
            phaseViewContractId = "trex.view.full-body-any.v1",
            allowedViewContractIds = setOf(
                "trex.view.full-body-any.v1",
                PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
            ),
            personLockArtifactId = "primary-person.temporal-lock.v1",
            personLockArtifactSha256 = SHA_B,
            viewQualifierArtifactId = "body-yaw.qualifier.v1",
            viewQualifierArtifactSha256 = SHA_C,
        )

        fun geometryContext(
            cropLeft: Int = 100,
            cropRightExclusive: Int = 1_700,
        ) = PoseCameraGeometryContext(
            sourceImageWidth = 1_920,
            sourceImageHeight = 1_080,
            cropLeft = cropLeft,
            cropTop = 20,
            cropRightExclusive = cropRightExclusive,
            cropBottomExclusive = 1_020,
            inputRotationDegrees = 90,
            outputImageWidth = 1_000,
            outputImageHeight = 1_600,
            inferencePixelsMirrored = false,
            displayMirrored = true,
            preprocessingArtifactSha256 = SHA_B,
        )

        fun landmark(index: Int) = PoseLandmark(
            x = index / 100.0,
            y = index / 100.0,
            z = index / 1_000.0,
        )
    }
}
