package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * 바닥 2D 기하의 순수 함수 — `FloorFeatureExtractor`(PostureFloor.kt)·`PlankGeometry`·`FloorChain` 이 함께 쓴다. 안드로이드·`PoseSample` 의존이 없어 재생기(replay-jvm)가 컴파일한다.
 * 모든 점은 px(정규화 × 이미지 크기)이고 연산은 Double(연구 float64 와 패리티).
 */
object Floor2d {
    /**
     * 직선 a→b 대비 점 p 의 수직 이탈 / |a−b|. 법선을 화면 위쪽(이미지 −y)으로 고정 —
     * n0 = (−u_y, u_x) 가 아래를 향하면 뒤집는다. 좌우 반전 불변.
     */
    fun devUp(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
        var ux = b[0] - a[0]
        var uy = b[1] - a[1]
        val len = max(hypot(ux, uy), 1e-6)
        ux /= len; uy /= len
        var nx = -uy
        var ny = ux
        if (ny > 0) { nx = -nx; ny = -ny }
        return ((p[0] - a[0]) * nx + (p[1] - a[1]) * ny) / len
    }

    /** b 를 꼭짓점으로 하는 2D 각(도). */
    fun ang(a: DoubleArray, b: DoubleArray, c: DoubleArray): Double {
        val ux = a[0] - b[0]; val uy = a[1] - b[1]
        val wx = c[0] - b[0]; val wy = c[1] - b[1]
        val n = max(hypot(ux, uy) * hypot(wx, wy), 1e-6)
        val cos = ((ux * wx + uy * wy) / n).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }
}

/**
 * 바닥 계열 관측 층(spec §99, `docs/FLOOR_FAMILY_DESIGN.md` §4.1) — 플랭크·크런치·라잉 레그 레이즈를 **몸 옆에서 보이는 쪽 한 사슬**로 본다.
 *
 * 왜 새 층인가: 중점 피처(`FloorFeatureExtractor.compute`)는 좌우 관절이 **모두** 보여야 계산돼 측면 촬영에서 먼 쪽이 가려지면 55~69 % 프레임만 남았고(09-23 플랭크),
 * 화면 좌표 그대로라 폰을 가로로 눕히면(09-27 롤 90.7°) '위 = +' 부호가 깨졌다. 그래서 여기서는
 *  - **쪽 잠금**: 세트 첫 [LOCK_WINDOW_MS] 동안 어깨·골반·무릎·발목 최소 가시성의 중앙값이 큰 쪽을 잠그고, 반대쪽이 [SWITCH_MARGIN] 넘게 큰 상태가 [SWITCH_HOLD_MS]
 *    이어질 때만 바꾼다(잠정 — 폰에서 세트당 교체 횟수를 센다). `PlankAlignmentTracker` 가 쪽이 바뀌면 상태를 끊으므로 깜빡임을 막는다.
 *  - **중력 기준**: '위' 는 IMU 중력 up 의 화면 성분(`LegGeometry.rollDeg` 와 같은 식)이다. 각과 몸통 정규화 거리는 회전 불변이라 좌표를 돌리지 않고 부호·수평만 up 으로 정한다.
 *    up 을 믿을 수 없으면(중력 없음·폰이 거의 평평 — 화면 성분 < [UP_MIN_INPLANE]) **절대 수평이 필요한 피처**(`fc_axis_h` — 누운 영역·플랭크 엎드림 게이트)만 유보하고
 *    (`fc_up_ok` = 0), 부호는 종전 바닥 피처처럼 화면 위를 '위' 로 둔다(그때 부호 피처는 화면이 서 있다는 가정 — 현행과 같다). 몸 내재 각(무릎·팔꿈치·측면도)은 그대로 낸다.
 *    `fc_torso_elev` 는 그때도 화면 위 기준으로 낸다 — 추적기는 **누운 기준 대비**(상대 기울기)로만 쓰므로 세트 안에서 롤이 바뀌지 않는 한 롤에 무관하다. 전에는 이것까지
 *    유보해 up 이 없으면 레그 레이즈의 출구(일어나 앉음)와 상체 들기 판별이 영영 꺼졌다(리뷰 2026-10-07). 플랭크는 그때 화면 수평도([FLAT_SCREEN] — 종전 `PlankGeometry`
 *    의 |발목x − 어깨x| ÷ 길이)를 내서 서 있는 사람을 플랭크로 재지 않게 한다.
 *  - **up 뒤집힘**: 앱 `checkUpSanity` 는 '골반이 발목 아래' 면 up 을 뒤집는데, 레그 레이즈 상단이 정확히 그 배치라 누운 종목에서 그 보정을 믿을 수 없다(설계 §3.1).
 *    호출자는 [gravityUp] 으로 원래 중력 벡터를 되돌려 넘긴다. 수평 대비 각은 **부호 없는 축각**(0~90°)이라 180° 튐에도 둔감하다.
 *  - **기준선**: 접지선 prefix 중앙값은 쓰지 않는다 — 프레임마다 몸 선분(발목→골반, 어깨→골반 연장)을 기준으로 한다(레그 레이즈의 자기참조 회피).
 *  - **가시성**: 사슬 관절 ≥ [CHAIN_VIS](화면 안). 먼 어깨·골반은 `fc_yaw`·`fc_ratio` 에만 ≥ [FAR_TORSO_VIS](`PlankGeometry` 와 같은 값), 먼 무릎은 두 다리 확인에만 ≥ [FAR_LEG_VIS].
 *    먼 쪽 외삽 좌표는 평균하지 않는다.
 *
 * 피처(접두 `fc_`, 각은 °, 거리는 그 쪽 어깨–골반 2D 길이 = 몸통으로 정규화) — 표는 설계 §4.1. `elev(v | d)` = 방향 d 대비 벡터 v 의 부호 각(위 = +).
 * 세트마다 [reset] 한다(쪽 잠금 이력). 앱 `FloorFeatureExtractor.computeForExercise` 와 재생기가 같은 함수를 부른다.
 */
