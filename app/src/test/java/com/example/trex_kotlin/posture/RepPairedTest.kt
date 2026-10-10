package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 팔별 카운터(spec §62c, 설계 §22) — 덤벨 컬. 동시 컬은 사이클마다 1회, 교대 컬은 왼 + 오른 = 1회, 한 팔 가림은 보이는 팔로,
 * 본인 기준 비율 ROM 이 부분 반복을 가르고, 기각 게이트(몸통·상완 스윙)가 '컬 아님' 을 세지 않는지를 잠근다.
 */
class RepPairedTest {

    private fun counter() = RepCounter.forSession("덤벨 컬", floor = false)!!

    /**
     * 팔꿈치각 한 사이클(복귀형 추적기 규약): 이완 3프레임(앵커) → 하강 → 최소 [bottom] 3프레임 → 복귀 3프레임. 300 ms 간격.
     * 카운터의 3점 중앙값 평활 때문에 최소·복귀가 각각 3프레임은 돼야 평활값이 거기까지 닿는다(2프레임이면 다음 사이클 첫 프레임에서 닫힌다).
     */
    private fun cycle(bottom: Float, top: Float = 165f): List<Float> {
        val mid = if (bottom < 100f) minOf(100f, bottom + 20f) else (bottom + 140f) / 2f
        return listOf(top, top, top, 140f, mid, bottom, bottom, bottom, mid, 140f, top, top, top)
    }

    private class Feed(val rc: RepCounter) {
        var t = 0L
        val fired = ArrayList<Long>()
        /** 마지막으로 회가 완료된 프레임의 ROM 판정 — `lastCycleValid` 는 프레임마다 비워지므로 발화 순간에 잡는다. */
        var lastValid: Boolean? = null
        /** [wl]/[wr] = 팔별 손목 높이(없으면 [wrist] 를 양팔에), [yaw] = 어깨 요(°, 방향 피처 — 먼 팔 판정 §101; null 이면 방향 피처 없음). */
        fun frame(l: Float?, r: Float?, torso: Float = 0.02f, upperarm: Float = 175f, extra: Map<String, Float> = emptyMap(), wrist: Float? = null,
                  wl: Float? = wrist, wr: Float? = wrist, yaw: Float? = null) {
            val f = HashMap<String, Float>(extra)
            wl?.let { f[Arm2d.WRIST_H_L] = it }; wr?.let { f[Arm2d.WRIST_H_R] = it }
            yaw?.let { val rad = Math.toRadians(it.toDouble()); f[ViewEstimator.FEAT_COS] = kotlin.math.cos(rad).toFloat(); f[ViewEstimator.FEAT_SIN] = kotlin.math.sin(rad).toFloat() }
            l?.let { f["elbow_L"] = it }; r?.let { f["elbow_R"] = it }
            val ms = listOfNotNull(l, r); if (ms.isNotEmpty()) f["elbow_minside"] = ms.min()
            f[Arm2d.TORSO_TILT] = torso; f["upperarm_vert_L"] = upperarm; f["upperarm_vert_R"] = upperarm
            if (rc.onFrameFeatures(t, f)) { fired += t; lastValid = rc.lastCycleValid }
            t += 300
        }
    }

    @Test
    fun simultaneousCurlCountsEveryCycleAndJudgesRomAgainstOwnFirstThree() {
        val rc = counter(); val f = Feed(rc)
        assertTrue(rc.paired); assertTrue(rc.signal.romExcludesShort)
        // 첫 세 사이클(진폭 85·85·85)의 중앙값이 기준 A0 = 85 → 유효 문턱 max(0.7×85, 45) = 59.5. 새 코어는 첫 두 사이클을 둘째가 끝날 때 함께 발표한다
        cycle(80f).forEach { f.frame(it, it) }
        assertEquals("첫 사이클부터 바로 센다 — 시작 확정 없음(0 → 2 로 뛰지 않는다, §62c 후속 9)", 1, rc.reps)
        cycle(80f).forEach { f.frame(it, it) }
        assertEquals(2, rc.reps)
        assertNull("기준을 이루는 사이클은 판정하지 않는다", f.lastValid)
        assertEquals("셋째 사이클까지가 기준", null to null, rc.armReference)
        cycle(80f).forEach { f.frame(it, it) }
        assertEquals(3, rc.reps); assertNull(f.lastValid)
        assertEquals(85f to 85f, rc.armReference)
        cycle(95f).forEach { f.frame(it, it) }            // 진폭 70 ≥ 59.5 → 유효
        assertEquals(4, rc.reps); assertEquals(true, f.lastValid)
        cycle(120f).forEach { f.frame(it, it) }           // 진폭 45 < 59.5 → 부분(세긴 세되 valid=false — 화면 수는 PostureLive 가 뺀다)
        assertEquals(5, rc.reps); assertEquals(false, f.lastValid)
        assertEquals(5, rc.armCycles.count { it.arm == 'L' }); assertEquals(5, rc.armCycles.count { it.arm == 'R' })
        assertTrue(rc.rejectedReps.isEmpty()); assertEquals(0, rc.armOrphans)
    }

