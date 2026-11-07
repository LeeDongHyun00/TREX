package com.example.trex_kotlin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 부위 글리프(UI 감사 2026-11-07 A6·A8·A11, 기준 K6 "둘러보기는 그림") — 사람 실루엣 하나에 부위만 색칠한다.
 * 종목 72개 중 가이드 GIF 가 있는 것은 16개뿐이라 목록 행은 전부 이 글리프로 통일한다(사진과 글리프를 섞으면 목록이 들쭉날쭉하다).
 * 어휘는 넷(전신·하체·상체·코어) — 가슴·등·어깨·팔은 상체, 복근은 코어, 유산소·회복은 전신으로 접는다. 그림은 부위 안내일 뿐 자세 정답이 아니다.
 */

/** 카탈로그 세부 부위(`catalogRegionOf`)·운동 카테고리 → 글리프 부위. */
fun bodyPartOf(region: String): String = when (region) {
    "하체" -> "하체"
    "가슴", "등", "어깨", "팔", "상체" -> "상체"
    "코어", "복근" -> "코어"
    else -> "전신"
}

/** 종목 이름·카테고리로 바로 — 운동 탭·카탈로그 행에서 쓴다. */
fun bodyPartForWorkout(name: String, category: String): String = bodyPartOf(catalogRegionOf(name, category))

/** 근육 → 글리프 부위 — 홈 근육 피로도 칸(UI 감사 A3)이 가장 피로한 근육의 자리를 색칠할 때. */
fun bodyPartOfMuscle(m: com.example.trex_kotlin.trainingload.Muscle): String = when (m) {
    com.example.trex_kotlin.trainingload.Muscle.QUADS, com.example.trex_kotlin.trainingload.Muscle.GLUTES, com.example.trex_kotlin.trainingload.Muscle.ABDUCTORS,
    com.example.trex_kotlin.trainingload.Muscle.ADDUCTORS, com.example.trex_kotlin.trainingload.Muscle.HAMSTRINGS, com.example.trex_kotlin.trainingload.Muscle.CALVES,
    com.example.trex_kotlin.trainingload.Muscle.HIP_FLEXORS, com.example.trex_kotlin.trainingload.Muscle.TIBIALIS -> "하체"
    com.example.trex_kotlin.trainingload.Muscle.ABS, com.example.trex_kotlin.trainingload.Muscle.OBLIQUES, com.example.trex_kotlin.trainingload.Muscle.ERECTORS,
    com.example.trex_kotlin.trainingload.Muscle.QUADRATUS_LUMBORUM -> "코어"
    else -> "상체"
}

/**
 * 부위 글리프 — [part] 는 전신·하체·상체·코어. [size] 안에 둥근 바탕(surface2)과 실루엣. 색칠은 primary, 나머지는 line 색.
 * Canvas 만 쓰므로 iPhone 도 그대로 그린다.
 */
@Composable
fun BodyPartGlyph(part: String, size: Dp = 40.dp, modifier: Modifier = Modifier, background: Color = Trex.c.surface2) {
    val c = Trex.c
    val on = c.primary
    val off = c.line
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(background).semantics { contentDescription = "$part 부위" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size).padding(size * 0.14f)) {
            val upper = part == "상체" || part == "전신"
            val core = part == "코어" || part == "전신"
            val lower = part == "하체" || part == "전신"
            drawFigure(upper, core, lower, on, off)
        }
    }
}

private fun DrawScope.drawFigure(upper: Boolean, core: Boolean, lower: Boolean, on: Color, off: Color) {
    val w = size.width; val h = size.height
    fun x(v: Float) = w * v
    fun y(v: Float) = h * v
    val limb = w * 0.13f
    // 머리 — 늘 바탕색(부위가 아니다)
    drawCircle(off, radius = w * 0.1f, center = Offset(x(0.5f), y(0.11f)))
    // 팔 — 상체
    val arm = if (upper) on else off
    drawLine(arm, Offset(x(0.34f), y(0.28f)), Offset(x(0.17f), y(0.55f)), strokeWidth = limb, cap = StrokeCap.Round)
    drawLine(arm, Offset(x(0.66f), y(0.28f)), Offset(x(0.83f), y(0.55f)), strokeWidth = limb, cap = StrokeCap.Round)
    // 몸통 — 위(상체)·아래(코어) 두 칸
    val torsoLeft = x(0.33f); val torsoW = x(0.34f)
    drawRoundRect(if (upper) on else off, Offset(torsoLeft, y(0.24f)), Size(torsoW, y(0.19f)), CornerRadius(w * 0.08f, w * 0.08f))
    drawRoundRect(if (core) on else off, Offset(torsoLeft, y(0.43f)), Size(torsoW, y(0.17f)), CornerRadius(w * 0.06f, w * 0.06f))
    // 다리 — 하체
    val leg = if (lower) on else off
    drawLine(leg, Offset(x(0.42f), y(0.62f)), Offset(x(0.37f), y(0.95f)), strokeWidth = limb * 1.15f, cap = StrokeCap.Round)
    drawLine(leg, Offset(x(0.58f), y(0.62f)), Offset(x(0.63f), y(0.95f)), strokeWidth = limb * 1.15f, cap = StrokeCap.Round)
    // 몸통 테두리로 칸 사이를 띄운다
    drawRoundRect(Color.Transparent, Offset(torsoLeft, y(0.24f)), Size(torsoW, y(0.36f)), CornerRadius(w * 0.08f, w * 0.08f), style = Stroke(1f))
}
