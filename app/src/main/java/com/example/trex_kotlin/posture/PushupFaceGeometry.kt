package com.example.trex_kotlin.posture

import kotlin.math.*

/** 얼굴 앞 방향을 읽는다. 눈동자의 시선이나 정상 자세를 판정하지 않는다. */
internal object PushupFaceGeometry {
    const val FOUND = "pushup_face_found"
    const val YAW = "pushup_face_yaw"
    const val CROP_PX = 256
    private const val CROP_SCALE = 3f

    data class Crop(val centerX: Float, val centerY: Float, val side: Float, val rotationDegrees: Float) {
        val scale: Float get() = CROP_PX / side
    }

    /** 0°는 옆얼굴, +90°는 렌즈 방향이다. 회전 행렬의 카메라 축 성분만 사용한다. */
    fun yawDegrees(matrix: FloatArray): Float? {
        if (matrix.size != 16 || matrix.any { !it.isFinite() }) return null
        // 평행이동 위치로 저장 순서를 구분한다. 순수 회전에서 두 배치의 z 성분과 벡터 길이는 같다.
        val columnMajor = abs(matrix[3]) < 1e-4f && abs(matrix[7]) < 1e-4f && abs(matrix[11]) < 1e-4f
        val x = if (columnMajor) matrix[8].toDouble() else matrix[2].toDouble()
        val y = if (columnMajor) matrix[9].toDouble() else matrix[6].toDouble()
        val z = matrix[10].toDouble()
        val length = sqrt(x*x + y*y + z*z)
        if (!length.isFinite() || length < 1e-6) return null
        return Math.toDegrees(asin((z / length).coerceIn(-1.0, 1.0))).toFloat()
    }

    /** 미검출과 잘못된 행렬은 관측 없음이다. 마지막 성공값을 재사용하지 않는다. */
    fun observedFeatures(found: Boolean, matrix: FloatArray?): Map<String, Float> {
        if (!found || matrix == null) return emptyMap()
        val yaw = yawDegrees(matrix) ?: return emptyMap()
        return mapOf(FOUND to 1f, YAW to yaw)
    }

    /** 보이는 코·귀를 중심으로 자르고 어깨→귀 방향을 화면 위쪽으로 돌린다. */
    fun crop(xy: FloatArray, vis: FloatArray, width: Int, height: Int): Crop? {
        if (xy.size != 66 || vis.size != 33 || width <= 0 || height <= 0) return null
        fun ok(i: Int) = vis[i].isFinite() && vis[i] >= .5f &&
            xy[i*2].isFinite() && xy[i*2+1].isFinite() && xy[i*2] in 0f..1f && xy[i*2+1] in 0f..1f
        fun x(i: Int) = xy[i*2] * width
        fun y(i: Int) = xy[i*2+1] * height
        val head = listOf(0, 7, 8).filter(::ok)
        val ears = listOf(7, 8).filter(::ok)
        val shoulders = listOf(11, 12).filter(::ok)
        if (head.size < 2 || ears.isEmpty() || shoulders.isEmpty()) return null
        val cx = head.map(::x).average().toFloat(); val cy = head.map(::y).average().toFloat()
        val ex = ears.map(::x).average().toFloat(); val ey = ears.map(::y).average().toFloat()
        val sx = shoulders.map(::x).average().toFloat(); val sy = shoulders.map(::y).average().toFloat()
        val vx = ex - sx; val vy = ey - sy
        val neck = hypot(vx, vy)
        if (!neck.isFinite() || neck <= 1f) return null
        val noseEar = if (ok(0)) ears.maxOf { hypot(x(0)-x(it), y(0)-y(it)) } else 0f
        val side = maxOf(noseEar * 2.2f, neck, 40f) * CROP_SCALE
        val rotation = -Math.toDegrees(atan2(vx.toDouble(), -vy.toDouble())).toFloat()
        return Crop(cx, cy, side, rotation)
    }
}
