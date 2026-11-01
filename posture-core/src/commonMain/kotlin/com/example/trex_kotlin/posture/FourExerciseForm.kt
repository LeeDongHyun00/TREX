// tools/sync_ios_core.py 생성본 — Android 정본에서 수정한 뒤 동기화하세요.
package com.example.trex_kotlin.posture

/**
 * 네 종목 사용자 보고(2026-10-31, §123)의 반복별 관측.
 * 운동 설명은 임계값 검증 자료가 아니다. 검사는 모두 beta이며 음성·점수를 바꾸지 않는다.
 * §123a·§126·§128 사용자 요청으로 레이즈 높이 미달과 관측된 푸시업 몸통·팔·고개 위반은 COACH 횟수에서 제외한다.
 * 척추 분절·근육의 힘·반동의 원인은 관절 모델로 직접 볼 수 없다.
 */
object FourExerciseForm {
    const val GOOD_MORNING_SIGNAL = "goodmorning_hinge"
    /** §133: 어깨–골반의 화면 수직 이탈 20° 이내. 척추의 곧음 인증이 아닌 왕복 시작·복귀 조건. */
    const val GOOD_MORNING_UPRIGHT_MIN = 160f
    const val PUSHUP_SIGNAL = "pushup_shoulder_height"
    const val PUSHUP_POSTURE = "pushup_posture"
    const val PUSHUP_HIP = "pushup_hip_offset"
    const val PUSHUP_KNEE = "pushup_knee"
    const val PUSHUP_ELBOW = "pushup_elbow"
    const val PUSHUP_HEAD = "pushup_head_pitch"
    const val PUSHUP_NECK = "pushup_neck_pitch"
    const val PUSHUP_TURN = "pushup_face_yaw"
    val pushupHeadFeatures = setOf(PUSHUP_HEAD, PUSHUP_NECK)
    val pushupOptionalFeatures = pushupHeadFeatures + PUSHUP_TURN + PushupBalance.keys
    const val ARM_PLANE = "raise_plane"
    const val RAISE_SIGNAL = "upperarm_vert_mean"
    val changedSignalExercises = setOf("굿모닝", "푸시업", "프런트 레이즈", "사이드 레터럴 레이즈")

    /** §126: 기존 바닥 일괄 제외에서 푸시업만 명시적으로 연다. Android·iOS 화면이 같은 횟수 정책을 쓴다. */
    fun usesFormCountGate(exercise: String, coach: Boolean, floor: Boolean): Boolean =
        coach && (!floor || exercise == "푸시업")

    /** §127·§128: 관측된 화면 쪽 돌림만 추가한다. 얼굴 미검출·반대쪽 가림은 정상도 위반도 아니다. */
    fun filterPushupHead(features: Map<String, Float>): Map<String, Float> {
        // 이전 칸의 파생값을 재사용하지 않는다. 고개가 안 보여도 몸통 신호는 보존한다.
        var observed = features - PUSHUP_TURN
        if (features[PUSHUP_POSTURE] != 1f) return observed - pushupHeadFeatures
        val found = features[FloorGaze.FOUND]
        val yaw = features[FloorGaze.YAW]
        if (found != null && found.isFinite() && found >= .5f &&
            yaw != null && yaw.isFinite() && yaw in -FloorGaze.TURN_DEG..90f) {
            observed = observed + (PUSHUP_TURN to yaw)
        }
        val faceInvalid = found != null && (!found.isFinite() || (found >= .5f &&
            (yaw == null || !yaw.isFinite() || kotlin.math.abs(yaw) > FloorGaze.TURN_DEG)))
        return if (faceInvalid) observed - pushupHeadFeatures else observed
    }

    private val oblique = setOf("B", "D", "SIDE_B", "SIDE_D")
    private val floorSide = setOf("SIDE_B", "SIDE_D")
    private const val CAUTION = "잠정 참고 띠입니다. 해당 종목의 실기기 정상·오류 반복으로 검증하기 전에는 음성·점수·횟수 차단에 쓰지 않습니다."

