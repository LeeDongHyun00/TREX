package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.atan2

/**
 * 이미지 2D 발 피처 (spec §62a 후속 3·§62b, 설계 §21.9·§21.12).
 *
 *  - `stance_2d`      = |발목 x 간격| ÷ |어깨 x 간격| — 프레임별 어깨로 나눈 비(구 발 너비 검사, 이제 로그·참고용).
 *  - `ankle_sep_2d`   = |발목 x 간격| (정규화 폭). 발 너비 검사의 본 피처 — 어깨로 나누지 않는다.
 *  - `shoulder_sep_2d`= |어깨 x 간격| (정규화 폭). 세트 시작 중앙값 하나만 절대 척도로 쓴다.
 *  - `toe2d_L/R`      = 이미지 발목→발끝(foot_index) 각(°), 사람 기준 **바깥 +**. `toe2d_maxside` = 더 벌어진 쪽.
 *
 * 왜 2D 인가: 검증 세트 좌표에서 MediaPipe **월드** 발목 간격은 발끝을 돌리면 같이 움직였고(안쪽 −18 %, 11:37 +80 %), 월드 발끝 각은
 * z 가 관측이 아니라 GHUM 추정치라 실제 회전을 2D 의 6할로만 반영했다(A7a: 기울기 0.57 vs 0.93). **이미지** 발목 x 간격은 발끝 회전에
 * ±5 % 안이고, 이미지 발끝 각은 정강이·발 너비 의존이 사람 주석과 같다(기하만). 어깨를 프레임마다 나누던 것이 오늘(2026-09-25 16:16)
 * 오탐의 원인이었다 — 어깨 x 간격은 서 있는 동안에도 변동계수 0.17, 한 프레임 26 px(정상 90) 로 ×3.21.
 *
 * 앱(`PostureAnalyzer`)과 재생기(`Replay.frameFeatures`)가 같은 함수를 부른다 — 파리티. [aspect] = 이미지 폭÷높이(세로 480×640 = 0.75) —
 * 정규화 좌표의 dx·dy 를 픽셀 비율로 되돌려 각을 낸다.
 */
object Stance2d {
    const val FEATURE = "stance_2d"
    const val ANKLE_SEP = "ankle_sep_2d"
    const val SHOULDER_SEP = "shoulder_sep_2d"
    const val TOE_L = "toe2d_L"
    const val TOE_R = "toe2d_R"
    const val TOE_MAXSIDE = "toe2d_maxside"
    const val DEFAULT_ASPECT = 480f / 640f

    private const val L_SHOULDER = 11
    private const val R_SHOULDER = 12
    private const val L_HIP = 23
    private const val R_HIP = 24
    private const val L_ANKLE = 27
    private const val R_ANKLE = 28
    private const val L_TOE = 31
    private const val R_TOE = 32
    /** 어깨 x 간격 하한(정규화 폭) — 이보다 좁으면 옆모습·겹침이라 비율을 만들지 않는다. */
    private const val MIN_SHOULDER_X = 0.04f
    /** 발목→발끝 벡터 길이 하한(정규화, 픽셀 비율 보정 후) — 발이 겹치거나 잘리면 각을 만들지 않는다. */
    private const val MIN_FOOT_LEN = 0.01f

    /** 구 비율 — 네 관절이 [minVisibility] 이상이고 어깨가 겹치지 않으면 비율, 아니면 null. */
    fun of(xy: FloatArray, vis: FloatArray, minVisibility: Float): Float? {
        val a = ankleSep(xy, vis, minVisibility) ?: return null
        val s = shoulderSep(xy, vis, minVisibility) ?: return null
        return a / s
    }

    fun ankleSep(xy: FloatArray, vis: FloatArray, minVisibility: Float): Float? {
        if (!ok(xy, vis, minVisibility, L_ANKLE, R_ANKLE)) return null
        return abs(xy[L_ANKLE * 2] - xy[R_ANKLE * 2])
    }

    fun shoulderSep(xy: FloatArray, vis: FloatArray, minVisibility: Float): Float? {
        if (!ok(xy, vis, minVisibility, L_SHOULDER, R_SHOULDER)) return null
        val sh = abs(xy[L_SHOULDER * 2] - xy[R_SHOULDER * 2])
        return if (sh < MIN_SHOULDER_X) null else sh
    }

    /**
     * 이미지 발목→발끝 각(°), 바깥 +. 사람의 왼쪽이 화면 어느 쪽인지는 골반 x 순서로 정한다(정면을 보면 왼골반이 화면 오른쪽 —
     * 분석 프레임은 미러가 아니다). 골반이 겹쳐 방향을 모르면 null.
     */
    fun toeAngle(xy: FloatArray, vis: FloatArray, minVisibility: Float, side: Char, aspect: Float = DEFAULT_ASPECT): Float? {
        val (ankle, toe, sg) = if (side == 'L') Triple(L_ANKLE, L_TOE, 1f) else Triple(R_ANKLE, R_TOE, -1f)
        if (!ok(xy, vis, minVisibility, ankle, toe, L_HIP, R_HIP)) return null
        val lat = xy[L_HIP * 2] - xy[R_HIP * 2]
        if (abs(lat) < 1e-4f) return null
        val dx = (xy[toe * 2] - xy[ankle * 2]) * aspect        // 폭 정규화 → 높이 단위로
        val dy = xy[toe * 2 + 1] - xy[ankle * 2 + 1]
        if (dx * dx + dy * dy < MIN_FOOT_LEN * MIN_FOOT_LEN) return null
        return Math.toDegrees(atan2((sg * (if (lat > 0) 1f else -1f) * dx).toDouble(), abs(dy).toDouble())).toFloat()
    }

    /** 프레임 피처 사전에 더할 항목 — 계산 불가한 항목은 키가 없다(= 유보). */
    fun features(xy: FloatArray, vis: FloatArray, minVisibility: Float, aspect: Float = DEFAULT_ASPECT): Map<String, Float> {
        val out = HashMap<String, Float>(6)
        val a = ankleSep(xy, vis, minVisibility)
        val s = shoulderSep(xy, vis, minVisibility)
        if (a != null) out[ANKLE_SEP] = a
        if (s != null) out[SHOULDER_SEP] = s
        if (a != null && s != null) out[FEATURE] = a / s
        val tl = toeAngle(xy, vis, minVisibility, 'L', aspect)
        val tr = toeAngle(xy, vis, minVisibility, 'R', aspect)
        if (tl != null) out[TOE_L] = tl
        if (tr != null) out[TOE_R] = tr
        when {
            tl != null && tr != null -> out[TOE_MAXSIDE] = maxOf(tl, tr)
            tl != null -> out[TOE_MAXSIDE] = tl
            tr != null -> out[TOE_MAXSIDE] = tr
        }
        return out
    }

    private fun ok(xy: FloatArray, vis: FloatArray, minVisibility: Float, vararg idx: Int): Boolean {
        if (xy.size < 66 || vis.size < 33) return false
        for (i in idx) if (!(vis[i] >= minVisibility) || !xy[i * 2].isFinite() || !xy[i * 2 + 1].isFinite()) return false
        return true
    }
}
