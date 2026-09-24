package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/**
 * 복귀 히스테리시스 코어 + 시작 확정 (docs/REP_ENGINE_DESIGN.md §4.2·§4.3).
 *
 * 합성 신호는 무릎각처럼 휴식 = 큰 값(170°)이고 반복이 80° 까지 내려갔다 돌아온다(DOWN). 게이트는 각도형 35°.
 * 이 테스트는 규약(언제 세고 언제 안 세는가)을 고정한다 — 실제 동작의 정확도는 재생기(MM-Fit)와 폰 라벨이 잰다.
 */
class RepHysteresisTest {

    private val knee = RepSignal("knee_mean", 35f, polarity = RepPolarity.DOWN)

    private fun session(signal: RepSignal = knee) = RepCounter(signal, maxGapMs = 1500L, completeOnReturn = true)

    /** 코사인 반복: t=0 에서 휴식(top)부터 내려간다. [phase0] 로 시작 위상을 옮길 수 있다(라디안). */
    private fun cosineValue(tMs: Long, periodMs: Long, top: Float = 170f, bottom: Float = 80f, phase0: Double = 0.0): Float {
        val mid = (top + bottom) / 2f
        val half = (top - bottom) / 2f
        return mid + half * cos(2 * PI * tMs / periodMs + phase0).toFloat()
    }

    /** [reps] 회를 [stepMs] 간격으로 먹이고 휴식 자세로 [holdMs] 를 더 먹인다. 마지막 샘플 다음 시각을 돌려준다. */
    private fun feedCosine(
        c: RepCounter, reps: Int, periodMs: Long, stepMs: Long = 300L, holdMs: Long = 0L, t0: Long = 0L,
        phase0: Double = 0.0, top: Float = 170f, bottom: Float = 80f,
    ): Long {
        var t = 0L
        val end = reps * periodMs
        while (t < end) {
            c.onFrame(t0 + t, cosineValue(t, periodMs, top, bottom, phase0)); t += stepMs
        }
        var h = 0L
        while (h < holdMs) {
            c.onFrame(t0 + t, top); t += stepMs; h += stepMs
        }
        return t0 + t
    }

    @Test fun lastRepIsConfirmedOnReturnWithoutAFollowingDescent() {
        // §42 요구 유지: 마지막 반복은 다음 하강이 아니라 복귀 지점에서 확정된다.
        val c = session()
        val period = 3_000L
        var t = 0L
        while (t < 10 * period) { c.onFrame(t, cosineValue(t, period)); t += 300L }
        // 10번째 반복의 복귀 구간 안에서 이미 10회 — 휴식 자세를 더 먹이지 않았다.
        assertEquals(10, c.reps)
        assertTrue("마지막 발화 ${c.repTimesMs.last()}", c.repTimesMs.last() > 9 * period)
        // 휴식 자세를 오래 유지해도 늘지 않는다(정지는 반복이 아니다).
        repeat(40) { c.onFrame(t, 170f); t += 300L }
        assertEquals(10, c.reps)
        assertEquals(0, c.pendingReps)
        assertNull(c.pendingAtSetEnd().inProgress)
    }

    @Test fun countsFromTheFirstFrameWithoutAPreparationPose() {
        // 준비 자세 없이 하강 중간(약 150°)에서 촬영이 시작돼도 첫 반복부터 센다 — ReturnRepTracker 는 3샘플 안정 자세를 먼저 요구했다.
        val c = session()
        val period = 2_400L
        val phase0 = PI / 4                       // cos(π/4)·45 + 125 ≈ 157°, 내려가는 중
        feedCosine(c, reps = 10, periodMs = period, phase0 = phase0, holdMs = 900L)
        assertEquals(10, c.reps)
        // 첫 사이클의 휴식 기준은 첫 샘플(준비 자세가 아니다)이고 그래도 짝이 맞아 함께 발표된다.
        assertTrue("첫 사이클 max=${c.publishedReps.first().max}", c.publishedReps.first().max < 165f)
    }

