import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from analyze_pose_coordinate_criteria import (
    ARTIFACT_KIND,
    DECISION_USE,
    AIHUB_JOINTS,
    SequenceDataError,
    analyze,
    extract_frame_features,
    extract_sequence_features,
    extract_subject_id,
    main,
)


def base_points(knee_straight: bool = True) -> dict[str, dict[str, float]]:
    coordinates = {
        "Nose": (0.0, 3.0, 0.5),
        "Left Eye": (-0.2, 3.1, 0.45),
        "Right Eye": (0.2, 3.1, 0.45),
        "Left Ear": (-0.45, 3.0, 0.0),
        "Right Ear": (0.45, 3.0, 0.0),
        "Left Shoulder": (-1.0, 2.0, 0.0),
        "Right Shoulder": (1.0, 2.0, 0.0),
        "Left Elbow": (-1.5, 1.0, 0.0),
        "Right Elbow": (1.5, 1.0, 0.0),
        "Left Wrist": (-1.5, 0.0, 0.0),
        "Right Wrist": (1.5, 0.0, 0.0),
        "Left Hip": (-0.8, 0.0, 0.0),
        "Right Hip": (0.8, 0.0, 0.0),
        "Left Knee": (-0.8, -2.0, 0.0),
        "Right Knee": (0.8, -2.0, 0.0),
        "Left Ankle": (-0.8 if knee_straight else 0.2, -4.0, 0.0),
        "Right Ankle": (0.8 if knee_straight else -0.2, -4.0, 0.0),
        "Neck": (0.0, 2.5, 0.0),
        "Left Palm": (-1.5, -0.2, 0.0),
        "Right Palm": (1.5, -0.2, 0.0),
        "Back": (0.0, 1.5, 0.0),
        "Waist": (0.0, 0.5, 0.0),
        "Left Foot": (-0.8 if knee_straight else 0.2, -4.2, 0.7),
        "Right Foot": (0.8 if knee_straight else -0.2, -4.2, 0.7),
    }
    assert set(coordinates) == set(AIHUB_JOINTS)
    return {
        joint: {"x": value[0], "y": value[1], "z": value[2]}
        for joint, value in coordinates.items()
    }


def transformed_points(
    points: dict[str, dict[str, float]],
    scale: float,
    translation: tuple[float, float, float],
) -> dict[str, dict[str, float]]:
    return {
        joint: {
            axis: value[axis] * scale + translation[index]
            for index, axis in enumerate(("x", "y", "z"))
        }
        for joint, value in points.items()
    }


def two_d_payload(
    code: str,
    exercise: str,
    conditions: list[tuple[str, bool]],
    subject: str,
    frame_count: int = 2,
    day: str = "Day01",
) -> dict:
    frames = [
        {
            "view1": {
                "pts": {},
                "active": "Yes",
                "img_key": f"{day}/1/A/{code}-pose-{subject}_A/{code}-{index:07d}.jpg",
            }
        }
        for index in range(frame_count)
    ]
    return {
        "frames": frames,
        "type": code,
        "type_info": {
            "key": code,
            "type": "synthetic",
            "pose": "synthetic",
            "exercise": exercise,
            "conditions": [
                {"condition": name, "value": value} for name, value in conditions
            ],
            "description": "synthetic fixture",
        },
    }


