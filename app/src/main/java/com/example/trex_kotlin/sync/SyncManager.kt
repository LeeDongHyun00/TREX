package com.example.trex_kotlin.sync

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.trex_kotlin.TrexStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * `trex_store` 항목을 서버와 맞춘다(규칙은 [SyncRules]).
 *
 * - 이 기기에서 항목이 바뀌면(저장소 변경 알림) 버전을 올리고 3초 모아서 올린다. 로그인 전에도 버전은 기록한다.
 * - 앱 시작·로그인·"지금 동기화" 때 서버 값을 받아 더 최신인 항목을 덮어쓰고 [onRemoteApplied] 로 화면 상태를 다시 읽게 한다.
 * - 계정마다 처음 한 번은 [beginAccount] 가 양쪽 기록을 보고 올릴지·받을지·물을지 정한다. 그 전에는 주고받지 않는다
 *   (`owner` 가 지금 계정이 아니면 [syncNow] 는 아무것도 안 한다) — 남의 계정 기록과 섞이는 것을 막는다.
 *
 * 저장소 알림과 [scope](viewModelScope, 메인)의 비네트워크 부분은 모두 메인 스레드에서 돈다.
 */
class SyncManager(
    context: Context,
    private val store: TrexStore,
    private val session: AuthSession,
    private val api: TrexApi,
    private val scope: CoroutineScope,
    private val onRemoteApplied: () -> Unit,
    private val onSessionExpired: () -> Unit,
) {
    private val prefs = context.applicationContext.getSharedPreferences("trex_sync", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var applyingRemote = false
    private var pushPending = false
    private var pushJob: Job? = null
    private var pendingFirst: Pair<String, List<RemoteItem>>? = null

    var syncing by mutableStateOf(false)
        private set
    var lastSyncedAt by mutableStateOf(prefs.getLong("last_synced_at", 0L))
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    fun start() {
        store.setChangeListener { key ->
            if (key in SyncRules.KEYS && !applyingRemote) {
                setVersion(key, SyncRules.changed(version(key), System.currentTimeMillis()))
                schedulePush()
            }
        }
    }

    /**
     * 이 서버의 이 계정과 처음 맞춘 적이 있는가. 없으면 [beginAccount] 부터.
     * 서버 주소까지 묶는다 — 로컬 서버와 맞춘 기기가 빈 운영 서버에 붙으면 "이미 맞췄다"고 착각해
     * 첫 올리기를 건너뛰고, 버전상 올릴 게 없어 운영 서버가 계속 비어 있었다(2026-10-13 운영 전환 때 발견).
     */
    fun isOwner(loginId: String): Boolean = prefs.getString("owner", null) == ownerKey(loginId)

    private fun ownerKey(loginId: String) = "${api.serverId}|$loginId"

    /**
     * 로그인 직후 한 번. 이미 맞춘 계정이면 평소 동기화만 하고 null.
     * [FirstSync.ASK] 를 돌려주면 [resolveFirst] 에 사용자의 선택을 넘겨야 동기화가 시작된다.
     */
    suspend fun beginAccount(loginId: String, localHasData: Boolean): FirstSync? {
        if (isOwner(loginId)) {
            syncNow()
            return null
        }
        val remote = api.syncItems()
        val decision = SyncRules.firstSync(localHasData, remote)
        when (decision) {
            FirstSync.UPLOAD_LOCAL -> { markAllLocalChanged(); setOwner(loginId); syncNow() }
            FirstSync.DOWNLOAD_REMOTE -> { applyRemote(remote); setOwner(loginId); syncNow() }
            FirstSync.ASK -> pendingFirst = loginId to remote
        }
        return decision
    }

    /**
     * [keepLocal] 이면 이 기기 기록으로 서버를 덮고, 아니면 서버 기록을 받는다.
     * 서버에 없는 항목은 어느 쪽이든 이 기기 값을 남겨 올린다 — 사용자가 고르지 않은 데이터를 지우지 않는다.
     */
    suspend fun resolveFirst(keepLocal: Boolean) {
        val (loginId, remote) = pendingFirst ?: return
        pendingFirst = null
        if (keepLocal) markAllLocalChanged() else applyRemote(remote)
        setOwner(loginId)
        syncNow()
    }

    suspend fun syncNow() = exchange(pull = true)

    /** 탈퇴 후 — 이 기기의 동기화 표시를 지운다. 기록 자체는 남는다. */
    fun forgetAccount() {
        prefs.edit().clear().apply()
        lastSyncedAt = 0L
        lastError = null
    }

    private fun schedulePush() {
        pushPending = true
        if (pushJob?.isActive == true) return
        pushJob = scope.launch {
            while (pushPending) {
                pushPending = false
                delay(3_000)
                exchange(pull = false)
            }
        }
    }

    private suspend fun exchange(pull: Boolean) = lock.withLock {
        val account = session.current() ?: return@withLock
        if (!isOwner(account.loginId)) return@withLock
        syncing = true
        try {
            if (pull) applyRemote(SyncRules.toApply(api.syncItems(), versions()))
            pushDirty()
            lastSyncedAt = System.currentTimeMillis().also { prefs.edit().putLong("last_synced_at", it).apply() }
            lastError = null
        } catch (e: ApiException) {
            if (e.status == 401) {
                session.clear()
                onSessionExpired()
            } else {
                lastError = e.message
            }
        } catch (_: IOException) {
            lastError = "서버에 연결하지 못했어요. 다음에 다시 시도해요."
        } catch (_: org.json.JSONException) {
            lastError = "서버 응답을 읽지 못했어요."
        } finally {
            syncing = false
        }
    }

    private suspend fun pushDirty() {
        for (key in SyncRules.KEYS) {
            val sent = version(key)?.takeIf { it.dirty } ?: continue
            when (val result = api.putItem(key, store.readRaw(key).orEmpty(), sent.updatedAt)) {
                is TrexApi.PutResult.Applied -> setVersion(key, SyncRules.uploaded(version(key), sent.updatedAt))
                // 서버가 더 최신이다. 그사이 이 기기에서 또 바꿨으면 다음 올리기에서 다시 판정한다.
                is TrexApi.PutResult.Conflict -> if (version(key)?.updatedAt == sent.updatedAt) applyRemote(listOf(result.item))
            }
        }
    }

    private suspend fun applyRemote(items: List<RemoteItem>) {
        val known = items.filter { it.key in SyncRules.KEYS }
        if (known.isEmpty()) return
        withContext(Dispatchers.Main) {
            // 저장소 변경 알림은 메인 스레드에서 commit 안에서 바로 온다 — 그동안은 "이 기기에서 바꿈"으로 세지 않는다.
            applyingRemote = true
            try {
                store.writeRaw(known.associate { it.key to it.payload })
            } finally {
                applyingRemote = false
            }
            known.forEach { setVersion(it.key, LocalVersion(it.updatedAt, dirty = false)) }
            onRemoteApplied()
        }
    }

    private fun markAllLocalChanged() {
        val now = System.currentTimeMillis()
        SyncRules.KEYS.forEach { setVersion(it, SyncRules.changed(version(it), now)) }
    }

    private fun setOwner(loginId: String) {
        prefs.edit().putString("owner", ownerKey(loginId)).apply()
    }

    private fun version(key: String): LocalVersion? =
        if (prefs.contains("v_$key")) LocalVersion(prefs.getLong("v_$key", 0L), prefs.getBoolean("d_$key", false)) else null

    private fun versions(): Map<String, LocalVersion> = SyncRules.KEYS.mapNotNull { k -> version(k)?.let { k to it } }.toMap()

    private fun setVersion(key: String, v: LocalVersion?) {
        if (v == null) return
        prefs.edit().putLong("v_$key", v.updatedAt).putBoolean("d_$key", v.dirty).apply()
    }
}
