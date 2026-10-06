// tools/sync_ios_core.py 생성본 — Android 정본에서 수정한 뒤 동기화하세요.
package com.example.trex_kotlin.posture

/** 촬영 방향의 좌우는 운동하는 사람 기준이다. 바닥 측면과 서서 C(정면)를 혼동하지 않는다. */
enum class CapturePosition(val title: String, val placement: String, val voice: String) {
    FRONT("정면", "가슴이 휴대폰을 향하게 서 주세요.", "가슴이 휴대폰을 향하게 서 주세요"),
    // 사선은 각도를 말한다(사용자 요청 2026-09-26 "45도로 비스듬히") — 준비 확인(`CaptureDirectionWindow`)은 MediaPipe 요가 사선(16.4~46.2°)일 때 통과시키고,
    // MediaPipe 는 사선 각을 실제보다 조금 작게 읽는다(AIHub D 카메라 39° → 33°). 45° 로 서면 대개 그 안에 든다
    RIGHT_FRONT("오른쪽 앞", "오른어깨가 휴대폰에 가까워지도록 45도쯤 비스듬히 서 주세요.", "오른어깨가 휴대폰에 가까워지도록 45도쯤 비스듬히 서 주세요"),
    LEFT_FRONT("왼쪽 앞", "왼어깨가 휴대폰에 가까워지도록 45도쯤 비스듬히 서 주세요.", "왼어깨가 휴대폰에 가까워지도록 45도쯤 비스듬히 서 주세요"),
    SIDE("몸 옆", "휴대폰에 몸의 옆면이 보이도록 돌아서 주세요.", "휴대폰에 몸의 옆면이 보이도록 돌아서 주세요"),
    FLOOR_SIDE("몸 옆 · 낮게", "휴대폰에 몸의 옆면이 보이도록 자리 잡아 주세요.", "몸의 옆면이 보이도록 편하게 앉거나 무릎을 대고 준비해 주세요"),
    FLOOR_FRONT("몸 앞 · 비스듬히", "양팔과 양다리가 겹치지 않도록 몸을 비스듬히 돌려 주세요.", "양팔과 양다리가 겹치지 않도록 몸을 비스듬히 돌려 주세요"),
}

enum class ObservationKind { REPS, HOLD, WINDOW, GUIDE }

/**
 * 좌우 한 번씩을 1회로 세는 종목([RepUnit.SIDE_PAIR])의 자동 횟수 정의 — 준비 안내(화면·음성)에 그대로 들어간다.
 * 사용자 결정(2026-09-24): "왼쪽 1회 + 오른쪽 1회 = 1회". 목표 10회 = 왼 10 + 오른 10. 이전 문장("한쪽 1회를 1회로")은 폐기.
 */
const val ALTERNATING_COUNT_RULE = "왼쪽과 오른쪽을 한 번씩 해야 1회로 셉니다."

/**
 * 쪽별로 따로 세는 종목([RepUnit.SIDE_EACH], 런지)의 준비 안내 — 사용자 결정(2026-09-26): 왼발 앞·오른발 앞을 따로 세고, 한쪽 목표를 다 채우면
 * 반대쪽으로 안내한다. 목표 10회 = 왼 10 + 오른 10(쌍 기준은 그대로).
 */
const val SIDE_EACH_COUNT_RULE = "왼발 앞과 오른발 앞을 따로 셉니다. 한쪽을 다 채우면 반대쪽을 알려 드려요. 앞무릎은 80도 가까이 굽혀 주세요."
/** 한 다리 계열(§97) — 쪽 = 움직인 다리. 네 종목 모두 쪽마다 목표, 왼쪽 한 번 + 오른쪽 한 번 = 1회(사용자 결정 2026-10-06 저녁). */
const val LEG_PAIR_COUNT_RULE = "왼쪽과 오른쪽을 따로 세고, 한 번씩 해야 1회예요. 좌우를 못 본 동작은 짝에 넣지 않아요."

/**
 * 본인 기준 반복 검사가 있는 종목의 시작 안내(§62c 후속 9) — 첫 반복들이 팔꿈치 위치의 기준이 된다. 처음부터 벌리면 그 벌림이 '정상' 이 되므로
 * 처음 두세 번을 바르게 하라고 먼저 밝힌다(모집단 띠로 기준을 자르는 장치는 큰 벌림만 막는다).
 */
