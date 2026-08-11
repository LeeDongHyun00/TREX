import copy
import json
import tempfile
import unittest
from pathlib import Path

if __package__:
    from .barbell_squat_phase_training_experiment import (
        ARTIFACT_KIND,
        DECISION_USE,
        EVALUATED_COORDINATE_DOMAIN,
        EVALUATED_DECODER_FAMILY_ID,
        EVALUATED_SIGNAL_FAMILY_ID,
        EVALUATED_VIEW_ROLE,
        PARAMETER_GRID,
        PHASES,
        PROTOCOL_CONTRACT,
        DecoderConfig,
        SubjectCounts,
        TrainingSequence,
        _canonical_sha256,
        _canonical_lf_text_sha256,
        _configuration_stability,
        _parser,
        _shared_dependency_provenance,
        aggregate_metrics,
        build_surrogate,
        decode_causal,
        decoder_configurations,
        check_committed_report,
        load_training,
        run_training_only_experiment,
    )
    from .barbell_squat_validation_experiment import (
        SquatExperimentError,
        atomic_write_json,
        validate_output_path,
        verify_report_fingerprint,
    )
else:
    from barbell_squat_phase_training_experiment import (
        ARTIFACT_KIND,
        DECISION_USE,
        EVALUATED_COORDINATE_DOMAIN,
        EVALUATED_DECODER_FAMILY_ID,
        EVALUATED_SIGNAL_FAMILY_ID,
        EVALUATED_VIEW_ROLE,
        PARAMETER_GRID,
        PHASES,
        PROTOCOL_CONTRACT,
        DecoderConfig,
        SubjectCounts,
        TrainingSequence,
        _canonical_sha256,
        _canonical_lf_text_sha256,
        _configuration_stability,
        _parser,
        _shared_dependency_provenance,
        aggregate_metrics,
        build_surrogate,
        decode_causal,
        decoder_configurations,
        check_committed_report,
        load_training,
        run_training_only_experiment,
    )
    from barbell_squat_validation_experiment import (
        SquatExperimentError,
        atomic_write_json,
        validate_output_path,
        verify_report_fingerprint,
    )


TRAJECTORY = (5.0, 7.0, 15.0, 35.0, 60.0, 80.0, 82.0, 75.0, 55.0, 30.0, 12.0, 7.0)


def sequence(subject: str, ordinal: int = 0, values=TRAJECTORY) -> TrainingSequence:
    digest = f"{ordinal + 1:064x}"
    return TrainingSequence(
        sequence_id=f"day/{subject}/{ordinal}",
        type_code="313",
        subject_id=subject,
        day_id="Day01",
        active_runs=(tuple(values),),
        frame_count=len(values),
        active_frame_count=len(values),
        two_d_sha256=digest,
        three_d_sha256=f"{ordinal + 101:064x}",
        two_d_coordinate_sha256=f"{ordinal + 201:064x}",
        three_d_coordinate_sha256=f"{ordinal + 301:064x}",
        active_contract_sha256=f"{ordinal + 401:064x}",
    )


def stable_config() -> DecoderConfig:
    return DecoderConfig(
        baseline_frame_count=2,
        trailing_median_window=1,
        baseline_stability_degrees=8.0,
        ready_band_degrees=12.0,
        descent_entry_degrees=10.0,
        motion_degrees_per_sample=1.5,
        bottom_minimum_displacement_degrees=55.0,
        reversal_degrees_per_sample=1.5,
    )