    @Test fun cosineTemposFromTwoToSixSecondsCountAllTenAtTheAppCadence() {
        // 앱의 페이스 안내는 스쿼트·런지 반복당 4초다. 예전 제안(모든 사이클에 4초 창)은 4.5초/회 이상에서 0회였다.
        // 앱 간격(300ms, 설계 §4.6 — 200ms 부스트는 없다) 격자의 시작점(0~290ms)을 바꿔도 전부 10회여야 한다.
        // 반복당 8초는 여기서 약속하지 않는다 — 첫 짝 창(8초) 경계에 걸려 8~10회다(아래 기록 테스트, 설계 §13.4 의 템포 절벽).
        for (period in listOf(2_000L, 3_000L, 4_500L, 6_000L)) for (offset in 0L until 300L step 10L) {
            val c = session()
            var t = offset
            while (t < 10 * period) { c.onFrame(t, cosineValue(t, period)); t += 300L }
            repeat(3) { c.onFrame(t, 170f); t += 300L }
            assertEquals("반복당 ${period}ms · 격자 ${offset}ms", 10, c.reps)
        }
    }

    @Test fun eightSecondRepsAtThreeHundredMsSitOnTheWindowEdge() {
        // 기록용 — 기본값(첫 짝 8초)을 바꾸지 않고 경계의 크기만 적는다. 같은 10회 동작을 300ms 샘플 격자의 시작점만
        // 바꿔(0~290ms) 먹이면 발화 간격이 7.8/8.1초로 흔들려, 첫 짝이 8.1초면 창을 넘어 앞 사이클이 버려진다: 8~10회.
        // 8초/회 이상의 페이스까지 약속하려면 창에 샘플 간격 한두 개의 여유가 필요하다(열린 결정).
        val counts = (0 until 30).map { k ->
            val c = session()
            var t = k * 10L
            while (t < 80_000L) { c.onFrame(t, cosineValue(t, 8_000L)); t += 300L }
            repeat(3) { c.onFrame(t, 170f); t += 300L }
            // 버려지거나 보류된 것까지 합치면 발화는 늘 10회다 — 놓친 것은 코어가 아니라 시작 확정의 창이다.
            assertEquals("격자 ${k * 10}ms", 10, c.reps + c.droppedReps.size + c.pendingReps)
            c.reps
        }
        assertEquals(10, counts.max())
        assertEquals(8, counts.min())
    }

    @Test fun theOldFourSecondWindowOnEveryRepCountsNothingAtSlowTempo() {
        // 로드맵 결함 (1)의 재현: 첫 짝·이후 반복 모두 4초 창이면 4.5초/회 세트가 0회. 새 기본값(8초 · 이후 진폭만)은 10회.
        val core = RepHysteresis(35f)
        val old = RepStartConfirmation(firstPairWindowMs = 4_000L, laterWindowMs = 4_000L)
        val new = RepStartConfirmation()
        var t = 0L
        val period = 4_500L
        var oldCount = 0
        var newCount = 0
        while (t < 10 * period + 900L) {
            val v = if (t < 10 * period) cosineValue(t, period) else 170f
            core.onFrame(t, v)?.let { cycle ->
                oldCount += old.offer(cycle).size
                newCount += new.offer(cycle).size
            }
            t += 300L
        }
        assertEquals(0, oldCount)
        assertEquals(10, newCount)
    }

    @Test fun refractoryDropsASecondFireWithinEightHundredMs() {
        val core = RepHysteresis(35f)
        val fires = ArrayList<RepCycle>()
        // 100ms 간격 두 번의 빠른 사이클: 두 번째 발화는 첫 발화 300ms 뒤 → 불응기(800ms)로 버린다.
        val values = listOf(170f, 120f, 170f, 170f, 120f, 170f, 170f)
        values.forEachIndexed { i, v -> core.onFrame(i * 100L, v)?.let(fires::add) }
        assertEquals(1, fires.size)
        assertEquals(300L, fires.single().tMs)
        assertEquals(1, core.refractoryDrops)
        // 불응기가 지난 뒤의 사이클은 다시 센다.
        listOf(120f, 170f, 170f).forEachIndexed { i, v -> core.onFrame(1_300L + i * 100L, v)?.let(fires::add) }
        assertEquals(2, fires.size)
    }

    @Test fun incompleteReturnFollowedByANewDescentFiresByRedescent() {
        // 복귀 지점(80 + 0.75·90 = 147.5)에 닿기 전에 다시 35° 이상 내려가면 '다음 사이클이 시작됐다' 로 보고 발화한다.
        val core = RepHysteresis(35f)
        val seq = listOf(170f, 130f, 80f, 120f, 140f, 100f, 80f, 120f, 150f, 170f)
        val fires = ArrayList<RepCycle>()
        seq.forEachIndexed { i, v -> core.onFrame(i * 300L, v)?.let(fires::add) }
        assertEquals(2, fires.size)
        val first = fires[0]
        assertTrue(first.byRedescent)
        assertEquals(1_500L, first.tMs)           // 100° 로 다시 내려간 프레임
        assertEquals(80f, first.min, 0f)
        assertEquals(170f, first.max, 0f)
        // 둘째 사이클의 휴식 기준은 재하강 직전의 최댓값(140°)이다.
        val second = fires[1]
        assertFalse(second.byRedescent)
        assertEquals(140f, second.max, 0f)
        assertEquals(80f, second.min, 0f)
    }

