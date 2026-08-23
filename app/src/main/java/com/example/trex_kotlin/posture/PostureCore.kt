package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 자세 판정 코어 — research/aihub_fitness/KOTLIN_PORTING_SPEC.md 의 §3~§6 구현.
 *
 * MediaPipe worldLandmarks(미터, 골반 중점 원점, y 아래 +) → cm, y 위 + 로 변환한 뒤
 * 신체 좌표계(골반 원점, x_b 사람의 왼쪽, y_b 위, z_b 전방)에서 각도/정규화 거리 피처를 계산한다.
 * 모든 피처는 연구 코드(research/aihub_fitness/features.py)와 같은 식을 쓴다.
 */

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val norm: Float get() = sqrt(this dot this)
    fun unit(): Vec3? = norm.let { if (it < 1e-6f) null else this * (1f / it) }
}

private const val RAD = 180.0 / Math.PI

fun mid(a: Vec3, b: Vec3) = (a + b) * 0.5f

/** b 를 꼭짓점으로 하는 각도(도). */
fun angle3(a: Vec3, b: Vec3, c: Vec3): Float? {
    val u = (a - b).unit() ?: return null
    val w = (c - b).unit() ?: return null
    return (acos((u dot w).coerceIn(-1f, 1f)) * RAD).toFloat()
}

fun angleVec(u: Vec3, w: Vec3): Float? {
    val a = u.unit() ?: return null
    val b = w.unit() ?: return null
    return (acos((a dot b).coerceIn(-1f, 1f)) * RAD).toFloat()
}

/** 점 p 에서 직선 a→b 까지의 수직 성분과 직선 길이. */
private fun perpFromLine(p: Vec3, a: Vec3, b: Vec3): Pair<Vec3, Float>? {
    val axis = b - a
    val len = axis.norm
    if (len < 1e-6f) return null
    val u = axis * (1f / len)
    val d = p - a
    return Pair(d - u * (d dot u), len)
}

/** 24 관절 이름 (AIHub 라벨 기준). Back/Waist 는 MediaPipe 에 없어 제외. */
object Joints {
    const val NOSE = "Nose"
    const val L_EYE = "LEye"
    const val R_EYE = "REye"
    const val L_EAR = "LEar"
    const val R_EAR = "REar"
    const val L_SHOULDER = "LShoulder"
    const val R_SHOULDER = "RShoulder"
    const val L_ELBOW = "LElbow"
    const val R_ELBOW = "RElbow"
    const val L_WRIST = "LWrist"
    const val R_WRIST = "RWrist"
    const val L_HIP = "LHip"
    const val R_HIP = "RHip"
    const val L_KNEE = "LKnee"
    const val R_KNEE = "RKnee"
    const val L_ANKLE = "LAnkle"
    const val R_ANKLE = "RAnkle"
    const val L_PALM = "LPalm"
    const val R_PALM = "RPalm"
    const val L_FOOT = "LFoot"
    const val R_FOOT = "RFoot"

    /** 단일 MediaPipe 인덱스로 매핑되는 관절. */
    val SINGLE: Map<String, Int> = mapOf(
        NOSE to 0, L_EYE to 2, R_EYE to 5, L_EAR to 7, R_EAR to 8,
        L_SHOULDER to 11, R_SHOULDER to 12, L_ELBOW to 13, R_ELBOW to 14,
        L_WRIST to 15, R_WRIST to 16, L_HIP to 23, R_HIP to 24,
        L_KNEE to 25, R_KNEE to 26, L_ANKLE to 27, R_ANKLE to 28,
        L_FOOT to 31, R_FOOT to 32,
    )

    /** 두 인덱스의 중점으로 만드는 관절. Neck = 어깨 중점(AIHub 의 목 기저보다 낮음 — spec §3 참고). */
    val PAIR: Map<String, Pair<Int, Int>> = mapOf(
        L_PALM to (17 to 19), // pinky, index
        R_PALM to (18 to 20),
    )
}

