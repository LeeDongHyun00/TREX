package com.example.trex_kotlin.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.trex_kotlin.AccountSignInButtons
import com.example.trex_kotlin.AppViewModel
import com.example.trex_kotlin.Cta
import com.example.trex_kotlin.DField
import com.example.trex_kotlin.GhostButton
import com.example.trex_kotlin.RoundIcon
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.Workout
import com.example.trex_kotlin.community.CommunityGate
import com.example.trex_kotlin.fullScreenDialogProperties
import com.example.trex_kotlin.rememberTextSharer
import com.example.trex_kotlin.routineOverview
import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.NetworkException
import com.example.trex_kotlin.posture.toDinoCopy
import kotlinx.coroutines.launch
import androidx.compose.material3.Text as UserText
import com.example.trex_kotlin.TrexText as Text

/*
 * 루틴 공유 화면(docs/superpowers/specs/2026-10-29-routine-share-design.md §3·§4) — 공유 창, 내 루틴 보관함, 링크로 받은 루틴.
 * 사용자가 적은 루틴 이름·닉네임은 공룡 말투로 바꾸지 않는다(UserText).
 */

/** 공유 창이 보일 단계 — 공유하려면 운동이 있고, 로그인했고, 커뮤니티 준비(닉네임·이용규칙, 정지 아님)가 끝나야 한다. */
enum class ShareStep { NoWorkouts, NeedLogin, Loading, Suspended, NeedCommunitySetup, Ready }

/** 커뮤니티 상태를 아직 받는 중([CommunityGate.Loading])이면 "준비를 마치면…" 대신 기다린다 — 준비된 사람에게 잠깐 잘못된 안내가 보였다. */
fun shareStep(hasWorkouts: Boolean, signedIn: Boolean, gate: CommunityGate): ShareStep = when {
    !hasWorkouts -> ShareStep.NoWorkouts
    !signedIn -> ShareStep.NeedLogin
    gate == CommunityGate.Loading -> ShareStep.Loading
    gate is CommunityGate.Suspended -> ShareStep.Suspended
    gate !is CommunityGate.Ready -> ShareStep.NeedCommunitySetup
    else -> ShareStep.Ready
}

/** "운동 N개 · 약 M분". */
internal fun routineMeta(workouts: List<Workout>): String = routineOverview(workouts).let { "운동 ${it.count}개 · 약 ${it.minutes}분" }

/** 앱과 같은 전체 화면 창 — 위에 ← 와 제목. */
@Composable
private fun FullScreen(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Trex.c
    Dialog(onDismissRequest = onDismiss, properties = fullScreenDialogProperties()) {
        Column(
            Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, onClick = onDismiss, contentDescription = "뒤로")
                Text(title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/** 오늘 루틴을 바꾸기 전 확인 — 보관함·받은 루틴이 같이 쓴다. */
@Composable
private fun ConfirmApplyDialog(app: AppViewModel, name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Trex.c.sheet,
        title = { UserText("지금 루틴을 '$name'" + "으로 바꿀까요?".toDinoCopy()) },
        text = { Text("지금 오늘 루틴(운동 ${app.workoutPlan.size}개)은 사라져요. 남겨 두려면 먼저 '내 루틴'에 저장하세요.") },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("바꾸기") } },
    )
}

/** 운동 목록 한 줄씩 — 이름, 횟수, 자세 교정 표시. */
@Composable
private fun WorkoutRows(workouts: List<Workout>) {
    val c = Trex.c
    workouts.forEach { w ->
        Surface(shape = RoundedCornerShape(14.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                UserText(w.name, color = c.text, fontSize = 15.sp)
                Text(w.reps + if (w.posture) " · 자세 교정" else "", color = c.text2, fontSize = 12.sp)
            }
        }
    }
}

/**
 * 공유 창 — 로그인·커뮤니티 준비(닉네임·이용규칙, 정지 아님)가 되어 있어야 보낼 수 있다(보낸 사람 이름 = 커뮤니티 닉네임).
 * "카카오톡 등으로 보내기"는 서버에 올려 받은 링크를 폰 공유 창에 넘긴다.
 */
