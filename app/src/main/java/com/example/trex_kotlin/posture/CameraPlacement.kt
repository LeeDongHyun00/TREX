package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.tan

/**
 * 세트의 **촬영 배치 지문** (`docs/SQUAT_CAMERA_RESEARCH.md` §7.2 · §9 P1) — 폰이 어디에 어떻게 놓였는가.
 *
 * 왜 남기나: 같은 동작도 카메라 높이·거리·기울기가 다르면 측정값이 움직인다(A1 모의: 바닥 폰 발끝 +21~24°, 1 m 폰 깊이 +13°).
 * 피처만 있는 로그로는 "자세가 달랐다" 와 "배치가 달랐다" 를 못 가른다. 세션 간 비교는 이 지문이 같을 때만 한다(§6-1).
 * 배치별 기준값(AIHub 3D 정답을 그 배치로 가상 촬영)도 이 값이 있어야 만들 수 있다.
 *
 * 값은 전부 **추정**이고 오프라인 재계산이 가능하도록 입력(프레임 `xy`·`w`·`up`, 렌즈 사양)이 로그에 함께 남는다.
 * - 피치·롤: 프레임 up 벡터(X 오른쪽·Y 위·Z 카메라 쪽)에서. A4 규약 그대로 — 피치 = asin(−up_z)(+ = 카메라가 위를 봄), 롤 = atan2(up_x, up_y).
 *   헤더 `tilt_deg` 는 마지막 프레임 값이라 폰을 집어 들면 그 각이 찍혔다(A4 §8 #5) — 여기서는 **피처가 있는 프레임의 중앙값**이다.
 * - 거리: MediaPipe 월드 어깨 폭(m) ↔ 이미지 어깨 폭(px) 의 핀홀 비(A4 교차 확인 (c)). 월드 척도는 평균 체형 가정이라 실제 키가 x % 크면 거리도 x % 크다.
 * - 높이: 발목 행이 수평선에서 몇 도 아래인지(A4 (b)) — `tan` 에 거리를 곱하고 발목 랜드마크의 바닥 위 높이 8 cm 를 더한다. 서서 하는 종목만.
 *
 * 기록하지 않는 것: 노출·실제 센서 fps(CameraX 가 캡처 결과를 노출하지 않는다). 추론 주기(`fps`)와 추론 시간만 남긴다.
 */
data class CameraPlacement(
    /** 피치·롤·기울기 중앙값을 낸 프레임 수(피처 있고 up 이 IMU 에서 온 프레임). 0 이면 각도 필드는 null. */
    val frames: Int,
    val pitchDeg: Float?,
    val rollDeg: Float?,
    val tiltDeg: Float?,
    /** 세트 중 기울기 최대 — 중앙값과 크게 다르면 폰이 세트 중에 움직였다(집어 듦·넘어짐). */
    val tiltMaxDeg: Float?,
    /** 실효 추론 주기(Hz) = (프레임 − 1) / 경과 시간. 2프레임 미만이면 null. */
    val fps: Float?,
    val inferMsMed: Float?,
    val lens: LensInfo?,
    /** 분석 이미지 긴 변 기준 초점거리(px) — [LensInfo.focalPx]. 렌즈 정보가 없으면 null 이고 거리·높이도 null. */
    val fPx: Float?,
    val distanceM: Float?,
    val heightM: Float?,
)

/**
 * Camera2 특성에서 읽은 렌즈 사양 — 전부 선택(기기가 안 주면 null).
 * @property focalMm `LENS_INFO_AVAILABLE_FOCAL_LENGTHS[0]`
 * @property sensorWMm·sensorHMm `SENSOR_INFO_PHYSICAL_SIZE`
 * @property activeW·activeH `SENSOR_INFO_ACTIVE_ARRAY_SIZE`(px)
 * @property zoom 바인딩 직후 `ZoomState.zoomRatio`(앱은 줌을 바꾸지 않으므로 보통 1.0)
 */
data class LensInfo(
    val focalMm: Float?,
    val sensorWMm: Float?,
    val sensorHMm: Float?,
    val activeW: Int?,
    val activeH: Int?,
    val zoom: Float?,
) {
    /**
     * 분석 이미지에서의 초점거리(px). 4:3 분석 스트림이 센서 활성 영역의 긴 변을 **크롭 없이** 쓴다고 가정한다 —
     * 센서 긴 변(mm) 이 이미지 긴 변(px) 에 대응하므로 f_px = focal_mm / sensor_long_mm × image_long_px × zoom.
     * 기기가 16:9 센서를 4:3 으로 자르면 이 값은 실제보다 작다(가로 크롭 → f 과소). 그 오차는 거리·높이에 같은 비율로 들어간다.
     */
    fun focalPx(imageW: Int, imageH: Int): Float? {
        val f = focalMm ?: return null
        val sw = sensorWMm ?: return null
        val sh = sensorHMm ?: return null
        val sensorLong = max(sw, sh)
        if (f <= 0f || sensorLong <= 0f) return null
        val imageLong = max(imageW, imageH)
        if (imageLong <= 0) return null
        return f / sensorLong * imageLong * (zoom?.takeIf { it > 0f } ?: 1f)
    }
}

