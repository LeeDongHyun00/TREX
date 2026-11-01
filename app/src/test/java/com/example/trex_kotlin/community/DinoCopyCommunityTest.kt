package com.example.trex_kotlin.community

import org.junit.Assert.assertEquals
import org.junit.Test

/** 커뮤니티 앱 문구는 공룡 말투로 통일하되(사용자 결정 2026-10-29) 닉네임은 원문으로 남는다. */
class DinoCopyCommunityTest {
    @Test
    fun 닉네임이_들어간_제목은_고정_부분만_바뀐다() {
        // 닉네임이 '요'로 끝나는 말을 품어도 바뀌지 않는다
        assertEquals("안녕하세요 반가워님을 차단할까룡?", withName("안녕하세요 반가워", "님을 차단할까요?"))
    }
}
