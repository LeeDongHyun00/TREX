import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


# `compile_aihub_criterion_policy` intentionally remains an executable tool module whose
# sibling catalog generator is imported by its script name.  Make that import layout work both
# for direct discovery under tools/ and for `python -m unittest tools.test_...` from repo root.
TOOLS_DIR = Path(__file__).resolve().parent
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

import compile_aihub_criterion_policy as compiler  # noqa: E402


SHA_A = "a" * 64
SHA_B = "b" * 64
SHA_C = "c" * 64
CONDITION_TEXT_ONE = "condition one"
CONDITION_TEXT_TWO = "condition two"
CONDITION_ONE = "aihub-exact-sha256-" + hashlib.sha256(
    CONDITION_TEXT_ONE.encode("utf-8")
).hexdigest()
CONDITION_TWO = "aihub-exact-sha256-" + hashlib.sha256(
    CONDITION_TEXT_TWO.encode("utf-8")
).hexdigest()


def _evidence(name: str, digest: str = SHA_C) -> str:
    return f"docs/{name}.json@sha256:{digest}"


def _fingerprint(value: dict) -> dict:
    result = copy.deepcopy(value)
    result["artifactSha256"] = compiler.canonical_json_sha256(result)
    return result


def _source() -> dict:
    def condition(condition_id: str, text: str, exercises: list[str]) -> dict:
        return {
            "id": condition_id,
            "normalizedExactText": text,
            "rawTextAliases": [text],
            "exerciseIds": exercises,
            "exerciseAssignmentCount": len(exercises),
            "typeOccurrenceCount": 0,
            "trueRecordCount": 0,
            "falseRecordCount": 0,
            "semanticAliasPolicy": "EXACT_SOURCE_TEXT_ONLY",
        }

    def assignment(ordinal: int, condition_id: str, text: str) -> dict:
        return {
            "ordinal": ordinal,
            "conditionId": condition_id,
            "normalizedExactText": text,
            "rawTextAliases": [text],
            "trueTypeCount": 0,
            "falseTypeCount": 0,
            "trueRecordCount": 0,
            "falseRecordCount": 0,
        }

    def exercise(exercise_id: str, assignments: list[dict]) -> dict:
        return {
            "id": exercise_id,
            "normalizedSourceName": exercise_id,
            "rawSourceNameAliases": [exercise_id],
            "recordCount": 0,
            "typeCount": 0,
            "conditionAssignmentCount": len(assignments),
            "conditions": assignments,
            "types": [],
            "truthVectorCollisionGroups": [],
        }

    return _fingerprint(
        {
            "schemaVersion": 1,
            "artifactKind": "AIHUB_CRITERION_COVERAGE",
            "authority": "CATALOG_AND_LABEL_PROVENANCE_ONLY_NOT_RUNTIME_RELEASE",
            "sourceProvenance": {
                "dataset": "synthetic fixture",
                "catalog": {
                    "path": "catalog.json",
                    "schemaVersion": 1,
                    "catalogSha256": SHA_A,
                    "canonicalTextFileSha256": SHA_A,
                },
                "twoDMetadataAudit": {
                    "sourceRoot": "synthetic",
                    "scope": "fixture",
                    "excluded": [],
                    "textIdentity": "UTF8_NFC",
                    "metadataSetSha256": SHA_B,
                },
                "quarantineRegistry": {
                    "path": "quarantine.json",
                    "schemaVersion": 1,
                    "registrySha256": SHA_C,
                },
            },
            "manifest": {
                "exerciseCount": 2,
                "typeCount": 0,
                "twoDRecordCount": 0,
                "exactConditionCount": 2,
                "exerciseConditionAssignmentCount": 3,
                "truthVectorCollisionExerciseCount": 0,
                "truthVectorCollisionGroupCount": 0,
                "truthVectorCollisionTypeCount": 0,
                "truthVectorExcessTypeCount": 0,
                "quarantinedTypeCount": 0,
                "quarantinedRecordCount": 0,
            },
            "conditionRegistry": [
                condition(
                    CONDITION_ONE,
                    CONDITION_TEXT_ONE,
                    ["barbell-curl", "barbell-deadlift"],
                ),
                condition(CONDITION_TWO, CONDITION_TEXT_TWO, ["barbell-curl"]),
            ],
            "exercises": [
                exercise(
                    "barbell-curl",
                    [
                        assignment(0, CONDITION_ONE, CONDITION_TEXT_ONE),
                        assignment(1, CONDITION_TWO, CONDITION_TEXT_TWO),
                    ],
                ),
                exercise(
                    "barbell-deadlift",
                    [assignment(0, CONDITION_ONE, CONDITION_TEXT_ONE)],
                ),
            ],
            "labelQuarantine": {"policy": "fixture", "entries": []},
        }
    )