object CameraPlacementEstimator {
    /** 발목 랜드마크의 바닥 위 높이(m) 가정 — A4 와 같다. */
    const val ANKLE_H_M = 0.08f
    private const val MIN_VIS = 0.5f
    private const val L_SH = 11; private const val R_SH = 12
    private const val L_AN = 27; private const val R_AN = 28

    /**
     * @param samples 세트 프레임(검출 안 된 프레임 포함)
     * @param timesMs 프레임 시각(세트 상대 ms, [samples] 와 같은 길이). null 이면 fps 생략
     * @param standing 서서 하는 종목인가 — 아니면(바닥) 발목 행으로 높이를 추정하지 않는다(누운 발목은 바닥 위 8 cm 가 아니다)
     * @return 모든 입력이 비어 있으면(IMU 프레임 0·렌즈 null·프레임 0) null — 로그에는 키 자체가 없다
     */
    fun estimate(samples: List<PoseSample>, timesMs: List<Long>?, lens: LensInfo?, standing: Boolean): CameraPlacement? {
        val imu = samples.filter { it.features.isNotEmpty() && it.upFromGravity }
        val pitch = ArrayList<Float>(imu.size); val roll = ArrayList<Float>(imu.size); val tilt = ArrayList<Float>(imu.size)
        for (s in imu) {
            val u = s.up.unit() ?: continue
            pitch += Math.toDegrees(asin(-u.z.coerceIn(-1f, 1f).toDouble())).toFloat()
            roll += Math.toDegrees(atan2(u.x.toDouble(), u.y.toDouble())).toFloat()
            tilt += tiltFromScreenUpDegrees(u)
        }
        val detected = samples.filter { it.detected }
        val first = detected.firstOrNull()
        val fPx = if (first != null && lens != null) lens.focalPx(first.imageWidth, first.imageHeight) else null
        val fps = timesMs?.takeIf { it.size >= 2 }?.let { t ->
            val span = t.last() - t.first()
            if (span > 0) (t.size - 1) * 1000f / span else null
        }
        val inferMed = median(samples.map { it.inferMs.toFloat() })
        val distance = fPx?.let { distanceM(detected, it) }
        val height = if (standing && fPx != null && distance != null) heightM(detected, fPx, distance, median(pitch)) else null
        if (imu.isEmpty() && lens == null && samples.isEmpty()) return null
        return CameraPlacement(
            frames = pitch.size,
            pitchDeg = median(pitch), rollDeg = median(roll), tiltDeg = median(tilt), tiltMaxDeg = tilt.maxOrNull(),
            fps = fps, inferMsMed = inferMed, lens = lens, fPx = fPx, distanceM = distance, heightM = height,
        )
    }

    /** 어깨 폭 핀홀 비 D = f · 어깨폭_m / 어깨폭_px 의 프레임 중앙값. 양 어깨가 보이는(vis ≥ 0.5) 프레임만. */
    private fun distanceM(detected: List<PoseSample>, fPx: Float): Float? {
        val d = ArrayList<Float>()
        for (s in detected) {
            val w = s.world ?: continue
            if (s.visibility[L_SH] < MIN_VIS || s.visibility[R_SH] < MIN_VIS) continue
            val xy = s.normalizedXy
            val pxW = abs(xy[2 * L_SH] - xy[2 * R_SH]) * s.imageWidth
            if (pxW < 1f) continue
            val dx = w[3 * L_SH] - w[3 * R_SH]; val dy = w[3 * L_SH + 1] - w[3 * R_SH + 1]; val dz = w[3 * L_SH + 2] - w[3 * R_SH + 2]
            val mW = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (mW <= 0f) continue
            d += fPx * mW / pxW
        }
        return median(d)
    }

    /**
     * 발목 행(두 발목 평균)이 광축 중심에서 몇 px 아래인지 → 수평선 아래 각 = atan((y − c_y)/f) − 피치 → 높이 = 8 cm + D · tan(각).
     * 피치를 모르면(IMU 없음) 0 으로 둔다 — 그 가정은 [CameraPlacement.frames] 가 0 인 것으로 드러난다.
     */
    private fun heightM(detected: List<PoseSample>, fPx: Float, distanceM: Float, pitchDeg: Float?): Float? {
        val rows = ArrayList<Float>()
        for (s in detected) {
            if (s.visibility[L_AN] < MIN_VIS || s.visibility[R_AN] < MIN_VIS) continue
            val xy = s.normalizedXy
            rows += 0.5f * (xy[2 * L_AN + 1] + xy[2 * R_AN + 1]) * s.imageHeight - s.imageHeight / 2f
        }
        val dy = median(rows) ?: return null
        val ang = atan(dy / fPx) - Math.toRadians((pitchDeg ?: 0f).toDouble()).toFloat()
        return ANKLE_H_M + distanceM * tan(ang)
    }

    private fun median(v: List<Float>): Float? {
        if (v.isEmpty()) return null
        val s = v.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5f * (s[n / 2 - 1] + s[n / 2])
    }
}
