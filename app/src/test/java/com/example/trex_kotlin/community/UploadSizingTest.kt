package com.example.trex_kotlin.community

import org.junit.Assert.assertEquals
import org.junit.Test

/** 올릴 사진 크기 — 긴 변 1080 으로 줄이고 비율은 지킨다, 작으면 그대로(설계 §5 글쓰기, 계획 2 Review Focus 2). */
class UploadSizingTest {
    @Test
    fun 긴_변_1080으로_맞춘다() {
        assertEquals(1080 to 810, UploadSizing.fit(4000, 3000, 1080))
        assertEquals(810 to 1080, UploadSizing.fit(3000, 4000, 1080))
        assertEquals(800 to 600, UploadSizing.fit(800, 600, 1080))
        assertEquals(1080 to 1, UploadSizing.fit(1081, 1, 1080))
        assertEquals(1080 to 1080, UploadSizing.fit(1080, 1080, 1080))
    }
}
