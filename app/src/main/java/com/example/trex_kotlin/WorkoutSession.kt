package com.example.trex_kotlin

import com.example.trex_kotlin.posture.ExerciseProfiles
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
    /**
     * 1회 예상 시간(초) — 예상 소요 시간에만 쓴다(종료 조건이 아니다). = 한 동작(카운터 사이클) 시간 × 1회를 이루는 동작 수.
     * 런지류(런지·바벨 런지·사이드 런지·크로스 런지)는 "왼쪽과 오른쪽을 한 번씩 = 1회"(사용자 결정 2026-09-24, `RepUnit.SIDE_PAIR`)라
     * 1회가 두 걸음이다 — 한 걸음 4초 × 2 = 8초. 목표 10회 = 20걸음이므로 한 걸음 시간으로 두면 세트 시간을 절반으로 잡는다.
     * 동작 수는 프로필의 표시 단위에서 읽는다 — 자동 횟수가 세는 단위와 예상 시간이 같은 정의를 쓰게.
     */
    fun secondsPerRep(name: String, category: String): Int = secondsPerMovement(name, category) *
        (ExerciseProfiles.forName(name)?.repUnit?.cyclesPerRep ?: 1)

    /** 한 동작(런지류는 한 걸음) 예상 시간(초). */
    private fun secondsPerMovement(name: String, category: String): Int = when (name) {
        "바벨 데드리프트", "기본 스쿼트", "바벨 런지", "런지", "사이드 런지", "크로스 런지", "불가리안 스플릿 스쿼트", "버피", "버피 테스트" -> 4
        "점핑잭", "하이 니", "마운틴 클라이머", "스키터 점프", "제자리 걷기" -> 2
        else -> if (category == "회복") 4 else 3
    }
    fun restSeconds(name: String, category: String): Int = when {
        name in setOf("바벨 데드리프트", "기본 스쿼트", "바벨 런지", "오버헤드 프레스", "딥스", "불가리안 스플릿 스쿼트") -> 120
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
    val workMillis: Map<Int, Long> = emptyMap(),
) {
    val secondsLeft get() = ((remainingMs.coerceAtLeast(0) + 999) / 1000).toInt()

    /**
     * @param heldDeltaMs 플랭크 유지 시계(spec §99, 설계 §4.3)가 이번 틱에 새로 인정한 시간 — null 이 아니고 시간 목표면 남은 시간을 벽시계 대신 **이 증분**으로
     *   줄인다(카메라가 확인한 플랭크 시간, 사용자 결정 Q2 2026-10-06 밤). 세션 경과는 그때도 벽시계([deltaMs])다. null 이면 종전과 같다.
     *   음수 증분은 0 으로 자른다 — 남은 시간은 거꾸로 가지 않는다(시계도 단조 적립).
     *   **세트 가동 시간**([workMillis] — 기록의 플랭크 초·근육 부하 초)은 시간 목표면 두 경로 모두 '목표가 인정한 시간'(남은 시간에서 뺀 양)이다. 인정 시간 경로에서
     *   벽시계를 쌓으면 무릎을 대거나 화면 밖에 있던 시간까지 플랭크 시간으로 기록됐다(원칙 #1, 리뷰 2026-10-07). 종전 경로는 전과 같다(목표에서 자른 벽시계).
     */
    fun tick(deltaMs: Long, paused: Boolean, timed: Boolean = true, trackElapsed: Boolean = true, trackWork: Boolean = false,
             heldDeltaMs: Long? = null): SessionProgress = if (paused || index < 0) this else {
        val wall = deltaMs.coerceAtLeast(0)
        val consumed = if (timed) (heldDeltaMs ?: deltaMs).coerceAtLeast(0).coerceAtMost(remainingMs.coerceAtLeast(0)) else wall
        // 세션 경과 — 종전 경로는 남은 시간과 같은 양(목표에서 자른 벽시계), 인정 시간 경로는 벽시계 그대로
        val clock = if (timed && heldDeltaMs == null) consumed else wall
        copy(remainingMs = if (timed) remainingMs - consumed else remainingMs, elapsedMs = elapsedMs + if (trackElapsed) clock else 0,
            workMillis = if (trackWork) workMillis + (index to ((workMillis[index] ?: 0L) + consumed)) else workMillis)
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
    fun completedWorkouts(steps: List<SessionStep>): List<Workout> = steps.filter { (it.token in completed || it.token in recordedCounts || (it.timed && (workMillis[it.token] ?: 0) >= 1000)) && it.phase == SessionPhase.WORK }
        .map { step -> recordedCounts[step.token]?.let { count ->
            step.workout.copy(done = step.token in completed, reps = "${count}회 × 1세트", target = WorkoutTarget.Repetitions(count))
        } ?: step.workout.copy(done = step.token in completed) }
    fun completedOriginalIds(steps: List<SessionStep>): Set<String> = steps.filter { it.phase == SessionPhase.WORK }.groupBy { it.originalId }
        .filterValues { group -> group.all { it.token in completed } }.keys
}

/** 휴식/준비는 제외하고 세트별 실제 가동 시간을 전달한다. */
fun SessionProgress.workDurations(steps: List<SessionStep>): Map<String, Int> = steps.filter { it.phase == SessionPhase.WORK }
    .associate { it.workout.id to ((workMillis[it.token] ?: 0L) / 1000).toInt() }

/**
 * 플랭크 유지 시계 → 세션 진행 다리(spec §99, `docs/FLOOR_FAMILY_DESIGN.md` §4.3 '브리지'). 분석 스레드가 [publish] 로 카메라가 확인한 시간을 쓰고,
 * TrexApp 의 tick 루프(100 ms)가 [take] 로 이번 틱에 줄일 남은 시간을 받는다. 라이브 화면 파라미터는 이 홀더 하나만 더한다(`PostureLiveSessionScreen` 의
 * 레지스터 — 기기 VerifyError, CLAUDE.md). 키는 세트의 운동 id(`…::set:N`) — 다른 세트·묶이지 않음이면 null(종전 벽시계).
 *  - camera: 인정 시간의 증분만 넘긴다(단조 — 시계가 거꾸로 가지 않으므로 `SessionProgress.tick` 의 자르기와 맞다).
 *  - clock: 시계가 폴백으로 넘어갔거나(WORK 20 s 동안 한 번도 확인 못 함 — `PlankHoldClock.FALLBACK_MS`) 분석 스레드 소식이 [PlankHoldClock.FALLBACK_MS] 넘게 없을 때
 *    (카메라 끊김) — 벽시계. 그때까지 한 번도 확인하지 못했다면 카메라 모드로 흘려보낸 벽시계를 한 번 더 넘긴다: "이번 세트는 시계로 잴게요"(설계 §4.7)는
 *    세트를 처음부터 시계로 잰 것과 같다(종전 동작). 한 번이라도 확인했으면 그 뒤만 벽시계로 잇는다(멈춘 시간을 소급해 채우지 않는다).
 * [ENABLED] 가 false 면 TrexApp 이 묶지 않는다(설계 §8 '플래그 뒤에 둔다').
 */
class HoldBridge {
    private var key: String? = null
    private var held = 0L
    private var camera = true
    private var consumed = 0L
    private var cameraWall = 0L
    private var sincePublish = 0L
    private var catchUp = 0L

    /** 이 세트를 묶는다(WORK 시작) — 상태를 처음부터. */
    @Synchronized fun bind(key: String) {
        this.key = key; held = 0L; camera = true; consumed = 0L; cameraWall = 0L; sincePublish = 0L; catchUp = 0L
    }

    /** 세트를 떠남(화면 소멸·세션 시작). 다른 키면 그대로 둔다. null 이면 무조건 푼다. */
    @Synchronized fun release(key: String? = null) { if (key == null || this.key == key) this.key = null }

    /** 분석 스레드 — 시계의 인정 시간과 출처(`camera`면 true). 묶인 키가 아니면 버린다. */
    @Synchronized fun publish(key: String, heldMs: Long, camera: Boolean) {
        if (this.key != key) return
        held = maxOf(held, heldMs); sincePublish = 0L
        if (this.camera && !camera) toClock()
    }

    /**
     * 이번 틱에 줄일 남은 시간 — null = 이 세트는 다리가 없다(벽시계). [wallDeltaMs] 는 이번 틱의 벽시계(일시정지 중에는 부르지 않는다).
     * 분석 소식이 끊겨 벽시계로 넘어가는 틱은 그 틱의 벽시계를 카메라 몫에 넣기 **전에** 넘긴다 — 전에는 소급분(그때까지의 벽시계)에 이번 틱이 이미 들어 있는데
     * 이번 틱을 또 더해 한 틱만큼 두 번 줄였다(리뷰 2026-10-07).
     */
    @Synchronized fun take(key: String?, wallDeltaMs: Long): Long? {
        if (key == null || this.key != key) return null
        val wall = wallDeltaMs.coerceAtLeast(0L)
        if (camera) {
            sincePublish += wall
            if (sincePublish <= com.example.trex_kotlin.posture.PlankHoldClock.FALLBACK_MS) {
                cameraWall += wall
                val d = (held - consumed).coerceAtLeast(0L); consumed = maxOf(consumed, held)
                return d
            }
            toClock()
        }
        val out = wall + catchUp; catchUp = 0L
        return out
    }

    /** 지금 카메라 시간으로 재는가(묶여 있고 폴백 전). */
    val usesCamera: Boolean @Synchronized get() = key != null && camera

    /** 이 세트([key])를 묶었고 벽시계로 넘어갔는가(시계 폴백이든 분석 소식 끊김이든) — 화면·리포트가 '카메라 미확인' 을 밝힌다. */
    @Synchronized fun clockFor(key: String?): Boolean = key != null && this.key == key && !camera

    private fun toClock() {
        camera = false
        if (held == 0L) catchUp += (cameraWall - consumed).coerceAtLeast(0L)
    }

    companion object { const val ENABLED = true }
}

/**
 * 목표 도달 자동 진행의 발화 기다림(§63) — 최대 [MAX_MS], 최소 [GRACE_MS] 뒤 [QUIET_POLLS] 번 연달아 조용하면 넘어간다.
 * 넘어갈 때 `speech.stop()` 이 마지막 판정·안내를 잘랐다(런지 "6" 뒤 0.7 s). 두 번 조용해야 하는 이유: `SpeechCoach` 는 끝난 발화 뒤
 * 보류한 코칭 문장을 이어 말하기 직전 아주 잠깐 조용하다.
 */
object AdvanceHold {
    const val MAX_MS = 2_000L
    const val GRACE_MS = 300L
    const val QUIET_POLLS = 2
    const val POLL_MS = 100L
    fun due(elapsedMs: Long, quietPolls: Int): Boolean = elapsedMs >= MAX_MS || (elapsedMs >= GRACE_MS && quietPolls >= QUIET_POLLS)
}
