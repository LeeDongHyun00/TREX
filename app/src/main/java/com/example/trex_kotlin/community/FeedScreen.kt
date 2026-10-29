package com.example.trex_kotlin.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.AppViewModel
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.nowMillis
import com.example.trex_kotlin.tabContentPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 피드(설계 §2 목표 1·§5) — 전체 최신순, 당겨서 새로고침, 끝에 닿으면 다음 쪽. 탭에 들어올 때 사진 주소가 곧 만료되면
 * (받은 지 50분) 새로 받는다. 정지된 계정은 읽기만 되고 + 버튼 대신 안내를 보인다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    app: AppViewModel,
    gate: CommunityGate,
    listState: LazyListState,
    onCompose: () -> Unit,
    onOpenPost: (Post) -> Unit,
    onOpenProfile: (CommunityProfile) -> Unit,
) {
    val c = Trex.c
    val feed = app.communityFeed
    val scope = rememberCoroutineScope()
    val list = listState
    var action by remember { mutableStateOf<CommunityAction?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val now = remember(feed.state.loadedAt) { nowMillis() }

    LaunchedEffect(Unit) { app.refreshFeedIfNeeded() }
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.let { it.index >= list.layoutInfo.totalItemsCount - 3 } == true } }
    LaunchedEffect(nearEnd) { if (nearEnd) feed.loadMore() }
    LaunchedEffect(notice) { if (notice != null) { delay(3000); notice = null } }

    PullToRefreshBox(isRefreshing = feed.refreshing, onRefresh = { scope.launch { feed.refresh() } }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = tabContentPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("커뮤니티", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (gate.canWrite) Surface(onClick = onCompose, shape = RoundedCornerShape(999.dp), color = c.primary) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Text("글쓰기", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
            if (gate is CommunityGate.Suspended) item(key = "suspended") {
                Banner("이용이 정지된 계정이에요. 글은 볼 수 있지만 글·댓글·좋아요·신고는 할 수 없어요.", c.errWash, c.err)
            }
            val message = notice ?: feed.message
            if (message != null) item(key = "message") {
                Banner(message, c.surface2, c.text, onClose = { notice = null; feed.clearMessage() })
            }
            items(feed.state.posts, key = { it.id }) { raw ->
                val post = feed.state.shown(raw)
                PostCard(
                    post = post,
                    now = now,
                    onLike = { if (gate.canWrite) feed.toggleLike(post.id) else notice = "이용이 정지된 계정이에요" },
                    onOpen = { onOpenPost(post) },
                    onAuthor = { onOpenProfile(post.author) },
                    menu = postMenu(post, gate) { action = it },
                )
            }
            if (feed.state.posts.isEmpty() && !feed.refreshing && feed.state.loadedAt != null) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("아직 글이 없어요", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("첫 글을 올려 보세요", color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (feed.loading) item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.primaryText) }
            }
        }
    }

    action?.let { pending ->
        CommunityActionDialog(app, pending, onDismiss = { action = null }) { done ->
            action = null
            notice = done
            when (pending) {
                is CommunityAction.DeletePost -> feed.removed(pending.post.id)
                is CommunityAction.Block -> scope.launch { feed.refresh() } // 차단한 사람의 글이 빠진 목록으로
                else -> Unit
            }
        }
    }
}

/** 카드 ⋯ 메뉴 — 내 글은 지우기, 남의 글은 신고·차단(정지된 계정은 신고 못 함, 서버 requireWriter). */
internal fun postMenu(post: Post, gate: CommunityGate, open: (CommunityAction) -> Unit): List<PostMenuItem> = buildList {
    if (post.mine) {
        add(PostMenuItem("지우기", destructive = true) { open(CommunityAction.DeletePost(post)) })
    } else {
        if (gate.canWrite) add(PostMenuItem("신고") { open(CommunityAction.Report(ReportTarget.POST, post.id.toString())) })
        add(PostMenuItem("${post.author.nickname}님 차단", destructive = true) { open(CommunityAction.Block(post.author)) })
    }
}

@Composable
internal fun Banner(text: String, background: Color, foreground: Color, onClose: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(16.dp), color = background) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = foreground, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "닫기", tint = Trex.c.text3, modifier = Modifier.size(18.dp)) }
        }
    }
}
