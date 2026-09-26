package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 이미지 2D 팔·몸통 피처 (spec §62c, 설계 §22, 연구 `docs/CURL_RULES_RESEARCH.md` B1·B4) — 덤벨 컬의 반동·팔꿈치 이탈·벌림·가동 범위용.
 *
 * 뷰와 무관하게 내는 것(B4, 정면 C 에서 검증):
 *  - `elbow_lat2d_L/R/max` : 팔꿈치가 같은 쪽 어깨보다 **바깥**(사람 기준)으로 나간 가로 거리 ÷ 어깨 x 간격. 정상 수축 p95 0.29, 옆 벌림 0.34~0.46.
 *  - `elbow_rise2d_L/R/max`: (어깨 y − 팔꿈치 y) ÷ 몸통 길이 — 팔꿈치가 어깨 쪽으로 올라온 정도. 반동(어깨로 들어 올림)·으쓱·앞 내밀기가 모두 여기 나타난다.
 *  - `wrist_h2d_L/R/max`   : (어깨 y − 손목 y) ÷ 몸통 길이 — 손목 높이. 반동이면 손목이 어깨 위(+0.15 이상)로 넘고, 가동 범위는 이 값의 창 진폭으로 잰다
 *                            (정면에서 월드 팔꿈치각 진폭보다 진실에 가깝다: AIHub GT 진폭 ρ 0.62 vs 월드 0.28).
 * 사선 뷰(B/D)에서만 내는 것(부호가 요에 달림, B1):
 *  - `elbow_fwd2d_L/R/mean`: 골반→어깨 선에서 팔꿈치까지의 **앞쪽** 거리 ÷ 몸통. 앞 = +. '팔꿈치 위치 고정'(내밀기) 위반 AUC 0.96.
 *  - `torso_tilt2d`        : 어깨 중점이 골반 중점보다 앞으로 기운 비. 앞 숙임 = +.
 *  - `elbow_lat2d_near`    : 카메라 쪽 팔(B = 오른팔, D = 왼팔) 하나의 바깥 가로 — `elbow_lat2d_{그 팔}` 과 같은 값(§62c 후속 7).
 * 사선과 옆(|yaw| 16.4~81.7°)에서 내는 것:
 *  - `elbow_fwd2d_near`    : 카메라 쪽 팔의 앞 성분 — **가까운 쪽 몸통 선**(가까운 골반→가까운 어깨)에서 팔꿈치까지의 앞쪽 거리 ÷ 몸통(§62c 후속 7).
 *    먼 팔꿈치는 몸통 뒤에 가려 추정이 몸 쪽에 머물고(11:54 세트: 가시성 0.55~0.85, 벌려도 가로가 그대로) 양팔 평균은 그 팔이 안 보이면 없다.
 *    기준선이 가운데 선이면 가까운 어깨가 반 어깨폭만큼 비켜 있는 상수(cos 요 × 반 어깨폭)가 들어가, 세트 안에서 각도가 바뀌면(21° → 40°) 정상 반복이 앞으로 읽힌다.
 *    사선에서 카메라 쪽 팔꿈치의 화면 가로 = cos 요 × 옆 벌림 − sin 요 × 앞 이동 — 숫자 하나에 미지수 둘이라 앞과 '몸에서 떨어짐(옆 또는 뒤)' 까지만 가른다
 *    (`docs/CURL_OBLIQUE_ELBOW_RESEARCH.md`).
 *
 * 왜 2D 인가: 월드 좌표의 앞 성분은 AUC 0.90 에 정상 세트 오탐 16 %(B1), 월드 팔꿈치각의 수축 최소는 정답과 상관 0.04(B2), 월드 몸통 기울기는 정면에서
 * 45~110° 로 튄다(MM-Fit 재생). 정면에서는 팔꿈치의 가로·세로 위치와 손목 높이가 가장 곧은 단서였다(B4: 폰 정답 세트 벌림 4/4·반동 3/3·짧은 회 2/2, 정상 오탐 0).
 *
 * 앞/뒤 부호는 촬영 방위(요)로: `ViewEstimator` 규약에서 yaw > 0 은 오른어깨가 카메라에 가깝다(B). 분석 프레임은 미러가 아니라 정면을 보는 사람의 오른쪽이
 * 화면 왼쪽인데, 오른어깨가 카메라 쪽으로 오도록 몸을 돌리면 몸의 앞 방향은 화면 **오른쪽(+x)** 을 향한다. 왼어깨가 가까우면(D) 반대. 사선 띠(|yaw| 16.4~46.2°) 밖은
 * 부호를 정하지 않는다. 사람 좌우(바깥 방향)는 골반 x 순서로 정하므로 전면 카메라 미러 여부와 무관하다.
 *
 * 앱(`PostureAnalyzer`)과 재생기(`Replay.frameFeatures`)가 같은 함수를 부른다 — 파리티. [aspect] = 폭÷높이(세로 480×640 = 0.75).
 */
