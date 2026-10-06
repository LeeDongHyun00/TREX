package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2

/** §93 네 종목의 공통 기하. 월드 이동을 접지/미끄러짐으로 해석하지 않는다. 앱·재생기 공용이다. */
object FourExerciseGeometry {
    fun features(p: PoseFrame, xy: FloatArray? = null, aspect: Float = Stance2d.DEFAULT_ASPECT): Map<String, Float> {
        val j = p.joints
        val out = HashMap<String, Float>()
        val lh = p.lHip ?: return out; val rh = p.rHip ?: return out
        val ls = p.lSh ?: return out; val rs = p.rSh ?: return out
        val lk = j[Joints.L_KNEE] ?: return out; val rk = j[Joints.R_KNEE] ?: return out
        val la = j[Joints.L_ANKLE] ?: return out; val ra = j[Joints.R_ANKLE] ?: return out
        val torso = (mid(ls, rs) - mid(lh, rh)).norm
        val leg = ((lh - lk).norm + (lk - la).norm + (rh - rk).norm + (rk - ra).norm) / 2f
        if (torso < 15f || leg < 40f || !leg.isFinite()) return out
        val axis = ls - rs
        val n = kotlin.math.sqrt(axis.x * axis.x + axis.z * axis.z)
        if (n < 10f) return out
        out["four_visible"] = 1f
        out["four_leg_cm"] = leg
        out["four_axis_x"] = axis.x / n; out["four_axis_z"] = axis.z / n
        out["four_feet_x"] = (la.x - ra.x) / leg
        out["four_feet_z"] = (la.z - ra.z) / leg
        if (xy != null && xy.size >= 66) {
            if (listOf(11,12,23,24,25,26,27,28).any { xy[it*2] !in 0f..1f || xy[it*2+1] !in 0f..1f }) return emptyMap()
            val tx = ((xy[22]+xy[24])-(xy[46]+xy[48])) * aspect / 2f
            val ty = ((xy[23]+xy[25])-(xy[47]+xy[49])) / 2f
            val torso2d = kotlin.math.sqrt(tx*tx+ty*ty)
            if (torso2d >= .05f) {
                out["four_img_torso"] = torso2d
                out["four_img_x_L"] = xy[54]*aspect; out["four_img_y_L"] = xy[55]
                out["four_img_x_R"] = xy[56]*aspect; out["four_img_y_R"] = xy[57]
            }
        }
        // 어깨와 골반의 상대 요: 정상 측굴을 회전과 구별한다.
        val hip = lh - rh
        val yaw = (atan2(axis.z, axis.x) - atan2(hip.z, hip.x)) * (180f / Math.PI.toFloat())
        out["four_twist"] = abs((yaw + 540f) % 360f - 180f)
        for (s in listOf("L", "R")) {
            val h = if (s == "L") lh else rh; val k = if (s == "L") lk else rk
            val a = if (s == "L") la else ra; val sh = if (s == "L") ls else rs
            val hb = p.body(h) ?: continue; val kb = p.body(k) ?: continue
            val ab = p.body(a) ?: continue
            val thigh = kb - hb
            if (thigh.norm < 10f) continue
            // 180° = 아래로 내린 허벅지, 90° = 수평. 몸통 숙임과 독립적인 중력 기준 각이다.
            out["four_thigh_$s"] = (acos((thigh.y / thigh.norm).coerceIn(-1f, 1f)) * 180.0 / Math.PI).toFloat()
            out["four_ankle_h_$s"] = ab.y / leg
            out["four_knee_out_$s"] = (if (s == "L") 1f else -1f) * thigh.x / leg
            out["four_trunk_$s"] = (sh - h).norm / torso
            j[if (s == "L") Joints.L_ELBOW else Joints.R_ELBOW]?.let { out["four_elbow_knee_$s"] = (it - k).norm / torso }
            val hand = j[if (s == "L") Joints.L_WRIST else Joints.R_WRIST]
            val ear = j[if (s == "L") Joints.L_EAR else Joints.R_EAR]
            if (hand != null && ear != null) out["four_hand_head_$s"] = (hand - ear).norm / torso
        }
        return out.filterValues { it.isFinite() }
    }
}

