package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 런지 걸음 기하 피처 (docs/LUNGE_RESEARCH.md §3-0, spec §63, 사용자 결정 2026-09-26). 안드로이드 의존 없음 — replay-jvm engineFiles 에 넣는다.
 * 앱(PostureAnalyzer)과 재생기(Replay.frameFeatures)가 같은 순서·같은 인자로 부른다(Stance2d·Arm2d 와 같은 틀).
 *
 * 3D(PoseFrame 신체 좌표계: 골반선 기준 x=왼쪽, y=up(IMU·자가검증 뒤), z=앞, 가시성 0.5 미만 관절은 없음):
 *  - lunge_fwd_d        (왼 발목 z − 오른 발목 z) ÷ 다리 길이(엉덩이→무릎 + 무릎→발목, 양쪽 평균). + = 왼발이 앞.
 *                       분모를 현(엉덩이→발목, PoseFrame.legLen)으로 두지 않는다 — 바닥에서 앞무릎이 굽으면 현이 줄어 깊을수록 값이 부푼다
 *                       (저장 세트: 현 0.72~1.17 vs 마디 합 0.46~0.84). 스쿼트 0.00~0.21, 무릎 들기 0.24~0.44(→ 발 들림으로 가른다).
 *  - lunge_front_knee   앞다리(|fwd_d| ≥ 0.25 의 부호) 3D 무릎각. knee_L/knee_R 와 같은 식. 로그·리포트용(깊이 판정은 knee_minside).
 *  - lunge_front_shin   앞 정강이(발목→무릎)가 중력 up 에서 **앞으로** 기운 각(°, 신체 시상면). + = 무릎이 발목보다 앞 — '무릎 쏠림'.
 *                       모집단 정상 걸음 바닥(MM-Fit B/D ≤ 46° 요) > 45° 0.6 %, 옆 5.1 %, AIHub GT 0 %. 옆에서 먼 다리가 앞이면 작게 읽힌다(못 잡는 쪽 오차).
 *  - lunge_back_knee_h  (뒷무릎 높이 − 앞발목 높이) ÷ 뒷정강이. 앞발목 기준(뒷발목은 뒤꿈치가 들려 떠 있다). 깊음 −0.14~0.29, 얕음 0.60~0.90.
 *  - lunge_foot_lift    |왼 발목 높이 − 오른 발목 높이| ÷ 정강이 평균. 걸음 바닥 0.08~0.48, 무릎 들기 1.2~1.6.
 * 2D(정규화 이미지, x × aspect 로 높이 단위. 분석 프레임은 미러가 아니다):
 *  - lunge_kh2d         (오른무릎 y − 왼무릎 y) ÷ 2D 몸통. + = 왼무릎이 화면에서 높다(= 왼발 앞). 쪽 판정의 2D 반증 단서.
 *  - lunge_names_ok     얼굴 방향(코 x − 보이는 귀 x)이 어깨 요가 주는 앞 방향과 같은가(1/0). 30° ≤ |요| ≤ 150° 에서만.
 *                       0 이면 MediaPipe 좌우 이름이 그 프레임에서 뒤바뀐 것 — 왼/오 이름으로 셀 수 없다.
 *  - sh_level2d         (오른어깨 y − 왼어깨 y) ÷ 2D 몸통. + = 왼어깨가 화면에서 높다(shoulder_asym 과 같은 부호).
 *                       어깨 가로폭이 아니라 몸통으로 나눈다 — 가로폭은 요에 따라 cos 로 줄어 세트 안에서 척도가 바뀐다.
 */
object Lunge2d {
    const val FWD_D = "lunge_fwd_d"
    const val FRONT_KNEE = "lunge_front_knee"
    const val FRONT_SHIN = "lunge_front_shin"
    const val BACK_KNEE_H = "lunge_back_knee_h"
    const val FOOT_LIFT = "lunge_foot_lift"
    const val KNEE_H2D = "lunge_kh2d"
    const val NAMES_OK = "lunge_names_ok"
    const val SH_LEVEL_2D = "sh_level2d"

