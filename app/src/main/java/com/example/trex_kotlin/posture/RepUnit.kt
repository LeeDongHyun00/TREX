package com.example.trex_kotlin.posture

/**
 * 표시 횟수의 단위 — 카운터 사이클 몇 개가 사용자에게 보이는 "1회" 인가 (사용자 결정 2026-09-24).
 *
 * 카운터(`RepCounter`)는 신호가 한 번 내려갔다 돌아오는 **사이클**을 센다. 런지류는 걸음마다 무릎이 굽어(앞으로 딛는 런지는 두 무릎이
 * 함께, 사이드 런지는 딛는 쪽 무릎이) 무릎 신호(knee_mean·knee_minside)가 걸음마다 한 번 내려가므로 사이클 하나 = 한쪽 한 걸음이다. 사용자는
 * "왼쪽과 오른쪽을 한 번씩 해야 1회" 로 정했다 — 목표 10회는 왼 10 + 오른 10(20걸음)이고, 한쪽만 했을 때는 수가 오르지 않으며,
 * 두 쪽을 다 해야 1회가 오른다. 목표 도달 자동 진행(spec §42)은 유지하므로 세트는 두 쪽이 모두 목표에 닿은 뒤에야 넘어간다.
 *
 * - [CYCLE]     사이클 하나 = 1회. 런지류를 뺀 모든 종목(바닥 종목 포함).
 * - [SIDE_PAIR] 사이클 둘(한쪽 + 반대쪽) = 1회. 런지·바벨 런지·사이드 런지·크로스 런지(`ExerciseProfile.repUnit`).
 *
 * **쪽을 모른다.** 무릎 신호는 좌우 대칭이라 어느 다리로 디뎠는지 알 수 없다. 짝은 **번갈아 한다는 가정** 위에서 연속한 두 사이클로
 * 짓는다. 한쪽을 몰아서 하면(왼 10 → 오른 10) 두 걸음마다 1회가 올라 총수는 같지만(20걸음 = 10회), 화면의 '반대쪽 차례' 는
 * 그 순서를 모른다 — 그래서 그 표시는 화면에만 두고 말하지 않는다(원칙 #6).
 *
 * 덤벨 컬·스탠딩 니업은 교대 동작이지만 [CYCLE] 이다. 카운트 신호가 두 팔·두 다리의 **평균**(elbow_mean·hip_mean)이라
 * 양쪽을 함께 하는 반복은 한 사이클 = 양쪽 = 1회로 이미 사용자 정의와 같다. 한쪽씩 번갈아 하는 반복은 평균 신호가 절반만 움직여
 * 한쪽이 한 사이클로 잡힌다는 보장부터 없고(MM-Fit 교대 컬 영상 MediaPipe: 지금 카운터 재현율 0.09), 쪽별 귀속도 없다 —
 * 지키지 못하는 짝 규칙을 약속하지 않는다(원칙 #1).
 *
 * @property cyclesPerRep 표시 1회를 이루는 카운터 사이클 수.
 * @property key 세트 로그 `reps.unit` 값.
 */
enum class RepUnit(val cyclesPerRep: Int, val key: String) {
    CYCLE(1, "cycle"),
    SIDE_PAIR(2, "side_pair"),
    /**
     * 런지(§63, 사용자 결정 2026-09-26): 왼발 앞·오른발 앞을 **따로** 센다(`SideStepCounter`) — 1회 = 한쪽씩 한 번, 목표는 쪽마다.
     * 걸음 쪽을 모르는 경로(검사기 없음)에서는 [SIDE_PAIR] 와 같이 두 걸음 = 1회다(이 누적기는 그 대체 경로와 로그의 사이클 기록을 맡는다).
     */
    SIDE_EACH(2, "side_each");

    companion object {
        /**
         * 세션(세트)의 표시 단위 — `PostureLive` 가 세트마다 카운터를 만들 때 이것으로 정한다. 바닥 경로는 늘 [CYCLE](짝 규칙을 안내하지
         * 않고 2D 피처·자체 ROM 정책을 쓴다), 서서 하는 종목은 프로필의 단위, 프로필이 없는 종목은 [CYCLE].
         */
        fun forSession(profile: ExerciseProfile?, floor: Boolean): RepUnit = if (floor) CYCLE else profile?.repUnit ?: CYCLE
    }
}

