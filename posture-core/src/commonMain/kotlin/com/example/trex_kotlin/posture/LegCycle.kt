// tools/sync_ios_core.py 생성본 — Android 정본에서 수정한 뒤 동기화하세요.
package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 한 다리 계열 v2(spec §97, `docs/SINGLE_LEG_FAMILY_DESIGN.md`) — 크로스 런지·사이드 런지·스탠딩 니업·스탠딩 사이드 크런치.
 *
 * v1(발 이탈 추적, §96)은 "발이 제자리로 돌아온다" 를 전제했는데 2026-10-06 폰 로그는 그 전제가 틀렸음을 보였다: 사이드 런지는 두 발을 옮기며 몸을 좌우로 옮기고,
 * 크로스 런지는 두 발이 모두 교차해 돌아올 때 선 자리가 매번 다르다. 그래서 v2 는 **다리의 기하 자체**로 사이클을 센다 —
 * 사이드 런지 = 한 무릎이 깊게 굽고 골반이 그 발 위로 옮겨감, 크로스 런지 = 발목이 교차하며 내려감, 니업·크런치 = 허벅지가 올라가며 발이 뜸(크런치는 팔꿈치가 무릎에 닿을 만큼 옆구리가 접힘).
 * 네 종목 모두 **왼쪽 한 번 + 오른쪽 한 번 = 1회**(사용자 결정 2026-10-06 저녁, `RepUnit.SIDE_EACH`).
 *
 * v3(§98, 2026-10-06 밤): 폰 2차 세트에서 사용자가 지적한 "세지 말아야 할 회" 를 판별에 더했다 — 얕은 런지(무릎 98~117°)가 띠 115/135 를 지났고, 허리를 숙인 회는 어느 종목에서도
 * 횟수에 영향이 없었고(beta 창 검사뿐), 크런치는 앞으로 올린 무릎·편 다리의 높은 킥이 팔꿈치–무릎 1.0 을 지났다. 그래서 **허리 숙임·편 다리·앞으로 올린 무릎·얕은 깊이**는 세지 않고 이유를 말한다(두 모드).
 *
 * @property signal 사이클 신호 — 극점에서 **작아지는** 값. `{moving}` 은 그 사이클의 다리. 크로스는 발목 교차 `leg_cross` 하나.
 * @property minAmp 반복 검사 창의 서 있음 띠 계산용 진폭(신호 단위).
 */
enum class LegProfile(val title: String, val signal: String, val minAmp: Float) {
    SIDE("사이드 런지", "knee_{moving}", 35f),
    CROSS("크로스 런지", LegGeometry.CROSS, 0.5f),
    KNEE_UP("스탠딩 니업", "thigh_{moving}", 35f),
    SIDE_CRUNCH("스탠딩 사이드 크런치", "thigh_{moving}", 35f);

    /** `{moving}`/`{support}` 를 그 사이클의 다리(움직인 다리 = 쪽)로 바꾼 피처 이름. 틀이 없으면 그대로. */
    fun resolve(template: String, moving: StepSide): String = template.replace("{moving}", moving.key).replace("{support}", moving.other.key)

    companion object { fun of(exercise: String): LegProfile? = entries.firstOrNull { it.title == exercise } }
}

/**
 * 프레임마다의 무상태 기하 — 앱 `PostureAnalyzer` 와 재생기 `frameFeatures` 가 같은 순서·같은 함수로 부른다. 단위는 그 프레임의 2D 몸통 길이(어깨 중점–골반 중점).
 * 2D 는 IMU 롤로 되돌린 이미지 좌표(가로를 aspect 로 등방화). 사용자 좌우는 어깨 x 차(`leg_face_dx`)의 부호로 안다 — 카메라를 보면 왼어깨가 화면 오른쪽.
 *
 * - `thigh_L/R` 중력 기준 허벅지각(°, 3D·IMU up): 180 = 아래로 내림, 90 = 수평. 몸통 숙임과 독립.
 * - `rel_yaw` 어깨선 요 − 골반선 요(°, 몸 좌표계): 비틀림.
 * - `leg_cross` (왼발목 x − 오른발목 x)·부호 ÷ 몸통: + 정상(왼발이 사용자 왼쪽), − 교차. 폰 실측: 선 자세 0.5~0.9, 커시 런지 바닥 −0.4~−0.7.
 * - `leg_hipshift` 골반 중점 x 의 두 발목 사이 위치: 0 = 오른발목 위, 1 = 왼발목 위. 사이드 런지에서 굽힌 다리 쪽 0.7~0.85 / 0.15~0.3.
 * - `leg_lift_L/R` 그 발목이 반대 발목보다 얼마나 높은가(몸통 단위). 니업 1.0~1.8, 런지 ≤ 0.45(뒷발은 멀어서 0.2~0.4).
 * - `hip_hike_L/R` 그 쪽 골반이 반대쪽보다 높은 정도(몸통 단위, 2D 골반선). `knee_out2d_L/R` 그 무릎의 골반 중점 대비 바깥쪽 위치. `head_lean_L/R` 귀선이 어깨선보다 그 쪽으로 더 기운 각(°).
 * - `hand_ear_L/R`·`elbow_knee_L/R` 같은 쪽 2D 거리. 폰 실측: 크런치 수축 팔꿈치–무릎 0.06~0.23(팔꿈치가 무릎에 닿음 — 관절 중심끼리라 0 이 아니다), 다리만 올림 0.77~1.6, 편 다리 높은 킥 0.49~0.66.
 *   `elbow_knee_min_L/R` 은 앱이 85 ms 추론 프레임에서 모은 최솟값(`ContactMinimum`, §98a) — 순간 접촉이 300 ms 판정 표본에 안 잡혀도 닿음을 본다.
 * - `lat_flex_L/R` 측굴(°, 2D): 어깨선이 골반선보다 그 쪽으로 더 기운 각 — 옆구리가 접히면 그 쪽 어깨가 내려가고 골반이 올라간다. 폰 실측: 크런치 수축 44~56, 다리만 14~26.
 * - `knee_fwd_foot_L/R` 무릎이 발 방향(발목→발끝 수평)으로 나간 거리 ÷ 정강이(3D): 발끝은 발목에서 약 0.6 정강이 앞이라 0.6 을 넘으면 무릎이 발끝을 넘은 셈이다.
 *   발끝 관절의 z 는 발 길이를 반으로 읽어(폰 실측 0.17~0.30 정강이) 발끝 자체와 비교하지 않는다. beta 검사 '무릎 발끝 넘김' 의 재료.
 * - `leg_hip_y`·`leg_ankle_y_L/R` 롤 보정 이미지 y 원값(추적기의 골반 하강·뒷발 판별용).
 */
