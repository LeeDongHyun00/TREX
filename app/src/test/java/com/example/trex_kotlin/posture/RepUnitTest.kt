package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 표시 횟수 단위 (사용자 결정 2026-09-24: 런지류는 "왼쪽과 오른쪽을 한 번씩 = 1회") — 카운터 사이클 → 표시 횟수. */
class RepUnitTest {

    private fun feed(acc: RepUnitAccumulator, cycles: List<Pair<Long, Boolean?>>): List<RepUnitAccumulator.UnitRep?> =
        cycles.map { (t, v) -> acc.offer(t, v) }

    @Test
    fun unitsDeclareCyclesPerRepAndLogKeys() {
        assertEquals(1, RepUnit.CYCLE.cyclesPerRep)
        assertEquals(2, RepUnit.SIDE_PAIR.cyclesPerRep)
        assertEquals("cycle", RepUnit.CYCLE.key)
        assertEquals("side_pair", RepUnit.SIDE_PAIR.key)
    }

    @Test
    fun cycleUnitIsTheIdentity() {
        // 사이클 하나 = 1회 — 시각·판정이 그대로, 기다리는 반쪽은 생기지 않는다 (런지류를 뺀 모든 종목의 지금 동작)
        val acc = RepUnitAccumulator(RepUnit.CYCLE)
        val cycles = listOf(1000L to true, 2500L to false, 4100L to null, 5600L to true)
        val out = feed(acc, cycles)
        assertEquals(cycles.map { (t, v) -> RepUnitAccumulator.UnitRep(t, v) }, out)
        assertEquals(4, acc.completed)
        assertEquals(1, acc.invalid)
        assertFalse(acc.pendingHalf)
        assertNull(acc.pendingHalfAtMs)
        assertEquals(listOf(1000L, 2500L, 4100L, 5600L), acc.repTimesMs)
    }

    @Test
    fun sidePairCountsOnlyWhenBothSidesAreDone() {
        val acc = RepUnitAccumulator(RepUnit.SIDE_PAIR)
        // 한쪽(첫 걸음): 수는 오르지 않고 '반대쪽 차례'
        assertNull(acc.offer(1000L, true))
        assertEquals(0, acc.completed)
        assertTrue(acc.pendingHalf)
        assertEquals(1000L, acc.pendingHalfAtMs)
        // 반대쪽: 1회 완료 — 시각은 짝을 마친 두 번째 걸음
        assertEquals(RepUnitAccumulator.UnitRep(2400L, true), acc.offer(2400L, true))
        assertEquals(1, acc.completed)
        assertFalse(acc.pendingHalf)
        assertNull(acc.pendingHalfAtMs)
    }

    @Test
    fun oddAndEvenSequences() {
        // 짝수: 20걸음 = 10회 (목표 10회 = 왼 10 + 오른 10), 반쪽 없음
        val even = RepUnitAccumulator(RepUnit.SIDE_PAIR)
        val evenOut = feed(even, (1..20).map { it * 1000L to true })
        assertEquals(10, even.completed)
        assertFalse(even.pendingHalf)
        // 1회는 짝을 마친 걸음에서만 — 홀수 번째 걸음은 null
        evenOut.forEachIndexed { i, r -> if (i % 2 == 0) assertNull("걸음 ${i + 1}", r) else assertEquals((i + 1) * 1000L, r!!.tMs) }
        assertEquals((1..10).map { it * 2000L }, even.repTimesMs)
        // 홀수: 19걸음 = 9회 + 반쪽 하나(세지 않는다)
        val odd = RepUnitAccumulator(RepUnit.SIDE_PAIR)
        feed(odd, (1..19).map { it * 1000L to true })
        assertEquals(9, odd.completed)
        assertTrue(odd.pendingHalf)
        assertEquals(19_000L, odd.pendingHalfAtMs)
        // 한 걸음만: 0회 + 반쪽
        val one = RepUnitAccumulator(RepUnit.SIDE_PAIR)
        one.offer(500L, false)
        assertEquals(0, one.completed)
        assertEquals(0, one.invalid)   // 반쪽의 미달은 아직 어느 회에도 들어가지 않았다
        assertTrue(one.pendingHalf)
        // 없음: 0회, 반쪽 없음
        val none = RepUnitAccumulator(RepUnit.SIDE_PAIR)
        assertEquals(0, none.completed)
        assertFalse(none.pendingHalf)
        assertTrue(none.repTimesMs.isEmpty())
    }

