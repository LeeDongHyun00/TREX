package com.example.trex_kotlin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class GuidePage(
    val headline: String,
    val body: String,
    val slot: String,
    val icon: ImageVector,
)

private val guidePages = listOf(
    GuidePage(
        headline = "움직임이 잘 보이도록\n카메라를 세워두세요",
        body = "전신과 주요 관절이 화면 안에 들어오면 TREX가 자세 변화를 더 정확하게 읽어줘룡",
        slot = "camera setup",
        icon = Icons.Rounded.PhotoCamera,
    ),
    GuidePage(
        headline = "실시간 피드백으로\n루틴의 흐름을 유지하세요",
        body = "동작 중 필요한 교정 신호를 바로 확인하고, 세트가 끝날 때까지 같은 리듬으로 운동해룡",
        slot = "live feedback",
        icon = Icons.Rounded.Visibility,
    ),
    GuidePage(
        headline = "운동이 끝나면\n기록을 한눈에 정리해요",
        body = "완료한 운동과 개선 포인트를 하루 단위로 남겨 다음 루틴을 더 쉽게 이어가룡",
        slot = "weekly record",
        icon = Icons.Rounded.BarChart,
    ),
    GuidePage(
        headline = "사진 한 장으로\n식단 기록을 시작하세요",
        body = "식사 사진을 고르면 음식을 분석하고 탄단지까지 정리해줘룡",
        slot = "photo diet log",
        icon = Icons.Rounded.Restaurant,
    ),
)

/** 가이드북 (리디자인) — 늘어나는 진행 점 + 아이콘 카드 + 이전/다음. */
@Composable
fun GuideBookScreen(onDone: () -> Unit) {
    val c = Trex.c
    var page by rememberSaveable { mutableIntStateOf(0) }
    val last = page == guidePages.lastIndex

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.padding(start = 22.dp, end = 22.dp, top = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                guidePages.indices.forEach { i ->
                    val w by animateDpAsState(if (i == page) 26.dp else 10.dp, tween(340), label = "guide-dot$i")
                    Box(
                        Modifier
                            .width(w).height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (i == page) c.primary else c.track),
                    )
                }
            }
            Text(
                "건너뛰기",
                color = c.text3, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onDone).padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }

        Column(Modifier.weight(1f).padding(start = 22.dp, end = 22.dp, top = 22.dp)) {
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(32.dp),
                color = c.surface,
                border = BorderStroke(1.dp, c.line),
                shadowElevation = 2.dp,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(c.primaryWash, Color.Transparent),
                                radius = 700f,
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInHorizontally(tween(340)) { it / 3 } + fadeIn()) togetherWith
                                    (slideOutHorizontally(tween(340)) { -it / 4 } + fadeOut())
                            } else {
                                (slideInHorizontally(tween(340)) { -it / 3 } + fadeIn()) togetherWith
                                    (slideOutHorizontally(tween(340)) { it / 4 } + fadeOut())
                            }
                        },
                        label = "guide-card",
                    ) { p ->
                        val g = guidePages[p]
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Surface(
                                modifier = Modifier.size(86.dp),
                                shape = RoundedCornerShape(30.dp),
                                color = c.surface,
                                border = BorderStroke(1.dp, c.line),
                                shadowElevation = 2.dp,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(g.icon, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(36.dp))
                                }
                            }
                            Text(
                                g.slot,
                                color = c.text3, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(c.surface)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            AnimatedContent(
                targetState = page,
                transitionSpec = { (fadeIn(tween(300)) togetherWith fadeOut(tween(200))) },
                label = "guide-copy",
            ) { p ->
                val g = guidePages[p]
                Column(Modifier.padding(top = 24.dp)) {
                    Text(g.headline, color = c.text, fontSize = 25.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold)
                    Text(g.body, color = c.text2, fontSize = 13.5.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 11.dp))
                }
            }
        }

        Row(
            Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.alpha(if (page == 0) 0.4f else 1f)) {
                RoundIcon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    onClick = { if (page > 0) page -= 1 },
                    size = 54.dp,
                    contentDescription = "이전",
                )
            }
            Cta(
                text = if (last) "시작하기" else "다음",
                onClick = { if (last) onDone() else page += 1 },
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
