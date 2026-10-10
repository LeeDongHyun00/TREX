package com.example.trex_kotlin.posture

/**
 * 바닥 계열(spec §99, `docs/FLOOR_FAMILY_DESIGN.md` §4.4·§4.7·§6)의 라이브 배선에서 화면·음성을 정하는 순수 로직 — `PostureLive` 가 부르고 유닛 테스트가 같은 함수를 본다.
 * 안드로이드에 의존하지 않는다.
 */
object FloorReasons {
    /** 세지 않은 동작의 짧은 이름(HUD '세지 않은 동작 n · 목만 당김', 리포트 사유별 수). 모르는 사유는 그대로. */
    fun repLabel(profile: FloorProfile?, reason: String?): String = when (reason) {
        "sit_up" -> "끝까지 일어남"
        "arms_only" -> "팔만 움직임"
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

    /**
     * 플랭크 멈춤 문장의 사유별 간격(§101c, 사용자 요청 "타이머가 중지되면 원인을 말하고 어떻게 교정되어야 하는지") — 종전 6 s(판별 기각과 같은 간격)는 10-09 플랭크의 둘째 멈춤(엉덩이 솟음 2 s)을
     * 삼켰다. 2 s 면 멈춤마다 말하되 문턱 안팎에서 1 s 확정을 오가는 재멈춤은 한 번만. 멈춘 채 사유가 바뀌면 시계가 새 멈춤 사건을 낸다(`PlankHoldClock` 사유 교체).
     */
    const val STOP_GAP_MS = 2_000L

    /**
     * 재개 사건의 문장(spec §100) — 직전 멈춤의 이유를 **말했고**([spokenStopReason]) 그 이유가 자세(무릎·골반·엉덩이 솟음)였으면 "좋아요, 교정됐어요. 다시 재요.".
     * 화면 밖·일어섬은 자세 교정이 아니라 톤만(종전). 이유를 말하지 않은 멈춤(6 s 간격 안의 재멈춤)의 재개도 톤만 — 재잘거림 방지(설계 §4.7)는 그대로다.
     */
    fun resumeCue(spokenStopReason: String?, heard: (String) -> Boolean = { true }): String? = when (spokenStopReason) {
        // 들림 장부(§101): 멈춤 문장이 재생을 시작하지 못했으면 교정 문장 대신 "다시 재요" 만
        PlankHoldClock.KNEES_DOWN, PlankHoldClock.HIPS_LOW, PlankHoldClock.PIKE -> if (heard(spokenStopReason)) PlankHoldClock.RESUME_CUE else PlankHoldClock.RESUME_CONTINUE
        else -> null
    }
}

/**
 * 플랭크 시선 음성(spec §100 — 사용자 요청 2026-10-08 "플랭크 시선은 양손 사이 바닥이나 살짝 앞쪽"). 정렬 추적기(`PlankAlignmentTracker`)의 **고개 항목**(규칙 `플랭크|고개 젖힘과 숙임`,
 * `plank_head_pitch` 띠 [−42.7, +25]°: 0 = 얼굴이 몸통에 수직으로 바닥, + = 앞)을 말로 낸다 — 바닥 beta 음성 금지(Q1)의 **사용자 결정 예외**로, 시선만. 그래서 보수적으로:
 *  - 유지 시계가 플랭크로 확인한 칸([AlignmentSnapshot.held])이고 측면·투영 준비([AlignmentSnapshot.placementReady])가 된 때만 — 무릎을 댄 칸·멈춘 동안은 보지 않는다.
 *  - 추적기 자체의 1 s 지속 위에 [sustainMs](2 s) 더 이어져야 말한다(합계 ≈ 3 s). 같은 방향·반대 방향 모두 [cooldownMs](15 s) 에 한 번.
 *  - 말한 뒤 OK 가 [recoverMs](1 s) 이어지면 "좋아요, 시선이 교정됐어요" 한 번. 유보(고개 관절 없음·|값| > 90°)는 교정이 아니다 — 기다린다.
 * 근거: AIHub 플랭크 C 2,478 프레임 중앙값 −19.8°, 띠 위 2.3 %(> +25)·아래 5.9 %(< −42.7), 수행자 25명 중 4명은 띠 밖 20 % 넘음(턱을 많이 당기는 스타일) — 아래쪽 음성이 잦으면
 * 폰 블록(`docs/exercises/floor_trial.md`)으로 띠를 다시 정한다. 폰 10-07 세트 1: 골반 내림 동안 고개 +33~35°(위), 세트 2: −39~−56°(아래).
 */
class PlankGazeVoice(private val sustainMs: Long = SUSTAIN_MS, private val cooldownMs: Long = COOLDOWN_MS, private val recoverMs: Long = RECOVER_MS) {
    private var since: Long? = null
    private var sinceSide = 0
    private var okSince: Long? = null
    private var lastSpokenAt = Long.MIN_VALUE / 2
    private var pendingRecovery = false
    /** 말한 사건(시각, 문장) — 세트 로그·진단. */
    val spoken = ArrayList<Pair<Long, String>>()
    /** 마지막으로 말한 방향(> 0 위·< 0 아래) — 화살표(§101). */
    var lastSide = 0
        private set