    private fun check(ex: String, name: String, body: String, feature: String,
                      lo: Float? = null, hi: Float? = null, low: String? = null, high: String? = null,
                      fix: String, stat: RepFormStat = RepFormStat.MEDIAN, phase: RepPhase = RepPhase.CYCLE,
                      views: Set<String> = oblique, floor: Boolean = false, unit: String = "°",
                      reason: String): RepFormCheck = RepFormCheck(
        "repform|$ex|$name", ex, "$name(반복)", body, RuleStatus.BETA, feature, phase, stat, RepFormRef.NONE,
        lo, hi, low, high, if (lo != null) "낮음" else null, if (hi != null) "높음" else null, fix,
        "$reason $CAUTION", unit = unit, cautions = listOf(CAUTION), views = views,
        gates = false, prior = false, cue = name, minSamples = if (stat == RepFormStat.SPAN) 4 else 2,
        minCoverage = .6f, requireView = !floor, referenceFix = true,
    )

    /** §133: 사용자가 요청한 관측 오류의 COACH 횟수 제외. 유보는 위반으로 바꾸지 않는다. */
    private fun countStanding(checks: List<RepFormCheck>) = checks.flatMap { c ->
        // 양방향 오류를 별도로 보존한다. 한 회 안 과굽힘과 과폄이 평균으로 상쇄되지 않는다.
        val directions = listOfNotNull(c.lo?.let { FormDirection.LOW }, c.hi?.let { FormDirection.HIGH })
        directions.map { d -> c.copy(
            id = c.id + if (directions.size > 1) "|${d.name}" else "",
            lo = if (d == FormDirection.LOW) c.lo else null,
            hi = if (d == FormDirection.HIGH) c.hi else null,
            stat = if (c.stat == RepFormStat.RANGE) c.stat else if (d == FormDirection.LOW) RepFormStat.MIN else RepFormStat.MAX,
            gates = true, countOnlyDirection = d,
            // sf_* 는 프레임마다 촬영 방향·가림을 통과한 값이다. 굽힘 중 3D 골반축의 회전으로
            // 반복 평균 뷰가 뒤쪽으로 흔들렸다고 관측된 측면 무릎까지 다시 버리지 않는다.
            views = setOf("C","B","D","SIDE_B","SIDE_D","A","E","R"), requireView = false,
            minSamples = if (c.stat == RepFormStat.RANGE) 2 else 1, minCoverage = 0f,
            reason = c.reason.substringBefore(CAUTION) + "§133: 관측된 오류를 COACH 횟수에 반영합니다. 각도는 해당 구간의 극값, 움직임은 최대−최소입니다. 지속 시간 조건은 없고 유효한 값이 없으면 유보합니다.",
            cautions = listOf("사용자 요청의 시험 단계 횟수 검사입니다. 음성·점수에는 쓰지 않으며 인식 튐의 실기기 검증이 필요합니다."),
        ) }
    }

    private fun frontRaise() = countStanding(buildList {
        add(check("프런트 레이즈", "상체 기울기", "상체", StandingFormGeometry.TILT, hi = 25f,
            high = "상체가 많이 기울어져 있어요", fix = "몸통을 세우고 팔을 앞으로 들어 주세요",
            reason = "처음부터 숙인 자세가 시작 기준에 흡수되지 않도록 몸통의 절대 기울기를 봅니다. 척추 말림 판정은 아닙니다."))
        add(check("프런트 레이즈", "몸통 흔들림", "상체", StandingFormGeometry.PITCH, hi = 20f, stat = RepFormStat.RANGE,
            high = "팔을 들 때 몸통이 앞뒤로 크게 움직였어요", fix = "몸통을 고정하고 반동 없이 천천히 들어 주세요",
            reason = "팔의 가속도 대신 몸통의 반복 안 앞뒤 회전을 봅니다. 근육의 힘이나 관성 자체를 측정하지 않습니다."))
        for ((side, label) in listOf("L" to "왼쪽", "R" to "오른쪽"))
            add(check("프런트 레이즈", "$label 무릎 흔들림", "무릎", StandingFormGeometry.knee(side), hi = 20f, stat = RepFormStat.RANGE,
                high = "팔을 들 때 $label 무릎이 크게 굽혔다 펴졌어요", fix = "무릎 움직임을 줄이고 팔을 천천히 들어 주세요",
                views = oblique + "C", reason = "몸통을 세운 채 무릎을 굽혔다 펴는 반동 징후를 쪽별로 관측합니다. 약한 고정 굽힘은 허용합니다."))
    })

