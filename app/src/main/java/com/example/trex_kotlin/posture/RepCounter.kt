package com.example.trex_kotlin.posture

/**
 * 자동 렙 카운터 v4 — 반전(reversal) 방식 (spec §27, 라벨 세트 실측으로 확정).
 *
 * v3(창 분위수 밴드)의 실측 결함 2건이 라벨 세트(깊3·얕3·깊3·얕3=12)에서 드러나 교체했다:
 *  (a) 손목-붕괴 잡프레임(값≈0.01)이 창 p10 을 끌어내려 밴드 전체가 내려앉음 → 얕은 렙 미카운트
 *  (b) 깊/얕 혼합 세트에서 밴드 상단(hi)이 깊은 렙 기준으로 높아져 얕은 렙의 복귀가 hi 에 못 닿음
 * v4 는 절대 위치(밴드)를 버리고 **방향 반전이 최소 스윙 h 를 넘을 때 극점을 확정**한다(만보기 원리):
 *  렙 = 하단 확정 → 상단 확정. 결과: 라벨 세트 9/12 검출(유효 5·무효 4 — 무효 렙 검출 최초 성공),
 *  baseline1 픽스처 4(정답 3~4), 플랭크 잡음 0·0·0·1. 놓친 3개는 잡프레임 구간·3.3fps 융합(±예산).
 *
 * 규약 (전부 모집단 파라미터 — 특정 사용자로 튜닝하지 않는다):
 *  1) h = 종목별 최소 스윙(물리 단위, 각도 35°/거리 0.25~0.30) — 플랭크 잡음 바닥 실측 위
 *  2) 물리 타당 범위 게이트: AIHub 프레임 분포 밖 값(손목 붕괴 등)은 가림과 동일하게 일시정지
 *  3) 평활은 **샘플 간격 기반**(dt≤0.35s 일 때만 3점 중앙값) — 주기 추정을 기다리는 이전 조건은
 *     초기 구간에서 성긴 신호까지 평활해 렙 꼭대기를 지웠다
 *  4) 불응기 1.2s — 단 불응기에 기각된 상단에서 하단 정보를 버리지 않는다(연쇄 유실 방지)
 *  5) 사이클 극값(lastCycleMin/Max)을 렙마다 노출 → ROM 유효성 판정 입력
 *
 * 참고: AIHub 0.6s(렙당 3~4샘플) 오프라인 분석은 밴드 v3(batch)가 우세 — 연구 코드는 그대로 두고
 * 이 클래스는 기기 스트리밍(렙당 10~17샘플) 전용이다. 밀도별 알고리즘 분리는 확립된 원칙.
 *
 * 경로는 셋이고 `signal.polarity` 가 가른다 (docs/REP_ENGINE_DESIGN.md §4.2·§4.3):
 *  - polarity = null, completeOnReturn = false → 반전형(위 v4). 연구 재생 파리티용.
 *  - polarity = null, completeOnReturn = true  → 복귀형(`ReturnRepTracker`, spec §42). **지금 앱 세션이 쓰는 것.**
 *  - polarity != null → 복귀 히스테리시스 코어(`RepHysteresis`) + 시작 확정(`RepStartConfirmation`).
 *    평활하지 않고, 준비 자세·상단 체류를 요구하지 않으며, 첫 두 사이클을 함께 발표한다. 이 경로에서는
 *    생성자의 refractoryMs·maxGapMs·completeOnReturn 을 쓰지 않고 코어의 설계값(0.8 s · 1.5 s, 복귀 완료)을 쓴다.
 *    폰 검증 전이라 `RepSignals` 의 어느 종목도 polarity 를 켜지 않는다 — 앱 동작은 바뀌지 않았다.
 * polarity = null 인 두 경로의 동작은 새 코어 도입 전과 같다(새 분기는 전부 polarity != null 일 때만 탄다 —
 * 기존 유닛 테스트 19개가 수정 없이 통과해야 한다).
 *
 * 반복 판별 게이트 (spec §62, 설계 §20) — 세 경로 모두에 같은 규칙으로 붙는다.
 *  카운트 신호가 사이클을 냈을 때 `signal.identityFeature` 의 그 사이클 창 스윙이 `identityMinAmp` 미만이면 **세지 않고** [rejectedReps] 에 남긴다.
 *  "좋은 반복인가" 가 아니라 "이 종목의 반복인가" 를 묻는다: 바벨 스쿼트의 knee_mean 은 양 무릎 평균이라 한쪽 무릎만 들어도(제자리 걷기)
 *  65° 가 흔들려 35° 게이트를 넘는다(2026-09-25 실기기 세트 — 7번 중 1번이 카운트됨). 더 편 쪽 무릎(knee_maxside)도 35° 굽어야 한다는
 *  조건으로 그 세트의 스쿼트 12회는 전부 남고 무릎 들기 7번은 전부 걸렸다. 판별 신호 샘플이 창에 2개 미만이면 판정하지 않고 센다
 *  (모르는 것을 기각으로 만들지 않는다 — 원칙 #1). 자세 규칙의 위반은 게이트가 아니다 — 자세는 세트 판정과 리포트의 몫이다.
 */
