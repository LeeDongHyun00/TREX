package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max

/**
 * 바닥 운동 2D 평면 피처 (spec §25, research/aihub_fitness/export_floor_rules.py 와 동일 정의).
 *
 * 바닥 종목은 3D(world landmark)와 중력축이 신뢰를 잃으므로, 이미지 2D 좌표만으로
 * 신체 내재 피처를 계산한다. 핵심 규약 두 가지:
 *  1) **부호 정준화**: 직선 대비 수직 이탈의 법선을 '화면 위쪽 = 양수'로 고정 →
 *     사용자가 왼쪽/오른쪽 어느 방향으로 누워도(좌우 반전) 값이 불변.
 *  2) **스트리밍 접지선**: 지금까지 본 프레임에서 이동량이 작은 접지점 쌍
 *     (골반↔발목 vs 손목↔발목)의 prefix 중앙값을 지면으로 삼는다. 지면 검출 모델 불필요.
 *
 * 연산은 전부 Double 로 하고(연구 float64 와 패리티, floor_port_fixture 로 검증) 결과만 Float.
 * 규칙은 assets/posture/rules_floor_v0.json — 전부 beta(임계값 미보정: AIHub 에 바닥 높이
 * 카메라가 없어 세트 로그 재보정 전까지 참고용).
 */
const val FLOOR_RULES_ASSET = "posture/rules_floor_v0.json"

/**
 * 피처별 가시성 컷 (spec §25a): 바닥 자세의 1차 실패는 몸통에 가려진 팔꿈치·손목·무릎이
 * '검출은 되는데 좌표가 틀리는' 것이다(관절 오차 0.17~0.22, 전체 3.0×). 요구 관절의 가시성이
 * 이 값 미만이면 그 피처만 프레임에서 유보한다 → 세트에서 측정 프레임이 부족한 규칙은 자연히
 * ABSTAIN 이 된다. 값은 휴리스틱(MP visibility 는 보정된 확률이 아님) — 세트 로그로 조정 대상.
 */
private const val FEATURE_VIS_CUT = 0.35f

private fun visOk(vis: FloatArray?, vararg ids: Int): Boolean =
    vis == null || ids.all { vis[it] >= FEATURE_VIS_CUT }   // null = 연구 픽스처 경로(게이트 없음)

/** MediaPipe 33점 인덱스 (바닥 피처에 쓰는 것만). */
private object M {
    const val NOSE = 0
    const val L_EAR = 7; const val R_EAR = 8
    const val L_SH = 11; const val R_SH = 12
    const val L_EL = 13; const val R_EL = 14
    const val L_WR = 15; const val R_WR = 16
    const val L_HIP = 23; const val R_HIP = 24
    const val L_KNEE = 25; const val R_KNEE = 26
    const val L_ANK = 27; const val R_ANK = 28
}

class FloorFeatureExtractor {

    // 접지선 스트리밍 상태 (세트 단위 — 세트 시작 시 reset)
    private val histHip = ArrayList<DoubleArray>()
    private val histAnk = ArrayList<DoubleArray>()
    private val histWr = ArrayList<DoubleArray>()
    private var moveHipAnkle = 0.0
    private var moveWristAnkle = 0.0

    val frameCount: Int get() = histHip.size

    fun reset() {
        histHip.clear(); histAnk.clear(); histWr.clear()
        moveHipAnkle = 0.0
        moveWristAnkle = 0.0
    }

