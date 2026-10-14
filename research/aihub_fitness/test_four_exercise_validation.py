"""채점기의 중복 대응·누락 성공·좌우 추정 승격을 막는 검사."""
import json
from pathlib import Path
import tempfile
import unittest

from four_exercise_validation import match, plan, score


class ValidationTest(unittest.TestCase):
    def test_one_to_one_maximum_match(self):
        self.assertEqual([(0, 0), (1, 1)], match([1000, 1500], [700, 1200], 400))
        self.assertEqual(1, len(match([1000, 1100], [1050], 200)))

    def test_empty_truth_and_missing_prediction_are_not_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            plan(out, 1)
            pred = out / "pred.jsonl"
            pred.write_text("", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "정답이 없습니다"):
                score(out / "rep-truth.csv", pred, 750)
            (out / "rep-truth.csv").write_text("id,subject,exercise,return_ms,side\nx,p,사이드 런지,,\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "재생 결과 없음"):
                score(out / "rep-truth.csv", pred, 750)

    def test_unknown_does_not_fill_a_pair_and_negatives_count_false_events(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            truth = out / "truth.csv"
            truth.write_text("id,subject,exercise,return_ms,side\na,p,사이드 런지,1000,L\na,p,사이드 런지,3000,R\nb,p,스탠딩 니업,,\n", encoding="utf-8")
            pred = out / "pred.jsonl"
            rows = [dict(id="a", exercise="사이드 런지", publishedMs=[1000, 3000],
                         repForm=dict(reps=[dict(t_ms=1000, side="L"), dict(t_ms=3000)])),
                    dict(id="b", exercise="스탠딩 니업", publishedMs=[1500], repForm=dict(reps=[dict(t_ms=1500, side="R")]))]
            pred.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows), encoding="utf-8")
            result = score(truth, pred, 750)
            a, b = result["sets"]
            self.assertEqual((1, 0), (a["truth_display_count"], a["pred_display_count"]))
            self.assertEqual(1, a["known_side_matches"])
            self.assertEqual(1, b["false_events"])
            self.assertEqual(0, result["summary"]["exact_set_fraction"])
            truth.write_text("id,subject,exercise,return_ms,side\na,p,사이드 런지,1000,\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "독립 좌우 정답"):
                score(truth, pred, 750)


if __name__ == "__main__":
    unittest.main()
