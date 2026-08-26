package com.example.trex_kotlin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TREX 리디자인 디자인 시스템 (claude.ai 디자인 캔버스 "TREX Redesign.dc.html" 구현).
 *
 * 라이트 우선 + 다크 + 시스템 모드. Primary #759848 고정.
 * 토큰명은 디자인 원본의 CSS 변수(--bg/--surface/--primaryWash …)를 그대로 따른다.
 */

enum class ThemeMode { Light, Dark, System }

@Immutable
data class TrexColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val sheet: Color,
    val field: Color,
    val fieldLine: Color,
    val line: Color,
    val track: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val primary: Color,
    val primaryText: Color,
    val primaryWash: Color,
    val primarySoftLine: Color,
    val lime: Color,
    val warn: Color,
    val warnWash: Color,
    val warnLine: Color,
    val err: Color,
    val errWash: Color,
    val errLine: Color,
    val navGlass: Color,
    val stripeA: Color,
    val stripeB: Color,
)

val TrexLightColors = TrexColors(
    isDark = false,
    bg = Color(0xFFEFF1EA), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF3F5EE),
    sheet = Color(0xFFF7F8F3), field = Color(0xFFFFFFFF), fieldLine = Color(0x1F1F2618),
    line = Color(0x171F2618), track = Color(0x171F2618),
    text = Color(0xFF1F2618), text2 = Color(0xFF5E6754), text3 = Color(0xFF8B937D),
    primary = Color(0xFF759848), primaryText = Color(0xFF5F7D39),
    primaryWash = Color(0xFFE7EEDA), primarySoftLine = Color(0x38759848),
    lime = Color(0xFFC7E26B),
    warn = Color(0xFFD78B28), warnWash = Color(0xFFFBF0DE), warnLine = Color(0x38D78B28),
    err = Color(0xFFC65454), errWash = Color(0x17C65454), errLine = Color(0x38C65454),
    navGlass = Color(0xD1EFF1EA),
    stripeA = Color(0xFFE4E7DC), stripeB = Color(0xFFEDEFE7),
)

val TrexDarkColors = TrexColors(
    isDark = true,
    bg = Color(0xFF14180E), surface = Color(0xFF1F2618), surface2 = Color(0xFF27301E),
    sheet = Color(0xFF1B2115), field = Color(0xFF1F2618), fieldLine = Color(0x21FFFFFF),
    line = Color(0x17FFFFFF), track = Color(0x1CFFFFFF),
    text = Color(0xFFF1F4EA), text2 = Color(0xFFA9B49B), text3 = Color(0xFF7C866F),
    primary = Color(0xFF759848), primaryText = Color(0xFFA8C47C),
    primaryWash = Color(0x2E759848), primarySoftLine = Color(0x38A8C47C),
    lime = Color(0xFFC7E26B),
    warn = Color(0xFFD78B28), warnWash = Color(0x24D78B28), warnLine = Color(0x42D78B28),
    err = Color(0xFFE28C8C), errWash = Color(0x24C65454), errLine = Color(0x4DC65454),
    navGlass = Color(0xD114180E),
    stripeA = Color(0xFF232C1B), stripeB = Color(0xFF1C2315),
)

val LocalTrexColors = staticCompositionLocalOf { TrexLightColors }

/** 현재 테마 토큰 — 모든 리디자인 화면이 이걸 통해 색을 읽는다. */
object Trex {
    val c: TrexColors
        @Composable get() = LocalTrexColors.current
}

@Composable
fun resolveDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.System -> isSystemInDarkTheme()
}

@Composable
fun TrexAppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = resolveDark(mode)
    val c = if (dark) TrexDarkColors else TrexLightColors
    val scheme = if (dark) {
        darkColorScheme(primary = c.primary, background = c.bg, surface = c.surface, onSurface = c.text, onBackground = c.text)
    } else {
        lightColorScheme(primary = c.primary, background = c.bg, surface = c.surface, onSurface = c.text, onBackground = c.text)
    }
    CompositionLocalProvider(LocalTrexColors provides c) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography.copy(
                titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
                bodyMedium = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.sp),
            ),
            content = content,
        )
    }
}

