package com.example.trex_kotlin

import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val loginAnimationFrames = intArrayOf(
    R.drawable.login_animation_frame_01,
    R.drawable.login_animation_frame_02,
    R.drawable.login_animation_frame_03,
    R.drawable.login_animation_frame_04,
    R.drawable.login_animation_frame_05,
    R.drawable.login_animation_frame_06,
    R.drawable.login_animation_frame_07,
    R.drawable.login_animation_frame_08,
    R.drawable.login_animation_frame_09,
    R.drawable.login_animation_frame_10,
    R.drawable.login_animation_frame_11,
    R.drawable.login_animation_frame_12,
    R.drawable.login_animation_frame_13,
    R.drawable.login_animation_frame_14,
    R.drawable.login_animation_frame_15,
    R.drawable.login_animation_frame_16,
)

@Composable
fun LoginScreen(onLogin: () -> Unit, onOpenPostureLab: () -> Unit = {}, onOpenBaselineGuide: () -> Unit = {}) {
    var mode by rememberSaveable { mutableStateOf(LoginMode.Login) }

    when (mode) {
        LoginMode.Login -> LoginView(
            onLogin = onLogin,
            goSignup = { mode = LoginMode.Signup },
            goFind = { mode = LoginMode.Find },
            onOpenPostureLab = onOpenPostureLab,
            onOpenBaselineGuide = onOpenBaselineGuide,
        )

        LoginMode.Signup -> SignupView(
            onBack = { mode = LoginMode.Login },
            onDone = onLogin,
        )

        LoginMode.Find -> FindAccountView(onBack = { mode = LoginMode.Login })
    }
}

@Composable
private fun LoginFrameAnimation(modifier: Modifier = Modifier) {
    var frameIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        for (nextFrame in 1 until loginAnimationFrames.size) {
            delay(95)
            frameIndex = nextFrame
        }
    }

    Image(
        painter = painterResource(id = loginAnimationFrames[frameIndex]),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer {
            scaleX = 1.8f
            scaleY = 1.8f
            transformOrigin = TransformOrigin(0.5f, 0f)
        },
    )
}

@Composable
private fun LoginView(
    onLogin: () -> Unit,
    goSignup: () -> Unit,
    goFind: () -> Unit,
    onOpenPostureLab: () -> Unit = {},
    onOpenBaselineGuide: () -> Unit = {},
) {
    var id by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark)
            .imePadding(),
    ) {
        // 세로가 짧은 기기(예: 1080×2280, ~690dp 가용)에서는 430dp 애니메이션 때문에 아래 버튼들이 화면 밖으로 밀려
        // 카카오/실험실 버튼이 보이지 않았다. 텍스트·입력창·버튼 4개·링크·여백 합계(≈602dp)를 뺀 만큼만 애니메이션에 준다.
        val animationHeight = (maxHeight - 602.dp).coerceIn(140.dp, 430.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .padding(top = 30.dp, bottom = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "TREX",
                color = TrexLime,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "바로 보고, 바로 고치고",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 6.dp),
            )

            LoginFrameAnimation(
                modifier = Modifier
                    .padding(top = 48.dp)
                    .fillMaxWidth()
                    .height(animationHeight),
            )

            Spacer(Modifier.weight(1f))
            TrexTextField(
                value = id,
                onValueChange = { id = it },
                placeholder = "아이디",
                leadingIcon = Icons.Rounded.Person,
            )
            Spacer(Modifier.height(12.dp))
            TrexTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = "비밀번호",
                password = true,
                leadingIcon = Icons.Rounded.Lock,
            )
            Spacer(Modifier.height(14.dp))
            TrexButton(
                text = "로그인",
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            TrexButton(
                text = "카카오톡으로 로그인",
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
                container = Color(0xFFFEE500),
                contentColor = Color(0xFF191919),
            )
            Spacer(Modifier.height(10.dp))
            // 개발용: 로그인 없이 자세 교정(MediaPipe + rules_mp_v0) 실험 화면으로 진입
            TrexButton(
                text = "자세 교정 실험실 (개발용)",
                onClick = onOpenPostureLab,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.CenterFocusStrong,
                container = Color.White.copy(alpha = 0.10f),
                contentColor = TrexLime,
            )
            Spacer(Modifier.height(10.dp))
            // 개인화: 기준선 대상 6종목을 정자세 3세트씩 찍어 '내 기준'을 만드는 안내 화면 (spec §15~§17)
            TrexButton(
                text = "자세 기준선 설정 (정자세 3세트)",
                onClick = onOpenBaselineGuide,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Check,
                container = Color.White.copy(alpha = 0.10f),
                contentColor = TrexLime,
            )

            Row(
                modifier = Modifier.padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "회원가입",
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = goSignup),
                )
                Spacer(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .width(1.dp)
                        .height(12.dp)
                        .background(Color.White.copy(alpha = 0.18f)),
                )
                Text(
                    text = "아이디/비밀번호 찾기",
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = goFind),
                )
            }
        }
    }
}

