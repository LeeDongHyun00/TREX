package com.example.trex_kotlin

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val loginAnimationFrames = intArrayOf(
    R.drawable.login_animation_frame_01, R.drawable.login_animation_frame_02,
    R.drawable.login_animation_frame_03, R.drawable.login_animation_frame_04,
    R.drawable.login_animation_frame_05, R.drawable.login_animation_frame_06,
    R.drawable.login_animation_frame_07, R.drawable.login_animation_frame_08,
    R.drawable.login_animation_frame_09, R.drawable.login_animation_frame_10,
    R.drawable.login_animation_frame_11, R.drawable.login_animation_frame_12,
    R.drawable.login_animation_frame_13, R.drawable.login_animation_frame_14,
    R.drawable.login_animation_frame_15, R.drawable.login_animation_frame_16,
)

// ============================================================= AUTH (로그인/회원가입 통합)

@Composable
fun AuthScreen(
    onLogin: () -> Unit,
    onOpenFind: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenPostureLab: () -> Unit = {},
    onOpenBaselineGuide: () -> Unit = {},
) {
    val c = Trex.c
    var signupMode by rememberSaveable { mutableStateOf(false) }
    var id by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var suName by rememberSaveable { mutableStateOf("") }
    var suId by rememberSaveable { mutableStateOf("") }
    var suPw by rememberSaveable { mutableStateOf("") }
    var suPw2 by rememberSaveable { mutableStateOf("") }
    var suEmail by rememberSaveable { mutableStateOf("") }
    var agree by rememberSaveable { mutableStateOf(false) }
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        for (next in 1 until loginAnimationFrames.size) {
            delay(95)
            frame = next
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .imePadding()
            .padding(horizontal = 26.dp)
            .padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 로고 카드 + 워드마크
        Surface(
            modifier = Modifier.size(84.dp),
            shape = RoundedCornerShape(28.dp),
            color = c.surface,
            border = BorderStroke(1.dp, c.line),
            shadowElevation = 2.dp,
        ) {
            Image(
                painter = painterResource(loginAnimationFrames[frame]),
                contentDescription = "TREX",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            "TREX",
            color = c.primaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text("바로 보고, 바로 고치고", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))

        SegmentedTabs(
            options = listOf("로그인", "회원가입"),
            selected = if (signupMode) 1 else 0,
            onSelect = { signupMode = it == 1 },
            modifier = Modifier.padding(top = 24.dp),
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 18.dp),
        ) {
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
            ) { signup ->
                if (!signup) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        DField(id, { id = it }, "아이디")
                        DField(password, { password = it }, "비밀번호", password = true)
                        Spacer(Modifier.height(6.dp))
                        Cta("로그인", onClick = onLogin, icon = Icons.AutoMirrored.Rounded.Login, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    val valid = suName.isNotBlank() && suId.length >= 4 && suPw.length >= 8 && suPw == suPw2 && suEmail.contains("@") && agree
                    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        DField(suName, { suName = it }, "지민", label = "이름", hint = "실명이 아니어도 괜찮아룡")
                        DField(suId, { suId = it }, "trex_user", label = "아이디", hint = "4자 이상 영문/숫자")
                        DField(suPw, { suPw = it }, "••••••••", label = "비밀번호", hint = "8자 이상, 영문·숫자 조합", password = true)
                        DField(
                            suPw2, { suPw2 = it }, "••••••••", label = "비밀번호 확인",
                            hint = if (suPw.isNotBlank() && suPw2.isNotBlank() && suPw != suPw2) "비밀번호가 일치하지 않아룡" else "한 번 더 입력해주세요",
                            password = true,
                        )
                        DField(suEmail, { suEmail = it }, "jimin@trex.app", label = "이메일", hint = "아이디·비밀번호 찾기에 사용돼요", keyboardType = KeyboardType.Email)
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
                                    "서비스 약관 및 개인정보 처리방침에 동의합니다",
                                    color = c.text, fontSize = 12.5.sp, lineHeight = 17.sp,
                                    modifier = Modifier.padding(start = 11.dp).weight(1f),
                                )
                            }
                        }
                        Cta("가입 완료", onClick = onLogin, enabled = valid, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("아이디/비밀번호 찾기", color = c.text2, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onOpenFind))
                Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(11.dp).background(c.line))
                Text("가이드북", color = c.text2, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onOpenGuide))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 22.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("자세 교정 실험실", color = c.text3, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onOpenPostureLab))
                Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(10.dp).background(c.line))
                Text("자세 기준선 설정", color = c.text3, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onOpenBaselineGuide))
            }
        }
    }
}

// ============================================================= FIND

@Composable
fun FindAccountScreen(onBack: () -> Unit) {
    val c = Trex.c
    var findPw by rememberSaveable { mutableStateOf(false) }
    var id by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 52.dp)) {
            Kicker("RECOVER", color = c.primaryText)
            TitleBig("아이디 / 비밀번호 찾기")
        }
        Box(Modifier.padding(horizontal = 24.dp).padding(top = 18.dp)) {
            SegmentedTabs(
                options = listOf("아이디 찾기", "비밀번호 찾기"),
                selected = if (findPw) 1 else 0,
                onSelect = { findPw = it == 1; sent = false },
                height = 40.dp,
                filled = true,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 20.dp),
        ) {
            if (sent) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(c.primaryWash)
                        .border(1.dp, c.primarySoftLine, RoundedCornerShape(24.dp))
                        .padding(20.dp),
                ) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Mail, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                    Text(
                        if (findPw) "재설정 링크를 이메일로 보내드렸어룡" else "아이디를 이메일로 보내드렸어룡",
                        color = c.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp),
                    )
                    Text("$email 로 전송됨", color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                    Cta("로그인으로 돌아가기", onClick = onBack, height = 46.dp, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    if (findPw) {
                        DField(id, { id = it }, "가입 시 사용한 아이디", label = "아이디")
                    }
                    DField(email, { email = it }, "가입 시 등록한 이메일", label = "이메일", keyboardType = KeyboardType.Email)
                    Text(
                        if (findPw) "입력한 정보가 일치하면 비밀번호 재설정 링크를 보내드려룡." else "입력한 이메일로 가입된 아이디를 안내해드려룡.",
                        color = c.text2, fontSize = 12.sp, lineHeight = 19.sp,
                    )
                }
            }
        }
        if (!sent) {
            Row(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Cta(
                    text = if (findPw) "재설정 링크 받기" else "아이디 받기",
                    onClick = { if (email.contains("@") && (!findPw || id.isNotBlank())) sent = true },
                    enabled = email.contains("@") && (!findPw || id.isNotBlank()),
                    modifier = Modifier.weight(1f),
                )
                RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, onClick = onBack, size = 54.dp, contentDescription = "뒤로")
            }
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

    BackHandler(enabled = step > 0 || bodyStep > 0) { goBack() }

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
                        val count = Integer.bitCount(dayMask)
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
