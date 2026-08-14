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
        // Literature-standard entries: the number cites a published standard (parallel squat at
        // ~90 degrees plus the measured bridge bias; the plank's straight-line protocol). Better
        // than a bare default, still not a fit, and owing no dataset attribution.
        assertEquals(110.0, FormCheckExercise.BARBELL_SQUAT.repAngleDegrees, 0.0)
        assertEquals(105.0, FormCheckExercise.BARBELL_SQUAT.reachedAngleDegrees, 0.0)
        assertEquals(
            FormCheckThresholdProvenance.LITERATURE_STANDARD,
            FormCheckExercise.BARBELL_SQUAT.provenance,
        )
        assertEquals(
            FormCheckThresholdProvenance.LITERATURE_STANDARD,
            FormCheckExercise.PLANK.provenance,
        )
        // The three exercises whose thresholds were measured through the app's own model, from the
        // camera view the selection artifact picks per capture day, and cleared a leave-one-subject
        // -out balanced accuracy of 0.75.
        val calibrated = mapOf(
            // 115 from the twelve-day fit (LOSO 0.800, 55 subjects, every fold at 115). The
            // nine-day fit said 105; the wider population wins, by the same rule that withdrew
            // the forward lunge's one-day number.
            FormCheckExercise.STANDING_KNEE_UP to (135.0 to 115.0),
            FormCheckExercise.LAT_PULLDOWN to (130.0 to 67.0),
            FormCheckExercise.DIPS to (135.0 to 106.0),
            // The one lunge whose depth condition survives MediaPipe: 118 at LOSO 0.840 over 33
            // participants. The pull-up was measured in the same run and failed (0.674 against a
            // 0.778 label ceiling), which is why it is absent here and stays a default.
            FormCheckExercise.CROSS_LUNGE to (130.0 to 118.0),
        )
        for ((spec, thresholds) in calibrated) {
            val (rep, reached) = thresholds
            assertEquals(spec.name, rep, spec.repAngleDegrees, 0.0)
            assertEquals(spec.name, reached, spec.reachedAngleDegrees, 0.0)
            assertEquals(
                spec.name,
                FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_FIT_V2,
                spec.provenance,
            )
        }

        // All three lunges share one uncalibrated value. The forward lunge briefly held a measured
        // 116 from a single capture day; across six days and 48 participants it falls to 0.746,
        // under the gate, so the fit was withdrawn rather than kept because it had once looked
        // good. The backward lunge can never be fitted at all — its condition separates at 0.736
        // on perfect 3D data, so no measurement view was ever selected for it.
        val lunges = listOf(
            FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE,
            FormCheckExercise.STEP_BACKWARD_DYNAMIC_LUNGE,
            FormCheckExercise.BARBELL_LUNGE,
        )
        for (lunge in lunges) {
            assertEquals(lunge.name, 129.0, lunge.reachedAngleDegrees, 0.0)
            assertEquals(
                "${lunge.name} must not claim a fit that did not survive its population",
                FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                lunge.provenance,
            )
        }
        assertEquals(134.0, FormCheckExercise.STEP_FORWARD_DYNAMIC_LUNGE.repAngleDegrees, 0.0)
        assertEquals(130.0, FormCheckExercise.STEP_BACKWARD_DYNAMIC_LUNGE.repAngleDegrees, 0.0)

        // Waves 1 and 2 plus the stability wave's rep drivers, all uncalibrated per §4.3.
        val expected = mapOf(
            FormCheckExercise.BARBELL_LUNGE to (134.0 to 129.0),
            FormCheckExercise.GOOD_MORNING to (135.0 to 128.0),
            FormCheckExercise.PUSH_UP to (135.0 to 125.0),
            FormCheckExercise.KNEE_PUSH_UP to (135.0 to 125.0),
            FormCheckExercise.BARBELL_CURL to (120.0 to 100.0),
            FormCheckExercise.DUMBBELL_CURL to (120.0 to 100.0),
            FormCheckExercise.ROWING_MACHINE to (110.0 to 100.0),
            FormCheckExercise.STANDING_SIDE_CRUNCH to (135.0 to 125.0),
            FormCheckExercise.HIP_THRUST to (145.0 to 160.0),
            FormCheckExercise.OVERHEAD_PRESS to (150.0 to 165.0),
            FormCheckExercise.CABLE_PUSH_DOWN to (150.0 to 165.0),
            // Wave 3, all uncalibrated per §4.3. The pull-up and the deadlift guard were
            // measured and failed their gates, which the policy records; nothing here may
            // quietly claim otherwise.
            FormCheckExercise.PULL_UP to (120.0 to 100.0),
            FormCheckExercise.FACE_PULL to (120.0 to 95.0),
            FormCheckExercise.UPRIGHT_ROW to (120.0 to 100.0),
            FormCheckExercise.BARBELL_ROW to (120.0 to 100.0),
            FormCheckExercise.DUMBBELL_BENT_OVER_ROW to (120.0 to 100.0),
            FormCheckExercise.BARBELL_DEADLIFT to (125.0 to 105.0),
            FormCheckExercise.BARBELL_STIFF_DEADLIFT to (130.0 to 115.0),
            FormCheckExercise.HANGING_LEG_RAISE to (130.0 to 110.0),
            FormCheckExercise.LYING_LEG_RAISE to (130.0 to 110.0),
            FormCheckExercise.LYING_TRICEPS_EXTENSION to (150.0 to 165.0),
            FormCheckExercise.FRONT_RAISE to (70.0 to 85.0),
            FormCheckExercise.DUMBBELL_PULLOVER to (150.0 to 165.0),
            // Wave 4, the coronal-plane pair the frontal placement unlocked.
            FormCheckExercise.SIDE_LUNGE to (130.0 to 120.0),
            FormCheckExercise.SIDE_LATERAL_RAISE to (70.0 to 85.0),
        )
        for ((spec, thresholds) in expected) {
            val (rep, reached) = thresholds
            assertEquals(spec.name, rep, spec.repAngleDegrees, 0.0)
            assertEquals(spec.name, reached, spec.reachedAngleDegrees, 0.0)
            assertEquals(
                "${spec.name} must not claim calibration it never had",
                FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                spec.provenance,
            )
        }
        assertEquals(160.0, FormCheckExercise.PLANK.repAngleDegrees, 0.0)
        assertEquals(33, FormCheckExercise.entries.size)
    }

    @Test
    fun guardsMirrorThePolicyDocumentTable() {
        // §4.6: a guard watches the joint that must stay put while the driver counts. The limit,
        // the chain, the window extreme and the provenance are all part of the published table.
        data class Expected(
            val driver: FormCheckDriver,
            val extreme: FormCheckGuardExtreme,
            val limit: Double,
            val provenance: FormCheckThresholdProvenance,
        )

        val expected = mapOf(
            FormCheckExercise.BARBELL_CURL to Expected(
                FormCheckDriver.SHOULDER,
                FormCheckGuardExtreme.MAX,
                52.0,
                FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_FIT_V2,
            ),
            // Back to the borrowed barbell limit. Its own fit reached 0.753 in IMAGE mode and
            // fell to 0.718 when the same footage was replayed through VIDEO mode, which is the
            // mode the app runs — so the calibration claim was withdrawn rather than kept
            // because it had once looked good.
            FormCheckExercise.DUMBBELL_CURL to Expected(
                FormCheckDriver.SHOULDER,
                FormCheckGuardExtreme.MAX,
                52.0,
                FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
            ),
            FormCheckExercise.ROWING_MACHINE to Expected(
                FormCheckDriver.TRUNK,
                FormCheckGuardExtreme.MAX,
                132.0,
                FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_FIT_V2,
            ),
            FormCheckExercise.STANDING_SIDE_CRUNCH to Expected(
                FormCheckDriver.ELBOW,
                FormCheckGuardExtreme.MIN,
                94.0,
                FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_FIT_V2,
            ),
            // The upper arm stays vertical over the face while the forearm travels: 127 at LOSO
            // 0.820 over 63 participants — the widest guard sample in the table. The barbell
            // deadlift's bar-path condition was measured in the same run and failed at 0.641,
            // which is why the deadlift carries no guard.
            FormCheckExercise.LYING_TRICEPS_EXTENSION to Expected(
                FormCheckDriver.SHOULDER,
                FormCheckGuardExtreme.MAX,
                127.0,
                FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_FIT_V2,
            ),
        )

        for (spec in FormCheckExercise.entries) {
            val want = expected[spec]
            val guard = spec.guard
            if (want == null) {
                assertEquals("${spec.name} must not quietly grow a guard", null, guard)
                continue
            }
            assertEquals("${spec.name} guard chain", want.driver, guard?.driver)
            assertEquals("${spec.name} guard extreme", want.extreme, guard?.extreme)
            assertEquals("${spec.name} guard limit", want.limit, guard?.limitDegrees)
            assertEquals("${spec.name} guard provenance", want.provenance, guard?.provenance)
        }
    }

    @Test
    fun definitionGatesMirrorThePolicyDocumentTable() {
        // §4.9: a definition gate is part of what makes an excursion this exercise's repetition.
        // The roster is contract — exercises must not quietly grow or lose clauses.
        data class Gate(
            val chain: FormCheckDriver,
            val side: FormCheckGateSide,
            val statistic: FormCheckGateStatistic,
            val comparator: FormCheckGateComparator,
            val bound: Double,
        )

        fun gates(spec: FormCheckExercise): List<Gate> = spec.definition.map {
            Gate(it.chain, it.side, it.statistic, it.comparator, it.boundDegrees)
        }

        val expected = mapOf(
            FormCheckExercise.BARBELL_SQUAT to listOf(
                Gate(
                    FormCheckDriver.HIP,
                    FormCheckGateSide.DRIVER,
                    FormCheckGateStatistic.WINDOW_MINIMUM,
                    FormCheckGateComparator.AT_MOST,
                    140.0,
                ),
            ),
            FormCheckExercise.STANDING_KNEE_UP to listOf(
                Gate(
                    FormCheckDriver.KNEE,
                    FormCheckGateSide.DRIVER,
                    FormCheckGateStatistic.WINDOW_MINIMUM,
                    FormCheckGateComparator.AT_MOST,
                    150.0,
                ),
                Gate(
                    FormCheckDriver.HIP,
                    FormCheckGateSide.OPPOSITE,
                    FormCheckGateStatistic.WINDOW_MINIMUM,
                    FormCheckGateComparator.AT_LEAST,
                    140.0,
                ),
            ),
            FormCheckExercise.STANDING_SIDE_CRUNCH to listOf(
                Gate(
                    FormCheckDriver.KNEE,
                    FormCheckGateSide.DRIVER,
                    FormCheckGateStatistic.WINDOW_MINIMUM,
                    FormCheckGateComparator.AT_MOST,
                    150.0,
                ),
                Gate(
                    FormCheckDriver.HIP,
                    FormCheckGateSide.OPPOSITE,
                    FormCheckGateStatistic.WINDOW_MINIMUM,
                    FormCheckGateComparator.AT_LEAST,
                    140.0,
                ),
            ),
            FormCheckExercise.GOOD_MORNING to listOf(
                Gate(
                    FormCheckDriver.KNEE,
                    FormCheckGateSide.DRIVER,
                    FormCheckGateStatistic.WINDOW_MINIMUM,
                    FormCheckGateComparator.AT_LEAST,
                    135.0,
                ),
            ),
            // Both curls carry the same clause, and must: gating one twin and not the other
            // would give a user two behaviours for one movement. It is the same line the
            // overhead press requires from the other side, so no excursion satisfies both.
            FormCheckExercise.BARBELL_CURL to listOf(
                Gate(
                    FormCheckDriver.SHOULDER,
                    FormCheckGateSide.DRIVER,
                    FormCheckGateStatistic.WINDOW_MAXIMUM,
                    FormCheckGateComparator.AT_MOST,
                    140.0,
                ),
            ),
            FormCheckExercise.DUMBBELL_CURL to listOf(
                Gate(
                    FormCheckDriver.SHOULDER,
                    FormCheckGateSide.DRIVER,
                    FormCheckGateStatistic.WINDOW_MAXIMUM,
                    FormCheckGateComparator.AT_MOST,
                    140.0,
                ),
            ),
            FormCheckExercise.LAT_PULLDOWN to listOf(
                Gate(
                    FormCheckDriver.ELBOW,
                    FormCheckGateSide.DRIVER,
                    FormCheckGateStatistic.WINDOW_MINIMUM,
                    FormCheckGateComparator.AT_MOST,
                    130.0,
                ),
            ),
            FormCheckExercise.HIP_THRUST to listOf(
                Gate(
                    FormCheckDriver.KNEE,
                    FormCheckGateSide.DRIVER,
                    FormCheckGateStatistic.WINDOW_MAXIMUM,
                    FormCheckGateComparator.AT_MOST,
                    140.0,
                ),
            ),
            FormCheckExercise.OVERHEAD_PRESS to listOf(
                Gate(
                    FormCheckDriver.SHOULDER,
                    FormCheckGateSide.DRIVER,
                    FormCheckGateStatistic.WINDOW_MAXIMUM,
                    FormCheckGateComparator.AT_LEAST,
                    140.0,
                ),
            ),
            FormCheckExercise.CABLE_PUSH_DOWN to listOf(
                Gate(
                    FormCheckDriver.SHOULDER,
                    FormCheckGateSide.DRIVER,
                    FormCheckGateStatistic.WINDOW_MAXIMUM,
                    FormCheckGateComparator.AT_MOST,
                    70.0,
                ),
            ),
        )

        for (spec in FormCheckExercise.entries) {
            assertEquals(
                "${spec.name} must carry exactly the definition the policy table names",
                expected[spec] ?: emptyList<Gate>(),
                gates(spec),
            )
        }

        // Every gate bound is an uncalibrated default: none was fitted, so none may claim a
        // provenance that would put the dataset credit on it.
        for (spec in FormCheckExercise.entries) {
            for (gate in spec.definition) {
                assertEquals(
                    "${spec.name}'s gate bounds are defaults until the fit pipeline measures them",
                    FormCheckThresholdProvenance.HEURISTIC_DEFAULT,
                    gate.provenance,
                )
            }
        }
    }

    @Test
    fun aGateSharingAJointWithAGuardStaysOutsideTheExercisesOwnPopulation() {
        // §4.9 rule 7. The curls are the first exercises where a guard and a gate read the same
        // chain and the same extreme, and the two say different kinds of thing: the guard
        // describes a swing inside a counted repetition, the gate says the arc was not this
        // exercise. That is only safe while the gate bound lies outside the movement's own
        // population — otherwise a user reads them as two rungs of one severity scale, and the
        // guard retroactively becomes the quality grade §4.6 forbids it from being.
        for (spec in FormCheckExercise.entries) {
            val guard = spec.guard ?: continue
            for (gate in spec.definition) {
                if (gate.chain !== guard.driver) continue
                val sameExtreme = when (guard.extreme) {
                    FormCheckGuardExtreme.MAX ->
                        gate.statistic == FormCheckGateStatistic.WINDOW_MAXIMUM
                    FormCheckGuardExtreme.MIN ->
                        gate.statistic == FormCheckGateStatistic.WINDOW_MINIMUM
                }
                if (!sameExtreme) continue
                assertTrue(
                    "${spec.name}'s gate at ${gate.boundDegrees} sits too close to its guard at " +
                        "${guard.limitDegrees} to read as a different kind of statement",
                    gate.boundDegrees - guard.limitDegrees >= 60.0,
                )
            }
        }
    }

    @Test
    fun stayClausesRequireASustainedReadingAndReachClausesDoNot() {
        // §4.9 rule 6. Noise acts on the two shapes in opposite directions: it can only make a
        // REACH clause easier to satisfy, but a single blown frame is enough to make a STAY
        // clause discard a repetition that really happened. The shape classification is what
        // routes them, so it is pinned rather than left to be re-derived at each call site.
        val stay = FormCheckExercise.entries
            .flatMap { spec -> spec.definition.map { spec to it } }
            .filter { (_, gate) -> gate.requiresSustainedReading }
            .map { (spec, gate) -> "${spec.name}:${gate.chain.vertex.name}" }
            .toSet()
        assertEquals(
            setOf(
                "STANDING_KNEE_UP:HIP",
                "STANDING_SIDE_CRUNCH:HIP",
                "GOOD_MORNING:KNEE",
                "BARBELL_CURL:SHOULDER",
                "DUMBBELL_CURL:SHOULDER",
                "HIP_THRUST:KNEE",
                "CABLE_PUSH_DOWN:SHOULDER",
            ),
            stay,
        )
    }

    @Test
    fun onlyCoronalPlaneMovementsAskForTheFrontalPlacement() {
        // §3: the preferred view follows the plane the movement works in. Everything sagittal
        // reads best from the side; a movement that travels sideways would put its whole
        // excursion along the camera's depth axis there, which is what kept these two refused
        // until a frontal placement existed to ask for.
        val frontal = FormCheckExercise.entries
            .filter { it.view == FormCheckView.FRONTAL }
            .toSet()
        assertEquals(
            setOf(FormCheckExercise.SIDE_LUNGE, FormCheckExercise.SIDE_LATERAL_RAISE),
            frontal,
        )
        for (spec in FormCheckExercise.entries) {
            // A hint may address something other than placement — the lunges ask which leg is in
            // front, because a straight near-side reading genuinely can mean the camera-side leg
            // is the rear one. What it may never do is name the placement the guidance is not
            // aiming at, which would put the screen and the voice in contradiction.
            val contradiction = when (spec.view) {
                FormCheckView.LATERAL -> "정면"
                FormCheckView.FRONTAL -> "옆모습"
            }
            assertFalse(
                "${spec.name} asks for ${spec.view} but its hint says '$contradiction'",
                spec.setupHint.contains(contradiction),
            )
        }
    }

    @Test
    fun theFrontalTokenNeverClaimsToKnowWhichWayThePersonFaces() {
        // A shoulder and hip axis cannot tell a chest from a back. The token is named for the
        // axis and the wording that reaches the user follows: it asks somebody to face the
        // camera, and never reports back that it checked they did.
        assertTrue(
            "The frontal placement must be named for the axis, not a facing direction",
            FormCheckView.FRONTAL.contractId.contains("frontal-axis"),
        )
        assertFalse(FormCheckView.FRONTAL.contractId.contains("facing"))
        for (view in FormCheckView.entries) {
            assertFalse(
                "${view.name}'s phrases must not claim a verified facing direction",
                view.setupPhrase.contains("앞을 향") || view.noteSubject.contains("앞을 향"),
            )
        }
    }

    @Test
    fun definitionGatesObserveAndNeverUrge() {
        // A failed gate states what a joint did and that the excursion was not counted. "Keep
        // it still" or "bend more" phrased at a body part is a corrective cue, and cues belong
        // to the sealed release chain — the same rule the guard lives under.
        for (spec in FormCheckExercise.entries) {
            for (gate in spec.definition) {
                assertTrue(
                    "${spec.name}'s gate must state the measured angle",
                    gate.shortfallObservation.contains("%d"),
                )
                assertTrue(
                    "${spec.name}'s gate must state the excursion was not counted",
                    gate.shortfallObservation.endsWith("횟수로 세지 않았어요"),
                )
                assertFalse(
                    "${spec.name}'s gate must not phrase a suggestion",
                    gate.shortfallObservation.contains("볼까요"),
                )
                assertFalse(
                    "${spec.name}'s gate must not phrase an instruction",
                    gate.shortfallObservation.contains("주세요"),
                )
            }
        }
    }

    @Test
    fun guardsObserveAndNeverUrge() {
        // A guard reports what happened; "keep it still" phrased as advice is a corrective cue,
        // which belongs to the sealed release chain. The suggestion-shaped ending is the tell.
        for (spec in FormCheckExercise.entries) {
            val guard = spec.guard ?: continue
            assertTrue(
                "${spec.name}'s guard must state the measured angle",
                guard.crossedObservation.contains("%d"),
            )
            assertFalse(
                "${spec.name}'s guard must not phrase a suggestion",
                guard.crossedObservation.contains("볼까요"),
            )
            assertFalse(
                "${spec.name}'s guard must not phrase an instruction",
                guard.crossedObservation.contains("주세요"),
            )
        }
    }

    @Test
    fun theHoldCadenceIsDeclaredOnlyWhereThePolicyTableSaysSo() {
        // §4.35: a hold has no excursion, so it can carry no range suggestion and its band reads
        // enter-then-release rather than rest-then-extreme.
        val holds = FormCheckExercise.entries.filter { it.cadence == FormCheckCadence.HOLD }
        assertEquals(listOf(FormCheckExercise.PLANK), holds)
        for (spec in holds) {
            assertNull(spec.attemptHint)
            assertNull(spec.rangeHint)
            assertTrue(
                "${spec.name} must enter its hold past the release line",
                spec.toDetector(spec.repAngleDegrees) < spec.toDetector(spec.restAngleDegrees),
            )
            // Unused by the hold path, but it still has to sit inside the band so a meaningless
            // number cannot be parked there.
            assertTrue(
                "${spec.name}'s unused attempt angle must stay inside its band",
                spec.toDetector(spec.attemptAngleDegrees) >
                    spec.toDetector(spec.repAngleDegrees) &&
                    spec.toDetector(spec.attemptAngleDegrees) <
                    spec.toDetector(spec.restAngleDegrees),
            )
        }
        assertEquals(160.0, FormCheckExercise.PLANK.repAngleDegrees, 0.0)
        assertEquals(145.0, FormCheckExercise.PLANK.restAngleDegrees, 0.0)
        assertEquals(152.0, FormCheckExercise.PLANK.attemptAngleDegrees, 0.0)
        assertEquals(FormCheckDriver.HIP, FormCheckExercise.PLANK.driver)
    }

    @Test
    fun everyExercisePinsItsRestAndAttemptColumnsToo() {
        // §4: the rep and reached columns were mirrored, but rest and attempt decide whether a
        // repetition ever arms, so they are just as much part of the table.
        val bands = mapOf(
            FormCheckExercise.HIP_THRUST to (110.0 to 130.0),
            FormCheckExercise.OVERHEAD_PRESS to (100.0 to 120.0),
            FormCheckExercise.CABLE_PUSH_DOWN to (100.0 to 120.0),
            FormCheckExercise.LYING_TRICEPS_EXTENSION to (100.0 to 120.0),
            FormCheckExercise.FRONT_RAISE to (20.0 to 40.0),
            FormCheckExercise.DUMBBELL_PULLOVER to (90.0 to 110.0),
            FormCheckExercise.SIDE_LATERAL_RAISE to (20.0 to 40.0),
        )
        for (spec in FormCheckExercise.entries) {
            if (spec.cadence == FormCheckCadence.HOLD) continue
            val (rest, attempt) = bands[spec] ?: (150.0 to 140.0)
            assertEquals("${spec.name} rest", rest, spec.restAngleDegrees, 0.0)
            assertEquals("${spec.name} attempt", attempt, spec.attemptAngleDegrees, 0.0)
        }
    }

    @Test
    fun everyWaveTwoExerciseReadsTheChainThePolicyTableNames() {
        assertEquals(FormCheckDriver.ELBOW, FormCheckExercise.KNEE_PUSH_UP.driver)
        assertEquals(FormCheckDriver.ELBOW, FormCheckExercise.DIPS.driver)
        assertEquals(FormCheckDriver.ELBOW, FormCheckExercise.BARBELL_CURL.driver)
        assertEquals(FormCheckDriver.ELBOW, FormCheckExercise.DUMBBELL_CURL.driver)
        // Not the elbow. The condition this exercise is measured against is how far the upper arm
        // closes toward the ribs, which the elbow angle scores at chance and the shoulder chain at
        // 0.879 balanced on 3D ground truth.
        assertEquals(FormCheckDriver.SHOULDER, FormCheckExercise.LAT_PULLDOWN.driver)
        // The stability wave counts with an ordinary chain; the fitted constant is the guard's.
        assertEquals(FormCheckDriver.KNEE, FormCheckExercise.ROWING_MACHINE.driver)
        assertEquals(FormCheckDriver.HIP, FormCheckExercise.STANDING_SIDE_CRUNCH.driver)
        assertEquals(FormCheckDriver.ELBOW, FormCheckExercise.OVERHEAD_PRESS.driver)
        assertEquals(FormCheckDriver.ELBOW, FormCheckExercise.CABLE_PUSH_DOWN.driver)
        assertEquals(FormCheckDriver.HIP, FormCheckExercise.HIP_THRUST.driver)
    }

    @Test
    fun extensionExercisesDeclareTheirThresholdsTheOtherWayRound() {
        // §4: the table states real joint angles, so an extension exercise's rep line is a larger
        // number than its rest. Mirroring happens in the session, not in the table. Holds are
        // excluded: their two angles are a band, not an excursion, and §4.35 covers them.
        val repetitions = FormCheckExercise.entries
            .filter { it.cadence == FormCheckCadence.REPETITION }
        val extension = repetitions
            .filter { it.direction == FormCheckWorkingDirection.EXTENSION }
        assertEquals(
            setOf(
                FormCheckExercise.HIP_THRUST,
                FormCheckExercise.OVERHEAD_PRESS,
                FormCheckExercise.CABLE_PUSH_DOWN,
                FormCheckExercise.LYING_TRICEPS_EXTENSION,
                FormCheckExercise.FRONT_RAISE,
                FormCheckExercise.DUMBBELL_PULLOVER,
                FormCheckExercise.SIDE_LATERAL_RAISE,
            ),
            extension.toSet(),
        )
        for (spec in extension) {
            assertTrue(
                "${spec.name} works upward from rest",
                spec.repAngleDegrees > spec.restAngleDegrees,
            )
            assertTrue(
                "${spec.name} reaches further than it counts",
                spec.reachedAngleDegrees > spec.repAngleDegrees,
            )
        }
        for (spec in repetitions - extension.toSet()) {
            assertTrue(
                "${spec.name} works downward from rest",
                spec.repAngleDegrees < spec.restAngleDegrees,
            )
        }
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
    fun aiHubDerivedThresholdsCarryTheirAttribution() {
        // Policy §4.5: the dataset's usage policy permits distributing what was learned from it
        // but requires saying so. Attribution follows provenance rather than being app-wide,
        // because most exercises carry no AI Hub-derived constant and a blanket credit would
        // claim a provenance they do not have.
        assertTrue(
            "The attribution must name the dataset",
            HeuristicFormCheckDeclaration.DATA_ATTRIBUTION.contains("AI Hub"),
        )
        assertFalse(
            "An uncalibrated default owes no attribution",
            FormCheckThresholdProvenance.HEURISTIC_DEFAULT.requiresDataAttribution,
        )
        assertFalse(
            "A literature standard cites a publication, not the dataset",
            FormCheckThresholdProvenance.LITERATURE_STANDARD.requiresDataAttribution,
        )
        assertTrue(
            "The MediaPipe-native fit was learned from AI Hub data and must be attributed",
            FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_FIT_V2.requiresDataAttribution,
        )

        // The exercise-level flag folds the guard in: a fitted guard on an otherwise-default
        // exercise still owes the credit, and a default guard confers none. The dumbbell curl
        // owes none again: its guard borrows the barbell limit after its own fit failed to
        // survive VIDEO mode, and a borrowed prior is not a thing learned from the dataset.
        assertTrue(FormCheckExercise.BARBELL_CURL.requiresDataAttribution)
        assertFalse(FormCheckExercise.DUMBBELL_CURL.requiresDataAttribution)
        assertTrue(FormCheckExercise.ROWING_MACHINE.requiresDataAttribution)
        assertTrue(FormCheckExercise.STANDING_SIDE_CRUNCH.requiresDataAttribution)
        assertFalse(FormCheckExercise.BARBELL_SQUAT.requiresDataAttribution)
        assertFalse(FormCheckExercise.PLANK.requiresDataAttribution)

        // The surface that renders it must actually consult the flag.
        val layer = mainSources().resolve("com/example/trex_kotlin/SessionFormCheckLayer.kt")
        val text = layer.readText()
        assertTrue(
            "SessionFormCheckLayer must gate the attribution on provenance",
            text.contains("requiresDataAttribution") && text.contains("DATA_ATTRIBUTION"),
        )
    }

    @Test
    fun theShoulderChainIsNeverDescribedAsBending() {
        // §4.1: the detector direction and the word shown to the user are different facts. A lat
        // pull-down closes the elbow-shoulder-hip angle, so the direction is flexion, but rendering
        // that as "어깨가 67도까지 굽혀졌어요" describes rounded shoulders — a posture judgement this
        // track does not make. Every shoulder-chain exercise must therefore name its own words:
        // drawn in when the arm closes on the torso, opened when it rises away.
        for (spec in FormCheckExercise.entries) {
            if (spec.driver != FormCheckDriver.SHOULDER) continue
            val expected = when (spec.direction) {
                FormCheckWorkingDirection.FLEXION -> FormCheckVocabulary.DRAWING_IN
                FormCheckWorkingDirection.EXTENSION -> FormCheckVocabulary.OPENING
            }
            assertEquals(
                "${spec.name} reads the shoulder and must not call it bending",
                expected,
                spec.vocabulary,
            )
        }

        // And the sentence itself, built the way the engine builds it.
        val pulldown = FormCheckExercise.LAT_PULLDOWN
        val observation = "${pulldown.driver.vertex.label} 67도까지 ${pulldown.vocabulary.reachedVerb}"
        assertEquals("어깨 67도까지 모아졌어요", observation)
        assertFalse(observation.contains("굽혀"))
    }

    @Test
    fun everyExerciseKeepsTheWordsThatMatchItsAnatomy() {
        // The overrides exist for the shoulder; nothing else may quietly acquire one.
        for (spec in FormCheckExercise.entries) {
            val expected = when {
                spec.driver == FormCheckDriver.SHOULDER &&
                    spec.direction == FormCheckWorkingDirection.FLEXION ->
                    FormCheckVocabulary.DRAWING_IN
                spec.driver == FormCheckDriver.SHOULDER ->
                    FormCheckVocabulary.OPENING
                else -> spec.direction.defaultVocabulary
            }
            assertEquals(spec.name, expected, spec.vocabulary)
        }
    }

    @Test
    fun sealingAnswersConsequenceNotEvidence() {
        // This rule used to read "only uncalibrated exercises may be sealed", on the assumption
        // that calibrating something was the same as clearing it to urge more range. Dips broke
        // that: it now carries a measured threshold and is still sealed, because at the bottom of a
        // dip the shoulder is at end range and a better threshold does not change that. §4.2 was
        // revised to say the seal is about the consequence of overshoot.
        //
        // What must still hold is the part that protects the user: a sealed exercise carries no
        // suggestion, whatever its evidence. That is asserted in sealedExercisesNeverUrgeMoreRange.
        val sealedAndFitted = FormCheckExercise.entries.filter {
            it.rangeUrgingSealed &&
                it.provenance == FormCheckThresholdProvenance.MEDIAPIPE_NATIVE_FIT_V2
        }
        assertEquals(
            "Only dips is deliberately both fitted and sealed; anything else is a slip",
            listOf(FormCheckExercise.DIPS),
            sealedAndFitted,
        )
    }

    @Test
    fun sealedExercisesNeverUrgeMoreRange() {
        // Policy §4.2: an uncalibrated range-increase suggestion is the heaviest output this
        // track could produce wherever overshooting has a real consequence — an external load on
        // the spine, or a joint already at end range.
        val sealed = FormCheckExercise.entries.filter { it.rangeUrgingSealed }.toSet()
        assertEquals(
            setOf(
                FormCheckExercise.BARBELL_SQUAT,
                FormCheckExercise.BARBELL_LUNGE,
                FormCheckExercise.GOOD_MORNING,
                FormCheckExercise.DIPS,
                FormCheckExercise.HIP_THRUST,
                FormCheckExercise.OVERHEAD_PRESS,
                // Wave 3's loaded hinges and end-range positions: the rows and deadlifts hold an
                // external load on a hinged spine, the upright row's top carries the shoulder
                // toward its famous overuse position, and a pullover's bottom is a loaded
                // overhead end range — the dips criterion again.
                FormCheckExercise.BARBELL_ROW,
                FormCheckExercise.DUMBBELL_BENT_OVER_ROW,
                FormCheckExercise.BARBELL_DEADLIFT,
                FormCheckExercise.BARBELL_STIFF_DEADLIFT,
                FormCheckExercise.UPRIGHT_ROW,
                FormCheckExercise.DUMBBELL_PULLOVER,
                FormCheckExercise.SIDE_LATERAL_RAISE,
            ),
            sealed,
        )
        for (spec in sealed) {
            assertNull("${spec.name} must not carry an attempt hint", spec.attemptHint)
            assertNull("${spec.name} must not carry a range hint", spec.rangeHint)
        }
    }

    @Test
    fun theSurfaceNeverDrawsAThresholdTarget() {
        // Six exercises may never be urged toward more range (§4.2), and a target drawn only on
        // the other twelve would make the seal visible as a missing feature. So no surface draws
        // one at all: no reach tick, no ideal band, no notch, for any exercise. The only way such
        // a mark could get built is by reading a threshold in the drawing code, so the threshold
        // names are what this test forbids. It converts a rule about wording into a rule about
        // rendering, which is the level §4.2 actually operates at.
        val layer = mainSources().resolve("com/example/trex_kotlin/SessionFormCheckLayer.kt")
        val text = layer.readText()
        for (threshold in listOf(
            "reachedAngleDegrees",
            "repAngleDegrees",
            "attemptAngleDegrees",
            "restAngleDegrees",
            "limitDegrees",
        )) {
            assertFalse(
                "SessionFormCheckLayer must not render the $threshold line as a target",
                text.contains(threshold),
            )
        }
    }

    @Test
    fun theSurfaceNeverPaintsAnErrorColour() {
        // Abstention is a statement about what the camera could observe, not a fault, and an
        // alarm colour on a live picture of somebody's body is read as a verdict about the body.
        // Colour on this surface encodes tense only: being measured, seen but not measured, or
        // absent. Every state it shows is also carried by a shape or a sentence, so nothing here
        // depends on colour alone.
        val layer = mainSources().resolve("com/example/trex_kotlin/SessionFormCheckLayer.kt")
        val text = layer.readText()
        for (alarm in listOf("TrexError", "TrexWarning")) {
            assertFalse("SessionFormCheckLayer must not paint $alarm", text.contains(alarm))
        }
    }

    @Test
    fun theSurfaceClearsItsLiveMirrorWhereverItClearsTheFrame() {
        // The layer mirrors the engine's live reading into its own state so the Canvas can read it
        // without recomposing text at frame rate. That mirror is a second copy of a value whose
        // nullability is the abstention contract, and a camera that stops delivering frames never
        // pushes a null through the callback — so the layer has to clear it on the same paths it
        // clears the frame, or the last angle stays drawn on a body that has left.
        val layer = mainSources().resolve("com/example/trex_kotlin/SessionFormCheckLayer.kt")
        val text = layer.readText()
        assertTrue(
            "The layer must mirror the engine's abstention rather than its own guess",
            text.contains("liveState.value = session.liveReading"),
        )
        val cleared = Regex("""frameState\.value = null\s*\n\s*liveState\.value = null""")
        assertTrue(
            "liveState must be cleared beside frameState",
            cleared.containsMatchIn(text),
        )
        assertTrue(
            "A paused camera stops delivering frames, so the mirror must be cleared explicitly",
            text.contains("if (paused) liveState.value = null"),
        )
    }

    @Test
    fun everySurfaceThatShowsANumberAlsoShowsTheDisclosure() {
        // The live slab is no longer the only place this track puts a count in front of somebody:
        // the rest period shows the set's review. Both owe the same disclosure, so the count is
        // checked wherever it is rendered rather than only where it was first written.
        val layer = mainSources().resolve("com/example/trex_kotlin/SessionFormCheckLayer.kt")
        val text = layer.readText()
        for (surface in listOf("FormCheckCountSlab", "FormCheckSetSummaryCard", "FormCheckIntroCard")) {
            val body = text.substringAfter("fun $surface", "").substringBefore("\n}\n")
            assertTrue("$surface is missing", body.isNotEmpty())
            assertTrue(
                "$surface must carry the beta disclosure",
                body.contains("BETA_DISCLOSURE"),
            )
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
