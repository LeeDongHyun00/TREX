package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 앱 쪽 발화 대기열(spec §101 F2, 10-08 '세트 끝 교정 멘트가 잘린다') — 같은 키 대체·번갈아·세트 끝 정리·봉인·꼬리·들림 장부. */
class SpeechQueueTest {
    private fun count(text: String, key: String?, at: Long) = SpeechQueue.Item(SpeechQueue.Kind.COUNT, text, key = key, atMs = at)
    private fun coach(text: String, heard: String? = null, at: Long, setScoped: Boolean = false) =
        SpeechQueue.Item(SpeechQueue.Kind.COACH, text, heardKeys = listOfNotNull(heard), setScoped = setScoped, atMs = at)
    private fun boundary(text: String, at: Long) = SpeechQueue.Item(SpeechQueue.Kind.BOUNDARY, text, atMs = at)

    @Test fun sameKeyKeepsOnlyTheLatestCountAndCoachingAlternatesWithCounts() {
        val q = SpeechQueue()
        assertEquals("왼쪽 1", q.request(count("왼쪽 1", "왼쪽", 0), 0)!!.item.text)   // 비어 있으면 바로
        q.onStart(10)
        assertNull(q.request(count("왼쪽 2", "왼쪽", 500), 500))
        assertNull(q.request(count("왼쪽 3", "왼쪽", 900), 900))
        assertNull(q.request(count("오른쪽 1", "오른쪽", 950), 950))
        assertNull(q.request(coach("더 깊이 앉으세요.", "rj:shallow", 1000), 1000))
        assertEquals(listOf("왼쪽 3", "오른쪽 1"), q.pendingCounts.map { it.text })   // 같은 쪽은 최신만
        // 방금 COUNT 였으므로 보류 코칭이 먼저, 그 다음 카운트, 그 다음 카운트
        assertEquals("더 깊이 앉으세요.", q.onDone(1500)!!.item.text)
        assertEquals(SpeechQueue.Status.REQUESTED, q.status("rj:shallow")); q.onStart(1510); assertEquals(SpeechQueue.Status.STARTED, q.status("rj:shallow"))
        assertEquals("왼쪽 3", q.onDone(3000)!!.item.text)
        assertEquals(SpeechQueue.Status.DONE, q.status("rj:shallow"))
        assertNull(q.request(coach("가슴을 드세요.", "rf:x", 3100), 3100))
        // 방금 COACH 가 아니라 COUNT 였으니 보류 코칭이 먼저가 아니다 — COUNT 뒤엔 코칭
        assertEquals("가슴을 드세요.", q.onDone(3500)!!.item.text)
        assertEquals("오른쪽 1", q.onDone(5000)!!.item.text)
        assertNull(q.onDone(6000)); assertFalse(q.busy)
    }

    @Test fun staleMiddleCountsAreDroppedButTheNewestStays() {
        val q = SpeechQueue()
        q.request(coach("지적.", null, 0), 0); q.onStart(5)
        q.request(count("3", null, 100), 100); q.request(count("4", null, 200), 200); q.request(count("5", null, 4000), 4000)
        assertEquals("5", q.onDone(4100)!!.item.text)   // 3 s 넘게 묵은 3·4 는 버림
        assertNull(q.onDone(4500))
    }

    @Test fun boundaryFlushesEverythingWhenThereIsNoTailAndQueuesBehindTheTailOtherwise() {
        val q = SpeechQueue()
        q.request(coach("교정.", "rf:a", 0), 0); q.onStart(5)
        q.request(count("7", "n", 100), 100)
        val sub = q.request(boundary("쉬는 시간이에요", 200), 200)!!
        assertTrue(sub.flush); assertEquals("쉬는 시간이에요", sub.item.text); assertTrue(q.pendingCounts.isEmpty())
        // 꼬리가 있으면 뒤에 붙는다
        val t = SpeechQueue()
        t.request(coach("팔꿈치를 붙이세요.", "rf:b", 0), 0); t.onStart(5)
        t.handOff(100)
        assertTrue(t.tailActive(200))
        assertNull(t.request(boundary("권장 촬영 방향은 정면입니다.", 300), 300))
        val next = t.onDone(2800)!!
        assertFalse(next.flush); assertEquals("권장 촬영 방향은 정면입니다.", next.item.text)
    }