// ---- 공용 아톰 (디자인 원본의 반복 패턴)

/** 작은 자간 킥커 라벨 ("오늘 섭취", "RECOVER" …) */
@Composable
fun Kicker(text: String, color: Color = Trex.c.text3) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, maxLines = 1)
}

@Composable
fun TitleBig(text: String, size: Int = 22) {
    Text(text, color = Trex.c.text, fontSize = size.sp, lineHeight = (size * 1.3).sp, fontWeight = FontWeight.Bold)
}

/** Primary CTA (녹색 채움, 흰 글자). */
@Composable
fun Cta(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 54.dp,
) {
    val c = Trex.c
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(18.dp),
        color = if (enabled) c.primary else c.track,
        contentColor = if (enabled) Color.White else c.text3,
        shadowElevation = if (enabled) 6.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 서피스 톤 보조 버튼. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 50.dp,
    tone: Color? = null,
) {
    val c = Trex.c
    Surface(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(18.dp),
        color = c.surface2,
        contentColor = tone ?: c.text2,
        border = BorderStroke(1.dp, c.line),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = c.primaryText)
                Spacer(Modifier.width(6.dp))
            }
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 둥근 아이콘 버튼 (중립 닫기/뒤로 — 빨강은 파괴적 동작 전용). */
@Composable
fun RoundIcon(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    contentDescription: String? = null,
) {
    val c = Trex.c
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = c.surface,
        contentColor = c.text3,
        border = BorderStroke(1.dp, c.line),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(size * 0.45f))
        }
    }
}

@Composable
fun SheetClose(onClick: () -> Unit) = RoundIcon(Icons.Rounded.Close, onClick, contentDescription = "닫기")

