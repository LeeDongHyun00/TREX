package com.example.trex_kotlin.pose.feature

import com.example.trex_kotlin.pose.PoseCoordinateSpace
import com.example.trex_kotlin.pose.PoseFrame
import com.example.trex_kotlin.pose.PoseJoint
import com.example.trex_kotlin.pose.PoseLandmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseFeatureEngineTest {
    private val engine = PoseFeatureEngine(minimumConfidence = 0.6)

    @Test
    fun featureAstAndRuntimePinPrimitiveMeasurementSemantics() {
        val spec = PoseScalarFeatureSpec.JointAngle(
            featureContractId = "test.left-knee-angle.world.v1",
            coordinateSpace = PoseCoordinateSpace.WORLD,
            first = PoseJoint.LEFT_HIP,
            vertex = PoseJoint.LEFT_KNEE,
            third = PoseJoint.LEFT_ANKLE,
        )

        assertNotEquals(
            spec.featureSpecSha256,
            canonicalFeatureSpecSha256(spec, primitiveContractSha256 = "f".repeat(64)),
        )
        assertNotEquals(
            engine.runtimeContractSha256,
            PoseFeatureEngine(minimumConfidence = 0.0).runtimeContractSha256,
        )
        assertEquals(
            PoseFeaturePrimitiveContract.degeneracyEpsilon,
            engine.degeneracyEpsilon,
            0.0,
        )
    }

    @Test
    fun normalizedImageAngleCorrectsAspectRatio() {
        val frame = frame(
            normalized = mapOf(
                PoseJoint.LEFT_HIP to point(0.30, 0.10),
                PoseJoint.LEFT_KNEE to point(0.10, 0.10),
                PoseJoint.LEFT_ANKLE to point(0.30, 0.50),
            ),
            width = 2_000,
            height = 1_000,
        )

        val result = engine.angle(
            frame,
            PoseJoint.LEFT_HIP,
            PoseJoint.LEFT_KNEE,
            PoseJoint.LEFT_ANKLE,
            PoseCoordinateSpace.NORMALIZED_IMAGE,
        )

        assertKnown(result, 45.0)
        assertEquals(FeatureUnit.DEGREES, result.unit)
        assertEquals(PoseCoordinateSpace.NORMALIZED_IMAGE, result.coordinateSpace)
    }

    @Test
    fun coordinateDomainIsExplicitAndNeverFallsBack() {
        val frame = frame(
            normalized = anglePoints(90.0),
            world = anglePoints(180.0),
        )

        val normalized = kneeAngle(frame, PoseCoordinateSpace.NORMALIZED_IMAGE)
        val world = kneeAngle(frame, PoseCoordinateSpace.WORLD)

        assertKnown(normalized, 90.0)
        assertKnown(world, 180.0)

        val normalizedOnly = frame(normalized = anglePoints(90.0))
        val missingWorld = kneeAngle(normalizedOnly, PoseCoordinateSpace.WORLD)
        assertUnknown(missingWorld, FeatureUnknownReason.MISSING_JOINT)
    }

    @Test
    fun angleAndBodyNormalizedDistanceAreTranslationAndScaleInvariant() {
        val original = mapOf(
            PoseJoint.LEFT_HIP to point(0.0, 1.0, 0.0),
            PoseJoint.LEFT_KNEE to point(0.0, 0.0, 0.0),
            PoseJoint.LEFT_ANKLE to point(1.0, 0.0, 0.0),
            PoseJoint.LEFT_SHOULDER to point(-1.0, 0.0, 0.0),
            PoseJoint.RIGHT_SHOULDER to point(1.0, 0.0, 0.0),
        )
        val transformed = original.mapValues { (_, landmark) ->
            point(
                x = landmark.x * 7.5 + 11.0,
                y = landmark.y * 7.5 - 4.0,
                z = landmark.z * 7.5 + 2.0,
            )
        }

        val firstFrame = frame(world = original)
        val secondFrame = frame(world = transformed)
        val firstAngle = kneeAngle(firstFrame, PoseCoordinateSpace.WORLD)
        val secondAngle = kneeAngle(secondFrame, PoseCoordinateSpace.WORLD)
        val firstDistance = engine.normalizedDistance(
            firstFrame,
            PoseJoint.LEFT_HIP,
            PoseJoint.LEFT_ANKLE,
            PoseJoint.LEFT_SHOULDER,
            PoseJoint.RIGHT_SHOULDER,
            PoseCoordinateSpace.WORLD,
        )
        val secondDistance = engine.normalizedDistance(
            secondFrame,
            PoseJoint.LEFT_HIP,
            PoseJoint.LEFT_ANKLE,
            PoseJoint.LEFT_SHOULDER,
            PoseJoint.RIGHT_SHOULDER,
            PoseCoordinateSpace.WORLD,
        )

        assertKnown(firstAngle, requireNotNull(secondAngle.value))
        assertKnown(firstDistance, requireNotNull(secondDistance.value))
    }

    @Test
    fun mirrorWithAnatomicalSideSwapPreservesAnglesAndFlipsSignedHorizontalAlignment() {
        val original = frame(
            world = mapOf(
                PoseJoint.LEFT_HIP to point(0.0, 2.0),
                PoseJoint.LEFT_KNEE to point(1.0, 1.0),
                PoseJoint.LEFT_ANKLE to point(0.0, 0.0),
                PoseJoint.LEFT_SHOULDER to point(-1.0, 2.0),
                PoseJoint.RIGHT_SHOULDER to point(1.0, 2.0),
            ),
        )
        val mirrored = frame(
            world = mapOf(
                PoseJoint.RIGHT_HIP to point(0.0, 2.0),
                PoseJoint.RIGHT_KNEE to point(-1.0, 1.0),
                PoseJoint.RIGHT_ANKLE to point(0.0, 0.0),
                PoseJoint.LEFT_SHOULDER to point(-1.0, 2.0),
                PoseJoint.RIGHT_SHOULDER to point(1.0, 2.0),
            ),
        )

        val originalAngle = engine.angle(
            original,
            PoseJoint.LEFT_HIP,
            PoseJoint.LEFT_KNEE,
            PoseJoint.LEFT_ANKLE,
            PoseCoordinateSpace.WORLD,
        )
        val mirroredAngle = engine.angle(
            mirrored,
            PoseJoint.RIGHT_HIP,
            PoseJoint.RIGHT_KNEE,
            PoseJoint.RIGHT_ANKLE,
            PoseCoordinateSpace.WORLD,
        )
        val originalAlignment = horizontalKneeAlignment(
            original,
            PoseJoint.LEFT_KNEE,
            PoseJoint.LEFT_ANKLE,
        )
        val mirroredAlignment = horizontalKneeAlignment(
            mirrored,
            PoseJoint.RIGHT_KNEE,
            PoseJoint.RIGHT_ANKLE,
        )

        assertKnown(originalAngle, requireNotNull(mirroredAngle.value))
        assertEquals(
            requireNotNull(originalAlignment.value),
            -requireNotNull(mirroredAlignment.value),
            1e-9,
        )
    }

    @Test
    fun atan2AngleIsStableAtCollinearBoundary() {
        val result = kneeAngle(
            frame(
                world = mapOf(
                    PoseJoint.LEFT_HIP to point(-1.0, 0.0),
                    PoseJoint.LEFT_KNEE to point(0.0, 0.0),
                    PoseJoint.LEFT_ANKLE to point(1.0, 0.0),
                ),
            ),
            PoseCoordinateSpace.WORLD,
        )

        assertKnown(result, 180.0)
    }

    @Test
    fun rawConfidenceIsTheMinimumUncalibratedLandmarkConfidence() {
        val result = kneeAngle(
            frame(
                world = mapOf(
                    PoseJoint.LEFT_HIP to point(1.0, 0.0, confidence = 0.91),
                    PoseJoint.LEFT_KNEE to point(0.0, 0.0, confidence = 0.72),
                    PoseJoint.LEFT_ANKLE to point(0.0, 1.0, confidence = 0.83),
                ),
            ),
            PoseCoordinateSpace.WORLD,
        )

        assertKnown(result, 90.0)
        assertEquals(0.72, result.rawConfidence, 0.0)
    }

    @Test
    fun missingLowConfidenceAndInvalidImageGeometryFailClosed() {
        val missing = kneeAngle(
            frame(world = mapOf(PoseJoint.LEFT_HIP to point(0.0, 1.0))),
            PoseCoordinateSpace.WORLD,
        )
        assertUnknown(missing, FeatureUnknownReason.MISSING_JOINT)

        val lowConfidencePoints = anglePoints(90.0).toMutableMap().apply {
            this[PoseJoint.LEFT_KNEE] = point(0.0, 0.0, confidence = 0.59)
        }
        val lowConfidence = kneeAngle(
            frame(world = lowConfidencePoints),
            PoseCoordinateSpace.WORLD,
        )
        assertUnknown(lowConfidence, FeatureUnknownReason.LOW_CONFIDENCE)
        assertEquals(0.59, lowConfidence.rawConfidence, 0.0)

        val invalidDimensions = kneeAngle(
            frame(normalized = anglePoints(90.0), width = 0, height = 0),
            PoseCoordinateSpace.NORMALIZED_IMAGE,
        )
        assertUnknown(invalidDimensions, FeatureUnknownReason.INVALID_IMAGE_DIMENSIONS)
    }

    @Test
    fun degenerateSegmentsAndReferencesFailClosed() {
        val zeroLimb = frame(
            world = mapOf(
                PoseJoint.LEFT_HIP to point(0.0, 0.0),
                PoseJoint.LEFT_KNEE to point(0.0, 0.0),
                PoseJoint.LEFT_ANKLE to point(1.0, 0.0),
            ),
        )
        assertUnknown(
            kneeAngle(zeroLimb, PoseCoordinateSpace.WORLD),
            FeatureUnknownReason.DEGENERATE_VECTOR,
        )

        val zeroScale = frame(
            world = mapOf(
                PoseJoint.LEFT_HIP to point(0.0, 1.0),
                PoseJoint.LEFT_ANKLE to point(0.0, 0.0),
                PoseJoint.LEFT_SHOULDER to point(1.0, 1.0),
                PoseJoint.RIGHT_SHOULDER to point(1.0, 1.0),
            ),
        )
        val distance = engine.normalizedDistance(
            zeroScale,
            PoseJoint.LEFT_HIP,
            PoseJoint.LEFT_ANKLE,
            PoseJoint.LEFT_SHOULDER,
            PoseJoint.RIGHT_SHOULDER,
            PoseCoordinateSpace.WORLD,
        )
        assertUnknown(distance, FeatureUnknownReason.DEGENERATE_SCALE)

        val orientation = engine.segmentOrientation(
            frame(world = anglePoints(90.0)),
            PoseJoint.LEFT_HIP,
            PoseJoint.LEFT_KNEE,
            OrientationReference.Gravity(Vector3(0.0, 0.0, 0.0)),
            PoseCoordinateSpace.WORLD,
        )
        assertUnknown(orientation, FeatureUnknownReason.DEGENERATE_REFERENCE)
    }

    @Test
    fun gravityAndBodyFrameOrientationAreExplicitAndDistinct() {
        val frame = frame(
            world = mapOf(
                PoseJoint.LEFT_HIP to point(0.0, 0.0),
                PoseJoint.LEFT_KNEE to point(0.0, 1.0),
                PoseJoint.LEFT_SHOULDER to point(0.0, 0.0),
                PoseJoint.RIGHT_SHOULDER to point(1.0, 0.0),
            ),
        )

        val gravity = engine.segmentOrientation(
            frame,
            PoseJoint.LEFT_HIP,
            PoseJoint.LEFT_KNEE,
            OrientationReference.Gravity(Vector3(0.0, 1.0, 0.0)),
            PoseCoordinateSpace.WORLD,
        )
        val body = engine.segmentOrientation(
            frame,
            PoseJoint.LEFT_HIP,
            PoseJoint.LEFT_KNEE,
            OrientationReference.BodyAxis(
                PoseJoint.LEFT_SHOULDER,
                PoseJoint.RIGHT_SHOULDER,
            ),
            PoseCoordinateSpace.WORLD,
        )

        assertKnown(gravity, 0.0)
        assertKnown(body, 90.0)
        assertTrue(PoseJoint.RIGHT_SHOULDER in body.requiredJoints)
    }

    @Test
    fun symmetryAndRomPreserveDomainQualityAndUnknowns() {
        val first = kneeAngle(frame(world = anglePoints(90.0)), PoseCoordinateSpace.WORLD)
        val second = kneeAngle(frame(world = anglePoints(120.0)), PoseCoordinateSpace.WORLD)
        val difference = engine.signedDifference(first, second)
        val symmetry = engine.absoluteDifference(first, second)
        val rom = engine.rangeOfMotion(listOf(first, second), PoseCoordinateSpace.WORLD)

        assertKnown(difference, -30.0)
        assertKnown(symmetry, 30.0)
        assertKnown(rom, 30.0)

        val unavailable = kneeAngle(frame(), PoseCoordinateSpace.WORLD)
        val incompleteRom = engine.rangeOfMotion(
            listOf(first, unavailable),
            PoseCoordinateSpace.WORLD,
        )
        assertUnknown(incompleteRom, FeatureUnknownReason.MISSING_JOINT)
    }

    @Test
    fun combinatorsRejectMixedCoordinateDomainsAndUnits() {
        val worldAngle = kneeAngle(frame(world = anglePoints(90.0)), PoseCoordinateSpace.WORLD)
        val imageAngle = kneeAngle(
            frame(normalized = anglePoints(90.0)),
            PoseCoordinateSpace.NORMALIZED_IMAGE,
        )
        val worldDistance = engine.normalizedDistance(
            frame(
                world = anglePoints(90.0) + mapOf(
                    PoseJoint.LEFT_SHOULDER to point(-1.0, 0.0),
                    PoseJoint.RIGHT_SHOULDER to point(1.0, 0.0),
                ),
            ),
            PoseJoint.LEFT_HIP,
            PoseJoint.LEFT_ANKLE,
            PoseJoint.LEFT_SHOULDER,
            PoseJoint.RIGHT_SHOULDER,
            PoseCoordinateSpace.WORLD,
        )

        assertUnknown(
            engine.absoluteDifference(worldAngle, imageAngle),
            FeatureUnknownReason.INCOMPATIBLE_MEASUREMENTS,
        )
        assertUnknown(
            engine.absoluteDifference(worldAngle, worldDistance),
            FeatureUnknownReason.INCOMPATIBLE_MEASUREMENTS,
        )
    }

    private fun kneeAngle(
        frame: PoseFrame,
        coordinateSpace: PoseCoordinateSpace,
    ): FeatureMeasurement = engine.angle(
        frame,
        PoseJoint.LEFT_HIP,
        PoseJoint.LEFT_KNEE,
        PoseJoint.LEFT_ANKLE,
        coordinateSpace,
    )

    private fun horizontalKneeAlignment(
        frame: PoseFrame,
        knee: PoseJoint,
        ankle: PoseJoint,
    ): FeatureMeasurement = engine.signedAlignment(
        frame = frame,
        point = knee,
        anchor = ankle,
        reference = OrientationReference.BodyAxis(
            PoseJoint.LEFT_SHOULDER,
            PoseJoint.RIGHT_SHOULDER,
        ),
        scaleStart = PoseJoint.LEFT_SHOULDER,
        scaleEnd = PoseJoint.RIGHT_SHOULDER,
        coordinateSpace = PoseCoordinateSpace.WORLD,
    )

    private fun anglePoints(angleDegrees: Double): Map<PoseJoint, PoseLandmark> {
        val radians = Math.toRadians(angleDegrees)
        return mapOf(
            PoseJoint.LEFT_HIP to point(1.0, 0.0),
            PoseJoint.LEFT_KNEE to point(0.0, 0.0),
            PoseJoint.LEFT_ANKLE to point(kotlin.math.cos(radians), kotlin.math.sin(radians)),
        )
    }

    private fun frame(
        normalized: Map<PoseJoint, PoseLandmark> = emptyMap(),
        world: Map<PoseJoint, PoseLandmark> = emptyMap(),
        width: Int = 1_000,
        height: Int = 1_000,
    ): PoseFrame = PoseFrame(
        timestampMs = 0L,
        landmarks = normalized,
        worldLandmarks = world,
        imageWidth = width,
        imageHeight = height,
    )

    private fun point(
        x: Double,
        y: Double,
        z: Double = 0.0,
        confidence: Double = 1.0,
    ): PoseLandmark = PoseLandmark(
        x = x,
        y = y,
        z = z,
        visibility = confidence,
        presence = confidence,
    )

    private fun assertKnown(result: FeatureMeasurement, expected: Double) {
        assertTrue(result.isKnown)
        assertNull(result.unknownReason)
        assertEquals(expected, requireNotNull(result.value), 1e-9)
    }

    private fun assertUnknown(
        result: FeatureMeasurement,
        reason: FeatureUnknownReason,
    ) {
        assertFalse(result.isKnown)
        assertNull(result.value)
        assertEquals(reason, result.unknownReason)
    }
}
