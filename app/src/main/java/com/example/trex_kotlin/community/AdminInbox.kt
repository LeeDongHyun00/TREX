package com.example.trex_kotlin.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.AppViewModel
import com.example.trex_kotlin.SegmentedTabs
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.nowMillis
import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.NetworkException
import kotlinx.coroutines.launch

/**
 * 관리자 신고함(설계 §3 관리자 — 내 정보 탭, 관리자에게만) — 신고 대상별 줄(미리보기·누적 수·사유 → 그대로 둠/지움),
 * 숨긴 글(복구·지우기), 정지 계정(해제·프로필 사진 지우기). 신고는 디스코드 알림으로 들어오고 24시간 안에 여기서 처리한다.
 */
@Composable
fun AdminInbox(app: AppViewModel, onClose: () -> Unit) {
    val c = Trex.c
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var reports by remember { mutableStateOf<List<AdminReport>?>(null) }
    var hidden by remember { mutableStateOf<List<Post>?>(null) }
    var suspended by remember { mutableStateOf<List<CommunityProfile>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var action by remember { mutableStateOf<CommunityAction?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val now = remember(reloadKey) { nowMillis() }

    fun fail(e: Exception) {
        message = when (e) {
            is ApiException -> e.message
            is NetworkException -> "네트워크에 연결하지 못했어요"
            else -> throw e
        }
    }

    fun direct(done: String, call: suspend () -> Unit) {
        scope.launch {
            try { call(); message = done; reloadKey++ } catch (e: NetworkException) { fail(e) }
        }
    }

    LaunchedEffect(tab, reloadKey) {
        try {
            when (tab) {
                0 -> reports = app.community.adminReports()
                1 -> hidden = app.community.hiddenPosts()
                else -> suspended = app.community.suspended()
            }
        } catch (e: NetworkException) {
            fail(e)
        }
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("커뮤니티 신고함", color = c.text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("닫기") }
        }
        SegmentedTabs(listOf("신고", "숨긴 글", "정지 계정"), tab, { tab = it })
        message?.let { Banner(it, c.surface2, c.text, onClose = { message = null }) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                0 -> {
                    val list = reports
                    if (list != null && list.isEmpty()) item { Empty("처리할 신고가 없어요") }
                    items(list.orEmpty(), key = { it.target.name + it.targetId }) { r ->
                        AdminCard {
                            Text(
                                "${reportTargetLabel(r.target)} · 신고 ${r.reportCount}건" + if (r.hidden) " · " + (if (r.target == ReportTarget.USER) "정지됨" else "숨김됨") else "",
                                color = c.text2, fontSize = 12.5.sp,
                            )
                            Text(r.preview, color = c.text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(r.reasons.distinct().joinToString(" · ") { it.label } + " · " + timeAgo(r.firstReportedAt, now), color = c.text3, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { action = CommunityAction.AdminResolve(r, kept = true) }) { Text("그대로 두기") }
                                TextButton(onClick = { action = CommunityAction.AdminResolve(r, kept = false) }) {
                                    Text(if (r.target == ReportTarget.USER) "정지" else "지우기", color = c.err)
                                }
                            }
                        }
                    }
                }
                1 -> {
                    val list = hidden
                    if (list != null && list.isEmpty()) item { Empty("숨긴 글이 없어요") }
                    items(list.orEmpty(), key = { it.id }) { p ->
                        AdminCard {
                            Text("${p.author.nickname} · ${timeAgo(p.createdAt, now)}" + (p.hiddenReason?.let { " · $it" } ?: ""), color = c.text2, fontSize = 12.5.sp)
                            Text(p.body ?: "(사진 ${p.images.size}장)", color = c.text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { direct("글을 복구했어요") { app.community.restorePost(p.id) } }) { Text("복구") }
                                TextButton(onClick = { action = CommunityAction.DeletePost(p) }) { Text("지우기", color = c.err) }
                            }
                        }
                    }
                }
                else -> {
                    val list = suspended
                    if (list != null && list.isEmpty()) item { Empty("정지한 계정이 없어요") }
                    items(list.orEmpty(), key = { it.publicId }) { p ->
                        AdminCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(p, 32)
                                Text(p.nickname, color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                            }
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                if (p.avatarUrl != null) TextButton(onClick = { direct("프로필 사진을 지웠어요") { app.community.adminClearAvatar(p.publicId) } }) {
                                    Text("프로필 사진 지우기", color = c.err)
                                }
                                TextButton(onClick = { direct("정지를 풀었어요") { app.community.unsuspend(p.publicId) } }) { Text("해제") }
                            }
                        }
                    }
                }
            }
        }
    }

    action?.let { pending ->
        CommunityActionDialog(app, pending, onDismiss = { action = null }) { done ->
            action = null
            message = done
            reloadKey++
            scope.launch { app.communityFeed.refresh() }
        }
    }
}

@Composable
private fun AdminCard(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = Trex.c.surface) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, color = Trex.c.text2, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
}
