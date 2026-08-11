#!/usr/bin/env python3
"""Fail-closed tests for the catalog-wide pose Gold decision contract."""

from __future__ import annotations

import ast
import copy
import json
import os
import shutil
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

import compile_pose_exercise_planning_matrix as planning  # noqa: E402
import compile_pose_gold_decision_contract as decision  # noqa: E402


SOURCE = planning.DEFAULT_SOURCE
POLICY = planning.DEFAULT_POLICY
APPROVAL = planning.DEFAULT_APPROVAL
REGISTRY = planning.DEFAULT_REGISTRY
MATRIX = planning.DEFAULT_OUTPUT
OUTPUT = decision.DEFAULT_OUTPUT
PUBLIC_INPUTS = [SOURCE, POLICY, APPROVAL, REGISTRY, MATRIX]


def _read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _write(path: Path, value: dict) -> None:
    path.write_text(decision.render_json(value), encoding="utf-8", newline="\n")


def _refingerprint(value: dict) -> dict:
    result = copy.deepcopy(value)
    result["artifactSha256"] = decision.artifact_sha256(result)
    return result


class PoseGoldDecisionContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract = decision.compile_from_paths()
        cls.matrix = planning.load_json(MATRIX, "matrix", require_pretty_lf=True)
        cls.registry = planning.load_json(REGISTRY, "registry", require_pretty_lf=True)

    def _workspace(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        docs = root / "docs"
        docs.mkdir()
        for source in PUBLIC_INPUTS:
            shutil.copyfile(source, docs / source.name)
        registry = _read(REGISTRY)
        for registration in registry["registeredPlans"]:
            source = ROOT / registration["artifactPath"]
            destination = root / registration["artifactPath"]
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        return temporary, root

    def _compile_workspace(self, root: Path) -> dict:
        return decision.compile_from_paths(
            source_path=root / "docs" / SOURCE.name,
            policy_path=root / "docs" / POLICY.name,
            approval_path=root / "docs" / APPROVAL.name,
            registry_path=root / "docs" / REGISTRY.name,
            matrix_path=root / "docs" / MATRIX.name,
            project_root=root,
        )

    def _all_bindings(self, contract: dict | None = None) -> list[dict]:
        artifact = self.contract if contract is None else contract
        return [
            binding
            for exercise in artifact["exercises"]
            for binding in exercise["bindings"]
        ]

    def test_deterministic_hash_compiler_pin_lf_and_checked_output(self) -> None:
        second = decision.compile_from_paths()
        self.assertEqual(second, self.contract)
        self.assertEqual(
            self.contract["artifactSha256"], decision.artifact_sha256(self.contract)
        )
        compiler_sha = decision.canonical_lf_text_sha256(
            Path(decision.__file__).resolve()
        )
        self.assertEqual(
            self.contract["compilerImplementation"]["canonicalTextSha256"],
            compiler_sha,
        )
        raw = OUTPUT.read_bytes()
        self.assertNotIn(b"\r", raw)
        self.assertTrue(raw.endswith(b"\n"))
        self.assertEqual(raw, decision.render_json(self.contract).encode("utf-8"))

    def test_exact_41_97_167_148_zero_scope_and_binding_tuples(self) -> None:
        scope = self.contract["catalogScope"]
        for key, expected in decision.EXPECTED_SCOPE.items():
            self.assertEqual(scope[key], expected)
        self.assertEqual(scope["phaseRoleCount"], 6)
        self.assertEqual(scope["sidePolicyKindCount"], 9)
        self.assertEqual(scope["registeredExerciseProfileCount"], 2)
        self.assertEqual(len(self.contract["exercises"]), 41)

        actual = {
            (
                binding["bindingKey"]["exerciseId"],
                binding["bindingKey"]["sourceConditionId"],
            ): binding
            for binding in self._all_bindings()
        }
        self.assertEqual(len(actual), 167)
        self.assertEqual(
            len({source_id for _, source_id in actual}),
            97,
        )
        expected = {
            (exercise["exerciseId"], binding["sourceConditionId"]): binding
            for exercise in self.matrix["exercises"]
            for binding in exercise["bindings"]
        }
        self.assertEqual(set(actual), set(expected))
        policy_registry = self.matrix["policyProvenance"]["policyRegistrySha256"]
        for key, source_binding in expected.items():
            binding = actual[key]
            self.assertEqual(
                binding["bindingKey"],
                {
                    "exerciseId": key[0],
                    "sourceConditionId": key[1],
                    "bindingId": source_binding["bindingId"],
                    "bindingPolicySha256": source_binding["bindingPolicySha256"],
                    "policyRegistrySha256": policy_registry,
                },
            )

    def test_six_phase_roles_have_no_approved_scope(self) -> None:
        catalog = self.contract["phaseRoleCatalog"]
        expected_counts = {
            "trex.phase-role.compound-transition.v1": 3,
            "trex.phase-role.concentric.v1": 1,
            "trex.phase-role.contracted-endpoint.v1": 20,
            "trex.phase-role.full-cycle.v1": 99,
            "trex.phase-role.lengthened-endpoint.v1": 22,
            "trex.phase-role.static-hold.v1": 3,
        }
        self.assertEqual(
            [row["phaseRoleId"] for row in catalog],
            list(decision.PHASE_ROLE_IDS),
        )
        for row in catalog:
            self.assertEqual(
                row["bindingCount"], expected_counts[row["phaseRoleId"]]
            )
            self.assertEqual(
                row["scopeContractApprovalState"], "NO_APPROVED_SCOPE_CONTRACT"
            )
            self.assertIsNone(row["scopeContractArtifactSha256"])
            self.assertEqual(row["permittedDecisionState"], "UNKNOWN_GOLD_ONLY")
            self.assertFalse(row["determinateDecisionEligible"])
        self.assertEqual(sum(row["bindingCount"] for row in catalog), 148)
        emitted_counts = Counter(
            role_id
            for binding in self._all_bindings()
            for role_id in binding["phaseRoleIds"]
        )
        self.assertEqual(
            {row["phaseRoleId"]: row["bindingCount"] for row in catalog},
            dict(emitted_counts),
        )

    def test_nine_side_policy_symbolic_slot_table_is_lossless(self) -> None:
        expected_counts = {
            "ACTIVE_LIMB": 3,
            "ALTERNATING_PAIR": 1,
            "BILATERAL_COUPLED": 16,
            "BILATERAL_INDEPENDENT": 55,
            "CONTRALATERAL_PAIR": 1,
            "GLOBAL_BODY": 8,
            "LEAD_LIMB": 10,
            "MIDLINE": 51,
            "TRAIL_LIMB": 3,
        }
        catalog = {
            row["sidePolicyKind"]: row for row in self.contract["sidePolicyCatalog"]
        }
        self.assertEqual(set(catalog), set(decision.SIDE_POLICY_SYMBOLIC_SLOTS))
        for kind, slots in decision.SIDE_POLICY_SYMBOLIC_SLOTS.items():
            row = catalog[kind]
            self.assertEqual(row["bindingCount"], expected_counts[kind])
            self.assertEqual(row["symbolicSlots"], slots)
            relative = kind in decision.ROLE_RELATIVE_SIDE_POLICIES
            self.assertEqual(
                row["resolverRequirement"],
                "EXACT_BINDING_POLICY_RESOLVER_REQUIRED"
                if relative
                else "NOT_APPLICABLE",
            )
            self.assertEqual(
                row["resolverApprovalState"],
                "NO_APPROVED_RESOLVER_ARTIFACT" if relative else "NOT_APPLICABLE",
            )
            self.assertIsNone(row["resolverArtifactSha256"])
            self.assertFalse(row["determinateDecisionEligible"])
        self.assertEqual(sum(row["bindingCount"] for row in catalog.values()), 148)
        emitted_counts = Counter(
            binding["sidePolicy"]["kind"]
            for binding in self._all_bindings()
            if binding["sidePolicy"] is not None
        )
        self.assertEqual(
            {kind: row["bindingCount"] for kind, row in catalog.items()},
            dict(emitted_counts),
        )

    def test_reviewed_and_unreviewed_states_are_fail_closed(self) -> None:
        reviewed = []
        unresolved = []
        for binding in self._all_bindings():
            if binding["interpretationState"] == "REVIEWED_POLICY_PROJECTION":
                reviewed.append(binding)
            else:
                unresolved.append(binding)
        self.assertEqual(len(reviewed), 148)
        self.assertEqual(len(unresolved), 19)
        for binding in reviewed:
            eligibility = binding["decisionEligibility"]
            self.assertEqual(eligibility["state"], "UNKNOWN_GOLD_ONLY")
            self.assertEqual(eligibility["permittedGoldStates"], ["UNKNOWN_GOLD"])
            self.assertEqual(eligibility["determinateGoldStates"], [])
            self.assertTrue(binding["phaseRoleIds"])
            self.assertIsNotNone(binding["sidePolicy"])
            self.assertIsNotNone(binding["evidenceRequirements"])
            for blocker in decision.REVIEWED_BLOCKERS:
                self.assertIn(blocker, eligibility["blockers"])
            if binding["sidePolicy"]["kind"] in decision.ROLE_RELATIVE_SIDE_POLICIES:
                self.assertIn(decision.ROLE_RESOLVER_BLOCKER, eligibility["blockers"])
        for binding in unresolved:
            self.assertEqual(
                binding["decisionEligibility"]["state"],
                "SOURCE_INTERPRETATION_UNRESOLVED",
            )
            self.assertEqual(binding["phaseRoleIds"], [])
            self.assertIsNone(binding["sidePolicy"])
            self.assertIsNone(binding["evidenceRequirements"])
            self.assertEqual(binding["decisionEligibility"]["permittedGoldStates"], [])

    def test_view_blockers_are_exactly_view_state_aware(self) -> None:
        reviewed = [
            binding
            for binding in self._all_bindings()
            if binding["interpretationState"] == "REVIEWED_POLICY_PROJECTION"
        ]
        camera_insufficient = [
            binding
            for binding in reviewed
            if binding["evidenceRequirements"]["viewApplicability"]["state"]
            == "NO_CAMERA_VIEW_SUFFICIENT"
        ]
        self.assertEqual(len(camera_insufficient), 16)
        for binding in reviewed:
            state = binding["evidenceRequirements"]["viewApplicability"]["state"]
            blockers = binding["decisionEligibility"]["blockers"]
            expected = decision.VIEW_STATE_BLOCKERS[state]
            for blocker in {
                value
                for value in decision.VIEW_STATE_BLOCKERS.values()
                if value is not None
            }:
                self.assertEqual(blocker in blockers, blocker == expected)

    def test_every_reviewed_binding_preserves_exact_policy_phase_side_and_resolver(self) -> None:
        expected = {
            (exercise["exerciseId"], binding["sourceConditionId"]): binding
            for exercise in self.matrix["exercises"]
            for binding in exercise["bindings"]
            if binding["interpretationProjection"] is not None
        }
        actual = {
            (
                binding["bindingKey"]["exerciseId"],
                binding["bindingKey"]["sourceConditionId"],
            ): binding
            for binding in self._all_bindings()
            if binding["interpretationState"] == "REVIEWED_POLICY_PROJECTION"
        }
        self.assertEqual(set(actual), set(expected))
        for key, source in expected.items():
            interpretation = source["interpretationProjection"]
            self.assertEqual(
                actual[key]["phaseRoleIds"],
                interpretation["phaseApplicability"]["phaseRoleIds"],
            )
            self.assertEqual(
                actual[key]["sidePolicy"]["kind"],
                interpretation["sidePolicy"]["kind"],
            )
            self.assertEqual(
                actual[key]["sidePolicy"]["roleResolverContractId"],
                interpretation["sidePolicy"]["roleResolverContractId"],
            )
            self.assertEqual(
                actual[key]["evidenceRequirements"],
                {
                    "measurementConstructId": interpretation[
                        "measurementConstructId"
                    ],
                    "observability": interpretation["observability"],
                    "requiredCapabilityIds": interpretation[
                        "requiredCapabilityIds"
                    ],
                    "viewApplicability": interpretation["viewApplicability"],
                    "calibrationProvenance": {
                        "state": interpretation["calibrationProvenance"]["state"],
                        "artifactSha256": interpretation["calibrationProvenance"][
                            "artifactSha256"
                        ],
                        "runtimeDomainId": interpretation[
                            "calibrationProvenance"
                        ]["runtimeDomainId"],
                    },
                },
            )

    def test_registered_profile_preserves_unresolved_without_inventing_semantics(self) -> None:
        exercise = {
            "exerciseId": "fixture-exercise",
            "registeredPlan": {
                "artifactKind": "TREX_TEST_PLAN",
                "artifactPath": "docs/fixture.json",
                "artifactSha256": "0" * 64,
                "planState": "POLICY_PROJECTION_ONLY_NO_APPROVED_TOPOLOGY",
                "commonPolicyProjectionValidationState": "VERIFIED",
                "deepArtifactValidationState": "OUTSIDE_M9_DECISION_CONTRACT_SCOPE",
            },
        }
        binding = {
            "bindingKey": {
                "exerciseId": "fixture-exercise",
                "sourceConditionId": "fixture-condition",
                "bindingId": "fixture-binding",
                "bindingPolicySha256": "1" * 64,
                "policyRegistrySha256": "2" * 64,
            },
            "reviewState": "UNREVIEWED",
            "interpretationState": "SOURCE_INTERPRETATION_UNRESOLVED",
            "phaseRoleIds": [],
            "sidePolicy": None,
            "evidenceRequirements": None,
            "decisionEligibility": {
                "state": "SOURCE_INTERPRETATION_UNRESOLVED",
                "permittedGoldStates": [],
                "determinateGoldStates": [],
                "blockers": ["SOURCE_INTERPRETATION_UNRESOLVED"],
            },
        }
        profile = decision._registered_profile(exercise, [binding])
        self.assertEqual(profile["phaseRoleCounts"], {})
        self.assertEqual(profile["sidePolicyCounts"], {})
        self.assertEqual(profile["roleResolverContractIds"], [])
        self.assertEqual(
            profile["bindingDecisionTuples"],
            [
                {
                    "bindingKey": binding["bindingKey"],
                    "phaseRoleIds": [],
                    "sidePolicyKind": None,
                    "roleResolverContractId": None,
                    "symbolicSlots": [],
                    "decisionEligibilityState": (
                        "SOURCE_INTERPRETATION_UNRESOLVED"
                    ),
                }
            ],
        )

    def test_registered_profiles_preserve_exact_plan_pins_and_binding_sets(self) -> None:
        profiles = {
            profile["exerciseId"]: profile
            for profile in self.contract["registeredExerciseProfiles"]
        }
        expected_exercises = {
            exercise["exerciseId"]: exercise
            for exercise in self.matrix["exercises"]
            if exercise["registeredPlan"] is not None
        }
        self.assertEqual(set(profiles), set(expected_exercises))
        for exercise_id, source in expected_exercises.items():
            profile = profiles[exercise_id]
            self.assertEqual(profile["planReference"], source["registeredPlan"])
            self.assertEqual(profile["bindingCount"], len(source["bindings"]))
            self.assertEqual(len(profile["bindingDecisionTuples"]), len(source["bindings"]))
            self.assertEqual(profile["determinateEligibleBindingCount"], 0)

    def test_standing_profile_has_exact_five_phase_side_slot_tuples(self) -> None:
        profile = next(
            row
            for row in self.contract["registeredExerciseProfiles"]
            if row["exerciseId"] == "standing-side-crunch"
        )
        self.assertEqual(profile["bindingCount"], 5)
        self.assertEqual(
            profile["phaseRoleCounts"],
            {
                "trex.phase-role.contracted-endpoint.v1": 2,
                "trex.phase-role.full-cycle.v1": 3,
            },
        )
        self.assertEqual(
            profile["sidePolicyCounts"],
            {
                "ACTIVE_LIMB": 1,
                "BILATERAL_COUPLED": 1,
                "CONTRALATERAL_PAIR": 1,
                "MIDLINE": 2,
            },
        )
        self.assertEqual(len(profile["roleResolverContractIds"]), 2)
        for row in profile["bindingDecisionTuples"]:
            self.assertEqual(
                row["symbolicSlots"],
                decision.SIDE_POLICY_SYMBOLIC_SLOTS[row["sidePolicyKind"]],
            )
            self.assertEqual(row["decisionEligibilityState"], "UNKNOWN_GOLD_ONLY")

    def test_zero_authority_and_schema_only_boundary(self) -> None:
        self.assertEqual(self.contract["authority"], planning.AUTHORITY_ZERO)
        self.assertEqual(self.contract["catalogScope"]["releaseEligibleBindingCount"], 0)
        self.assertEqual(
            self.contract["catalogScope"]["determinateEligibleBindingCount"], 0
        )
        self.assertTrue(
            all(value is False for value in self.contract["contractBoundary"].values())
        )
        self.assertEqual(self.contract["decisionStateContract"]["determinateGoldStates"], [])
        self.assertFalse(
            self.contract["decisionStateContract"]["positiveOrNegativeDecisionAllowed"]
        )

    def test_refingerprinted_matrix_policy_and_exact_set_drift_fails(self) -> None:
        mutations = (
            "phase",
            "side",
            "resolver",
            "capability",
            "view",
            "observability",
            "calibration",
            "binding",
            "missing",
            "duplicate",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                temporary, root = self._workspace()
                try:
                    matrix_path = root / "docs" / MATRIX.name
                    matrix = _read(matrix_path)
                    reviewed = [
                        binding
                        for exercise in matrix["exercises"]
                        for binding in exercise["bindings"]
                        if binding["interpretationProjection"] is not None
                    ]
                    if mutation == "phase":
                        reviewed[0]["interpretationProjection"]["phaseApplicability"][
                            "phaseRoleIds"
                        ] = ["trex.phase-role.unknown.v1"]
                    elif mutation == "side":
                        reviewed[0]["interpretationProjection"]["sidePolicy"]["kind"] = (
                            "ACTIVE_LIMB"
                        )
                    elif mutation == "resolver":
                        target = next(
                            row
                            for row in reviewed
                            if row["interpretationProjection"]["sidePolicy"][
                                "roleResolverContractId"
                            ]
                            is not None
                        )
                        target["interpretationProjection"]["sidePolicy"][
                            "roleResolverContractId"
                        ] = "trex.role-resolver.drift.v1"
                    elif mutation == "capability":
                        reviewed[0]["interpretationProjection"][
                            "requiredCapabilityIds"
                        ] = ["trex.capability.drift.v1"]
                    elif mutation == "view":
                        reviewed[0]["interpretationProjection"][
                            "viewApplicability"
                        ]["viewContractIds"] = ["trex.view.drift.v1"]
                    elif mutation == "observability":
                        reviewed[0]["interpretationProjection"]["observability"] = (
                            "NOT_OBSERVABLE"
                        )
                    elif mutation == "calibration":
                        reviewed[0]["interpretationProjection"][
                            "calibrationProvenance"
                        ]["runtimeDomainId"] = "trex.runtime-domain.drift.v1"
                    elif mutation == "binding":
                        reviewed[0]["bindingId"] = "aihub-binding-sha256-" + "0" * 64
                    elif mutation == "missing":
                        matrix["exercises"][0]["bindings"].pop()
                    else:
                        matrix["exercises"][0]["bindings"].append(
                            copy.deepcopy(matrix["exercises"][0]["bindings"][0])
                        )
                    matrix = _refingerprint(matrix)
                    _write(matrix_path, matrix)
                    with self.assertRaises(decision.DecisionContractError):
                        self._compile_workspace(root)
                finally:
                    temporary.cleanup()

    def test_bad_matrix_hash_and_refingerprinted_registry_plan_pin_fail(self) -> None:
        for mutation in ("matrix_hash", "registry_plan_pin"):
            with self.subTest(mutation=mutation):
                temporary, root = self._workspace()
                try:
                    if mutation == "matrix_hash":
                        matrix_path = root / "docs" / MATRIX.name
                        matrix = _read(matrix_path)
                        matrix["artifactSha256"] = "0" * 64
                        _write(matrix_path, matrix)
                    else:
                        registry_path = root / "docs" / REGISTRY.name
                        registry = _read(registry_path)
                        registry["registeredPlans"][0]["artifactSha256"] = "0" * 64
                        registry = _refingerprint(registry)
                        _write(registry_path, registry)
                    with self.assertRaises(decision.DecisionContractError):
                        self._compile_workspace(root)
                finally:
                    temporary.cleanup()

    def test_refingerprinted_determinate_approved_scope_resolver_authority_mutations_fail(self) -> None:
        mutations = ("determinate", "approved_scope", "approved_resolver", "authority")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                changed = copy.deepcopy(self.contract)
                if mutation == "determinate":
                    target = next(
                        row
                        for row in self._all_bindings(changed)
                        if row["interpretationState"] == "REVIEWED_POLICY_PROJECTION"
                    )
                    target["decisionEligibility"]["state"] = "DETERMINATE_ALLOWED"
                    target["decisionEligibility"]["permittedGoldStates"] = [
                        "CONDITION_SATISFIED"
                    ]
                elif mutation == "approved_scope":
                    changed["phaseRoleCatalog"][0][
                        "scopeContractApprovalState"
                    ] = "APPROVED"
                    changed["phaseRoleCatalog"][0]["scopeContractArtifactSha256"] = "1" * 64
                elif mutation == "approved_resolver":
                    target = next(
                        row
                        for row in changed["sidePolicyCatalog"]
                        if row["sidePolicyKind"] in decision.ROLE_RELATIVE_SIDE_POLICIES
                    )
                    target["resolverApprovalState"] = "APPROVED"
                    target["resolverArtifactSha256"] = "2" * 64
                else:
                    changed["authority"]["releaseAuthority"] = 1
                changed = _refingerprint(changed)
                with self.assertRaises(decision.DecisionContractError):
                    decision.validate_contract(changed, expected=self.contract)

    def test_refingerprinted_output_evidence_requirement_drift_fails(self) -> None:
        for mutation in ("capability", "view", "observability", "calibration"):
            with self.subTest(mutation=mutation):
                changed = copy.deepcopy(self.contract)
                target = next(
                    row
                    for row in self._all_bindings(changed)
                    if row["interpretationState"] == "REVIEWED_POLICY_PROJECTION"
                )
                evidence = target["evidenceRequirements"]
                if mutation == "capability":
                    evidence["requiredCapabilityIds"] = [
                        "trex.capability.drift.v1"
                    ]
                elif mutation == "view":
                    evidence["viewApplicability"]["viewContractIds"] = [
                        "trex.view.drift.v1"
                    ]
                elif mutation == "observability":
                    evidence["observability"] = "NOT_OBSERVABLE"
                else:
                    evidence["calibrationProvenance"]["runtimeDomainId"] = (
                        "trex.runtime-domain.drift.v1"
                    )
                changed = _refingerprint(changed)
                with self.assertRaises(decision.DecisionContractError):
                    decision.validate_contract(changed, expected=self.contract)

    def test_duplicate_nonfinite_non_nfc_inputs_fail_closed(self) -> None:
        for mutation in ("duplicate", "nonfinite", "non_nfc"):
            with self.subTest(mutation=mutation):
                temporary, root = self._workspace()
                try:
                    matrix_path = root / "docs" / MATRIX.name
                    if mutation == "duplicate":
                        text = matrix_path.read_text(encoding="utf-8")
                        text = text.replace(
                            '"artifactKind":',
                            '"schemaVersion": 1,\n  "artifactKind":',
                            1,
                        )
                        matrix_path.write_text(text, encoding="utf-8", newline="\n")
                    elif mutation == "nonfinite":
                        text = matrix_path.read_text(encoding="utf-8")
                        text = text.replace('"schemaVersion": 1', '"schemaVersion": NaN', 1)
                        matrix_path.write_text(text, encoding="utf-8", newline="\n")
                    else:
                        matrix = _read(matrix_path)
                        matrix["decisionUse"] = "A\u030A"
                        matrix = _refingerprint(matrix)
                        _write(matrix_path, matrix)
                    with self.assertRaises(decision.DecisionContractError):
                        self._compile_workspace(root)
                finally:
                    temporary.cleanup()

    def test_exact_output_path_atomic_replace_check_and_compiler_drift(self) -> None:
        temporary, root = self._workspace()
        self.addCleanup(temporary.cleanup)
        with self.assertRaises(decision.DecisionContractError):
            decision._safe_output_path(
                root / "docs" / "other.json",
                project_root=root,
                input_paths=[],
            )
        with self.assertRaises(decision.DecisionContractError):
            decision._safe_output_path(
                root.parent / OUTPUT.name,
                project_root=root,
                input_paths=[],
            )
        output = decision._safe_output_path(
            root / "docs" / OUTPUT.name,
            project_root=root,
            input_paths=[root / "docs" / MATRIX.name],
        )
        decision.write_or_check(output, self.contract, check=False)
        decision.write_or_check(output, self.contract, check=True)
        decision.write_or_check(output, self.contract, check=False)
        changed = decision.compile_contract(
            registry=self.registry,
            matrix=self.matrix,
            compiler_implementation_sha256="0" * 64,
        )
        with self.assertRaisesRegex(decision.DecisionContractError, "stale"):
            decision.write_or_check(output, changed, check=True)

    def test_symlinked_input_is_rejected_when_supported(self) -> None:
        temporary, root = self._workspace()
        self.addCleanup(temporary.cleanup)
        matrix_path = root / "docs" / MATRIX.name
        real_path = root / "docs" / "real-matrix.json"
        matrix_path.replace(real_path)
        try:
            os.symlink(real_path, matrix_path)
        except OSError as error:
            self.skipTest(f"symlink unavailable: {error}")
        with self.assertRaises(decision.DecisionContractError):
            self._compile_workspace(root)

    def test_simulated_reparse_output_parent_is_rejected(self) -> None:
        temporary, root = self._workspace()
        self.addCleanup(temporary.cleanup)
        docs = root / "docs"
        output = docs / OUTPUT.name
        with mock.patch.object(
            planning,
            "_is_reparse",
            side_effect=lambda path: path == docs,
        ):
            with self.assertRaises(decision.DecisionContractError):
                decision._safe_output_path(
                    output,
                    project_root=root,
                    input_paths=[docs / MATRIX.name],
                )

    def test_compiler_has_no_exercise_id_control_flow_or_runtime_apis(self) -> None:
        source = Path(decision.__file__).read_text(encoding="utf-8")
        tree = ast.parse(source)
        exercise_ids = {
            exercise["exerciseId"] for exercise in self.matrix["exercises"]
        }
        string_literals = {
            node.value
            for node in ast.walk(tree)
            if isinstance(node, ast.Constant) and isinstance(node.value, str)
        }
        self.assertFalse(exercise_ids & string_literals)
        function_names = {
            node.name.lower()
            for node in ast.walk(tree)
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        }
        forbidden_fragments = (
            "evaluate",
            "score",
            "cue",
            "session",
            "restricted_bundle",
            "synthetic_fixture",
            "real_evidence",
            "timestamp",
            "interval",
        )
        for function_name in function_names - {"_parser"}:
            for forbidden in forbidden_fragments:
                self.assertNotIn(forbidden, function_name)


if __name__ == "__main__":
    unittest.main()
