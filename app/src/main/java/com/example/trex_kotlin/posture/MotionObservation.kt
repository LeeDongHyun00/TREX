package com.example.trex_kotlin.posture

/** 관측 시각은 카메라의 단조 시계를 따른다. 벽시계 변경은 반복 간격을 바꾸지 않는다. */
class CaptureTimeline {
    private var originNs: Long? = null
    private var originEpochMs = 0L
    private var lastNs: Long? = null
    fun map(timestampNs: Long, epochMs: Long): Long? {
        if (timestampNs <= 0L || lastNs?.let { timestampNs <= it } == true) return null
        if (originNs == null) { originNs = timestampNs; originEpochMs = epochMs }
        lastNs = timestampNs
        return originEpochMs + (timestampNs - originNs!!) / 1_000_000L
    }
}

/** 기존 8프레임 규칙 창의 시간 길이를 고속 카운터 때문에 바꾸지 않는다. */
class SampleCadence(private val intervalMs: Long = 300L) {
    private var last: Long? = null
    fun accept(t: Long): Boolean {
        if (last?.let { t <= it || t - it < intervalMs } == true) return false
        last = t; return true
    }
    fun reset() { last = null }
}

data class DirectionObservation(val key: String, val atMs: Long, val text: String, val landmarks: Set<Int>, val value: Float)

/** 발 기준 부호가 직접 관측될 때만 좌우 방향을 설명한다. 컷은 표시 잡음 억제용이며 정오 임계값이 아니다. */
class KneeDirectionObserver(private val exercise: String) {
    private data class Sustained(val sign: Int, val since: Long, var samples: Int)
    private val states = HashMap<String, Sustained>()
    private var lastAt: Long? = null
    private val enabled = exercise in setOf("바벨 스쿼트", "스텝 포워드 다이나믹 런지", "바벨 런지", "사이드 런지", "크로스 런지")
    fun reset() { states.clear(); lastAt = null }
    fun add(t: Long, features: Map<String, Float>, active: Boolean): List<DirectionObservation> {
        if (!enabled || !active || lastAt?.let { t <= it || t - it > 450 } == true) { reset(); if (!active) return emptyList() }
        lastAt = t
        if (!enabled) return emptyList()
        return buildList {
            for ((side, name, index) in listOf(Triple("L","왼쪽",0),Triple("R","오른쪽",1))) {
                val v = features["knee_track_$side"]?.takeIf(Float::isFinite)
                val knee = features["knee_$side"]
                // 다리를 펴고 서 있을 때의 작은 위치 차이로 교정하지 않는다.
                val sign = if (v == null || knee == null || knee > 155f) 0 else if (v > .15f) 1 else if (v < -.15f) -1 else 0
                if (sign == 0) { states.remove(side); continue }
                val state = states[side]?.takeIf { it.sign == sign } ?: Sustained(sign,t,0).also { states[side] = it }
                state.samples++
                if (t - state.since >= 220 && state.samples >= 3) add(DirectionObservation(
                    "knee_$side:$sign",t,
                    "참고: $name 무릎이 발끝을 따라 그은 선보다 ${if (sign > 0) "바깥쪽" else "안쪽"}에 보여요. 무릎과 발끝의 진행 방향을 확인해 주세요.",
                    setOf(25+index,27+index,29+index,31+index),v!!))
            }
        }
    }
}

/** 발화 대기열 대신 현재 증거를 고른다. 늦어진 지시는 폐기하고, 같은 안내는 8초 간격을 둔다. */
class MotionSpeechArbiter {
    private val lastByKey = HashMap<String, Long>()
    private var last = Long.MIN_VALUE
    fun offer(t: Long, key: String, observedAt: Long, ready: Boolean): Boolean {
        if (!ready || t - observedAt !in 0..900 || last != Long.MIN_VALUE && t - last < 3000 ||
            lastByKey[key]?.let { t - it < 8000 } == true) return false
        last = t; lastByKey[key] = t; return true
    }
    fun reset() { lastByKey.clear(); last = Long.MIN_VALUE }
}

