#!/usr/bin/env python3
"""Fail-closed tests for the catalog-wide pose Gold annotation contract."""

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
from collections import Counter
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

import compile_pose_exercise_planning_matrix as planning  # noqa: E402
import compile_pose_gold_annotation_contract as annotation  # noqa: E402
import compile_pose_gold_decision_contract as decision  # noqa: E402


SOURCE = decision.DEFAULT_SOURCE
POLICY = decision.DEFAULT_POLICY
APPROVAL = decision.DEFAULT_APPROVAL
REGISTRY = decision.DEFAULT_REGISTRY
MATRIX = decision.DEFAULT_MATRIX
M9 = decision.DEFAULT_OUTPUT
OUTPUT = annotation.DEFAULT_OUTPUT
PUBLIC_INPUTS = [SOURCE, POLICY, APPROVAL, REGISTRY, MATRIX, M9]

EXPECTED_PHASE_TEMPLATE_COUNTS = {
    "trex.phase-role.compound-transition.v1": 3,
    "trex.phase-role.concentric.v1": 2,
    "trex.phase-role.contracted-endpoint.v1": 26,
    "trex.phase-role.full-cycle.v1": 135,
    "trex.phase-role.lengthened-endpoint.v1": 33,
    "trex.phase-role.static-hold.v1": 4,
}
EXPECTED_SIDE_TEMPLATE_COUNTS = {
    "ACTIVE_LIMB": 3,
    "ALTERNATING_PAIR": 1,
    "BILATERAL_COUPLED": 16,
    "BILATERAL_INDEPENDENT": 110,
    "CONTRALATERAL_PAIR": 1,
    "GLOBAL_BODY": 8,
    "LEAD_LIMB": 10,
    "MIDLINE": 51,
    "TRAIL_LIMB": 3,
}


def _read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _write(path: Path, value: dict) -> None:
    path.write_text(annotation.render_json(value), encoding="utf-8", newline="\n")


def _refingerprint(value: dict) -> dict:
    result = copy.deepcopy(value)
    result["artifactSha256"] = annotation.artifact_sha256(result)
    return result


def _binding_key_text(value: dict) -> str:
    return annotation.canonical_json(value)


class PoseGoldAnnotationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract = annotation.compile_from_paths()
        cls.m9 = planning.load_json(M9, "M9", require_pretty_lf=True)

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
        return annotation.compile_from_paths(
            source_path=docs / SOURCE.name,
            policy_path=docs / POLICY.name,
            approval_path=docs / APPROVAL.name,
            registry_path=docs / REGISTRY.name,
            matrix_path=docs / MATRIX.name,
            decision_contract_path=docs / M9.name,
            project_root=root,
        )

    def _all_templates(self, contract: dict | None = None) -> list[dict]:
        artifact = self.contract if contract is None else contract
        return [
            row
            for exercise in artifact["exercises"]
            for row in exercise["decisionTemplates"]
        ]

    def _all_unresolved(self, contract: dict | None = None) -> list[dict]:
        artifact = self.contract if contract is None else contract
        return [
            row
            for exercise in artifact["exercises"]
            for row in exercise["unresolvedBindings"]
        ]

    def _profile(self, exercise_id: str) -> dict:
        return next(
            row
            for row in self.contract["registeredExerciseProfiles"]
            if row["exerciseId"] == exercise_id
        )

    def test_deterministic_self_hash_compiler_pin_lf_and_checked_output(self) -> None:
        second = annotation.compile_from_paths()
        self.assertEqual(second, self.contract)
        self.assertEqual(
            self.contract["artifactSha256"],
            annotation.artifact_sha256(self.contract),
        )
        self.assertEqual(
            self.contract["compilerImplementation"]["canonicalTextSha256"],
            annotation.canonical_lf_text_sha256(Path(annotation.__file__).resolve()),
        )
        raw = OUTPUT.read_bytes()
        self.assertNotIn(b"\r", raw)
        self.assertTrue(raw.endswith(b"\n"))
        self.assertEqual(raw, annotation.render_json(self.contract).encode("utf-8"))

    def test_exact_scope_and_full_203_phase_slot_product(self) -> None:
        self.assertEqual(self.contract["catalogScope"], annotation.EXPECTED_SCOPE)
        templates = self._all_templates()
        unresolved = self._all_unresolved()
        self.assertEqual(len(templates), 203)
        self.assertEqual(len(unresolved), 19)
        self.assertEqual(len({row["templateId"] for row in templates}), 203)
        self.assertEqual(len(self.contract["exercises"]), 41)

        actual = {
            (
                _binding_key_text(row["bindingKey"]),
                row["phaseRoleId"],
                row["sidePolicyKind"],
                row["symbolicSlot"],
                row["roleResolverContractId"],
            )
            for row in templates
        }
        expected = set()
        reviewed_binding_keys = set()
        unresolved_binding_keys = set()
        for exercise in self.m9["exercises"]:
            for binding in exercise["bindings"]:
                key_text = _binding_key_text(binding["bindingKey"])
                if binding["interpretationState"] == "SOURCE_INTERPRETATION_UNRESOLVED":
                    unresolved_binding_keys.add(key_text)
                    continue
                reviewed_binding_keys.add(key_text)
                side = binding["sidePolicy"]
                for phase_role_id in binding["phaseRoleIds"]:
                    for slot in side["symbolicSlots"]:
                        expected.add(
                            (
                                key_text,
                                phase_role_id,
                                side["kind"],
                                slot,
                                side["roleResolverContractId"],
                            )
                        )
        self.assertEqual(actual, expected)
        self.assertEqual(len(reviewed_binding_keys), 148)
        self.assertEqual(
            {_binding_key_text(row["bindingKey"]) for row in unresolved},
            unresolved_binding_keys,
        )
        self.assertFalse(
            reviewed_binding_keys
            & {_binding_key_text(row["bindingKey"]) for row in unresolved}
        )

    def test_six_symbolic_phase_roles_have_exact_template_counts_and_no_topology(self) -> None:
        contract = self.contract["phaseScopeReferenceContract"]
        self.assertEqual(contract["keyFields"], ["phaseRoleId", "occurrenceOrdinal"])
        self.assertEqual(
            contract["futureIntervalConvention"],
            "START_INCLUSIVE_END_EXCLUSIVE",
        )
        self.assertFalse(contract["timestampValueFieldsAllowed"])
        self.assertFalse(contract["runtimeIntervalModelIncluded"])
        self.assertFalse(contract["externalIntervalInputIncluded"])
        self.assertFalse(contract["topologyIncluded"])
        self.assertEqual(contract["currentPermittedScopeStates"], ["UNKNOWN_GOLD"])
        self.assertEqual(contract["determinateScopeStates"], [])
        roles = {row["phaseRoleId"]: row for row in contract["phaseRoles"]}
        self.assertEqual(set(roles), set(decision.PHASE_ROLE_IDS))
        self.assertEqual(
            {
                role_id: row["annotationDecisionTemplateCount"]
                for role_id, row in roles.items()
            },
            EXPECTED_PHASE_TEMPLATE_COUNTS,
        )
        self.assertEqual(
            sum(row["annotationDecisionTemplateCount"] for row in roles.values()),
            203,
        )
        for row in roles.values():
            self.assertEqual(row["approvedScopeState"], "NO_APPROVED_SCOPE_CONTRACT")
            self.assertIsNone(row["approvedScopeArtifactSha256"])
            self.assertFalse(row["determinateScopeEligible"])

    def test_nine_side_policies_preserve_ten_distinct_symbolic_slots(self) -> None:
        contract = self.contract["sideRoleReferenceContract"]
        policies = {row["sidePolicyKind"]: row for row in contract["sidePolicies"]}
        self.assertEqual(set(policies), set(decision.SIDE_POLICY_SYMBOLIC_SLOTS))
        self.assertEqual(len(contract["symbolicSlots"]), 10)
        self.assertEqual(len(set(contract["symbolicSlots"])), 10)
        self.assertFalse(contract["staticSymbolicSlotAliasingAllowed"])
        self.assertFalse(contract["anatomicalAssignmentIncluded"])
        self.assertEqual(contract["currentPermittedSideStates"], ["UNKNOWN_GOLD"])
        self.assertEqual(contract["determinateSideStates"], [])
        for kind, slots in decision.SIDE_POLICY_SYMBOLIC_SLOTS.items():
            row = policies[kind]
            self.assertEqual(row["symbolicSlots"], slots)
            self.assertEqual(
                row["annotationDecisionTemplateCount"],
                EXPECTED_SIDE_TEMPLATE_COUNTS[kind],
            )
            relative = kind in decision.ROLE_RELATIVE_SIDE_POLICIES
            self.assertEqual(row["roleRelative"], relative)
            self.assertEqual(
                row["resolverRequirement"],
                "EXACT_BINDING_POLICY_RESOLVER_REQUIRED"
                if relative
                else "NOT_APPLICABLE",
            )
            self.assertIsNone(row["resolverArtifactSha256"])
            self.assertFalse(row["anatomicalAssignmentIncluded"])
        self.assertEqual(sum(EXPECTED_SIDE_TEMPLATE_COUNTS.values()), 203)
        self.assertEqual(policies["BILATERAL_COUPLED"]["symbolicSlots"], ["BILATERAL_PAIR"])
        self.assertEqual(
            policies["BILATERAL_INDEPENDENT"]["symbolicSlots"],
            ["LEFT", "RIGHT"],
        )

    def test_templates_preserve_exact_m9_evidence_blockers_and_unknown_eligibility(self) -> None:
        source_by_key = {
            _binding_key_text(binding["bindingKey"]): binding
            for exercise in self.m9["exercises"]
            for binding in exercise["bindings"]
            if binding["interpretationState"] == "REVIEWED_POLICY_PROJECTION"
        }
        for row in self._all_templates():
            source = source_by_key[_binding_key_text(row["bindingKey"])]
            self.assertEqual(row["reviewState"], source["reviewState"])
            self.assertEqual(row["interpretationState"], source["interpretationState"])
            self.assertIn(row["phaseRoleId"], source["phaseRoleIds"])
            self.assertEqual(row["sidePolicyKind"], source["sidePolicy"]["kind"])
            self.assertIn(row["symbolicSlot"], source["sidePolicy"]["symbolicSlots"])
            self.assertEqual(
                row["roleResolverContractId"],
                source["sidePolicy"]["roleResolverContractId"],
            )
            self.assertEqual(row["evidenceRequirements"], source["evidenceRequirements"])
            self.assertEqual(row["decisionEligibility"], source["decisionEligibility"])
            self.assertEqual(row["blockers"], row["decisionEligibility"]["blockers"])
            self.assertEqual(row["currentGoldState"], "UNKNOWN_GOLD")
            self.assertEqual(
                row["decisionEligibility"]["permittedGoldStates"],
                ["UNKNOWN_GOLD"],
            )
            self.assertEqual(row["decisionEligibility"]["determinateGoldStates"], [])

    def test_unresolved_bindings_are_explicit_and_generate_zero_templates(self) -> None:
        unresolved_keys = {
            _binding_key_text(row["bindingKey"]) for row in self._all_unresolved()
        }
        template_keys = {
            _binding_key_text(row["bindingKey"]) for row in self._all_templates()
        }
        self.assertEqual(len(unresolved_keys), 19)
        self.assertFalse(unresolved_keys & template_keys)
        for row in self._all_unresolved():
            self.assertEqual(row["annotationDecisionTemplateCount"], 0)
            self.assertEqual(row["phaseRoleIds"], [])
            self.assertIsNone(row["sidePolicy"])
            self.assertIsNone(row["evidenceRequirements"])
            self.assertEqual(
                row["decisionEligibility"]["state"],
                "SOURCE_INTERPRETATION_UNRESOLVED",
            )

    def test_per_exercise_template_histogram_and_counts_are_exact(self) -> None:
        histogram = Counter(
            exercise["annotationDecisionTemplateCount"]
            for exercise in self.contract["exercises"]
        )
        self.assertEqual(histogram, Counter({2: 2, 3: 8, 4: 8, 5: 11, 6: 4, 7: 3, 8: 2, 9: 3}))
        for exercise in self.contract["exercises"]:
            self.assertEqual(
                exercise["bindingCount"],
                exercise["reviewedBindingCount"] + exercise["unresolvedBindingCount"],
            )
            self.assertEqual(exercise["determinateEligibleTemplateCount"], 0)

    def test_registered_profiles_preserve_plans_and_squat_standing_semantics(self) -> None:
        source_profiles = {
            row["exerciseId"]: row for row in self.m9["registeredExerciseProfiles"]
        }
        profiles = {
            row["exerciseId"]: row
            for row in self.contract["registeredExerciseProfiles"]
        }
        self.assertEqual(set(profiles), set(source_profiles))
        for exercise_id, profile in profiles.items():
            self.assertEqual(profile["planReference"], source_profiles[exercise_id]["planReference"])
            self.assertEqual(len(profile["decisionTemplateIds"]), profile["annotationDecisionTemplateCount"])
            self.assertEqual(profile["determinateEligibleTemplateCount"], 0)

        squat = profiles["barbell-squat"]
        self.assertEqual(squat["annotationDecisionTemplateCount"], 6)
        self.assertEqual(
            squat["phaseRoleTemplateCounts"],
            {"trex.phase-role.full-cycle.v1": 6},
        )
        self.assertEqual(
            squat["symbolicSlotTemplateCounts"],
            {"LEFT": 2, "MIDLINE": 2, "RIGHT": 2},
        )

        standing = profiles["standing-side-crunch"]
        self.assertEqual(standing["annotationDecisionTemplateCount"], 5)
        self.assertEqual(
            standing["phaseRoleTemplateCounts"],
            {
                "trex.phase-role.contracted-endpoint.v1": 2,
                "trex.phase-role.full-cycle.v1": 3,
            },
        )
        self.assertEqual(
            standing["symbolicSlotTemplateCounts"],
            {
                "ACTIVE_LIMB": 1,
                "BILATERAL_PAIR": 1,
                "CONTRALATERAL_PAIR": 1,
                "MIDLINE": 2,
            },
        )

    def test_unknown_conformance_envelopes_are_exact_for_both_registered_profiles(self) -> None:
        expected_shapes = {
            "barbell-squat": (1, 3, 6),
            "standing-side-crunch": (2, 4, 5),
        }
        for exercise_id, expected_shape in expected_shapes.items():
            with self.subTest(exercise_id=exercise_id):
                profile = self._profile(exercise_id)
                envelope = annotation.build_unknown_conformance_envelope(exercise_id)
                annotation.validate_unknown_conformance_envelope(
                    envelope,
                    exercise_id,
                )
                self.assertEqual(
                    (
                        len(envelope["phaseScopeReferences"]),
                        len(envelope["sideRoleReferences"]),
                        len(envelope["criterionDecisions"]),
                    ),
                    expected_shape,
                )
                self.assertEqual(envelope["authority"], planning.AUTHORITY_ZERO)
                self.assertEqual(
                    envelope["syntheticUnit"],
                    {
                        "unitKind": "UNKNOWN_GOLD_REFERENCE_CONFORMANCE_ONLY",
                        "occurrenceOrdinal": 0,
                        "goldState": "UNKNOWN_GOLD",
                    },
                )
                self.assertEqual(
                    {row["templateId"] for row in envelope["criterionDecisions"]},
                    set(profile["decisionTemplateIds"]),
                )
                self.assertTrue(
                    all(
                        row["scopeState"] == "UNKNOWN_GOLD"
                        and row["scopeApprovalState"] == "NO_APPROVED_SCOPE_CONTRACT"
                        and row["occurrenceOrdinal"] == 0
                        and row["approvedScopeArtifactSha256"] is None
                        for row in envelope["phaseScopeReferences"]
                    )
                )
                self.assertTrue(
                    all(
                        row["goldState"] == "UNKNOWN_GOLD"
                        for row in envelope["criterionDecisions"]
                    )
                )
                phase_refs = {
                    (row["phaseRoleId"], row["occurrenceOrdinal"])
                    for row in envelope["phaseScopeReferences"]
                }
                used_phase_refs = {
                    (
                        row["phaseScopeReference"]["phaseRoleId"],
                        row["phaseScopeReference"]["occurrenceOrdinal"],
                    )
                    for row in envelope["criterionDecisions"]
                }
                self.assertEqual(phase_refs, used_phase_refs)
                side_refs = {
                    (
                        row["sidePolicyKind"],
                        row["symbolicSlot"],
                        row["roleResolverContractId"],
                    )
                    for row in envelope["sideRoleReferences"]
                }
                used_side_refs = {
                    (
                        row["sideRoleReference"]["sidePolicyKind"],
                        row["sideRoleReference"]["symbolicSlot"],
                        row["sideRoleReference"]["roleResolverContractId"],
                    )
                    for row in envelope["criterionDecisions"]
                }
                self.assertEqual(side_refs, used_side_refs)

    def test_unknown_envelope_adversarial_mutations_fail_closed(self) -> None:
        exercise_id = "standing-side-crunch"
        base = annotation.build_unknown_conformance_envelope(exercise_id)
        mutations = (
            "determinate",
            "scope_approved",
            "timestamp",
            "resolver_artifact",
            "anatomical_assignment",
            "wrong_template",
            "wrong_binding",
            "missing_decision",
            "duplicate_decision",
            "missing_phase_ref",
            "extra_phase_ref",
            "duplicate_phase_ref",
            "missing_side_ref",
            "extra_side_ref",
            "duplicate_side_ref",
            "wrong_phase",
            "wrong_slot",
            "wrong_resolver",
            "authority",
            "pii",
            "private_key",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                changed = copy.deepcopy(base)
                if mutation == "determinate":
                    changed["criterionDecisions"][0]["goldState"] = "CONDITION_SATISFIED"
                elif mutation == "scope_approved":
                    changed["phaseScopeReferences"][0]["scopeApprovalState"] = "APPROVED"
                elif mutation == "timestamp":
                    changed["phaseScopeReferences"][0]["startTimestampMs"] = 0
                elif mutation == "resolver_artifact":
                    target = next(
                        row
                        for row in changed["sideRoleReferences"]
                        if row["roleResolverContractId"] is not None
                    )
                    target["resolverArtifactSha256"] = "1" * 64
                elif mutation == "anatomical_assignment":
                    changed["sideRoleReferences"][0]["anatomicalAssignment"] = "LEFT"
                elif mutation == "wrong_template":
                    changed["criterionDecisions"][0]["templateId"] = (
                        "trex.annotation-template-sha256-" + "0" * 64
                    )
                elif mutation == "wrong_binding":
                    changed["criterionDecisions"][0]["bindingKey"]["bindingId"] = (
                        "aihub-binding-sha256-" + "0" * 64
                    )
                elif mutation == "missing_decision":
                    changed["criterionDecisions"].pop()
                elif mutation == "duplicate_decision":
                    changed["criterionDecisions"].append(
                        copy.deepcopy(changed["criterionDecisions"][0])
                    )
                elif mutation == "missing_phase_ref":
                    changed["phaseScopeReferences"].pop()
                elif mutation == "extra_phase_ref":
                    changed["phaseScopeReferences"].append(
                        {
                            "phaseRoleId": "trex.phase-role.static-hold.v1",
                            "occurrenceOrdinal": 0,
                            "scopeState": "UNKNOWN_GOLD",
                            "scopeApprovalState": "NO_APPROVED_SCOPE_CONTRACT",
                            "approvedScopeArtifactSha256": None,
                        }
                    )
                elif mutation == "duplicate_phase_ref":
                    changed["phaseScopeReferences"].append(
                        copy.deepcopy(changed["phaseScopeReferences"][0])
                    )
                elif mutation == "missing_side_ref":
                    changed["sideRoleReferences"].pop()
                elif mutation == "extra_side_ref":
                    changed["sideRoleReferences"].append(
                        {
                            "sidePolicyKind": "MIDLINE",
                            "symbolicSlot": "GLOBAL_BODY",
                            "roleResolverContractId": None,
                            "resolverApprovalState": "NOT_APPLICABLE",
                            "resolverArtifactSha256": None,
                            "anatomicalAssignment": None,
                            "sideState": "UNKNOWN_GOLD",
                        }
                    )
                elif mutation == "duplicate_side_ref":
                    changed["sideRoleReferences"].append(
                        copy.deepcopy(changed["sideRoleReferences"][0])
                    )
                elif mutation == "wrong_phase":
                    changed["criterionDecisions"][0]["phaseScopeReference"][
                        "phaseRoleId"
                    ] = "trex.phase-role.static-hold.v1"
                elif mutation == "wrong_slot":
                    changed["criterionDecisions"][0]["sideRoleReference"][
                        "symbolicSlot"
                    ] = "LEFT"
                elif mutation == "wrong_resolver":
                    changed["criterionDecisions"][0]["sideRoleReference"][
                        "roleResolverContractId"
                    ] = "trex.role-resolver.drift.v1"
                elif mutation == "authority":
                    changed["authority"]["cueAuthority"] = 1
                elif mutation == "pii":
                    changed["criterionDecisions"][0]["participantId"] = "person"
                else:
                    changed["criterionDecisions"][0]["privateKey"] = "secret"
                with self.assertRaises(annotation.AnnotationContractError):
                    annotation.validate_unknown_conformance_envelope(
                        changed,
                        exercise_id,
                    )

    def test_public_unknown_envelope_api_rebuilds_repository_inputs_and_rejects_mappings(self) -> None:
        self.assertEqual(
            list(inspect.signature(annotation.build_unknown_conformance_envelope).parameters),
            ["exercise_id"],
        )
        self.assertEqual(
            list(inspect.signature(annotation.validate_unknown_conformance_envelope).parameters),
            ["envelope", "exercise_id"],
        )
        exercise_id = "standing-side-crunch"
        envelope = annotation.build_unknown_conformance_envelope(exercise_id)
        annotation.validate_unknown_conformance_envelope(envelope, exercise_id)

        with self.assertRaisesRegex(
            annotation.AnnotationContractError,
            "registered repository profile",
        ):
            annotation.build_unknown_conformance_envelope("unregistered-exercise")

        changed_schema = copy.deepcopy(self.contract)
        profile = next(
            row
            for row in changed_schema["registeredExerciseProfiles"]
            if row["exerciseId"] == exercise_id
        )
        profile["exerciseId"] = "participant-alice"
        exercise = next(
            row for row in changed_schema["exercises"] if row["exerciseId"] == exercise_id
        )
        exercise["exerciseId"] = "participant-alice"
        changed_schema = _refingerprint(changed_schema)
        with self.assertRaises(annotation.AnnotationContractError):
            annotation.build_unknown_conformance_envelope(changed_schema)  # type: ignore[arg-type]
        with self.assertRaises(annotation.AnnotationContractError):
            annotation.validate_unknown_conformance_envelope(
                envelope,
                changed_schema,  # type: ignore[arg-type]
            )

    def test_refingerprinted_output_authority_scope_side_template_and_evidence_drift_fails(self) -> None:
        mutations = (
            "determinate",
            "approved_scope",
            "approved_resolver",
            "anatomical_assignment",
            "evidence",
            "blockers",
            "eligibility",
            "missing_template",
            "duplicate_template",
            "authority",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                changed = copy.deepcopy(self.contract)
                template = changed["exercises"][0]["decisionTemplates"][0]
                if mutation == "determinate":
                    template["currentGoldState"] = "CONDITION_VIOLATED"
                elif mutation == "approved_scope":
                    changed["phaseScopeReferenceContract"]["phaseRoles"][0][
                        "approvedScopeState"
                    ] = "APPROVED"
                elif mutation == "approved_resolver":
                    target = next(
                        row
                        for row in changed["sideRoleReferenceContract"]["sidePolicies"]
                        if row["roleRelative"]
                    )
                    target["resolverApprovalState"] = "APPROVED"
                elif mutation == "anatomical_assignment":
                    changed["sideRoleReferenceContract"]["anatomicalAssignmentIncluded"] = True
                elif mutation == "evidence":
                    template["evidenceRequirements"]["observability"] = "NOT_OBSERVABLE"
                elif mutation == "blockers":
                    template["blockers"] = []
                elif mutation == "eligibility":
                    template["decisionEligibility"]["state"] = "DETERMINATE_ALLOWED"
                elif mutation == "missing_template":
                    changed["exercises"][0]["decisionTemplates"].pop()
                elif mutation == "duplicate_template":
                    changed["exercises"][0]["decisionTemplates"].append(
                        copy.deepcopy(template)
                    )
                else:
                    changed["authority"]["releaseAuthority"] = 1
                changed = _refingerprint(changed)
                with self.assertRaises(annotation.AnnotationContractError):
                    annotation.validate_contract(changed, expected=self.contract)

    def test_refingerprinted_m9_binding_evidence_profile_and_exact_set_drift_fails(self) -> None:
        mutations = (
            "phase",
            "slot",
            "resolver",
            "evidence",
            "eligibility",
            "profile",
            "missing",
            "duplicate",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                temporary, root = self._workspace()
                try:
                    m9_path = root / "docs" / M9.name
                    m9 = _read(m9_path)
                    reviewed = [
                        binding
                        for exercise in m9["exercises"]
                        for binding in exercise["bindings"]
                        if binding["interpretationState"] == "REVIEWED_POLICY_PROJECTION"
                    ]
                    if mutation == "phase":
                        reviewed[0]["phaseRoleIds"] = ["trex.phase-role.static-hold.v1"]
                    elif mutation == "slot":
                        reviewed[0]["sidePolicy"]["symbolicSlots"] = ["MIDLINE"]
                    elif mutation == "resolver":
                        target = next(
                            row
                            for row in reviewed
                            if row["sidePolicy"]["roleResolverContractId"] is not None
                        )
                        target["sidePolicy"]["roleResolverContractId"] = (
                            "trex.role-resolver.drift.v1"
                        )
                    elif mutation == "evidence":
                        reviewed[0]["evidenceRequirements"]["requiredCapabilityIds"] = [
                            "trex.capability.drift.v1"
                        ]
                    elif mutation == "eligibility":
                        reviewed[0]["decisionEligibility"]["state"] = "DETERMINATE_ALLOWED"
                    elif mutation == "profile":
                        m9["registeredExerciseProfiles"][0]["planReference"][
                            "artifactSha256"
                        ] = "0" * 64
                    elif mutation == "missing":
                        m9["exercises"][0]["bindings"].pop()
                    else:
                        m9["exercises"][0]["bindings"].append(
                            copy.deepcopy(m9["exercises"][0]["bindings"][0])
                        )
                    _write(m9_path, _refingerprint(m9))
                    with self.assertRaises(annotation.AnnotationContractError):
                        self._compile_workspace(root)
                finally:
                    temporary.cleanup()

    def test_bad_hash_duplicate_nonfinite_non_nfc_and_symlinked_m9_fail(self) -> None:
        for mutation in ("hash", "duplicate", "nonfinite", "non_nfc", "symlink"):
            with self.subTest(mutation=mutation):
                temporary, root = self._workspace()
                try:
                    m9_path = root / "docs" / M9.name
                    if mutation == "hash":
                        m9 = _read(m9_path)
                        m9["artifactSha256"] = "0" * 64
                        _write(m9_path, m9)
                    elif mutation == "duplicate":
                        text = m9_path.read_text(encoding="utf-8")
                        text = text.replace(
                            '"artifactKind":',
                            '"schemaVersion": 2,\n  "artifactKind":',
                            1,
                        )
                        m9_path.write_text(text, encoding="utf-8", newline="\n")
                    elif mutation == "nonfinite":
                        text = m9_path.read_text(encoding="utf-8")
                        text = text.replace('"schemaVersion": 2', '"schemaVersion": NaN', 1)
                        m9_path.write_text(text, encoding="utf-8", newline="\n")
                    elif mutation == "non_nfc":
                        m9 = _read(m9_path)
                        m9["decisionUse"] = "A\u030A"
                        _write(m9_path, _refingerprint(m9))
                    else:
                        real_path = root / "docs" / "real-m9.json"
                        m9_path.replace(real_path)
                        try:
                            os.symlink(real_path, m9_path)
                        except OSError as error:
                            self.skipTest(f"symlink unavailable: {error}")
                    with self.assertRaises(annotation.AnnotationContractError):
                        self._compile_workspace(root)
                finally:
                    temporary.cleanup()

    def test_output_confinement_atomic_replace_check_and_simulated_reparse(self) -> None:
        temporary, root = self._workspace()
        self.addCleanup(temporary.cleanup)
        docs = root / "docs"
        with self.assertRaises(annotation.AnnotationContractError):
            annotation._safe_output_path(
                docs / "other.json",
                project_root=root,
                input_paths=[],
            )
        with self.assertRaises(annotation.AnnotationContractError):
            annotation._safe_output_path(
                root.parent / OUTPUT.name,
                project_root=root,
                input_paths=[],
            )
        output = annotation._safe_output_path(
            docs / OUTPUT.name,
            project_root=root,
            input_paths=[docs / M9.name],
        )
        annotation.write_or_check(output, self.contract, check=False)
        annotation.write_or_check(output, self.contract, check=True)
        annotation.write_or_check(output, self.contract, check=False)
        changed = annotation.compile_annotation_contract(
            decision_contract=self.m9,
            compiler_implementation_sha256="0" * 64,
        )
        with self.assertRaisesRegex(annotation.AnnotationContractError, "stale"):
            annotation.write_or_check(output, changed, check=True)
        with mock.patch.object(
            planning,
            "_is_reparse",
            side_effect=lambda path: path == docs,
        ):
            with self.assertRaises(annotation.AnnotationContractError):
                annotation._safe_output_path(
                    docs / OUTPUT.name,
                    project_root=root,
                    input_paths=[docs / M9.name],
                )

    def test_zero_authority_no_runtime_or_external_input_and_no_exercise_branches(self) -> None:
        self.assertEqual(self.contract["authority"], planning.AUTHORITY_ZERO)
        self.assertTrue(
            all(value is False for value in self.contract["contractBoundary"].values())
        )
        self.assertEqual(
            self.contract["criterionDecisionReferenceContract"]["determinateGoldStates"],
            [],
        )
        source = Path(annotation.__file__).read_text(encoding="utf-8")
        tree = ast.parse(source)
        exercise_ids = {
            exercise["exerciseId"] for exercise in self.m9["exercises"]
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
        for function_name in function_names - {"_parser"}:
            for forbidden in (
                "evaluate",
                "score",
                "cue",
                "session",
                "verdict",
                "feedback",
                "parse_restricted",
                "parse_real",
            ):
                self.assertNotIn(forbidden, function_name)

    def test_package_and_script_check_modes(self) -> None:
        commands = (
            [sys.executable, "-m", "tools.compile_pose_gold_annotation_contract", "--check"],
            [sys.executable, str(Path("tools") / "compile_pose_gold_annotation_contract.py"), "--check"],
        )
        for command in commands:
            with self.subTest(command=command):
                result = subprocess.run(
                    command,
                    cwd=ROOT,
                    capture_output=True,
                    text=True,
                    timeout=30,
                    check=False,
                )
                self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