object Arm2d {
    const val ELBOW_FWD_L = "elbow_fwd2d_L"
    const val ELBOW_FWD_R = "elbow_fwd2d_R"
    const val ELBOW_FWD_MEAN = "elbow_fwd2d_mean"
    const val TORSO_TILT = "torso_tilt2d"
    const val ELBOW_LAT_L = "elbow_lat2d_L"
    const val ELBOW_LAT_R = "elbow_lat2d_R"
    const val ELBOW_LAT_MAX = "elbow_lat2d_max"
    const val ELBOW_RISE_L = "elbow_rise2d_L"
    const val ELBOW_RISE_R = "elbow_rise2d_R"
    const val ELBOW_RISE_MAX = "elbow_rise2d_max"
    const val WRIST_H_L = "wrist_h2d_L"
    const val WRIST_H_R = "wrist_h2d_R"
    const val WRIST_H_MAX = "wrist_h2d_max"
    const val ELBOW_FWD_NEAR = "elbow_fwd2d_near"
    const val ELBOW_LAT_NEAR = "elbow_lat2d_near"

    private const val L_SHOULDER = 11
    private const val R_SHOULDER = 12
    private const val L_ELBOW = 13
    private const val R_ELBOW = 14
    private const val L_WRIST = 15
    private const val R_WRIST = 16
    private const val L_HIP = 23
    private const val R_HIP = 24
    /**
     * 가까운 팔 피처를 만드는 프레임 요 띠(§62c 후속 10, 임의값) — 반복 뷰 게이팅이 세트 잠금 뷰로 바뀌어, 사선으로 잠긴 세트에서 살짝 정면(10~16°)이나
     * 옆 초입(46~60°)으로 흔들린 프레임도 재야 한다. 정면 띠(16.4°) 안쪽에서는 앞 성분이 약하고, 60° 밖에서는 어깨 가로폭(cos 요 ≤ 0.5)이 작아 가로 비가 흔들린다.
     */
    const val NEAR_MIN_DEG = 10f
    const val NEAR_LAT_MAX_DEG = 60f
    /** 몸통 길이 하한(높이 정규화) — 이보다 짧으면 겹침·잘림. */
    private const val MIN_TORSO = 0.08f
    /** 어깨 x 간격 하한(높이 단위) — 옆모습·겹침이면 가로 비를 만들지 않는다. */
    private const val MIN_SHOULDER_X = 0.03f

    /** 프레임 피처의 `view_cos`/`view_sin` 에서 요(°). 둘 중 하나라도 없으면 null. */
    fun yawOf(features: Map<String, Float>): Float? {
        val c = features[ViewEstimator.FEAT_COS] ?: return null
        val s = features[ViewEstimator.FEAT_SIN] ?: return null
        if (!c.isFinite() || !s.isFinite()) return null
        return Math.toDegrees(atan2(s.toDouble(), c.toDouble())).toFloat()
    }

    /** 몸의 앞 방향이 화면 x 에서 갖는 부호 — 사선(B/D)에서만 정의. */
    fun forwardSign(yawDeg: Float?): Float? {
        if (yawDeg == null || !yawDeg.isFinite()) return null
        val a = abs(yawDeg)
        if (a <= ViewEstimator.FRONT_MAX_DEG || a > ViewEstimator.OBLIQUE_MAX_DEG) return null
        return if (yawDeg * ViewEstimator.B_SIGN > 0f) 1f else -1f
    }

    /**
     * 가까운 팔 피처의 앞 방향 화면 부호 — 사선(B/D)과 옆(SIDE_B/SIDE_D, |yaw| 16.4~81.7°)에서 정의. +1 = B 쪽(오른어깨가 카메라에 가까움 = 가까운 팔은 오른팔).
     */
    fun nearSign(yawDeg: Float?): Float? {
        if (yawDeg == null || !yawDeg.isFinite()) return null
        val a = abs(yawDeg)
        if (a <= NEAR_MIN_DEG || a >= ViewEstimator.REAR_MIN_DEG) return null
        return if (yawDeg * ViewEstimator.B_SIGN > 0f) 1f else -1f
    }