object LegGeometry {
    const val CROSS = "leg_cross"
    const val HIPSHIFT = "leg_hipshift"
    const val HIP_Y = "leg_hip_y"
    const val TORSO2D = "leg_torso2d"
    const val FACE_DX = "leg_face_dx"
    const val REL_YAW = "rel_yaw"
    fun lift(s: StepSide) = "leg_lift_${s.key}"
    fun ankleY(s: StepSide) = "leg_ankle_y_${s.key}"
    fun thigh(s: StepSide) = "thigh_${s.key}"
    fun knee(s: StepSide) = "knee_${s.key}"
    fun elbowKnee(s: StepSide) = "elbow_knee_${s.key}"
    /** 판정 프레임 사이 85 ms 프레임들의 팔꿈치–무릎 최솟값(`ContactMinimum`, §98a) — 있으면 추적기가 [elbowKnee] 대신 쓴다. */
    fun elbowKneeMin(s: StepSide) = "elbow_knee_min_${s.key}"
    fun latFlex(s: StepSide) = "lat_flex_${s.key}"
    fun kneeFwdFoot(s: StepSide) = "knee_fwd_foot_${s.key}"
    fun kneeLat(s: StepSide) = "knee_lat_${s.key}"
    fun kneeOut2d(s: StepSide) = "knee_out2d_${s.key}"
    const val TORSO_PITCH = "torso_pitch"
    const val HIP_HEIGHT = "hip_height_rel"

    private const val MIN_TORSO = 0.08f          // Arm2d·Lunge2d 와 같다
    private const val MIN_THIGH_CM = 10f
    private const val MIN_FOOT_CM = 3f            // 발목→발끝 수평 길이가 이보다 짧으면(관절 붕괴) 무릎 방향을 재지 않는다 — PostureCore toe_out 과 같다
    private const val MIN_SPAN = 0.2f            // 골반 위치(hipshift)를 재려면 두 발목이 이만큼은 벌어져야 한다(옆모습 제외)
    private const val L_EAR = 7; private const val R_EAR = 8
    private const val L_SH = 11; private const val R_SH = 12
    private const val L_ELBOW = 13; private const val R_ELBOW = 14
    private const val L_WRIST = 15; private const val R_WRIST = 16
    private const val L_HIP = 23; private const val R_HIP = 24
    private const val L_KNEE = 25; private const val R_KNEE = 26
    private const val L_ANKLE = 27; private const val R_ANKLE = 28
    private const val RAD = 180.0 / Math.PI

    /** 화면 롤(°) — `CameraPlacement` 의 roll 과 같은 식(up 의 화면 가로 성분). */
    fun rollDeg(up: Vec3): Float = (atan2(up.x.toDouble(), up.y.toDouble()) * RAD).toFloat()

