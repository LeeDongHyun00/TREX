package com.example.trex_kotlin.sync

/**
 * 계정 동기화의 판정 규칙(docs/SERVER_SYNC_HANDOFF.md D5·D6). 저장·네트워크 없이 값만 다룬다 — 단위 테스트 대상.
 *
 * 동기화 단위는 `trex_store` 의 항목(프로필·계획·기록·식단…) 하나를 통째로다. 항목마다 이 기기에서 마지막으로 바꾼
 * 시각([LocalVersion.updatedAt])과 아직 서버에 못 올렸는지([LocalVersion.dirty])를 기억한다.
 * 충돌은 항목별로 **나중에 바꾼 쪽이 이긴다**. 서버가 더 최신이면 서버가 409 로 자기 값을 돌려주고, 이 기기는 그 값을 받는다.
 */
data class LocalVersion(val updatedAt: Long, val dirty: Boolean)

data class RemoteItem(val key: String, val payload: String, val updatedAt: Long)

/** 처음 계정을 연결했을 때 할 일. */
enum class FirstSync {
    /** 서버가 비어 있다 — 이 기기 기록을 올린다. */
    UPLOAD_LOCAL,

    /** 이 기기가 비어 있다(온보딩 전) — 서버 기록을 받는다. */
    DOWNLOAD_REMOTE,

    /** 양쪽에 다 있다 — 어느 쪽을 남길지 사용자에게 묻는다. 자동으로 섞지 않는다. */
    ASK,

    /**
     * 이 기기에 **다른 계정**으로 쓰던 기록이 있다(같은 서버에서 다른 계정이 로그아웃한 뒤 새 계정이 들어옴).
     * 묻지 않고 올리면 앞 사람의 건강·식단 기록이 새 계정 백업으로 들어간다 — 옮길지 사용자에게 묻는다.
     */
    SWITCH_ACCOUNT,
}

object SyncRules {

    /**
     * 서버와 맞춰 둘 항목들. 테마·안내 완료·오늘 계획 날짜 같은 기기 설정은 넣지 않는다.
     * 내 음식 기억(food_memory.tsv)·자세 기준선·자세 로그는 `trex_store` 밖이라 대상이 아니다 — 음식 기억은 폰 밖으로 내보내지 않는다(FOOD_EVAL §9).
     */
    // routines = 내 루틴 보관함(루틴 공유, docs/superpowers/specs/2026-10-29-routine-share-design.md) — 옛 앱은 모르는 항목을 무시한다
    val KEYS: List<String> = listOf("profile", "plan", "history", "diet", "water", "custom_foods", "goal_override", "routines")

    /**
     * @param otherAccountOnDevice 이 서버에서 이 기기와 마지막으로 맞춘 계정이 지금 계정과 다르다.
     */
    fun firstSync(localHasData: Boolean, remote: List<RemoteItem>, otherAccountOnDevice: Boolean = false): FirstSync = when {
        otherAccountOnDevice && localHasData -> FirstSync.SWITCH_ACCOUNT
        remote.none { it.key in KEYS && it.payload.isNotEmpty() } -> FirstSync.UPLOAD_LOCAL
        !localHasData -> FirstSync.DOWNLOAD_REMOTE
        else -> FirstSync.ASK
    }

    /**
     * 받은 서버 항목 중 이 기기에 덮어쓸 것. 아직 못 올린 로컬 변경이 있는 항목은 건드리지 않는다 —
     * 올릴 때 서버가 시각을 비교해 판정한다(서버가 더 최신이면 409 로 서버 값이 온다).
     */
    fun toApply(remote: List<RemoteItem>, local: Map<String, LocalVersion>): List<RemoteItem> =
        remote.filter { r ->
            if (r.key !in KEYS) return@filter false   // 새 버전 앱이 올린 모르는 항목은 무시한다
            val l = local[r.key]
            l == null || (!l.dirty && r.updatedAt > l.updatedAt)
        }

    /** 로컬에서 항목을 바꿨을 때의 새 버전. 시계가 뒤로 가도 버전은 줄지 않는다. */
    fun changed(previous: LocalVersion?, now: Long): LocalVersion =
        LocalVersion(maxOf(now, (previous?.updatedAt ?: 0L) + 1), dirty = true)

    /**
     * "이 기기 기록으로 올린다"고 정했을 때의 버전 — 이미 받아 본 서버 값([remoteUpdatedAt])보다 반드시 새것으로 찍는다.
     * 이 기기 시계가 다른 기기보다 늦으면 지금 시각만으로는 서버 값보다 오래돼 409 로 서버 값(빈 항목일 수도 있다)을
     * 받아 버린다 — 사용자가 고른 "이 기기 기록 유지"가 조용히 뒤집히고 로컬 기록이 지워진다(코드 리뷰 지적).
     */
    fun uploadVersion(previous: LocalVersion?, now: Long, remoteUpdatedAt: Long?): LocalVersion =
        LocalVersion(maxOf(now, (previous?.updatedAt ?: 0L) + 1, (remoteUpdatedAt ?: -1L) + 1), dirty = true)

    /** 올리기가 반영됐을 때. 올리는 동안 또 바뀌었으면(버전이 다르면) 아직 올릴 게 남았다. */
    fun uploaded(current: LocalVersion?, sentVersion: Long): LocalVersion? =
        if (current != null && current.updatedAt == sentVersion) current.copy(dirty = false) else current
}
