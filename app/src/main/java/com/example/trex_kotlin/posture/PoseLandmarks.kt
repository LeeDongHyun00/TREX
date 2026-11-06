package com.example.trex_kotlin.posture

/**
 * 추론 **이후** 처리(spec §2~§3) — MediaPipe 한 사람의 랜드마크를 [PoseSample](관절·up 자가검증·프레임 피처)로 바꾼다.
 * Android `PostureAnalyzer`(CameraX·MediaPipe Android)와 iPhone 카메라(AVFoundation·MediaPipe iOS)가 **같은 함수**로 피처를 만든다 —
 * 두 플랫폼의 판정이 다르면 그 원인이 이 단계여서는 안 된다(§108c). 추론·영상 처리(회전·크롭 그리기)는 플랫폼에 남는다.
 * 엔진 동기화(`tools/sync_ios_core.py`)가 이 파일을 공통 모듈로 그대로 옮긴다 — 안드로이드 의존을 넣지 않는다.
 */

const val MP_LANDMARK_COUNT = 33
private const val MIN_VISIBILITY = 0.5f
/** 얼굴 추론 간격 — 판정 격자(§96, 300 ms)와 같다. */
const val FACE_INTERVAL_MS = 300L
/** 이 시간 안의 샘플에는 마지막 얼굴 결과를 얹는다(판정 칸의 첫 프레임이 얼굴을 돌린 프레임과 85 ms 어긋날 수 있다). */
const val FACE_STALE_MS = 450L
/** 머리 크롭의 출력 한 변(px) — 검출기 입력 128 에서 얼굴이 40 px 넘게. */
const val FACE_CROP_PX = 256
/** 크롭 한 변 = 머리 높이 × 이 배수(머리가 크롭의 1/3). */
const val FACE_CROP_SCALE = 3.0f
/** 얼굴 검출·존재 신뢰도 하한 — 옆얼굴은 점수가 낮다(기본 0.5 → 0.3). */
const val FACE_MIN_CONFIDENCE = 0.3f

/**
 * 얼굴 자세 행렬(4×4, MediaPipe facial transformation matrix) → 얼굴 앞 방향과 화면 평면이 이루는 각(°, §101d `FloorGaze.YAW`).
 * 정준 얼굴 모델의 +z 가 얼굴 앞(보는 쪽)이고 카메라 공간은 카메라가 −z 를 바라보므로, 카메라를 보는 얼굴의 앞 방향은 +z(카메라 쪽)다.
 * 요 = asin(앞 방향의 z 성분): **0 = 옆얼굴(올바른 시선), +90 = 카메라를 봄, −90 = 뒤통수**. 행렬 배치(열 우선/행 우선)는 마지막 행·열의 0 으로 가른다 —
 * 열 우선이면 m[3]·m[7]·m[11] 이 0(마지막 행), 행 우선이면 m[12..14] 가 평행이동. 폰 1차 확인 전 부호는 가정이다([FACE_YAW_SIGN] 으로 뒤집는다).
 */
fun faceYawDeg(m: FloatArray): Float {
    if (m.size < 16) return Float.NaN
    val colMajor = kotlin.math.abs(m[3]) < 1e-4f && kotlin.math.abs(m[7]) < 1e-4f && kotlin.math.abs(m[11]) < 1e-4f
    val zx = if (colMajor) m[8] else m[2]
    val zy = if (colMajor) m[9] else m[6]
    val zz = m[10]
    val n = kotlin.math.sqrt(zx * zx + zy * zy + zz * zz)
    if (!(n > 1e-6f) || !n.isFinite()) return Float.NaN
    return FACE_YAW_SIGN * Math.toDegrees(kotlin.math.asin((zz / n).coerceIn(-1f, 1f).toDouble())).toFloat()
}
private const val FACE_YAW_SIGN = 1f

enum class PoseModel(val asset: String, val label: String) {
    FULL("posture/pose_landmarker_full.task", "full"),
    LITE("posture/pose_landmarker_lite.task", "lite"),
}

