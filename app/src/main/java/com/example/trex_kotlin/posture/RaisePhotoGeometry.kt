package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** 레이즈 사진의 올림 방향과 어깨 움직임을 위한 관측값. 근육 긴장·견갑골 회전을 직접 재지 않는다. */
object RaisePhotoGeometry {
    fun features(xy: FloatArray, vis: FloatArray, width: Int, height: Int, source: Map<String, Float>,
                 world: FloatArray? = null, up: Vec3 = Vec3(0f, 1f, 0f)): Map<String, Float> {
        if (xy.size < 66 || vis.size < 33 || width <= 0 || height <= 0) return emptyMap()
        val view = ViewEstimator.estimate(listOf(source), 1)?.cls ?: return emptyMap()
        if (view !in setOf(ViewEstimator.ViewClass.B, ViewEstimator.ViewClass.C, ViewEstimator.ViewClass.D,
                ViewEstimator.ViewClass.SIDE_B, ViewEstimator.ViewClass.SIDE_D, ViewEstimator.ViewClass.A, ViewEstimator.ViewClass.E)) return emptyMap()
        fun visible(i: Int) = vis[i].isFinite() && vis[i] >= .5f &&
            xy[2*i].isFinite() && xy[2*i+1].isFinite() && xy[2*i] in 0f..1f && xy[2*i+1] in 0f..1f
        val out = mutableMapOf<String, Float>()
        if (listOf(0,7,8,11,12,23,24).all(::visible)) {
            source["head_yaw"]?.takeIf { it.isFinite() && it in -180f..180f }?.let {
                out["raise_head_turn"] = abs(it)
            }
            source["head_pitch"]?.takeIf { it.isFinite() && it in -90f..90f }?.let {
                out["raise_head_pitch"] = it
            }
        }
        val aspect = width.toFloat() / height
        fun p(i: Int) = Vec3(xy[2*i] * aspect, -xy[2*i+1], 0f)
        // 두 레이즈 모두 시작부터 으쓱한 어깨와 심한 들림을 같은 관측 조건으로 확인한다.
        out.putAll(FrontRaiseShoulderGeometry.features(xy,vis,width,height,source,up))
        if (world == null || world.size < 99 || !listOf(up.x, up.y, up.z).all { it.isFinite() }) return out
        val vertical = up.unit() ?: return out
        fun w(i: Int): Vec3? {
            if (!visible(i)) return null
            val x=world[3*i]; val y=world[3*i+1]; val z=world[3*i+2]
            return if (listOf(x,y,z).all { it.isFinite() }) Vec3(x,-y,-z) else null
        }
        val lh=w(23) ?: return out; val rh=w(24) ?: return out
        val ls=w(11) ?: return out; val rs=w(12) ?: return out
        val torso=(mid(ls,rs)-mid(lh,rh)).norm
        if (torso !in .2f..1f) return out
        val hips=lh-rh; val hipAxis=hips-vertical*(hips dot vertical)
        if (hipAxis.norm/torso !in .2f..1.2f || (ls-rs).norm/torso !in .3f..1.5f) return out
        val lateral=hipAxis.unit() ?: return out
        val forward=(lateral cross vertical).unit() ?: return out
        for ((side,n) in listOf("L" to 0,"R" to 1)) {
            val sh=if(n==0)ls else rs; val hip=if(n==0)lh else rh
            // 몸통과 어깨의 전후 이동을 함께 재는 투영값. 견갑골만의 움직임으로 해석하지 않는다.
            if (view in setOf(ViewEstimator.ViewClass.B,ViewEstimator.ViewClass.D))
                out["raise_shoulder_fwd_$side"] = ((sh-hip) dot forward)/torso
            if (view !in setOf(ViewEstimator.ViewClass.B,ViewEstimator.ViewClass.C,ViewEstimator.ViewClass.D)) continue
            val el=w(13+n) ?: continue
            val arm=el-sh; val len=arm.norm
            if (len/torso !in .25f..1.2f) continue
            val projected=(p(13+n)-p(11+n)).norm
            if (projected < .04f) continue // 카메라 쪽으로 겹친 위팔은 방향을 단정하지 않는다.
            val away=(arm dot lateral)*(if(n==0)1f else -1f)
            val ahead=arm dot forward
            if (hypot(away,ahead) < len*.35f) continue // 팔을 내린 때에는 수평 방향이 정의되지 않는다.
            out["raise_front_plane_$side"] = Math.toDegrees(atan2(away.toDouble(),ahead.toDouble())).toFloat()
            out["raise_lateral_plane_$side"] = Math.toDegrees(atan2(ahead.toDouble(),away.toDouble())).toFloat()
        }
        return out
    }
}
