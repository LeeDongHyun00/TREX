package com.example.trex_kotlin.posture

// PostureAnalyzer.faceYawDeg 정본 슬라이스(§101d) — Swift 가 넘긴 얼굴 자세 행렬의 요 계산을 Android 와 같은 식으로 한다.
/**
 * 얼굴 자세 행렬(4×4, MediaPipe facial transformation matrix) → 얼굴 앞 방향과 화면 평면이 이루는 각(°, §101d `FloorGaze.YAW`).
 * 정준 얼굴 모델의 +z 가 얼굴 앞(보는 쪽)이고 카메라 공간은 카메라가 −z 를 바라보므로, 카메라를 보는 얼굴의 앞 방향은 +z(카메라 쪽)다.
 * 요 = asin(앞 방향의 z 성분): **0 = 옆얼굴(올바른 시선), +90 = 카메라를 봄, −90 = 뒤통수**. 행렬 배치(열 우선/행 우선)는 마지막 행·열의 0 으로 가른다 —
 * 열 우선이면 m[3]·m[7]·m[11] 이 0(마지막 행), 행 우선이면 m[12..14] 가 평행이동. 폰 1차 확인 전 부호는 가정이다([FACE_YAW_SIGN] 으로 뒤집는다).
 */
internal fun faceYawDeg(m: FloatArray): Float {
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
