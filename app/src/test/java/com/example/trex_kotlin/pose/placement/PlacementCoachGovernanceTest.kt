package com.example.trex_kotlin.pose.placement

import com.example.trex_kotlin.pose.release.PlacementCoachDisplayAuthorization
import com.example.trex_kotlin.pose.release.PostureCorrectionRuntimeFacade
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seals the display-only track. Every assertion here corresponds to a numbered rule in
 * `docs/pose-nonverdict-display-policy.v1.md`.
 */
class PlacementCoachGovernanceTest {

    @Test
    fun policyDocumentMatchesItsPin() {
        val document = resolveFile(
            "../${PlacementCoachDisplayPolicy.POLICY_DOCUMENT_PATH}",
            PlacementCoachDisplayPolicy.POLICY_DOCUMENT_PATH,
        ) ?: error("Policy document not found from ${File("").absolutePath}")

        // Normalise line endings so the pin survives a CRLF checkout regardless of .gitattributes.
        val normalised = document.readBytes()
            .toString(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalised)
            .joinToString("") { "%02x".format(it) }

        assertEquals(
            "Policy document and its pin must change in the same commit",
            PlacementCoachDisplayPolicy.POLICY_DOCUMENT_SHA256,
            digest,
        )
    }

    @Test
    fun noProductionCodeOpensAnEvaluationSession() {
        val sources = mainSources()

        assertNoMatch(sources, "PoseExerciseEvaluationSession(", allow = setOf("PoseExerciseEvaluationSession.kt"))
        assertNoMatch(sources, "PoseCriterionRuntimeReadinessCatalog", allow = setOf("PoseCriterionRuntimeReadiness.kt"))
        assertNoMatch(sources, "posture = true")
        assertNoMatch(sources, "withPostureCorrection(true)")
    }

    @Test
    fun placementSourcesReferenceNoOutcomeMachinery() {
        val sources = mainSources()
        val forbidden = listOf(
            "PoseExerciseEvaluationSession",
            "PoseCriterionResult",
            "PoseFeedback",
            "PoseMetrics",
            "GlassPostureCard",
            "CircularScoreGauge",
            "RepCounter",
            "JointLegend",
            "pose.shadow",
            "pose.criterion",
            "pose.readiness",
            // Policy B10: a display-only surface must never speak or beep on its own.
            "TextToSpeech",
            "ToneGenerator",
        )

        for (file in trackFiles(sources)) {
            val text = file.readText()
            for (symbol in forbidden) {
                assertFalse("${file.name} references $symbol", text.contains(symbol))
            }
        }
    }

    @Test
    fun placementSourcesHaveNoPersistenceOrTransport() {
        val sources = mainSources()
        val forbidden = listOf("java.io", "Log.", "println", "http", "SharedPreferences", "MediaRecorder")

        for (file in trackFiles(sources)) {
            val text = file.readText()
            for (symbol in forbidden) {
                assertFalse("${file.name} uses $symbol", text.contains(symbol))
            }
        }
    }

    @Test
    fun theTrackClaimsNoProductAuthority() {
        assertEquals(
            setOf("measurement", "verdict", "score", "cue", "shadow", "release"),
            PlacementCoachDisplayAuthorization.grants.keys,
        )
        assertTrue(PlacementCoachDisplayAuthorization.grants.values.none { it })
        assertEquals(0, PlacementCoachDisplayAuthorization.observedUserSelectableExerciseCount)
        assertEquals(0, PlacementCoachDisplayAuthorization.observedReleasedCriterionCount)
        assertEquals("trex.display-only.placement-coach.v1", PlacementCoachDisplayAuthorization.TRACK_ID)
    }

    @Test
    fun theReleaseBoundaryStillOffersNothing() {
        assertTrue(PostureCorrectionRuntimeFacade.userSelectableExercises.isEmpty())
        assertTrue(PostureCorrectionRuntimeFacade.availabilities.none { it.sessionOpenAllowed })
        assertTrue(PostureCorrectionRuntimeFacade.availabilities.all { it.releasedCriterionCount == 0 })
    }

    @Test
    fun theDisplayTypeCannotBeForgedByCopy() {
        val methods = PlacementCoachDisplay::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()

        assertFalse("A generated copy() would let any call site mint a reached display", "copy" in methods)
        assertEquals(
            setOf(
                "getGoal",
                "getStage",
                "getGuidance",
                "getSkeletonVisible",
                "getSuppressedReasons",
                "getGoalReached",
                "equals",
                "hashCode",
                "toString",
            ),
            methods,
        )
    }

    /** Every source file that makes up the display-only track, logic and screen alike. */
    private fun trackFiles(sources: File): List<File> {
        val placement = sources.resolve("com/example/trex_kotlin/pose/placement")
        assertTrue("Placement package is missing", placement.isDirectory)
        val logic = placement.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Placement package is empty", logic.isNotEmpty())

        val screens = listOf(
            "com/example/trex_kotlin/PlacementCoachScreen.kt",
            "com/example/trex_kotlin/CameraPermissionState.kt",
            "com/example/trex_kotlin/SessionCameraGuideLayer.kt",
        ).map { path ->
            sources.resolve(path).also { assertTrue("$path is missing", it.isFile) }
        }

        return logic + screens
    }

    private fun assertNoMatch(sources: File, needle: String, allow: Set<String> = emptySet()) {
        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name in allow }
            .filter { it.readText().contains(needle) }
            .map { it.relativeTo(sources).path.replace('\\', '/') }
            .toList()

        assertTrue("Production code contains \"$needle\": $offenders", offenders.isEmpty())
    }

    /**
     * A sealing test that cannot find the sources it is meant to seal has failed, not passed, so
     * this raises instead of returning null.
     */
    private fun mainSources(): File = listOf("src/main/java", "app/src/main/java")
        .map(::File)
        .firstOrNull(File::isDirectory)
        ?: error("Main sources not found from ${File("").absolutePath}")

    private fun resolveFile(vararg candidates: String): File? =
        candidates.map(::File).firstOrNull(File::isFile)
}
