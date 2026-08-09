#!/usr/bin/env python3
"""Create a portable, metadata-only snapshot of an AI Hub pose dataset.

This tool deliberately never reads file contents during its normal inventory.
``--references`` is the opt-in exception: it parses JSON files to report
``img_key`` reference health, but it still does not hash image contents.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator


SCHEMA_VERSION = 1
JPG_EXTENSIONS = {".jpg", ".jpeg"}
DUPLICATE_PATH_SAMPLE_LIMIT = 5
DUPLICATE_GROUP_SAMPLE_LIMIT = 100
LABELING_DATA_COMPONENT = "라벨링데이터"
RAW_DATA_COMPONENT = "원시데이터"


class AuditError(RuntimeError):
    """Raised when an inventory cannot be completed faithfully."""


@dataclass
class CandidateGroup:
    file_count: int = 0
    sample_relative_paths: list[str] = field(default_factory=list)

    def add(self, relative_path: str) -> None:
        self.file_count += 1
        if len(self.sample_relative_paths) < DUPLICATE_PATH_SAMPLE_LIMIT:
            self.sample_relative_paths.append(relative_path)


def _is_reparse_point(mode: int, file_attributes: int) -> bool:
    return stat.S_ISLNK(mode) or bool(
        file_attributes & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x0400)
    )


def _lstat_is_reparse(path: Path) -> bool:
    try:
        metadata = path.lstat()
    except OSError as error:
        raise AuditError(f"cannot stat {path}: {error}") from error
    return _is_reparse_point(metadata.st_mode, getattr(metadata, "st_file_attributes", 0))


def _iter_regular_files(source_root: Path) -> Iterator[tuple[Path, os.stat_result]]:
    """Yield only regular files below source_root without following reparse points.

    Reparse entries are yielded once so the caller can count them, but they are
    never traversed or counted as regular input files.
    """

    pending = [source_root]
    while pending:
        directory = pending.pop()
        try:
            with os.scandir(directory) as entries:
                ordered_entries = sorted(entries, key=lambda item: item.name)
        except OSError as error:
            raise AuditError(f"cannot read directory {directory}: {error}") from error
        for entry in reversed(ordered_entries):
            path = Path(entry.path)
            try:
                metadata = entry.stat(follow_symlinks=False)
            except OSError as error:
                raise AuditError(f"cannot stat {path}: {error}") from error
            if _is_reparse_point(metadata.st_mode, getattr(metadata, "st_file_attributes", 0)):
                yield path, metadata  # Caller records it, never traverses or counts it.
            elif stat.S_ISDIR(metadata.st_mode):
                pending.append(path)
            elif stat.S_ISREG(metadata.st_mode):
                yield path, metadata


def _extension(path: Path) -> str:
    return path.suffix.casefold() or "(none)"


def _top_level_name(relative_path: str) -> str:
    for component in relative_path.split("/"):
        normalized = component.casefold()
        if normalized == "training" or normalized.endswith(".training"):
            return "Training"
        if normalized == "validation" or normalized.endswith(".validation"):
            return "Validation"
    return "Other"


def _day_name(relative_path: str) -> str | None:
    components = relative_path.split("/")
    for component in components:
        normalized = component.casefold()
        for day in ("day04", "day05", "day17"):
            # Source directories are commonly named e.g. Day05_200925_F.
            if normalized == day or normalized.startswith(day + "_"):
                return day.capitalize()
    return None


def _training_data_scope(relative_path: str) -> str:
    components = {component.casefold() for component in relative_path.split("/")}
    if LABELING_DATA_COMPONENT.casefold() in components:
        return "labeling"
    if RAW_DATA_COMPONENT.casefold() in components:
        return "raw"
    return "other"


def _canonical_json(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"


def portable_snapshot_payload(manifest: dict[str, Any]) -> dict[str, Any]:
    """Return the identity payload that is deliberately independent of location/time."""

    return {
        key: value
        for key, value in manifest.items()
        if key not in {"generatedAt", "sourceRoot", "portableSnapshotSha256"}
    }


def portable_snapshot_sha256(manifest: dict[str, Any]) -> str:
    return hashlib.sha256(_canonical_json(portable_snapshot_payload(manifest)).encode("utf-8")).hexdigest()


def _iter_img_keys(value: Any) -> Iterator[str]:
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "img_key" and isinstance(child, str):
                yield child
            yield from _iter_img_keys(child)
    elif isinstance(value, list):
        for child in value:
            yield from _iter_img_keys(child)


def _reference_stats(
    json_paths: list[Path],
    exact_jpg_paths: set[str],
    jpg_basenames: Counter[str],
) -> dict[str, int]:
    stats: Counter[str] = Counter()
    for path in json_paths:
        stats["jsonFilesScanned"] += 1
        try:
            with path.open("r", encoding="utf-8") as source:
                payload = json.load(source)
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            stats["jsonFilesWithParseError"] += 1
            continue
        stats["jsonFilesParsed"] += 1
        for raw_key in _iter_img_keys(payload):
            stats["imgKeyReferenceCount"] += 1
            candidate = raw_key.replace("\\", "/").lstrip("./").casefold()
            if candidate in exact_jpg_paths:
                stats["resolvedImgKeyReferenceCount"] += 1
            elif jpg_basenames.get(Path(candidate).name, 0) == 1:
                stats["resolvedImgKeyReferenceCount"] += 1
            elif jpg_basenames.get(Path(candidate).name, 0) > 1:
                stats["ambiguousImgKeyReferenceCount"] += 1
            else:
                stats["missingImgKeyReferenceCount"] += 1
    # Explicit zeroes make the schema stable for an empty fixture or dataset.
    return {
        key: stats[key]
        for key in (
            "jsonFilesScanned",
            "jsonFilesParsed",
            "jsonFilesWithParseError",
            "imgKeyReferenceCount",
            "resolvedImgKeyReferenceCount",
            "ambiguousImgKeyReferenceCount",
            "missingImgKeyReferenceCount",
        )
    }


def build_manifest(
    source: Path,
    *,
    include_references: bool = False,
    generated_at: str | None = None,
    source_label: str | None = None,
) -> dict[str, Any]:
    """Inventory a source tree using directory entries and metadata only by default."""

    source_root = Path(os.path.abspath(os.fspath(source)))
    if not source_root.exists() or not source_root.is_dir():
        raise AuditError(f"source directory does not exist: {source}")
    if _lstat_is_reparse(source_root):
        raise AuditError(f"source directory must not be a symlink or reparse point: {source_root}")

    extension_counts: Counter[str] = Counter()
    extension_bytes: Counter[str] = Counter()
    top_level_counts: Counter[str] = Counter()
    top_level_bytes: Counter[str] = Counter()
    training_day_jpg_counts: dict[str, Counter[str]] = {
        day: Counter() for day in ("Day04", "Day05", "Day17")
    }
    # Unique basenames keep only their first path. A CandidateGroup/list is allocated
    # only after a duplicate appears, which keeps the default scan bounded in practice.
    candidate_groups: dict[str, str | CandidateGroup] = {}
    json_paths: list[Path] = []
    exact_jpg_paths: set[str] = set()
    jpg_basenames: Counter[str] = Counter()
    file_count = 0
    file_bytes = 0
    skipped_reparse_point_count = 0
    metadata_tree_hash = hashlib.sha256()

    for path, metadata in _iter_regular_files(source_root):
        if _is_reparse_point(metadata.st_mode, getattr(metadata, "st_file_attributes", 0)):
            skipped_reparse_point_count += 1
            continue
        relative_path = path.relative_to(source_root).as_posix()
        extension = _extension(path)
        byte_count = metadata.st_size
        file_count += 1
        file_bytes += byte_count
        metadata_tree_hash.update(relative_path.encode("utf-8"))
        metadata_tree_hash.update(b"\0")
        metadata_tree_hash.update(str(byte_count).encode("ascii"))
        metadata_tree_hash.update(b"\n")
        extension_counts[extension] += 1
        extension_bytes[extension] += byte_count
        top_level = _top_level_name(relative_path)
        top_level_counts[top_level] += 1
        top_level_bytes[top_level] += byte_count

        basename = path.name.casefold()
        existing_group = candidate_groups.get(basename)
        if existing_group is None:
            candidate_groups[basename] = relative_path
        elif isinstance(existing_group, str):
            group = CandidateGroup(file_count=1, sample_relative_paths=[existing_group])
            group.add(relative_path)
            candidate_groups[basename] = group
        else:
            existing_group.add(relative_path)
        if extension == ".json" and include_references:
            json_paths.append(path)
        if extension in JPG_EXTENSIONS:
            if include_references:
                exact_jpg_paths.add(relative_path.casefold())
                jpg_basenames[basename] += 1
            if top_level == "Training":
                day = _day_name(relative_path)
                if day is not None:
                    training_day_jpg_counts[day][_training_data_scope(relative_path)] += 1

    duplicate_groups = sorted(
        (
            (basename, group)
            for basename, group in candidate_groups.items()
            if isinstance(group, CandidateGroup)
        ),
        key=lambda item: item[0],
    )
    manifest: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": generated_at
        or datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "sourceRoot": source_label or str(source_root),
        "inventory": {
            "fileCount": file_count,
            "fileBytes": file_bytes,
            "metadataTreeSha256": metadata_tree_hash.hexdigest(),
            "metadataTreeIdentityScope": "relative UTF-8 path and file size; file contents are not hashed",
            "extensions": [
                {"extension": extension, "fileCount": extension_counts[extension], "fileBytes": extension_bytes[extension]}
                for extension in sorted(extension_counts)
            ],
            "topLevel": [
                {"name": name, "fileCount": top_level_counts[name], "fileBytes": top_level_bytes[name]}
                for name in ("Training", "Validation", "Other")
            ],
            "topLevelClassificationScope": (
                "first relative path component equal to Training/Validation or ending in "
                ".Training/.Validation"
            ),
            "trainingDayJpg": [
                {
                    "day": day,
                    "fileCount": sum(training_day_jpg_counts[day].values()),
                    "labelingFileCount": training_day_jpg_counts[day]["labeling"],
                    "rawFileCount": training_day_jpg_counts[day]["raw"],
                    "otherFileCount": training_day_jpg_counts[day]["other"],
                }
                for day in ("Day04", "Day05", "Day17")
            ],
            "skippedReparsePointCount": skipped_reparse_point_count,
        },
        "duplicateCandidates": {
            "status": "unverifiedByContent",
            "grouping": "case-folded basename only; files are not confirmed duplicates without content comparison",
            "groupCount": len(duplicate_groups),
            "fileCount": sum(group.file_count for _, group in duplicate_groups),
            "sampleLimit": DUPLICATE_GROUP_SAMPLE_LIMIT,
            "sampleTruncated": len(duplicate_groups) > DUPLICATE_GROUP_SAMPLE_LIMIT,
            "groups": [
                {
                    "basename": basename,
                    "fileCount": group.file_count,
                    "sampleRelativePaths": group.sample_relative_paths,
                }
                for basename, group in duplicate_groups[:DUPLICATE_GROUP_SAMPLE_LIMIT]
            ],
        },
    }
    if include_references:
        manifest["referenceStats"] = _reference_stats(
            json_paths,
            exact_jpg_paths,
            jpg_basenames,
        )
    manifest["portableSnapshotSha256"] = portable_snapshot_sha256(manifest)
    return manifest


def _expected_identity(path: Path) -> str:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AuditError(f"cannot read expected manifest {path}: {error}") from error
    if not isinstance(payload, dict):
        raise AuditError(f"expected manifest must be a JSON object: {path}")
    supplied = payload.get("portableSnapshotSha256")
    computed = portable_snapshot_sha256(payload)
    if supplied is not None and supplied != computed:
        raise AuditError(f"expected manifest has an invalid portableSnapshotSha256: {path}")
    return supplied or computed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="dataset root to inventory")
    parser.add_argument("--output", type=Path, help="write canonical JSON to this file instead of stdout")
    parser.add_argument("--check", type=Path, metavar="EXPECTED_MANIFEST", help="fail if portable identity differs")
    parser.add_argument(
        "--references",
        action="store_true",
        help=(
            "parse every JSON img_key and report JPG reference statistics "
            "(content-reading, slower and more memory-intensive)"
        ),
    )
    parser.add_argument("--generated-at", help="ISO-8601 timestamp override for reproducible artifacts")
    parser.add_argument(
        "--source-label",
        help="portable sourceRoot label for a committed manifest (for example: data)",
    )
    args = parser.parse_args(argv)

    try:
        manifest = build_manifest(
            args.source,
            include_references=args.references,
            generated_at=args.generated_at,
            source_label=args.source_label,
        )
        if args.references and manifest["referenceStats"]["jsonFilesWithParseError"] > 0:
            raise AuditError(
                "reference scan is incomplete: "
                f"{manifest['referenceStats']['jsonFilesWithParseError']} JSON file(s) could not be parsed"
            )
        if args.check is not None:
            expected = _expected_identity(args.check)
            if manifest["portableSnapshotSha256"] != expected:
                raise AuditError(
                    "snapshot identity mismatch: "
                    f"expected={expected} actual={manifest['portableSnapshotSha256']}"
                )
        rendered = _canonical_json(manifest)
        if args.output is None:
            if args.check is None:
                sys.stdout.write(rendered)
            else:
                print(f"snapshot identity matched: {manifest['portableSnapshotSha256']}")
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8", newline="\n")
    except AuditError as error:
        print(f"dataset snapshot audit failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
