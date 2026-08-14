import json
import math
import tempfile
import unittest
from pathlib import Path

from measure_lunge_divergence_separability import (
    EXERCISES,
    IMPOSTOR,
    LUNGES,
    build_artifact,
    clip_measurements,
    collect,
    included_angle,
    is_shallow,
    percentile,
    subject_of,
    sweep,
    with_measurement_error,
)


def right_angle_frame(left_knee_degrees: float, right_knee_degrees: float) -> dict:
    """A frame whose two knee chains sit at the requested included angles.

    The hip is above the knee and the ankle swings about it, so the divergence is exactly the
    difference between the two requested angles.
    """
    points = {}
    for side, degrees, offset in (
        ("Left", left_knee_degrees, -10.0),
        ("Right", right_knee_degrees, 10.0),
    ):
        radians = math.radians(degrees)
        points[f"{side} Knee"] = {"x": offset, "y": 0.0, "z": 0.0}
        points[f"{side} Hip"] = {"x": offset, "y": 40.0, "z": 0.0}
        points[f"{side} Ankle"] = {
            "x": offset + 40.0 * math.sin(radians),
            "y": 40.0 * math.cos(radians),
            "z": 0.0,
        }
        points[f"{side} Shoulder"] = {"x": offset, "y": 90.0, "z": 0.0}
    return {"pts": points}


class IncludedAngleTest(unittest.TestCase):
    def test_matches_constructed_angles(self):
        for target in (30.0, 90.0, 175.0):
            radians = math.radians(target)
            angle = included_angle(
                (0.0, 0.0, 0.0),
                (0.0, 1.0, 0.0),
                (math.sin(radians), math.cos(radians), 0.0),
            )
            self.assertAlmostEqual(target, angle, places=6)

    def test_degenerate_segment_abstains(self):
        self.assertIsNone(included_angle((0.0, 0.0, 0.0), (0.0, 0.0, 0.0), (0.0, 1.0, 0.0)))


class ClipMeasurementTest(unittest.TestCase):
    def test_window_maximum_is_the_largest_frame_divergence(self):
        frames = [
            right_angle_frame(170.0, 170.0),
            right_angle_frame(120.0, 170.0),
            right_angle_frame(160.0, 170.0),
        ]
        measured = clip_measurements(frames)
        self.assertAlmostEqual(50.0, measured["kneeDivergenceMax"], places=3)

    def test_drop_one_reports_the_runner_up_frame(self):
        # The middle frame is the corrupted one this statistic exists to survive.
        frames = [
            right_angle_frame(170.0, 170.0),
            right_angle_frame(20.0, 170.0),
            right_angle_frame(140.0, 170.0),
        ]
        measured = clip_measurements(frames)
        self.assertAlmostEqual(150.0, measured["kneeDivergenceMax"], places=3)
        self.assertAlmostEqual(30.0, measured["kneeDivergenceSecond"], places=3)

    def test_unmeasurable_clip_abstains(self):
        self.assertIsNone(clip_measurements([{"pts": {"Nose": {"x": 0, "y": 0, "z": 0}}}]))

    def test_frame_differences_are_kept_for_the_error_model(self):
        frames = [right_angle_frame(170.0, 170.0), right_angle_frame(130.0, 170.0)]
        measured = clip_measurements(frames)
        self.assertEqual(2, len(measured["kneeFrameDifferences"]))


class MetadataTest(unittest.TestCase):
    def test_shallow_is_the_failed_ninety_degree_condition(self):
        self.assertTrue(is_shallow({"conditions": [{"condition": "무릎 각도 90도", "value": False}]}))
        self.assertFalse(is_shallow({"conditions": [{"condition": "무릎 각도 90도", "value": True}]}))
        self.assertFalse(is_shallow({"conditions": [{"condition": "고개 정면", "value": False}]}))

    def test_subject_is_the_global_z_code(self):
        metadata = {"frames": [{"view1": {"img_key": "Day05_x/1/A/697-3-5-34-Z39_A/f-0001.jpg"}}]}
        self.assertEqual("Z39", subject_of(metadata))
        self.assertIsNone(subject_of({"frames": [{"view1": {"img_key": "no-code.jpg"}}]}))


class SweepTest(unittest.TestCase):
    def test_a_bound_rejects_below_itself_on_both_populations(self):
        rows = sweep([10.0, 30.0], [2.0, 4.0], [5.0])
        self.assertAlmostEqual(0.0, rows[0]["lungeFalseRejectionRate"])
        self.assertAlmostEqual(1.0, rows[0]["impostorCaughtRate"])

    def test_raising_the_bound_costs_the_lunge_and_catches_the_squat_together(self):
        rows = sweep([10.0, 30.0], [2.0, 4.0], [20.0])
        self.assertAlmostEqual(0.5, rows[0]["lungeFalseRejectionRate"])
        self.assertAlmostEqual(1.0, rows[0]["impostorCaughtRate"])

    def test_percentile_is_nearest_rank(self):
        self.assertEqual(1.0, percentile([1.0, 2.0, 3.0, 4.0], 0.05))
        self.assertEqual(4.0, percentile([1.0, 2.0, 3.0, 4.0], 1.0))
        self.assertIsNone(percentile([], 0.5))