class RepCounter(
    val signal: RepSignal,
    /** 레거시 경로의 불응기. 새 코어 경로는 쓰지 않는다 — 실제 값은 [effectiveRefractoryMs]. */
    val refractoryMs: Long = 1_200L,
    /** 레거시 경로의 끊김 초기화 기준. 새 코어 경로는 쓰지 않는다 — 실제 값은 [effectiveMaxGapMs]. */
    val maxGapMs: Long = Long.MAX_VALUE,
    /** 세션 목표 카운트는 다음 하강 대신 준비 위치 복귀로 완료한다. 기존 재생 패리티는 기본값을 유지한다. */
    val completeOnReturn: Boolean = false,
    /**
     * 새 코어의 시작 확정(`RepStartConfirmation` — 첫 사이클을 둘째가 확인할 때까지 보류했다가 둘을 함께 발표)을 쓰는가.
     * 덤벨 컬 팔별 자식은 쓰지 않는다(§62c 후속 9): 회 = 두 팔 짝이라 화면 수가 0 → 2 로 뛰었고, 사선에서 가려졌던 먼 팔이 다시 보일 때마다
     * 그 팔의 '첫' 사이클이 다시 보류돼 세트 중에도 회가 몇 초씩 늦게 한꺼번에 올랐다(12:38 세트: 4회가 8.8 s 늦게). 컬 준비 동작(덤벨 집기)은 팔을 편 채라
     * 팔꿈치각 사이클이 거의 없어 확정이 막을 것이 적고, '컬 아님' 은 기각 게이트(몸통·상완 스윙)가 따로 막는다.
     */
    val startConfirmation: Boolean = true,
) {
    private val hysteresis = signal.polarity?.let { RepHysteresis(signal.minAmp, it) }
    private val confirmation = if (hysteresis != null && startConfirmation) RepStartConfirmation() else null
    private val returnTracker = if (completeOnReturn && hysteresis == null) ReturnRepTracker(signal.minAmp, refractoryMs) else null
    var reps: Int = 0
        private set
    val repTimesMs = ArrayList<Long>()

    /** 새 코어 경로인가 (signal.polarity != null). */
    val usesHysteresis: Boolean get() = hysteresis != null

    /**
     * 이 카운터가 **실제로 쓰는** 구성 — 세트 로그(`RepEngineLog`)가 이 값을 그대로 적는다(복사한 상수는 조용히 어긋난다).
     * 새 코어 경로는 생성자의 불응기·끊김 기준 대신 코어의 값을 쓰고, 복귀 완료는 코어의 성질이다.
     */
    val effectiveRefractoryMs: Long get() = hysteresis?.refractoryMs ?: refractoryMs
    val effectiveMaxGapMs: Long get() = hysteresis?.maxGapMs ?: maxGapMs
    val effectiveCompleteOnReturn: Boolean get() = hysteresis != null || completeOnReturn

    /** 새 코어의 복귀 잔여 비율(f). 레거시 경로는 null. */
    val returnFraction: Float? get() = hysteresis?.returnFraction

    /** 새 코어의 시작 확정 구성(값만 — 내부 상태는 내보내지 않는다). 레거시 경로는 null. */
    val confirmationConfig: RepConfirmationConfig? get() = confirmation?.config

    /**
     * 새 코어 경로에서 발표된(= 센) 사이클, 발화 순서대로 — 각자의 발화 시각과 원값 극값을 갖는다.
     * 레거시 경로에서는 비어 있다(레거시는 [lastCycleMin]/[lastCycleMax] 만 노출한다).
     * 내부 목록의 보기라 onFrame 과 같은 락 안에서 복사해 쓴다([repTimesMs] 와 같다).
     */
    val publishedReps: List<RepCycle> get() = confirmation?.published ?: emptyList()

    /**
     * 직전 onFrame 에서 새로 발표된 사이클들. 시작 확정은 첫 두 사이클을 **한 프레임에 함께** 발표하므로
     * onFrame 이 true 를 돌려줘도 [reps] 가 2 늘 수 있다 — 호출 쪽은 이 목록의 크기만큼 센다. 레거시 경로에서는 비어 있다.
     */
    var newlyPublished: List<RepCycle> = emptyList()
        private set

    /** 발화했지만 시작 확정을 기다리는 사이클 수(0 또는 1). 세지 않는다 — 화면에는 '감지 중' 으로만 쓸 수 있다(설계 §4.8). */
    val pendingReps: Int get() = if (confirmation?.pending != null) 1 else 0

    /** 확정되지 못하고 버려진 사이클 — 세트 로그용. 레거시 경로에서는 비어 있다. */
    val droppedReps: List<RepCycle> get() = confirmation?.dropped ?: emptyList()

    /**
     * 세트 종료 시점의 미완 상태 — **세지 않고** 로그에 '미완 후보' 로 남긴다(설계 §4.2·§4.7).
     * 절반만 올라온 동작이나 짝을 못 만난 한 번의 사이클을 한 회로 만들지 않는다. 레거시 경로에서는 둘 다 null.
     */
    fun pendingAtSetEnd(): RepPendingState = RepPendingState(confirmation?.pending, hysteresis?.candidate())

    /** 방금 완료된 렙의 사이클 극값 (onFrame 이 true 를 돌려준 직후 유효). */
    var lastCycleMin: Float = Float.NaN
        private set
    var lastCycleMax: Float = Float.NaN
        private set

    /**
     * 반복 판별 게이트가 **세지 않은** 사이클 — 세트 로그 `reps.rejected`. 판별 신호가 없는 종목은 늘 비어 있다.
     * [repTimesMs] 와 같은 락 안에서 복사해 쓴다.
     */
    val rejectedReps = ArrayList<RepRejected>()

    /**
     * 센 사이클마다의 판별 신호 스윙 — [repTimesMs] 와 같은 순서·길이. 판별 신호가 없는 종목은 비어 있고,
     * 그 사이클 창에 판별 샘플이 2개 미만이면 null(판정하지 않고 셌다). Gate A 가 게이트 여유를 재는 원자재.
     */
    val identitySwings = ArrayList<Float?>()

    private val identitySamples = ArrayList<Pair<Long, Float>>()
    private var identityGateAt = Long.MIN_VALUE / 2   // 마지막으로 판별한 사이클의 끝 — 다음 창은 그 뒤부터

    // ---- 팔별 경로 (spec §62c) — signal.pairedFeatures 가 있을 때만. 자식 카운터는 같은 구성(불응기·끊김·복귀 완료)으로 각자 센다.
    private val arms: Array<RepCounter>? = signal.pairedFeatures?.let { (l, r) ->
        arrayOf(l, r).map { f ->
            // 자식은 새 코어(복귀 히스테리시스, DOWN = 휴식이 신호의 높은 쪽 = 팔을 편 각) — 설계 §18.3 의 팔별 카운터가 이 코어였다(MediaPipe 교대 컬
            // 세트 정확 0.90). 레거시 복귀형을 팔별로 돌리면 MM-Fit 59세트에서 거의 세지 못했다(재생 2026-09-26: 세트 정확 0.05).
            RepCounter(signal.copy(feature = f, pairedFeatures = null, identityFeature = null, identityMinAmp = null, rejectFeatures = emptyMap(),
                romDirection = null, romThreshold = null, romRatio = null, comparisonFeature = null, comparisonMinAmp = null,
                polarity = RepPolarity.DOWN),
                refractoryMs, maxGapMs, completeOnReturn, startConfirmation = false)
        }.toTypedArray()
    }
    /** 팔별 경로인가. */
    val paired: Boolean get() = arms != null
    /** 방금 완료된 회의 ROM 판정(팔별 경로 — 본인 기준 비율). 레거시·새 코어 경로는 null(`RepSignal.isValidRep` 가 판정). */
    var lastCycleValid: Boolean? = null
        private set
    /** 팔별 경로에서 이 프레임에 완료된 회들의 ROM 판정 — [newlyPublished] 와 같은 순서·길이. */
    var newlyPublishedValid: List<Boolean?> = emptyList()
        private set
    /** 팔별 경로에서 이 프레임에 완료된 회들의 ROM 미달 사유 — [newlyPublishedValid] 와 같은 순서·길이, 미달이 아니면 null. 부분 회의 음성 사유(`PostureLive`). */
    var newlyPublishedShort: List<RomShort?> = emptyList()
        private set
    /** 팔별 경로에서 각 팔이 낸 사이클 전부(회로 묶이기 전·기각 포함) — 세트 로그 `reps.arms`. [repTimesMs] 와 같은 락 안에서 복사한다. */
    val armCycles = ArrayList<ArmCycle>()
    private val armVirtual = IntArray(2)                          // 안 보이는 팔 대신 센 회
    private val armLastSeen = LongArray(2) { Long.MIN_VALUE / 2 }
    private val armAmps = arrayOf(ArrayList<Float>(), ArrayList<Float>())   // 기준 확보용 첫 사이클 진폭
    private val armRef = arrayOf<Float?>(null, null)              // A0 (NaN = 기준 미확보로 확정)
    private val armQueue = arrayOf(ArrayDeque<ArmCycle>(), ArrayDeque<ArmCycle>())      // 아직 회로 묶이지 않은 팔 사이클
    private val rejectSamples = HashMap<String, ArrayList<Pair<Long, Float>>>()
    private val auxSamples = arrayOf(ArrayList<Pair<Long, Float>>(), ArrayList<Pair<Long, Float>>())   // 보조 ROM 피처(팔별)
    private val auxAmps = arrayOf(ArrayList<Float>(), ArrayList<Float>())
    private val auxRef = arrayOf<Float?>(null, null)
    private var pairGateAt = Long.MIN_VALUE / 2
    private var pairsSeen = 0                                     // 센 회 + 기각한 회
    /**
     * 첫 회 잠정(§62c 후속 9) — 팔별 자식의 시작 확정을 걷어낸 대신 **회 단위**로: 첫 회를 바로 세되 [FIRST_REP_CONFIRM_MS] 안에 둘째 회가 없으면 거둔다.
     * 준비 동작 한 번(MM-Fit 세트 첫머리 진폭 39~98° 사이클 뒤 10~16 s 쉼)은 세트의 시작이 아니다. 팔마다 확정하면 회 = 두 팔 짝이라 화면이 0 → 2 로 뛰고
     * 가려졌던 팔이 다시 보일 때마다 회가 늦게 한꺼번에 올랐다. MM-Fit 59세트: 팔별 확정 세트 정확 0.76, 확정 없음 0.66, 회 단위 8 s 0.73(±1 셋 다 0.92).
     */
    private var tentativeFirstAt: Long? = null
    /** 이 프레임에 첫 회를 거뒀다 — 앱·재생기가 그 회로 센 수·기록·자세 기준을 지운다. */
    var newlyRetracted: Boolean = false
        private set
    /** 거둔 첫 회의 시각(로그 `reps.retracted`). */
    val retractedReps = ArrayList<Long>()
    /** 짝 없이 버린 팔 사이클(조각) 수 — 로그용. 그 사이클은 [armCycles] 에 `orphan` 으로 남는다. */
    var armOrphans = 0
        private set

    /** 최근 렙 주기(ms) 지수평활 추정 — 빠른 렙 자가진단·적응 샘플링 신호. */
    var periodMs: Long? = null
        private set

    private val raw3 = FloatArray(3)
    private var rawCount = 0
    private var dirn = 0                     // 0=초기, -1=하강 추적, +1=상승 추적
    private var ext = Float.NaN              // 현재 방향의 극값 후보
    private var extT = 0L
    private var pendingBottom = Float.NaN    // 확정된 하단 (상단 확정 시 렙으로 승격)
    private var lastRepAt = Long.MIN_VALUE / 2
    private var prevT: Long? = null
    private var dtMs: Float? = null          // 샘플 간격 지수평활 (평활 모드 판단)

    fun reset() {
        returnTracker?.reset()
        hysteresis?.reset()
        confirmation?.reset()
        newlyPublished = emptyList()
        reps = 0
        repTimesMs.clear()
        periodMs = null
        rawCount = 0
        dirn = 0
        ext = Float.NaN
        pendingBottom = Float.NaN
        lastRepAt = Long.MIN_VALUE / 2
        prevT = null
        dtMs = null
        lastCycleMin = Float.NaN
        lastCycleMax = Float.NaN
        rejectedReps.clear()
        identitySwings.clear()
        identitySamples.clear()
        identityGateAt = Long.MIN_VALUE / 2
        arms?.forEach { it.reset() }
        lastCycleValid = null; newlyPublishedValid = emptyList(); newlyPublishedShort = emptyList()
        armCycles.clear()
        armVirtual.fill(0); armLastSeen.fill(Long.MIN_VALUE / 2)
        armAmps.forEach { it.clear() }; armRef.fill(null); armQueue.forEach { it.clear() }
        rejectSamples.clear(); pairGateAt = Long.MIN_VALUE / 2; pairsSeen = 0
        auxSamples.forEach { it.clear() }; auxAmps.forEach { it.clear() }; auxRef.fill(null)
        armOrphans = 0
        tentativeFirstAt = null; newlyRetracted = false; retractedReps.clear()
    }

    /**
     * 일시정지·카메라 재배치 전후를 한 반복으로 잇지 않는다. 완료(발표)한 수는 유지한다.
     * 새 코어: 진행 중 사이클과 **확정 대기(보류) 사이클**을 함께 버린다(버린 사이클은 [droppedReps] 로 — 로그용).
     * 보류를 남기면 리셋 앞의 한 번이 리셋 뒤 첫 사이클과 짝지어 +2 로 발표돼, 사람이 멈췄던 경계를 넘어 센다(설계 §4.7, 원칙 #1).
     * 끊김(검출 공백 > maxGap)은 이 함수가 아니다 — 코어가 진행 중 사이클만 버리고 보류의 수명은 짝 창이 정한다(프로토타입·배터리와 같다).
     */
    fun resetCycle() {
        returnTracker?.resetCycle()
        hysteresis?.resetCycle()
        confirmation?.dropPending()
        dirn = 0; ext = Float.NaN; pendingBottom = Float.NaN; rawCount = 0; dtMs = null; prevT = null
        identitySamples.clear()   // 리셋 앞의 판별 샘플을 리셋 뒤 첫 사이클 창에 섞지 않는다
        arms?.forEach { it.resetCycle() }
        rejectSamples.clear()     // 기각 창도 같다. 반대 팔을 기다리는 사이클(armQueue)은 이미 낸 실제 동작이라 버리지 않는다(RepUnitAccumulator 와 같은 결정)
        tentativeFirstAt = null   // 일시정지·재배치 앞에서 이미 보인 첫 회는 그대로 둔다(쉬는 동안 거두지 않는다)
    }

    /**
     * 프레임 피처 사전으로 한 프레임 처리 — `PostureLive`·재생기가 부르는 입구. 팔별 경로가 아니면 [onFrame] 과 같다
     * (카운트 신호 값 + 판별 신호 값). 팔별 경로(spec §62c):
     *  1) 기각 피처 표본을 모은다  2) 두 팔에 각자 값을 준다(가려진 팔은 null = 일시정지)  3) 팔이 사이클을 내면 [armCycles] 에 남기고 그 팔의
     *  ROM(본인 기준 비율)을 판정한다  4) min(nL + 가상, nR + 가상) 이 늘면 회 하나를 완료 — 기각 게이트(창 스윙)를 지나면 센다.
     * @return 이 프레임에서 회가 완료됐으면 true(팔별 경로에서는 한 프레임에 최대 한 회).
     */
    fun onFrameFeatures(tMs: Long, features: Map<String, Float>): Boolean {
        val a = arms ?: return onFrame(tMs, features[signal.feature], signal.identityFeature?.let { features[it] })
        lastCycleValid = null; newlyPublished = emptyList(); newlyPublishedValid = emptyList(); newlyPublishedShort = emptyList()
        newlyRetracted = false
        tentativeFirstAt?.let { t0 -> if (reps == 1 && tMs - t0 > FIRST_REP_CONFIRM_MS) retractFirst() }
        for ((tpl, _) in signal.rejectFeatures) for (f in if ("{side}" in tpl) listOf(tpl.replace("{side}", "L"), tpl.replace("{side}", "R")) else listOf(tpl)) {
            val v = features[f] ?: continue
            if (!v.isFinite()) continue
            val list = rejectSamples.getOrPut(f) { ArrayList() }
            list += tMs to v
            if (list.size > IDENTITY_SAMPLE_CAP) list.subList(0, IDENTITY_SAMPLE_CAP / 2).clear()
        }
        signal.romAuxFeature?.let { tpl ->
            for (i in 0..1) {
                val v = features[tpl.replace("{side}", if (i == 0) "L" else "R")] ?: continue
                if (!v.isFinite()) continue
                auxSamples[i] += tMs to v
                if (auxSamples[i].size > IDENTITY_SAMPLE_CAP) auxSamples[i].subList(0, IDENTITY_SAMPLE_CAP / 2).clear()
            }
        }
        val (fl, fr) = signal.pairedFeatures!!
        val fired = BooleanArray(2)
        for (i in 0..1) {
            val v = features[if (i == 0) fl else fr]
            if (v != null && v.isFinite()) armLastSeen[i] = tMs
            if (!a[i].onFrame(tMs, v)) continue
            // 자식(새 코어)은 첫 두 사이클을 한 프레임에 함께 발표한다 — 발표된 사이클마다 하나씩
            for (c in a[i].newlyPublished) {
                val amp = c.amplitude
                val side = if (i == 0) 'L' else 'R'
                // 기각 게이트는 팔 사이클 단위(그 팔의 구간 [startMs, tMs], "{side}" 자리엔 그 팔) — 두 팔 구간을 합치면 교대 컬의 정상 반복이 걸린다(MM-Fit 재생: 세트 정확 0.66)
                val reject = rejectFor(side, c.startMs, c.tMs)
                // 보조 창은 팔 사이클 창 그대로 [startMs, tMs]. 새 코어 사이클은 복귀 75 % 지점에서 끝나 그 회의 완전 신전(손목 최저)은 창 뒤에 남지만, 창의 앞 끝(하강 직전)이
                // 직전 매달린 자세라 아래 끝 판정은 "이 회가 매달린 자세에서 시작했는가" 가 된다. 앞 여유(1 s)를 두면 첫 회 창에 덤벨을 드는 동작이 들어가 기준이 부풀고
                // (MM-Fit 재생 2026-09-26: 손목 기준 1.1~2.6 vs 이후 회 0.7~1.0, 부분 오탐 33건) 그 뒤 정상 회가 전부 '부분' 이 된다 — 쓰지 않는다
                val aux = auxWindow(i, c.startMs, c.tMs)
                // 월드 진폭 판정과 보조(2D 손목) 판정의 결합: 한쪽이 미판정이면 다른 쪽을 따르고, 둘 다 있으면 둘 다 충족해야 유효
                val worldV = armRomValid(i, amp); val auxV = auxRomValid(i, aux)
                val valid = when { auxV == null -> worldV; worldV == null -> auxV; else -> worldV && auxV }
                // 미달 사유: 손목이 아래 끝에 못 닿았으면 '덜 폄', 아래 끝은 닿았는데 손목 진폭이 모자라면 '덜 올림', 월드 진폭만 모자라면 어느 끝인지 모른다
                val short = when {
                    valid != false -> null
                    aux != null && signal.romAuxFloor != null && aux.second > signal.romAuxFloor -> RomShort.BOTTOM
                    auxV == false -> RomShort.TOP
                    else -> RomShort.RANGE
                }
                val ac = ArmCycle(side, c.tMs, c.startMs, c.min, c.max, amp, valid, reject?.first, reject?.second, aux?.first, aux?.second, romShort = short)
                armCycles += ac; armQueue[i].addLast(ac); fired[i] = true
            }
        }
        // 먼 팔 가림(동시 컬): 한 팔이 ARM_ABSENT_MS 넘게 안 보이면 그 회는 보이는 팔로 센다 — 짝을 기다리는 보이는 팔 사이클 중 **반대 팔이 마지막으로 보인 뒤에
        // 끝난 것**(그 팔은 그동안 안 보여 짝 사이클을 낼 수 없었다)마다 복사본을 반대 팔 자리에 둔다. 반대 팔이 이 세트에서 아직 사이클을 한 번도 내지 않았으면
        // (처음부터 가려진 먼 팔) 기다리던 사이클 전부. 매 프레임 본다(§62c 후속 9) — 사이클이 난 프레임에만 붙이면, 반대 팔이 잠깐 보이던 때 끝난 사이클은
        // 짝을 못 얻고 다음 사이클의 복사본에 밀려 조각으로 버려졌다(12:38 세트 첫 컬 100°, 12:36 세트 88~90 s 컬 101° — 팔별 시작 확정이 사이클을 늦게
        // 함께 발표하던 때는 우연히 가려졌다). 반대 팔이 보이던 동안 끝난 사이클에는 붙이지 않는다 — 그 팔은 보였는데 굽히지 않았다(한 팔 조각), 그리고
        // 기다리던 사이클 전부에 붙이면 잠깐 가려졌다 돌아온 팔의 늦은 사이클과 겹쳐 두 번 셌다(11:54 세트 21 → 25)
        var addedVirtual = false
        for (i in 0..1) {
            val o = 1 - i
            if (tMs - armLastSeen[o] <= ARM_ABSENT_MS) continue
            val oSide = if (o == 0) 'L' else 'R'
            val neverCycled = armCycles.none { it.arm == oSide }
            val waiting = (armQueue[i].size - armQueue[o].size).coerceAtLeast(0)
            for (c in armQueue[i].toList().takeLast(waiting)) {
                if (!neverCycled && c.tMs <= armLastSeen[o]) continue
                armVirtual[o]++; armQueue[o].addLast(c.copy(arm = oSide)); addedVirtual = true
            }
        }
        if (!fired[0] && !fired[1] && !addedVirtual) return false
        val published = ArrayList<RepCycle>(2); val valids = ArrayList<Boolean?>(2); val shorts = ArrayList<RomShort?>(2)
        var counted = false
        while (armQueue[0].isNotEmpty() && armQueue[1].isNotEmpty()) {
            val cl = armQueue[0].first(); val cr = armQueue[1].first()
            // 짝 판정은 쌍마다(spec §62c). 두 팔 사이클 창이 겹치면 동시 컬의 한 회. 안 겹치면 먼저 끝난 팔의 다음 사이클을 본다 — 그것이 반대 팔 사이클과
            // 겹치면 앞선 것은 짝 없는 조각(한 팔의 이중 굴곡: 폰 2026-09-25 15:34 세트 9회 왼팔 45.9°)이라 버리고, 아직 돌아오는 중이면 기다린다.
            // 어느 쪽도 아니면 교대 컬(왼 다음 오른)이라 순서대로 짝짓는다. 세트 단위 규약(첫 쌍으로 동시/교대 확정)은 못 쓴다 — MM-Fit 교대 컬 13세트의
            // 첫 쌍이 100 % 겹쳤고(첫 사이클 창이 세트 시작부터), 폰 세트 하나는 교대에서 동시로 바꿨다. 조각을 순서로 짝지으면 그 뒤 모든 회의 왼·오른이
            // 한 사이클씩 어긋난다(13회 세트의 부분 2회가 0회로)
            if (overlapRatio(cl, cr) < PAIR_OVERLAP_MIN) {
                val e = if (cl.tMs <= cr.tMs) 0 else 1                  // 먼저 끝난 쪽
                val late = if (e == 0) cr else cl
                val next = armQueue[e].elementAtOrNull(1)
                if (next != null) {
                    if (overlapRatio(next, late) >= PAIR_OVERLAP_MIN) {
                        val orphan = armQueue[e].removeFirst()
                        val k = armCycles.indexOfLast { it === orphan }
                        if (k >= 0) armCycles[k] = orphan.copy(orphan = true)
                        armOrphans++
                        continue
                    }
                } else {
                    // 같은 팔의 다음 사이클이 반대 팔 사이클의 전반부에 시작해 아직 돌아오는 중이면(동시 컬에서 한 프레임 늦는 팔) 그 사이클이 끝날 때 다시 본다
                    val ps = a[e].pendingAtSetEnd()
                    val ipStart = ps.inProgress?.startMs ?: ps.unconfirmed?.startMs
                    if (ipStart != null && ipStart < late.startMs + (late.tMs - late.startMs) / 2) break
                }
            }
            armQueue[0].removeFirst(); armQueue[1].removeFirst()
            pairsSeen++
            val valid = combineValid(cl.valid, cr.valid)
            val late = if (cr.tMs >= cl.tMs) cr else cl          // 회를 완성한(늦은) 팔의 사이클이 회의 극값·시각
            pairGateAt = late.tMs
            // 어느 팔 사이클이든 기각됐으면 그 회는 '이 종목이 아님'
            val bad = listOf(cl, cr).firstOrNull { it.rejectFeature != null }
            if (bad != null) { rejectedReps += RepRejected(late.tMs, bad.min, bad.max, bad.rejectSwing ?: 0f, bad.rejectFeature); continue }
            if (lastRepAt > Long.MIN_VALUE / 4) {
                val p = late.tMs - lastRepAt
                periodMs = periodMs?.let { (it + p) / 2 } ?: p
            }
            reps++; repTimesMs.add(late.tMs); lastRepAt = late.tMs
            if (reps == 1) tentativeFirstAt = late.tMs else tentativeFirstAt = null     // 첫 회는 잠정, 둘째 회가 창 안에 오면 확정
            lastCycleMin = late.min; lastCycleMax = late.max; lastCycleValid = valid
            // 회의 시작 = 먼저 시작한 팔의 사이클 시작(§62c 후속 10 — 반복 검사 창의 앞 경계). 극값·끝 시각은 늦은 팔
            published += RepCycle(late.tMs, minOf(cl.startMs, cr.startMs), late.min, late.max); valids += valid
            // 두 팔 사유를 한 회로: 덜 폄 > 덜 올림 > 불명(덜 폄은 손목이 직접 보여 준 것이라 가장 확실하다)
            shorts += if (valid != false) null else listOfNotNull(cl.romShort, cr.romShort).let { r ->
                when { RomShort.BOTTOM in r -> RomShort.BOTTOM; RomShort.TOP in r -> RomShort.TOP; else -> RomShort.RANGE }
            }
            counted = true
        }
        for (i in 0..1) auxSamples[i].removeAll { it.first < tMs - REJECT_KEEP_MS }
        // 기각 표본은 두 팔이 모두 지나간 구간만 버린다(반대 팔 사이클이 아직 그 구간을 볼 수 있다)
        val keepFrom = minOf(armQueue[0].firstOrNull()?.startMs ?: Long.MAX_VALUE, armQueue[1].firstOrNull()?.startMs ?: Long.MAX_VALUE, tMs - REJECT_KEEP_MS)
        rejectSamples.values.forEach { l -> l.removeAll { it.first < keepFrom } }
        newlyPublished = published; newlyPublishedValid = valids; newlyPublishedShort = shorts
        return counted
    }

    /**
     * 기각 게이트(spec §62c) — 팔 [side] 의 사이클 구간 [[startMs], [endMs]] 에서 기각 피처("{side}" 는 그 팔로) 스윙이 상한을 넘으면 (피처, 스윙).
     * 표본 2개 미만이면 판정하지 않는다(원칙 #1).
     */
    private fun rejectFor(side: Char, startMs: Long, endMs: Long): Pair<String, Float>? {
        for ((tpl, maxSwing) in signal.rejectFeatures) {
            val f = tpl.replace("{side}", side.toString())
            val list = rejectSamples[f] ?: continue
            var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY; var n = 0
            for ((t, v) in list) if (t >= startMs && t <= endMs) { if (v < lo) lo = v; if (v > hi) hi = v; n++ }
            if (n >= 2 && hi - lo > maxSwing) return f to (hi - lo)
        }
        return null
    }

    /**
     * 잠정 첫 회를 거둔다 — 세트가 아직 시작되지 않았던 것이다. 그 회와 그 회로 세운 본인 기준(팔 ROM·보조 ROM)을 지운다
     * (준비 동작이 기준이 되면 그 뒤 정상 회가 부분으로 읽힌다). 팔 사이클 기록([armCycles])은 로그용이라 남긴다.
     */
    private fun retractFirst() {
        repTimesMs.firstOrNull()?.let { retractedReps += it }
        reps = 0; repTimesMs.clear(); lastRepAt = Long.MIN_VALUE / 2; periodMs = null
        lastCycleMin = Float.NaN; lastCycleMax = Float.NaN
        armAmps.forEach { it.clear() }; armRef.fill(null); auxAmps.forEach { it.clear() }; auxRef.fill(null)
        tentativeFirstAt = null; newlyRetracted = true
    }

    /** 두 팔 사이클 창의 겹침 비 — 겹친 길이 ÷ 짧은 창 길이(0~1). 동시 컬의 짝 판정. */
    private fun overlapRatio(x: ArmCycle, y: ArmCycle): Float {
        val ov = (minOf(x.tMs, y.tMs) - maxOf(x.startMs, y.startMs)).coerceAtLeast(0L)
        val shorter = minOf(x.tMs - x.startMs, y.tMs - y.startMs).coerceAtLeast(1L)
        return ov.toFloat() / shorter
    }

    /** 팔 [arm] 의 보조 ROM 피처 창 (진폭, 최소) — 표본 2개 미만이면 null. */
    private fun auxWindow(arm: Int, startMs: Long, endMs: Long): Pair<Float, Float>? {
        if (signal.romAuxFeature == null) return null
        var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY; var n = 0
        for ((t, v) in auxSamples[arm]) if (t >= startMs && t <= endMs) { if (v < lo) lo = v; if (v > hi) hi = v; n++ }
        return if (n >= 2) (hi - lo) to lo else null
    }

    /**
     * 보조 ROM 판정(spec §62c B4): 창 진폭 ≥ [RepSignal.romAuxRatio] × 그 팔 첫 [RepSignal.romRefCycles] 창 진폭 중앙값 이고 창 최소 ≤ [RepSignal.romAuxFloor].
     * 기준 전(첫 사이클들)·표본 없음 = null. 기준을 이루는 사이클도 아래 끝은 판정한다.
     */
    private fun auxRomValid(arm: Int, aux: Pair<Float, Float>?): Boolean? {
        val ratio = signal.romAuxRatio ?: return null
        if (aux == null) return null
        val (amp, lo) = aux
        val floorOk = signal.romAuxFloor?.let { lo <= it } ?: true
        val ref = auxRef[arm]
        if (ref == null) {
            val amps = auxAmps[arm]
            amps += amp
            if (amps.size >= signal.romRefCycles) auxRef[arm] = amps.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f }
            return if (floorOk) null else false
        }
        return amp >= ratio * ref && floorOk
    }

    /** 팔 [arm] 의 사이클 진폭 [amp] 를 본인 기준 비율 ROM 으로 판정한다. 기준 전·기준 미확보·비율 ROM 없음 = null. */
    private fun armRomValid(arm: Int, amp: Float): Boolean? {
        val ratio = signal.romRatio ?: return null
        val ref = armRef[arm]
        if (ref == null) {
            val amps = armAmps[arm]
            amps += amp
            if (amps.size < signal.romRefCycles) return null
            val a0 = amps.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f }
            armRef[arm] = if (signal.romRefMin != null && a0 < signal.romRefMin) Float.NaN else a0
            return null                                   // 기준을 이룬 사이클 자신은 판정하지 않는다(이미 셌다)
        }
        if (ref.isNaN()) return null
        return amp >= maxOf(ratio * ref, signal.romAbsMin ?: 0f)
    }

    /** 두 팔 사이클의 ROM 판정을 한 회로: 미달이 하나라도 있으면 false, 아니고 미판정이 있으면 null, 아니면 true(`RepUnitAccumulator.combine` 과 같은 규약 — 재생기는 RepUnit.kt 를 컴파일하지 않는다). */
    private fun combineValid(l: Boolean?, r: Boolean?): Boolean? = when {
        l == false || r == false -> false
        l == null || r == null -> null
        else -> true
    }

    /** 팔별 경로의 본인 기준 진폭(왼, 오른) — 화면·로그용. 기준 전은 null, 미확보(첫 사이클이 너무 얕음)는 NaN. */
    val armReference: Pair<Float?, Float?> get() = armRef[0] to armRef[1]

    /**
     * @param identity 반복 판별 신호(`signal.identityFeature`)의 이 프레임 값. 판별 신호가 없는 종목이거나 이 프레임에서 계산되지 않았으면 null —
     *   재생기·테스트의 기존 두 인자 호출은 그대로 컴파일된다(판별 없이 종전과 같이 센다).
     * @return 이 프레임에서 렙이 완료됐으면 true. value=null(가림)·물리범위 밖이면 일시정지.
     */
    fun onFrame(tMs: Long, value: Float?, identity: Float? = null): Boolean {
        if (hysteresis != null) newlyPublished = emptyList()
        if (signal.identityFeature != null && identity != null && identity.isFinite()) {
            identitySamples += tMs to identity
            if (identitySamples.size > IDENTITY_SAMPLE_CAP) identitySamples.subList(0, IDENTITY_SAMPLE_CAP / 2).clear()
        }
        if (value == null || !value.isFinite()) return false
        val plo = signal.plausibleMin
        val phi = signal.plausibleMax
        if ((plo != null && value < plo) || (phi != null && value > phi)) return false
        if (hysteresis != null) return onFrameHysteresis(tMs, value, hysteresis, confirmation)

        // 바닥 경로에서는 가림·일시정지 전후를 한 반복으로 이어 세지 않는다.
        if (prevT?.let { tMs - it > maxGapMs || tMs <= it } == true) {
            returnTracker?.resetCycle()
            dirn = 0; ext = Float.NaN; pendingBottom = Float.NaN; rawCount = 0; dtMs = null
            identitySamples.removeAll { it.first < tMs }   // 끊김 앞의 판별 샘플도 버린다(이 프레임 것은 새 창의 첫 샘플)
        }
        prevT?.let { p ->
            val d = (tMs - p).toFloat()
            if (d > 0f && d < 2_000f) dtMs = dtMs?.let { it * 0.7f + d * 0.3f } ?: d
        }
        prevT = tMs
        raw3[rawCount % 3] = value
        rawCount++
        // 평활: 촘촘한 샘플링(≤350ms)일 때만 — 성긴 신호 평활은 렙 꼭대기를 지운다 (AIHub 실측)
        val v = if (rawCount >= 3 && (dtMs ?: 999f) <= 350f) median3(raw3) else value

        returnTracker?.let { tracker ->
            val cycle = tracker.onFrame(tMs, v) ?: return false
            if (!identityAdmits(tMs, cycle.min, cycle.max)) return false
            if (lastRepAt > Long.MIN_VALUE / 4) {
                val p = tMs - lastRepAt
                periodMs = periodMs?.let { (it + p) / 2 } ?: p
            }
            reps++; repTimesMs.add(tMs); lastRepAt = tMs
            lastCycleMin = cycle.min; lastCycleMax = cycle.max
            return true
        }

        if (ext.isNaN()) {
            ext = v
            extT = tMs
            return false
        }
        val h = signal.minAmp
        if (dirn <= 0) {                                   // 하강 추적(또는 초기)
            if (v < ext) {
                ext = v; extT = tMs
            } else if (v - ext >= h) {                     // 반등이 h 를 넘음 → 하단 확정
                pendingBottom = ext
                dirn = 1
                ext = v; extT = tMs
            }
        } else {                                           // 상승 추적
            if (v > ext) {
                ext = v; extT = tMs
            } else if (ext - v >= h) {                     // 하락이 h 를 넘음 → 상단 확정
                var fired = false
                if (!pendingBottom.isNaN() && tMs - lastRepAt >= refractoryMs) {
                    if (identityAdmits(tMs, pendingBottom, ext)) {
                        if (lastRepAt > Long.MIN_VALUE / 4) {
                            val p = tMs - lastRepAt
                            periodMs = periodMs?.let { (it + p) / 2 } ?: p
                        }
                        reps++
                        repTimesMs.add(extT)
                        lastCycleMin = pendingBottom
                        lastCycleMax = ext
                        lastRepAt = tMs
                        fired = true
                    }
                    pendingBottom = Float.NaN              // 카운트·기각된 하단만 소거 — 불응기 기각 시엔 유지
                }
                dirn = -1
                ext = v; extT = tMs
                if (fired) return true
            }
        }
        return false
    }

    /** 새 코어 경로 — 원값 그대로(평활 없음). 레거시 필드(raw3·dtMs·dirn 등)는 건드리지 않는다. */
    private fun onFrameHysteresis(tMs: Long, value: Float, core: RepHysteresis, confirm: RepStartConfirmation?): Boolean {
        val cycle = core.onFrame(tMs, value) ?: return false
        // 시작 확정이 없으면(컬 팔별 자식) 사이클을 바로 발표한다
        val published = confirm?.offer(cycle) ?: listOf(cycle)
        if (published.isEmpty()) return false
        // 판별 게이트는 발표된 사이클마다 — 함께 발표된 첫 두 사이클 중 하나만 기각될 수 있다. `confirmation.published`(→ [publishedReps])에는
        // 기각된 사이클도 남는다(코어의 발표 기록). 세는 것은 여기서 통과한 사이클뿐이다.
        val out = published.filter { identityAdmits(it.tMs, it.min, it.max) }
        if (out.isEmpty()) return false
        for (c in out) {
            if (lastRepAt > Long.MIN_VALUE / 4) {
                val p = c.tMs - lastRepAt
                periodMs = periodMs?.let { (it + p) / 2 } ?: p
            }
            reps++
            repTimesMs.add(c.tMs)
            lastRepAt = c.tMs
        }
        val newest = out.last()
        lastCycleMin = newest.min
        lastCycleMax = newest.max
        newlyPublished = out
        return true
    }

    /**
     * 반복 판별 게이트 — 카운트 신호가 낸 사이클(끝 [endMs])을 셀지 정한다. 판별 신호가 없는 종목은 늘 true.
     * 창 = (직전에 판별한 사이클의 끝, endMs]. 창의 판별 샘플이 2개 미만이면 판정하지 않고 true(스윙 null 로 기록).
     * 기각된 사이클은 [rejectedReps] 에, 센 사이클의 스윙은 [identitySwings] 에 남긴다. 어느 쪽이든 창은 소비된다.
     */
    private fun identityAdmits(endMs: Long, cycleMin: Float, cycleMax: Float): Boolean {
        val h = signal.identityMinAmp
        if (signal.identityFeature == null || h == null) return true
        var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY; var n = 0
        for ((t, v) in identitySamples) if (t > identityGateAt && t <= endMs) { if (v < lo) lo = v; if (v > hi) hi = v; n++ }
        identityGateAt = endMs
        identitySamples.removeAll { it.first <= endMs }
        val swing = if (n >= 2) hi - lo else null
        if (swing != null && swing < h) {
            rejectedReps += RepRejected(endMs, cycleMin, cycleMax, swing)
            return false
        }
        identitySwings += swing
        return true
    }

    companion object {
        /** 판별 샘플 상한(300 ms 샘플링 10분). 넘으면 앞 절반을 버린다 — 사이클이 한 번도 안 난 긴 세트에서 무한히 쌓이지 않게. */
        private const val IDENTITY_SAMPLE_CAP = 2_000
        /** 팔별 경로에서 한 팔이 이보다 오래 안 보이면(피처 없음) 다른 팔의 사이클을 그 회로 센다 — 폰 1세트에서 오른팔이 8 s 미검출(B2). */
        const val ARM_ABSENT_MS = 2_500L
        /** 기각 표본 보관 하한(ms) — 반대 팔 사이클이 늦게 발표돼도 자기 구간을 볼 수 있게 이만큼은 남긴다. */
        const val REJECT_KEEP_MS = 10_000L
        /** 팔별 경로 첫 회 잠정 창(ms) — 첫 회 뒤 이 안에 둘째 회가 없으면 첫 회를 거둔다. 새 코어 시작 확정의 첫 짝 창과 같은 값. */
        const val FIRST_REP_CONFIRM_MS = 8_000L
        /** 두 팔 사이클이 한 회(동시 컬)로 묶이는 창 겹침 하한(짧은 창 대비). 이보다 덜 겹치면 순서(교대 컬)로 짝짓되, 다음 사이클이 이만큼 겹치면 앞선 것은 조각. */
        const val PAIR_OVERLAP_MIN = 0.5f
        /** 종목에 카운터가 정의돼 있고 등척성이 아니면 생성 (플랭크 등은 HoldTimer 대상 — 카운터 미적용). */
        fun forExercise(exercise: String): RepCounter? {
            val sig = RepSignals.byExercise[exercise] ?: return null
            return if (sig.isometric) null else RepCounter(sig)
        }

        /**
         * 세션 카운터 — `PostureLive` 가 세트마다 만드는 구성을 한 곳에 모은 순수 함수.
         * 재생기(`research/external_rep_replay/replay-jvm`)가 같은 함수를 불러 "앱과 같은 구성" 을 구조로 보장한다.
         *
         *  - 규칙 JSON 에 kind=rep 규칙의 rep 설정이 있으면 그 ROM 방향·임계값으로 덮는다(검증 표시는 끈다).
         *  - 없고 바닥 종목이면 ROM 을 뗀다(바닥 ROM 은 규칙 설정이 있을 때만 쓴다).
         *  - 서서 하는 종목은 등록부 신호 그대로.
         * 전부 maxGapMs = 1500, completeOnReturn = true. 새 코어 사용 여부는 신호의 polarity 가 정한다.
         *
         * `RepRuleConfig` 는 FloorTemporal.kt 에 있어 재생기가 컴파일하지 않으므로 방향·임계값을 원시값으로 받는다.
         * 둘 다 있어야 규칙 설정으로 본다(`RepRuleConfig` 의 두 필드는 null 이 될 수 없다).
         *
         * @return 등록부에 없거나 등척성 종목이면 null (= `forExercise` 가 null 인 경우와 같다).
         */
        fun forSession(exercise: String, ruleRomDirection: String? = null, ruleRomThreshold: Float? = null, floor: Boolean): RepCounter? {
            val sig = RepSignals.byExercise[exercise] ?: return null
            if (sig.isometric) return null
            val signal = when {
                ruleRomDirection != null && ruleRomThreshold != null ->
                    sig.copy(romDirection = ruleRomDirection, romThreshold = ruleRomThreshold, romValidated = false)
                floor -> sig.copy(romThreshold = null, romDirection = null, romValidated = false)
                else -> sig
            }
            return RepCounter(signal, maxGapMs = 1500L, completeOnReturn = true)
        }

        private fun median3(a: FloatArray): Float {
            val x = a[0]; val y = a[1]; val z = a[2]
            return maxOf(minOf(x, y), minOf(maxOf(x, y), z))
        }
    }
}