    /** 앞다리를 정하는 |fwd_d| 하한(잠정) — 폰 걸음 바닥 0.46~0.84, 스쿼트 0.00~0.21. */
    const val FRONT_MIN = 0.25f
    /** 2D 무릎 높이가 3D 쪽 판정을 뒤집는 반대 부호 여유(몸통 단위). 이보다 약하면 3D 를 믿는다(얕은 걸음은 뒷무릎이 높아 2D 가 약하다). */
    const val KH2D_VETO = 0.10f
    private const val MIN_LEG_CM = 40f          // PoseFrame.legLen 과 같은 하한
    private const val MIN_SHIN_CM = 10f
    private const val MIN_TORSO = 0.08f         // Arm2d 와 같다
    private const val FACING_MIN_DEG = 30f
    private const val FACING_MAX_DEG = 150f
    private const val FACING_MIN_DX = 0.005f

    private const val NOSE = 0; private const val L_EAR = 7; private const val R_EAR = 8
    private const val L_SH = 11; private const val R_SH = 12
    private const val L_HIP = 23; private const val R_HIP = 24
    private const val L_KNEE = 25; private const val R_KNEE = 26
    private const val RAD = 180.0 / Math.PI

    fun features(frame: PoseFrame, xy: FloatArray, vis: FloatArray, minVisibility: Float,
                 aspect: Float = Stance2d.DEFAULT_ASPECT, yawShDeg: Float?): Map<String, Float> {
        val out = HashMap<String, Float>(10)
        val j = frame.joints
        val lH = frame.lHip; val rH = frame.rHip
        val lK = j[Joints.L_KNEE]; val rK = j[Joints.R_KNEE]
        val lA = j[Joints.L_ANKLE]; val rA = j[Joints.R_ANKLE]

        // ---- 3D
        var fwd: Float? = null
        if (lA != null && rA != null) {
            val bl = frame.body(lA); val br = frame.body(rA)
            if (bl != null && br != null) {
                val segs = ArrayList<Float>(2)
                if (lH != null && lK != null) segs += (lH - lK).norm + (lK - lA).norm
                if (rH != null && rK != null) segs += (rH - rK).norm + (rK - rA).norm
                val leg = if (segs.isEmpty()) null else segs.sum() / segs.size
                if (leg != null && leg >= MIN_LEG_CM) { fwd = (bl.z - br.z) / leg; out[FWD_D] = fwd }
                if (lK != null && rK != null) {
                    val shin = ((lK - lA).norm + (rK - rA).norm) / 2f
                    if (shin >= MIN_SHIN_CM) out[FOOT_LIFT] = abs(bl.y - br.y) / shin
                }
            }
        }
        val front: Char? = when {
            fwd == null -> null
            fwd >= FRONT_MIN -> 'L'
            fwd <= -FRONT_MIN -> 'R'
            else -> null
        }
        if (front != null) {
            val fH = if (front == 'L') lH else rH
            val fK = if (front == 'L') lK else rK
            val fA = if (front == 'L') lA else rA
            val bK = if (front == 'L') rK else lK
            val bA = if (front == 'L') rA else lA
            if (fH != null && fK != null && fA != null) angle3(fH, fK, fA)?.let { out[FRONT_KNEE] = it }
            if (fK != null && fA != null) {
                val kb = frame.body(fK); val ab = frame.body(fA)
                if (kb != null && ab != null && (fK - fA).norm >= MIN_SHIN_CM) {
                    // 시상면(위·앞) 성분만 — 옆 벌어짐(x)은 무릎 쏠림이 아니다
                    out[FRONT_SHIN] = (atan2((kb.z - ab.z).toDouble(), (kb.y - ab.y).toDouble()) * RAD).toFloat()
                }
            }
            if (bK != null && bA != null && fA != null) {
                val kb = frame.body(bK); val ab = frame.body(fA); val shinB = (bK - bA).norm
                if (kb != null && ab != null && shinB >= MIN_SHIN_CM) out[BACK_KNEE_H] = (kb.y - ab.y) / shinB
            }
        }

        // ---- 2D
        if (xy.size < 66 || vis.size < 33) return out
        fun ok(vararg idx: Int): Boolean {
            for (i in idx) if (!(vis[i] >= minVisibility) || !xy[i * 2].isFinite() || !xy[i * 2 + 1].isFinite()) return false
            return true
        }
        fun x(i: Int) = xy[i * 2] * aspect
        fun y(i: Int) = xy[i * 2 + 1]
        if (ok(L_SH, R_SH, L_HIP, R_HIP)) {
            val dx = (x(L_SH) + x(R_SH)) / 2f - (x(L_HIP) + x(R_HIP)) / 2f
            val dy = (y(L_SH) + y(R_SH)) / 2f - (y(L_HIP) + y(R_HIP)) / 2f
            val torso = sqrt(dx * dx + dy * dy)
            if (torso >= MIN_TORSO) {
                out[SH_LEVEL_2D] = (y(R_SH) - y(L_SH)) / torso
                if (ok(L_KNEE, R_KNEE)) out[KNEE_H2D] = (y(R_KNEE) - y(L_KNEE)) / torso
            }
        }
        // 얼굴이 향하는 화면 방향 = 어깨 요가 주는 몸 앞 방향인가 — 어깨 요 > 0(B 쪽) 이면 몸 앞이 화면 +x (Arm2d 규약)
        val yaw = yawShDeg?.takeIf { it.isFinite() }
        if (yaw != null && abs(yaw) in FACING_MIN_DEG..FACING_MAX_DEG && ok(NOSE)) {
            val fSign = if (yaw * ViewEstimator.B_SIGN > 0f) 1f else -1f
            val ears = listOf(L_EAR, R_EAR).filter { ok(it) }
            if (ears.isNotEmpty()) {
                val fx = x(NOSE) - ears.map { x(it) }.average().toFloat()
                if (abs(fx) >= FACING_MIN_DX) out[NAMES_OK] = if ((fx > 0f) == (fSign > 0f)) 1f else 0f
            }
        }
        return out
    }