class MeasurementErrorTest(unittest.TestCase):
    def test_zero_error_reproduces_the_window_maximum(self):
        clips = [{"kneeFrameDifferences": [3.0, 40.0, 7.0]}]
        values = with_measurement_error(clips, "kneeFrameDifferences", 0.0, 1, 1)
        self.assertAlmostEqual(40.0, values[0], places=6)

    def test_error_inflates_a_near_zero_divergence(self):
        # The point of the whole exercise: an impostor whose two sides genuinely agree reads the
        # maximum of many draws of the error, which grows with the window instead of averaging.
        clips = [{"kneeFrameDifferences": [0.0] * 16}]
        quiet = with_measurement_error(clips, "kneeFrameDifferences", 0.0, 1, 7)
        noisy = with_measurement_error(clips, "kneeFrameDifferences", 8.0, 1, 7)
        self.assertAlmostEqual(0.0, quiet[0], places=6)
        self.assertGreater(noisy[0], 10.0)

    def test_seeded_and_reproducible(self):
        clips = [{"kneeFrameDifferences": [5.0] * 16}]
        first = with_measurement_error(clips, "kneeFrameDifferences", 8.0, 3, 42)
        second = with_measurement_error(clips, "kneeFrameDifferences", 8.0, 3, 42)
        self.assertEqual(first, second)


class ArtifactTest(unittest.TestCase):
    def synthetic_clips(self) -> list:
        clips = []
        for index in range(20):
            for exercise in LUNGES:
                clips.append(
                    {
                        "exercise": exercise,
                        "subject": f"Z{index}",
                        "shallow": index % 2 == 0,
                        "clip": f"{exercise}/{index}",
                        "hipDivergenceMax": 50.0,
                        "kneeDivergenceMax": 55.0,
                        "hipDivergenceSecond": 45.0,
                        "kneeDivergenceSecond": 50.0,
                        "ankleSeparationMax": 1.4,
                        "ankleSeparationSecond": 1.3,
                        "hipFrameDifferences": [50.0, 10.0],
                        "kneeFrameDifferences": [55.0, 10.0],
                    }
                )
            clips.append(
                {
                    "exercise": IMPOSTOR,
                    "subject": f"Z{index}",
                    "shallow": False,
                    "clip": f"{IMPOSTOR}/{index}",
                    "hipDivergenceMax": 5.0,
                    "kneeDivergenceMax": 4.0,
                    "hipDivergenceSecond": 4.0,
                    "kneeDivergenceSecond": 3.0,
                    "ankleSeparationMax": 0.9,
                    "ankleSeparationSecond": 0.85,
                    "hipFrameDifferences": [5.0, 1.0],
                    "kneeFrameDifferences": [4.0, 1.0],
                }
            )
        return clips

    def test_a_clean_separation_is_reported_as_one(self):
        artifact = build_artifact(self.synthetic_clips())
        best = artifact["chains"]["KNEE"]["bestBoundAtOnePercentFalseRejection"]
        self.assertIsNotNone(best)
        self.assertEqual(1.0, best["impostorCaughtRate"])
        self.assertEqual(0.0, best["lungeFalseRejectionRate"])

    def test_error_scenarios_are_present_and_ordered_by_damage(self):
        artifact = build_artifact(self.synthetic_clips())
        scenarios = artifact["underMeasurementError"]["KNEE"]
        self.assertEqual(
            {"perfect", "optimistic_3deg", "walking_gait"},
            set(scenarios),
        )
        self.assertLessEqual(
            scenarios["perfect"]["impostor"]["median"],
            scenarios["walking_gait"]["impostor"]["median"],
        )

    def test_artifact_records_its_own_limitations(self):
        artifact = build_artifact(self.synthetic_clips())
        self.assertEqual("LUNGE_DIVERGENCE_SEPARABILITY", artifact["artifactKind"])
        self.assertEqual("LEAVE_ONE_GLOBAL_Z_SUBJECT_OUT", artifact["generalisationUnit"])
        self.assertTrue(artifact["limitations"])


class CollectTest(unittest.TestCase):
    def test_reads_a_pair_of_label_files(self):
        with tempfile.TemporaryDirectory() as root:
            base = Path(root) / "Day01"
            base.mkdir()
            metadata = {
                "type_info": {
                    "exercise": LUNGES[0],
                    "conditions": [{"condition": "앞다리 무릎 각도 90도", "value": False}],
                },
                "frames": [{"view1": {"img_key": "Day01/1/A/1-1-1-1-Z7_A/f-0001.jpg"}}],
            }
            (base / "clip.json").write_text(json.dumps(metadata), encoding="utf-8")
            coordinates = {"frames": [right_angle_frame(120.0, 170.0)]}
            (base / "clip-3d.json").write_text(json.dumps(coordinates), encoding="utf-8")

            clips = collect(root)

        self.assertEqual(1, len(clips))
        self.assertEqual(LUNGES[0], clips[0]["exercise"])
        self.assertEqual("Z7", clips[0]["subject"])
        self.assertTrue(clips[0]["shallow"])
        self.assertAlmostEqual(50.0, clips[0]["kneeDivergenceMax"], places=3)

    def test_ignores_exercises_outside_the_question(self):
        with tempfile.TemporaryDirectory() as root:
            base = Path(root) / "Day01"
            base.mkdir()
            (base / "clip.json").write_text(
                json.dumps({"type_info": {"exercise": "푸시업"}, "frames": []}), encoding="utf-8"
            )
            (base / "clip-3d.json").write_text(
                json.dumps({"frames": [right_angle_frame(120.0, 170.0)]}), encoding="utf-8"
            )
            self.assertEqual([], collect(root))

    def test_the_four_exercises_are_the_three_lunges_and_the_squat(self):
        self.assertEqual(set(EXERCISES), set(LUNGES) | {IMPOSTOR})
        self.assertEqual(3, len(LUNGES))


if __name__ == "__main__":
    unittest.main()