/** 종목별 렙 신호. minAmp 는 물리 단위(각도=도, 거리=몸통 정규화). validated=실기기 라벨 검증 여부. */
data class RepSignal(
    val feature: String,
    val minAmp: Float,
    val isometric: Boolean = false,
    val validated: Boolean = false,
    /**
     * ROM(가동범위) 유효성 — "얕으면 무효 렙" (spec §27 수정판, REP_VALIDITY.md).
     * 임계값은 AIHub **전 조건 정상 클립**의 렙 극값 분포에서 기준 렙 90% 통과 분위수 —
     * 문장 기준을 좌표에 직역하면 정상 스쿼트 98% 가 실격이라, 기준은 반드시 데이터에서.
     * romDirection: "min"=사이클 하단 극값이 임계값 이하여야 유효, "max"=상단 극값이 이상.
     * romValidated: ROM 성격의 AIHub 조건으로 판별력 검증됨(위반 클립 무효율 2.8~3.8배) —
     * 검증 종목만 구체 사유(romCue)를 말하고, 나머지는 방향 중립 사유로.
     */
    val romDirection: String? = null,
    val romThreshold: Float? = null,
    val romValidated: Boolean = false,
    val romCue: String? = null,
    /** 팔별 경로의 미달 사유별 문구(spec §62c 후속 3) — 덜 올림 / 덜 폄. 없으면 [romCue]. */
    val romCueTop: String? = null,
    val romCueBottom: String? = null,
    /** 물리 타당 범위 (모집단: AIHub 프레임 분포) — 밖의 값은 측정 붕괴로 보고 일시정지. */
    val plausibleMin: Float? = null,
    val plausibleMax: Float? = null,
    /**
     * 새 카운터 코어(복귀 히스테리시스, `RepHysteresis`)를 쓸 때의 반복 방향. null = 레거시 경로 그대로.
     * 코어는 "휴식 = 신호의 휴식 쪽 끝" 을 가정하므로 방향을 모르는 신호에 켜면 마지막 반복을 잃는다 —
     * 그래서 종목별로 명시해 켜고(opt-in), 폰 라벨 검증 전에는 등록부 어느 종목에도 켜지 않는다.
     */
    val polarity: RepPolarity? = null,
    /**
     * TRACK 비교(`PostureComparison`)가 볼 신호. null = [feature]/[minAmp] 와 같다.
     * 카운트 신호를 바꿔도 비교 지표(초기 대비 변화)의 의미가 조용히 바뀌지 않게 둘을 떼어 둔다 — 비교 기준은
     * 사용자가 이미 쌓은 기록의 단위라, 카운트 개선이 기록의 단위를 바꾸면 안 된다.
     */
    val comparisonFeature: String? = null,
    val comparisonMinAmp: Float? = null,
    /**
     * 반복 판별 신호 (spec §62, 설계 §20) — 카운트 신호가 낸 사이클을 셀지 정하는 둘째 신호. null = 게이트 없음(종전과 같다).
     * 사이클 창 안에서 이 신호의 스윙이 [identityMinAmp] 미만이면 그 사이클은 이 종목의 반복이 아니다(세지 않고 로그).
     * 바벨 스쿼트: `knee_maxside`(더 편 쪽 무릎) 35° — 양 무릎이 함께 굽어야 스쿼트다. 게이트 값은 카운트 신호와 같은 모집단 상수(잡음 바닥)라
     * 새 상수를 만들지 않는다. 자세 규칙(무릎 방향·척추 등)은 판별 신호가 **아니다** — 자세 위반은 세트 판정·리포트로 간다.
     */
    val identityFeature: String? = null,
    val identityMinAmp: Float? = null,
    /**
     * 팔별(좌·우) 신호로 세는 종목(덤벨 컬, spec §62c·설계 §22): (왼쪽 피처, 오른쪽 피처). 자식 카운터 둘이 각자 사이클을 내고
     * **완료 = min(nL, nR) 증가** — 동시 컬은 사이클마다 1회, 교대 컬은 왼 + 오른 = 1회(런지 결정과 같은 단위)가 한 식으로 된다.
     * 두 팔 평균(`elbow_mean`)은 교대 컬에서 한 팔 스윙의 절반만 움직여 MM-Fit 교대 59세트 재현율 0.09 였다(B2).
     * 한 팔이 [RepCounter.ARM_ABSENT_MS] 넘게 안 보이는 동안 다른 팔이 사이클을 내면 그 회는 보이는 팔로 센다(동시 컬의 먼 팔 가림).
     * 판별 게이트([identityFeature])는 이 경로에서 쓰지 않고 [rejectFeatures] 가 그 자리다.
     */
    val pairedFeatures: Pair<String, String>? = null,
    /**
     * '이 종목이 아님' 기각 게이트(팔별 경로만) — 그 팔 사이클 구간에서 이 피처의 스윙(최대 − 최소)이 값을 **넘으면** 세지 않고
     * [RepCounter.rejectedReps] 에 남긴다. 컬: `torso_tilt2d` 0.30(≈ 24°, 몸통을 젖히거나 숙이며 들어 올림 — 사선 뷰에서만 정의되고 정면·측면이면
     * 키가 없어 판정하지 않는다. 월드 `torso_incl` 은 정면에서 45~110° 로 튀어 못 쓴다), `upperarm_vert_{side}` 60°(그 팔의 상완 대스윙 — 정상 p99 45°,
     * MM-Fit 정상 최대 55°, 폰 비컬 구간 91~115°; "{side}" 는 사이클을 낸 팔 L/R). 창은 **팔 사이클 구간**이다 — 두 팔 구간을 합치면 교대 컬 정상 반복이
     * 걸린다(MM-Fit 재생 세트 정확 0.66 → 팔 단위로). 자세 검사가 아니라 동작 판별이다(원칙 #7).
     */
    val rejectFeatures: Map<String, Float> = emptyMap(),
    /**
     * 본인 기준 비율 ROM(spec §62c, B2): 그 팔 첫 [romRefCycles] 사이클 진폭의 중앙값 A0 가 [romRefMin] 이상일 때 기준이 되고, 이후 사이클은
     * 진폭 ≥ max([romRatio]·A0, [romAbsMin]) 이면 유효, 아니면 '부분'. 기준 전·기준 미확보는 판정하지 않는다(null).
     * 절대 각 임계(옛 81.3°)는 GT 척도라 MediaPipe 월드각(수축 시 +33~39° 편향, 정답과 상관 0.04)에 못 쓴다 — 진폭(상관 0.56~0.73)만 신호다.
     * "같은 팔 첫 2회의 0.7" 이 정답 반복 94 % 유지(절대 각 69 %). 팔별 경로만.
     */
    val romRatio: Float? = null,
    val romAbsMin: Float? = null,
    val romRefMin: Float? = null,
    val romRefCycles: Int = 2,
    /** ROM 미달 회('부분')를 화면 횟수·목표 진행에서 뺀다 — 본인 기준 비율 ROM 이 있는 종목만. 미검증 절대 ROM 종목은 종전대로 센다. */
    val romExcludesShort: Boolean = false,
    /**
     * 보조 ROM 피처(팔별, "{side}" 는 L/R) — 그 팔 사이클 창의 **진폭(최대 − 최소)** 이 그 팔 첫 [romRefCycles] 창 진폭 중앙값의 [romAuxRatio] 미만이거나,
     * 창 **최소** 가 [romAuxFloor] 보다 크면(다 안 폄) 부분. 컬: 2D 손목 높이 `wrist_h2d_{side}`(B4 — 정면에서 월드 팔꿈치각 진폭보다 진실에 가깝다,
     * 비율 0.8 에서 정답 손실 2 %·폰 짧은 회 2/2, 아래 끝 −0.75 에서 정답 손실 0.5 %). 표본 2개 미만이면 판정하지 않는다.
     */
    val romAuxFeature: String? = null,
    val romAuxRatio: Float? = null,
    val romAuxFloor: Float? = null,
) {
    /** 팔별 경로인가. */
    val paired: Boolean get() = pairedFeatures != null

    /**
     * 비교용 신호 — [comparisonFeature]/[comparisonMinAmp] 가 있으면 그것으로 바꾼 사본, 없으면 자기 자신.
     * 피처가 바뀌면 ROM·물리 범위는 카운트 신호의 단위라 떼어 낸다(다른 피처에 붙이면 의미가 없다).
     */
    fun comparisonSignal(): RepSignal {
        // 판별 게이트(§62)는 그대로 둔다 — 비교 추적기는 onFrame 에 판별 값을 주지 않으므로(두 인자 호출) 게이트가 동작하지 않는다.
        // 여기서 사본을 만들면 "비교 신호 = 자기 자신" 을 assertSame 으로 잠근 테스트(PostureComparisonTest·RepHysteresisTest)가 깨진다.
        if (comparisonFeature == null && comparisonMinAmp == null) return this
        val f = comparisonFeature ?: feature
        val sameFeature = f == feature
        return copy(
            feature = f, minAmp = comparisonMinAmp ?: minAmp, polarity = null,
            comparisonFeature = null, comparisonMinAmp = null,
            identityFeature = null, identityMinAmp = null,   // 비교 지표는 사이클을 세지 않는다 — 판별 게이트는 카운트의 성질
            pairedFeatures = null, rejectFeatures = emptyMap(), romRatio = null, romAbsMin = null, romRefMin = null, romExcludesShort = false,
            romAuxFeature = null, romAuxRatio = null, romAuxFloor = null,
            romDirection = if (sameFeature) romDirection else null, romThreshold = if (sameFeature) romThreshold else null,
            romValidated = sameFeature && romValidated, romCue = if (sameFeature) romCue else null,
            romCueTop = null, romCueBottom = null,
            plausibleMin = if (sameFeature) plausibleMin else null, plausibleMax = if (sameFeature) plausibleMax else null,
        )
    }

    /**
     * 완료된 렙의 ROM 판정. null = 판정하지 않음(기준 없음·방향 불명) — 미달로 세지 않아 수는 줄지 않지만 '유효' 도 아니다
     * (화면은 '범위 미판정', spec §58). true/false 를 어떤 확신으로 말할지는 `RepRomTier` 가 정한다.
     */
    fun isValidRep(cycleMin: Float, cycleMax: Float): Boolean? {
        val thr = romThreshold ?: return null
        return when (romDirection) {
            "min" -> cycleMin <= thr
            "max" -> cycleMax >= thr
            else -> null
        }
    }

    val invalidCue: String
        get() = romCue ?: "동작 범위가 부족했어요. 끝까지 움직여 주세요"

    /** 미달 사유별 음성 문구 — 사유별 문구가 없으면 [invalidCue]. */
    fun shortCue(reason: RomShort?): String = when (reason) {
        RomShort.TOP -> romCueTop
        RomShort.BOTTOM -> romCueBottom
        else -> null
    } ?: invalidCue
}