    @Test fun movementBelowTheGateIsNotARep() {
        val c = session()
        feedCosine(c, reps = 10, periodMs = 3_000L, top = 170f, bottom = 140f, holdMs = 900L)   // 스윙 30° < 35°
        assertEquals(0, c.reps)
        assertEquals(0, c.pendingReps)
        assertTrue(c.droppedReps.isEmpty())
    }

    @Test fun gapLongerThanMaxGapDiscardsTheCycleInProgressButKeepsTheCount() {
        val c = session()
        var t = feedCosine(c, reps = 3, periodMs = 3_000L, holdMs = 600L)
        assertEquals(3, c.reps)
        // 하강 도중 가림 → 2초 공백(> 1.5초) → 바닥에서 재개해 올라온다: 끊긴 사이클을 완료로 만들지 않는다.
        for (v in listOf(150f, 120f, 90f)) { c.onFrame(t, v); t += 300L }
        t += 2_000L
        for (v in listOf(80f, 110f, 140f, 160f, 170f, 170f)) { assertFalse(c.onFrame(t, v)); t += 300L }
        assertEquals(3, c.reps)
        // 이후의 온전한 반복은 다시 센다(진폭이 직전 발표 사이클과 비슷하므로 바로 발표).
        feedCosine(c, reps = 2, periodMs = 3_000L, holdMs = 600L, t0 = t)
        assertEquals(5, c.reps)
    }

    @Test fun cycleExtremaAreRawValues() {
        // 한 샘플짜리 바닥(62°)이 그대로 남는다 — 3점 중앙값이면 110° 로 지워진다(레거시 경로).
        val c = session()
        var t = 0L
        fun rep(bottom: Float) {
            for (v in listOf(170f, 140f, 110f, bottom, 110f, 140f, 170f)) { c.onFrame(t, v); t += 300L }
        }
        rep(62f); rep(75f)
        assertEquals(2, c.reps)
        assertEquals(listOf(62f, 75f), c.publishedReps.map { it.min })
        assertEquals(listOf(170f, 170f), c.publishedReps.map { it.max })
        assertEquals(75f, c.lastCycleMin, 0f)
        assertEquals(170f, c.lastCycleMax, 0f)
    }

    @Test fun aOneRepSetCountsNothingAndLeavesOnePending() {
        // 짝이 없는 한 번은 반복으로 발표하지 않는다(원칙 #1). 세트 끝에 '미완 후보' 로만 남는다.
        val c = session()
        feedCosine(c, reps = 1, periodMs = 3_000L, holdMs = 3_000L)
        assertEquals(0, c.reps)
        assertEquals(1, c.pendingReps)
        val pending = c.pendingAtSetEnd()
        assertNotNull(pending.unconfirmed)
        assertNull(pending.inProgress)
        assertTrue(c.repTimesMs.isEmpty())
    }

    @Test fun resetCycleDropsTheHeldCycleSoItCannotPairAcrossAPause() {
        // 설계 §4.7: 일시정지·카메라 전환의 resetCycle() 은 진행 중 사이클과 보류 사이클을 함께 버린다.
        // 남기면 리셋 앞의 한 번이 리셋 뒤 첫 사이클과 짝지어 +2 로 발표된다 — 멈췄던 경계를 넘어 세는 것(원칙 #1).
        val c = session()
        var t = feedCosine(c, reps = 1, periodMs = 3_000L, holdMs = 600L)
        assertEquals(0, c.reps)
        assertEquals(1, c.pendingReps)
        val held = c.pendingAtSetEnd().unconfirmed!!
        c.resetCycle()                                   // PostureLive 가 일시정지·카메라 전환에서 부르는 것
        assertEquals(0, c.pendingReps)
        assertEquals(listOf(held), c.droppedReps)        // 버린 사이클은 로그로 남는다
        t += 2_000L                                      // 일시정지 2초(짝 창 8초 안)
        t = feedCosine(c, reps = 1, periodMs = 3_000L, holdMs = 600L, t0 = t)
        assertEquals(0, c.reps)                          // 리셋 뒤 첫 사이클은 다시 보류 — 리셋 앞 사이클과 짝짓지 않는다
        assertEquals(1, c.pendingReps)
        feedCosine(c, reps = 1, periodMs = 3_000L, holdMs = 600L, t0 = t)
        assertEquals(2, c.reps)                          // 리셋 뒤 두 사이클이 서로 짝지어 함께 발표된다
        assertTrue(c.repTimesMs.all { it > held.tMs + 2_000L })
    }

