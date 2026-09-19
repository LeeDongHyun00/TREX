package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RecentRepViewTest {
    private fun yaw(degrees: Double): Map<String, Float> {
        val r = Math.toRadians(degrees)
        return mapOf(ViewEstimator.FEAT_COS to cos(r).toFloat(), ViewEstimator.FEAT_SIN to sin(r).toFloat())
    }
    private fun warmup(view: RecentRepView, degrees: Double = 0.0, startMs: Long = 0): ViewEstimator.ViewClass? {
        var result: ViewEstimator.ViewClass? = null
        repeat(8) { result = view.add(startMs + it * 100L, yaw(degrees)) }
        return result
    }

    @Test fun requiresEightRecentValidFramesThenReturnsCurrentClass() {
        val view = RecentRepView()
        repeat(7) { assertNull(view.add(it * 100L, yaw(0.0))) }
        assertEquals(ViewEstimator.ViewClass.C, view.add(700, yaw(0.0)))
        assertEquals(ViewEstimator.ViewClass.C, view.add(800, yaw(0.0)))
    }

    @Test fun turningNowCannotPassUsingTheOldFrontWindow() {
        val view = RecentRepView()
        assertEquals(ViewEstimator.ViewClass.C, warmup(view))
        assertNull(view.add(800, yaw(60.0)))
        repeat(7) { view.add(900 + it * 100L, yaw(60.0)) }
        assertEquals(ViewEstimator.ViewClass.SIDE_B, view.add(1600, yaw(60.0)))
    }

    @Test fun rearAndEitherSideArePreservedForTheProfileGate() {
        for ((degrees, expected) in listOf(180.0 to ViewEstimator.ViewClass.R,
            110.0 to ViewEstimator.ViewClass.A, -110.0 to ViewEstimator.ViewClass.E,
            60.0 to ViewEstimator.ViewClass.SIDE_B, -60.0 to ViewEstimator.ViewClass.SIDE_D,
            33.0 to ViewEstimator.ViewClass.B, -33.0 to ViewEstimator.ViewClass.D)) {
            assertEquals(expected, warmup(RecentRepView(), degrees))
        }
    }

    @Test fun missingOrNonFiniteCurrentFrameClearsEvenAFullWindow() {
        val invalid = listOf(emptyMap(), mapOf(ViewEstimator.FEAT_COS to 1f),
            mapOf(ViewEstimator.FEAT_COS to Float.NaN, ViewEstimator.FEAT_SIN to 0f),
            mapOf(ViewEstimator.FEAT_COS to 1f, ViewEstimator.FEAT_SIN to Float.POSITIVE_INFINITY))
        for (frame in invalid) {
            val view = RecentRepView()
            assertEquals(ViewEstimator.ViewClass.C, warmup(view))
            assertNull(view.add(800, frame))
            repeat(7) { assertNull(view.add(900 + it * 100L, yaw(0.0))) }
            assertEquals(ViewEstimator.ViewClass.C, view.add(1600, yaw(0.0)))
        }
    }

    @Test fun anUnknownCurrentVectorDoesNotBorrowThePriorDirection() {
        val view = RecentRepView()
        warmup(view)
        assertEquals(ViewEstimator.ViewClass.UNKNOWN,
            view.add(800, mapOf(ViewEstimator.FEAT_COS to 0f, ViewEstimator.FEAT_SIN to 0f)))
        assertNull(view.add(900, yaw(0.0)))
    }

    @Test fun opposedSamplesCannotBecomeAConfidentDirection() {
        val view = RecentRepView()
        repeat(8) { assertNull(view.add(it * 100L, yaw(if (it % 2 == 0) 0.0 else 180.0))) }
    }

    @Test fun gapStartsAFreshWindowInsteadOfBridgingThePreviousOne() {
        val view = RecentRepView()
        warmup(view)
        assertNull(view.add(2300, yaw(0.0)))
        repeat(6) { assertNull(view.add(2400 + it * 100L, yaw(0.0))) }
        assertEquals(ViewEstimator.ViewClass.C, view.add(3000, yaw(0.0)))
    }

    @Test fun reversalAndDuplicateTimestampDiscardTheCurrentSampleAndWindow() {
        for (invalidTime in listOf(600L, 700L, -1L)) {
            val view = RecentRepView()
            warmup(view)
            assertNull(view.add(invalidTime, yaw(0.0)))
            repeat(7) { assertNull(view.add(800 + it * 100L, yaw(0.0))) }
            assertEquals(ViewEstimator.ViewClass.C, view.add(1500, yaw(0.0)))
        }
    }

    @Test fun eightSamplesSpreadOverMoreThanThreeSecondsDoNotWarmUp() {
        val view = RecentRepView()
        repeat(20) { assertNull(view.add(it * 500L, yaw(0.0))) }
        assertNull(view.add(9600, yaw(0.0)))
        assertEquals(ViewEstimator.ViewClass.C, view.add(9700, yaw(0.0)))
    }

    @Test fun aQueueOfOldFramesCannotRewindAndBuildANewDirectionWindow() {
        val view = RecentRepView()
        assertEquals(ViewEstimator.ViewClass.C, warmup(view, startMs = 1000))
        repeat(12) { assertNull(view.add(it * 100L, yaw(60.0))) }
        repeat(7) { assertNull(view.add(1800 + it * 100L, yaw(0.0))) }
        assertEquals(ViewEstimator.ViewClass.C, view.add(2500, yaw(0.0)))
    }

    @Test fun explicitResetDropsPreparationAndSetDirection() {
        val view = RecentRepView()
        warmup(view)
        view.reset()
        assertNull(view.add(800, yaw(0.0)))
    }
}
