package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 라잉 레그 레이즈 바닥 카운터(spec §99) — 앱 세션 구성(forSession(floor = true))에 300 ms 프레임 피처를 넣는다.
 * 카운트 신호 hip_ang(양측 중점), 판별 신호 hip_ang_maxside(더 편 쪽) 20°.
 */
class LegRaiseCounterTest {
    private class Session {
        val rc = RepCounter.forSession("라잉 레그 레이즈", floor = true)!!
        var t = 0L
        fun frame(hip: Float, maxside: Float?) {
            rc.onFrameFeatures(t, buildMap { put("hip_ang", hip); maxside?.let { put("hip_ang_maxside", it) } }); t += 300
        }
        fun rest(n: Int = 6, maxside: Float? = 176f) = repeat(n) { frame(175f, maxside) }
        /** 한 회: 양다리면 둘 다, 한 다리면 중점만 절반쯤 움직이고 더 편 쪽은 바닥 다리 그대로. 먼 무릎이 안 보이는 회는 뒤의 쉬는 프레임에서도 안 보인다
         *  (복귀형 v1 은 복귀 뒤 둘째 쉬는 프레임에서 회를 닫으므로, 쉬는 프레임에 판별 표본이 있으면 그 두 표본이 창을 판정한다). */
        fun rep(both: Boolean, maxsideVisible: Boolean = true) {
            for (h in listOf(160f, 135f, 110f, 95f, 95f, 110f, 135f, 160f, 175f)) {
                val mid = if (both) h else 175f - (175f - h) / 2f
                frame(mid, if (!maxsideVisible) null else if (both) h + 1f else 176f)
            }
            rest(4, if (maxsideVisible) 176f else null)
        }
    }

    @Test fun bothLegsRaisedCount() {
        val s = Session(); s.rest(); repeat(4) { s.rep(both = true) }
        assertEquals(4, s.rc.reps)
        assertTrue(s.rc.rejectedReps.isEmpty())
    }

    @Test fun oneLegRaisedIsNotALegRaiseAndSaysWhy() {
        val s = Session(); s.rest(); repeat(3) { s.rep(both = false) }
        assertEquals(0, s.rc.reps)
        assertEquals(3, s.rc.rejectedReps.size)
        assertEquals("두 다리를 함께 들어 주세요", s.rc.signal.identityCue)
    }

    @Test fun unseenFarKneeIsNotJudgedSoTheRepCounts() {
        // 판별 표본이 2개 미만이면 판정하지 않고 센다(원칙 #1 — 모르는 것을 기각으로 만들지 않는다)
        val s = Session(); repeat(6) { s.frame(175f, null) }; s.rep(both = false, maxsideVisible = false)
        assertEquals(1, s.rc.reps)
        assertNull(s.rc.identitySwings.single())
    }

    @Test fun floorPreparationSeedsTheLyingBaseline() {
        // §99 준비 프레임(누운 자세 1.5 s)으로 기준을 심으면 첫 프레임부터 다리를 들어도 첫 회를 센다. 심지 않으면 기준을 잡기 전에 움직여 놓친다
        val prep = (0 until 5).map { it * 300L to mapOf("hip_ang" to 175f, "hip_ang_maxside" to 176f) }
        val seeded = Session().apply { t = 1500L }
        seeded.rc.standingSeedFrom(prep, seeded.t)?.let { seeded.rc.seedStanding(seeded.t, it) }
        seeded.rep(both = true)
        assertEquals(1, seeded.rc.reps)
        val unseeded = Session().apply { t = 1500L }
        unseeded.rep(both = true)
        assertEquals(0, unseeded.rc.reps)
    }

    @Test fun groundSignalsAreNotSeeded() {
        // 크런치 head_ground 는 준비 전용 추출기와 세트 추출기의 접지선이 달라 심지 않는다
        val rc = RepCounter.forSession("크런치", floor = true)!!
        val prep = (0 until 5).map { it * 300L to mapOf("head_ground" to 0.05f) }
        assertNull(rc.standingSeedFrom(prep, 1500L))
    }
}
