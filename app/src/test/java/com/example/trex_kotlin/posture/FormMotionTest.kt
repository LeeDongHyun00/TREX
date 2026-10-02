package com.example.trex_kotlin.posture

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 화살표 어휘 점검(docs/LIVE_SCREEN_REDESIGN.md §3.4) — 말하는 검사(ship)는 전부 "어디서 어느 쪽으로" 를 갖는다.
 * 빠지면 음성은 나오는데 화면은 부위만 붉고 방향이 없다. beta 는 화살표가 없어야 한다(원칙 #2 — 말하지 않는 판정이 몸을 움직이면 안 된다).
 */
class FormMotionTest {
    @Test
    fun everyShipCheckHasAMotionForEachDirectionItCanFire() {
        for ((ex, checks) in RepFormSpecs.byExercise) for (c in checks) {
            if (!c.ship) { assertNull("$ex ${c.id}: beta 는 화살표 없음", c.motion); continue }
            val m = c.motion
            assertNotNull("$ex ${c.id}: 화살표 어휘", m)
            if (c.hi != null) assertNotNull("$ex ${c.id}: 높은 쪽 화살표", m!!.kindFor(FormDirection.HIGH))
            if (c.lo != null) assertNotNull("$ex ${c.id}: 낮은 쪽 화살표", m!!.kindFor(FormDirection.LOW))
        }
    }

    @Test
    fun windowRulesOnlyDrawWhatTheyCanSee() {
        assertNotNull(FormMotion.forWindowRule("torso_incl__mean"))
        assertNull(FormMotion.forWindowRule("head_yaw__std"))
    }
}
