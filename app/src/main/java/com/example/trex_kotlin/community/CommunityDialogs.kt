package com.example.trex_kotlin.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.material3.Text as UserText
import com.example.trex_kotlin.posture.toDinoCopy
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
    // 관리자(설계 §3 관리자 — 카드 ⋯ 와 신고함)
    data class AdminHidePost(val post: Post) : CommunityAction
    data class AdminSuspend(val profile: CommunityProfile) : CommunityAction
    /** 신고 처리 — [kept] 면 그대로 두고 숨김을 푼다, 아니면 지운다(사용자면 정지). */
    data class AdminResolve(val report: AdminReport, val kept: Boolean) : CommunityAction
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
            title = "글을 지울까요?".toDinoCopy()
            confirm = "지우기"
            body = { Text("지운 글과 사진, 댓글은 되돌릴 수 없어요.") }
            onConfirm = { run { app.community.deletePost(action.post.id); "글을 지웠어요" } }
        }
        is CommunityAction.DeleteComment -> {
            title = "댓글을 지울까요?".toDinoCopy()
            confirm = "지우기"
            body = { Text("지운 댓글은 되돌릴 수 없어요.") }
            onConfirm = { run { app.community.deleteComment(action.comment.id); "댓글을 지웠어요" } }
        }
        is CommunityAction.Block -> {
            title = withName(action.profile.nickname, "님을 차단할까요?")
            confirm = "차단"
            body = { Text("이 사람의 글과 댓글이 더 이상 보이지 않아요. 상대에게는 알리지 않아요. 차단 목록은 내 프로필에서 풀 수 있어요.") }
            onConfirm = { run { app.community.block(action.profile.publicId); "차단했어요" } }
        }
        is CommunityAction.AdminHidePost -> {
            title = "이 글을 숨길까요?".toDinoCopy()
            confirm = "숨기기"
            body = { Text("작성자와 관리자에게만 \"숨김 처리됨\"으로 보여요. 숨긴 글은 신고함의 숨긴 글에서 복구할 수 있어요.") }
            onConfirm = { run { app.community.hidePost(action.post.id); "글을 숨겼어요" } }
        }
        is CommunityAction.AdminSuspend -> {
            title = withName(action.profile.nickname, "님의 이용을 정지할까요?")
            confirm = "정지"
            body = { Text("글과 프로필이 다른 사용자에게 보이지 않고, 글·댓글·좋아요·신고를 할 수 없어요. 신고함의 정지 계정에서 풀 수 있어요.") }
            onConfirm = { run { app.community.suspend(action.profile.publicId); "이용을 정지했어요" } }
        }
        is CommunityAction.AdminResolve -> {
            val what = reportTargetLabel(action.report.target)
            title = (if (action.kept) "그대로 둘까요?" else if (action.report.target == ReportTarget.USER) "이 사용자를 정지할까요?" else "이 $what 을(를) 지울까요?").toDinoCopy()
            confirm = if (action.kept) "그대로 두기" else if (action.report.target == ReportTarget.USER) "정지" else "지우기"
            body = { Text(if (action.kept) "규칙 위반이 아니라고 처리하고 숨김을 풀어요. 신고한 사람에게 결과가 보여요." else "되돌릴 수 없어요. 신고한 사람에게 결과가 보여요.") }
            onConfirm = { run { app.community.resolve(action.report.target, action.report.targetId, action.kept); "처리했어요" } }
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
        title = { UserText(title) }, // 제목은 위에서 고정 부분만 공룡 말투로 바꿨다(닉네임은 원문)
        text = {
            Column {
                body()
                error?.let { Text(it, color = c.err, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("취소") } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                val calm = action is CommunityAction.Report || (action is CommunityAction.AdminResolve && action.kept)
                Text(if (busy) "보내는 중…" else confirm, color = if (calm) c.primaryText else c.err)
            }
        },
    )
}

/** 닉네임 + 앱 문구 — 앱 문구만 공룡 말투로 바꾼다(닉네임 안의 '…요 '까지 바뀌지 않게). */
internal fun withName(name: String, rest: String): String = name + rest.toDinoCopy()
