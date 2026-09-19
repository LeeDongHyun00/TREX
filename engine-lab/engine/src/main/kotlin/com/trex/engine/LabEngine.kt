package com.trex.engine

import kotlin.math.abs
import kotlin.math.hypot

const val ENGINE_VERSION = "trex-observation/2.0.0"
const val MP_LANDMARK_COUNT = 33
val SCREEN_UP = Vec3(0f, 1f, 0f)

/** 화면 좌표는 미러 전, 월드 좌표는 MediaPipe 원본 미터 단위다. */
data class LandmarkFrame(val xy: FloatArray, val world: FloatArray, val visibility: FloatArray, val width: Int, val height: Int)

class LandmarkFeatures {
    private val floor = FloorFeatureExtractor()
    private var selectedFloorSide:Int?=null
    fun reset() { floor.reset();selectedFloorSide=null }
    fun extract(frame: LandmarkFrame, isFloor: Boolean): Map<String, Float> {
        if (frame.xy.size != 66 || frame.world.size != 99 || frame.visibility.size != 33 || frame.width <= 0 || frame.height <= 0) {
            reset(); return emptyMap()
        }
        val visibility = FloatArray(33) { i ->
            val v = frame.visibility[i]
            if (v.isFinite() && frame.xy[2*i] in 0f..1f && frame.xy[2*i+1] in 0f..1f &&
                (0..2).all { frame.world[3*i+it].isFinite() }) v else 0f
        }
        val points = Array<Vec3?>(33) { i -> if (visibility[i] >= .5f)
            Vec3(frame.world[3*i]*100f, -frame.world[3*i+1]*100f, -frame.world[3*i+2]*100f) else null }
        val joints = Joints.SINGLE.mapValues { points[it.value] }.toMutableMap()
        for ((name, pair) in Joints.PAIR) {
            val a = points[pair.first]; val b = points[pair.second]
            joints[name] = if (a != null && b != null) mid(a,b) else null
        }
        val standing = PoseFrame(joints).features() + ViewEstimator.frameFeatures(joints)
        val out = if (isFloor) floor.compute(frame.xy, visibility, frame.width, frame.height) +
            floor.sideFeatures(frame.xy, visibility, frame.width, frame.height) else standing
        return out.toMutableMap().apply {
            if (isFloor) {
                // 보이는 동일 측만 사용한다. 어깨·팔꿈치·손목을 반대쪽과 조합하지 않는다.
                val available = listOf("L" to 0, "R" to 1).filter { (_, o) ->
                    listOf(11,23,25).all { visibility[it+o] >= .5f }
                }
                // 작은 confidence 차이로 측을 매 프레임 바꾸면 진행 중인 반복이 계속 취소된다.
                val side = available.firstOrNull { it.second==selectedFloorSide }
                    ?: available.maxByOrNull { (_,o)->listOf(11,13,15,23,25).minOf { visibility[it+o] } }
                selectedFloorSide=side?.second
                side?.let { (name, o) ->
                    put("floor_side", o.toFloat())
                    out["elbow_ang_$name"]?.let { put("visible_elbow_angle", it) }
                    val dx = (frame.xy[(11+o)*2] - frame.xy[(23+o)*2])*frame.width
                    val dy = (frame.xy[(23+o)*2+1] - frame.xy[(11+o)*2+1])*frame.height
                    val torso = hypot(dx,dy)
                    if(torso > 10f) {
                        put("body_horizontal",abs(dx)/torso)
                        put("torso_ground_angle", Math.toDegrees(kotlin.math.atan2(dy.toDouble(), abs(dx).toDouble())).toFloat())
                        if(visibility[15+o]>=.5f) put("wrist_shoulder_d",hypot(
                            (frame.xy[(15+o)*2]-frame.xy[(11+o)*2])*frame.width,
                            (frame.xy[(15+o)*2+1]-frame.xy[(11+o)*2+1])*frame.height)/torso)
                        for(key in listOf("hip_ang","knee_ang","hip_dev_ankle","hip_dev_knee")) {
                            remove(key)
                            out["${key}_$name"]?.let { put(key,it) }
                        }
                    }
                }
                if (listOf(11,12,23,24,25,26).all { visibility[it] >= .6f }) {
                    val sx = (frame.xy[22]+frame.xy[24])/2; val sy = (frame.xy[23]+frame.xy[25])/2
                    val hx = (frame.xy[46]+frame.xy[48])/2; val hy = (frame.xy[47]+frame.xy[49])/2
                    val tx = (sx-hx)*frame.width; val ty = (sy-hy)*frame.height
                    val length = hypot(tx,ty)
                    if (length > 10) put("scissor_signed", ((frame.xy[50]-frame.xy[52])*frame.width * -ty +
                        (frame.xy[51]-frame.xy[53])*frame.height * tx) / (length*length))
                }
            }
            // 바닥 유지 시간은 최소한 어깨–골반의 수평 투영이 있어야 관측한다.
            if (listOf(11,12,23,24).all { visibility[it] >= .5f }) {
                val sx = (frame.xy[22]+frame.xy[24])/2; val sy = (frame.xy[23]+frame.xy[25])/2
                val hx = (frame.xy[46]+frame.xy[48])/2; val hy = (frame.xy[47]+frame.xy[49])/2
                val dx = (sx-hx)*frame.width; val dy = (sy-hy)*frame.height
                if (hypot(dx,dy) > 10) put("body_horizontal", abs(dx)/hypot(dx,dy))
            }
        }.filterValues { it.isFinite() }
    }
}