/**
 * 렙 신호 등록부 (REP_SIGNALS.md 큐레이션).
 *
 * 바닥 종목은 기기 실측(푸시업류 4/4 적중) + 운동학 채택. 서서 종목은 AIHub 전수 설문(합의일치
 * 0.81~1.00) 승자를 **앱 가용 피처로 매핑**한 것 — 설문 승자 중 일부(hip_R, knee_fwd_mean,
 * elbow_h 등)는 앱 피처 집합에 없어 같은 패밀리/러너업으로 대체했다. 전부 beta: 실기기 라벨로
 * 확정 전까지 카운트는 참고용이며 ±1 오차를 약속에 포함하지 않는다.
 */
object RepSignals {
    // 새 카운터 코어(polarity)는 어느 종목에도 켜지 않는다 — 바벨 스쿼트·스텝 포워드 다이나믹 런지·덤벨 컬(모두 DOWN)이
    // 첫 대상이지만 폰 라벨 세트 검증(설계 §7 단계 4) 전이라 앱의 카운트 동작은 레거시 복귀형 그대로 둔다(원칙 #2).
    private const val ANGLE = 35f      // 각도형 게이트: 플랭크 유지 중 잡음 바닥(10~30°/5s) 위
    private const val NORM = 0.25f     // 몸통 정규화 거리형
    private const val NORM_S = 0.10f   // 작은 스케일 정규화형 (이탈·높이차)

