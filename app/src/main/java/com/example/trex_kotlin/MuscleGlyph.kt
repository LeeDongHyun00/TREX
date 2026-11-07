package com.example.trex_kotlin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.trex_kotlin.trainingload.Muscle
import com.example.trex_kotlin.trainingload.MuscleLoadCatalog

/*
 * 근육 글리프(2026-11-07, 사용자 요청 "근육 피로도 이미지 퀄리티 개선") — 막대 인형 대신 **2D 근육도(Z-Anatomy, atlas-2d.js)의 실루엣과 근육 영역**을
 * 작게 그린다. 앞·뒷면 두 실루엣을 나란히(Hevy·Fitbod·Muscle Map 류 앱의 '회색 실루엣 + 색칠한 근육' 관례). 경로는 `MuscleGlyphPaths.kt`(생성본,
 * `tools/gen_muscle_glyph_paths.py` 가 1.5 단위로 단순화)에서 읽어 한 번 파싱해 둔다. 색칠은 '어디를 쓰는가' 이지 자극 강도·순위가 아니다.
 */

// 실루엣·영역에 구멍(팔과 몸통 사이 등)이 있어 EvenOdd 로 채운다(생성 파일 머리말)
private fun parse(d: String): Path = PathParser().parsePathString(d).toPath().apply { fillType = PathFillType.EvenOdd }
private val frontBodyPath: Path by lazy { parse(MuscleGlyphPaths.frontBody) }
private val backBodyPath: Path by lazy { parse(MuscleGlyphPaths.backBody) }
private val frontRegionPaths: Map<String, Path> by lazy { MuscleGlyphPaths.front.mapValues { parse(it.value) } }
private val backRegionPaths: Map<String, Path> by lazy { MuscleGlyphPaths.back.mapValues { parse(it.value) } }

/** 카탈로그 세부 부위(`catalogRegionOf`)·운동 카테고리 → 글리프 부위(전신·하체·상체·코어). 가슴·등·어깨·팔은 상체, 복근은 코어, 유산소·회복은 전신. */
fun bodyPartOf(region: String): String = when (region) {
    "하체" -> "하체"
    "가슴", "등", "어깨", "팔", "상체" -> "상체"
    "코어", "복근" -> "코어"
    else -> "전신"
}

/** 종목 이름·카테고리로 바로 — 프로필 없는 종목의 대체 키를 고를 때. */
fun bodyPartForWorkout(name: String, category: String): String = bodyPartOf(catalogRegionOf(name, category))

/** 근육 → 좌우 키 둘(`QUADS_L`·`QUADS_R`). */
fun muscleKeys(muscles: Collection<Muscle>): Set<String> = muscles.flatMap { listOf("${it.name}_L", "${it.name}_R") }.toSet()

/** 종목의 주 근육 키(가중치 ≥ .75). 프로필이 없는 직접 추가 종목은 부위 글리프 키([partMuscleKeys])로. */
fun muscleKeysFor(name: String, category: String = ""): Set<String> {
    val p = MuscleLoadCatalog.find(name) ?: return partMuscleKeys(bodyPartForWorkout(name, category))
    val primary = p.primary.ifEmpty { p.weights.entries.sortedByDescending { it.value }.take(2).map { it.key } }
    return muscleKeys(primary)
}

/** 부위 → 대표 근육 키 — 프로필 없는 종목·홈 칸의 대체. */
fun partMuscleKeys(part: String): Set<String> = muscleKeys(when (part) {
    "하체" -> listOf(Muscle.QUADS, Muscle.GLUTES, Muscle.HAMSTRINGS)
    "상체" -> listOf(Muscle.PECS, Muscle.DELTOIDS, Muscle.LATS)
    "코어" -> listOf(Muscle.ABS, Muscle.OBLIQUES)
    else -> listOf(Muscle.QUADS, Muscle.PECS, Muscle.LATS, Muscle.ABS)
})

/**
 * 근육 글리프 — [height] 높이의 둥근 바탕 안에 앞면·뒷면 실루엣(연한 색)을 나란히 두고 [keys] 의 근육만 [highlight] 색으로 칠한다.
 * [showBack] 거짓이면 앞면 하나. 바탕 폭은 실루엣 비율(360:1200)에서 나온다.
 */
@Composable
fun MuscleGlyph(keys: Set<String>, height: Dp = 44.dp, modifier: Modifier = Modifier, showBack: Boolean = true,
    background: Color = Trex.c.surface2, highlight: Color = Trex.c.primary, description: String = "사용 근육") {
    val c = Trex.c
    val silhouette = c.line
    val bodyH = height - 8.dp
    val bodyW = bodyH * (MuscleGlyphPaths.VIEW_W / MuscleGlyphPaths.VIEW_H)
    val gap = 3.dp
    val boxW = if (showBack) bodyW * 2 + gap + 12.dp else bodyW + 12.dp
    Box(modifier.height(height).width(boxW).clip(RoundedCornerShape(height * 0.28f)).background(background)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.padding(6.dp, 4.dp).height(bodyH).width(if (showBack) bodyW * 2 + gap else bodyW)) {
            val s = size.height / MuscleGlyphPaths.VIEW_H
            drawBody(frontBodyPath, frontRegionPaths, keys, s, silhouette, highlight)
            if (showBack) translate(left = bodyW.toPx() + gap.toPx()) { drawBody(backBodyPath, backRegionPaths, keys, s, silhouette, highlight) }
        }
    }
}

private fun DrawScope.drawBody(body: Path, regions: Map<String, Path>, keys: Set<String>, s: Float, silhouette: Color, highlight: Color) {
    scale(s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
        drawPath(body, silhouette)
        keys.forEach { k -> regions[k]?.let { drawPath(it, highlight) } }
    }
}
