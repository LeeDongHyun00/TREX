package com.example.trex_kotlin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================= 로그인 버튼 · 공룡 얼굴 · 로그인 로딩 화면
//
// 로그인 화면(AuthScreen)·커뮤니티 계정 안내·내 정보의 계정 칸이 같은 버튼을 쓴다. 예전에는 iPhone 의 Apple 버튼만 시스템 기본
// 버튼(UIKit)이고 구글은 앱의 녹색 버튼이라 둘의 크기·글자·모양이 달랐다(사용자 요청 2026-10-29 "애플과 구글 디자인 서로 맞출거야").
// 둘 다 Compose 로 같은 틀(높이·알약 모양·글자)에 그리고, 색·로고만 각 회사 지침을 따른다 — Apple HIG 는 지침을 지킨 직접 그린
// 버튼을 허용하고, 알약 모양은 Google 지침의 두 모양(사각·알약) 중 하나다.

/** 로그인 버튼의 회사. */
enum class SignInBrand { Apple, Google }

/** 버튼 색 — [border] 가 없으면 테두리를 그리지 않는다. */
internal data class SignInButtonColors(val container: Color, val content: Color, val border: Color?)

/**
 * 각 회사 지침의 밝은/어두운 배경용 색 그대로. Apple: 밝은 배경엔 검정, 어두운 배경엔 흰 버튼. Google: Light
 * (#FFFFFF · 테두리 #747775 · 글자 #1F1F1F) / Dark(#131314 · 테두리 #8E918F · 글자 #E3E3E3).
 */
internal fun signInButtonColors(brand: SignInBrand, dark: Boolean): SignInButtonColors = when (brand) {
    SignInBrand.Apple -> if (dark) SignInButtonColors(Color.White, Color.Black, null) else SignInButtonColors(Color.Black, Color.White, null)
    SignInBrand.Google -> if (dark) SignInButtonColors(Color(0xFF131314), Color(0xFFE3E3E3), Color(0xFF8E918F))
    else SignInButtonColors(Color.White, Color(0xFF1F1F1F), Color(0xFF747775))
}

/**
 * 버튼 문구 — Apple 이 허용한 제목("Continue with Apple")의 한국어 그대로이고, Google 도 같은 꼴로 맞춘다.
 * 공룡 말투 변환(TrexText)을 거치지 않는다 — 회사가 정한 문구라 바뀌면 안 된다.
 */
internal fun signInButtonTitle(brand: SignInBrand): String = when (brand) {
    SignInBrand.Apple -> "Apple로 계속하기"
    SignInBrand.Google -> "Google로 계속하기"
}

/** 버튼 줄의 공통 높이 — "로그인 없이 사용"도 같다. */
internal val SignInButtonHeight = 54.dp

/** Apple 로고 — Sign in with Apple 버튼에 쓰도록 Apple 이 허락한 단색 로고(글자색을 따른다). */
private val AppleLogo: ImageVector = ImageVector.Builder("AppleLogo", 24.dp, 24.dp, 24f, 24f).addPath(
    addPathNodes(
        "M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 " +
            "1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48 " +
            "3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559" +
            "-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052" +
            "-2.662.805-3.532 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701",
    ),
    fill = SolidColor(Color.Black),
).build()

/** Google "G" 로고 — 로그인 버튼용 표준 4색 그대로(색을 바꾸지 않는다). */
private val GoogleLogo: ImageVector = ImageVector.Builder("GoogleLogo", 48.dp, 48.dp, 48f, 48f)
    .addPath(
        addPathNodes("M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"),
        fill = SolidColor(Color(0xFFEA4335)),
    )
    .addPath(
        addPathNodes("M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"),
        fill = SolidColor(Color(0xFF4285F4)),
    )
    .addPath(
        addPathNodes("M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"),
        fill = SolidColor(Color(0xFFFBBC05)),
    )
    .addPath(
        addPathNodes("M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"),
        fill = SolidColor(Color(0xFF34A853)),
    )
    .build()