    private var turnSince: Long? = null
    private var turnState = FloorGaze.UNKNOWN
    private var turnOkSince: Long? = null
    private var pendingTurnRecovery = false
    /** 지금 이어지는 틀린 시선의 길이(ms, 화면 칩) — 없으면 0. */
    fun wrongForMs(now: Long): Long = turnSince?.let { now - it } ?: 0L
    /** 마지막으로 말한 틀린 상태(화면 칩). 교정되면 모름. */
    var lastWrong = FloorGaze.UNKNOWN
        private set

    fun reset() { since = null; sinceSide = 0; okSince = null; lastSpokenAt = Long.MIN_VALUE / 2; pendingRecovery = false; spoken.clear(); lastSide = 0; turnSince = null; turnState = FloorGaze.UNKNOWN; turnOkSince = null; pendingTurnRecovery = false; lastWrong = FloorGaze.UNKNOWN }

    /**
     * 판정 칸마다. 말할 문장 또는 null. [gaze] = 그 칸의 시선 상태(§101d `FloorGaze` — 얼굴 메시 모델의 요: 정면·화면 쪽·반대쪽·모름): 버티는 칸에서 틀린 시선이 [TURN_SUSTAIN_MS] 이어지면
     * [TURN_CUE]("시선이 벗어났어요. 시선을 양손 사이 바닥에 두세요." — 사용자 결정 10-10 오후: 화면 쪽·반대쪽을 가르지 않고 벗어남만 알리고 정상 시선을 안내한다; 쿨다운 공유),
     * 그 뒤 [recoverMs] 동안 벗어남이 아니면(정면 또는 화면 밖 — 측면 폰은 옆얼굴을 못 찾아 정면이 거의 관측되지 않는다, 10-10) [RECOVERED_CUE]. 모름은 지속을 끊지 않되 세지도 않는다.
     * 고개 위·아래(정렬 항목)보다 먼저 본다 — 폰을 보는 것이 더 흔하다(10-09 플랭크).
     * [heard] = 지적 문장이 재생을 시작했는가(§101 들림 장부) — 아니면 교정 문장을 내지 않고 대기만 푼다.
     */
    fun frame(now: Long, snapshot: AlignmentSnapshot, gaze: Int = FloorGaze.UNKNOWN, heard: () -> Boolean = { true }): String? {
        if (!snapshot.held) { turnSince = null; turnOkSince = null }
        else if (FloorGaze.wrong(gaze)) {
            turnOkSince = null
            if (turnSince == null || gaze != turnState) { turnSince = now; turnState = gaze }
            if (now - turnSince!! >= TURN_SUSTAIN_MS && now - lastSpokenAt >= cooldownMs) {
                lastSpokenAt = now; pendingTurnRecovery = true; turnSince = null; lastSide = 0; lastWrong = gaze
                return turnCueFor(gaze).also { spoken += now to it }
            }
        } else if (FloorGaze.recovers(gaze)) {
            turnSince = null
            if (pendingTurnRecovery) {
                val ok = turnOkSince ?: now.also { turnOkSince = it }
                if (now - ok >= recoverMs) {
                    pendingTurnRecovery = false; turnOkSince = null; lastWrong = FloorGaze.UNKNOWN
                    if (heard()) return RECOVERED_CUE.also { spoken += now to it }
                }
            }
        }
        val item = snapshot.items.firstOrNull { it.head }
        if (!snapshot.held || !snapshot.placementReady || item == null || item.value == null || item.verdict == Verdict.ABSTAIN) { since = null; okSince = null; return null }
        if (item.verdict == Verdict.VIOLATION) {
            okSince = null
            if (since == null || item.side != sinceSide) { since = now; sinceSide = item.side }
            if (now - since!! >= sustainMs && now - lastSpokenAt >= cooldownMs) {
                lastSpokenAt = now; pendingRecovery = true; since = null; lastSide = item.side
                return cueFor(item.side).also { spoken += now to it }
            }
            return null
        }
        since = null
        if (!pendingRecovery) return null
        val ok = okSince ?: now.also { okSince = it }
        if (now - ok < recoverMs) return null
        pendingRecovery = false; okSince = null
        if (!heard()) return null
        return RECOVERED_CUE.also { spoken += now to it }
    }

