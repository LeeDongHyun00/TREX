package com.example.trex_kotlin.posture

/** 측은 해부학적 좌우다. 화면 미러, 앞다리, 카메라에 가까운 쪽과 서로 바꾸지 않는다. */
enum class RepSideAttribution { INDEPENDENT_LIMBS, USER_DECLARED_LEAD, COMMON_ONLY, HOLD }

data class RepProfileObservation(val features: Map<String, Float>, val reason: String? = null) {
    val accepted: Boolean get() = features.isNotEmpty()
}

/**
 * 26개 운동의 개발 미리보기용 반복 관측 계약. 등록은 실기기 정확도나 자세 검증 완료를 의미하지 않는다.
 * minAmp는 초기 움직임/잡음 구분값이며 정자세 ROM 기준이 아니다. 기존 모집단 ROM을 새 채널로 옮기지 않는다.
 * allowedStandingViews는 카운트의 보수적인 촬영 지원 범위다. 자세 규칙의 views_ok/검증 등급과 독립이다.
 */
data class ExerciseRepProfile(
    val exercise: String,
    val variant: String,
    val commonSignal: RepSignal? = null,
    val leftSignal: RepSignal? = null,
    val rightSignal: RepSignal? = null,
    val defaultPattern: RepMovementPattern = RepMovementPattern.SIMULTANEOUS,
    val allowedPatterns: List<RepMovementPattern> = listOf(RepMovementPattern.SIMULTANEOUS),
    val sideAttribution: RepSideAttribution = RepSideAttribution.COMMON_ONLY,
    val floor: Boolean = false,
    val isometric: Boolean = false,
    val countDefinition: String,
    val limitations: String,
    /** 원래 피처와 함께 보여야 할 보조 관측. 유한성은 동작 일치/정상 자세 인증이 아니다. */
    val channelRequirements: Map<String, Set<String>> = emptyMap(),
    val allowedStandingViews: Set<ViewEstimator.ViewClass> = setOf(
        ViewEstimator.ViewClass.C, ViewEstimator.ViewClass.B, ViewEstimator.ViewClass.D,
    ),
    /** 양측 채널의 교대/동시 측정 시 두 팔이 분리되는 정면을 우선 지원한다. */
    val frontForBothChannels: Boolean = false,
    val evidence: List<String> = emptyList(),
    val validated: Boolean = false,
) {
    private fun selectedSignals(pattern: RepMovementPattern): Triple<RepSignal?, RepSignal?, RepSignal?> {
        require(pattern in allowedPatterns) { "이 운동에서 지원하지 않는 반복 방식입니다: $pattern" }
        return when {
            pattern == RepMovementPattern.LEFT_ONLY -> Triple(leftSignal, null, null)
            pattern == RepMovementPattern.RIGHT_ONLY -> Triple(null, rightSignal, null)
            commonSignal != null -> Triple(null, null, commonSignal)
            else -> Triple(leftSignal, rightSignal, null)
        }
    }

    /** 플랭크는 횟수기를 만들지 않는다. 유지 시간은 별도 관측 품질/타이머 경로가 담당한다. */
    fun createTracker(pattern: RepMovementPattern = defaultPattern): ExerciseRepTracker? {
        if (isometric) return null
        val (left, right, common) = selectedSignals(pattern)
        return ExerciseRepTracker(pattern, leftSignal = left, rightSignal = right, commonSignal = common)
    }

    fun requiredFeatures(pattern: RepMovementPattern = defaultPattern): Set<String> {
        if (isometric) return emptySet()
        val (left, right, common) = selectedSignals(pattern)
        return listOfNotNull(left, right, common).flatMap { signal ->
            listOf(signal.feature) + channelRequirements[signal.feature].orEmpty()
        }.toSet()
    }

    /**
     * 실제 가시성/화면 범위/시각 품질은 호출부가 qualityOk로 전달한다. 준비 완료를 방향 확정으로 바꾸지 않는다.
     * 바닥은 서서 yaw 추정기를 적용하지 않는다. 기존 바닥 관절 가시성으로 계산된 유한 피처만 받는다.
     * 일부 독립 채널만 보이면 그 채널만 남긴다. 반대쪽 결측을 평균/0/보이는 쪽으로 대체하지 않는다.
     */
    fun observe(
        features: Map<String, Float>,
        pattern: RepMovementPattern = defaultPattern,
        view: ViewEstimator.ViewClass?,
        qualityOk: Boolean,
    ): RepProfileObservation {
        if (isometric) return RepProfileObservation(emptyMap(), "횟수 대신 유지 시간을 측정합니다")
        if (pattern !in allowedPatterns) return RepProfileObservation(emptyMap(), "선택한 반복 방식은 이 변형에서 지원하지 않습니다")
        if (!qualityOk) return RepProfileObservation(emptyMap(), "필요한 관절과 촬영 범위를 확인해 주세요")
        if (!floor && (view == null || view !in allowedStandingViews)) {
            return RepProfileObservation(emptyMap(), "반복 측정 촬영 방향을 확인할 수 없습니다")
        }
        val bothChannels = pattern == RepMovementPattern.SIMULTANEOUS || pattern == RepMovementPattern.ALTERNATING_EACH
        if (!floor && frontForBothChannels && bothChannels && view != ViewEstimator.ViewClass.C) {
            return RepProfileObservation(emptyMap(), "양쪽을 각각 세려면 양팔이 분리되어 보이는 정면으로 촬영해 주세요")
        }
        val (left, right, common) = selectedSignals(pattern)
        val selected = listOfNotNull(left, right, common)
        val accepted = selected.filter { signal ->
            (setOf(signal.feature) + channelRequirements[signal.feature].orEmpty()).all { features[it]?.isFinite() == true }
        }
        if (accepted.isEmpty()) return RepProfileObservation(emptyMap(), "반복 측정에 필요한 관절이 보이지 않습니다")
        val out = features.filterValues { it.isFinite() }.toMutableMap()
        // 가려진 채널은 제거한다. tracker가 그 채널의 진행 중 반복을 무효화한다.
        for (signal in selected) if (signal !in accepted) out.remove(signal.feature)
        return RepProfileObservation(out)
    }
}

