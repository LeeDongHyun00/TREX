package com.example.trex_kotlin.pose.research

import com.example.trex_kotlin.catalog.AiHubExercise
import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import com.example.trex_kotlin.pose.PoseSide
import com.example.trex_kotlin.pose.feature.FeatureUnknownReason
import com.example.trex_kotlin.pose.feature.PoseFeatureEngine
import com.example.trex_kotlin.pose.feature.PoseFeaturePrimitiveContract
import com.example.trex_kotlin.pose.feature.PoseScalarFeatureSpec
import com.example.trex_kotlin.pose.feature.measure
import com.example.trex_kotlin.pose.policy.AiHubCriterionPolicyCatalog
import com.example.trex_kotlin.pose.policy.AiHubCriterionSidePolicyKind
import com.example.trex_kotlin.pose.runtime.AttestedPoseObservation
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryContext
import com.example.trex_kotlin.pose.runtime.PoseCameraGeometryEpoch
import com.example.trex_kotlin.pose.runtime.PoseObservationContract
import com.example.trex_kotlin.pose.runtime.PoseObservationSource
import com.example.trex_kotlin.pose.runtime.PosePersonTrackEpoch
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoodMorningKneeFlexionResearchDiagnosticTest {
    @Test
    fun exactGoodMorningTupleProducesOnlyBilateralFrameLocalUnknownDiagnostic() {
        val fixture = Fixture()
        val window = fixture.window()
        val diagnostic = GoodMorningKneeFlexionResearchDiagnostic(fixture.source, 0.6)

        val output = diagnostic.accept(window.second, window.receipt)

        val binding = requireNotNull(
            AiHubCriterionPolicyCatalog.binding(
                AiHubExercise.GOOD_MORNING,
                GoodMorningKneeFlexionResearchDiagnostic.SOURCE_CONDITION_ID,
            ),
        )
        assertEquals(GoodMorningKneeFlexionResearchDiagnostic.BINDING_ID, binding.bindingId)
        assertEquals(
            GoodMorningKneeFlexionResearchDiagnostic.BINDING_POLICY_SHA256,
            binding.bindingPolicySha256,
        )
        val interpretation = requireNotNull(binding.interpretation)
        assertEquals(
            GoodMorningKneeFlexionResearchDiagnostic.MEASUREMENT_CONSTRUCT_ID,
            interpretation.measurementConstructId,
        )
        assertEquals(
            listOf(GoodMorningKneeFlexionResearchDiagnostic.PHASE_ROLE_ID),
            interpretation.phaseApplicability.phaseRoleIds,
        )
        assertEquals(
            AiHubCriterionSidePolicyKind.BILATERAL_INDEPENDENT,
            interpretation.sidePolicy.kind,
        )
        assertEquals(
            listOf(PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID),
            interpretation.viewApplicability.viewContractIds,
        )
        assertEquals(GoodMorningKneeFlexionResearchState.UNKNOWN, output.state)
        assertEquals(PoseCoordinateSpace.WORLD, output.coordinateSpace)
        assertEquals(PoseFeaturePrimitiveContract.sha256, output.featurePrimitiveContractSha256)
        assertEquals(
            GoodMorningKneeFlexionResearchDiagnostic.FLEXION_FORMULA_ID,
            output.flexionFormulaId,
        )
        assertEquals(
            GoodMorningKneeFlexionResearchDiagnostic.RESEARCH_USE_ID,
            output.researchUseId,
        )
        assertEquals(1_040L, output.observationTimestampMs)
        assertEquals(window.receipt.receiptSha256, output.capabilityReceiptSha256)
        assertTrue(output.diagnosticContractSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(output.diagnosticProvenanceSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(
            output.diagnosticProvenanceSha256,
            diagnostic.accept(window.second, window.receipt).diagnosticProvenanceSha256,
        )
        val changedContractOutput = GoodMorningKneeFlexionResearchDiagnostic(
            fixture.source,
            0.7,
        ).accept(window.second, window.receipt)
        assertNotEquals(
            output.diagnosticContractSha256,
            changedContractOutput.diagnosticContractSha256,
        )
        assertNotEquals(
            output.diagnosticProvenanceSha256,
            changedContractOutput.diagnosticProvenanceSha256,
        )
        assertEquals(0, output.authority.totalAuthority)
        assertEquals(0, output.authority.scoreAuthority)
        assertEquals(0, output.authority.cueAuthority)
        assertEquals(ALWAYS_BLOCKERS, output.blockers)
        assertEquals(120.0, output.sideDiagnostics.getValue(PoseSide.LEFT)
            .includedAngleDegrees!!, 1e-6)
        assertEquals(60.0, output.sideDiagnostics.getValue(PoseSide.LEFT)
            .flexionDegrees!!, 1e-6)
        assertEquals(120.0, output.sideDiagnostics.getValue(PoseSide.RIGHT)
            .includedAngleDegrees!!, 1e-6)
        assertEquals(60.0, output.sideDiagnostics.getValue(PoseSide.RIGHT)
            .flexionDegrees!!, 1e-6)
        val normalizedSpec = PoseScalarFeatureSpec.JointAngle(
            featureContractId = "trex.test.good-morning.left-knee.normalized.v1",
            coordinateSpace = PoseCoordinateSpace.NORMALIZED_IMAGE,
            first = PoseJoint.LEFT_HIP,
            vertex = PoseJoint.LEFT_KNEE,
            third = PoseJoint.LEFT_ANKLE,
        )
        assertEquals(
            90.0,
            PoseFeatureEngine(0.6).measure(window.second.frame, normalizedSpec).value!!,
            1e-6,
        )
        val xyOnlyWorldFrame = window.second.frame.copy(
            worldLandmarks = window.second.frame.worldLandmarks.mapValues { (_, point) ->
                point.copy(z = 0.0)
            },
        )
        val worldSpec = PoseScalarFeatureSpec.JointAngle(
            featureContractId = "trex.test.good-morning.left-knee.world.v1",
            coordinateSpace = PoseCoordinateSpace.WORLD,
            first = PoseJoint.LEFT_HIP,
            vertex = PoseJoint.LEFT_KNEE,
            third = PoseJoint.LEFT_ANKLE,
        )
        assertEquals(
            180.0,
            PoseFeatureEngine(0.6).measure(xyOnlyWorldFrame, worldSpec).value!!,
            1e-6,
        )
        listOf(PoseJoint.LEFT_HIP, PoseJoint.LEFT_KNEE, PoseJoint.LEFT_ANKLE).forEach { joint ->
            assertNotEquals(0.0, window.second.frame.worldLandmarks.getValue(joint).z, 0.0)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (output.sideDiagnostics as MutableMap).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (output.blockers as MutableSet).clear()
        }
    }

    @Test
    fun completeReceiptKeepsTheOtherSideWhenOneSideIsLocallyUnavailable() {
        val lowConfidenceFixture = Fixture(leftConfidence = 0.1)
        val lowConfidenceWindow = lowConfidenceFixture.window()
        val lowConfidenceOutput = GoodMorningKneeFlexionResearchDiagnostic(
            lowConfidenceFixture.source,
            0.6,
        ).accept(lowConfidenceWindow.second, lowConfidenceWindow.receipt)

        val left = lowConfidenceOutput.sideDiagnostics.getValue(PoseSide.LEFT)
        val right = lowConfidenceOutput.sideDiagnostics.getValue(PoseSide.RIGHT)
        assertNull(left.includedAngleDegrees)
        assertNull(left.flexionDegrees)
        assertEquals(FeatureUnknownReason.LOW_CONFIDENCE, left.featureUnknownReason)
        assertEquals(120.0, right.includedAngleDegrees!!, 1e-6)
        assertEquals(60.0, right.flexionDegrees!!, 1e-6)
        assertNull(right.featureUnknownReason)

        val degenerateFixture = Fixture(leftDegenerate = true)
        val degenerateWindow = degenerateFixture.window()
        val degenerateOutput = GoodMorningKneeFlexionResearchDiagnostic(
            degenerateFixture.source,
            0.6,
        ).accept(degenerateWindow.second, degenerateWindow.receipt)
        assertEquals(
            FeatureUnknownReason.DEGENERATE_VECTOR,
            degenerateOutput.sideDiagnostics.getValue(PoseSide.LEFT).featureUnknownReason,
        )
        assertEquals(
            120.0,
            degenerateOutput.sideDiagnostics.getValue(PoseSide.RIGHT)
                .includedAngleDegrees!!,
            1e-6,
        )
        assertNotEquals(
            lowConfidenceOutput.diagnosticProvenanceSha256,
            degenerateOutput.diagnosticProvenanceSha256,
        )
    }

    @Test
    fun receiptMustMatchTheFullCurrentObservationTuple() {
        val fixture = Fixture()
        val window = fixture.window()
        val diagnostic = GoodMorningKneeFlexionResearchDiagnostic(fixture.source)

        assertCapabilityBlocked(diagnostic.accept(window.second, null))
        assertCapabilityBlocked(diagnostic.accept(window.first, window.receipt))
        assertCapabilityBlocked(
            diagnostic.accept(
                fixture.observation(1_040L, modifiedNose = true),
                window.receipt,
            ),
        )

        val foreign = Fixture().window().second
        assertCapabilityBlocked(diagnostic.accept(foreign, window.receipt))

        val differentPerson = fixture.source.newPersonTrackEpoch()
        assertCapabilityBlocked(
            diagnostic.accept(
                fixture.observation(1_040L, personEpoch = differentPerson),
                window.receipt,
            ),
        )
        val differentGeometry = fixture.source.newCameraGeometryEpoch(fixture.context)
        assertCapabilityBlocked(
            diagnostic.accept(
                fixture.observation(1_040L, geometryEpoch = differentGeometry),
                window.receipt,
            ),
        )

        assertEquals(
            window.receipt.receiptSha256,
            diagnostic.accept(window.second, window.receipt).capabilityReceiptSha256,
        )
        fixture.source.close()
        assertCapabilityBlocked(diagnostic.accept(window.second, window.receipt))
    }

    @Test
    fun surfaceHasNoDecisionOrProductCallerAndDoesNotReuseSquatMachinery() {
        val surfaces = listOf(
            GoodMorningKneeFlexionResearchDiagnostic::class.java,
            GoodMorningKneeFlexionResearchOutput::class.java,
            GoodMorningKneeFlexionSideDiagnostic::class.java,
        )
        val forbiddenTokens = setOf("pass", "fail", "cue", "score", "session", "evaluate")
        surfaces.flatMap { type ->
            type.declaredMethods.map { it.name } + type.declaredFields.map { it.name }
        }.forEach { name ->
            val tokens = name
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .split(Regex("[^A-Za-z0-9]+"))
                .filter(String::isNotEmpty)
                .map(String::lowercase)
            assertTrue("Forbidden research surface: $name", forbiddenTokens.none(tokens::contains))
        }

        val retainedTypes = GoodMorningKneeFlexionResearchOutput::class.java.declaredFields
            .map { it.type }
            .toSet()
        assertTrue(
            retainedTypes.intersect(
                setOf(
                    PoseFrame::class.java,
                    PoseLandmark::class.java,
                    AttestedPoseObservation::class.java,
                    PoseObservationSource::class.java,
                    PosePersonTrackEpoch::class.java,
                    PoseCameraGeometryEpoch::class.java,
                ),
            ).isEmpty(),
        )

        assertTrue(
            GoodMorningKneeFlexionResearchAuthority::class.java.declaredConstructors
                .all { Modifier.isPrivate(it.modifiers) },
        )
        val authorityConstructor =
            GoodMorningKneeFlexionResearchAuthority::class.java.declaredConstructors
                .find { it.parameterCount == 6 }
        checkNotNull(authorityConstructor).isAccessible = true
        assertThrows(InvocationTargetException::class.java) {
            authorityConstructor.newInstance(1, 0, 0, 0, 0, 0)
        }

        val sourceRoot = listOf(File("src/main/java"), File("app/src/main/java"))
            .find(File::isDirectory)
        if (sourceRoot != null) {
            val callers = sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.name == "GoodMorningKneeFlexionResearchDiagnostic.kt" }
                .filter { it.readText().contains("GoodMorningKneeFlexionResearchDiagnostic") }
                .toList()
            assertEquals(
                listOf(
                    "com/example/trex_kotlin/pose/research/" +
                        "GoodMorningKneeStabilityResearchTrace.kt",
                ),
                callers.map { caller ->
                    caller.relativeTo(sourceRoot).path.replace('\\', '/')
                },
            )

            val implementation = sourceRoot.resolve(
                "com/example/trex_kotlin/pose/research/" +
                    "GoodMorningKneeFlexionResearchDiagnostic.kt",
            ).readText()
            assertFalse(implementation.contains("BarbellSquat"))
            assertFalse(implementation.contains("median", ignoreCase = true))
            assertFalse(implementation.contains(".first("))
            assertFalse(implementation.contains(".single("))
        }
    }

    private fun assertCapabilityBlocked(output: GoodMorningKneeFlexionResearchOutput) {
        assertEquals(GoodMorningKneeFlexionResearchState.UNKNOWN, output.state)
        assertNull(output.observationTimestampMs)
        assertNull(output.capabilityReceiptSha256)
        assertTrue(
            GoodMorningKneeFlexionResearchBlocker.CAPABILITY_RECEIPT_UNAVAILABLE in
                output.blockers,
        )
        assertTrue(output.sideDiagnostics.values.all { !it.isFrameLocalValueAvailable })
        assertEquals(0, output.authority.totalAuthority)
    }

    private class Fixture(
        private val leftConfidence: Double = 0.99,
        private val leftDegenerate: Boolean = false,
    ) {
        val source = PoseObservationSource(observationContract())
        val context = geometryContext()
        private val geometry = source.newCameraGeometryEpoch(context)
        private val person = source.newPersonTrackEpoch()

        fun window(): EvidenceWindow {
            val first = observation(1_000L)
            val second = observation(1_040L)
            val evidence = PoseObservationResearchCapabilityEvidence(source, 100L)
            evidence.accept(first)
            return EvidenceWindow(
                first = first,
                second = second,
                receipt = requireNotNull(evidence.accept(second).receipt),
            )
        }

        fun observation(
            timestampMs: Long,
            personEpoch: PosePersonTrackEpoch = person,
            geometryEpoch: PoseCameraGeometryEpoch = geometry,
            modifiedNose: Boolean = false,
        ): AttestedPoseObservation {
            val normalized = completeNormalizedLandmarks(leftConfidence).toMutableMap()
            if (modifiedNose) {
                normalized[PoseJoint.NOSE] = normalized.getValue(PoseJoint.NOSE).copy(x = 0.99)
            }
            val frame = PoseFrame(
                timestampMs = timestampMs,
                landmarks = normalized,
                worldLandmarks = completeWorldLandmarks(leftConfidence, leftDegenerate),
                imageWidth = 1_000,
                imageHeight = 1_000,
            )
            return source.attest(
                frame = frame,
                personTrackEpoch = personEpoch,
                viewQualifications = listOf(
                    source.qualifyView(
                        PoseObservationResearchCapabilities.LATERAL_VIEW_CONTRACT_ID,
                        personEpoch,
                        timestampMs,
                    ),
                ),
                cameraGeometryEpoch = geometryEpoch,
            )
        }
    }

    private class EvidenceWindow(
        val first: AttestedPoseObservation,
        val second: AttestedPoseObservation,
        val receipt: PoseObservationResearchCapabilityReceipt,
    )

    private companion object {
        val ALWAYS_BLOCKERS = setOf(
            GoodMorningKneeFlexionResearchBlocker.PHASE_SCOPE_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.CALIBRATION_ARTIFACT_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.REFERENCE_EVIDENCE_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.TRUSTED_EVIDENCE_INTAKE_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.SHADOW_AUTHORIZATION_UNAVAILABLE,
            GoodMorningKneeFlexionResearchBlocker.RELEASE_AUTHORIZATION_UNAVAILABLE,
        )
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SHA_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"

        fun observationContract() = PoseObservationContract(
            runtimeDomainId = "mediapipe-full.good-morning-research.v1",
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

        fun completeNormalizedLandmarks(
            leftConfidence: Double,
        ): Map<PoseJoint, PoseLandmark> {
            val landmarks = baseLandmarks()
            landmarks[PoseJoint.LEFT_HIP] = landmark(0.2, 0.2, z = 0.1)
            landmarks[PoseJoint.LEFT_KNEE] = landmark(0.2, 0.5, leftConfidence, z = 0.2)
            landmarks[PoseJoint.LEFT_ANKLE] = landmark(0.5, 0.5, z = 0.3)
            landmarks[PoseJoint.RIGHT_HIP] = landmark(0.8, 0.2, z = 0.1)
            landmarks[PoseJoint.RIGHT_KNEE] = landmark(0.8, 0.5, z = 0.2)
            landmarks[PoseJoint.RIGHT_ANKLE] = landmark(0.5, 0.5, z = 0.3)
            return landmarks
        }

        fun completeWorldLandmarks(
            leftConfidence: Double,
            leftDegenerate: Boolean,
        ): Map<PoseJoint, PoseLandmark> {
            val landmarks = baseLandmarks()
            landmarks[PoseJoint.LEFT_HIP] = if (leftDegenerate) {
                landmark(0.2, 0.5, z = 0.3)
            } else {
                landmark(0.2, 0.2, z = 0.3)
            }
            landmarks[PoseJoint.LEFT_KNEE] = landmark(0.2, 0.5, leftConfidence, z = 0.3)
            landmarks[PoseJoint.LEFT_ANKLE] = landmark(0.2, 0.65, z = 0.5598076211)
            landmarks[PoseJoint.RIGHT_HIP] = landmark(0.8, 0.2, z = 0.3)
            landmarks[PoseJoint.RIGHT_KNEE] = landmark(0.8, 0.5, z = 0.3)
            landmarks[PoseJoint.RIGHT_ANKLE] = landmark(0.8, 0.65, z = 0.5598076211)
            return landmarks
        }

        fun baseLandmarks(): MutableMap<PoseJoint, PoseLandmark> =
            PoseJoint.entries.associateWith { joint ->
                PoseLandmark(
                    x = 0.01 + joint.mediaPipeIndex * 0.001,
                    y = 0.01 + joint.mediaPipeIndex * 0.001,
                    z = 0.01 + joint.mediaPipeIndex * 0.001,
                )
            }.toMutableMap()

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
