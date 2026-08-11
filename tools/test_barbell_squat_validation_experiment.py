import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

if __package__:
    from .barbell_squat_validation_experiment import (
        CANDIDATE_FEATURES,
        CONDITIONS,
        EXPECTED_BITS,
        Observation,
        PROTOCOL_CONTRACT,
        SCHEMA_VERSION,
        SquatExperimentError,
        _contiguous_bottom_indices,
        _canonical_lf_text_sha256,
        _expected_condition_tuple,
        _frame_proxy_features,
        _two_d_coordinate_payload,
        _verified_catalog_provenance,
        _validate_internal_sequence_uniqueness,
        atomic_write_json,
        evaluate_criteria,
        extract_predeclared_features,
        paired_sign_report,
        validate_output_path,
        verify_report_fingerprint,
    )
    from .analyze_pose_coordinate_criteria import (
        SequenceDataError,
        _canonical_sha256,
        normalize_frame_points,
    )
else:
    from barbell_squat_validation_experiment import (
        CANDIDATE_FEATURES,
        CONDITIONS,
        EXPECTED_BITS,
        Observation,
        PROTOCOL_CONTRACT,
        SCHEMA_VERSION,
        SquatExperimentError,
        _contiguous_bottom_indices,
        _canonical_lf_text_sha256,
        _expected_condition_tuple,
        _frame_proxy_features,
        _two_d_coordinate_payload,
        _verified_catalog_provenance,
        _validate_internal_sequence_uniqueness,
        atomic_write_json,
        evaluate_criteria,
        extract_predeclared_features,
        paired_sign_report,
        validate_output_path,
        verify_report_fingerprint,
    )
    from analyze_pose_coordinate_criteria import (
        SequenceDataError,
        _canonical_sha256,
        normalize_frame_points,
    )


def base_3d_points(
    *,
    spine_bend: float = 0.0,
    head_yaw: bool = False,
    knee_mismatch: bool = False,
    foot_shift: float = 0.0,
    knee_forward: float = 0.25,
) -> dict[str, dict[str, float]]:
    points = {
        "Nose": (0.0, 2.9, 0.4),
        "Left Eye": (-0.12, 2.95, 0.35),
        "Right Eye": (0.12, 2.95, 0.35),
        "Left Ear": (-0.25, 2.75, 0.0),
        "Right Ear": (0.25, 2.75, 0.0),
        "Left Shoulder": (-0.6, 2.0, 0.0),
        "Right Shoulder": (0.6, 2.0, 0.0),
        "Left Elbow": (-0.9, 1.2, 0.0),
        "Right Elbow": (0.9, 1.2, 0.0),
        "Left Wrist": (-0.8, 0.8, 0.0),
        "Right Wrist": (0.8, 0.8, 0.0),
        "Left Hip": (-0.5, 0.0, 0.0),
        "Right Hip": (0.5, 0.0, 0.0),
        "Left Knee": (-0.5, -1.0, knee_forward),
        "Right Knee": (0.5, -1.0, knee_forward),
        "Left Ankle": (-0.5, -2.0, 0.0),
        "Right Ankle": (0.5, -2.0, 0.0),
        "Neck": (0.0, 2.4, 0.0),
        "Left Palm": (-0.8, 0.7, 0.0),
        "Right Palm": (0.8, 0.7, 0.0),
        "Back": (spine_bend, 1.45, 0.0),
        "Waist": (0.0, 0.65, 0.0),
        "Left Foot": (-0.5 + foot_shift, -2.15, 0.45),
        "Right Foot": (0.5 + foot_shift, -2.15, 0.45),
    }
    if head_yaw:
        points["Left Ear"] = (0.0, 2.75, -0.25)
        points["Right Ear"] = (0.0, 2.75, 0.25)
    if knee_mismatch:
        points["Left Knee"] = (-0.9, -1.0, 0.0)
        points["Right Knee"] = (0.9, -1.0, 0.0)
    return {
        joint: {"x": value[0], "y": value[1], "z": value[2]}
        for joint, value in points.items()
    }


def two_d_points(
    foot_shift: float = 0.0,
    knee_forward: float = 0.25,
) -> dict[str, dict[str, float]]:
    source = base_3d_points(foot_shift=foot_shift, knee_forward=knee_forward)
    # A deterministic oblique projection keeps both forward (z) and lateral (x) signal.
    return {
        joint: {
            "x": value["x"] * 100.0 + value["z"] * 35.0 + 960.0,
            "y": -value["y"] * 100.0 + 540.0,
        }
        for joint, value in source.items()
    }


