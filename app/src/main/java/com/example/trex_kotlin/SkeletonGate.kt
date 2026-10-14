package com.example.trex_kotlin

import com.example.trex_kotlin.posture.MP_LANDMARK_COUNT
import kotlin.math.hypot

/**
 * 뼈대 관절 게이트(§101c — 사용자 보고 2026-10-09 "런지 오른 무릎을 셀 때 골반 아래 관절들이 깨진다"). 화면에 그릴 관절과 그 좌표를 정한다 — 판정·카운터·로그와 무관한 **표시 전용**이다.
 *
 * 원인(10-09 15:52 런지, 사선 B, 오른발 앞 걸음): 먼(왼) 무릎의 가시성이 0.45~0.73 으로 떨어지고 그 좌표가 프레임마다 튀었다(x 0.42 → 0.74 → 0.51, 가까운 무릎은 0.98 로 매끈).
 * 종전 게이트(문턱 0.5 ± 0.10)는 그 무릎을 실선/점선으로 오가며 **튀는 자리에** 그렸고, 보간이 그 사이를 선으로 쓸었다. MediaPipe 는 가려진 관절의 자리를 지어내므로
 * 가려진 다리를 '정확히' 그릴 방법은 없다 — 할 수 있는 것은 **믿을 수 없는 관절을 그리지 않고, 마지막으로 믿은 자리에 두는 것**이다(원칙 #5 의 화면판).
 *
 *  - 다리 관절(무릎·발목·뒤꿈치·발끝)은 더 높은 문턱 [LEG_CUT](이력 ±0.05): 0.75 아래로 내려가면 숨기고 0.85 위로 올라와야 다시 그린다. 정면 스쿼트의 다리는 0.95 이상이라 영향이 없다.
 *  - 순간 이동 보류: 그리고 있던 관절이 가시성 [TRUST_VIS] 아래인 채 한 샘플에 [JUMP](높이 단위) 넘게 움직이면 [JUMP_FRAMES] 샘플 동안 숨기고 좌표는 마지막 자리에 둔다 —
 *    같은 자리에 머물면 받아들인다(실제 빠른 움직임은 가시성이 높거나 다음 샘플이 이어진다).
 *  - 숨긴 관절의 [target] 은 마지막으로 믿은 자리(없으면 원시 좌표) — 점선 끝이 지어낸 자리를 따라 춤추지 않게.
 */
internal class JointGate(private val cut: Float) {
    /** 실선으로 그릴 관절(가시성 이력 ∧ 보류 아님). */
    val shown = BooleanArray(MP_LANDMARK_COUNT)
    /** 보간·그리기의 목표 좌표(정규화) — 보이는 관절은 원시 좌표, 숨긴 관절은 마지막으로 믿은 자리. */
    val target = FloatArray(MP_LANDMARK_COUNT * 2) { Float.NaN }
    private val state = BooleanArray(MP_LANDMARK_COUNT)
    private val last = FloatArray(MP_LANDMARK_COUNT * 2) { Float.NaN }
    private val suspect = IntArray(MP_LANDMARK_COUNT)

    /** 새 샘플. [aspect] = 이미지 폭 ÷ 높이(x 를 높이 단위로 맞춘다). */
    fun update(xy: FloatArray, vis: FloatArray, aspect: Float) {
        for (i in 0 until MP_LANDMARK_COUNT) {
            val v = vis.getOrNull(i) ?: 0f
            val leg = i in LEG_FIRST..LEG_LAST
            val hi = if (leg) maxOf(cut + 0.10f, LEG_CUT + 0.05f) else cut + 0.10f
            val lo = if (leg) maxOf(cut - 0.10f, LEG_CUT - 0.05f) else cut - 0.10f
            if (v >= hi) state[i] = true else if (v < lo) state[i] = false
            val x = xy.getOrNull(i * 2) ?: Float.NaN; val y = xy.getOrNull(i * 2 + 1) ?: Float.NaN
            val lx = last[i * 2]; val ly = last[i * 2 + 1]
            if (!x.isFinite() || !y.isFinite()) { shown[i] = false; target[i * 2] = lx; target[i * 2 + 1] = ly; continue }
            val jumped = state[i] && v < TRUST_VIS && lx.isFinite() && hypot((x - lx) * aspect, y - ly) > JUMP
            if (jumped && suspect[i] < JUMP_FRAMES) {
                suspect[i]++
                shown[i] = false; target[i * 2] = lx; target[i * 2 + 1] = ly
                continue
            }
            suspect[i] = 0
            if (state[i]) {
                shown[i] = true; last[i * 2] = x; last[i * 2 + 1] = y; target[i * 2] = x; target[i * 2 + 1] = y
            } else {
                shown[i] = false
                if (lx.isFinite()) { target[i * 2] = lx; target[i * 2 + 1] = ly } else { target[i * 2] = x; target[i * 2 + 1] = y }
            }
        }
    }

    companion object {
        const val LEG_FIRST = 25
        const val LEG_LAST = 32
        /** 다리 관절의 가시성 문턱(이력 ±0.05) — 10-09 런지 먼 무릎 0.45~0.73·먼 발목 0.75~0.88 은 숨기고, 가까운 다리 0.93~0.99·정면 다리 ≥ 0.95 는 그린다. */
        const val LEG_CUT = 0.80f
        /** 이 가시성 이상이면 순간 이동도 믿는다. */
        const val TRUST_VIS = 0.90f
        /** 한 샘플(85~400 ms)의 이동 한도(높이 단위) — 0.12 ≈ 640 px 세로의 77 px. */
        const val JUMP = 0.12f
        /** 보류 샘플 수 — 그 뒤 같은 자리에 머물면 받아들인다. */
        const val JUMP_FRAMES = 2
    }
}
