package com.example.trex_kotlin.posture

/** 유지 시계의 한 구간 — [state] = `hold`·`wait`(처음 버티기 전)·멈춤 사유. 시각은 호출자가 준 ms 그대로. */
data class HoldSegment(val t0: Long, val t1: Long, val state: String)

/** 처음 멈춤 — 버티던 시간이 멈춘 시각(마지막으로 조건을 만족한 프레임)과 사유. */
data class HoldStop(val tMs: Long, val reason: String)

/** 시계가 낸 사건 — 음성·틱의 원천. [reason] 은 STOP·REMIND 에만. */
data class PlankHoldEvent(val kind: Kind, val tMs: Long, val reason: String? = null) {
    enum class Kind {
        /** 세트 처음으로 플랭크를 확인 — "시간을 재기 시작해요" + 틱(세트에 한 번). */
        START,
        /** 멈춘 뒤 다시 확인 — 짧은 톤만(재잘거림 방지). */
        RESUME,
        /** 1 s 이어진 멈춤 확정. */
        STOP,
        /** 일어섬·앉음(`not_prone`)이 5 s 이어짐 — 한 번 "플랭크 자세가 보이면 이어서 재요". */
        REMIND,
        /** WORK 단계 20 s 동안 한 번도 확인 못 했고 그동안 몸이 대부분 화면 밖이라 시계로 넘김 — 한 번 말하고 리포트에 '카메라 미확인'. */
        FALLBACK,
        /** 처음 버티기 전(WAIT) 한 사유가 5 s 이어짐 — 사유마다 세트에 한 번 "무릎을 펴면 시간을 재기 시작해요" 등([reason]). */
        HINT,
    }
}

/** 화면·브리지·로그가 읽는 시계 상태의 사본(분석 스레드와 화면 스레드 사이). */
data class PlankHoldSnapshot(
    val phase: PlankHoldClock.Phase, val heldMs: Long, val wallMs: Long, val stopMs: Map<String, Long>, val firstStop: HoldStop?,
    val segments: List<HoldSegment>, val source: String, val reason: String?,
    /** 처음 HOLD 를 확정한 구간의 시작(소급, [PlankHoldClock.firstHoldAt]). 없으면 null. */
    val firstHoldAt: Long? = null,
    /** 시계 시작(WORK 시작, [PlankHoldClock.start] — 안 불렀으면 첫 프레임)과 마지막으로 본 판정 칸. 재생기가 앱과 같은 판정 칸을 다시 만든다(로그 `hold.start_t_ms`·`end_t_ms`). */
    val startAt: Long? = null, val endAt: Long? = null,
    /** 처음 버티기 전(WAIT)의 사유별 시간 — 멈춤([stopMs])이 아니다(멈출 것이 없었다). 무릎 플랭크로 세트를 보냈으면 여기 '무릎' 이 남는다. */
    val waitMs: Map<String, Long> = emptyMap(),
)

