package com.example.trex_kotlin

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.trex_kotlin.posture.SpeechCoach
import com.example.trex_kotlin.posture.SpeechPlaybackState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 Android 음성 경로를 검증한다. 스피커 음량·착용 중인 이어폰의 청취 여부는 별도 확인 대상. */
@RunWith(AndroidJUnit4::class)
class FloorFeedbackDeviceTest {
    @Test fun koreanSpeechInitializesAndCompletesOnDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var speech: SpeechCoach
        instrumentation.runOnMainSync { speech = SpeechCoach(instrumentation.targetContext) }
        try {
            val readyDeadline = SystemClock.elapsedRealtime() + 10000
            while (!speech.ready && speech.unavailableReason == null && SystemClock.elapsedRealtime() < readyDeadline) SystemClock.sleep(50)
            assertTrue(speech.unavailableReason ?: "한국어 음성 초기화 시간 초과", speech.ready)
            instrumentation.runOnMainSync { speech.speak("음성 안내 확인입니다. 변화가 지속되면 알려드릴게요") }
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (speech.playbackState != SpeechPlaybackState.COMPLETED && speech.playbackState != SpeechPlaybackState.ERROR && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertEquals(speech.unavailableReason ?: "음성 재생 완료 콜백 시간 초과", SpeechPlaybackState.COMPLETED, speech.playbackState)
        } finally {
            instrumentation.runOnMainSync { speech.shutdown() }
        }
    }
}