    @Test
    fun pairValidityRule() {
        // 어느 한쪽이라도 미달 → 미달, 아니고 어느 한쪽이라도 미판정 → 미판정, 둘 다 충족 → 충족 (순서 무관)
        val cases = listOf(
            Triple(true, true, true),
            Triple(true, false, false), Triple(false, true, false), Triple(false, false, false),
            Triple(false, null, false), Triple(null, false, false),
            Triple(true, null, null), Triple(null, true, null), Triple(null, null, null),
        )
        for ((a, b, expected) in cases) {
            assertEquals("combine($a, $b)", expected, RepUnitAccumulator.combine(listOf(a, b)))
            val acc = RepUnitAccumulator(RepUnit.SIDE_PAIR)
            acc.offer(1000L, a)
            assertEquals("pair($a, $b)", expected, acc.offer(2000L, b)!!.valid)
            assertEquals(if (expected == false) 1 else 0, acc.invalid)
        }
        // 한 사이클(CYCLE)의 판정은 그대로
        assertEquals(null, RepUnitAccumulator.combine(listOf(null)))
        assertEquals(true, RepUnitAccumulator.combine(listOf(true)))
    }

    @Test
    fun mixedSetCountsInvalidPairsAndNeverPromotesUnjudged() {
        // 바벨 런지(검증 ROM): 걸음 [충족, 미달, 충족, 충족, 미판정(가림 등 극값 없음), 충족] → 회 [미달, 충족, 미판정]
        val acc = RepUnitAccumulator(RepUnit.SIDE_PAIR)
        feed(acc, listOf(1000L to true, 2000L to false, 3000L to true, 4000L to true, 5000L to null, 6000L to true))
        assertEquals(listOf(false, true, null), acc.reps.map { it.valid })
        assertEquals(3, acc.completed)
        assertEquals(1, acc.invalid)   // 미달 1회 — 미판정은 미달로도 충족으로도 세지 않는다
        assertEquals(listOf(2000L, 4000L, 6000L), acc.repTimesMs)
        // 템포는 표시 단위(짝) 간격 — 걸음 간격(1 s)이 아니라 2 s
        assertEquals(2000L, RepMetrics.medianPeriodMs(acc.repTimesMs))
    }

    @Test
    fun counterCycleResetKeepsTheFinishedHalf() {
        // 일시정지·카메라 전환은 카운터의 진행 사이클만 버린다. 이미 발표된 한 걸음(반쪽)은 실제로 끝난 동작이라 남긴다 —
        // 사용자는 한쪽을 마치고 멈췄다가 반대쪽을 이어 할 수 있다.
        val acc = RepUnitAccumulator(RepUnit.SIDE_PAIR)
        acc.offer(1000L, true)
        acc.onCounterCycleReset()
        assertTrue(acc.pendingHalf)
        assertEquals(1000L, acc.pendingHalfAtMs)
        assertEquals(0, acc.completed)
        // 멈춘 뒤 반대쪽 → 1회
        assertEquals(RepUnitAccumulator.UnitRep(30_000L, true), acc.offer(30_000L, true))
        assertEquals(1, acc.completed)
        // 완료한 회도 그대로
        acc.onCounterCycleReset()
        assertEquals(1, acc.completed)
        assertFalse(acc.pendingHalf)
    }

    @Test
    fun resetStartsANewSet() {
        val acc = RepUnitAccumulator(RepUnit.SIDE_PAIR)
        feed(acc, listOf(1000L to false, 2000L to true, 3000L to true))
        assertEquals(1, acc.completed)
        assertTrue(acc.pendingHalf)
        acc.reset()
        assertEquals(0, acc.completed)
        assertEquals(0, acc.invalid)
        assertFalse(acc.pendingHalf)
        assertNull(acc.pendingHalfAtMs)
        assertTrue(acc.repTimesMs.isEmpty())
        // 리셋 뒤 첫 걸음은 새 짝의 첫 쪽이다 — 앞 세트의 반쪽과 짝지어지지 않는다
        assertNull(acc.offer(5000L, true))
        assertEquals(RepUnitAccumulator.UnitRep(6000L, true), acc.offer(6000L, true))
    }