data class Metric(val key: String, val label: String)
/** 값과 해석 범위만 제공한다. 각도 하나로 척추/발 접지/장비 위치를 정답 판정하지 않는다. */
object FormMetrics {
    private val labels = mapOf(
        "knee_asym" to "좌우 무릎각 차이(도)", "knee_out_L" to "왼 무릎 횡이동", "knee_out_R" to "오른 무릎 횡이동",
        "stance_w" to "발목 간격 / 골반 폭", "heel_lift_L" to "왼 뒤꿈치–발끝 높이", "heel_lift_R" to "오른 뒤꿈치–발끝 높이",
        "torso_incl" to "몸통 기울기(도)", "torso_roll" to "몸통 옆 기울기(도)", "hip_mean" to "고관절 평균각(도)",
        "elbow_asym" to "좌우 팔꿈치각 차이(도)", "hand_h_asym" to "양손 높이 차이", "shoulder_asym" to "어깨 높이 차이",
        "elbow_torso_L" to "왼 팔꿈치–몸통 거리", "elbow_torso_R" to "오른 팔꿈치–몸통 거리",
        "hip_dev_ankle" to "어깨–발목 선 대비 골반", "hip_dev_knee" to "어깨–무릎 선 대비 골반",
        "head_trunk_ang" to "머리–몸통 투영각(도)", "knee_ang" to "무릎 투영각(도)", "hip_ang" to "고관절 투영각(도)",
        "hand_shoulder_off" to "몸통 선 대비 손 높이", "knee_gap2d" to "양 무릎 투영 간격", "ankle_gap2d" to "양 발목 투영 간격",
        "knee_elbow_dist_L" to "왼 무릎–팔꿈치 거리", "knee_elbow_dist_R" to "오른 무릎–팔꿈치 거리",
        "knee_h_L" to "왼 무릎 상대 높이", "knee_h_R" to "오른 무릎 상대 높이")
    fun forExercise(p: ExerciseRepProfile): List<Metric> {
        val keys = when (p.exercise) {
            "바벨 스쿼트" -> listOf("knee_asym","knee_out_L","knee_out_R","stance_w","heel_lift_L","heel_lift_R","torso_incl")
            "스텝 포워드 다이나믹 런지", "바벨 런지", "사이드 런지", "크로스 런지" -> listOf("knee_out_L","knee_out_R","torso_roll","stance_w")
            "바벨 데드리프트", "굿모닝" -> listOf("hip_mean","knee_asym","torso_incl","torso_roll")
            "힙쓰러스트", "푸시업", "플랭크" -> listOf("hip_dev_ankle","head_trunk_ang","knee_ang")
            "니푸쉬업" -> listOf("hip_dev_knee","head_trunk_ang","knee_ang")
            "크런치", "라잉 레그 레이즈" -> listOf("hip_ang","knee_ang","head_trunk_ang")
            "Y - Exercise" -> listOf("hand_shoulder_off","head_trunk_ang","hip_dev_ankle")
            "시저크로스" -> listOf("knee_gap2d","ankle_gap2d","hip_ang")
            "스탠딩 사이드 크런치" -> listOf("torso_roll","knee_elbow_dist_L","knee_elbow_dist_R")
            "스탠딩 니업", "행잉 레그 레이즈" -> listOf("knee_h_L","knee_h_R","torso_incl","knee_asym")
            else -> listOf("elbow_asym","elbow_torso_L","elbow_torso_R","hand_h_asym","shoulder_asym","torso_incl")
        }
        return keys.map { Metric(it, labels.getValue(it)) }
    }
}