def _source_reference(source: dict) -> dict:
    return {
        "catalogSha256": source["sourceProvenance"]["catalog"]["catalogSha256"],
        "coverageArtifactSha256": source["artifactSha256"],
        "metadataSetSha256": source["sourceProvenance"]["twoDMetadataAudit"][
            "metadataSetSha256"
        ],
    }


def _interpretation(
    condition_id: str,
    *,
    observability: str = "DIRECT",
) -> dict:
    digest = condition_id.removeprefix("aihub-exact-sha256-")
    return {
        "semanticId": f"aihub.condition.exact.{digest}.v1",
        "semanticFamilyId": "aihub.family.gross-joint-alignment.v1",
        "measurementConstructId": "trex.construct.gross-coordinate-alignment.v1",
        "claimBoundary": "Camera-qualified gross pose relationship only; no clinical claim.",
        "observability": observability,
        "phaseApplicability": {
            "state": "BOUND",
            "phaseRoleIds": ["trex.phase.active-motion.v1"],
        },
        "sidePolicy": {
            "kind": "MIDLINE",
            "roleResolverContractId": None,
        },
        "viewApplicability": {
            "state": "QUALIFIED_VIEW_REQUIRED",
            "viewContractIds": ["trex.view.front-full-body.v1"],
        },
        "requiredCapabilityIds": [
            "trex.capability.pose-2d.v1",
            "trex.capability.primary-person-lock.v1",
            "trex.capability.view-qualified.v1",
        ],
        "calibrationProvenance": {
            "state": "NO_APPROVED_ARTIFACT",
            "artifactSha256": None,
            "runtimeDomainId": None,
            "evidenceRefs": [_evidence("engineering-calibration-review")],
        },
        "unsupportedReasonCodes": ["NO_APPROVED_RUNTIME_CALIBRATION"],
        "reviewEvidenceRefs": [_evidence("engineering-policy-review")],
    }


def _binding(
    exercise_id: str,
    condition_id: str,
    review_state: str,
) -> dict:
    reviewed = review_state == "REVIEWED_ENGINEERING_V1"
    if reviewed:
        reasons: list[str] = []
    elif review_state == "SOURCE_AMBIGUOUS_REQUIRES_ADJUDICATION":
        reasons = ["SOURCE_SEMANTICS_REQUIRE_ADJUDICATION"]
    else:
        reasons = ["PENDING_ENGINEERING_REVIEW"]
    return {
        "exerciseId": exercise_id,
        "sourceConditionId": condition_id,
        "reviewState": review_state,
        "releaseState": "CATALOG_ONLY",
        "reasonCodes": reasons,
        "decisionEvidenceRefs": [_evidence("binding-decision")],
        "interpretation": _interpretation(condition_id) if reviewed else None,
    }


def _policy(source: dict) -> dict:
    # Deliberately not source-key order.  Compilation must canonicalize bindings.
    return {
        "schemaVersion": 1,
        "artifactKind": "AIHUB_CURATED_CRITERION_POLICY",
        "authority": "CATALOG_ONLY_NOT_RUNTIME_RELEASE",
        "sourceCoverage": _source_reference(source),
        "bindings": [
            _binding("barbell-deadlift", CONDITION_ONE, "UNREVIEWED"),
            _binding("barbell-curl", CONDITION_TWO, "SOURCE_AMBIGUOUS_REQUIRES_ADJUDICATION"),
            _binding("barbell-curl", CONDITION_ONE, "REVIEWED_ENGINEERING_V1"),
        ],
    }


def _compile_without_approval(source: dict, policy: dict) -> dict:
    return compiler.compile_policy(
        source_artifact=source,
        policy=policy,
        approval=None,
        enforce_service_pins=False,
    )


