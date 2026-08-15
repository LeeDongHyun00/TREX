import unittest

from measure_gate_bound_quantiles import (
    ATTEMPT_DEGREES,
    MINIMUM_COMPANION_FRAMES,
    REST_DEGREES,
    percentile,
    reconstruct_excursions,
    sweep_shapes,
)
import measure_gate_bound_quantiles as quantiles


def _frame(knee: float, hip: float) -> dict:
    """A left-side lower-body frame posed at the requested included angles.

    Hip above knee on the y axis. The ankle sits at the requested knee included angle measured
    from the knee->hip direction, mirrored so 180 degrees is a straight leg; the shoulder sits
    at the requested hip included angle from the hip->knee direction, so 180 degrees is an
    upright torso. The same construction the Kotlin test harness uses.
    """
    import math

    hip_at = (0.0, 1.0, 0.0)
    knee_at = (0.0, 0.5, 0.0)
    knee_rad = math.radians(knee)
    # knee->hip points up, so the ankle direction (sin k, cos k) makes the included angle k:
    # 180 puts the ankle straight below, a straight leg.
    ankle_at = (
        knee_at[0] + 0.5 * math.sin(knee_rad),
        knee_at[1] + 0.5 * math.cos(knee_rad),
        0.0,
    )
    hip_rad = math.radians(hip)
    # hip->knee points down, so the shoulder direction (sin h, -cos h) makes the included angle
    # h: 180 puts the shoulder straight above, an upright torso.
    shoulder_at = (
        hip_at[0] + 0.6 * math.sin(hip_rad),
        hip_at[1] - 0.6 * math.cos(hip_rad),
        0.0,
    )

    def node(p):
        return {"x": p[0], "y": p[1], "z": p[2]}

    return {
        "pts": {
            "Left Hip": node(hip_at),
            "Left Knee": node(knee_at),
            "Left Ankle": node(ankle_at),
            "Left Shoulder": node(shoulder_at),
        }
    }


class FixtureSanityTest(unittest.TestCase):
    def test_the_fixture_reproduces_its_requested_angles(self):
        pts = _frame(knee=100.0, hip=120.0)["pts"]
        knee = quantiles.chain_angle(pts, "Left", "KNEE")
        hip = quantiles.chain_angle(pts, "Left", "HIP")
        self.assertAlmostEqual(100.0, knee, delta=0.5)
        self.assertAlmostEqual(120.0, hip, delta=0.5)


class ExcursionMachineTest(unittest.TestCase):
    def test_a_repetition_shaped_excursion_is_reconstructed(self):
        frames = (
            [_frame(175.0, 170.0)] * 2
            + [_frame(120.0, 130.0), _frame(100.0, 95.0), _frame(120.0, 130.0)]
            + [_frame(175.0, 170.0)] * 2
        )
        excursions = reconstruct_excursions(frames, "Left", "KNEE", 110.0, ("HIP",))
        self.assertEqual(1, len(excursions))
        self.assertAlmostEqual(100.0, excursions[0]["driverMinimum"], delta=0.5)
        self.assertAlmostEqual(95.0, excursions[0]["HIP"]["windowMinimum"], delta=0.5)
        self.assertAlmostEqual(130.0, excursions[0]["HIP"]["windowMaximum"], delta=0.5)

    def test_a_shallow_arc_is_not_repetition_shaped(self):
        # Arms at the attempt line but never reaches the rep line: the runtime reports an
        # attempt and counts nothing, so this tool must count nothing too.
        frames = (
            [_frame(175.0, 170.0)]
            + [_frame(130.0, 140.0)] * 3
            + [_frame(175.0, 170.0)]
        )
        self.assertEqual([], reconstruct_excursions(frames, "Left", "KNEE", 110.0, ("HIP",)))

    def test_an_excursion_still_armed_at_clip_end_is_discarded(self):
        # The engine discards an excursion the camera stopped watching; a clip that ends
        # mid-repetition is the same fact.
        frames = [_frame(175.0, 170.0)] + [_frame(100.0, 95.0)] * 4
        self.assertEqual([], reconstruct_excursions(frames, "Left", "KNEE", 110.0, ("HIP",)))

    def test_a_companion_below_the_observation_floor_abstains(self):
        # One credible companion frame is not a window. The excursion itself still counts —
        # abstention is about the companion clause, not the repetition.
        frames = (
            [_frame(175.0, 170.0)]
            + [_frame(100.0, 95.0)]
            + [
                {
                    "pts": {
                        key: value
                        for key, value in _frame(100.0, 95.0)["pts"].items()
                        if key != "Left Shoulder"
                    }
                }
            ]
            * MINIMUM_COMPANION_FRAMES
            + [_frame(175.0, 170.0)]
        )
        excursions = reconstruct_excursions(frames, "Left", "KNEE", 110.0, ("HIP",))
        self.assertEqual(1, len(excursions))
        self.assertNotIn("HIP", excursions[0])

    def test_the_band_constants_are_the_runtime_ones(self):
        self.assertEqual(150.0, REST_DEGREES)
        self.assertEqual(140.0, ATTEMPT_DEGREES)


class SweepTest(unittest.TestCase):
    def test_each_clause_shape_reads_its_own_extreme(self):
        minima = [100.0, 110.0, 120.0]
        maxima = [150.0, 160.0, 170.0]
        rows = {row["boundDegrees"]: row for row in sweep_shapes(minima, maxima)}
        # REACH AT_LEAST on the maximum: an excursion whose max never reached 165 fails.
        self.assertAlmostEqual(2 / 3, rows[165.0]["reachAtLeastOnMaximum"], places=4)
        # STAY AT_MOST on the maximum: an excursion whose max exceeded 165 fails.
        self.assertAlmostEqual(1 / 3, rows[165.0]["stayAtMostOnMaximum"], places=4)
        # REACH AT_MOST on the minimum: an excursion whose min stayed above 105 fails.
        self.assertAlmostEqual(2 / 3, rows[105.0]["reachAtMostOnMinimum"], places=4)
        # STAY AT_LEAST on the minimum: an excursion whose min fell under 105 fails.
        self.assertAlmostEqual(1 / 3, rows[105.0]["stayAtLeastOnMinimum"], places=4)

    def test_percentile_is_nearest_rank(self):
        values = list(range(1, 101))
        self.assertEqual(99, percentile(values, 0.99))
        self.assertEqual(1, percentile(values, 0.005))
        self.assertIsNone(percentile([], 0.5))


class HonestyTest(unittest.TestCase):
    def test_the_module_documents_what_it_cannot_answer(self):
        doc = quantiles.__doc__ or ""
        for phrase in ("cannot answer", "sparse keyframes", "MediaPipe", "impostor"):
            self.assertIn(phrase.lower(), doc.lower())

    def test_the_deadlifts_carry_the_squat_impostor_replay(self):
        by_name = {target.exercise: target for target in quantiles.TARGETS}
        self.assertIn("바벨 스쿼트", by_name["바벨 데드리프트"].impostors)
        self.assertIn("바벨 스쿼트", by_name["바벨 스티프 데드리프트"].impostors)


if __name__ == "__main__":
    unittest.main()