/** 한 프레임 추론 결과. */
class PoseSample(
    val detected: Boolean,
    /** 정규화 좌표 (회전 보정된 이미지 기준, 0..1). size = 33*2 (x,y 반복) */
    val normalizedXy: FloatArray,
    val visibility: FloatArray,
    val features: Map<String, Float>,
    val visibleJointCount: Int,
    val inferMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    /** 이 프레임 계산에 쓴 up 벡터 (IMU 중력축 또는 화면 세로축 폴백). */
    val up: Vec3 = SCREEN_UP,
    /** up 이 IMU 에서 온 것인지 (false = 화면 세로축 가정). */
    val upFromGravity: Boolean = false,
    /** 관절 배치 자가검증(checkUpSanity)으로 up 을 뒤집어 보정했는지. */
    val upFlipped: Boolean = false,
    /** 자가검증으로 up 방향을 확인할 수 있었는지 (false = 누운 자세/관절 부족 등으로 미검증). */
    val upVerified: Boolean = false,
    /**
     * MediaPipe 월드 랜드마크 원값(m, MediaPipe 부호 그대로 — y 아래·z 카메라 쪽 음수), 33×3 = x0,y0,z0,…. 검출 프레임만, 아니면 null.
     * 피처 계산에는 쓰지 않는다(피처는 가시성 거른 cm·부호 반전 좌표). 검증 모드 세트 로그가 이 값을 남겨 오프라인에서 같은 후처리를
     * 다시 돌리고(재생 파리티) 좌우 판별·화면 잘림 같은 새 분석을 폰 데이터로 할 수 있게 한다 (spec §61).
     */
    val world: FloatArray? = null,
) {
    companion object {
        fun empty(inferMs: Long = 0L, w: Int = 0, h: Int = 0, up: Vec3 = SCREEN_UP, fromGravity: Boolean = false) = PoseSample(
            detected = false,
            normalizedXy = FloatArray(MP_LANDMARK_COUNT * 2),
            visibility = FloatArray(MP_LANDMARK_COUNT),
            features = emptyMap(),
            visibleJointCount = 0,
            inferMs = inferMs,
            imageWidth = w,
            imageHeight = h,
            up = up,
            upFromGravity = fromGravity,
        )
    }
}

/** 바닥 종목용: 같은 샘플에 features 만 바닥 2D 피처로 바꾼 사본 (세트 로그·집계에 그대로 흘림). PostureFloor.kt 에서 옮겼다 — 그 파일은 재생기가 컴파일한다(spec §99). */
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
    world = world,
)

/** 추론 통계 (UI 표시용 스냅샷). */
data class AnalyzerStats(
    val delegate: String,
    val model: String,
    val ready: Boolean,
    val inferCount: Long,
    val emaInferMs: Float,
    val lastInferMs: Long,
    val error: String?,
)

object PoseLandmarks {
    /**
     * 한 사람의 랜드마크 → [PoseSample]. [xy] = 정규화 좌표 33×2, [vis] = min(visibility, presence)(값이 없으면 1), [world] = 월드 좌표 원값(m) 33×3.
     * 월드 좌표는 m → cm, y·z 부호 반전(spec §3), 가시성 0.5 미만 관절은 버린다. [up] 이 IMU 값이 아니면 [SCREEN_UP] 그 객체를 넘긴다(동일성으로 가른다).
     * [addFace] 가 있으면 피처 사본에 얼굴 피처를 얹는다(§101d — 영상 크롭·추론은 플랫폼 몫). [onFlip] 은 up 을 뒤집어 보정한 프레임의 진단 로그용.
     */
    fun sample(
        xy: FloatArray, vis: FloatArray, world: FloatArray, width: Int, height: Int, inferMs: Long, up: Vec3,
        addFace: ((HashMap<String, Float>) -> Unit)? = null, onFlip: ((UpSanity) -> Unit)? = null,
    ): PoseSample {
        val fromGravity = up !== SCREEN_UP
        // 월드 좌표: m → cm, y/z 부호 반전 (spec §3)
        val pts = arrayOfNulls<Vec3>(MP_LANDMARK_COUNT)
        for (i in 0 until MP_LANDMARK_COUNT) {
            if (vis[i] < MIN_VISIBILITY) continue
            pts[i] = Vec3(world[i * 3] * 100f, -world[i * 3 + 1] * 100f, -world[i * 3 + 2] * 100f)
        }

        val joints = HashMap<String, Vec3?>(24)
        for ((name, idx) in Joints.SINGLE) joints[name] = pts.getOrNull(idx)
        for ((name, pair) in Joints.PAIR) {
            val a = pts.getOrNull(pair.first)
            val b = pts.getOrNull(pair.second)
            joints[name] = if (a != null && b != null) mid(a, b) else a ?: b
        }
        // up 자가검증: IMU 부호/회전 매핑이 틀려 up 이 뒤집혔으면(골반이 발목 아래로 계산됨) −up 으로 보정 (spec §4)
        val sanity = checkUpSanity(joints, up)
        val upUsed = if (sanity.flipped) (up * -1f).unit() ?: up else up
        if (sanity.flipped) onFlip?.invoke(sanity)
        val frame = PoseFrame(joints, upUsed)
        // §33: 촬영 방향 피처(view_cos/view_sin)도 같은 프레임 피처로 — 집계·로그·규칙 게이팅이 추가 배선 없이 받는다
        // §62a 후속 3: 이미지 2D 발 너비 — 월드 발목 간격은 발끝 회전에 흔들린다(Stance2d 주석). 재생기 frameFeatures 도 같은 함수
        // §62c: 컬의 팔꿈치 앞 이탈·몸통 기울기는 이미지 2D + 촬영 방위 부호(Arm2d). 재생기 frameFeatures 도 같은 순서·같은 함수
        val viewF = ViewEstimator.frameFeatures(joints)
        val aspect = width.toFloat() / height
        // §63: 런지 걸음 기하(앞다리·깊이·무릎 쏠림·어깨 높이) — 어깨선 요로. 재생기 frameFeatures 도 같은 순서·같은 함수
        val features = frame.features() + viewF + Stance2d.features(xy, vis, MIN_VISIBILITY, aspect) +
            Arm2d.features(xy, vis, MIN_VISIBILITY, aspect, Arm2d.yawOf(viewF)) +
            Lunge2d.features(frame, xy, vis, MIN_VISIBILITY, aspect, ViewEstimator.shoulderYawOf(viewF), gravityUp = fromGravity) +
            // §96: 한 발 떠남 계열의 기하(허벅지각·비틀림·롤 보정 2D 발목/무릎/골반·손–귀·팔꿈치–무릎) — 재생기 frameFeatures 도 같은 순서·같은 함수
            LegGeometry.features(frame, xy, vis, MIN_VISIBILITY, aspect, LegGeometry.rollDeg(upUsed))
        // §101d: 얼굴 메시 피처(바닥 세 종목의 시선) — 판정 격자마다 한 번, 같은 영상. 재생기 .cap 에는 없다(로그 전용)
        val featuresOut = if (addFace != null) HashMap(features).also(addFace) else features
        val visibleCount = vis.count { it >= MIN_VISIBILITY }
        return PoseSample(
            detected = true,
            normalizedXy = xy,
            visibility = vis,
            features = featuresOut,
            visibleJointCount = visibleCount,
            inferMs = inferMs,
            imageWidth = width,
            imageHeight = height,
            up = upUsed,
            upFromGravity = fromGravity,
            upFlipped = sanity.flipped,
            upVerified = sanity.verified,
            world = world,
        )
    }
}

