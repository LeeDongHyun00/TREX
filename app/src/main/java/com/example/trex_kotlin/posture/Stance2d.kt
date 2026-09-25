package com.example.trex_kotlin.posture

import kotlin.math.abs

/**
 * 이미지 2D 발 너비 (spec §62a 후속 3, 설계 §21.9) — `stance_2d` = |발목 x 간격| ÷ |어깨 x 간격| (정규화 이미지 좌표).
 *
 * 왜 3D 가 아니라 2D 인가: 검증 모드 세트(12:19)의 좌표로 비교했더니 MediaPipe **월드** 발목 간격은 발끝을 돌리면 같이 움직였다
 * (발끝 안쪽 −18 %, 11:37 세트에서는 +80 %) — 발을 디딘 자리는 그대로인데. **이미지** 발목 x 간격은 발끝 안쪽·바깥에서 ±5 % 안에 머물고
 * 넓게 서면 ×2.0 으로 뛴다. 어깨 x 간격으로 나눠 거리·화면 크기를 상쇄한다(같은 높이는 아니라 원근이 완전히 상쇄되진 않는다 — 폰이 낮으면
 * 발목이 어깨보다 가까워 비율이 조금 커진다. 시작 자세 대비 상대 검사가 그 상수를 흡수한다).
 *
 * 앱(`PostureAnalyzer`)과 재생기(`Replay.frameFeatures`)가 같은 함수를 부른다 — 파리티.
 */
object Stance2d {
    const val FEATURE = "stance_2d"
    private const val L_SHOULDER = 11
    private const val R_SHOULDER = 12
    private const val L_ANKLE = 27
    private const val R_ANKLE = 28
    /** 어깨 x 간격 하한(정규화 폭) — 이보다 좁으면 옆모습·겹침이라 비율을 만들지 않는다. */
    private const val MIN_SHOULDER_X = 0.04f

    /**
     * @param xy 정규화 이미지 좌표 33×2 (x, y 순), @param vis 33 가시성(min(visibility, presence)).
     * @return 네 관절이 [minVisibility] 이상이고 어깨가 겹치지 않으면 비율, 아니면 null.
     */
    fun of(xy: FloatArray, vis: FloatArray, minVisibility: Float): Float? {
        if (xy.size < 66 || vis.size < 33) return null
        for (i in intArrayOf(L_SHOULDER, R_SHOULDER, L_ANKLE, R_ANKLE)) {
            if (!(vis[i] >= minVisibility) || !xy[i * 2].isFinite()) return null
        }
        val sh = abs(xy[L_SHOULDER * 2] - xy[R_SHOULDER * 2])
        if (sh < MIN_SHOULDER_X) return null
        return abs(xy[L_ANKLE * 2] - xy[R_ANKLE * 2]) / sh
    }

    /** 프레임 피처 사전에 더할 항목 — 계산 불가면 빈 맵(키 없음 = 유보). */
    fun features(xy: FloatArray, vis: FloatArray, minVisibility: Float): Map<String, Float> =
        of(xy, vis, minVisibility)?.let { mapOf(FEATURE to it) } ?: emptyMap()
}
