package com.example.trex_kotlin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import com.example.trex_kotlin.TrexText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.trex_kotlin.sync.SignupForm
import com.example.trex_kotlin.sync.loginIdProblem
import com.example.trex_kotlin.sync.passwordProblem
import com.example.trex_kotlin.sync.signupProblem
import kotlinx.coroutines.delay

/** 로그인 로고 애니메이션의 프레임 수 — 그림은 플랫폼이 준다([loginAnimationFrame], Android 는 res/drawable-nodpi). */
private const val LOGIN_ANIMATION_FRAMES = 16

// ============================================================= AUTH (아이디·비밀번호 / Apple / Google / 로그인 없이 사용)

private const val PRIVACY_URL = "https://leedonghyun00.github.io/TREX/privacy.html"

/**
 * 시작 화면. 계정은 기록 백업·기기 이전과 커뮤니티용이다 — 계정 없이도 커뮤니티를 뺀 모든 기능을 쓴다
 * (docs/SERVER_SYNC_HANDOFF.md 2단계, 커뮤니티 설계 docs/superpowers/specs/2026-10-28-community-design.md §3 계정).
 * 위는 아이디·비밀번호 로그인/회원가입 탭(D13 — 처음 계획대로), 아래는 Apple(iPhone)·Google·로그인 없이 사용. 계정은 서로 따로다.
 */
