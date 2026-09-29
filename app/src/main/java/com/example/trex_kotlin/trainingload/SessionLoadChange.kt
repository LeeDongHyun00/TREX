package com.example.trex_kotlin.trainingload

data class SessionMuscleChange(val before: MuscleStatus, val after: MuscleStatus) {
    val delta: Double get() = after.value - before.value
}

/** 같은 평가 시각에서 이번 세션만 제외/포함한다. 운동 전 실측값이나 회복량 비교가 아니다. */
fun sessionLoadChanges(snapshot: LoadSnapshot, sessionId: String): List<SessionMuscleChange> {
    if (sessionId.isBlank()) return emptyList()
    val after = MuscleLoadEngine.snapshot(snapshot.sets, snapshot.calculatedAt)
    val current = after.sets.filter { it.sessionId == sessionId }
    val affected = current.flatMap { MuscleLoadEngine.doses(it) }.filter { it.dose > 0 }.map { it.muscle }.toSet()
    if (affected.isEmpty()) return emptyList()
    val before = MuscleLoadEngine.snapshot(after.sets.filter { it.sessionId != sessionId }, after.calculatedAt)
        .muscles.associateBy { it.muscle }
    return after.muscles.filter { it.muscle in affected }.map { SessionMuscleChange(before.getValue(it.muscle), it) }
        .sortedByDescending { it.delta }
}