val REFERENCE_HINTS: Map<String, String> = mapOf(
    "덤벨 컬" to "처음 두세 번은 팔꿈치를 옆구리에 붙이고 정확하게 해 주세요. 그 자세를 기준으로 봐요.",
    "바벨 컬" to "처음 두세 번은 팔꿈치를 옆구리에 붙이고 정확하게 해 주세요. 그 자세를 기준으로 봐요.",
)

/** 쪽별로 세는 종목(앱 이름) — 런지 둘은 `RepFormSpecs.STEP_LUNGES`(걸음 검사기), 나머지 넷은 한 다리 계열(`LegProfile`, §97). 왼 1 + 오른 1 = 1회. */
val SIDE_EACH_EXERCISES = setOf("런지", "바벨 런지", "크로스 런지", "사이드 런지", "스탠딩 니업", "스탠딩 사이드 크런치")

/**
 * @property alternating 좌우를 번갈아 하는 종목(런지류·덤벨 컬·스탠딩 니업) — 동작의 성질(메타데이터)이다. 횟수 단위는 [repUnit] 이 정한다.
 * @property repUnit 자동 횟수의 표시 단위 — **런지류만** [RepUnit.SIDE_PAIR], 나머지는 [RepUnit.CYCLE].
 *   사용자 결정(2026-09-24): 교대 동작은 "왼쪽과 오른쪽을 한 번씩 = 1회" 로 센다(목표 10회 = 왼 10 + 오른 10, 한쪽만 하면 수가 오르지 않는다).
 *   런지류는 걸음마다 무릎이 굽어 무릎 신호(knee_mean·knee_minside)가 걸음마다 한 번 내려간다 — 카운터 사이클 하나가 한 걸음이므로
 *   사이클 둘을 1회로 묶는다(`RepUnitAccumulator`). 목표 도달 자동 진행(spec §42)은 유지하므로 세트는 두 쪽을 다 한 뒤에 넘어간다.
 *   이 정의는 시작 전에 밝힌다([ALTERNATING_COUNT_RULE], 설계 §4.8).
 *   덤벨 컬·스탠딩 니업은 [RepUnit.CYCLE] 이고 안내에 짝 규칙을 넣지 않는다. 카운트 신호가 두 팔·두 다리의 **평균**(elbow_mean·hip_mean)이라
 *   양쪽을 함께 하는 반복은 한 사이클 = 양쪽 = 1회로 이미 같은 정의다. 한쪽씩 번갈아 하는 반복은 평균 신호가 절반만 움직여 한쪽이 한 사이클로
 *   잡힌다는 보장이 없고(MM-Fit 교대 컬 영상 MediaPipe: 지금 카운터 재현율 0.09) 쪽별 귀속도 없다 — 지키지 못하는 정의를 말하지 않는다(원칙 #1).
 */
