package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 촬영 뷰 추정 — 카메라가 몸의 어느 쪽에 있는지를 MediaPipe 월드 랜드마크만으로 (spec §33, `research/aihub_fitness/view_estimator.py`).
 *
 * 왜 필요한가: 규칙의 `view_best_front`·`mirror_safe`·"valgus 는 정면에서만" 주의는 전부 **사용자가 안내대로 폰을 놓았다는 가정**이다.
 * 이 추정기 전에는 그 가정을 확인하는 코드가 없어서 폰이 뒤·옆·반대편에 있어도 규칙이 그대로 점수에 들어갔다(§32 감사).
 *
 * 원리: MediaPipe 월드 좌표는 **카메라 정렬** 좌표계다(x 오른쪽, y 위, z 카메라 쪽 — `PostureAnalyzer` 의 y/z 뒤집기 뒤). 그래서
 * 어깨선·골반선의 x–z 평면 방향이 곧 몸의 요(yaw)다.
 *   s = RShoulder − LShoulder, h = RHip − LHip (x, z 성분)
 *   u = unit(−s.x, s.z) + unit(−h.x, h.z)   (한쪽이 없으면 나머지만)
 *   yaw = atan2(u.z, u.x)  → 정면 0°, 후방 ±180°, 부호가 좌/우
 * 프레임마다 cos/sin 을 피처로 내고(`view_cos`/`view_sin`), 창 평균의 atan2 로 세트 요를 잡는다(원형 평균).
 * 결과 벡터 길이 R 이 프레임 간 일관성이다 — MediaPipe 가 좌우를 뒤집었다 말았다 하는 클립은 R 이 낮아 UNKNOWN 이 된다.
 *
 * 검증 (AIHub 서서 종목 8,042 (클립,뷰), 정답 = GT 2D 어깨 순서로 보정한 실제 방향): UNKNOWN 4.7% 제외 등급 정확도 0.965,
 * 계열(정면/전방 사선 좌·우/후방) 0.968, 전/후 반구 0.982. **순수 측면 뷰는 데이터에 없어 SIDE 등급은 미검증** — 그래서 어떤 규칙의
 * `views_ok` 에도 들어가지 않고(=유보), 파리티 픽스처(`view_fixture.txt`)로만 정의를 고정한다.
 * 한계: MediaPipe 는 ±40° 를 약 ±33° 로 압축해 읽는다(깊이 축 과소추정). 벤치에 눕는 종목·로잉머신은 기하가 달라 무의미하다(앱 미노출).
 */
object ViewEstimator {
    const val FEAT_COS = "view_cos"
    const val FEAT_SIN = "view_sin"

    // outputs/view_thresholds.json (§33) — 명목 방향과 일치하는 서서 종목 클립의 뷰별 분포에서 유도. 바꾸면 view_fixture.txt 도 재생성.
    const val B_SIGN = 1f                 // yaw 가 양수면 B 쪽 (B · SIDE_L · A)
    const val FRONT_MAX_DEG = 16.4f       // |yaw| ≤ → C (정면)
    const val OBLIQUE_MAX_DEG = 46.2f     // |yaw| ≤ → B/D (전방 사선; MP 실측 중앙값 ±33°)
    const val REAR_MIN_DEG = 81.7f        // 그 사이 → SIDE (미검증)
    const val REAR_PURE_MIN_DEG = 163.6f  // |yaw| < → A/E (후방 사선), 그 이상 → R (순수 후방)
    const val MIN_R = 0.7f                // 원형 평균 결과 벡터 길이가 이보다 작으면 UNKNOWN
    const val MIN_FRAMES = 8              // 이보다 적으면 추정하지 않는다 (= 게이팅 없음)

    /**
     * 좌우는 **사용자 기준**이다. yaw > 0 은 오른어깨가 카메라에 더 가깝다는 뜻(z 가 카메라 쪽)이고 AIHub 코드 B 가 그쪽이다 —
     * 데이터셋의 "전방사선L" 은 카메라 쪽에서 본 왼쪽 = 사용자의 오른쪽. SIDE_B/SIDE_D 는 각각 B·D 와 같은 쪽의 옆.
     */
    enum class ViewClass(val letter: String, val code: Int, val label: String) {
        C("C", 0, "정면"),
        B("B", 1, "앞 비스듬히, 사용자 오른쪽"),
        D("D", 2, "앞 비스듬히, 사용자 왼쪽"),
        SIDE_B("SIDE_B", 3, "옆, 사용자 오른쪽"),
        SIDE_D("SIDE_D", 4, "옆, 사용자 왼쪽"),
        A("A", 5, "뒤 비스듬히, 사용자 오른쪽"),
        E("E", 6, "뒤 비스듬히, 사용자 왼쪽"),
        R("R", 7, "뒤"),
        UNKNOWN("UNKNOWN", 8, "방향 불명");

