package com.example.trex_kotlin.posture

/**
 * 바닥 계열(spec §99, `docs/FLOOR_FAMILY_DESIGN.md` §4.4·§4.7·§6)의 라이브 배선에서 화면·음성을 정하는 순수 로직 — `PostureLive` 가 부르고 유닛 테스트가 같은 함수를 본다.
 * 안드로이드에 의존하지 않는다.
 */
object FloorReasons {
    /** 세지 않은 동작의 짧은 이름(HUD '세지 않은 동작 n · 목만 당김', 리포트 사유별 수). 모르는 사유는 그대로. */
    fun repLabel(profile: FloorProfile?, reason: String?): String = when (reason) {
        "sit_up" -> "끝까지 일어남"
        "neck_only" -> "목만 당김"
        "shallow" -> if (profile == FloorProfile.LEG_RAISE) "얕게 올림" else "덜 올라옴"
        "trunk_up" -> "상체 들림"
        "knee_bent" -> "무릎 굽힘"
        "one_leg" -> "한 다리"
        "feet_touch" -> "발 닿음"
        else -> reason ?: "기타"
    }

    /** 플랭크 멈춤 사유의 짧은 이름(리포트 '멈춤: 무릎 6초 · 골반 4초'). */
    fun holdLabel(reason: String?): String = when (reason) {
        PlankHoldClock.OUT_OF_VIEW -> "화면 밖"
        PlankHoldClock.NOT_PRONE -> "자세 벗어남"
        PlankHoldClock.KNEES_DOWN -> "무릎"
        PlankHoldClock.HIPS_LOW -> "골반"
        PlankHoldClock.PIKE -> "엉덩이 솟음"
        else -> reason ?: "기타"
    }

    /**
     * HUD 상태 줄(설계 §6) — 멈춘 동안 '멈춤 · 무릎이 바닥에 닿음'. 처음 버티기 전(WAIT)은 **그 사유에 맞는** 재개 조건 — 전에는 화면 밖이 아니면 모두 '골반을 들면' 이라
     * 엉덩이를 높이 든(pike) 사용자에게 반대 방향을 가르쳤다(원칙 #6, 리뷰 2026-10-07). HOLD 면 null.
     */
    fun holdStatus(phase: PlankHoldClock.Phase, reason: String?): String? = when (phase) {
        PlankHoldClock.Phase.HOLD -> null
        PlankHoldClock.Phase.WAIT -> "대기 · " + when (reason) {
            PlankHoldClock.OUT_OF_VIEW -> "어깨부터 발목까지 보이게 해 주세요"
            PlankHoldClock.NOT_PRONE -> "플랭크 자세가 보이면 재요"
            PlankHoldClock.KNEES_DOWN -> "무릎을 펴면 재기 시작해요"
            PlankHoldClock.HIPS_LOW -> "골반을 들면 재기 시작해요"
            PlankHoldClock.PIKE -> "엉덩이를 내리면 재기 시작해요"
            else -> "다리를 펴고 골반을 들면 재기 시작해요"
        }
        PlankHoldClock.Phase.STOP -> "멈춤 · " + when (reason) {
            PlankHoldClock.OUT_OF_VIEW -> "몸이 화면 밖"
            PlankHoldClock.NOT_PRONE -> "플랭크 자세가 아님"
            PlankHoldClock.KNEES_DOWN -> "무릎이 바닥에 닿음"
            PlankHoldClock.HIPS_LOW -> "골반이 내려감"
            PlankHoldClock.PIKE -> "엉덩이가 높이 올라감"
            else -> "다시 확인 중"
        }
    }

    /**
     * 바닥 종목의 라이브 큰 줄(`PostureLive` 바닥 분기) — 안내(신호 결측·누운 기준 없음) > 바닥 피드백의 관측·측정 문장. beta 확인 필요(ATTENTION)·범위 복귀(RECOVERED)는
     * '참고' 칩이 맡으므로 여기서는 **중립 측정 문장**이다 — 전에는 그때 배치 지시('옆모습을 화면에 담아 주세요')로 떨어져 제대로 찍힌 사용자가 폰을 옮기게 했다
     * (리뷰 2026-10-07). 배치 지시는 피드백이 아직 없을 때만.
     */
    fun liveMessage(guide: String?, feedback: FloorFeedback?): String = guide ?: when (feedback?.phase) {
        null -> PLACEMENT_LINE
        FloorFeedbackPhase.ATTENTION, FloorFeedbackPhase.RECOVERED -> MEASURING_LINE
        else -> feedback.message
    }
    const val PLACEMENT_LINE = "옆모습을 화면에 담아 주세요."
    const val MEASURING_LINE = "측정 중이에요 · 참고"
}

/**
 * 플랭크 유지 시계 사건의 소리(설계 §4.7) — `FloorLiveState.holdFrame` 이 쓰고 테스트가 같은 함수를 본다.
 */
object HoldVoice {
    /**
     * 이 사건을 소리 낼 것인가. 시계 폴백 뒤(`source = clock`)에는 폴백 안내만 — 남은 시간이 벽시계로 줄어드는데 "시간을 재기 시작해요"·"…시간을 멈췄어요" 라고 하면
     * 사용자가 시간이 멈춘 줄 알고 잘못 움직인다(원칙 #6, 리뷰 2026-10-07). 카메라 시간은 로그용으로 계속 잰다.
     */
    fun voiced(kind: PlankHoldEvent.Kind, source: String): Boolean = source == PlankHoldClock.SOURCE_CAMERA || kind == PlankHoldEvent.Kind.FALLBACK

