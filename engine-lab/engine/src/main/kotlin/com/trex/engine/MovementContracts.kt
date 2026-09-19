package com.trex.engine

/** 제품·실험 앱·오프라인 재생이 공유하는 관측 조건. 수치는 검출 경계이며 정자세의 정답이 아니다. */
object MovementContracts {
    fun apply(p: ExerciseRepProfile): ExerciseRepProfile {
        fun configure(s: RepSignal?): RepSignal? = s?.let {
            var r = when {
                it.feature.startsWith("knee_") && !it.feature.startsWith("knee_h") && it.feature != "knee_gap2d" ->
                    it.copy(startMin = 150f, outboundSign = -1, plausibleMin = 15f, plausibleMax = 185f)
                it.feature.startsWith("hip_") && !p.floor && it.feature != "hip_below_knee" ->
                    it.copy(startMin = 150f, outboundSign = -1, plausibleMin = 15f, plausibleMax = 185f)
                it.feature.startsWith("elbow_") && !it.feature.startsWith("elbow_h") ->
                    it.copy(startMin = 145f, outboundSign = -1, plausibleMin = 15f, plausibleMax = 185f)
                it.feature.startsWith("upperarm_vert") -> it.copy(startMin = 145f, outboundSign = -1, plausibleMin = 0f, plausibleMax = 185f)
                it.feature.startsWith("knee_h") -> it.copy(startMax = -.15f, outboundSign = 1)
                it.feature == "wrist_shoulder_d" -> it.copy(startMin = .4f, outboundSign = -1, plausibleMin = .1f,
                    supportingMotion = mapOf("visible_elbow_angle" to 25f))
                it.feature == "head_ground" -> it.copy(feature = "torso_ground_angle", minAmp = 15f, startMax = 25f, outboundSign = 1)
                it.feature == "hip_ang" -> it.copy(startMin = 135f, outboundSign = -1)
                it.feature == "knee_gap2d" -> it.copy(signChangeFeature = "scissor_signed")
                else -> it
            }
            val side = if (it.feature.endsWith("_L")) "L" else "R"
            if (p.exercise == "스탠딩 니업") r = r.copy(supportingMotion = mapOf("knee_h_$side" to .12f))
            if (p.exercise in setOf("바벨 데드리프트", "굿모닝")) r = r.copy(supportingMotion = mapOf("torso_incl" to 12f))
            if (p.exercise == "스탠딩 사이드 크런치") r = r.copy(supportingMotion = mapOf("knee_elbow_dist_$side" to .12f, "torso_roll" to 5f))
            r
        }
        return p.copy(commonSignal = configure(p.commonSignal), leftSignal = configure(p.leftSignal), rightSignal = configure(p.rightSignal))
    }

    /** 사용자가 출발 구간을 명시한다. 장비·지면 접촉은 추측하지 않는다. */
    fun deadliftStart(p: ExerciseRepProfile, fromFloor: Boolean): ExerciseRepProfile =
        if (p.exercise != "바벨 데드리프트") p else p.copy(commonSignal = p.commonSignal?.copy(
            startMin = if (fromFloor) null else 150f, startMax = if (fromFloor) 135f else null,
            outboundSign = if (fromFloor) 1 else -1,
        ))
}

/** 직접 선택한 최소 왕복 범위. 초반 움직임을 자동 복사하지 않는다. */
data class RangeGoal(val amplitude: Float) {
    init { require(amplitude.isFinite() && amplitude > 0f) }
    fun met(event: ExerciseRepEvent): Boolean = event.cycles.isNotEmpty() &&
        event.cycles.values.all { it.max - it.min >= amplitude }
}

/** 범위 목표가 있으면 사용자 총횟수 수정으로 그 범위까지 충족했다고 추정하지 않는다. */
data class RepProgress(val observed:Int, val rangeMet:Int?=null, val userEntered:Int?=null) {
    val goalProgress:Int get()=rangeMet ?: userEntered ?: observed
    val recordedCount:Int get()=userEntered ?: observed
}
