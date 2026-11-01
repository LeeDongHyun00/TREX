package com.example.trex_kotlin.posture

import kotlin.math.abs
import kotlin.math.max

/** §132: 어깨·골반의 동반 하강/복귀를 확인한다. 관측 유보는 자세 위반과 다른 사유로 남긴다. */
class PushupCycleTracker(private val minAmp: Float) {
    private data class Frame(val t: Long, val shoulder: Float, val hip: Float, val ratio: Float,
                             val offset: Float, val knee: Float, val elbow: Float, val side: Float)
    private var anchor: Frame? = null
    private var previous: Frame? = null
    private var lastTime: Long? = null
    private var start = 0L
    private var low = 0f
    private var hipLow = 0f
    private var issue: String? = null
    private var recovering = false
    var pending = false
        private set
    var note: String? = null
        private set
    val rejected = ArrayList<RepRejected>()

    fun candidate(): RepCandidate? = anchor?.takeIf { pending }?.let { RepCandidate(start,low,it.shoulder) }
    fun resetCycle() { anchor=null; previous=null; lastTime=null; pending=false; issue=null; recovering=false; note=null }
    fun reset() { resetCycle(); rejected.clear() }

    private fun read(t: Long, f: Map<String,Float>): Frame? {
        if (f[FourExerciseForm.PUSHUP_POSTURE] != 1f) return null
        val keys=listOf(FourExerciseForm.PUSHUP_SIGNAL,HIP_HEIGHT,HIP_RATIO,FourExerciseForm.PUSHUP_HIP,
            FourExerciseForm.PUSHUP_KNEE,FourExerciseForm.PUSHUP_ELBOW,FloorChain.SIDE)
        if (keys.any { f[it]?.isFinite()!=true }) return null
        val a=Frame(t,f.getValue(keys[0]),f.getValue(keys[1]),f.getValue(keys[2]),f.getValue(keys[3]),
            f.getValue(keys[4]),f.getValue(keys[5]),f.getValue(keys[6]))
        return a.takeIf { it.ratio in .2f.. .9f && it.knee in 0f..180f && it.elbow in 0f..180f && it.side in setOf(0f,1f) }
    }
    private fun top(f: Frame) = f.elbow>=150f && f.knee>=150f && abs(f.offset)<=.08f
    private fun reject(t: Long, reason: String) {
        val a=anchor ?: return
        rejected += RepRejected(t,low,a.shoulder,0f,reason)
    }
    private fun unavailable(t: Long) {
        if (pending) reject(t,UNOBSERVED)
        pending=false; issue=null; recovering=true; previous=null
        note="판단 유보 · 필요한 관절이나 움직임을 놓쳤어요. 팔을 편 시작 자세로 돌아와 주세요"
    }

    fun onFrame(t: Long, features: Map<String,Float>): List<RepCycle> {
        val gap=lastTime?.let { t-it }; lastTime=t
        val f=read(t,features)
        if (f==null || gap!=null && (gap<=0 || gap>MAX_GAP_MS) || anchor?.let { f.side!=it.side }==true) {
            unavailable(t)
            // 쪽이 바뀌면 서로 다른 관절을 한 움직임으로 잇지 않는다. 다음 상단에서 새 쪽을 잡는다.
            if (f!=null && anchor?.side!=f.side) anchor=null
            return emptyList()
        }
        val a=anchor
        if (a==null || recovering) {
            // 가림 뒤 폰이나 발 위치가 바뀔 수 있다. 이전 높이를 강요하지 않고 관측된 시작 자세로 새 회를 연다.
            if (top(f)) { anchor=f; previous=f; recovering=false }
            else if (!recovering) note="팔을 펴고 몸통을 한 선으로 맞추면 다음 회를 시작해요"
            return emptyList()
        }
        val shoulderDrop=a.shoulder-f.shoulder
        val hipDrop=(a.hip-f.hip)/a.ratio
        if (!pending) {
            if (shoulderDrop>START_DROP || hipDrop>START_DROP || abs(f.offset)>.08f) {
                pending=true; start=(previous ?: a).t; low=minOf(a.shoulder,f.shoulder); hipLow=minOf(a.hip,f.hip); issue=null
            } else {
                // 상단에서 더 펴진 자세만 기준을 올린다. 천천히 내려가는 자세를 새 기준으로 흡수하지 않는다.
                if (top(f) && f.shoulder>a.shoulder) anchor=f
                previous=f
                return emptyList()
            }
        }
        low=minOf(low,f.shoulder); hipLow=minOf(hipLow,f.hip)
        val depth=a.shoulder-low
        // 오류 유지 시간 조건 없음. 관측된 정렬 이탈·동반 이동 불일치는 이 회가 끝날 때까지 보존한다.
        if (abs(f.offset)>.08f) issue=ALIGNMENT
        if (issue==null && abs(shoulderDrop-hipDrop)>max(COUPLING_ABS,depth*COUPLING_FRACTION)) issue=ASYNC
        val returnBand=max(RETURN_ABS,depth*RETURN_FRACTION)
        val returned=shoulderDrop<=returnBand && hipDrop<=returnBand && f.elbow>=150f
        if (!returned) {
            note=when(issue) {
                ALIGNMENT -> "몸통 정렬이 흐트러져 이번 동작은 세지 않아요 · 참고"
                ASYNC -> "어깨와 골반이 함께 움직이지 않아 이번 동작은 세지 않아요 · 참고"
                else -> if (depth>=minAmp) "어깨와 골반이 함께 올라와 팔을 펴면 1회로 세요" else null
            }
            previous=f
            return emptyList()
        }
        val reason=issue ?: if (depth<minAmp) SHALLOW
            else if ((a.hip-hipLow)/a.ratio<depth*.55f) ASYNC else null
        val cycle=if(reason==null) RepCycle(t,start,low,a.shoulder) else null
        if(reason!=null) reject(t,reason)
        note=when(reason) {
            ALIGNMENT -> "직전 동작은 몸통 정렬 이탈로 세지 않았어요 · 참고"
            ASYNC -> "직전 동작은 어깨·골반의 동반 움직임이 부족해 세지 않았어요 · 참고"
            SHALLOW -> "직전 동작은 내려간 범위가 부족해 세지 않았어요 · 참고"
            else -> null
        }
        pending=false; issue=null; anchor=if(top(f)) f else null; previous=f
        return listOfNotNull(cycle)
    }

    companion object {
        const val VERSION="pushup_coupled_v1"
        const val HIP_HEIGHT="pushup_pelvis_height"
        const val HIP_RATIO="pushup_pelvis_position"
        const val UNOBSERVED="pushup_unobserved"
        const val ALIGNMENT="pushup_alignment"
        const val ASYNC="pushup_async"
        const val SHALLOW="pushup_shallow"
        const val MAX_GAP_MS=1500L
        private const val START_DROP=.03f
        private const val COUPLING_ABS=.035f
        private const val COUPLING_FRACTION=.25f
        private const val RETURN_ABS=.015f
        private const val RETURN_FRACTION=.12f
    }
}