@Composable
private fun SignupView(onBack: () -> Unit, onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var id by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordConfirm by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    val valid = name.isNotBlank() &&
        id.length >= 4 &&
        password.length >= 8 &&
        password == passwordConfirm &&
        email.contains("@") &&
        agreed

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark)
            .imePadding(),
    ) {
        SimpleHeader(title = "회원가입")
        Text(
            text = "TREX와 함께 시작할 준비가 되었어룡?",
            color = Color.White.copy(alpha = 0.74f),
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            TrexTextField(name, { name = it }, "이름", leadingIcon = Icons.Rounded.AccountCircle)
            TrexTextField(id, { id = it }, "아이디", hint = "4자 이상 영문/숫자", leadingIcon = Icons.Rounded.Person)
            TrexTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = "비밀번호",
                password = true,
                hint = "8자 이상, 영문·숫자 조합",
                leadingIcon = Icons.Rounded.Lock,
            )
            TrexTextField(
                value = passwordConfirm,
                onValueChange = { passwordConfirm = it },
                placeholder = "비밀번호 확인",
                password = true,
                hint = if (password.isNotBlank() && passwordConfirm.isNotBlank() && password != passwordConfirm) {
                    "비밀번호가 일치하지 않아요"
                } else {
                    null
                },
                leadingIcon = Icons.Rounded.Lock,
            )
            TrexTextField(
                value = email,
                onValueChange = { email = it },
                placeholder = "이메일",
                keyboardType = KeyboardType.Email,
                hint = "아이디·비밀번호 찾기에 사용돼요",
                leadingIcon = Icons.Rounded.Email,
            )

            Surface(
                onClick = { agreed = !agreed },
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.04f),
                border = dimBorder(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .then(
                                if (agreed) {
                                    Modifier.background(TrexLime)
                                } else {
                                    Modifier.border(1.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(6.dp))
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (agreed) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = TrexDark, modifier = Modifier.size(14.dp))
                        }
                    }
                    Text(
                        text = "서비스 약관 및 개인정보 처리방침에 동의합니다",
                        color = Color.White.copy(alpha = 0.86f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrexButton(
                text = "가입 완료",
                onClick = onDone,
                enabled = valid,
                modifier = Modifier.weight(1f),
            )
                IconCircleButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                onClick = onBack,
                size = 52.dp,
                background = Color.White.copy(alpha = 0.08f),
                border = dimBorder(0.15f),
                contentDescription = "뒤로",
            )
        }
    }
}

@Composable
private fun FindAccountView(onBack: () -> Unit) {
    var findPw by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var id by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }
    val valid = email.contains("@") && (!findPw || id.isNotBlank())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark)
            .imePadding(),
    ) {
        SimpleHeader(title = "아이디 / 비밀번호 찾기")

        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 18.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToggleSegment(
                text = "아이디 찾기",
                icon = Icons.Rounded.AccountCircle,
                active = !findPw,
                modifier = Modifier.weight(1f),
                onClick = {
                    findPw = false
                    sent = false
                },
            )
            ToggleSegment(
                text = "비밀번호 찾기",
                icon = Icons.Rounded.Key,
                active = findPw,
                modifier = Modifier.weight(1f),
                onClick = {
                    findPw = true
                    sent = false
                },
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 24.dp),
        ) {
            if (sent) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(TrexLime)
                        .padding(20.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(TrexDark),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Email, contentDescription = null, tint = TrexLime, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = if (findPw) "재설정 링크를 이메일로 보내드렸어요" else "아이디를 이메일로 보내드렸어요",
                        color = TrexDark,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Text(
                        text = "$email 로 전송됨",
                        color = TrexDark.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    TrexButton(
                        text = "로그인으로 돌아가기",
                        onClick = onBack,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .fillMaxWidth(),
                        container = TrexDark,
                        contentColor = TrexLime,
                        height = 46.dp,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    if (findPw) {
                        TrexTextField(
                            value = id,
                            onValueChange = { id = it },
                            placeholder = "아이디",
                            hint = "가입 시 사용한 아이디",
                            leadingIcon = Icons.Rounded.Person,
                        )
                    }
                    TrexTextField(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = "이메일",
                        keyboardType = KeyboardType.Email,
                        hint = "가입 시 등록한 이메일로 보내드려요",
                        leadingIcon = Icons.Rounded.Email,
                    )
                    Text(
                        text = if (findPw) {
                            "입력한 정보가 일치하면 비밀번호 재설정 링크를 보내드려요."
                        } else {
                            "입력한 이메일로 가입된 아이디를 안내해드려요."
                        },
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        if (!sent) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TrexButton(
                    text = if (findPw) "재설정 링크 받기" else "아이디 받기",
                    onClick = { sent = true },
                    enabled = valid,
                    modifier = Modifier.weight(1f),
                )
                IconCircleButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    onClick = onBack,
                    size = 52.dp,
                    background = Color.White.copy(alpha = 0.08f),
                    border = dimBorder(0.15f),
                    contentDescription = "뒤로",
                )
            }
        }
    }
}

@Composable
private fun SimpleHeader(title: String) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 42.dp)) {
        ScreenTitle(text = title)
    }
}

