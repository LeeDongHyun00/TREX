package com.example.trex_kotlin.posture

/** 촬영 방향의 좌우는 운동하는 사람 기준이다. 바닥 측면과 서서 C(정면)를 혼동하지 않는다. */
enum class CapturePosition(val title: String, val placement: String, val voice: String) {
    FRONT("정면", "가슴이 휴대폰을 향하게 서 주세요.", "가슴이 휴대폰을 향하게 서 주세요"),
    RIGHT_FRONT("오른쪽 앞", "오른어깨가 휴대폰에 더 가까워지도록 몸을 돌려 주세요.", "오른어깨가 휴대폰에 더 가까워지도록 몸을 돌려 주세요"),
    LEFT_FRONT("왼쪽 앞", "왼어깨가 휴대폰에 더 가까워지도록 몸을 돌려 주세요.", "왼어깨가 휴대폰에 더 가까워지도록 몸을 돌려 주세요"),
    SIDE("몸 옆", "휴대폰에 몸의 옆면이 보이도록 돌아서 주세요.", "휴대폰에 몸의 옆면이 보이도록 돌아서 주세요"),
    FLOOR_SIDE("몸 옆 · 낮게", "휴대폰에 몸의 옆면이 보이도록 자리 잡아 주세요.", "몸의 옆면이 보이도록 편하게 앉거나 무릎을 대고 준비해 주세요"),
    FLOOR_FRONT("몸 앞 · 비스듬히", "양팔과 양다리가 겹치지 않도록 몸을 비스듬히 돌려 주세요.", "양팔과 양다리가 겹치지 않도록 몸을 비스듬히 돌려 주세요"),
}

enum class ObservationKind { REPS, HOLD, WINDOW, GUIDE }
data class ExerciseProfile(val name: String, val referenceExercise: String?, val capture: CapturePosition,
    val floor: Boolean, val kind: ObservationKind, val metricFeatures: List<String>) {
    val preparationDirection get() = when(capture) {
        CapturePosition.SIDE -> "측면"
        CapturePosition.FLOOR_SIDE -> "낮은 측면"
        CapturePosition.FLOOR_FRONT -> "앞쪽 사선"
        else -> capture.title
    }
    val preparationInstruction get() = "권장 촬영 방향은 ${preparationDirection}입니다. ${capture.voice}. 몸이 화면에 잡히면 5초 뒤 시작해요."
    val cameraEnabled get() = kind != ObservationKind.GUIDE
    val comparisonOnly get() = referenceExercise == null
    val startHint get() = when(kind) {
        ObservationKind.HOLD -> "처음 5초를 기준으로 유지 중 변화를 비교해요."
        ObservationKind.REPS -> "초반 반복을 기준으로 이후 움직임을 비교해요."
        ObservationKind.WINDOW -> "초반 움직임의 범위를 저장해 변화만 알려드려요."
        ObservationKind.GUIDE -> "편안한 범위에서 움직이며 타이머를 따라가세요."
    }
}

/** 26개 카탈로그의 공통 계약. 미검증 종목에는 다른 운동의 정답 규칙을 이식하지 않는다. */
object ExerciseProfiles {
    private val legs = listOf("knee_L", "knee_R", "hip_L", "hip_R", "torso_pitch")
    private val arms = listOf("elbow_L", "elbow_R", "elbow_torso_L", "elbow_torso_R", "torso_pitch")
    private val raises = listOf("upperarm_vert_L", "upperarm_vert_R", "elbow_L", "elbow_R", "torso_pitch")
    private val floorLegs = listOf("hip_ang_L", "hip_ang_R", "knee_ang_L", "knee_ang_R")
    private val floorArms = listOf("elbow_ang_L", "elbow_ang_R", "hip_dev_ankle_L", "hip_dev_ankle_R")
    val all: List<ExerciseProfile> = com.trex.engine.ExerciseCatalog.profiles.map { p ->
        val capture = when {
            p.exercise in setOf("시저크로스", "Y - Exercise") -> CapturePosition.FLOOR_FRONT
            p.floor -> CapturePosition.FLOOR_SIDE
            p.exercise == "바벨 런지" -> CapturePosition.LEFT_FRONT
            p.exercise == "스텝 포워드 다이나믹 런지" -> CapturePosition.RIGHT_FRONT
            p.exercise in setOf("바벨 데드리프트", "굿모닝", "프런트 레이즈", "딥스", "랫풀 다운") -> CapturePosition.RIGHT_FRONT
            else -> CapturePosition.FRONT
        }
        ExerciseProfile(p.exercise, p.exercise, capture, p.floor,
            if (p.isometric) ObservationKind.HOLD else ObservationKind.REPS,
            com.trex.engine.FormMetrics.forExercise(p).map { it.key })
    }
    fun forName(name: String): ExerciseProfile? = com.trex.engine.ExerciseCatalog.canonical(name)?.let { n -> all.firstOrNull { it.name == n } }
    fun forReference(name: String): ExerciseProfile? = forName(name)
    fun metrics(profile: ExerciseProfile): List<ComparisonMetric> = profile.metricFeatures.map { feature ->
        val base=feature.removeSuffix("_L").removeSuffix("_R")
        val side=if(feature.endsWith("_L")) "왼쪽 " else if(feature.endsWith("_R")) "오른쪽 " else ""
        val label=when(base) {
            "knee","knee_ang"->"무릎각"; "hip","hip_ang"->"고관절각"; "elbow","elbow_ang"->"팔꿈치각"
            "elbow_torso"->"팔꿈치 위치"; "upperarm_vert"->"팔 들림각"; "torso_pitch"->"몸통 앞뒤 기울기"
            "torso_roll"->"몸통 좌우 기울기"; "knee_h"->"무릎 높이"; "heel_lift"->"뒤꿈치 들림(근사)"
            "hip_dev_ankle","hip_dev_knee",PlankGeometry.HIP->"골반 정렬"
            PlankGeometry.HEAD->"고개 기울기"; PlankGeometry.NECK->"목 정렬"
            "head_ground"->"머리 들림(근사)"; "head_trunk_ang"->"투영 고개각"
            "hand_shoulder_off"->"손 위치(투영)"; "shoulder_asym2d"->"어깨 높이 차이"
            "stance_w","ankle_gap2d"->"다리 간격"; else->base
        }
        val angle=base in setOf("knee","knee_ang","hip","hip_ang","elbow","elbow_ang","upperarm_vert","torso_pitch","torso_roll","head_trunk_ang",PlankGeometry.HEAD,PlankGeometry.NECK)
        ComparisonMetric(feature,side+label,if(angle) "°" else "정규화 비율",if(angle)8f else .06f)
    }
}