class FloorChain(val kind: Kind) {
    /** 이 층을 쓰는 종목. 축각의 몸 축이 종목마다 다르다(플랭크 어깨→발목, 누운 종목 어깨→골반). */
    enum class Kind(val exercise: String) {
        PLANK("플랭크"), CRUNCH("크런치"), LEG_RAISE("라잉 레그 레이즈");
        companion object { fun of(exercise: String): Kind? = entries.firstOrNull { it.exercise == exercise } }
    }

    private val windowT = ArrayList<Long>()
    private val windowL = ArrayList<Float>()
    private val windowR = ArrayList<Float>()
    private var windowStart: Long? = null
    private var locked: Int? = null
    private var switchSince: Long? = null
    private var lastT: Long? = null

    /** 잠긴 쪽(0 = MediaPipe 왼쪽 관절, 1 = 오른쪽). 잠그기 전([LOCK_WINDOW_MS])은 null — 그동안은 프레임마다 더 잘 보이는 쪽을 쓴다. */
    val lockedSide: Int? get() = locked
    /** 세트 중 쪽을 바꾼 횟수 — 로그용(잠정 규칙의 검증 재료). */
    var switches = 0
        private set

    fun reset() {
        windowT.clear(); windowL.clear(); windowR.clear(); windowStart = null
        locked = null; switchSince = null; lastT = null; switches = 0
    }