data class ExerciseProfile(val name: String, val referenceExercise: String?, val capture: CapturePosition,
    val floor: Boolean, val kind: ObservationKind, val metricFeatures: List<String>, val alternating: Boolean = false,
    val repUnit: RepUnit = RepUnit.CYCLE,
    /** 본인 기준을 쓰는 반복 검사가 있는 종목의 시작 안내(§62c 후속 9) — 첫 반복들이 기준이 되므로 처음을 바르게 하라고 밝힌다. */
    val referenceHint: String? = null) {
    val preparationDirection get() = when(capture) {
        CapturePosition.SIDE -> "측면"
        CapturePosition.FLOOR_SIDE -> "낮은 측면"
        CapturePosition.FLOOR_FRONT -> "앞쪽 사선"
        else -> capture.title
    }
    val preparationInstruction get() = "권장 촬영 방향은 ${preparationDirection}입니다. ${capture.voice}. " +
        (when (name) {
            "크로스 런지" -> "$LEG_PAIR_COUNT_RULE 왼발을 뒤로 교차해 내려가면 왼쪽 1회예요. 발이 교차하고 무릎이 깊게 굽어야 세요. 허리를 숙이면 세지 않아요. "
            "사이드 런지" -> "$LEG_PAIR_COUNT_RULE 왼 무릎을 굽히며 몸을 왼쪽으로 옮기면 왼쪽 1회예요. 무릎을 허벅지가 수평이 되게 깊게 굽혀야 세요. 허리를 숙이거나 무릎이 발끝을 넘으면 세지 않아요. "
            "스탠딩 니업" -> "$LEG_PAIR_COUNT_RULE 무릎을 접은 채 골반 높이까지 올려야 세요. 다리를 뻗거나 허리를 숙이면 세지 않아요. "
            "스탠딩 사이드 크런치" -> "$LEG_PAIR_COUNT_RULE 무릎을 굽힌 채 옆으로 올리며 옆구리를 접어 팔꿈치가 무릎에 닿아야 세요. 다리만 올리거나, 앞으로 올리거나, 앞으로 숙이면 세지 않아요. "
            else -> if (repUnit == RepUnit.SIDE_PAIR) "$ALTERNATING_COUNT_RULE " else if (repUnit == RepUnit.SIDE_EACH) "$SIDE_EACH_COUNT_RULE " else ""
        }) + (referenceHint?.let { "$it " } ?: "") + "몸이 화면에 잡히면 3초 뒤 시작해요."
    val cameraEnabled get() = kind != ObservationKind.GUIDE
    /**
     * 준비 확인이 요구하는 몸 범위(§89) — 무릎·골반 피처를 쓰지 않는 서서 하는 팔 운동(컬·레이즈·프레스·랫풀·딥스)은 상체만.
     * 가까이 찍어 다리가 화면 밖인 컬 세트가 전신 요구에 막혀 시작하지 못했다. 바닥 종목은 늘 전신(옆 사슬).
     */
    val framingRegion: FramingRegion get() =
        if (!floor && metricFeatures.none { it.startsWith("knee") || it.startsWith("hip") }) FramingRegion.UPPER else FramingRegion.FULL
    val comparisonOnly get() = referenceExercise == null
    val startHint get() = when(kind) {
        ObservationKind.HOLD -> "처음 5초를 기준으로 유지 중 변화를 비교해요."
        ObservationKind.REPS -> "초반 반복을 기준으로 이후 움직임을 비교해요."
        ObservationKind.WINDOW -> "초반 움직임의 범위를 저장해 변화만 알려드려요."
        ObservationKind.GUIDE -> "편안한 범위에서 움직이며 타이머를 따라가세요."
    }
}

/**
 * 26개 카탈로그(AIHub 규칙 종목과 1:1, spec §56)의 공통 계약. 미검증 종목에는 다른 운동의 정답 규칙을 이식하지 않는다.
 * 카탈로그 밖 이름(예전 저장 루틴의 종목)은 프로필이 없어 카메라를 켜지 않는다.
 */
