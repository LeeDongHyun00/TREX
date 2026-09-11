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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
    /** assets/guid_img 의 공룡 일러스트 SVG. */
    val asset: String,
)

private val guidePages = listOf(
    GuidePage(
        headline = "움직임이 잘 보이도록\n카메라를 세워두세요",
        body = "전신과 주요 관절이 화면 안에 들어오면 TREX가 자세 변화를 더 정확하게 읽어줘룡",
        slot = "camera setup",
        asset = "trex_guideImage_phone1.svg",
    ),
    GuidePage(
        headline = "실시간 피드백으로\n루틴의 흐름을 유지하세요",
        body = "동작 중 필요한 교정 신호를 바로 확인하고, 세트가 끝날 때까지 같은 리듬으로 운동해룡",
        slot = "live feedback",
        asset = "trext_guideImage_phone2.svg",
    ),
    GuidePage(
        headline = "운동이 끝나면\n기록을 한눈에 정리해요",
        body = "완료한 운동과 개선 포인트를 하루 단위로 남겨 다음 루틴을 더 쉽게 이어가룡",
        slot = "weekly record",
        asset = "trext_guideImage_phone3.svg",
    ),
    GuidePage(
        headline = "사진 한 장으로\n식단 기록을 시작하세요",
        body = "식사 사진을 고르면 음식을 분석하고 탄단지까지 정리해줘룡",
        slot = "photo diet log",
        asset = "trext_guideImage_phone4.svg",
    ),
)

/** 가이드북 (리디자인) — 늘어나는 진행 점 + 아이콘 카드 + 이전/다음. */
@Composable
fun GuideBookScreen(onDone: () -> Unit) {
    val c = Trex.c
    var page by rememberSaveable { mutableIntStateOf(0) }
    val last = page == guidePages.lastIndex
    val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxSize().background(c.bg).navigationBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(targetState = page,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "guide-image", modifier = Modifier.fillMaxSize()) { p ->
                GuideSvgImage(guidePages[p].asset, Modifier.fillMaxSize())
            }
            if (!largeText) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, c.bg.copy(alpha = .94f), c.bg))))
                GuideCaption(page, Modifier.align(Alignment.BottomStart).padding(horizontal = 26.dp, vertical = 20.dp))
            }
            Row(Modifier.statusBarsPadding().padding(26.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                guidePages.indices.forEach { i ->
                    Box(Modifier.width(if (i == page) 24.dp else 8.dp).height(4.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (i == page) c.primary else c.text3.copy(alpha = .4f)))
                }
            }
        }
        if (largeText) GuideCaption(page, Modifier.padding(horizontal = 26.dp, vertical = 12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton(if (page == 0) "건너뛰기" else "이전", onClick = { if (page == 0) onDone() else page-- }, modifier = Modifier.width(100.dp))
            Cta(if (last) "시작하기" else "다음", onClick = { if (last) onDone() else page++ }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun GuideCaption(page: Int, modifier: Modifier = Modifier) {
    val titles = listOf("휴대폰을 놓고,\n전신을 담으세요.", "움직임을 보며,\n자세를 비교해요.", "운동의 변화를\n기록으로 남겨요.", "사진 한 장으로\n식단을 기록하세요.")
    Column(modifier) {
        Text(titles[page], color = Trex.c.text, fontSize = 27.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** assets/guid_img 의 공룡 SVG 를 WebView 로 렌더 (복잡한 SVG 라 VectorDrawable 변환 불가). */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GuideSvgImage(assetName: String, modifier: Modifier = Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { context ->
            android.webkit.WebView(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                isClickable = false
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
            }
        },
        update = { webView ->
            if (webView.tag != assetName) {
            webView.tag = assetName
            val html = """
                <!doctype html>
                <html><head><meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                  html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:transparent}
                  body{display:flex;align-items:center;justify-content:center}
                  img{width:100%;height:100%;object-fit:cover;object-position:50% 35%;display:block}
                </style></head>
                <body><img src="$assetName"></body></html>
            """.trimIndent()
            webView.loadDataWithBaseURL("file:///android_asset/guid_img/", html, "text/html", "UTF-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}
