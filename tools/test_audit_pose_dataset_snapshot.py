import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from audit_pose_dataset_snapshot import build_manifest, portable_snapshot_sha256


SCRIPT = Path(__file__).with_name("audit_pose_dataset_snapshot.py")
FIXED_TIME = "2026-08-09T00:00:00Z"


def write_fixture(root: Path, reverse: bool = False) -> None:
    files = [
        ("Training/Day05_200925_F/a.JPG", b"a"),
        ("Training/Day04/sample.json", b'{"frames":[]}'),
        ("Training/Day17/repeat.jpg", b"one"),
        ("Validation/Day17/repeat.jpg", b"two"),
        ("Validation/data.bin", b"xyz"),
        ("notes", b"n"),
    ]
    for relative, content in reversed(files) if reverse else files:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)


class PoseDatasetSnapshotTest(unittest.TestCase):
    def test_portable_identity_and_canonical_order_do_not_depend_on_root_or_creation_order(self) -> None:
        with tempfile.TemporaryDirectory() as first_dir, tempfile.TemporaryDirectory() as second_dir:
            first = Path(first_dir)
            second = Path(second_dir)
            write_fixture(first)
            write_fixture(second, reverse=True)
            first_manifest = build_manifest(first, generated_at=FIXED_TIME)
            second_manifest = build_manifest(second, generated_at=FIXED_TIME)
            labelled_manifest = build_manifest(first, generated_at=FIXED_TIME, source_label="data")

        self.assertNotEqual(first_manifest["sourceRoot"], second_manifest["sourceRoot"])
        self.assertEqual(first_manifest["portableSnapshotSha256"], second_manifest["portableSnapshotSha256"])
        self.assertEqual("data", labelled_manifest["sourceRoot"])
        self.assertEqual(first_manifest["portableSnapshotSha256"], labelled_manifest["portableSnapshotSha256"])
        self.assertEqual(portable_snapshot_sha256(first_manifest), portable_snapshot_sha256(second_manifest))
        self.assertEqual([item["extension"] for item in first_manifest["inventory"]["extensions"]], ["(none)", ".bin", ".jpg", ".json"])

    def test_metadata_identity_detects_renames_even_when_counts_and_bytes_match(self) -> None:
        with tempfile.TemporaryDirectory() as first_dir, tempfile.TemporaryDirectory() as second_dir:
            first = Path(first_dir)
            second = Path(second_dir)
            (first / "Training").mkdir()
            (second / "Training").mkdir()
            (first / "Training/a.json").write_text("{}", encoding="utf-8")
            (second / "Training/b.json").write_text("{}", encoding="utf-8")

            first_manifest = build_manifest(first, generated_at=FIXED_TIME)
            second_manifest = build_manifest(second, generated_at=FIXED_TIME)

        self.assertEqual(first_manifest["inventory"]["fileCount"], second_manifest["inventory"]["fileCount"])
        self.assertEqual(first_manifest["inventory"]["fileBytes"], second_manifest["inventory"]["fileBytes"])
        self.assertNotEqual(
            first_manifest["inventory"]["metadataTreeSha256"],
            second_manifest["inventory"]["metadataTreeSha256"],
        )
        self.assertNotEqual(first_manifest["portableSnapshotSha256"], second_manifest["portableSnapshotSha256"])

    def test_inventory_reports_top_level_day_counts_unknown_extensions_and_unverified_candidates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(root)
            manifest = build_manifest(root, generated_at=FIXED_TIME)

        top_level = {item["name"]: item["fileCount"] for item in manifest["inventory"]["topLevel"]}
        days = {item["day"]: item["fileCount"] for item in manifest["inventory"]["trainingDayJpg"]}
        extensions = {item["extension"]: item["fileCount"] for item in manifest["inventory"]["extensions"]}
        self.assertEqual({"Training": 3, "Validation": 2, "Other": 1}, top_level)
        self.assertEqual({"Day04": 0, "Day05": 1, "Day17": 1}, days)
        self.assertEqual(1, extensions[".bin"])
        candidates = manifest["duplicateCandidates"]
        self.assertEqual("unverifiedByContent", candidates["status"])
        self.assertEqual(1, candidates["groupCount"])
        self.assertEqual("repeat.jpg", candidates["groups"][0]["basename"])

    def test_numbered_aihub_partition_directories_are_classified(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            training = root / "013.dataset/1.Training/source/Day05_200925_F/frame.jpg"
            validation = root / "013.dataset/2.Validation/labels/sample.json"
            training.parent.mkdir(parents=True)
            validation.parent.mkdir(parents=True)
            training.write_bytes(b"jpg")
            validation.write_text("{}", encoding="utf-8")

            manifest = build_manifest(root, generated_at=FIXED_TIME)

        top_level = {item["name"]: item["fileCount"] for item in manifest["inventory"]["topLevel"]}
        days = {item["day"]: item["fileCount"] for item in manifest["inventory"]["trainingDayJpg"]}
        self.assertEqual({"Training": 1, "Validation": 1, "Other": 0}, top_level)
        self.assertEqual(1, days["Day05"])

    def test_training_day_counts_separate_labeling_raw_and_other_images(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            day = root / "013.dataset/1.Training"
            paths = (
                day / "라벨링데이터/bodyweight/Day05_200925_F/label.jpg",
                day / "원시데이터/Day05_200925_F/raw.jpg",
                day / "misc/Day05_200925_F/other.jpg",
            )
            for path in paths:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"jpg")

            manifest = build_manifest(root, generated_at=FIXED_TIME)

        day05 = next(
            item for item in manifest["inventory"]["trainingDayJpg"] if item["day"] == "Day05"
        )
        self.assertEqual(3, day05["fileCount"])
        self.assertEqual(1, day05["labelingFileCount"])
        self.assertEqual(1, day05["rawFileCount"])
        self.assertEqual(1, day05["otherFileCount"])

    def test_windows_reparse_attribute_is_recognized_without_junction_privileges(self) -> None:
        from audit_pose_dataset_snapshot import _is_reparse_point

        self.assertTrue(_is_reparse_point(0, 0x0400))

    def test_reparse_points_are_not_traversed_or_counted_when_symlinks_are_available(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "visible.txt").write_text("ok", encoding="utf-8")
            outside = root / "outside"
            outside.mkdir()
            (outside / "hidden.txt").write_text("hidden", encoding="utf-8")
            try:
                os.symlink(outside, root / "linked-outside", target_is_directory=True)
            except (NotImplementedError, OSError) as error:
                self.skipTest(f"symlink creation is unavailable: {error}")
            manifest = build_manifest(root, generated_at=FIXED_TIME)

        self.assertEqual(1, manifest["inventory"]["fileCount"])
        self.assertEqual(1, manifest["inventory"]["skippedReparsePointCount"])

    def test_reference_parsing_is_opt_in_and_check_success_and_mismatch_are_reported(self) -> None:
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as output_directory:
            root = Path(directory)
            (root / "Training/Day05").mkdir(parents=True)
            (root / "Training/Day05/image.jpg").write_bytes(b"jpg")
            (root / "Training/Day05/good.json").write_text(
                '{"frames":[{"view1":{"img_key":"image.jpg"}}]}', encoding="utf-8"
            )
            (root / "Training/Day05/bad.json").write_text("not-json", encoding="utf-8")
            default_manifest = build_manifest(root, generated_at=FIXED_TIME)
            reference_manifest = build_manifest(root, include_references=True, generated_at=FIXED_TIME)
            expected = Path(output_directory) / "expected.json"
            expected.write_text(json.dumps(default_manifest), encoding="utf-8")

            success = subprocess.run(
                [sys.executable, str(SCRIPT), str(root), "--generated-at", FIXED_TIME, "--check", str(expected)],
                text=True,
                capture_output=True,
                check=False,
            )
            (root / "Training/Day05/added.txt").write_text("new", encoding="utf-8")
            mismatch = subprocess.run(
                [sys.executable, str(SCRIPT), str(root), "--generated-at", FIXED_TIME, "--check", str(expected)],
                text=True,
                capture_output=True,
                check=False,
            )
            reference_error = subprocess.run(
                [sys.executable, str(SCRIPT), str(root), "--generated-at", FIXED_TIME, "--references"],
                text=True,
                capture_output=True,
                check=False,
            )

        self.assertNotIn("referenceStats", default_manifest)
        self.assertEqual(1, reference_manifest["referenceStats"]["jsonFilesWithParseError"])
        self.assertEqual(1, reference_manifest["referenceStats"]["resolvedImgKeyReferenceCount"])
        self.assertEqual(0, success.returncode, success.stderr)
        self.assertIn("snapshot identity matched", success.stdout)
        self.assertNotEqual(0, mismatch.returncode)
        self.assertIn("snapshot identity mismatch", mismatch.stderr)
        self.assertNotEqual(0, reference_error.returncode)
        self.assertIn("reference scan is incomplete", reference_error.stderr)


if __name__ == "__main__":
    unittest.main()
