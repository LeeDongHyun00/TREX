package com.example.trex_kotlin

import com.trex.engine.ExerciseCatalog

/** 새 계획·대체·세션 진입 모두 같은 26종을 사용한다. 과거 운동 기록은 변환하지 않는다. */
internal fun normalizeV2Plan(plan: List<Workout>): List<Workout> = plan.mapNotNull { workout ->
    val canonical = ExerciseCatalog.canonical(workout.name) ?: return@mapNotNull null
    workout.copy(name = canonical, category = ExerciseCatalog.category(canonical),
        alt = workout.alt?.let { alt -> ExerciseCatalog.canonical(alt.name)?.let { alt.copy(name = it) } })
}