    fun features(xy: FloatArray, vis: FloatArray, minVisibility: Float, aspect: Float = Stance2d.DEFAULT_ASPECT, yawDeg: Float?): Map<String, Float> {
        if (xy.size < 66 || vis.size < 33) return emptyMap()
        fun ok(vararg idx: Int): Boolean { for (i in idx) if (!(vis[i] >= minVisibility) || !xy[i * 2].isFinite() || !xy[i * 2 + 1].isFinite()) return false; return true }
        if (!ok(L_SHOULDER, R_SHOULDER, L_HIP, R_HIP)) return emptyMap()
        fun x(i: Int) = xy[i * 2] * aspect        // 폭 정규화 → 높이 단위
        fun y(i: Int) = xy[i * 2 + 1]
        val shX = (x(L_SHOULDER) + x(R_SHOULDER)) / 2f; val shY = (y(L_SHOULDER) + y(R_SHOULDER)) / 2f
        val hipX = (x(L_HIP) + x(R_HIP)) / 2f; val hipY = (y(L_HIP) + y(R_HIP)) / 2f
        val dx = shX - hipX; val dy = shY - hipY
        val torso = sqrt(dx * dx + dy * dy)
        if (torso < MIN_TORSO || abs(dy) < 1e-4f) return emptyMap()
        val out = HashMap<String, Float>(14)
        // 사람 왼쪽이 화면 +x 인가(정면 비미러 = 그렇다). 골반이 겹치면 가로 방향을 모른다
        val latDir = x(L_HIP) - x(R_HIP)
        val shGap = abs(x(L_SHOULDER) - x(R_SHOULDER))
        val sides = listOf(Triple(L_SHOULDER, L_ELBOW, L_WRIST), Triple(R_SHOULDER, R_ELBOW, R_WRIST))
        var latMax = Float.NEGATIVE_INFINITY; var riseMax = Float.NEGATIVE_INFINITY; var wristMax = Float.NEGATIVE_INFINITY
        var nLat = 0; var nRise = 0; var nWrist = 0
        for ((k, s) in sides.withIndex()) {
            val (sh, el, wr) = s
            val tag = if (k == 0) "L" else "R"
            if (ok(el)) {
                if (abs(latDir) >= 1e-4f && shGap >= MIN_SHOULDER_X) {
                    val outward = (if (k == 0) 1f else -1f) * (if (latDir > 0) 1f else -1f)
                    val lat = outward * (x(el) - x(sh)) / shGap
                    out["elbow_lat2d_$tag"] = lat; if (lat > latMax) latMax = lat; nLat++
                }
                val rise = (y(sh) - y(el)) / torso
                out["elbow_rise2d_$tag"] = rise; if (rise > riseMax) riseMax = rise; nRise++
            }
            if (ok(wr)) {
                val wh = (y(sh) - y(wr)) / torso
                out["wrist_h2d_$tag"] = wh; if (wh > wristMax) wristMax = wh; nWrist++
            }
        }
        if (nLat > 0) out[ELBOW_LAT_MAX] = latMax
        if (nRise > 0) out[ELBOW_RISE_MAX] = riseMax
        if (nWrist > 0) out[WRIST_H_MAX] = wristMax
        // 사선 전용(앞/뒤 부호)
        val sign = forwardSign(yawDeg)
        if (sign != null) {
            out[TORSO_TILT] = sign * dx / torso
            var sum = 0f; var n = 0
            for ((key, el) in listOf(ELBOW_FWD_L to L_ELBOW, ELBOW_FWD_R to R_ELBOW)) {
                if (!ok(el)) continue
                val lineX = hipX + (y(el) - hipY) / dy * dx   // 팔꿈치 높이에서의 몸통 선 x
                val v = sign * (x(el) - lineX) / torso
                out[key] = v; sum += v; n++
            }
            if (n == 2) out[ELBOW_FWD_MEAN] = sum / 2f
        }
        // 가까운 팔(§62c 후속 7) — 카메라 쪽 팔 하나. 앞 성분은 사선·옆, 바깥 가로는 사선에서만(옆은 어깨 가로폭이 작아 앞 성분이 가로를 덮는다)
        val nSign = nearSign(yawDeg)
        if (nSign != null) {
            val nearR = nSign > 0f
            val nsh = if (nearR) R_SHOULDER else L_SHOULDER
            val nel = if (nearR) R_ELBOW else L_ELBOW
            val nhip = if (nearR) R_HIP else L_HIP
            if (ok(nel)) {
                val ndy = y(nsh) - y(nhip)
                if (abs(ndy) >= 1e-4f) {
                    val lineX = x(nhip) + (y(nel) - y(nhip)) / ndy * (x(nsh) - x(nhip))   // 팔꿈치 높이에서의 가까운 쪽 몸통 선 x
                    out[ELBOW_FWD_NEAR] = nSign * (x(nel) - lineX) / torso
                }
                if (abs(yawDeg!!) <= NEAR_LAT_MAX_DEG && abs(latDir) >= 1e-4f && shGap >= MIN_SHOULDER_X) {
                    val outward = (if (nearR) -1f else 1f) * (if (latDir > 0) 1f else -1f)
                    out[ELBOW_LAT_NEAR] = outward * (x(nel) - x(nsh)) / shGap
                }
            }
        }
        return out
    }
}
