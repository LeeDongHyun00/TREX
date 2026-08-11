#!/usr/bin/env python3
"""Fail-closed tests for the M11 phase-scope and side-resolver requirement inventory."""

from __future__ import annotations

import ast
import copy
import inspect
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from collections import defaultdict
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

import compile_pose_exercise_planning_matrix as planning  # noqa: E402
import compile_pose_gold_annotation_contract as annotation  # noqa: E402
import compile_pose_gold_decision_contract as decision  # noqa: E402
import compile_pose_gold_scope_resolver_requirements as requirements  # noqa: E402


SOURCE = annotation.DEFAULT_SOURCE
POLICY = annotation.DEFAULT_POLICY
APPROVAL = annotation.DEFAULT_APPROVAL
REGISTRY = annotation.DEFAULT_REGISTRY
MATRIX = annotation.DEFAULT_MATRIX
M9 = annotation.DEFAULT_DECISION_CONTRACT
M10 = annotation.DEFAULT_OUTPUT
OUTPUT = requirements.DEFAULT_OUTPUT
PUBLIC_INPUTS = [SOURCE, POLICY, APPROVAL, REGISTRY, MATRIX, M9, M10]


def _read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _write(path: Path, value: dict) -> None:
    path.write_text(requirements.render_json(value), encoding="utf-8", newline="\n")


def _refingerprint(value: dict) -> dict:
    result = copy.deepcopy(value)
    result["artifactSha256"] = requirements.artifact_sha256(result)
    return result


class PoseGoldScopeResolverRequirementsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract = requirements.compile_from_paths()
        cls.m10 = planning.load_json(M10, "M10", require_pretty_lf=True)
        cls.templates = [
            row
            for exercise in cls.m10["exercises"]
            for row in exercise["decisionTemplates"]
        ]
        cls.unresolved = [
            row
            for exercise in cls.m10["exercises"]
            for row in exercise["unresolvedBindings"]
        ]

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
        docs = root / "docs"
        return requirements.compile_from_paths(
            source_path=docs / SOURCE.name,
            policy_path=docs / POLICY.name,
            approval_path=docs / APPROVAL.name,
            registry_path=docs / REGISTRY.name,
            matrix_path=docs / MATRIX.name,
            decision_contract_path=docs / M9.name,
            annotation_contract_path=docs / M10.name,
            project_root=root,
        )

    def test_deterministic_self_hash_compiler_pin_lf_and_checked_output(self) -> None:
        second = requirements.compile_from_paths()
        self.assertEqual(second, self.contract)
        self.assertEqual(
            self.contract["artifactSha256"],
            requirements.artifact_sha256(self.contract),
        )
        self.assertEqual(
            self.contract["compilerImplementation"]["canonicalTextSha256"],
            requirements.canonical_lf_text_sha256(Path(requirements.__file__).resolve()),
        )
        self.assertEqual(
            self.contract["inputProvenance"]["annotationContractArtifactSha256"],
            "5d52c5408187a24e50c0017fb086675aadef8be757aa1091e6abac8ed64a57b7",
        )
        raw = OUTPUT.read_bytes()
        self.assertNotIn(b"\r", raw)
        self.assertTrue(raw.endswith(b"\n"))
        self.assertEqual(raw, requirements.render_json(self.contract).encode("utf-8"))

    def test_exact_78_phase_and_13_resolver_requirements_cover_templates(self) -> None:
        self.assertEqual(self.contract["catalogScope"], requirements.EXPECTED_SCOPE)
        self.assertEqual(
            self.contract["requirementCoverage"], requirements.EXPECTED_COVERAGE
        )
        self.assertEqual(len(self.templates), 203)
        self.assertEqual(len(self.unresolved), 19)

        expected_phase: dict[tuple[str, str], set[str]] = defaultdict(set)
        expected_resolver: dict[tuple[str, str, str], set[str]] = defaultdict(set)
        expected_resolver_slots: dict[tuple[str, str, str], set[str]] = defaultdict(set)
        role_relative_ids = set()
        for template in self.templates:
            exercise_id = template["bindingKey"]["exerciseId"]
            expected_phase[(exercise_id, template["phaseRoleId"])].add(
                template["templateId"]
            )
            if template["sidePolicyKind"] in decision.ROLE_RELATIVE_SIDE_POLICIES:
                key = (
                    exercise_id,
                    template["sidePolicyKind"],
                    template["roleResolverContractId"],
                )
                expected_resolver[key].add(template["templateId"])
                expected_resolver_slots[key].add(template["symbolicSlot"])
                role_relative_ids.add(template["templateId"])

        actual_phase = {
            (row["exerciseId"], row["phaseRoleId"]): set(row["coveredTemplateIds"])
            for row in self.contract["phaseScopeRequirements"]
        }
        actual_resolver = {
            (
                row["exerciseId"],
                row["sidePolicyKind"],
                row["roleResolverContractId"],
            ): set(row["coveredTemplateIds"])
            for row in self.contract["sideResolverRequirements"]
        }
        self.assertEqual(len(actual_phase), 78)
        self.assertEqual(len(actual_resolver), 13)
        self.assertEqual(actual_phase, expected_phase)
        self.assertEqual(actual_resolver, expected_resolver)
        self.assertEqual(len(role_relative_ids), 18)
        for row in self.contract["sideResolverRequirements"]:
            key = (
                row["exerciseId"],
                row["sidePolicyKind"],
                row["roleResolverContractId"],
            )
            self.assertEqual(set(row["symbolicSlots"]), expected_resolver_slots[key])

        phase_coverage = [
            template_id
            for row in self.contract["phaseScopeRequirements"]
            for template_id in row["coveredTemplateIds"]
        ]
        resolver_coverage = [
            template_id
            for row in self.contract["sideResolverRequirements"]
            for template_id in row["coveredTemplateIds"]
        ]
        self.assertEqual(len(phase_coverage), 203)
        self.assertEqual(len(phase_coverage), len(set(phase_coverage)))
        self.assertEqual(set(phase_coverage), {row["templateId"] for row in self.templates})
        self.assertEqual(len(resolver_coverage), 18)
        self.assertEqual(len(resolver_coverage), len(set(resolver_coverage)))
        self.assertEqual(set(resolver_coverage), role_relative_ids)
        self.assertEqual(
            self.contract["requirementCoverage"][
                "unresolvedBindingGeneratedRequirementCount"
            ],
            0,
        )

    def test_pending_only_zero_authority_and_no_operational_contract(self) -> None:
        self.assertEqual(self.contract["authority"], planning.AUTHORITY_ZERO)
        self.assertTrue(
            all(value is False for value in self.contract["contractBoundary"].values())
        )
        self.assertEqual(
            self.contract["requirementStateContract"],
            requirements.REQUIREMENT_STATE_CONTRACT,
        )
        for row in self.contract["phaseScopeRequirements"]:
            self.assertEqual(row["requirementState"], "PENDING_TRUSTED_ARTIFACT")
            for field in requirements.COMMON_NULL_CANDIDATE_FIELDS:
                self.assertIsNone(row[field])
            self.assertNotIn("anatomicalAssignment", row)
        for row in self.contract["sideResolverRequirements"]:
            self.assertEqual(row["requirementState"], "PENDING_TRUSTED_ARTIFACT")
            for field in {
                **requirements.COMMON_NULL_CANDIDATE_FIELDS,
                **requirements.RESOLVER_ONLY_NULL_CANDIDATE_FIELDS,
            }:
                self.assertIsNone(row[field])

    def test_public_candidate_validators_rebuild_canonical_requirements(self) -> None:
        phase = self.contract["phaseScopeRequirements"][0]
        resolver = self.contract["sideResolverRequirements"][0]
        requirements.validate_phase_scope_candidate(phase)
        requirements.validate_side_resolver_candidate(resolver)
        self.assertEqual(
            list(inspect.signature(requirements.validate_phase_scope_candidate).parameters),
            ["candidate"],
        )
        self.assertEqual(
            list(inspect.signature(requirements.validate_side_resolver_candidate).parameters),
            ["candidate"],
        )

        changed_key = copy.deepcopy(phase)
        changed_key["exerciseId"] = "synthetic-noncanonical-exercise"
        with self.assertRaises(requirements.ScopeResolverRequirementsError):
            requirements.validate_phase_scope_candidate(changed_key)

        changed_set = copy.deepcopy(phase)
        replacement = self.contract["phaseScopeRequirements"][1]["coveredTemplateIds"]
        changed_set["coveredTemplateIds"] = list(replacement)
        changed_set["coveredTemplateCount"] = len(replacement)
        with self.assertRaises(requirements.ScopeResolverRequirementsError):
            requirements.validate_phase_scope_candidate(changed_set)

        changed_resolver = copy.deepcopy(resolver)
        changed_resolver["roleResolverContractId"] += ".self-issued"
        with self.assertRaises(requirements.ScopeResolverRequirementsError):
            requirements.validate_side_resolver_candidate(changed_resolver)

        changed_resolver_set = copy.deepcopy(resolver)
        replacement = self.contract["sideResolverRequirements"][1][
            "coveredTemplateIds"
        ]
        changed_resolver_set["coveredTemplateIds"] = list(replacement)
        changed_resolver_set["coveredTemplateCount"] = len(replacement)
        with self.assertRaises(requirements.ScopeResolverRequirementsError):
            requirements.validate_side_resolver_candidate(changed_resolver_set)

    def test_candidates_reject_positive_fields_and_forbidden_extensions(self) -> None:
        phase = self.contract["phaseScopeRequirements"][0]
        resolver = self.contract["sideResolverRequirements"][0]
        phase_mutations = {
            "state": ("requirementState", "APPROVED"),
            "artifact": ("candidateArtifactSha256", "0" * 64),
            "signature": ("detachedSignatureEnvelope", {"signature": "self-issued"}),
            "trust": ("trustRegistryArtifactSha256", "1" * 64),
            "approver": ("approverId", "self"),
        }
        for label, (field, value) in phase_mutations.items():
            with self.subTest(candidate="phase", mutation=label):
                mutated = copy.deepcopy(phase)
                mutated[field] = value
                with self.assertRaises(requirements.ScopeResolverRequirementsError):
                    requirements.validate_phase_scope_candidate(mutated)

        resolver_mutations = {
            **phase_mutations,
            "anatomy": ("anatomicalAssignment", "LEFT"),
        }
        for label, (field, value) in resolver_mutations.items():
            with self.subTest(candidate="resolver", mutation=label):
                mutated = copy.deepcopy(resolver)
                mutated[field] = value
                with self.assertRaises(requirements.ScopeResolverRequirementsError):
                    requirements.validate_side_resolver_candidate(mutated)

        for extra in ("topology", "timestampNs", "evidenceIntake", "runtimeProvider"):
            with self.subTest(extra=extra):
                mutated = copy.deepcopy(phase)
                mutated[extra] = None
                with self.assertRaises(requirements.ScopeResolverRequirementsError):
                    requirements.validate_phase_scope_candidate(mutated)

    def test_coupled_independent_and_role_relative_resolver_boundaries(self) -> None:
        coupled = [
            row for row in self.templates if row["sidePolicyKind"] == "BILATERAL_COUPLED"
        ]
        independent = [
            row
            for row in self.templates
            if row["sidePolicyKind"] == "BILATERAL_INDEPENDENT"
        ]
        self.assertEqual(len(coupled), 16)
        self.assertEqual({row["symbolicSlot"] for row in coupled}, {"BILATERAL_PAIR"})
        self.assertEqual({row["roleResolverContractId"] for row in coupled}, {None})
        self.assertEqual(len(independent), 110)
        self.assertEqual(
            {slot: sum(row["symbolicSlot"] == slot for row in independent) for slot in ("LEFT", "RIGHT")},
            {"LEFT": 55, "RIGHT": 55},
        )
        self.assertEqual({row["roleResolverContractId"] for row in independent}, {None})
        resolver_template_ids = {
            template_id
            for row in self.contract["sideResolverRequirements"]
            for template_id in row["coveredTemplateIds"]
        }
        self.assertTrue(resolver_template_ids.isdisjoint({row["templateId"] for row in coupled}))
        self.assertTrue(
            resolver_template_ids.isdisjoint({row["templateId"] for row in independent})
        )

    def test_refingerprinted_output_mutations_are_rejected(self) -> None:
        mutations = []

        value = copy.deepcopy(self.contract)
        value["phaseScopeRequirements"].pop()
        mutations.append(("missing phase", value))

        value = copy.deepcopy(self.contract)
        value["phaseScopeRequirements"].append(
            copy.deepcopy(value["phaseScopeRequirements"][0])
        )
        mutations.append(("duplicate phase", value))

        value = copy.deepcopy(self.contract)
        value["sideResolverRequirements"].pop()
        mutations.append(("missing resolver", value))

        value = copy.deepcopy(self.contract)
        value["sideResolverRequirements"].append(
            copy.deepcopy(value["sideResolverRequirements"][0])
        )
        mutations.append(("duplicate resolver", value))

        value = copy.deepcopy(self.contract)
        value["phaseScopeRequirements"][0]["coveredTemplateIds"] = list(
            value["phaseScopeRequirements"][1]["coveredTemplateIds"]
        )
        value["phaseScopeRequirements"][0]["coveredTemplateCount"] = len(
            value["phaseScopeRequirements"][0]["coveredTemplateIds"]
        )
        mutations.append(("cross-referenced phase templates", value))

        value = copy.deepcopy(self.contract)
        value["sideResolverRequirements"][0]["coveredTemplateIds"] = list(
            value["sideResolverRequirements"][1]["coveredTemplateIds"]
        )
        value["sideResolverRequirements"][0]["coveredTemplateCount"] = len(
            value["sideResolverRequirements"][0]["coveredTemplateIds"]
        )
        mutations.append(("cross-referenced resolver templates", value))

        value = copy.deepcopy(self.contract)
        value["authority"]["releaseAuthority"] = 1
        mutations.append(("authority", value))

        value = copy.deepcopy(self.contract)
        value["contractBoundary"]["phaseTopologyIncluded"] = True
        mutations.append(("topology boundary", value))

        value = copy.deepcopy(self.contract)
        value["requirementCoverage"]["phaseUncoveredTemplateCount"] = 1
        mutations.append(("coverage", value))

        value = copy.deepcopy(self.contract)
        value["inputProvenance"]["annotationContractArtifactSha256"] = "0" * 64
        mutations.append(("M10 pin", value))

        value = copy.deepcopy(self.contract)
        value["compilerImplementation"]["canonicalTextSha256"] = "1" * 64
        mutations.append(("compiler pin", value))

        value = copy.deepcopy(self.contract)
        value["runtimeReleaseAuthorization"] = True
        mutations.append(("unknown top-level field", value))

        for label, value in mutations:
            with self.subTest(mutation=label):
                with self.assertRaises(requirements.ScopeResolverRequirementsError):
                    requirements.validate_contract(_refingerprint(value))

        broken_hash = copy.deepcopy(self.contract)
        broken_hash["artifactSha256"] = "0" * 64
        with self.assertRaises(requirements.ScopeResolverRequirementsError):
            requirements.validate_contract(broken_hash)

    def test_refingerprinted_upstream_m10_drift_is_rejected(self) -> None:
        def phase_drift(value: dict) -> None:
            value["exercises"][0]["decisionTemplates"][0]["phaseRoleId"] = (
                "trex.phase-role.static-hold.v1"
            )

        def resolver_drift(value: dict) -> None:
            template = next(
                row
                for exercise in value["exercises"]
                for row in exercise["decisionTemplates"]
                if row["roleResolverContractId"] is not None
            )
            template["roleResolverContractId"] += ".drift"

        def binding_drift(value: dict) -> None:
            template = value["exercises"][0]["decisionTemplates"][0]
            template["bindingKey"]["bindingId"] = "aihub-binding-sha256-" + "0" * 64

        def compiler_drift(value: dict) -> None:
            value["compilerImplementation"]["canonicalTextSha256"] = "0" * 64

        for label, mutate in (
            ("phase", phase_drift),
            ("resolver", resolver_drift),
            ("binding", binding_drift),
            ("compiler", compiler_drift),
        ):
            with self.subTest(mutation=label):
                temporary, root = self._workspace()
                with temporary:
                    path = root / "docs" / M10.name
                    value = _read(path)
                    mutate(value)
                    value["artifactSha256"] = annotation.artifact_sha256(value)
                    _write(path, value)
                    with self.assertRaises(requirements.ScopeResolverRequirementsError):
                        self._compile_workspace(root)

    def test_duplicate_nonfinite_and_non_nfc_m10_json_are_rejected(self) -> None:
        for label in ("duplicate", "nonfinite", "non_nfc"):
            with self.subTest(mutation=label):
                temporary, root = self._workspace()
                with temporary:
                    path = root / "docs" / M10.name
                    raw = path.read_text(encoding="utf-8")
                    if label == "duplicate":
                        marker = '  "artifactKind":'
                        self.assertIn(marker, raw)
                        raw = raw.replace(
                            marker,
                            '  "artifactKind": "DUPLICATE",\n' + marker,
                            1,
                        )
                    elif label == "nonfinite":
                        marker = '  "schemaVersion": 2'
                        self.assertIn(marker, raw)
                        raw = raw.replace(marker, '  "schemaVersion": NaN', 1)
                    else:
                        value = _read(path)
                        value["decisionUse"] = "e\u0301"
                        raw = json.dumps(
                            value,
                            ensure_ascii=False,
                            sort_keys=True,
                            indent=2,
                            allow_nan=False,
                        ) + "\n"
                    path.write_text(raw, encoding="utf-8", newline="\n")
                    with self.assertRaises(requirements.ScopeResolverRequirementsError):
                        self._compile_workspace(root)

    def test_exact_output_path_atomic_replace_and_byte_check(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docs = root / "docs"
            docs.mkdir()
            target = docs / OUTPUT.name
            inputs = [docs / "input.json"]
            safe = requirements._safe_output_path(
                target, project_root=root, input_paths=inputs
            )
            self.assertEqual(safe, target.resolve())
            target.write_text("stale", encoding="utf-8")
            requirements.write_or_check(safe, self.contract, check=False)
            self.assertEqual(
                target.read_bytes(), requirements.render_json(self.contract).encode("utf-8")
            )
            requirements.write_or_check(safe, self.contract, check=True)
            target.write_text("stale", encoding="utf-8")
            with self.assertRaises(requirements.ScopeResolverRequirementsError):
                requirements.write_or_check(safe, self.contract, check=True)

            with self.assertRaises(requirements.ScopeResolverRequirementsError):
                requirements._safe_output_path(
                    docs / "other.json", project_root=root, input_paths=inputs
                )
            with self.assertRaises(requirements.ScopeResolverRequirementsError):
                requirements._safe_output_path(
                    target, project_root=root, input_paths=[target]
                )
            with self.assertRaises(requirements.ScopeResolverRequirementsError):
                requirements._safe_output_path(
                    root.parent / OUTPUT.name, project_root=root, input_paths=inputs
                )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(requirements.ScopeResolverRequirementsError):
                requirements._safe_output_path(
                    root / "docs" / OUTPUT.name,
                    project_root=root,
                    input_paths=[],
                )

    def test_output_symlink_parent_is_rejected_when_supported(self) -> None:
        with tempfile.TemporaryDirectory() as root_directory, tempfile.TemporaryDirectory() as outside:
            root = Path(root_directory)
            link = root / "docs"
            try:
                os.symlink(Path(outside), link, target_is_directory=True)
            except (OSError, NotImplementedError) as error:
                self.skipTest(f"directory symlink unavailable: {error}")
            with self.assertRaises(requirements.ScopeResolverRequirementsError):
                requirements._safe_output_path(
                    link / OUTPUT.name,
                    project_root=root,
                    input_paths=[],
                )

    def test_script_and_package_check_modes(self) -> None:
        commands = (
            [sys.executable, "tools/compile_pose_gold_scope_resolver_requirements.py", "--check"],
            [sys.executable, "-m", "tools.compile_pose_gold_scope_resolver_requirements", "--check"],
        )
        for command in commands:
            with self.subTest(command=command):
                result = subprocess.run(
                    command,
                    cwd=ROOT,
                    capture_output=True,
                    text=True,
                    timeout=120,
                    check=False,
                )
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_compiler_has_no_exercise_literals_or_operational_api(self) -> None:
        source = Path(requirements.__file__).read_text(encoding="utf-8")
        tree = ast.parse(source)
        literals = {
            node.value
            for node in ast.walk(tree)
            if isinstance(node, ast.Constant) and isinstance(node.value, str)
        }
        exercise_ids = {row["exerciseId"] for row in self.m10["exercises"]}
        self.assertTrue(exercise_ids.isdisjoint(literals))
        function_names = {
            node.name.lower()
            for node in ast.walk(tree)
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        }
        forbidden = {
            "evaluate",
            "score",
            "cue",
            "session",
            "verdict",
            "feedback",
            "open_session",
            "parse_restricted",
        }
        self.assertTrue(function_names.isdisjoint(forbidden))


if __name__ == "__main__":
    unittest.main()
