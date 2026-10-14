package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 푸시업의 관측 가능한 2D 기하. 자세의 통과 여부와 촬영 관측 조건을 분리한다. */
object PushupGeometry {
    const val HIP = "pushup_hip_offset"
    const val HEAD = "pushup_head_pitch"
    const val NECK = "pushup_neck_pitch"
    const val KNEE = "pushup_knee_angle"
    const val KNEE_CLEARANCE = "pushup_knee_clearance"
    const val HAND_WIDTH = "pushup_hand_width"
    const val READY = "pushup_side_ready"
    const val SIDE = "pushup_visible_side"

    fun features(xy: FloatArray, vis: FloatArray, width: Int, height: Int): Map<String, Float> {
        if (xy.size != 66 || vis.size != 33 || width <= 0 || height <= 0) return emptyMap()
        fun visible(i: Int, cut: Float = .5f) = vis[i].isFinite() && vis[i] >= cut &&
            xy[i*2].isFinite() && xy[i*2+1].isFinite() && xy[i*2] in 0f..1f && xy[i*2+1] in 0f..1f
        fun p(i: Int) = doubleArrayOf(xy[i*2].toDouble()*width, xy[i*2+1].toDouble()*height)
        fun dist(a: DoubleArray, b: DoubleArray) = hypot(a[0]-b[0], a[1]-b[1])
        val result = mutableMapOf<String, Float>()

        // 손 너비는 양쪽 어깨·손목이 분리돼 보일 때만 잰다. 측면의 겹친 손을 3D로 추정하지 않는다.
        if (listOf(11,12,15,16,23,24).all { visible(it) }) {
            val ls=p(11); val rs=p(12); val lw=p(15); val rw=p(16)
            val sh=doubleArrayOf((ls[0]+rs[0])/2,(ls[1]+rs[1])/2)
            val hp=doubleArrayOf((p(23)[0]+p(24)[0])/2,(p(23)[1]+p(24)[1])/2)
            val torso=dist(sh,hp); val shoulderWidth=dist(ls,rs)
            if (torso >= 20 && shoulderWidth >= max(20.0,torso*.4)) {
                val ux=(rs[0]-ls[0])/shoulderWidth; val uy=(rs[1]-ls[1])/shoulderWidth
                val bodyAlongShoulders=abs((hp[0]-sh[0])*ux+(hp[1]-sh[1])*uy)/torso
                val handSpan=abs((rw[0]-lw[0])*ux+(rw[1]-lw[1])*uy)
                // 투영 폭과 축 분리의 초기 관측 조건이며, 올바른 손 너비의 임계값은 아니다.
                // 손 간격이 좁다는 이유로 관측을 버리면 가장 좁은 손이 검사에서 빠진다.
                if (bodyAlongShoulders <= .5) {
                    result[HAND_WIDTH]=(handSpan/shoulderWidth).toFloat()
                    for ((s, label) in listOf(0 to "L", 1 to "R")) {
                        if (listOf(11+s,13+s,23+s).all { visible(it) }) {
                            val upper=dist(p(11+s),p(13+s))
                            if (upper >= torso*.25) result["pushup_elbow_flare_$label"] =
                                FloorFeatureExtractor.ang(p(23+s),p(11+s),p(13+s)).toFloat()
                        }
                    }
                }
            }
        }

        // 귀, 어깨, 골반, 무릎, 발목, 손목, 발끝. 서로 다른 쪽의 관절을 섞지 않는다.
        val sides=listOf(intArrayOf(7,11,23,25,27,15,31),intArrayOf(8,12,24,26,28,16,32))
        val side=sides.filter { s -> (1..4).all { visible(s[it]) } }
            .maxByOrNull { s -> (1..4).minOf { vis[s[it]] } } ?: return result
        val earId=side[0]; val shoulderId=side[1]; val hipId=side[2]; val kneeId=side[3]; val ankleId=side[4]
        val shoulder=p(shoulderId); val hip=p(hipId); val knee=p(kneeId); val ankle=p(ankleId)
        val torso=dist(shoulder,hip); val length=dist(shoulder,ankle)
        if (torso < 20 || length < torso*1.6 || length < min(width,height)*.25) return result
        if (!visible(11,.2f) || !visible(12,.2f)) return result
        val ratio=length/max(dist(p(11),p(12)),1.0)
        if (ratio < 8 || abs(ankle[0]-shoulder[0])/length < .7) return result
        result.remove(HAND_WIDTH)
        val suffix=if (shoulderId==11) "L" else "R"
        result[READY]=1f
        result[SIDE]=if (shoulderId==11) 0f else 1f
        result["${KNEE}_$suffix"]=FloorFeatureExtractor.ang(hip,knee,ankle).toFloat()
        result["${HIP}_$suffix"]=FloorFeatureExtractor.devUp(hip,shoulder,ankle).toFloat()
        if (visible(side[5])) {
            val wrist=p(side[5])
            result["pushup_hand_along_torso_$suffix"]=(((wrist[0]-shoulder[0])*(hip[0]-shoulder[0])+
                (wrist[1]-shoulder[1])*(hip[1]-shoulder[1]))/(torso*torso)).toFloat()
        }

        // 손목-발끝 선과 무릎 사이의 높이. 접지 자체를 확정하는 값이 아니며 무릎 각도와 함께 해석한다.
        if (visible(side[5]) && visible(side[6])) {
            val wrist=p(side[5]); val toe=p(side[6]); val support=dist(wrist,toe)
            val footLength=dist(ankle,toe)
            if (support >= max(torso*1.6,min(width,height)*.25) && footLength in torso*.03..torso*.8) {
                val ux=(toe[0]-wrist[0])/support; val uy=(toe[1]-wrist[1])/support
                var nx=-uy; var ny=ux
                var hipHeight=(hip[0]-wrist[0])*nx+(hip[1]-wrist[1])*ny
                if (hipHeight < 0) { nx=-nx; ny=-ny; hipHeight=-hipHeight }
                val along=((knee[0]-wrist[0])*ux+(knee[1]-wrist[1])*uy)/support
                if (hipHeight >= torso*.05 && along in .1.. .95) {
                    result["${KNEE_CLEARANCE}_$suffix"]=(((knee[0]-wrist[0])*nx+(knee[1]-wrist[1])*ny)/torso).toFloat()
                }
            }
        }

        if (visible(earId) && visible(0)) {
            val ear=p(earId); val nose=p(0); val faceLength=dist(ear,nose)
            if (faceLength in torso*.03..torso*.65) {
                val ux=(shoulder[0]-hip[0])/torso; val uy=(shoulder[1]-hip[1])/torso
                var nx=-uy; var ny=ux
                if (ny>0) { nx=-nx; ny=-ny }
                val fx=nose[0]-ear[0]; val fy=nose[1]-ear[1]
                result["${HEAD}_$suffix"]=Math.toDegrees(kotlin.math.atan2(fx*ux+fy*uy,-(fx*nx+fy*ny))).toFloat()
                val ex=ear[0]-shoulder[0]; val ey=ear[1]-shoulder[1]
                if (dist(ear,shoulder) >= torso*.08) {
                    result["${NECK}_$suffix"]=Math.toDegrees(kotlin.math.atan2(ex*nx+ey*ny,ex*ux+ey*uy)).toFloat()
                }
            }
        }
        return result
    }
}