/**
 * 한 프레임의 24관절(월드 cm). 값이 null 이면 그 관절은 신뢰도 미달.
 *
 * @param up 중력 반대 방향(단위벡터, world 좌표계). IMU 를 못 쓰면 화면 세로축 [SCREEN_UP].
 *   모든 "높이" 피처는 이 축으로의 투영으로 계산되므로, 폰이 기울어도 값이 보존된다.
 */
class PoseFrame(val joints: Map<String, Vec3?>, up: Vec3 = Vec3(0f, 1f, 0f)) {

    private fun g(name: String): Vec3? = joints[name]

    private val up: Vec3 = up.unit() ?: Vec3(0f, 1f, 0f)

    /** 중력축 높이 (스칼라). 점이면 원점 기준 높이, 방향 벡터면 up 성분. */
    private fun h(p: Vec3): Float = p dot this.up

    /** up 성분을 제거한 수평 성분. */
    private fun flat(v: Vec3): Vec3 = v - this.up * (v dot this.up)

    val lHip = g(Joints.L_HIP)
    val rHip = g(Joints.R_HIP)
    val hipMid: Vec3? = if (lHip != null && rHip != null) mid(lHip, rHip) else null
    val lSh = g(Joints.L_SHOULDER)
    val rSh = g(Joints.R_SHOULDER)
    val shMid: Vec3? = if (lSh != null && rSh != null) mid(lSh, rSh) else null

    /** Neck 은 MediaPipe 에 없어 어깨 중점으로 대체 (spec §3). */
    val neck: Vec3? = shMid

    private val xb: Vec3? = if (lHip != null && rHip != null) flat(lHip - rHip).unit() else null
    private val zb: Vec3? = xb?.let { (it cross up).unit() }

    val valid: Boolean get() = hipMid != null && xb != null && zb != null

    /** 신체 좌표계 좌표 (골반 원점). */
    fun body(p: Vec3): Vec3? {
        val o = hipMid ?: return null
        val x = xb ?: return null
        val z = zb ?: return null
        val d = p - o
        return Vec3(d dot x, d dot up, d dot z)
    }

    /** 방향 벡터를 신체 좌표계로 회전만 적용. */
    private fun bodyDir(v: Vec3): Vec3? {
        val x = xb ?: return null
        val z = zb ?: return null
        return Vec3(v dot x, v dot up, v dot z)
    }

    // 정규화 분모 — 해부학적으로 불가능하면 null (spec §4)
    val torsoLen: Float? = if (neck != null && hipMid != null) (neck - hipMid).norm.takeIf { it >= 20f } else null
    val legLen: Float? = run {
        val l = if (lHip != null && g(Joints.L_ANKLE) != null) (lHip - g(Joints.L_ANKLE)!!).norm else null
        val r = if (rHip != null && g(Joints.R_ANKLE) != null) (rHip - g(Joints.R_ANKLE)!!).norm else null
        val v = when {
            l != null && r != null -> (l + r) / 2f
            l != null -> l
            r != null -> r
            else -> null
        }
        v?.takeIf { it >= 40f }
    }
    val shW: Float? = if (lSh != null && rSh != null) (lSh - rSh).norm.takeIf { it >= 15f } else null
    val hipW: Float? = if (lHip != null && rHip != null) (lHip - rHip).norm.takeIf { it >= 8f } else null
    private val ankleMid: Vec3? = run {
        val a = g(Joints.L_ANKLE); val b = g(Joints.R_ANKLE)
        if (a != null && b != null) mid(a, b) else a ?: b
    }
    private val kneeMid: Vec3? = run {
        val a = g(Joints.L_KNEE); val b = g(Joints.R_KNEE)
        if (a != null && b != null) mid(a, b) else a ?: b
    }
    private val palmMid: Vec3? = run {
        val a = g(Joints.L_PALM); val b = g(Joints.R_PALM)
        if (a != null && b != null) mid(a, b) else a ?: b
    }
    private val earMid: Vec3? = run {
        val a = g(Joints.L_EAR); val b = g(Joints.R_EAR)
        if (a != null && b != null) mid(a, b) else a ?: b
    }
    private val bodyH: Float? = if (neck != null && ankleMid != null) h(neck - ankleMid).takeIf { abs(it) >= 30f } else null

