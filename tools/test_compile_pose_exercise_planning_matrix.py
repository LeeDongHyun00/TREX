#!/usr/bin/env python3
"""Fail-closed conformance tests for the exercise planning matrix compiler."""

from __future__ import annotations

import copy
import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

import compile_aihub_criterion_policy as policy_compiler  # noqa: E402
import compile_pose_exercise_planning_matrix as matrix_compiler  # noqa: E402


SOURCE = ROOT / "docs" / "aihub-criterion-coverage.json"
POLICY = ROOT / "docs" / "aihub-criterion-policy.json"
APPROVAL = ROOT / "docs" / "aihub-criterion-policy-approval.json"
REGISTRY = ROOT / "docs" / "pose-exercise-planning-registry.v1.json"
OUTPUT = ROOT / "docs" / "pose-exercise-planning-matrix.v1.json"
INPUT_FILES = [SOURCE, POLICY, APPROVAL, REGISTRY]


def _read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _write(path: Path, value: dict) -> None:
    path.write_text(matrix_compiler.render_json(value), encoding="utf-8", newline="\n")


def _refingerprint(value: dict) -> dict:
    result = copy.deepcopy(value)
    result["artifactSha256"] = matrix_compiler.artifact_sha256(result)
    return result


class PlanningMatrixCompilerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.matrix = matrix_compiler.compile_from_paths()
        cls.source = matrix_compiler.load_json(SOURCE, "source")
        cls.policy = matrix_compiler.load_json(POLICY, "policy")
        cls.approval = matrix_compiler.load_json(APPROVAL, "approval")
        cls.registry = matrix_compiler.load_json(
            REGISTRY, "registry", require_pretty_lf=True
        )
        cls.compiled = policy_compiler.compile_policy(
            source_artifact=cls.source,
            policy=cls.policy,
            approval=cls.approval,
            enforce_service_pins=True,
        )

    def _workspace(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        docs = root / "docs"
        docs.mkdir()
        for source in INPUT_FILES:
            shutil.copyfile(source, docs / source.name)
        registry = _read(REGISTRY)
        for registration in registry["registeredPlans"]:
            relative = Path(registration["artifactPath"])
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)
        return temporary, root

    def _compile_workspace(self, root: Path) -> dict:
        return matrix_compiler.compile_from_paths(
            source_path=root / "docs" / SOURCE.name,
            policy_path=root / "docs" / POLICY.name,
            approval_path=root / "docs" / APPROVAL.name,
            registry_path=root / "docs" / REGISTRY.name,
            project_root=root,
        )

    def test_deterministic_self_hash_lf_and_checked_in_output(self) -> None:
        second = matrix_compiler.compile_from_paths()
        self.assertEqual(self.matrix, second)
        self.assertEqual(
            self.matrix["artifactSha256"],
            matrix_compiler.artifact_sha256(self.matrix),
        )
        self.assertEqual(
            self.matrix["compilerImplementation"]["canonicalTextSha256"],
            matrix_compiler.canonical_lf_text_sha256(
                Path(matrix_compiler.__file__).resolve()
            ),
        )
        raw = OUTPUT.read_bytes()
        self.assertNotIn(b"\r", raw)
        self.assertTrue(raw.endswith(b"\n"))
        self.assertEqual(raw, matrix_compiler.render_json(self.matrix).encode("utf-8"))

    def test_exact_catalog_scope_and_all_167_bindings(self) -> None:
        self.assertEqual(
            {
                key: self.matrix["catalogScope"][key]
                for key in matrix_compiler.EXPECTED_SCOPE
            },
            matrix_compiler.EXPECTED_SCOPE,
        )
        self.assertEqual(len(self.matrix["exercises"]), 41)
        rows = {
            (exercise["exerciseId"], binding["sourceConditionId"]): binding
            for exercise in self.matrix["exercises"]
            for binding in exercise["bindings"]
        }
        self.assertEqual(len(rows), 167)
        expected = {
            (binding["exerciseId"], binding["sourceConditionId"]): binding
            for binding in self.compiled["bindings"]
        }
        self.assertEqual(set(rows), set(expected))
        for key, binding in expected.items():
            actual = rows[key]
            self.assertEqual(actual["bindingId"], binding["bindingId"])
            self.assertEqual(
                actual["bindingPolicySha256"], binding["bindingPolicySha256"]
            )
            self.assertEqual(actual["reviewState"], binding["reviewState"])
            self.assertEqual(actual["releaseState"], "CATALOG_ONLY")
            self.assertEqual(actual["reasonCodes"], binding["reasonCodes"])
            self.assertEqual(
                actual["decisionEvidenceRefs"], binding["decisionEvidenceRefs"]
            )
            interpretation = binding["interpretation"]
            projection = actual["interpretationProjection"]
            if interpretation is None:
                self.assertIsNone(projection)
            else:
                self.assertEqual(
                    projection["phaseApplicability"],
                    interpretation["phaseApplicability"],
                )
                self.assertEqual(projection["sidePolicy"], interpretation["sidePolicy"])
                self.assertEqual(
                    projection["viewApplicability"],
                    interpretation["viewApplicability"],
                )
                self.assertEqual(
                    projection["requiredCapabilityIds"],
                    interpretation["requiredCapabilityIds"],
                )
                self.assertEqual(
                    projection["reviewEvidenceRefs"],
                    interpretation["reviewEvidenceRefs"],
                )

    def test_source_record_type_and_assignment_counts_are_preserved(self) -> None:
        source_by_id = {exercise["id"]: exercise for exercise in self.source["exercises"]}
        total_bindings = 0
        for exercise in self.matrix["exercises"]:
            source = source_by_id[exercise["exerciseId"]]
            self.assertEqual(
                exercise["sourceCatalogCounts"],
                {
                    "recordCount": source["recordCount"],
                    "typeCount": source["typeCount"],
                    "conditionAssignmentCount": source["conditionAssignmentCount"],
                },
            )
            self.assertEqual(
                len(exercise["bindings"]), source["conditionAssignmentCount"]
            )
            total_bindings += len(exercise["bindings"])
        self.assertEqual(total_bindings, 167)

    def test_registered_and_unregistered_planning_states(self) -> None:
        manifest = self.matrix["planningManifest"]
        self.assertEqual(manifest["registeredPlanCount"], 2)
        self.assertEqual(manifest["unregisteredExerciseCount"], 39)
        self.assertEqual(sum(manifest["planningStateCounts"].values()), 41)
        registered = [
            exercise
            for exercise in self.matrix["exercises"]
            if exercise["registeredPlan"] is not None
        ]
        self.assertEqual(len(registered), 2)
        for exercise in registered:
            reference = exercise["registeredPlan"]
            self.assertEqual(reference["commonPolicyProjectionValidationState"], "VERIFIED")
            self.assertEqual(
                reference["deepArtifactValidationState"],
                "OUTSIDE_MATRIX_COMPILER_REQUIRES_ARTIFACT_SPECIFIC_CHECK",
            )

    def test_different_phase_and_side_topologies_are_projected(self) -> None:
        by_id = {exercise["exerciseId"]: exercise for exercise in self.matrix["exercises"]}
        dynamic = by_id["barbell-squat"]["policySummary"]
        role_relative = by_id["standing-side-crunch"]["policySummary"]
        self.assertEqual(
            dynamic["phaseRoles"]["counts"]["trex.phase-role.full-cycle.v1"], 4
        )
        self.assertEqual(
            role_relative["phaseRoles"]["counts"][
                "trex.phase-role.contracted-endpoint.v1"
            ],
            2,
        )
        self.assertEqual(
            role_relative["phaseRoles"]["counts"]["trex.phase-role.full-cycle.v1"],
            3,
        )
        self.assertEqual(
            set(role_relative["sidePolicyKinds"]["ids"]),
            {"ACTIVE_LIMB", "BILATERAL_COUPLED", "CONTRALATERAL_PAIR", "MIDLINE"},
        )
        self.assertEqual(len(role_relative["roleResolverContracts"]["ids"]), 2)

    def test_unresolved_policy_bindings_remain_supported_and_non_determinate(self) -> None:
        unresolved = [
            binding
            for exercise in self.matrix["exercises"]
            for binding in exercise["bindings"]
            if binding["reviewState"] != "REVIEWED_ENGINEERING_V1"
        ]
        self.assertEqual(len(unresolved), 19)
        self.assertTrue(unresolved)
        for binding in unresolved:
            self.assertIsNone(binding["interpretationProjection"])
            self.assertEqual(
                binding["planningDisposition"],
                "SOURCE_INTERPRETATION_UNRESOLVED",
            )

    def test_authority_and_release_are_zero_everywhere(self) -> None:
        self.assertEqual(self.matrix["authority"], matrix_compiler.AUTHORITY_ZERO)
        self.assertEqual(self.matrix["catalogScope"]["releaseEligibleBindingCount"], 0)
        self.assertEqual(
            self.matrix["compilerImplementationUse"],
            "IMPLEMENTATION_DRIFT_DETECTION_ONLY_NOT_APPROVAL_OR_AUTHORITY",
        )
        for exercise in self.matrix["exercises"]:
            self.assertEqual(exercise["releaseEligibleBindingCount"], 0)
            self.assertEqual(exercise["policySummary"]["releaseEligibleBindingCount"], 0)
            for binding in exercise["bindings"]:
                self.assertEqual(binding["releaseState"], "CATALOG_ONLY")

    def test_policy_input_order_does_not_change_output(self) -> None:
        temporary, root = self._workspace()
        self.addCleanup(temporary.cleanup)
        path = root / "docs" / POLICY.name
        policy = _read(path)
        policy["bindings"].reverse()
        _write(path, policy)
        self.assertEqual(self._compile_workspace(root), self.matrix)

    def test_source_policy_registry_and_plan_drift_are_rejected(self) -> None:
        mutations = ("source", "policy", "registry", "plan")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                temporary, root = self._workspace()
                try:
                    if mutation == "source":
                        path = root / "docs" / SOURCE.name
                        value = _read(path)
                        value["manifest"]["exerciseCount"] = 40
                        _write(path, value)
                    elif mutation == "policy":
                        path = root / "docs" / POLICY.name
                        value = _read(path)
                        value["bindings"][0]["releaseState"] = "RELEASED"
                        _write(path, value)
                    elif mutation == "registry":
                        path = root / "docs" / REGISTRY.name
                        value = _read(path)
                        value["catalogScope"]["bindingCount"] = 166
                        _write(path, value)
                    else:
                        registry = _read(root / "docs" / REGISTRY.name)
                        path = root / registry["registeredPlans"][0]["artifactPath"]
                        value = _read(path)
                        value["readiness"] = "READY"
                        _write(path, value)
                    with self.assertRaises(matrix_compiler.PlanningMatrixError):
                        self._compile_workspace(root)
                finally:
                    temporary.cleanup()

    def test_refingerprinted_modern_projection_drift_is_rejected(self) -> None:
        temporary, root = self._workspace()
        self.addCleanup(temporary.cleanup)
        registry_path = root / "docs" / REGISTRY.name
        registry = _read(registry_path)
        registration = next(
            row
            for row in registry["registeredPlans"]
            if row["artifactKind"] == "TREX_POSE_EXERCISE_GOLD_PLANNING_DECLARATION"
        )
        plan_path = root / registration["artifactPath"]
        plan = _read(plan_path)
        plan["criterionPlans"][0]["claimBoundary"] += " drift"
        plan = _refingerprint(plan)
        _write(plan_path, plan)
        registration["artifactSha256"] = plan["artifactSha256"]
        registry = _refingerprint(registry)
        _write(registry_path, registry)
        with self.assertRaisesRegex(
            matrix_compiler.PlanningMatrixError, "claimBoundary drift"
        ):
            self._compile_workspace(root)

    def test_modern_top_level_policy_unions_and_zero_evidence_are_enforced(self) -> None:
        mutations = (
            "actual_evidence_nonzero",
            "actual_evidence_bool",
            "blockers",
            "phase_role_union",
            "phase_topology",
            "side_kind_union",
            "side_resolver_union",
            "view_state_union",
            "view_id_union",
            "capability_union",
            "decision_use",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                temporary, root = self._workspace()
                try:
                    registry_path = root / "docs" / REGISTRY.name
                    registry = _read(registry_path)
                    registration = next(
                        row
                        for row in registry["registeredPlans"]
                        if row["artifactKind"]
                        == "TREX_POSE_EXERCISE_GOLD_PLANNING_DECLARATION"
                    )
                    plan_path = root / registration["artifactPath"]
                    plan = _read(plan_path)
                    if mutation == "actual_evidence_nonzero":
                        plan["currentActualEvidenceCounts"]["captureGroupCount"] = 1
                    elif mutation == "actual_evidence_bool":
                        plan["currentActualEvidenceCounts"]["captureGroupCount"] = True
                    elif mutation == "blockers":
                        plan["blockers"].pop()
                    elif mutation == "phase_role_union":
                        plan["phaseRequirements"]["requiredPolicyPhaseRoleIds"].pop()
                    elif mutation == "phase_topology":
                        plan["phaseRequirements"]["topologyState"] = "DEFINED"
                    elif mutation == "side_kind_union":
                        plan["sideRequirements"]["sidePolicyKinds"].pop()
                    elif mutation == "side_resolver_union":
                        plan["sideRequirements"]["roleResolverContractIds"].pop()
                    elif mutation == "view_state_union":
                        plan["viewRequirements"]["viewApplicabilityStates"] = []
                    elif mutation == "view_id_union":
                        plan["viewRequirements"]["viewContractIds"].pop()
                    elif mutation == "capability_union":
                        plan["capabilityRequirements"]["requiredCapabilityIds"].pop()
                    else:
                        plan["decisionUse"] = "RUNTIME_AUTHORITY"
                    plan = _refingerprint(plan)
                    _write(plan_path, plan)
                    registration["artifactSha256"] = plan["artifactSha256"]
                    registry = _refingerprint(registry)
                    _write(registry_path, registry)
                    with self.assertRaises(matrix_compiler.PlanningMatrixError):
                        self._compile_workspace(root)
                finally:
                    temporary.cleanup()

    def test_legacy_and_modern_plan_states_cannot_be_swapped(self) -> None:
        temporary, root = self._workspace()
        self.addCleanup(temporary.cleanup)
        registry_path = root / "docs" / REGISTRY.name
        registry = _read(registry_path)
        registry["registeredPlans"][0]["planState"] = (
            "POLICY_PROJECTION_ONLY_NO_APPROVED_TOPOLOGY"
        )
        registry = _refingerprint(registry)
        _write(registry_path, registry)
        with self.assertRaisesRegex(
            matrix_compiler.PlanningMatrixError, "legacy preregistration"
        ):
            self._compile_workspace(root)

    def test_legacy_actual_evidence_must_remain_zero(self) -> None:
        temporary, root = self._workspace()
        self.addCleanup(temporary.cleanup)
        registry_path = root / "docs" / REGISTRY.name
        registry = _read(registry_path)
        registration = registry["registeredPlans"][0]
        plan_path = root / registration["artifactPath"]
        plan = _read(plan_path)
        plan["currentActualEvidenceCounts"]["participantCount"] = 1
        plan = _refingerprint(plan)
        _write(plan_path, plan)
        registration["artifactSha256"] = plan["artifactSha256"]
        registry = _refingerprint(registry)
        _write(registry_path, registry)
        with self.assertRaisesRegex(
            matrix_compiler.PlanningMatrixError, "actual evidence counts"
        ):
            self._compile_workspace(root)

    def test_refingerprinted_legacy_runtime_cue_and_unknown_kind_are_rejected(self) -> None:
        mutations = ("runtime_top_field", "cue_row_field", "unknown_artifact_kind")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                temporary, root = self._workspace()
                try:
                    registry_path = root / "docs" / REGISTRY.name
                    registry = _read(registry_path)
                    registration = next(
                        row
                        for row in registry["registeredPlans"]
                        if row["planState"]
                        == "PREREGISTERED_GOLD_STUDY_PLAN_NOT_READY"
                    )
                    plan_path = root / registration["artifactPath"]
                    plan = _read(plan_path)
                    if mutation == "runtime_top_field":
                        plan["runtimeReleaseAuthorization"] = True
                    elif mutation == "cue_row_field":
                        plan["criterionPlans"][0]["cueText"] = "unsafe cue"
                    else:
                        plan["artifactKind"] = "TREX_UNKNOWN_LEGACY_PLAN"
                        registration["artifactKind"] = plan["artifactKind"]
                    plan = _refingerprint(plan)
                    _write(plan_path, plan)
                    registration["artifactSha256"] = plan["artifactSha256"]
                    registry = _refingerprint(registry)
                    _write(registry_path, registry)
                    with self.assertRaises(matrix_compiler.PlanningMatrixError):
                        self._compile_workspace(root)
                finally:
                    temporary.cleanup()

    def test_plan_exact_binding_set_is_enforced(self) -> None:
        temporary, root = self._workspace()
        self.addCleanup(temporary.cleanup)
        registry_path = root / "docs" / REGISTRY.name
        registry = _read(registry_path)
        registration = registry["registeredPlans"][0]
        plan_path = root / registration["artifactPath"]
        plan = _read(plan_path)
        plan["criterionPlans"].pop()
        plan = _refingerprint(plan)
        _write(plan_path, plan)
        registration["artifactSha256"] = plan["artifactSha256"]
        registry = _refingerprint(registry)
        _write(registry_path, registry)
        with self.assertRaisesRegex(
            matrix_compiler.PlanningMatrixError, "exact-set differs"
        ):
            self._compile_workspace(root)

    def test_duplicate_nonfinite_and_non_nfc_json_are_rejected(self) -> None:
        cases = ("duplicate", "nonfinite", "non_nfc")
        for case in cases:
            with self.subTest(case=case):
                temporary, root = self._workspace()
                try:
                    registry_path = root / "docs" / REGISTRY.name
                    if case == "duplicate":
                        text = registry_path.read_text(encoding="utf-8")
                        text = text.replace(
                            '"artifactKind":',
                            '"schemaVersion": 1,\n  "artifactKind":',
                            1,
                        )
                        registry_path.write_text(text, encoding="utf-8", newline="\n")
                    elif case == "nonfinite":
                        text = registry_path.read_text(encoding="utf-8")
                        text = text.replace('"schemaVersion": 1', '"schemaVersion": NaN')
                        registry_path.write_text(text, encoding="utf-8", newline="\n")
                    else:
                        registry = _read(registry_path)
                        registry["registeredPlans"][0]["planState"] = "A\u030A"
                        registry = _refingerprint(registry)
                        _write(registry_path, registry)
                    with self.assertRaises(matrix_compiler.PlanningMatrixError):
                        self._compile_workspace(root)
                finally:
                    temporary.cleanup()

    def test_no_exercise_specific_branch_exists_in_compiler(self) -> None:
        source = Path(matrix_compiler.__file__).read_text(encoding="utf-8")
        self.assertNotIn('"barbell-squat"', source)
        self.assertNotIn('"standing-side-crunch"', source)
        self.assertNotIn("if exercise_id ==", source)

    def test_output_path_confinement_atomic_replace_check_and_compiler_drift(self) -> None:
        temporary, root = self._workspace()
        self.addCleanup(temporary.cleanup)
        outside = root.parent / "escaped-planning-matrix.json"
        with self.assertRaisesRegex(matrix_compiler.PlanningMatrixError, "escapes"):
            matrix_compiler._safe_output_path(
                outside,
                project_root=root,
                input_paths=[],
            )

        output = matrix_compiler._safe_output_path(
            root / "docs" / "pose-exercise-planning-matrix.v1.json",
            project_root=root,
            input_paths=[root / "docs" / SOURCE.name],
        )
        matrix_compiler.write_or_check(output, self.matrix, check=False)
        matrix_compiler.write_or_check(output, self.matrix, check=True)
        matrix_compiler.write_or_check(output, self.matrix, check=False)

        changed = copy.deepcopy(self.matrix)
        changed["compilerImplementation"]["canonicalTextSha256"] = "0" * 64
        changed = matrix_compiler.with_artifact_sha256(changed)
        with self.assertRaisesRegex(matrix_compiler.PlanningMatrixError, "stale"):
            matrix_compiler.write_or_check(output, changed, check=True)
        matrix_compiler.write_or_check(output, changed, check=False)
        matrix_compiler.write_or_check(output, changed, check=True)

    def test_registered_plan_path_traversal_is_rejected(self) -> None:
        temporary, root = self._workspace()
        self.addCleanup(temporary.cleanup)
        registry_path = root / "docs" / REGISTRY.name
        registry = _read(registry_path)
        registry["registeredPlans"][0]["artifactPath"] = "docs/../outside.json"
        registry = _refingerprint(registry)
        _write(registry_path, registry)
        with self.assertRaisesRegex(matrix_compiler.PlanningMatrixError, "unsafe segment"):
            self._compile_workspace(root)

    def test_registered_plan_symlink_is_rejected_when_supported(self) -> None:
        temporary, root = self._workspace()
        self.addCleanup(temporary.cleanup)
        registry_path = root / "docs" / REGISTRY.name
        registry = _read(registry_path)
        registration = registry["registeredPlans"][0]
        target = root / registration["artifactPath"]
        link = root / "docs" / "linked-plan.json"
        try:
            os.symlink(target, link)
        except OSError as error:
            self.skipTest(f"symlink unavailable: {error}")
        registration["artifactPath"] = "docs/linked-plan.json"
        registry = _refingerprint(registry)
        _write(registry_path, registry)
        with self.assertRaisesRegex(matrix_compiler.PlanningMatrixError, "symlink or junction"):
            self._compile_workspace(root)


if __name__ == "__main__":
    unittest.main()