    private data class Rom(val dir: String, val thr: Float, val validated: Boolean, val cue: String?)

    /**
     * ROM 유효성 임계값 (rep_validity_thresholds.py 산출, REP_VALIDITY.md).
     * AIHub 전 조건 정상 클립의 렙 극값 분포에서 기준 렙 90% 통과 분위수.
     * validated=true(5종목)는 ROM 성격의 AIHub 조건으로 판별력 검증됨 — 구체 사유 발화.
     * 나머지는 방향 자동판정이 수축 끝 대신 복귀 끝을 잡았을 수 있어 방향 중립 사유만.
     */
    private val ROM: Map<String, Rom> = mapOf(
        "푸시업" to Rom("min", 0.7101f, true, "얕았어요. 가슴을 더 내려 주세요"),
        "니푸쉬업" to Rom("min", 0.8377f, true, "얕았어요. 가슴을 더 내려 주세요"),
        "크런치" to Rom("max", 0.2953f, true, "덜 올라왔어요. 상체를 더 말아 올려 주세요"),
        "라잉 레그 레이즈" to Rom("min", 119.4981f, false, null),
        "힙쓰러스트" to Rom("min", -0.1618f, false, null),
        "Y - Exercise" to Rom("min", 0.1275f, false, null),
        "시저크로스" to Rom("max", 0.3932f, false, null),
        "바이시클 크런치" to Rom("max", 0.4310f, false, null),
        "바벨 데드리프트" to Rom("min", 102.2493f, false, null),
        "바벨 스티프 데드리프트" to Rom("min", 100.0967f, false, null),
        "굿모닝" to Rom("min", 109.2276f, false, null),
        "바벨 스쿼트" to Rom("min", 97.8905f, false, null),
        "버피 테스트" to Rom("min", 129.9882f, false, null),
        "크로스 런지" to Rom("min", 115.6725f, false, null),
        "바벨 런지" to Rom("min", 112.0852f, true, "무릎을 충분히 굽혀 주세요"),
        "사이드 런지" to Rom("min", 106.0722f, false, null),
        // 스텝 포워드 다이나믹 런지: ROM 없음 → 판정하지 않는다(isValidRep = null, 화면 '범위 미판정' — spec §58).
        // 옛 기준(knee_out_mean −0.0076)은 신호 교체와 함께 뗐다 — 아래 base() 의 신호 교체 주석 참고.
        "스텝 백워드 다이나믹 런지" to Rom("min", 141.7641f, false, null),
        "스탠딩 니업" to Rom("min", 142.7811f, false, null),
        "풀업" to Rom("min", 80.3965f, false, null),
        "딥스" to Rom("min", 93.9059f, true, "얕았어요. 더 내려가 주세요"),
        "바벨 로우" to Rom("min", 112.6863f, false, null),
        "덤벨 벤트오버 로우" to Rom("min", 113.4363f, false, null),
        "바벨 컬" to Rom("min", 66.4238f, false, null),
        // 덤벨 컬: 절대 ROM(81.3°, GT 척도)을 뗐다 — 팔별 본인 기준 비율 ROM 으로(base() 의 신호 정의, spec §62c)
        "페이스 풀" to Rom("min", 84.0116f, false, null),
        "랫풀 다운" to Rom("max", 19.2011f, false, null),
        "사이드 레터럴 레이즈" to Rom("min", 107.4791f, false, null),
        "프런트 레이즈" to Rom("min", 96.5170f, false, null),
        "업라이트로우" to Rom("min", 116.1889f, false, null),
        "덤벨 체스트 플라이" to Rom("max", 31.6000f, false, null),
        "덤벨 인클라인 체스트 플라이" to Rom("max", 29.0803f, false, null),
        "오버 헤드 프레스" to Rom("min", 0.4568f, false, null),
        "케이블 푸시 다운" to Rom("min", -0.7237f, false, null),
        "라잉 트라이셉스 익스텐션" to Rom("min", 0.4038f, false, null),
        "덤벨 풀 오버" to Rom("min", 0.2460f, false, null),
        "로잉머신" to Rom("min", -0.1773f, false, null),
        "행잉 레그 레이즈" to Rom("min", 0.1498f, false, null),
        "케이블 크런치" to Rom("min", 1.1342f, false, null),
    )