    /**
     * 이 프레임의 모든 피처. 계산 불가한 항목은 맵에서 빠진다.
     * 키는 rules_mp_v0.json 의 base_feature 와 1:1 대응.
     */
    fun features(): Map<String, Float> {
        val f = HashMap<String, Float>(80)
        if (!valid) return f
        fun put(k: String, v: Float?) { if (v != null && v.isFinite()) f[k] = v }

        val nose = g(Joints.NOSE)
        val lEl = g(Joints.L_ELBOW); val rEl = g(Joints.R_ELBOW)
        val lWr = g(Joints.L_WRIST); val rWr = g(Joints.R_WRIST)
        val lKn = g(Joints.L_KNEE); val rKn = g(Joints.R_KNEE)
        val lAn = g(Joints.L_ANKLE); val rAn = g(Joints.R_ANKLE)
        val lPa = g(Joints.L_PALM); val rPa = g(Joints.R_PALM)
        val lFt = g(Joints.L_FOOT); val rFt = g(Joints.R_FOOT)

        // ---- 관절 각도 (체형 불변)
        val kneeL = if (lHip != null && lKn != null && lAn != null) angle3(lHip, lKn, lAn) else null
        val kneeR = if (rHip != null && rKn != null && rAn != null) angle3(rHip, rKn, rAn) else null
        put("knee_L", kneeL); put("knee_R", kneeR)
        val hipL = if (lSh != null && lHip != null && lKn != null) angle3(lSh, lHip, lKn) else null
        val hipR = if (rSh != null && rHip != null && rKn != null) angle3(rSh, rHip, rKn) else null
        put("hip_L", hipL); put("hip_R", hipR)
        val elbowL = if (lSh != null && lEl != null && lWr != null) angle3(lSh, lEl, lWr) else null
        val elbowR = if (rSh != null && rEl != null && rWr != null) angle3(rSh, rEl, rWr) else null
        put("elbow_L", elbowL); put("elbow_R", elbowR)
        put("shoulder_L", if (lEl != null && lSh != null && lHip != null) angle3(lEl, lSh, lHip) else null)
        put("shoulder_R", if (rEl != null && rSh != null && rHip != null) angle3(rEl, rSh, rHip) else null)
        put("ankle_L", if (lKn != null && lAn != null && lFt != null) angle3(lKn, lAn, lFt) else null)
        put("ankle_R", if (rKn != null && rAn != null && rFt != null) angle3(rKn, rAn, rFt) else null)
        put("wrist_L", if (lEl != null && lWr != null && lPa != null) angle3(lEl, lWr, lPa) else null)
        put("wrist_R", if (rEl != null && rWr != null && rPa != null) angle3(rEl, rWr, rPa) else null)

        if (kneeL != null && kneeR != null) {
            put("knee_mean", (kneeL + kneeR) / 2f)
            put("knee_minside", minOf(kneeL, kneeR)); put("knee_maxside", maxOf(kneeL, kneeR))
            put("knee_asym", kneeL - kneeR)
        }
        if (hipL != null && hipR != null) { put("hip_mean", (hipL + hipR) / 2f); put("hip_asym", hipL - hipR) }
        if (elbowL != null && elbowR != null) {
            put("elbow_mean", (elbowL + elbowR) / 2f)
            put("elbow_minside", minOf(elbowL, elbowR)); put("elbow_maxside", maxOf(elbowL, elbowR))
            put("elbow_asym", elbowL - elbowR)
        }

        // ---- 몸통 자세
        val chord = if (neck != null && hipMid != null) neck - hipMid else null
        if (chord != null) put("torso_incl", angleVec(chord, up))
        val nb = neck?.let { body(it) }
        if (nb != null) {
            put("torso_pitch", (atan2(nb.z, nb.y) * RAD).toFloat())
            put("torso_roll", (atan2(nb.x, nb.y) * RAD).toFloat())
            torsoLen?.let { put("sh_over_hip_fwd", nb.z / it) }
            if (ankleMid != null && legLen != null) {
                val ab = body(ankleMid)
                if (ab != null) put("neck_over_ankle", (nb.z - ab.z) / legLen)
            }
        }

        // ---- 머리 / 시선
        if (nose != null && earMid != null) {
            val face = nose - earMid
            val fb = bodyDir(face)
            if (fb != null) {
                put("head_pitch", (atan2(fb.y, hypot(fb.x, fb.z)) * RAD).toFloat())
                put("head_yaw", (atan2(fb.x, fb.z) * RAD).toFloat())
            }
            if (chord != null) put("face_vs_torso", angleVec(face, chord))
            zb?.let { put("face_vs_forward", angleVec(face, it)) }
        }
        if (earMid != null && shMid != null && torsoLen != null) {
            put("ear_shoulder_gap", h(earMid - shMid) / torsoLen)
        }

        // ---- 무릎 / 발
        for (side in listOf('L', 'R')) {
            val hip = if (side == 'L') lHip else rHip
            val knee = if (side == 'L') lKn else rKn
            val ankle = if (side == 'L') lAn else rAn
            val foot = if (side == 'L') lFt else rFt
            val sign = if (side == 'L') 1f else -1f
            if (knee != null && ankle != null && foot != null) {
                val shinH = flat(knee - ankle)
                val footH = flat(foot - ankle)
                if (shinH.norm > 8f) put("kneefoot_$side", angleVec(shinH, footH))
                put("foot_pitch_$side", (foot - ankle).unit()?.let { (asin(h(it).coerceIn(-1f, 1f)) * RAD).toFloat() })
            }
            if (ankle != null) put("ankle_y_$side", h(ankle))
            if (foot != null) put("foot_y_$side", h(foot))
            if (hip != null && knee != null && ankle != null) {
                val hb = body(hip); val kb = body(knee); val ab = body(ankle)
                if (hb != null && kb != null && ab != null) {
                    val denom = hb.y - ab.y
                    val leg = (hip - ankle).norm
                    if (abs(denom) >= 1e-3f && leg >= 1e-3f) {
                        val t = (kb.y - ab.y) / denom
                        val expX = ab.x + t * (hb.x - ab.x)
                        put("knee_out_$side", sign * (kb.x - expX) / leg)
                    }
                    val shin = (knee - ankle).norm
                    if (shin >= 1e-3f) put("knee_fwd_$side", (kb.z - ab.z) / shin)
                    hipW?.let { put("knee_lat_$side", sign * (kb.x - hb.x) / it) }
                }
                legLen?.let { ll -> hipMid?.let { put("knee_h_$side", h(knee - it) / ll) } }
            }
        }
        val koL = f["knee_out_L"]; val koR = f["knee_out_R"]
        if (koL != null && koR != null) put("knee_out_mean", (koL + koR) / 2f)
        // kneefoot_mean 은 연구 코드가 nanmean 을 쓰므로 한쪽만 있어도 그 값을 쓴다
        val kfL = f["kneefoot_L"]; val kfR = f["kneefoot_R"]
        when {
            kfL != null && kfR != null -> put("kneefoot_mean", (kfL + kfR) / 2f)
            kfL != null -> put("kneefoot_mean", kfL)
            kfR != null -> put("kneefoot_mean", kfR)
        }
        val ayL = f["ankle_y_L"]; val ayR = f["ankle_y_R"]
        if (ayL != null && ayR != null) put("ankle_y_mean", (ayL + ayR) / 2f)
        val fyL = f["foot_y_L"]; val fyR = f["foot_y_R"]
        if (fyL != null && fyR != null) put("foot_y_mean", (fyL + fyR) / 2f)
        if (lKn != null && rKn != null && hipW != null) put("knee_gap", (lKn - rKn).norm / hipW)
        if (hipMid != null && ankleMid != null && legLen != null) {
            put("hip_height_rel", h(hipMid - ankleMid) / legLen)
            if (kneeMid != null) put("hip_below_knee", h(hipMid - kneeMid) / legLen)
        }
        if (lAn != null && rAn != null && hipW != null) put("stance_w", (lAn - rAn).norm / hipW)

        // ---- 손 / 팔
        val pb = palmMid?.let { body(it) }
        if (pb != null && torsoLen != null) {
            put("palm_fwd_hip", pb.z / torsoLen)
            put("palm_dist_body", hypot(pb.x, pb.z) / torsoLen)
            kneeMid?.let { km -> body(km)?.let { put("palm_fwd_knee", (pb.z - it.z) / torsoLen) } }
            ankleMid?.let { am -> body(am)?.let { put("palm_fwd_ankle", (pb.z - it.z) / torsoLen) } }
        }
        if (pb != null && shW != null) put("palm_lat", pb.x / shW)
        if (palmMid != null && ankleMid != null && bodyH != null) put("palm_h_rel", h(palmMid - ankleMid) / bodyH)
        if (palmMid != null && shMid != null && torsoLen != null) put("palm_h_sh", h(palmMid - shMid) / torsoLen)
        if (palmMid != null && earMid != null && torsoLen != null) put("palm_head_dist", (palmMid - earMid).norm / torsoLen)
        if (lPa != null && rPa != null) {
            shW?.let { put("grip_w", (lPa - rPa).norm / it) }
            torsoLen?.let { put("hand_h_asym", h(lPa - rPa) / it) }
        }
        for (side in listOf('L', 'R')) {
            val sh = if (side == 'L') lSh else rSh
            val el = if (side == 'L') lEl else rEl
            val wr = if (side == 'L') lWr else rWr
            val hip = if (side == 'L') lHip else rHip
            if (el != null && wr != null) put("forearm_vert_$side", angleVec(wr - el, up))
            if (sh != null && el != null) put("upperarm_vert_$side", angleVec(el - sh, up))
            if (sh != null && el != null && hip != null) {
                perpFromLine(el, hip, sh)?.let { (perp, len) -> put("elbow_torso_$side", perp.norm / len) }
            }
            if (sh != null && el != null && torsoLen != null) put("elbow_h_$side", h(el - sh) / torsoLen)
            if (el != null && wr != null && torsoLen != null) put("elbow_wrist_h_$side", h(el - wr) / torsoLen)
            if (sh != null && hipMid != null && torsoLen != null) put("shoulder_h_$side", h(sh - hipMid) / torsoLen)
        }
        val fvL = f["forearm_vert_L"]; val fvR = f["forearm_vert_R"]
        if (fvL != null && fvR != null) put("forearm_vert_mean", (fvL + fvR) / 2f)
        if (lSh != null && rSh != null && shW != null) put("shoulder_asym", h(lSh - rSh) / shW)

        // ---- 무릎-팔꿈치
        if (torsoLen != null) {
            val dL = if (lKn != null && lEl != null) (lKn - lEl).norm else null
            val dR = if (rKn != null && rEl != null) (rKn - rEl).norm else null
            val d = when {
                dL != null && dR != null -> minOf(dL, dR)
                dL != null -> dL
                dR != null -> dR
                else -> null
            }
            if (d != null) put("knee_elbow_dist", d / torsoLen)
        }

        // ---- 좌/우 쌍 피처의 side-agnostic 집계 (연구 코드 features.py 와 동일)
        // mean 은 양쪽이 모두 있어야 하고, minside/maxside 는 nanmin/nanmax 처럼 한쪽만 있어도 그 값을 쓴다.
        // 한쪽 관절만 보이는 프레임도 있으므로 _L / _R 양쪽에서 base 를 수집한다
        val pairBases = f.keys.filter { it.endsWith("_L") || it.endsWith("_R") }.map { it.dropLast(2) }.distinct()
        for (base in pairBases) {
            val l = f["${base}_L"]
            val r = f["${base}_R"]
            if (l != null && r != null) {
                f.putIfAbsent("${base}_mean", (l + r) / 2f)
                f.putIfAbsent("${base}_minside", minOf(l, r))
                f.putIfAbsent("${base}_maxside", maxOf(l, r))
            } else {
                val one = l ?: r
                if (one != null) {
                    f.putIfAbsent("${base}_minside", one)
                    f.putIfAbsent("${base}_maxside", one)
                }
            }
        }
        return f
    }
}

