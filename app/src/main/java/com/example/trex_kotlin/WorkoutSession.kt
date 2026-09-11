package com.example.trex_kotlin

import kotlin.math.ceil

/** 표시 문자열과 실행 타이머가 같은 계산을 사용한다. 속도/휴식은 사용자가 바꿀 수 있는 실행 기본값이다. */
data class WorkoutTiming(val repetitions: Int, val sets: Int, val secondsPerRep: Int, val workSeconds: Int, val restSeconds: Int) {
    val totalSeconds: Int get() = workSeconds * sets + restSeconds * (sets - 1).coerceAtLeast(0)
    val minutes: Int get() = ceil(totalSeconds / 60.0).toInt().coerceAtLeast(1)
}

object WorkoutPacing {
    fun secondsPerRep(name: String, category: String): Int = when (name) {
        "바벨 데드리프트", "바벨 스쿼트", "바벨 런지", "런지", "사이드 런지", "크로스 런지", "불가리안 스플릿 스쿼트", "버피", "버피 테스트" -> 4
        "점핑잭", "하이 니", "마운틴 클라이머", "스키터 점프", "제자리 걷기" -> 2
        else -> if (category == "회복") 4 else 3
    }
    fun restSeconds(name: String, category: String): Int = when {
        name in setOf("바벨 데드리프트", "바벨 스쿼트", "바벨 런지", "오버헤드 프레스", "딥스", "불가리안 스플릿 스쿼트") -> 120
        category in setOf("유산소", "회복") -> 30
        category in setOf("코어", "복근") -> 45
        else -> 60
    }
}

fun Workout.timing(): WorkoutTiming {
    val parsed = repsSpec()
    val pace = (secondsPerRep ?: WorkoutPacing.secondsPerRep(name, category)).coerceIn(1, 15)
    val sets = parsed.sets.coerceIn(1, 20)
    val work = when {
        reps.contains("초") -> parsed.count.coerceIn(1, 3600)
        reps.contains("분") && !reps.contains("회") -> parsed.count.coerceIn(1, 60) * 60
        reps.contains("회") -> parsed.count.coerceIn(1, 999) * pace
        else -> (Regex("\\d+").find(duration)?.value?.toIntOrNull() ?: 6).coerceIn(1, 60) * 60
    }
    return WorkoutTiming(parsed.count, sets, pace, work, (restSeconds ?: WorkoutPacing.restSeconds(name, category)).coerceIn(0, 600))
}

enum class SessionPhase { PREPARE, WORK, REST }

data class SessionStep(
    val token: Int, val phase: SessionPhase, val workout: Workout, val originalId: String,
    val exerciseIndex: Int, val setNumber: Int, val totalSets: Int, val seconds: Int,
) {
    val setLabel get() = "$setNumber / $totalSets 세트"
}

/** 세트별 고유 ID로 리포트를 보존한다. 휴식은 마지막 세트 뒤에는 넣지 않는다. */
fun buildSessionSteps(plan: List<Workout>): List<SessionStep> = buildList {
    plan.forEachIndexed { exerciseIndex, workout ->
        val timing = workout.timing()
        fun addStep(phase: SessionPhase, set: Int, seconds: Int) {
            val single = workout.copy(id = "${workout.id}::set:$set", reps = "${workout.repsSpec().targetLabel} × 1세트",
                duration = "${ceil(timing.workSeconds / 60.0).toInt()}분", restSeconds = 0, done = false)
            add(SessionStep(size, phase, single, workout.id, exerciseIndex, set, timing.sets, seconds))
        }
        addStep(SessionPhase.PREPARE, 1, if (workout.posture && workout.postureSupported()) 15 else 5)
        for (set in 1..timing.sets) {
            addStep(SessionPhase.WORK, set, timing.workSeconds)
            if (set < timing.sets && timing.restSeconds > 0) addStep(SessionPhase.REST, set + 1, timing.restSeconds)
        }
    }
}

/** UI 콜백과 타이머 만료가 겹쳐도 같은 token을 한 번만 넘기는 순수 상태 머신. */
data class SessionProgress(
    val index: Int, val remainingMs: Long, val elapsedMs: Long = 0,
    val completed: Set<Int> = emptySet(), val skipped: Set<Int> = emptySet(),
) {
    val secondsLeft get() = ((remainingMs.coerceAtLeast(0) + 999) / 1000).toInt()
    fun tick(deltaMs: Long, paused: Boolean): SessionProgress = if (paused || index < 0) this else {
        val consumed = deltaMs.coerceAtLeast(0).coerceAtMost(remainingMs.coerceAtLeast(0))
        copy(remainingMs = remainingMs - consumed, elapsedMs = elapsedMs + consumed)
    }
    fun advance(steps: List<SessionStep>, expectedToken: Int, skip: Boolean): SessionProgress {
        val step = steps.getOrNull(index) ?: return this
        if (step.token != expectedToken) return this
        val next = steps.getOrNull(index + 1)
        return copy(index = next?.token ?: -1, remainingMs = (next?.seconds ?: 0) * 1000L,
            completed = if (step.phase == SessionPhase.WORK && !skip) completed + step.token else completed,
            skipped = if (step.phase == SessionPhase.WORK && skip) skipped + step.token else skipped)
    }
    fun completedWorkouts(steps: List<SessionStep>): List<Workout> = steps.filter { it.token in completed && it.phase == SessionPhase.WORK }
        .map { it.workout.copy(done = true) }
    fun completedOriginalIds(steps: List<SessionStep>): Set<String> = steps.filter { it.phase == SessionPhase.WORK }.groupBy { it.originalId }
        .filterValues { group -> group.all { it.token in completed } }.keys
}