/**
 * 머리 크롭 기하(§101d) — 영상 픽셀 기준. 중심 = 코·귀 중 보이는 점의 평균, 크기 = max(코–귀 거리 × 2.2, 어깨 중점 → 귀 중점 길이, 40 px) ≈ 머리 높이,
 * 한 변 = 크기 × [FACE_CROP_SCALE]. 회전 = 어깨 중점 → 귀 중점(목 방향)이 위를 향하게 — 누워 턱을 당긴 크런치(머리가 몸통 축과 70° 꺾임)에서 몸통 축(골반→어깨)으로 돌리면 얼굴이 옆으로 눕는다.
 * 그리는 순서(평행이동 −c → 회전 [rotationDeg](화면 시계 방향 양수) → 배율 [scale] → 평행이동 +[FACE_CROP_PX]/2)는 플랫폼이 같은 순서로 따른다.
 */
class FaceCrop(val cx: Float, val cy: Float, val headPx: Float, val rotationDeg: Float) {
    val side: Float get() = headPx * FACE_CROP_SCALE
    val scale: Float get() = FACE_CROP_PX / side

    companion object {
        /** 코·귀가 둘 다 없거나(보이는 점 2개 미만) 어깨가 없으면 null. */
        fun of(xy: FloatArray, vis: FloatArray, width: Int, height: Int): FaceCrop? {
            val w = width.toFloat(); val h = height.toFloat()
            fun ok(i: Int) = vis.getOrNull(i)?.let { it >= MIN_VISIBILITY } == true && xy[i * 2].isFinite() && xy[i * 2 + 1].isFinite()
            fun px(i: Int) = xy[i * 2] * w
            fun py(i: Int) = xy[i * 2 + 1] * h
            val head = listOf(0, 7, 8).filter { ok(it) }
            if (head.size < 2 || !(ok(11) || ok(12))) return null
            val cx = head.map { px(it) }.average().toFloat(); val cy = head.map { py(it) }.average().toFloat()
            val ears = listOf(7, 8).filter { ok(it) }
            val noseEar = if (ok(0) && ears.isNotEmpty()) ears.maxOf { kotlin.math.hypot(px(0) - px(it), py(0) - py(it)) } else 0f
            val shoulders = listOf(11, 12).filter { ok(it) }
            val sx = shoulders.map { px(it) }.average().toFloat(); val sy = shoulders.map { py(it) }.average().toFloat()
            val ex = if (ears.isNotEmpty()) ears.map { px(it) }.average().toFloat() else cx
            val ey = if (ears.isNotEmpty()) ears.map { py(it) }.average().toFloat() else cy
            val neck = kotlin.math.hypot(ex - sx, ey - sy)
            val headPx = maxOf(noseEar * 2.2f, neck, 40f)
            // 회전: 목 방향(어깨 중점 → 귀 중점)을 위(−y)로. Android Matrix 의 양의 각은 화면에서 시계 방향 — 벡터가 위에서 시계 방향으로 φ 만큼 돌아 있으면 −φ 돌린다
            val vx = ex - sx; val vy = ey - sy
            val phi = if (kotlin.math.hypot(vx, vy) > 1f) Math.toDegrees(kotlin.math.atan2(vx.toDouble(), (-vy).toDouble())).toFloat() else 0f
            return FaceCrop(cx, cy, headPx, -phi)
        }
    }
}