object ExerciseProfiles {
    private val legs = listOf("knee_L", "knee_R", "hip_L", "hip_R", "torso_pitch")
    private val arms = listOf("elbow_L", "elbow_R", "elbow_torso_L", "elbow_torso_R", "torso_pitch")
    private val raises = listOf("upperarm_vert_L", "upperarm_vert_R", "elbow_L", "elbow_R", "torso_pitch")
    private val floorLegs = listOf("hip_ang_L", "hip_ang_R", "knee_ang_L", "knee_ang_R")
    private val floorArms = listOf("elbow_ang_L", "elbow_ang_R", "hip_dev_ankle_L", "hip_dev_ankle_R")
    val all: List<ExerciseProfile> = buildList {
        fun p(name: String, ref: String?, capture: CapturePosition, features: List<String>, floor: Boolean = false,
              kind: ObservationKind = if (ref == null) ObservationKind.WINDOW else ObservationKind.REPS) {
            val lunges = setOf("런지","바벨 런지","사이드 런지","크로스 런지")
            val alternating = name in lunges || name in setOf("덤벨 컬","스탠딩 니업","스탠딩 사이드 크런치")
            add(ExerciseProfile(name, ref, capture, floor, if (alternating) ObservationKind.WINDOW else kind, features, alternating,
                // 런지·바벨 런지는 쪽별로 센다(§63·§66 — 앞뒤로 딛는 걸음의 앞다리 기하). 사이드·크로스 런지는 두 걸음 = 1회 그대로(딛는 방향이 달라 쪽 기하가 없다)
                repUnit = if (name in SIDE_EACH_EXERCISES) RepUnit.SIDE_EACH else if (name in lunges) RepUnit.SIDE_PAIR else RepUnit.CYCLE, referenceHint = REFERENCE_HINTS[name]))
        }
        val c=CapturePosition.FRONT; val b=CapturePosition.RIGHT_FRONT; val d=CapturePosition.LEFT_FRONT
        val low=CapturePosition.FLOOR_SIDE; val oblique=CapturePosition.FLOOR_FRONT
        p("기본 스쿼트","바벨 스쿼트",c,legs)   // 앱 이름 "기본 스쿼트"(사용자 결정 2026-09-25 저녁) — AIHub 참조·규칙·로그는 "바벨 스쿼트" 그대로
        p("런지","스텝 포워드 다이나믹 런지",b,legs); p("바벨 런지","바벨 런지",d,legs)
        // 한 다리 계열(§97): 사이드·크로스 런지는 정면(C) — 무릎 비대칭·골반 이동·발목 교차가 정면 2D 에서 보인다(2026-10-06 폰 세트로 확인)
        p("사이드 런지","사이드 런지",c,legs); p("크로스 런지","크로스 런지",c,legs)
        p("바벨 데드리프트","바벨 데드리프트",c,legs); p("굿모닝","굿모닝",c,legs)
        p("딥스","딥스",b,arms); p("오버헤드 프레스","오버 헤드 프레스",c,raises)
        // 덤벨 컬 권장 = 왼어깨 쪽 45도 사선 D(사용자 결정 2026-09-26, 정면에서 되돌림) — 사선에서는 앞 이탈·몸에서 떨어짐·상체 숙임을 판정하고, 정면 전용인 뜸·옆 벌림은 유보된다
        p("덤벨 컬","덤벨 컬",d,arms); p("바벨 컬","바벨 컬",d,arms)
        p("사이드 레터럴 레이즈","사이드 레터럴 레이즈",d,raises)
        p("프런트 레이즈","프런트 레이즈",b,raises); p("랫풀 다운","랫풀 다운",d,arms)
        p("업라이트로우","업라이트로우",c,raises)
        p("스탠딩 사이드 크런치","스탠딩 사이드 크런치",c,listOf("knee_h_L","knee_h_R","torso_roll"))
        p("스탠딩 니업","스탠딩 니업",b,listOf("knee_h_L","knee_h_R","hip_L","hip_R","torso_pitch"))
        p("행잉 레그 레이즈","행잉 레그 레이즈",c,legs)
        p("푸쉬업","푸시업",low,floorArms,true); p("니 푸쉬업","니푸쉬업",low,listOf("elbow_ang_L","elbow_ang_R","hip_dev_knee_L","hip_dev_knee_R"),true)
        p("플랭크","플랭크",low,listOf(PlankGeometry.HIP,PlankGeometry.HEAD,PlankGeometry.NECK),true,ObservationKind.HOLD)
        p("크런치","크런치",low,listOf("head_ground","hip_ang_L","hip_ang_R"),true)
        p("레그 레이즈","라잉 레그 레이즈",low,floorLegs,true)
        p("힙 쓰러스트","힙쓰러스트",low,listOf("hip_dev_knee_L","hip_dev_knee_R","hip_ang_L","hip_ang_R"),true)
        p("시저 크로스","시저크로스",oblique,floorLegs+"ankle_gap2d",true)
        p("Y 레이즈","Y - Exercise",oblique,listOf("hand_shoulder_off_L","hand_shoulder_off_R"),true)
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
            "stance_w","ankle_gap2d"->"다리 간격"; "stance_sh","stance_2d"->"발 너비(어깨 기준)"; "toe_out"->"발끝 방향"; else->base
        }
        val angle=base in setOf("knee","knee_ang","hip","hip_ang","elbow","elbow_ang","upperarm_vert","torso_pitch","torso_roll","head_trunk_ang",PlankGeometry.HEAD,PlankGeometry.NECK)
        ComparisonMetric(feature,side+label,if(angle) "°" else "정규화 비율",if(angle)8f else .06f)
    }
}
