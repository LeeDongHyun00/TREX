package com.example.trex_kotlin

import org.junit.Assert.*
import org.junit.Test

/** 앱 내 업데이트 순수 로직 — 버전 비교·BUILD_INFO 파싱·해시·확인 주기·본문 요약. */
class AppUpdateTest {
    private val buildInfo = """
        {
          "versionName": "1.3.0-preview.6",
          "versionCode": 11,
          "commit": "abc",
          "apkSHA256": "599A7162C792AEF9C98C3E83ABAE356226BF4CA1A90415B007C31FC87CE79819",
          "certificateSHA256": "3027771596e23100d4203da03cd44c4a81f5e3d4cf197fdb7fb2ba4e4dc751ef"
        }
    """.trimIndent()

    @Test fun parsesBuildInfoAndComparesVersionCodes() {
        val info = AppUpdateLogic.parseBuildInfo(buildInfo)!!
        assertEquals("1.3.0-preview.6", info.versionName); assertEquals(11L, info.versionCode)
        assertEquals("599a7162c792aef9c98c3e83abae356226bf4ca1a90415b007c31fc87ce79819", info.apkSha256)
        assertEquals("3027771596e23100d4203da03cd44c4a81f5e3d4cf197fdb7fb2ba4e4dc751ef", info.certificateSha256)
        assertTrue(AppUpdateLogic.isNewer(info.versionCode, 10L)); assertFalse(AppUpdateLogic.isNewer(info.versionCode, 11L)); assertFalse(AppUpdateLogic.isNewer(10L, 11L))
        assertNull("versionCode 없는 릴리스는 모른다", AppUpdateLogic.parseBuildInfo("{\"versionName\":\"1.0\"}"))
        val old = AppUpdateLogic.parseBuildInfo("{\"versionName\":\"1.1.0-preview.2\",\"versionCode\":3}")!!
        assertNull(old.apkSha256); assertNull(old.certificateSha256)
        assertTrue(AppUpdateLogic.isApk("TREX-1.3.0-preview.6.apk")); assertFalse(AppUpdateLogic.isApk("SHA256SUMS.txt"))
    }

    @Test fun checkIntervalAndSnooze() {
        val h6 = 6 * 60 * 60 * 1000L
        assertTrue(AppUpdateLogic.checkDue(now = 10 * h6, lastCheckAt = 0L, intervalMs = h6))
        assertFalse(AppUpdateLogic.checkDue(now = 10 * h6, lastCheckAt = 10 * h6 - 1, intervalMs = h6))
        assertTrue("시계가 뒤로 갔으면 다시 확인", AppUpdateLogic.checkDue(now = 100L, lastCheckAt = 10 * h6, intervalMs = h6))
        assertFalse(AppUpdateLogic.promptDue(now = 5L, snoozedUntil = 6L)); assertTrue(AppUpdateLogic.promptDue(now = 6L, snoozedUntil = 6L))
    }

    @Test fun sha256HexIsLowercaseAndSummaryKeepsBulletsOnly() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", AppUpdateLogic.sha256Hex(ByteArray(0)))
        val body = "# Android 체험판 1.3.0-preview.6\n\n2026-10-06 · 브랜치 `redesign`\n\n## 한 다리 계열\n\n- **얕은 회**는 세지 않아요. [설계](docs/x.md) 참고.\n- 허리를 숙이면 세지 않아요\n| 표 | 줄 |\n```\ncode\n```\n"
        val s = AppUpdateLogic.summary(body)
        assertEquals("· 2026-10-06 · 브랜치 `redesign`\n· 얕은 회는 세지 않아요. 설계 참고.\n· 허리를 숙이면 세지 않아요", s)
        assertEquals("새 체험판이 올라왔어요", AppUpdateLogic.summary("# 제목만\n"))
    }
}
