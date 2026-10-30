package com.example.trex_kotlin.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.rememberLazyListState
import com.example.trex_kotlin.AppViewModel
import com.example.trex_kotlin.PlatformBackHandler
import com.example.trex_kotlin.nowMillis
import com.example.trex_kotlin.Trex
import com.example.trex_kotlin.Cta
import com.example.trex_kotlin.GhostButton
import com.example.trex_kotlin.AccountSignInButtons
import com.example.trex_kotlin.PasswordAuthDialog
import com.example.trex_kotlin.DField
import com.example.trex_kotlin.IdSignInButton
import com.example.trex_kotlin.SignInBrand
import com.example.trex_kotlin.SocialSignInButton
import com.example.trex_kotlin.rememberAppleCredentialRequest
import com.example.trex_kotlin.rememberGoogleIdTokenRequest
import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.NetworkException
import com.example.trex_kotlin.tabContentPadding
import kotlinx.coroutines.launch

/**
 * 커뮤니티 탭(설계 §2·§5 진입) — 게이트([CommunityGate])에 따라 안내·닉네임·이용규칙 동의·피드를 보인다.
 * 커뮤니티만 서버 계정이 필요하고, 다른 탭은 계정 없이도 그대로다(설계 §2 반목표).
 */
@Composable
fun CommunityTabScreen(app: AppViewModel) {
    val gate = app.communityGate
    val loginId = app.account?.loginId
    LaunchedEffect(loginId) {
        if (loginId != null && app.communityMe == null) app.refreshCommunityMe()
    }
    CommunityWidth { CommunityGateContent(app, gate) }
}

/**
 * 커뮤니티 화면 폭 — 펼친 폴드·태블릿에서 사진이 화면 폭을 다 채우면 너무 커서(실기기 2026-10-29) 인스타처럼 600dp 로 줄여
 * 가운데에 둔다. 접은 폰은 그대로 꽉 찬다.
 */
@Composable
private fun CommunityWidth(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = COMMUNITY_MAX_WIDTH).fillMaxSize()) { content() }
    }
}

internal val COMMUNITY_MAX_WIDTH = 600.dp

@Composable
private fun CommunityGateContent(app: AppViewModel, gate: CommunityGate) {
    when (gate) {
        CommunityGate.NoLogin -> CommunityNotice(
            title = "이 기기에서는 아직 커뮤니티를 쓸 수 없어요",
            body = "운동·식단·자세 기능은 그대로 쓸 수 있어요.",
        )
        CommunityGate.NotSignedIn -> SignInNotice(app)
        CommunityGate.Loading -> LoadingNotice(app)
        CommunityGate.NeedNickname -> NicknameSetup(app)
        CommunityGate.NeedTerms -> TermsConsent(app)
        is CommunityGate.Ready, is CommunityGate.Suspended -> CommunityNavigation(app, gate)
    }
}

/** 커뮤니티 탭 안의 화면 — 피드 위에 상세·글쓰기·프로필을 쌓는다. 뒤로 가면 하나씩 닫는다. */
private sealed interface CommunityRoute {
    data class Detail(val post: Post, val owner: FeedController) : CommunityRoute
    data class Compose(val card: WorkoutCardData? = null) : CommunityRoute
    data class Profile(val profile: CommunityProfile, val posts: FeedController) : CommunityRoute
}