    fun features(frame: PoseFrame, xy: FloatArray, vis: FloatArray, minVisibility: Float, aspect: Float, rollDeg: Float): Map<String, Float> {
        val out = HashMap<String, Float>()
        // ---- 3D 각도(몸 좌표계: x = 골반축, y = 중력 위)
        for (s in StepSide.entries) {
            val hip = frame.joints[if (s == StepSide.LEFT) Joints.L_HIP else Joints.R_HIP] ?: continue
            val knee = frame.joints[if (s == StepSide.LEFT) Joints.L_KNEE else Joints.R_KNEE] ?: continue
            val hb = frame.body(hip) ?: continue
            val kb = frame.body(knee) ?: continue
            val t = kb - hb
            if (t.norm < MIN_THIGH_CM) continue
            out[thigh(s)] = (acos((t.y / t.norm).coerceIn(-1f, 1f)) * RAD).toFloat()
        }
        val ls = frame.lSh; val rs = frame.rSh
        if (ls != null && rs != null) {
            val a = frame.body(ls); val b = frame.body(rs)
            if (a != null && b != null) {
                val d = a - b
                if (sqrt(d.x * d.x + d.z * d.z) >= 5f) out[REL_YAW] = wrap((atan2(d.z.toDouble(), d.x.toDouble()) * RAD).toFloat())
            }
        }
        for (s in StepSide.entries) {
            val knee = frame.joints[if (s == StepSide.LEFT) Joints.L_KNEE else Joints.R_KNEE] ?: continue
            val ankle = frame.joints[if (s == StepSide.LEFT) Joints.L_ANKLE else Joints.R_ANKLE] ?: continue
            val foot = frame.joints[if (s == StepSide.LEFT) Joints.L_FOOT else Joints.R_FOOT] ?: continue
            val kb = frame.body(knee) ?: continue; val ab = frame.body(ankle) ?: continue; val fb = frame.body(foot) ?: continue
            val fx = fb.x - ab.x; val fz = fb.z - ab.z
            val fl = sqrt(fx * fx + fz * fz); val shin = (kb - ab).norm
            if (fl < MIN_FOOT_CM || shin < MIN_THIGH_CM) continue
            out[kneeFwdFoot(s)] = ((kb.x - ab.x) * fx + (kb.z - ab.z) * fz) / fl / shin
        }
        // ---- 2D(롤 보정, 가로를 aspect 로 등방화)
        if (xy.size < 66 || vis.size < 33) return out
        fun ok(vararg idx: Int): Boolean {
            for (i in idx) if (!(vis[i] >= minVisibility) || !xy[i * 2].isFinite() || !xy[i * 2 + 1].isFinite()) return false
            return true
        }
        if (!ok(L_SH, R_SH, L_HIP, R_HIP)) return out
        val th = Math.toRadians(rollDeg.toDouble()); val c = cos(th).toFloat(); val sn = sin(th).toFloat()
        val cx = aspect / 2f; val cy = 0.5f
        fun x(i: Int): Float { val px = xy[i * 2] * aspect - cx; val py = xy[i * 2 + 1] - cy; return cx + px * c + py * sn }
        fun y(i: Int): Float { val px = xy[i * 2] * aspect - cx; val py = xy[i * 2 + 1] - cy; return cy - px * sn + py * c }
        val shX = (x(L_SH) + x(R_SH)) / 2f; val shY = (y(L_SH) + y(R_SH)) / 2f
        val hipX = (x(L_HIP) + x(R_HIP)) / 2f; val hipY = (y(L_HIP) + y(R_HIP)) / 2f
        val torso = sqrt((shX - hipX) * (shX - hipX) + (shY - hipY) * (shY - hipY))
        if (torso < MIN_TORSO) return out
        val faceDx = (x(L_SH) - x(R_SH)) / torso
        val sign = if (faceDx >= 0f) 1f else -1f
        out[TORSO2D] = torso
        out[FACE_DX] = faceDx
        out[HIP_Y] = hipY
        out["hip_hike_L"] = (y(R_HIP) - y(L_HIP)) / torso
        out["hip_hike_R"] = (y(L_HIP) - y(R_HIP)) / torso
        // 선의 기울기(°): 사용자의 왼쪽이 아래로 기울면 +. 측굴 lat_flex_L = 어깨선이 골반선보다 왼쪽으로 더 기움(왼 옆구리가 접힘), head_lean_L = 귀선이 어깨선보다 더 기움
        fun tilt(l: Int, r: Int): Float = (atan2(((y(l) - y(r)) * sign).toDouble(), abs(x(l) - x(r)).toDouble()) * RAD).toFloat()
        val shTilt = tilt(L_SH, R_SH); val hipTilt = tilt(L_HIP, R_HIP)
        out[latFlex(StepSide.LEFT)] = shTilt - hipTilt; out[latFlex(StepSide.RIGHT)] = hipTilt - shTilt
        if (ok(L_EAR, R_EAR)) {
            val earTilt = tilt(L_EAR, R_EAR)
            out["head_lean_L"] = earTilt - shTilt; out["head_lean_R"] = shTilt - earTilt
        }
        for (s in StepSide.entries) {
            val left = s == StepSide.LEFT
            val knee = if (left) L_KNEE else R_KNEE
            val wrist = if (left) L_WRIST else R_WRIST; val ear = if (left) L_EAR else R_EAR; val elbow = if (left) L_ELBOW else R_ELBOW
            if (ok(wrist, ear)) out["hand_ear_${s.key}"] = dist(x(wrist), y(wrist), x(ear), y(ear)) / torso
            if (ok(elbow, knee)) out[elbowKnee(s)] = dist(x(elbow), y(elbow), x(knee), y(knee)) / torso
            if (ok(knee)) out["knee_out2d_${s.key}"] = (x(knee) - hipX) * (if (left) sign else -sign) / torso
        }
        if (ok(L_ANKLE, R_ANKLE)) {
            val xl = x(L_ANKLE); val xr = x(R_ANKLE); val yl = y(L_ANKLE); val yr = y(R_ANKLE)
            out[CROSS] = (xl - xr) * sign / torso
            out[ankleY(StepSide.LEFT)] = yl; out[ankleY(StepSide.RIGHT)] = yr
            out[lift(StepSide.LEFT)] = (yr - yl) / torso
            out[lift(StepSide.RIGHT)] = (yl - yr) / torso
            val span = xl - xr
            if (abs(span) / torso >= MIN_SPAN) out[HIPSHIFT] = (hipX - xr) / span
        }
        return out
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
    private fun wrap(deg: Float): Float = ((deg + 540f) % 360f) - 180f
}

/**
 * 다리 사이클 추적기 — 다리마다(크로스는 발목 교차 하나) 신호가 서 있는 기준에서 [departFor] 넘게 내려갔다가 [returnBand] 안으로 돌아와 [DWELL_MS] 머물면 사이클을 닫고,
 * 극점의 기하로 "이 종목의 반복인가" 를 판별한다(원칙 #7 — 횟수의 입장 조건이지 자세가 아니다). 통과하면 발표(`RepCycle.side` = 그 다리), 아니면 [rejected] 에 사유.
 * v3(§98)부터 사용자가 "세지 말라" 고 정의한 조건(허리 숙임·편 다리·앞으로 올린 무릎·얕은 깊이)도 입장 조건이다 — 사용자 결정 2026-10-06 밤, 두 모드.
 * 크런치는 **팔꿈치가 무릎에 닿아야** 1회다(§98a): 같은 쪽 팔꿈치–무릎 최솟값 ≤ [CRUNCH_TOUCH_MAX] — 판정 프레임에 `elbow_knee_min` 이 있으면(85 ms 최솟값) 그것을 쓴다.
 * 사이드 런지는 **굽힌 무릎이 발끝을 넘으면** 세지 않는다(§98b, 사용자 결정 2026-10-06 밤 3): 바닥 프레임들(무릎 최소 + 15° 안)의 `knee_fwd_foot` 중앙값 > [SIDE_KNEE_TOE_MAX].
 *
 * - **기준(서 있음)**: 준비 카운트다운([prepare]) 또는 세트 중 정지(0.4 s·3프레임, 각 신호 퍼짐 ≤ 8°)에서 중앙값. **복귀마다 재기준**(체류 프레임 중앙값, 종전과 25° 안일 때) —
 *   발을 옮기며 하는 사람의 선 자세는 매번 조금 다르다.
 * - 관절 누락 프레임은 건너뛴다. [MAX_GAP_MS] 넘게 끊기면 진행 후보만 버린다(기준 유지). [MAX_CYCLE_MS] 안 돌아오면 `timeout`.
 * - 두 다리가 함께 움직여도 각자 판별한다 — 스쿼트(두 무릎)는 사이드 런지의 비대칭 조건이, 점프(두 발)는 니업의 반대 다리 조건이 거른다.
 * - 판별 띠는 2026-10-06 폰 세트(사용자 1명, 설계 §3)에서 잡은 **잠정값**이다. 폰 세션으로 확정한다.
 */
class LegCycleTracker(val profile: LegProfile) {
    private class Leg(val side: StepSide?) {
        var base: Float? = null
        var moving = false
        var startMs = 0L
        var sigMin = Float.POSITIVE_INFINITY
        var sigMax = Float.NEGATIVE_INFINITY
        var extreme: Map<String, Float> = emptyMap()
        var extremeMs = 0L
        var auxMin = Float.POSITIVE_INFINITY      // 사이클 중 보조값 최소(크런치: 같은 쪽 팔꿈치–무릎)
        var hipDropMax = Float.NEGATIVE_INFINITY
        var torsoDep: Float? = null               // 출발 프레임의 몸통 전후 기울기 — "허리를 굽힌 다음 진행" 의 재료
        var torsoMax = Float.NEGATIVE_INFINITY    // 사이클 중 몸통 전후 기울기 최대
        var hipHeightMin = Float.POSITIVE_INFINITY // 사이클 중 골반 높이(÷ 다리 길이, 3D) 최소 — 런지 깊이의 두 번째 재료
        var latFlexMax = Float.NEGATIVE_INFINITY  // 사이클 중 그 쪽 측굴 최대(크런치)
        var kneeMinCycle = Float.POSITIVE_INFINITY // 사이클 중 두 무릎 중 더 굽은 값의 최소(크로스) — 교차 극점 프레임의 무릎은 바닥보다 펴져 있을 수 있다
        val kneeFwd = ArrayList<Pair<Float, Float>>() // (신호, 그 무릎이 발 방향으로 나간 거리 ÷ 정강이) — 바닥 중앙값용(사이드 런지 §98b)
        var returnAt: Long? = null
        val dwell = ArrayList<Float>()
        var named = true
        fun clear() {
            moving = false; sigMin = Float.POSITIVE_INFINITY; sigMax = Float.NEGATIVE_INFINITY; extreme = emptyMap()
            auxMin = Float.POSITIVE_INFINITY; hipDropMax = Float.NEGATIVE_INFINITY; torsoDep = null; torsoMax = Float.NEGATIVE_INFINITY
            hipHeightMin = Float.POSITIVE_INFINITY; latFlexMax = Float.NEGATIVE_INFINITY; kneeMinCycle = Float.POSITIVE_INFINITY; kneeFwd.clear()
            returnAt = null; dwell.clear(); named = true
        }
    }

