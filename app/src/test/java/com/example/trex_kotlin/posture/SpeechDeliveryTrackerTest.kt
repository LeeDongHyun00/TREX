package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Test

/** TTS 플랫폼 콜백을 대체해 완료·중단·늦은 완료 순서를 검증한다. */
class SpeechDeliveryTrackerTest {
    @Test fun onlyCompletionDeliversExactlyOnce() {
        val tracker = SpeechDeliveryTracker()
        var delivered = 0
        tracker.register("cue", false) { delivered++ }
        assertEquals(0, delivered) // 등록/시작은 완료가 아니다.
        tracker.completed("cue")
        tracker.completed("cue")
        assertEquals(1, delivered)
    }

    @Test fun flushAndLateOnDoneDoNotDeliverInterruptedCue() {
        val tracker = SpeechDeliveryTracker()
        val delivered = ArrayList<String>()
        tracker.register("old", false) { delivered += "old" }
        tracker.register("new", true) { delivered += "new" }
        tracker.completed("old")
        tracker.completed("new")
        assertEquals(listOf("new"), delivered)
    }

    @Test fun errorStopAndShutdownDiscardPendingDelivery() {
        val tracker = SpeechDeliveryTracker()
        var delivered = 0
        tracker.register("error", false) { delivered++ }
        tracker.discard("error")
        tracker.completed("error")
        tracker.register("stop", false) { delivered++ }
        tracker.clear()
        tracker.completed("stop")
        assertEquals(0, delivered)
    }

    @Test fun queuedCuesCompleteIndependentlyAndCallbackMaySubmitAnotherCue() {
        val tracker = SpeechDeliveryTracker()
        val delivered = ArrayList<String>()
        tracker.register("one", false) {
            delivered += "one"
            tracker.register("three", false) { delivered += "three" }
        }
        tracker.register("two", false) { delivered += "two" }
        tracker.completed("one")
        tracker.completed("two")
        tracker.completed("three")
        assertEquals(listOf("one", "two", "three"), delivered)
    }
}
