// tools/sync_ios_core.py 생성본 — Android 정본에서 수정한 뒤 동기화하세요.
package com.example.trex_kotlin.posture

import kotlin.math.*

/** §133: 세 종목 횟수 검사에 쓸 관측만 따로 만든다. 가린 3D 관절을 정상·오류로 확정하지 않는다. */
object StandingFormGeometry {
    const val TILT="sf_torso_tilt"
    const val PITCH="sf_torso_pitch"
    fun knee(side: String)="sf_knee_$side"
    fun elbow(side: String)="sf_elbow_$side"
    fun plane(side: String)="sf_raise_plane_$side"
    val keys=setOf(TILT,PITCH,FourExerciseForm.GOOD_MORNING_SIGNAL)+listOf("L","R").flatMap {listOf(knee(it),elbow(it),plane(it))}

    fun apply(base: Map<String,Float>, xy: FloatArray, vis: FloatArray, aspect: Float, up: Vec3): Map<String,Float> {
        val result=HashMap(base-keys)
        if(xy.size!=66 || vis.size!=33 || !aspect.isFinite() || aspect<=0f) return result
        val vertical=Vec3(up.x,up.y,0f).takeIf {it.norm>=.5f}?.unit() ?: return result
        val horizontal=Vec3(vertical.y,-vertical.x,0f)
        fun point(i: Int): Vec3? {
            val x=xy[i*2];val y=xy[i*2+1]
            if(!vis[i].isFinite() || vis[i]<.8f || !x.isFinite() || !y.isFinite() || x !in .01f.. .99f || y !in .01f.. .99f) return null
            return Vec3(x*aspect,-y,0f)
        }
        fun angle(a: Vec3,b: Vec3): Float? = if(a.norm<.015f || b.norm<.015f) null else angleVec(a,b)
        val yaw=Arm2d.yawOf(base)?.takeIf(Float::isFinite)
        // 앞뒤 굽힘은 옆·사선에서 보일 때만 잰다. 정면의 불확실한 깊이 추정으로 대체하지 않는다.
        if(yaw!=null && abs(yaw)>=25f && abs(yaw)<=110f) {
            val candidates=listOf(0,1).mapNotNull { side->
                val sh=point(11+side);val hip=point(23+side)
                if(sh==null || hip==null || (sh-hip).norm<.06f) null
                else Triple(side,sh-hip,min(vis[11+side],vis[23+side]))
            }
            candidates.maxByOrNull {it.third}?.let { (_,body,_)->
                val pitch=atan2(body dot horizontal,body dot vertical)*180f/PI.toFloat()
                if(abs(pitch)<=110f) {
                    result[TILT]=abs(pitch);result[PITCH]=pitch
                    result[FourExerciseForm.GOOD_MORNING_SIGNAL]=180f-abs(pitch)
                }
            }
            for((side,label) in listOf(0 to "L",1 to "R")) {
                val h=point(23+side);val k=point(25+side);val a=point(27+side)
                if(h!=null && k!=null && a!=null) angle(h-k,a-k)?.let {result[knee(label)]=it}
            }
        }
        // 팔꿈치와 경로는 팔을 든 구간만. 가림 및 2D/3D 굽힘 불일치가 있으면 해당 팔은 유보한다.
        for((side,label) in listOf(0 to "L",1 to "R")) {
            val sh=point(11+side) ?: continue;val el=point(13+side) ?: continue;val wr=point(15+side) ?: continue
            val elevation=base["upperarm_vert_$label"] ?: continue
            if(!elevation.isFinite() || elevation !in 60f..110f) continue
            val imageAngle=angle(sh-el,wr-el) ?: continue
            val worldAngle=base["elbow_$label"] ?: continue
            if(!worldAngle.isFinite() || abs(imageAngle-worldAngle)>20f) continue
            result[elbow(label)]=worldAngle
            val shoulders=listOfNotNull(point(11),point(12));val hips=listOfNotNull(point(23),point(24))
            if(shoulders.size!=2 || hips.size!=2 || (shoulders[0]-shoulders[1]).norm<.025f || (hips[0]-hips[1]).norm<.015f) continue
            if(yaw==null || abs(yaw) !in 20f..80f) continue
            // 사선의 먼 팔 앞뒤 깊이는 몸통 뒤 가림과 겹친다. 가까운 팔만 경로를 확정한다.
            if(label != if(yaw>0f) "R" else "L") continue
            base["${FourExerciseForm.ARM_PLANE}_$label"]?.takeIf(Float::isFinite)?.let {result[plane(label)]=it}
        }
        return result
    }
}