    private val legs: List<Leg> = if (profile == LegProfile.CROSS) listOf(Leg(null)) else listOf(Leg(StepSide.LEFT), Leg(StepSide.RIGHT))
    private val still = ArrayList<Pair<Long, Map<String, Float>>>()
    private var hipBase: Float? = null
    private var torsoBase: Float? = null
    private var torsoPitchBase: Float? = null     // 서 있을 때 몸통 전후 기울기(°) — 허리 숙임은 이 기준 대비로 본다(사람마다 서 있는 기울기가 다르다)
    private val latFlexBase = HashMap<StepSide, Float>()
    private var lastAt: Long? = null

    val completed = ArrayList<RepCycle>()
    val rejected = ArrayList<RepRejected>()
    var left = 0; private set
    var right = 0; private set
    var unknown = 0; private set
    val pending: Boolean get() = legs.any { it.moving }
    /** 세트 로그 `reps.config.standing` — 세트 처음 잡힌 기준(재기준 전). 재생기가 같은 값을 심는다([restoreStanding]). */
    var standing: Map<String, Float>? = null
        private set
    fun candidate(): RepCandidate? = legs.firstOrNull { it.moving }?.let { RepCandidate(it.startMs, it.sigMin, it.sigMax) }
    val hasBase: Boolean get() = legs.all { it.base != null } && hipBase != null

    fun signalKey(leg: StepSide?): String = if (leg == null) profile.signal else profile.resolve(profile.signal, leg)

    /** 일시정지·카메라 전환·끊김 — 진행 후보만 버린다. 기준·완료 원장은 지킨다. */
    fun resetCycle() { legs.forEach { it.clear() }; still.clear(); lastAt = null }
    fun reset() { resetCycle(); legs.forEach { it.base = null }; hipBase = null; torsoBase = null; torsoPitchBase = null; latFlexBase.clear(); standing = null; completed.clear(); rejected.clear(); left = 0; right = 0; unknown = 0 }

    /** 준비 카운트다운 프레임([atMs] 앞 [PREP_WINDOW_MS])에서 기준을 잡는다(`RepCounter.standingSeedFrom` 입구). 움직이고 있었으면 잡지 않는다. */
    fun prepare(frames: List<Pair<Long, Map<String, Float>>>, atMs: Long) {
        if (hasBase) return
        baseFrom(frames.filter { atMs - it.first in 0..PREP_WINDOW_MS && complete(it.second) })?.let { setBase(it) }
    }

    /** 로그의 기준을 심는다(재생 파리티). */
    fun restoreStanding(m: Map<String, Float>) {
        if (REQUIRED.any { m[it]?.isFinite() != true }) return
        setBase(m)
    }

    /** 기준 대비 값을 더한다(`hip_drop` = 골반 하강 ÷ 몸통). 기준이 없으면 그대로. 앱·재생기 모두 평가기 `onFrame` 앞에서 부른다. */
    fun annotate(features: Map<String, Float>): Map<String, Float> {
        val hb = hipBase ?: return features
        val tb = torsoBase ?: return features
        val hy = features[LegGeometry.HIP_Y] ?: return features
        return features + ("hip_drop" to (hy - hb) / tb)
    }