    companion object {
        const val SUSTAIN_MS = 2_000L
        const val COOLDOWN_MS = 15_000L
        const val RECOVER_MS = 1_000L
        const val RECOVERED_CUE = "좋아요, 시선이 교정됐어요."
        /** 교정 문장인가 — 배선이 들림 장부 키 없이 낸다. */
        fun isRecovery(line: String): Boolean = line == RECOVERED_CUE
        /** 틀린 시선(화면 쪽·반대쪽)이 이만큼 이어지면 말한다(§101d) — 크런치·레그 레이즈의 '2회 두기' 에 맞춘 버티기 쪽 유예. */
        const val TURN_SUSTAIN_MS = 3_000L
        /** 머리 돌림(화면 쪽·반대쪽 공통 — 사용자 결정 2026-10-10 오후 "구분하지 말고 벗어났다는 것만 알리고 정상 시선 안내"). */
        const val TURN_CUE = "시선이 벗어났어요. 시선을 양손 사이 바닥에 두세요."
        fun turnCueFor(@Suppress("UNUSED_PARAMETER") state: Int): String = TURN_CUE
        /** 시선 화살표(§101·§101d) — 고개 들림은 코에서 중력 아래로, 떨굼은 위로(코 하나라 쪽이 없다). 돌린 시선(0)은 가야 할 곳: 코에서 양손 사이(손목)로. */
        fun motionFor(side: Int): FormMotion = if (side == 0) FormMotion(MotionAnchor.HEAD, high = MotionKind.TOWARD_TARGET, target = MotionAnchor.WRISTS, targetPick = MotionPick.CHAIN, frame = MotionFrame.GRAVITY)
            else FormMotion(MotionAnchor.HEAD, high = if (side > 0) MotionKind.DOWN else MotionKind.UP, frame = MotionFrame.GRAVITY)
        /** [side] > 0 = 얼굴이 앞·위(고개 들림), < 0 = 아래(고개 떨굼). 화면 문장(`AlignmentItem.message`)과 같은 말. */
        fun cueFor(side: Int): String = if (side > 0) "고개가 들려 앞을 보고 있어요. 시선을 양손 사이 바닥에 두세요." else "고개가 많이 숙여졌어요. 시선을 양손 사이 바닥이나 살짝 앞에 두세요."
    }
}

/**
 * 크런치·레그 레이즈 시선 음성(§101d — 사용자 정의 2026-10-09 "시선 = 정면(천장·무릎 앞)이 올바름, 카메라 쪽·반대쪽은 틀림. 2번 정도는 두고 2번 넘게 이어 하면 멘트, 횟수는 막지 마").
 * 센 회마다 `FloorRep.gaze`(사이클 프레임의 `FloorGaze` 다수결 — 얼굴 메시 모델의 요)로 본다. **틀린 시선(화면 쪽·반대쪽)이 연속 [GRACE_REPS] 회까지는 두고** 그다음 회부터 [cooldownMs] 에 한 번 말한다.
 * 횟수는 막지 않는다(코칭 전용). **문장은 하나**(종목별 정상 시선 안내 — 사용자 결정 2026-10-10 오후 "반대편·휴대폰을 구분하지 말고 벗어났다는 것만 알리고 정상 시선을 안내해"):
 * 측면 폰은 카메라 쪽 얼굴만 찾고 옆얼굴(정면)·뒤통수(반대쪽)는 똑같이 못 찾으므로(10-10) 벗어남 = 얼굴이 카메라를 향함이다. 말한 뒤 벗어남이 아닌 회(정면 또는 화면 밖 — 카메라를
 * 향하지 않음)에 [RECOVERED_CUE] 한 번(§100 — 말한 지적의 통과). 모름(재료 없음)은 유보 — 연속을 끊지 않되 세지도 않는다. 세지 않은 동작(판별 기각)은 보지 않는다.
 * §101c 의 귀 간격 비(`faceCam`)는 반대쪽을 정면과 구분하지 못했다(설계 §2) — 로그 필드로만 남는다.
 */
class FloorGazeVoice(private val cooldownMs: Long = COOLDOWN_MS) {
    private var streak = 0
    private var lastSpokenAt = Long.MIN_VALUE / 2
    private var pendingRecovery = false
    private var lastRepMs = Long.MIN_VALUE
    val spoken = ArrayList<Pair<Long, String>>()
    /** 마지막으로 말한 틀린 상태(화면 쪽·반대쪽) — 화면 칩. 교정되면 모름. */
    var lastWrong = FloorGaze.UNKNOWN
        private set
    /** 지금 이어지는 틀린 시선 회 수(화면 칩). */
    val wrongStreak: Int get() = streak

