package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 세션 스피커의 공유 정책(§108c `SpeechCoach`) — Android TextToSpeech 와 iPhone AVSpeechSynthesizer 가 같은 정책 위에 선다.
 * 가짜 엔진으로 준비 전 대기·코칭 보류·거절된 제출·음소거·초기화 실패를 확인한다(대기열 규칙 자체는 SpeechQueueTest).
 */
class SpeechCoachTest {

    private class FakeEngine(private val result: Int? = SpeechEngine.SUCCESS) : SpeechEngine {
        lateinit var events: SpeechEngine.Events
        val spoken = ArrayList<Pair<String, Boolean>>()
        val ids = ArrayList<String>()
        var stops = 0
        var focus = 0
        var abandons = 0
        val traces = ArrayList<String>()
        private var next = 0
        override fun start(events: SpeechEngine.Events) { this.events = events }
        override fun speak(text: String, flush: Boolean, id: String): Int? { spoken += text to flush; ids += id; return result }
        override fun stop() { stops++ }
        override fun shutdown() {}
        override fun requestFocus() { focus++ }
        override fun abandonFocus() { abandons++ }
        override fun nextUtteranceId(): String = "u${next++}"
        override fun trace(atMs: Long, kind: String, text: String, utteranceId: String?, muted: Boolean, ready: Boolean) { traces += kind }
    }

    @Test
    fun requestsBeforeInitAreSpokenInOrderAfterwards() {
        val e = FakeEngine()
        val coach = SpeechCoach(e)
        assertFalse(coach.ready)
        coach.speak("첫 안내")
        coach.speak("둘째 안내", flush = false)
        assertTrue("준비 전에는 엔진에 내지 않는다", e.spoken.isEmpty())
        e.events.onInit(true, null, null)
        assertTrue(coach.ready)
        assertEquals("첫 안내", e.spoken.first().first)
        assertTrue("준비 전 요청의 첫 것은 경계 안내(flush)", e.spoken.first().second)
        // 둘째는 대기열 — 첫 문장이 끝나면 이어서
        e.events.onStart(e.ids[0])
        e.events.onFinished(e.ids[0], "tts_done", "")
        assertEquals(listOf("첫 안내", "둘째 안내"), e.spoken.map { it.first })
    }

    @Test
    fun coachingLineIsHeldWhileSpeakingAndFollowsWhenDone() {
        val e = FakeEngine()
        val coach = SpeechCoach(e)
        e.events.onInit(true, null, null)
        coach.speak("시작하세요.")
        e.events.onStart(e.ids[0])
        coach.speakLatest("무릎을 바깥으로.", heardKey = "rf:knee")
        assertEquals("말하는 중의 코칭은 끊지 않고 보류한다", 1, e.spoken.size)
        assertTrue(coach.isSpeaking)
        e.events.onFinished(e.ids[0], "tts_done", "")
        assertEquals("무릎을 바깥으로.", e.spoken.last().first)
        e.events.onStart(e.ids[1])
        assertTrue("재생을 시작한 지적은 교정 문장을 낼 수 있다", coach.recoverable("rf:knee"))
        e.events.onFinished(e.ids[1], "tts_done", "")
        assertFalse(coach.isSpeaking)
        assertTrue("마지막 발화가 끝나면 포커스를 놓는다", e.abandons > 0)
    }

    @Test
    fun rejectedSubmitDoesNotLeaveTheQueueBusy() {
        val e = FakeEngine(result = -1)
        val coach = SpeechCoach(e)
        e.events.onInit(true, null, null)
        coach.speak("하나")
        assertEquals(1, e.spoken.size)
        assertFalse("엔진이 받지 않은 문장은 끝난 것으로 친다", coach.isSpeaking)
        assertTrue(e.traces.contains("tts_rejected"))
    }

    @Test
    fun mutedAndStoppedSpeakNothing() {
        val e = FakeEngine()
        val coach = SpeechCoach(e)
        e.events.onInit(true, null, null)
        coach.muted = true
        coach.speak("하나"); coach.speakLatest("둘")
        assertTrue(e.spoken.isEmpty())
        coach.muted = false
        coach.speak("셋")
        coach.speakLatest("넷")
        coach.stop()
        assertEquals(1, e.stops)
        assertFalse(coach.isSpeaking)
        assertFalse("정지 뒤 보류분은 버린다", e.spoken.any { it.first == "넷" })
    }

    @Test
    fun failedInitExplainsAndDropsRequests() {
        val e = FakeEngine()
        val coach = SpeechCoach(e)
        assertNull("초기화 중에는 이유를 말하지 않는다", coach.unavailableReason)
        e.events.onInit(false, "한국어 음성 데이터 없음", "음성 안내를 쓸 수 없어요")
        assertEquals("음성 안내를 쓸 수 없어요", coach.unavailableReason)
        assertEquals("한국어 음성 데이터 없음", coach.lastError)
        coach.speak("하나")
        assertTrue("못 쓰는 엔진에는 담아 두지도 않는다", e.spoken.isEmpty())
        assertFalse(coach.isSpeaking)
    }
}