/**
 * 플랭크 유지 시계(spec §99, `docs/FLOOR_FAMILY_DESIGN.md` §4.3·§4.7) — 벽시계가 아니라 **카메라가 확인한 플랭크 시간**을 잰다(사용자 결정 Q2, 2026-10-06 밤).
 * 지금까지의 진행은 벽시계라 몸이 화면 밖이어도, 무릎을 꿇어도 줄었다.
 *
 * 판정 프레임(300 ms)마다 상태 하나 — 아래 멈춤 사유가 하나도 없으면 HOLD 후보, 있으면 첫 사유(순서 = 우선순위, 첫 사유만 말한다):
 *  1. `out_of_view` 보이는 쪽 어깨·골반·무릎·발목 중 하나라도 안 보임(`fc_chain` ≠ 1) 또는 [MAX_GAP_MS] 넘는 공백 — 관측 못 한 시간은 세지 않는다(원칙 #1).
 *  2. `not_prone` 몸 축(어깨→발목)의 중력 수평 대비 각 > [NOT_PRONE_DEG] — 일어섬·앉음. 바로 말하지 않고 5 s 뒤 한 번. 축각이 없으면(up 미확인) 화면 수평도 < [FLAT_SCREEN_MIN].
 *  3. `knees_down` 무릎 < [KNEE_MIN_DEG] — 무릎을 대면 골반 오프셋이 양쪽으로 오독되므로(AIHub 진입·이탈 +0.18 vs 폰 −0.12~−0.18) 골반보다 먼저 묻는다. 무릎 플랭크는 멈춤(Q3 기본).
 *  4. `hips_low` 팔꿈치→발목 지지선 대비 골반 높이 < [HIP_FLOOR_MIN] 몸통(무릎이 관측될 때만) — 엎드려 쉼. 폰 골반 내림 0.086~0.107, 버팀 0.38~0.53, AIHub 정상 프레임 0.15 미만 2.3 %.
 *     골반으로 멈춘 뒤의 재개는 문턱 + [HIP_FLOOR_HYSTERESIS] 위에서만(이력).
 *  5. `pike` 골반 오프셋 > [PIKE_MIN], 측면(`fc_yaw` ≤ [SIDE_YAW_MAX])일 때만 — 정상 0 %·위반 35 %(2연속 클립). TRACK 도 같다 — 교정이 아니라 재개 조건.
 * 지지 방식(전완 / 팔 편)은 게이트가 아니다 — 팔 편 플랭크를 시간에서 빼는 것은 사용자 결정 없는 스타일 삭제(Q3). 발목만 빠졌을 때 이어 세는 축소 관측은 하지 않는다(Q5).
 *
 * 적립(단조 증가 — `SessionProgress.tick` 이 음수 증분을 0으로 자르는 구조와 맞다): 확인 전(WAIT)·멈춤(STOP)에서 HOLD 조건이 [HOLD_CONFIRM_MS] 이어지면 확정하고
 * 그 구간을 **소급** 적립한다(카운트다운 끝이 아니라 실제 진입부터). HOLD 안에서는 만족하는 칸을 바로 적립(칸 간격은 [MAX_DT_MS] 로 자름). 조건이 깨진 칸은 보류했다가
 * 최근 [STOP_CONFIRM_MS] 의 위반 비율이 [STOP_MAJORITY] 아래로 내려오고 지금 칸이 정상이면 회복해 보류분을 적립하고, 비율이 그 이상인 채 [STOP_CONFIRM_MS] 가 지나면
 * STOP 확정과 함께 버린다(그 시간은 사유별 멈춤 시간 — 끊김 안의 정상 칸도 확정 사유로). 한 프레임의 회복은 확정을 리셋하지 않는다(2026-10-08). 시계가 거꾸로 가지 않는다.
 *
 * 폴백: [start] 뒤 [FALLBACK_MS] 가 지나도록 HOLD 가 한 번도 없고, **그동안 몸이 대부분 화면 밖이었으면**(처음 버티기 전 시간의 절반 넘게 `out_of_view`) [source] = `clock`
 * (한 번 [PlankHoldEvent.Kind.FALLBACK]) — 09-12 같은 촬영 실패가 기록 0 이 되지 않게. 몸을 보면서 무릎·골반·솟음·서 있음으로 기다린 것은 촬영 실패가 아니다 —
 * 폴백하면 무릎 플랭크가 시계로 플랭크 시간이 된다(Q3·원칙 #1 우회, 리뷰 2026-10-07). 그때는 폴백하지 않고 그 사유를 [WAIT_HINT_MS] 뒤 한 번 말한다
 * ([PlankHoldEvent.Kind.HINT]). HOLD 후보를 확인하는 중(1 s)에는 폴백하지 않는다(확정이나 깨짐까지 기다린다). 그 뒤에도 카메라 시간은 로그용으로 계속 잰다.
 * `PostureRule` 에 의존하지 않는다(재생기 컴파일).
 *
 * 일시정지([pause]~[resume])는 시계 밖이다 — 적립·멈춤·경과·폴백 20 s 어디에도 넣지 않고 구간에는 `pause` 로 남긴다(앱 배선 2026-10-07: 재개 뒤 첫 프레임의
 * 공백이 [MAX_GAP_MS] 를 넘어 `out_of_view` 멈춤과 그 음성이 나가던 것을 막는다).
 */
class PlankHoldClock : IdentityCueSource {
    enum class Phase { WAIT, HOLD, STOP }

