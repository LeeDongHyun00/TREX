package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 자동 렙 카운터 (spec §27) — 파이썬 레퍼런스(rep_replay.py)와의 패리티가 핵심.
 * 실기기 픽스처(rep_fixture_baseline1.txt)는 파이썬 재생에서 4렙(정답 3~4)이 나온 세트다.
 */
class RepCounterTest {

    private fun counter(minAmp: Float = 0.30f) = RepCounter(RepSignal("wrist_shoulder_d", minAmp, plausibleMin = 0.10f))

    /** 주기 periodMs 의 삼각파: bottom↔top 왕복. 300ms 샘플링. */
    private fun triangle(c: RepCounter, cycles: Int, periodMs: Long, top: Float = 1.4f, bottom: Float = 0.4f): Int {
        var t = 0L
        var completed = 0
        val half = (periodMs / 2).toInt()
        val step = 300
        repeat(cycles) {
            var x = 0
            while (x < half) { // 하강
                val v = top - (top - bottom) * x / half
                if (c.onFrame(t, v)) completed++
                t += step; x += step
            }
            x = 0
            while (x < half) { // 상승
                val v = bottom + (top - bottom) * x / half
                if (c.onFrame(t, v)) completed++
                t += step; x += step
            }
        }
        return completed
    }

    @Test
    fun deviceFixtureMatchesPythonReference() {
        // 실기기 푸시업 baseline 1/3 (82프레임, 가림 프레임 포함) — 파이썬 스트리밍 카운터 = 4렙
        val lines = javaClass.classLoader!!.getResourceAsStream("rep_fixture_baseline1.txt")!!
            .bufferedReader().readLines().filter { !it.startsWith("#") && it.isNotBlank() }
        val c = counter()
        for (ln in lines) {
            val (t, v) = ln.split(",", limit = 2)
            c.onFrame(t.toLong(), v.takeIf { it.isNotBlank() }?.toFloat())
        }
        assertEquals(4, c.reps)
        // 불응기: 렙 간격이 전부 1.2s 이상
        c.repTimesMs.zipWithNext { a, b -> assertTrue("렙 간격 ${b - a}ms", b - a >= 1_200L) }
    }

    @Test
    fun countsTriangleWaveAtNormalTempo() {
        val c = counter()
        triangle(c, cycles = 5, periodMs = 4_800L)
        // 워밍업(첫 8샘플)과 위상에 따라 첫 사이클은 놓칠 수 있다 — 5사이클에서 4~5
        assertTrue("reps=${c.reps}", c.reps in 4..5)
        assertNotNull(c.periodMs)
        assertTrue("period=${c.periodMs}", c.periodMs!! in 3_800L..5_800L)
    }

    @Test
    fun amplitudeGateBlocksNoise() {
        // 스윙 0.2 < 게이트 0.30 — 잡음/무활동은 세지 않는다 (플랭크 잡음 바닥 실측의 교훈)
        val c = counter(minAmp = 0.30f)
        triangle(c, cycles = 6, periodMs = 4_800L, top = 1.0f, bottom = 0.8f)
        assertEquals(0, c.reps)
    }

    @Test
    fun occlusionPausesWithoutCounting() {
        val c = counter()
        var t = 0L
        // 정상 2사이클 → 가림 구간 → 재개 1사이클: 가림 중 카운트 없음, 재개 후 계속
        triangle(c, 2, 4_800L)
        val before = c.reps
        repeat(10) { c.onFrame(t + 100_000L + it * 300L, null) }
        assertEquals(before, c.reps)
        val c2 = counter()
        triangle(c2, 3, 4_800L)
        assertTrue(c2.reps >= before)   // 재개 경로가 상태를 깨뜨리지 않는다 (같은 로직 재사용 확인)
    }

    @Test
    fun refractorySuppressesTooFastCycles() {
        // 0.9s 주기(불응기 1.2s 미만): 사이클마다 세지 않고 걸러진다 — 놓침이지 과카운트가 아님
        val c = counter()
        triangle(c, cycles = 8, periodMs = 900L)
        assertTrue("reps=${c.reps}", c.reps < 8)
    }

