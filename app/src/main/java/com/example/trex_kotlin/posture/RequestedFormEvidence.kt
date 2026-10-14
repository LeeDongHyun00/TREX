package com.example.trex_kotlin.posture

/** 네 종목에서 실제 관측한 지속 오류를 보존한다. 전체 중앙값이나 뒤의 정상 복귀로 지우지 않는다. */
object RequestedFormEvidence {
    private const val HOLD_MS = 450L
    private const val MAX_GAP_MS = 450L
    private const val HOLD_SAMPLES = 3
    private data class Reading(val raw:Float, val value:Float, val direction:FormDirection, val atMs:Long)

    /** 완료된 기존 종목은 원래 판정 그대로. 가림·방향 불명은 오류의 근거로 사용하지 않는다. */
    fun review(outcome:RepFormOutcome, top:List<Pair<Long,Map<String,Float>>>,
               cycle:List<Pair<Long,Map<String,Float>>>, bottom:List<Pair<Long,Map<String,Float>>>,
               observed:(RepFormCheck,Pair<Long,Map<String,Float>>)->Boolean):RepFormOutcome {
        val c=outcome.check
        if(!RequestedFormCoaching.isTarget(c.exercise) || !c.gates || c.status==RuleStatus.EXCLUDE) return outcome
        if(c.exercise=="굿모닝" && (c.feature.startsWith("goodmorning_knee_side_") || c.feature.startsWith("goodmorning_hip_height_")))
            return squat(outcome,top,cycle,bottom,observed)
        if(!supported(c.feature) || c.stat!=RepFormStat.MEDIAN) return outcome
        // 이미 충분한 반복 관측으로 잡은 위반은 유지한다. 아래는 평균에 묻힌 연속 오류를 보완한다.
        if(outcome.verdict==Verdict.VIOLATION && outcome.gate) return outcome
        val reference=when(c.ref) {
            RepFormRef.NONE -> null
            RepFormRef.REP_DELTA -> reference(c,top,observed) ?: return outcome
            else -> return outcome
        }
        val run=confirmed(cycle,HOLD_SAMPLES,HOLD_MS) { f ->
            if(!observed(c,f)) null else {
                val raw=f.second.getValue(c.feature)
                val value=raw-(reference ?: 0f)
                val direction=c.judge(value)
                if(direction==null || (c.gateHi!=null && (direction!=FormDirection.HIGH || value<c.gateHi ||
                        (c.gateAbsMin!=null && raw<c.gateAbsMin)))) null
                else Reading(raw,value,direction,f.first)
            }
        } ?: return outcome
        if(reference!=null && !connected(c,top,cycle,run.last().atMs,observed)) return outcome
        return RepFormOutcome(c,Verdict.VIOLATION,median(run.map{it.value}),median(run.map{it.raw}),reference,
            run.first().direction,run.size,gate=true)
    }

    private fun supported(key:String)=key.startsWith("raise_shoulder_gap_") || key.startsWith("raise_shoulder_lift_") ||
        key=="raise_head_turn" || key=="torso_pitch" || key.startsWith("goodmorning_hand_head_") || key.startsWith("goodmorning_toe_") ||
        key.startsWith("pushup_knee_support_") || key.startsWith("pushup_hip_offset_") ||
        key=="pushup_face_yaw" || key=="pushup_hand_width"

    private fun reference(c:RepFormCheck,top:List<Pair<Long,Map<String,Float>>>,
                          observed:(RepFormCheck,Pair<Long,Map<String,Float>>)->Boolean):Float? {
        val valid=top.filter { observed(c,it) }
        if(valid.size<c.minReferenceSamples || valid.size.toFloat()/top.size.coerceAtLeast(1)<c.minCoverage) return null
        if(valid.zipWithNext().any { (a,b)->b.first-a.first !in 1..MAX_GAP_MS }) return null
        return median(valid.map{it.second.getValue(c.feature)})
    }

    /** 상대 변화는 기준 자세에서 오류 관측까지 연결돼 있어야 한다. 그 뒤 가림은 확정한 오류를 지우지 않는다. */
    private fun connected(c:RepFormCheck,top:List<Pair<Long,Map<String,Float>>>,
                          cycle:List<Pair<Long,Map<String,Float>>>,end:Long,
                          observed:(RepFormCheck,Pair<Long,Map<String,Float>>)->Boolean):Boolean {
        val start=top.lastOrNull { observed(c,it) } ?: return false
        val valid=(listOf(start)+cycle.filter{it.first>start.first && it.first<=end && observed(c,it)})
        return valid.last().first==end && valid.zipWithNext().all{(a,b)->b.first-a.first in 1..750L}
    }

    private fun squat(o:RepFormOutcome,top:List<Pair<Long,Map<String,Float>>>,cycle:List<Pair<Long,Map<String,Float>>>,bottom:List<Pair<Long,Map<String,Float>>>,
                      observed:(RepFormCheck,Pair<Long,Map<String,Float>>)->Boolean):RepFormOutcome {
        val c=o.check;val side=c.feature.takeLast(1)
        val knee=c.copy(feature="goodmorning_knee_side_$side")
        val hip=c.copy(feature="goodmorning_hip_height_$side")
        val base=reference(hip,top,observed) ?: return o.copy(gate=false)
        // 무릎과 골반의 별도 중앙값을 조합하지 않는다. 같은 쪽·같은 프레임의 두 조건이어야 한다.
        // 하단은 짧으므로 2개 관측과 250 ms 지속을 요구한다. 한 프레임 튐은 제외 근거가 아니다.
        val run=confirmed(bottom,2,250L) { f ->
            if(!observed(knee,f) || !observed(hip,f)) null else {
                val k=f.second.getValue(knee.feature);val h=f.second.getValue(hip.feature)
                if(k>=135f || h-base>=-.50f) null else {
                    val raw=f.second.getValue(c.feature)
                    Reading(raw,if(c.ref==RepFormRef.REP_DELTA)raw-base else raw,FormDirection.LOW,f.first)
                }
            }
        } ?: return o.copy(gate=false)
        if(!connected(hip,top,cycle,run.last().atMs,observed)) return o.copy(gate=false)
        return RepFormOutcome(c,Verdict.VIOLATION,median(run.map{it.value}),median(run.map{it.raw}),
            if(c.ref==RepFormRef.REP_DELTA)base else null,FormDirection.LOW,run.size,gate=true)
    }

    private fun confirmed(frames:List<Pair<Long,Map<String,Float>>>,samples:Int,holdMs:Long,
                          read:(Pair<Long,Map<String,Float>>)->Reading?):List<Reading>? {
        val run=ArrayList<Reading>();var start=0L;var last:Long?=null
        for(f in frames) {
            val value=read(f)
            if(value==null) { run.clear();last=null;continue }
            if(last==null || f.first-last !in 1..MAX_GAP_MS || run.lastOrNull()?.direction!=value.direction) {
                run.clear();start=f.first
            }
            run+=value;last=f.first
            if(run.size>=samples && f.first-start>=holdMs) return run.toList()
        }
        return null
    }

    private fun median(values:List<Float>):Float {
        val a=values.sorted();val n=a.size
        return if(n%2==1)a[n/2] else (a[n/2-1]+a[n/2])/2f
    }
}
