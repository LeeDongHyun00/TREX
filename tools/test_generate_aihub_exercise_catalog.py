import tempfile
import unittest
from pathlib import Path

from generate_aihub_exercise_catalog import (
    CatalogError,
    decode_tail,
    frame_state,
    normalize_text,
    normalized_conditions,
    validate_identity_map,
)


class CatalogGeneratorValidationTest(unittest.TestCase):
    def test_normalizes_unicode_and_whitespace_but_preserves_distinct_words(self) -> None:
        path = Path("sample.json")
        self.assertEqual("바벨 컬", normalize_text("  바벨\t컬 \n", "exercise", path))
        self.assertEqual("Y - Exercise", normalize_text("Y  -  Exercise", "exercise", path))

    def test_rejects_empty_exercise(self) -> None:
        with self.assertRaises(CatalogError):
            normalize_text(" \t\n", "type_info.exercise", Path("empty.json"))

    def test_rejects_duplicate_normalized_conditions(self) -> None:
        with self.assertRaises(CatalogError):
            normalized_conditions(
                [
                    {"condition": "척추 중립", "value": True},
                    {"condition": " 척추  중립 ", "value": False},
                ],
                Path("duplicate-condition.json"),
            )

    def test_rejects_duplicate_or_invalid_stable_ids(self) -> None:
        with self.assertRaises(CatalogError):
            validate_identity_map({"운동 A": "same-id", "운동 B": "same-id"})
        with self.assertRaises(CatalogError):
            validate_identity_map({"운동": "Not Valid"})

    def test_tail_decoder_preserves_three_digit_type_code(self) -> None:
        payload = (
            '{"frames":[],"type":"001","type_info":'
            '{"key":"001","type":"맨몸 운동","pose":"맨몸 운동",'
            '"exercise":"테스트","conditions":[{"condition":"정렬","value":true}]}}'
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample.json"
            path.write_text(payload, encoding="utf-8")
            type_code, type_info = decode_tail(path)

        self.assertEqual("001", type_code)
        self.assertEqual("테스트", type_info["exercise"])

    def test_empty_frames_are_a_quality_state_not_a_parse_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            empty = Path(directory) / "empty.json"
            non_empty = Path(directory) / "non-empty.json"
            missing = Path(directory) / "missing.json"
            empty.write_text('{"frames": []}', encoding="utf-8")
            non_empty.write_text('{"frames": [{"view1": {}}]}', encoding="utf-8")
            missing.write_text('{"type": "001"}', encoding="utf-8")

            self.assertEqual("empty", frame_state(empty))
            self.assertEqual("nonEmpty", frame_state(non_empty))
            self.assertEqual("missing", frame_state(missing))


if __name__ == "__main__":
    unittest.main()