object ExerciseRepProfiles {
    private val allPatterns = listOf(RepMovementPattern.SIMULTANEOUS, RepMovementPattern.ALTERNATING_EACH,
        RepMovementPattern.LEFT_ONLY, RepMovementPattern.RIGHT_ONLY)
    private fun signal(feature: String, amplitude: Float = 35f) = RepSignal(feature, amplitude,
        validated = false, romDirection = null, romThreshold = null, romValidated = false)

    private fun common(name: String, feature: String, amplitude: Float, variant: String, limit: String,
                       floor: Boolean = false, definition: String = "준비 위치에서 움직였다가 돌아오는 완전 왕복 1회") =
        ExerciseRepProfile(name, variant, commonSignal = signal(feature, amplitude), floor = floor,
            countDefinition = definition, limitations = limit)

    private fun independent(name: String, feature: String, amplitude: Float, default: RepMovementPattern,
                            variant: String, limit: String, front: Boolean = false,
                            requirements: Map<String, Set<String>> = emptyMap()) =
        ExerciseRepProfile(name, variant, leftSignal = signal("${feature}_L", amplitude), rightSignal = signal("${feature}_R", amplitude),
            defaultPattern = default, allowedPatterns = allPatterns, sideAttribution = RepSideAttribution.INDEPENDENT_LIMBS,
            countDefinition = "각 측의 완전 왕복 1회; 동시 방식은 양측 복귀를 한 번으로 묶음",
            limitations = limit, frontForBothChannels = front, channelRequirements = requirements)

    private fun lunge(name: String, commonFeature: String, variant: String, limit: String) =
        ExerciseRepProfile(name, variant, commonSignal = signal(commonFeature),
            leftSignal = signal("knee_L"), rightSignal = signal("knee_R"),
            defaultPattern = RepMovementPattern.ALTERNATING_EACH,
            allowedPatterns = listOf(RepMovementPattern.ALTERNATING_EACH, RepMovementPattern.LEFT_ONLY, RepMovementPattern.RIGHT_ONLY),
            sideAttribution = RepSideAttribution.USER_DECLARED_LEAD,
            countDefinition = "내려갔다가 시작 높이로 복귀 1회; 양측 방식은 측 미확정 합계만 기록",
            limitations = "$limit 한쪽 방식의 좌우는 사용자가 선택한 앞/지지 다리이며 자동 판별한 결과가 아닙니다.",
            evidence = listOf("https://www.acefitness.org/resources/everyone/exercise-library/94/forward-lunge/"))

