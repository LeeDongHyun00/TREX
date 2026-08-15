import unittest

from summarize_extreme_error import (
    MINIMUM_PAIRED_FRAMES,
    excursion_overstatements,
    summarise,
)
import summarize_extreme_error as extreme


def _rows(clip, side, extreme, pairs, exercise="랫풀 다운", chain="SHOULDER"):
    return [
        {
            "clip": clip,
            "key": f"{clip}-{i}",
            "side": side,
            "exercise": exercise,
            "chain": chain,
            "extreme": extreme,
            "day": "Day17_201017_F",
            "subject": "Z1",
            "mediapipe": mp,
            "aihub": gt,
        }
        for i, (mp, gt) in enumerate(pairs)
    ]


class OverstatementTest(unittest.TestCase):
    def test_a_min_chain_reads_how_far_short_the_mediapipe_minimum_sits(self):
        # Label bottoms out at 60; MediaPipe's shallowest reading is 75. The excursion overstates
        # the shortfall by 15 — the sign that a "you were N short" sentence would inherit.
        rows = _rows("c1", "Left", "min", [(120, 118), (95, 90), (75, 60), (80, 65), (110, 105)])
        per_chain = excursion_overstatements(rows)
        self.assertEqual(1, len(per_chain["SHOULDER"]))
        self.assertAlmostEqual(15.0, per_chain["SHOULDER"][0]["overstatementDegrees"], places=2)

    def test_a_max_chain_flips_the_sign_convention(self):
        # For an extension chain the shortfall is reading LOWER than the truth at the top.
        rows = _rows("c2", "Right", "max", [(100, 100), (150, 160), (155, 170), (140, 150), (120, 120)])
        per_chain = excursion_overstatements(rows)
        self.assertAlmostEqual(15.0, per_chain["SHOULDER"][0]["overstatementDegrees"], places=2)

    def test_sides_are_never_mixed(self):
        # The whole point of the sides stream: Left is paired with Left, Right with Right, and
        # each yields its own excursion record.
        left = _rows("c3", "Left", "min", [(90, 80)] * MINIMUM_PAIRED_FRAMES)
        right = _rows("c3", "Right", "min", [(70, 80)] * MINIMUM_PAIRED_FRAMES)
        per_chain = excursion_overstatements(left + right)
        values = sorted(r["overstatementDegrees"] for r in per_chain["SHOULDER"])
        self.assertEqual([-10.0, 10.0], values)

    def test_a_side_below_the_paired_floor_abstains(self):
        rows = _rows("c4", "Left", "min", [(90, 80)] * (MINIMUM_PAIRED_FRAMES - 1))
        self.assertEqual({}, dict(excursion_overstatements(rows)))


class SummaryTest(unittest.TestCase):
    def test_exceedance_is_the_fraction_at_or_above(self):
        records = [
            {"clip": f"c{i}", "side": "Left", "exercise": "x", "day": "d", "subject": "s",
             "extreme": "min", "pairedFrames": 5, "overstatementDegrees": v}
            for i, v in enumerate([5.0, 15.0, 25.0, 35.0])
        ]
        s = summarise(records)
        self.assertEqual(4, s["excursions"])
        self.assertAlmostEqual(0.75, s["exceedance"]["P(S>=15)"], places=4)
        self.assertAlmostEqual(0.25, s["exceedance"]["P(S>=35)"], places=4)
        self.assertEqual(["min"], s["extremes"])


class HonestyTest(unittest.TestCase):
    def test_the_module_documents_what_it_cannot_answer(self):
        doc = extreme.__doc__ or ""
        for phrase in ("cannot answer", "bootstrap", "extension", "IMAGE-mode"):
            self.assertIn(phrase.lower(), doc.lower())


if __name__ == "__main__":
    unittest.main()
