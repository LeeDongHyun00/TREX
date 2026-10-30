package com.example.trex_kotlin

import org.junit.Assert.assertEquals
import org.junit.Test

/** 제어판 세트 칸(스펙 §115) — 세션 단계의 세트 표기("1 / 2 세트")를 짧은 분수로, 모르는 형식은 그대로. */
class SessionControlPanelTest {
    @Test fun setLabelBecomesShortFraction() {
        assertEquals("1/2", setFraction("1 / 2 세트"))
        assertEquals("12/15", setFraction("12 / 15 세트"))
        assertEquals("3/3", setFraction(SessionStep(0, SessionPhase.WORK, todayPlan[0], todayPlan[0].id, 0, 3, 3, 30).setLabel))
    }

    @Test fun unknownLabelStaysAsIs() {
        assertEquals("전신", setFraction("전신"))
        assertEquals("", setFraction(""))
    }
}
