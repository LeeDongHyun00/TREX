from __future__ import annotations

import copy
import inspect
import json
from pathlib import Path
import tempfile
import unittest

try:
    from . import good_morning_gold_conformance as gold
except ImportError:
    import good_morning_gold_conformance as gold


class GoodMorningGoldConformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract = gold._load_and_rebuild_decision_contract()

    def fixture(self) -> dict:
        return copy.deepcopy(gold._SYNTHETIC_FIXTURE)

    def test_compiled_receipt_is_unknown_only_zero_authority_and_exact_policy_joined(self) -> None:
        value = gold.compile_readiness()

        self.assertEqual(gold.ARTIFACT_KIND, value["artifactKind"])
        self.assertEqual(gold._artifact_sha256(value), value["artifactSha256"])
        self.assertEqual(gold.BINDING_KEY, value["inputProvenance"]["bindingKey"])
        self.assertEqual("NOT_READY", value["readiness"]["state"])
        self.assertTrue(all(count == 0 for count in value["readiness"]["actualEvidenceCounts"].values()))
        self.assertEqual(gold.READINESS_BLOCKERS, value["readiness"]["blockers"])
        self.assertEqual(gold.AUTHORITY_KEYS, list(value["authority"]))
        self.assertTrue(all(authority == 0 for authority in value["authority"].values()))
        self.assertTrue(all(flag is False for flag in value["contractBoundary"].values()))
        self.assertEqual(
            gold._canonical_lf_sha256(Path(gold.__file__)),
            value["compilerImplementation"]["canonicalLfSha256"],
        )

    def test_exact_binding_rejects_refingerprinted_semantic_drift(self) -> None:
        mutated = copy.deepcopy(self.contract)
        exercise = next(item for item in mutated["exercises"] if item["exerciseId"] == "good-morning")
        binding = next(item for item in exercise["bindings"] if item["bindingKey"] == gold.BINDING_KEY)
        binding["evidenceRequirements"]["viewApplicability"]["viewContractIds"] = [
            "trex.view.full-body-any.v1"
        ]

        with self.assertRaises(gold.GoodMorningGoldConformanceError):
            gold._find_and_validate_binding(mutated)

    def test_compound_truth_table_is_component_independent_and_fail_closed(self) -> None:
        self.assertEqual(
            "CONDITION_SATISFIED",
            gold.compound_gold_state("CONDITION_SATISFIED", "CONDITION_SATISFIED"),
        )
        self.assertEqual(
            "CONDITION_VIOLATED",
            gold.compound_gold_state("CONDITION_VIOLATED", "CONDITION_SATISFIED"),
        )
        self.assertEqual(
            "CONDITION_VIOLATED",
            gold.compound_gold_state("CONDITION_SATISFIED", "CONDITION_VIOLATED"),
        )
        for unknown in ("UNKNOWN_GOLD", "NOT_OBSERVABLE"):
            self.assertEqual(
                "UNKNOWN_GOLD",
                gold.compound_gold_state(unknown, "CONDITION_SATISFIED"),
            )
            self.assertEqual(
                "UNKNOWN_GOLD",
                gold.compound_gold_state("CONDITION_SATISFIED", unknown),
            )
        with self.assertRaises(gold.GoodMorningGoldConformanceError):
            gold.compound_gold_state("PASS", "CONDITION_SATISFIED")

    def test_phase_requires_500ms_ready_and_exact_half_open_full_cycle(self) -> None:
        gold._validate_phase(self.fixture()["phase"])
        mutations = []
        wrong_baseline = self.fixture()["phase"]
        wrong_baseline["readyBaselineStartOffsetMs"] = 1
        mutations.append(wrong_baseline)
        wrong_scope = self.fixture()["phase"]
        wrong_scope["scopeConvention"] = "CLOSED"
        mutations.append(wrong_scope)
        wrong_order = self.fixture()["phase"]
        wrong_order["transitions"][1], wrong_order["transitions"][2] = (
            wrong_order["transitions"][2], wrong_order["transitions"][1]
        )
        mutations.append(wrong_order)
        wrong_end = self.fixture()["phase"]
        wrong_end["cycleEndOffsetMs"] = 1_601
        mutations.append(wrong_end)
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with self.assertRaises(gold.GoodMorningGoldConformanceError):
                    gold._validate_phase(mutation)

    def test_clock_requires_monotonic_pairs_and_protocol_residual(self) -> None:
        gold._validate_clock(self.fixture()["clock"])
        nonmonotonic = self.fixture()["clock"]
        nonmonotonic["pairs"][2]["deviceOffsetMicros"] = 100_000
        with self.assertRaises(gold.GoodMorningGoldConformanceError):
            gold._validate_clock(nonmonotonic)
        misaligned = self.fixture()["clock"]
        misaligned["pairs"][-1]["referenceOffsetMicros"] += 20_000
        with self.assertRaises(gold.GoodMorningGoldConformanceError):
            gold._validate_clock(misaligned)

    def test_component_units_are_exact_four_unknown_gold_rows(self) -> None:
        units = self.fixture()["componentUnits"]
        gold._validate_component_units(units)
        for mutation in (
            units[:-1],
            units + [copy.deepcopy(units[0])],
        ):
            with self.assertRaises(gold.GoodMorningGoldConformanceError):
                gold._validate_component_units(mutation)
        determinate = copy.deepcopy(units)
        determinate[0]["goldState"] = "CONDITION_SATISFIED"
        with self.assertRaises(gold.GoodMorningGoldConformanceError):
            gold._validate_component_units(determinate)
        reordered = copy.deepcopy(units)
        reordered[0], reordered[1] = reordered[1], reordered[0]
        with self.assertRaises(gold.GoodMorningGoldConformanceError):
            gold._validate_component_units(reordered)

    def test_review_shape_requires_three_unique_blinded_slots_and_exact_units(self) -> None:
        reviews = self.fixture()["reviews"]
        gold._validate_reviews(reviews)
        duplicate = copy.deepcopy(reviews)
        duplicate[1]["syntheticReviewerToken"] = duplicate[0]["syntheticReviewerToken"]
        with self.assertRaises(gold.GoodMorningGoldConformanceError):
            gold._validate_reviews(duplicate)
        missing = copy.deepcopy(reviews)
        missing[0]["unitKeys"].pop()
        with self.assertRaises(gold.GoodMorningGoldConformanceError):
            gold._validate_reviews(missing)

    def test_split_shape_rejects_participant_crossing_and_missing_partition(self) -> None:
        assignments = self.fixture()["splitAssignments"]
        gold._validate_split(assignments)
        self.assertEqual(
            {row["split"] for row in assignments},
            {"DEVELOPMENT", "CALIBRATION", "LOCKED_INTERNAL_TEST", "EXTERNAL_TEST"},
        )
        crossing = copy.deepcopy(assignments)
        crossing[1]["syntheticParticipantToken"] = crossing[0]["syntheticParticipantToken"]
        with self.assertRaises(gold.GoodMorningGoldConformanceError):
            gold._validate_split(crossing)
        missing = copy.deepcopy(assignments)
        missing[-1]["split"] = "DEVELOPMENT"
        with self.assertRaises(gold.GoodMorningGoldConformanceError):
            gold._validate_split(missing)

    def test_public_artifact_contains_only_aggregate_synthetic_shape(self) -> None:
        value = gold.compile_readiness()
        rendered = gold.render_json(value).decode("utf-8")
        forbidden_values = [
            "SYNTHETIC_P0",
            "SYNTHETIC_REVIEWER_0",
            "deviceOffsetMicros",
            "referenceOffsetMicros",
            "componentUnits",
            "splitAssignments",
            "participantPseudonym",
            "sessionId",
            "captureTimestamp",
            "landmarks",
        ]
        for forbidden in forbidden_values:
            self.assertNotIn(forbidden, rendered)
        self.assertEqual(1, value["syntheticConformance"]["fixtureCount"])
        self.assertEqual("UNKNOWN_GOLD", value["syntheticConformance"]["allSyntheticComponentStates"])

    def test_cli_exposes_no_real_bundle_dataset_threshold_or_override_input(self) -> None:
        parser = gold._parser()
        destinations = {action.dest for action in parser._actions}
        self.assertEqual({"help", "output", "check"}, destinations)
        source = inspect.getsource(gold._parser)
        for forbidden in ("bundle", "dataset", "participant", "threshold", "force", "skip", "real"):
            self.assertNotIn(forbidden, source.lower())

    def test_publish_is_atomic_no_clobber_and_check_is_byte_exact(self) -> None:
        value = gold.compile_readiness()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt.json"
            gold.write_or_check(path, value, check=False)
            expected = gold.render_json(value)
            self.assertEqual(expected, path.read_bytes())
            gold.write_or_check(path, value, check=True)
            with self.assertRaises(gold.GoodMorningGoldConformanceError):
                gold.write_or_check(path, value, check=False)
            path.write_text("SENTINEL", encoding="utf-8")
            with self.assertRaises(gold.GoodMorningGoldConformanceError):
                gold.write_or_check(path, value, check=True)
            self.assertEqual("SENTINEL", path.read_text(encoding="utf-8"))

    def test_committed_artifact_is_exact_and_deterministic(self) -> None:
        value = gold.compile_readiness()
        self.assertEqual(
            gold.render_json(value),
            gold.DEFAULT_OUTPUT.read_bytes(),
        )


if __name__ == "__main__":
    unittest.main()
