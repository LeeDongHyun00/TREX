package com.example.trex_kotlin.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trex_kotlin.AppViewModel
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.NetworkException
import kotlinx.coroutines.launch

/** 글·댓글·사람에 대한 확인이 필요한 동작(설계 §2 목표 7 안전 장치) — 피드·상세·프로필이 같이 쓴다. */
sealed interface CommunityAction {
    data class DeletePost(val post: Post) : CommunityAction
    data class DeleteComment(val comment: Comment) : CommunityAction
    data class Report(val target: ReportTarget, val targetId: String) : CommunityAction
    data class Block(val profile: CommunityProfile) : CommunityAction
}

/**
 * [action] 의 확인 대화상자 — 확인하면 서버를 부르고, 성공하면 [onDone] 에 알릴 문구를 넘긴다. 실패하면 대화상자에 서버 문구를
 * 남긴다(닫지 않는다). 부르는 쪽은 [onDone] 에서 목록을 고친다(지운 글 빼기, 차단 뒤 새로고침).
 */
@Composable
fun CommunityActionDialog(app: AppViewModel, action: CommunityAction, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val c = Trex.c
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf<ReportReason?>(null) }

    fun run(block: suspend () -> String) {
        scope.launch {
            busy = true
            error = null
            try {
                onDone(block())
            } catch (e: ApiException) {
                error = e.message
            } catch (_: NetworkException) {
                error = "네트워크에 연결하지 못했어요"
            } finally {
                busy = false
            }
        }
    }

    val title: String
    val confirm: String
    val body: @Composable () -> Unit
    val onConfirm: () -> Unit
    var confirmEnabled = !busy
    when (action) {
        is CommunityAction.DeletePost -> {
            title = "글을 지울까요?"
            confirm = "지우기"
            body = { Text("지운 글과 사진, 댓글은 되돌릴 수 없어요.") }
            onConfirm = { run { app.community.deletePost(action.post.id); "글을 지웠어요" } }
        }
        is CommunityAction.DeleteComment -> {
            title = "댓글을 지울까요?"
            confirm = "지우기"
            body = { Text("지운 댓글은 되돌릴 수 없어요.") }
            onConfirm = { run { app.community.deleteComment(action.comment.id); "댓글을 지웠어요" } }
        }
        is CommunityAction.Block -> {
            title = "${action.profile.nickname}님을 차단할까요?"
            confirm = "차단"
            body = { Text("이 사람의 글과 댓글이 더 이상 보이지 않아요. 상대에게는 알리지 않아요. 차단 목록은 내 프로필에서 풀 수 있어요.") }
            onConfirm = { run { app.community.block(action.profile.publicId); "차단했어요" } }
        }
        is CommunityAction.Report -> {
            title = when (action.target) {
                ReportTarget.POST -> "글 신고"
                ReportTarget.COMMENT -> "댓글 신고"
                ReportTarget.USER -> "사용자 신고"
            }
            confirm = "신고하기"
            confirmEnabled = confirmEnabled && reason != null
            body = {
                Column {
                    Text("신고 사유를 골라 주세요. 신고한 사람은 상대에게 알리지 않아요.", fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                    ReportReason.entries.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable { reason = r },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = reason == r, onClick = { reason = r }, colors = RadioButtonDefaults.colors(selectedColor = c.primary))
                            Text(r.label, color = c.text, fontSize = 15.sp)
                        }
                    }
                }
            }
            onConfirm = {
                val chosen = reason
                if (chosen != null) run {
                    app.community.report(action.target, action.targetId, chosen)
                    "신고했어요. 운영자가 24시간 안에 확인해요"
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = c.sheet,
        title = { Text(title) },
        text = {
            Column {
                body()
                error?.let { Text(it, color = c.err, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("취소") } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(if (busy) "보내는 중…" else confirm, color = if (action is CommunityAction.Report) c.primaryText else c.err)
            }
        },
    )
}