    @Test
    fun registryMapsExercisesHonestly() {
        // 등척성은 카운터 미적용 (HoldTimer 대상)
        assertNull(RepCounter.forExercise("플랭크"))
        // 기기 검증 종목
        val push = RepCounter.forExercise("푸시업")
        assertNotNull(push)
        assertEquals("wrist_shoulder_d", push!!.signal.feature)
        assertTrue(push.signal.validated)
        // 서서 종목: 힙 힌지 → hip_mean (앱 가용 매핑), beta
        val dead = RepCounter.forExercise("바벨 데드리프트")
        assertEquals("hip_mean", dead!!.signal.feature)
        assertTrue(!dead.signal.validated)
        // 신뢰 신호가 없는 종목은 미등록 — 오카운트보다 미표시
        assertNull(RepCounter.forExercise("스탠딩 사이드 크런치"))
    }
@Test
    fun romValidityJudgesShallowRepsInvalid() {
        // 푸시업 ROM: min 방향, 임계 0.710 (AIHub 전조건 정상 렙 90% 통과 분위수)
        val sig = RepSignals.byExercise.getValue("푸시업")
        assertEquals("min", sig.romDirection)
        assertEquals(0.710f, sig.romThreshold!!, 0.01f)
        assertTrue(sig.romValidated)
        assertTrue(sig.invalidCue.contains("가슴"))
        // 깊은 렙(하단 0.45) = 유효, 얕은 렙(하단 0.85) = 무효
        assertEquals(true, sig.isValidRep(0.45f, 1.4f))
        assertEquals(false, sig.isValidRep(0.85f, 1.4f))
        // max 방향 종목 (크런치: 상단 극값이 임계 이상이어야 유효)
        val cr = RepSignals.byExercise.getValue("크런치")
        assertEquals("max", cr.romDirection)
        assertEquals(true, cr.isValidRep(0f, cr.romThreshold!! + 0.1f))
        assertEquals(false, cr.isValidRep(0f, cr.romThreshold!! - 0.1f))
    }

    @Test
    fun cycleExtremaAreExposedPerRep() {
        val c = counter()
        // 깊은 사이클 1개: 하단 0.4 → lastCycleMin 이 그 근방
        triangle(c, cycles = 2, periodMs = 4_800L, top = 1.4f, bottom = 0.4f)
        assertTrue(c.reps >= 1)
        assertTrue("min=${c.lastCycleMin}", c.lastCycleMin <= 0.55f)   // 300ms 이산화로 정확한 저점(0.4)은 방출 안 됨(최저 0.525)
        assertTrue("max=${c.lastCycleMax}", c.lastCycleMax >= 1.2f)
    }

    @Test
    fun mixedDepthSetSeparatesValidAndInvalid() {
        // 라벨 세트 실측 시나리오: 깊(0.45)·얕(0.85) 혼합 — 반전 카운터는 둘 다 사이클로 잡고
        // ROM(0.71)이 유효/무효를 가른다. v3 밴드는 얕은 렙을 사이클로도 못 잡았다(실측 5/12 원인).
        val sig = RepSignals.byExercise.getValue("푸시업")
        val c = RepCounter(sig)
        var t = 0L
        var valid = 0
        var invalid = 0
        fun rep(bottom: Float) {
            for (v in listOf(1.4f, 1.1f, 0.95f, bottom, bottom + 0.1f, 1.1f, 1.4f, 1.4f)) {
                if (c.onFrame(t, v)) {
                    if (sig.isValidRep(c.lastCycleMin, c.lastCycleMax) == true) valid++ else invalid++
                }
                t += 300
            }
        }
        repeat(3) { rep(0.45f) }   // 깊 3
        repeat(3) { rep(0.85f) }   // 얕 3
        repeat(3) { rep(0.45f) }   // 깊 3
        // 마지막 상단은 다음 하락이 있어야 확정 — 마무리 하락 프레임
        c.onFrame(t, 1.0f)
        if (c.onFrame(t + 300, 0.95f)) { if (sig.isValidRep(c.lastCycleMin, c.lastCycleMax) == true) valid++ else invalid++ }
        assertTrue("valid=$valid invalid=$invalid", valid in 5..6)
        assertTrue("invalid=$invalid", invalid == 3)
    }