    // ---- 세션 경로: RepCounter.forSession → RepUnitAccumulator.onCounterFrame (PostureLive 분석 루프가 부르는 그대로)

    /**
     * 합성 걸음 신호로 세션 카운터를 돌린다 — 300 ms 간격, 선 자세 170°, 한 걸음 = 170 → [bottom] → 170(2.4 s) + 1.5 s 서 있기.
     * 카운터가 완료를 알린 프레임마다 [RepUnitAccumulator.onCounterFrame] 을 부르고, 표시 수(진행·자동 넘김에 더하는 수)를 모은다.
     */
    private class SessionDriver(exercise: String, unit: RepUnit?, val counter: RepCounter = RepCounter.forSession(exercise, floor = false)!!) {
        val acc = unit?.let { RepUnitAccumulator(it) }
        val records = ArrayList<RepRecord>()
        var t = 0L
        var displayed = 0
        var short = 0
        var half = false
        var tempo: Long? = null
        val completedPerFrame = ArrayList<Int>()

        fun frame(v: Float) {
            if (counter.onFrame(t, v)) {
                val tally = RepUnitAccumulator.onCounterFrame(counter, t, records, acc)
                displayed += tally.completedReps
                short += tally.repsShort
                half = tally.halfPending
                tempo = tally.tempoMs
                completedPerFrame += tally.completedReps
                assertEquals(tally.records, records.takeLast(tally.records.size))
            }
            t += 300L
        }

        fun stand(n: Int) = repeat(n) { frame(170f) }

        fun step(bottom: Float = 95f) {
            for (i in 0..8) frame(170f - (170f - bottom) * kotlin.math.sin(Math.PI * i / 8).toFloat())
            stand(5)
        }

        /** 걸음마다 (그 걸음 뒤 표시 수, 반대쪽 차례). */
        fun steps(bottoms: List<Float>, pauseAfter: Int? = null): List<Pair<Int, Boolean>> {
            stand(8)
            return bottoms.mapIndexed { k, b ->
                step(b)
                if (pauseAfter == k + 1) { counter.resetCycle(); acc?.onCounterCycleReset(); stand(6) }
                displayed to half
            }
        }
    }

    @Test
    fun sessionUnitComesFromTheProfileAndTheFloorPathIsAlwaysCycles() {
        for (name in listOf("런지", "바벨 런지", "사이드 런지", "크로스 런지")) {
            // 런지만 쪽별(§63) — 나머지 런지류는 두 걸음 = 1회
            assertEquals(name, if (name == "런지") RepUnit.SIDE_EACH else RepUnit.SIDE_PAIR, RepUnit.forSession(ExerciseProfiles.forName(name), floor = false))
            assertEquals(name, RepUnit.CYCLE, RepUnit.forSession(ExerciseProfiles.forName(name), floor = true))
        }
        for (name in listOf("덤벨 컬", "스탠딩 니업", "바벨 스쿼트")) {
            assertEquals(name, RepUnit.CYCLE, RepUnit.forSession(ExerciseProfiles.forName(name), floor = false))
        }
        assertEquals(RepUnit.CYCLE, RepUnit.forSession(null, floor = false))
    }

