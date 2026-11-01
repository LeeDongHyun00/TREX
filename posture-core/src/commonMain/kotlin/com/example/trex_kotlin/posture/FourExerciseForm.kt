// tools/sync_ios_core.py 생성본 — Android 정본에서 수정한 뒤 동기화하세요.
package com.example.trex_kotlin.posture

/**
 * 네 종목 사용자 보고(2026-10-31, §123)의 반복별 관측.
 * 운동 설명은 임계값 검증 자료가 아니다. 새 검사는 모두 beta·화면 참고이며 음성·점수를 바꾸지 않는다.
 * §123a 사용자 요청으로 두 레이즈의 관측된 높이 미달만 COACH 횟수에서 제외한다.
 * 척추 분절·근육의 힘·반동의 원인은 관절 모델로 직접 볼 수 없다.
 */
object FourExerciseForm {
    const val GOOD_MORNING_SIGNAL = "goodmorning_hinge"
    const val PUSHUP_SIGNAL = "pushup_distance"
    const val PUSHUP_POSTURE = "pushup_posture"
    const val PUSHUP_HIP = "pushup_hip_offset"
    const val PUSHUP_KNEE = "pushup_knee"
    const val PUSHUP_ELBOW = "pushup_elbow"
    const val ARM_PLANE = "raise_plane"
    const val RAISE_SIGNAL = "upperarm_vert_mean"
    val changedSignalExercises = setOf("굿모닝", "푸시업", "프런트 레이즈", "사이드 레터럴 레이즈")

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

    private fun frontRaise() = listOf(
        check("프런트 레이즈", "상체 기울기", "상체", "torso_incl", hi = 25f,
            high = "상체가 많이 기울어져 있어요", fix = "몸통을 세우고 팔을 앞으로 들어 주세요",
            reason = "처음부터 숙인 자세가 시작 기준에 흡수되지 않도록 반복 몸통 기울기 중앙값을 봅니다. 척추 말림 판정은 아닙니다."),
        check("프런트 레이즈", "몸통 흔들림", "상체", "torso_pitch", hi = 20f, stat = RepFormStat.SPAN,
            high = "팔을 들 때 몸통이 앞뒤로 크게 움직였어요", fix = "몸통을 고정하고 반동 없이 천천히 들어 주세요",
            reason = "정상 팔 들림을 반동으로 오인하지 않고 몸통의 반복 안 p90−p10만 봅니다. 20°는 잠정 관측 띠이며 힘의 출처는 모릅니다."),
    )

    private fun goodMorning() = buildList {
        for ((side, label) in listOf("L" to "왼쪽", "R" to "오른쪽")) {
            add(check("굿모닝", "$label 무릎 움직임", "무릎", "knee_$side", hi = 20f, stat = RepFormStat.SPAN,
                high = "반복 중 $label 무릎 각도가 크게 변했어요", fix = "무릎은 살짝 굽힌 상태로 유지하고 엉덩이를 뒤로 보내 주세요",
                views = oblique + "C", reason = "무릎의 약한 굽힘은 정상입니다. 양 무릎 평균으로 한쪽 움직임을 희석하지 않고 쪽별 변화량을 봅니다."))
            add(check("굿모닝", "$label 무릎 과굽힘", "무릎", "knee_$side", lo = 140f, phase = RepPhase.BOTTOM,
                low = "$label 무릎이 많이 굽혀졌어요", fix = "무릎은 살짝만 굽히고 고관절을 접어 주세요",
                views = oblique + "C", reason = "무릎을 완전히 펴라고 지시하지 않습니다. 하단의 과한 굽힘만 넓은 잠정 띠로 관측합니다."))
        }
    }

    private fun lateralRaise() = buildList {
        for ((side, label) in listOf("L" to "왼쪽", "R" to "오른쪽")) {
            add(check("사이드 레터럴 레이즈", "$label 팔꿈치 굽힘", "팔꿈치", "elbow_$side", lo = 135f, hi = 175f,
                low = "$label 팔꿈치를 많이 접었어요", high = "$label 팔꿈치가 거의 펴져 있어요",
                fix = "팔꿈치를 살짝 굽힌 모양을 유지하며 들어 주세요", phase = RepPhase.BOTTOM,
                views = oblique + "C", reason = "첨부 영상의 약한 팔꿈치 굽힘을 팔마다 관측합니다. 135~175°는 영상에서 보정한 값이 아닌 잠정 참고 띠입니다."))
            add(check("사이드 레터럴 레이즈", "$label 팔 경로", "팔", "${ARM_PLANE}_$side", lo = 5f, hi = 60f,
                low = "$label 팔이 몸 옆이나 뒤쪽으로 올라왔어요", high = "$label 팔이 앞쪽으로 많이 모였어요",
                fix = "팔을 몸보다 조금 앞쪽 사선으로 들어 주세요", phase = RepPhase.BOTTOM,
                reason = "팔을 든 구간에만 몸의 좌우·전방 축으로 상완 방향을 잽니다. 어깨 내회전·견갑 움직임은 보지 못합니다. 개인차를 오류로 단정하지 않는 beta입니다."))
        }
    }

