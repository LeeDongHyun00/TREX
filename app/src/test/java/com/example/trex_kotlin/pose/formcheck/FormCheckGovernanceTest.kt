package com.example.trex_kotlin.pose.formcheck

import com.example.trex_kotlin.devcapture.DevPoseCapture
import com.example.trex_kotlin.pose.release.PostureCorrectionRuntimeFacade
import com.example.trex_kotlin.todayPlan
import com.example.trex_kotlin.withFormCheck
import com.example.trex_kotlin.canUsePostureSession
import com.example.trex_kotlin.createWorkoutHistoryDay
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seals the heuristic beta track. Every assertion corresponds to a rule in
 * `docs/pose-heuristic-form-check.v1.md`.
 */
class FormCheckGovernanceTest {

    @Test
    fun policyDocumentMatchesItsPin() {
        val document = listOf(
            "../${HeuristicFormCheckDeclaration.POLICY_DOCUMENT_PATH}",
            HeuristicFormCheckDeclaration.POLICY_DOCUMENT_PATH,
        ).map(::File).firstOrNull(File::isFile)
            ?: error("Policy document not found from ${File("").absolutePath}")

        val normalised = document.readBytes()
            .toString(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalised)
            .joinToString("") { "%02x".format(it) }

        assertEquals(
            "Policy document and its pin must change in the same commit",
            HeuristicFormCheckDeclaration.POLICY_DOCUMENT_SHA256,
            digest,
        )
    }

    @Test
    fun theTrackClaimsNoValidatedAuthority() {
        assertEquals(
            setOf("calibrated", "clinical", "usesReleaseChain", "storesRecords", "influencesTimer"),
            HeuristicFormCheckDeclaration.claims.keys,
        )
        assertTrue(HeuristicFormCheckDeclaration.claims.values.none { it })
        assertEquals("trex.heuristic-form-check.beta.v1", HeuristicFormCheckDeclaration.TRACK_ID)
    }

    @Test
    fun thresholdsMirrorThePolicyDocumentTable() {
        assertEquals(110.0, FormCheckExercise.BARBELL_SQUAT.repDepthDegrees, 0.0)
        assertEquals(105.0, FormCheckExercise.BARBELL_SQUAT.reachedDepthDegrees, 0.0)
        assertEquals(
            FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
            FormCheckExercise.BARBELL_SQUAT.provenance,
        )
        assertEquals(134.0, FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE.repDepthDegrees, 0.0)
        assertEquals(129.0, FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE.reachedDepthDegrees, 0.0)
        assertEquals(
            FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_DAY05_FIT_V1,
            FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE.provenance,
        )
        assertEquals(130.0, FormCheckExercise.STEP_BACKWARD_DYNAMIC_LUNGE.repDepthDegrees, 0.0)
        assertEquals(123.0, FormCheckExercise.STEP_BACKWARD_DYNAMIC_LUNGE.reachedDepthDegrees, 0.0)
        assertEquals(
            FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_DAY05_FIT_V1,
            FormCheckExercise.STEP_BACKWARD_DYNAMIC_LUNGE.provenance,
        )

        // Wave 1, all uncalibrated per §4.3.
        assertEquals(134.0, FormCheckExercise.BARBELL_LUNGE.repDepthDegrees, 0.0)
        assertEquals(129.0, FormCheckExercise.BARBELL_LUNGE.reachedDepthDegrees, 0.0)
        assertEquals(135.0, FormCheckExercise.STANDING_KNEE_UP.repDepthDegrees, 0.0)
        assertEquals(125.0, FormCheckExercise.STANDING_KNEE_UP.reachedDepthDegrees, 0.0)
        assertEquals(135.0, FormCheckExercise.GOOD_MORNING.repDepthDegrees, 0.0)
        assertEquals(128.0, FormCheckExercise.GOOD_MORNING.reachedDepthDegrees, 0.0)
        assertEquals(135.0, FormCheckExercise.PUSH_UP.repDepthDegrees, 0.0)
        assertEquals(125.0, FormCheckExercise.PUSH_UP.reachedDepthDegrees, 0.0)
        assertEquals(7, FormCheckExercise.entries.size)
    }