/**
 * up 방향 자가검증 결과.
 * - [flipped] = 관절 배치가 "골반이 발목 아래 / 귀가 어깨 아래" 면 up 이 뒤집힌 것으로 보고 −up 을 쓴다.
 * - [verified] = 서 있는 자세라 방향을 확인할 수 있었는지(누운 자세·관절 부족이면 false, 보정도 하지 않음).
 */
data class UpSanity(
    val verified: Boolean,
    val flipped: Boolean,
    /** (HipMid − AnkleMid)·up, cm. 서 있으면 +60~+100. */
    val hipAboveAnkleCm: Float?,
    /** (EarMid − ShMid)·up, cm. 서 있으면 +10~+25. */
    val earAboveShoulderCm: Float?,
) {
    companion object {
        val UNVERIFIED = UpSanity(verified = false, flipped = false, hipAboveAnkleCm = null, earAboveShoulderCm = null)
    }
}

private const val UP_SANITY_HIP_ANKLE_CM = 30f
private const val UP_SANITY_EAR_SHOULDER_CM = 6f

/**
 * 관절 배치로 up 벡터 방향을 검증한다 (IMU 부호·회전 매핑 오류의 안전장치).
 * 골반-발목 높이차가 −30cm 미만이면(또는 다리가 안 보일 때 귀-어깨가 −6cm 미만) 뒤집힘으로 판정.
 * 누운 자세처럼 높이차가 작으면 판정 불가(verified=false) — 이때는 보정하지 않는다.
 */
