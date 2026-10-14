package com.example.trex_kotlin.posture

/** 화면·목표 진행·음성이 같은 반복 결과를 사용한다. 일시정지는 관측만, 새 세트는 전체를 초기화한다. */
data class RequestedFormState(
    val exercise: String = "",
    val excluded: Int = 0,
    val latest: RepFormRep? = null,
    private val featureKeys: Set<String> = emptySet(),
    private val frameAt: Long = 0L,
    private val eventAt: Long = Long.MIN_VALUE,
    private val lastIssueId: String? = null,
    private val lastLongSpeechAt: Long = Long.MIN_VALUE,
    private val speechLine: String? = null,
) {
    fun onFrame(now: Long, features: Map<String, Float>): RequestedFormState {
        if (!RequestedFormCoaching.isTarget(exercise)) return this
        return copy(featureKeys = features.filterValues { it.isFinite() }.keys, frameAt = now)
    }

    fun clearObservation() = copy(latest = null, featureKeys = emptySet(), speechLine = null, eventAt = Long.MIN_VALUE)

    fun onReps(reps: List<RepFormRep>, records: List<RepRecord>, excludeShort: Boolean, now: Long): RequestedFormState {
        if (!RequestedFormCoaching.isTarget(exercise) || reps.isEmpty()) return this
        val fresh = reps.withIndex().filter { (_, rep) -> latest == null || rep.tMs > latest.tMs }
        if (fresh.isEmpty()) return this
        val last = fresh.last().value
        val issue = RequestedFormCoaching.firstIssue(last)
        val brief = issue != null && issue.check.id == lastIssueId &&
            lastLongSpeechAt != Long.MIN_VALUE && now - lastLongSpeechAt in 0 until 8_000L
        return copy(
            excluded = excluded + fresh.count { (i, rep) -> RequestedFormCoaching.excluded(rep) &&
                !(excludeShort && records.getOrNull(i)?.valid == false) },
            latest = last, eventAt = now,
            speechLine = RequestedFormCoaching.message(last, brief),
            lastIssueId = issue?.check?.id,
            lastLongSpeechAt = if (issue != null && !brief) now else lastLongSpeechAt,
        )
    }

    private val visibleIssue: RepFormOutcome? get() = RequestedFormCoaching.firstIssue(latest)?.takeIf {
        it.check.feature in featureKeys && frameAt - eventAt in 0..6_000L
    }

    val message: String? get() {
        if (!RequestedFormCoaching.isTarget(exercise)) return null
        if (featureKeys.isEmpty()) return "자세를 확인할 수 없어요. 몸 전체가 보이게 해 주세요."
        if (visibleIssue != null) return RequestedFormCoaching.message(latest)
        if (latest != null && !RequestedFormCoaching.observed(latest)) return "동작은 기록했지만 이 회의 자세는 확인하지 못했어요."
        return "보이는 부위의 자세를 확인하며 반복을 기록하고 있어요."
    }

    val scopeNote: String? get() {
        if (!RequestedFormCoaching.isTarget(exercise)) return null
        return if (exercise == "푸시업") buildList {
            if ("pushup_hand_width" !in featureKeys) add("손 간격·팔꿈치 벌어짐 미확인")
            if ("pushup_face_yaw" !in featureKeys) add("얼굴 방향 미확인")
        }.joinToString(" · ").ifEmpty { "교정 기준 확인 중" }
        else if(exercise=="굿모닝") buildList {
            if (featureKeys.none { it.startsWith("goodmorning_toe_") }) add("발끝 방향 미확인")
            if (featureKeys.none { it.startsWith("goodmorning_torso_incl_") }) add("상체 숙임 깊이 미확인")
            if (!("goodmorning_hand_head_L" in featureKeys && "goodmorning_hand_head_R" in featureKeys)) add("손 위치 일부 미확인")
            add("손가락 깍지는 판정하지 않아요")
        }.joinToString(" · ") else buildList {
            val prefix=if(exercise=="프런트 레이즈")"raise_front_plane_" else "raise_lateral_plane_"
            if (!(prefix+"L" in featureKeys && prefix+"R" in featureKeys)) add("팔 올림 방향 일부 미확인")
            if (!("raise_shoulder_lift_L" in featureKeys && "raise_shoulder_lift_R" in featureKeys)) add("어깨 들림 일부 미확인")
            if ("raise_head_turn" !in featureKeys) add("고개 방향 미확인")
            add("근육의 힘·눈동자 시선은 확인할 수 없어요")
        }.joinToString(" · ")
    }

    val landmarks: Set<Int> get() = visibleIssue?.let {
        RuleHighlight.landmarksFor(it.check.highlight ?: it.check.feature)
    }.orEmpty()

    /** 한 완료 사건에서 한 번만 호출한다. 다음 프레임에서는 지난 교정 음성을 재사용하지 않는다. */
    fun speech(now: Long): String? = speechLine.takeIf { now == eventAt }
}