/** 증거가 설명할 수 있는 범위 안에서만 지시한다. 조건명의 고정된 한 방향 문구를 피한다. */
object EvidenceCues {
    fun forRule(rule: PostureRule, direction: Direction?): CoachCue? {
        if (rule.exercise in FloorTemporal.exercises) return null // 바닥 경로는 검토된 2D 측정 설명을 유지한다.
        val feature = rule.baseFeature
        val upper = if (direction == Direction.OPPOSITE) (rule.oppositeGuard?.op ?: if (rule.op == "<") ">" else "<") == ">" else rule.op == ">"
        val side = when { feature.endsWith("_L") -> "왼쪽 "; feature.endsWith("_R") -> "오른쪽 "; else -> "" }
        val part = when {
            feature.startsWith("knee_out") || feature.startsWith("kneefoot") || feature.startsWith("knee_lat") -> "무릎"
            feature.startsWith("elbow_torso") -> "팔꿈치"
            feature.startsWith("head_") || feature.startsWith("face_") -> "고개"
            feature.startsWith("torso_") || feature.startsWith("shoulder_asym") -> "상체"
            else -> return measuredCue(rule,side,upper)
        }
        val observation = when {
            rule.stat in setOf("range","std") -> "$side$part 움직임의 ${if (rule.stat == "range") "범위가" else "변동이"} 참고 기준보다 ${if (upper) "커요" else "작아요"}"
            feature.startsWith("knee_out") || feature.startsWith("knee_lat") -> "$side 무릎의 측방 위치가 참고 범위를 벗어났어요"
            feature.startsWith("kneefoot") -> "$side 무릎과 발의 수평 투영 방향 차이가 참고 범위를 벗어났어요"
            feature.startsWith("elbow_torso") -> "$side 팔꿈치가 몸통에서 ${if (upper) "멀어져" else "가까워져"} 있어요"
            feature == "head_pitch" -> "고개가 참고 범위보다 ${if (upper) "들려" else "숙여져"} 있어요"
            feature == "torso_roll" -> "몸통이 ${if (upper) "왼쪽" else "오른쪽"}으로 기울어 보여요"
            feature == "torso_pitch" -> "몸통이 참고 범위보다 ${if (upper) "앞" else "뒤"}쪽으로 기울어 보여요"
            feature == "shoulder_asym" -> "${if (upper) "왼쪽" else "오른쪽"} 어깨가 상대적으로 높아 보여요"
            feature.startsWith("torso_") -> "몸통 기울기가 참고 범위를 벗어났어요"
            else -> "고개와 몸통의 상대 방향이 참고 범위를 벗어났어요"
        }
        val instruction = when (part) {
            "무릎" -> "무릎과 발끝의 진행 방향을 확인해 주세요."
            "팔꿈치" -> "운동 목표에 맞는 팔꿈치 위치를 확인해 주세요."
            "고개" -> "고개를 편안한 중립 위치로 조절해 주세요."
            else -> "운동 동작에 맞는 몸통 정렬을 확인해 주세요."
        }
        return CoachCue(part,"관측한 구간에서 ${observation.trim()}. $instruction", "초반 측정과 비교해 ${observation.trim()}. $instruction")
    }