    private fun goodMorning() = countStanding(buildList {
        for ((side, label) in listOf("L" to "왼쪽", "R" to "오른쪽")) {
            add(check("굿모닝", "$label 무릎 움직임", "무릎", StandingFormGeometry.knee(side), hi = 30f, stat = RepFormStat.RANGE,
                high = "반복 중 $label 무릎 각도가 크게 변했어요", fix = "무릎은 살짝 굽힌 상태로 유지하고 엉덩이를 뒤로 보내 주세요",
                views = oblique + "C", reason = "무릎의 약한 굽힘은 정상입니다. 양 무릎 평균으로 한쪽 움직임을 희석하지 않고 쪽별 변화량을 봅니다."))
            add(check("굿모닝", "$label 무릎 과굽힘", "무릎", StandingFormGeometry.knee(side), lo = 140f,
                low = "$label 무릎이 많이 굽혀졌어요", fix = "무릎은 살짝만 굽히고 고관절을 접어 주세요",
                views = oblique + "C", reason = "무릎을 완전히 펴라고 지시하지 않습니다. 반복 중 과한 굽힘을 관측합니다."))
        }
    })

    private fun lateralRaise() = countStanding(buildList {
        for ((side, label) in listOf("L" to "왼쪽", "R" to "오른쪽")) {
            add(check("사이드 레터럴 레이즈", "$label 팔꿈치 굽힘", "팔꿈치", StandingFormGeometry.elbow(side), lo = 135f, hi = 175f,
                low = "$label 팔꿈치를 많이 접었어요", high = "$label 팔꿈치가 거의 펴져 있어요",
                fix = "팔꿈치를 살짝 굽힌 모양을 유지하며 들어 주세요", phase = RepPhase.BOTTOM,
                views = oblique + "C", reason = "첨부 영상의 약한 팔꿈치 굽힘을 팔마다 관측합니다. 135~175°는 영상에서 보정한 값이 아닌 잠정 참고 띠입니다."))
            add(check("사이드 레터럴 레이즈", "$label 팔 경로", "팔", StandingFormGeometry.plane(side), lo = 5f, hi = 60f,
                low = "$label 팔이 몸 옆이나 뒤쪽으로 올라왔어요", high = "$label 팔이 앞쪽으로 많이 모였어요",
                fix = "팔을 몸보다 조금 앞쪽 사선으로 들어 주세요", phase = RepPhase.BOTTOM,
                reason = "팔을 든 구간에만 몸의 좌우·전방 축으로 상완 방향을 잽니다. 어깨 내회전·견갑 움직임은 보지 못합니다. 개인차를 오류로 단정하지 않는 beta입니다."))
        }
    })