@Composable
private fun CommunityNavigation(app: AppViewModel, gate: CommunityGate) {
    var stack by remember { mutableStateOf(listOf<CommunityRoute>()) }
    val feedList = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun push(route: CommunityRoute) { stack = stack + route }
    // 프로필의 글 목록은 화면이 아니라 쌓기가 든다 — 상세에 다녀오면 프로필 화면은 다시 그려지지만 목록·좋아요 보내기는 이어진다
    fun openProfile(profile: CommunityProfile) = push(CommunityRoute.Profile(profile, FeedController(
        load = { before -> app.community.userPosts(profile.publicId, before) },
        setLike = { id, liked -> if (liked) app.community.like(id) else app.community.unlike(id) },
        scope = scope,
        now = ::nowMillis,
    )))
    fun pop() { stack = stack.dropLast(1) }
    PlatformBackHandler(enabled = stack.isNotEmpty()) { pop() }
    // 운동 완료 화면의 "오운완 올리기" — 카드를 넣은 글쓰기를 연다
    val pendingCard = app.pendingWorkoutCard
    LaunchedEffect(pendingCard) {
        if (pendingCard != null) {
            // 닉네임·동의 단계에서는 이 화면이 아직 없어 카드가 기다린다. 정지된 계정이면 버린다(올릴 수 없다).
            if (gate.canWrite) push(CommunityRoute.Compose(pendingCard))
            app.pendingWorkoutCard = null
        }
    }
    // 메신저로 받은 게시글 링크(trex://p/{id}) — 이용 준비가 끝난 이 화면에서 상세를 연다. 숨김·삭제된 글이면 알린다.
    val pendingPostId = app.pendingPostId
    var linkError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingPostId) {
        if (pendingPostId != null) {
            runCatching { app.community.post(pendingPostId) }
                .onSuccess { push(CommunityRoute.Detail(it, app.communityFeed)) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else linkError = "볼 수 없는 글이에요" }
            app.pendingPostId = null
        }
    }
    linkError?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { linkError = null },
            containerColor = Trex.c.sheet,
            text = { Text(message) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { linkError = null }) { Text("확인") } },
        )
    }
    when (val top = stack.lastOrNull()) {
        null -> FeedScreen(
            app = app,
            gate = gate,
            listState = feedList,
            onCompose = { push(CommunityRoute.Compose()) },
            onOpenPost = { push(CommunityRoute.Detail(it, app.communityFeed)) },
            onOpenProfile = ::openProfile,
        )
        is CommunityRoute.Detail -> PostDetailScreen(
            app = app,
            initial = top.post,
            owner = top.owner,
            gate = gate,
            onBack = ::pop,
            onOpenProfile = ::openProfile,
        )
        is CommunityRoute.Compose -> ComposeScreen(
            app = app,
            card = top.card,
            onClose = ::pop,
            onPosted = { pop(); scope.launch { feedList.scrollToItem(0) } },
        )
        is CommunityRoute.Profile -> ProfileScreen(
            app = app,
            initial = top.profile,
            posts = top.posts,
            gate = gate,
            onBack = ::pop,
            onOpenPost = { post, owner -> push(CommunityRoute.Detail(post, owner)) },
        )
    }
}

@Composable
private fun NoticeColumn(content: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = tabContentPadding, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("커뮤니티", color = Trex.c.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold) }
        item { content() }
    }
}