    var phase = Phase.WAIT
        private set
    /** 카메라가 확인한 플랭크 시간(ms) — 단조 증가. */
    @Volatile var heldMs = 0L
        private set
    /** [start](또는 첫 프레임)부터 마지막 프레임까지의 벽시계(ms). */
    var wallMs = 0L
        private set
    /** `camera` | `clock`(폴백). */
    @Volatile var source = SOURCE_CAMERA
        private set
    /** 처음 HOLD 를 확정한 구간의 시작(소급). 없으면 null. */
    var firstHoldAt: Long? = null
        private set
    var firstStop: HoldStop? = null
        private set
    /** 지금의 멈춤·대기 사유(HUD '멈춤 · 무릎이 바닥에 닿음'). HOLD 면 null. */
    var reason: String? = null
        private set
    private val stops = LinkedHashMap<String, Long>()
    /** 사유별 멈춤 시간(ms) — 처음 버티기 전(WAIT)은 넣지 않는다(멈출 것이 없었다 — 그 시간은 [waitMs]). */
    val stopMs: Map<String, Long> get() = stops
    private val waits = LinkedHashMap<String, Long>()
    /** 처음 버티기 전(WAIT)의 사유별 시간(ms). */
    val waitMs: Map<String, Long> get() = waits
    private var waitTotal = 0L
    private var waitReason: String? = null
    private var waitReasonSince = 0L
    private val hinted = HashSet<String>()

    private val closed = ArrayList<HoldSegment>()
    private var segStart: Long? = null
    private var segLabel = WAIT_LABEL
    /** 지난 구간 + 열린 구간(마지막 프레임까지). */
    val segments: List<HoldSegment> get() = closed + listOfNotNull(segStart?.let { s -> lastT?.takeIf { it > s }?.let { HoldSegment(s, it, segLabel) } })

    /** 시계 시작(WORK 시작 — [start], 안 불렀으면 첫 프레임). */
    var startAt: Long? = null
        private set
    private var lastT: Long? = null
    // HOLD 안의 끊김(보류) — 끊김이 시작된 뒤의 판정 칸 전부(위반·정상 섞임). 최근 [STOP_CONFIRM_MS] 의 위반 비율로 멈춤·회복을 정한다
    private class PendingFrame(val t: Long, val dt: Long, val credit: Long, val reason: String?)
    private var breakFrom: Long? = null
    private val pendingFrames = ArrayList<PendingFrame>()
    // WAIT·STOP 의 HOLD 후보 구간
    private var candSince: Long? = null
    private var candCredit = 0L
    private var candWall = 0L
    // not_prone 알림
    private var notProneSince: Long? = null
    private var reminded = false
    // 일시정지
    private var pausedAt: Long? = null
    private var pausedTotal = 0L
    private var labelBeforePause = WAIT_LABEL

    override fun cueFor(reason: String): String? = Companion.cueFor(reason)

    /** WORK 단계 시작 — 폴백 20 s 의 기준. 부르지 않으면 첫 프레임 시각. */
    @Synchronized fun start(tMs: Long) { if (startAt == null) startAt = tMs }

    @Synchronized fun reset() {
        phase = Phase.WAIT; heldMs = 0L; wallMs = 0L; source = SOURCE_CAMERA; firstHoldAt = null; firstStop = null; reason = null
        stops.clear(); closed.clear(); segStart = null; segLabel = WAIT_LABEL; startAt = null; lastT = null
        clearBreak(); candSince = null; candCredit = 0L; candWall = 0L
        notProneSince = null; reminded = false; pausedAt = null; pausedTotal = 0L; labelBeforePause = WAIT_LABEL
        waits.clear(); waitTotal = 0L; waitReason = null; waitReasonSince = 0L; hinted.clear()
    }

    /** 일시정지 시작 — [resume] 까지는 시계 밖이다. 첫 프레임 전이면 기록만 한다. 이미 멈춰 있으면 무시. */
    @Synchronized fun pause(tMs: Long) {
        if (pausedAt != null) return
        pausedAt = tMs
        if (segStart == null) return
        labelBeforePause = segLabel
        relabel(maxOf(tMs, segStart!!), PAUSE_LABEL)
    }

    /**
     * 일시정지 끝 — 멈춘 동안을 건너뛴다(다음 프레임의 공백이 화면 밖으로 세지 않는다). 확인 중이던 HOLD 후보와 HOLD 안의 보류한 끊김은 버리고
     * (재개 뒤 다시 확인 — 보류분은 적립하지 않는다, 보수적), 상태(HOLD/STOP/WAIT)는 이어 간다.
     */
    @Synchronized fun resume(tMs: Long) {
        val p = pausedAt ?: return
        pausedAt = null
        if (startAt == null) return
        pausedTotal += (tMs - p).coerceAtLeast(0L)
        // 확인 중이던 HOLD 후보 시간은 후보가 깨진 것과 같이 멈춘 시간으로(지금 구간 이름은 pause 라 멈추기 전 이름으로 센다)
        if (candSince != null) { account(labelBeforePause, candWall); candSince = null; candCredit = 0L; candWall = 0L }
        clearBreak(); notProneSince = null; waitReason = null
        if (segStart != null) relabel(maxOf(tMs, segStart!!), labelBeforePause)
        lastT?.let { lastT = maxOf(it, tMs) }
    }