    private fun pushup() = listOf(
        check("푸시업", "골반 처짐", "골반", PUSHUP_HIP, lo = -.08f, stat = RepFormStat.MIN,
            low = "골반이 어깨와 발목을 잇는 선보다 내려갔어요",
            fix = "골반을 올려 몸통을 한 선으로 유지해 주세요", views = floorSide, floor = true, unit = "",
            reason = "보이는 쪽 골반의 몸통선 이탈을 봅니다. 척추 곡선이나 좌우 골반 기울기의 판정은 아닙니다."),
        check("푸시업", "골반 들림", "골반", PUSHUP_HIP, hi = .08f, stat = RepFormStat.MAX,
            high = "골반이 어깨와 발목을 잇는 선보다 올라갔어요",
            fix = "골반을 내려 몸통을 한 선으로 유지해 주세요", views = floorSide, floor = true, unit = "",
            reason = "처짐과 들림을 따로 관측해 한 반복의 반대 방향 오류가 중앙값에서 상쇄되지 않게 합니다."),
        check("푸시업", "다리 펴기", "무릎", PUSHUP_KNEE, lo = 150f,
            low = "푸쉬업 중 무릎이 많이 굽혀졌어요", fix = "다리를 펴고 발끝으로 지지해 주세요. 무릎을 대려면 니 푸쉬업을 선택해 주세요",
            stat = RepFormStat.MIN, views = floorSide, floor = true, reason = "푸시업과 니푸쉬업의 지지 자세를 구분해 안내합니다. 보이는 쪽 무릎만 측정하며 접지·압력은 판정하지 않습니다."),
        check("푸시업", "팔 굽힘 범위", "팔꿈치", PUSHUP_ELBOW, hi = 120f, phase = RepPhase.BOTTOM, stat = RepFormStat.MAX,
            high = "아래 구간에서도 팔꿈치가 조금만 굽혀졌어요",
            fix = "몸통을 한 선으로 유지하며 가슴을 조금 더 내려 주세요", views = floorSide, floor = true,
            reason = "§123b 첨부 영상의 충분한 가동범위 안내. 보이는 쪽 팔꿈치 굽힘각으로 얕은 하강을 참고하며, 가슴의 실제 바닥 접촉을 판정하지 않습니다. 120°는 실기기 검증 전 잠정 띠이며 영상의 팔 벌림 45°와 다른 각도입니다."),
        check("푸시업", "시작 팔 펴기", "팔꿈치", PUSHUP_ELBOW, lo = 150f, phase = RepPhase.TOP, stat = RepFormStat.MIN,
            low = "내려가기 전 팔꿈치가 덜 펴져 있었어요",
            fix = "몸통을 한 선으로 유지하며 팔을 펴고 다음 반복을 시작해 주세요", views = floorSide, floor = true,
            reason = "§123b 하강 직전 상단 창의 팔꿈치각만 봅니다. 150°는 잠정 참고 띠입니다. 카운터가 복귀 도중 확정할 수 있어 현재 회의 끝까지 폄을 판정했다고 말하지 않습니다."),
    ).map { c -> c.copy(
        gates = true, countOnlyDirection = if (c.lo != null) FormDirection.LOW else FormDirection.HIGH,
        minSamples = 1, minCoverage = 0f,
        reason = c.reason.substringBefore(CAUTION) + "§130: 사용자 요청으로 해당 회에서 유효한 위반 관측이 하나라도 있으면 COACH 횟수에서 제외합니다. 골반·무릎은 사이클 극값, 팔은 원래 판정 위상의 극값을 봅니다. 지속 시간·비율 조건은 없으며 유효한 값이 없으면 유보합니다.",
        cautions = listOf("실기기 보정 전 beta 검사입니다. 사용자 요청으로 횟수에만 반영하며 음성·점수에는 쓰지 않습니다. 관측 부족은 유보합니다."),
    ) } + pushupHead() + pushupBalance()

    /** §131: 사용자 요청의 '의심 알림'이다. 기존 횟수 예외 map에 넣지 않는다. */
    private fun pushupBalance() = listOf(
        check("푸시업", "양쪽 어깨 기울어짐 의심", "어깨", PushupBalance.SHOULDER, hi = 20f,
            high = "양쪽 어깨 기울어짐이 의심돼요", fix = "양쪽 어깨의 높이를 확인해 주세요",
            views = floorSide, floor = true,
            reason = "§131 양쪽 어깨 축과 중력 수평면의 추정 각도입니다. 겹침·가림·투영 불일치는 유보합니다. 20°는 실기기 보정 전 넓은 잠정 참고 띠입니다."),
        check("푸시업", "양쪽 골반 기울어짐 의심", "골반", PushupBalance.PELVIS, hi = 20f,
            high = "양쪽 골반 기울어짐이 의심돼요", fix = "양쪽 골반의 높이를 확인해 주세요",
            views = floorSide, floor = true,
            reason = "§131 양쪽 엉덩이 관절 축의 추정 높이 차입니다. 골반 자체의 앞뒤 경사나 해부학적 회전을 측정하지 않습니다. 20°는 실기기 보정 전 넓은 잠정 참고 띠입니다."),
        check("푸시업", "어깨와 골반 비틀림 의심", "몸통", PushupBalance.TWIST, hi = 25f,
            high = "어깨와 골반 사이의 비틀림이 의심돼요", fix = "어깨와 골반의 정렬을 확인해 주세요",
            views = floorSide, floor = true,
            reason = "§131 몸통 축에 수직인 면에서 어깨선과 골반선의 추정 방향 차를 봅니다. 양쪽이 함께 회전한 자세와 상대적인 비틀림을 구분합니다. 25°는 미보정 참고 띠이며 척추 비틀림 진단이 아닙니다."),
    )