    @Test
    fun everyDriverMatchesThePolicyTablesChain() {
        assertEquals(FormCheckDriver.KNEE, FormCheckExercise.BARBELL_SQUAT.driver)
        assertEquals(FormCheckDriver.KNEE, FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE.driver)
        assertEquals(FormCheckDriver.KNEE, FormCheckExercise.STEP_BACKWARD_DYNAMIC_LUNGE.driver)
        assertEquals(FormCheckDriver.KNEE, FormCheckExercise.BARBELL_LUNGE.driver)
        assertEquals(FormCheckDriver.HIP, FormCheckExercise.STANDING_KNEE_UP.driver)
        assertEquals(FormCheckDriver.HIP, FormCheckExercise.GOOD_MORNING.driver)
        assertEquals(FormCheckDriver.ELBOW, FormCheckExercise.PUSH_UP.driver)

        // §3: an exercise waits for its own chain and nothing else.
        assertEquals(
            setOf(
                FormCheckJointGroup.HIP,
                FormCheckJointGroup.KNEE,
                FormCheckJointGroup.ANKLE,
            ),
            FormCheckExercise.BARBELL_SQUAT.requiredJoints,
        )
        assertEquals(
            setOf(
                FormCheckJointGroup.SHOULDER,
                FormCheckJointGroup.ELBOW,
                FormCheckJointGroup.WRIST,
            ),
            FormCheckExercise.PUSH_UP.requiredJoints,
        )
    }

    @Test
    fun onlyUncalibratedExercisesMayBeLoadBearing() {
        // A calibrated exercise carries hints; a load-bearing one must not. The two sets are
        // therefore disjoint, and a future calibration of a loaded lift has to revisit §4.2
        // deliberately rather than silently gaining depth urging.
        for (spec in FormCheckExercise.entries) {
            if (spec.provenance != FormCheckThresholdProvenance.HEURISTIC_DEFAULT) {
                assertFalse(
                    "${spec.name} is calibrated, so §4.2's load-bearing seal needs re-deciding",
                    spec.loadBearing,
                )
            }
        }
    }

    @Test
    fun loadBearingExercisesNeverUrgeMoreDepth() {
        // Policy §4.2: an uncalibrated deeper-suggestion under external load is the heaviest
        // output this track could produce, so load-bearing exercises must not carry the hints.
        assertTrue(FormCheckExercise.BARBELL_SQUAT.loadBearing)
        for (spec in FormCheckExercise.entries) {
            if (spec.loadBearing) {
                assertNull("${spec.name} must not carry a shallow hint", spec.shallowHint)
                assertNull("${spec.name} must not carry a deeper hint", spec.deeperHint)
            }
        }
    }

    @Test
    fun formCheckSourcesNeverTouchTheReleaseChain() {
        val forbidden = listOf(
            "PostureCorrectionRuntimeFacade",
            "pose.release",
            "pose.shadow",
            "pose.criterion",
            "pose.readiness",
            "PoseExerciseEvaluationSession",
            "withPostureCorrection",
        )
        for (file in trackFiles()) {
            val text = file.readText()
            for (symbol in forbidden) {
                assertFalse("${file.name} references $symbol", text.contains(symbol))
            }
        }
    }

    @Test
    fun formCheckSourcesUseObservationalLanguageOnly() {
        val bannedVocabulary = listOf(
            "잘못", "틀렸", "정확", "완벽", "합격", "불합격", "위험", "부상", "진단", "교정",
        )
        for (file in trackFiles()) {
            val text = file.readText()
            for (word in bannedVocabulary) {
                assertFalse("${file.name} contains assertive vocabulary '$word'", text.contains(word))
            }
        }
    }