    /** 일시정지 중인가. */
    val paused: Boolean @Synchronized get() = pausedAt != null

    @Synchronized fun snapshot(): PlankHoldSnapshot =
        PlankHoldSnapshot(phase, heldMs, wallMs, LinkedHashMap(stops), firstStop, segments, source, reason, firstHoldAt, startAt, lastT, LinkedHashMap(waits))

    /**
     * 판정 프레임 하나. [features] = 그 프레임의 `FloorChain` 피처(플랭크), 사람이 안 잡혔으면 null(= `out_of_view`). 사람이 없어도 판정 칸마다 부른다 —
     * 부르지 않은 공백이 [MAX_GAP_MS] 를 넘으면 그 시간은 화면 밖으로 센다. @return 이 프레임의 사건(대개 비어 있다).
     */
    @Synchronized fun onFrame(tMs: Long, features: Map<String, Float>?): List<PlankHoldEvent> {
        val events = ArrayList<PlankHoldEvent>(1)
        if (pausedAt != null) return events
        val prev = lastT
        if (prev != null && tMs <= prev) return events
        if (startAt == null) startAt = tMs
        if (segStart == null) segStart = tMs
        // 골반 내림 이력(2026-10-08): 골반으로 멈춘 뒤에는 문턱 + [HIP_FLOOR_HYSTERESIS] 위로 올라와야 재개 후보다 — 문턱 안팎의 흔들림이 재개·멈춤을 반복하지 않게
        val hipMin = if (phase == Phase.STOP && segLabel == HIPS_LOW) HIP_FLOOR_MIN + HIP_FLOOR_HYSTERESIS else HIP_FLOOR_MIN
        val r = if (features == null) OUT_OF_VIEW else stopReason(features, hipMin)
        when {
            prev == null -> step(tMs, r, 0L, events)
            tMs - prev > MAX_GAP_MS -> { step(tMs, OUT_OF_VIEW, tMs - prev, events); step(tMs, r, 0L, events) }
            else -> step(tMs, r, tMs - prev, events)
        }
        lastT = tMs
        wallMs = tMs - startAt!! - pausedTotal
        reason = if (phase == Phase.HOLD) null else (if (candSince != null) null else r)
        // not_prone 은 바로 말하지 않는다 — STOP 에서 5 s 이어지면 한 번
        if (phase == Phase.STOP && r == NOT_PRONE) {
            val s = notProneSince ?: tMs.also { notProneSince = it }
            if (!reminded && tMs - s >= NOT_PRONE_REMIND_MS) { reminded = true; events += PlankHoldEvent(PlankHoldEvent.Kind.REMIND, tMs, NOT_PRONE) }
        } else notProneSince = null
        // 처음 버티기 전의 사유 — 5 s 이어지면 사유마다 세트에 한 번 무엇이 있어야 재는지 말한다(전에는 WAIT 의 멈춤 사유가 사건을 내지 않아 20 s 동안 무음이었다)
        if (phase == Phase.WAIT && candSince == null && r != null) {
            if (r != waitReason) { waitReason = r; waitReasonSince = tMs }
            else if (tMs - waitReasonSince >= WAIT_HINT_MS && hinted.add(r)) events += PlankHoldEvent(PlankHoldEvent.Kind.HINT, tMs, r)
        } else waitReason = null
        // 폴백 = 촬영 실패만 — 처음 버티기 전 시간의 절반 넘게 화면 밖이었을 때. 확인 중인 HOLD 후보가 있으면 확정이나 깨짐까지 기다린다
        if (source == SOURCE_CAMERA && firstHoldAt == null && candSince == null && tMs - startAt!! - pausedTotal >= FALLBACK_MS &&
            2 * (waits[OUT_OF_VIEW] ?: 0L) > waitTotal) {
            source = SOURCE_CLOCK; events += PlankHoldEvent(PlankHoldEvent.Kind.FALLBACK, tMs)
        }
        return events
    }

