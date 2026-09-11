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

/** 57개 카탈로그의 공통 계약. 미검증 종목에는 다른 운동의 정답 규칙을 이식하지 않는다. */
object ExerciseProfiles {
    private val legs = listOf("knee_L", "knee_R", "hip_L", "hip_R", "torso_pitch")
    private val arms = listOf("elbow_L", "elbow_R", "elbow_torso_L", "elbow_torso_R", "torso_pitch")
    private val raises = listOf("upperarm_vert_L", "upperarm_vert_R", "elbow_L", "elbow_R", "torso_pitch")
    private val floorLegs = listOf("hip_ang_L", "hip_ang_R", "knee_ang_L", "knee_ang_R")
    private val floorArms = listOf("elbow_ang_L", "elbow_ang_R", "hip_dev_ankle_L", "hip_dev_ankle_R")
    val all: List<ExerciseProfile> = buildList {
        fun p(name: String, ref: String?, capture: CapturePosition, features: List<String>, floor: Boolean = false,
              kind: ObservationKind = if (ref == null) ObservationKind.WINDOW else ObservationKind.REPS) {
            val alternating = name in setOf("런지","바벨 런지","사이드 런지","크로스 런지","덤벨 컬","스탠딩 니업")
            add(ExerciseProfile(name, ref, capture, floor, if (alternating) ObservationKind.WINDOW else kind, features))
        }
        val c=CapturePosition.FRONT; val b=CapturePosition.RIGHT_FRONT; val d=CapturePosition.LEFT_FRONT
        val side=CapturePosition.SIDE; val low=CapturePosition.FLOOR_SIDE; val oblique=CapturePosition.FLOOR_FRONT
        p("기본 스쿼트","바벨 스쿼트",c,legs); p("바벨 스쿼트","바벨 스쿼트",c,legs)
        p("런지","스텝 포워드 다이나믹 런지",b,legs); p("바벨 런지","바벨 런지",d,legs)
        p("사이드 런지","사이드 런지",b,legs); p("크로스 런지","크로스 런지",c,legs)
        p("바벨 데드리프트","바벨 데드리프트",c,legs); p("굿모닝","굿모닝",c,legs)
        p("딥스","딥스",b,arms); p("오버헤드 프레스","오버 헤드 프레스",c,raises)
        p("덤벨 컬","덤벨 컬",d,arms); p("바벨 컬","바벨 컬",d,arms)
        p("사이드 레터럴 레이즈","사이드 레터럴 레이즈",d,raises)
        p("프런트 레이즈","프런트 레이즈",b,raises); p("랫풀 다운","랫풀 다운",d,arms)
        p("업라이트로우","업라이트로우",c,raises)
        p("스탠딩 사이드 크런치","스탠딩 사이드 크런치",c,listOf("knee_h_L","knee_h_R","torso_roll"),kind=ObservationKind.WINDOW)
        p("스탠딩 니업","스탠딩 니업",b,listOf("knee_h_L","knee_h_R","hip_L","hip_R","torso_pitch"))
        p("행잉 레그 레이즈","행잉 레그 레이즈",c,legs)
        p("푸쉬업","푸시업",low,floorArms,true); p("니 푸쉬업","니푸쉬업",low,listOf("elbow_ang_L","elbow_ang_R","hip_dev_knee_L","hip_dev_knee_R"),true)
        p("플랭크","플랭크",low,listOf(PlankGeometry.HIP,PlankGeometry.HEAD,PlankGeometry.NECK),true,ObservationKind.HOLD)
        p("크런치","크런치",low,listOf("head_ground","hip_ang_L","hip_ang_R"),true)
        p("레그 레이즈","라잉 레그 레이즈",low,floorLegs,true)
        p("힙 쓰러스트","힙쓰러스트",low,listOf("hip_dev_knee_L","hip_dev_knee_R","hip_ang_L","hip_ang_R"),true)
        p("시저 크로스","시저크로스",oblique,floorLegs+"ankle_gap2d",true)
        p("Y 레이즈","Y - Exercise",oblique,listOf("hand_shoulder_off_L","hand_shoulder_off_R"),true)
        p("불가리안 스플릿 스쿼트",null,b,legs)
        p("글루트 브릿지",null,low,listOf("hip_dev_knee_L","hip_dev_knee_R"),true)
        p("월 싯",null,side,legs,kind=ObservationKind.HOLD)
        p("카프 레이즈",null,side,listOf("heel_lift_L","heel_lift_R"))
        p("인클라인 푸쉬업",null,side,floorArms,true); p("벽 푸쉬업",null,side,floorArms,true)
        p("밴드 로우",null,b,arms)
        p("사이드 플랭크",null,side,listOf("hip_dev_ankle_L","hip_dev_ankle_R"),true,ObservationKind.HOLD)
        p("플랭크 숄더탭",null,oblique,listOf("shoulder_asym2d")+floorArms,true)
        p("버드독",null,oblique,floorLegs+floorArms,true); p("데드버그",null,oblique,floorLegs+floorArms,true)
        p("할로우 홀드",null,low,floorLegs+listOf("hand_shoulder_off_L","hand_shoulder_off_R"),true,ObservationKind.HOLD)
        p("힙 브릿지 홀드",null,low,listOf("hip_dev_knee_L","hip_dev_knee_R"),true,ObservationKind.HOLD)
        p("리버스 크런치",null,low,floorLegs,true); p("바이시클 크런치",null,oblique,floorLegs,true)
        p("러시안 트위스트",null,oblique,listOf("hand_shoulder_off_L","hand_shoulder_off_R"),true)
        p("제자리 걷기",null,c,listOf("knee_h_L","knee_h_R")); p("하이 니",null,b,listOf("knee_h_L","knee_h_R"))
        p("마운틴 클라이머",null,oblique,floorLegs,true); p("점핑잭",null,c,raises+"stance_w")
        p("스텝업",null,b,legs); p("스키터 점프",null,c,listOf("stance_w","torso_roll","knee_L","knee_R"))
        p("섀도 복싱",null,b,arms); p("버피",null,b,legs+arms)
        p("마무리 스트레칭",null,c,emptyList(),kind=ObservationKind.GUIDE)
        p("캣카우 스트레칭",null,low,listOf("hip_ang_L","hip_ang_R","head_trunk_ang"),true)
        p("차일드 포즈",null,low,floorLegs,true,ObservationKind.HOLD)
        p("폼롤러 마무리",null,c,emptyList(),kind=ObservationKind.GUIDE)
        p("햄스트링 스트레칭",null,side,legs,kind=ObservationKind.HOLD)
        p("흉추 회전 스트레칭",null,oblique,listOf("hand_shoulder_off_L","hand_shoulder_off_R"),true)
    }
    fun forName(name: String): ExerciseProfile? = all.firstOrNull { it.name == name }
    fun forReference(name: String): ExerciseProfile? = forName(name) ?: all.firstOrNull { it.referenceExercise == name }
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