    /**
     * 걸음 하나의 앞다리 쪽 — 걸음 바닥 프레임들로 정한다(spec §63). 3D 발목 전후(`lunge_fwd_d` 중앙값)의 부호가 |값| ≥ [FRONT_MIN] 일 때만.
     * 모름(null): 정면(C)·뷰 모름이 아닌데도 — 2D 무릎 높이가 반대 부호로 [KH2D_VETO] 이상, 또는 얼굴 방향 점검(`lunge_names_ok`) 중앙값이 0.
     * 정면에서는 앞뒤가 깊이축이라 늘 null. MediaPipe 이름(왼/오)은 분석 프레임이 미러가 아니라 사용자의 해부학적 쪽이다.
     */
    fun stepSide(bottom: List<Map<String, Float>>, view: String?): StepSide? {
        if (view == "C") return null
        val fwd = bottom.mapNotNull { it[FWD_D] }.takeIf { it.isNotEmpty() }?.let { median(it) } ?: return null
        val side = when {
            fwd >= FRONT_MIN -> StepSide.LEFT
            fwd <= -FRONT_MIN -> StepSide.RIGHT
            else -> return null
        }
        bottom.mapNotNull { it[KNEE_H2D] }.takeIf { it.isNotEmpty() }?.let { median(it) }?.let { kh ->
            val sign = if (side == StepSide.LEFT) 1f else -1f
            if (kh * sign <= -KH2D_VETO) return null
        }
        bottom.mapNotNull { it[NAMES_OK] }.takeIf { it.isNotEmpty() }?.let { median(it) }?.let { if (it < 0.5f) return null }
        return side
    }

    private fun median(v: List<Float>): Float = v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f }

}