/** 버튼 줄의 공통 틀 — 알약 모양, 로고와 글자를 가운데 한 묶음으로. 누를 수 없을 때(로그인 중)는 흐리게. */
@Composable
private fun SignInButtonFrame(
    label: String,
    onClick: () -> Unit,
    colors: SignInButtonColors,
    enabled: Boolean,
    modifier: Modifier,
    logo: (@Composable () -> Unit)?,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(SignInButtonHeight).alpha(if (enabled) 1f else 0.55f),
        shape = CircleShape,
        color = colors.container,
        contentColor = colors.content,
        border = colors.border?.let { BorderStroke(1.dp, it) },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (logo != null) {
                logo()
                Spacer(Modifier.width(10.dp))
            }
            Text(label, color = colors.content, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Apple로 계속하기" · "Google로 계속하기" — 세 곳(로그인 화면·커뮤니티·내 정보)이 같은 버튼을 쓴다. */
@Composable
fun SocialSignInButton(brand: SignInBrand, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = signInButtonColors(brand, Trex.c.isDark)
    SignInButtonFrame(signInButtonTitle(brand), onClick, colors, enabled, modifier) {
        when (brand) {
            SignInBrand.Apple -> Icon(AppleLogo, contentDescription = null, tint = colors.content, modifier = Modifier.size(20.dp))
            SignInBrand.Google -> Image(GoogleLogo, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * "로그인 없이 사용" — 두 로그인 버튼과 같은 틀·간격. 바탕 없이 테두리만 둔다 — 채우면 어두운 화면에서 Google 버튼(#131314)보다
 * 눈에 띄었다(2026-10-29 화면 확인).
 */
@Composable
fun WithoutAccountButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Trex.c
    SignInButtonFrame("로그인 없이 사용", onClick, SignInButtonColors(Color.Transparent, c.text, c.fieldLine), enabled, modifier, logo = null)
}

/** 앱의 공룡 얼굴 — 종이를 찢고 나온 로그인 로고에서 공룡만 오린 그림(tools/icon/make_dino_head.py, 정사각 캔버스 가운데). */
@Composable
fun TrexDinoHead(modifier: Modifier = Modifier) {
    Image(trexDinoHeadImage(), contentDescription = "TREX 로고", modifier = modifier, contentScale = ContentScale.Fit)
}

/** 공룡 그림이 원 지름에서 차지하는 비율 — 머리 끝이 그림 반폭의 1.025배까지 가서, 0.86 이면 원 반지름의 88% 안에 든다. */
private const val DINO_IN_BADGE = 0.86f

/**
 * 검정 원 안의 공룡 얼굴(로그인·로그인 로딩 화면) — 밝은 바탕에서는 오린 가장자리가 드러났다(사용자 요청 2026-10-29
 * "누끼가 티나서 주변에 검정색 원으로 감싸"). 원래 그림도 검은 구멍 속 공룡이라, 외곽선이 검정 바탕에 묻혀 원본처럼 보인다.
 */
@Composable
fun TrexDinoBadge(modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
        TrexDinoHead(Modifier.fillMaxSize(DINO_IN_BADGE))
    }
}

/**
 * 로그인 로딩 화면 — 계정 선택을 마친 뒤 서버 로그인·첫 동기화를 기다리는 동안 앱 전체를 덮는다([AppViewModel.authVerifying]).
 * 가운데 로딩바 하나만 둔다(동현님 요청 2026-10-30 — 처음엔 공룡이 고개를 까딱이고 점 셋이 깜빡였다). 뒤 화면의 버튼이 눌리지 않게
 * 터치를 여기서 받는다. 글자는 없고 화면 읽기에는 "로그인하는 중"으로 읽힌다.
 */
@Composable
fun SignInLoadingScreen(modifier: Modifier = Modifier) {
    val c = Trex.c
    Box(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .semantics(mergeDescendants = true) { contentDescription = "로그인하는 중" },
        contentAlignment = Alignment.Center,
    ) {
        // 앱의 다른 로딩바(사진 식단 분석)와 같은 모양 — 8dp, 둥근 끝, primary 위 track
        LinearProgressIndicator(
            modifier = Modifier.width(200.dp).height(8.dp).clip(CircleShape),
            color = c.primary,
            trackColor = c.track,
        )
    }
}