    fun onFrame(t: Long, input: Map<String, Float>): List<RepCycle> {
        val f = input.filterValues { it.isFinite() }
        if (!complete(f)) return emptyList()
        val last = lastAt
        if (last != null && (t <= last || t - last > MAX_GAP_MS)) { legs.forEach { it.clear() }; still.clear() }
        lastAt = t
        if (!hasBase) {
            still += t to f
            while (still.isNotEmpty() && t - still.first().first > STILL_WINDOW_MS) still.removeAt(0)
            baseFrom(still)?.let { setBase(it); still.clear() }
            return emptyList()
        }
        val events = ArrayList<RepCycle>()
        val hipDrop = (f.getValue(LegGeometry.HIP_Y) - hipBase!!) / torsoBase!!
        for (leg in legs) {
            val key = signalKey(leg.side)
            val x = f[key] ?: continue
            val base = leg.base!!
            if (!leg.moving) {
                if (x <= base - departFor(leg)) { leg.clear(); leg.moving = true; leg.startMs = t; leg.extreme = f; leg.extremeMs = t; leg.sigMin = x; leg.sigMax = base; leg.torsoDep = f[LegGeometry.TORSO_PITCH] }
                else continue
            }
            leg.named = leg.named && f[Lunge2d.NAMES_OK] != 0f
            if (x < leg.sigMin) { leg.sigMin = x; leg.extreme = f; leg.extremeMs = t }
            leg.sigMax = maxOf(leg.sigMax, x)
            leg.hipDropMax = maxOf(leg.hipDropMax, hipDrop)
            f[LegGeometry.TORSO_PITCH]?.let { leg.torsoMax = maxOf(leg.torsoMax, it) }
            f[LegGeometry.HIP_HEIGHT]?.let { leg.hipHeightMin = minOf(leg.hipHeightMin, it) }
            if (profile == LegProfile.CROSS) leg.kneeMinCycle = minOf(leg.kneeMinCycle, minOf(f.getValue("knee_L"), f.getValue("knee_R")))
            if (profile == LegProfile.SIDE && leg.side != null) f[LegGeometry.kneeFwdFoot(leg.side)]?.let { leg.kneeFwd += x to it }
            if (profile == LegProfile.SIDE_CRUNCH && leg.side != null) {
                // 닿음은 85 ms 최솟값(`elbow_knee_min`, 앱 ContactMinimum)이 있으면 그것 — 300 ms 표본은 순간 접촉을 놓친다(§98a). 재생기 .cap 경로는 300 ms 값으로 후퇴
                (f[LegGeometry.elbowKneeMin(leg.side)] ?: f[LegGeometry.elbowKnee(leg.side)])?.let { leg.auxMin = minOf(leg.auxMin, it) }
                f[LegGeometry.latFlex(leg.side)]?.let { leg.latFlexMax = maxOf(leg.latFlexMax, it) }
            }
            if (t - leg.startMs > MAX_CYCLE_MS) { rejected += RepRejected(t, leg.sigMin, leg.sigMax, Float.NaN, "timeout"); leg.clear(); continue }
            val back = if (profile == LegProfile.CROSS) x >= CROSS_RETURN && (f["knee_L"] ?: 0f) >= CROSS_RETURN_KNEE && (f["knee_R"] ?: 0f) >= CROSS_RETURN_KNEE
                else x >= base - RETURN_BAND
            if (!back) { leg.returnAt = null; leg.dwell.clear(); continue }
            if (leg.returnAt == null) leg.returnAt = t
            leg.dwell += x
            if (t - leg.returnAt!! < DWELL_MS || leg.dwell.size < DWELL_FRAMES || t - leg.startMs < MIN_CYCLE_MS) continue
            // 복귀 확정 — 판별 → 발표/기각, 재기준
            val (side, reason) = identity(leg, f)
            if (reason != null) rejected += RepRejected(t, leg.sigMin, leg.sigMax, Float.NaN, reason)
            else {
                val c = RepCycle(t, leg.startMs, leg.sigMin, leg.sigMax, side = side.takeIf { leg.named })
                completed += c; events += c
                when (c.side) { StepSide.LEFT -> left++; StepSide.RIGHT -> right++; null -> unknown++ }
            }
            if (profile != LegProfile.CROSS) { val nb = median(leg.dwell); if (abs(nb - base) <= REBASE_MAX) leg.base = nb }
            hipBase = f.getValue(LegGeometry.HIP_Y)
            leg.clear()
        }
        return events
    }

    private fun departFor(leg: Leg): Float = when (profile) {
        LegProfile.SIDE -> KNEE_DEPART
        LegProfile.CROSS -> leg.base!! - CROSS_DEPART       // base − depart = CROSS_DEPART 절대값
        else -> THIGH_DEPART
    }