    /**
     * 한 프레임의 바닥 피처. 좌표는 정규화(0..1) × 이미지 크기 = px.
     * @param vis 33점 가시성. 핵심 관절(어깨·골반·발목)이 안 보이면 빈 맵(프레임 스킵, 접지선도 안 갱신).
     */
    fun compute(normalizedXy: FloatArray, vis: FloatArray?, imageWidth: Int, imageHeight: Int): Map<String, Float> {
        if (normalizedXy.size < MP_LANDMARK_COUNT * 2 || imageWidth <= 0 || imageHeight <= 0) return emptyMap()
        // 코어 = 어깨·골반뿐. 이 둘은 몸통 길이(torso) 정규화와 신체 주축에 쓰여 **모든** 피처의 전제다.
        // 발목은 코어에서 뺐다(§25b): 실측에서 발목이 프레임 밖으로 잘려 푸시업 세트의 85% 프레임이
        // 버려졌는데, 그 세트의 규칙 3개는 발목을 쓰지도 않았다. 발목이 필요한 피처만 아래에서 개별 유보한다.
        if (vis != null) {
            val core = intArrayOf(M.L_SH, M.R_SH, M.L_HIP, M.R_HIP)
            if (core.any { vis[it] < 0.2f }) return emptyMap()
        }
        val w = imageWidth.toDouble()
        val h = imageHeight.toDouble()
        fun x(i: Int) = normalizedXy[i * 2].toDouble() * w
        fun y(i: Int) = normalizedXy[i * 2 + 1].toDouble() * h
        fun pt(i: Int) = doubleArrayOf(x(i), y(i))
        fun mid(a: Int, b: Int) = doubleArrayOf((x(a) + x(b)) / 2, (y(a) + y(b)) / 2)

        val sh = mid(M.L_SH, M.R_SH)
        val hp = mid(M.L_HIP, M.R_HIP)
        val kn = mid(M.L_KNEE, M.R_KNEE)
        val an = mid(M.L_ANK, M.R_ANK)
        val wr = mid(M.L_WR, M.R_WR)
        val el = mid(M.L_EL, M.R_EL)
        val ear = mid(M.L_EAR, M.R_EAR)
        val nose = pt(M.NOSE)
        val torso = max(hypot(sh[0] - hp[0], sh[1] - hp[1]), 1e-6)

        // 가림 게이트: 코어(어깨·골반·발목)는 위에서 프레임 전체를 거르고, 그 외 관절은 피처 단위로 유보
        val vHead = visOk(vis, M.NOSE, M.L_EAR, M.R_EAR)
        val vKnee = visOk(vis, M.L_KNEE, M.R_KNEE)
        val vWrist = visOk(vis, M.L_WR, M.R_WR)
        val vElbow = visOk(vis, M.L_EL, M.R_EL)
        val vArmL = visOk(vis, M.L_SH, M.L_EL, M.L_WR)
        val vAnkle = visOk(vis, M.L_ANK, M.R_ANK)
        // 접지선은 발목을 기준점으로 쓰므로, 발목이 안 보이면 ground 계열 전체가 유보된다
        val vGround = vAnkle

        val f = HashMap<String, Float>(24)
        fun put(name: String, v: Double, ok: Boolean = true) {
            if (ok && v.isFinite()) f[name] = v.toFloat()
        }

        put("hip_dev_ankle", devUp(hp, sh, an), vAnkle)
        put("hip_dev_knee", devUp(hp, sh, kn), vKnee)
        put("knee_dev", devUp(kn, hp, an), vKnee && vAnkle)
        put("shoulder_dev", devUp(sh, hp, wr), vWrist)
        val ls = pt(M.L_SH); val le = pt(M.L_EL); val lw = pt(M.L_WR)
        put("elbow_ang", ang(ls, le, lw), vArmL)
        put("knee_ang", ang(hp, kn, an), vKnee && vAnkle)
        put("hip_ang", ang(sh, hp, kn), vKnee)
        put("trunk_ankle_ang", ang(sh, hp, an), vAnkle)
        put("head_trunk_ang", ang(nose, ear, hp), vHead)
        put("shoulder_arm_ang", ang(hp, sh, el), vElbow)
        put("hand_shoulder_off", devUp(wr, sh, hp), vWrist)
        put("wrist_shoulder_d", hypot(wr[0] - sh[0], wr[1] - sh[1]) / torso, vWrist)
        put("knee_shoulder_d", hypot(kn[0] - sh[0], kn[1] - sh[1]) / torso, vKnee)
        put("ankle_hip_d", hypot(an[0] - hp[0], an[1] - hp[1]) / torso, vAnkle)
        put("elbow_width", abs(devUp(le, ls, lw)), vArmL)
        val lk = pt(M.L_KNEE); val rk = pt(M.R_KNEE)
        val la = pt(M.L_ANK); val ra = pt(M.R_ANK)
        put("knee_gap2d", hypot(lk[0] - rk[0], lk[1] - rk[1]) / torso, vKnee)
        put("ankle_gap2d", hypot(la[0] - ra[0], la[1] - ra[1]) / torso, vAnkle)
        val rs = pt(M.R_SH)
        put("shoulder_asym2d", devUp(ls, rs, hp))

        // 접지선: 이력 갱신 → 이동량 작은 쌍의 prefix 중앙값
        if (histHip.isNotEmpty()) {
            val ph = histHip.last(); val pa = histAnk.last(); val pw = histWr.last()
            val ankMove = hypot(an[0] - pa[0], an[1] - pa[1])
            moveHipAnkle += hypot(hp[0] - ph[0], hp[1] - ph[1]) + ankMove
            moveWristAnkle += hypot(wr[0] - pw[0], wr[1] - pw[1]) + ankMove
        }
        histHip.add(hp); histAnk.add(an); histWr.add(wr)
        val groundA = median2(if (moveHipAnkle <= moveWristAnkle) histHip else histWr)
        val groundB = median2(histAnk)
        put("shoulder_ground", devUp(sh, groundA, groundB), vGround)
        put("hip_ground", devUp(hp, groundA, groundB), vGround)
        put("knee_ground", devUp(kn, groundA, groundB), vKnee && vGround)
        put("ankle_ground", devUp(an, groundA, groundB), vGround)
        put("head_ground", devUp(ear, groundA, groundB), vHead && vGround)
        return f
    }

    companion object {
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

        /** 성분별 중앙값 (numpy median 과 동일: 짝수면 가운데 둘의 평균). */
        fun median2(pts: List<DoubleArray>): DoubleArray {
            fun med(sel: (DoubleArray) -> Double): Double {
                val v = pts.map(sel).sorted()
                val n = v.size
                return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2
            }
            return doubleArrayOf(med { it[0] }, med { it[1] })
        }
    }
}

/** 바닥 종목용: 같은 샘플에 features 만 바닥 2D 피처로 바꾼 사본 (세트 로그·집계에 그대로 흘림). */
fun PoseSample.withFeatures(newFeatures: Map<String, Float>): PoseSample = PoseSample(
    detected = detected,
    normalizedXy = normalizedXy,
    visibility = visibility,
    features = newFeatures,
    visibleJointCount = visibleJointCount,
    inferMs = inferMs,
    imageWidth = imageWidth,
    imageHeight = imageHeight,
    up = up,
    upFromGravity = upFromGravity,
    upFlipped = upFlipped,
    upVerified = upVerified,
)