fun checkUpSanity(joints: Map<String, Vec3?>, up: Vec3): UpSanity {
    val u = up.unit() ?: return UpSanity.UNVERIFIED
    fun m(a: String, b: String): Vec3? {
        val p = joints[a]; val q = joints[b]
        return if (p != null && q != null) mid(p, q) else p ?: q
    }
    val hipMid = m(Joints.L_HIP, Joints.R_HIP)
    val ankleMid = m(Joints.L_ANKLE, Joints.R_ANKLE)
    val earMid = m(Joints.L_EAR, Joints.R_EAR)
    val shMid = m(Joints.L_SHOULDER, Joints.R_SHOULDER)
    val hipAnkle = if (hipMid != null && ankleMid != null) (hipMid - ankleMid) dot u else null
    val earSh = if (earMid != null && shMid != null) (earMid - shMid) dot u else null
    return when {
        hipAnkle != null && hipAnkle < -UP_SANITY_HIP_ANKLE_CM -> UpSanity(true, true, hipAnkle, earSh)
        hipAnkle != null && hipAnkle > UP_SANITY_HIP_ANKLE_CM -> UpSanity(true, false, hipAnkle, earSh)
        hipAnkle == null && earSh != null && earSh < -UP_SANITY_EAR_SHOULDER_CM -> UpSanity(true, true, null, earSh)
        hipAnkle == null && earSh != null && earSh > UP_SANITY_EAR_SHOULDER_CM -> UpSanity(true, false, null, earSh)
        else -> UpSanity(false, false, hipAnkle, earSh)
    }
}

/**
 * 세트 창 집계 (spec §6). 프레임 피처를 모아 mean/min/max/std/range 를 낸다.
 * 샘플링 간격은 호출 측에서 2~4 fps 로 맞춘다 — 임계값의 전제.
 */
class FeatureAggregator {
    private val values = HashMap<String, MutableList<Float>>()
    var frameCount: Int = 0
        private set

    fun add(frameFeatures: Map<String, Float>) {
        frameCount += 1
        for ((k, v) in frameFeatures) {
            if (v.isFinite()) values.getOrPut(k) { ArrayList() }.add(v)
        }
    }

    fun count(base: String): Int = values[base]?.size ?: 0

    fun stat(base: String, stat: String): Float? {
        val v = values[base] ?: return null
        if (v.isEmpty()) return null
        return when (stat) {
            "mean" -> v.average().toFloat()
            "min" -> v.min()
            "max" -> v.max()
            "range" -> v.max() - v.min()
            "std" -> {
                val m = v.average()
                sqrt(v.sumOf { (it - m) * (it - m) } / v.size).toFloat()
            }
            else -> null
        }
    }

    fun reset() {
        values.clear()
        frameCount = 0
    }
}
