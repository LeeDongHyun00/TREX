package com.example.trex_kotlin

import org.junit.Assert.assertEquals
import org.junit.Test

/** 설정 화면의 '버전' 표기(§119) — 스토어·업데이트 창이 말하는 버전 이름 뒤에 빌드 번호. */
class AppVersionLabelTest {
    @Test fun versionNameThenBuildNumber() {
        assertEquals("1.3.0 (1)", appVersionLabel("1.3.0", "1"))
        assertEquals("1.3.0-preview.7-dev (12)", appVersionLabel("1.3.0-preview.7-dev", "12"))
        assertEquals("빌드 번호를 모르면 버전 이름만", "1.3.0", appVersionLabel("1.3.0", ""))
    }
}
