package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** 런지 쪽별 카운트(spec §63, 사용자 결정 2026-09-26) — 차감이 아니라 쪽마다 따로 세고, 한쪽을 다 채우면 반대쪽을 안내한다. */
class LungeSidesTest {
    private val L = StepSide.LEFT
    private val R = StepSide.RIGHT

    private fun run(c: SideStepCounter, vararg steps: StepSide?, coach: Boolean = false) = steps.map { c.offer(0L, it, blocked = false).of(coach) }

    @Test
    fun blockOrderCountsEachSideAndSwitchesOnceWhenOneSideIsDone() {
        // 사용자 프로토콜처럼 한쪽씩 몰아서(왼 2 → 오 2), 목표 2
        val c = SideStepCounter(2)
        val ev = run(c, L, L, R, R)
        assertEquals(listOf(0, 0, 1, 2), ev.map { it.pairs })
        assertEquals(listOf(1, 0, 1, 0), ev.map { it.remaining })
        assertEquals("왼쪽을 다 채운 순간 한 번만", listOf(null, R, null, null), ev.map { it.switchTo })
        assertTrue(c.done(false))
    }

    @Test
    fun extraStepsOnAFinishedSideAreNotCountedAndDoNotRepeatTheSwitch() {
        val c = SideStepCounter(2)
        val ev = run(c, L, L, L, L)
        assertEquals(listOf(true, true, false, false), ev.map { it.counted })
        assertEquals(listOf(false, false, true, true), ev.map { it.extraOnDone })
        // 반대쪽 안내는 채운 순간 한 번 — 더 디딘 걸음은 extraOnDone 만(다시 말할지는 화면이 간격으로 정한다)
        assertEquals(listOf(null, R, null, null), ev.map { it.switchTo })
        assertEquals("한쪽만으로는 쌍이 오르지 않는다", 0, c.track.pairs)
        assertEquals(2, c.track.extra)
    }

    @Test
    fun aGatedStepOnAFinishedSideIsAnExtraNotABlock() {
        // COACH: 왼쪽을 다 채운 뒤 왼쪽으로 얕게 한 걸음 — 어차피 세지 않을 걸음이라 '자세로 뺀 걸음' 이 아니라 '더 디딘 걸음'
        val c = SideStepCounter(1)
        c.offer(0L, L, blocked = false)
        val e = c.offer(0L, L, blocked = true).coach
        assertTrue(e.extraOnDone); assertFalse(e.blocked)
        assertEquals(0, c.coach.blocked); assertEquals(1, c.coach.extra)
    }

    @Test
    fun alternatingNeedsNoSpecialHandling() {
        val c = SideStepCounter(3)
        val ev = run(c, L, R, L, R, L, R)
        assertEquals(listOf(0, 1, 1, 2, 2, 3), ev.map { it.pairs })
        assertEquals("왼쪽을 다 채운 순간(오른쪽은 1 남음)", R, ev[4].switchTo)
        assertNull(ev[5].switchTo)
    }

    @Test
    fun unknownStepsFollowTheBlockWhileDoingOneSideAtATime() {
        // 안내한 방식(한쪽씩 몰아서): 왼쪽 블록 중 모르는 걸음은 왼쪽 — '적은 쪽' 이면 오른쪽에 들어가 오른쪽 블록이 한 걸음 일찍 끝났다
        val c = SideStepCounter(3)
        val ev = run(c, L, L, null, R, R, R)
        assertEquals(3, c.track.left); assertEquals(3, c.track.right); assertEquals(1, c.track.unknown)
        assertEquals(L, ev[2].side); assertFalse(ev[2].known)
        assertEquals("왼쪽을 채운 모르는 걸음에서 안내", R, ev[2].switchTo)
        assertEquals(listOf(true, true, true, true, true, true), ev.map { it.counted })
        assertEquals(3, c.track.pairs)
    }

    @Test
    fun unknownStepsAlternateWhenTheUserAlternates() {
        val c = SideStepCounter(null)
        val ev = run(c, L, R, null, null, L)
        assertEquals(listOf(L, R, L, R, L), ev.map { it.side })
        // 채울 쪽이 이미 목표면 반대쪽
        val t = SideStepCounter(2)
        val et = run(t, L, L, null)
        assertEquals(R, et[2].side); assertTrue(et[2].counted)
    }

    @Test
    fun allUnknownFillsTheShorterSide() {
        // 확신 걸음이 없으면 적은 쪽 = 번갈아 채운다 = 종전 두 걸음 = 1회
        val u = SideStepCounter(null)
        assertEquals(listOf(0, 1, 1, 2), run(u, null, null, null, null).map { it.pairs })
        assertEquals(4, u.track.unknown)
    }

    @Test
    fun blockedStepsLeaveOnlyTheCoachPool() {
        val c = SideStepCounter(2)
        val a = c.offer(0L, L, blocked = true)
        assertTrue(a.track.counted); assertFalse(a.coach.counted); assertTrue(a.coach.blocked)
        c.offer(0L, L, blocked = false); c.offer(0L, R, blocked = false); c.offer(0L, R, blocked = false)
        assertEquals(2, c.track.pairs)
        assertEquals("COACH 는 왼쪽이 하나 모자라다", 1, c.coach.pairs)
        assertEquals(1, c.coach.blocked)
        assertTrue(c.done(false)); assertFalse(c.done(true))
        val t = c.tallies()
        assertEquals("왼 ✓ · 오 ✓", t.headline(false))
        assertEquals("왼 1 · 오 ✓", t.headline(true))
        assertEquals(L, t.next(true))
        assertEquals("왼 1 · 오 2걸음 · 자세로 뺀 걸음 1", t.reportLine(true))
    }
}
