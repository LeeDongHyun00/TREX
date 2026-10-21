package com.example.trex_kotlin.posture

import org.junit.Assert.assertEquals
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
    fun windowRulesDrawByRuleIdTableAndDipsLeanIsAMarkNotAnUpArrow() {
        // §101: 창 규칙은 규칙 id 별 표 — 종전 접두사 화이트리스트는 딥스 '상체 살짝 숙임 유지'(음성 "앞으로 숙이세요")에 어깨 위 화살표를 그렸다(10-08 RC3)
        assertEquals(MotionKind.UP, WindowMotions.kindFor("바벨 스쿼트|척추의 중립[all]", Direction.PRIMARY))
        assertEquals(MotionKind.MARK, WindowMotions.kindFor("딥스|상체 살짝 숙임 유지", Direction.PRIMARY))
        assertEquals(MotionKind.AWAY_MIDLINE, WindowMotions.kindFor("바벨 데드리프트|발과 무릎의 방향 일치", Direction.PRIMARY))
        assertEquals(MotionKind.TOWARD_MIDLINE, WindowMotions.kindFor("바벨 데드리프트|발과 무릎의 방향 일치", Direction.OPPOSITE))
        assertNull("반대 방향 띠가 없는 규칙은 그 방향에 화살표 없음", WindowMotions.forRule("바벨 스쿼트|척추의 중립[all]", Direction.OPPOSITE))
        assertNull("표에 없는 규칙은 문장만", WindowMotions.forRule("굿모닝|없는 규칙", Direction.PRIMARY))
    }
}
