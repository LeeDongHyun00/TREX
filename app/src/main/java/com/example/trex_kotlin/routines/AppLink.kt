package com.example.trex_kotlin.routines

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 링크로 앱이 열렸을 때 갈 곳(docs/superpowers/specs/2026-10-29-routine-share-design.md §4) — 웹 페이지의 "앱에서 열기"가 여는
 * `trex://r/{code}`·`trex://p/{id}`, 나중에 앱 바로 열기를 붙이면 들어올 `https://…/r/{code}`·`/p/{id}` 를 같이 받는다.
 */
sealed interface AppLink {
    data class Routine(val code: String) : AppLink
    data class Post(val id: Long) : AppLink
}

private val LINK = Regex("^(?:trex://|https?://[^/]+/)([rp])/([^/?#]+)")
private val CODE = Regex("^[A-Za-z0-9]{8}$")

fun parseAppLink(url: String): AppLink? {
    val m = LINK.find(url.trim()) ?: return null
    val (kind, value) = m.destructured
    return when (kind) {
        "r" -> if (CODE.matches(value)) AppLink.Routine(value) else null
        else -> value.toLongOrNull()?.takeIf { it > 0 }?.let { AppLink.Post(it) }
    }
}

/**
 * 플랫폼이 받은 앱 주소를 넣는 곳 — Android `MainActivity`(onCreate·onNewIntent), iPhone `onOpenURL`(`AppLinkInbox.shared.deliver(url:)`).
 * 공유 화면(`TrexApp`)이 [latest] 를 지켜보다 꺼내 간다.
 */
object AppLinkInbox {
    var latest by mutableStateOf<String?>(null)

    fun deliver(url: String?) {
        if (!url.isNullOrBlank()) latest = url
    }
}