    private fun pushupHead() = listOf(
        check("푸시업", "머리 처짐", "고개", PUSHUP_NECK, lo = -25f, stat = RepFormStat.MIN,
            low = "머리가 몸통선보다 아래로 많이 내려갔어요", fix = "머리와 몸통이 함께 움직이도록 목을 길게 유지해 주세요",
            views = floorSide, floor = true,
            reason = "§127 영상 38~66초의 머리부터 내리는 동작을 참고합니다. 어깨–귀 선과 몸통선의 측면 각도이며 목뼈 곡선·바닥 접촉을 직접 재지 않습니다."),
        check("푸시업", "고개 과들림", "고개", PUSHUP_HEAD, hi = 60f, stat = RepFormStat.MAX,
            high = "고개가 몸통에 비해 많이 들려 있어요", fix = "목을 뒤로 꺾지 말고 편한 방향으로 고개를 맞춰 주세요",
            views = floorSide, floor = true,
            reason = "§127 약간 앞을 보는 영상 안내를 허용합니다. 코–귀 방향의 몸통 대비 각이며 영상의 시선 30°를 같은 측정값으로 보지 않습니다."),
        check("푸시업", "고개 과숙임", "고개", PUSHUP_HEAD, lo = -35f, stat = RepFormStat.MIN,
            low = "고개가 몸통에 비해 많이 숙여져 있어요", fix = "턱을 과하게 당기지 말고 목을 길게 유지해 주세요",
            views = floorSide, floor = true,
            reason = "§127 코·귀의 측면 방향으로 과한 숙임을 참고합니다. 눈동자가 보는 곳을 판정하지 않습니다."),
        check("푸시업", "화면 쪽 고개 돌림", "고개", PUSHUP_TURN, hi = FloorGaze.TURN_DEG, stat = RepFormStat.MAX,
            high = "얼굴이 화면 쪽으로 돌아갔어요", fix = "고개를 몸통과 나란한 방향으로 맞춰 주세요",
            views = floorSide, floor = true,
            reason = "§128 얼굴 검출과 방향값이 함께 있는 칸만 사용합니다. 측면 카메라에서 가려진 반대편 고개나 눈동자 시선을 추정하지 않습니다."),
    ).map { c -> c.copy(minSamples = 1, minCoverage = 0f,
        gates = true, countOnlyDirection = if (c.lo != null) FormDirection.LOW else FormDirection.HIGH,
        reason = c.reason.substringBefore(CAUTION) + "§130: 사용자 요청으로 해당 반복의 최솟값·최댓값을 사용해 유효한 오류 관측 하나도 정상값에 묻히지 않게 합니다. 시간·비율 조건은 없고 오류 뒤 정상으로 돌아와도 그 회는 제외합니다. beta·무음·점수 제외를 유지하며 관측값이 없으면 유보합니다.",
        cautions = listOf("고개 횟수 차단은 사용자 요청에 따른 beta 예외입니다. 가림·비정상 좌표는 유보하며 몸통 측정은 계속합니다. 유효한 값으로 통과한 인식 튐도 회를 제외할 수 있어 실기기 오탐 확인이 필요합니다. 약간 앞을 보는 고개는 허용합니다.")) }