    /** 칸 하나 — [dt] 는 직전 프레임에서 이 프레임까지(이 프레임의 상태로 센다). 구간 [t − dt, t]. 공백 뒤의 같은 프레임은 dt = 0 으로 한 번 더 부른다. */
    private fun step(t: Long, r: String?, dt: Long, events: MutableList<PlankHoldEvent>) {
        val credit = minOf(dt, MAX_DT_MS)
        when (phase) {
            Phase.HOLD -> {
                if (r == null && breakFrom == null) { heldMs += credit; return }
                if (breakFrom == null) breakFrom = t - dt          // 마지막으로 조건을 만족한 칸의 끝부터 보류
                pendingFrames += PendingFrame(t, dt, credit, r)
                // 최근 1 s 판정 칸의 위반 비율(2026-10-08, 폰 플랭크 10-07: 골반을 바닥에 댄 7.5 s 동안 값이 문턱 안팎을 오가 한 프레임 회복이 확정을 번번이 리셋했다).
                // 비율 ≥ [STOP_MAJORITY] 이고 지금 칸도 위반이면 멈춤 확정, 지금 칸이 정상이고 비율이 그 아래면 회복(보류분 적립). 그 사이는 보류가 이어진다
                val window = pendingFrames.filter { it.t > t - STOP_CONFIRM_MS }
                val share = window.filter { it.reason != null }.sumOf { it.dt }.toFloat() / window.sumOf { it.dt }.coerceAtLeast(1L)
                if (r != null && t - breakFrom!! >= STOP_CONFIRM_MS && share >= STOP_MAJORITY) confirmStop(dominantReason(window) ?: r, events)
                else if (r == null && share < STOP_MAJORITY) { heldMs += pendingFrames.sumOf { it.credit }; clearBreak() }
            }
            Phase.WAIT, Phase.STOP -> if (r == null) {
                waitAccount(null, dt)
                if (candSince == null) { candSince = t; account(null, dt) } else { candCredit += credit; candWall += dt }
                if (t - candSince!! >= HOLD_CONFIRM_MS) confirmHold(events)
            } else {
                waitAccount(r, dt)
                if (candSince != null) { account(null, candWall); candSince = null; candCredit = 0L; candWall = 0L }
                account(r, dt)
                if (phase == Phase.STOP && r != segLabel) relabel(t - dt, r)
            }
        }
    }

    /** 처음 버티기 전(WAIT) 시간 — 전체와 사유별(r = null 은 HOLD 후보를 확인하던 칸이라 사유 없이 전체에만). 폴백 판정(화면 밖 비율)과 리포트의 재료. */
    private fun waitAccount(r: String?, dt: Long) {
        if (phase != Phase.WAIT || dt <= 0L) return
        waitTotal += dt
        if (r != null) waits[r] = (waits[r] ?: 0L) + dt
    }

    /** 멈춤 시간 적립 — STOP 에서만(사유 r, null = 지금 구간의 사유). */
    private fun account(r: String?, dt: Long) {
        if (phase != Phase.STOP || dt <= 0L) return
        val k = r ?: segLabel
        stops[k] = (stops[k] ?: 0L) + dt
    }

    private fun confirmHold(events: MutableList<PlankHoldEvent>) {
        val from = candSince!!
        heldMs += candCredit
        val first = firstHoldAt == null
        if (first) firstHoldAt = from
        relabel(from, HOLD_LABEL)
        phase = Phase.HOLD; candSince = null; candCredit = 0L; candWall = 0L; reminded = false
        events += PlankHoldEvent(if (first) PlankHoldEvent.Kind.START else PlankHoldEvent.Kind.RESUME, from)
    }

    /** 보류 칸 중 위반 시간이 가장 긴 사유 — 멈춤의 이름. 위반 칸이 없으면 null. */
    private fun dominantReason(frames: List<PendingFrame>): String? =
        frames.filter { it.reason != null }.groupBy { it.reason!! }.maxByOrNull { (_, fs) -> fs.sumOf { it.dt } }?.key

    private fun clearBreak() { breakFrom = null; pendingFrames.clear() }

