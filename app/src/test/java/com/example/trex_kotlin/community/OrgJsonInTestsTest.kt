package com.example.trex_kotlin.community

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 단위 테스트의 org.json 은 실제 구현이다(커뮤니티 API 파싱을 JVM 에서 검증하려고) — Android 스텁이면 "not mocked" 로 죽는다. */
class OrgJsonInTestsTest {
    @Test
    fun 실제_org_json으로_읽고_쓴다() {
        assertEquals(1, JSONObject("{\"a\":1}").getInt("a"))
        assertEquals("{\"b\":\"한글\"}", JSONObject().put("b", "한글").toString())
    }
}
