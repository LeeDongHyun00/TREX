import copy
import contextlib
import hashlib
import io
import json
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock
from types import SimpleNamespace

if __package__:
    from .good_morning_knee_stability_experiment import (
        ARTIFACT_KIND,
        CONTINUATION_POLICY,
        DECISION_USE,
        EXPECTED_LABEL_COUNTS,
        EXPECTED_SEQUENCE_COUNT,
        EXPECTED_SUBJECT_COUNT,
        EXPECTED_TRUTH_VECTORS,
        EXPECTED_TYPE_COUNTS,
        COMPONENT_METRIC_CONTRACT,
        FileIdentity,
        METRIC_DIRECTION_CONTRACT,
        METRIC_IDS,
        PROTOCOL_CONTRACT,
        SIDES,
        Decision,
        DiagnosticRule,
        GoodMorningExperimentError,
        SequenceObservation,
        SideFeatures,
        _canonical_lf_text_sha256,
        _canonical_sha256,
        _classification_metrics,
        _absolute_training_root,
        _iter_training_files,
        _parser,
        _reject_validation_path_before_probe,
        _shared_dependency_provenance,
        _side_features,
        _snapshot_json_once,
        atomic_write_json,
        check_committed_report,
        fit_component_rule,
        run_outer_cv,
        verify_report_fingerprint,
    )
else:
    from good_morning_knee_stability_experiment import (
        ARTIFACT_KIND,
        CONTINUATION_POLICY,
        DECISION_USE,
        EXPECTED_LABEL_COUNTS,
        EXPECTED_SEQUENCE_COUNT,
        EXPECTED_SUBJECT_COUNT,
        EXPECTED_TRUTH_VECTORS,
        EXPECTED_TYPE_COUNTS,
        COMPONENT_METRIC_CONTRACT,
        FileIdentity,
        METRIC_DIRECTION_CONTRACT,
        METRIC_IDS,
        PROTOCOL_CONTRACT,
        SIDES,
        Decision,
        DiagnosticRule,
        GoodMorningExperimentError,
        SequenceObservation,
        SideFeatures,
        _canonical_lf_text_sha256,
        _canonical_sha256,
        _classification_metrics,
        _absolute_training_root,
        _iter_training_files,
        _parser,
        _reject_validation_path_before_probe,
        _shared_dependency_provenance,
        _side_features,
        _snapshot_json_once,
        atomic_write_json,
        check_committed_report,
        fit_component_rule,
        run_outer_cv,
        verify_report_fingerprint,
    )


def features(*, median: float, robust: float, delta: float) -> SideFeatures:
    return SideFeatures(median, robust, delta)


def observation(
    subject: str,
    ordinal: int,
    target: bool,
    *,
    left: SideFeatures | None = None,
    right: SideFeatures | None = None,
) -> SequenceObservation:
    digest = f"{ordinal + 1:064x}"
    default = features(
        median=40.0 if target else 5.0,
        robust=5.0 if target else 30.0,
        delta=1.0 if target else 8.0,
    )
    return SequenceObservation(
        sequence_id=f"Day01/{subject}/{ordinal}",
        type_code="185" if target else "186",
        subject_id=subject,
        day_id="Day01",
        target=target,
        left=left or default,
        right=right or default,
        two_d_sha256=digest,
        three_d_sha256=f"{ordinal + 1000:064x}",
    )


def synthetic_observations() -> list[SequenceObservation]:
    rows = []
    ordinal = 0
    for subject_index in range(4):
        subject = f"Z{subject_index + 1}"
        for target in (False, False, True, True):
            robust = 30.0 + subject_index if not target else 5.0 + subject_index
            median = 40.0 + subject_index if target else 5.0 + subject_index
            delta = 8.0 + subject_index if not target else 1.0 + subject_index / 10.0
            side = features(median=median, robust=robust, delta=delta)
            rows.append(
                observation(subject, ordinal, target, left=side, right=side)
            )
            ordinal += 1
    return rows