    @Test fun setEndKeepsOnlyTheNewestCountDropsSetScopedLinesAndSealsNewRequestsAfterGrace() {
        val q = SpeechQueue()
        q.request(count("왼쪽 9", "왼쪽", 0), 0); q.onStart(5)
        q.request(count("왼쪽 10", "왼쪽", 100), 100); q.request(count("오른쪽 10", "오른쪽", 150), 150)
        q.request(coach("이제 왼쪽 동작을 해 주세요.", null, 160, setScoped = true), 160)
        q.beginSetEnd(1000, dropCounts = false)
        assertEquals(listOf("오른쪽 10"), q.pendingCounts.map { it.text }); assertNull(q.heldCoach)
        // 봉인 전(t0+300 안)에 온 마지막 교정은 들어간다, 그 뒤는 버려진다
        assertNull(q.request(coach("좋아요, 교정됐어요.", "rf:c", 1200), 1200)); assertNotNull(q.heldCoach)
        assertNull(q.request(count("11", "n", 1400), 1400)); assertEquals(listOf("오른쪽 10"), q.pendingCounts.map { it.text })
        assertNull(q.request(coach("늦은 지적.", "rf:late", 1500), 1500)); assertEquals(SpeechQueue.Status.DROPPED, q.status("rf:late"))
        q.handOff(1600)
        assertEquals("좋아요, 교정됐어요.", q.onDone(1700)!!.item.text)   // 번갈아: 방금 COUNT 라 코칭 먼저
        assertEquals("오른쪽 10", q.onDone(4000)!!.item.text)
        assertNull(q.onDone(5000)); assertFalse(q.tailActive(5000))
        // 꼬리가 끝나면 봉인도 풀린다
        assertNotNull(q.request(count("1", "n", 5100), 5100))
    }

    @Test fun tailHasACapAndTheCallerStopsTheEngineWhenItExpires() {
        val q = SpeechQueue()
        q.request(coach("긴 문장.", null, 0), 0); q.onStart(5)
        q.request(coach("보류.", null, 100), 100)
        q.handOff(1000)
        assertTrue(q.tailActive(5000)); assertFalse(q.tailExpired(5000))
        assertTrue(q.tailExpired(11_000))
        q.stop(); assertFalse(q.busy)
    }

    @Test fun recoverableOnlyAfterTheRemarkStartedPlayingAndWithdrawsUnstartedOnes() {
        val q = SpeechQueue()
        q.request(count("3", "n", 0), 0); q.onStart(5)
        q.request(coach("팔꿈치를 붙이세요.", "rf:elbow", 100), 100)
        // 아직 시작 못 함 → 교정 불가, 지적도 거둔다
        assertFalse(q.recoverable("rf:elbow")); assertNull(q.heldCoach); assertEquals(SpeechQueue.Status.DROPPED, q.status("rf:elbow"))
        q.request(coach("가슴을 드세요.", "rf:chest", 200), 200)
        q.onDone(1000); q.onStart(1010)
        assertTrue(q.recoverable("rf:chest"))
        q.onDone(3000); assertTrue(q.recoverable("rf:chest"))
        assertFalse(q.recoverable("rf:unknown"))
    }

    @Test fun watchdogFinishesAnUtteranceWhoseCallbackNeverCame() {
        val q = SpeechQueue()
        q.request(coach("콜백이 안 오는 문장.", null, 0), 0)   // 제출만, onStart 없음
        q.request(count("1", "n", 100), 100)
        assertNull(q.watchdog(1000))
        val sub = q.watchdog(PrepSpeech.watchdogMs("콜백이 안 오는 문장.") + 1)
        assertEquals("1", sub!!.item.text)
    }

    @Test fun stopKeepingCurrentDropsWaitingItemsButNotThePlayingOne() {
        val q = SpeechQueue()
        q.request(coach("재생 중.", null, 0), 0); q.onStart(5)
        q.request(count("2", "n", 100), 100); q.request(coach("보류.", null, 200), 200)
        q.stopKeepingCurrent()
        assertNotNull(q.playing); assertTrue(q.pendingCounts.isEmpty()); assertNull(q.heldCoach)
    }
}