def write_pair(
    root: Path,
    stem: str,
    code: str,
    exercise: str,
    conditions: list[tuple[str, bool]],
    subject: str,
    knee_straight: bool,
    frame_count: int = 2,
    day: str = "Day01",
) -> None:
    two_d = root / f"{stem}-{code}.json"
    three_d = root / f"{stem}-{code}-3d.json"
    two_d.write_text(
        json.dumps(
            two_d_payload(code, exercise, conditions, subject, frame_count, day),
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    points = base_points(knee_straight=knee_straight)
    three_d.write_text(
        json.dumps({"frames": [{"pts": points} for _ in range(frame_count)]}),
        encoding="utf-8",
    )


class PoseCoordinateCriterionResearchTest(unittest.TestCase):
    def test_extracts_one_global_subject_from_all_img_keys(self) -> None:
        payload = two_d_payload(
            "001",
            "Synthetic",
            [("alignment", True)],
            "Z42",
            frame_count=3,
        )
        for frame in payload["frames"]:
            frame["view2"] = {
                "img_key": "Day01/1/B/001-pose-Z42_B/001-0000001.jpg",
                "pts": {},
            }

        self.assertEqual("Z42", extract_subject_id(payload))

        payload["frames"][0]["view2"]["img_key"] = "Day01/1/B/001-pose-Z7_B/x.jpg"
        with self.assertRaises(SequenceDataError):
            extract_subject_id(payload)

    def test_pelvis_torso_normalization_is_translation_and_scale_invariant(self) -> None:
        original = base_points(knee_straight=False)
        transformed = transformed_points(original, scale=7.5, translation=(90.0, -31.0, 8.0))
        original_features, original_invalid = extract_sequence_features(
            [{"pts": original}, {"pts": original}],
        )
        transformed_features, transformed_invalid = extract_sequence_features(
            [{"pts": transformed}, {"pts": transformed}],
        )

        self.assertEqual(0, original_invalid)
        self.assertEqual(0, transformed_invalid)
        self.assertEqual(original_features.keys(), transformed_features.keys())
        for feature in original_features:
            self.assertAlmostEqual(
                original_features[feature],
                transformed_features[feature],
                places=10,
                msg=feature,
            )

    def test_atan2_angle_is_stable_at_zero_and_one_eighty_degrees(self) -> None:
        points = base_points(knee_straight=True)
        straight = extract_frame_features(points)
        self.assertAlmostEqual(180.0, straight["left_knee_angle_deg"], places=12)

        folded = base_points(knee_straight=True)
        folded["Left Ankle"] = dict(folded["Left Hip"])
        zero = extract_frame_features(folded)
        self.assertAlmostEqual(0.0, zero["left_knee_angle_deg"], places=12)

    def test_default_quarantine_pair_join_and_quality_accounting(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_pair(
                root,
                "D01-1",
                "001",
                "Synthetic",
                [("alignment", True)],
                "Z1",
                knee_straight=True,
            )
            write_pair(
                root,
                "D01-2",
                "062",
                "Synthetic",
                [("alignment", True)],
                "Z2",
                knee_straight=True,
            )
            unpaired = root / "D01-3-002.json"
            unpaired.write_text(
                json.dumps(
                    two_d_payload(
                        "002",
                        "Synthetic",
                        [("alignment", False)],
                        "Z3",
                    ),
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            (root / "malformed.json").write_text("{not-json", encoding="utf-8")

            report = analyze(root)

        inventory = report["inventory"]
        self.assertEqual(ARTIFACT_KIND, report["artifactKind"])
        self.assertEqual(DECISION_USE, report["decisionUse"])
        self.assertEqual(4, inventory["twoDDiscovered"])
        self.assertEqual(1, inventory["malformedTwoDMetadata"])
        self.assertEqual(1, inventory["quarantinedByTypeCode"])
        self.assertEqual(1, inventory["unpairedTwoD"])
        self.assertEqual(1, inventory["analyzed"])
        self.assertFalse(report["supportUnit"]["framesAreIndependentN"])
        self.assertFalse(report["supportUnit"]["viewsAreIndependentN"])

    def test_report_is_portable_across_absolute_source_roots(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            first_root = Path(directory) / "first" / "labeling"
            second_root = Path(directory) / "second" / "labeling"
            first_root.mkdir(parents=True)
            second_root.mkdir(parents=True)
            for root in (first_root, second_root):
                write_pair(
                    root,
                    "D01-1",
                    "001",
                    "Synthetic",
                    [("alignment", True)],
                    "Z1",
                    knee_straight=True,
                )

            first = analyze(first_root)
            second = analyze(second_root)

        self.assertEqual(first, second)

    def test_cli_rejects_output_inside_source_before_analysis(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()
            write_pair(
                source,
                "D01-1",
                "001",
                "Synthetic",
                [("alignment", True)],
                "Z1",
                knee_straight=True,
            )
            input_path = source / "D01-1-001.json"
            original_input = input_path.read_bytes()
            report_path = source / "candidate-report.json"

            for output_path in (report_path, input_path):
                errors = io.StringIO()
                with contextlib.redirect_stderr(errors):
                    exit_code = main([str(source), "--output", str(output_path)])
                self.assertEqual(2, exit_code)
                self.assertIn("Output must be outside source_root", errors.getvalue())

            self.assertFalse(report_path.exists())
            self.assertEqual(original_input, input_path.read_bytes())

    def test_cli_atomically_writes_output_outside_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            source.mkdir()
            write_pair(
                source,
                "D01-1",
                "001",
                "Synthetic",
                [("alignment", True)],
                "Z1",
                knee_straight=True,
            )
            output_path = root / "reports" / "candidate-report.json"

            self.assertEqual(0, main([str(source), "--output", str(output_path)]))

            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(1, report["inventory"]["analyzed"])
            self.assertEqual([], list(output_path.parent.glob(f".{output_path.name}.*.tmp")))

    def test_filter_audits_all_metadata_and_quarantines_type_identity_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_pair(
                root,
                "A-first",
                "001",
                "Not Selected",
                [("alignment", False)],
                "Z1",
                knee_straight=False,
            )
            write_pair(
                root,
                "Z-later",
                "001",
                "Selected",
                [("alignment", True)],
                "Z2",
                knee_straight=True,
            )

            report = analyze(root, exercise_filters=["Selected"])

        inventory = report["inventory"]
        self.assertEqual(2, inventory["validTwoDMetadata"])
        self.assertEqual(0, inventory["twoDMetadataNotDecoded"])
        self.assertEqual(1, inventory["filteredOutByExercise"])
        self.assertEqual(1, inventory["selectedByExercise"])
        self.assertEqual(1, inventory["metadataConflictSequences"])
        self.assertEqual(0, inventory["analyzed"])

    def test_cap_round_robins_subject_day_cells_instead_of_earliest_sequences(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index in range(1, 5):
                write_pair(
                    root,
                    f"A-early-{index}",
                    "001",
                    "Synthetic",
                    [("alignment", True)],
                    "Z1",
                    knee_straight=True,
                    day="Day01",
                )
            for index in range(1, 5):
                write_pair(
                    root,
                    f"Z-late-{index}",
                    "001",
                    "Synthetic",
                    [("alignment", True)],
                    "Z2",
                    knee_straight=True,
                    day="Day02",
                )

            report = analyze(root, max_sequences=2)
            repeated = analyze(root, max_sequences=2)

        self.assertEqual(report, repeated)
        self.assertEqual(2, report["inventory"]["attempted"])
        self.assertEqual(6, report["inventory"]["limitedOut"])
        self.assertEqual(2, report["exercises"][0]["subjectSupport"])

    def test_unpaired_subject_effect_is_descriptive_and_direction_ineligible(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_pair(
                root,
                "D01-1",
                "001",
                "Synthetic",
                [("alignment", True)],
                "Z1",
                knee_straight=True,
            )
            write_pair(
                root,
                "D01-2",
                "002",
                "Synthetic",
                [("alignment", False)],
                "Z2",
                knee_straight=False,
            )

            report = analyze(root)

        criterion = report["exercises"][0]["conditionSchemas"][0]["criteria"][0]
        self.assertFalse(criterion["pairedCandidateContrastAvailable"])
        knee_effect = next(
            effect
            for effect in criterion["featureEffects"]
            if effect["feature"] == "left_knee_angle_deg__median"
        )
        self.assertFalse(knee_effect["candidateDirectionEligible"])
        self.assertIsNone(knee_effect["robustMedianEffectAcrossMatchedStrata"])
        self.assertEqual(
            "INELIGIBLE_NO_PAIRED_SUBJECT_SUPPORT",
            knee_effect["direction"],
        )
        stratum = knee_effect["effectsByStratum"][0]
        self.assertFalse(stratum["candidateEligible"])
        self.assertIsNone(stratum["robustMedianEffectTrueMinusFalse"])
        self.assertIsNotNone(stratum["descriptiveUnpairedSubjectMedianDifference"])
        self.assertEqual("NO_PAIRED_SUBJECT_SUPPORT", stratum["ineligibilityReason"])

    def test_hamming_one_strata_control_other_conditions_and_count_subjects(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            combinations = [
                ("001", False, False),
                ("002", True, False),
                ("003", False, True),
                ("004", True, True),
            ]
            sequence_index = 1
            for subject in ("Z1", "Z2"):
                for code, alignment, depth in combinations:
                    write_pair(
                        root,
                        f"D01-{sequence_index}",
                        code,
                        "Synthetic Squat",
                        [("alignment", alignment), ("depth", depth)],
                        subject,
                        knee_straight=alignment,
                    )
                    sequence_index += 1

            first = analyze(root, exercise_filters=["Synthetic Squat"])
            second = analyze(root, exercise_filters=["Synthetic Squat"])
            capped = analyze(
                root,
                exercise_filters=["Synthetic Squat"],
                max_sequences=4,
            )

        self.assertEqual(first, second)
        schema = first["exercises"][0]["conditionSchemas"][0]
        alignment = next(
            criterion for criterion in schema["criteria"] if criterion["criterion"] == "alignment"
        )
        self.assertEqual(2, alignment["hammingOneStrata"])
        self.assertEqual(2, alignment["hammingOneTypeVectorPairs"])
        self.assertEqual({"sequences": 4, "subjects": 2}, alignment["trueSupport"])
        self.assertEqual({"sequences": 4, "subjects": 2}, alignment["falseSupport"])
        self.assertEqual(2, alignment["pairedSubjects"])
        self.assertEqual(
            [False, True],
            [stratum["otherConditions"][0]["value"] for stratum in alignment["strata"]],
        )
        knee_effect = next(
            effect
            for effect in alignment["featureEffects"]
            if effect["feature"] == "left_knee_angle_deg__median"
        )
        self.assertGreater(knee_effect["robustMedianEffectAcrossMatchedStrata"], 0.0)
        self.assertEqual("TRUE_HIGHER", knee_effect["direction"])
        self.assertEqual(1.0, knee_effect["directionConsistencyAcrossNonzeroStrata"])
        self.assertEqual(4, capped["inventory"]["attempted"])
        self.assertEqual(4, capped["inventory"]["limitedOut"])
        self.assertEqual(
            ["001", "002", "003", "004"],
            capped["exercises"][0]["typeCodes"],
        )

    def test_empty_and_frame_mismatch_are_explicit_not_silent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            empty_payload = two_d_payload(
                "001",
                "Synthetic",
                [("alignment", True)],
                "Z1",
                frame_count=0,
            )
            (root / "D01-1-001.json").write_text(
                json.dumps(empty_payload, ensure_ascii=False),
                encoding="utf-8",
            )
            (root / "D01-1-001-3d.json").write_text(
                json.dumps({"frames": [{"pts": base_points()}]}),
                encoding="utf-8",
            )
            two_d = two_d_payload(
                "002",
                "Synthetic",
                [("alignment", False)],
                "Z2",
                frame_count=2,
            )
            (root / "D01-2-002.json").write_text(
                json.dumps(two_d, ensure_ascii=False),
                encoding="utf-8",
            )
            (root / "D01-2-002-3d.json").write_text(
                json.dumps({"frames": [{"pts": base_points()}]}),
                encoding="utf-8",
            )
            (root / "D01-3-003.json").write_text(
                json.dumps(
                    two_d_payload(
                        "003",
                        "Synthetic",
                        [("alignment", True)],
                        "Z3",
                    ),
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            (root / "D01-3-003-3d.json").write_text("{not-json", encoding="utf-8")
            (root / "D01-4-004.json").write_text(
                json.dumps(
                    two_d_payload(
                        "004",
                        "Synthetic",
                        [("alignment", False)],
                        "Z4",
                        frame_count=3,
                    ),
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            (root / "D01-4-004-3d.json").write_text(
                json.dumps(
                    {
                        "frames": [
                            {"pts": base_points()},
                            {"pts": {}},
                            {"pts": base_points()},
                        ]
                    }
                ),
                encoding="utf-8",
            )

            report = analyze(root)

        self.assertEqual(1, report["inventory"]["emptyTwoDFrames"])
        self.assertEqual(1, report["inventory"]["frameCountMismatch"])
        self.assertEqual(1, report["inventory"]["malformedThreeDPayload"])
        self.assertEqual(1, report["inventory"]["invalidThreeDFrames"])
        self.assertEqual(1, report["inventory"]["partiallyValidThreeDSequences"])
        self.assertEqual(0, report["inventory"]["analyzed"])


if __name__ == "__main__":
    unittest.main()
