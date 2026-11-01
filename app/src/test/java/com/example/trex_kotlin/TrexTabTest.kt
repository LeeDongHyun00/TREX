package com.example.trex_kotlin

import org.junit.Assert.assertEquals
import org.junit.Test

/** 하단 바 탭 순서 — 커뮤니티는 식단과 내 정보 사이(계획 3 Global Constraints). */
class TrexTabTest {
    @Test
    fun 탭_순서() {
        assertEquals(listOf("홈", "운동", "식단", "커뮤니티", "내 정보"), TrexTab.entries.map { it.label })
    }
}