    private fun confirmStop(r: String, events: MutableList<PlankHoldEvent>) {
        val from = breakFrom!!
        // 보류 시간은 전부 멈춤 시간 — 위반 칸은 그 사유로, 끊김 안의 정상 칸(문턱 안팎의 흔들림)은 확정 사유로
        for (f in pendingFrames) { val k = f.reason ?: r; stops[k] = (stops[k] ?: 0L) + f.dt }
        clearBreak()
        phase = Phase.STOP
        relabel(from, r)
        if (firstStop == null) firstStop = HoldStop(from, r)
        events += PlankHoldEvent(PlankHoldEvent.Kind.STOP, from, r)
    }

    private fun relabel(at: Long, label: String) {
        val s = segStart
        if (s != null && at > s) closed += HoldSegment(s, at, segLabel)
        segStart = if (s == null || at > s) at else s
        segLabel = label
    }

    companion object {
        const val ENGINE = "plankhold_v1_beta"
        const val SOURCE_CAMERA = "camera"
        const val SOURCE_CLOCK = "clock"
        const val HOLD_LABEL = "hold"
        const val WAIT_LABEL = "wait"
        /** 일시정지 구간의 이름(구간 목록에만 — 사유가 아니다). */
        const val PAUSE_LABEL = "pause"

        const val OUT_OF_VIEW = "out_of_view"
        const val NOT_PRONE = "not_prone"
        const val KNEES_DOWN = "knees_down"
        const val HIPS_LOW = "hips_low"
        const val PIKE = "pike"
        /** 사유 순서 = 우선순위. */
        val REASONS = listOf(OUT_OF_VIEW, NOT_PRONE, KNEES_DOWN, HIPS_LOW, PIKE)

        const val HOLD_CONFIRM_MS = 1_000L
        const val STOP_CONFIRM_MS = 1_000L       // §34·FLOOR_DEVICE_VALIDATION 의 '2 s' 대신 §39 구현의 1 s 로 통일(설계 §9.3)
        /** HOLD 안의 끊김을 멈춤으로 확정하는 최근 [STOP_CONFIRM_MS] 위반 시간 비율(2026-10-08). 그 아래로 내려오고 지금 칸이 정상이면 회복. */
        const val STOP_MAJORITY = 0.7f
        const val MAX_GAP_MS = 750L
        const val MAX_DT_MS = 750L
        const val FALLBACK_MS = 20_000L
        const val NOT_PRONE_REMIND_MS = 5_000L
        /** 처음 버티기 전 한 사유가 이만큼 이어지면 그 사유를 한 번 말한다(사유마다 세트에 한 번). */
        const val WAIT_HINT_MS = 5_000L
        const val NOT_PRONE_DEG = 30f            // 잠정
        /** up 을 믿을 수 없을 때 엎드림 판정의 폴백 — 어깨→발목 화면 수평도(`fc_flat_screen`)가 이보다 작으면 `not_prone`. 종전 `PlankGeometry` 준비 조건(≥ 0.7, 수평 대비 약 46°). */
        const val FLAT_SCREEN_MIN = 0.7f
        const val KNEE_MIN_DEG = 145f            // AIHub C 사슬 프레임 79~81 % 통과(나머지는 무릎을 댄 진입·이탈)
        /**
         * 골반 지지선 높이(몸통 단위)의 멈춤 문턱. 0.10(2026-10-07 까지, AIHub C 정상 프레임 0.16 %)은 폰에서 **골반을 바닥에 댄 값(0.086~0.107) 한가운데**라
         * 7.5 s 를 유지로 적립했다(`docs/PHONE_REPORT_2026-10-07_DESIGN.md` §3.5). 0.15 로: 그 폰 세트의 골반 내림 전 구간이 위반, 버팀(0.38~0.53)은 여유.
         * AIHub C 정상 프레임(무릎 ≥ 145°, `data/floor_family/designer/near_frames.parquet` 의 엔진 정의 `hip_floor`): p1 0.125 · p2 0.146 · p5 0.188 · 중앙 0.383,
         * 0.15 미만 2.3 %(0.20 이면 6.3 %, 0.25 면 15 %) — 프레임 비율이고 멈춤은 1 s 의 70 % 를 요구한다. 폰 2명 이상의 골반 내림 블록으로 확정.
         */
        const val HIP_FLOOR_MIN = 0.15f
        /** 골반으로 멈춘 뒤 재개하려면 문턱보다 이만큼 더 올라와야 한다(이력). 폰 골반 내림 최대 0.107 과 버팀 최소 0.376 사이. */
        const val HIP_FLOOR_HYSTERESIS = 0.05f
        const val PIKE_MIN = 0.25f
        const val SIDE_YAW_MAX = 0.15f

        const val START_CUE = "플랭크가 보여요. 시간을 재기 시작해요."
        const val FALLBACK_CUE = "카메라가 플랭크를 확인하지 못해 이번 세트는 시계로 잴게요."
        const val NOT_PRONE_REMIND_CUE = "플랭크 자세가 보이면 이어서 재요."

        /**
         * 이 프레임의 멈춤 사유 — 없으면 null(HOLD 조건). 순서 = 우선순위. 재료가 없는 조건은 묻지 않는다(골반 지지선은 팔꿈치가 보일 때만, 솟음은 측면일 때만).
         * 엎드림은 중력 축각(`fc_axis_h`)으로, up 을 믿을 수 없으면 화면 수평도(`fc_flat_screen`, 화면이 서 있다는 가정 — 종전 `PlankGeometry` 와 같다)로 묻는다 —
         * 전에는 up 이 없으면 아예 묻지 않아 서서 팔짱을 껴도 시간이 쌓일 수 있었다(리뷰 2026-10-07).
         */
        fun stopReason(f: Map<String, Float>, hipFloorMin: Float = HIP_FLOOR_MIN): String? {
            fun g(k: String) = f[k]?.takeIf { it.isFinite() }
            if (g(FloorChain.CHAIN) != 1f) return OUT_OF_VIEW
            val axis = g(FloorChain.AXIS_H)
            if (axis != null) { if (axis > NOT_PRONE_DEG) return NOT_PRONE }
            else g(FloorChain.FLAT_SCREEN)?.let { if (it < FLAT_SCREEN_MIN) return NOT_PRONE }
            val knee = g(FloorChain.KNEE)
            if (knee != null && knee < KNEE_MIN_DEG) return KNEES_DOWN
            if (knee != null) g(FloorChain.HIP_FLOOR)?.let { if (it < hipFloorMin) return HIPS_LOW }
            val yaw = g(FloorChain.YAW)
            if (yaw != null && yaw <= SIDE_YAW_MAX) g(FloorChain.HIP_OFF)?.let { if (it > PIKE_MIN) return PIKE }
            return null
        }

        /** 멈춤 사유의 음성(두 모드, `speakLatest`, 사유별 6 s). `not_prone` 은 바로 말하지 않는다(5 s 뒤 [NOT_PRONE_REMIND_CUE]). */
        fun cueFor(reason: String?): String? = when (reason) {
            OUT_OF_VIEW -> "몸이 화면에서 벗어나 시간을 멈췄어요. 어깨부터 발목까지 보이게 해 주세요."
            KNEES_DOWN -> "무릎이 바닥에 닿아 시간을 멈췄어요. 무릎을 펴면 다시 재요."
            HIPS_LOW -> "골반이 내려가 시간을 멈췄어요. 몸을 들면 다시 재요."
            PIKE -> "엉덩이가 높이 올라가 시간을 멈췄어요. 엉덩이를 내리면 다시 재요."
            else -> null
        }

        /** 처음 버티기 전 사유의 안내(사유마다 세트에 한 번) — '무엇이 있어야 재기 시작하는가'. 폴백하지 않는 대신 이유를 말한다. */
        fun waitCue(reason: String?): String? = when (reason) {
            OUT_OF_VIEW -> "어깨부터 발목까지 보이게 해 주세요. 보이면 시간을 재기 시작해요."
            NOT_PRONE -> "플랭크 자세가 보이면 시간을 재기 시작해요."
            KNEES_DOWN -> "무릎을 펴면 시간을 재기 시작해요."
            HIPS_LOW -> "골반을 들면 시간을 재기 시작해요."
            PIKE -> "엉덩이를 내리면 시간을 재기 시작해요."
            else -> null
        }

        /** 사건의 음성 — 재개는 짧은 톤만이라 null. */
        fun cueFor(event: PlankHoldEvent): String? = when (event.kind) {
            PlankHoldEvent.Kind.START -> START_CUE
            PlankHoldEvent.Kind.RESUME -> null
            PlankHoldEvent.Kind.STOP -> cueFor(event.reason)
            PlankHoldEvent.Kind.REMIND -> NOT_PRONE_REMIND_CUE
            PlankHoldEvent.Kind.FALLBACK -> FALLBACK_CUE
            PlankHoldEvent.Kind.HINT -> waitCue(event.reason)
        }
    }
}
