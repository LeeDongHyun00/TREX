package com.example.trex_kotlin.community

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.AppViewModel
import com.example.trex_kotlin.LocalTrexNavSpace
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.nowMillis
import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.NetworkException
import kotlinx.coroutines.launch

/**
 * 글 상세(설계 §2 목표 3) — 글 전체와 댓글(오래된 순, "이전 댓글 보기"), 댓글 입력(500자). [owner] 는 이 글이 들어 있는 목록
 * (전체 피드 또는 프로필의 글 목록) — 좋아요·댓글 수·지우기를 그 목록에도 반영해 돌아갔을 때 맞는다.
 */
@Composable
fun PostDetailScreen(
    app: AppViewModel,
    initial: Post,
    owner: FeedController,
    gate: CommunityGate,
    onBack: () -> Unit,
    onOpenProfile: (CommunityProfile) -> Unit,
) {
    val c = Trex.c
    val scope = rememberCoroutineScope()
    var fetched by remember { mutableStateOf(initial) }
    var comments by remember { mutableStateOf(CommentsState()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var action by remember { mutableStateOf<CommunityAction?>(null) }
    var gone by remember { mutableStateOf(false) }
    val now = remember { nowMillis() }
    val post = owner.state.shown(initial.id) ?: fetched

    fun fail(e: Exception) {
        message = when (e) {
            is ApiException -> e.message
            is NetworkException -> "네트워크에 연결하지 못했어요"
            else -> throw e
        }
    }

    LaunchedEffect(initial.id) {
        try {
            val fresh = app.community.post(initial.id)
            fetched = fresh
            owner.replaced(fresh)
            comments = comments.withFirstPage(app.community.comments(initial.id, null))
        } catch (e: ApiException) {
            if (e.status == 404) { gone = true; owner.removed(initial.id) } else fail(e)
        } catch (e: NetworkException) {
            fail(e)
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = c.text) }
            Text("게시물", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        if (gone) {
            Text("지워졌거나 볼 수 없는 글이에요", color = c.text2, fontSize = 15.sp, modifier = Modifier.padding(24.dp))
            return@Column
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "post") {
                PostCard(
                    post = post,
                    now = now,
                    onLike = { if (gate.canWrite) owner.toggleLike(post.id) else message = "이용이 정지된 계정이에요" },
                    onOpen = {},
                    onAuthor = { onOpenProfile(post.author) },
                    menu = postMenu(post, gate) { action = it },
                    expandedBody = true,
                )
            }
            message?.let { m -> item(key = "message") { Banner(m, c.surface2, c.text, onClose = { message = null }) } }
            if (comments.hasOlder) item(key = "older") {
                Text(
                    "이전 댓글 보기", color = c.text2, fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
                        scope.launch {
                            try {
                                comments = comments.withOlderPage(app.community.comments(post.id, comments.olderBefore))
                            } catch (e: NetworkException) {
                                fail(e)
                            }
                        }
                    }.padding(vertical = 4.dp),
                )
            }
            items(comments.items, key = { "c" + it.id }) { comment ->
                CommentRow(
                    comment = comment,
                    now = now,
                    onAuthor = { onOpenProfile(comment.author) },
                    menu = buildList {
                        if (comment.mine || post.mine) add(PostMenuItem("지우기", destructive = true) { action = CommunityAction.DeleteComment(comment) })
                        if (!comment.mine && gate.canWrite) add(PostMenuItem("신고") { action = CommunityAction.Report(ReportTarget.COMMENT, comment.id.toString()) })
                    },
                )
            }
        }
        if (gate.canWrite) {
            Row(
                Modifier.fillMaxWidth().background(c.surface).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                    .padding(bottom = LocalTrexNavSpace.current - 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = takeCodePoints(it, CommentsState.MAX_BODY) },
                    textStyle = TextStyle(color = c.text, fontSize = 14.sp, lineHeight = 20.sp),
                    cursorBrush = SolidColor(c.primary),
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(RoundedCornerShape(21.dp)).background(c.field)
                                .border(1.dp, c.fieldLine, RoundedCornerShape(21.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (input.isEmpty()) Text("댓글 달기…", color = c.text3, fontSize = 14.sp)
                            inner()
                        }
                    },
                )
                TextButton(
                    enabled = !sending && canSendComment(input),
                    onClick = {
                        scope.launch {
                            sending = true
                            try {
                                val added = app.community.addComment(post.id, input.trim())
                                comments = comments.added(added)
                                input = ""
                                owner.commentsChanged(post.id, +1)
                                fetched = fetched.copy(commentCount = fetched.commentCount + 1)
                            } catch (e: NetworkException) {
                                fail(e) // 입력은 지우지 않는다
                            } finally {
                                sending = false
                            }
                        }
                    },
                ) { Text("게시", color = c.primaryText, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    action?.let { pending ->
        CommunityActionDialog(app, pending, onDismiss = { action = null }) { done ->
            action = null
            message = done
            when (pending) {
                is CommunityAction.DeletePost -> { owner.removed(pending.post.id); onBack() }
                is CommunityAction.DeleteComment -> {
                    comments = comments.removed(pending.comment.id)
                    owner.commentsChanged(post.id, -1)
                    fetched = fetched.copy(commentCount = (fetched.commentCount - 1).coerceAtLeast(0))
                }
                is CommunityAction.Block, is CommunityAction.AdminSuspend -> { scope.launch { owner.refresh() }; onBack() }
                is CommunityAction.AdminHidePost -> scope.launch { owner.refresh() }
                else -> Unit
            }
        }
    }
}

@Composable
private fun CommentRow(comment: Comment, now: Long, onAuthor: () -> Unit, menu: List<PostMenuItem>) {
    val c = Trex.c
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.clickable(onClick = onAuthor).padding(top = 2.dp)) { Avatar(comment.author, 30) }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.author.nickname, color = c.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onAuthor))
                Text("  " + timeAgo(comment.createdAt, now), color = c.text3, fontSize = 12.sp)
            }
            if (comment.hidden) HiddenBadge(comment.hiddenReason, Modifier.padding(vertical = 4.dp))
            Text(comment.body, color = c.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (menu.isNotEmpty()) MenuButton(menu)
    }
}