    /**
     * 멈춤 사건의 틱·문장 — 말할 문장이 없는 멈춤(`not_prone` 일어섬·앉음: 5 s 뒤 한 번 말한다)은 **틱도 없다**. 낮은 틱은 '이유가 있는 멈춤' 의 신호라 이유 없이 나면
     * 무엇이 틀렸는지 모른다(리뷰 2026-10-07). 같은 사유는 [gate] 의 간격(6 s) 안에 다시 말하지 않는다.
     */
    fun stopCue(gate: RejectCueGate, now: Long, reason: String?, source: IdentityCueSource): RejectCueGate.Cue =
        if (reason == null || source.cueFor(reason) == null) RejectCueGate.Cue(false, null)
        else gate.next(now, reason, source, silent = false, perReason = true)
}

/**
 * 판별 기각(세지 않은 동작)·플랭크 멈춤의 틱과 이유 음성 결정(설계 §4.4) — 같은 이유는 [gapMs](6 s) 안에 다시 말하지 않는다.
 * 바닥은 **사유별** 간격, 한 다리 계열은 종전처럼 사유와 무관한 하나의 간격(그 동작은 바꾸지 않는다). 틱은 간격과 무관하게 매번(세지 않은 동작 = 낮은 틱).
 * 검증 모드·일시정지·음소거([silent])면 틱도 말도 없다. 두 모드(COACH·TRACK) 같다 — 횟수의 입장 조건이지 자세 코칭이 아니다.
 */
class RejectCueGate(private val gapMs: Long = GAP_MS) {
    /** [tick] = 낮은 틱을 낸다, [speech] = 말할 문장(null = 말하지 않음). */
    data class Cue(val tick: Boolean, val speech: String?)

    private val lastAt = HashMap<String, Long>()

    fun next(now: Long, reason: String?, source: IdentityCueSource?, silent: Boolean, perReason: Boolean): Cue {
        if (source == null || silent) return Cue(false, null)
        val text = reason?.let(source::cueFor)
        val key = if (perReason) reason.orEmpty() else "*"
        val due = text != null && now - (lastAt[key] ?: Long.MIN_VALUE / 2) > gapMs
        if (due) lastAt[key] = now
        return Cue(true, if (due) text else null)
    }

    fun reset() = lastAt.clear()

    companion object { const val GAP_MS = 6_000L }
}

/**
 * `PostureLive` 기각 블록(설계 §4.4 — 2026-10-07 배선). 전에는 이 블록 전체가 반복 검사 평가기(`RepFormSpecs`) 조건 안에 있어 평가기가 0개인 바닥 종목의
 * 판별 기각은 틱도 이유도 안 나갔다(검증자 발견). 이제 새 기각을 세는 것은 평가기와 무관하고, 평가기가 있을 때만 그 창을 소비시킨다([RepFormEvaluator.onRejected]).
 */
object RejectionCues {
    /** [seen] = 처리한 기각 수(다음 호출의 입력), [cue] = 새 기각이 있었고 원천([RepCounter.cueSource])이 있을 때의 결정. [last] = 마지막 새 기각. */
    data class Result(val seen: Int, val cue: RejectCueGate.Cue?, val last: RepRejected?)

    fun handle(rc: RepCounter, rf: RepFormEvaluator?, seen: Int, now: Long, gate: RejectCueGate, silent: Boolean): Result {
        val n = rc.rejectedReps.size
        if (n <= seen) return Result(seen, null, null)
        if (rf != null) for (i in seen until n) rf.onRejected(rc.rejectedReps[i].tMs)
        val last = rc.rejectedReps[n - 1]
        val cue = rc.cueSource?.let { gate.next(now, last.feature, it, silent, perReason = rc.floorTracker != null) }
        return Result(n, cue, last)
    }
}

/**
 * 플랭크 시간 읽기(설계 §4.7) — 10 s 마다 인정 시간을, 남은 5 s 는 카운트를. 숫자 읽기 설정을 따르고(끄면 짧은 톤) speak 대기열로 낸다(끊지 않는다).
 * 시계가 한 번에 여러 경계를 넘으면(1 s 소급 적립) 마지막 경계 하나만.
 */
object HoldAnnounce {
    /** [text] = 읽을 말, [countdown] = 남은 5 s 카운트(톤이면 이중 톤). */
    data class Announcement(val text: String, val countdown: Boolean)

    const val STEP_MS = 10_000L
    const val COUNTDOWN_S = 5

    fun between(prevMs: Long, nowMs: Long, targetMs: Long): Announcement? {
        if (nowMs <= prevMs || targetMs <= 0L) return null
        val remainPrev = targetMs - prevMs
        val remainNow = targetMs - nowMs
        if (remainNow <= 0L) return null
        val k = (1..COUNTDOWN_S).firstOrNull { remainPrev > it * 1000L && remainNow <= it * 1000L }
        if (k != null) return Announcement("$k", true)
        val b = (nowMs / STEP_MS) * STEP_MS
        if (b > prevMs && b > 0L && targetMs - b > COUNTDOWN_S * 1000L) return Announcement(secondsText((b / 1000L).toInt()), false)
        return null
    }

    /** "10초"·"1분"·"1분 10초". */
    fun secondsText(s: Int): String = when {
        s < 60 -> "${s}초"
        s % 60 == 0 -> "${s / 60}분"
        else -> "${s / 60}분 ${s % 60}초"
    }
}
