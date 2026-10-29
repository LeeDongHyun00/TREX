package com.example.trex_kotlin.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.material3.Text as UserText
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.AppViewModel
import com.example.trex_kotlin.DField
import com.example.trex_kotlin.GhostButton
import com.example.trex_kotlin.LocalTrexNavSpace
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.nowMillis
import com.example.trex_kotlin.rememberFoodGallery
import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.NetworkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 프로필(설계 §2 목표 6) — 사진·닉네임·글 수·3열 격자(첫 사진, 글만이면 글 타일). 내 프로필은 닉네임·사진 바꾸기, 차단 목록,
 * 내 신고(처리 상태 — 정보통신망법 신청인 알림, 설계 §3). 남의 프로필은 ⋯ 에서 신고·차단, 차단했으면 글 대신 해제 안내.
 */
@Composable
fun ProfileScreen(
    app: AppViewModel,
    initial: CommunityProfile,
    /** 이 사람의 글 목록 — 탭 안 화면 쌓기가 들고 있어 상세에 다녀와도 남는다(상세의 좋아요가 이 목록에 반영된다). */
    posts: FeedController,
    gate: CommunityGate,
    onBack: () -> Unit,
    onOpenPost: (Post, FeedController) -> Unit,
) {
    val c = Trex.c
    val scope = rememberCoroutineScope()
    val mine = initial.publicId == app.communityMe?.profile?.publicId
    var user by remember { mutableStateOf<UserProfile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var action by remember { mutableStateOf<CommunityAction?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) } // nickname | blocks | reports
    val grid = rememberLazyGridState()
    val profile = if (mine) app.communityMe?.profile ?: initial else user?.profile ?: initial

    fun fail(e: Exception) {
        message = when (e) {
            is ApiException -> e.message
            is NetworkException -> "네트워크에 연결하지 못했어요"
            else -> throw e
        }
    }

    suspend fun reload() {
        try {
            user = app.community.user(initial.publicId)
        } catch (e: NetworkException) {
            fail(e)
        }
        if (posts.state.isStale(nowMillis())) posts.refresh()
    }
    LaunchedEffect(initial.publicId) { reload() }
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.let { it.index >= grid.layoutInfo.totalItemsCount - 6 } == true } }
    LaunchedEffect(nearEnd, posts.state.posts.size) { if (nearEnd) posts.loadMore() }

    val pickAvatar = rememberFoodGallery(maxItems = 1) { loaders ->
        val loader = loaders.firstOrNull() ?: return@rememberFoodGallery
        scope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) { loader.load()?.jpeg(maxDimension = 640) } ?: run { message = "사진을 읽지 못했어요"; return@launch }
                app.community.setAvatar(jpeg)
                app.refreshCommunityMe()
                message = "프로필 사진을 바꿨어요"
            } catch (e: NetworkException) {
                fail(e)
            }
        }
    }

    val blocked = user?.blockedByMe == true
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = c.text) }
            UserText(profile.nickname, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!mine) MenuButton(buildList {
                if (gate.canWrite) add(PostMenuItem("신고") { action = CommunityAction.Report(ReportTarget.USER, profile.publicId) })
                if (blocked) add(PostMenuItem("차단 해제") {
                    scope.launch {
                        try { app.community.unblock(profile.publicId); message = "차단을 풀었어요"; reload() } catch (e: NetworkException) { fail(e) }
                    }
                }) else add(PostMenuItem("차단", destructive = true) { action = CommunityAction.Block(profile) })
            })
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = grid,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalTrexNavSpace.current),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(profile, 76)
                        Column(Modifier.padding(start = 18.dp)) {
                            UserText(profile.nickname, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            user?.let { Text("게시물 ${it.postCount}", color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                        }
                    }
                    if (mine) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("닉네임 바꾸기", { sheet = "nickname" }, Modifier.weight(1f), height = 42.dp)
                            GhostButton("사진 바꾸기", pickAvatar, Modifier.weight(1f), height = 42.dp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("차단 목록", { sheet = "blocks" }, Modifier.weight(1f), height = 42.dp)
                            GhostButton("내 신고", { sheet = "reports" }, Modifier.weight(1f), height = 42.dp)
                        }
                        if (profile.avatarUrl != null) Text(
                            "프로필 사진 지우기", color = c.text3, fontSize = 13.sp,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    try { app.community.clearAvatar(); app.refreshCommunityMe(); message = "프로필 사진을 지웠어요" } catch (e: NetworkException) { fail(e) }
                                }
                            },
                        )
                    }
                    message?.let { Banner(it, c.surface2, c.text, onClose = { message = null }) }
                    if (blocked) Text("차단한 사용자예요. ⋯ 에서 차단을 풀면 글이 다시 보여요.", color = c.text2, fontSize = 14.sp)
                    else if (posts.state.posts.isEmpty() && posts.state.loadedAt != null) Text("아직 올린 글이 없어요", color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp))
                }
            }
            if (!blocked) items(posts.state.posts, key = { it.id }) { post ->
                GridTile(post, onClick = { onOpenPost(post, posts) })
            }
        }
    }

    action?.let { pending ->
        CommunityActionDialog(app, pending, onDismiss = { action = null }) { done ->
            action = null
            message = done
            if (pending is CommunityAction.Block) {
                scope.launch { reload(); posts.refresh(); app.communityFeed.refresh() }
            }
        }
    }
    when (sheet) {
        "nickname" -> NicknameDialog(app, profile.nickname, onDismiss = { sheet = null }) { sheet = null; message = "닉네임을 바꿨어요" }
        "blocks" -> BlocksDialog(app, onDismiss = { sheet = null }, onChanged = { scope.launch { app.communityFeed.refresh() } })
        "reports" -> MyReportsDialog(app, onDismiss = { sheet = null })
    }
}

