package com.example.trex_kotlin

import com.example.trex_kotlin.posture.PoseSample

/** 보이는 몸의 경계 상자(정규화 이미지 좌표). 머리는 코 하나로 대신한다 — 얼굴 점은 그리지 않는다. */
internal data class BodyBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}

/** 몸통·팔다리 관절(11~32)과 코(0)만 — 가시성 [cut] 이상. 셋 미만이면 null(몸이 아니다). */
internal fun PoseSample.bodyBox(cut: Float): BodyBox? {
    if (!detected || imageWidth <= 0 || features.isEmpty()) return null
    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
    var n = 0
    for (i in 0 until visibility.size) {
        if (i in 1..10) continue
        if (visibility[i] < cut) continue
        val x = normalizedXy[i * 2]; val y = normalizedXy[i * 2 + 1]
        if (!x.isFinite() || !y.isFinite()) continue
        if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        n++
    }
    return if (n < 3) null else BodyBox(l, t, r, b)
}

/**
 * 무대 배율 잠금(docs/LIVE_SCREEN_REDESIGN.md §2.2) — "뼈대를 크게" 를 프레임마다 몸에 맞추면 스쿼트 하강에서 키가 줄어 뼈대가 **커진다**.
 * 그래서 세트 시작 뒤 몸이 [settleMs] 동안 이어 보이면 그때의 경계 상자를 한 번 잠그고, 세트 중엔 바꾸지 않는다.
 * 몸을 [lostMs] 동안 잃으면 잠금을 풀고([lost] = 카메라 영상 복귀) 돌아오면 다시 잠근다. 순수 상태기 — 그리기는 [SkeletonStage] 가 한다.
 */
internal class StageFitController(private val settleMs: Long = 1000L, private val lostMs: Long = 1500L) {
    var locked: BodyBox? = null
        private set
    /** 몸을 [lostMs] 이상 못 봤다 — 영상을 다시 보여 자리를 잡게 한다. */
    var lost: Boolean = false
        private set
    private var stableSince: Long? = null
    private var lostSince: Long? = null

    fun reset() { locked = null; lost = false; stableSince = null; lostSince = null }

    fun update(now: Long, box: BodyBox?): BodyBox? {
        if (box == null) {
            stableSince = null
            if (lostSince == null) lostSince = now
            if (now - lostSince!! >= lostMs) { locked = null; lost = true }
            return locked
        }
        lostSince = null
        lost = false
        if (locked != null) return locked
        if (stableSince == null) stableSince = now
        if (now - stableSince!! >= settleMs) locked = box
        return locked
    }
}