enum class FourExercise(val title: String, val paired: Boolean) {
    CROSS("크로스 런지", true), SIDE("사이드 런지", true),
    KNEE_UP("스탠딩 니업", false), SIDE_CRUNCH("스탠딩 사이드 크런치", false);
    val signal: String get() = if (paired) "knee_{side}" else "four_thigh_{side}"
    companion object { fun of(exercise: String): FourExercise? = entries.firstOrNull { it.title == exercise } }
}

/**
 * 준비→이탈→복귀 카운터(beta). 런지는 두 무릎 폄+발 배치 복귀, 들기는 같은 다리의 복귀로 마감한다.
 * 진폭 25°·발 이동 0.12~0.15는 검출용 잠정 잡음 문턱이며 정상 폼/실기기 정확도 기준이 아니다.
 * 쪽을 추측해 채우지 않는다. 이름 반증은 원시 반복의 쪽을 null로 남기고, 궤적 끊김은 후보를 버린다.
 */
class FourExerciseTracker(val exercise: FourExercise) {
    private data class Motion(val side: StepSide?, val start: Long, val high: Float, var low: Float,
                              var peak: Map<String, Float>, var named: Boolean = true, var returnAt: Long? = null,
                              var observedSide: StepSide? = null, var sideConflict: Boolean = false, var simultaneous: Boolean = false)
    private var ready = emptyMap<String, Float>()
    private val preparation = ArrayList<Pair<Long, Map<String, Float>>>()
    private val motion = arrayOfNulls<Motion>(2)
    private var lastAt: Long? = null
    private var lastFrame = emptyMap<String, Float>()
    private var axisX = 0f; private var axisZ = 0f
    var seed: Map<String, Float>? = null
        private set
    val rejected = ArrayList<RepRejected>()
    val completed = ArrayList<RepCycle>()
    val pending: Boolean get() = motion.any { it != null }
    var left: Int = 0; private set
    var right: Int = 0; private set
    var unknown: Int = 0; private set
    fun candidate(): RepCandidate? = motion.filterNotNull().firstOrNull()?.let { RepCandidate(it.start, it.low, it.high) }

    fun resetCycle() { ready = emptyMap(); preparation.clear(); motion.fill(null); lastAt = null; lastFrame = emptyMap() }
    fun reset() { resetCycle(); rejected.clear(); completed.clear(); seed = null; left = 0; right = 0; unknown = 0 }
    /** 준비 프레임으로 같은 안정성 검사를 거친 뒤 시작 기준만 옮긴다. 준비 동작은 발표하지 않는다. */
    fun prepare(frames: List<Pair<Long, Map<String, Float>>>, atMs: Long) {
        val recent = frames.filter { atMs - it.first in 0..1600 }
        if (recent.isEmpty() || atMs - recent.last().first > MAX_GAP_MS) return
        val probe = FourExerciseTracker(exercise)
        recent.forEach { probe.onFrame(it.first, it.second) }
        if (probe.ready.isNotEmpty() && !probe.pending && probe.completed.isEmpty()) restoreSeed(probe.ready)
    }
    /** 로그의 준비 기준을 재생할 때 쓰는 입구. */
    fun restoreSeed(features: Map<String, Float>) {
        val required = listOf("four_axis_x", "four_axis_z", "four_feet_x", "four_feet_z", "knee_L", "knee_R",
            "four_thigh_L", "four_thigh_R", "four_ankle_h_L", "four_ankle_h_R")
        if (required.any { features[it]?.isFinite() != true }) return
        ready = features.toMap(); seed = ready
        axisX = ready.getValue("four_axis_x"); axisZ = ready.getValue("four_axis_z")
    }
    private fun value(f: Map<String, Float>, side: StepSide) = f[exercise.signal.replace("{side}", side.key)]
    private fun lateral(f: Map<String, Float>) = f.getValue("four_feet_x") * axisX + f.getValue("four_feet_z") * axisZ
    private fun forward(f: Map<String, Float>) = -f.getValue("four_feet_x") * axisZ + f.getValue("four_feet_z") * axisX
    private fun known(f: Map<String, Float>) = f[Lunge2d.NAMES_OK] != 0f
    private fun footTravel(f: Map<String, Float>, side: StepSide): Float? {
        val scale = ready["four_img_torso"]?.takeIf { it >= .05f } ?: return null
        val x = f["four_img_x_${side.key}"] ?: return null; val y = f["four_img_y_${side.key}"] ?: return null
        val x0 = ready["four_img_x_${side.key}"] ?: return null; val y0 = ready["four_img_y_${side.key}"] ?: return null
        return kotlin.math.sqrt((x-x0)*(x-x0)+(y-y0)*(y-y0))/scale
    }

