package com.example.trex_kotlin

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Login
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private data class GuidePage(
    val asset: String,
    val headline: String,
    val body: String,
)

private val guidePages = listOf(
    GuidePage(
        asset = "trex_guideImage_phone1.svg",
        headline = "움직임이 잘 보이도록\n카메라를 세워두세요",
        body = "카메라 자세 기능은 검증과 출시 승인이 끝난 운동에서만 제공하며, 운동 목록에서 상태를 먼저 알려줘룡",
    ),
    GuidePage(
        asset = "trext_guideImage_phone2.svg",
        headline = "검증 상태에 맞춰\n안전하게 루틴을 이어가세요",
        body = "승인된 자세 기준이 없으면 임의 점수나 교정 신호 대신 타이머 모드로 운동해룡",
    ),
    GuidePage(
        asset = "trext_guideImage_phone3.svg",
        headline = "운동이 끝나면\n기록을 한눈에 정리해요",
        body = "완료한 운동 기록을 정리하고, 검증된 결과가 있을 때만 자세 개선 항목을 남겨룡",
    ),
    GuidePage(
        asset = "trext_guideImage_phone4.svg",
        headline = "사진 한 장으로\n식단 기록을 시작하세요",
        body = "식사 사진을 선택하면 음식을 분석하고 영양 정보를 기록하기 쉽게 정리해줘룡",
    ),
)

@Composable
fun GuideBookScreen(onLogin: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val current = guidePages[page]
    val last = page == guidePages.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TrexDark),
    ) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                fadeIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                ) togetherWith fadeOut()
            },
            label = "guide-page",
        ) { guide ->
            SvgAssetBackground(assetName = guide.asset)
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(470.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            TrexDark.copy(alpha = 0.26f),
                            TrexDark.copy(alpha = 0.72f),
                            TrexDark,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    fadeIn(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ) togetherWith fadeOut()
                },
                label = "guide-copy",
            ) { guide ->
                Column {
                    Text(
                        text = guide.headline,
                        color = Color.White,
                        fontSize = 31.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        text = guide.body,
                        color = Color.White.copy(alpha = 0.74f),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                guidePages.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == page) 9.dp else 6.dp)
                            .background(
                                color = if (index == page) TrexLime else Color.White.copy(alpha = 0.34f),
                                shape = CircleShape,
                            ),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            TrexButton(
                text = if (last) "로그인하고 시작하기" else "다음",
                onClick = {
                    if (last) {
                        onLogin()
                    } else {
                        page += 1
                    }
                },
                icon = if (last) Icons.AutoMirrored.Rounded.Login else Icons.AutoMirrored.Rounded.ArrowForward,
                modifier = Modifier.fillMaxWidth(),
                height = 54.dp,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SvgAssetBackground(assetName: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
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
            val html = """
                <!doctype html>
                <html>
                  <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                      html, body {
                        margin: 0;
                        padding: 0;
                        width: 100%;
                        height: 100%;
                        overflow: hidden;
                        background: #1F2618;
                      }
                      body {
                        display: flex;
                        align-items: center;
                        justify-content: center;
                      }
                      img {
                        width: 100%;
                        height: 100%;
                        object-fit: contain;
                        display: block;
                      }
                    </style>
                  </head>
                  <body>
                    <img src="$assetName" />
                  </body>
                </html>
            """.trimIndent()
            webView.loadDataWithBaseURL(
                "file:///android_asset/guid_img/",
                html,
                "text/html",
                "UTF-8",
                null,
            )
        },
    )
}
