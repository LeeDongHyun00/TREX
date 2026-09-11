package com.example.trex_kotlin

import kotlin.math.ceil

/** 자세 관측 방식과 독립된 운동 목표. 반복 운동의 예상 시간은 종료 조건이 아니다. */
sealed interface WorkoutTarget {
    val amount: Int
    data class Repetitions(override val amount: Int) : WorkoutTarget
    data class Duration(override val amount: Int) : WorkoutTarget
}

fun Workout.resolvedTarget(): WorkoutTarget = target ?: when {
    reps.contains("초") -> WorkoutTarget.Duration(repsSpec().count.coerceIn(1, 3600))
    reps.contains("분") && !reps.contains("회") -> WorkoutTarget.Duration(repsSpec().count.coerceIn(1, 60) * 60)
    else -> WorkoutTarget.Repetitions(repsSpec().count.coerceIn(1, 999))
}

/** 반복 시간은 예상 소요 시간에만 사용한다. */
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
    val work = if (target == null && !Regex("(회|초|분)").containsMatchIn(reps)) {
        // 단위 없는 구형 자유 운동의 예상치는 보존한다. 실행은 시간 종료로 바꾸지 않는다.
        (Regex("\\d+").find(duration)?.value?.toIntOrNull() ?: 6).coerceIn(1, 60) * 60
    } else when (val goal = resolvedTarget()) {
        is WorkoutTarget.Duration -> goal.amount.coerceIn(1, 3600)
        is WorkoutTarget.Repetitions -> goal.amount.coerceIn(1, 999) * pace
    }
    return WorkoutTiming(parsed.count, sets, pace, work, (restSeconds ?: WorkoutPacing.restSeconds(name, category)).coerceIn(0, 600))
}

enum class SessionPhase { PREPARE, WORK, REST }

data class SessionStep(
    val token: Int, val phase: SessionPhase, val workout: Workout, val originalId: String,
    val exerciseIndex: Int, val setNumber: Int, val totalSets: Int, val seconds: Int,
) {
    val setLabel get() = "$setNumber / $totalSets 세트"
    val timed get() = phase == SessionPhase.REST || (phase == SessionPhase.WORK && workout.resolvedTarget() is WorkoutTarget.Duration)
    val targetCount get() = (workout.resolvedTarget() as? WorkoutTarget.Repetitions)?.amount
}

/** 세트별 고유 ID로 리포트를 보존한다. 휴식은 마지막 세트 뒤에는 넣지 않는다. */
fun buildSessionSteps(plan: List<Workout>): List<SessionStep> = buildList {
    plan.forEachIndexed { exerciseIndex, workout ->
        val timing = workout.timing()
        fun addStep(phase: SessionPhase, set: Int, seconds: Int) {
            val targetLabel = when (val goal = workout.resolvedTarget()) {
                is WorkoutTarget.Duration -> if (goal.amount % 60 == 0) "${goal.amount / 60}분" else "${goal.amount}초"
                is WorkoutTarget.Repetitions -> "${goal.amount}회"
            }
            val single = workout.copy(id = "${workout.id}::set:$set", reps = "$targetLabel × 1세트",
                duration = "${ceil(timing.workSeconds / 60.0).toInt()}분", restSeconds = 0, done = false)
            add(SessionStep(size, phase, single, workout.id, exerciseIndex, set, timing.sets, seconds))
        }
        addStep(SessionPhase.PREPARE, 1, 0)
        for (set in 1..timing.sets) {
            addStep(SessionPhase.WORK, set, if (workout.resolvedTarget() is WorkoutTarget.Duration) timing.workSeconds else 0)
            if (set < timing.sets && timing.restSeconds > 0) addStep(SessionPhase.REST, set + 1, timing.restSeconds)
        }
    }
}

/** UI 콜백과 타이머 만료가 겹쳐도 같은 token을 한 번만 넘기는 순수 상태 머신. */
data class SessionProgress(
    val index: Int, val remainingMs: Long, val elapsedMs: Long = 0,
    val completed: Set<Int> = emptySet(), val skipped: Set<Int> = emptySet(),
    val repetitions: Int = 0,
    /** 수동 보정과 부분 수행을 원본 카메라 검출 수와 구분해 보존한다. */
    val recordedCounts: Map<Int, Int> = emptyMap(),
) {
    val secondsLeft get() = ((remainingMs.coerceAtLeast(0) + 999) / 1000).toInt()
    fun tick(deltaMs: Long, paused: Boolean, timed: Boolean = true, trackElapsed: Boolean = true): SessionProgress = if (paused || index < 0) this else {
        val consumed = if (timed) deltaMs.coerceAtLeast(0).coerceAtMost(remainingMs.coerceAtLeast(0)) else deltaMs.coerceAtLeast(0)
        copy(remainingMs = if (timed) remainingMs - consumed else remainingMs, elapsedMs = elapsedMs + if (trackElapsed) consumed else 0)
    }
    fun setRepetitions(steps: List<SessionStep>, expectedToken: Int, value: Int): SessionProgress {
        val step = steps.getOrNull(index) ?: return this
        if (step.token != expectedToken || step.phase != SessionPhase.WORK || step.targetCount == null) return this
        return copy(repetitions = value.coerceIn(0, 999))
    }
    fun targetReached(step: SessionStep): Boolean = step.token == index && when {
        step.phase == SessionPhase.PREPARE -> false
        step.timed -> remainingMs <= 0
        else -> step.targetCount?.let { repetitions >= it } == true
    }
    fun captureCount(step: SessionStep): SessionProgress = if (step.token != index || step.phase != SessionPhase.WORK || step.targetCount == null) this
        else copy(recordedCounts = if (repetitions > 0) recordedCounts + (step.token to repetitions) else recordedCounts - step.token)
    fun advance(steps: List<SessionStep>, expectedToken: Int, skip: Boolean): SessionProgress {
        val step = steps.getOrNull(index) ?: return this
        if (step.token != expectedToken) return this
        val next = steps.getOrNull(index + 1)
        val finished = !skip && (step.targetCount == null || repetitions >= step.targetCount!!)
        return copy(index = next?.token ?: -1, remainingMs = (next?.seconds ?: 0) * 1000L, repetitions = 0,
            completed = if (step.phase == SessionPhase.WORK && finished) completed + step.token else completed,
            recordedCounts = if (step.phase == SessionPhase.WORK && step.targetCount != null && repetitions > 0)
                recordedCounts + (step.token to repetitions) else recordedCounts,
            skipped = if (step.phase == SessionPhase.WORK && !finished) skipped + step.token else skipped)
    }
    fun completedWorkouts(steps: List<SessionStep>): List<Workout> = steps.filter { (it.token in completed || it.token in recordedCounts) && it.phase == SessionPhase.WORK }
        .map { step -> recordedCounts[step.token]?.let { count ->
            step.workout.copy(done = step.token in completed, reps = "${count}회 × 1세트", target = WorkoutTarget.Repetitions(count))
        } ?: step.workout.copy(done = true) }
    fun completedOriginalIds(steps: List<SessionStep>): Set<String> = steps.filter { it.phase == SessionPhase.WORK }.groupBy { it.originalId }
        .filterValues { group -> group.all { it.token in completed } }.keys
}
