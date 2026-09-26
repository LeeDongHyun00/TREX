package com.example.trex_kotlin.posture

/** 걸음의 앞다리 쪽(사용자 해부학 기준 — 분석 프레임은 미러가 아니다). 모름은 null. */
enum class StepSide(val key: String, val label: String) {
    LEFT("L", "왼쪽"), RIGHT("R", "오른쪽");
    val other: StepSide get() = if (this == LEFT) RIGHT else LEFT
}

/**
 * 런지 쪽별 카운트 (spec §63, 사용자 결정 2026-09-26: "왼쪽 오른쪽 따로 카운트한 다음 한쪽 카운트가 다 사라지면 안내해서 다른 쪽을 하게 유도").
 * 안드로이드 의존 없음 — 앱(PostureLive)과 재생기(Replay)가 같은 코드를 쓴다.
 *
 * - 쪽마다 목표([target], 세트의 목표 회수)까지 따로 센다. 화면은 쪽마다 **남은 수**를 보인다(카운트다운).
 * - 한쪽이 목표를 채우면 그 순간 반대쪽으로 안내한다([SideStepEvent.switchTo]) — 세트에서 쪽마다 한 번.
 * - 목표를 채운 쪽으로 더 디딘 걸음은 세지 않는다([SideStepEvent.extraOnDone], [SideStepEvent.switchTo] 는 null — 다시 안내할지는 화면이 간격으로 정한다).
 *   자세 차단보다 먼저 본다 — 어차피 세지 않을 걸음에 "더 깊이" 를 말하면 끝난 다리를 고치라는 말이 된다.
 * - 화면 큰 숫자·목표 진행(쌍) = min(왼, 오른) — 두 쪽이 모두 목표에 닿아야 세트가 끝난다. 차감이 아니다: 걸음마다 +0 또는 +1.
 * - 쪽을 모르는 걸음(정면·반증·이름 점검 실패)은 **걸음 순서의 흐름**에 채운다 — 걸음을 버리지 않되, 화면에 '좌우 미확인' 으로 밝힌다.
 *   최근 확신 두 걸음이 같은 쪽이면 한쪽씩 몰아서 하는 중(안내한 방식) → 직전에 채운 쪽, 번갈았으면 → 직전의 반대쪽, 확신 걸음이 없으면 적은 쪽.
 *   '적은 쪽' 하나로 채우면 몰아서 하는 동안 모르는 걸음이 전부 아직 안 한 쪽으로 가 반대쪽 안내가 한 걸음 일찍 나왔다(검토 2026-09-26).
 *   채울 쪽이 이미 목표면 반대쪽. 쪽 판정 범위: MM-Fit 97 %·REHAB 98.3 % 걸음이 확신, 정확도 96.8 %(REHAB 확신 걸음).
 * - 풀이 둘이다: TRACK = 판별을 통과한 모든 걸음, COACH = ship 차단 위반이 없는 걸음(§62b). 두 풀을 늘 함께 세 두어 세트 중 모드를 바꿔도 어긋나지 않는다.
 *   판정·임계는 두 모드에서 같고(원칙 #3·#4) 어느 풀을 보일지만 모드가 정한다.
 * [target] 이 null(목표 없음)이면 상한·안내 없이 센다.
 */
class SideStepCounter(val target: Int?) {

    class Pool {
        var left = 0; internal set
        var right = 0; internal set
        /** 쪽을 몰라 채운 걸음 수(어느 쪽에 채웠든). */
        var unknown = 0; internal set
        /** 목표를 채운 쪽으로 더 디뎌 세지 않은 걸음 수. */
        var extra = 0; internal set
        /** 이 풀에서 뺀(차단) 걸음 수 — COACH 풀만 늘어난다. */
        var blocked = 0; internal set
        internal var lastSide: StepSide? = null
        internal val switched = HashSet<StepSide>()
        val pairs: Int get() = minOf(left, right)
        fun count(side: StepSide) = if (side == StepSide.LEFT) left else right
        fun remaining(side: StepSide, target: Int?): Int? = target?.let { (it - count(side)).coerceAtLeast(0) }
        fun tally(): SideTally = SideTally(left, right, unknown, extra, blocked)
    }

    /** 화면·로그·리포트용 사본(두 풀). */
    fun tallies(): SideTallies = SideTallies(target, track.tally(), coach.tally())

    val track = Pool()
    val coach = Pool()

    fun pool(coachMode: Boolean): Pool = if (coachMode) coach else track

    /** 두 쪽이 모두 목표에 닿았다. */
    fun done(coachMode: Boolean): Boolean = target != null && pool(coachMode).let { it.left >= target && it.right >= target }

    /**
     * 걸음 하나. [side] null = 쪽 모름. [blocked] = ship 차단 위반(COACH 풀에서만 뺀다).
     * @return 두 풀의 사건 — [SideStep.track]/[SideStep.coach]. 화면·음성은 지금 모드의 사건을 쓴다.
     */
    fun offer(tMs: Long, side: StepSide?, blocked: Boolean): SideStep {
        val guess = side ?: guessSide()
        val step = SideStep(apply(track, tMs, side, guess, blocked = false), apply(coach, tMs, side, guess, blocked))
        if (side != null) { knownPrev = knownLast; knownLast = side }
        lastStep = guess
        return step
    }

    /** 최근 확신 두 걸음(걸음 순서 — 풀과 무관). */
    private var knownLast: StepSide? = null
    private var knownPrev: StepSide? = null
    /** 직전 걸음의 쪽(모르는 걸음이면 채운 쪽). */
    private var lastStep: StepSide? = null

