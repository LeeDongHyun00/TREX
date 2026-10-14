package com.example.trex_kotlin.posture

/** 네 종목만 관측 피처를 보강한다. 나머지는 원본 맵을 그대로 반환한다. */
object RequestedFormFeatures {
    fun augment(ex: String, features: Map<String,Float>, xy: FloatArray, vis: FloatArray, width: Int, height: Int,
                world: FloatArray? = null, up: Vec3 = Vec3(0f,1f,0f)): Map<String,Float> = when(ex) {
        "굿모닝" -> GoodMorningGeometry.features(xy,vis,width,height,features,up).let { extra ->
            features.filterKeys { !it.startsWith("goodmorning_") } + extra + extra["goodmorning_visible_hip"]?.let { mapOf("hip_mean" to it) }.orEmpty()
        }
        "프런트 레이즈", "사이드 레터럴 레이즈" ->
            raiseFeatures(features,xy,vis,width,height) + RaisePhotoGeometry.features(xy,vis,width,height,features,world,up)
        else -> features
    }

    private fun raiseFeatures(source: Map<String,Float>, xy: FloatArray, vis: FloatArray, width:Int,height:Int):Map<String,Float> {
        val out=source.filterKeys { !it.startsWith("raise_") }.toMutableMap()
        fun visible(vararg indices:Int) = xy.size>=66 && vis.size>=33 && width>0 && height>0 && indices.all {
            vis[it].isFinite() && vis[it]>=.5f && xy[2*it].isFinite() && xy[2*it+1].isFinite() &&
                xy[2*it] in 0f..1f && xy[2*it+1] in 0f..1f
        }
        for((side,i) in listOf("L" to 0,"R" to 1)) {
            if(!visible(11+i,13+i,15+i)) out.remove("elbow_$side")
            if(!visible(11+i,13+i)) out.remove("upperarm_vert_$side")
            if(!visible(23+i,25+i,27+i)) out.remove("knee_$side")
        }
        // 가려진 팔의 월드 추정이 평균을 붙잡지 않도록 실제 보이는 위팔만으로 카운트 신호를 만든다.
        val arms=listOfNotNull(out["upperarm_vert_L"],out["upperarm_vert_R"]).filter { it.isFinite() && it in 0f..180f }
        out.remove("upperarm_vert_mean")
        if(arms.isNotEmpty()) out["upperarm_vert_mean"]=arms.average().toFloat()
        if(!visible(11,12,23,24)) out.remove("torso_pitch")
        return out
    }
}
