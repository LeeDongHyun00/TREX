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
 */
class RepCounter(
    val signal: RepSignal,
    /** 레거시 경로의 불응기. 새 코어 경로는 쓰지 않는다 — 실제 값은 [effectiveRefractoryMs]. */
    val refractoryMs: Long = 1_200L,
    /** 레거시 경로의 끊김 초기화 기준. 새 코어 경로는 쓰지 않는다 — 실제 값은 [effectiveMaxGapMs]. */
    val maxGapMs: Long = Long.MAX_VALUE,
    /** 세션 목표 카운트는 다음 하강 대신 준비 위치 복귀로 완료한다. 기존 재생 패리티는 기본값을 유지한다. */
    val completeOnReturn: Boolean = false,
) {
    private val hysteresis = signal.polarity?.let { RepHysteresis(signal.minAmp, it) }
    private val confirmation = if (hysteresis != null) RepStartConfirmation() else null
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
    }

    /** @return 이 프레임에서 렙이 완료됐으면 true. value=null(가림)·물리범위 밖이면 일시정지. */
    fun onFrame(tMs: Long, value: Float?): Boolean {
        if (hysteresis != null) newlyPublished = emptyList()
        if (value == null || !value.isFinite()) return false
        val plo = signal.plausibleMin
        val phi = signal.plausibleMax
        if ((plo != null && value < plo) || (phi != null && value > phi)) return false
        if (hysteresis != null) return onFrameHysteresis(tMs, value, hysteresis, confirmation!!)

        // 바닥 경로에서는 가림·일시정지 전후를 한 반복으로 이어 세지 않는다.
        if (prevT?.let { tMs - it > maxGapMs || tMs <= it } == true) {
            returnTracker?.resetCycle()
            dirn = 0; ext = Float.NaN; pendingBottom = Float.NaN; rawCount = 0; dtMs = null
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
                    if (lastRepAt > Long.MIN_VALUE / 4) {
                        val p = tMs - lastRepAt
                        periodMs = periodMs?.let { (it + p) / 2 } ?: p
                    }
                    reps++
                    repTimesMs.add(extT)
                    lastCycleMin = pendingBottom
                    lastCycleMax = ext
                    lastRepAt = tMs
                    pendingBottom = Float.NaN              // 카운트된 하단만 소거 — 불응기 기각 시엔 유지
                    fired = true
                }
                dirn = -1
                ext = v; extT = tMs
                if (fired) return true
            }
        }
        return false
    }

    /** 새 코어 경로 — 원값 그대로(평활 없음). 레거시 필드(raw3·dtMs·dirn 등)는 건드리지 않는다. */
    private fun onFrameHysteresis(tMs: Long, value: Float, core: RepHysteresis, confirm: RepStartConfirmation): Boolean {
        val cycle = core.onFrame(tMs, value) ?: return false
        val out = confirm.offer(cycle)
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

    companion object {
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
) {
    /**
     * 비교용 신호 — [comparisonFeature]/[comparisonMinAmp] 가 있으면 그것으로 바꾼 사본, 없으면 자기 자신.
     * 피처가 바뀌면 ROM·물리 범위는 카운트 신호의 단위라 떼어 낸다(다른 피처에 붙이면 의미가 없다).
     */
    fun comparisonSignal(): RepSignal {
        if (comparisonFeature == null && comparisonMinAmp == null) return this
        val f = comparisonFeature ?: feature
        val sameFeature = f == feature
        return copy(
            feature = f, minAmp = comparisonMinAmp ?: minAmp, polarity = null,
            comparisonFeature = null, comparisonMinAmp = null,
            romDirection = if (sameFeature) romDirection else null, romThreshold = if (sameFeature) romThreshold else null,
            romValidated = sameFeature && romValidated, romCue = if (sameFeature) romCue else null,
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
        "덤벨 컬" to Rom("min", 81.3342f, false, null),
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
        put("바벨 스쿼트", RepSignal("knee_mean", ANGLE))
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
        //  - 반복 정의는 MM-Fit 라벨과 같은 '한 걸음 = 1회'(왼·오른 5+5 = 10). 목표 도달 자동 진행(§42)이 이 수로 넘어가므로
        //    준비 안내가 이 정의를 밝힌다(ExerciseProfiles '런지', 설계 §4.4·§15 #18).
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
        for (ex in listOf("풀업", "딥스", "바벨 로우", "덤벨 벤트오버 로우", "바벨 컬", "덤벨 컬", "페이스 풀")) {
            put(ex, RepSignal("elbow_mean", ANGLE))
        }
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

/** 완료된 렙 하나의 기록 — 사이클 극값과 ROM 판정. 세트 로그에 렙별로 남겨 후반 드리프트(피로)
 *  분석을 오프라인에서 가능하게 한다 (spec §29 — 숙련자 계기판의 원자재). */
data class RepRecord(val tMs: Long, val cycleMin: Float, val cycleMax: Float, val valid: Boolean?)

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