    /**
     * 판별 — (쪽, 기각 사유). 사유 null = 통과. 순서 = 사유의 우선순위(첫 사유만 말한다): 사이드 런지는 깊이 → 비대칭·발·방향 → 허리(얕은 회는 비대칭도 작아 스쿼트로 잘못 말한다),
     * 크로스는 교차 → 깊이 → 허리, 니업·크런치는 편 다리 → (옆으로·옆구리) → 높이 → 들림 → 반대 다리 → 허리.
     * 띠는 설계 §3(2026-10-06 폰 1·2차 세트)의 잠정값. 재료가 없는 조건(골반 높이·몸통 기울기·측굴·무릎 바깥 위치)은 통과한다 — 못 본 것으로 세지 않는 쪽으로 틀리지 않는다(원칙 #1 은 판정에, 횟수는 사용자 정의에 따른다).
     */
    private fun identity(leg: Leg, now: Map<String, Float>): Pair<StepSide?, String?> {
        val p = leg.extreme
        return when (profile) {
            LegProfile.SIDE -> {
                val s = leg.side!!
                val other = p[LegGeometry.knee(s.other)]
                val shift = p[LegGeometry.HIPSHIFT]
                val toward = if (s == StepSide.LEFT) shift else shift?.let { 1f - it }
                val reason = when {
                    leg.sigMin > SIDE_KNEE_MAX || (leg.hipHeightMin.isFinite() && leg.hipHeightMin > SIDE_HIP_HEIGHT_MAX) -> "shallow"
                    other != null && other - leg.sigMin < SIDE_ASYM_MIN -> "squat_like"
                    (p[LegGeometry.lift(s)] ?: 0f) > LUNGE_LIFT_MAX -> "foot_lifted"
                    toward == null -> "no_direction"
                    toward < SIDE_SHIFT_MIN -> "no_shift"
                    bottomMedian(leg) > SIDE_KNEE_TOE_MAX -> "knee_over_toe"
                    torsoBent(leg, LUNGE_TORSO_DEP, LUNGE_TORSO_MAX, LUNGE_TORSO_ABS) -> "torso_bent"
                    else -> null
                }
                s to reason
            }
            LegProfile.CROSS -> {
                val kneeMin = if (leg.kneeMinCycle.isFinite()) leg.kneeMinCycle else minOf(p["knee_L"] ?: 180f, p["knee_R"] ?: 180f)
                val yl = p[LegGeometry.ankleY(StepSide.LEFT)]; val yr = p[LegGeometry.ankleY(StepSide.RIGHT)]; val tb = torsoBase ?: 1f
                // 뒷발(화면에서 더 위 = 더 멀다)이 움직인 다리
                val side = if (yl != null && yr != null) { if (yl < yr - BACK_FOOT_MIN * tb) StepSide.LEFT else if (yr < yl - BACK_FOOT_MIN * tb) StepSide.RIGHT else null } else null
                val reason = when {
                    leg.sigMin > CROSS_MIN -> "no_cross"
                    kneeMin > CROSS_NO_DESCENT_KNEE && leg.hipDropMax < CROSS_HIP_DROP -> "no_descent"
                    kneeMin > CROSS_KNEE_MAX || (leg.hipHeightMin.isFinite() && leg.hipHeightMin > CROSS_HIP_HEIGHT_MAX) -> "shallow"
                    torsoBent(leg, LUNGE_TORSO_DEP, LUNGE_TORSO_MAX, LUNGE_TORSO_ABS) -> "torso_bent"
                    else -> null
                }
                side to reason
            }
            LegProfile.KNEE_UP -> {
                val s = leg.side!!
                // 편 다리를 앞으로 차는 회는 허벅지가 얕게 읽히기도 해서(폰 실측 136~143°) 높이보다 먼저 묻는다 — "골반 높이까지" 만 들으면 왜 안 세는지 모른다
                val reason = when {
                    (p[LegGeometry.knee(s)] ?: 0f) > KNEE_BENT_MAX -> "knee_straight"
                    leg.sigMin > KNEE_UP_THIGH_MAX -> "shallow"
                    (p[LegGeometry.lift(s)] ?: 0f) < KNEE_UP_LIFT_MIN -> "no_lift"
                    (p[LegGeometry.thigh(s.other)] ?: 180f) < OTHER_THIGH_MIN -> "both_legs"
                    torsoBent(leg, UPRIGHT_TORSO_MAX, UPRIGHT_TORSO_MAX, UPRIGHT_TORSO_ABS) -> "torso_bent"
                    else -> null
                }
                s to reason
            }
            LegProfile.SIDE_CRUNCH -> {
                val s = leg.side!!
                // 순서: 다리의 정체(편 다리·앞으로 올림) → 옆구리 수축 → 높이 → 허리. 다리만 올린 회는 대개 얕기도 해서 높이를 먼저 물으면 "더 높이" 만 듣고 정작 안 접은 옆구리를 모른다(폰 1차 세트 8/12)
                val kneeLat = p[LegGeometry.kneeLat(s)]; val kneeOut = p[LegGeometry.kneeOut2d(s)]
                val forward = if (kneeLat != null) kneeLat < CRUNCH_ABDUCT_MIN else if (kneeOut != null) kneeOut < CRUNCH_ABDUCT2D_MIN else false
                // 닿음이 정의다(사용자 결정 2026-10-06 밤 2) — 측굴은 판별에 넣지 않는다(닿았는데 측굴이 작다고 안 세면 정의와 어긋난다). 측굴은 beta 검사 '옆구리 접힘'
                val reason = when {
                    (p[LegGeometry.knee(s)] ?: 0f) > KNEE_BENT_MAX -> "knee_straight"
                    forward -> "no_abduct"
                    !leg.auxMin.isFinite() -> "no_elbow"
                    leg.auxMin > CRUNCH_TOUCH_MAX -> "no_crunch"
                    leg.sigMin > CRUNCH_THIGH_MAX -> "shallow"
                    (p[LegGeometry.lift(s)] ?: 0f) < CRUNCH_LIFT_MIN -> "no_lift"
                    (p[LegGeometry.thigh(s.other)] ?: 180f) < OTHER_THIGH_MIN -> "both_legs"
                    torsoBent(leg, UPRIGHT_TORSO_MAX, UPRIGHT_TORSO_MAX, UPRIGHT_TORSO_ABS) -> "torso_bent"
                    else -> null
                }
                s to reason
            }
        }
    }

    /** 바닥(신호 최소 + [BOTTOM_BAND] 안) 프레임들의 무릎 발 방향 진행 중앙값 — 한 프레임의 z 흔들림에 휘둘리지 않게. 재료가 없으면 −∞(통과). */
    private fun bottomMedian(leg: Leg): Float {
        val vs = leg.kneeFwd.filter { it.first <= leg.sigMin + BOTTOM_BAND }.map { it.second }
        return if (vs.isEmpty()) Float.NEGATIVE_INFINITY else median(vs)
    }

    /**
     * 허리 숙임 — 서 있는 기준 대비 몸통 전후 기울기가 출발 때 [dep] 넘거나(굽힌 다음 진행) 사이클 중 [max] 넘거나, 기준과 무관하게 [abs] 를 넘으면(굽힌 채로 서서 기준을 잡은 경우).
     * 기준(`torso_pitch`)이 없으면 묻지 않는다.
     */
    private fun torsoBent(leg: Leg, dep: Float, max: Float, abs: Float): Boolean {
        val b = torsoPitchBase ?: return false
        if (leg.torsoMax.isFinite() && (leg.torsoMax - b > max || leg.torsoMax > abs)) return true
        val d = leg.torsoDep ?: return false
        return d - b > dep
    }

    // ---- 기준
    private fun complete(f: Map<String, Float>): Boolean = REQUIRED.all { f[it]?.isFinite() == true }

