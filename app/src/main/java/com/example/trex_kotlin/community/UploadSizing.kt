package com.example.trex_kotlin.community

import kotlin.math.roundToInt

/** 올릴 사진 크기 — 긴 변이 [maxDimension] 을 넘으면 비율을 지켜 줄이고(최소 1px), 작으면 그대로(설계 §5 글쓰기: 긴 변 1080). */
object UploadSizing {
    fun fit(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        val longer = maxOf(width, height)
        if (longer <= maxDimension) return width to height
        val scale = maxDimension.toDouble() / longer
        return (width * scale).roundToInt().coerceIn(1, maxDimension) to (height * scale).roundToInt().coerceIn(1, maxDimension)
    }
}