@Composable
fun ShareRoutineSheet(app: AppViewModel, workouts: List<Workout>, initialName: String?, onOpenCommunity: () -> Unit, onDismiss: () -> Unit) {
    val c = Trex.c
    val scope = rememberCoroutineScope()
    val share = rememberTextSharer()
    var name by remember { mutableStateOf(initialName ?: "${routineOverview(workouts).focus.title} 루틴") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val account = app.account
    LaunchedEffect(account) { if (account != null) runCatching { app.refreshCommunityMe() } }
    FullScreen("루틴 공유", onDismiss) {
        when (shareStep(workouts.isNotEmpty(), account != null, app.communityGate)) {
            ShareStep.NoWorkouts -> Text("공유할 운동이 없어요", color = c.text2, fontSize = 15.sp)
            ShareStep.NeedLogin -> {
                Text("공유하려면 로그인이 필요해요", color = c.text, fontSize = 16.sp)
                AccountSignInButtons(app)
                app.authMessage?.let { Text(it, color = c.err, fontSize = 13.sp) }
            }
            ShareStep.Loading -> CircularProgressIndicator(color = c.primaryText)
            ShareStep.Suspended -> Text("이용이 정지된 계정이라 공유할 수 없어요", color = c.text, fontSize = 16.sp)
            ShareStep.NeedCommunitySetup -> {
                Text("커뮤니티 닉네임과 이용규칙 동의를 마치면 공유할 수 있어요", color = c.text, fontSize = 16.sp)
                Cta("커뮤니티로 가기", onClick = { onOpenCommunity(); onDismiss() }, modifier = Modifier.fillMaxWidth())
            }
            ShareStep.Ready -> {
                val trimmed = name.trim()
                DField(name, { name = it.take(30) }, "하체 불태우기", label = "루틴 이름", hint = "받는 사람에게 보일 이름이에요 (30자까지)")
                UserText(routineMeta(workouts), color = c.text2, fontSize = 13.sp)
                Text("링크를 받은 사람은 누구나 루틴과 내 닉네임을 볼 수 있어요", color = c.text2, fontSize = 13.sp)
                Cta(
                    if (busy) "링크 만드는 중…" else "카카오톡 등으로 보내기",
                    onClick = {
                        scope.launch {
                            busy = true
                            message = null
                            try {
                                val url = app.shareRoutine(trimmed, workouts)
                                share("$trimmed — T-REX 루틴\n$url")
                            } catch (e: ApiException) {
                                message = e.message
                            } catch (_: NetworkException) {
                                message = "네트워크에 연결하지 못했어요"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && trimmed.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                GhostButton(
                    "내 루틴 보관함에도 저장",
                    onClick = { message = app.saveRoutine(trimmed, workouts, null) ?: "보관함에 저장했어요" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        message?.let { Text(it, color = c.text2, fontSize = 13.sp) }
    }
}

/** 내 루틴 보관함 — 최근 저장 순, 루틴마다 오늘 루틴으로·공유·삭제. */
@Composable
fun RoutineLibraryScreen(app: AppViewModel, onShare: (SavedRoutine) -> Unit, onDismiss: () -> Unit) {
    val c = Trex.c
    var applying by remember { mutableStateOf<SavedRoutine?>(null) }
    var deleting by remember { mutableStateOf<SavedRoutine?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    FullScreen("내 루틴", onDismiss) {
        notice?.let { Text(it, color = c.text2, fontSize = 13.sp) }
        if (app.savedRoutines.isEmpty()) {
            Text("저장한 루틴이 없어요. 오늘 루틴을 공유하거나 받은 루틴을 저장해 보세요.", color = c.text2, fontSize = 14.sp)
        }
        app.savedRoutines.forEach { r ->
            Surface(shape = RoundedCornerShape(18.dp), color = c.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    UserText(r.name, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    UserText(routineMeta(r.workouts) + (r.from?.let { " · ${it}님이 보냄" } ?: ""), color = c.text2, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { applying = r }) { Text("오늘 루틴으로") }
                        TextButton(onClick = { onShare(r) }) { Text("공유") }
                        TextButton(onClick = { deleting = r }) { Text("삭제", color = c.err) }
                    }
                }
            }
        }
    }
    applying?.let { r ->
        ConfirmApplyDialog(app, r.name, onConfirm = { app.applyRoutine(r.name, r.workouts); applying = null; notice = "오늘 루틴을 바꿨어요" },
            onDismiss = { applying = null })
    }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = c.sheet,
            title = { UserText("'${r.name}'" + "을 지울까요?".toDinoCopy()) },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
            confirmButton = { TextButton(onClick = { app.deleteRoutine(r.id); deleting = null }) { Text("지우기", color = c.err) } },
        )
    }
}

/** 링크로 받은 루틴 — 이름·보낸 사람·운동 목록, "오늘 루틴으로 바로 적용"(확인 뒤)·"내 루틴에 저장". */
@Composable
fun ReceivedRoutineDialog(app: AppViewModel, code: String, onDismiss: () -> Unit) {
    val c = Trex.c
    var routine by remember { mutableStateOf<SharedRoutine?>(null) }
    var failed by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(code) {
        runCatching { app.loadSharedRoutine(code) }
            .onSuccess { routine = it }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else failed = true }
    }
    FullScreen("받은 루틴", onDismiss) {
        val r = routine
        when {
            failed -> Text("없어졌거나 잘못된 링크예요", color = c.text, fontSize = 16.sp)
            r == null -> CircularProgressIndicator(color = c.primaryText)
            else -> {
                UserText(r.name, color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                UserText(routineMeta(r.workouts) + " · ${r.senderNickname}님이 보냄", color = c.text2, fontSize = 13.sp)
                WorkoutRows(r.workouts)
                Cta("오늘 루틴으로 바로 적용", onClick = { confirm = true }, enabled = r.workouts.isNotEmpty(), modifier = Modifier.fillMaxWidth())
                GhostButton(
                    "내 루틴에 저장",
                    onClick = { notice = app.saveRoutine(r.name, r.workouts, r.senderNickname) ?: "내 루틴에 저장했어요" },
                    modifier = Modifier.fillMaxWidth(),
                )
                notice?.let { Text(it, color = c.text2, fontSize = 13.sp) }
            }
        }
    }
    val r = routine
    if (confirm && r != null) {
        ConfirmApplyDialog(app, r.name, onConfirm = { app.applyRoutine(r.name, r.workouts); confirm = false; onDismiss() },
            onDismiss = { confirm = false })
    }
}