    val byExercise: Map<String, RepSignal> = base().mapValues { (ex, sig) ->
        ROM[ex]?.let { sig.copy(romDirection = it.dir, romThreshold = it.thr, romValidated = it.validated, romCue = it.cue) } ?: sig
    }

    private fun base(): Map<String, RepSignal> = buildMap {
        // ---- 바닥 (M0 재생 검증 — rep_replay.py SIGNALS 와 일치)
        put("푸시업", RepSignal("wrist_shoulder_d", 0.30f, validated = true, plausibleMin = 0.10f))
        put("니푸쉬업", RepSignal("wrist_shoulder_d", 0.30f, validated = true, plausibleMin = 0.10f))
        put("크런치", RepSignal("head_ground", 0.15f))
        put("라잉 레그 레이즈", RepSignal("hip_ang", 25f))
        put("힙쓰러스트", RepSignal("hip_dev_ankle", NORM_S))
        put("Y - Exercise", RepSignal("hand_shoulder_off", 0.20f))
        put("시저크로스", RepSignal("knee_gap2d", NORM))
        put("바이시클 크런치", RepSignal("knee_gap2d", NORM))
        put("플랭크", RepSignal("trunk_ankle_ang", ANGLE, isometric = true))
        // ---- 서서: 힙 힌지 (설문 hip_R 0.98~1.00 → 앱 가용 hip_mean)
        for (ex in listOf("바벨 데드리프트", "바벨 스티프 데드리프트", "굿모닝")) put(ex, RepSignal("hip_mean", ANGLE))
        // ---- 스쿼트·런지·버피 (무릎각 계열)
        // 바벨 스쿼트: 카운트는 양 무릎 평균, 판별은 더 편 쪽 무릎(spec §62). 평균만 보면 한쪽 무릎 들기(제자리 걷기)가 65° 를 흔들어 카운트된다
        // (2026-09-25 실기기 세트: 무릎 들기 7번 중 1번 카운트, 스쿼트 바닥의 knee_maxside 66~126° vs 무릎 들기 156~163°). 런지류는
        // 한쪽 무릎만 굽는 종목이라 이 게이트를 붙이지 않는다(knee_minside 카운트 신호가 이미 그 성질이다).
        put("바벨 스쿼트", RepSignal("knee_mean", ANGLE, identityFeature = "knee_maxside", identityMinAmp = ANGLE))
        put("버피 테스트", RepSignal("knee_mean", ANGLE))
        put("크로스 런지", RepSignal("knee_mean", ANGLE))
        put("바벨 런지", RepSignal("knee_minside", ANGLE))
        put("사이드 런지", RepSignal("knee_minside", ANGLE))
        // 앱 '런지'. 카운트 신호를 knee_out_mean(0.10) → knee_mean(35°) 으로 바꿨다 (REP_ENGINE_DESIGN.md §4.1·§10).
        //  - 앞으로 딛는 런지에서 무릎은 옆으로 벌어지지 않아 knee_out_mean 에 반복이 거의 담기지 않는다(좌우가 상쇄된다).
        //    AIHub GT 3D 에서도 사이클 스윙 중앙값 0.047 로 게이트 0.10 을 넘는 사이클이 15% 뿐이었다 — 설문이 고른 신호에
        //    검증 없이 물리 게이트를 붙인 설정이었다. MM-Fit 3D 연속 세트(현재 복귀형 카운터 그대로): 재현율 0.10 → 0.84.
        //    그래도 이 카운터는 §1 수용 기준 밖이다(MM-Fit 세트 정확 일치 0.48, 세트 앞뒤 헛카운트 — 설계 §0·§14). beta 다.
        //  - 양 무릎이 함께 굽으므로 평균 무릎각. 게이트 35° 는 다른 각도형 신호와 같은 모집단 값(잡음 바닥)이고 어느
        //    데이터셋에도 맞추지 않았다. 이 경로(레거시 코어)의 신호는 knee_mean 으로 정했다(knee_minside 는 기존 코어에서 열세).
        //    knee_minside 와의 재비교는 새 코어를 켤 때(Gate A)의 일이다(설계 §4.1·§15 #11).
        //  - 이 카운터의 사이클 하나 = 한 걸음(무릎이 걸음마다 한 번 굽는다 — MM-Fit 라벨의 걸음 수와 같은 단위, 왼·오른 5+5 = 10사이클).
        //    화면의 1회는 사용자 결정(2026-09-24)으로 **왼쪽 + 오른쪽 한 쌍** = 사이클 둘이다 — RepUnitAccumulator 가 묶고(RepUnit.SIDE_PAIR,
        //    spec §59) 목표 도달 자동 진행(§42)도 쌍의 수로 넘어간다. 준비 안내가 그 정의를 밝힌다(ExerciseProfiles, 설계 §4.4·§15 #18).
        //  - 같은 변경에서 ROM(knee_out_mean −0.0076)을 뗐다. 단위가 다른 신호의 기준이라 붙여 두면 거짓 판정이 된다.
        //    knee_mean 의 AIHub 후보(146.6°)는 "조금만 굽혀도 유효" 라 기준 구실을 못 해 쓰지 않는다 → ROM 없음(null).
        //  - TRACK 비교는 기존 기록의 단위(knee_out_mean · 0.10)를 유지한다 — comparisonFeature/comparisonMinAmp.
        put("스텝 포워드 다이나믹 런지", RepSignal("knee_mean", ANGLE, comparisonFeature = "knee_out_mean", comparisonMinAmp = NORM_S))
        put("스텝 백워드 다이나믹 런지", RepSignal("hip_mean", ANGLE))
        put("스탠딩 니업", RepSignal("hip_mean", ANGLE))
        // ---- 팔꿈치 각 계열 (풀업·랫풀·딥스·로우·컬·페이스풀)
        // 덤벨 컬은 카운트 신호를 elbow_mean 으로 유지한다(설계 §4.4·§15 #9). 교대 컬에서는 두 팔 평균이 한 팔 스윙의 절반만 움직여
        // MM-Fit 영상(MediaPipe)에서 이 신호는 거의 세지 못한다(새 코어로도 정확 일치 0.00). 새 코어의 후보는 이 피처 그대로의
        // elbow_minside(한쪽이 가려지면 보이는 쪽 — PostureCore)다: 합성 가림은 이 폴백이 과다 카운트를 낸다고 예측했지만 실제 영상에서는
        // 재현되지 않았고 "양팔 보일 때만" 이 오히려 나빴다(0.77 vs 0.86, 설계 §16.4). 새 코어로도 0.85 라 항상-10(0.92) 아래 —
        // 레거시 경로에 minside 를 줘도 0.03 이라 지금 바꿀 이유가 없고, 새 코어를 켤 때(Gate A) 함께 정한다.
        for (ex in listOf("풀업", "딥스", "바벨 로우", "덤벨 벤트오버 로우", "바벨 컬", "페이스 풀")) {
            put(ex, RepSignal("elbow_mean", ANGLE))
        }
        // 덤벨 컬(spec §62c, 설계 §22, 연구 docs/CURL_RULES_RESEARCH.md): 팔별 신호 elbow_L/elbow_R 로 세고 완료 = min(nL, nR).
        //  - 위의 elbow_mean 은 교대 컬에서 두 팔 스윙의 절반만 움직여 MM-Fit 교대 59세트 재현율 0.09. 팔별 카운터(설계 §18.3)는 세트 정확 0.85~0.90.
        //  - `feature` = elbow_minside 는 반복별 자세 검사(RepFormEvaluator)의 창 분할·비교 지표용 — 카운트는 pairedFeatures 가 한다.
        //  - ROM: 절대 각(81.3°)은 GT 척도(MediaPipe 월드각 수축 시 +33~39° 편향, 정답과 상관 0.04)라 폐기. 그 팔 첫 2사이클 진폭(≥ 50°)의
        //    0.7 이상이고 45° 이상이면 유효 — 정답 반복 94 % 유지(절대 각 69 %). 미달은 '부분' 으로 세지 않는다(romExcludesShort).
        //    기준은 첫 3사이클 중앙값: 첫 사이클 창에 덤벨을 드는 동작이 끼어 진폭이 부풀기 일쑤라(MM-Fit) 첫 2회 중앙값이면 그 뒤 정상 회가 부분이 된다
        //    (재생 2026-09-26: 부분 오탐 41 → 21 / 629 팔 사이클). 셋째 회까지는 판정하지 않는다.
        //    보조(B4, 정면): 2D 손목 높이 창 진폭 ≥ 0.8 × 첫 2회(정답 손실 2 %, 폰 짧은 회 2/2 — 월드 비율 0.7 은 0.87~1.01 로 놓쳤다) 이고 창 최소 ≤ −0.75(다 안 폄, 손실 0.5 %).
        //  - 기각(컬 아님): 그 팔 사이클 구간의 2D 몸통 기울기 비 범위 > 0.30(≈ 24°, 사선 뷰에서만 정의 — 정면이면 판정 안 함), 그 팔 상완 스윙 > 60°.
        //    월드 몸통 기울기(torso_incl 25°)는 정면 MM-Fit 정상 반복에서 45~110° 로 튀어 16회를 기각했다(2026-09-26 재생) — B1 대로 월드는 못 쓴다.
        //    상완 50° 는 정상 반복 4회 기각(50~55°) → 60°(0회). 폰 비컬 구간(몸통 25~36°·상완 91~115°)은 여전히 걸린다.
        put("덤벨 컬", RepSignal("elbow_minside", ANGLE, pairedFeatures = "elbow_L" to "elbow_R",
            rejectFeatures = mapOf(Arm2d.TORSO_TILT to 0.30f, "upperarm_vert_{side}" to 60f),
            romRatio = 0.7f, romAbsMin = 45f, romRefMin = 50f, romRefCycles = 3, romValidated = true, romExcludesShort = true,
            romAuxFeature = "wrist_h2d_{side}", romAuxRatio = 0.8f, romAuxFloor = -0.75f,
            romCue = "덜 올렸거나 덜 내렸어요. 팔꿈치를 끝까지 접었다 펴 주세요",
            romCueTop = "끝까지 올리지 않았어요. 덤벨을 어깨 앞까지 올려 주세요",
            romCueBottom = "팔을 끝까지 펴지 않았어요. 내릴 때 팔꿈치를 다 펴 주세요"))
        put("랫풀 다운", RepSignal("forearm_vert_mean", ANGLE))
        // ---- 전완 수직도 계열 (들어올림)
        for (ex in listOf("사이드 레터럴 레이즈", "프런트 레이즈", "업라이트로우", "덤벨 체스트 플라이", "덤벨 인클라인 체스트 플라이")) {
            put(ex, RepSignal("forearm_vert_mean", ANGLE))
        }
        // ---- 손 높이/거리 계열
        put("오버 헤드 프레스", RepSignal("palm_h_sh", NORM))
        put("케이블 푸시 다운", RepSignal("palm_h_sh", NORM))
        put("라잉 트라이셉스 익스텐션", RepSignal("palm_h_sh", NORM))
        put("덤벨 풀 오버", RepSignal("palm_h_sh", NORM))
        put("로잉머신", RepSignal("palm_fwd_knee", NORM))
        put("행잉 레그 레이즈", RepSignal("hip_below_knee", NORM_S))
        put("케이블 크런치", RepSignal("knee_elbow_dist", NORM))
        // 미등록(신뢰 가능한 앱 가용 신호 없음): 스탠딩 사이드 크런치 — 오카운트보다 미표시가 정직
    }
}