    private fun baseFrom(frames: List<Pair<Long, Map<String, Float>>>): Map<String, Float>? {
        if (frames.size < STILL_MIN_FRAMES || frames.last().first - frames.first().first < STILL_SPAN_MS) return null
        val out = HashMap<String, Float>()
        for (k in REQUIRED) {
            val vs = frames.map { it.second.getValue(k) }
            val tol = if (k == LegGeometry.CROSS) STILL_TOL_CROSS else if (k == LegGeometry.HIP_Y || k == LegGeometry.TORSO2D) STILL_TOL_IMG else STILL_TOL_DEG
            if (vs.max() - vs.min() > tol) return null
            out[k] = median(vs)
        }
        // 선택 키(허리·측굴 기준): 정지 판정에는 안 들고, 모든 프레임에 있을 때만 중앙값을 기준에 넣는다
        for (k in OPTIONAL) {
            val vs = frames.mapNotNull { it.second[k]?.takeIf { v -> v.isFinite() } }
            if (vs.size == frames.size) out[k] = median(vs)
        }
        return out
    }

    private fun setBase(m: Map<String, Float>) {
        for (leg in legs) leg.base = m.getValue(signalKey(leg.side))
        hipBase = m.getValue(LegGeometry.HIP_Y); torsoBase = m.getValue(LegGeometry.TORSO2D)
        torsoPitchBase = m[LegGeometry.TORSO_PITCH]
        for (sd in StepSide.entries) m[LegGeometry.latFlex(sd)]?.let { latFlexBase[sd] = it }
        if (standing == null) standing = (REQUIRED + OPTIONAL.filter { m.containsKey(it) }).associateWith { m.getValue(it) }
    }

    private fun median(xs: List<Float>): Float { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f }

