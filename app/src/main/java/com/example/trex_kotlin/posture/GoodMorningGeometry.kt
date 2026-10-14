package com.example.trex_kotlin.posture

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/** 굿모닝 전용 관측 피처. 다른 종목의 월드 피처와 힙힌지 카운터는 바꾸지 않는다. */
object GoodMorningGeometry {
    const val OBLIQUE_KNEE = "goodmorning_knee_oblique_L"
    const val SIDE_KNEE = "goodmorning_knee_side"
    const val SHOULDER_SWAY = "goodmorning_shoulder_asym"

    /** 가려지거나 화면을 벗어난 관절은 월드 추정값이 있어도 내보내지 않는다. */
    fun features(xy: FloatArray, visibility: FloatArray, width: Int, height: Int,
                 source: Map<String, Float>, up: Vec3 = Vec3(0f,1f,0f)): Map<String, Float> {
        if (xy.size < 66 || visibility.size < 33 || width <= 0 || height <= 0) return emptyMap()
        val out = HashMap<String, Float>()
        val scale = minOf(width, height).toFloat()
        fun point(i: Int, threshold: Float = 0.5f): P? {
            val x = xy[i * 2]; val y = xy[i * 2 + 1]; val v = visibility[i]
            if (!x.isFinite() || !y.isFinite() || !v.isFinite() || v < threshold || x !in 0f..1f || y !in 0f..1f) return null
            return P(x * width, y * height)
        }
        val c = source[ViewEstimator.FEAT_COS]; val s = source[ViewEstimator.FEAT_SIN]
        val yaw = if(c != null && s != null && c.isFinite() && s.isFinite() && hypot(c,s)>=ViewEstimator.MIN_R)
            Math.toDegrees(atan2(s.toDouble(),c.toDouble())).toFloat() else null
        val view = yaw?.let { ViewEstimator.classify(it) }
        fun leg(side: Int): List<P>? {
            val hip = point(23 + side) ?: return null
            val knee = point(25 + side) ?: return null
            val ankle = point(27 + side) ?: return null
            val thigh = (hip - knee).length; val shin = (ankle - knee).length
            if (minOf(thigh, shin) < maxOf(12f, scale * .035f) || thigh / shin !in .35f..2.8f) return null
            return listOf(hip, knee, ankle)
        }
        // 기존 자료에서 구분력이 확인된 사용자 왼쪽 사선만 월드 각도를 사용한다.
        // min(L,R) 대신 보이는 왼무릎을 써 반대쪽 굽힘에 가려지지 않는다.
        if (view == ViewEstimator.ViewClass.D || view == ViewEstimator.ViewClass.E) {
            if (leg(0) != null) source["knee_L"]?.takeIf { it.isFinite() && it in 0f..180f }
                ?.let { out[OBLIQUE_KNEE] = it }
        }
        // 측면의 2D 값은 사선 월드 각도와 단위 이름만 같을 뿐 별도 기준으로 다룬다.
        if (view == null || (yaw != null && kotlin.math.abs(yaw) in 46.2f..110f)) {
            val side = if(view == null) (0..1).filter { leg(it)!=null && point(11+it)!=null }
                .maxByOrNull { minOf(visibility[11+it],visibility[23+it],visibility[25+it],visibility[27+it]) }
                else if (yaw!! > 0f) 1 else 0
            val points = side?.let { leg(it) }
            val sh = side?.let { point(11 + it) }; val farSh = side?.let { point(12 - it, .3f) }
            if (side != null && points != null && sh != null && farSh != null) {
                val torso = (sh - points[0]).length
                if (torso >= maxOf(20f, scale * .06f) && (sh - farSh).length <= .45f * torso) {
                    angle(points[0], points[1], points[2])?.let { out["${SIDE_KNEE}_${if (side == 0) "L" else "R"}"] = it }
                    val suffix=if(side==0) "L" else "R"
                    // 먼 다리가 가려져 hip_mean이 사라져도 같은 쪽의 힙힌지 신호는 유지한다.
                    (source["hip_$suffix"]?.takeIf { it.isFinite() && it in 0f..180f }
                        ?: angle(sh,points[0],points[1]))?.let { out["goodmorning_visible_hip"]=it }
                    val shin=(points[1]-points[2]).length
                    out["goodmorning_hip_height_$suffix"]=(points[2].y-points[0].y)/shin
                    // 사진의 숙임 깊이를 수직 대비 몸통 각도로 확인한다. 폰 기울기는 관측된 위쪽 축으로 보정한다.
                    val projectedUp = P(up.x,-up.y)
                    if (projectedUp.x.isFinite() && projectedUp.y.isFinite() && projectedUp.length > .5f) {
                        val v = sh-points[0]
                        val dot = (v.x*projectedUp.x+v.y*projectedUp.y)/(torso*projectedUp.length)
                        out["goodmorning_torso_incl_$suffix"]=Math.toDegrees(acos(dot.coerceIn(-1f,1f)).toDouble()).toFloat()
                    }
                }
            }
        }
        if (view == ViewEstimator.ViewClass.C) {
            val ls = point(11); val rs = point(12); val lh = point(23); val rh = point(24)
            if (ls != null && rs != null && lh != null && rh != null &&
                (ls - rs).length >= maxOf(12f, scale * .04f) &&
                ((ls + rs) * .5f - (lh + rh) * .5f).length >= maxOf(16f, scale * .06f)) {
                source["shoulder_asym"]?.takeIf { it.isFinite() }?.let { out[SHOULDER_SWAY] = it }
                val la=point(27); val ra=point(28)
                val hipWidth=kotlin.math.abs(lh.x-rh.x)
                if(la!=null && ra!=null && hipWidth>=maxOf(16f,scale*.05f)) {
                    out["goodmorning_stance"]=kotlin.math.abs(la.x-ra.x)/hipWidth
                    for((i,label) in listOf(0 to "L",1 to "R")) {
                        val hip=if(i==0) lh else rh; val ankle=if(i==0) la else ra
                        val knee=point(25+i); val toe=point(31+i)
                        if(knee!=null && ankle.y-hip.y>=scale*.12f) {
                            val expected=hip.x+(ankle.x-hip.x)*(knee.y-hip.y)/(ankle.y-hip.y)
                            val sign=if((lh.x-rh.x)*(if(i==0) 1f else -1f)>0) 1 else -1
                            out["goodmorning_knee_out_$label"]=sign*(knee.x-expected)/hipWidth
                        }
                        if(toe!=null && (toe-ankle).length>=scale*.02f) {
                            Stance2d.toeAngle(xy,visibility,.5f,if(i==0) 'L' else 'R',width.toFloat()/height)
                                ?.let { out["goodmorning_toe_$label"]=it }
                        }
                    }
                }
            }
        }
        // 손가락 깍지나 접촉은 판정하지 않는다. 보이는 팔의 손목이 머리 부근에 있는지만 넓게 확인한다.
        for ((side,suffix) in listOf(0 to "L",1 to "R")) {
            val ear=point(7+side); val wrist=point(15+side); val elbow=point(13+side)
            val shoulder=point(11+side); val hip=point(23+side)
            if(ear!=null && wrist!=null && elbow!=null && shoulder!=null && hip!=null) {
                val torso=(shoulder-hip).length
                if(torso>=maxOf(30f,scale*.10f) && (elbow-shoulder).length>=scale*.025f &&
                    (wrist-elbow).length>=scale*.025f)
                    out["goodmorning_hand_head_$suffix"]=(wrist-ear).length/torso
            }
        }
        return out
    }

    private data class P(val x: Float, val y: Float) {
        operator fun minus(other: P) = P(x - other.x, y - other.y)
        operator fun plus(other: P) = P(x + other.x, y + other.y)
        operator fun times(k: Float) = P(x * k, y * k)
        val length get() = hypot(x, y)
    }
    private fun angle(a: P, b: P, c: P): Float? {
        val u = a - b; val v = c - b; val den = u.length * v.length
        if (den <= 1e-6f) return null
        return Math.toDegrees(acos(((u.x * v.x + u.y * v.y) / den).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }
}
