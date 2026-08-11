package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.PoseSide
import com.example.trex_kotlin.pose.feature.FeatureUnknownReason
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryEpoch
import com.example.trex_kotlin.pose.runtime.PoseObservationContract
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import com.example.trex_kotlin.pose.runtime.PosePersonTrackEpoch
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoodMorningKneeStabilityResearchTraceTest {
    @Test
    fun canonicalWorldTraceIsRelativeImmutableUnknownAndClearsOnSeal() {
        val fixture = Fixture()
        val trace = fixture.trace(maximumSamples = 4, maximumDurationMs = 500L)

        assertEquals(
            GoodMorningKneeStabilityTraceStatus.STABILIZING,
            trace.accept(fixture.observation(1_000L)).status,
        )
        val second = trace.accept(fixture.observation(1_040L))
        val third = trace.accept(fixture.observation(1_080L))

        assertEquals(GoodMorningKneeStabilityTraceStatus.SAMPLE_APPENDED, second.status)
        assertEquals(40L, second.appendedSample!!.elapsedMs)
        assertEquals(80L, third.appendedSample!!.elapsedMs)
        assertEquals(60.0, second.appendedSample.left.flexionDegrees!!, 1e-6)
        assertEquals(60.0, second.appendedSample.right.flexionDegrees!!, 1e-6)
        assertEquals(0.99, second.appendedSample.left.rawConfidence, 1e-9)
        assertNull(second.appendedSample.left.featureUnknownReason)
        assertNotEquals(second.appendedSample.contentSha256, third.appendedSample.contentSha256)

        val snapshot = trace.seal()
        assertEquals(listOf(40L, 80L), snapshot.samples.map { it.elapsedMs })
        assertEquals(80L, snapshot.durationMs)
        assertEquals(GoodMorningKneeFlexionResearchState.UNKNOWN, snapshot.state)
        assertFalse(snapshot.isGold)
        assertEquals(0, snapshot.authority.totalAuthority)
        assertEquals(GoodMorningKneeStabilityTraceBlocker.entries.toSet(), snapshot.blockers)
        assertEquals(PoseCoordinateSpace.WORLD, snapshot.coordinateSpace)
        assertEquals(fixture.context.artifactSha256, snapshot.geometry.contextSha256)
        assertTrue(snapshot.contentSha256.matches(SHA_REGEX))
        assertEquals(0, trace.retainedSampleCount)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.samples as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.blockers as MutableSet).clear()
        }
        assertEquals(
            GoodMorningKneeStabilityTraceStatus.STABILIZING,
            trace.accept(fixture.observation(1_120L)).status,
        )
    }

    @Test
    fun oneSideMayAbstainWithoutDiscardingTheOtherSide() {
        val fixture = Fixture(leftConfidence = 0.1)
        val trace = fixture.trace()
        trace.accept(fixture.observation(1_000L))

        val sample = trace.accept(fixture.observation(1_040L)).appendedSample!!

        assertNull(sample.left.flexionDegrees)
        assertEquals(0.1, sample.left.rawConfidence, 1e-9)
        assertEquals(FeatureUnknownReason.LOW_CONFIDENCE, sample.left.featureUnknownReason)
        assertEquals(60.0, sample.right.flexionDegrees!!, 1e-6)
        assertNull(sample.right.featureUnknownReason)
    }

    @Test
    fun everyObservationDiscontinuityClearsTheWholeTraceAndRequiresRestabilization() {
        fun assertEvidenceReset(
            expectedReason: PoseObservationResearchCapabilityRejectionReason,
            invalid: (Fixture) -> AttestedPoseObservation,
        ) {
            val fixture = Fixture()
            val trace = fixture.trace(maximumGapMs = 50L)
            trace.accept(fixture.observation(1_000L))
            trace.accept(fixture.observation(1_020L))

            val reset = trace.accept(invalid(fixture))

            assertEquals(GoodMorningKneeStabilityTraceStatus.RESET, reset.status)
            assertEquals(
                GoodMorningKneeStabilityTraceResetReason.OBSERVATION_EVIDENCE_REJECTED,
                reset.resetReason,
            )
            assertTrue(expectedReason in reset.evidenceRejectionReasons)
            assertEquals(0, trace.retainedSampleCount)
            assertEquals(
                GoodMorningKneeStabilityTraceStatus.STABILIZING,
                trace.accept(fixture.observation(1_040L)).status,
            )
        }

        assertEvidenceReset(PoseObservationResearchCapabilityRejectionReason.FOREIGN_SOURCE) {
            Fixture().observation(1_030L)
        }
        assertEvidenceReset(PoseObservationResearchCapabilityRejectionReason.PERSON_TRACK_EPOCH_DRIFT) {
            it.observation(1_030L, personEpoch = it.source.newPersonTrackEpoch())
        }
        assertEvidenceReset(PoseObservationResearchCapabilityRejectionReason.CAMERA_GEOMETRY_EPOCH_DRIFT) {
            it.observation(1_030L, geometryEpoch = it.source.newCameraGeometryEpoch(it.context))
        }
        assertEvidenceReset(
            PoseObservationResearchCapabilityRejectionReason.LATERAL_VIEW_QUALIFICATION_MISSING,
        ) { it.observation(1_030L, includeView = false) }
        assertEvidenceReset(
            PoseObservationResearchCapabilityRejectionReason.TIMESTAMP_NOT_STRICTLY_INCREASING,
        ) { it.observation(1_020L) }
        assertEvidenceReset(PoseObservationResearchCapabilityRejectionReason.MAXIMUM_FRAME_GAP_EXCEEDED) {
            it.observation(1_080L)
        }
        assertEvidenceReset(
            PoseObservationResearchCapabilityRejectionReason.NORMALIZED_LANDMARKS_INCOMPLETE,
        ) { it.observation(1_030L, missingNormalized = PoseJoint.NOSE) }
        assertEvidenceReset(
            PoseObservationResearchCapabilityRejectionReason.WORLD_LANDMARKS_INCOMPLETE,
        ) { it.observation(1_030L, missingWorld = PoseJoint.NOSE) }

        val closedFixture = Fixture()
        val closedTrace = closedFixture.trace()
        closedTrace.accept(closedFixture.observation(1_000L))
        closedTrace.accept(closedFixture.observation(1_020L))
        val beforeClose = closedFixture.observation(1_030L)
        closedFixture.source.close()
        val closedReset = closedTrace.accept(beforeClose)
        assertTrue(
            PoseObservationResearchCapabilityRejectionReason.SOURCE_CLOSED in
                closedReset.evidenceRejectionReasons,
        )
        assertEquals(0, closedTrace.retainedSampleCount)
    }

    @Test
    fun boundsEarlySealCloseAndSurfaceAllFailClosed() {
        val capacityFixture = Fixture()
        val capacityTrace = capacityFixture.trace(maximumSamples = 2)
        capacityTrace.accept(capacityFixture.observation(1_000L))
        capacityTrace.accept(capacityFixture.observation(1_040L))
        capacityTrace.accept(capacityFixture.observation(1_060L))
        assertEquals(
            GoodMorningKneeStabilityTraceResetReason.SAMPLE_CAPACITY_EXCEEDED,
            capacityTrace.accept(capacityFixture.observation(1_080L)).resetReason,
        )
        assertEquals(0, capacityTrace.retainedSampleCount)

        val durationFixture = Fixture()
        val durationTrace = durationFixture.trace(maximumDurationMs = 50L)
        durationTrace.accept(durationFixture.observation(1_000L))
        durationTrace.accept(durationFixture.observation(1_040L))
        assertEquals(
            GoodMorningKneeStabilityTraceResetReason.TRACE_DURATION_EXCEEDED,
            durationTrace.accept(durationFixture.observation(1_060L)).resetReason,
        )
        assertEquals(0, durationTrace.retainedSampleCount)

        val earlySealFixture = Fixture()
        val earlySealTrace = earlySealFixture.trace()
        earlySealTrace.accept(earlySealFixture.observation(1_000L))
        earlySealTrace.accept(earlySealFixture.observation(1_040L))
        assertThrows(IllegalStateException::class.java) { earlySealTrace.seal() }
        assertEquals(1, earlySealTrace.retainedSampleCount)

        assertThrows(IllegalArgumentException::class.java) {
            Fixture().trace(maximumSamples = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Fixture().trace(maximumSamples = 2_049)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Fixture().trace(maximumDurationMs = 600_001L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Fixture().trace(maximumGapMs = 1_001L)
        }

        val closeFixture = Fixture()
        val closeTrace = closeFixture.trace()
        closeTrace.accept(closeFixture.observation(1_000L))
        closeTrace.accept(closeFixture.observation(1_040L))
        closeTrace.close()
        closeTrace.close()
        assertEquals(0, closeTrace.retainedSampleCount)
        assertThrows(IllegalStateException::class.java) {
            closeTrace.accept(closeFixture.observation(1_080L))
        }
        assertThrows(IllegalStateException::class.java) { closeTrace.seal() }

        val forbiddenTypes = setOf(
            PoseFrame::class.java,
            PoseLandmark::class.java,
            AttestedPoseObservation::class.java,
            PoseObservationSource::class.java,
            PosePersonTrackEpoch::class.java,
            PoseCameraGeometryEpoch::class.java,
        )
        listOf(
            GoodMorningKneeStabilityCandidateSnapshot::class.java,
            GoodMorningKneeStabilityTraceSample::class.java,
            GoodMorningKneeStabilitySideSample::class.java,
            GoodMorningKneeStabilityGeometry::class.java,
        ).forEach { type ->
            assertTrue(type.declaredFields.none { it.type in forbiddenTypes })
            assertTrue(type.declaredFields.none { field ->
                field.name.contains("timestamp", ignoreCase = true)
            })
        }
        val accepts = GoodMorningKneeStabilityResearchTrace::class.java.declaredMethods
            .filter { it.name == "accept" }
        assertEquals(1, accepts.size)
        assertEquals(listOf(AttestedPoseObservation::class.java), accepts.single().parameterTypes.toList())
        assertTrue(
            GoodMorningKneeStabilityResearchTrace::class.java.declaredFields
                .filter { it.name in setOf("source", "evidence", "diagnostic") }
                .all { !Modifier.isPublic(it.modifiers) },
        )

        val sourceRoot = listOf(File("src/main/java"), File("app/src/main/java"))
            .find(File::isDirectory)
        if (sourceRoot != null) {
            val callers = sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.name == "GoodMorningKneeStabilityResearchTrace.kt" }
                .filter { it.readText().contains("GoodMorningKneeStabilityResearchTrace") }
                .toList()
            assertTrue("Unexpected production caller: $callers", callers.isEmpty())
            val implementation = sourceRoot.resolve(
                "com/example/trex_kotlin/pose/research/GoodMorningKneeStabilityResearchTrace.kt",
            ).readText()
            assertFalse(implementation.contains("java.io"))
            assertFalse(implementation.contains("android.graphics"))
            assertFalse(implementation.contains("pass", ignoreCase = true))
            assertFalse(implementation.contains("score", ignoreCase = true))
            assertFalse(implementation.contains("cue", ignoreCase = true))
        }
    }

    private class Fixture(private val leftConfidence: Double = 0.99) {
        val source = PoseObservationSource(contract())
        val context = geometryContext()
        private val geometry = source.newCameraGeometryEpoch(context)
        private val person = source.newPersonTrackEpoch()

        fun trace(
            maximumSamples: Int = 8,
            maximumDurationMs: Long = 1_000L,
            maximumGapMs: Long = 100L,
        ) = GoodMorningKneeStabilityResearchTrace(
            expectedSource = source,
            maximumSamples = maximumSamples,
            maximumTraceDurationMs = maximumDurationMs,
            maximumFrameGapMs = maximumGapMs,
        )

        fun observation(
            timestampMs: Long,
            personEpoch: PosePersonTrackEpoch = person,
            geometryEpoch: PoseCameraGeometryEpoch = geometry,
            includeView: Boolean = true,
            missingNormalized: PoseJoint? = null,
            missingWorld: PoseJoint? = null,
        ): AttestedPoseObservation {
            val normalized = normalizedLandmarks().filterKeys { it != missingNormalized }
            val world = worldLandmarks().filterKeys { it != missingWorld }
            val views = if (includeView) {
                listOf(
                    source.qualifyView(
                        PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
                        personEpoch,
                        timestampMs,
                    ),
                )
            } else {
                emptyList()
            }
            return source.attest(
                frame = PoseFrame(
                    timestampMs = timestampMs,
                    landmarks = normalized,
                    worldLandmarks = world,
                    imageWidth = 1_000,
                    imageHeight = 1_000,
                ),
                personTrackEpoch = personEpoch,
                viewQualifications = views,
                cameraGeometryEpoch = geometryEpoch,
            )
        }

        private fun normalizedLandmarks(): Map<PoseJoint, PoseLandmark> =
            baseLandmarks().apply {
                this[PoseJoint.LEFT_HIP] = landmark(0.2, 0.2)
                this[PoseJoint.LEFT_KNEE] = landmark(0.2, 0.5, leftConfidence)
                this[PoseJoint.LEFT_ANKLE] = landmark(0.5, 0.5)
                this[PoseJoint.RIGHT_HIP] = landmark(0.8, 0.2)
                this[PoseJoint.RIGHT_KNEE] = landmark(0.8, 0.5)
                this[PoseJoint.RIGHT_ANKLE] = landmark(0.5, 0.5)
            }

        private fun worldLandmarks(): Map<PoseJoint, PoseLandmark> =
            baseLandmarks().apply {
                this[PoseJoint.LEFT_HIP] = landmark(0.2, 0.2, z = 0.3)
                this[PoseJoint.LEFT_KNEE] = landmark(0.2, 0.5, leftConfidence, z = 0.3)
                this[PoseJoint.LEFT_ANKLE] = landmark(0.2, 0.65, z = 0.5598076211)
                this[PoseJoint.RIGHT_HIP] = landmark(0.8, 0.2, z = 0.3)
                this[PoseJoint.RIGHT_KNEE] = landmark(0.8, 0.5, z = 0.3)
                this[PoseJoint.RIGHT_ANKLE] = landmark(0.8, 0.65, z = 0.5598076211)
            }

        private fun baseLandmarks(): MutableMap<PoseJoint, PoseLandmark> =
            PoseJoint.entries.associateWith { joint ->
                landmark(
                    x = 0.01 + joint.mediaPipeIndex * 0.001,
                    y = 0.01 + joint.mediaPipeIndex * 0.001,
                    z = 0.01 + joint.mediaPipeIndex * 0.001,
                )
            }.toMutableMap()
    }

    private companion object {
        val SHA_REGEX = Regex("^[0-9a-f]{64}$")
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SHA_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"

        fun contract() = PoseObservationContract(
            runtimeDomainId = "trex.mediapipe-pose-full.video-cpu.v1",
            modelArtifactId = "mediapipe.pose-landmarker.full.v1",
            modelArtifactSha256 = SHA_A,
            inferenceOptionsContractId = "mediapipe.video-options.v1",
            inferenceOptionsArtifactSha256 = SHA_D,
            preprocessingContractId = "camerax.geometry-described.v1",
            preprocessingArtifactSha256 = SHA_B,
            landmarkSchemaId = "mediapipe.pose-33.v1",
            landmarkSchemaArtifactSha256 = SHA_C,
            supportedCoordinateSpaces = PoseCoordinateSpace.entries.toSet(),
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

        fun geometryContext() = PoseCameraGeometryContext(
            sourceImageWidth = 1_000,
            sourceImageHeight = 1_000,
            cropLeft = 0,
            cropTop = 0,
            cropRightExclusive = 1_000,
            cropBottomExclusive = 1_000,
            inputRotationDegrees = 0,
            outputImageWidth = 1_000,
            outputImageHeight = 1_000,
            inferencePixelsMirrored = false,
            displayMirrored = false,
            preprocessingArtifactSha256 = SHA_B,
        )

        fun landmark(
            x: Double,
            y: Double,
            confidence: Double = 0.99,
            z: Double = 0.0,
        ) = PoseLandmark(
            x = x,
            y = y,
            z = z,
            visibility = confidence,
            presence = confidence,
        )
    }
}