    /**
     * 한 프레임의 `fc_*` 피처. [xy] 정규화 33×2, [vis] = min(visibility, presence) 33, [up] 중력 up(카메라 좌표 — x 화면 오른쪽, y 화면 위, z 카메라 쪽),
     * 모르면 null. 같은 세트의 프레임은 시각 순서로 부른다(쪽 잠금 이력).
     */
    fun features(tMs: Long, xy: FloatArray, vis: FloatArray, width: Int, height: Int, up: Vec3?): Map<String, Float> {
        if (xy.size < 66 || vis.size < 33 || width <= 0 || height <= 0) return emptyMap()
        val out = HashMap<String, Float>(24)
        val side = chooseSide(tMs, chainVis(vis, 0), chainVis(vis, 1))
        out[SIDE] = side.toFloat()
        // 중력 '위' 의 화면 방향(px 좌표, y 아래) — LegGeometry.rollDeg 와 같은 식
        val inPlane = up?.let { u -> val n = u.norm; if (n > 1e-6f && n.isFinite()) hypot(u.x.toDouble(), u.y.toDouble()) / n else 0.0 } ?: 0.0
        val upOk = up != null && inPlane >= UP_MIN_INPLANE
        val ux: Double; val uy: Double
        if (upOk) { val r = Math.toRadians(LegGeometry.rollDeg(up!!).toDouble()); ux = sin(r); uy = -cos(r) } else { ux = 0.0; uy = -1.0 }
        out[UP_OK] = if (upOk) 1f else 0f

        fun visible(i: Int, cut: Float = CHAIN_VIS): Boolean = vis[i].isFinite() && vis[i] >= cut &&
            xy[i * 2].isFinite() && xy[i * 2 + 1].isFinite() && xy[i * 2] in 0f..1f && xy[i * 2 + 1] in 0f..1f
        fun p(i: Int) = doubleArrayOf(xy[i * 2].toDouble() * width, xy[i * 2 + 1].toDouble() * height)
        val o = side; val f = 1 - side
        val ear = 7 + o; val sh = 11 + o; val el = 13 + o; val wr = 15 + o; val hip = 23 + o; val knee = 25 + o; val ank = 27 + o
        val farSh = 11 + f; val farHip = 23 + f; val farKnee = 25 + f
        val chain = visible(sh) && visible(hip) && visible(knee) && visible(ank)
        if (!visible(sh) || !visible(hip)) { out[CHAIN] = 0f; return out }
        val S = p(sh); val H = p(hip)
        val torso = hypot(S[0] - H[0], S[1] - H[1])
        if (torso < MIN_TORSO_PX) { out[CHAIN] = 0f; return out }
        out[CHAIN] = if (chain) 1f else 0f
        out[TORSO] = torso.toFloat()
        fun put(k: String, v: Double) { if (v.isFinite()) out[k] = v.toFloat() }
        fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1])

        // 측면도 — 먼 어깨·골반이 가까운 쪽에서 몸통 축 방향으로 얼마나 벌어졌나(카메라 높이는 수직 성분으로 가서 섞이지 않는다)
        if (visible(farSh, FAR_TORSO_VIS) && visible(farHip, FAR_TORSO_VIS)) {
            val tx = (H[0] - S[0]) / torso; val ty = (H[1] - S[1]) / torso
            val a = sub(p(farSh), S); val b = sub(p(farHip), H)
            put(YAW, max(abs(a[0] * tx + a[1] * ty), abs(b[0] * tx + b[1] * ty)) / torso)
        }
        val ankleOk = visible(ank)
        val A = if (ankleOk) p(ank) else null
        if (A != null && visible(11, FAR_TORSO_VIS) && visible(12, FAR_TORSO_VIS)) {
            val length = hypot(A[0] - S[0], A[1] - S[1])
            val p11 = p(11); val p12 = p(12)
            put(RATIO, length / max(hypot(p11[0] - p12[0], p11[1] - p12[1]), 1.0))
        }
        val kneeOk = visible(knee)
        val K = if (kneeOk) p(knee) else null
        if (K != null && A != null) put(KNEE, Floor2d.ang(H, K, A))
        if (visible(el) && visible(wr)) put(ELBOW, Floor2d.ang(S, p(el), p(wr)))

        when (kind) {
            Kind.PLANK -> {
                if (A != null) {
                    if (upOk) put(AXIS_H, axisDeg(sub(A, S), ux, uy))
                    else put(FLAT_SCREEN, abs(A[0] - S[0]) / max(hypot(A[0] - S[0], A[1] - S[1]), 1e-6))
                    put(HIP_OFF, devAlong(H, S, A, ux, uy))
                    if (visible(el)) {
                        val E = p(el)
                        put(HIP_FLOOR, devAlong(H, E, A, ux, uy) * hypot(A[0] - E[0], A[1] - E[1]) / torso)
                    }
                }
                if (visible(ear) && visible(0)) headPitch(p(ear), p(0), S, H, torso, ux, uy)?.let { put(HEAD_PITCH, it) }
            }
            Kind.CRUNCH -> {
                if (upOk) put(AXIS_H, axisDeg(sub(S, H), ux, uy))
                put(TORSO_ELEV, elevAbove(sub(S, H), ux, uy))                // up 이 없으면 화면 위 기준 — 상대값으로만 쓴다
                if (A != null) {
                    val ground = sub(H, A)                                   // 지면 = 그 프레임의 발목→골반 선
                    put(TRUNK_LIFT, elev(sub(S, H), ground, ux, uy))
                    if (visible(ear)) put(EAR_LIFT, elev(sub(p(ear), H), ground, ux, uy))
                }
            }
            Kind.LEG_RAISE -> {
                if (upOk) put(AXIS_H, axisDeg(sub(S, H), ux, uy))
                put(TORSO_ELEV, elevAbove(sub(S, H), ux, uy))                // up 이 없으면 화면 위 기준 — 출구·상체 들기는 누운 기준 대비로 본다
                val trunkExt = sub(H, S)                                     // 어깨→골반 연장선(0 = 누움)
                if (K != null) put(THIGH, elev(sub(K, H), trunkExt, ux, uy))
                if (A != null) put(LEG, elev(sub(A, H), trunkExt, ux, uy))
                if (visible(ear)) put(HEAD_LIFT, elev(sub(p(ear), S), sub(S, H), ux, uy))
                if (K != null && visible(farKnee, FAR_LEG_VIS)) {
                    val FK = p(farKnee)
                    put(KNEE_GAP, hypot(FK[0] - K[0], FK[1] - K[1]) / torso)
                    if (visible(farHip, FAR_TORSO_VIS)) put(THIGH_FAR, elev(sub(FK, p(farHip)), trunkExt, ux, uy))
                }
            }
        }
        return out
    }

    // ---- 쪽 잠금
    private fun chainVis(vis: FloatArray, o: Int): Float {
        var m = Float.POSITIVE_INFINITY
        for (i in intArrayOf(11 + o, 23 + o, 25 + o, 27 + o)) { val v = vis[i]; m = minOf(m, if (v.isFinite()) v else 0f) }
        return m
    }

    private fun chooseSide(tMs: Long, mvL: Float, mvR: Float): Int {
        val last = lastT
        if (last != null && (tMs <= last || tMs - last > MAX_GAP_MS)) switchSince = null
        lastT = tMs
        val lk = locked
        if (lk == null) {
            if (maxOf(mvL, mvR) >= LOCK_MIN_VIS) {
                if (windowStart == null) windowStart = tMs
                windowT += tMs; windowL += mvL; windowR += mvR
            }
            val ws = windowStart
            if (ws != null && tMs - ws >= LOCK_WINDOW_MS) {
                val side = if (median(windowR) > median(windowL)) 1 else 0
                locked = side; windowT.clear(); windowL.clear(); windowR.clear()
                return side
            }
            return if (mvR > mvL) 1 else 0
        }
        val mine = if (lk == 0) mvL else mvR
        val other = if (lk == 0) mvR else mvL
        if (other - mine > SWITCH_MARGIN) {
            val s = switchSince ?: tMs.also { switchSince = it }
            if (tMs - s >= SWITCH_HOLD_MS) { locked = 1 - lk; switches++; switchSince = null }
        } else switchSince = null
        return locked!!
    }

    private fun median(xs: List<Float>): Float { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f }

    companion object {
        const val SIDE = "fc_side"
        const val TORSO = "fc_torso"
        /** 보이는 쪽 사슬(어깨·골반·무릎·발목 ≥ [CHAIN_VIS], 화면 안)이 다 보이면 1. 플랭크 시간의 관측 조건. */
        const val CHAIN = "fc_chain"
        /** 중력 up 을 믿을 수 있으면 1 — 0 이면 `fc_axis_h` 가 없고 부호 피처(`fc_torso_elev` 포함)는 화면 위 기준. */
        const val UP_OK = "fc_up_ok"
        const val YAW = "fc_yaw"
        const val RATIO = "fc_ratio"
        const val AXIS_H = "fc_axis_h"
        /** 플랭크, up 을 믿을 수 없을 때만 — 어깨→발목의 화면 수평도 |Δx| ÷ 길이(0~1, 종전 `PlankGeometry` 준비 조건 ≥ 0.7). 엎드림 게이트의 폴백(`PlankHoldClock.stopReason`). */
        const val FLAT_SCREEN = "fc_flat_screen"
        const val KNEE = "fc_knee"
        const val ELBOW = "fc_elbow"
        const val TRUNK_LIFT = "fc_trunk_lift"
        const val EAR_LIFT = "fc_ear_lift"
        const val THIGH = "fc_thigh"
        const val LEG = "fc_leg"
        const val HEAD_LIFT = "fc_head_lift"
        /** 어깨–골반 축의 중력 수평 대비 부호 각(어깨가 위 = +). 누운 기준 대비 값 `fc_torso_tilt` 는 반복 추적기가 더한다(`FloorCycleTracker.annotate`). */
        const val TORSO_ELEV = "fc_torso_elev"
        const val TORSO_TILT = "fc_torso_tilt"
        const val KNEE_GAP = "fc_knee_gap"
        const val THIGH_FAR = "fc_thigh_far"
        const val HIP_OFF = "fc_hip_off"
        const val HIP_FLOOR = "fc_hip_floor"
        const val HEAD_PITCH = "fc_head_pitch"

        const val CHAIN_VIS = 0.5f
        const val FAR_TORSO_VIS = 0.2f       // PlankGeometry 의 양 어깨 조건과 같다
        const val FAR_LEG_VIS = 0.35f
        const val LOCK_MIN_VIS = 0.2f        // 이보다 안 보이는 프레임은 잠금 창에 넣지 않는다(화면에 들어오는 중)
        const val LOCK_WINDOW_MS = 1_500L
        const val SWITCH_MARGIN = 0.2f
        const val SWITCH_HOLD_MS = 2_000L
        const val MAX_GAP_MS = 750L
        const val MIN_TORSO_PX = 20.0        // PlankGeometry 와 같다
        /** up 의 화면 성분 하한(단위 벡터 기준) — 이보다 작으면 폰이 거의 평평해 화면 '위' 를 정할 수 없다(약 72° 넘게 젖힘). 잠정. */
        const val UP_MIN_INPLANE = 0.3
        const val HEAD_MAX_DEG = 90.0        // |고개각| > 90° 는 얼굴 방향 뒤집힘(관측 실패, 09-23 −154.88°) — 버린다

        /**
         * 바닥 종목이 쓸 중력 up — 앱 `PoseSample` 의 up 은 `checkUpSanity` 가 뒤집었을 수 있어([flipped]) 되돌린다. 중력에서 온 값이 아니면([fromGravity] = false,
         * 화면 세로축 가정) null = 모름.
         */
        fun gravityUp(up: Vec3, fromGravity: Boolean, flipped: Boolean): Vec3? = when {
            !fromGravity -> null
            flipped -> up * -1f
            else -> up
        }

        /** 방향 d 대비 벡터 v 의 부호 각(°). 법선은 '위'(ux, uy) 쪽 — 위가 화면 위면 연구 `selev` 와 같다. */
        fun elev(v: DoubleArray, d: DoubleArray, ux: Double, uy: Double): Double {
            val l = hypot(d[0], d[1]); if (l < 1e-9) return Double.NaN
            val gx = d[0] / l; val gy = d[1] / l
            val (nx, ny) = upNormal(gx, gy, ux, uy)
            return Math.toDegrees(atan2(v[0] * nx + v[1] * ny, v[0] * gx + v[1] * gy))
        }

        /** 직선 a→b 대비 점 p 의 '위' 쪽 수직 이탈 ÷ |a−b| — 위가 화면 위면 `Floor2d.devUp` 과 같다(`fc_hip_off` = `plank_hip_offset`). */
        fun devAlong(p: DoubleArray, a: DoubleArray, b: DoubleArray, ux: Double, uy: Double): Double {
            val l = max(hypot(b[0] - a[0], b[1] - a[1]), 1e-6)
            val gx = (b[0] - a[0]) / l; val gy = (b[1] - a[1]) / l
            val (nx, ny) = upNormal(gx, gy, ux, uy)
            return ((p[0] - a[0]) * nx + (p[1] - a[1]) * ny) / l
        }

        /** 축(선분 방향, 부호 없음)의 중력 수평 대비 각 0~90°. */
        fun axisDeg(a: DoubleArray, ux: Double, uy: Double): Double {
            val l = hypot(a[0], a[1]); if (l < 1e-9) return Double.NaN
            return Math.toDegrees(asin((abs(a[0] * ux + a[1] * uy) / l).coerceIn(0.0, 1.0)))
        }

        /** 벡터의 중력 수평 대비 부호 각 −90~90°(위로 향하면 +). */
        fun elevAbove(a: DoubleArray, ux: Double, uy: Double): Double {
            val l = hypot(a[0], a[1]); if (l < 1e-9) return Double.NaN
            return Math.toDegrees(asin(((a[0] * ux + a[1] * uy) / l).coerceIn(-1.0, 1.0)))
        }

        /** (gx, gy) 의 법선 중 '위' 쪽 — 위와 직교하면 종전 규약(화면 위, ny ≤ 0)으로 정한다. */
        private fun upNormal(gx: Double, gy: Double, ux: Double, uy: Double): Pair<Double, Double> {
            var nx = -gy; var ny = gx
            val s = nx * ux + ny * uy
            if (s < 0 || (s == 0.0 && ny > 0)) { nx = -nx; ny = -ny }
            return nx to ny
        }

        /**
         * 플랭크 고개각 — `PlankGeometry.HEAD` 와 같은 식(얼굴이 몸통에 수직으로 바닥을 향하면 0°, 몸통 앞쪽으로 들면 +)이되 법선을 중력 '위' 로 정한다.
         * 얼굴 길이가 몸통의 3~65 % 밖이거나 |값| > [HEAD_MAX_DEG] 면 null.
         */
        internal fun headPitch(ear: DoubleArray, nose: DoubleArray, sh: DoubleArray, hip: DoubleArray, torso: Double, ux: Double, uy: Double): Double? {
            val face = hypot(nose[0] - ear[0], nose[1] - ear[1])
            if (face < torso * .03 || face > torso * .65) return null
            val ax = (sh[0] - hip[0]) / torso; val ay = (sh[1] - hip[1]) / torso
            val (nx, ny) = upNormal(ax, ay, ux, uy)
            val fx = nose[0] - ear[0]; val fy = nose[1] - ear[1]
            val v = Math.toDegrees(atan2(fx * ax + fy * ay, -(fx * nx + fy * ny)))
            return v.takeIf { abs(it) <= HEAD_MAX_DEG }
        }
    }
}