    @Test
    fun plausibilityGateIgnoresWristCollapse() {
        // 손목-붕괴 잡값(0.01)은 물리 하한(0.10) 밖 — 가림처럼 일시정지, 하단으로 오인하지 않음
        val c = counter()
        var t = 0L
        for (v in listOf(1.4f, 1.0f, 0.6f, 0.01f, 0.02f, 0.6f, 1.0f, 1.4f, 1.4f, 1.4f, 1.0f, 0.9f, 0.8f)) {   // 꼬리 하락 — 3점 평활 지연 보상
            c.onFrame(t, v); t += 300
        }
        assertTrue(c.reps >= 1)
        assertTrue("cycleMin=${c.lastCycleMin} — 잡값이 하단이 되면 안 됨", c.lastCycleMin >= 0.5f)
    }

    @Test
    fun unvalidatedExercisesUseNeutralCue() {
        // 방향 자동판정이 복귀 끝을 잡았을 수 있는 종목 — 방향 중립 문구만 (정직성)
        val sig = RepSignals.byExercise.getValue("힙쓰러스트")
        assertTrue(!sig.romValidated)
        assertTrue(sig.invalidCue.contains("끝까지"))
    }

    // ---- 반복 판별 게이트 (spec §62) — 2026-09-25 실기기 세트의 재현: 무릎 들기(제자리 걷기)는 knee_mean 을 흔들지만 스쿼트가 아니다

    /** 세션 카운터(앱 경로) 에 (knee_mean, knee_maxside) 를 300 ms 간격으로 넣는다. */
    private fun feed(c: RepCounter, t0: Long, pairs: List<Pair<Float, Float?>>): Long {
        var t = t0
        for ((mean, maxside) in pairs) { c.onFrame(t, mean, maxside); t += 300 }
        return t
    }
    private val stand = List(3) { 162f to 163f }
    /** 양 무릎이 함께 굽는 스쿼트: 평균 162→95→162, 더 편 무릎 163→100→163. */
    private val squat = listOf(150f to 152f, 120f to 125f, 95f to 100f, 100f to 104f, 130f to 135f, 155f to 158f, 161f to 162f, 162f to 163f, 162f to 163f)
    /** 한쪽 무릎 들기: 평균은 162→100 으로 같은 폭이 흔들리지만 더 편 무릎은 158~163 그대로. */
    private val march = listOf(150f to 161f, 110f to 160f, 100f to 158f, 140f to 161f, 160f to 162f, 162f to 163f, 162f to 163f)

    @Test
    fun squatIdentityGateRejectsSingleLegKneeRaise() {
        val c = RepCounter.forSession("바벨 스쿼트", floor = false)!!
        assertEquals("knee_maxside", c.signal.identityFeature)
        assertEquals(35f, c.signal.identityMinAmp!!, 1e-6f)
        var t = feed(c, 0L, stand + squat)
        assertEquals(1, c.reps)
        t = feed(c, t, march)
        assertEquals("무릎 들기는 스쿼트가 아니다 — 세지 않는다", 1, c.reps)
        assertEquals(1, c.rejectedReps.size)
        val r = c.rejectedReps.single()
        assertTrue("swing=${r.identitySwing}", r.identitySwing < 35f)
        assertTrue("min=${r.min}", r.min <= 115f)   // 카운트 신호는 사이클을 냈다(3점 평활로 바닥 100 → 110) — 기각 사유는 판별 신호
        feed(c, t, squat)
        assertEquals(2, c.reps)
        // 센 사이클의 판별 스윙은 repTimesMs 와 나란히, 전부 게이트 이상
        assertEquals(2, c.identitySwings.size)
        c.identitySwings.forEach { assertTrue("swing=$it", it != null && it >= 35f) }
        // 기각은 불응기·주기 추정을 건드리지 않는다(반복이 아니었으므로)
        assertEquals(2, c.repTimesMs.size)
    }

