package com.example.trex_kotlin.pose.phase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PosePhaseEngineTest {
    private val ready = PosePhaseStateId("ready")
    private val descending = PosePhaseStateId("descending")
    private val bottom = PosePhaseStateId("bottom")
    private val ascending = PosePhaseStateId("ascending")

    @Test
    fun validatedGraphCollectionsCannotBeMutated() {
        val states = mutableListOf(
            PosePhaseState(ready, PosePhaseEnterPredicate(PhaseScalarInterval(160.0, 180.0))),
            PosePhaseState(
                descending,
                PosePhaseEnterPredicate(PhaseScalarInterval(100.0, 150.0)),
            ),
        )
        val transitions = mutableListOf(PosePhaseTransition(ready, descending))
        val graph = OrderedPosePhaseGraph(states, ready, transitions)
        states.clear()
        transitions.clear()

        assertEquals(setOf(ready, descending), graph.states.keys)
        assertEquals(1, graph.transitions.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (graph.states as MutableMap<PosePhaseStateId, PosePhaseState>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (graph.transitions as MutableList<PosePhaseTransition>).clear()
        }
    }

    @Test
    fun completeOrderedCyclePublishesRetrospectivePhaseWindows() {
        val engine = engine()
        val updates = feed(engine, completeCycle())

        assertEquals(ready, updates.last().activeStateId)
        assertEquals(1, updates.last().completedCycleCount)

        val events = updates.flatMap(PosePhaseUpdate::events)
        val starts = events.filterIsInstance<PosePhaseWindowStarted>()
        val ended = events.filterIsInstance<PosePhaseWindowEnded>()
        val completed = events.filterIsInstance<PosePhaseCycleCompleted>().single()

        assertEquals(
            listOf(ready, descending, bottom, ascending, ready),
            starts.map(PosePhaseWindowStarted::stateId),
        )
        assertEquals(listOf(0L, 200L, 400L, 600L, 800L), starts.map { it.startTimestampMs })
        assertEquals(
            listOf(ready, descending, bottom, ascending),
            ended.map { it.window.stateId },
        )
        assertEquals(listOf(200L, 400L, 600L, 800L), ended.map { it.window.endTimestampMs })
        assertEquals(0L, completed.cycleStartTimestampMs)
        assertEquals(800L, completed.cycleEndTimestampMs)
        assertEquals(900L, completed.confirmedAtTimestampMs)
    }

    @Test
    fun stretchingWallClockTimePreservesTheSameLegalPhaseOrder() {
        val normalEvents = feed(engine(), completeCycle())
            .flatMap(PosePhaseUpdate::events)
            .filterIsInstance<PosePhaseWindowStarted>()
            .map(PosePhaseWindowStarted::stateId)
        val stretchedEvents = feed(
            engine(),
            completeCycle().map { it.copy(timestampMs = it.timestampMs * 3L) },
        ).flatMap(PosePhaseUpdate::events)
            .filterIsInstance<PosePhaseWindowStarted>()
            .map(PosePhaseWindowStarted::stateId)

        assertEquals(normalEvents, stretchedEvents)
        assertEquals(listOf(ready, descending, bottom, ascending, ready), stretchedEvents)
    }

    @Test
    fun shortDropoutHoldsPhaseButDoesNotCountAsDwell() {
        val engine = engine(graceMs = 250L)
        feed(
            engine,
            listOf(
                observation(0L, 175.0),
                observation(100L, 175.0),
                observation(200L, 150.0),
                observation(300L, 145.0),
            ),
        )
        assertEquals(descending, engine.activeStateId)

        val dropout = engine.accept(
            PosePhaseObservation(350L, scalar = null, qualitySignal = 0.0),
        )
        assertEquals(descending, dropout.activeStateId)
        assertTrue(dropout.isHoldingUnusableObservation)

        // The missing 100 ms cannot satisfy bottom's dwell. A fresh pair of usable samples does.
        val firstBottom = engine.accept(observation(450L, 105.0))
        assertEquals(descending, firstBottom.activeStateId)
        assertFalse(firstBottom.isHoldingUnusableObservation)
        val confirmedBottom = engine.accept(observation(550L, 104.0))
        assertEquals(bottom, confirmedBottom.activeStateId)
    }

    @Test
    fun prolongedLowQualityResetsAndClosesAtTheLastUsableEvidence() {
        val engine = engine(graceMs = 250L)
        feed(
            engine,
            listOf(
                observation(0L, 175.0),
                observation(100L, 175.0),
                observation(200L, 150.0),
                observation(300L, 145.0),
            ),
        )

        engine.accept(PosePhaseObservation(400L, scalar = 140.0, qualitySignal = 0.1))
        val reset = engine.accept(
            PosePhaseObservation(700L, scalar = 140.0, qualitySignal = 0.1),
        )

        assertNull(reset.activeStateId)
        assertEquals(0, reset.completedCycleCount)
        val ended = reset.events.filterIsInstance<PosePhaseWindowEnded>().single()
        assertEquals(descending, ended.window.stateId)
        assertEquals(300L, ended.window.endTimestampMs)
        assertEquals(
            PosePhaseResetReason.UNUSABLE_OBSERVATION_TIMEOUT,
            reset.events.filterIsInstance<PosePhaseTrackingReset>().single().reason,
        )
    }

    @Test
    fun sparseUnusableCallbackSpendsGraceFromTheLastUsableBoundary() {
        val engine = engine(graceMs = 250L, maxGapMs = 1_000L)
        feed(
            engine,
            listOf(
                observation(0L, 175.0),
                observation(100L, 175.0),
                observation(200L, 150.0),
                observation(300L, 145.0),
            ),
        )
        assertEquals(descending, engine.activeStateId)

        // The callback arrives late, but loss began at the 300 ms observation boundary. It must
        // not receive a new 250 ms grace period starting at 700 ms.
        val reset = engine.accept(
            PosePhaseObservation(700L, scalar = 100.0, qualitySignal = 0.1),
        )
        assertNull(reset.activeStateId)
        assertEquals(
            PosePhaseResetReason.UNUSABLE_OBSERVATION_TIMEOUT,
            reset.events.filterIsInstance<PosePhaseTrackingReset>().single().reason,
        )
        assertEquals(
            300L,
            reset.events.filterIsInstance<PosePhaseWindowEnded>().single().window.endTimestampMs,
        )

        // Recovery at a bottom-like scalar cannot transition from the stale descending state.
        val recovery = feed(
            engine,
            listOf(observation(750L, 100.0), observation(850L, 100.0)),
        )
        assertNull(recovery.last().activeStateId)
        assertEquals(0, recovery.last().completedCycleCount)
    }

    @Test
    fun phaseDurationTimeoutClosesWindowAndClearsTracking() {
        val engine = engine(maximumPhaseDurationMs = 250L)
        engine.accept(observation(0L, 175.0))
        engine.accept(observation(100L, 175.0))

        val timedOut = engine.accept(observation(300L, 175.0))

        assertNull(timedOut.activeStateId)
        assertEquals(
            PosePhaseResetReason.PHASE_DURATION_TIMEOUT,
            timedOut.events.filterIsInstance<PosePhaseTrackingReset>().single().reason,
        )
    }

    @Test
    fun hysteresisAndDwellPreventJitterFromCreatingMultipleTransitions() {
        val engine = engine(directionTolerance = 3.0)
        val updates = feed(
            engine,
            listOf(
                observation(0L, 175.0),
                observation(100L, 175.0),
                observation(200L, 155.0),
                observation(250L, 157.0), // Small reversal: retained by direction hysteresis.
                observation(300L, 154.0),
                observation(350L, 151.0),
                observation(400L, 149.0),
            ),
        )

        val starts = updates.flatMap(PosePhaseUpdate::events)
            .filterIsInstance<PosePhaseWindowStarted>()
        assertEquals(listOf(ready, descending), starts.map(PosePhaseWindowStarted::stateId))
        assertEquals(descending, updates.last().activeStateId)
        assertEquals(0, updates.last().completedCycleCount)
    }

    @Test
    fun startingMidRepetitionCannotSkipToBottomOrCompleteARep() {
        val engine = engine()
        val updates = feed(
            engine,
            listOf(
                observation(0L, 100.0),
                observation(100L, 102.0),
                observation(200L, 130.0),
                observation(300L, 145.0),
                observation(400L, 170.0),
                observation(500L, 172.0),
            ),
        )

        assertEquals(ready, updates.last().activeStateId)
        assertEquals(0, updates.last().completedCycleCount)
        assertEquals(
            listOf(ready),
            updates.flatMap(PosePhaseUpdate::events)
                .filterIsInstance<PosePhaseWindowStarted>()
                .map(PosePhaseWindowStarted::stateId),
        )
    }

    @Test
    fun activeReadyPhaseRejectsADirectJumpToBottom() {
        val engine = engine()
        val updates = feed(
            engine,
            listOf(
                observation(0L, 175.0),
                observation(100L, 175.0),
                observation(200L, 100.0),
                observation(300L, 102.0),
            ),
        )

        assertEquals(ready, updates.last().activeStateId)
        assertEquals(0, updates.last().completedCycleCount)
        assertTrue(
            updates.flatMap(PosePhaseUpdate::events)
                .filterIsInstance<PosePhaseWindowStarted>()
                .none { it.stateId == bottom },
        )
    }

    @Test
    fun duplicateTimestampContributesNoTransitionEvidence() {
        val engine = engine()
        feed(
            engine,
            listOf(observation(0L, 175.0), observation(100L, 175.0)),
        )

        engine.accept(observation(200L, 150.0))
        val duplicate = engine.accept(observation(200L, 140.0))
        val onlyFiftyMilliseconds = engine.accept(observation(250L, 145.0))
        val enoughEvidence = engine.accept(observation(300L, 140.0))

        assertTrue(duplicate.events.isEmpty())
        assertEquals(ready, onlyFiftyMilliseconds.activeStateId)
        assertEquals(descending, enoughEvidence.activeStateId)
    }

    @Test
    fun maximumInputGapResetsBeforeTheNewObservationIsConsidered() {
        val engine = engine(maxGapMs = 300L)
        feed(engine, listOf(observation(0L, 175.0), observation(100L, 175.0)))

        val result = engine.accept(observation(500L, 150.0))

        assertNull(result.activeStateId)
        assertEquals(
            PosePhaseResetReason.MAXIMUM_OBSERVATION_GAP,
            result.events.filterIsInstance<PosePhaseTrackingReset>().single().reason,
        )
        assertEquals(100L, result.events.filterIsInstance<PosePhaseWindowEnded>().single()
            .window.endTimestampMs)
    }

    @Test
    fun resetDiscardsZeroDurationWindowInsteadOfPublishingIt() {
        val graph = OrderedPosePhaseGraph(
            states = listOf(
                PosePhaseState(
                    ready,
                    PosePhaseEnterPredicate(
                        enterInterval = PhaseScalarInterval(0.0, 1.0),
                        minimumDwellMs = 0L,
                    ),
                ),
            ),
            initialStateId = ready,
            transitions = emptyList(),
        )
        val engine = PosePhaseEngine(
            PosePhaseEngineConfig(
                graph = graph,
                minimumQualitySignal = 0.5,
                maximumObservationGapMs = 100L,
                unusableObservationGraceMs = 50L,
                maximumPhaseDurationMs = 1_000L,
            ),
        )
        engine.accept(observation(0L, 0.5))

        val reset = engine.accept(observation(200L, 0.5))

        assertTrue(reset.events.any { it is PosePhaseTrackingReset })
        assertTrue(reset.events.none { it is PosePhaseWindowEnded })
    }

    @Test
    fun personIdentityDiscontinuityResetsImmediatelyEvenWhenIdle() {
        val active = engine()
        feed(active, listOf(observation(0L, 175.0), observation(100L, 175.0)))

        val changed = active.resetForIdentityDiscontinuity(
            PosePhaseResetReason.PERSON_CHANGED,
            timestampMs = 150L,
        )
        assertNull(changed.activeStateId)
        assertEquals(
            PosePhaseResetReason.PERSON_CHANGED,
            changed.events.filterIsInstance<PosePhaseTrackingReset>().single().reason,
        )

        val idle = engine().resetForIdentityDiscontinuity(
            PosePhaseResetReason.PERSON_LOCK_LOST,
            timestampMs = 0L,
        )
        assertEquals(
            PosePhaseResetReason.PERSON_LOCK_LOST,
            idle.events.filterIsInstance<PosePhaseTrackingReset>().single().reason,
        )
        assertIllegalArgument {
            active.resetForIdentityDiscontinuity(
                PosePhaseResetReason.MAXIMUM_OBSERVATION_GAP,
                timestampMs = 151L,
            )
        }
    }

    @Test
    fun decreasingTimestampAndInvalidContractsFailFast() {
        val engine = engine()
        engine.accept(observation(100L, 175.0))
        assertIllegalArgument { engine.accept(observation(99L, 175.0)) }
        assertIllegalArgument { PhaseScalarInterval(Double.NaN, 1.0) }
        assertIllegalArgument {
            PosePhaseEnterPredicate(
                enterInterval = PhaseScalarInterval(0.0, 1.0),
                holdInterval = PhaseScalarInterval(0.1, 0.9),
            )
        }
        assertIllegalArgument {
            OrderedPosePhaseGraph(
                states = graph().states.values,
                initialStateId = ready,
                transitions = graph().transitions + PosePhaseTransition(ready, descending),
            )
        }
        assertIllegalArgument {
            PosePhaseEngineConfig(
                graph = graph(),
                minimumQualitySignal = 0.5,
                maximumObservationGapMs = 400L,
                unusableObservationGraceMs = 250L,
                maximumPhaseDurationMs = MAXIMUM_SUPPORTED_PHASE_DURATION_MS + 1L,
            )
        }
        assertIllegalArgument {
            PosePhaseEngineConfig(
                graph = graph(),
                minimumQualitySignal = 0.5,
                maximumObservationGapMs = 400L,
                unusableObservationGraceMs = 250L,
                maximumPhaseDurationMs = 99L,
            )
        }
    }

    private fun engine(
        directionTolerance: Double = 0.0,
        maxGapMs: Long = 400L,
        graceMs: Long = 250L,
        maximumPhaseDurationMs: Long = 10_000L,
    ) = PosePhaseEngine(
        PosePhaseEngineConfig(
            graph = graph(directionTolerance),
            minimumQualitySignal = 0.5,
            maximumObservationGapMs = maxGapMs,
            unusableObservationGraceMs = graceMs,
            maximumPhaseDurationMs = maximumPhaseDurationMs,
        ),
    )

    private fun graph(directionTolerance: Double = 0.0) = OrderedPosePhaseGraph(
        states = listOf(
            state(ready, 165.0, 180.0, 160.0, 180.0),
            state(
                descending,
                130.0,
                155.0,
                125.0,
                160.0,
                PhaseScalarDirection.DECREASING,
                directionTolerance,
            ),
            state(bottom, 80.0, 110.0, 75.0, 115.0),
            state(
                ascending,
                120.0,
                150.0,
                115.0,
                155.0,
                PhaseScalarDirection.INCREASING,
                directionTolerance,
            ),
        ),
        initialStateId = ready,
        transitions = listOf(
            PosePhaseTransition(ready, descending),
            PosePhaseTransition(descending, bottom),
            PosePhaseTransition(bottom, ascending),
            PosePhaseTransition(ascending, ready, completesCycle = true),
        ),
    )

    private fun state(
        id: PosePhaseStateId,
        enterLower: Double,
        enterUpper: Double,
        holdLower: Double,
        holdUpper: Double,
        direction: PhaseScalarDirection = PhaseScalarDirection.ANY,
        directionTolerance: Double = 0.0,
    ) = PosePhaseState(
        id = id,
        enterPredicate = PosePhaseEnterPredicate(
            enterInterval = PhaseScalarInterval(enterLower, enterUpper),
            holdInterval = PhaseScalarInterval(holdLower, holdUpper),
            direction = direction,
            directionTolerance = directionTolerance,
            minimumDwellMs = 100L,
        ),
    )

    private fun completeCycle() = listOf(
        observation(0L, 175.0),
        observation(100L, 175.0),
        observation(200L, 150.0),
        observation(300L, 145.0),
        observation(400L, 105.0),
        observation(500L, 104.0),
        observation(600L, 130.0),
        observation(700L, 140.0),
        observation(800L, 170.0),
        observation(900L, 172.0),
    )

    private fun observation(timestampMs: Long, scalar: Double) = PosePhaseObservation(
        timestampMs = timestampMs,
        scalar = scalar,
        qualitySignal = 1.0,
    )

    private fun feed(
        engine: PosePhaseEngine,
        observations: List<PosePhaseObservation>,
    ): List<PosePhaseUpdate> = observations.map(engine::accept)

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