/** 세그먼트 토글 (로그인/회원가입, 아이디/비번 찾기, 화면 모드, 끼니 탭). */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 42.dp,
    filled: Boolean = false,
) {
    val c = Trex.c
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Surface(
                onClick = { onSelect(i) },
                modifier = Modifier.weight(1f).height(height),
                shape = RoundedCornerShape(12.dp),
                color = when {
                    sel && filled -> c.primary
                    sel -> c.surface
                    else -> Color.Transparent
                },
                contentColor = when {
                    sel && filled -> Color.White
                    sel -> c.text
                    else -> c.text3
                },
                shadowElevation = if (sel) 2.dp else 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 가로 스크롤 필터 칩 — 항목이 많아 세그먼트로 나누기 어려운 목록(운동 카테고리)용.
 *
 * 선택된 칩만 primary 로 채우고 나머지는 서피스 톤으로 두는 리디자인 규칙을 따른다.
 */
@Composable
fun FilterChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
) {
    val c = Trex.c
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        options.forEach { option ->
            val sel = option == selected
            Surface(
                onClick = { onSelect(option) },
                shape = RoundedCornerShape(999.dp),
                color = if (sel) c.primary else c.surface2,
                contentColor = if (sel) Color.White else c.text2,
                border = BorderStroke(1.dp, if (sel) Color.Transparent else c.line),
                shadowElevation = if (sel) 3.dp else 0.dp,
            ) {
                Box(Modifier.height(height).padding(horizontal = 15.dp), contentAlignment = Alignment.Center) {
                    Text(option, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** 리디자인 입력 필드. */
@Composable
fun DField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    hint: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    height: Dp = 50.dp,
    centered: Boolean = false,
    textSize: Int = 14,
) {
    val c = Trex.c
    Column(modifier) {
        if (label != null) {
            Text(label, color = c.text2, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(5.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(
                color = c.text, fontSize = textSize.sp, fontWeight = if (centered) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
            ),
            cursorBrush = SolidColor(c.primary),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height)
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.field)
                        .border(1.dp, c.fieldLine, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp),
                    contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart,
                ) {
                    if (value.isBlank()) {
                        Text(placeholder, color = c.text3, fontSize = textSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, color = c.text3, fontSize = 11.sp)
        }
    }
}

/** 원형 진행 링 (칼로리/타이머). */
@Composable
fun RingGauge(
    progress: Float,
    size: Dp,
    stroke: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = Trex.c
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val radius = (this.size.minDimension - sw) / 2f
            drawCircle(color = c.track, radius = radius, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(
                color = c.primary,
                startAngle = -90f,
                sweepAngle = progress.coerceIn(0f, 1f) * 360f,
                useCenter = false,
                style = Stroke(sw, cap = StrokeCap.Round),
                topLeft = Offset(sw / 2f, sw / 2f),
                size = Size(this.size.width - sw, this.size.height - sw),
            )
        }
        content()
    }
}

/** 가로 진행 바. */
@Composable
fun TrackBar(progress: Float, color: Color = Trex.c.primary, height: Dp = 6.dp, modifier: Modifier = Modifier) {
    val c = Trex.c
    Box(
        modifier = modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(999.dp)).background(c.track),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(999.dp))
                .background(color),
        )
    }
}

/** 라벨 + 값/목표 + 진행 바 (탄단지). */
@Composable
fun MacroBar(label: String, value: Int, goal: Int, color: Color, barHeight: Dp = 6.dp) {
    val c = Trex.c
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, color = c.text2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$value / ${goal}g", color = c.text3, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(5.dp))
        TrackBar(progress = if (goal > 0) value / goal.toFloat() else 0f, color = color, height = barHeight)
    }
}

/** ± 스테퍼 (목표/세트/수량 수정). */
@Composable
fun StepperControl(
    valueLabel: String,
    onDec: () -> Unit,
    onInc: () -> Unit,
    decIcon: ImageVector = Icons.Rounded.Remove,
    valueMinWidth: Dp = 48.dp,
) {
    val c = Trex.c
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(999.dp))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Surface(onClick = onDec, modifier = Modifier.size(32.dp), shape = CircleShape, color = Color.Transparent, contentColor = c.text2) {
            Box(contentAlignment = Alignment.Center) { Icon(decIcon, contentDescription = "감소", modifier = Modifier.size(15.dp)) }
        }
        Text(
            valueLabel,
            color = c.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(valueMinWidth),
        )
        Surface(
            onClick = onInc, modifier = Modifier.size(32.dp), shape = CircleShape,
            color = c.surface, contentColor = c.primaryText, shadowElevation = 1.dp,
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, contentDescription = "증가", modifier = Modifier.size(15.dp)) }
        }
    }
}

/** 바텀 시트 호스트 (스크림 + 하단 고정, 닫기는 스크림 탭). */
@Composable
fun SheetHost(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Trex.c
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB01A2010))
            .clickable(interactionSource = remember2(), indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember2(), indication = null, onClick = {}),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = c.sheet,
            contentColor = c.text,
        ) {
            Box(Modifier.navigationBarsPadding()) { content() }
        }
    }
}

@Composable
private fun remember2(): MutableInteractionSource =
    androidx.compose.runtime.remember { MutableInteractionSource() }

/** 시트 상단 그랩 핸들. */
@Composable
fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 9.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(38.dp).height(4.dp).clip(RoundedCornerShape(999.dp)).background(Trex.c.track))
    }
}

/** 워시 톤 안내 배너 (팁/경고). */
@Composable
fun WashBanner(text: String, icon: ImageVector, warnTone: Boolean = false) {
    val c = Trex.c
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (warnTone) c.warnWash else c.primaryWash)
            .border(1.dp, if (warnTone) c.warnLine else c.primarySoftLine, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = if (warnTone) c.warn else c.primaryText, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = c.text2, fontSize = 11.5.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
    }
}