    @Test
    fun identityGateAbstainsWhenIdentitySignalIsMissing() {
        // 판별 신호가 안 잡히면(무릎 하나 가림) 판정하지 않고 종전처럼 센다 — 모르는 것을 기각으로 만들지 않는다(원칙 #1)
        val c = RepCounter.forSession("바벨 스쿼트", floor = false)!!
        feed(c, 0L, (stand + squat).map { it.first to null })
        assertEquals(1, c.reps)
        assertEquals(listOf<Float?>(null), c.identitySwings)
        assertTrue(c.rejectedReps.isEmpty())
        // 두 인자 호출(재생기·기존 테스트)도 같은 뜻이다
        val c2 = RepCounter.forSession("바벨 스쿼트", floor = false)!!
        var t = 0L
        for ((mean, _) in stand + squat) { c2.onFrame(t, mean); t += 300 }
        assertEquals(1, c2.reps)
    }

    @Test
    fun onlySquatHasAnIdentityGate() {
        // 런지류는 한쪽 무릎만 굽는 종목 — 게이트를 붙이면 반복을 버린다. 그 외 종목도 종전 그대로.
        for (ex in listOf("바벨 런지", "스텝 포워드 다이나믹 런지", "덤벨 컬", "바벨 데드리프트", "푸시업")) {
            val sig = RepSignals.byExercise.getValue(ex)
            assertNull(ex, sig.identityFeature)
        }
        // 비교 신호를 따로 두는 종목(런지)의 비교 사본에는 판별 게이트가 붙지 않는다. 스쿼트의 비교 신호는 자기 자신(assertSame 계약)이지만
        // 비교 추적기는 판별 값을 주지 않는 두 인자 onFrame 을 쓰므로 게이트가 동작하지 않는다.
        assertNull(RepSignals.byExercise.getValue("스텝 포워드 다이나믹 런지").comparisonSignal().identityFeature)
    }

    @Test
    fun identityGateAppliesPerPublishedCycleOnTheNewCore() {
        // 새 코어는 첫 두 사이클을 함께 발표한다 — 판별은 사이클마다: 스쿼트 + 무릎 들기 쌍이면 스쿼트만 센다
        val sig = RepSignals.byExercise.getValue("바벨 스쿼트").copy(polarity = RepPolarity.DOWN)
        val c = RepCounter(sig, maxGapMs = 1500L, completeOnReturn = true)
        assertTrue(c.usesHysteresis)
        val frames = listOf(162f to 163f, 162f to 163f, 120f to 125f, 95f to 100f, 100f to 104f, 135f to 140f, 150f to 155f,   // 스쿼트: 발화 t=1800(보류)
            162f to 163f, 120f to 160f, 100f to 159f, 140f to 161f, 155f to 162f)                                          // 무릎 들기: 발화 t=3300 → 쌍 발표
        feed(c, 0L, frames)
        assertEquals(1, c.reps)
        assertEquals(listOf(1800L), c.repTimesMs)
        assertEquals(1, c.rejectedReps.size)
        assertEquals(3300L, c.rejectedReps.single().tMs)
        assertEquals(1, c.newlyPublished.size)
        // 코어의 발표 기록에는 둘 다 남는다(세는 것은 통과한 사이클뿐)
        assertEquals(2, c.publishedReps.size)
    }

    @Test
    fun medianPeriodIsRobustToOneOutlierGap() {
        // §29 기록 모드 템포: 렙 사이에 휴식 하나가 끼어도 중앙값은 흔들리지 않는다 (EMA 는 끌려간다)
        assertEquals(3000L, RepMetrics.medianPeriodMs(listOf(0L, 3_000L, 6_000L, 9_000L, 60_000L)))
        assertEquals(2500L, RepMetrics.medianPeriodMs(listOf(0L, 2_000L, 5_000L)))   // 짝수 개 간격 = 가운데 평균
        assertNull(RepMetrics.medianPeriodMs(listOf(5_000L)))
        assertNull(RepMetrics.medianPeriodMs(emptyList()))
    }
}