    @Test
    fun formCheckSourcesHaveNoPersistenceTransportOrAudio() {
        val forbidden = listOf(
            "java.io", "Log.", "println", "http", "SharedPreferences", "MediaRecorder",
            "TextToSpeech", "ToneGenerator",
        )
        for (file in trackFiles()) {
            val text = file.readText()
            for (symbol in forbidden) {
                assertFalse("${file.name} uses $symbol", text.contains(symbol))
            }
        }
    }

    @Test
    fun theSealedDisplayOnlyTrackDoesNotReferenceThisTrack() {
        val placement = mainSources().resolve("com/example/trex_kotlin/pose/placement")
        assertTrue(placement.isDirectory)
        val offenders = placement.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("formcheck", ignoreCase = true) }
            .map(File::getName)
            .toList()
        assertTrue("Display-only sources reference the beta track: $offenders", offenders.isEmpty())
    }

    @Test
    fun enablingFormCheckGrantsNoPostureAuthority() {
        for (workout in todayPlan) {
            val checked = workout.withFormCheck(true)

            assertFalse(checked.posture)
            assertFalse(checked.canUsePostureSession())
            if (FormCheckExercise.supports(workout.exercise)) {
                assertTrue(checked.formCheck)
            } else {
                assertFalse("Unsupported exercises must refuse the toggle", checked.formCheck)
            }
        }
        assertTrue(PostureCorrectionRuntimeFacade.userSelectableExercises.isEmpty())
        assertTrue(PostureCorrectionRuntimeFacade.availabilities.all { it.releasedCriterionCount == 0 })
    }

    @Test
    fun formCheckSessionsLeaveNoStoredPostureClaim() {
        val plan = todayPlan.map { it.withFormCheck(true) }

        val day = createWorkoutHistoryDay(plan, elapsedSeconds = 1_200)

        assertTrue(day.items.isNotEmpty())
        for (item in day.items) {
            assertNull(item.postureCorrection)
        }
    }

    @Test
    fun theReleaseVariantHasNoPoseStoragePathAtAll() {
        // Policy §5-5. Variant source sets replace rather than merge, so the shipped build links
        // this twin instead of the recorder; the promise is only as good as the twin staying inert.
        val stub = variantSources("release")
            .resolve("com/example/trex_kotlin/devcapture/DevPoseCapture.kt")
        assertTrue("Release DevPoseCapture twin is missing", stub.isFile)

        val text = stub.readText()
        val forbidden = listOf(
            "java.io", "java.nio", "Executors", "bufferedWriter", "getExternalFilesDir",
            "openFileOutput", "FileOutputStream", "filesDir", "mkdirs",
        )
        for (symbol in forbidden) {
            assertFalse("Release DevPoseCapture references $symbol", text.contains(symbol))
        }
    }

    @Test
    fun theDebugRecorderStaysOffUntilADeveloperOptsIn() {
        // Policy §5-5: installing a debug build must not begin recording body coordinates.
        assertFalse(DevPoseCapture.ENABLED)
        assertFalse(DevPoseCapture.isEnabled)
    }

    private fun variantSources(variant: String): File =
        listOf("src/$variant/java", "app/src/$variant/java")
            .map(::File)
            .firstOrNull(File::isDirectory)
            ?: error("$variant sources not found from ${File("").absolutePath}")

    private fun trackFiles(): List<File> {
        val sources = mainSources()
        val formcheck = sources.resolve("com/example/trex_kotlin/pose/formcheck")
        assertTrue("Form-check package is missing", formcheck.isDirectory)
        val logic = formcheck.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(logic.isNotEmpty())
        val ui = sources.resolve("com/example/trex_kotlin/SessionFormCheckLayer.kt")
        assertTrue("SessionFormCheckLayer.kt is missing", ui.isFile)
        return logic + ui
    }

    private fun mainSources(): File = listOf("src/main/java", "app/src/main/java")
        .map(::File)
        .firstOrNull(File::isDirectory)
        ?: error("Main sources not found from ${File("").absolutePath}")
}
