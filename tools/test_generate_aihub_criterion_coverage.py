import json
import tempfile
import unittest
from pathlib import Path

from generate_aihub_criterion_coverage import (
    CoverageError,
    atomic_write_text,
    canonical_text_file_sha256,
    canonical_sha256,
    generate,
    render_kotlin,
    validate_output_path,
    validate_output_paths,
    verify_artifact_fingerprint,
    write_or_check,
    write_text_or_check,
)


def _type_entry(code: str, count: int, values: list[bool]) -> dict:
    names = ["척추 중립", "무릎 정렬"][: len(values)]
    return {
        "code": code,
        "recordCount": count,
        "emptyFrameRecordCount": 0,
        "missingFrameRecordCount": 0,
        "nonEmptyFrameRecordCount": count,
        "typeInfoType": "맨몸 운동",
        "conditions": [
            {"condition": name, "value": value}
            for name, value in zip(names, values, strict=True)
        ],
    }


def _catalog() -> dict:
    first_types = [
        _type_entry("001", 2, [True, False]),
        _type_entry("002", 1, [False, False]),
    ]
    second_types = [
        {
            **_type_entry("003", 1, [True]),
            "conditions": [{"condition": "척추 중립", "value": True}],
        }
    ]
    catalog = {
        "schemaVersion": 1,
        "sourceRules": {
            "authoritativeFiles": "2D JSON only",
            "authoritativeFields": ["type", "type_info"],
            "textNormalization": "test fixture",
        },
        "manifest": {
            "jsonCount": 4,
            "twoDJsonCount": 4,
            "threeDJsonCount": 0,
            "pairedBasenameCount": 0,
            "twoDOnlyCount": 4,
            "threeDOnlyCount": 0,
            "exerciseCount": 2,
            "typeCount": 3,
            "recordCount": 4,
            "emptyFrameRecordCount": 0,
            "missingFrameRecordCount": 0,
            "nonEmptyFrameRecordCount": 4,
        },
        "exercises": [
            {
                "id": "barbell-curl",
                "name": "운동 A",
                "rawNames": ["운동 A"],
                "recordCount": 3,
                "emptyFrameRecordCount": 0,
                "missingFrameRecordCount": 0,
                "nonEmptyFrameRecordCount": 3,
                "typeCount": 2,
                "types": first_types,
            },
            {
                "id": "barbell-deadlift",
                "name": "운동 B",
                "rawNames": ["운동 B"],
                "recordCount": 1,
                "emptyFrameRecordCount": 0,
                "missingFrameRecordCount": 0,
                "nonEmptyFrameRecordCount": 1,
                "typeCount": 1,
                "types": second_types,
            },
        ],
    }
    catalog["catalogSha256"] = canonical_sha256(catalog)
    return catalog


def _source_payload(
    code: str,
    exercise: str,
    values: list[tuple[str, bool]],
    description: str,
) -> dict:
    return {
        "frames": [],
        "type": code,
        "type_info": {
            "key": code,
            "type": "맨몸 운동",
            "pose": "맨몸 운동",
            "exercise": exercise,
            "conditions": [
                {"condition": name, "value": value} for name, value in values
            ],
            "description": description,
        },
    }