    /** 쪽 모르는 걸음의 흐름 추정 — null 이면 풀마다 적은 쪽. */
    private fun guessSide(): StepSide? {
        val last = lastStep ?: return null
        val k1 = knownLast ?: return null
        val alternating = knownPrev != null && knownPrev != k1
        return if (alternating) last.other else last
    }

    private fun apply(p: Pool, tMs: Long, side: StepSide?, guess: StepSide?, blocked: Boolean): SideStepEvent {
        val flow = side ?: guess ?: when {
            p.left < p.right -> StepSide.LEFT
            p.right < p.left -> StepSide.RIGHT
            else -> p.lastSide?.other ?: StepSide.LEFT
        }
        // 모르는 걸음을 이미 목표인 쪽으로 채우지 않는다
        val assigned = if (side == null && target != null && p.count(flow) >= target && p.count(flow.other) < target) flow.other else flow
        val before = p.pairs
        // 목표를 채운 쪽 — 자세와 무관하게 세지 않는다(차단보다 먼저)
        if (target != null && p.count(assigned) >= target) {
            p.extra++
            val other = assigned.other.takeIf { p.count(it) < target }
            return SideStepEvent(tMs, assigned, side != null, counted = false, blocked = false, extraOnDone = other != null,
                remaining = 0, switchTo = null, pairs = p.pairs, pairAdded = false)
        }
        if (blocked) {
            p.blocked++
            return SideStepEvent(tMs, assigned, side != null, counted = false, blocked = true, extraOnDone = false,
                remaining = p.remaining(assigned, target), switchTo = null, pairs = p.pairs, pairAdded = false)
        }
        if (side == null) p.unknown++
        if (assigned == StepSide.LEFT) p.left++ else p.right++
        p.lastSide = assigned
        val rem = p.remaining(assigned, target)
        // 이 걸음으로 한쪽이 목표를 채웠고 반대쪽이 남았다 → 반대쪽 안내(쪽마다 한 번)
        val switchTo = if (target != null && rem == 0 && p.count(assigned.other) < target && p.switched.add(assigned)) assigned.other else null
        return SideStepEvent(tMs, assigned, side != null, counted = true, blocked = false, extraOnDone = false,
            remaining = rem, switchTo = switchTo, pairs = p.pairs, pairAdded = p.pairs > before)
    }
}

/** 한 걸음이 두 풀에 준 결과. */
data class SideStep(val track: SideStepEvent, val coach: SideStepEvent) {
    fun of(coachMode: Boolean) = if (coachMode) coach else track
}

/**
 * 걸음 하나의 결과(한 풀).
 * @property side 센(또는 채운) 쪽, @property known 쪽을 판정했는가(false = 걸음 흐름으로 채움 — 이 걸음에서 나온 안내는 쪽을 말하지 않는다),
 * @property counted 셌는가, @property blocked 자세 차단으로 뺐는가(COACH), @property extraOnDone 목표를 채운 쪽으로 더 디뎠고 반대쪽이 남았다(세지 않음),
 * @property remaining 그 쪽의 남은 수(목표 없으면 null), @property switchTo 이 걸음으로 한쪽이 목표를 채워 반대쪽으로 안내할 쪽(쪽마다 한 번, 없으면 null), @property pairs 이 풀의 쌍,
 * @property pairAdded 이 걸음으로 쌍이 늘었다.
 */
data class SideStepEvent(
    val tMs: Long,
    val side: StepSide,
    val known: Boolean,
    val counted: Boolean,
    val blocked: Boolean,
    val extraOnDone: Boolean,
    val remaining: Int?,
    val switchTo: StepSide?,
    val pairs: Int,
    val pairAdded: Boolean,
)

/** 한 풀의 쪽별 집계 사본. [pairs] = min(왼, 오른) — 목표 진행. */
data class SideTally(val left: Int, val right: Int, val unknown: Int, val extra: Int, val blocked: Int) {
    val pairs: Int get() = minOf(left, right)
    val steps: Int get() = left + right
}

/** 세트의 쪽별 집계(두 풀) — HUD·세트 로그 `reps.sides`·완료 화면이 같은 값을 쓴다. */
data class SideTallies(val target: Int?, val track: SideTally, val coach: SideTally) {
    fun of(coachMode: Boolean): SideTally = if (coachMode) coach else track

    /** HUD 큰 글자 — 목표가 있으면 쪽마다 남은 수(카운트다운, 0 이면 ✓), 없으면 센 수. */
    fun headline(coachMode: Boolean): String {
        val t = of(coachMode)
        fun part(label: String, n: Int) = if (target == null) "$label $n" else (target - n).coerceAtLeast(0).let { r -> if (r == 0) "$label ✓" else "$label $r" }
        return "${part("왼", t.left)} · ${part("오", t.right)}"
    }

    /** 다음에 할 쪽 — 한쪽만 목표를 채웠을 때. */
    fun next(coachMode: Boolean): StepSide? {
        val t = of(coachMode); val tg = target ?: return null
        return when { t.left >= tg && t.right < tg -> StepSide.RIGHT; t.right >= tg && t.left < tg -> StepSide.LEFT; else -> null }
    }

    /** 완료 화면 렙 줄 꼬리 — "왼 10 · 오 8걸음" + 좌우 미확인·자세로 뺀 걸음. */
    fun reportLine(coachMode: Boolean): String {
        val t = of(coachMode)
        return listOfNotNull("왼 ${t.left} · 오 ${t.right}걸음",
            "좌우 미확인 ${t.unknown}걸음".takeIf { t.unknown > 0 },
            "자세로 뺀 걸음 ${t.blocked}".takeIf { coachMode && t.blocked > 0 }).joinToString(" · ")
    }
}
