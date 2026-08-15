import unittest

from measure_mediapipe_aihub_bridge import LABEL_CHAINS, _label_angle, _label_sides


def _pts(**named):
    """AI Hub label points from (x, y, z) triples keyed by joint name."""
    return {name: {"x": x, "y": y, "z": z} for name, (x, y, z) in named.items()}


class LabelSidesTest(unittest.TestCase):
    """The per-side label reader is the collapsed reader with the reduce removed — nothing else."""

    def test_each_side_is_reported_under_its_own_name(self):
        # Left knee straight (180), right knee at a right angle (90). The collapsed reader
        # returns one number for the chain; the per-side reader must return both, keyed.
        pts = _pts(
            **{
                "Left Hip": (0, 1, 0), "Left Knee": (0, 0, 0), "Left Ankle": (0, -1, 0),
                "Right Hip": (1, 1, 0), "Right Knee": (1, 0, 0), "Right Ankle": (2, 0, 0),
            }
        )
        sides = _label_sides(pts, ("x", "y", "z"), "KNEE")
        self.assertEqual({"Left", "Right"}, set(sides))
        self.assertAlmostEqual(180.0, sides["Left"], places=6)
        self.assertAlmostEqual(90.0, sides["Right"], places=6)

    def test_the_collapsed_reader_is_exactly_the_extreme_of_the_sides(self):
        # Contract: collapsing per-side values with min/max reproduces the old reader
        # bit-for-bit, so the sides stream adds information without changing the old numbers.
        pts = _pts(
            **{
                "Left Hip": (0, 1, 0), "Left Knee": (0, 0, 0), "Left Ankle": (0.5, -0.8, 0),
                "Right Hip": (1, 1, 0), "Right Knee": (1, 0, 0), "Right Ankle": (1.9, -0.3, 0),
            }
        )
        sides = _label_sides(pts, ("x", "y", "z"), "KNEE")
        self.assertEqual(min(sides.values()), _label_angle(pts, ("x", "y", "z"), "KNEE", "min"))
        self.assertEqual(max(sides.values()), _label_angle(pts, ("x", "y", "z"), "KNEE", "max"))

    def test_a_side_missing_a_joint_is_simply_absent(self):
        # No guessing, no zero-fill: an unobservable side contributes no pair. This is what
        # lets the sides stream show WHICH side dropped, which the collapsed stream hid.
        pts = _pts(
            **{
                "Left Hip": (0, 1, 0), "Left Knee": (0, 0, 0), "Left Ankle": (0, -1, 0),
                "Right Hip": (1, 1, 0), "Right Knee": (1, 0, 0),
            }
        )
        sides = _label_sides(pts, ("x", "y", "z"), "KNEE")
        self.assertEqual({"Left"}, set(sides))

    def test_every_chain_the_runtime_names_has_both_sides_defined(self):
        for chain, per_side in LABEL_CHAINS.items():
            self.assertEqual({"Left", "Right"}, set(per_side), chain)


class SidesStreamContractTest(unittest.TestCase):
    """The archive tool must emit sides only alongside — never instead of — the old streams."""

    def test_the_worker_keeps_the_collapsed_extreme_and_adds_the_sides(self):
        import inspect

        import measure_bridge_from_archives as tool

        source = inspect.getsource(tool._worker)
        # The collapsed extreme is still what decides `chain_below_confidence`.
        self.assertIn("angle = chain_extreme(sides, extreme_by_key[clip_id])", source)
        # And the per-side dict rides along in the same message.
        self.assertIn("outbox.put((clip_id, img_key, outcome, angle, sides))", source)

    def test_the_sides_rows_carry_full_identity_and_the_runtime_confidence(self):
        import inspect

        import measure_bridge_from_archives as tool

        source = inspect.getsource(tool.run)
        for field in ('"clip": clip_id', '"key": key', '"side": side', '"confidence": round(mp_confidence, 4)'):
            self.assertIn(field, source, f"the sides stream must carry {field}")

    def test_the_confidence_is_the_runtimes_own_definition(self):
        # FormCheckGeometry: landmark.confidence = min(visibility, presence); chain confidence
        # = minOf over the three joints; the side with the higher value wins. The worker must
        # compute the identical quantity or the error card reproduces a different selector.
        import inspect

        import measure_bridge_from_archives as tool

        source = inspect.getsource(tool._worker)
        self.assertIn("confidence = min(min(lm.visibility, lm.presence) for lm in points)", source)
        self.assertIn("sides[side] = (angle, confidence)", source)


if __name__ == "__main__":
    unittest.main()
