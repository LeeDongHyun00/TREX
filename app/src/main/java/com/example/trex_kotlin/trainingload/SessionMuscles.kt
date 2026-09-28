package com.example.trex_kotlin.trainingload

/** 현재 세션에서 수행량이 있는 근육/좌우만 반환한다. 강조 강도는 피로 점수와 무관하다. */
fun sessionUsedMuscles(sets: List<LoadSet>, sessionId: String): Set<String> {
    if (sessionId.isBlank()) return emptySet()
    return sets.associateBy { it.id }.values.filter { it.sessionId == sessionId }
        .flatMap { MuscleLoadEngine.doses(it) }.filter { it.dose > 0 }
        .map { "${it.muscle.name}_${it.side}" }.toSet()
}