    @Test
    fun aLoneFirstRepIsRetractedWhenNoSecondRepFollowsWithinTheWindow() {
        // §62c 후속 9: 첫 회는 바로 센다(0 → 2 로 뛰지 않게) — 대신 8 s 안에 둘째 회가 없으면 준비 동작으로 보고 거둔다(MM-Fit 세트 첫머리 준비 동작)
        val rc = counter(); val f = Feed(rc)
        cycle(80f).forEach { f.frame(it, it) }
        assertEquals(1, rc.reps)
        repeat(30) { f.frame(165f, 165f) }                 // 9 s 쉼(팔을 내린 채)
        assertEquals("둘째 회가 없어 첫 회를 거뒀다", 0, rc.reps)
        assertEquals(1, rc.retractedReps.size)
        assertEquals("그 회로 세운 ROM 기준도 지웠다", null to null, rc.armReference)
        cycle(80f).forEach { f.frame(it, it) }
        assertEquals("다음 실제 회가 새 첫 회", 1, rc.reps)
        cycle(80f).forEach { f.frame(it, it) }
        assertEquals(2, rc.reps)
        repeat(30) { f.frame(165f, 165f) }
        assertEquals("둘째 회가 창 안에 왔으면 첫 회는 확정 — 그 뒤 쉬어도 거두지 않는다", 2, rc.reps)
        assertEquals(1, rc.retractedReps.size)
    }

    @Test
    fun alternatingCurlCountsOnePerLeftRightPair() {
        val rc = counter(); val f = Feed(rc)
        // 왼팔이 굽는 동안 오른팔은 펴 있음(165), 그다음 오른팔 — 두 팔 평균 신호였다면 절반만 움직인다
        cycle(80f).forEach { f.frame(it, 165f) }
        cycle(80f).forEach { f.frame(165f, it) }
        assertEquals("왼 + 오른 첫 사이클 = 1회", 1, rc.reps)
        cycle(80f).forEach { f.frame(it, 165f) }
        assertEquals("왼팔 둘째 사이클만으로는 회가 안 된다", 1, rc.reps)
        cycle(80f).forEach { f.frame(165f, it) }
        assertEquals("오른팔 둘째 사이클 → 2회", 2, rc.reps)
        assertEquals(4, rc.armCycles.size)
        cycle(80f).forEach { f.frame(it, 165f) }
        cycle(80f).forEach { f.frame(165f, it) }
        assertEquals(3, rc.reps)
        assertEquals("교대 컬은 순서로 짝짓는다 — 조각 없음", 0, rc.armOrphans)
    }

    @Test
    fun simultaneousCurlDropsAnUnpairedFragmentInsteadOfDesynchronizingThePairs() {
        // 폰 2026-09-25 15:34 세트 9회: 왼팔만 이중 굴곡(작은 조각 사이클 뒤 진짜 사이클). 순서로 짝지으면 조각이 오른팔 사이클과 묶이고 그 뒤 모든 회가 한 사이클씩 어긋나
        // 마지막 부분 반복이 유효로 둔갑했다 — 동시 컬은 겹치는 사이클끼리 짝짓고 겹치지 않는 앞선 조각은 버린다
        val rc = counter(); val f = Feed(rc)
        repeat(3) { cycle(80f).forEach { f.frame(it, it) } }
        assertEquals(3, rc.reps)
        cycle(115f).forEach { f.frame(it, 165f) }         // 왼팔 조각(진폭 50 — 부분) — 오른팔은 펴 있음
        assertEquals("조각만으로는 회가 안 된다", 3, rc.reps)
        cycle(80f).forEach { f.frame(it, it) }            // 진짜 사이클(양팔)
        assertEquals(4, rc.reps)
        assertEquals("겹치는 양팔 사이클이 짝이 됐다 — 조각(부분)이 아니라", true, f.lastValid)
        assertEquals(1, rc.armOrphans)
        val orphan = rc.armCycles.single { it.orphan }
        assertEquals('L', orphan.arm); assertEquals(50f, orphan.amp, 0.01f); assertEquals(false, orphan.valid)
        assertTrue(rc.rejectedReps.isEmpty())
    }

