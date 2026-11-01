// tools/sync_ios_core.py 생성본 — Android 정본에서 수정한 뒤 동기화하세요.
package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.max

/**
 * §131: 양쪽 어깨·골반의 정렬 의심을 화면에만 참고한다. 바닥 자세의 world 깊이는 추정값이다.
 * 2D 엎드림 관측과 IMU 중력, 네 관절의 가시성·분리·투영 일치가 모두 필요하다.
 * 한쪽 가림이나 겹침은 0도(정상)가 아니라 빈 맵이다. 골반의 앞뒤 경사·척추·관절 회전을 진단하지 않는다.
 */
object PushupBalance {
    const val SHOULDER = "pushup_shoulder_tilt"
    const val PELVIS = "pushup_pelvis_tilt"
    const val TWIST = "pushup_torso_twist"
    val keys = setOf(SHOULDER, PELVIS, TWIST)
    private val joints = listOf(11, 12, 23, 24)
    private const val DEGREES = 57.29578f

    fun features(xy: FloatArray, vis: FloatArray, width: Int, height: Int,
                 world: FloatArray?, up: Vec3?, prone: Boolean): Map<String, Float> {
        if (!prone || world == null || up == null || xy.size < 66 || vis.size < 33 || world.size < 99 ||
            width <= 0 || height <= 0 || !finite(up) || !up.norm.isFinite()) return emptyMap()
        val vertical = up.unit()?.takeIf(::finite) ?: return emptyMap()
        if (joints.any { i -> !vis[i].isFinite() || vis[i] < .8f ||
                !(xy[i*2] in 0f..1f) || !(xy[i*2+1] in 0f..1f) ||
                (0..2).any { !world[i*3+it].isFinite() } }) return emptyMap()
        fun image(i: Int) = Vec3(xy[i*2]*width, -xy[i*2+1]*height, 0f)
        // PoseLandmarks와 같은 좌표계. 원본 MediaPipe y·z 부호를 뒤집어 IMU up과 맞춘다.
        fun point(i: Int) = Vec3(world[i*3], -world[i*3+1], -world[i*3+2])
        val s = point(12) - point(11); val h = point(24) - point(23)
        val body = mid(point(11),point(12)) - mid(point(23),point(24))
        val si = image(12) - image(11); val hi = image(24) - image(23)
        val bi = mid(image(11),image(12)) - mid(image(23),image(24))
        if (listOf(s,h,body,si,hi,bi).any { !finite(it) || !it.norm.isFinite() }) return emptyMap()
        val length = body.norm
        // 폭/몸통 비는 정상 자세 기준이 아니라 붕괴한 추정 좌표를 버리는 넓은 관측 필터다.
        if (length < .05f || bi.norm < 40f || s.norm / length !in .3f..1.8f || h.norm / length !in .2f..1.5f)
            return emptyMap()
        val minSeparation = max(8f, bi.norm * .06f)
        if (si.norm < minSeparation || hi.norm < minSeparation ||
            !projectionAgrees(s,si) || !projectionAgrees(h,hi) || !projectionAgrees(body,bi)) return emptyMap()
        val axis = body.unit() ?: return emptyMap()
        val sp = s - axis * (s dot axis); val hp = h - axis * (h dot axis)
        if (sp.norm < s.norm * .7f || hp.norm < h.norm * .7f) return emptyMap()
        val su = sp.unit() ?: return emptyMap(); val hu = hp.unit() ?: return emptyMap()
        // 좌우 라벨 뒤집힘 같은 극단적인 불일치를 '180도 비틀림'으로 안내하지 않는다.
        val alignment = su dot hu
        if (!alignment.isFinite() || alignment <= 0f) return emptyMap()
        return mapOf(
            SHOULDER to asin(abs(s.unit()!! dot vertical).coerceIn(0f,1f)) * DEGREES,
            PELVIS to asin(abs(h.unit()!! dot vertical).coerceIn(0f,1f)) * DEGREES,
            TWIST to acos(alignment.coerceIn(-1f,1f)) * DEGREES,
        )
    }

    private fun finite(p: Vec3) = p.x.isFinite() && p.y.isFinite() && p.z.isFinite()

    private fun projectionAgrees(world: Vec3, image: Vec3): Boolean {
        val projected = hypot(world.x,world.y)
        if (projected < world.norm * .15f || projected < 1e-6f) return false
        val agreement = (world.x*image.x + world.y*image.y) / (projected*image.norm)
        return agreement.isFinite() && agreement >= .8f
    }
}