@Composable
private fun ToggleSegment(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (active) TrexLime else Color.Transparent,
        contentColor = if (active) TrexDark else Color.White.copy(alpha = 0.82f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(text = text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LegacyOnboardingScreen(onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var selectedGoal by rememberSaveable { mutableStateOf<String?>(null) }
    var dayMask by rememberSaveable { mutableIntStateOf(0) }
    var selectedPlace by rememberSaveable { mutableStateOf<String?>(null) }
    var equipmentMask by rememberSaveable { mutableIntStateOf(0) }
    var selectedGender by rememberSaveable { mutableStateOf("none") }
    var heightCm by rememberSaveable { mutableIntStateOf(170) }
    var weightKg by rememberSaveable { mutableIntStateOf(65) }
    var ageInput by rememberSaveable { mutableStateOf("") }
    val canNext = when (step) {
        0 -> selectedGoal != null
        1 -> dayMask != 0
        2 -> selectedPlace != null
        else -> true
    }

    fun goNext() {
        if (step < 3) {
            step += 1
        } else {
            onDone()
        }
    }

    BackHandler(enabled = step > 0) {
        step -= 1
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark)
            .imePadding()
            .padding(horizontal = 24.dp)
            .padding(top = 42.dp, bottom = 22.dp),
    ) {
        OnboardingProgressHeader(step = step, total = 4) {
            if (step > 0) {
                IconCircleButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    onClick = { step -= 1 },
                    size = 38.dp,
                    background = Color.White.copy(alpha = 0.08f),
                    contentDescription = "이전",
                )
            }
        }

        AnimatedContent(
            targetState = step,
            modifier = Modifier
                .weight(1f)
                .padding(top = 34.dp),
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                }.using(SizeTransform(clip = false))
            },
            label = "onboarding-step",
        ) { visibleStep ->
            Column(Modifier.fillMaxSize()) {
                SectionLabel(text = "Step ${visibleStep + 1}", color = TrexLime)
                ScreenTitle(
                    text = when (visibleStep) {
                        0 -> "목표를 선택해 주세요"
                        1 -> "운동 요일을 정해 주세요"
                        2 -> "운동 장소를 선택해 주세요"
                        else -> "신체 정보를 입력해 주세요"
                    },
                    color = Color.White,
                )
                Text(
                    text = when (visibleStep) {
                        0 -> "첫 루틴의 방향을 정하는 데 사용돼요."
                        1 -> "최소 하루 이상 선택해야 루틴을 만들 수 있어요."
                        2 -> "장소와 기구에 맞춰 가능한 운동만 추천해요."
                        else -> "칼로리, 강도, 반복 수를 더 현실적으로 계산하기 위해 필요해요."
                    },
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )

                when (visibleStep) {
                    0 -> GoalStep(
                        selectedGoal = selectedGoal,
                        onSelect = { selectedGoal = it },
                        onSkip = {
                            selectedGoal = "general"
                            goNext()
                        },
                    )

                    1 -> DaysStep(
                        dayMask = dayMask,
                        onToggleDay = { dayIndex ->
                            dayMask = dayMask xor (1 shl dayIndex)
                        },
                    )

                    2 -> PlaceStep(
                        selectedPlace = selectedPlace,
                        equipmentMask = equipmentMask,
                        onBodyweightOnly = {
                            selectedPlace = "bodyweight"
                            equipmentMask = 0
                        },
                        onPlaceSelect = { place ->
                            selectedPlace = place
                            equipmentMask = when (place) {
                                "gym", "both" -> allEquipmentMask()
                                "home" -> equipmentBit("보조도구")
                                else -> equipmentMask
                            }
                        },
                        onToggleEquipment = { label ->
                            equipmentMask = equipmentMask xor equipmentBit(label)
                        },
                    )

                    3 -> BodyInfoStep(
                        selectedGender = selectedGender,
                        heightCm = heightCm,
                        weightKg = weightKg,
                        ageInput = ageInput,
                        onGender = { selectedGender = it },
                        onHeight = { heightCm = it.coerceIn(130, 210) },
                        onWeight = { weightKg = it.coerceIn(35, 160) },
                        onAge = { ageInput = it.filter(Char::isDigit).take(3) },
                        onAverage = {
                            selectedGender = "none"
                            heightCm = 170
                            weightKg = 65
                            ageInput = ""
                        },
                        onSkip = onDone,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrexButton(
                text = "이전",
                onClick = { step = (step - 1).coerceAtLeast(0) },
                enabled = step > 0,
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                modifier = Modifier.weight(1f),
                container = Color.White.copy(alpha = 0.08f),
                contentColor = Color.White,
            )
            TrexButton(
                text = if (step < 3) "다음" else "시작",
                onClick = ::goNext,
                enabled = canNext,
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OnboardingProgressHeader(
    step: Int,
    total: Int,
    trailing: @Composable () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${step + 1}/$total",
                color = TrexLime,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(total) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (index <= step) TrexLime else Color.White.copy(alpha = 0.13f)),
                )
            }
        }
    }
}

@Composable
private fun GoalStep(
    selectedGoal: String?,
    onSelect: (String) -> Unit,
    onSkip: () -> Unit,
) {
    val goals = listOf(
        OnboardingChoice("muscle", "근육 증가", "근비대 중심 루틴", Icons.Rounded.FitnessCenter),
        OnboardingChoice("diet", "다이어트", "소모 칼로리와 식단 관리", Icons.Rounded.LocalFireDepartment),
        OnboardingChoice("stamina", "체력 향상", "유산소와 전신 순환", Icons.Rounded.PlayArrow),
        OnboardingChoice("maintain", "유지", "무리 없는 습관 루틴", Icons.Rounded.Check),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 22.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        goals.forEach { goal ->
            OnboardingChoiceCard(
                choice = goal,
                active = selectedGoal == goal.id,
                onClick = { onSelect(goal.id) },
            )
        }

        Text(
            text = "건너뛰기",
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable(onClick = onSkip)
                .padding(vertical = 10.dp),
        )
        Text(
            text = "건너뛰면 범용 전신 루틴으로 시작해요.",
            color = Color.White.copy(alpha = 0.36f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OnboardingProgressHeaderV2(step: Int, total: Int) {
    Column {
        Text(
            text = "${step + 1}/$total",
            color = TrexLime,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(total) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (index <= step) TrexLime else Color.White.copy(alpha = 0.13f)),
                )
            }
        }
    }
}

@Composable
private fun OnboardingBottomBarV2(
    step: Int,
    bodyInputStep: Int,
    canNext: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (step == 3) {
            if (bodyInputStep > 0) {
                TrexButton(
                    text = "이전",
                    onClick = onBack,
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    modifier = Modifier.weight(1f),
                    container = Color.White.copy(alpha = 0.08f),
                    contentColor = Color.White,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            TrexButton(
                text = if (bodyInputStep < 3) "다음" else "완료",
                onClick = onNext,
                enabled = canNext,
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                modifier = Modifier.weight(1f),
            )
        } else {
            if (step > 0) {
                TrexButton(
                    text = "이전",
                    onClick = onBack,
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    modifier = Modifier.weight(1f),
                    container = Color.White.copy(alpha = 0.08f),
                    contentColor = Color.White,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            TrexButton(
                text = "다음",
                onClick = onNext,
                enabled = canNext,
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GoalStepV2(
    selectedGoal: String?,
    onSelect: (String) -> Unit,
    onSkip: () -> Unit,
) {
    val goals = listOf(
        OnboardingChoiceV2("muscle", "근육 증가", "무거운 중량 위주, 분할 루틴 중심", Icons.Rounded.FitnessCenter),
        OnboardingChoiceV2("diet", "다이어트", "유산소와 고반복 운동 중심", Icons.Rounded.LocalFireDepartment),
        OnboardingChoiceV2("stamina", "체력 향상", "전신 기능성 운동 중심", Icons.Rounded.PlayArrow),
        OnboardingChoiceV2("maintain", "유지", "현재 체력 유지, 균형 잡힌 루틴", Icons.Rounded.Check),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 22.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "운동 목표를 선택해주세요",
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        )
        goals.forEach { goal ->
            OnboardingChoiceCardV2(
                choice = goal,
                active = selectedGoal == goal.id,
                onClick = { onSelect(goal.id) },
            )
        }
        Text(
            text = "건너뛰기",
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clickable(onClick = onSkip)
                .padding(vertical = 8.dp),
        )
        Text(
            text = "건너뛰면 범용 전신 운동 루틴으로 시작합니다.",
            color = Color.White.copy(alpha = 0.36f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DaysStepV2(
    dayMask: Int,
    onToggleDay: (Int) -> Unit,
) {
    val days = listOf("월", "화", "수", "목", "금", "토", "일")
    val count = dayMask.countSelectedBits()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "운동할 요일을 선택해주세요",
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            days.forEachIndexed { index, day ->
                val active = (dayMask and (1 shl index)) != 0
                Surface(
                    onClick = { onToggleDay(index) },
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    color = if (active) TrexLime else Color.White.copy(alpha = 0.06f),
                    contentColor = if (active) TrexDark else Color.White,
                    border = if (active) null else dimBorder(0.12f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(day, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Surface(
            modifier = Modifier
                .padding(top = 24.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = if (count > 0) TrexLime else Color.White.copy(alpha = 0.05f),
            contentColor = if (count > 0) TrexDark else Color.White,
            border = if (count > 0) null else dimBorder(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("주 ${count}일 운동", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (count == 0) "최소 1일 이상 선택해야 다음으로 진행할 수 있습니다." else selectedDaysTextV2(dayMask, days),
                    color = if (count > 0) TrexDark.copy(alpha = 0.66f) else Color.White.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (hasThreeConsecutiveDays(dayMask)) {
            Surface(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = TrexWarning.copy(alpha = 0.15f),
                contentColor = Color(0xFFFFC873),
                border = dimBorder(0.1f),
            ) {
                Text(
                    text = "연속 운동일이 3일 이상이면 부상 위험이 높아집니다. 휴식일을 중간에 넣는 것을 추천합니다.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaceStepV2(
    selectedPlace: String?,
    bodyweightOnly: Boolean,
    equipmentMask: Int,
    expandedMask: Int,
    onBodyweightOnly: () -> Unit,
    onPlaceSelect: (String) -> Unit,
    onToggleCategoryExpanded: (Int) -> Unit,
    onToggleCategoryAll: (EquipmentCategoryV2) -> Unit,
    onToggleEquipment: (String) -> Unit,
) {
    val places = listOf(
        OnboardingChoiceV2("gym", "헬스장", "기구와 머신 기반", Icons.Rounded.FitnessCenter),
        OnboardingChoiceV2("home", "홈트", "집에서 가능한 루틴", Icons.Rounded.Home),
        OnboardingChoiceV2("both", "둘 다", "상황에 맞춰 전환", Icons.Rounded.Check),
    )
    val categories = equipmentCategoriesV2()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 12.dp, bottom = 14.dp),
    ) {
        Text(
            text = "어디서 운동하시나요?",
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            places.forEach { place ->
                CompactOnboardingChoiceCardV2(
                    choice = place,
                    active = selectedPlace == place.id,
                    onClick = { onPlaceSelect(place.id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (selectedPlace != null) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    onClick = onBodyweightOnly,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = if (bodyweightOnly) TrexLime else Color.White.copy(alpha = 0.06f),
                    contentColor = if (bodyweightOnly) TrexDark else Color.White,
                    border = if (bodyweightOnly) null else dimBorder(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (bodyweightOnly) TrexDark else TrexLime)
                        Text(
                            "맨몸 운동만",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(start = 9.dp)
                                .weight(1f),
                        )
                        if (bodyweightOnly) ConfirmBadge()
                    }
                }
                categories.forEachIndexed { index, category ->
                    EquipmentCategoryAccordionV2(
                        category = category,
                        expanded = (expandedMask and (1 shl index)) != 0,
                        selectedCount = selectedEquipmentCountV2(category, equipmentMask),
                        equipmentMask = equipmentMask,
                        onToggleExpanded = { onToggleCategoryExpanded(index) },
                        onToggleAll = { onToggleCategoryAll(category) },
                        onToggleEquipment = onToggleEquipment,
                    )
                }
            }
        } else {
            Spacer(Modifier.height(26.dp))
            Text(
                text = "장소를 선택하면 기구 선택 영역이 나타납니다.",
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BodyInfoStepV2(
    inputStep: Int,
    selectedGender: String,
    heightInput: String,
    weightInput: String,
    ageInput: String,
    onGender: (String) -> Unit,
    onSkipGender: () -> Unit,
    onHeight: (String) -> Unit,
    onWeight: (String) -> Unit,
    onAge: (String) -> Unit,
    onAverageHeight: () -> Unit,
    onAverageWeight: () -> Unit,
    onAverageAge: () -> Unit,
) {
    val explanation = when (inputStep) {
        0 -> "운동 중량 추천과 영양 정보 계산에 사용됩니다"
        1 -> "적정 운동 중량과 칼로리 소모량 계산에 사용됩니다"
        2 -> "운동 강도 조절과 칼로리 계산에 사용됩니다"
        else -> "최대 심박수 계산과 운동 강도 추천에 사용됩니다"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 24.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = explanation,
            color = Color.White.copy(alpha = 0.52f),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.weight(1f))
        AnimatedContent(
            targetState = inputStep,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                } else {
                    (slideInVertically { -it / 2 } + fadeIn()) togetherWith (slideOutVertically { it / 2 } + fadeOut())
                }.using(SizeTransform(clip = false))
            },
            label = "body-info-input-v2",
            modifier = Modifier.fillMaxWidth(),
        ) { visibleInput ->
            when (visibleInput) {
                0 -> GenderInputV2(
                    selectedGender = selectedGender,
                    onGender = onGender,
                    onSkip = onSkipGender,
                )
                1 -> NumericInputV2(
                    title = "키를 입력해주세요",
                    value = heightInput,
                    onValueChange = onHeight,
                    unit = "cm",
                    keyboardType = KeyboardType.Decimal,
                    skipText = "평균으로 설정하고 건너뛰기",
                    onSkip = onAverageHeight,
                    focusKey = visibleInput,
                )
                2 -> NumericInputV2(
                    title = "몸무게를 입력해주세요",
                    value = weightInput,
                    onValueChange = onWeight,
                    unit = "kg",
                    keyboardType = KeyboardType.Decimal,
                    skipText = "평균으로 설정하고 건너뛰기",
                    onSkip = onAverageWeight,
                    focusKey = visibleInput,
                )
                else -> NumericInputV2(
                    title = "나이를 입력해주세요",
                    value = ageInput,
                    onValueChange = onAge,
                    unit = "세",
                    keyboardType = KeyboardType.Number,
                    skipText = "평균으로 설정하고 건너뛰기",
                    onSkip = onAverageAge,
                    focusKey = visibleInput,
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun GenderInputV2(
    selectedGender: String,
    onGender: (String) -> Unit,
    onSkip: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "성별을 선택해주세요",
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        ) {
            GenderCardV2("male", "남성", selectedGender == "male", onGender, Modifier.weight(1f))
            GenderCardV2("female", "여성", selectedGender == "female", onGender, Modifier.weight(1f))
        }
        Text(
            text = "건너뛰기",
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
                .clickable(onClick = onSkip)
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun NumericInputV2(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    keyboardType: KeyboardType,
    skipText: String,
    onSkip: () -> Unit,
    focusKey: Int,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusKey) {
        focusRequester.requestFocus()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrexTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = title.removeSuffix("주세요"),
                keyboardType = keyboardType,
                leadingIcon = Icons.Rounded.Person,
                focusRequester = focusRequester,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = unit,
                color = Color.White.copy(alpha = 0.74f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(34.dp),
            )
        }
        Text(
            text = skipText,
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
                .clickable(onClick = onSkip)
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun OnboardingChoiceCardV2(
    choice: OnboardingChoiceV2,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (active) TrexLime else Color.White.copy(alpha = 0.05f),
        contentColor = if (active) TrexDark else Color.White,
        border = if (active) null else dimBorder(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) TrexDark else Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(choice.icon, contentDescription = null, tint = TrexLime, modifier = Modifier.size(18.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(choice.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    choice.description,
                    color = if (active) TrexDark.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (active) ConfirmBadge()
        }
    }
}

@Composable
private fun CompactOnboardingChoiceCardV2(
    choice: OnboardingChoiceV2,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(104.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (active) TrexLime else Color.White.copy(alpha = 0.05f),
        contentColor = if (active) TrexDark else Color.White,
        border = if (active) null else dimBorder(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(choice.icon, contentDescription = null, tint = if (active) TrexDark else TrexLime, modifier = Modifier.size(20.dp))
            Text(
                choice.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                choice.description,
                color = if (active) TrexDark.copy(alpha = 0.66f) else Color.White.copy(alpha = 0.46f),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun EquipmentCategoryAccordionV2(
    category: EquipmentCategoryV2,
    expanded: Boolean,
    selectedCount: Int,
    equipmentMask: Int,
    onToggleExpanded: () -> Unit,
    onToggleAll: () -> Unit,
    onToggleEquipment: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = dimBorder(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onToggleExpanded)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "${category.title} ($selectedCount/${category.items.size})",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f),
                )
                Surface(
                    onClick = onToggleAll,
                    shape = RoundedCornerShape(999.dp),
                    color = if (selectedCount == category.items.size) TrexLime else Color.White.copy(alpha = 0.08f),
                    contentColor = if (selectedCount == category.items.size) TrexDark else Color.White,
                    border = if (selectedCount == category.items.size) null else dimBorder(0.12f),
                ) {
                    Text(
                        text = "전체 선택",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    category.items.chunked(2).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            row.forEach { label ->
                                EquipmentChipV2(
                                    label = label,
                                    active = (equipmentMask and equipmentBitV2(label)) != 0,
                                    onClick = { onToggleEquipment(label) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EquipmentChipV2(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(999.dp),
        color = if (active) TrexLime else Color.White.copy(alpha = 0.06f),
        contentColor = if (active) TrexDark else Color.White.copy(alpha = 0.82f),
        border = if (active) null else dimBorder(0.12f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GenderCardV2(
    id: String,
    text: String,
    active: Boolean,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { onClick(id) },
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (active) TrexLime else Color.White.copy(alpha = 0.06f),
        contentColor = if (active) TrexDark else Color.White,
        border = if (active) null else dimBorder(0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp), maxLines = 1)
        }
    }
}

private data class OnboardingChoiceV2(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
)

private data class EquipmentCategoryV2(
    val title: String,
    val items: List<String>,
)

private fun equipmentCategoriesV2(): List<EquipmentCategoryV2> = listOf(
    EquipmentCategoryV2("프리웨이트", listOf("덤벨", "바벨", "EZ바", "케틀벨", "플레이트")),
    EquipmentCategoryV2(
        "머신",
        listOf(
            "케이블 머신",
            "스미스 머신",
            "레그프레스",
            "랫풀다운",
            "체스트프레스 머신",
            "시티드 로우 머신",
            "레그컬 머신",
            "레그익스텐션 머신",
            "펙덱 플라이 머신",
            "숄더프레스 머신",
            "힙 어브덕터/어덕터 머신",
        ),
    ),
    EquipmentCategoryV2("벤치 및 랙", listOf("플랫 벤치", "인클라인 벤치", "디클라인 벤치", "스쿼트 랙", "딥스 바", "풀업 바 (치닝디핑)")),
    EquipmentCategoryV2("유산소 기구", listOf("러닝머신", "실내 자전거", "로잉머신", "스텝퍼", "일립티컬")),
    EquipmentCategoryV2("보조 도구", listOf("저항 밴드", "ab 롤러", "폼롤러", "짐볼")),
)

private fun equipmentLabelsV2(): List<String> = equipmentCategoriesV2().flatMap { it.items }

private fun equipmentBitV2(label: String): Int {
    val index = equipmentLabelsV2().indexOf(label).coerceAtLeast(0)
    return 1 shl index
}

private fun allEquipmentMaskV2(): Int = equipmentLabelsV2().fold(0) { acc, label -> acc or equipmentBitV2(label) }

private fun categoryMaskV2(category: EquipmentCategoryV2): Int =
    category.items.fold(0) { acc, label -> acc or equipmentBitV2(label) }

private fun selectedEquipmentCountV2(category: EquipmentCategoryV2, equipmentMask: Int): Int =
    category.items.count { (equipmentMask and equipmentBitV2(it)) != 0 }

private fun sanitizeDecimalInputV2(input: String): String {
    var dotSeen = false
    return input
        .filter { char ->
            when {
                char.isDigit() -> true
                char == '.' && !dotSeen -> {
                    dotSeen = true
                    true
                }
                else -> false
            }
        }
        .take(6)
}

private fun selectedDaysTextV2(dayMask: Int, days: List<String>): String =
    days.filterIndexed { index, _ -> (dayMask and (1 shl index)) != 0 }.joinToString(" · ")

@Composable
private fun DaysStep(
    dayMask: Int,
    onToggleDay: (Int) -> Unit,
) {
    val days = listOf("월", "화", "수", "목", "금", "토", "일")
    val count = dayMask.countSelectedBits()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            days.forEachIndexed { index, day ->
                val active = dayMask and (1 shl index) != 0
                Surface(
                    onClick = { onToggleDay(index) },
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = if (active) TrexLime else Color.White.copy(alpha = 0.06f),
                    contentColor = if (active) TrexDark else Color.White,
                    border = if (active) null else dimBorder(0.12f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(day, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .padding(top = 24.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = if (count > 0) TrexLime else Color.White.copy(alpha = 0.05f),
            contentColor = if (count > 0) TrexDark else Color.White,
            border = if (count > 0) null else dimBorder(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("주 ${count}일 운동", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (count == 0) "최소 1일 이상 선택해 주세요." else selectedDaysText(dayMask, days),
                    color = if (count > 0) TrexDark.copy(alpha = 0.66f) else Color.White.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (hasThreeConsecutiveDays(dayMask)) {
            Surface(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = TrexWarning.copy(alpha = 0.15f),
                contentColor = Color(0xFFFFC873),
                border = dimBorder(0.1f),
            ) {
                Text(
                    text = "연속 3일 이상 선택했어요. 중간에 휴식일을 하루 넣는 것을 추천해요.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaceStep(
    selectedPlace: String?,
    equipmentMask: Int,
    onBodyweightOnly: () -> Unit,
    onPlaceSelect: (String) -> Unit,
    onToggleEquipment: (String) -> Unit,
) {
    val bodyweightOnly = selectedPlace == "bodyweight"
    val places = listOf(
        OnboardingChoice("gym", "헬스장", "기구와 머신 기반", Icons.Rounded.FitnessCenter),
        OnboardingChoice("home", "홈트", "집에서 가능한 루틴", Icons.Rounded.Home),
        OnboardingChoice("both", "둘 다", "상황에 맞춰 전환", Icons.Rounded.Check),
    )
    val equipment = equipmentLabels()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 22.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            onClick = onBodyweightOnly,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = if (bodyweightOnly) TrexLime else Color.White.copy(alpha = 0.06f),
            contentColor = if (bodyweightOnly) TrexDark else Color.White,
            border = if (bodyweightOnly) null else dimBorder(),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (bodyweightOnly) TrexDark else TrexLime)
                Text(
                    "맨몸 운동만",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(start = 9.dp)
                        .weight(1f),
                )
                if (bodyweightOnly) ConfirmBadge()
            }
        }

        places.forEach { place ->
            OnboardingChoiceCard(
                choice = place,
                active = selectedPlace == place.id,
                onClick = { onPlaceSelect(place.id) },
            )
        }

        if (selectedPlace != null && !bodyweightOnly) {
            Column(
                modifier = Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("사용 가능한 기구", color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                equipment.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { label ->
                            EquipmentChip(
                                label = label,
                                active = equipmentMask and equipmentBit(label) != 0,
                                onClick = { onToggleEquipment(label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BodyInfoStep(
    selectedGender: String,
    heightCm: Int,
    weightKg: Int,
    ageInput: String,
    onGender: (String) -> Unit,
    onHeight: (Int) -> Unit,
    onWeight: (Int) -> Unit,
    onAge: (String) -> Unit,
    onAverage: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color.White.copy(alpha = 0.05f),
            border = dimBorder(),
        ) {
            Text(
                text = "입력한 정보는 권장 칼로리, 운동 강도, 세트 구성을 계산하는 데만 사용돼요.",
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(14.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            GenderCard("male", "남성", selectedGender == "male", onGender, Modifier.weight(1f))
            GenderCard("female", "여성", selectedGender == "female", onGender, Modifier.weight(1f))
        }
        GenderCard("none", "선택하지 않음", selectedGender == "none", onGender, Modifier.fillMaxWidth())

        MetricSliderInput(
            title = "키",
            value = heightCm,
            suffix = "cm",
            range = 130..210,
            onValueChange = onHeight,
        )
        MetricSliderInput(
            title = "몸무게",
            value = weightKg,
            suffix = "kg",
            range = 35..160,
            onValueChange = onWeight,
        )
        TrexTextField(
            value = ageInput,
            onValueChange = onAge,
            placeholder = "나이 선택 입력",
            keyboardType = KeyboardType.Number,
            leadingIcon = Icons.Rounded.Person,
            hint = "미입력 시 기본값 30세로 계산해요.",
        )

        TrexButton(
            text = "한국 평균으로 설정하기",
            onClick = onAverage,
            icon = Icons.Rounded.Check,
            modifier = Modifier.fillMaxWidth(),
            container = Color.White.copy(alpha = 0.08f),
            contentColor = Color.White,
        )
        Text(
            text = "전체 건너뛰기",
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSkip)
                .padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun OnboardingChoiceCard(
    choice: OnboardingChoice,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (active) TrexLime else Color.White.copy(alpha = 0.05f),
        contentColor = if (active) TrexDark else Color.White,
        border = if (active) null else dimBorder(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) TrexDark else Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(choice.icon, contentDescription = null, tint = TrexLime, modifier = Modifier.size(18.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(choice.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    choice.description,
                    color = if (active) TrexDark.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (active) ConfirmBadge()
        }
    }
}

@Composable
private fun EquipmentChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(999.dp),
        color = if (active) TrexLime else Color.White.copy(alpha = 0.06f),
        contentColor = if (active) TrexDark else Color.White.copy(alpha = 0.82f),
        border = if (active) null else dimBorder(0.12f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GenderCard(
    id: String,
    text: String,
    active: Boolean,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { onClick(id) },
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (active) TrexLime else Color.White.copy(alpha = 0.06f),
        contentColor = if (active) TrexDark else Color.White,
        border = if (active) null else dimBorder(0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp), maxLines = 1)
        }
    }
}

@Composable
private fun MetricSliderInput(
    title: String,
    value: Int,
    suffix: String,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = dimBorder(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box(modifier = Modifier.width(92.dp)) {
                    TrexTextField(
                        value = value.toString(),
                        onValueChange = { input ->
                            input.filter(Char::isDigit).toIntOrNull()?.let { onValueChange(it.coerceIn(range.first, range.last)) }
                        },
                        placeholder = "$title ($suffix)",
                        keyboardType = KeyboardType.Number,
                    )
                }
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt().coerceIn(range.first, range.last)) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = TrexLime,
                    activeTrackColor = TrexLime,
                    inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                ),
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "$value $suffix",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 11.sp,
            )
        }
    }
}

private data class OnboardingChoice(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
)

private fun equipmentLabels(): List<String> = listOf("프리웨이트", "머신", "유산소", "보조도구")

private fun equipmentBit(label: String): Int {
    val index = equipmentLabels().indexOf(label).coerceAtLeast(0)
    return 1 shl index
}

private fun allEquipmentMask(): Int = equipmentLabels().fold(0) { acc, label -> acc or equipmentBit(label) }

private fun Int.countSelectedBits(): Int {
    var value = this
    var count = 0
    while (value != 0) {
        count += value and 1
        value = value shr 1
    }
    return count
}

private fun hasThreeConsecutiveDays(dayMask: Int): Boolean {
    for (start in 0..6) {
        val first = dayMask and (1 shl start) != 0
        val second = dayMask and (1 shl ((start + 1) % 7)) != 0
        val third = dayMask and (1 shl ((start + 2) % 7)) != 0
        if (first && second && third) return true
    }
    return false
}

private fun selectedDaysText(dayMask: Int, days: List<String>): String =
    days.filterIndexed { index, _ -> dayMask and (1 shl index) != 0 }.joinToString(" · ")

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var selectedGoal by rememberSaveable { mutableStateOf<String?>(null) }
    var dayMask by rememberSaveable { mutableIntStateOf(0) }
    var selectedPlace by rememberSaveable { mutableStateOf<String?>(null) }
    var bodyweightOnly by rememberSaveable { mutableStateOf(false) }
    var equipmentMask by rememberSaveable { mutableIntStateOf(0) }
    var expandedEquipmentMask by rememberSaveable { mutableIntStateOf(1) }
    var bodyInputStep by rememberSaveable { mutableIntStateOf(0) }
    var selectedGender by rememberSaveable { mutableStateOf("none") }
    var heightInput by rememberSaveable { mutableStateOf("") }
    var weightInput by rememberSaveable { mutableStateOf("") }
    var ageInput by rememberSaveable { mutableStateOf("") }

    val bodyCanProceed = when (bodyInputStep) {
        0 -> selectedGender != "none"
        1 -> heightInput.isNotBlank()
        2 -> weightInput.isNotBlank()
        else -> ageInput.isNotBlank()
    }
    val canNext = when (step) {
        0 -> selectedGoal != null
        1 -> dayMask != 0
        2 -> selectedPlace != null
        else -> bodyCanProceed
    }

    fun goBack() {
        if (step == 3 && bodyInputStep > 0) {
            bodyInputStep -= 1
        } else if (step > 0) {
            step -= 1
        }
    }

    fun goNext() {
        if (step < 3) {
            step += 1
            return
        }
        when (bodyInputStep) {
            0, 1, 2 -> bodyInputStep += 1
            else -> onDone()
        }
    }

    BackHandler(enabled = step > 0 || (step == 3 && bodyInputStep > 0)) {
        goBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark)
            .imePadding()
            .padding(horizontal = 24.dp)
            .padding(top = 42.dp, bottom = 22.dp),
    ) {
        OnboardingProgressHeaderV2(step = step, total = 4)

        AnimatedContent(
            targetState = step,
            modifier = Modifier
                .weight(1f)
                .padding(top = 24.dp),
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                }.using(SizeTransform(clip = false))
            },
            label = "onboarding-step-v2",
        ) { visibleStep ->
            when (visibleStep) {
                0 -> GoalStepV2(
                    selectedGoal = selectedGoal,
                    onSelect = { selectedGoal = it },
                    onSkip = {
                        selectedGoal = "general"
                        Toast.makeText(context, "나중에 프로필에서 설정할 수 있습니다", Toast.LENGTH_SHORT).show()
                        goNext()
                    },
                )

                1 -> DaysStepV2(
                    dayMask = dayMask,
                    onToggleDay = { dayIndex ->
                        dayMask = dayMask xor (1 shl dayIndex)
                    },
                )

                2 -> PlaceStepV2(
                    selectedPlace = selectedPlace,
                    bodyweightOnly = bodyweightOnly,
                    equipmentMask = equipmentMask,
                    expandedMask = expandedEquipmentMask,
                    onBodyweightOnly = {
                        bodyweightOnly = true
                        equipmentMask = 0
                    },
                    onPlaceSelect = { place ->
                        selectedPlace = place
                        bodyweightOnly = false
                        equipmentMask = if (place == "gym") allEquipmentMaskV2() else 0
                        expandedEquipmentMask = 1
                    },
                    onToggleCategoryExpanded = { categoryIndex ->
                        expandedEquipmentMask = expandedEquipmentMask xor (1 shl categoryIndex)
                    },
                    onToggleCategoryAll = { category ->
                        val mask = categoryMaskV2(category)
                        val selectedAll = (equipmentMask and mask) == mask
                        equipmentMask = if (selectedAll) {
                            (equipmentMask and mask.inv()) and allEquipmentMaskV2()
                        } else {
                            equipmentMask or mask
                        }
                        bodyweightOnly = false
                    },
                    onToggleEquipment = { label ->
                        equipmentMask = equipmentMask xor equipmentBitV2(label)
                        bodyweightOnly = false
                    },
                )

                else -> BodyInfoStepV2(
                    inputStep = bodyInputStep,
                    selectedGender = selectedGender,
                    heightInput = heightInput,
                    weightInput = weightInput,
                    ageInput = ageInput,
                    onGender = {
                        selectedGender = it
                        bodyInputStep = 1
                    },
                    onSkipGender = {
                        selectedGender = "male"
                        bodyInputStep = 1
                    },
                    onHeight = { heightInput = sanitizeDecimalInputV2(it) },
                    onWeight = { weightInput = sanitizeDecimalInputV2(it) },
                    onAge = { ageInput = it.filter(Char::isDigit).take(3) },
                    onAverageHeight = {
                        heightInput = if (selectedGender == "female") "160.0" else "173.5"
                        bodyInputStep = 2
                    },
                    onAverageWeight = {
                        weightInput = if (selectedGender == "female") "58.4" else "74.3"
                        bodyInputStep = 3
                    },
                    onAverageAge = {
                        ageInput = "30"
                        onDone()
                    },
                )
            }
        }

        OnboardingBottomBarV2(
            step = step,
            bodyInputStep = bodyInputStep,
            canNext = canNext,
            onBack = ::goBack,
            onNext = ::goNext,
        )
    }
}