        /** 규칙이 학습된 전방 반구(B/C/D)인가. */
        val front: Boolean get() = this == C || this == B || this == D
    }

    data class Estimate(val yawDeg: Float, val r: Float, val frames: Int) {
        val cls: ViewClass = classify(yawDeg, r)
        val letter: String get() = cls.letter
    }

    /** 프레임 요(도). 어깨선·골반선 중 있는 것으로 계산, 둘 다 없으면 null. 연구 `frame_yaw` 와 같은 정의. */
    fun frameYawDeg(joints: Map<String, Vec3?>): Float? {
        var ux = 0f
        var uz = 0f
        var n = 0
        fun line(l: Vec3?, r: Vec3?) {
            if (l == null || r == null) return
            val x = -(r.x - l.x)
            val z = r.z - l.z
            val len = hypot(x, z)
            if (len <= 1e-3f) return
            ux += x / len
            uz += z / len
            n += 1
        }
        line(joints[Joints.L_SHOULDER], joints[Joints.R_SHOULDER])
        line(joints[Joints.L_HIP], joints[Joints.R_HIP])
        if (n == 0) return null
        return Math.toDegrees(atan2(uz.toDouble(), ux.toDouble())).toFloat()
    }

    /** 집계·로그용 프레임 피처. 요를 못 구하면 빈 맵 (aggregator 는 없는 키를 세지 않는다). */
    fun frameFeatures(joints: Map<String, Vec3?>): Map<String, Float> {
        val yaw = frameYawDeg(joints) ?: return emptyMap()
        val rad = Math.toRadians(yaw.toDouble())
        return mapOf(FEAT_COS to cos(rad).toFloat(), FEAT_SIN to sin(rad).toFloat())
    }

    fun classify(yawDeg: Float, r: Float = 1f): ViewClass {
        if (!yawDeg.isFinite() || r < MIN_R) return ViewClass.UNKNOWN
        val a = abs(yawDeg)
        val bSide = (yawDeg > 0f) == (B_SIGN > 0f)
        return when {
            a <= FRONT_MAX_DEG -> ViewClass.C
            a <= OBLIQUE_MAX_DEG -> if (bSide) ViewClass.B else ViewClass.D
            a < REAR_MIN_DEG -> if (bSide) ViewClass.SIDE_B else ViewClass.SIDE_D
            a < REAR_PURE_MIN_DEG -> if (bSide) ViewClass.A else ViewClass.E
            else -> ViewClass.R
        }
    }

    /** cos/sin 평균에서 (원형 평균). */
    fun fromMeans(meanCos: Float, meanSin: Float, frames: Int): Estimate =
        Estimate(Math.toDegrees(atan2(meanSin.toDouble(), meanCos.toDouble())).toFloat(), hypot(meanCos, meanSin), frames)

    /** 집계 창에서 추정. 프레임이 [minFrames] 미만이면 null — 호출부는 null 을 "모름"으로 두고 게이팅하지 않는다. */
    fun estimate(agg: FeatureAggregator, minFrames: Int = MIN_FRAMES): Estimate? {
        val n = agg.count(FEAT_COS)
        if (n < minFrames) return null
        val c = agg.stat(FEAT_COS, "mean") ?: return null
        val s = agg.stat(FEAT_SIN, "mean") ?: return null
        return fromMeans(c, s, n)
    }

    /** 프레임 피처 목록에서 추정 (세트 로그용). */
    fun estimate(frames: List<Map<String, Float>>, minFrames: Int = MIN_FRAMES): Estimate? {
        var c = 0.0
        var s = 0.0
        var n = 0
        for (f in frames) {
            val fc = f[FEAT_COS] ?: continue
            val fs = f[FEAT_SIN] ?: continue
            if (!fc.isFinite() || !fs.isFinite()) continue
            c += fc; s += fs; n += 1
        }
        if (n < minFrames) return null
        return fromMeans((c / n).toFloat(), (s / n).toFloat(), n)
    }
}