/** 좌우 짝 단위의 화면 표기 — 실시간 HUD 와 세트 리포트가 같은 말을 쓴다. 전부 화면 전용이다(음성으로 말하지 않는다, 원칙 #6). */
const val SIDE_PAIR_UNIT_HINT = "좌우 한 번씩 = 1회"
/** 한쪽을 마치고 반대쪽을 기다리는 동안의 화면 표기. 쪽을 모르므로(번갈아 한다는 가정) 말하지 않는다. */
const val SIDE_PAIR_NEXT_HINT = "반대쪽 차례"
/** 세트 끝에 짝을 못 채운 한쪽이 남았을 때 리포트 렙 줄에 붙는 말 — 그 한 걸음은 세지 않았다. */
const val SIDE_PAIR_HALF_UNCOUNTED = "반대쪽 없이 끝난 한쪽은 세지 않음"

/**
 * 카운터 사이클의 순서열을 [unit] 단위의 **표시 횟수**로 바꾸는 순수 누적기. 세트마다 새로 만들거나 [reset] 한다.
 *
 * 입력은 카운터가 발표한 사이클마다 한 번씩([offer]) — 시각과 사이클의 ROM 판정(`RepSignal.isValidRep`: true 범위 충족 /
 * false 기준 미달 / null 판정 안 함). [CYCLE] 은 항등(사이클 하나가 곧 1회)이다.
 *
 * 짝의 ROM 판정([combine]): 어느 한쪽이라도 미달(false)이면 그 1회는 미달, 아니고 어느 한쪽이라도 판정하지 않았으면(null) 그 1회도
 * 판정하지 않음, 둘 다 충족일 때만 충족. 판정하지 않은 쪽을 충족으로 올려 세지 않는다(원칙 #1).
 *
 * 1회의 완료 시각 = 짝을 마친(두 번째) 사이클의 시각. 템포는 이 시각들의 간격이다 — 표시 단위와 같은 단위.
 *
 * 스레드: 스스로 동기화하지 않는다. 앱은 렙 기록과 같은 락 안에서 부르고 읽는다.
 */
class RepUnitAccumulator(val unit: RepUnit) {

    /** 표시 단위 1회 — [tMs] 완료 시각(짝을 마친 사이클의 시각), [valid] 짝의 ROM 판정([combine]). */
    data class UnitRep(val tMs: Long, val valid: Boolean?)

    private val done = ArrayList<UnitRep>()
    /** 아직 1회를 채우지 못한 사이클들의 ROM 판정 (SIDE_PAIR 에서 0 또는 1개). */
    private val half = ArrayList<Boolean?>()
    private var halfAt: Long? = null

    /** 완료한 표시 횟수. */
    val completed: Int get() = done.size

    /** 완료한 회 중 ROM 기준 미달(valid == false)로 판정된 수. */
    val invalid: Int get() = done.count { it.valid == false }

    /** 첫 쪽을 마치고 반대쪽을 기다리는 중인가 — 화면의 '반대쪽 차례'. [CYCLE] 에서는 늘 false. */
    val pendingHalf: Boolean get() = half.isNotEmpty()

    /** 기다리는 반쪽 사이클의 시각(없으면 null). */
    val pendingHalfAtMs: Long? get() = halfAt

    /** 완료한 회의 시각, 순서대로. */
    val repTimesMs: List<Long> get() = done.map { it.tMs }

    /** 완료한 회, 순서대로 (복사본). */
    val reps: List<UnitRep> get() = done.toList()

    /**
     * 카운터가 발표한 사이클 하나를 넣는다.
     * @return 이 사이클로 1회가 완료됐으면 그 회, 아니면(반쪽을 기다림) null.
     */
    fun offer(tMs: Long, valid: Boolean?): UnitRep? {
        if (half.isEmpty()) halfAt = tMs
        half += valid
        if (half.size < unit.cyclesPerRep) return null
        val rep = UnitRep(tMs, combine(half))
        half.clear()
        halfAt = null
        done += rep
        return rep
    }

    /**
     * 카운터의 진행 사이클 리셋(일시정지·카메라 전환 — `RepCounter.resetCycle`) 때 부른다. **기다리는 반쪽을 버리지 않는다.**
     * 반쪽은 카운터가 이미 발표한(완료한) 실제 한 걸음이다 — 사용자는 한쪽을 마치고 멈췄다가 반대쪽을 이어 할 수 있다.
     * 카운터가 리셋 때 버리는 것은 아직 끝나지 않은 사이클과 새 코어의 확정 대기 후보(잡음일 수 있는 것)이고, 이미 센 걸음은 아니다.
     * 그래서 이 함수는 아무것도 바꾸지 않는다 — 그 결정을 호출 자리와 테스트에 남기기 위해 있다.
     */
    fun onCounterCycleReset() {}

    /** 새 세트 — 완료한 회와 기다리는 반쪽을 모두 비운다. */
    fun reset() {
        done.clear()
        half.clear()
        halfAt = null
    }

