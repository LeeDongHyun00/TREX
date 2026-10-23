package com.example.trex_kotlin.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRulesTest {

    private fun remote(key: String, at: Long, payload: String = "x") = RemoteItem(key, payload, at)

    @Test
    fun `서버가 비었으면 이 기기 기록을 올린다`() {
        assertEquals(FirstSync.UPLOAD_LOCAL, SyncRules.firstSync(localHasData = true, remote = emptyList()))
        // 빈 값(지운 항목)과 모르는 항목만 있으면 비어 있는 것과 같다
        assertEquals(FirstSync.UPLOAD_LOCAL, SyncRules.firstSync(true, listOf(remote("goal_override", 5, ""), remote("future_key", 5))))
    }

    @Test
    fun `이 기기가 비었으면 받고 양쪽에 있으면 묻는다`() {
        assertEquals(FirstSync.DOWNLOAD_REMOTE, SyncRules.firstSync(localHasData = false, remote = listOf(remote("profile", 1))))
        assertEquals(FirstSync.ASK, SyncRules.firstSync(localHasData = true, remote = listOf(remote("profile", 1))))
    }

    @Test
    fun `다른 계정의 기록이 있는 기기면 묻는다 - 기록이 없으면 평소대로`() {
        val empty = emptyList<RemoteItem>()
        assertEquals(FirstSync.SWITCH_ACCOUNT, SyncRules.firstSync(localHasData = true, remote = empty, otherAccountOnDevice = true))
        // 서버(새 계정)에 기록이 있어도 마찬가지 — 묻지 않고 올리면 앞 사람 기록이 섞이고, 받으면 이 기기 기록이 덮인다
        assertEquals(FirstSync.SWITCH_ACCOUNT, SyncRules.firstSync(true, listOf(remote("profile", 1)), otherAccountOnDevice = true))
        // 옮길 기록이 없으면 섞일 것도 없다
        assertEquals(FirstSync.UPLOAD_LOCAL, SyncRules.firstSync(localHasData = false, remote = empty, otherAccountOnDevice = true))
        assertEquals(FirstSync.DOWNLOAD_REMOTE, SyncRules.firstSync(false, listOf(remote("profile", 1)), otherAccountOnDevice = true))
    }

    @Test
    fun `이 기기 기록으로 올릴 때는 이미 받아 본 서버 값보다 새 버전으로 찍는다`() {
        // 이 기기 시계가 늦어도(지금 100) 서버 값(500)보다 새것이어야 409 로 뒤집히지 않는다
        assertEquals(LocalVersion(501, dirty = true), SyncRules.uploadVersion(null, now = 100, remoteUpdatedAt = 500))
        assertEquals(LocalVersion(900, dirty = true), SyncRules.uploadVersion(LocalVersion(50, false), now = 900, remoteUpdatedAt = 500))
        assertEquals(LocalVersion(1001, dirty = true), SyncRules.uploadVersion(LocalVersion(1000, false), now = 900, remoteUpdatedAt = 500))
        assertEquals(LocalVersion(100, dirty = true), SyncRules.uploadVersion(null, now = 100, remoteUpdatedAt = null))
    }

    @Test
    fun `서버 값은 더 최신이고 이 기기에 못 올린 변경이 없을 때만 덮어쓴다`() {
        val local = mapOf(
            "diet" to LocalVersion(100, dirty = false),
            "history" to LocalVersion(100, dirty = true),
            "water" to LocalVersion(300, dirty = false),
        )
        val applied = SyncRules.toApply(
            listOf(remote("diet", 200), remote("history", 200), remote("water", 200), remote("plan", 1), remote("future_key", 999)),
            local,
        ).map { it.key }
        // diet: 서버가 최신 / history: 못 올린 변경이 있어 올릴 때 서버가 판정 / water: 이 기기가 최신
        // plan: 이 기기에 버전이 없다(처음 받음) / future_key: 모르는 항목은 무시
        assertEquals(listOf("diet", "plan"), applied)
    }

    @Test
    fun `같은 시각이면 다시 받지 않는다`() {
        assertTrue(SyncRules.toApply(listOf(remote("diet", 100)), mapOf("diet" to LocalVersion(100, false))).isEmpty())
    }

    @Test
    fun `바뀐 버전은 시계가 뒤로 가도 줄지 않는다`() {
        assertEquals(LocalVersion(500, dirty = true), SyncRules.changed(null, now = 500))
        assertEquals(LocalVersion(1001, dirty = true), SyncRules.changed(LocalVersion(1000, false), now = 900))
        assertEquals(LocalVersion(2000, dirty = true), SyncRules.changed(LocalVersion(1000, false), now = 2000))
    }

    @Test
    fun `올리는 동안 또 바뀌었으면 아직 올릴 게 남는다`() {
        assertEquals(LocalVersion(10, dirty = false), SyncRules.uploaded(LocalVersion(10, true), sentVersion = 10))
        val changedMeanwhile = LocalVersion(11, true)
        assertEquals(changedMeanwhile, SyncRules.uploaded(changedMeanwhile, sentVersion = 10))
        assertNull(SyncRules.uploaded(null, sentVersion = 10))
    }

    @Test
    fun `기기 설정은 동기화하지 않는다`() {
        listOf("theme_mode", "guide_done", "logged_in", "onboarded", "plan_done_day").forEach { assertFalse(it in SyncRules.KEYS) }
    }
}