    @Test fun aDetectionGapKeepsTheHeldCycleUntilThePairWindowCloses() {
        // 끊김(검출 공백 > maxGap 1.5 s)은 resetCycle 이 아니다 — 코어는 진행 중 사이클만 버리고, 보류는 짝 창이 수명을 정한다
        // (프로토타입·배터리와 같은 규약 — 설계 §13 의 숫자가 이 동작 위에서 나왔다).
        val c = session()
        var t = feedCosine(c, reps = 1, periodMs = 3_000L, holdMs = 600L)
        assertEquals(1, c.pendingReps)
        repeat(7) { c.onFrame(t, null); t += 300L }      // 가림 2.1 s — 값이 없는 프레임
        feedCosine(c, reps = 1, periodMs = 3_000L, holdMs = 600L, t0 = t)
        assertEquals(2, c.reps)
        assertTrue(c.droppedReps.isEmpty())
    }

    @Test fun halfReturnedCandidateAtSetEndIsNotCounted() {
        val c = session()
        var t = feedCosine(c, reps = 2, periodMs = 3_000L, holdMs = 600L)
        assertEquals(2, c.reps)
        // 셋째 반복을 절반만 올라온 채 세트가 끝난다: ASC 후보로만 남는다.
        for (v in listOf(150f, 120f, 90f, 80f, 120f, 130f)) { c.onFrame(t, v); t += 300L }
        assertEquals(2, c.reps)
        val candidate = c.pendingAtSetEnd().inProgress
        assertNotNull(candidate)
        assertEquals(80f, candidate!!.min, 0f)
    }

    @Test fun firstTwoCyclesArePublishedTogetherInOrderWithTheirOwnTimes() {
        val c = session()
        var t = 0L
        var publishFrames = 0
        var lastBatch: List<RepCycle> = emptyList()
        while (t < 2 * 3_000L + 900L) {
            val v = if (t < 6_000L) cosineValue(t, 3_000L) else 170f
            if (c.onFrame(t, v)) { publishFrames++; lastBatch = c.newlyPublished }
            t += 300L
        }
        assertEquals(1, publishFrames)                 // 한 프레임에
        assertEquals(2, c.reps)                        // 두 회가 함께
        assertEquals(2, lastBatch.size)
        val (a, b) = c.publishedReps
        assertTrue("발화 시각 ${a.tMs} < ${b.tMs}", a.tMs < b.tMs)
        assertEquals(listOf(a.tMs, b.tMs), c.repTimesMs)
        assertTrue(a.tMs < 3_000L && b.tMs > 3_000L)
        assertEquals(b.min, c.lastCycleMin, 0f)
        assertEquals(b.max, c.lastCycleMax, 0f)
        // 다음 프레임에서는 새로 발표된 것이 없다.
        c.onFrame(t, 170f)
        assertTrue(c.newlyPublished.isEmpty())
    }

    @Test fun anIsolatedLargeMovementAfterConfirmedRepsIsDropped() {
        // 이후 반복은 직전 발표 사이클과 진폭 비 0.5~2 안이어야 한다. 한 번 튄 사이클은 바로 다음 사이클과 짝이 안 맞으면 버린다.
        val conf = RepStartConfirmation()
        fun cyc(t: Long, amp: Float) = RepCycle(t, t - 1_000L, 170f - amp, 170f)
        assertEquals(0, conf.offer(cyc(1_000L, 40f)).size)
        assertEquals(2, conf.offer(cyc(3_000L, 40f)).size)
        assertEquals(0, conf.offer(cyc(5_000L, 90f)).size)      // 2.25배 — 보류
        assertEquals(1, conf.offer(cyc(7_000L, 42f)).size)      // 보류와 짝이 안 맞음 → 보류는 버림, 직전 발표와는 맞음
        assertEquals(listOf(5_000L), conf.dropped.map { it.tMs })
        assertEquals(listOf(1_000L, 3_000L, 7_000L), conf.published.map { it.tMs })
        // 진폭이 달라진 두 사이클이 연달아 오면 새 기준으로 함께 발표한다(깊이를 바꾼 세트).
        assertEquals(0, conf.offer(cyc(9_000L, 90f)).size)
        assertEquals(2, conf.offer(cyc(11_000L, 85f)).size)
        assertEquals(listOf(1_000L, 3_000L, 7_000L, 9_000L, 11_000L), conf.published.map { it.tMs })
    }