    private fun pushup() = listOf(
        check("푸시업", "몸통 정렬", "골반", PUSHUP_HIP, lo = -.08f, hi = .08f,
            low = "골반이 어깨와 발목을 잇는 선보다 내려갔어요", high = "골반이 어깨와 발목을 잇는 선보다 올라갔어요",
            fix = "복부와 엉덩이에 힘을 주고 어깨부터 발목까지 한 선을 유지해 주세요", views = floorSide, floor = true, unit = "",
            reason = "플랭크와 같은 보이는 쪽 사슬·중력 기준 부호를 씁니다. 시작부터 틀어진 자세도 절대 이탈로 관측하며 척추 곡선의 판정은 아닙니다."),
        check("푸시업", "다리 펴기", "무릎", PUSHUP_KNEE, lo = 150f,
            low = "푸쉬업 중 무릎이 많이 굽혀졌어요", fix = "다리를 펴고 발끝으로 지지해 주세요. 무릎을 대려면 니 푸쉬업을 선택해 주세요",
            views = floorSide, floor = true, reason = "푸시업과 니푸쉬업의 지지 자세를 구분해 안내합니다. 보이는 쪽 무릎만 측정하며 접지·압력은 판정하지 않습니다."),
        check("푸시업", "팔 굽힘 범위", "팔꿈치", PUSHUP_ELBOW, hi = 120f, phase = RepPhase.BOTTOM,
            high = "아래 구간에서도 팔꿈치가 조금만 굽혀졌어요",
            fix = "몸통을 한 선으로 유지하며 가슴을 조금 더 내려 주세요", views = floorSide, floor = true,
            reason = "§123b 첨부 영상의 충분한 가동범위 안내. 보이는 쪽 팔꿈치 굽힘각으로 얕은 하강을 참고하며, 가슴의 실제 바닥 접촉을 판정하지 않습니다. 120°는 실기기 검증 전 잠정 띠이며 영상의 팔 벌림 45°와 다른 각도입니다."),
        check("푸시업", "시작 팔 펴기", "팔꿈치", PUSHUP_ELBOW, lo = 150f, phase = RepPhase.TOP,
            low = "내려가기 전 팔꿈치가 덜 펴져 있었어요",
            fix = "몸통을 한 선으로 유지하며 팔을 펴고 다음 반복을 시작해 주세요", views = floorSide, floor = true,
            reason = "§123b 하강 직전 상단 창의 팔꿈치각만 봅니다. 150°는 잠정 참고 띠입니다. 카운터가 복귀 도중 확정할 수 있어 현재 회의 끝까지 폄을 판정했다고 말하지 않습니다."),
    )

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

    val scopeLines = mapOf(
        "프런트 레이즈" to "코치 모드에서는 팔 높이 미달이 확인된 회를 세지 않아요. 몸통 기울기와 흔들림은 참고로 표시하며 척추 말림과 힘의 출처는 판정하지 않아요.",
        "굿모닝" to "상체를 숙였다 펴는 움직임을 세고, 무릎 움직임은 참고로 표시해요. 무릎은 살짝 굽혀도 됩니다. 척추가 둥글게 말리는지는 판정하지 못해요.",
        "사이드 레터럴 레이즈" to "코치 모드에서는 팔 높이 미달이 확인된 회를 세지 않아요. 팔꿈치와 앞쪽 사선 경로는 참고로 표시하며 견갑골 움직임은 판정하지 않아요.",
        "푸시업" to "옆에서 엎드린 자세가 확인될 때 가슴 이동을 세요. 골반·무릎·팔 굽힘과 시작 시 팔 펴기는 참고로 표시해요. 척추 말림·어깨 회전·바닥 접촉은 판정하지 못해요.",
    )
}