def sequence_payloads(moving_feet: bool) -> tuple[list[dict], list[dict]]:
    frames_2d = []
    frames_3d = []
    for index in range(8):
        shift = index * 0.04 if moving_feet else 0.0
        knee_forward = 0.25 + 0.65 * (1.0 - abs(3.5 - index) / 3.5)
        frames_3d.append(
            {"pts": base_3d_points(foot_shift=shift, knee_forward=knee_forward)}
        )
        points = two_d_points(foot_shift=shift, knee_forward=knee_forward)
        frames_2d.append(
            {
                f"view{view}": {
                    "pts": points,
                    "active": "Yes",
                    "img_key": f"Z{view}-{index * 2 + 1:07d}.jpg",
                }
                for view in range(1, 6)
            }
        )
    return frames_2d, frames_3d


def synthetic_observations(subjects: list[str], validation_flip: bool = False) -> list[Observation]:
    observations: list[Observation] = []
    feature_names = sorted({name for values in CANDIDATE_FEATURES.values() for name in values})
    for subject_index, subject in enumerate(subjects):
        for type_index, type_code in enumerate(sorted(EXPECTED_BITS)):
            conditions = _expected_condition_tuple(type_code)
            features = {name: 5.0 + subject_index * 0.01 for name in feature_names}
            for condition_index, condition_name in enumerate(CONDITIONS):
                candidates = CANDIDATE_FEATURES[condition_name]
                label = conditions[condition_index]
                # Candidate zero is strongest in Training.  Validation can deliberately reverse
                # it to prove that feature selection is not repeated on the locked split.
                features[candidates[0]] = (
                    (9.0 if label else 1.0)
                    if validation_flip
                    else (1.0 if label else 9.0)
                ) + subject_index * 0.01
                for candidate_index, candidate in enumerate(candidates[1:], start=1):
                    features[candidate] = (
                        5.0 + subject_index * 0.01
                        if candidate_index == 1
                        else (7.0 if label else 3.0) + subject_index * 0.01
                    )
            observations.append(
                Observation(
                    sequence_id=f"{subject}/{type_code}",
                    type_code=type_code,
                    subject_id=subject,
                    day_id="Day01",
                    conditions=conditions,
                    features=features,
                    frame_count=16,
                    active_frame_count=16,
                    two_d_sha256=f"2d{subject_index:02x}{type_index:02x}".ljust(64, "0"),
                    three_d_sha256=f"{subject_index:02x}{type_index:02x}".ljust(64, "0"),
                    two_d_coordinate_sha256=
                        f"2c{subject_index:02x}{type_index:02x}".ljust(64, "0"),
                    three_d_coordinate_sha256=
                        f"3c{subject_index:02x}{type_index:02x}".ljust(64, "0"),
                    active_contract_sha256=
                        f"ac{subject_index:02x}{type_index:02x}".ljust(64, "0"),
                )
            )
    return observations