    fun reset() { streak = 0; lastSpokenAt = Long.MIN_VALUE / 2; pendingRecovery = false; lastRepMs = Long.MIN_VALUE; spoken.clear(); lastWrong = FloorGaze.UNKNOWN }

    /** 센 회 하나(같은 회를 두 번 주면 무시). 말할 문장 또는 null. [profile] 은 문장(무릎 쪽·천장)을 고른다. [heard] = 지적이 재생을 시작했는가(§101). */
    fun rep(now: Long, rep: FloorRep, profile: FloorProfile, heard: () -> Boolean = { true }): String? {
        if (rep.tMs == lastRepMs) return null
        lastRepMs = rep.tMs
        val g = rep.gaze
        if (g == FloorGaze.UNKNOWN) return null
        if (FloorGaze.wrong(g)) {
            streak++
            if (streak > GRACE_REPS && now - lastSpokenAt >= cooldownMs) { lastSpokenAt = now; pendingRecovery = true; lastWrong = g; return cueFor(profile, g).also { spoken += now to it } }
            return null
        }
        streak = 0
        if (!pendingRecovery || !FloorGaze.recovers(g)) return null
        pendingRecovery = false; lastWrong = FloorGaze.UNKNOWN
        if (!heard()) return null
        return RECOVERED_CUE.also { spoken += now to it }
    }

    companion object {
        /** 이만큼 연속으로 틀린 시선이어도 두고, 그다음 회부터 말한다(사용자 결정 "2번 정도는"). */
        const val GRACE_REPS = 2
        const val COOLDOWN_MS = 12_000L
        /** 종목별 한 문장 — 벗어남 알림 + 정상 시선 안내(사용자 결정 10-10 오후). 상태(화면 쪽·반대쪽)는 문장을 가르지 않는다. */
        const val CRUNCH_CUE = "시선이 벗어났어요. 턱을 살짝 당겨 무릎 쪽을 보세요."
        const val LEG_RAISE_CUE = "시선이 벗어났어요. 고개를 돌리지 말고 천장을 보세요."
        fun cueFor(profile: FloorProfile, @Suppress("UNUSED_PARAMETER") state: Int): String = when (profile) {
            FloorProfile.CRUNCH -> CRUNCH_CUE
            FloorProfile.LEG_RAISE -> LEG_RAISE_CUE
        }
        /** 시선 화살표(§101d) — 틀린 방향이 아니라 **가야 할 곳**을 그린다: 크런치는 코에서 보이는 쪽 무릎으로, 레그 레이즈는 코에서 중력 위(천장)로. */
        fun motionFor(profile: FloorProfile): FormMotion = when (profile) {
            FloorProfile.CRUNCH -> FormMotion(MotionAnchor.HEAD, high = MotionKind.TOWARD_TARGET, target = MotionAnchor.KNEES, targetPick = MotionPick.CHAIN, frame = MotionFrame.GRAVITY)
            FloorProfile.LEG_RAISE -> FormMotion(MotionAnchor.HEAD, high = MotionKind.UP, frame = MotionFrame.GRAVITY)
        }
        /** 벗어남 지적 뒤 벗어나지 않은 회 — "돌아왔다" 는 이 앱의 정의(벗어남 = 카메라를 향함) 안의 말이다(사용자 결정 10-10 오후). */
        const val RECOVERED_CUE = "좋아요, 시선이 돌아왔어요."
        /** 교정 문장인가 — 배선이 들림 장부 키 없이 낸다. */
        fun isRecovery(line: String): Boolean = line == RECOVERED_CUE
    }
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
    /** 마지막으로 **말한** 기각 사유(spec §100) — 다음 센 회가 그 교정이다([takeRecovery]). 말하지 않은 기각(간격 안·음소거)은 넣지 않는다. [RECOVERY_ENABLED] 가 꺼져 있으면 넣지 않는다. */
    private var pendingRecovery: String? = null

    fun next(now: Long, reason: String?, source: IdentityCueSource?, silent: Boolean, perReason: Boolean): Cue {
        if (source == null || silent) return Cue(false, null)
        if (reason != null && source.silent(reason)) return Cue(false, null)   // 관측 붕괴 사이클(§101) — 틱도 없다
        val text = reason?.let(source::cueFor)
        val key = if (perReason) reason.orEmpty() else "*"
        val due = text != null && now - (lastAt[key] ?: Long.MIN_VALUE / 2) > gapMs
        if (due) { lastAt[key] = now; if (RECOVERY_ENABLED && reason != null && source.recovers(reason)) pendingRecovery = reason }
        return Cue(true, if (due) text else null)
    }