data class EngineOutput(
    val timeMs: Long, val counts: ExerciseRepCounts, val events: List<ExerciseRepEvent>,
    val status: String, val view: String, val observedHoldMs: Long,
    val measurements: Map<String, Float?>, val phase: String,
    val formStatus: String = "RESEARCH_MEASUREMENTS_ONLY",
    val rangeMet: Int? = null,
    val acceptedFrames: Int = 0,
    val totalFrames: Int = 0,
    val epoch: Int = 0,
    val comparison: String? = null,
)

/** 단일 세션용. 전 앱·LiveCoach·규칙 JSON에 의존하지 않는다. */
class PoseEvaluationEngine(val profile: ExerciseRepProfile, val pattern: RepMovementPattern = profile.defaultPattern,
    val goal: RangeGoal? = null) {
    private val tracker = profile.createTracker(pattern)
    private val recentView = RecentRepView()
    private var lastTime: Long? = null
    private var previousAccepted = false
    private var previousView: String? = null
    private var holdMs = 0L
    private var readySince: Long? = null
    private var acceptedFrames = 0
    private var totalAccepted = 0
    private var totalFrames = 0
    private var met = 0
    private var epoch = 0
    private var previousScissor: Float? = null
    private var previousFloorSide: Float? = null
    private val comparison=ObservedRangeComparison()
    private var comparisonText:String?=null
    fun process(timeMs: Long, features: Map<String, Float>, qualityOk: Boolean = true, floorSideConfirmed: Boolean = false): EngineOutput {
        val last = lastTime
        if (last != null && timeMs <= last) {
            interrupt()
            return output(timeMs, features, emptyList(), "시각 역행 · 진행 동작 초기화", "UNKNOWN")
        }
        if (last != null && timeMs-last > 1000) interrupt()
        lastTime = timeMs
        totalFrames++
        val view = if (profile.floor) null else recentView.add(timeMs, features)
        val viewName = if (profile.floor && floorSideConfirmed) "SIDE_USER_DECLARED" else view?.name ?: "UNKNOWN"
        if (previousView != null && previousView != viewName) {
            // 새 방향의 창을 여기서 비우면 UNKNOWN↔C가 반복돼 영원히 준비되지 않는다.
            tracker?.onObservationLost(); readySince = null; acceptedFrames = 0; previousAccepted = false
            comparison.reset();comparisonText=null
        }
        previousView = viewName
        val floorSide = features["floor_side"]
        if(profile.floor && previousFloorSide != null && floorSide != previousFloorSide) {
            tracker?.onObservationLost(); readySince=null; acceptedFrames=0; previousAccepted=false
            comparison.reset();comparisonText=null
        }
        previousFloorSide=floorSide
        var observation = if (profile.isometric) {
            val ok = qualityOk && floorSideConfirmed && features["body_horizontal"]?.let { it > .55f } == true &&
                listOf("hip_dev_ankle", "knee_ang").all { features[it]?.isFinite() == true }
            RepProfileObservation(if (ok) features else emptyMap(), if (ok) null else "몸 전체가 보이는 측면에서 플랭크를 준비해 주세요")
        } else if (profile.floor && !floorSideConfirmed) RepProfileObservation(emptyMap(), "측면 촬영 확인이 필요합니다")
        else profile.observe(features, pattern, view, qualityOk)
        if (profile.floor && features["body_horizontal"]?.let { it > .55f } != true) {
            observation = RepProfileObservation(emptyMap(), "몸통이 옆으로 보이게 촬영 위치를 확인해 주세요")
        }
        if (profile.exercise in setOf("사이드 레터럴 레이즈", "프런트 레이즈")) {
            val selected = observation.features.toMutableMap()
            for (side in listOf("L", "R")) {
                val angle = selected["upperarm_vert_$side"]
                val plane = selected["raise_lateral_$side"]
                if (angle != null && angle < 135f && (plane == null ||
                    if (profile.exercise == "사이드 레터럴 레이즈") plane < .6f else plane > .4f)) selected.remove("upperarm_vert_$side")
            }
            observation = profile.observe(selected,pattern,view,qualityOk).let {
                if(it.accepted) it else RepProfileObservation(emptyMap(),"선택한 방향의 위팔 움직임을 확인할 수 없습니다")
            }
        }
        if (profile.exercise == "시저크로스") {
            val signed = features["scissor_signed"]
            if (signed == null || previousScissor?.let { abs(signed-it) > .5f } == true)
                observation = RepProfileObservation(emptyMap(), "다리 교차와 좌우를 확인할 수 없어 다시 준비합니다")
            previousScissor = signed
        }
        val accepted = observation.accepted
        val events = if (accepted) tracker?.onFrame(timeMs, observation.features).orEmpty() else {
            tracker?.onObservationLost(); emptyList()
        }
        if (accepted) {
            totalAccepted++
            acceptedFrames++
            if (readySince == null) readySince = timeMs
            if (profile.isometric && previousAccepted && last != null && timeMs-last <= 1500 &&
                timeMs-readySince!! >= 1000 && acceptedFrames >= 5) holdMs += timeMs-last
        } else { readySince = null; acceptedFrames = 0 }
        previousAccepted = accepted
        met += events.count { goal?.met(it) == true }
        if(!accepted) {comparison.reset();comparisonText=null}
        else comparison.add(events)?.let { comparisonText=it }
        return output(timeMs, features, events, observation.reason ?: if (profile.isometric) "자세가 보이는 시간 측정 중 · 정자세 인증 아님" else "반복 관측 중 · 처음에는 준비 위치에서 잠시 멈춰 주세요", viewName)
    }
    fun interrupt() {
        tracker?.onObservationLost(); recentView.reset(); readySince = null; acceptedFrames = 0; previousAccepted = false
        previousScissor = null; epoch++
        comparison.reset();comparisonText=null
    }
    private fun output(t: Long, f: Map<String,Float>, e: List<ExerciseRepEvent>, status: String, view: String) = EngineOutput(
        t, tracker?.counts ?: ExerciseRepCounts(), e, status, view, holdMs,
        FormMetrics.forExercise(profile).associate { it.key to f[it.key]?.takeIf { v -> previousAccepted && v.isFinite() } },
        if (!previousAccepted) "UNOBSERVABLE" else if (e.isNotEmpty()) "RETURN_COMPLETE" else "TRACKING",
        rangeMet = goal?.let { met }, acceptedFrames = totalAccepted, totalFrames = totalFrames, epoch = epoch,
        comparison = comparisonText,
    )
}

/** 독립 실험 앱의 기존 API도 같은 엔진을 사용한다. */
typealias LabEngine = PoseEvaluationEngine
