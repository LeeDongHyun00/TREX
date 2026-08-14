import json
import tempfile
import unittest
from pathlib import Path

from summarize_bridge_frame_error import build_artifact, main, percentile, summarise


def row(chain="SHOULDER", exercise="바벨 컬", day="Day04", subject="Z1", mediapipe=60.0, aihub=50.0):
    return {
        "chain": chain,
        "exercise": exercise,
        "day": day,
        "subject": subject,
        "mediapipe": mediapipe,
        "aihub": aihub,
    }


class PercentileTest(unittest.TestCase):
    def test_nearest_rank(self):
        self.assertEqual(1.0, percentile([4.0, 1.0, 3.0, 2.0], 0.05))
        self.assertEqual(4.0, percentile([4.0, 1.0, 3.0, 2.0], 1.0))
        self.assertIsNone(percentile([], 0.5))


class SummariseTest(unittest.TestCase):
    def test_signed_bias_is_mediapipe_minus_label(self):
        # MediaPipe reading straighter than the label must come out positive — the direction
        # every clip-level bias in the bridge card points.
        card = summarise([row(mediapipe=110.0, aihub=100.0), row(mediapipe=95.0, aihub=100.0)])
        self.assertEqual(2, card["frames"])
        self.assertAlmostEqual(2.5, card["signedBias"]["median"])
        self.assertAlmostEqual(7.5, card["absoluteError"]["median"])

    def test_counts_subjects_and_days_not_just_frames(self):
        card = summarise(
            [row(subject="Z1", day="Day04"), row(subject="Z2", day="Day08"), row(subject="Z1", day="Day04")]
        )
        self.assertEqual(2, card["subjects"])
        self.assertEqual(["Day04", "Day08"], card["captureDays"])


class ArtifactTest(unittest.TestCase):
    def test_groups_by_chain_and_exercise(self):
        rows = [
            row(chain="SHOULDER", exercise="바벨 컬"),
            row(chain="SHOULDER", exercise="덤벨 컬"),
            row(chain="HIP", exercise="바벨 런지"),
        ]
        artifact = build_artifact(rows, source="test.json")
        self.assertEqual({"SHOULDER", "HIP"}, set(artifact["perChain"]))
        self.assertEqual({"바벨 컬", "덤벨 컬", "바벨 런지"}, set(artifact["perExercise"]))
        self.assertEqual(2, artifact["perChain"]["SHOULDER"]["frames"])

    def test_artifact_states_its_own_limits(self):
        artifact = build_artifact([row()], source="test.json")
        self.assertEqual("BRIDGE_PER_FRAME_ERROR_CARD", artifact["artifactKind"])
        self.assertEqual("MEDIAPIPE_MINUS_AIHUB_3D_LABEL", artifact["signedConvention"])
        self.assertTrue(artifact["limitations"])


class MainTest(unittest.TestCase):
    def test_end_to_end(self):
        with tempfile.TemporaryDirectory() as root:
            src = Path(root) / "frames.json"
            out = Path(root) / "card.json"
            src.write_text(
                json.dumps({"rows": [row(), row(mediapipe=70.0)]}, ensure_ascii=False),
                encoding="utf-8",
            )
            self.assertEqual(0, main([str(src), "--out", str(out)]))
            card = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(2, card["perChain"]["SHOULDER"]["frames"])

    def test_empty_input_is_an_error(self):
        with tempfile.TemporaryDirectory() as root:
            src = Path(root) / "frames.json"
            src.write_text(json.dumps({"rows": []}), encoding="utf-8")
            with self.assertRaises(SystemExit):
                main([str(src), "--out", str(Path(root) / "card.json")])


if __name__ == "__main__":
    unittest.main()
