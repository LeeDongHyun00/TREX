package com.example.trex_kotlin

import com.example.trex_kotlin.posture.PoseSample
import kotlin.math.hypot

/** 제어판을 위한 거리 변화 추정이다. 자세 정답이나 실제 거리를 판정하지 않는다. */
internal fun PoseSample.panelBodyScale(): Float? {
    if (features.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
    val aspect = imageWidth.toFloat() / imageHeight
    return listOf(11 to 23, 12 to 24).mapNotNull { (a, b) ->
        if (visibility.getOrElse(a) { 0f } < .5f || visibility.getOrElse(b) { 0f } < .5f) null
        else {
            val dx = (normalizedXy[a * 2] - normalizedXy[b * 2]) * aspect
            val dy = normalizedXy[a * 2 + 1] - normalizedXy[b * 2 + 1]
            hypot(dx, dy).takeIf { it.isFinite() && it > .035f }
        }
    }.maxOrNull()
}

/** 짧은 가림에는 반응하지 않고 직접 조작 뒤에는 충분히 열린 상태를 유지한다. */
internal class LivePanelController {
    var visible = true
        private set
    private var reference: Float? = null
    private var stableAt: Long? = null
    private var absentAt: Long? = null
    private var closeAt: Long? = null
    private var holdUntil = 0L
    private var awaitingInitialFraming = false

    fun reveal(now: Long) { visible = true; holdUntil = now + 8000; stableAt = null; awaitingInitialFraming = false }
    fun collapse(now: Long) { visible = false; holdUntil = now + 1000; absentAt = null; closeAt = null }

    /** 준비 완료는 즉시 몰입한다. 건너뛰기는 첫 전신 관측 1초만 기다린다. */
    fun beginSession(now: Long, skipped: Boolean) {
        reference = null; stableAt = null; absentAt = null; closeAt = null
        holdUntil = now
        awaitingInitialFraming = skipped
        visible = skipped
        if (!skipped) collapse(now)
    }

    fun update(now: Long, scale: Float?, forceVisible: Boolean, fullBody: Boolean = true): Boolean {
        if (forceVisible) { reveal(now); return visible }
        if (awaitingInitialFraming) {
            if (!fullBody || scale == null) stableAt = null
            else {
                if (stableAt == null) stableAt = now
                if (now - stableAt!! >= 1000) {
                    reference = scale; awaitingInitialFraming = false; collapse(now)
                }
            }
            return visible
        }
        if (scale == null) {
            stableAt = null; closeAt = null
            if (absentAt == null) absentAt = now
            if (now - absentAt!! >= 1500 && now >= holdUntil) visible = true
            return visible
        }
        absentAt = null
        if (reference == null) reference = scale
        val near = scale >= reference!! * 1.65f || scale >= .60f
        if (near) {
            stableAt = null
            if (closeAt == null) closeAt = now
            if (now - closeAt!! >= 700) visible = true
        } else {
            closeAt = null
            if (!fullBody) { stableAt = null; return visible }
            if (stableAt == null) stableAt = now
            if (now - stableAt!! >= 3000 && now >= holdUntil) visible = false
        }
        return visible
    }
}