    @Test
    fun lungeSessionCountsOnlyWhenTheSecondSideIsDone() {
        // 사용자 결정(2026-09-24): 한쪽만 하면 수가 오르지 않고('반대쪽 차례'), 두 쪽을 다 해야 1회 — 목표 3회는 6걸음째에야 닿는다
        val d = SessionDriver("바벨 런지", RepUnit.forSession(ExerciseProfiles.forName("바벨 런지"), floor = false))
        val after = d.steps(List(6) { 95f })
        assertEquals(listOf(0, 1, 1, 2, 2, 3), after.map { it.first })
        assertEquals(listOf(true, false, true, false, true, false), after.map { it.second })
        assertEquals(listOf(0, 1, 0, 1, 0, 1), d.completedPerFrame)       // 완료 프레임마다 더하는 수 — 첫 쪽의 프레임은 0
        assertEquals(6, d.records.size)                                   // 로그의 렙 기록은 사이클(걸음) 단위 그대로
        assertEquals(6, d.counter.reps)
        assertEquals(d.counter.repTimesMs, d.records.map { it.tMs })
        // 템포는 표시 단위(두 걸음)의 간격
        assertEquals(d.acc!!.repTimesMs, d.records.map { it.tMs }.filterIndexed { i, _ -> i % 2 == 1 })
        assertEquals(RepMetrics.medianPeriodMs(d.acc.repTimesMs), d.tempo)
        assertEquals(2 * RepMetrics.medianPeriodMs(d.counter.repTimesMs)!!, d.tempo)
    }

    @Test
    fun lungeSessionPairRomIsShortWhenEitherStepIsShort() {
        // 바벨 런지(knee_minside, 검증 ROM 112.0852°): 둘째 걸음만 얕다(125°) → 첫 쌍이 미달, 나머지 쌍은 충족
        val d = SessionDriver("바벨 런지", RepUnit.SIDE_PAIR)
        d.steps(listOf(95f, 125f, 95f, 95f))
        assertEquals(listOf(true, false, true, true), d.records.map { it.valid })
        assertEquals(2, d.displayed)
        assertEquals(1, d.short)
        assertEquals(listOf(false, true), d.acc!!.reps.map { it.valid })
    }

    @Test
    fun cycleSessionCountsEveryCycle() {
        val d = SessionDriver("바벨 스쿼트", RepUnit.forSession(ExerciseProfiles.forName("바벨 스쿼트"), floor = false))
        val after = d.steps(List(3) { 95f })
        assertEquals(listOf(1, 2, 3), after.map { it.first })
        assertTrue(after.none { it.second })
        assertEquals(3, d.records.size)
    }

    @Test
    fun pauseBetweenSidesKeepsTheFirstSideInTheSession() {
        val d = SessionDriver("바벨 런지", RepUnit.SIDE_PAIR)
        val after = d.steps(List(4) { 95f }, pauseAfter = 1)
        assertEquals(listOf(0, 1, 1, 2), after.map { it.first })
        assertEquals(4, d.records.size)
    }

    @Test
    fun newCoreFirstTwoStepsArePublishedTogetherAsOnePair() {
        // 새 코어(polarity 켬)는 시작 확정으로 첫 두 사이클을 한 프레임에 함께 발표한다 — 그 프레임에서 한 쌍이 완료된다
        val sig = RepSignals.byExercise.getValue("바벨 런지").copy(polarity = RepPolarity.DOWN)
        val d = SessionDriver("바벨 런지", RepUnit.SIDE_PAIR, RepCounter(sig, maxGapMs = 1500L, completeOnReturn = true))
        val after = d.steps(List(4) { 95f })
        assertEquals(listOf(0, 1, 1, 2), after.map { it.first })
        assertEquals(listOf(1, 0, 1), d.completedPerFrame)                // 첫 프레임에 두 걸음(기록 2개) → 1회
        assertEquals(4, d.records.size)
    }

    @Test
    fun withoutAnAccumulatorEachCycleIsOneRep() {
        // 방어 경로: 누적기가 없으면 사이클 하나 = 1회(지금까지의 동작), 반쪽 없음, 템포는 카운터 발표 간격
        val d = SessionDriver("바벨 런지", null)
        val after = d.steps(List(3) { 95f })
        assertEquals(listOf(1, 2, 3), after.map { it.first })
        assertFalse(d.half)
        assertEquals(RepMetrics.medianPeriodMs(d.counter.repTimesMs), d.tempo)
    }

    @Test
    fun repsIsASnapshot() {
        val acc = RepUnitAccumulator(RepUnit.CYCLE)
        acc.offer(1000L, true)
        val snap = acc.reps
        acc.offer(2000L, true)
        assertEquals(1, snap.size)
        assertEquals(2, acc.reps.size)
    }
}