    @Test
    fun aFragmentWaitsForTheLaggingArmsCycleBeforeBeingDropped() {
        // 동시 컬에서 한 팔이 한 프레임 늦으면 오른팔 사이클이 먼저 끝나는 프레임에 왼팔 진짜 사이클은 아직 돌아오는 중 — 조각을 그 자리에서 오른팔과 짝지으면 안 된다
        val rc = counter(); val f = Feed(rc)
        repeat(3) { cycle(80f).forEach { f.frame(it, it) } }
        cycle(115f).forEach { f.frame(it, 165f) }
        val seq = cycle(80f)
        for (i in seq.indices) f.frame(seq.getOrElse(i - 1) { 165f }, seq[i])
        f.frame(165f, 165f)
        assertEquals(4, rc.reps); assertEquals(true, f.lastValid); assertEquals(1, rc.armOrphans)
    }

    @Test
    fun anArmUnseenLongerThanTheAbsenceWindowRejectsTheOtherArmsCycleAsUnseenPair() {
        // §101(10-08): 종전에는 2.5 s 넘게 안 보인 팔 자리에 보이는 팔 사이클을 **복사**해 1회로 셌다 — 그 팔이 움직였다는 근거 없이(왼팔만 한 회가 화면 3·4 를 올림).
        // 이제 그 사이클은 '못 본 짝' 으로 기각한다(arm_unseen_R) — 세지 않고 이유를 말한다(PairedArmCues). 사용자 결정 U1
        val rc = counter(); val f = Feed(rc)
        repeat(2) { cycle(80f).forEach { f.frame(it, it) } }   // 양팔 두 사이클 = 2회(첫 회 확정)
        cycle(80f).forEach { f.frame(it, null) }           // 오른팔이 팔꿈치도 손목도 안 보임
        cycle(80f).forEach { f.frame(it, null) }
        assertEquals("보이는 팔만으로는 회가 되지 않는다", 2, rc.reps)
        assertEquals(2, rc.armCycles.count { it.arm == 'R' })
        assertEquals(4, rc.armCycles.count { it.arm == 'L' })
        val unseen = rc.rejectedReps.filter { it.feature == "arm_unseen_R" }
        assertEquals(2, unseen.size)
        assertEquals(2, rc.armCycles.count { it.arm == 'L' && it.orphan && it.src == 'U' })
        assertEquals("촬영 안내 문장이 있다", "오른팔이 화면에 안 보여 세지 않았어요. 휴대폰 쪽으로 조금 돌아서 주세요.", rc.cueSource!!.cueFor("arm_unseen_R"))
        assertFalse("못 본 팔은 '교정' 대상이 아니다", rc.cueSource!!.recovers("arm_unseen_R"))
        assertNotNull("대스윙 기각에도 이제 문장이 있다", rc.cueSource!!.cueFor("upperarm_vert_L"))
    }

    /** 사선 D(먼 팔 = 오른팔) 손목 높이 한 사이클 — 어깨 쪽이 높다(−0.1), 내리면 −0.9. [cycle] 과 같은 길이. */
    private fun wristOf(v: Float, bottom: Float = 80f, wTop: Float = -0.10f, wBottom: Float = -0.90f) = wBottom + (165f - v) / (165f - bottom) * (wTop - wBottom)

    @Test
    fun farArmWithHiddenElbowIsCountedFromItsWristInObliqueView() {
        // §101 먼 팔 3상태 — 사선에서 먼 팔꿈치가 가시성 0.5 아래로 빠져도 손목은 남는다: 손목 높이 사이클(진폭 ≥ 0.35, 꼭대기 ≥ −0.45)로 그 팔을 센다
        val rc = counter(); val f = Feed(rc)
        // D 사선(요 −40°): 왼팔이 카메라 쪽, 오른팔이 먼 팔. 오른 팔꿈치는 내내 없고 오른 손목만 움직인다
        repeat(3) { cycle(80f).forEach { v -> f.frame(v, null, wl = wristOf(v), wr = wristOf(v), yaw = -40f) } }
        assertEquals("동시 컬 3회 — 오른팔은 손목으로", 3, rc.reps)
        assertEquals(3, rc.armCycles.count { it.arm == 'R' && it.src == 'W' })
        assertTrue("손목 사이클은 팔꿈치 극값이 없다", rc.armCycles.filter { it.src == 'W' }.all { it.min.isNaN() })
        assertTrue(rc.rejectedReps.none { it.feature?.startsWith("arm_unseen") == true })
        // 교대: 왼팔 사이클 뒤 오른 손목 사이클 → 1회
        cycle(80f).forEach { v -> f.frame(v, null, wl = wristOf(v), wr = -0.90f, yaw = -40f) }
        cycle(80f).forEach { v -> f.frame(165f, null, wl = -0.90f, wr = wristOf(v), yaw = -40f) }
        assertEquals(4, rc.reps)
    }

