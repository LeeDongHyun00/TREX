package com.example.trex_kotlin

/** 창 좌표를 콘텐츠 좌표로 옮긴 사각형. 픽셀과 dp 중 호출부가 선택한 동일 단위를 쓴다. */
data class ContentRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = (right - left).coerceAtLeast(0)
    val height get() = (bottom - top).coerceAtLeast(0)
}

data class SessionRegions(val camera: ContentRect, val controls: ContentRect)

/** 패널의 실제 이동 경계에 맞춰 PiP가 확장된다. 전환 중에도 영상과 패널은 겹치지 않는다. */
internal fun pipCameraRegion(width: Int, height: Int, base: SessionRegions, fold: ContentRect?,
    density: Float, panelProgress: Float, headerHeight: Int): ContentRect {
    val p = panelProgress.coerceIn(0f, 1f)
    if (splitAroundFold(width, height, fold) != null) return base.camera
    val panelTop = base.controls.top + ((1f - p) * base.controls.height).toInt()
    // 가로 창은 제어판을 오른쪽으로 보내며 같은 속도로 영상 폭을 넓힌다.
    val sidePanel = base.controls.left > 0
    val right = if (sidePanel) base.controls.left + ((1f - p) * base.controls.width).toInt() else width
    val bottom = if (sidePanel) height else panelTop
    // 횟수는 아래 표시줄로 옮긴다. 좌우/상단 여백 없이 준비 화면처럼 영상을 크게 쓴다.
    val dock = (headerHeight * p).toInt().coerceAtMost((bottom - 64 * density).toInt().coerceAtLeast(0))
    return ContentRect(0, 0, right.coerceAtLeast(1), (bottom - dock).coerceAtLeast(1))
}

/** 실제로 화면을 가르거나 가리는 힌지만 전달한다. 힌지가 없는 일반 폰에도 같은 정책을 쓴다. */
fun splitAroundFold(width: Int, height: Int, fold: ContentRect?): Pair<ContentRect, ContentRect>? {
    if (fold == null || width <= 0 || height <= 0) return null
    val x1 = fold.left.coerceIn(0, width)
    val x2 = fold.right.coerceIn(0, width)
    val y1 = fold.top.coerceIn(0, height)
    val y2 = fold.bottom.coerceIn(0, height)
    return when {
        fold.width >= width && y1 > 0 && y2 < height ->
            ContentRect(0, 0, width, y1) to ContentRect(0, y2, width, height)
        fold.height >= height && x1 > 0 && x2 < width ->
            ContentRect(0, 0, x1, height) to ContentRect(x2, 0, width, height)
        else -> null
    }
}

fun singleContentRegion(width: Int, height: Int, fold: ContentRect?, maxWidth: Int): ContentRect {
    val halves = splitAroundFold(width, height, fold)
    val region = halves?.let { (a, b) -> if (a.width.toLong() * a.height >= b.width.toLong() * b.height) a else b }
        ?: ContentRect(0, 0, width, height)
    val contentWidth = region.width.coerceAtMost(maxWidth)
    val left = region.left + (region.width - contentWidth) / 2
    return ContentRect(left, region.top, left + contentWidth, region.bottom)
}

/** 카메라는 원본 영상 전체를 표시한다. 낮은 창에서도 조작부와 프리뷰 공간을 확보한다. */
fun sessionRegions(width: Int, height: Int, fold: ContentRect?, density: Float, preparing: Boolean = false): SessionRegions {
    splitAroundFold(width, height, fold)?.let { return SessionRegions(it.first, it.second) }
    val wide = width >= (600 * density) && width > height
    return if (wide || width >= 840 * density) {
        val panel = (width * .38f).toInt().coerceAtMost((360 * density).toInt())
        SessionRegions(ContentRect(0, 0, width - panel, height), ContentRect(width - panel, 0, width, height))
    } else {
        // 준비에서는 방향 시범을 읽을 공간을 먼저 확보한다. 운동 중에는 영상 영역을 넓힌다.
        val panel = (height * if (preparing) .58f else .38f).toInt()
            .coerceAtMost(((if (preparing) 460 else 300) * density).toInt())
        SessionRegions(ContentRect(0, 0, width, height - panel), ContentRect(0, height - panel, width, height))
    }
}