    /**
     * 센 회가 나왔다(spec §100) — 직전에 말한 기각 사유가 있으면 "좋아요, 교정됐어요." 를 한 번 돌려주고 지운다. 세는 것 자체가 그 사유의 교정이다(사유는 횟수의 입장 조건).
     * 한 다리 계열은 반대쪽 다리의 센 회도 교정으로 친다(사유는 양쪽 공통).
     *
     * **지금은 꺼져 있다**([RECOVERY_ENABLED], 사용자 결정 2026-10-08 U0) — '다음 센 회 = 교정' 은 쪽·사유·여유를 보지 않아 10-08 폰에서 틀린 칭찬을 했다(니업: 오른쪽 지적에
     * 왼쪽 회 칭찬, 문턱 1.1° 안쪽 경계 회 칭찬; 크런치: 손 머리 뒤 기각 뒤 팔 뻗기로 자세를 바꾼 회 칭찬; 사이드 런지: 유령 기각 뒤 칭찬). 공용 기반 F1(`RecoveryContext` —
     * 들린 지적 ∧ 판정 통과 ∧ 깨끗하게 센 회 ∧ 같은 쪽 ∧ 여유)이 들어올 때 다시 켠다(`docs/PHONE_REPORT_2026-10-08_DESIGN.md` §5).
     */
    fun takeRecovery(heard: (String) -> Boolean = { true }): String? = pendingRecovery?.let { r -> pendingRecovery = null; if (heard(r)) RECOVERED_CUE else null }

    /** 교정됨을 기다리는 사유(테스트·진단). */
    val awaitingRecovery: String? get() = pendingRecovery

    fun reset() { lastAt.clear(); pendingRecovery = null }

    companion object {
        const val GAP_MS = 6_000L
        const val RECOVERED_CUE = "좋아요, 교정됐어요."
        /** 판별 기각 뒤의 "교정됐어요" — F1 `RecoveryContext` 전까지 끔(사용자 결정 2026-10-08 U0). */
        const val RECOVERY_ENABLED = false
    }
}

/**
 * `PostureLive` 기각 블록(설계 §4.4 — 2026-10-07 배선). 전에는 이 블록 전체가 반복 검사 평가기(`RepFormSpecs`) 조건 안에 있어 평가기가 0개인 바닥 종목의
 * 판별 기각은 틱도 이유도 안 나갔다(검증자 발견). 이제 새 기각을 세는 것은 평가기와 무관하고, 평가기가 있을 때만 그 창을 소비시킨다([RepFormEvaluator.onRejected]).
 */
object RejectionCues {
    /**
     * [seen] = 처리한 기각 수(다음 호출의 입력), [cue] = 새 기각이 있었고 원천([RepCounter.cueSource])이 있을 때의 결정. [last] = 마지막 새 기각.
     * [motion] = **말한 경우에만** 그 사유의 화살표(§101 — 화살표의 문턱이 곧 음성의 문턱, 간격 안의 무음 기각은 새 화살표를 만들지 않는다), 쪽은 [last] 의 `side`.
     */
    data class Result(val seen: Int, val cue: RejectCueGate.Cue?, val last: RepRejected?, val motion: FormMotion? = null)

    fun handle(rc: RepCounter, rf: RepFormEvaluator?, seen: Int, now: Long, gate: RejectCueGate, silent: Boolean): Result {
        val n = rc.rejectedReps.size
        if (n <= seen) return Result(seen, null, null)
        if (rf != null) for (i in seen until n) rf.onRejected(rc.rejectedReps[i].tMs)
        // 음성·틱은 새 기각 중 무음이 아닌 마지막 것으로 — 같은 프레임에서 왼쪽 blip 과 오른쪽 실제 사유가 함께 끝나도 무음이 실제 사유를 가리지 않게(§101)
        val src = rc.cueSource
        val last = (seen until n).map { rc.rejectedReps[it] }.lastOrNull { r -> src == null || r.feature == null || !src.silent(r.feature) } ?: rc.rejectedReps[n - 1]
        val cue = rc.cueSource?.let { gate.next(now, last.feature, it, silent, perReason = rc.floorTracker != null) }
        val motion = if (cue?.speech != null) last.feature?.let { f -> src?.motionFor(f) } else null
        return Result(n, cue, last, motion)
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