    val all: List<ExerciseRepProfile> = listOf(
        common("바벨 스쿼트", "knee_mean", 35f, "양발 스쿼트", "양쪽 무릎이 모두 보여야 합니다. 깊이의 정답이나 바벨 위치를 판정하지 않습니다."),
        lunge("스텝 포워드 다이나믹 런지", "knee_mean", "앞으로 한 발 내디뎠다가 복귀", "양 무릎이 같은 반복에서 굽혀지므로 두 독립 반복으로 세지 않습니다. 기존 knee_out_mean은 횡방향 정렬값이라 주 반복 신호에서 제외합니다."),
        lunge("바벨 런지", "knee_minside", "바벨을 든 앞뒤 스탠스 런지", "보이는 최소 무릎각은 앞다리를 알려주지 않습니다. 바벨과 발 지지력은 관측하지 않습니다."),
        lunge("사이드 런지", "knee_minside", "한쪽으로 체중을 옮겼다가 복귀", "더 굽힌 무릎은 신호 선택일 뿐 lead side 확정이 아닙니다. 데이터의 앞/뒤 무릎 90도 라벨을 일반 측면 런지 정답으로 쓰지 않습니다.")
            .copy(evidence = listOf("https://www.acefitness.org/resources/everyone/exercise-library/50/side-lunge/")),
        lunge("크로스 런지", "knee_mean", "한 발을 반대 다리 뒤로 교차했다가 복귀", "뒤로 옮긴 발과 앞에서 지지하는 다리가 다릅니다. 골반 회전·가림 때문에 무릎각만으로 그 역할을 분류하지 않습니다."),
        common("바벨 데드리프트", "hip_mean", 35f, "양측 힙힌지", "고관절 각도 왕복을 셉니다. 척추 중립·바벨 접촉·바닥 터치는 별도 근거가 필요합니다."),
        common("굿모닝", "hip_mean", 35f, "바벨을 등에 둔 힙힌지", "데드리프트와 같은 신호라도 선택한 운동 변형이 다릅니다. 영상만으로 장비나 척추 상태를 인증하지 않습니다."),
        common("힙쓰러스트", "hip_dev_ankle", .10f, "벤치 지지 힙쓰러스트", "골반 정렬의 투영 왕복입니다. 벤치 높이와 촬영 위치 변화는 새 관측 구간으로 처리해야 합니다.", true),
        common("오버 헤드 프레스", "palm_h_sh", .25f, "서서 하는 양팔 엄격 프레스", "손 높이 왕복입니다. 팔꿈치 잠금·견갑 움직임·무릎 반동의 정상 여부는 카운트와 별도입니다."),
        common("랫풀 다운", "elbow_mean", 35f, "몸 앞쪽으로 당기는 양팔 랫풀다운", "전완의 방향 변화만으로 세지 않고 팔꿈치 굽힘을 사용합니다. 그립 폭·기구 가림·팔꿈치 깊이는 실기기 검증이 필요합니다."),
        common("딥스", "elbow_mean", 35f, "평행봉 딥스", "양팔 굽힘 왕복입니다. 벤치/링 딥스와 별도 변형이며 보편적인 안전 깊이를 판정하지 않습니다."),
        independent("덤벨 컬", "elbow", 35f, RepMovementPattern.SIMULTANEOUS, "동시 또는 각 팔의 컬",
            "교대 팔꿈치각을 평균하면 역위상 동작이 상쇄됩니다. 해부학적 좌우 채널을 따로 유지하며 정면 양팔 관측을 요구합니다.", true),
        common("바벨 컬", "elbow_mean", 35f, "한 바벨을 양손으로 드는 컬", "연동된 양팔 컬 합계입니다. 바벨 없는 교대 컬은 덤벨 컬 프로필에서 측별로 관측합니다.")
            .copy(evidence = listOf("https://www.nasm.org/resource-center/exercise-library/barbell-bicep-curl")),
        independent("사이드 레터럴 레이즈", "upperarm_vert", 35f, RepMovementPattern.SIMULTANEOUS, "측면으로 팔 올리기",
            "전완 대신 위팔 들림을 셉니다. 팔꿈치 굽힘만으로 세지 않으며 어깨높이가 안전한 공통 목표라는 뜻은 아닙니다."),
        independent("프런트 레이즈", "upperarm_vert", 35f, RepMovementPattern.SIMULTANEOUS, "앞쪽으로 팔 올리기",
            "위팔 들림각은 앞/옆 운동면을 구별하지 못합니다. 선택한 변형의 반복을 관측하며 실제 카메라 깊이 오차 검증이 필요합니다."),
        common("업라이트로우", "elbow_h_mean", .25f, "바벨을 몸 앞에서 올리는 업라이트로우", "팔꿈치 높이 왕복입니다. 손목만 돌리는 움직임과 분리하며 그립·충돌·견갑 하강의 정답을 판정하지 않습니다."),
        common("푸시업", "wrist_shoulder_d", .30f, "발끝을 지지한 푸시업", "보이는 팔의 손목–어깨 거리 왕복입니다. 몸통 정렬과 깊이 판정은 별도이며 좌우를 자동 귀속하지 않습니다.", true),
        common("니푸쉬업", "wrist_shoulder_d", .30f, "무릎을 지지한 푸시업", "무릎 지지 변형을 사용자 선택으로 구별합니다. 어깨–골반–무릎 정렬을 발목 기준과 혼동하지 않습니다.", true),
        common("Y - Exercise", "hand_shoulder_off", .20f, "엎드린 Y 레이즈", "선 자세 Y와 다릅니다. 작은 팔 들림은 투영과 가림에 민감하고 엄지·견갑 상태를 인증하지 않습니다.", true),
        ExerciseRepProfile("플랭크", "정적 전완 플랭크", isometric = true, floor = true,
            allowedPatterns = emptyList(), sideAttribution = RepSideAttribution.HOLD,
            countDefinition = "반복 0회; 관측 가능한 유지 시간", limitations = "진입·퇴장을 1회로 세지 않습니다. 시간과 정렬 평가는 별도입니다."),
        independent("스탠딩 사이드 크런치", "knee_h", .15f, RepMovementPattern.ALTERNATING_EACH,
            "AIHub 같은 쪽 측면 무릎 올림과 몸통 접근", "무릎 상승·복귀를 셉니다. 팔만 내리거나 서서 측굴만 하는 변형은 대상이 아닙니다. 팔꿈치 거리와 몸통 기울기는 보조 관측이며 측굴의 정답이나 교정 성공을 인증하지 않습니다.",
            requirements = mapOf("knee_h_L" to setOf("knee_elbow_dist_L", "torso_roll"),
                "knee_h_R" to setOf("knee_elbow_dist_R", "torso_roll"))),
        independent("스탠딩 니업", "hip", 35f, RepMovementPattern.ALTERNATING_EACH, "한쪽 무릎을 올렸다가 내리는 니업",
            "양 고관절 평균은 교대 신호를 상쇄할 수 있습니다. 측별 고관절각을 사용하되 허리만 굽히는 움직임은 주의가 필요해 같은 쪽 무릎 높이도 보여야 합니다.",
            requirements = mapOf("hip_L" to setOf("knee_h_L"), "hip_R" to setOf("knee_h_R"))),
        common("행잉 레그 레이즈", "hip_below_knee", .10f, "매달린 양다리 레그 레이즈", "무릎–골반 상대 높이 왕복입니다. 몸 전체 흔들림·무릎 굽힘 변형을 정자세로 인증하지 않습니다."),
        common("크런치", "head_ground", .15f, "누운 크런치", "머리 높이의 투영을 근사로 사용합니다. 목만 움직이는 동작과 흉곽의 말림을 완전히 구별하지 못합니다.", true),
        common("라잉 레그 레이즈", "hip_ang", 25f, "누운 양다리 레그 레이즈", "보이는 측의 2D 고관절각 왕복입니다. 허리 바닥 접촉과 다리 높이의 보편적 정답을 판정하지 않습니다.", true),
        common("시저크로스", "knee_gap2d", .25f, "누워 다리를 좌우로 교차", "간격은 어느 다리가 위인지 알려주지 않습니다. 수직 가위차기 변형과 분리하며 좌우 개별 횟수는 추정하지 않습니다.", true,
            "벌어진 위치→교차→다시 벌어진 위치의 관측 왕복 1회; 좌우 교차 한 쌍과는 다를 수 있음"),
    )

    private fun key(name: String) = name.filterNot(Char::isWhitespace).lowercase()
    private val aliases = mapOf("런지" to "스텝 포워드 다이나믹 런지", "기본 스쿼트" to "바벨 스쿼트",
        "푸쉬업" to "푸시업", "니 푸쉬업" to "니푸쉬업", "니푸시업" to "니푸쉬업",
        "레그 레이즈" to "라잉 레그 레이즈", "Y 레이즈" to "Y - Exercise")
    private val byName = buildMap<String, ExerciseRepProfile> {
        for (profile in all) put(key(profile.exercise), profile)
        for ((alias, canonical) in aliases) put(key(alias), all.first { it.exercise == canonical })
    }
    fun forExercise(canonicalOrAlias: String): ExerciseRepProfile? = byName[key(canonicalOrAlias)]
}
