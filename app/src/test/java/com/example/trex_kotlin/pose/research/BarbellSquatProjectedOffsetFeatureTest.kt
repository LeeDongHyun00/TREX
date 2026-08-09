package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.PoseSide
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import com.example.trex_kotlin.pose.runtime.PoseObservationContract
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BarbellSquatProjectedOffsetFeatureTest {
    @Test
    fun deterministicKnownGeometryProducesSeparateBilateralValues() {
        val fixture = fixture()
        val measurement = fixture.feature.measure(
            fixture.observation(landmarks = knownLandmarks()),
        )

        assertEquals("projected-knee-to-foot-index-lateral-offset", measurement.constructId)
        assertEquals(setOf(PoseSide.LEFT, PoseSide.RIGHT), measurement.sideSamples.keys)
        assertEquals(0.4, measurement.measured(PoseSide.LEFT).normalizedOffset, 1e-12)
        assertEquals(0.3, measurement.measured(PoseSide.RIGHT).normalizedOffset, 1e-12)
        assertEquals(0.99, measurement.measured(PoseSide.LEFT).minimumObservedRawConfidence, 0.0)
        assertEquals(0.99, measurement.measured(PoseSide.RIGHT).minimumObservedRawConfidence, 0.0)
    }

    @Test
    fun normalizedImageXIsAspectCorrectedBeforeProjection() {
        val fixture = fixture()
        val diagonal = knownLandmarks().toMutableMap().apply {
            put(PoseJoint.LEFT_SHOULDER, landmark(0.2, 0.2))
            put(PoseJoint.RIGHT_SHOULDER, landmark(0.6, 0.4))
            put(PoseJoint.LEFT_ANKLE, landmark(0.3, 0.8))
            put(PoseJoint.LEFT_KNEE, landmark(0.4, 0.8))
            put(PoseJoint.LEFT_FOOT_INDEX, landmark(0.3, 0.8))
        }

        val wide = fixture.feature.measure(
            fixture.observation(landmarks = diagonal, imageWidth = 200, imageHeight = 100),
        ).measured(PoseSide.LEFT)
        val square = fixture.feature.measure(
            fixture.observation(landmarks = diagonal, imageWidth = 100, imageHeight = 100),
        ).measured(PoseSide.LEFT)

        assertEquals(4.0 / 17.0, wide.normalizedOffset, 1e-12)
        assertEquals(0.2, square.normalizedOffset, 1e-12)
        assertNotEquals(square.normalizedOffset, wide.normalizedOffset, 1e-12)
    }

    @Test
    fun horizontalMirrorLeavesEachAnatomicalSideValueUnchanged() {
        val fixture = fixture()
        val original = fixture.feature.measure(fixture.observation(landmarks = knownLandmarks()))
        val mirroredLandmarks = knownLandmarks().mapValues { (_, point) ->
            point.copy(x = 1.0 - point.x)
        }
        val mirrored = fixture.feature.measure(
            fixture.observation(landmarks = mirroredLandmarks, isMirrored = true),
        )

        PoseSide.entries.forEach { side ->
            assertEquals(
                original.measured(side).normalizedOffset,
                mirrored.measured(side).normalizedOffset,
                1e-12,
            )
        }
    }

    @Test
    fun oneSideCanAbstainWithoutSuppressingOrAveragingTheOther() {
        val fixture = fixture()
        val missingLeftKnee = knownLandmarks() - PoseJoint.LEFT_KNEE

        val measurement = fixture.feature.measure(
            fixture.observation(landmarks = missingLeftKnee),
        )
        val left = measurement.abstained(PoseSide.LEFT)

        assertEquals(
            setOf(ProjectedOffsetAbstentionReason.REQUIRED_JOINT_MISSING),
            left.reasons,
        )
        assertEquals(setOf(PoseJoint.LEFT_KNEE), left.missingJoints)
        assertEquals(0.3, measurement.measured(PoseSide.RIGHT).normalizedOffset, 1e-12)
        assertTrue(
            BarbellSquatProjectedOffsetMeasurement::class.java.declaredMethods
                .none { method -> "average" in method.name.lowercase() },
        )
    }

    @Test
    fun missingAndLowConfidenceJointsAreAttributedPerSide() {
        val fixture = fixture(minimumRawConfidence = 0.8)
        val affected = knownLandmarks().toMutableMap().apply {
            remove(PoseJoint.LEFT_FOOT_INDEX)
            put(PoseJoint.RIGHT_KNEE, landmark(0.65, 0.6, confidence = 0.79))
        }

        val measurement = fixture.feature.measure(fixture.observation(landmarks = affected))
        val left = measurement.abstained(PoseSide.LEFT)
        val right = measurement.abstained(PoseSide.RIGHT)

        assertEquals(setOf(PoseJoint.LEFT_FOOT_INDEX), left.missingJoints)
        assertEquals(
            setOf(ProjectedOffsetAbstentionReason.REQUIRED_JOINT_MISSING),
            left.reasons,
        )
        assertEquals(setOf(PoseJoint.RIGHT_KNEE), right.lowConfidenceJoints)
        assertEquals(
            setOf(ProjectedOffsetAbstentionReason.RAW_CONFIDENCE_BELOW_MINIMUM),
            right.reasons,
        )
    }

    @Test
    fun sharedShoulderFailuresAbstainBothSides() {
        val fixture = fixture(minimumRawConfidence = 0.8)
        val lowShoulder = knownLandmarks() + (
            PoseJoint.LEFT_SHOULDER to landmark(0.25, 0.2, confidence = 0.79)
        )
        val missing = fixture.feature.measure(
            fixture.observation(landmarks = knownLandmarks() - PoseJoint.RIGHT_SHOULDER),
        )
        val low = fixture.feature.measure(fixture.observation(landmarks = lowShoulder))
        val degenerate = fixture.feature.measure(
            fixture.observation(
                landmarks = knownLandmarks() +
                    (PoseJoint.RIGHT_SHOULDER to knownLandmarks().getValue(PoseJoint.LEFT_SHOULDER)),
            ),
        )

        PoseSide.entries.forEach { side ->
            assertEquals(
                setOf(PoseJoint.RIGHT_SHOULDER),
                missing.abstained(side).missingJoints,
            )
            assertEquals(
                setOf(PoseJoint.LEFT_SHOULDER),
                low.abstained(side).lowConfidenceJoints,
            )
            assertEquals(
                setOf(ProjectedOffsetAbstentionReason.DEGENERATE_SHOULDER_AXIS),
                degenerate.abstained(side).reasons,
            )
        }
    }

    @Test
    fun wrongSourceNoLockAndMissingAllowedViewAbstainBeforeGeometry() {
        val fixture = fixture()
        val foreign = fixture(
            observationContract = fixture.source.contract,
            allowedViews = setOf(FRONT_VIEW),
        )
        val invalidGeometry = emptyMap<PoseJoint, PoseLandmark>()
        val wrongSource = fixture.feature.measure(
            foreign.observation(landmarks = invalidGeometry),
        )
        val noLock = fixture.feature.measure(
            fixture.observation(landmarks = invalidGeometry, hasPersonLock = false),
        )
        val noAllowedView = fixture.feature.measure(
            fixture.observation(landmarks = invalidGeometry, qualifiedViews = setOf(OTHER_VIEW)),
        )

        PoseSide.entries.forEach { side ->
            assertEquals(
                setOf(ProjectedOffsetAbstentionReason.OBSERVATION_SOURCE_MISMATCH),
                wrongSource.abstained(side).reasons,
            )
            assertEquals(
                setOf(ProjectedOffsetAbstentionReason.PRIMARY_PERSON_LOCK_MISSING),
                noLock.abstained(side).reasons,
            )
            assertEquals(
                setOf(ProjectedOffsetAbstentionReason.ALLOWED_VIEW_QUALIFICATION_MISSING),
                noAllowedView.abstained(side).reasons,
            )
        }
    }

    @Test
    fun unavailableImageGeometryAbstainsInsteadOfAssumingSquarePixels() {
        val fixture = fixture()
        val measurement = fixture.feature.measure(
            fixture.observation(landmarks = knownLandmarks(), imageWidth = 0, imageHeight = 0),
        )

        PoseSide.entries.forEach { side ->
            assertEquals(
                setOf(ProjectedOffsetAbstentionReason.IMAGE_DIMENSIONS_UNAVAILABLE),
                measurement.abstained(side).reasons,
            )
        }
    }

    @Test
    fun canonicalContractPinsInputsAndDetectsEveryConfigurableDrift() {
        val baseline = fixture()
        val reorderedViews = fixture(allowedViews = linkedSetOf(OBLIQUE_VIEW, FRONT_VIEW))
        val confidenceDrift = fixture(minimumRawConfidence = 0.81)
        val viewDrift = fixture(allowedViews = setOf(FRONT_VIEW))
        val observationDrift = fixture(observationContract = observationContract(modelSha = SHA_B))

        assertEquals(baseline.feature.contract.artifactSha256, reorderedViews.feature.contract.artifactSha256)
        assertEquals(EXPECTED_CONTRACT_SHA256, baseline.feature.contract.artifactSha256)
        assertNotEquals(baseline.feature.contract.artifactSha256, confidenceDrift.feature.contract.artifactSha256)
        assertNotEquals(baseline.feature.contract.artifactSha256, viewDrift.feature.contract.artifactSha256)
        assertNotEquals(baseline.feature.contract.artifactSha256, observationDrift.feature.contract.artifactSha256)
        assertEquals(
            baseline.source.contract.artifactSha256,
            baseline.feature.contract.observationContractSha256,
        )
    }

    @Test
    fun contractRejectsEmptyLateralOrArbitraryViewsOutsideTheCanonicalPolicy() {
        listOf(
            emptySet(),
            setOf(OTHER_VIEW),
            setOf(FRONT_VIEW, OTHER_VIEW),
            setOf("trex.view.unreviewed-camera-angle.v1"),
        ).forEach { disallowedViews ->
            assertThrows(IllegalArgumentException::class.java) {
                fixture(allowedViews = disallowedViews)
            }
        }

        fixture(allowedViews = setOf(FRONT_VIEW))
        fixture(allowedViews = setOf(OBLIQUE_VIEW))
        fixture(allowedViews = setOf(FRONT_VIEW, OBLIQUE_VIEW))
    }

    @Test
    fun contractAndMeasurementsSnapshotCollectionsAndExposeNoVerdictApis() {
        val mutableViews = linkedSetOf(FRONT_VIEW, OBLIQUE_VIEW)
        val fixture = fixture(allowedViews = mutableViews)
        mutableViews.clear()
        val measurement = fixture.feature.measure(
            fixture.observation(landmarks = knownLandmarks() - PoseJoint.LEFT_KNEE),
        )

        assertEquals(setOf(FRONT_VIEW, OBLIQUE_VIEW), fixture.feature.contract.allowedViewContractIds)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (fixture.feature.contract.allowedViewContractIds as MutableSet<String>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (measurement.sideSamples as MutableMap<PoseSide, ProjectedOffsetSideSample>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (measurement.abstained(PoseSide.LEFT).reasons as
                MutableSet<ProjectedOffsetAbstentionReason>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (measurement.abstained(PoseSide.LEFT).missingJoints as MutableSet<PoseJoint>).clear()
        }

        val forbiddenTokens = setOf("pass", "fail", "unknown", "verdict", "score", "cue", "evaluate")
        val apiTypes = listOf(
            BarbellSquatProjectedOffsetFeature::class.java,
            BarbellSquatProjectedOffsetContract::class.java,
            BarbellSquatProjectedOffsetMeasurement::class.java,
            ProjectedOffsetSideSample::class.java,
            ProjectedOffsetSideSample.Measured::class.java,
            ProjectedOffsetSideSample.Abstained::class.java,
        )
        val publicMethods = apiTypes.flatMap { type ->
            type.declaredMethods.filter { method -> Modifier.isPublic(method.modifiers) }
        }
        assertTrue(publicMethods.none { method ->
            forbiddenTokens.any { token -> token in method.name.lowercase() }
        })
        assertTrue(publicMethods.flatMap { method ->
            listOf(method.returnType) + method.parameterTypes
        }.none { type ->
            forbiddenTokens.any { token -> token in type.name.lowercase() }
        })
    }

    private fun BarbellSquatProjectedOffsetMeasurement.measured(
        side: PoseSide,
    ): ProjectedOffsetSideSample.Measured =
        sideSamples.getValue(side) as ProjectedOffsetSideSample.Measured

    private fun BarbellSquatProjectedOffsetMeasurement.abstained(
        side: PoseSide,
    ): ProjectedOffsetSideSample.Abstained =
        sideSamples.getValue(side) as ProjectedOffsetSideSample.Abstained

    private fun fixture(
        minimumRawConfidence: Double = 0.8,
        allowedViews: Set<String> = setOf(FRONT_VIEW, OBLIQUE_VIEW),
        observationContract: PoseObservationContract = observationContract(),
    ): Fixture {
        val source = PoseObservationSource(observationContract)
        return Fixture(
            source = source,
            feature = BarbellSquatProjectedOffsetFeature(
                expectedSource = source,
                allowedViewContractIds = allowedViews,
                minimumRawConfidence = minimumRawConfidence,
            ),
        )
    }

    private data class Fixture(
        val source: PoseObservationSource,
        val feature: BarbellSquatProjectedOffsetFeature,
    ) {
        fun observation(
            landmarks: Map<PoseJoint, PoseLandmark>,
            imageWidth: Int = 200,
            imageHeight: Int = 100,
            isMirrored: Boolean = false,
            hasPersonLock: Boolean = true,
            qualifiedViews: Set<String> = setOf(FRONT_VIEW),
        ): AttestedPoseObservation {
            val timestampMs = 42L
            val epoch = if (hasPersonLock) source.newPersonTrackEpoch() else null
            val qualifications = if (epoch == null) {
                emptyList()
            } else {
                qualifiedViews.map { viewId -> source.qualifyView(viewId, epoch, timestampMs) }
            }
            return source.attest(
                frame = PoseFrame(
                    timestampMs = timestampMs,
                    landmarks = landmarks,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    isMirrored = isMirrored,
                ),
                personTrackEpoch = epoch,
                viewQualifications = qualifications,
            )
        }
    }

    private companion object {
        const val FRONT_VIEW = "trex.view.front-full-body.v1"
        const val OBLIQUE_VIEW = "trex.view.front-oblique-full-body.v1"
        const val OTHER_VIEW = "trex.view.lateral-full-body.v1"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SHA_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val EXPECTED_CONTRACT_SHA256 =
            "b431e97d19b7bcf9784c90e388ee5391ed5b64b9bb797b7178870fd7a36d41ba"

        fun observationContract(modelSha: String = SHA_A): PoseObservationContract =
            PoseObservationContract(
                runtimeDomainId = "mediapipe-full.normalized-image.research.v1",
                modelArtifactId = "mediapipe.pose-landmarker.full.v1",
                modelArtifactSha256 = modelSha,
                inferenceOptionsContractId = "mediapipe.video-options.v1",
                inferenceOptionsArtifactSha256 = SHA_D,
                preprocessingContractId = "camerax.viewport-rotation-mirror-described.v1",
                preprocessingArtifactSha256 = SHA_B,
                landmarkSchemaId = "mediapipe.pose-33.v1",
                landmarkSchemaArtifactSha256 = SHA_C,
                supportedCoordinateSpaces = setOf(PoseCoordinateSpace.NORMALIZED_IMAGE),
                phaseViewContractId = FRONT_VIEW,
                allowedViewContractIds = setOf(FRONT_VIEW, OBLIQUE_VIEW, OTHER_VIEW),
                personLockArtifactId = "primary-person.temporal-lock.v1",
                personLockArtifactSha256 = SHA_B,
                viewQualifierArtifactId = "front-axis.qualifier.research.v1",
                viewQualifierArtifactSha256 = SHA_C,
            )

        fun knownLandmarks(): Map<PoseJoint, PoseLandmark> = linkedMapOf(
            PoseJoint.LEFT_SHOULDER to landmark(0.25, 0.2),
            PoseJoint.RIGHT_SHOULDER to landmark(0.75, 0.2),
            PoseJoint.LEFT_KNEE to landmark(0.4, 0.6),
            PoseJoint.LEFT_ANKLE to landmark(0.3, 0.8),
            PoseJoint.LEFT_FOOT_INDEX to landmark(0.2, 0.9),
            PoseJoint.RIGHT_KNEE to landmark(0.65, 0.6),
            PoseJoint.RIGHT_ANKLE to landmark(0.7, 0.8),
            PoseJoint.RIGHT_FOOT_INDEX to landmark(0.8, 0.9),
        )

        fun landmark(
            x: Double,
            y: Double,
            confidence: Double = 0.99,
        ): PoseLandmark = PoseLandmark(
            x = x,
            y = y,
            visibility = confidence,
            presence = confidence,
        )
    }
}