    companion object {
        const val VERSION = "legcycle_v3_beta"

        /**
         * 판별 기각의 이유(음성) — 횟수의 입장 조건이라 두 모드 모두 말한다("세지 않았어요" 의 이유, 침묵하면 카운트가 죽은 줄 안다). null = 말하지 않는다(낮은 틱만).
         * 자세 코칭이 아니라 "무엇이 있어야 세는가" 를 말한다 — 폰 1차 세트(2026-10-06)에서 사용자가 지적한 '얕은 니업'·'다리만 올린 크런치' 가 세진 일의 답이다.
         */
        fun cueFor(profile: LegProfile, reason: String?): String? = when (reason) {
            "shallow" -> when (profile) {
                LegProfile.SIDE -> "더 깊게 앉아야 세요."
                LegProfile.KNEE_UP -> "무릎을 골반 높이까지 올려야 세요."
                LegProfile.SIDE_CRUNCH -> "무릎을 더 높이 옆으로 올려야 세요."
                LegProfile.CROSS -> "더 깊게 내려가야 세요."
            }
            "torso_bent" -> when (profile) {
                LegProfile.SIDE, LegProfile.CROSS -> "허리를 숙이면 세지 않아요. 가슴을 들고 상체를 세워 주세요."
                LegProfile.KNEE_UP -> "허리를 숙이면 세지 않아요. 가슴을 펴고 몸통을 세운 채 올려 주세요."
                LegProfile.SIDE_CRUNCH -> "앞으로 숙이면 세지 않아요. 몸통을 세운 채 옆구리만 접어 주세요."
            }
            "knee_straight" -> when (profile) {
                LegProfile.KNEE_UP -> "다리를 뻗으면 세지 않아요. 무릎을 접은 채 올려 주세요."
                LegProfile.SIDE_CRUNCH -> "다리를 뻗으면 세지 않아요. 무릎을 굽힌 채 옆으로 올려 주세요."
                else -> null
            }
            "no_abduct" -> "앞으로 올리면 세지 않아요. 무릎을 옆으로 올려 주세요."
            "knee_over_toe" -> "무릎이 발끝을 넘으면 세지 않아요. 엉덩이를 뒤로 보내 무릎을 발끝 뒤에 두세요."
            "no_lift" -> when (profile) {
                LegProfile.KNEE_UP -> "발이 바닥에서 더 떠야 세요. 무릎을 골반 높이까지 올려 주세요."
                LegProfile.SIDE_CRUNCH -> "발이 바닥에서 더 떠야 세요. 무릎을 더 높이 옆으로 올려 주세요."
                else -> null
            }
            "no_crunch" -> "다리만 올리면 세지 않아요. 옆구리를 접어 팔꿈치가 무릎에 닿아야 세요."
            "no_cross" -> "발이 교차해야 세요."
            "no_descent" -> "교차한 뒤 무릎을 굽혀 내려가야 세요."
            "squat_like" -> "한쪽 무릎만 굽혀야 세요."
            "no_shift" -> "몸을 굽힌 다리 쪽으로 옮겨야 세요."
            else -> null
        }
        /** 추적기가 프레임마다 요구하는 키 — 하나라도 없으면 그 프레임은 건너뛴다. 기준 맵(`reps.config.standing`)의 키이기도 하다. */
        val REQUIRED = listOf("knee_L", "knee_R", "thigh_L", "thigh_R", LegGeometry.CROSS, LegGeometry.HIP_Y, LegGeometry.TORSO2D)
        /** 기준에 함께 담는 선택 키 — 허리 숙임(몸통 전후 기울기)·측굴의 서 있는 값. 없으면 그 조건은 묻지 않는다. */
        val OPTIONAL = listOf(LegGeometry.TORSO_PITCH, "lat_flex_L", "lat_flex_R")
        const val KNEE_DEPART = 30f
        const val THIGH_DEPART = 25f
        const val RETURN_BAND = 15f
        const val REBASE_MAX = 25f
        const val CROSS_DEPART = -0.15f
        const val CROSS_RETURN = 0.05f
        const val CROSS_RETURN_KNEE = 145f
        const val DWELL_MS = 150L
        const val DWELL_FRAMES = 2
        const val MIN_CYCLE_MS = 400L
        const val MAX_CYCLE_MS = 10_000L
        const val MAX_GAP_MS = 750L
        const val STILL_WINDOW_MS = 1_000L
        const val STILL_SPAN_MS = 400L
        const val STILL_MIN_FRAMES = 3
        /** 정지 판정의 각 퍼짐(°) — 8° 로는 세트 첫 1 s 에 몸을 고쳐 서는 사람(폰 사이드 런지 무릎 161→152)의 기준이 안 잡혀 첫 회를 놓쳤다. 준비 카운트다운이 있으면 그쪽이 먼저다. */
        const val STILL_TOL_DEG = 12f
        const val STILL_TOL_CROSS = 0.12f
        const val STILL_TOL_IMG = 0.03f
        const val PREP_WINDOW_MS = 1_600L
        // 판별 띠(잠정, 설계 §3 — 2026-10-06 폰 1·2차 세트, 사용자 1명)
        const val SIDE_KNEE_MAX = 95f           // 굽힌 무릎 ≤ 95°(허벅지 수평 ≈ 90). 2차 세트: 제대로 60~87, 사용자가 '얕다' 한 회 98~100, 아주 얕은 회 120~124 — 115 는 98~100 을 지나쳤다
        const val SIDE_HIP_HEIGHT_MAX = 0.82f   // 골반 높이 ÷ 다리 길이(3D, 선 자세 0.98) ≤ 0.82 — 2차 세트 제대로 0.68~0.80, 얕은 회 0.83~0.87
        const val SIDE_ASYM_MIN = 30f           // 반대 무릎 − 굽힌 무릎 ≥ 30°(실측 50~90; 스쿼트는 0~10)
        /**
         * 무릎 발끝 넘김(§98b): 굽힌 무릎이 발 방향(발목→발끝 수평)으로 나간 거리 ÷ 정강이 의 **바닥 중앙값** ≤ 0.6 — 발끝은 발목에서 발 길이(≈ 0.6 정강이) 앞이라 그 너머가 "넘음".
         * 발끝 관절 자체의 z 는 발 길이를 반으로 읽어(2차 세트 0.17~0.30 정강이) 쓰지 않고 발 방향만 쓴다. 2차 세트 깊은 회의 바닥 중앙값 0.46~0.70(매끄럽게 커졌다 작아진다 — 흔들림이 아니라 진행),
         * 세진 14회 중 10회가 0.6 을 넘는다(라벨 없음) — 이 사용자가 정말 넘기는지, 3D z 의 편향인지는 3차 세트 '무릎발끝넘김' 블록으로 확정.
         */
        const val SIDE_KNEE_TOE_MAX = 0.6f
        const val BOTTOM_BAND = 15f             // 바닥 프레임 = 신호 최소 + 15° 안(2차 세트 회당 2~4프레임)
        const val SIDE_SHIFT_MIN = 0.58f        // 골반이 굽힌 발 쪽으로(실측 0.69~0.86)
        const val LUNGE_LIFT_MAX = 0.45f        // 런지의 발은 바닥에(니업은 1.0~1.8)
        const val CROSS_MIN = -0.2f             // 발목 교차(실측 바닥 −0.4~−0.7)
        const val CROSS_NO_DESCENT_KNEE = 135f  // 교차만 하고 안 내려감(두 무릎 모두 > 135 이고 골반 하강 < 0.25)
        const val CROSS_KNEE_MAX = 110f         // 더 굽은 무릎 ≤ 110° — 2차 세트: 깊은 회 89~100, 얕은 회 107~117(모두 오른발 뒤), 서성임 140~160
        const val CROSS_HIP_HEIGHT_MAX = 0.80f  // 골반 높이 ÷ 다리 길이 ≤ 0.80 — 2차 세트 깊은 회 0.72~0.77, 얕은 회 0.80~0.82, 서성임 0.90~0.96
        const val CROSS_HIP_DROP = 0.25f        // 골반 하강 ÷ 몸통(실측 0.4~0.6)
        const val BACK_FOOT_MIN = 0.15f         // 뒷발 = 반대 발목보다 이만큼 위(실측 0.3~0.4)
        const val KNEE_UP_THIGH_MAX = 110f      // 허벅지 ≤ 110°(수평 90°; 실측 전체 45~99, 얕게 125~138) — 사용자 결정: 얕게는 세지 않는다
        const val KNEE_UP_LIFT_MIN = 0.6f
        const val KNEE_BENT_MAX = 110f          // 올린 다리의 무릎(허벅지 극점에서) ≤ 110° = 접은 채 올림. 2차 세트: 니업 46~92, 크런치 수축 51~72, 편 다리 킥 125~170
        const val OTHER_THIGH_MIN = 150f
        const val CRUNCH_THIGH_MAX = 115f       // 실측 수축 39~80, 다리만 106~129
        const val CRUNCH_LIFT_MIN = 0.5f
        const val CRUNCH_ABDUCT_MIN = 0.35f     // 무릎의 골반 대비 바깥쪽 위치(`knee_lat`, 골반 너비 단위) ≥ 0.35 = 옆으로 올림. 2차 세트: 옆 0.46~0.86, 앞으로 올림 −0.08~0.22, 편 다리 옆 킥 0.31
        const val CRUNCH_ABDUCT2D_MIN = 0.45f   // 3D 가 없을 때 2D 무릎 바깥 위치(`knee_out2d`, 몸통 단위): 옆 0.55~0.79, 앞 0.16~0.41
        /**
         * 닿음(§98a): 같은 쪽 팔꿈치–무릎 최솟값 ÷ 몸통 ≤ 0.30. 관절 중심끼리의 거리라 닿아도 0 이 아니다 — 팔꿈치·무릎 반지름 합 6~10 cm ≈ 0.12~0.2 몸통(몸통 45~50 cm),
         * 여기에 랜드마크 흔들림과 표본 사이의 놓침 여유. 2차 세트(300 ms 표본): 닿은 회 0.06~0.23, 옆으로 올리기만 0.77, 앞 0.80~0.98, 편 다리 높은 킥 0.49~0.66.
         */
        const val CRUNCH_TOUCH_MAX = 0.30f
        // 허리 숙임(몸통 전후 기울기 `torso_pitch`, 서 있는 기준 대비). 니업·크런치는 세운 채 하는 종목(2차 세트 정상 ≤ 8°/10°, 숙임 15~36), 런지는 힙 힌지가 있어 넓게(사이드 런지 정상 7~35, 숙임 40~58; 굽힌 다음 진행한 회는 출발에 이미 32)
        const val UPRIGHT_TORSO_MAX = 10f
        const val UPRIGHT_TORSO_ABS = 25f
        const val LUNGE_TORSO_DEP = 22f
        const val LUNGE_TORSO_MAX = 32f
        const val LUNGE_TORSO_ABS = 45f
    }
}