    @Test
    fun aStillFarArmDoesNotMakeTheNearArmsCyclesReps() {
        // §101: 먼 팔꿈치는 안 보이지만 손은 보이고 **가만히** 있다(10-08 구간 A 의 왼팔만 회) — 회가 되지 않고, 같은 팔만 3번 이어지면 '한 팔만' 화면 안내
        val rc = counter(); val f = Feed(rc)
        repeat(3) { cycle(80f).forEach { v -> f.frame(v, null, wl = wristOf(v), wr = -0.90f, yaw = -40f) } }
        assertEquals("오른손이 가만히 있다 — 왼팔 사이클만으로는 0회", 0, rc.reps)
        assertTrue("오른손이 보이니 '못 본 짝' 도 아니다", rc.rejectedReps.isEmpty())
        assertEquals('L', rc.takeOneArmNotice()); assertNull("한 번만", rc.takeOneArmNotice())
        assertEquals("오른팔 차례예요. 양팔을 한 번씩 해야 1회예요.", PairedArmCues.oneArmNote('L'))
        // 그 뒤 동시 회가 오면 쌓인 왼팔 반쪽은 조각으로 버리고 그 회만 센다(큐 전체 조각 탐색)
        cycle(80f).forEach { v -> f.frame(v, null, wl = wristOf(v), wr = wristOf(v), yaw = -40f) }
        assertEquals(1, rc.reps)
        assertEquals(3, rc.armOrphans)
    }

    @Test
    fun wristFallbackIsOffInFrontalView() {
        // §101: 정면(C)에는 '먼 팔' 이 없다 — 팔꿈치가 안 보이면 손목이 보여도 그 팔은 못 본 것(못 본 짝)
        val rc = counter(); val f = Feed(rc)
        repeat(2) { cycle(80f).forEach { v -> f.frame(v, null, wl = wristOf(v), wr = wristOf(v), yaw = 0f) } }
        assertEquals(0, rc.reps)
        assertTrue(rc.armCycles.none { it.src == 'W' })
        assertTrue(rc.rejectedReps.any { it.feature == "arm_unseen_R" })
    }

    @Test
    fun torsoSwingRejectsTheRepAsNotACurl() {
        val rc = counter(); val f = Feed(rc)
        cycle(80f).forEach { f.frame(it, it) }
        assertEquals(1, rc.reps)
        // 둘째 사이클에서 몸통을 뒤로 젖히며(2D 기울기 비 −0.35) 들어 올림 → 그 팔 사이클 구간의 스윙 > 0.30 → 기각. 첫 회(깨끗함)는 이미 셌다
        val frames = cycle(80f)
        frames.forEachIndexed { i, v -> f.frame(v, v, torso = if (i in 3..8) -0.35f else 0.02f) }
        assertEquals(1, rc.reps)
        assertEquals(1, rc.rejectedReps.size)
        assertEquals(Arm2d.TORSO_TILT, rc.rejectedReps[0].feature)
        assertTrue(rc.rejectedReps[0].identitySwing > 0.30f)
        // 다음 정상 회는 다시 센다(기각 창은 소비됐다)
        cycle(80f).forEach { f.frame(it, it) }
        assertEquals(2, rc.reps)
    }

    @Test
    fun tooShallowReferenceLeavesRomUnjudged() {
        val rc = counter(); val f = Feed(rc)
        for (b in listOf(125f, 125f, 125f, 125f)) cycle(b).forEach { f.frame(it, it) }   // 진폭 40 < 기준 하한 50 → 기준 미확보
        assertEquals(4, rc.reps)
        assertNull(f.lastValid)
        assertTrue(rc.armReference.first!!.isNaN())
    }