class BarbellSquatPhaseTrainingExperimentTest(unittest.TestCase):
    def test_protocol_forbids_validation_gold_and_all_authority(self) -> None:
        self.assertEqual(
            "FORBIDDEN_NOT_READ_NOT_REUSED",
            PROTOCOL_CONTRACT["officialValidationRole"],
        )
        self.assertEqual("ABSENT", PROTOCOL_CONTRACT["phaseGold"])
        self.assertEqual(0, PROTOCOL_CONTRACT["releaseAuthority"])
        self.assertEqual(0, PROTOCOL_CONTRACT["shadowAuthority"])
        self.assertEqual(0, PROTOCOL_CONTRACT["userDecisionAuthority"])
        self.assertIn("RESEARCH_ONLY", DECISION_USE)
        self.assertIn("TRAINING_ONLY", ARTIFACT_KIND)
        self.assertEqual(
            "RESEARCH_CANDIDATE_DIAGNOSTICS_ONLY_NOT_RUNTIME_PARAMETERS",
            PROTOCOL_CONTRACT["learnedThresholdRole"],
        )

    def test_cli_has_no_validation_input(self) -> None:
        with self.assertRaises(SystemExit):
            _parser().parse_args(["training", "--validation-root", "validation"])

    def test_validation_named_root_is_rejected_before_discovery(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "2.Validation" / "labels"
            root.mkdir(parents=True)
            with self.assertRaisesRegex(SquatExperimentError, "Validation is forbidden"):
                load_training(root)

    def test_output_is_confined_outside_source_and_immutable_by_default(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "source"
            root.mkdir()
            with self.assertRaises(SquatExperimentError):
                validate_output_path(root / "report.json", (root,))

            report = {"value": 1}
            report["reportFingerprintSha256"] = _canonical_sha256(report)
            output = Path(directory) / "report.json"
            atomic_write_json(output, report)
            with self.assertRaisesRegex(SquatExperimentError, "already exists"):
                atomic_write_json(output, report)

    def test_check_contract_rejects_stale_content_and_noncanonical_bytes(self) -> None:
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
            with self.assertRaisesRegex(SquatExperimentError, "stale"):
                check_committed_report(path, stale)

            path.write_text(json.dumps(report), encoding="utf-8")
            with self.assertRaisesRegex(SquatExperimentError, "canonical layout"):
                check_committed_report(path, report)

    def test_portable_text_hash_is_identical_for_lf_crlf_and_cr(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lf = root / "lf.txt"
            crlf = root / "crlf.txt"
            cr = root / "cr.txt"
            lf.write_bytes(b"first\nsecond\n")
            crlf.write_bytes(b"first\r\nsecond\r\n")
            cr.write_bytes(b"first\rsecond\r")
            self.assertEqual(_canonical_lf_text_sha256(lf), _canonical_lf_text_sha256(crlf))
            self.assertEqual(_canonical_lf_text_sha256(lf), _canonical_lf_text_sha256(cr))

    def test_committed_artifact_is_fingerprint_and_source_protocol_fresh(self) -> None:
        repository = Path(__file__).resolve().parent.parent
        artifact_path = repository / "docs" / "barbell-squat-phase-training-experiment.json"
        artifact = json.loads(artifact_path.read_text(encoding="utf-8"))
        script_path = repository / "tools" / "barbell_squat_phase_training_experiment.py"
        self.assertTrue(verify_report_fingerprint(artifact))
        self.assertEqual(_canonical_sha256(PROTOCOL_CONTRACT), artifact["protocolSha256"])
        self.assertEqual(
            _canonical_lf_text_sha256(script_path),
            artifact["tool"]["scriptCanonicalLfTextSha256"],
        )
        self.assertEqual(
            _shared_dependency_provenance(),
            artifact["sharedImplementationProvenance"],
        )
        gate = artifact["experiment"]["researchContinuationGate"]
        self.assertEqual(
            {
                "signalFamilyId": EVALUATED_SIGNAL_FAMILY_ID,
                "decoderFamilyId": EVALUATED_DECODER_FAMILY_ID,
                "coordinateDomain": EVALUATED_COORDINATE_DOMAIN,
                "viewRole": EVALUATED_VIEW_ROLE,
                "frontCandidateEvaluated": False,
            },
            artifact["evaluatedScope"],
        )
        self.assertEqual(
            EVALUATED_SIGNAL_FAMILY_ID,
            PROTOCOL_CONTRACT["evaluatedSignalFamilyId"],
        )
        self.assertEqual("REJECTED", gate["continuationStatus"])
        self.assertEqual(
            "NO_RUNTIME_DECODER_PARAMETERS",
            gate["runtimeDecoderParameterStatus"],
        )
        self.assertEqual(0, artifact["authority"]["runtimeProviderAuthority"])
        inventory = artifact["trainingInventory"]
        self.assertNotIn("activeFramesIncluded", inventory["metadataAudit"])
        self.assertGreater(inventory["phaseExtractionCounts"]["activeFramesIncluded"], 0)
        self.assertGreater(inventory["phaseExtractionCounts"]["activeRuns"], 0)

    def test_surrogate_is_complete_but_explicitly_retrospective(self) -> None:
        record, reason = build_surrogate(sequence("Z01"))
        self.assertIsNone(reason)
        self.assertIsNotNone(record)
        assert record is not None
        self.assertEqual(set(PHASES), set(record.labels))
        self.assertGreater(record.rom_degrees, 20.0)
        self.assertEqual(len(TRAJECTORY), len(record.labels))
        self.assertEqual(
            "CENTERED_THREE_SAMPLE_MEDIAN_FUTURE_ALLOWED_REFERENCE_ONLY",
            PROTOCOL_CONTRACT["surrogateReference"]["smoothing"],
        )

    def test_surrogate_does_not_bridge_inactive_runs(self) -> None:
        item = sequence("Z01")
        split = TrainingSequence(
            **{
                **item.__dict__,
                "active_runs": ((5.0, 7.0, 12.0), (55.0, 70.0, 82.0)),
                "active_frame_count": 6,
            }
        )
        record, reason = build_surrogate(split)
        self.assertIsNone(record)
        self.assertEqual(
            "NO_CONTIGUOUS_ACTIVE_RUN_WITH_MINIMUM_FRAMES_AND_ROM",
            reason,
        )

    def test_causal_decoder_is_prefix_invariant_for_every_prefix(self) -> None:
        config = stable_config()
        full = decode_causal(TRAJECTORY, config)
        for length in range(1, len(TRAJECTORY) + 1):
            self.assertEqual(full[:length], decode_causal(TRAJECTORY[:length], config))

    def test_suffix_mutation_cannot_change_prior_decisions(self) -> None:
        config = stable_config()
        prefix_length = 7
        original = decode_causal(TRAJECTORY, config)
        mutated = decode_causal(TRAJECTORY[:prefix_length] + (5.0, 5.0, 5.0), config)
        self.assertEqual(original[:prefix_length], mutated[:prefix_length])

    def test_unstable_initial_baseline_abstains_without_recalibrating(self) -> None:
        decisions = decode_causal((5.0, 30.0, 60.0, 80.0, 20.0), stable_config())
        self.assertTrue(all(item.phase is None for item in decisions))
        self.assertEqual(
            "INITIAL_BASELINE_NOT_STABLE",
            decisions[-1].abstention_reason,
        )

    def test_candidate_grid_is_complete_unique_and_content_addressed(self) -> None:
        configs = decoder_configurations()
        expected_count = 1
        for values in PARAMETER_GRID.values():
            expected_count *= len(values)
        self.assertEqual(expected_count, len(configs))
        self.assertEqual(len(configs), len({item.configuration_id for item in configs}))
        self.assertEqual(configs, tuple(sorted(configs, key=lambda item: item.configuration_id)))

    def test_metrics_count_unknown_as_miss_and_report_selective_agreement(self) -> None:
        counts = SubjectCounts(
            subject_id="Z01",
            reference_by_phase=(2, 2, 2, 2),
            correct_by_phase=(1, 1, 1, 1),
            determinate_by_phase=(1, 1, 1, 1),
            selective_correct=4,
            reference_frames=8,
            determinate_frames=4,
            sequence_count=1,
            completed_topology_count=0,
            abstention_reasons=(("CALIBRATION", 4),),
        )
        metrics = aggregate_metrics([counts])
        self.assertEqual(0.5, metrics["predictionCoverage"])
        self.assertEqual(0.5, metrics["subjectMacroSurrogateRecall"])
        self.assertEqual(1.0, metrics["selectiveSurrogateAgreement"])
        self.assertEqual(4, metrics["abstainedFrameCount"])

    def test_configuration_stability_uses_all_outer_fold_selections(self) -> None:
        first = stable_config()
        second = DecoderConfig(
            **{**first.__dict__, "ready_band_degrees": 8.0}
        )
        result = _configuration_stability([first, first, first, second])
        self.assertEqual(4, result["foldCount"])
        self.assertEqual(2, result["uniqueConfigurationCount"])
        self.assertEqual(0.75, result["modalConfigurationFraction"])

    def test_subject_grouped_experiment_is_deterministic_and_prefix_exact(self) -> None:
        sequences = [
            sequence(subject, ordinal, tuple(value + ordinal * 0.1 for value in TRAJECTORY))
            for ordinal, subject in enumerate(("Z01", "Z02", "Z03", "Z04"))
        ]
        inventory = {
            "sequenceCount": len(sequences),
            "subjectCount": 4,
            "officialValidationReadCount": 0,
        }
        first = run_training_only_experiment(sequences, inventory)
        second = run_training_only_experiment(copy.deepcopy(sequences), copy.deepcopy(inventory))
        self.assertEqual(_canonical_sha256(first), _canonical_sha256(second))
        outer = first["subjectGroupedOuterEvaluation"]
        self.assertEqual(4, outer["foldCount"])
        self.assertTrue(all(fold["subjectOverlapCount"] == 0 for fold in outer["folds"]))
        self.assertEqual(1.0, first["causalPrefixAudit"]["invarianceRate"])
        self.assertEqual(0, first["researchContinuationGate"]["releaseAuthority"])


if __name__ == "__main__":
    unittest.main()