/**
 * 세트 종료 시점에 **세지 않은** 것 — 새 코어 경로만 채운다(설계 §4.2·§4.9).
 * @property unconfirmed 발화했지만 짝(시작 확정)을 못 만난 사이클.
 * @property inProgress 되돌아오는 중이던(ASC) 후보 — 절반만 올라온 동작을 한 회로 만들지 않는다.
 */
data class RepPendingState(val unconfirmed: RepCycle?, val inProgress: RepCandidate?)

/**
 * 반복 판별 게이트가 세지 않은 사이클 (spec §62) — 세트 로그 `reps.rejected`.
 * @property tMs 사이클이 끝난(카운트 신호가 발화한) 프레임 시각. @property identitySwing 그 사이클 창의 판별 신호 스윙(게이트 미만) —
 *   팔별 경로의 기각 게이트(spec §62c)에서는 상한을 **넘은** 기각 피처의 스윙이고 [feature] 가 그 피처다(판별 게이트는 null).
 */
data class RepRejected(val tMs: Long, val min: Float, val max: Float, val identitySwing: Float, val feature: String? = null)

/**
 * 팔별 경로(spec §62c)에서 한 팔이 낸 사이클 — 세트 로그 `reps.arms`. 회로 묶이기 전 원자재라 기각된 회의 사이클도 있다.
 * @property amp 진폭(max − min). @property valid 본인 기준 비율 ROM 판정(기준 전·미확보는 null).
 */