    private fun raiseHeight(ex: String) = listOf("L" to "왼쪽", "R" to "오른쪽").map { (side, label) ->
        check(ex, "$label 팔 높이", "팔", "upperarm_vert_$side", lo = 60f, hi = 125f,
            low = "$label 위팔이 어깨보다 많이 높아졌어요", high = "$label 위팔이 어깨 높이보다 낮아요",
            fix = "팔을 어깨 높이 부근까지 들어 주세요", phase = RepPhase.BOTTOM, views = oblique + "C",
            reason = "별도 네 운동 브랜치의 위팔 높이 띠를 유지합니다.").copy(
                // 사용자 요청(2026-10-31): 기존 높이 미달 차단만 복원. 너무 높음(LOW)·미관측·다른 beta로 예외를 넓히지 않는다.
                gates = true, gateHi = 125f, countOnlyDirection = FormDirection.HIGH,
                reason = "§123a: 사용자 요청으로 올린 구간의 위팔각 중앙값이 기존 125°를 넘는 높이 미달만 COACH 횟수에서 제외합니다. beta 상태와 무음·점수 제외 정책은 유지합니다.",
                cautions = listOf("높이 미달 횟수 차단은 사용자 요청으로 유지한 예외입니다. 검증 등급은 beta이며 촬영 방향·관측 부족은 위반으로 바꾸지 않습니다."),
            )
    }

    val byExercise = mapOf("프런트 레이즈" to (frontRaise() + raiseHeight("프런트 레이즈")), "굿모닝" to goodMorning(),
        "사이드 레터럴 레이즈" to (lateralRaise() + raiseHeight("사이드 레터럴 레이즈")), "푸시업" to pushup())

    /** 오류가 없다는 표시와 실제로 보지 못한 항목을 구분한다. 횟수 차감 사유로 쓰지 않는다. */
    fun unobservedNote(rep: RepFormRep): String? {
        val missed=rep.outcomes.filter { it.check.exercise != "푸시업" && it.check.feature.startsWith("sf_") && it.verdict==Verdict.ABSTAIN }
        if(missed.isEmpty()) return null
        val parts=missed.map {it.check.bodyPart}.distinct().joinToString("·")
        return "판단 유보 · ${parts}의 일부 자세를 확인하지 못했어요. 관절이 보이는 옆이나 사선에서 촬영해 주세요."
    }

    val scopeLines = mapOf(
        "프런트 레이즈" to "코치 모드에서는 팔 높이 미달, 상체 기울기, 몸통이나 무릎의 큰 움직임이 관측된 회를 세지 않아요. 시험 단계이며 힘의 출처·미세한 반동·척추 말림을 직접 판정하지 않아요. 촬영 방향이나 관절이 확인되지 않는 항목은 판단을 유보해요.",
        "굿모닝" to "상체를 숙였다 펴는 움직임을 세요. 코치 모드에서는 무릎의 큰 움직임이나 과한 굽힘이 관측된 회를 세지 않아요. 무릎의 약한 고정 굽힘은 허용해요. 척추가 둥글게 말리는지는 판정하지 못해요. 무릎이 가려진 항목은 판단을 유보해요.",
        "사이드 레터럴 레이즈" to "코치 모드에서는 팔 높이 미달, 팔꿈치의 과한 굽힘·펴짐, 앞쪽 사선 경로 이탈이 관측된 회를 세지 않아요. 첨부 영상의 약한 팔꿈치 굽힘·앞쪽 사선을 연습하는 시험 단계 기준이며 모든 변형의 정답은 아니에요. 관절이나 촬영 방향을 확인할 수 없으면 판단을 유보해요. 견갑골 움직임은 판정하지 않아요.",
        "푸시업" to "몸통 정렬을 유지하면서 어깨와 골반이 함께 내려갔다 올라와 팔을 펴야 1회로 세요. 필요한 어깨·골반·무릎·발목·팔 관절을 놓친 동작은 판단을 유보하고 세지 않아요. 시작 자세로 돌아오면 다음 회를 확인해요. 코치 모드에서는 한 회 안에 골반 처짐·들림, 무릎 굽힘, 고개 과들림·과숙임·머리 처짐·화면 쪽 돌림이 한 번이라도 관측되면 그 회를 제외해요. 팔 범위는 시작·아래 구간에서 판단해요. 약간 앞을 보는 고개는 허용해요. 검사는 시험 단계이며 화면에 참고로 표시해요. 양쪽 어깨·골반이 보이면 기울어짐과 비틀림 의심을 참고로만 알리며, 이 안내로 횟수를 빼지 않아요. 측면에서 관절이 겹치거나 가려지면 유보해요. 골반의 정확한 앞뒤 경사·가려진 고개·눈동자 시선·척추 곡선·견갑골 움직임·바닥 접촉은 판정하지 못해요.",
    )
}