/**
 * 얼굴 결과의 시간 관리(§101d) — [FACE_INTERVAL_MS] 마다 한 번 추론하고(85 ms 추론 루프마다 돌리지 않는다) 마지막 결과를 [FACE_STALE_MS] 안의 샘플 피처에 얹는다.
 * 키는 [FloorGaze.FOUND]·[FloorGaze.YAW]·[FloorGaze.INFER_MS]·[FloorGaze.ROTATION]·[FloorGaze.SIZE]. 얼굴이 없으면 요 키는 없다. 모델이 없으면(실패·미지원) 키를 넣지 않는다 — 시선은 '측정 중'.
 * 추론 스레드 하나에서만 부른다(플랫폼 분석기의 락 안).
 *
 * 결과 시각은 Long 으로 둔다(§108c 후속) — 전에는 다른 값과 같은 Float 칸에 있어서 epoch ms(약 1.76e12)에서는 131 s 단위로만 갈렸고,
 * 머리가 안 보이게 된 뒤에도 마지막 시선 값이 [FACE_STALE_MS] 가 아니라 1~2분 동안 얹혀 시선 판정·음성이 묵은 얼굴을 봤다.
 */
class FaceTrack {
    private var lastFaceMs = Long.MIN_VALUE / 2
    /** 마지막 얼굴 결과 — [검출 1/0, 요 °(NaN = 없음), 추론 ms, 회전, 머리 px]. 시각은 [lastFaceAt]. */
    private var lastFace: FloatArray? = null
    /** [lastFace] 를 추론한 시각(ms, 분석기 시각 그대로 — epoch ms). Float 칸에 두면 450 ms 가 갈리지 않는다. */
    private var lastFaceAt = 0L

    /** 이 시각에 얼굴 추론을 돌릴 차례인가. */
    fun due(ts: Long): Boolean = ts - lastFaceMs >= FACE_INTERVAL_MS

    /** 추론했다 — 검출 여부·요(없으면 NaN)·추론 ms 와 그 크롭의 회전·머리 크기를 남긴다. */
    fun record(ts: Long, found: Boolean, yaw: Float, inferMs: Long, crop: FaceCrop) {
        lastFaceMs = ts
        lastFace = floatArrayOf(if (found) 1f else 0f, yaw, inferMs.toFloat(), crop.rotationDeg, crop.headPx)
        lastFaceAt = ts
    }

    /** 머리가 안 보여 이번 칸은 건너뛴다 — 마지막 결과는 [FACE_STALE_MS] 뒤 사라진다(모름). */
    fun skip(ts: Long) { lastFaceMs = ts }

    fun clear() { lastFace = null }

    /** 마지막 결과를 [out] 에 얹는다(오래됐으면 아무것도 넣지 않는다). */
    fun apply(ts: Long, out: MutableMap<String, Float>) {
        val f = lastFace ?: return
        if (ts - lastFaceAt > FACE_STALE_MS) return
        out[FloorGaze.FOUND] = f[0]
        if (f[1].isFinite()) out[FloorGaze.YAW] = f[1]
        out[FloorGaze.INFER_MS] = f[2]
        out[FloorGaze.ROTATION] = f[3]
        out[FloorGaze.SIZE] = f[4]
    }
}

/** 오버레이용 골격 연결 (MediaPipe 33점). */
val POSE_CONNECTIONS: List<Pair<Int, Int>> = listOf(
    // 얼굴
    0 to 1, 1 to 2, 2 to 3, 3 to 7, 0 to 4, 4 to 5, 5 to 6, 6 to 8,
    9 to 10,
    // 몸통
    11 to 12, 11 to 23, 12 to 24, 23 to 24,
    // 왼팔
    11 to 13, 13 to 15, 15 to 17, 15 to 19, 15 to 21, 17 to 19,
    // 오른팔
    12 to 14, 14 to 16, 16 to 18, 16 to 20, 16 to 22, 18 to 20,
    // 왼다리
    23 to 25, 25 to 27, 27 to 29, 27 to 31, 29 to 31,
    // 오른다리
    24 to 26, 26 to 28, 28 to 30, 28 to 32, 30 to 32,
)