data class ArmCycle(val arm: Char, val tMs: Long, val startMs: Long, val min: Float, val max: Float, val amp: Float, val valid: Boolean?,
                    /** 기각 게이트에 걸린 피처와 스윙(spec §62c) — 걸리지 않았으면 null. 이 사이클이 이루는 회는 세지 않는다. */
                    val rejectFeature: String? = null, val rejectSwing: Float? = null,
                    /** 보조 ROM(2D 손목 높이) 창 진폭·최소 — 표본이 없으면 null. */
                    val auxAmp: Float? = null, val auxMin: Float? = null,
                    /** 짝 없이 버린 조각(같은 팔의 다음 사이클이 반대 팔 사이클과 겹쳤다) — 회가 되지 않았다. */
                    val orphan: Boolean = false,
                    /** ROM 미달 사유 — [valid] 가 false 일 때만. */
                    val romShort: RomShort? = null)

/** 팔별 경로의 ROM 미달 사유(spec §62c 후속 3) — 음성 사유를 고른다. TOP = 덜 올림, BOTTOM = 덜 폄(아래 끝 미도달), RANGE = 어느 끝인지 모름. */
enum class RomShort { TOP, BOTTOM, RANGE }

/** 완료된 렙 하나의 기록 — 사이클 극값과 ROM 판정. 세트 로그에 렙별로 남겨 후반 드리프트(피로)
 *  분석을 오프라인에서 가능하게 한다 (spec §29 — 숙련자 계기판의 원자재). */
data class RepRecord(val tMs: Long, val cycleMin: Float, val cycleMax: Float, val valid: Boolean?,
                     /** 이 사이클의 시작 시각(새 코어·팔별 경로) — 반복 검사 창의 앞 경계(§62c 후속 10). 레거시 경로는 null(창 = 직전 회 끝 이후 전부). */
                     val cycleStartMs: Long? = null)

object RepMetrics {
    /**
     * 렙 간격 **중앙값**(ms) — 기록 모드 템포 표시용. RepCounter.periodMs(지수평활)는 이상 렙
     * 하나(휴식 끼임 등)에 끌려가므로, 표시는 중앙값으로 강건하게. 렙 2개 미만이면 null.
     */
    fun medianPeriodMs(repTimesMs: List<Long>): Long? {
        if (repTimesMs.size < 2) return null
        val gaps = repTimesMs.zipWithNext { a, b -> b - a }.sorted()
        val m = gaps.size
        return if (m % 2 == 1) gaps[m / 2] else (gaps[m / 2 - 1] + gaps[m / 2]) / 2
    }
}