    @Test fun firstPairOutsideTheWindowIsNotConfirmed() {
        val conf = RepStartConfirmation()
        fun cyc(t: Long) = RepCycle(t, t - 1_000L, 80f, 170f)
        assertEquals(0, conf.offer(cyc(0L)).size)
        assertEquals(0, conf.offer(cyc(8_001L)).size)            // 8초 초과 — 첫 사이클은 버리고 둘째가 보류
        assertEquals(listOf(0L), conf.dropped.map { it.tMs })
        assertEquals(2, conf.offer(cyc(16_001L)).size)           // 정확히 8초 — 창은 포함
    }

    @Test fun upPolarityCountsTheMirroredSignalAndReportsRealExtrema() {
        // UP 은 아직 어느 종목에도 쓰지 않는다(opt-in). 부호를 뒤집은 같은 규칙이 도는지만 고정한다.
        val up = RepCounter(RepSignal("x", 35f, polarity = RepPolarity.UP), maxGapMs = 1500L, completeOnReturn = true)
        val down = session()
        var t = 0L
        while (t < 5 * 3_000L + 900L) {
            val v = if (t < 15_000L) cosineValue(t, 3_000L) else 170f
            down.onFrame(t, v)
            up.onFrame(t, 250f - v)                      // 80..170 ↔ 170..80 거울상
            t += 300L
        }
        assertEquals(5, down.reps)
        assertEquals(down.reps, up.reps)
        assertEquals(down.repTimesMs, up.repTimesMs)
        val d = down.publishedReps.last()
        val u = up.publishedReps.last()
        assertEquals(250f - d.max, u.min, 1e-3f)
        assertEquals(250f - d.min, u.max, 1e-3f)
    }

    @Test fun deviceFixtureBaseline1IsRecordedNotAsserted() {
        // 실기기 푸시업 baseline 1/3 — 정답은 3~4회(사람이 센 값의 폭). 여기서는 두 경로가 **센 값을 기록**한다:
        // 새 코어 4회(정답 폭 안), 앱 세션의 현재 복귀형 0회(로드맵 결함 7 — 안정 준비 자세를 못 잡는다).
        // 정답을 단정하는 단언이 아니다 — 코어를 바꾸면 이 숫자가 바뀌는 것을 알아차리기 위한 회귀 기록이다.
        val lines = javaClass.classLoader!!.getResourceAsStream("rep_fixture_baseline1.txt")!!
            .bufferedReader().readLines().filter { !it.startsWith("#") && it.isNotBlank() }
        val push = RepSignal("wrist_shoulder_d", 0.30f, plausibleMin = 0.10f)
        val core = RepCounter(push.copy(polarity = RepPolarity.DOWN), maxGapMs = 1500L, completeOnReturn = true)
        val live = RepCounter(push, maxGapMs = 1500L, completeOnReturn = true)
        for (ln in lines) {
            val (t, v) = ln.split(",", limit = 2)
            val value = v.takeIf { it.isNotBlank() }?.toFloat()
            core.onFrame(t.toLong(), value)
            live.onFrame(t.toLong(), value)
        }
        assertEquals(4, core.reps)
        assertEquals(0, core.pendingReps)
        assertEquals(0, live.reps)
    }