@Composable
fun AuthScreen(
    busy: Boolean,
    message: String?,
    onPasswordLogin: (loginId: String, password: String) -> Unit,
    onSignup: (SignupForm) -> Unit,
    onGoogle: () -> Unit,
    onWithoutAccount: () -> Unit,
    /** iPhone 만 참 — Apple 버튼을 Google 위에(App Store 지침 4.8, 설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md). */
    appleAvailable: Boolean = false,
    onApple: () -> Unit = {},
) {
    val c = Trex.c
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        for (next in 1 until LOGIN_ANIMATION_FRAMES) {
            delay(95)
            frame = next
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp)
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        // 로고 카드 + 워드마크
        Surface(
            modifier = Modifier.size(84.dp),
            shape = RoundedCornerShape(28.dp),
            color = c.surface,
            border = BorderStroke(1.dp, c.line),
            shadowElevation = 2.dp,
        ) {
            LoginLogo(frame, Modifier.fillMaxSize().padding(5.dp))
        }
        Text(
            "TREX",
            color = c.primaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text("움직임을 보고 함께 운동해룡", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))

        PasswordAuthForm(busy, message, onPasswordLogin, onSignup, Modifier.padding(top = 24.dp))

        OrDivider(Modifier.padding(vertical = 18.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (appleAvailable) AppleSignInButton(onClick = { if (!busy) onApple() })
            GhostButton(
                "Google로 계속하기",
                onClick = { if (!busy) onGoogle() },
                icon = Icons.AutoMirrored.Rounded.Login,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "어느 계정이든 기록이 백업돼 기기를 바꿔도 이어서 쓸 수 있어요",
                color = c.text2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "로그인 없이 사용",
                color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { if (!busy) onWithoutAccount() }.padding(vertical = 10.dp),
            )
            Text(
                "기록은 이 기기에만 저장돼요. 나중에 설정에서 계정을 연결할 수 있어요",
                color = c.text3, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 아이디·비밀번호 로그인/회원가입 탭(D13) — 10-13 에 지웠던 동현님 화면을 서버에 연결해 되살렸다. 시작 화면과 [PasswordAuthDialog] 가 같이 쓴다.
 * 회원가입은 [signupProblem] 이 없을 때만 누를 수 있고, 아이디·비밀번호 칸은 적기 시작하면 안내 자리에 그 칸의 문제를 보인다.
 * 비밀번호 찾기는 아직 없어서 가입 버튼 위에서 알린다. [message] 는 로그인 실패 문구(구글·Apple 실패도 여기 보인다).
 */
@Composable
fun PasswordAuthForm(
    busy: Boolean,
    message: String?,
    onLogin: (loginId: String, password: String) -> Unit,
    onSignup: (SignupForm) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Trex.c
    val uri = LocalUriHandler.current
    var signupMode by rememberSaveable { mutableStateOf(false) }
    var id by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var suName by rememberSaveable { mutableStateOf("") }
    var suId by rememberSaveable { mutableStateOf("") }
    var suPw by rememberSaveable { mutableStateOf("") }
    var suPw2 by rememberSaveable { mutableStateOf("") }
    var suEmail by rememberSaveable { mutableStateOf("") }
    var agree by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        SegmentedTabs(
            options = listOf("로그인", "회원가입"),
            selected = if (signupMode) 1 else 0,
            onSelect = { signupMode = it == 1 },
        )
        AnimatedContent(
            targetState = signupMode,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(tween(300)) { it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally(tween(300)) { -it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally(tween(300)) { -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally(tween(300)) { it / 4 } + fadeOut())
                }
            },
            label = "auth-mode",
            modifier = Modifier.padding(top = 18.dp),
        ) { signup ->
            if (!signup) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    DField(id, { id = it }, "아이디")
                    DField(password, { password = it }, "비밀번호", password = true, keyboardType = KeyboardType.Password)
                    Spacer(Modifier.height(6.dp))
                    Cta(
                        if (busy) "로그인 중…" else "로그인",
                        onClick = { onLogin(id, password) },
                        enabled = !busy && id.isNotBlank() && password.isNotEmpty(),
                        icon = Icons.AutoMirrored.Rounded.Login,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                val form = SignupForm(suName, suId, suPw, suPw2, suEmail, agree)
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    DField(suName, { suName = it }, "지민", label = "이름", hint = "실명이 아니어도 괜찮아요")
                    DField(
                        suId, { suId = it }, "trex_user", label = "아이디",
                        hint = loginIdProblem(suId).takeIf { suId.isNotEmpty() } ?: "영문·숫자·밑줄 4~20자",
                    )
                    DField(
                        suPw, { suPw = it }, "••••••••", label = "비밀번호",
                        hint = passwordProblem(suPw).takeIf { suPw.isNotEmpty() } ?: "8자 이상, 영문·숫자 조합",
                        password = true, keyboardType = KeyboardType.Password,
                    )
                    DField(
                        suPw2, { suPw2 = it }, "••••••••", label = "비밀번호 확인",
                        hint = if (suPw2.isNotEmpty() && suPw != suPw2) "비밀번호가 일치하지 않아요" else "한 번 더 입력해 주세요",
                        password = true, keyboardType = KeyboardType.Password,
                    )
                    DField(
                        suEmail, { suEmail = it }, "jimin@trex.app", label = "이메일",
                        hint = "나중에 비밀번호 찾기를 만들면 이 주소를 써요", keyboardType = KeyboardType.Email,
                    )
                    Surface(
                        onClick = { agree = !agree },
                        shape = RoundedCornerShape(16.dp),
                        color = c.surface,
                        border = BorderStroke(1.dp, c.line),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (agree) c.primary else Color.Transparent)
                                    .border(1.dp, if (agree) c.primary else c.fieldLine, RoundedCornerShape(7.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (agree) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                            }
                            Text(
                                "개인정보 처리방침에 동의합니다",
                                color = c.text, fontSize = 12.5.sp, lineHeight = 17.sp,
                                modifier = Modifier.padding(start = 11.dp).weight(1f),
                            )
                            Text(
                                "보기",
                                color = c.primaryText, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { uri.openUri(PRIVACY_URL) }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                            )
                        }
                    }
                    Text(
                        "아직 비밀번호 찾기가 없어요. 비밀번호를 잊으면 이 계정을 되찾을 수 없으니 꼭 기억해 주세요.",
                        color = c.text2, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                    Cta(
                        if (busy) "가입 중…" else "가입 완료",
                        onClick = { onSignup(form) },
                        enabled = !busy && signupProblem(form) == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (message != null) {
            Text(message, color = c.err, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }
}

/** 시작 화면의 아이디 칸과 소셜 버튼 사이 "또는". */
@Composable
private fun OrDivider(modifier: Modifier = Modifier) {
    val c = Trex.c
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.line))
        Text("또는", color = c.text3, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.line))
    }
}

/**
 * 설정의 계정 칸·커뮤니티 안내의 "아이디로 로그인" — 시작 화면과 같은 칸을 전체 화면 창으로 띄운다(D13).
 * 열 때 지난 로그인 실패 문구를 지우고, 로그인·가입이 되면 저절로 닫힌다.
 */
@Composable
fun PasswordAuthDialog(app: AppViewModel, onDismiss: () -> Unit) {
    val c = Trex.c
    LaunchedEffect(Unit) { app.clearAuthMessage() }
    LaunchedEffect(app.account) { if (app.account != null) onDismiss() }
    Dialog(onDismissRequest = onDismiss, properties = fullScreenDialogProperties()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, onClick = onDismiss, contentDescription = "뒤로")
                Text("아이디로 로그인", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            }
            PasswordAuthForm(
                busy = app.authBusy,
                message = app.authMessage,
                onLogin = { id, pw -> app.loginWithPassword(id, pw) },
                onSignup = { app.signUpWithPassword(it) },
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

// ============================================================= ONBOARDING

private data class OnbChoice(val id: String, val label: String, val desc: String, val icon: ImageVector)

private val onbGoals = listOf(
    OnbChoice("muscle", "근육 증가", "무거운 중량 위주, 분할 루틴 중심", Icons.Rounded.FitnessCenter),
    OnbChoice("diet", "다이어트", "유산소와 고반복 운동 중심", Icons.Rounded.LocalFireDepartment),
    OnbChoice("stamina", "체력 향상", "전신 기능성 운동 중심", Icons.Rounded.PlayArrow),
    OnbChoice("maintain", "유지", "현재 체력 유지, 균형 잡힌 루틴", Icons.Rounded.Check),
)

private val onbPlaces = listOf(
    OnbChoice("gym", "헬스장", "기구와 머신 기반", Icons.Rounded.FitnessCenter),
    OnbChoice("home", "홈트", "집에서 가능한 루틴", Icons.Rounded.Home),
    OnbChoice("both", "둘 다", "상황에 맞춰 전환", Icons.Rounded.Check),
)

private data class EquipCat(val name: String, val items: List<String>)

private val equipCats = listOf(
    EquipCat("프리웨이트", listOf("덤벨", "바벨", "EZ바", "케틀벨", "플레이트")),
    EquipCat("머신", listOf("케이블 머신", "스미스 머신", "레그프레스", "랫풀다운", "체스트프레스 머신", "시티드 로우 머신", "레그컬 머신", "레그익스텐션 머신", "펙덱 플라이 머신", "숄더프레스 머신", "힙 어브덕터/어덕터 머신")),
    EquipCat("벤치 및 랙", listOf("플랫 벤치", "인클라인 벤치", "디클라인 벤치", "스쿼트 랙", "딥스 바", "풀업 바 (치닝디핑)")),
    EquipCat("유산소 기구", listOf("러닝머신", "실내 자전거", "로잉머신", "스텝퍼", "일립티컬")),
    EquipCat("보조 도구", listOf("저항 밴드", "ab 롤러", "폼롤러", "짐볼")),
)

private val equipAll: List<String> = equipCats.flatMap { it.items }
private fun equipBit(label: String): Int = 1 shl equipAll.indexOf(label).coerceAtLeast(0)
private fun equipMaskOf(items: List<String>): Int = items.fold(0) { acc, l -> acc or equipBit(l) }

private val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")

@Composable
fun OnboardingScreen(onDone: (UserProfile) -> Unit) {
    val c = Trex.c
    var step by rememberSaveable { mutableIntStateOf(0) }
    var goal by rememberSaveable { mutableStateOf<String?>(null) }
    var dayMask by rememberSaveable { mutableIntStateOf(0) }
    var place by rememberSaveable { mutableStateOf<String?>(null) }
    var bodyweightOnly by rememberSaveable { mutableStateOf(false) }
    var equipMask by rememberSaveable { mutableIntStateOf(0) }
    var equipOpenMask by rememberSaveable { mutableIntStateOf(1) }
    var bodyStep by rememberSaveable { mutableIntStateOf(0) }
    var gender by rememberSaveable { mutableStateOf("none") }
    var heightInput by rememberSaveable { mutableStateOf("") }
    var weightInput by rememberSaveable { mutableStateOf("") }
    var ageInput by rememberSaveable { mutableStateOf("") }

    val canNext = when (step) {
        0 -> goal != null
        1 -> dayMask != 0
        2 -> place != null
        else -> when (bodyStep) {
            0 -> gender != "none"
            1 -> heightInput.isNotBlank()
            2 -> weightInput.isNotBlank()
            else -> ageInput.isNotBlank()
        }
    }

    fun finish() {
        val d = UserProfile()
        onDone(
            UserProfile(
                goal = goal ?: d.goal,
                dayMask = dayMask,
                place = place,
                bodyweightOnly = bodyweightOnly,
                equipmentMask = equipMask,
                gender = gender,
                heightCm = heightInput.toDoubleOrNull() ?: d.heightCm,
                weightKg = weightInput.toDoubleOrNull() ?: d.weightKg,
                age = ageInput.toIntOrNull() ?: d.age,
            ),
        )
    }

    fun goBack() {
        if (step == 3 && bodyStep > 0) bodyStep -= 1 else if (step > 0) step -= 1
    }

    fun goNext() {
        if (!canNext) return
        if (step < 3) {
            step += 1
        } else if (bodyStep < 3) {
            bodyStep += 1
        } else {
            finish()
        }
    }

    PlatformBackHandler(enabled = step > 0 || bodyStep > 0) { goBack() }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 48.dp)) {
            Text("${step + 1}/4", color = c.primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .weight(1f).height(5.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(if (i <= step) c.primary else c.track),
                    )
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(top = 22.dp, bottom = 8.dp),
        ) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally(tween(320)) { it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut())
                    } else {
                        (slideInHorizontally(tween(320)) { -it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally(tween(320)) { it / 4 } + fadeOut())
                    }
                },
                label = "onb-step",
            ) { visible ->
                when (visible) {
                    0 -> Column {
                        Text(
                            "운동 목표를 선택해주세요",
                            color = c.text, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            onbGoals.forEach { g -> OnbChoiceCard(g, selected = goal == g.id, onClick = { goal = g.id }) }
                        }
                        Text(
                            "건너뛰기",
                            color = c.text2, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth().padding(top = 16.dp)
                                .clickable { goal = "general"; step = 1 }
                                .padding(vertical = 8.dp),
                        )
                        Text("건너뛰면 범용 전신 운동 루틴으로 시작합니다.", color = c.text3, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }

                    1 -> Column {
                        Text(
                            "운동할 요일을 선택해주세요",
                            color = c.text, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            dayLabels.forEachIndexed { i, d ->
                                val sel = dayMask and (1 shl i) != 0
                                Surface(
                                    onClick = { dayMask = dayMask xor (1 shl i) },
                                    modifier = Modifier.size(38.dp),
                                    shape = CircleShape,
                                    color = if (sel) c.primary else c.surface,
                                    contentColor = if (sel) Color.White else c.text2,
                                    border = BorderStroke(1.dp, if (sel) c.primary else c.line),
                                    shadowElevation = if (sel) 4.dp else 1.dp,
                                ) {
                                    Box(contentAlignment = Alignment.Center) { Text(d, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                                }
                            }
                        }
                        val count = dayMask.countOneBits()
                        Surface(
                            modifier = Modifier.padding(top = 24.dp).fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            color = if (count > 0) c.primaryWash else c.surface,
                            contentColor = c.text,
                            border = BorderStroke(1.dp, if (count > 0) c.primarySoftLine else c.line),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("주 ${count}일 운동", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = if (count == 0) "최소 1일 이상 선택해야 다음으로 진행할 수 있습니다."
                                    else dayLabels.filterIndexed { i, _ -> dayMask and (1 shl i) != 0 }.joinToString(" · "),
                                    color = if (count > 0) c.text2 else c.text3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        if (hasThreeConsecutiveDays(dayMask)) {
                            Spacer(Modifier.height(12.dp))
                            WashBanner("연속 운동일이 3일 이상이면 부상 위험이 높아집니다. 휴식일을 중간에 넣는 것을 추천합니다.", Icons.Rounded.Warning, warnTone = true)
                        }
                    }

                    2 -> Column {
                        Text(
                            "어디서 운동하시나요?",
                            color = c.text, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            onbPlaces.forEach { p ->
                                val sel = place == p.id
                                Surface(
                                    onClick = {
                                        place = p.id
                                        bodyweightOnly = false
                                        equipMask = when (p.id) {
                                            "gym", "both" -> equipMaskOf(equipAll)
                                            "home" -> equipMaskOf(listOf("저항 밴드", "ab 롤러", "폼롤러", "짐볼"))
                                            else -> equipMask
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (sel) c.primaryWash else c.surface,
                                    contentColor = c.text,
                                    border = BorderStroke(1.dp, if (sel) c.primarySoftLine else c.line),
                                ) {
                                    Column(Modifier.padding(vertical = 14.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(if (sel) c.primary else c.surface2),
                                            contentAlignment = Alignment.Center,
                                        ) { Icon(p.icon, contentDescription = null, tint = if (sel) Color.White else c.primaryText, modifier = Modifier.size(18.dp)) }
                                        Text(p.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 9.dp))
                                        Text(p.desc, color = if (sel) c.text2 else c.text3, fontSize = 10.5.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp))
                                    }
                                }
                            }
                        }
                        if (place == null) {
                            Text(
                                "장소를 선택하면 기구 선택 영역이 나타납니다.",
                                color = c.text3, fontSize = 12.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 34.dp),
                            )
                        } else {
                            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Surface(
                                    onClick = {
                                        bodyweightOnly = !bodyweightOnly
                                        if (bodyweightOnly) equipMask = 0
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (bodyweightOnly) c.primaryWash else c.surface,
                                    contentColor = c.text,
                                    border = BorderStroke(1.dp, if (bodyweightOnly) c.primarySoftLine else c.line),
                                ) {
                                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Person, contentDescription = null, tint = if (bodyweightOnly) c.primaryText else c.text3, modifier = Modifier.size(18.dp))
                                        Text("맨몸 운동만", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp).weight(1f))
                                        if (bodyweightOnly) {
                                            Box(Modifier.size(22.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                                                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                                            }
                                        }
                                    }
                                }
                                equipCats.forEachIndexed { ci, cat ->
                                    val open = equipOpenMask and (1 shl ci) != 0
                                    val pickedN = cat.items.count { equipMask and equipBit(it) != 0 }
                                    val allSel = pickedN == cat.items.size
                                    val rot by animateFloatAsState(if (open) 180f else 0f, tween(280), label = "eq-rot$ci")
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = c.surface,
                                        contentColor = c.text,
                                        border = BorderStroke(1.dp, c.line),
                                        modifier = Modifier.animateContentSize(tween(300)),
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Row(
                                                    Modifier
                                                        .weight(1f)
                                                        .clickable { equipOpenMask = equipOpenMask xor (1 shl ci) }
                                                        .padding(14.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(cat.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                                    Text("$pickedN/${cat.items.size}", color = if (pickedN > 0) c.primaryText else c.text3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                    Icon(
                                                        Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = c.text3,
                                                        modifier = Modifier.padding(start = 6.dp).size(16.dp).rotate(rot),
                                                    )
                                                }
                                                Surface(
                                                    onClick = {
                                                        val mask = equipMaskOf(cat.items)
                                                        equipMask = if (allSel) equipMask and mask.inv() else equipMask or mask
                                                        bodyweightOnly = false
                                                    },
                                                    shape = RoundedCornerShape(999.dp),
                                                    color = if (allSel) c.primary else c.surface2,
                                                    contentColor = if (allSel) Color.White else c.text2,
                                                    border = BorderStroke(1.dp, if (allSel) c.primary else c.line),
                                                    modifier = Modifier.padding(end = 12.dp),
                                                ) {
                                                    Text("전체", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                                                }
                                            }
                                            if (open) {
                                                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    cat.items.chunked(2).forEach { rowItems ->
                                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                                            rowItems.forEach { label ->
                                                                val on = equipMask and equipBit(label) != 0
                                                                Surface(
                                                                    onClick = {
                                                                        equipMask = equipMask xor equipBit(label)
                                                                        bodyweightOnly = false
                                                                    },
                                                                    modifier = Modifier.weight(1f).height(40.dp),
                                                                    shape = RoundedCornerShape(999.dp),
                                                                    color = if (on) c.primary else c.surface2,
                                                                    contentColor = if (on) Color.White else c.text2,
                                                                    border = BorderStroke(1.dp, if (on) c.primary else c.line),
                                                                ) {
                                                                    Box(contentAlignment = Alignment.Center) {
                                                                        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                                                    }
                                                                }
                                                            }
                                                            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when (bodyStep) {
                                0 -> "운동 중량 추천과 영양 정보 계산에 사용됩니다"
                                1 -> "적정 운동 중량과 칼로리 소모량 계산에 사용됩니다"
                                2 -> "운동 강도 조절과 칼로리 계산에 사용됩니다"
                                else -> "최대 심박수 계산과 운동 강도 추천에 사용됩니다"
                            },
                            color = c.text3, fontSize = 12.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AnimatedContent(
                            targetState = bodyStep,
                            transitionSpec = {
                                (slideInVertically(tween(300)) { it / 2 } + fadeIn()) togetherWith
                                    (slideOutVertically(tween(300)) { -it / 2 } + fadeOut())
                            },
                            label = "body-step",
                            modifier = Modifier.fillMaxWidth(),
                        ) { bs ->
                            when (bs) {
                                0 -> Column(Modifier.padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("성별을 선택해주세요", color = c.text, fontSize = 23.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                    Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        listOf("male" to "남성", "female" to "여성").forEach { (idv, label) ->
                                            val sel = gender == idv
                                            Surface(
                                                onClick = { gender = idv },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(22.dp),
                                                color = if (sel) c.primaryWash else c.surface,
                                                contentColor = c.text,
                                                border = BorderStroke(1.dp, if (sel) c.primarySoftLine else c.line),
                                            ) {
                                                Column(Modifier.padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Box(
                                                        Modifier.size(40.dp).clip(CircleShape).background(if (sel) c.primary else c.surface2),
                                                        contentAlignment = Alignment.Center,
                                                    ) { Icon(Icons.Rounded.Person, contentDescription = null, tint = if (sel) Color.White else c.primaryText, modifier = Modifier.size(19.dp)) }
                                                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                                                }
                                            }
                                        }
                                    }
                                    Text(
                                        "건너뛰기", color = c.text2, fontSize = 12.sp, textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
                                            .clickable { gender = "male"; bodyStep = 1 }
                                            .padding(vertical = 8.dp),
                                    )
                                }

                                else -> {
                                    val (title, unit, avg) = when (bs) {
                                        1 -> Triple("키를 입력해주세요", "cm", if (gender == "female") "160" else "173")
                                        2 -> Triple("몸무게를 입력해주세요", "kg", if (gender == "female") "58" else "74")
                                        else -> Triple("나이를 입력해주세요", "세", "30")
                                    }
                                    val value = when (bs) {
                                        1 -> heightInput
                                        2 -> weightInput
                                        else -> ageInput
                                    }
                                    Column(Modifier.padding(top = 30.dp)) {
                                        Text(title, color = c.text, fontSize = 23.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                        Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                                            DField(
                                                value = value,
                                                onValueChange = { v ->
                                                    val digits = v.filter(Char::isDigit).take(3)
                                                    when (bs) {
                                                        1 -> heightInput = digits
                                                        2 -> weightInput = digits
                                                        else -> ageInput = digits
                                                    }
                                                },
                                                placeholder = title.removeSuffix("주세요"),
                                                keyboardType = KeyboardType.Number,
                                                height = 60.dp,
                                                centered = true,
                                                textSize = 22,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(unit, color = c.text2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp).width(34.dp))
                                        }
                                        Text(
                                            "평균으로 설정하고 건너뛰기", color = c.text2, fontSize = 12.sp, textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
                                                .clickable {
                                                    when (bs) {
                                                        1 -> { heightInput = avg; bodyStep = 2 }
                                                        2 -> { weightInput = avg; bodyStep = 3 }
                                                        else -> { ageInput = avg; finish() }
                                                    }
                                                }
                                                .padding(vertical = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val prevEnabled = step > 0 || bodyStep > 0
            Surface(
                onClick = { goBack() },
                enabled = prevEnabled,
                modifier = Modifier.weight(1f).height(54.dp),
                shape = RoundedCornerShape(18.dp),
                color = c.surface,
                contentColor = c.text2,
                border = BorderStroke(1.dp, c.line),
            ) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(17.dp))
                    Text("이전", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 7.dp))
                }
            }
            Cta(
                text = if (step == 3 && bodyStep == 3) "완료" else "다음",
                onClick = { goNext() },
                enabled = canNext,
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OnbChoiceCard(choice: OnbChoice, selected: Boolean, onClick: () -> Unit) {
    val c = Trex.c
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) c.primaryWash else c.surface,
        contentColor = c.text,
        border = BorderStroke(1.dp, if (selected) c.primarySoftLine else c.line),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) c.primary else c.surface2),
                contentAlignment = Alignment.Center,
            ) { Icon(choice.icon, contentDescription = null, tint = if (selected) Color.White else c.primaryText, modifier = Modifier.size(19.dp)) }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(choice.label, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Text(choice.desc, color = if (selected) c.text2 else c.text3, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(if (selected) c.primary else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
    }
}

fun hasThreeConsecutiveDays(dayMask: Int): Boolean {
    for (start in 0..6) {
        val first = dayMask and (1 shl start) != 0
        val second = dayMask and (1 shl ((start + 1) % 7)) != 0
        val third = dayMask and (1 shl ((start + 2) % 7)) != 0
        if (first && second && third) return true
    }
    return false
}

/** 원본은 720×1280 투명 캔버스다. 애니메이션 전체에 같은 로고 영역을 사용해 중앙이 흔들리지 않게 한다. */
@Composable
internal fun LoginLogo(frame: Int, modifier: Modifier = Modifier) {
    val bitmap = loginAnimationFrame(frame)
    androidx.compose.foundation.Canvas(modifier.semantics { contentDescription = "TREX 로고" }) {
        val side = size.minDimension.toInt()
        drawImage(
            image = bitmap,
            srcOffset = androidx.compose.ui.unit.IntOffset(180, 180),
            srcSize = androidx.compose.ui.unit.IntSize(360, 360),
            dstOffset = androidx.compose.ui.unit.IntOffset((size.width.toInt() - side) / 2, (size.height.toInt() - side) / 2),
            dstSize = androidx.compose.ui.unit.IntSize(side, side),
        )
    }
}

/**
 * 계정 연결 버튼 — 아이디 로그인·가입 창(D13) → Apple(iPhone) → Google, 시작 화면과 같은 순서.
 * 커뮤니티 계정 안내와 루틴 공유 창(로그인 전)이 같이 쓴다. 실패 문구([AppViewModel.authMessage])는 부르는 쪽이 보인다.
 */
@Composable
fun AccountSignInButtons(app: AppViewModel) {
    val requestGoogleIdToken = rememberGoogleIdTokenRequest()
    val requestAppleCredential = rememberAppleCredentialRequest()
    var showPasswordLogin by remember { mutableStateOf(false) }
    if (showPasswordLogin) PasswordAuthDialog(app, onDismiss = { showPasswordLogin = false })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Cta(text = "아이디로 로그인·가입", onClick = { showPasswordLogin = true }, enabled = !app.authBusy, modifier = Modifier.fillMaxWidth())
        if (app.appleSignInAvailable) AppleSignInButton(onClick = { if (!app.authBusy) app.continueWithApple(requestAppleCredential) })
        GhostButton(
            text = if (app.authBusy) "연결 중…" else "Google 계정 연결",
            onClick = { if (!app.authBusy) app.continueWithGoogle(requestGoogleIdToken) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