    private fun measuredCue(rule: PostureRule, side: String, upper: Boolean): CoachCue? {
        val f=rule.baseFeature
        val measurement = when {
            f.startsWith("elbow_mean")||f.startsWith("elbow_minside") -> "팔꿈치" to "팔꿈치 각도"
            f.startsWith("knee_mean")||f.startsWith("knee_minside")||f.startsWith("knee_maxside") -> "무릎" to "무릎 각도"
            f=="knee_asym" -> "무릎" to "좌우 무릎 각도 차이"
            f=="hip_asym" -> "골반" to "좌우 고관절 각도 차이"
            f=="forearm_vert_mean" -> "팔" to "전완의 중력축 대비 각도"
            f=="grip_w" -> "손" to "양손 간격"
            f=="hand_h_asym" -> "손" to "좌우 손 높이 차이"
            f=="stance_w" -> "발" to "양발 간격"
            f=="knee_elbow_dist" -> "무릎·팔꿈치" to "무릎과 팔꿈치 사이 거리"
            f=="hip_below_knee" -> "골반" to "무릎 대비 골반 높이"
            f=="heel_lift" -> "발" to "발 안의 뒤꿈치·발끝 상대 높이"
            f=="palm_fwd_hip" -> "손" to "골반 대비 손의 전후 위치"
            f=="palm_h_rel" -> "손" to "손 높이 비율"
            f=="palm_head_dist" -> "손" to "손과 머리 사이 거리"
            f=="palm_lat" -> "손" to "양손 중심의 측방 위치"
            f=="sh_over_hip_fwd" -> "상체" to "골반 대비 어깨의 전후 위치"
            f.startsWith("shoulder_h") -> "어깨" to "골반 대비 어깨 높이"
            f=="shoulder_R"||f=="shoulder_L" -> "어깨" to "팔과 몸통 사이 각도"
            else -> return null
        }
        val (part,label)=measurement
        val change=if(upper) "커요" else "작아요"
        val statistic=when(rule.stat) { "range" -> "움직임 범위가"; "std" -> "변동이"; else -> "측정값이" }
        val observation="$side$label $statistic 참고 기준보다 $change"
        val instruction=when {
            rule.stat in setOf("range","std") -> "의도한 동작 범위를 일정하게 유지해 주세요."
            f=="grip_w" -> "이 운동의 손 간격을 확인해 주세요."
            f=="stance_w" -> "이 운동의 발 간격을 확인해 주세요."
            else -> "$part 위치와 움직임을 확인해 주세요."
        }
        return CoachCue(part,"${observation.trim()}. $instruction", "초반 측정과 비교해 ${observation.trim()}. $instruction")
    }
}

data class MotionTraceFrame(val tMs: Long, val phase: String, val features: Map<String, Float>, val completed: Int,
    val inferMs: Long, val observations: List<String> = emptyList())

/** 재생용 입력을 유한 크기로 보관한다. 일반 세트 로그의 300ms 통계와 분리된다. */
class MotionTrace(private val limit: Int = 6000) {
    private val frames = ArrayList<MotionTraceFrame>()
    var dropped = 0; private set
    @Synchronized fun add(frame: MotionTraceFrame) { if (frames.size < limit) frames += frame else dropped++ }
    @Synchronized fun snapshot() = frames.toList()
    @Synchronized fun clear() { frames.clear(); dropped = 0 }
}

enum class MotionActivity(val label: String) {
    WAITING("시작 자세 관측"), MOVING("선택한 동작 추적"), STILL("움직임 대기"),
    UNMATCHED("준비 또는 다른 움직임"), UNOBSERVABLE("필요한 관절 미확인"), PAUSED("일시정지"),
}

/** 관측된 움직임이 현재 사이클에 연결되는지 표시한다. 생활 행동의 이름이나 피로 원인을 추측하지 않는다. */
class MotionActivityTracker {
    private var previous: Map<String,Float> = emptyMap()
    private var movementAt: Long? = null
    private var previousAt: Long? = null
    fun reset() { previous=emptyMap(); movementAt=null; previousAt=null }
    fun update(t: Long, contract: MotionContract?, phase: MotionPhase, features: Map<String,Float>): MotionActivity {
        if (phase == MotionPhase.PAUSED) { reset(); return MotionActivity.PAUSED }
        if (phase == MotionPhase.UNOBSERVABLE || contract == null || features.isEmpty()) { reset(); return MotionActivity.UNOBSERVABLE }
        if (previousAt?.let { t<=it || t-it>450 } == true) reset()
        val moved=contract.channels.any { c ->
            val x=features[c.primary]; val p=previous[c.primary]
            x!=null && p!=null && kotlin.math.abs(x-p) > c.travel*.06f
        }
        if(movementAt==null||moved) movementAt=t
        previous=features; previousAt=t
        return when {
            phase in setOf(MotionPhase.OUTBOUND,MotionPhase.RETURNING,MotionPhase.REARM) -> MotionActivity.MOVING
            t-movementAt!!>1500 -> MotionActivity.STILL
            moved -> MotionActivity.UNMATCHED
            else -> MotionActivity.WAITING
        }
    }
}
