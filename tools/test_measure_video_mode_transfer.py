import unittest

from measure_video_mode_transfer import (
    ASSUMED_FRAME_INTERVAL_MS,
    POISON,
    QUEUE_DEPTH,
)
import measure_video_mode_transfer as video_mode


class MethodContractTest(unittest.TestCase):
    """The claims this tool's artifact makes are the ones its method actually supports."""

    def test_the_frame_clock_is_stated_because_smoothing_depends_on_it(self):
        # VIDEO mode smooths over time, so the interval is not an incidental detail: it is a
        # parameter of the thing being measured, and the artifact says so.
        self.assertEqual(33, ASSUMED_FRAME_INTERVAL_MS)

    def test_the_module_documents_what_it_cannot_answer(self):
        doc = video_mode.__doc__ or ""
        for phrase in ("studio", "thermal"):
            self.assertIn(
                phrase,
                doc,
                f"the module must keep saying it does not answer {phrase}",
            )

    def test_a_landmarker_never_outlives_its_clip(self):
        # Reusing one across clips would carry a tracked person from the previous clip into the
        # next, which would be an artefact of this harness rather than of the runtime. The
        # worker creates and closes one inside the per-clip loop.
        import inspect

        source = inspect.getsource(video_mode._worker)
        create = source.index("create_from_options")
        loop = source.index("while True:")
        self.assertGreater(
            create,
            loop,
            "the landmarker must be constructed inside the per-clip loop, not before it",
        )
        self.assertIn("landmarker.close()", source)
        self.assertIn("finally:", source)

    def test_the_statistic_is_taken_only_over_labelled_frames(self):
        # Unlabelled frames exist to give the tracker temporal context. Letting them into the
        # statistic would change both the estimator and the sample, and the comparison against
        # the IMAGE run would no longer isolate the inference mode.
        import inspect

        source = inspect.getsource(video_mode._worker)
        self.assertIn("if not wanted:", source)
        self.assertIn("continue", source)

    def test_video_running_mode_is_what_is_exercised(self):
        import inspect

        source = inspect.getsource(video_mode._worker)
        self.assertIn("RunningMode.VIDEO", source)
        self.assertIn("detect_for_video", source)
        self.assertNotIn("RunningMode.IMAGE", source)


class QueueContractTest(unittest.TestCase):
    def test_whole_clips_are_the_unit_of_work(self):
        # A frame-level queue would let a clip's frames reach different workers, and VIDEO mode
        # would then see interleaved sequences. The queue is shallow for the same reason: each
        # item is a whole clip's worth of JPEG bytes.
        self.assertLessEqual(QUEUE_DEPTH, 32)
        self.assertIsNone(POISON)


if __name__ == "__main__":
    unittest.main()