@Composable
private fun NoticeCard(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = Trex.c.surface) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun CommunityNotice(title: String, body: String) {
    val c = Trex.c
    NoticeColumn {
        NoticeCard {
            Icon(Icons.Rounded.Forum, null, tint = c.primaryText, modifier = Modifier.size(28.dp))
            Text(title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = c.text2, fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
}

/** 계정이 없다 — 설정의 계정 연결과 같은 흐름(로그인하면 기록 백업도 켜진다, 설계 §3 로그인 시 백업). */
@Composable
private fun SignInNotice(app: AppViewModel) {
    val c = Trex.c
    NoticeColumn {
        NoticeCard {
            Icon(Icons.Rounded.Forum, null, tint = c.primaryText, modifier = Modifier.size(28.dp))
            Text("커뮤니티는 계정을 연결하면 쓸 수 있어요", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "오운완·루틴·대회 소식을 나눠요. 계정을 연결하면 운동·식단 기록도 계정에 백업돼요. 다른 기능은 계정 없이도 그대로예요.",
                color = c.text2, fontSize = 14.sp, lineHeight = 21.sp,
            )
            AccountSignInButtons(app)
            app.authMessage?.let { Text(it, color = c.err, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun LoadingNotice(app: AppViewModel) {
    val c = Trex.c
    val scope = rememberCoroutineScope()
    NoticeColumn {
        val message = app.communityMessage
        if (message == null) {
            Row(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = c.primaryText)
            }
        } else {
            NoticeCard {
                Text(message, color = c.text, fontSize = 16.sp)
                Cta(
                    text = "다시 시도", onClick = { scope.launch { app.refreshCommunityMe() } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 처음 들어올 때 공개 이름을 정한다(설계 §3 공개 이름) — 구글 이름·이메일은 다른 사용자에게 보이지 않는다. */
@Composable
private fun NicknameSetup(app: AppViewModel) {
    val c = Trex.c
    val scope = rememberCoroutineScope()
    var nickname by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val length = codePointLength(nickname.trim())
    NoticeColumn {
        NoticeCard {
            Text("커뮤니티에서 쓸 닉네임을 정해 주세요", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("다른 사용자에게는 닉네임과 프로필 사진만 보여요. 구글 이름·이메일은 보이지 않아요.", color = c.text2, fontSize = 14.sp, lineHeight = 21.sp)
            DField(
                value = nickname,
                onValueChange = { nickname = takeCodePoints(it, 12); error = null },
                placeholder = "닉네임",
                hint = "2~12자 · 나중에 프로필에서 바꿀 수 있어요",
            )
            error?.let { Text(it, color = c.err, fontSize = 13.sp) }
            Cta(
                text = if (busy) "저장 중…" else "다음",
                enabled = !busy && length in 2..12,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            app.community.setNickname(nickname.trim())
                            app.refreshCommunityMe()
                        } catch (e: ApiException) {
                            error = e.message // 이미 쓰는 닉네임·금칙어 — 서버 문구 그대로
                        } catch (_: NetworkException) {
                            error = "네트워크에 연결하지 못했어요"
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 이용규칙 동의(설계 §4) — 만 14세 이상 확인과 이용규칙 전문 링크. 규칙이 바뀌면(버전) 다시 묻는다. */
@Composable
private fun TermsConsent(app: AppViewModel) {
    val c = Trex.c
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var over14 by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    NoticeColumn {
        NoticeCard {
            Text("커뮤니티 이용규칙", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            listOf(
                "올린 글과 사진은 서버에 저장되고 모든 사용자에게 공개돼요.",
                "욕설·혐오, 음란·폭력, 스팸·광고, 남의 개인정보나 권리를 침해하는 글은 올릴 수 없어요.",
                "신고된 글은 운영자가 24시간 안에 확인하고, 규칙을 어기면 지우거나 이용을 정지해요.",
                "싫은 사람은 차단할 수 있어요. 차단한 사람의 글은 보이지 않아요.",
            ).forEach { line ->
                Row {
                    Text("·", color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                    Text(line, color = c.text2, fontSize = 14.sp, lineHeight = 21.sp)
                }
            }
            Text(
                "이용규칙 전문 보기",
                color = c.primaryText, fontSize = 14.sp, textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { uri.openUri(CommunityApi.TERMS_URL) }.padding(vertical = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().clickable { over14 = !over14 },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = over14, onCheckedChange = { over14 = it },
                    colors = CheckboxDefaults.colors(checkedColor = c.primary, uncheckedColor = c.text3),
                )
                Text("만 14세 이상이에요", color = c.text, fontSize = 15.sp)
            }
            error?.let { Text(it, color = c.err, fontSize = 13.sp) }
            Cta(
                text = if (busy) "저장 중…" else "동의하고 시작하기",
                enabled = over14 && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            app.community.acceptTerms()
                            app.refreshCommunityMe()
                        } catch (e: ApiException) {
                            error = e.message
                        } catch (_: NetworkException) {
                            error = "네트워크에 연결하지 못했어요"
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