    @Test fun sessionFactoryReproducesPostureLiveConstruction() {
        // PostureLive.kt 의 세 갈래: 규칙 rep 설정 / 바닥(ROM 뗌) / 서서(등록부 그대로). 전부 maxGap 1.5 s · 복귀형.
        assertNull(RepCounter.forSession("플랭크", floor = true))
        assertNull(RepCounter.forSession("스탠딩 사이드 크런치", floor = false))
        val rule = RepCounter.forSession("푸시업", ruleRomDirection = "min", ruleRomThreshold = 0.5f, floor = true)!!
        assertEquals("min", rule.signal.romDirection)
        assertEquals(0.5f, rule.signal.romThreshold!!, 0f)
        assertFalse(rule.signal.romValidated)
        val floor = RepCounter.forSession("푸시업", floor = true)!!
        assertNull(floor.signal.romThreshold)
        assertNull(floor.signal.romDirection)
        assertFalse(floor.signal.romValidated)
        assertEquals(RepSignals.byExercise.getValue("바벨 스쿼트"), RepCounter.forSession("바벨 스쿼트", floor = false)!!.signal)
        // 방향·임계값 중 하나만 있으면 규칙 설정으로 보지 않는다(RepRuleConfig 는 둘 다 가진다).
        assertNull(RepCounter.forSession("푸시업", ruleRomDirection = "min", floor = true)!!.signal.romThreshold)
        // 동작도 직접 만든 앱 구성과 같다 — 가림(1.5 s 초과 공백)이 있는 기기 픽스처로 확인.
        val lines = javaClass.classLoader!!.getResourceAsStream("rep_fixture_baseline1.txt")!!
            .bufferedReader().readLines().filter { !it.startsWith("#") && it.isNotBlank() }
        val manual = RepCounter(RepSignals.byExercise.getValue("푸시업").copy(romThreshold = null, romDirection = null, romValidated = false),
            maxGapMs = 1500L, completeOnReturn = true)
        for (ln in lines) {
            val (t, v) = ln.split(",", limit = 2)
            val value = v.takeIf { it.isNotBlank() }?.toFloat()
            floor.onFrame(t.toLong(), value); manual.onFrame(t.toLong(), value)
        }
        assertEquals(manual.reps, floor.reps)
        assertEquals(manual.repTimesMs, floor.repTimesMs)
        // 서서 하는 종목: 준비 자세 → 반복 3회 (복귀형이 세는 입력)
        val squat = RepCounter.forSession("바벨 스쿼트", floor = false)!!
        val squatManual = RepCounter(RepSignals.byExercise.getValue("바벨 스쿼트"), maxGapMs = 1500L, completeOnReturn = true)
        var t = 0L
        repeat(7) { squat.onFrame(t, 170f); squatManual.onFrame(t, 170f); t += 300L }
        var k = 0L
        while (k < 3 * 3_000L + 2_100L) {
            val v = if (k < 9_000L) cosineValue(k, 3_000L) else 170f
            squat.onFrame(t + k, v); squatManual.onFrame(t + k, v); k += 300L
        }
        assertTrue(squatManual.reps > 0)
        assertEquals(squatManual.reps, squat.reps)
        assertEquals(squatManual.repTimesMs, squat.repTimesMs)
    }

    @Test fun theNewCoreIsDisabledForEveryExerciseInTheApp() {
        // 폰 라벨 검증 전에는 앱의 카운트 동작을 바꾸지 않는다(원칙 #2) — 등록부 어느 종목도 polarity 를 켜지 않는다.
        RepSignals.byExercise.forEach { (ex, sig) -> assertNull("$ex polarity", sig.polarity) }
        RepSignals.byExercise.keys.forEach { ex -> RepCounter.forSession(ex, floor = false)?.let { assertFalse(ex, it.usesHysteresis) } }
        assertTrue(RepCounter.forSession("바벨 스쿼트", floor = false)!!.publishedReps.isEmpty())
    }

    @Test fun lungeCountsOnKneeMeanWithoutRomAndKeepsTheComparisonSignal() {
        val lunge = RepSignals.byExercise.getValue("스텝 포워드 다이나믹 런지")
        assertEquals("knee_mean", lunge.feature)
        assertEquals(35f, lunge.minAmp, 0f)
        // ROM 은 knee_out_mean 단위였다 — 신호와 함께 뗐다. 판정하지 않은 ROM 을 '유효' 로 말하지 않도록 null.
        assertNull(lunge.romThreshold)
        assertNull(lunge.romDirection)
        assertNull(lunge.isValidRep(60f, 170f))
        // TRACK 비교는 기존 단위 그대로.
        val cmp = lunge.comparisonSignal()
        assertEquals("knee_out_mean", cmp.feature)
        assertEquals(0.10f, cmp.minAmp, 0f)
        assertNull(cmp.romThreshold)
        // 다른 종목은 비교 신호가 곧 카운트 신호다.
        val squat = RepSignals.byExercise.getValue("바벨 스쿼트")
        assertTrue(squat.comparisonSignal() === squat)
        // 덤벨 컬은 영상 단계 결과 전까지 elbow_mean.
        assertEquals("elbow_mean", RepSignals.byExercise.getValue("덤벨 컬").feature)
    }
}