    fun onFrame(t: Long, input: Map<String, Float>): List<RepCycle> {
        val f = input.filterValues { it.isFinite() }.toMutableMap()
        val keys = listOf("four_visible", "four_feet_x", "four_feet_z", "four_axis_x", "four_axis_z", "four_leg_cm",
            "knee_L", "knee_R", "four_thigh_L", "four_thigh_R", "four_ankle_h_L", "four_ankle_h_R")
        if (keys.any { it !in f } || lastAt?.let { t <= it || t - it > MAX_GAP_MS } == true) {
            resetCycle()
            return emptyList()
        }
        if (ready.isNotEmpty()) {
            val scale = f.getValue("four_leg_cm") / (ready["four_leg_cm"] ?: f.getValue("four_leg_cm"))
            for (key in listOf("four_feet_x", "four_feet_z", "four_ankle_h_L", "four_ankle_h_R")) f[key] = f.getValue(key) * scale
        }
        // 마디 길이 붕괴·몸 이름 급반전은 같은 궤적으로 이어 세지 않는다.
        if (lastFrame.isNotEmpty()) {
            val ratio = f.getValue("four_leg_cm") / lastFrame.getValue("four_leg_cm")
            val dot = f.getValue("four_axis_x") * lastFrame.getValue("four_axis_x") + f.getValue("four_axis_z") * lastFrame.getValue("four_axis_z")
            if (ratio !in .7f..1.4f || dot < .5f) { resetCycle(); return emptyList() }
        }
        lastAt = t; lastFrame = f
        if (ready.isEmpty()) {
            val standing = listOf("knee_L", "knee_R", "four_thigh_L", "four_thigh_R").all { f.getValue(it) >= 145f }
            if (!standing) { preparation.clear(); return emptyList() }
            preparation += t to f
            while (preparation.size > 12) preparation.removeAt(0)
            if (preparation.size < 3 || t - preparation.first().first < 400) return emptyList()
            if (listOf("knee_L", "knee_R", "four_thigh_L", "four_thigh_R", "four_feet_x", "four_feet_z").any { key ->
                    val vs = preparation.map { it.second.getValue(key) }; vs.max() - vs.min() > if (key.startsWith("four_feet")) .08f else 10f
                }) { preparation.removeAt(0); return emptyList() }
            ready = f.keys.associateWith { key -> preparation.mapNotNull { it.second[key] }.sorted().let { it[it.size / 2] } }
            axisX = ready.getValue("four_axis_x"); axisZ = ready.getValue("four_axis_z")
            preparation.clear()
            return emptyList()
        }
        val events = ArrayList<RepCycle>()
        // 준비 축에서 45° 넘게 돌아선 궤적은 축을 새로 잡은 뒤 센다.
        if (f.getValue("four_axis_x") * axisX + f.getValue("four_axis_z") * axisZ < .707f) { resetCycle(); return emptyList() }
        for (i in 0 until if (exercise.paired) 1 else 2) {
            val s = if (i == 0) StepSide.LEFT else StepSide.RIGHT
            val v = if (exercise.paired) minOf(f.getValue("knee_L"), f.getValue("knee_R")) else value(f, s)!!
            val high = if (exercise.paired) minOf(ready.getValue("knee_L"), ready.getValue("knee_R")) else value(ready, s)!!
            var m = motion[i]
            if (m == null && high - v >= MIN_AMP) {
                m = Motion(if (exercise.paired) null else s, t, high, v, f, known(f)); motion[i] = m
                if (!exercise.paired) motion[1-i]?.takeIf { abs(t-it.start) <= 200 }?.let { it.simultaneous = true; m.simultaneous = true }
            }
            if (m == null) continue
            m.named = m.named && known(f)
            if (exercise.paired && high - v >= MIN_AMP) {
                val observed = when (exercise) {
                    FourExercise.CROSS -> if (forward(f) > .12f) StepSide.LEFT else if (forward(f) < -.12f) StepSide.RIGHT else null
                    else -> if (f.getValue("knee_R") - f.getValue("knee_L") > 12f) StepSide.LEFT
                        else if (f.getValue("knee_L") - f.getValue("knee_R") > 12f) StepSide.RIGHT else null
                }
                if (observed != null) {
                    if (m.observedSide != null && m.observedSide != observed) m.sideConflict = true
                    m.observedSide = observed
                }
            }
            if (v < m.low) { m.low = v; m.peak = f }
            if (t - m.start > MAX_CYCLE_MS) { motion[i] = null; continue }
            val feetBack = !exercise.paired || (abs(lateral(f) - lateral(ready)) < .15f && abs(forward(f) - forward(ready)) < .15f &&
                StepSide.entries.all { (footTravel(f,it) ?: Float.POSITIVE_INFINITY) < .20f })
            val legsBack = !exercise.paired || listOf("knee_L", "knee_R").all { f.getValue(it) >= ready.getValue(it) - RETURN_BAND }
            val ankleBack = exercise.paired || abs(f.getValue("four_ankle_h_${s.key}") - ready.getValue("four_ankle_h_${s.key}")) < .12f
            if (v < high - RETURN_BAND || !feetBack || !legsBack || !ankleBack) { m.returnAt = null; continue }
            if (m.returnAt == null) m.returnAt = t
            if (t - m.returnAt!! < RETURN_MS || t - m.start < 400) continue
            if (exercise == FourExercise.SIDE_CRUNCH && abs((f["torso_roll"] ?: 90f) - (ready["torso_roll"] ?: 0f)) > 12f) continue
            motion[i] = null
            val p = m.peak
            val side = when (exercise) {
                FourExercise.CROSS -> if (forward(p) > .12f) StepSide.LEFT else if (forward(p) < -.12f) StepSide.RIGHT else null
                FourExercise.SIDE -> when {
                    p.getValue("knee_R") - p.getValue("knee_L") > 12 -> StepSide.LEFT
                    p.getValue("knee_L") - p.getValue("knee_R") > 12 -> StepSide.RIGHT
                    else -> null
                }
                else -> s
            }
            val identity = when (exercise) {
                FourExercise.CROSS -> abs(forward(p) - forward(ready)) >= .18f && lateral(ready) - lateral(p) >= .12f
                FourExercise.SIDE -> lateral(p) - lateral(ready) >= .15f
                FourExercise.KNEE_UP -> p.getValue("four_ankle_h_${s.key}") - ready.getValue("four_ankle_h_${s.key}") >= .10f
                FourExercise.SIDE_CRUNCH -> p.getValue("four_ankle_h_${s.key}") - ready.getValue("four_ankle_h_${s.key}") >= .10f &&
                    ((p["four_knee_out_${s.key}"] ?: 0f) >= .10f || abs(p["torso_roll"] ?: 0f) >= 5f)
            }
            val kh = p[Lunge2d.KNEE_H2D]
            val imageConflict = !exercise.paired && kh != null && (if (s == StepSide.LEFT) kh < -.10f else kh > .10f)
            val moving = if (exercise == FourExercise.CROSS) side?.other else side
            val moved = moving?.let { footTravel(p,it) }
            val support = moving?.let { footTravel(p,it.other) }
            val stepObserved = !exercise.paired || (moved != null && support != null && moved >= .10f && support <= moved*.7f+.05f)
            if (!identity || !stepObserved || imageConflict || m.simultaneous) {
                rejected += RepRejected(t, m.low, m.high, Float.NaN, "four_identity_unknown")
                continue
            }
            val c = RepCycle(t, m.start, m.low, m.high, side = side.takeIf { m.named && !m.sideConflict })
            completed += c; events += c
            when (c.side) { StepSide.LEFT -> left++; StepSide.RIGHT -> right++; null -> unknown++ }
        }
        return events
    }

    companion object {
        const val VERSION = "four_v1_beta"
        const val MIN_AMP = 25f
        const val RETURN_BAND = 10f
        const val RETURN_MS = 120L
        const val MAX_GAP_MS = 750L
        const val MAX_CYCLE_MS = 15000L
    }
}