class BarbellSquatValidationExperimentTest(unittest.TestCase):
    def test_semantic_geometry_proxies_move_in_expected_error_direction(self) -> None:
        neutral = _frame_proxy_features(
            normalize_frame_points(base_3d_points()),
            dimensions=3,
        )
        bent = _frame_proxy_features(
            normalize_frame_points(base_3d_points(spine_bend=0.4)),
            dimensions=3,
        )
        yawed = _frame_proxy_features(
            normalize_frame_points(base_3d_points(head_yaw=True)),
            dimensions=3,
        )
        misaligned = _frame_proxy_features(
            normalize_frame_points(base_3d_points(knee_mismatch=True)),
            dimensions=3,
        )

        self.assertGreater(bent["spine"], neutral["spine"])
        self.assertGreater(yawed["head"], neutral["head"])
        self.assertGreater(misaligned["knee_foot"], neutral["knee_foot"])

    def test_unilateral_knee_error_is_not_hidden_by_bilateral_average(self) -> None:
        points = base_3d_points()
        points["Left Knee"]["x"] = -1.2

        unilateral = _frame_proxy_features(
            normalize_frame_points(points),
            dimensions=3,
        )
        neutral = _frame_proxy_features(
            normalize_frame_points(base_3d_points()),
            dimensions=3,
        )

        self.assertGreater(unilateral["knee_foot"], neutral["knee_foot"])

    def test_knee_offset_proxy_is_invariant_to_forward_torso_lean(self) -> None:
        neutral_points = base_3d_points(knee_mismatch=True)
        leaned_points = base_3d_points(knee_mismatch=True)
        for joint in ("Left Shoulder", "Right Shoulder", "Neck", "Back", "Waist"):
            leaned_points[joint]["z"] += 1.0

        neutral = _frame_proxy_features(
            normalize_frame_points(neutral_points),
            dimensions=3,
        )
        leaned = _frame_proxy_features(
            normalize_frame_points(leaned_points),
            dimensions=3,
        )

        self.assertAlmostEqual(neutral["knee_foot"], leaned["knee_foot"], places=10)

    def test_active_mask_is_common_and_temporal_features_do_not_bridge_gap(self) -> None:
        frames_2d, frames_3d = sequence_payloads(moving_feet=True)
        for view in frames_2d[3].values():
            view["active"] = "No"
        diagnostics: dict[str, int] = {}

        features = extract_predeclared_features(frames_2d, frames_3d, diagnostics)

        self.assertEqual(7, diagnostics["activeFramesIncluded"])
        self.assertEqual(1, diagnostics["inactiveFramesExcluded"])
        self.assertEqual(2, diagnostics["activeRuns"])
        self.assertTrue(features)

        frames_2d[2]["view5"]["active"] = "No"
        with self.assertRaises(SequenceDataError):
            extract_predeclared_features(frames_2d, frames_3d)

    def test_phase_proxy_rejects_negligible_rom(self) -> None:
        with self.assertRaises(SequenceDataError):
            _contiguous_bottom_indices(
                (({"knee_angle": 150.0}, {"knee_angle": 151.0}),)
            )

    def test_phase_proxy_does_not_create_rom_across_inactive_gap(self) -> None:
        with self.assertRaises(SequenceDataError):
            _contiguous_bottom_indices(
                (
                    ({"knee_angle": 150.0}, {"knee_angle": 151.0}),
                    ({"knee_angle": 100.0}, {"knee_angle": 101.0}),
                )
            )

    def test_temporal_and_view_candidates_detect_coordinate_motion(self) -> None:
        fixed_2d, fixed_3d = sequence_payloads(moving_feet=False)
        moving_2d, moving_3d = sequence_payloads(moving_feet=True)

        fixed = extract_predeclared_features(fixed_2d, fixed_3d)
        moving = extract_predeclared_features(moving_2d, moving_3d)

        self.assertEqual(11, len(fixed))
        self.assertGreater(
            moving[
                "3d.active.worst-side-camera-coordinate-foot-step-q75-per-torso-scale"
            ],
            fixed[
                "3d.active.worst-side-camera-coordinate-foot-step-q75-per-torso-scale"
            ],
        )
        self.assertGreater(
            moving[
                "3d.active.worst-side-camera-coordinate-foot-excursion-q90-per-torso-scale"
            ],
            fixed[
                "3d.active.worst-side-camera-coordinate-foot-excursion-q90-per-torso-scale"
            ],
        )
        self.assertGreater(
            moving[
                "2d.active.views.worst-side-camera-coordinate-foot-step-q75-per-torso-scale-median"
            ],
            fixed[
                "2d.active.views.worst-side-camera-coordinate-foot-step-q75-per-torso-scale-median"
            ],
        )

    def test_training_only_selection_is_not_reselected_on_validation(self) -> None:
        training = synthetic_observations(["Z1", "Z2", "Z3", "Z4"])
        validation = synthetic_observations(["Z5", "Z6"], validation_flip=True)

        reports = evaluate_criteria(training, validation)

        self.assertEqual(4, len(reports))
        for report in reports:
            self.assertEqual(
                CANDIDATE_FEATURES[report["condition"]][0],
                report["selectedFeature"],
            )
            self.assertEqual(1.0, report["trainingCandidates"][0]["trainingLoso"]["balancedAccuracy"])
            self.assertLess(report["lockedValidation"]["balancedAccuracy"], 0.5)
            self.assertEqual("INSUFFICIENT_ROBUST_REPLICATION", report["proxyResearchState"])

    def test_hamming_one_paired_contrast_counts_subjects_not_frames(self) -> None:
        observations = synthetic_observations(["Z1", "Z2", "Z3"])
        feature = CANDIDATE_FEATURES[CONDITIONS[0]][0]

        report = paired_sign_report(observations, 0, feature)

        self.assertEqual(24, report["matchedSubjectDayConditionCells"])
        self.assertEqual(3, report["matchedSubjects"])
        self.assertEqual(1.0, report["semanticTrueLowerConsistency"])

    def test_split_internal_coordinate_duplicate_is_rejected(self) -> None:
        observations = synthetic_observations(["Z1", "Z2"])
        source = observations[0]
        observations[1] = replace(
            observations[1],
            two_d_coordinate_sha256=source.two_d_coordinate_sha256,
        )

        with self.assertRaises(SquatExperimentError):
            _validate_internal_sequence_uniqueness(observations, "TRAINING")

    def test_source_output_collision_is_rejected_and_atomic_write_leaves_no_temp(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "source"
            root.mkdir()
            with self.assertRaises(SquatExperimentError):
                validate_output_path(root / "report.json", [root])

            output = Path(directory) / "reports" / "result.json"
            resolved = validate_output_path(output, [root])
            atomic_write_json(resolved, {"value": "결과"})

            self.assertEqual({"value": "결과"}, json.loads(output.read_text(encoding="utf-8")))
            self.assertEqual([], list(output.parent.glob("*.tmp")))
            with self.assertRaises(SquatExperimentError):
                atomic_write_json(resolved, {"value": "덮어쓰기"})

            immutable_output = Path(directory) / "reports" / "artifact.json"
            old_report = {"schemaVersion": 1, "value": "old"}
            old_report["reportFingerprintSha256"] = _canonical_sha256(old_report)
            atomic_write_json(immutable_output, old_report)
            new_report = {"schemaVersion": 2, "value": "new"}
            new_report["reportFingerprintSha256"] = _canonical_sha256(new_report)
            atomic_write_json(
                immutable_output,
                new_report,
                overwrite=True,
                expected_old_fingerprint=old_report["reportFingerprintSha256"],
            )
            self.assertEqual(
                new_report,
                json.loads(immutable_output.read_text(encoding="utf-8")),
            )

    def test_report_fingerprint_detects_tampering(self) -> None:
        report = {"schemaVersion": 2, "value": "원본"}
        report["reportFingerprintSha256"] = _canonical_sha256(report)

        self.assertTrue(verify_report_fingerprint(report))
        report["value"] = "변조"
        self.assertFalse(verify_report_fingerprint(report))

    def test_two_d_coordinate_hash_excludes_active_and_image_metadata(self) -> None:
        frames_2d, _ = sequence_payloads(moving_feet=False)
        original = _canonical_sha256(_two_d_coordinate_payload(frames_2d))

        frames_2d[0]["view1"]["active"] = "No"
        frames_2d[0]["view1"]["img_key"] = "different-0000001.jpg"

        self.assertEqual(original, _canonical_sha256(_two_d_coordinate_payload(frames_2d)))

    def test_exact_factorial_truth_table_is_complete_and_unique(self) -> None:
        vectors = {_expected_condition_tuple(code) for code in EXPECTED_BITS}

        self.assertEqual(16, len(EXPECTED_BITS))
        self.assertEqual(16, len(vectors))
        self.assertEqual((True, True, True, True), _expected_condition_tuple("313"))
        self.assertEqual((False, False, False, False), _expected_condition_tuple("328"))

    def test_committed_catalog_truth_coverage_matches_independent_pin(self) -> None:
        provenance = _verified_catalog_provenance()

        self.assertEqual(
            "1f6ab0ea0981c6d1ef693ace7e72608a2e9af363b4b52f789a1749f92dae9cb5",
            provenance["barbellSquatCoverageSha256"],
        )

    def test_committed_report_matches_current_schema_protocol_and_implementation(self) -> None:
        tool_path = Path(__file__).resolve().with_name("barbell_squat_validation_experiment.py")
        report_path = Path(__file__).resolve().parent.parent / "docs" / (
            "barbell-squat-coordinate-experiment.json"
        )
        report = json.loads(report_path.read_text(encoding="utf-8"))

        self.assertTrue(verify_report_fingerprint(report))
        self.assertEqual(SCHEMA_VERSION, report["schemaVersion"])
        self.assertEqual(
            _canonical_sha256(PROTOCOL_CONTRACT),
            report["protocolArtifactSha256"],
        )
        self.assertEqual(
            _canonical_lf_text_sha256(tool_path),
            report["implementationProvenance"]["scriptCanonicalLfTextSha256"],
        )
        self.assertEqual(
            PROTOCOL_CONTRACT["implementationTextHashNormalization"],
            report["implementationProvenance"]["textHashNormalization"],
        )

        ledger_path = report_path.with_name("barbell-squat-holdout-ledger.json")
        ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
        validation = report["inventory"]["validation"]
        self.assertEqual(report["experimentFamilyId"], ledger["experimentFamilyId"])
        self.assertEqual(report["protocolArtifactSha256"], ledger["protocolArtifactSha256"])
        self.assertEqual(report["reportFingerprintSha256"], ledger["reportFingerprintSha256"])
        self.assertEqual(
            validation["coordinateFingerprintSha256"],
            ledger["coordinateFingerprintSha256"],
        )
        self.assertEqual(
            validation["activeContractManifestSha256"],
            ledger["activeContractManifestSha256"],
        )

    def test_repository_text_hash_is_portable_across_line_endings(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = []
            for index, newline in enumerate(("\n", "\r\n", "\r")):
                path = root / f"source-{index}.txt"
                path.write_bytes(f"alpha{newline}beta{newline}".encode("utf-8"))
                paths.append(path)

            self.assertEqual(
                1,
                len({_canonical_lf_text_sha256(path) for path in paths}),
            )


if __name__ == "__main__":
    unittest.main()