class GoodMorningKneeStabilityExperimentTest(unittest.TestCase):
    def test_protocol_is_training_only_ordinal_side_preserving_and_zero_authority(self) -> None:
        self.assertIn("TRAINING_ONLY", ARTIFACT_KIND)
        self.assertIn("NO_RUNTIME_THRESHOLD", DECISION_USE)
        self.assertEqual(
            "FORBIDDEN_NO_STAT_NO_READ_NO_REUSE",
            PROTOCOL_CONTRACT["officialValidationRole"],
        )
        self.assertEqual(
            "LEFT_AND_RIGHT_RETAINED_INDEPENDENTLY_NO_BILATERAL_COLLAPSE",
            PROTOCOL_CONTRACT["sideContract"],
        )
        self.assertEqual(list(METRIC_IDS), PROTOCOL_CONTRACT["featureContract"]["predeclaredMetricIds"])
        self.assertEqual(
            {
                "MEDIAN_FLEXION_DEGREES": "HIGH_IS_TRUE",
                "ROBUST_P90_P10_RANGE_DEGREES": "LOW_IS_TRUE",
                "MEDIAN_ABSOLUTE_ORDINAL_DELTA_DEGREES": "LOW_IS_TRUE",
            },
            METRIC_DIRECTION_CONTRACT,
        )
        self.assertEqual(
            METRIC_DIRECTION_CONTRACT,
            PROTOCOL_CONTRACT["featureContract"]["metricDirectionContract"],
        )
        self.assertEqual(
            {key: list(value) for key, value in COMPONENT_METRIC_CONTRACT.items()},
            PROTOCOL_CONTRACT["featureContract"]["compoundComponentMetricContract"],
        )
        self.assertEqual(
            "FLEXION_AND_STABILITY_UNKNOWN_IF_EITHER_COMPONENT_UNKNOWN",
            PROTOCOL_CONTRACT["featureContract"]["compoundDecision"],
        )
        self.assertEqual(
            "FORBIDDEN_FIXED_BY_METRIC_DIRECTION_CONTRACT",
            PROTOCOL_CONTRACT["ruleFamily"]["directionSelection"],
        )
        self.assertEqual("ABSENT", PROTOCOL_CONTRACT["phaseGold"])
        self.assertEqual("ABSENT_NO_GOOD_MORNING_PIXELS", PROTOCOL_CONTRACT["mediaPipeBridge"])
        self.assertTrue(all(value == 0 for value in PROTOCOL_CONTRACT["authority"].values()))

    def test_exact_inventory_contract_is_fixed(self) -> None:
        self.assertEqual(348, EXPECTED_SEQUENCE_COUNT)
        self.assertEqual(41, EXPECTED_SUBJECT_COUNT)
        self.assertEqual(8, len(EXPECTED_TYPE_COUNTS))
        self.assertEqual(348, sum(EXPECTED_TYPE_COUNTS.values()))
        self.assertEqual({"FALSE": 174, "TRUE": 174}, EXPECTED_LABEL_COUNTS)
        self.assertEqual(
            {"111", "011", "101", "110", "001", "100", "010", "000"},
            set(EXPECTED_TRUTH_VECTORS.values()),
        )

    def test_cli_has_no_validation_or_time_view_phase_arguments(self) -> None:
        for forbidden in (
            "--validation-root",
            "--fps",
            "--timestamp-ms",
            "--view",
            "--phase",
            "--overwrite",
        ):
            with contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    _parser().parse_args(["1.Training", forbidden, "x"])

    def test_validation_path_is_rejected_before_any_filesystem_probe(self) -> None:
        path = Path("data") / "2.Validation" / "labels"
        with mock.patch.object(Path, "lstat", side_effect=AssertionError("must not stat")):
            with self.assertRaisesRegex(GoodMorningExperimentError, "before any path stat"):
                _absolute_training_root(path)

    def test_validation_named_descendant_is_rejected_before_entry_stat(self) -> None:
        class ForbiddenEntry:
            name = "2.Validation"
            path = str(Path("1.Training") / name)

            def stat(self, *, follow_symlinks: bool) -> None:
                raise AssertionError("Validation descendant must not be statted")

        class ScanResult:
            def __enter__(self) -> list[ForbiddenEntry]:
                return [ForbiddenEntry()]

            def __exit__(self, *_args: object) -> None:
                return None

        module = sys.modules[_iter_training_files.__module__]
        with mock.patch.object(module.os, "scandir", return_value=ScanResult()):
            with self.assertRaisesRegex(GoodMorningExperimentError, "before stat"):
                list(_iter_training_files(Path("1.Training")))

    def test_parent_symlink_prefix_is_rejected_before_training_root_read(self) -> None:
        real_lstat = Path.lstat

        def prefix_lstat(path: Path) -> object:
            if path.name == "linked-parent":
                return SimpleNamespace(st_mode=stat.S_IFLNK, st_file_attributes=0)
            return real_lstat(path)

        root = Path("linked-parent") / "1.Training"
        with mock.patch.object(Path, "lstat", autospec=True, side_effect=prefix_lstat):
            with self.assertRaisesRegex(GoodMorningExperimentError, "path prefix"):
                _absolute_training_root(root)

    def test_side_feature_contract_uses_all_sixteen_ordinals(self) -> None:
        frames = []
        for ordinal in range(16):
            # A slightly changing, nondegenerate included angle for both sides.
            x = 0.25 + ordinal / 100.0
            frames.append(
                {
                    "pts": {
                        "Left Hip": {"x": 0.0, "y": 1.0, "z": 0.0},
                        "Left Knee": {"x": 0.0, "y": 0.0, "z": 0.0},
                        "Left Ankle": {"x": x, "y": -1.0, "z": 0.0},
                        "Right Hip": {"x": 0.0, "y": 1.0, "z": 0.0},
                        "Right Knee": {"x": 0.0, "y": 0.0, "z": 0.0},
                        "Right Ankle": {"x": -x, "y": -1.0, "z": 0.0},
                    }
                }
            )
        left = _side_features(frames, "LEFT")
        right = _side_features(frames, "RIGHT")
        self.assertIsNone(left.unknown_reason)
        self.assertIsNone(right.unknown_reason)
        self.assertEqual(left.payload(), right.payload())
        self.assertGreater(left.robust_range_degrees or 0.0, 0.0)
        self.assertGreater(left.median_absolute_ordinal_delta_degrees or 0.0, 0.0)

    def test_side_feature_known_answer_uses_true_3d_not_xy_projection(self) -> None:
        z = 3.0 ** 0.5 / 2.0
        base_points = {
            "Left Hip": {"x": 0.0, "y": 1.0, "z": 0.0},
            "Left Knee": {"x": 0.0, "y": 0.0, "z": 0.0},
            "Left Ankle": {"x": 0.0, "y": -0.5, "z": z},
        }
        frames_3d = [{"pts": copy.deepcopy(base_points)} for _ in range(16)]
        result_3d = _side_features(frames_3d, "LEFT")
        self.assertAlmostEqual(60.0, result_3d.median_flexion_degrees or -1.0, places=8)
        self.assertEqual(0.0, result_3d.robust_range_degrees)
        self.assertEqual(0.0, result_3d.median_absolute_ordinal_delta_degrees)

        flattened = copy.deepcopy(frames_3d)
        for frame in flattened:
            frame["pts"]["Left Ankle"]["z"] = 0.0
        result_xy = _side_features(flattened, "LEFT")
        self.assertAlmostEqual(0.0, result_xy.median_flexion_degrees or 0.0, places=8)
        self.assertNotEqual(result_3d.median_flexion_degrees, result_xy.median_flexion_degrees)

    def test_snapshot_parses_and_hashes_the_same_single_read_bytes(self) -> None:
        content = b'{"value":1}'
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            path.write_bytes(content)
            identity = FileIdentity.from_stat(path.lstat())
            snapshot = _snapshot_json_once(path, identity)
            path.write_bytes(b'{"value":2}')
            self.assertEqual({"value": 1}, snapshot.payload)
            self.assertEqual(hashlib.sha256(content).hexdigest(), snapshot.content_sha256)
            self.assertEqual(len(content), snapshot.byte_count)

    def test_replacement_after_enumeration_is_rejected_even_at_same_size(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "input.json"
            replacement = root / "replacement.json"
            path.write_bytes(b'{"value":1}')
            identity = FileIdentity.from_stat(path.lstat())
            replacement.write_bytes(b'{"value":2}')
            os.replace(replacement, path)
            with self.assertRaisesRegex(GoodMorningExperimentError, "identity changed"):
                _snapshot_json_once(path, identity)

    def test_symlink_redirected_open_after_enumeration_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "input.json"
            target = root / "target.json"
            path.write_bytes(b'{"value":1}')
            target.write_bytes(b'{"value":2}')
            identity = FileIdentity.from_stat(path.lstat())
            real_open = os.open
            module = sys.modules[_snapshot_json_once.__module__]

            def redirected_open(_path: Path, flags: int) -> int:
                return real_open(target, flags & ~getattr(os, "O_NOFOLLOW", 0))

            with mock.patch.object(module.os, "open", side_effect=redirected_open):
                with self.assertRaisesRegex(GoodMorningExperimentError, "identity changed"):
                    _snapshot_json_once(path, identity)

    def test_nonfinite_feature_is_unknown_and_counts_as_miss(self) -> None:
        rows = [
            observation("Z1", 0, False),
            observation(
                "Z1",
                1,
                True,
                left=SideFeatures(None, None, None, "NONFINITE"),
            ),
        ]
        decisions = [Decision(False), Decision(None, "NONFINITE")]
        metrics = _classification_metrics(rows, decisions)
        self.assertEqual(0.5, metrics["predictionCoverage"])
        self.assertEqual(0.5, metrics["sequenceAccuracyUnknownAsMiss"])
        self.assertEqual(0.5, metrics["subjectMacroBalancedAccuracyUnknownAsMiss"])
        self.assertEqual({"NONFINITE": 1}, metrics["unknownReasons"])

    def test_fit_and_loso_are_subject_grouped_deterministic_and_side_preserving(self) -> None:
        rows = synthetic_observations()
        flexion, flexion_inner = fit_component_rule(rows, "LEFT", "FLEXION")
        stability, stability_inner = fit_component_rule(rows, "LEFT", "STABILITY")
        self.assertIsNotNone(flexion)
        self.assertIsNotNone(stability)
        assert flexion is not None and flexion_inner is not None
        assert stability is not None and stability_inner is not None
        self.assertEqual("MEDIAN_FLEXION_DEGREES", flexion.metric_id)
        self.assertEqual("HIGH_IS_TRUE", flexion.direction)
        self.assertIn(stability.metric_id, COMPONENT_METRIC_CONTRACT["STABILITY"])
        self.assertEqual("LOW_IS_TRUE", stability.direction)
        first = run_outer_cv(rows)
        second = run_outer_cv(list(reversed(rows)))
        self.assertEqual(first, second)
        self.assertEqual(4, first["outerFoldAudit"]["foldCount"])
        self.assertEqual(3, first["outerFoldAudit"]["trainingSubjectsPerFold"])
        self.assertEqual(0, first["outerFoldAudit"]["maximumSubjectOverlapCount"])
        self.assertEqual(set(SIDES), set(first["perSide"]))
        for side in SIDES:
            self.assertEqual(
                1.0,
                first["perSide"][side]["compoundOuterMetrics"][
                    "subjectMacroBalancedAccuracyUnknownAsMiss"
                ],
            )

    def test_one_good_component_cannot_pass_compound_continuation(self) -> None:
        rows = []
        ordinal = 0
        for subject_index in range(4):
            subject = f"Z{subject_index + 1}"
            for target in (False, False, True, True):
                # Flexion is perfectly separable; both stability metrics are constant.
                side = features(
                    median=40.0 if target else 5.0,
                    robust=10.0,
                    delta=2.0,
                )
                rows.append(observation(subject, ordinal, target, left=side, right=side))
                ordinal += 1
        result = run_outer_cv(rows)
        self.assertEqual("REJECTED", result["researchContinuationGate"]["continuationStatus"])
        for side in SIDES:
            self.assertEqual(
                1.0,
                result["perSide"][side]["components"]["FLEXION"]["outerMetrics"][
                    "subjectMacroBalancedAccuracyUnknownAsMiss"
                ],
            )
            self.assertEqual(
                0.0,
                result["perSide"][side]["compoundOuterMetrics"]["predictionCoverage"],
            )

    def test_check_rejects_stale_and_noncanonical_artifacts(self) -> None:
        report = {"value": 1}
        report["reportFingerprintSha256"] = _canonical_sha256(report)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            path.write_text(
                json.dumps(report, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
                encoding="utf-8",
            )
            check_committed_report(path, report)
            stale = copy.deepcopy(report)
            stale["value"] = 2
            stale["reportFingerprintSha256"] = _canonical_sha256({"value": 2})
            with self.assertRaisesRegex(GoodMorningExperimentError, "stale"):
                check_committed_report(path, stale)
            path.write_text(json.dumps(report), encoding="utf-8")
            with self.assertRaisesRegex(GoodMorningExperimentError, "canonical layout"):
                check_committed_report(path, report)

    def test_no_clobber_publish_does_not_overwrite_a_racing_target(self) -> None:
        report = {"value": 1}
        report["reportFingerprintSha256"] = _canonical_sha256(report)
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "report.json"
            competitor = b"racing-writer"
            module = sys.modules[atomic_write_json.__module__]

            def racing_link(_source: str, destination: Path) -> None:
                Path(destination).write_bytes(competitor)
                raise FileExistsError("simulated target creation race")

            with mock.patch.object(module.os, "link", side_effect=racing_link):
                with self.assertRaisesRegex(GoodMorningExperimentError, "appeared"):
                    atomic_write_json(target, report)
            self.assertEqual(competitor, target.read_bytes())

    def test_canonical_lf_hash_is_checkout_newline_independent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lf = root / "lf.txt"
            crlf = root / "crlf.txt"
            cr = root / "cr.txt"
            lf.write_bytes(b"one\ntwo\n")
            crlf.write_bytes(b"one\r\ntwo\r\n")
            cr.write_bytes(b"one\rtwo\r")
            self.assertEqual(_canonical_lf_text_sha256(lf), _canonical_lf_text_sha256(crlf))
            self.assertEqual(_canonical_lf_text_sha256(lf), _canonical_lf_text_sha256(cr))

    def test_committed_artifact_is_current_rejected_and_has_zero_authority(self) -> None:
        repository = Path(__file__).resolve().parent.parent
        artifact_path = repository / "docs" / "good-morning-knee-stability-training-experiment.json"
        artifact = json.loads(artifact_path.read_text(encoding="utf-8"))
        script_path = repository / "tools" / "good_morning_knee_stability_experiment.py"
        self.assertTrue(verify_report_fingerprint(artifact))
        self.assertEqual(_canonical_sha256(PROTOCOL_CONTRACT), artifact["protocolSha256"])
        self.assertEqual(
            _canonical_lf_text_sha256(script_path),
            artifact["tool"]["scriptCanonicalLfTextSha256"],
        )
        self.assertEqual(_shared_dependency_provenance(), artifact["sharedImplementationProvenance"])
        inventory = artifact["trainingInventory"]
        self.assertEqual(EXPECTED_SEQUENCE_COUNT, inventory["sequenceCount"])
        self.assertEqual(EXPECTED_SUBJECT_COUNT, inventory["globalZSubjectCount"])
        self.assertEqual(EXPECTED_LABEL_COUNTS, inventory["targetCounts"])
        self.assertEqual(0, inventory["officialValidationStatCount"])
        self.assertEqual(0, inventory["officialValidationReadCount"])
        gate = artifact["experiment"]["researchContinuationGate"]
        self.assertEqual(CONTINUATION_POLICY, gate["policy"])
        self.assertEqual("REJECTED", gate["continuationStatus"])
        self.assertEqual("NO_RUNTIME_THRESHOLD", gate["runtimeThresholdStatus"])
        self.assertIsNone(gate["selectedRuntimeRule"])
        for side in SIDES:
            side_result = artifact["experiment"]["perSide"][side]
            self.assertIn("compoundOuterMetrics", side_result)
            for component in COMPONENT_METRIC_CONTRACT:
                counts = side_result["components"][component]["ruleStability"][
                    "familySelectionCounts"
                ]
                for family in counts:
                    metric_id, direction = family.split("|", 1)
                    self.assertIn(metric_id, COMPONENT_METRIC_CONTRACT[component])
                    self.assertEqual(METRIC_DIRECTION_CONTRACT[metric_id], direction)
        threat = artifact["inputSnapshotThreatModel"]
        self.assertIsNone(threat["externalContentRoot"])
        self.assertIsNone(threat["detachedInputSignature"])
        self.assertFalse(threat["cryptographicFilesystemAuthenticity"])
        self.assertTrue(all(value == 0 for value in artifact["authority"].values()))


if __name__ == "__main__":
    unittest.main()