    companion object {
        /** 여러 사이클(짝)의 ROM 판정을 한 회의 판정으로: 미달이 하나라도 있으면 false, 아니고 미판정이 있으면 null, 아니면 true. */
        fun combine(valid: List<Boolean?>): Boolean? = when {
            valid.any { it == false } -> false
            valid.any { it == null } -> null
            else -> true
        }

        /**
         * 카운터가 완료를 알린 프레임([RepCounter.onFrame] 이 true)의 처리 — `PostureLive` 분석 루프가 이것 하나를 부른다
         * (사용자 결정 2026-09-24 의 "두 쪽을 다 해야 1회" 가 화면 수·진행·자동 넘김으로 가는 자리라, 화면 코드 밖의 순수 함수로 두고 테스트한다).
         *
         * 이 프레임에 발표된 사이클마다 ROM 을 판정해([RepSignal.isValidRep]) [records] 에 **사이클 단위 그대로** 더하고(세트 로그·재생 파리티),
         * [acc] 에 넣어 **표시 단위의 완료 회**를 센다. 새 코어는 첫 두 사이클을 한 프레임에 함께 발표하고([RepCounter.newlyPublished] 2개),
         * 레거시 경로는 그 목록이 비어 이 프레임의 한 사이클([RepCounter.lastCycleMin]/[RepCounter.lastCycleMax], 시각 = [frameMs])이다.
         * [acc] 가 null 이면(카운터와 함께 만들지 않은 조합 — 방어) 사이클 하나 = 1회, 템포는 카운터의 발표 시각으로.
         *
         * 스레드: 스스로 동기화하지 않는다. 호출자는 [records]·[acc] 를 지키는 락(세트 마감의 복사와 같은 락) 안에서 부른다.
         */
        fun onCounterFrame(counter: RepCounter, frameMs: Long, records: MutableList<RepRecord>, acc: RepUnitAccumulator?): RepFrameTally {
            val cycles = counter.newlyPublished.map { RepRecord(it.tMs, it.min, it.max, null, it.startMs) }
                .ifEmpty { listOf(RepRecord(frameMs, counter.lastCycleMin, counter.lastCycleMax, null)) }
            val added = ArrayList<RepRecord>(cycles.size)
            var notShort = 0
            var short = 0
            for ((k, c) in cycles.withIndex()) {
                val (tMs, cycleMin, cycleMax) = c
                // 팔별 경로(spec §62c)는 본인 기준 비율 ROM 을 카운터가 회마다 판정한다 — 절대 임계 isValidRep 는 그 경로에 없다
                val valid = if (counter.paired) counter.newlyPublishedValid.getOrNull(k) else counter.signal.isValidRep(cycleMin, cycleMax)
                val record = c.copy(valid = valid)
                records += record
                added += record
                val rep = if (acc != null) acc.offer(tMs, valid) else UnitRep(tMs, valid)
                if (rep != null) { if (rep.valid == false) short++ else notShort++ }
            }
            return RepFrameTally(added, notShort, short, acc?.pendingHalf == true,
                RepMetrics.medianPeriodMs(acc?.repTimesMs ?: counter.repTimesMs))
        }
    }
}

/**
 * 카운터 완료 프레임 하나의 결과([RepUnitAccumulator.onCounterFrame]).
 * @property records 이 프레임에 발표된 카운터 사이클의 기록, 발표 순서대로 — 사이클 단위(런지 = 한 걸음). 로그·바닥 피드백용.
 * @property repsNotShort 이 프레임에 **완료된 표시 1회** 중 ROM 미달로 판정되지 않은 수(판정하지 않은 회 포함 — '유효' 가 아니다).
 * @property repsShort 이 프레임에 완료된 표시 1회 중 ROM 기준 미달로 판정된 수.
 * @property halfPending 첫 쪽을 마치고 반대쪽을 기다리는 중 — 화면의 '반대쪽 차례'(말하지 않는다, 원칙 #6).
 * @property tempoMs 표시 1회 완료 간격의 중앙값(좌우 짝이면 두 걸음). 2회 미만이면 null.
 */
data class RepFrameTally(
    val records: List<RepRecord>,
    val repsNotShort: Int,
    val repsShort: Int,
    val halfPending: Boolean,
    val tempoMs: Long?,
) {
    /** 이 프레임에 완료된 표시 횟수 — 진행·자동 넘김(spec §42)·숫자 발화에 더할 수. 좌우 짝의 첫 쪽만 끝난 프레임은 0 이다. */
    val completedReps: Int get() = repsNotShort + repsShort
}
