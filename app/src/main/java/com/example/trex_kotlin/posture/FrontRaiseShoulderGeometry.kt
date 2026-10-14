package com.example.trex_kotlin.posture

import kotlin.math.abs

/** 두 레이즈의 보이는 쪽 귀-어깨 간격. 첫 자세부터 으쓱해도 상대 변화에 묻히지 않게 원값도 보낸다. */
object FrontRaiseShoulderGeometry {
    fun features(xy:FloatArray, vis:FloatArray, width:Int, height:Int, source:Map<String,Float>,
                 up:Vec3=Vec3(0f,1f,0f)):Map<String,Float> {
        if(xy.size<66 || vis.size<33 || width<=0 || height<=0) return emptyMap()
        val view=ViewEstimator.estimate(listOf(source),1) ?: return emptyMap()
        if(view.cls==ViewEstimator.ViewClass.UNKNOWN || abs(view.yawDeg)>110f) return emptyMap()
        if(!listOf(up.x,up.y,up.z).all { it.isFinite() }) return emptyMap()
        val vertical=Vec3(up.x,up.y,0f).takeIf { it.norm>=.5f }?.unit() ?: return emptyMap()
        fun visible(i:Int)=vis[i].isFinite() && vis[i]>=.5f && xy[2*i].isFinite() && xy[2*i+1].isFinite() &&
            xy[2*i] in 0f..1f && xy[2*i+1] in 0f..1f
        if(!visible(0)) return emptyMap()
        val aspect=width.toFloat()/height
        fun p(i:Int)=Vec3(xy[2*i]*aspect,-xy[2*i+1],0f)
        val pitch=source["head_pitch"]
        if(pitch!=null && (!pitch.isFinite() || abs(pitch)>30f)) return emptyMap()
        val yaw=source["head_yaw"]
        if(yaw!=null && (!yaw.isFinite() || abs(yaw)>60f)) return emptyMap()
        // 머리를 옆으로 기울여 한쪽 귀가 낮아진 모습을 어깨 들림으로 부르지 않는다.
        if(visible(7) && visible(8)) {
            val ears=p(7)-p(8)
            if(ears.norm>=.025f && abs(ears dot vertical)/ears.norm>.423f) return emptyMap()
        }
        val out=mutableMapOf<String,Float>()
        for((side,n) in listOf("L" to 0,"R" to 1)) {
            if(!listOf(7+n,11+n,23+n).all(::visible)) continue
            val torso=p(11+n)-p(23+n);val len=torso.norm
            val axis=torso.unit() ?: continue
            if(len<.10f || (axis dot vertical)<.85f) continue
            if(pitch==null) {
                // 옆에서 먼 골반·귀가 가려지면 월드 고개각이 없을 수 있다. 같은 쪽 코-귀가 충분히 보일 때만 보완한다.
                if(abs(view.yawDeg)<55f) continue
                val face=p(0)-p(7+n);val rise=face dot vertical
                val horizontal=(face-vertical*rise).norm
                if(horizontal<len*.05f || abs(rise)>.65f*horizontal) continue
            }
            val neck=p(7+n)-p(11+n);val gap=neck dot axis
            val across=(neck-axis*gap).norm
            // 옆으로 떨어진 정도를 '좁아지는 귀-어깨 간격'으로 나누면 심한 으쓱임일수록 유보되는 문제가 생긴다.
            // 몸통 길이로 관측 가능성을 확인해 간격 0 부근도 유지한다. 극단적인 추적 오류만 유보한다.
            if(neck.norm>len*.9f || across>len*.40f || gap/len !in -.20f.. .85f) continue
            out["raise_shoulder_gap_$side"]=gap/len
            out["raise_shoulder_lift_$side"]=-gap/len
        }
        return out
    }
}
