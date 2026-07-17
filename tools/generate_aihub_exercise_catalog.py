#!/usr/bin/env python3
"""Generate TREX's canonical exercise catalog from AI Hub dataset 231 2D JSON.

Only root ``type`` and ``type_info`` from non-3D JSON files are authoritative.
The large frame payload is intentionally not decoded.  Exercise and condition text
is normalized with Unicode NFC plus whitespace collapsing; no exercise aliases are
merged.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
TAIL_BYTES = 64 * 1024

# Stable application identifiers are deliberately explicit.  A new or renamed
# source exercise makes generation fail instead of silently changing app identity.
EXERCISE_IDS = {
    "스탠딩 사이드 크런치": "standing-side-crunch",
    "스탠딩 니업": "standing-knee-up",
    "버피 테스트": "burpee-test",
    "스텝 포워드 다이나믹 런지": "step-forward-dynamic-lunge",
    "스텝 백워드 다이나믹 런지": "step-backward-dynamic-lunge",
    "사이드 런지": "side-lunge",
    "크로스 런지": "cross-lunge",
    "굿모닝": "good-morning",
    "라잉 레그 레이즈": "lying-leg-raise",
    "크런치": "crunch",
    "바이시클 크런치": "bicycle-crunch",
    "시저크로스": "scissor-cross",
    "힙쓰러스트": "hip-thrust",
    "플랭크": "plank",
    "푸시업": "push-up",
    "니푸쉬업": "knee-push-up",
    "Y - Exercise": "y-exercise",
    "프런트 레이즈": "front-raise",
    "업라이트로우": "upright-row",
    "바벨 스티프 데드리프트": "barbell-stiff-deadlift",
    "바벨 로우": "barbell-row",
    "덤벨 벤트오버 로우": "dumbbell-bent-over-row",
    "바벨 데드리프트": "barbell-deadlift",
    "바벨 스쿼트": "barbell-squat",
    "바벨 런지": "barbell-lunge",
    "오버 헤드 프레스": "overhead-press",
    "사이드 레터럴 레이즈": "side-lateral-raise",
    "바벨 컬": "barbell-curl",
    "덤벨 컬": "dumbbell-curl",
    "덤벨 체스트 플라이": "dumbbell-chest-fly",
    "덤벨 인클라인 체스트 플라이": "dumbbell-incline-chest-fly",
    "덤벨 풀 오버": "dumbbell-pullover",
    "라잉 트라이셉스 익스텐션": "lying-triceps-extension",
    "딥스": "dips",
    "풀업": "pull-up",
    "행잉 레그 레이즈": "hanging-leg-raise",
    "랫풀 다운": "lat-pulldown",
    "페이스 풀": "face-pull",
    "케이블 크런치": "cable-crunch",
    "케이블 푸시 다운": "cable-push-down",
    "로잉머신": "rowing-machine",
}

ENUM_NAMES = {identifier: identifier.replace("-", "_").upper() for identifier in EXERCISE_IDS.values()}


class CatalogError(RuntimeError):
    pass


def validate_identity_map(identity_map: dict[str, str]) -> None:
    if not identity_map:
        raise CatalogError("Exercise identity map must not be empty")
    identifiers = list(identity_map.values())
    if len(identifiers) != len(set(identifiers)):
        duplicates = sorted(identifier for identifier, count in Counter(identifiers).items() if count > 1)
        raise CatalogError(f"Duplicate stable exercise IDs: {duplicates}")
    for name, identifier in identity_map.items():
        if not isinstance(name, str) or not name.strip():
            raise CatalogError("Exercise identity map contains an empty name")
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", identifier):
            raise CatalogError(f"Invalid stable exercise ID {identifier!r} for {name!r}")


def normalize_text(value: Any, field: str, path: Path) -> str:
    if not isinstance(value, str):
        raise CatalogError(f"{path}: {field} must be a string")
    normalized = re.sub(r"\s+", " ", unicodedata.normalize("NFC", value)).strip()
    if not normalized:
        raise CatalogError(f"{path}: {field} must not be empty")
    return normalized


def decode_tail(path: Path) -> tuple[str, dict[str, Any]]:
    size = path.stat().st_size
    with path.open("rb") as source:
        source.seek(max(0, size - TAIL_BYTES))
        tail = source.read().decode("utf-8")

    marker = tail.rfind('"type_info"')
    if marker < 0:
        raise CatalogError(f"{path}: type_info not found in final {TAIL_BYTES} bytes")

    prefix = tail[:marker]
    matches = list(re.finditer(r'"type"\s*:\s*"([^"\\]+)"\s*,\s*$', prefix))
    if not matches:
        raise CatalogError(f"{path}: root type immediately before type_info is missing")
    type_code = normalize_text(matches[-1].group(1), "type", path)
    if not type_code.isdigit():
        raise CatalogError(f"{path}: type must contain only digits, got {type_code!r}")

    colon = tail.find(":", marker)
    value_start = colon + 1
    while value_start < len(tail) and tail[value_start].isspace():
        value_start += 1
    try:
        type_info, end = json.JSONDecoder().raw_decode(tail, value_start)
    except (ValueError, json.JSONDecodeError) as error:
        raise CatalogError(f"{path}: invalid type_info JSON: {error}") from error
    if not isinstance(type_info, dict):
        raise CatalogError(f"{path}: type_info must be an object")
    if tail[end:].strip() != "}":
        raise CatalogError(f"{path}: unexpected content after type_info")
    return type_code, type_info


def frame_state(path: Path) -> str:
    with path.open("rb") as source:
        head = source.read(4096)
    match = re.search(rb'"frames"\s*:\s*\[', head)
    if match is None:
        return "missing"
    position = match.end()
    while position < len(head) and head[position] in b" \t\r\n":
        position += 1
    return "empty" if position < len(head) and head[position] == ord("]") else "nonEmpty"


def normalized_conditions(value: Any, path: Path) -> list[dict[str, Any]]:
    if not isinstance(value, list) or not value:
        raise CatalogError(f"{path}: type_info.conditions must be a non-empty array")
    result: list[dict[str, Any]] = []
    names: set[str] = set()
    for index, item in enumerate(value):
        if not isinstance(item, dict) or set(item) != {"condition", "value"}:
            raise CatalogError(f"{path}: conditions[{index}] must contain condition and value only")
        name = normalize_text(item["condition"], f"conditions[{index}].condition", path)
        if name in names:
            raise CatalogError(f"{path}: duplicate normalized condition {name!r}")
        names.add(name)
        condition_value = item["value"]
        if not isinstance(condition_value, bool):
            raise CatalogError(f"{path}: conditions[{index}].value must be boolean")
        result.append({"condition": name, "value": condition_value})
    return result


def metadata_key(type_info_type: str, conditions: list[dict[str, Any]]) -> str:
    return json.dumps(
        {"typeInfoType": type_info_type, "conditions": conditions},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def scan(source_root: Path) -> dict[str, Any]:
    validate_identity_map(EXERCISE_IDS)
    all_json = sorted(source_root.rglob("*.json"))
    two_d = [path for path in all_json if not path.name.endswith("-3d.json")]
    three_d = [path for path in all_json if path.name.endswith("-3d.json")]
    if not two_d:
        raise CatalogError(f"No 2D JSON files found under {source_root}")

    logical_keys: set[str] = set()
    variants: dict[tuple[str, str], Counter[str]] = defaultdict(Counter)
    variant_frame_states: dict[tuple[str, str], Counter[str]] = defaultdict(Counter)
    metadata_by_key: dict[str, dict[str, Any]] = {}
    raw_names: dict[str, set[str]] = defaultdict(set)
    exercise_counts: Counter[str] = Counter()
    exercise_frame_states: dict[str, Counter[str]] = defaultdict(Counter)
    code_to_exercise: dict[str, str] = {}

    for path in two_d:
        logical_key = path.relative_to(source_root).as_posix().casefold()
        if logical_key in logical_keys:
            raise CatalogError(f"Duplicate 2D logical path: {path}")
        logical_keys.add(logical_key)

        type_code, type_info = decode_tail(path)
        exercise_raw = type_info.get("exercise")
        exercise = normalize_text(exercise_raw, "type_info.exercise", path)
        raw_names[exercise].add(exercise_raw)
        type_info_type = normalize_text(type_info.get("type"), "type_info.type", path)
        type_info_key = normalize_text(type_info.get("key"), "type_info.key", path)
        if type_info_key != type_code:
            raise CatalogError(f"{path}: root type {type_code} != type_info.key {type_info_key}")
        conditions = normalized_conditions(type_info.get("conditions"), path)
        frames = frame_state(path)
        metadata = {"typeInfoType": type_info_type, "conditions": conditions}
        key = metadata_key(type_info_type, conditions)
        metadata_by_key[key] = metadata

        previous_exercise = code_to_exercise.setdefault(type_code, exercise)
        if previous_exercise != exercise:
            raise CatalogError(
                f"Type {type_code} maps to multiple exercises: {previous_exercise!r}, {exercise!r}"
            )
        variants[(exercise, type_code)][key] += 1
        variant_frame_states[(exercise, type_code)][frames] += 1
        exercise_counts[exercise] += 1
        exercise_frame_states[exercise][frames] += 1

    actual_names = set(exercise_counts)
    expected_names = set(EXERCISE_IDS)
    if actual_names != expected_names:
        missing = sorted(expected_names - actual_names)
        unexpected = sorted(actual_names - expected_names)
        raise CatalogError(f"Exercise identity drift; missing={missing}, unexpected={unexpected}")

    exercises: list[dict[str, Any]] = []
    for exercise in sorted(actual_names, key=lambda item: EXERCISE_IDS[item]):
        type_entries: list[dict[str, Any]] = []
        for (variant_exercise, type_code), metadata_counts in variants.items():
            if variant_exercise != exercise:
                continue
            if len(metadata_counts) != 1:
                raise CatalogError(
                    f"Exercise {exercise!r} type {type_code} has conflicting condition metadata: "
                    f"{dict(metadata_counts)}"
                )
            key, record_count = metadata_counts.most_common(1)[0]
            type_frame_states = variant_frame_states[(exercise, type_code)]
            type_entries.append(
                {
                    "code": type_code,
                    "recordCount": record_count,
                    "emptyFrameRecordCount": type_frame_states["empty"],
                    "missingFrameRecordCount": type_frame_states["missing"],
                    "nonEmptyFrameRecordCount": type_frame_states["nonEmpty"],
                    **metadata_by_key[key],
                }
            )
        type_entries.sort(key=lambda item: int(item["code"]))
        exercises.append(
            {
                "id": EXERCISE_IDS[exercise],
                "name": exercise,
                "rawNames": sorted(raw_names[exercise]),
                "recordCount": exercise_counts[exercise],
                "emptyFrameRecordCount": exercise_frame_states[exercise]["empty"],
                "missingFrameRecordCount": exercise_frame_states[exercise]["missing"],
                "nonEmptyFrameRecordCount": exercise_frame_states[exercise]["nonEmpty"],
                "typeCount": len(type_entries),
                "types": type_entries,
            }
        )

    two_d_keys = {
        (path.parent.relative_to(source_root).as_posix().casefold(), path.stem.casefold())
        for path in two_d
    }
    three_d_keys = {
        (
            path.parent.relative_to(source_root).as_posix().casefold(),
            path.name[: -len("-3d.json")].casefold(),
        )
        for path in three_d
    }
    pair_count = len(two_d_keys & three_d_keys)

    catalog = {
        "schemaVersion": SCHEMA_VERSION,
        "sourceRules": {
            "authoritativeFiles": "2D JSON only (*-3d.json excluded)",
            "authoritativeFields": ["type", "type_info.exercise", "type_info.type", "type_info.conditions"],
            "textNormalization": "Unicode NFC, collapse whitespace, trim",
        },
        "manifest": {
            "jsonCount": len(all_json),
            "twoDJsonCount": len(two_d),
            "threeDJsonCount": len(three_d),
            "pairedBasenameCount": pair_count,
            "twoDOnlyCount": len(two_d_keys - three_d_keys),
            "threeDOnlyCount": len(three_d_keys - two_d_keys),
            "exerciseCount": len(exercises),
            "typeCount": sum(item["typeCount"] for item in exercises),
            "recordCount": sum(item["recordCount"] for item in exercises),
            "emptyFrameRecordCount": sum(item["emptyFrameRecordCount"] for item in exercises),
            "missingFrameRecordCount": sum(item["missingFrameRecordCount"] for item in exercises),
            "nonEmptyFrameRecordCount": sum(item["nonEmptyFrameRecordCount"] for item in exercises),
        },
        "exercises": exercises,
    }
    digest_payload = json.dumps(catalog, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    catalog["catalogSha256"] = hashlib.sha256(digest_payload.encode("utf-8")).hexdigest()
    return catalog


def kotlin_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def render_kotlin(catalog: dict[str, Any]) -> str:
    lines = [
        "// Generated by tools/generate_aihub_exercise_catalog.py. Do not edit manually.",
        "package com.example.trex_kotlin.catalog",
        "",
        "enum class AiHubExercise(",
        "    val id: String,",
        "    val displayName: String,",
        "    val typeInfoType: String,",
        "    val recordCount: Int,",
        "    typeCodes: Array<String>,",
        ") {",
    ]
    for index, item in enumerate(catalog["exercises"]):
        codes = ", ".join(kotlin_string(entry["code"]) for entry in item["types"])
        suffix = ";" if index == len(catalog["exercises"]) - 1 else ","
        type_info_types = sorted({entry["typeInfoType"] for entry in item["types"]})
        if len(type_info_types) != 1:
            raise CatalogError(f"Exercise {item['name']!r} has multiple type_info.type values")
        lines.extend(
            [
                f"    {ENUM_NAMES[item['id']]}(",
                f"        id = {kotlin_string(item['id'])},",
                f"        displayName = {kotlin_string(item['name'])},",
                f"        typeInfoType = {kotlin_string(type_info_types[0])},",
                f"        recordCount = {item['recordCount']},",
                f"        typeCodes = arrayOf({codes}),",
                f"    ){suffix}",
            ]
        )
    lines.extend(
        [
            "",
            "    val typeCodes: List<String> = typeCodes.toList()",
            "",
            "    companion object {",
            "        const val CATALOG_SHA256: String = " + kotlin_string(catalog["catalogSha256"]),
            "        val byId: Map<String, AiHubExercise> = entries.associateBy(AiHubExercise::id)",
            "        val byDisplayName: Map<String, AiHubExercise> = entries.associateBy(AiHubExercise::displayName)",
            "    }",
            "}",
            "",
        ]
    )
    return "\n".join(lines)


def write_or_check(path: Path, content: str, check: bool) -> None:
    if check:
        current = path.read_text(encoding="utf-8") if path.exists() else None
        if current != content:
            raise CatalogError(f"Generated file is stale: {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="Training 라벨링데이터 directory")
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    try:
        catalog = scan(args.source.resolve())
        artifact = json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
        kotlin = render_kotlin(catalog)
        write_or_check(args.project_root / "docs" / "aihub-exercise-catalog.json", artifact, args.check)
        write_or_check(
            args.project_root
            / "app/src/main/java/com/example/trex_kotlin/catalog/AiHubExerciseCatalog.kt",
            kotlin,
            args.check,
        )
    except (CatalogError, OSError) as error:
        print(f"catalog generation failed: {error}", file=sys.stderr)
        return 1

    manifest = catalog["manifest"]
    print(
        f"catalog ok: {manifest['exerciseCount']} exercises, {manifest['typeCount']} types, "
        f"{manifest['recordCount']} 2D records, sha256={catalog['catalogSha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