def _approved(source: dict, policy: dict) -> tuple[dict, dict]:
    candidate = _compile_without_approval(source, policy)
    approval = compiler.approval_draft(candidate)
    compiled = compiler.compile_policy(
        source_artifact=source,
        policy=policy,
        approval=approval,
        enforce_service_pins=False,
    )
    return compiled, approval


class CriterionPolicyCompilerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = _source()
        self.policy = _policy(self.source)

    def assertPolicyError(self, function, pattern: str | None = None) -> None:
        context = self.assertRaises(compiler.PolicyError)
        with context:
            function()
        if pattern is not None:
            self.assertIn(pattern, str(context.exception))

    def test_valid_approved_policy_preserves_exact_inventory_and_provenance(self) -> None:
        source_before = copy.deepcopy(self.source)
        policy_before = copy.deepcopy(self.policy)
        compiled, approval = _approved(self.source, self.policy)

        self.assertEqual(source_before, self.source)
        self.assertEqual(policy_before, self.policy)
        self.assertEqual(2, compiled["manifest"]["exerciseCount"])
        self.assertEqual(2, compiled["manifest"]["conditionCount"])
        self.assertEqual(3, compiled["manifest"]["bindingCount"])
        self.assertEqual(1, compiled["manifest"]["reviewedBindingCount"])
        self.assertEqual(0, compiled["manifest"]["releaseEligibleBindingCount"])
        self.assertEqual(_source_reference(self.source), compiled["sourceCoverage"])
        self.assertEqual(
            compiler.canonical_json_sha256(approval),
            compiled["approvalArtifactSha256"],
        )
        self.assertRegex(compiled["policySha256"], r"^[0-9a-f]{64}$")
        self.assertRegex(compiled["registrySha256"], r"^[0-9a-f]{64}$")

        actual_keys = [
            (binding["exerciseId"], binding["sourceConditionId"])
            for binding in compiled["bindings"]
        ]
        self.assertEqual(sorted(actual_keys), actual_keys)
        self.assertEqual(
            compiler.binding_id("barbell-curl", CONDITION_ONE),
            next(
                binding["bindingId"]
                for binding in compiled["bindings"]
                if binding["exerciseId"] == "barbell-curl"
                and binding["sourceConditionId"] == CONDITION_ONE
            ),
        )

    def test_source_fingerprint_and_policy_approval_schemas_are_strict(self) -> None:
        corrupt_source = copy.deepcopy(self.source)
        corrupt_source["conditionRegistry"].append({"id": "aihub-exact-sha256-" + "3" * 64})
        self.assertPolicyError(
            lambda: _compile_without_approval(corrupt_source, self.policy),
            "fingerprint mismatch",
        )

        for target, extra_field in (
            ("policy", "unexpectedPolicyField"),
            ("binding", "unexpectedBindingField"),
            ("interpretation", "unexpectedInterpretationField"),
        ):
            policy = copy.deepcopy(self.policy)
            if target == "policy":
                policy[extra_field] = True
            elif target == "binding":
                policy["bindings"][0][extra_field] = True
            else:
                policy["bindings"][2]["interpretation"][extra_field] = True
            with self.subTest(target=target):
                self.assertPolicyError(lambda policy=policy: _compile_without_approval(self.source, policy))

        candidate = _compile_without_approval(self.source, self.policy)
        approval = compiler.approval_draft(candidate)
        approval["unexpectedApprovalField"] = True
        self.assertPolicyError(
            lambda: compiler.compile_policy(
                source_artifact=self.source,
                policy=self.policy,
                approval=approval,
                enforce_service_pins=False,
            ),
            "approval fields differ",
        )

    def test_json_numbers_strings_and_source_assignments_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            duplicate = root / "duplicate.json"
            duplicate.write_text('{"key": 1, "key": 2}', encoding="utf-8")
            self.assertPolicyError(
                lambda: compiler._load_json(duplicate, "duplicate fixture"),
                "duplicate object key",
            )
            nonfinite = root / "nonfinite.json"
            nonfinite.write_text('{"value": NaN}', encoding="utf-8")
            self.assertPolicyError(
                lambda: compiler._load_json(nonfinite, "nonfinite fixture"),
                "non-finite number",
            )

        boolean_policy = copy.deepcopy(self.policy)
        boolean_policy["schemaVersion"] = True
        self.assertPolicyError(
            lambda: _compile_without_approval(self.source, boolean_policy),
            "must be an integer",
        )

        candidate = _compile_without_approval(self.source, self.policy)
        boolean_approval = compiler.approval_draft(candidate)
        boolean_approval["approvedReviewedBindingCount"] = True
        self.assertPolicyError(
            lambda: compiler.compile_policy(
                source_artifact=self.source,
                policy=self.policy,
                approval=boolean_approval,
                enforce_service_pins=False,
            ),
            "must be an integer",
        )

        blank_claim = copy.deepcopy(self.policy)
        blank_claim["bindings"][2]["interpretation"]["claimBoundary"] = "   "
        self.assertPolicyError(
            lambda: _compile_without_approval(self.source, blank_claim),
            "non-empty string",
        )

        orphan_source = copy.deepcopy(self.source)
        orphan_source["exercises"][0]["conditions"].pop()
        orphan_source["exercises"][0]["conditionAssignmentCount"] = 1
        orphan_source["manifest"]["exerciseConditionAssignmentCount"] = 2
        orphan_source.pop("artifactSha256")
        orphan_source = _fingerprint(orphan_source)
        self.assertPolicyError(
            lambda: _compile_without_approval(orphan_source, self.policy),
            "orphan=1",
        )

        unknown_source_field = copy.deepcopy(self.source)
        unknown_source_field["conditionRegistry"][0]["unreviewedField"] = True
        unknown_source_field.pop("artifactSha256")
        unknown_source_field = _fingerprint(unknown_source_field)
        self.assertPolicyError(
            lambda: _compile_without_approval(unknown_source_field, self.policy),
            "source condition fields differ",
        )

        nfd_alias = copy.deepcopy(self.source)
        nfd_alias["conditionRegistry"][0]["rawTextAliases"] = ["e\u0301"]
        nfd_alias.pop("artifactSha256")
        nfd_alias = _fingerprint(nfd_alias)
        self.assertPolicyError(
            lambda: _compile_without_approval(nfd_alias, self.policy),
            "non-NFC string",
        )

        in_memory_nonfinite = copy.deepcopy(self.source)
        in_memory_nonfinite["labelQuarantine"]["unexpectedMetric"] = float("nan")
        in_memory_nonfinite.pop("artifactSha256")
        in_memory_nonfinite = _fingerprint(in_memory_nonfinite)
        self.assertPolicyError(
            lambda: _compile_without_approval(in_memory_nonfinite, self.policy),
            "non-finite number",
        )

    def test_policy_binding_exact_set_rejects_missing_duplicate_and_unexpected_pairs(self) -> None:
        missing = copy.deepcopy(self.policy)
        missing["bindings"].pop()
        self.assertPolicyError(
            lambda: _compile_without_approval(self.source, missing),
            "exact-set differs",
        )

        duplicate = copy.deepcopy(self.policy)
        duplicate["bindings"].append(copy.deepcopy(duplicate["bindings"][0]))
        self.assertPolicyError(
            lambda: _compile_without_approval(self.source, duplicate),
            "Duplicate policy binding",
        )

        unexpected = copy.deepcopy(self.policy)
        unexpected["bindings"].append(
            _binding("barbell-deadlift", CONDITION_TWO, "UNREVIEWED")
        )
        self.assertPolicyError(
            lambda: _compile_without_approval(self.source, unexpected),
            "exact-set differs",
        )

    def test_review_state_is_a_fail_closed_discriminated_union(self) -> None:
        cases: list[tuple[str, callable, str]] = []

        reviewed_without_interpretation = copy.deepcopy(self.policy)
        reviewed_without_interpretation["bindings"][2]["interpretation"] = None
        cases.append(("reviewed-null", lambda: _compile_without_approval(self.source, reviewed_without_interpretation), "interpretation"))

        reviewed_with_reason = copy.deepcopy(self.policy)
        reviewed_with_reason["bindings"][2]["reasonCodes"] = ["STILL_UNRESOLVED"]
        cases.append(("reviewed-reason", lambda: _compile_without_approval(self.source, reviewed_with_reason), "unresolved reasonCodes"))

        ambiguous_with_interpretation = copy.deepcopy(self.policy)
        ambiguous_with_interpretation["bindings"][1]["interpretation"] = _interpretation(CONDITION_TWO)
        cases.append(("ambiguous-interpretation", lambda: _compile_without_approval(self.source, ambiguous_with_interpretation), "cannot carry"))

        ambiguous_without_reason = copy.deepcopy(self.policy)
        ambiguous_without_reason["bindings"][1]["reasonCodes"] = []
        cases.append(("ambiguous-reason", lambda: _compile_without_approval(self.source, ambiguous_without_reason), "requires reasonCodes"))

        unreviewed_with_interpretation = copy.deepcopy(self.policy)
        unreviewed_with_interpretation["bindings"][0]["interpretation"] = _interpretation(CONDITION_ONE)
        cases.append(("unreviewed-interpretation", lambda: _compile_without_approval(self.source, unreviewed_with_interpretation), "cannot carry"))

        no_decision_provenance = copy.deepcopy(self.policy)
        no_decision_provenance["bindings"][0]["decisionEvidenceRefs"] = []
        cases.append(("missing-decision-evidence", lambda: _compile_without_approval(self.source, no_decision_provenance), "must not be empty"))

        for name, action, message in cases:
            with self.subTest(name=name):
                self.assertPolicyError(action, message)

    def test_source_policy_and_repository_pin_drift_all_fail(self) -> None:
        compiled, approval = _approved(self.source, self.policy)

        policy_source_drift = copy.deepcopy(self.policy)
        policy_source_drift["sourceCoverage"]["metadataSetSha256"] = "d" * 64
        self.assertPolicyError(
            lambda: _compile_without_approval(self.source, policy_source_drift),
            "source provenance differs",
        )

        source_drift = copy.deepcopy(self.source)
        source_drift["sourceProvenance"]["twoDMetadataAudit"]["metadataSetSha256"] = "d" * 64
        source_drift.pop("artifactSha256")
        source_drift = _fingerprint(source_drift)
        self.assertPolicyError(
            lambda: _compile_without_approval(source_drift, self.policy),
            "source provenance differs",
        )

        policy_mutation = copy.deepcopy(self.policy)
        policy_mutation["bindings"][2]["interpretation"]["claimBoundary"] += " Changed."
        mutated = _compile_without_approval(self.source, policy_mutation)
        self.assertNotEqual(compiled["policySha256"], mutated["policySha256"])
        self.assertPolicyError(
            lambda: compiler.compile_policy(
                source_artifact=self.source,
                policy=policy_mutation,
                approval=approval,
                enforce_service_pins=False,
            ),
            "approvedPolicySha256",
        )

        for field, value in (
            ("authority", "HUMAN_APPROVED"),
            ("approvalScope", "CUE_ELIGIBLE"),
            ("approvedSourceCoverageArtifactSha256", "0" * 64),
            ("approvedReviewedBindingSetSha256", "0" * 64),
            ("approvedReviewedBindingCount", 999),
        ):
            stale = copy.deepcopy(approval)
            stale[field] = value
            with self.subTest(field=field):
                self.assertPolicyError(
                    lambda stale=stale: compiler.compile_policy(
                        source_artifact=self.source,
                        policy=self.policy,
                        approval=stale,
                        enforce_service_pins=False,
                    )
                )

    def test_catalog_policy_cannot_authorize_runtime_release(self) -> None:
        release_attempt = copy.deepcopy(self.policy)
        release_attempt["bindings"][2]["releaseState"] = "CUE_ELIGIBLE"
        self.assertPolicyError(
            lambda: _compile_without_approval(self.source, release_attempt),
            "releaseState must remain CATALOG_ONLY",
        )

        compiled, _ = _approved(self.source, self.policy)
        kotlin = compiler.render_kotlin(compiled)
        self.assertEqual(0, compiled["manifest"]["releaseEligibleBindingCount"])
        self.assertEqual(
            {"CATALOG_ONLY"},
            {binding["releaseState"] for binding in compiled["bindings"]},
        )
        self.assertIn("cannot evaluate, score, or cue a user", kotlin)
        self.assertNotIn("CUE_ELIGIBLE", kotlin)
        self.assertNotIn("fun evaluate(", kotlin)
        self.assertNotIn("runtimeSpec", kotlin)

        unapproved = _compile_without_approval(self.source, self.policy)
        self.assertPolicyError(
            lambda: compiler.render_kotlin(unapproved),
            "Approved policy is required",
        )

    def test_proxy_gold_validated_is_rejected_without_approved_artifact(self) -> None:
        policy = copy.deepcopy(self.policy)
        policy["bindings"][2]["interpretation"]["observability"] = "PROXY_GOLD_VALIDATED"
        self.assertPolicyError(
            lambda: _compile_without_approval(self.source, policy),
            "separate approved release artifact",
        )

    def test_policy_hash_binding_order_and_renderer_are_deterministic(self) -> None:
        first, approval = _approved(self.source, self.policy)
        reordered = copy.deepcopy(self.policy)
        reordered["bindings"].reverse()
        second = compiler.compile_policy(
            source_artifact=self.source,
            policy=reordered,
            approval=approval,
            enforce_service_pins=False,
        )

        self.assertEqual(first["policySha256"], second["policySha256"])
        self.assertEqual(first["reviewedBindingSetSha256"], second["reviewedBindingSetSha256"])
        self.assertEqual(first["registrySha256"], second["registrySha256"])
        self.assertEqual(first["bindings"], second["bindings"])
        self.assertEqual(compiler.render_kotlin(first), compiler.render_kotlin(second))

        identity_payload = (
            "bindingIdSchemaVersion:1:1\n"
            "exerciseId:12:barbell-curl\n"
            f"sourceConditionId:{len(CONDITION_ONE.encode('utf-8'))}:{CONDITION_ONE}\n"
        )
        expected_id = "aihub-binding-sha256-" + hashlib.sha256(
            identity_payload.encode("utf-8")
        ).hexdigest()
        self.assertEqual(expected_id, compiler.binding_id("barbell-curl", CONDITION_ONE))

        kotlin = compiler.render_kotlin(first)
        self.assertTrue(kotlin.endswith("\n"))
        self.assertNotIn("\r", kotlin)
        self.assertIn("object AiHubCriterionPolicyCatalog", kotlin)
        self.assertIn("not expert, clinical, Gold, calibration, or release approval", kotlin)
        self.assertIn("const val REGISTRY_SHA256", kotlin)
        self.assertIn("approvedRegistrySha256 = REGISTRY_SHA256", kotlin)
        self.assertIn("AiHubCriterionPhaseApplicabilityState.BOUND", kotlin)
        self.assertIn("AiHubCriterionSidePolicyKind.MIDLINE", kotlin)
        self.assertIn("AiHubCriterionViewApplicabilityState.QUALIFIED_VIEW_REQUIRED", kotlin)
        self.assertIn(
            "AiHubCriterionCalibrationProvenanceState.NO_APPROVED_ARTIFACT",
            kotlin,
        )
        self.assertEqual(1, kotlin.count("fun binding("))
        self.assertEqual(1, kotlin.count("fun bindings("))
        self.assertEqual('"safe\\u000c\\$value"', compiler._kotlin_string("safe\f$value"))

    def test_json_line_endings_do_not_change_compilation(self) -> None:
        compiled, approval = _approved(self.source, self.policy)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "source.json"
            policy_path = root / "policy.json"
            approval_path = root / "approval.json"
            source_path.write_bytes(json.dumps(self.source, ensure_ascii=False, indent=2).replace("\n", "\r\n").encode("utf-8"))
            policy_path.write_bytes(json.dumps(self.policy, ensure_ascii=False, indent=2).encode("utf-8"))
            approval_path.write_bytes(json.dumps(approval, ensure_ascii=False, indent=2).replace("\n", "\r\n").encode("utf-8"))

            loaded = compiler.compile_policy(
                source_artifact=compiler._load_json(source_path, "source"),
                policy=compiler._load_json(policy_path, "policy"),
                approval=compiler._load_json(approval_path, "approval"),
                enforce_service_pins=False,
            )
            self.assertEqual(compiled["policySha256"], loaded["policySha256"])
            self.assertEqual(compiled["registrySha256"], loaded["registrySha256"])
            self.assertEqual(compiler.render_kotlin(compiled), compiler.render_kotlin(loaded))

    def test_repository_evidence_refs_are_content_verified_cross_platform(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "docs" / "review.md"
            evidence.parent.mkdir()
            evidence.write_bytes(b"first line\r\nsecond line\r\n")
            digest = hashlib.sha256(b"first line\nsecond line\n").hexdigest()
            compiled = {
                "bindings": [
                    {
                        "decisionEvidenceRefs": [
                            f"docs/review.md@sha256:{digest}",
                        ],
                        "interpretation": None,
                    }
                ]
            }

            compiler.verify_repository_evidence_refs(compiled, project_root=root)
            self.assertEqual(digest, compiler.canonical_text_file_sha256(evidence))

            evidence.write_text("changed\n", encoding="utf-8")
            self.assertPolicyError(
                lambda: compiler.verify_repository_evidence_refs(
                    compiled,
                    project_root=root,
                ),
                "Evidence artifact drift",
            )

            escaping = copy.deepcopy(compiled)
            escaping["bindings"][0]["decisionEvidenceRefs"] = [
                f"docs/../outside.md@sha256:{SHA_A}"
            ]
            self.assertPolicyError(
                lambda: compiler.verify_repository_evidence_refs(
                    escaping,
                    project_root=root,
                ),
                "not canonical under docs",
            )

            outside = root / "outside.md"
            outside.write_text("outside\n", encoding="utf-8")
            symlink = root / "docs" / "linked-outside.md"
            try:
                symlink.symlink_to(outside)
            except OSError:
                pass  # Windows developer mode may not permit symlink creation.
            else:
                linked = copy.deepcopy(compiled)
                linked["bindings"][0]["decisionEvidenceRefs"] = [
                    "docs/linked-outside.md@sha256:"
                    + compiler.canonical_text_file_sha256(outside)
                ]
                self.assertPolicyError(
                    lambda: compiler.verify_repository_evidence_refs(
                        linked,
                        project_root=root,
                    ),
                    "escapes docs",
                )

            unresolved = copy.deepcopy(compiled)
            unresolved["bindings"][0]["decisionEvidenceRefs"] = [
                f"external-review@sha256:{SHA_A}"
            ]
            self.assertPolicyError(
                lambda: compiler.verify_repository_evidence_refs(
                    unresolved,
                    project_root=root,
                ),
                "No verified evidence resolver",
            )

    def test_write_check_collision_and_atomic_failure_are_safe(self) -> None:
        compiled, _ = _approved(self.source, self.policy)
        kotlin = compiler.render_kotlin(compiled)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.json"
            policy = root / "policy.json"
            approval = root / "approval.json"
            for path in (source, policy, approval):
                path.write_text("protected", encoding="utf-8")

            for protected in (source, policy, approval):
                with self.subTest(protected=protected.name):
                    self.assertPolicyError(
                        lambda protected=protected: compiler._resolved_output(
                            protected,
                            protected_inputs=[source, policy, approval],
                        ),
                        "collides with protected input",
                    )

            generated_root = root / "generated"
            output = compiler._resolved_output(
                generated_root / "AiHubCriterionPolicyCatalog.kt",
                protected_inputs=[source, policy, approval],
                allowed_root=generated_root,
            )
            compiler.write_or_check(output, kotlin, check=False)
            self.assertEqual(kotlin.encode("utf-8"), output.read_bytes())
            self.assertNotIn(b"\r\n", output.read_bytes())
            compiler.write_or_check(output, kotlin, check=True)

            output.write_bytes(kotlin.replace("\n", "\r\n").encode("utf-8"))
            self.assertPolicyError(
                lambda: compiler.write_or_check(output, kotlin, check=True),
                "stale",
            )

            self.assertPolicyError(
                lambda: compiler._resolved_output(
                    root / "README.md",
                    protected_inputs=[source, policy, approval],
                    allowed_root=generated_root,
                ),
                "canonical AiHubCriterionPolicyCatalog.kt",
            )

            output.write_text("stale", encoding="utf-8")
            self.assertPolicyError(
                lambda: compiler.write_or_check(output, kotlin, check=True),
                "stale",
            )

            output.write_text("preserve-me", encoding="utf-8")
            with mock.patch.object(compiler.os, "replace", side_effect=OSError("replace failed")):
                with self.assertRaises(OSError):
                    compiler.atomic_write(output, kotlin)
            self.assertEqual("preserve-me", output.read_text(encoding="utf-8"))
            self.assertEqual([], list(output.parent.glob(f".{output.name}.*.tmp")))


if __name__ == "__main__":
    unittest.main()