def _write_fixture(root: Path, *, mismatch: bool = False) -> tuple[Path, Path, Path]:
    source = root / "source"
    source.mkdir()
    payloads = [
        (
            "z/record-2.json",
            _source_payload(
                "001",
                "운동 A",
                [("척추  중립", True), ("무릎 정렬", False)],
                "첫 번째 설명",
            ),
        ),
        (
            "a/record-1.json",
            _source_payload(
                "001",
                "운동 A ",
                [(" 척추\t 중립 ", True), ("무릎 정렬", False)],
                "첫 번째 설명",
            ),
        ),
        (
            "record-3.json",
            _source_payload(
                "002",
                "운동 A",
                [("척추  중립", mismatch), ("무릎 정렬", False)],
                "라벨 충돌 설명",
            ),
        ),
        (
            "record-4.json",
            _source_payload(
                "003",
                "운동 B",
                [("척추  중립", True)],
                "두 번째 운동 설명",
            ),
        ),
    ]
    for relative, payload in payloads:
        path = source / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    catalog_path = root / "catalog.json"
    catalog_path.write_text(
        json.dumps(_catalog(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    quarantine_path = root / "quarantine.json"
    quarantine_path.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "entries": [
                    {
                        "exerciseId": "barbell-curl",
                        "typeCode": "002",
                        "state": "QUARANTINED_PENDING_BLIND_GOLD",
                        "reasonCodes": ["SOURCE_DESCRIPTION_TRUTH_VECTOR_CONFLICT"],
                        "evidenceRefs": ["fixture-audit"],
                    }
                ],
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return source, catalog_path, quarantine_path


class CriterionCoverageGeneratorTest(unittest.TestCase):
    def test_preserves_raw_aliases_truth_vectors_counts_and_quarantine(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, catalog, quarantine = _write_fixture(root)
            artifact = generate(
                catalog_path=catalog,
                source_root=source,
                quarantine_path=quarantine,
                project_root=root,
                enforce_pins=False,
            )

        self.assertTrue(verify_artifact_fingerprint(artifact))
        self.assertEqual(2, artifact["manifest"]["exerciseCount"])
        self.assertEqual(3, artifact["manifest"]["typeCount"])
        self.assertEqual(2, artifact["manifest"]["exactConditionCount"])
        self.assertEqual(3, artifact["manifest"]["exerciseConditionAssignmentCount"])
        first_condition = next(
            condition
            for condition in artifact["conditionRegistry"]
            if condition["normalizedExactText"] == "척추 중립"
        )
        self.assertEqual([" 척추\t 중립 ", "척추  중립"], first_condition["rawTextAliases"])

        exercise = next(item for item in artifact["exercises"] if item["id"] == "barbell-curl")
        first_type = next(item for item in exercise["types"] if item["code"] == "001")
        quarantined = next(item for item in exercise["types"] if item["code"] == "002")
        self.assertEqual("10", first_type["truthVector"])
        self.assertEqual(2, first_type["recordCount"])
        self.assertEqual(["첫 번째 설명"], first_type["rawDescriptionTexts"])
        self.assertEqual("QUARANTINED_PENDING_BLIND_GOLD", quarantined["labelEligibility"]["state"])
        self.assertFalse(
            quarantined["labelEligibility"]["eligibleForAutomaticCriterionCalibration"]
        )
        self.assertEqual(1, artifact["manifest"]["quarantinedRecordCount"])

        kotlin = render_kotlin(artifact)
        self.assertIn("object AiHubCriterionSourceCatalog", kotlin)
        self.assertIn("const val CATALOG_SHA256", kotlin)
        self.assertIn("const val COVERAGE_ARTIFACT_SHA256", kotlin)
        self.assertIn("const val METADATA_SET_SHA256", kotlin)
        self.assertIn("val registry: AiHubCriterionSourceRegistry", kotlin)
        self.assertIn("fun coverage(exercise: AiHubExercise)", kotlin)
        self.assertIn("fun requireCoverage(exercise: AiHubExercise)", kotlin)
        self.assertIn("private fun barbellCurlCoverage()", kotlin)
        self.assertIn("private fun barbellDeadliftCoverage()", kotlin)
        self.assertIn("exercise = AiHubExercise.BARBELL_CURL", kotlin)
        self.assertIn("truthVector = \"10\"", kotlin)
        self.assertIn("AiHubSourceLabelState.QUARANTINED_PENDING_BLIND_GOLD", kotlin)
        self.assertIn("SOURCE_DESCRIPTION_TRUTH_VECTOR_CONFLICT", kotlin)

    def test_duplicate_truth_vector_is_reviewable_not_automatically_quarantined(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, catalog_path, quarantine = _write_fixture(root)
            catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
            catalog["exercises"][0]["types"][1]["conditions"][0]["value"] = True
            catalog["catalogSha256"] = canonical_sha256(
                {key: value for key, value in catalog.items() if key != "catalogSha256"}
            )
            catalog_path.write_text(json.dumps(catalog, ensure_ascii=False), encoding="utf-8")
            payload_path = source / "record-3.json"
            payload = json.loads(payload_path.read_text(encoding="utf-8"))
            payload["type_info"]["conditions"][0]["value"] = True
            payload_path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

            artifact = generate(
                catalog_path=catalog_path,
                source_root=source,
                quarantine_path=quarantine,
                project_root=root,
                enforce_pins=False,
            )

        self.assertEqual(1, artifact["manifest"]["truthVectorCollisionGroupCount"])
        self.assertEqual(1, artifact["manifest"]["truthVectorExcessTypeCount"])
        exercise = next(item for item in artifact["exercises"] if item["id"] == "barbell-curl")
        first_type = next(item for item in exercise["types"] if item["code"] == "001")
        self.assertEqual("COLLISION_REVIEW_REQUIRED", first_type["truthVectorIdentity"]["state"])
        self.assertTrue(first_type["labelEligibility"]["eligibleForAutomaticCriterionCalibration"])
        kotlin = render_kotlin(artifact)
        self.assertIn('collidingTypeCodes = listOf("001", "002")', kotlin)

    def test_source_truth_vector_drift_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, catalog, quarantine = _write_fixture(root, mismatch=True)
            with self.assertRaises(CoverageError):
                generate(
                    catalog_path=catalog,
                    source_root=source,
                    quarantine_path=quarantine,
                    project_root=root,
                    enforce_pins=False,
                )

    def test_service_snapshot_pins_fail_closed_on_other_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, catalog, quarantine = _write_fixture(root)
            with self.assertRaisesRegex(CoverageError, "Pinned AI Hub coverage count drift"):
                generate(
                    catalog_path=catalog,
                    source_root=source,
                    quarantine_path=quarantine,
                    project_root=root,
                    enforce_pins=True,
                )

    def test_output_is_atomic_checkable_and_outside_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, catalog, quarantine = _write_fixture(root)
            with self.assertRaises(CoverageError):
                validate_output_path(
                    source / "coverage.json",
                    source_root=source,
                    protected_inputs=[catalog, quarantine],
                )
            with self.assertRaises(CoverageError):
                validate_output_path(
                    catalog,
                    source_root=source,
                    protected_inputs=[catalog, quarantine],
                )
            with self.assertRaises(CoverageError):
                validate_output_paths(
                    root / "same-output",
                    root / "same-output",
                    source_root=source,
                    protected_inputs=[catalog, quarantine],
                )

            artifact = generate(
                catalog_path=catalog,
                source_root=source,
                quarantine_path=quarantine,
                project_root=root,
                enforce_pins=False,
            )
            output = root / "generated" / "coverage.json"
            kotlin_output = root / "generated" / "AiHubCriterionSourceCatalog.kt"
            validated_json, validated_kotlin = validate_output_paths(
                output,
                kotlin_output,
                source_root=source,
                protected_inputs=[catalog, quarantine],
            )
            self.assertEqual(output.resolve(), validated_json)
            self.assertEqual(kotlin_output.resolve(), validated_kotlin)
            write_or_check(output, artifact, check=False)
            write_or_check(output, artifact, check=True)
            kotlin = render_kotlin(artifact)
            write_text_or_check(kotlin_output, kotlin, check=False)
            write_text_or_check(kotlin_output, kotlin, check=True)
            self.assertEqual([], list(output.parent.glob("*.tmp")))
            stale = dict(artifact)
            stale["artifactSha256"] = "0" * 64
            with self.assertRaises(CoverageError):
                write_or_check(output, stale, check=True)
            kotlin_output.write_text(kotlin + "// stale\n", encoding="utf-8")
            with self.assertRaises(CoverageError):
                write_text_or_check(kotlin_output, kotlin, check=True)

            replacement = root / "generated" / "atomic.txt"
            atomic_write_text(replacement, "old\n")
            atomic_write_text(replacement, "new\n")
            self.assertEqual("new\n", replacement.read_text(encoding="utf-8"))

    def test_generation_is_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, catalog, quarantine = _write_fixture(root)
            first = generate(
                catalog_path=catalog,
                source_root=source,
                quarantine_path=quarantine,
                project_root=root,
                enforce_pins=False,
            )
            second = generate(
                catalog_path=catalog,
                source_root=source,
                quarantine_path=quarantine,
                project_root=root,
                enforce_pins=False,
            )

        self.assertEqual(first, second)

    def test_catalog_text_provenance_is_identical_for_lf_and_crlf(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, catalog, quarantine = _write_fixture(root)
            first = generate(
                catalog_path=catalog,
                source_root=source,
                quarantine_path=quarantine,
                project_root=root,
                enforce_pins=False,
            )
            lf_bytes = catalog.read_bytes().replace(b"\r\n", b"\n")
            catalog.write_bytes(lf_bytes.replace(b"\n", b"\r\n"))
            second = generate(
                catalog_path=catalog,
                source_root=source,
                quarantine_path=quarantine,
                project_root=root,
                enforce_pins=False,
            )
            canonical_catalog_hash = canonical_text_file_sha256(catalog)

        self.assertEqual(first, second)
        self.assertEqual(
            first["sourceProvenance"]["catalog"]["canonicalTextFileSha256"],
            canonical_catalog_hash,
        )

    def test_committed_coverage_matches_service_scale_pins(self) -> None:
        artifact_path = (
            Path(__file__).resolve().parent.parent
            / "docs"
            / "aihub-criterion-coverage.json"
        )
        artifact = json.loads(artifact_path.read_text(encoding="utf-8"))

        self.assertTrue(verify_artifact_fingerprint(artifact))
        self.assertEqual(
            "d0c0e91917943e0f9698d989e083895b35e0ba0a6fcf584c6b75a69d30eccb19",
            artifact["sourceProvenance"]["catalog"]["canonicalTextFileSha256"],
        )
        self.assertEqual(41, artifact["manifest"]["exerciseCount"])
        self.assertEqual(816, artifact["manifest"]["typeCount"])
        self.assertEqual(34_468, artifact["manifest"]["twoDRecordCount"])
        self.assertEqual(97, artifact["manifest"]["exactConditionCount"])
        self.assertEqual(167, artifact["manifest"]["exerciseConditionAssignmentCount"])
        self.assertEqual(15, artifact["manifest"]["truthVectorCollisionExerciseCount"])
        self.assertEqual(55, artifact["manifest"]["truthVectorCollisionGroupCount"])
        self.assertEqual(159, artifact["manifest"]["truthVectorCollisionTypeCount"])
        self.assertEqual(104, artifact["manifest"]["truthVectorExcessTypeCount"])
        self.assertEqual(3, artifact["manifest"]["quarantinedTypeCount"])
        self.assertEqual(153, artifact["manifest"]["quarantinedRecordCount"])

    def test_committed_generated_kotlin_matches_coverage_artifact(self) -> None:
        root = Path(__file__).resolve().parent.parent
        artifact = json.loads(
            (root / "docs" / "aihub-criterion-coverage.json").read_text(encoding="utf-8")
        )
        generated = (
            root
            / "app/src/main/java/com/example/trex_kotlin/catalog/"
            / "AiHubCriterionSourceCatalog.kt"
        )

        self.assertEqual(render_kotlin(artifact), generated.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