    @Test
    fun resetClearsArmStateAndEngineLogCarriesThePairedConfig() {
        val rc = counter(); val f = Feed(rc)
        cycle(80f).forEach { f.frame(it, it) }
        rc.reset()
        assertEquals(0, rc.reps); assertTrue(rc.armCycles.isEmpty()); assertEquals(null to null, rc.armReference)
        val e = RepEngineLog.of(rc)
        assertEquals("elbow_L" to "elbow_R", e.pairedFeatures)
        assertEquals(mapOf(Arm2d.TORSO_TILT to 0.30f, "upperarm_vert_{side}" to 60f), e.rejectFeatures)
        assertEquals(0.7f, e.romRatio)
        // 팔별이 아닌 종목은 종전과 같다
        val squat = RepCounter.forSession("바벨 스쿼트", floor = false)!!
        assertFalse(squat.paired)
        assertNull(RepEngineLog.of(squat).pairedFeatures)
    }

    @Test
    fun wristHeightAmplitudeIsTheSecondRomJudgeInFrontalView() {
        // B4: 정면에서 2D 손목 창 진폭 비율 0.8 이 월드 각 비율 0.7 보다 진실에 가깝다 — 실기기 짧은 회는 월드 0.87~1.01 로 통과했지만 손목 0.71~0.81
        val rc = counter(); val f = Feed(rc)
        // 손목 높이: 이완 −0.95 → 수축 −0.10 (진폭 0.85). 팔꿈치각과 같은 위상
        fun wristOf(v: Float, top: Float, bottom: Float, wTop: Float = -0.10f, wBottom: Float = -0.95f) = wBottom + (top - v) / (top - bottom) * (wTop - wBottom)
        fun run(bottom: Float, wTop: Float, wBottom: Float = -0.95f) { cycle(bottom).forEach { v -> f.frame(v, v, wrist = wristOf(v, 165f, bottom, wTop, wBottom)) } }
        run(80f, -0.10f); run(80f, -0.10f); run(80f, -0.10f)
        assertEquals(3, rc.reps)
        run(80f, -0.10f)                                   // 같은 진폭 → 유효
        assertEquals(4, rc.reps); assertEquals(true, f.lastValid)
        run(85f, -0.35f)                                   // 월드 진폭 80(0.94×) 은 통과하지만 손목 진폭 0.60(0.71×) < 0.8 → 부분
        assertEquals(5, rc.reps); assertEquals(false, f.lastValid)
        run(80f, -0.10f, -0.65f)                           // 다 안 폄: 손목 최저 −0.65 > −0.75 → 부분
        assertEquals(6, rc.reps); assertEquals(false, f.lastValid)
        val last = rc.armCycles.last()
        assertTrue(last.auxAmp != null && last.auxMin != null && last.auxMin!! > -0.75f)
    }

    @Test
    fun partialRepCarriesTheReasonForTheSpokenCue() {
        // 부분 회는 음성으로 사유를 말한다(spec §62c 후속 3) — 손목이 위 끝에 못 가면 '덜 올림', 아래 끝에 못 가면 '덜 폄'
        val rc = counter(); val f = Feed(rc)
        fun wristOf(v: Float, bottom: Float, wTop: Float, wBottom: Float) = wBottom + (165f - v) / (165f - bottom) * (wTop - wBottom)
        var shorts: List<RomShort?> = emptyList()
        fun run(bottom: Float, wTop: Float, wBottom: Float = -0.95f) {
            cycle(bottom).forEach { v -> f.frame(v, v, wrist = wristOf(v, bottom, wTop, wBottom)); if (rc.newlyPublishedShort.isNotEmpty()) shorts = rc.newlyPublishedShort }
        }
        repeat(3) { run(80f, -0.10f) }
        run(80f, -0.10f)
        assertEquals(listOf<RomShort?>(null), shorts)
        run(85f, -0.35f)                                   // 손목 진폭 0.60 < 0.8 × 0.85, 아래 끝은 닿음 → 덜 올림
        assertEquals(listOf<RomShort?>(RomShort.TOP), shorts)
        run(80f, -0.10f, -0.65f)                           // 손목 최저 −0.65 > −0.75 → 덜 폄
        assertEquals(listOf<RomShort?>(RomShort.BOTTOM), shorts)
        assertTrue(rc.signal.shortCue(RomShort.TOP).contains("올려"))
        assertTrue(rc.signal.shortCue(RomShort.BOTTOM).contains("펴"))
        assertEquals(rc.signal.invalidCue, rc.signal.shortCue(RomShort.RANGE))
    }
}
