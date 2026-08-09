package com.example.trex_kotlin.pose.criterion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PoseCriterionGraphTest {
    private val engine = PoseCriterionEngine()
    private val capability = CriterionCapability.POSE_2D

    @Test
    fun validatesNodeIdentityReferencesAndCycles() {
        assertIllegalArgument {
            PoseCriterionGraph(listOf(node("duplicate"), node("duplicate")))
        }
        assertIllegalArgument {
            PoseCriterionGraph(listOf(node("only", dependencies = setOf("missing"))))
        }
        assertIllegalArgument {
            PoseCriterionGraph(listOf(node("only", suppresses = setOf("missing"))))
        }
        assertIllegalArgument {
            PoseCriterionGraph(
                listOf(
                    node("a", dependencies = setOf("b")),
                    node("b", dependencies = setOf("a")),
                ),
            )
        }
        assertIllegalArgument {
            PoseCriterionGraph(
                listOf(
                    node("a", suppresses = setOf("b")),
                    node("b", suppresses = setOf("a")),
                ),
            )
        }
    }

    @Test
    fun nodeInputsAreDefensivelyCopied() {
        val dependencies = mutableSetOf("root")
        val suppresses = mutableSetOf("other")
        val child = node("child", dependencies = dependencies, suppresses = suppresses)

        dependencies.clear()
        suppresses.clear()

        assertEquals(setOf("root"), child.dependencies)
        assertEquals(setOf("other"), child.suppresses)
        try {
            @Suppress("UNCHECKED_CAST")
            (child.dependencies as MutableSet<String>).clear()
            fail("Expected an immutable dependency set")
        } catch (_: UnsupportedOperationException) {
            // Expected.
        }
    }

    @Test
    fun graphIdentityChangesForEveryDecisionRelevantPolicyField() {
        val baseline = PoseCriterionGraph(
            listOf(node("a", lowCue = "a.low"), node("b")),
        ).graphSpecSha256

        val variants = listOf(
            PoseCriterionGraph(
                listOf(node("a", severity = CriterionSeverity.SAFETY, lowCue = "a.low"), node("b")),
            ),
            PoseCriterionGraph(
                listOf(node("a", lowCue = "a.changed"), node("b")),
            ),
            PoseCriterionGraph(
                listOf(node("a", lowCue = "a.low", suppresses = setOf("b")), node("b")),
            ),
            PoseCriterionGraph(
                listOf(node("b"), node("a", lowCue = "a.low")),
            ),
        )

        assertEquals(64, baseline.length)
        assertTrue(variants.all { it.graphSpecSha256 != baseline })
    }

    @Test
    fun releasedSubgraphMustBeClosedOverDependenciesAndShadowSuppression() {
        val shadowDependency = PoseCriterionGraph(
            listOf(
                node("shadow"),
                node("released", dependencies = setOf("shadow")),
            ),
        )
        val shadowSuppressor = PoseCriterionGraph(
            listOf(
                node("shadow", suppresses = setOf("released")),
                node("released"),
            ),
        )

        assertIllegalArgument { shadowDependency.validateReleaseClosure(setOf("released")) }
        assertIllegalArgument { shadowSuppressor.validateReleaseClosure(setOf("released")) }
        shadowDependency.validateReleaseClosure(setOf("shadow", "released"))
    }

    @Test
    fun allPassPreservesAtomicResultsAndProducesNoCue() {
        val rootResult = atomic("root", CriterionState.PASS)
        val childResult = atomic("child", CriterionState.PASS)
        val evaluation = PoseCriterionGraph(
            listOf(
                node("root"),
                node("child", dependencies = setOf("root")),
            ),
        ).evaluate(
            results = listOf(rootResult, childResult),
            cueEligibleCriterionIds = setOf("root", "child"),
        )

        assertEquals(CriterionGraphStatus.PASS, evaluation.status)
        assertNull(evaluation.selectedCue)
        assertSame(rootResult, evaluation.node("root").atomicResult)
        assertSame(childResult, evaluation.node("child").atomicResult)
        assertEquals(CriterionGraphStatus.PASS, evaluation.node("child").graphStatus)
    }

    @Test
    fun unknownDependencyConfoundsChildAndCannotBecomePass() {
        val childAtomicFailure = atomic(
            "child",
            CriterionState.FAIL,
            CriterionFailRegion.HIGH_SIDE,
        )
        val evaluation = PoseCriterionGraph(
            listOf(
                node("unknown-root"),
                node(
                    "child",
                    severity = CriterionSeverity.SAFETY,
                    dependencies = setOf("unknown-root"),
                    highCue = "child.high",
                ),
            ),
        ).evaluate(
            results = listOf(
                atomic("unknown-root", CriterionState.UNKNOWN),
                childAtomicFailure,
            ),
            cueEligibleCriterionIds = setOf("unknown-root", "child"),
        )

        val child = evaluation.node("child")
        assertEquals(CriterionGraphStatus.UNKNOWN_CONFOUNDED, evaluation.status)
        assertEquals(CriterionGraphStatus.UNKNOWN_CONFOUNDED, child.graphStatus)
        assertSame(childAtomicFailure, child.atomicResult)
        assertEquals(CriterionState.FAIL, child.atomicResult.state)
        assertFalse(child.cueEligible)
        assertEquals(setOf("unknown-root"), child.dependencyBlockers)
        assertTrue(
            CriterionCueSuppressionReason.UNKNOWN_DEPENDENCY in child.cueSuppressionReasons,
        )
        assertNull(evaluation.selectedCue)
    }

    @Test
    fun failedDependencyKeepsRootCauseAndSuppressesEveryDescendantCue() {
        val evaluation = PoseCriterionGraph(
            listOf(
                node("root", lowCue = "root.low"),
                node("middle", dependencies = setOf("root")),
                node(
                    "leaf",
                    severity = CriterionSeverity.SAFETY,
                    dependencies = setOf("middle"),
                    highCue = "leaf.high",
                ),
            ),
        ).evaluate(
            results = listOf(
                atomic("root", CriterionState.FAIL, CriterionFailRegion.LOW_SIDE),
                atomic("middle", CriterionState.PASS),
                atomic("leaf", CriterionState.FAIL, CriterionFailRegion.HIGH_SIDE),
            ),
            cueEligibleCriterionIds = setOf("root", "middle", "leaf"),
        )

        assertEquals(CriterionGraphStatus.FAIL, evaluation.status)
        assertEquals("root", evaluation.selectedCue?.criterionId)
        assertEquals("root.low", evaluation.selectedCue?.cueCode)
        assertTrue(evaluation.node("root").isRootCause)
        assertEquals(CriterionGraphStatus.FAIL, evaluation.node("middle").graphStatus)
        assertEquals(CriterionState.PASS, evaluation.node("middle").atomicResult.state)
        assertFalse(evaluation.node("leaf").isRootCause)
        assertFalse(evaluation.node("leaf").cueEligible)
        assertEquals(setOf("root"), evaluation.node("leaf").dependencyBlockers)
        assertTrue(
            CriterionCueSuppressionReason.FAILED_DEPENDENCY in
                evaluation.node("leaf").cueSuppressionReasons,
        )
    }

    @Test
    fun explicitRootCauseSuppressionOverridesDescendantSeverity() {
        val evaluation = PoseCriterionGraph(
            listOf(
                node(
                    "alignment-root",
                    lowCue = "alignment.low",
                    suppresses = setOf("knee-symptom"),
                ),
                node(
                    "knee-symptom",
                    severity = CriterionSeverity.SAFETY,
                    highCue = "knee.high",
                ),
            ),
        ).evaluate(
            results = listOf(
                atomic("alignment-root", CriterionState.FAIL, CriterionFailRegion.LOW_SIDE),
                atomic("knee-symptom", CriterionState.FAIL, CriterionFailRegion.HIGH_SIDE),
            ),
            cueEligibleCriterionIds = setOf("alignment-root", "knee-symptom"),
        )

        assertEquals("alignment-root", evaluation.selectedCue?.criterionId)
        assertFalse(evaluation.node("knee-symptom").cueEligible)
        assertEquals(
            setOf("alignment-root"),
            evaluation.node("knee-symptom").suppressingCriterionIds,
        )
        assertTrue(
            CriterionCueSuppressionReason.EXPLICIT_SUPPRESSION in
                evaluation.node("knee-symptom").cueSuppressionReasons,
        )
    }

    @Test
    fun selectsOneCueBySeverityThenStableDeclarationOrder() {
        val graph = PoseCriterionGraph(
            listOf(
                node("early-correction", lowCue = "early.low"),
                node(
                    "first-safety",
                    severity = CriterionSeverity.SAFETY,
                    highCue = "first.high",
                ),
                node(
                    "second-safety",
                    severity = CriterionSeverity.SAFETY,
                    lowCue = "second.low",
                ),
            ),
        )
        val evaluation = graph.evaluate(
            results = listOf(
                atomic("early-correction", CriterionState.FAIL, CriterionFailRegion.LOW_SIDE),
                atomic("first-safety", CriterionState.FAIL, CriterionFailRegion.HIGH_SIDE),
                atomic("second-safety", CriterionState.FAIL, CriterionFailRegion.LOW_SIDE),
            ),
            cueEligibleCriterionIds = graph.nodeIds,
        )

        assertEquals(CriterionGraphStatus.FAIL, evaluation.status)
        assertEquals("first-safety", evaluation.selectedCue?.criterionId)
        assertEquals("first.high", evaluation.selectedCue?.cueCode)
        assertEquals(CriterionSeverity.SAFETY, evaluation.selectedCue?.severity)
        assertEquals(CriterionFailRegion.HIGH_SIDE, evaluation.selectedCue?.failRegion)
        assertEquals(1, evaluation.nodes.count { it.cueCode == evaluation.selectedCue?.cueCode })
    }

    @Test
    fun shadowResultCannotChangeReleasedCueOrAggregate() {
        val graph = PoseCriterionGraph(
            listOf(
                node(
                    "shadow-safety",
                    severity = CriterionSeverity.SAFETY,
                    lowCue = "shadow.low",
                ),
                node(
                    "released",
                    lowCue = "released.low",
                ),
            ),
        )

        val releasedFailure = graph.evaluate(
            results = listOf(
                atomic("shadow-safety", CriterionState.FAIL, CriterionFailRegion.LOW_SIDE),
                atomic("released", CriterionState.FAIL, CriterionFailRegion.LOW_SIDE),
            ),
            cueEligibleCriterionIds = setOf("released"),
        )

        assertEquals(CriterionGraphStatus.FAIL, releasedFailure.status)
        assertEquals("released", releasedFailure.selectedCue?.criterionId)
        assertTrue(releasedFailure.node("released").isRootCause)
        assertTrue(releasedFailure.node("released").cueEligible)
        assertTrue(releasedFailure.node("released").dependencyBlockers.isEmpty())
        assertTrue(releasedFailure.node("released").suppressingCriterionIds.isEmpty())
        assertEquals(
            CriterionGraphStatus.FAIL,
            releasedFailure.node("shadow-safety").graphStatus,
        )
        assertFalse(releasedFailure.node("shadow-safety").isRootCause)
        assertFalse(releasedFailure.node("shadow-safety").cueEligible)
        assertTrue(
            CriterionCueSuppressionReason.NOT_RELEASED in
                releasedFailure.node("shadow-safety").cueSuppressionReasons,
        )

        val releasedPassDespiteShadowFailure = graph.evaluate(
            results = listOf(
                atomic("shadow-safety", CriterionState.FAIL, CriterionFailRegion.LOW_SIDE),
                atomic("released", CriterionState.PASS),
            ),
            cueEligibleCriterionIds = setOf("released"),
        )
        assertEquals(CriterionGraphStatus.PASS, releasedPassDespiteShadowFailure.status)
        assertEquals(
            CriterionGraphStatus.PASS,
            releasedPassDespiteShadowFailure.node("released").graphStatus,
        )
        assertNull(releasedPassDespiteShadowFailure.selectedCue)

        val releasedPassDespiteShadowUnknown = graph.evaluate(
            results = listOf(
                atomic("shadow-safety", CriterionState.UNKNOWN),
                atomic("released", CriterionState.PASS),
            ),
            cueEligibleCriterionIds = setOf("released"),
        )
        assertEquals(CriterionGraphStatus.PASS, releasedPassDespiteShadowUnknown.status)
        assertEquals(
            CriterionGraphStatus.PASS,
            releasedPassDespiteShadowUnknown.node("released").graphStatus,
        )

        val allShadow = graph.evaluate(
            results = listOf(
                atomic("shadow-safety", CriterionState.FAIL, CriterionFailRegion.LOW_SIDE),
                atomic("released", CriterionState.FAIL, CriterionFailRegion.LOW_SIDE),
            ),
            cueEligibleCriterionIds = emptySet(),
        )
        assertEquals(CriterionGraphStatus.UNKNOWN, allShadow.status)
        assertNull(allShadow.selectedCue)
        assertTrue(allShadow.nodes.none(CriterionNodeEvaluation::isRootCause))
        assertTrue(allShadow.nodes.none(CriterionNodeEvaluation::cueEligible))

        assertIllegalArgument {
            graph.evaluate(
                results = listOf(
                    atomic("shadow-safety", CriterionState.PASS),
                    atomic("released", CriterionState.PASS),
                ),
                cueEligibleCriterionIds = setOf("unknown"),
            )
        }
    }

    @Test
    fun mapsDirectionalCodesWithoutInterpretingTheirContents() {
        val graph = PoseCriterionGraph(
            listOf(
                node(
                    "directional",
                    lowCue = "opaque://criterion/low:v7",
                    highCue = "opaque://criterion/high:v3",
                ),
            ),
        )

        val low = graph.evaluate(
            results = listOf(
                atomic("directional", CriterionState.FAIL, CriterionFailRegion.LOW_SIDE),
            ),
            cueEligibleCriterionIds = setOf("directional"),
        )
        val high = graph.evaluate(
            results = listOf(
                atomic("directional", CriterionState.FAIL, CriterionFailRegion.HIGH_SIDE),
            ),
            cueEligibleCriterionIds = setOf("directional"),
        )

        assertEquals("opaque://criterion/low:v7", low.selectedCue?.cueCode)
        assertEquals("opaque://criterion/high:v3", high.selectedCue?.cueCode)
    }

    @Test
    fun unknownAtomicGraphStateIsNeverPromotedToPass() {
        val evaluation = PoseCriterionGraph(listOf(node("unknown"))).evaluate(
            results = listOf(atomic("unknown", CriterionState.UNKNOWN)),
            cueEligibleCriterionIds = setOf("unknown"),
        )

        assertEquals(CriterionGraphStatus.UNKNOWN, evaluation.status)
        assertEquals(CriterionGraphStatus.UNKNOWN, evaluation.node("unknown").graphStatus)
        assertNull(evaluation.selectedCue)
    }

    @Test
    fun runtimeResultsMustMatchGraphExactly() {
        val graph = PoseCriterionGraph(listOf(node("a"), node("b")))
        val a = atomic("a", CriterionState.PASS)
        val b = atomic("b", CriterionState.PASS)

        assertIllegalArgument { graph.evaluate(listOf(a), graph.nodeIds) }
        assertIllegalArgument { graph.evaluate(listOf(a, a), graph.nodeIds) }
        assertIllegalArgument {
            graph.evaluate(
                listOf(a, b, atomic("unexpected", CriterionState.PASS)),
                graph.nodeIds,
            )
        }
    }

    private fun node(
        id: String,
        severity: CriterionSeverity = CriterionSeverity.CORRECTION,
        dependencies: Set<String> = emptySet(),
        lowCue: String? = null,
        highCue: String? = null,
        suppresses: Set<String> = emptySet(),
    ) = CriterionNodeSpec(
        id = id,
        severity = severity,
        dependencies = dependencies,
        lowSideCueCode = lowCue,
        highSideCueCode = highCue,
        suppresses = suppresses,
    )

    private fun atomic(
        id: String,
        state: CriterionState,
        failRegion: CriterionFailRegion? = null,
    ): PoseCriterionResult {
        require((state == CriterionState.FAIL) == (failRegion != null))
        val contract = CriterionCalibrationContract(
            criterionId = id,
            featureContractId = "graph-test-feature:v1",
            featureSpecSha256 = "0".repeat(64),
            measurementUnit = "degree",
            aggregation = CriterionAggregation.WeightedMean,
            qualityContractId = "graph-test-quality:v1",
            qualityCalibrationArtifactSha256 = "a".repeat(64),
            runtimeDomainId = "graph-test-runtime:v1",
            validMeasurementInterval = MeasurementInterval(-100.0, 100.0),
            minimumSampleQuality = 0.1,
            minimumTimeCoverage = 1.0,
            minimumEvidenceMass = 1.0,
            minimumObservableDurationMs = 100L,
            minimumEffectiveSamples = 1.0,
            maximumGapMs = 100L,
            correlationHorizonMs = 100L,
            contractVersion = 1,
        )
        val calibration = CriterionAggregateCalibration(
            contract = contract,
            additiveErrorInterval = MeasurementInterval(0.0, 0.0),
        )
        val target = MeasurementInterval(0.0, 10.0)
        val capabilities = setOf(capability)
        val spec = PoseCriterionSpec(
            calibrationContract = contract,
            approvedCalibrationArtifactSha256 = calibration.artifactSha256,
            approvedEvaluatorSpecSha256 = evaluatorSpecSha256(
                approvedCalibrationArtifactSha256 = calibration.artifactSha256,
                targetInterval = target,
                requiredCapabilities = capabilities,
            ),
            targetInterval = target,
            requiredCapabilities = capabilities,
        )
        val measurement = when (failRegion) {
            CriterionFailRegion.LOW_SIDE -> -5.0
            CriterionFailRegion.HIGH_SIDE -> 15.0
            null -> 5.0
        }
        return engine.evaluate(
            spec = spec,
            phaseWindow = CriterionPhaseWindow(0L, 200L),
            samples = listOf(
                CriterionEvidenceSample(0L, measurement, 1.0),
                CriterionEvidenceSample(100L, measurement, 1.0),
                CriterionEvidenceSample(200L, measurement, 1.0),
            ),
            availableCapabilities = if (state == CriterionState.UNKNOWN) {
                emptySet()
            } else {
                capabilities
            },
            calibration = calibration,
        ).also { result ->
            assertEquals(state, result.state)
            assertEquals(failRegion, result.failRegion)
        }
    }

    private fun PoseCriterionGraphEvaluation.node(id: String): CriterionNodeEvaluation =
        nodes.single { it.spec.id == id }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
