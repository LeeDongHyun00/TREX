#!/usr/bin/env python3
"""Build a deterministic criterion-coverage artifact from AI Hub dataset 231.

The application catalog intentionally keeps only normalized condition text.  This
tool audits the authoritative 2D JSON metadata again so the research/release
pipeline can retain exact raw spellings, per-type truth vectors, source metadata
fingerprints, truth-vector collisions, and an explicit label-quarantine registry.

Frame coordinates are outside this artifact's provenance scope and are never
decoded.  A coverage artifact authorizes no runtime criterion by itself.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable, Mapping

try:
    from .generate_aihub_exercise_catalog import (
        ENUM_NAMES,
        CatalogError,
        decode_tail,
        normalize_text,
    )
except ImportError:  # Direct `python tools/...py` execution.
    from generate_aihub_exercise_catalog import (
        ENUM_NAMES,
        CatalogError,
        decode_tail,
        normalize_text,
    )


SCHEMA_VERSION = 1
QUARANTINE_SCHEMA_VERSION = 1
DEFAULT_PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOG = DEFAULT_PROJECT_ROOT / "docs" / "aihub-exercise-catalog.json"
DEFAULT_QUARANTINE = Path(__file__).resolve().with_name("aihub_label_quarantine.json")
DEFAULT_OUTPUT = DEFAULT_PROJECT_ROOT / "docs" / "aihub-criterion-coverage.json"
DEFAULT_KOTLIN_OUTPUT = (
    DEFAULT_PROJECT_ROOT
    / "app/src/main/java/com/example/trex_kotlin/catalog/AiHubCriterionSourceCatalog.kt"
)

PINNED_COUNTS = {
    "exerciseCount": 41,
    "typeCount": 816,
    "twoDRecordCount": 34_468,
    "exactConditionCount": 97,
    "exerciseConditionAssignmentCount": 167,
    "truthVectorCollisionExerciseCount": 15,
    "truthVectorCollisionGroupCount": 55,
    "truthVectorCollisionTypeCount": 159,
    "truthVectorExcessTypeCount": 104,
    "quarantinedTypeCount": 3,
    "quarantinedRecordCount": 153,
}


class CoverageError(RuntimeError):
    """Raised when coverage provenance or invariants are not trustworthy."""


def _canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_json(value).encode("utf-8")).hexdigest()


def canonical_text_file_sha256(path: Path) -> str:
    """Hash UTF-8 text with LF line endings so checkout policy cannot change identity."""

    text = path.read_text(encoding="utf-8")
    canonical = text.replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def verify_artifact_fingerprint(artifact: Mapping[str, Any]) -> bool:
    fingerprint = artifact.get("artifactSha256")
    if not isinstance(fingerprint, str):
        return False
    unsigned = dict(artifact)
    unsigned.pop("artifactSha256", None)
    return canonical_sha256(unsigned) == fingerprint


def _relative_label(path: Path, project_root: Path) -> str:
    resolved = path.resolve()
    try:
        return resolved.relative_to(project_root.resolve()).as_posix()
    except ValueError:
        # Tests and external audits must not leak machine-specific absolute paths.
        return resolved.name


def _is_relative_to(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def validate_output_path(
    output: Path,
    *,
    source_root: Path,
    protected_inputs: Iterable[Path],
) -> Path:
    resolved = output.resolve()
    resolved_source = source_root.resolve()
    if resolved == resolved_source or _is_relative_to(resolved, resolved_source):
        raise CoverageError("Coverage output must be outside the AI Hub source root")
    for protected in protected_inputs:
        if resolved == protected.resolve():
            raise CoverageError(f"Coverage output collides with protected input: {protected}")
    return resolved


def validate_output_paths(
    json_output: Path,
    kotlin_output: Path,
    *,
    source_root: Path,
    protected_inputs: Iterable[Path],
) -> tuple[Path, Path]:
    protected = list(protected_inputs)
    resolved_json = validate_output_path(
        json_output,
        source_root=source_root,
        protected_inputs=protected,
    )
    resolved_kotlin = validate_output_path(
        kotlin_output,
        source_root=source_root,
        protected_inputs=protected,
    )
    if resolved_json == resolved_kotlin:
        raise CoverageError("JSON and Kotlin outputs must be different files")
    return resolved_json, resolved_kotlin


def atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            prefix=f".{path.name}.",
            suffix=".tmp",
            dir=path.parent,
            delete=False,
        ) as target:
            temporary = Path(target.name)
            target.write(content)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def write_text_or_check(path: Path, content: str, check: bool) -> None:
    if check:
        current = path.read_text(encoding="utf-8") if path.exists() else None
        if current != content:
            raise CoverageError(f"Generated file is stale: {path}")
        return
    atomic_write_text(path, content)


def write_or_check(path: Path, artifact: Mapping[str, Any], check: bool) -> None:
    content = json.dumps(artifact, ensure_ascii=False, indent=2) + "\n"
    write_text_or_check(path, content, check)


def _load_json_object(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CoverageError(f"Cannot read {label} {path}: {error}") from error
    if not isinstance(value, dict):
        raise CoverageError(f"{label} must be a JSON object: {path}")
    return value


def load_catalog(path: Path) -> dict[str, Any]:
    catalog = _load_json_object(path, "catalog")
    declared = catalog.get("catalogSha256")
    if not isinstance(declared, str):
        raise CoverageError("Catalog is missing catalogSha256")
    unsigned = dict(catalog)
    unsigned.pop("catalogSha256", None)
    actual = canonical_sha256(unsigned)
    if actual != declared:
        raise CoverageError(
            f"Catalog fingerprint mismatch: declared={declared}, actual={actual}"
        )
    return catalog


def load_quarantine_registry(path: Path) -> dict[str, Any]:
    registry = _load_json_object(path, "quarantine registry")
    if registry.get("schemaVersion") != QUARANTINE_SCHEMA_VERSION:
        raise CoverageError("Unsupported quarantine registry schemaVersion")
    entries = registry.get("entries")
    if not isinstance(entries, list):
        raise CoverageError("Quarantine registry entries must be an array")

    seen: set[tuple[str, str]] = set()
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise CoverageError(f"Quarantine entry {index} must be an object")
        required = {
            "exerciseId",
            "typeCode",
            "state",
            "reasonCodes",
            "evidenceRefs",
        }
        if set(entry) != required:
            raise CoverageError(
                f"Quarantine entry {index} fields must be exactly {sorted(required)}"
            )
        exercise_id = entry["exerciseId"]
        type_code = entry["typeCode"]
        if not isinstance(exercise_id, str) or not exercise_id:
            raise CoverageError(f"Quarantine entry {index} has invalid exerciseId")
        if not isinstance(type_code, str) or len(type_code) != 3 or not type_code.isdigit():
            raise CoverageError(f"Quarantine entry {index} has invalid typeCode")
        if entry["state"] != "QUARANTINED_PENDING_BLIND_GOLD":
            raise CoverageError(f"Quarantine entry {index} has unsupported state")
        for field in ("reasonCodes", "evidenceRefs"):
            values = entry[field]
            if (
                not isinstance(values, list)
                or not values
                or any(not isinstance(value, str) or not value for value in values)
                or values != sorted(set(values))
            ):
                raise CoverageError(
                    f"Quarantine entry {index} {field} must be a sorted unique string array"
                )
        key = (exercise_id, type_code)
        if key in seen:
            raise CoverageError(f"Duplicate quarantine identity: {key}")
        seen.add(key)
    return registry


def _condition_id(text: str) -> str:
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
    return f"aihub-exact-sha256-{digest}"


def _validated_catalog_index(
    catalog: Mapping[str, Any],
) -> tuple[dict[str, dict[str, Any]], dict[str, tuple[dict[str, Any], dict[str, Any]]]]:
    exercises_value = catalog.get("exercises")
    if not isinstance(exercises_value, list) or not exercises_value:
        raise CoverageError("Catalog exercises must be a non-empty array")

    exercises: dict[str, dict[str, Any]] = {}
    types: dict[str, tuple[dict[str, Any], dict[str, Any]]] = {}
    names: set[str] = set()
    for exercise in exercises_value:
        if not isinstance(exercise, dict):
            raise CoverageError("Catalog exercise must be an object")
        exercise_id = exercise.get("id")
        name = exercise.get("name")
        if not isinstance(exercise_id, str) or not exercise_id:
            raise CoverageError("Catalog exercise has invalid id")
        if not isinstance(name, str) or not name:
            raise CoverageError(f"Catalog exercise {exercise_id!r} has invalid name")
        if exercise_id in exercises or name in names:
            raise CoverageError(f"Duplicate catalog exercise identity: {exercise_id!r}")
        exercises[exercise_id] = exercise
        names.add(name)

        type_entries = exercise.get("types")
        if not isinstance(type_entries, list) or not type_entries:
            raise CoverageError(f"Catalog exercise {exercise_id!r} has no types")
        if exercise.get("typeCount") != len(type_entries):
            raise CoverageError(f"Catalog exercise {exercise_id!r} typeCount drift")
        expected_schema: list[str] | None = None
        exercise_record_count = 0
        for type_entry in type_entries:
            if not isinstance(type_entry, dict):
                raise CoverageError(f"Catalog exercise {exercise_id!r} has invalid type")
            code = type_entry.get("code")
            if not isinstance(code, str) or len(code) != 3 or not code.isdigit():
                raise CoverageError(f"Catalog exercise {exercise_id!r} has invalid type code")
            if code in types:
                raise CoverageError(f"Catalog type code is not globally unique: {code}")
            record_count = type_entry.get("recordCount")
            if not isinstance(record_count, int) or isinstance(record_count, bool) or record_count <= 0:
                raise CoverageError(f"Catalog type {code} has invalid recordCount")
            exercise_record_count += record_count
            type_info_type = type_entry.get("typeInfoType")
            if not isinstance(type_info_type, str) or not type_info_type:
                raise CoverageError(f"Catalog type {code} has invalid typeInfoType")
            conditions = type_entry.get("conditions")
            if not isinstance(conditions, list) or not conditions:
                raise CoverageError(f"Catalog type {code} has no conditions")
            schema: list[str] = []
            for condition in conditions:
                if not isinstance(condition, dict) or set(condition) != {"condition", "value"}:
                    raise CoverageError(f"Catalog type {code} has invalid condition")
                text = condition["condition"]
                value = condition["value"]
                if not isinstance(text, str) or not text or not isinstance(value, bool):
                    raise CoverageError(f"Catalog type {code} has invalid condition value")
                schema.append(text)
            if len(schema) != len(set(schema)):
                raise CoverageError(f"Catalog type {code} repeats a condition")
            if expected_schema is None:
                expected_schema = schema
            elif schema != expected_schema:
                raise CoverageError(
                    f"Exercise {exercise_id!r} changes condition order/schema at type {code}"
                )
            types[code] = (exercise, type_entry)
        if exercise.get("recordCount") != exercise_record_count:
            raise CoverageError(f"Catalog exercise {exercise_id!r} recordCount drift")

    manifest = catalog.get("manifest")
    if not isinstance(manifest, dict):
        raise CoverageError("Catalog manifest must be an object")
    derived_manifest = {
        "exerciseCount": len(exercises),
        "typeCount": len(types),
        "recordCount": sum(entry[1]["recordCount"] for entry in types.values()),
    }
    manifest_drift = {
        field: {"declared": manifest.get(field), "derived": derived}
        for field, derived in derived_manifest.items()
        if manifest.get(field) != derived
    }
    if manifest_drift:
        raise CoverageError(f"Catalog manifest drift: {manifest_drift}")
    return exercises, types


def _raw_optional_text(value: Any, field: str, path: Path) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise CoverageError(f"{path}: {field} must be a string when present")
    return value


def _source_condition_rows(value: Any, path: Path) -> list[dict[str, Any]]:
    if not isinstance(value, list) or not value:
        raise CoverageError(f"{path}: type_info.conditions must be a non-empty array")
    rows: list[dict[str, Any]] = []
    normalized_seen: set[str] = set()
    for index, item in enumerate(value):
        if not isinstance(item, dict) or set(item) != {"condition", "value"}:
            raise CoverageError(
                f"{path}: conditions[{index}] must contain condition and value only"
            )
        raw = item["condition"]
        try:
            normalized = normalize_text(raw, f"conditions[{index}].condition", path)
        except CatalogError as error:
            raise CoverageError(str(error)) from error
        if normalized in normalized_seen:
            raise CoverageError(f"{path}: duplicate normalized condition {normalized!r}")
        normalized_seen.add(normalized)
        value_item = item["value"]
        if not isinstance(value_item, bool):
            raise CoverageError(f"{path}: conditions[{index}].value must be boolean")
        rows.append({"normalized": normalized, "raw": raw, "value": value_item})
    return rows


def audit_source_metadata(
    source_root: Path,
    catalog_types: Mapping[str, tuple[dict[str, Any], dict[str, Any]]],
) -> dict[str, Any]:
    resolved_root = source_root.resolve()
    all_json = sorted(
        resolved_root.rglob("*.json"),
        key=lambda path: path.relative_to(resolved_root).as_posix().casefold(),
    )
    two_d = [path for path in all_json if not path.name.endswith("-3d.json")]
    if not two_d:
        raise CoverageError(f"No 2D JSON files found under {source_root}")

    logical_paths: set[str] = set()
    record_counts: Counter[str] = Counter()
    raw_exercises: dict[str, set[str]] = defaultdict(set)
    raw_conditions: dict[str, set[str]] = defaultdict(set)
    raw_poses: dict[str, set[str]] = defaultdict(set)
    raw_descriptions: dict[str, set[str]] = defaultdict(set)
    first_paths: dict[str, str] = {}
    last_paths: dict[str, str] = {}
    global_hasher = hashlib.sha256()
    type_hashers = {code: hashlib.sha256() for code in catalog_types}

    for path in two_d:
        relative = path.relative_to(resolved_root).as_posix()
        logical = relative.casefold()
        if logical in logical_paths:
            raise CoverageError(f"Duplicate 2D logical source path: {relative}")
        logical_paths.add(logical)

        try:
            type_code, type_info = decode_tail(path)
        except CatalogError as error:
            raise CoverageError(str(error)) from error
        catalog_pair = catalog_types.get(type_code)
        if catalog_pair is None:
            raise CoverageError(f"{path}: type {type_code} is absent from catalog")
        exercise, type_entry = catalog_pair

        exercise_raw = type_info.get("exercise")
        try:
            exercise_normalized = normalize_text(
                exercise_raw, "type_info.exercise", path
            )
            type_info_type = normalize_text(type_info.get("type"), "type_info.type", path)
            type_info_key = normalize_text(type_info.get("key"), "type_info.key", path)
        except CatalogError as error:
            raise CoverageError(str(error)) from error
        if exercise_normalized != exercise["name"]:
            raise CoverageError(
                f"{path}: type {type_code} exercise drift; "
                f"catalog={exercise['name']!r}, source={exercise_normalized!r}"
            )
        if type_info_key != type_code:
            raise CoverageError(f"{path}: root type {type_code} != type_info.key {type_info_key}")
        if type_info_type != type_entry["typeInfoType"]:
            raise CoverageError(f"{path}: type_info.type drift for type {type_code}")

        source_conditions = _source_condition_rows(type_info.get("conditions"), path)
        source_pairs = [
            (row["normalized"], row["value"]) for row in source_conditions
        ]
        catalog_pairs = [
            (row["condition"], row["value"]) for row in type_entry["conditions"]
        ]
        if source_pairs != catalog_pairs:
            raise CoverageError(f"{path}: condition truth-vector drift for type {type_code}")

        pose_raw = _raw_optional_text(type_info.get("pose"), "type_info.pose", path)
        description_raw = _raw_optional_text(
            type_info.get("description"), "type_info.description", path
        )
        metadata_row = {
            "path": relative,
            "type": type_code,
            "typeInfo": {
                "key": type_info.get("key"),
                "type": type_info.get("type"),
                "pose": pose_raw,
                "exercise": exercise_raw,
                "description": description_raw,
                "conditions": [
                    {"condition": row["raw"], "value": row["value"]}
                    for row in source_conditions
                ],
            },
        }
        encoded = (_canonical_json(metadata_row) + "\n").encode("utf-8")
        global_hasher.update(encoded)
        type_hashers[type_code].update(encoded)

        record_counts[type_code] += 1
        raw_exercises[exercise["id"]].add(exercise_raw)
        for condition in source_conditions:
            raw_conditions[condition["normalized"]].add(condition["raw"])
        if pose_raw is not None:
            raw_poses[type_code].add(pose_raw)
        if description_raw is not None:
            raw_descriptions[type_code].add(description_raw)
        first_paths.setdefault(type_code, relative)
        last_paths[type_code] = relative

    if set(record_counts) != set(catalog_types):
        missing = sorted(set(catalog_types) - set(record_counts), key=int)
        raise CoverageError(f"Source is missing catalog type codes: {missing}")
    for code, (_, type_entry) in catalog_types.items():
        if record_counts[code] != type_entry["recordCount"]:
            raise CoverageError(
                f"Type {code} record count drift; catalog={type_entry['recordCount']}, "
                f"source={record_counts[code]}"
            )

    return {
        "twoDRecordCount": len(two_d),
        "metadataSetSha256": global_hasher.hexdigest(),
        "rawExercises": raw_exercises,
        "rawConditions": raw_conditions,
        "rawPoses": raw_poses,
        "rawDescriptions": raw_descriptions,
        "recordCounts": record_counts,
        "typeMetadataSha256": {
            code: digest.hexdigest() for code, digest in type_hashers.items()
        },
        "firstPaths": first_paths,
        "lastPaths": last_paths,
    }


def _validate_quarantine_against_catalog(
    registry: Mapping[str, Any],
    catalog_types: Mapping[str, tuple[dict[str, Any], dict[str, Any]]],
) -> dict[tuple[str, str], dict[str, Any]]:
    result: dict[tuple[str, str], dict[str, Any]] = {}
    for entry_value in registry["entries"]:
        entry = dict(entry_value)
        code = entry["typeCode"]
        catalog_pair = catalog_types.get(code)
        if catalog_pair is None:
            raise CoverageError(f"Quarantine type is absent from catalog: {code}")
        actual_exercise = catalog_pair[0]["id"]
        if actual_exercise != entry["exerciseId"]:
            raise CoverageError(
                f"Quarantine identity drift for type {code}: "
                f"registry={entry['exerciseId']}, catalog={actual_exercise}"
            )
        result[(actual_exercise, code)] = entry
    return result


def _validate_pinned_counts(manifest: Mapping[str, Any]) -> None:
    drift = {
        field: {"expected": expected, "actual": manifest.get(field)}
        for field, expected in PINNED_COUNTS.items()
        if manifest.get(field) != expected
    }
    if drift:
        raise CoverageError(f"Pinned AI Hub coverage count drift: {drift}")


def build_coverage_artifact(
    *,
    catalog: Mapping[str, Any],
    catalog_canonical_text_sha256: str,
    catalog_label: str,
    source_root_label: str,
    source_audit: Mapping[str, Any],
    quarantine_registry: Mapping[str, Any],
    quarantine_label: str,
    enforce_pins: bool = False,
) -> dict[str, Any]:
    exercises_by_id, catalog_types = _validated_catalog_index(catalog)
    quarantine_by_key = _validate_quarantine_against_catalog(
        quarantine_registry, catalog_types
    )

    condition_raw: Mapping[str, set[str]] = source_audit["rawConditions"]
    all_condition_texts = sorted(
        {
            condition["condition"]
            for exercise in exercises_by_id.values()
            for type_entry in exercise["types"]
            for condition in type_entry["conditions"]
        }
    )
    missing_raw = [text for text in all_condition_texts if not condition_raw.get(text)]
    if missing_raw:
        raise CoverageError(f"Source audit lacks raw spellings for conditions: {missing_raw}")

    condition_ids = {text: _condition_id(text) for text in all_condition_texts}
    if len(set(condition_ids.values())) != len(condition_ids):
        raise CoverageError("Condition SHA-256 identity collision")

    condition_exercises: dict[str, set[str]] = defaultdict(set)
    condition_type_counts: Counter[str] = Counter()
    condition_true_records: Counter[str] = Counter()
    condition_false_records: Counter[str] = Counter()

    output_exercises: list[dict[str, Any]] = []
    collision_group_count = 0
    collision_excess_type_count = 0
    collision_exercise_count = 0
    collision_type_count = 0
    quarantined_record_count = 0

    for exercise_id in sorted(exercises_by_id):
        exercise = exercises_by_id[exercise_id]
        type_entries = sorted(exercise["types"], key=lambda item: int(item["code"]))
        schema = [row["condition"] for row in type_entries[0]["conditions"]]
        schema_ids = [condition_ids[text] for text in schema]

        vector_to_codes: dict[str, list[str]] = defaultdict(list)
        for type_entry in type_entries:
            vector = "".join("1" if row["value"] else "0" for row in type_entry["conditions"])
            vector_to_codes[vector].append(type_entry["code"])
        collision_vectors = {
            vector: codes for vector, codes in vector_to_codes.items() if len(codes) > 1
        }
        if collision_vectors:
            collision_exercise_count += 1
        collision_group_count += len(collision_vectors)
        collision_excess_type_count += sum(len(codes) - 1 for codes in collision_vectors.values())
        collision_type_count += sum(len(codes) for codes in collision_vectors.values())

        assignment_rows: list[dict[str, Any]] = []
        for ordinal, text in enumerate(schema):
            true_types = 0
            false_types = 0
            true_records = 0
            false_records = 0
            for type_entry in type_entries:
                value = type_entry["conditions"][ordinal]["value"]
                records = type_entry["recordCount"]
                if value:
                    true_types += 1
                    true_records += records
                else:
                    false_types += 1
                    false_records += records
            condition_exercises[text].add(exercise_id)
            condition_type_counts[text] += len(type_entries)
            condition_true_records[text] += true_records
            condition_false_records[text] += false_records
            assignment_rows.append(
                {
                    "ordinal": ordinal,
                    "conditionId": condition_ids[text],
                    "normalizedExactText": text,
                    "rawTextAliases": sorted(condition_raw[text]),
                    "trueTypeCount": true_types,
                    "falseTypeCount": false_types,
                    "trueRecordCount": true_records,
                    "falseRecordCount": false_records,
                }
            )

        output_types: list[dict[str, Any]] = []
        for type_entry in type_entries:
            code = type_entry["code"]
            vector = "".join("1" if row["value"] else "0" for row in type_entry["conditions"])
            quarantine = quarantine_by_key.get((exercise_id, code))
            if quarantine is not None:
                quarantined_record_count += type_entry["recordCount"]
            is_collision = vector in collision_vectors
            row: dict[str, Any] = {
                "code": code,
                "recordCount": type_entry["recordCount"],
                "truthVector": vector,
                "truthValues": [row["value"] for row in type_entry["conditions"]],
                "conditionIds": schema_ids,
                "typeInfoType": type_entry["typeInfoType"],
                "rawPoseTexts": sorted(source_audit["rawPoses"].get(code, set())),
                "rawDescriptionTexts": sorted(
                    source_audit["rawDescriptions"].get(code, set())
                ),
                "sourceMetadata": {
                    "sha256": source_audit["typeMetadataSha256"][code],
                    "firstRelativePath": source_audit["firstPaths"][code],
                    "lastRelativePath": source_audit["lastPaths"][code],
                },
                "truthVectorIdentity": {
                    "state": (
                        "COLLISION_REVIEW_REQUIRED" if is_collision else "UNIQUE"
                    ),
                    "typeClassIdentitySafe": not is_collision,
                },
                "labelEligibility": {
                    "state": quarantine["state"] if quarantine else "CLEAR",
                    "eligibleForAutomaticCriterionCalibration": quarantine is None,
                },
            }
            if is_collision:
                row["truthVectorIdentity"]["collidingTypeCodes"] = collision_vectors[vector]
            if quarantine is not None:
                row["labelEligibility"]["reasonCodes"] = quarantine["reasonCodes"]
                row["labelEligibility"]["evidenceRefs"] = quarantine["evidenceRefs"]
            output_types.append(row)

        collision_rows = [
            {
                "truthVector": vector,
                "typeCodes": codes,
                "typeCount": len(codes),
                "excessTypeCount": len(codes) - 1,
                "recordCount": sum(
                    entry["recordCount"] for entry in type_entries if entry["code"] in codes
                ),
                "reviewState": "REVIEW_REQUIRED_NOT_AUTOMATIC_QUARANTINE",
            }
            for vector, codes in sorted(collision_vectors.items())
        ]
        output_exercises.append(
            {
                "id": exercise_id,
                "normalizedSourceName": exercise["name"],
                "rawSourceNameAliases": sorted(
                    source_audit["rawExercises"].get(exercise_id, set())
                ),
                "recordCount": exercise["recordCount"],
                "typeCount": len(type_entries),
                "conditionAssignmentCount": len(schema),
                "conditions": assignment_rows,
                "types": output_types,
                "truthVectorCollisionGroups": collision_rows,
            }
        )

    condition_registry = [
        {
            "id": condition_ids[text],
            "normalizedExactText": text,
            "rawTextAliases": sorted(condition_raw[text]),
            "exerciseIds": sorted(condition_exercises[text]),
            "exerciseAssignmentCount": len(condition_exercises[text]),
            "typeOccurrenceCount": condition_type_counts[text],
            "trueRecordCount": condition_true_records[text],
            "falseRecordCount": condition_false_records[text],
            "semanticAliasPolicy": "NO_SEMANTIC_ALIAS_MERGE",
        }
        for text in all_condition_texts
    ]

    exercise_condition_assignments = sum(
        exercise["conditionAssignmentCount"] for exercise in output_exercises
    )
    manifest = {
        "exerciseCount": len(output_exercises),
        "typeCount": len(catalog_types),
        "twoDRecordCount": source_audit["twoDRecordCount"],
        "exactConditionCount": len(condition_registry),
        "exerciseConditionAssignmentCount": exercise_condition_assignments,
        "truthVectorCollisionExerciseCount": collision_exercise_count,
        "truthVectorCollisionGroupCount": collision_group_count,
        "truthVectorCollisionTypeCount": collision_type_count,
        "truthVectorExcessTypeCount": collision_excess_type_count,
        "quarantinedTypeCount": len(quarantine_by_key),
        "quarantinedRecordCount": quarantined_record_count,
    }
    if enforce_pins:
        _validate_pinned_counts(manifest)

    quarantine_entries: list[dict[str, Any]] = []
    for entry_value in sorted(
        quarantine_registry["entries"],
        key=lambda entry: (entry["exerciseId"], int(entry["typeCode"])),
    ):
        entry = dict(entry_value)
        type_entry = catalog_types[entry["typeCode"]][1]
        entry["recordCount"] = type_entry["recordCount"]
        entry["truthVector"] = "".join(
            "1" if condition["value"] else "0"
            for condition in type_entry["conditions"]
        )
        quarantine_entries.append(entry)

    artifact: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "artifactKind": "AIHUB_CRITERION_COVERAGE",
        "authority": "CATALOG_AND_LABEL_PROVENANCE_ONLY_NOT_RUNTIME_RELEASE",
        "sourceProvenance": {
            "dataset": "AI Hub dataset 231 fitness posture Training labels",
            "catalog": {
                "path": catalog_label,
                "schemaVersion": catalog["schemaVersion"],
                "catalogSha256": catalog["catalogSha256"],
                "canonicalTextFileSha256": catalog_canonical_text_sha256,
            },
            "twoDMetadataAudit": {
                "sourceRoot": source_root_label,
                "scope": [
                    "relative JSON path",
                    "root type",
                    "type_info.key",
                    "type_info.type",
                    "type_info.pose",
                    "type_info.exercise",
                    "type_info.description",
                    "type_info.conditions raw text and boolean value",
                ],
                "excluded": ["frames", "coordinates", "images", "3D JSON"],
                "textIdentity": "Unicode NFC plus whitespace collapse/trim; raw aliases retained",
                "metadataSetSha256": source_audit["metadataSetSha256"],
            },
            "quarantineRegistry": {
                "path": quarantine_label,
                "schemaVersion": quarantine_registry["schemaVersion"],
                "registrySha256": canonical_sha256(quarantine_registry),
            },
        },
        "manifest": manifest,
        "conditionRegistry": condition_registry,
        "exercises": output_exercises,
        "labelQuarantine": {
            "policy": (
                "Quarantined types are excluded from automatic criterion calibration "
                "until blind expert Gold adjudication; duplicate truth vectors alone "
                "trigger review but are not automatically quarantined."
            ),
            "entries": quarantine_entries,
        },
    }
    artifact["artifactSha256"] = canonical_sha256(artifact)
    return artifact


def kotlin_string(value: str) -> str:
    if not isinstance(value, str):
        raise CoverageError("Kotlin string renderer received a non-string value")
    # JSON's quoted representation is Kotlin-compatible for this source corpus,
    # except Kotlin string templates require a literal dollar sign to be escaped.
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def _kotlin_string_list(values: Iterable[str]) -> str:
    rendered = [kotlin_string(value) for value in values]
    return "listOf(" + ", ".join(rendered) + ")"


def _coverage_factory_name(exercise_id: str) -> str:
    parts = exercise_id.split("-")
    if not parts or any(not part or not part.isalnum() for part in parts):
        raise CoverageError(f"Cannot render Kotlin factory for exercise id {exercise_id!r}")
    return parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:]) + "Coverage"


def render_kotlin(artifact: Mapping[str, Any]) -> str:
    """Render the compact on-device source-truth registry.

    Exercise rows are deliberately split into private factories.  Keeping the 816
    constructor calls out of one initializer avoids JVM's 64 KiB method limit.
    """

    if not verify_artifact_fingerprint(artifact):
        raise CoverageError("Cannot render Kotlin from an unverified coverage artifact")
    try:
        source = artifact["sourceProvenance"]
        catalog_sha = source["catalog"]["catalogSha256"]
        metadata_sha = source["twoDMetadataAudit"]["metadataSetSha256"]
        artifact_sha = artifact["artifactSha256"]
        manifest = artifact["manifest"]
        conditions = artifact["conditionRegistry"]
        exercises = artifact["exercises"]
    except (KeyError, TypeError) as error:
        raise CoverageError(f"Coverage artifact cannot be rendered: missing {error}") from error

    if not isinstance(conditions, list) or not isinstance(exercises, list):
        raise CoverageError("Coverage conditionRegistry and exercises must be arrays")
    exercise_ids = [exercise.get("id") for exercise in exercises]
    if any(exercise_id not in ENUM_NAMES for exercise_id in exercise_ids):
        unknown = sorted(
            str(exercise_id) for exercise_id in exercise_ids if exercise_id not in ENUM_NAMES
        )
        raise CoverageError(f"Coverage contains unknown Kotlin exercise ids: {unknown}")
    if len(exercise_ids) != len(set(exercise_ids)):
        raise CoverageError("Coverage contains duplicate exercise ids")

    required_manifest_fields = (
        "exerciseCount",
        "typeCount",
        "twoDRecordCount",
        "exactConditionCount",
        "exerciseConditionAssignmentCount",
        "truthVectorCollisionExerciseCount",
        "truthVectorCollisionGroupCount",
        "truthVectorCollisionTypeCount",
        "truthVectorExcessTypeCount",
        "quarantinedTypeCount",
        "quarantinedRecordCount",
    )
    for field in required_manifest_fields:
        value = manifest.get(field)
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            raise CoverageError(f"Coverage manifest has invalid {field}")

    lines = [
        "// Generated by tools/generate_aihub_criterion_coverage.py. Do not edit manually.",
        "package com.example.trex_kotlin.catalog",
        "",
        "/** AI Hub source truth only; this object cannot authorize a runtime posture cue. */",
        "object AiHubCriterionSourceCatalog {",
        f"    const val CATALOG_SHA256: String = {kotlin_string(catalog_sha)}",
        f"    const val COVERAGE_ARTIFACT_SHA256: String = {kotlin_string(artifact_sha)}",
        f"    const val METADATA_SET_SHA256: String = {kotlin_string(metadata_sha)}",
        "",
        "    val registry: AiHubCriterionSourceRegistry = AiHubCriterionSourceRegistry(",
        f"        schemaVersion = {artifact['schemaVersion']},",
        "        catalogSha256 = CATALOG_SHA256,",
        "        coverageArtifactSha256 = COVERAGE_ARTIFACT_SHA256,",
        "        metadataSetSha256 = METADATA_SET_SHA256,",
        "        sourceConditions = sourceConditions(),",
        "        coverages = exerciseCoverages(),",
        f"        expectedExerciseCount = {manifest['exerciseCount']},",
        f"        expectedTypeCount = {manifest['typeCount']},",
        f"        expectedRecordCount = {manifest['twoDRecordCount']},",
        f"        expectedExactConditionCount = {manifest['exactConditionCount']},",
        "        expectedExerciseConditionAssignmentCount = "
        f"{manifest['exerciseConditionAssignmentCount']},",
        "        expectedCollisionExerciseCount = "
        f"{manifest['truthVectorCollisionExerciseCount']},",
        "        expectedCollisionGroupCount = "
        f"{manifest['truthVectorCollisionGroupCount']},",
        "        expectedCollisionTypeCount = "
        f"{manifest['truthVectorCollisionTypeCount']},",
        "        expectedCollisionExcessTypeCount = "
        f"{manifest['truthVectorExcessTypeCount']},",
        f"        expectedQuarantinedTypeCount = {manifest['quarantinedTypeCount']},",
        f"        expectedQuarantinedRecordCount = {manifest['quarantinedRecordCount']},",
        "    )",
        "",
        "    fun coverage(exercise: AiHubExercise): AiHubExerciseSourceCoverage? =",
        "        registry.coverage(exercise)",
        "",
        "    fun requireCoverage(exercise: AiHubExercise): AiHubExerciseSourceCoverage =",
        "        registry.requireCoverage(exercise)",
        "",
        "    private fun sourceConditions(): List<AiHubExactSourceCondition> = listOf(",
    ]

    seen_condition_ids: set[str] = set()
    for condition in conditions:
        condition_id = condition.get("id")
        normalized_text = condition.get("normalizedExactText")
        raw_aliases = condition.get("rawTextAliases")
        if (
            not isinstance(condition_id, str)
            or condition_id in seen_condition_ids
            or not isinstance(normalized_text, str)
            or not isinstance(raw_aliases, list)
            or not raw_aliases
            or any(not isinstance(alias, str) for alias in raw_aliases)
        ):
            raise CoverageError("Coverage contains an invalid exact condition row")
        seen_condition_ids.add(condition_id)
        lines.extend(
            [
                "        AiHubExactSourceCondition(",
                f"            id = {kotlin_string(condition_id)},",
                f"            normalizedExactText = {kotlin_string(normalized_text)},",
                f"            rawTextAliases = {_kotlin_string_list(raw_aliases)},",
                "        ),",
            ]
        )
    lines.extend(
        [
            "    )",
            "",
            "    private fun exerciseCoverages(): List<AiHubExerciseSourceCoverage> = listOf(",
        ]
    )
    for exercise_id in exercise_ids:
        lines.append(f"        {_coverage_factory_name(exercise_id)}(),")
    lines.extend(["    )", ""])

    for exercise in exercises:
        exercise_id = exercise["id"]
        factory_name = _coverage_factory_name(exercise_id)
        conditions_for_exercise = exercise.get("conditions")
        type_rows = exercise.get("types")
        if not isinstance(conditions_for_exercise, list) or not isinstance(type_rows, list):
            raise CoverageError(f"Exercise {exercise_id!r} has invalid coverage arrays")
        condition_ids = [row.get("conditionId") for row in conditions_for_exercise]
        if any(not isinstance(condition_id, str) for condition_id in condition_ids):
            raise CoverageError(f"Exercise {exercise_id!r} has invalid condition ids")

        lines.extend(
            [
                f"    private fun {factory_name}(): AiHubExerciseSourceCoverage =",
                "        AiHubExerciseSourceCoverage(",
                f"            exercise = AiHubExercise.{ENUM_NAMES[exercise_id]},",
                "            conditionIds = listOf(",
            ]
        )
        for condition_id in condition_ids:
            lines.append(f"                {kotlin_string(condition_id)},")
        lines.extend(
            [
                "            ),",
                "            typeTruthRows = listOf(",
            ]
        )
        for type_row in type_rows:
            try:
                type_code = type_row["code"]
                record_count = type_row["recordCount"]
                truth_vector = type_row["truthVector"]
                identity = type_row["truthVectorIdentity"]
                identity_state = identity["state"]
                label = type_row["labelEligibility"]
                label_state = label["state"]
            except (KeyError, TypeError) as error:
                raise CoverageError(
                    f"Exercise {exercise_id!r} has an invalid type truth row: {error}"
                ) from error
            if identity_state not in {"UNIQUE", "COLLISION_REVIEW_REQUIRED"}:
                raise CoverageError(f"Type {type_code} has unsupported collision state")
            if label_state not in {"CLEAR", "QUARANTINED_PENDING_BLIND_GOLD"}:
                raise CoverageError(f"Type {type_code} has unsupported label state")

            lines.extend(
                [
                    "                AiHubSourceTypeTruth(",
                    f"                    typeCode = {kotlin_string(type_code)},",
                    f"                    recordCount = {record_count},",
                    f"                    truthVector = {kotlin_string(truth_vector)},",
                    "                    truthVectorIdentity = "
                    f"AiHubTruthVectorIdentity.{identity_state},",
                ]
            )
            if identity_state == "COLLISION_REVIEW_REQUIRED":
                collision_codes = identity.get("collidingTypeCodes")
                if not isinstance(collision_codes, list) or not collision_codes:
                    raise CoverageError(f"Type {type_code} collision codes are missing")
                lines.append(
                    "                    collidingTypeCodes = "
                    f"{_kotlin_string_list(collision_codes)},"
                )
            if label_state == "QUARANTINED_PENDING_BLIND_GOLD":
                reasons = label.get("reasonCodes")
                if not isinstance(reasons, list) or not reasons:
                    raise CoverageError(f"Type {type_code} quarantine reasons are missing")
                lines.extend(
                    [
                        "                    labelState = "
                        f"AiHubSourceLabelState.{label_state},",
                        "                    quarantineReasonCodes = "
                        f"{_kotlin_string_list(reasons)},",
                    ]
                )
            lines.extend(["                ),"])
        lines.extend(
            [
                "            ),",
                "        )",
                "",
            ]
        )

    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def generate(
    *,
    catalog_path: Path,
    source_root: Path,
    quarantine_path: Path,
    project_root: Path,
    enforce_pins: bool = True,
) -> dict[str, Any]:
    catalog = load_catalog(catalog_path)
    quarantine = load_quarantine_registry(quarantine_path)
    _, catalog_types = _validated_catalog_index(catalog)
    source_audit = audit_source_metadata(source_root, catalog_types)
    return build_coverage_artifact(
        catalog=catalog,
        catalog_canonical_text_sha256=canonical_text_file_sha256(catalog_path),
        catalog_label=_relative_label(catalog_path, project_root),
        source_root_label=_relative_label(source_root, project_root),
        source_audit=source_audit,
        quarantine_registry=quarantine,
        quarantine_label=_relative_label(quarantine_path, project_root),
        enforce_pins=enforce_pins,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "source",
        type=Path,
        help="AI Hub Training labeling-data root containing authoritative 2D JSON",
    )
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--quarantine", type=Path, default=DEFAULT_QUARANTINE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--kotlin-output", type=Path, default=DEFAULT_KOTLIN_OUTPUT)
    parser.add_argument("--project-root", type=Path, default=DEFAULT_PROJECT_ROOT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    try:
        output, kotlin_output = validate_output_paths(
            args.output,
            args.kotlin_output,
            source_root=args.source,
            protected_inputs=[args.catalog, args.quarantine],
        )
        artifact = generate(
            catalog_path=args.catalog.resolve(),
            source_root=args.source.resolve(),
            quarantine_path=args.quarantine.resolve(),
            project_root=args.project_root.resolve(),
        )
        kotlin = render_kotlin(artifact)
        write_or_check(output, artifact, args.check)
        write_text_or_check(kotlin_output, kotlin, args.check)
    except (CoverageError, OSError) as error:
        print(f"criterion coverage generation failed: {error}", file=os.sys.stderr)
        return 1

    manifest = artifact["manifest"]
    print(
        "criterion coverage ok: "
        f"{manifest['exerciseCount']} exercises, "
        f"{manifest['exactConditionCount']} exact conditions, "
        f"{manifest['exerciseConditionAssignmentCount']} assignments, "
        f"{manifest['quarantinedTypeCount']} quarantined types, "
        f"sha256={artifact['artifactSha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