/** 격자 한 칸 — 첫 사진(여러 장이면 겹친 사진 표시), 글만이면 본문 앞부분. */
@Composable
private fun GridTile(post: Post, onClick: () -> Unit) {
    val c = Trex.c
    Box(Modifier.aspectRatio(1f).background(c.surface2).clickable(onClick = onClick)) {
        val first = post.images.firstOrNull()
        if (first != null) {
            RemoteImage(first.url, contentDescription = null, modifier = Modifier.fillMaxSize(), maxDimension = 360)
            if (post.images.size > 1) Icon(
                Icons.Rounded.Collections, "사진 여러 장", tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
            )
        } else {
            UserText(
                post.body.orEmpty(), color = c.text, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 5, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
        if (post.hidden) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Text("숨김", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NicknameDialog(app: AppViewModel, current: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val c = Trex.c
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = c.sheet,
        title = { Text("닉네임 바꾸기") },
        text = {
            Column {
                DField(value, { value = takeCodePoints(it, 12); error = null }, placeholder = "닉네임", hint = "2~12자")
                error?.let { Text(it, color = c.err, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("취소") } },
        confirmButton = {
            TextButton(
                enabled = !busy && codePointLength(value.trim()) in 2..12 && value.trim() != current,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            app.community.setNickname(value.trim())
                            app.refreshCommunityMe()
                            onDone()
                        } catch (e: ApiException) {
                            error = e.message
                        } catch (_: NetworkException) {
                            error = "네트워크에 연결하지 못했어요"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("저장") }
        },
    )
}

@Composable
private fun BlocksDialog(app: AppViewModel, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val c = Trex.c
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<CommunityProfile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { list = app.community.blocks() } catch (e: ApiException) { error = e.message } catch (_: NetworkException) { error = "네트워크에 연결하지 못했어요" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sheet,
        title = { Text("차단 목록") },
        text = {
            Column(Modifier.heightIn(max = 360.dp)) {
                error?.let { Text(it, color = c.err, fontSize = 13.sp) }
                val items = list
                if (items != null && items.isEmpty()) Text("차단한 사람이 없어요", color = c.text2, fontSize = 14.sp)
                if (items != null) LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.publicId }) { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p, 32)
                            UserText(p.nickname, color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        app.community.unblock(p.publicId)
                                        list = list?.filterNot { it.publicId == p.publicId }
                                        onChanged()
                                    } catch (e: ApiException) { error = e.message } catch (_: NetworkException) { error = "네트워크에 연결하지 못했어요" }
                                }
                            }) { Text("해제") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** 내가 한 신고와 처리 결과(설계 §3 처리 결과 알림) — 푸시가 없어서 앱 안에서만 보인다. */
@Composable
private fun MyReportsDialog(app: AppViewModel, onDismiss: () -> Unit) {
    val c = Trex.c
    var list by remember { mutableStateOf<List<MyReport>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val now = remember { nowMillis() }
    LaunchedEffect(Unit) {
        try { list = app.community.myReports() } catch (e: ApiException) { error = e.message } catch (_: NetworkException) { error = "네트워크에 연결하지 못했어요" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sheet,
        title = { Text("내 신고") },
        text = {
            Column(Modifier.heightIn(max = 360.dp)) {
                error?.let { Text(it, color = c.err, fontSize = 13.sp) }
                val items = list
                if (items != null && items.isEmpty()) Text("신고한 내역이 없어요", color = c.text2, fontSize = 14.sp)
                if (items != null) LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(items, key = { it.id }) { r ->
                        Column {
                            Text("${reportTargetLabel(r.target)} · ${r.reason.label}", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("${timeAgo(r.createdAt, now)} · ${reportStatusLabel(r)}", color = c.text2, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

internal fun reportTargetLabel(target: ReportTarget): String = when (target) {
    ReportTarget.POST -> "글"
    ReportTarget.COMMENT -> "댓글"
    ReportTarget.USER -> "사용자"
}

/** 신고 처리 상태 문구 — 서버 resolution: KEPT(규칙 위반 아님), REMOVED(지움·정지). */
internal fun reportStatusLabel(report: MyReport): String = when {
    !report.resolved -> "확인 중"
    report.resolution == "REMOVED" -> "처리 완료 · 삭제·정지했어요"
    else -> "처리 완료 · 규칙 위반이 아니었어요"
}
