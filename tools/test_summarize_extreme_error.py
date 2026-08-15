import unittest

from summarize_extreme_error import (
    MINIMUM_PAIRED_FRAMES,
    excursion_overstatements,
    summarise,
)
import summarize_extreme_error as extreme


def _rows(clip, side, extreme, pairs, exercise="랫풀 다운", chain="SHOULDER", confidences=None):
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
            **({"confidence": confidences[i]} if confidences is not None else {}),
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


class RuntimeExactSelectorTest(unittest.TestCase):
    """The floors are quantiles under the runtime's own side choice, reproduced verbatim."""

    def test_the_first_jointly_credible_frame_decides_and_left_wins_ties(self):
        from summarize_extreme_error import selector_views

        n = MINIMUM_PAIRED_FRAMES
        # Left is deeper (would win runtimeLike) but Right is more confident on frame 0, so the
        # runtime locks Right — and holds it even though Left overtakes on later frames.
        left = _rows("c1", "Left", "min", [(60, 80)] * n, confidences=[0.60] + [0.99] * (n - 1))
        right = _rows("c1", "Right", "min", [(90, 80)] * n, confidences=[0.70] + [0.56] * (n - 1))
        views = selector_views(left + right)
        self.assertEqual("Right", views["runtimeExact"]["SHOULDER"][0]["side"])
        self.assertEqual("Left", views["runtimeLike"]["SHOULDER"][0]["side"])
        # Mean confidence would pick Left (0.91 vs 0.59): the two confidence rules can differ,
        # which is why both are reported.
        self.assertEqual("Left", views["runtimeByMeanConfidence"]["SHOULDER"][0]["side"])

        tie_l = _rows("c2", "Left", "min", [(90, 80)] * n, confidences=[0.8] * n)
        tie_r = _rows("c2", "Right", "min", [(60, 80)] * n, confidences=[0.8] * n)
        views = selector_views(tie_l + tie_r)
        self.assertEqual("Left", views["runtimeExact"]["SHOULDER"][0]["side"])

    def test_the_lock_is_decided_in_capture_order_not_emission_order(self):
        # The bridge tool's workers finish out of order, so rows arrive shuffled. The runtime
        # locks on the FIRST captured joint frame; the selector must sort by key to find it.
        import random

        from summarize_extreme_error import selector_views

        n = MINIMUM_PAIRED_FRAMES
        left = _rows("c5", "Left", "min", [(60, 80)] * n, confidences=[0.60] + [0.99] * (n - 1))
        right = _rows("c5", "Right", "min", [(90, 80)] * n, confidences=[0.70] + [0.56] * (n - 1))
        rows = left + right
        for seed in range(5):
            random.Random(seed).shuffle(rows)
            views = selector_views(rows)
            self.assertEqual("Right", views["runtimeExact"]["SHOULDER"][0]["side"], f"seed {seed}")

    def test_rows_without_the_field_leave_the_confidence_views_empty(self):
        from summarize_extreme_error import selector_views

        n = MINIMUM_PAIRED_FRAMES
        left = _rows("c3", "Left", "min", [(60, 80)] * n)
        right = _rows("c3", "Right", "min", [(90, 80)] * n)
        views = selector_views(left + right)
        self.assertEqual([], views["runtimeExact"]["SHOULDER"])
        self.assertEqual(1, len(views["runtimeLike"]["SHOULDER"]))


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
