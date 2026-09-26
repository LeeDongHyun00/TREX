package com.example.trex_kotlin.posture

/**
 * 반복의 방향 — 휴식 자세가 신호의 어느 끝에 있는가 (docs/REP_ENGINE_DESIGN.md §4.2).
 *
 * DOWN = 휴식이 큰 값이고 반복이 값을 내렸다가 돌아온다(스쿼트 무릎각, 컬 팔꿈치각, 푸시업 손목-어깨 거리).
 * UP   = 휴식이 작은 값이고 반복이 값을 올렸다가 돌아온다. 코어는 부호를 뒤집은 신호에 같은 규칙을 쓴다.
 *        아직 어느 종목에도 쓰지 않는다 — 검증 데이터가 있는 세 종목은 모두 DOWN 이다.
 *
 * 코어는 "휴식 = 신호의 휴식 쪽 끝" 을 가정한다. 방향을 틀리게 주면 마지막 반복이 복귀 대신 다음 하강을
 * 기다리게 되므로, 방향은 종목별로 명시하고(`RepSignal.polarity`) 없으면 새 코어를 쓰지 않는다.
 */
enum class RepPolarity { DOWN, UP }

/**
 * 발화한 사이클 하나. 값은 전부 **원래 신호 단위의 원값**(평활하지 않음)이다.
 *
 * @property tMs 이 사이클이 발화한 프레임 시각. 시작 확정으로 두 사이클이 한 프레임에 함께 발표돼도 각자의 발화 시각을 갖는다.
 * @property startMs 하강(반복)이 시작된 프레임 시각.
 * @property min 사이클 최저값(DOWN 이면 바닥, UP 이면 휴식 기준).
 * @property max 사이클 최고값(DOWN 이면 하강 직전의 휴식 기준, UP 이면 꼭대기).
 * @property byRedescent 복귀 지점에 닿기 전에 다시 내려가서 발화했다(불완전 복귀 폴백).
 */
data class RepCycle(val tMs: Long, val startMs: Long, val min: Float, val max: Float, val byRedescent: Boolean = false) {
    /** 사이클 진폭(휴식 기준 − 바닥). 시작 확정의 진폭 비 비교에 쓴다. */
    val amplitude: Float get() = max - min
}

/** 시작 확정의 구성값(설계 §4.3) — 첫 쌍 창, 이후 반복의 시간 창(null = 진폭 비만), 진폭 비 범위. */
data class RepConfirmationConfig(val firstPairWindowMs: Long, val laterWindowMs: Long?, val minRatio: Float, val maxRatio: Float)

/** 세트가 끝날 때 아직 되돌아오는 중이던 사이클(ASC 후보). 세지 않고 로그에만 남긴다 (설계 §4.2). */
data class RepCandidate(val startMs: Long, val min: Float, val max: Float)

/**
 * 복귀 히스테리시스 카운터 코어 (docs/REP_ENGINE_DESIGN.md §4.2). 한 반복 = 휴식 → 하강 → 반전 → 복귀.
 *
 * 현재 라이브 추적기(`ReturnRepTracker`)가 반복을 놓친 네 원인 중 셋을 구조에서 뺐다(설계 §2):
 * 준비 자세를 미리 요구하지 않고(첫 프레임부터 센다), 상단에 머물 것을 요구하지 않고(복귀 지점을 지나는 순간 발화),
 * 평활하지 않는다(3점 중앙값이 한 샘플짜리 바닥을 지웠다). 신호 선택(런지)은 코어가 아니라 `RepSignals` 의 몫이다.
 *
 * 규약 (DOWN 기준. UP 은 부호를 뒤집은 값에 같은 규칙):
 * ```
 * REST : r = 마지막 발화 이후 최댓값. v ≤ r − h 이면 DESC, r0 = r
 * DESC : m = 최솟값. v ≥ m + h 이면 ASC(반전 확정 = 후보), a = v
 * ASC  : v ≥ m + (1 − f)(r0 − m) 이면 발화 → REST(r = v)
 *        v ≤ a − h (복귀 전 재하강) 이면 발화 → 곧바로 DESC(r0 = a) — 다음 사이클이 시작됐다는 증거
 *        그 외 a = max(a, v)
 * 발화가 직전 발화와 refractory 미만이면 잡음으로 버린다(상태 전이는 그대로 한다).
 * 프레임 간격이 maxGap 을 넘으면 진행 중 사이클만 버린다 — 확정된 수는 호출 쪽(RepCounter)이 지킨다.
 * ```
 * 상수의 출처 (설계 §4.2·§12): h 는 종목 신호의 물리 게이트(각도 35° 는 플랭크 잡음 바닥 실측), f = 0.25·refractory 0.8 s 는
 * 설계값, maxGap 1.5 s 는 현재 앱과 같다 — 이 넷은 어떤 데이터셋의 결과를 보고 조정하지 않았다. 반면 **평활 없음**은
 * MM-Fit 재현율을 보고 골랐고(3점 중앙값이 스쿼트 0.997 → 0.975, 런지 0.990 → 0.938), 시작 확정 창([RepStartConfirmation])도
 * MM-Fit 배터리에서 골랐다. MM-Fit 은 봉인 데이터가 아니라 개발 데이터라(설계 §12) 이 선택들의 성능은 휴대폰 Gate B 에서만 말한다.
 * 개인·하위집단에 맞춘 값은 없다(원칙 #4).
 * 반전 프레임(DESC → ASC)에서는 발화를 검사하지 않는다 — 연구 프로토타입(`prototype_counter.py`)과 같은 규약이며
 * 재생 파리티가 이 동일성에 기대고 있다.
 *
 * 이 클래스는 "움직임이 반복 모양이었다" 만 말한다. 올바른 자세라는 판정이 아니고, 첫 사이클을 반복으로 인정할지는
 * [RepStartConfirmation] 이 정한다. 안드로이드 의존이 없어 JVM 재생기(`research/external_rep_replay/replay-jvm`)가 그대로 컴파일한다.
 */
class RepHysteresis(
    /** h — 최소 스윙(신호 단위). */
    val minAmp: Float,
    val polarity: RepPolarity = RepPolarity.DOWN,
    /** f — 복귀 잔여 비율. 휴식 기준까지 f 만큼 남긴 지점에서 발화한다. */
    val returnFraction: Float = DEFAULT_RETURN_FRACTION,
    val refractoryMs: Long = DEFAULT_REFRACTORY_MS,
    val maxGapMs: Long = DEFAULT_MAX_GAP_MS,
) {
    enum class Phase { REST, DESC, ASC }

    var phase: Phase = Phase.REST
        private set

    /** 불응기에 걸려 버린 발화 수 — 로그용(설계 §4.9). */
    var refractoryDrops: Int = 0
        private set

    // 아래 값은 전부 코어 방향(DOWN 이면 원값, UP 이면 부호 반전값)이다.
    private var rest = Float.NaN          // r
    private var cycleTop = Float.NaN      // r0
    private var bottom = Float.NaN        // m
    private var ascMax = Float.NaN        // a
    private var descentAt = 0L
    private var lastT: Long? = null
    private var lastFireAt: Long? = null

    private val sign = if (polarity == RepPolarity.DOWN) 1f else -1f

    /** 진행 중 사이클만 버린다(일시정지·카메라 전환·끊김). 불응기 시계는 유지한다. */
    fun resetCycle() {
        phase = Phase.REST
        rest = Float.NaN; cycleTop = Float.NaN; bottom = Float.NaN; ascMax = Float.NaN
    }

    /** 새 세션 — 시계까지 비운다. */
    fun reset() {
        resetCycle()
        lastT = null; lastFireAt = null; refractoryDrops = 0
    }

    /** 세트 종료 시 되돌아오는 중이던 후보. ASC 가 아니면 null. 세지 않는다. */
    fun candidate(): RepCandidate? = if (phase != Phase.ASC) null else toCandidate()

    /**
     * @param value 유한한 원값(가림·물리 범위 밖은 호출 쪽이 걸러 이 함수를 부르지 않는다 = 일시정지).
     * @return 이 프레임에서 발화해 불응기를 통과한 사이클, 아니면 null.
     */
    fun onFrame(tMs: Long, value: Float): RepCycle? {
        val prev = lastT
        if (prev != null && (tMs - prev > maxGapMs || tMs <= prev)) {
            // 가림·일시정지 뒤의 값을 이전 사이클에 잇지 않는다. 시계가 거꾸로 가면 불응기 기준도 무의미하다.
            resetCycle()
            if (tMs <= prev) lastFireAt = null
        }
        lastT = tMs
        val v = sign * value
        if (rest.isNaN()) rest = v
        when (phase) {
            Phase.REST -> {
                rest = maxOf(rest, v)
                if (v <= rest - minAmp) startDescent(tMs, top = rest, v = v)
                return null
            }
            Phase.DESC -> {
                if (v < bottom) bottom = v
                else if (v >= bottom + minAmp) { phase = Phase.ASC; ascMax = v }
                return null
            }
            Phase.ASC -> {
                val returned = v >= bottom + (1f - returnFraction) * (cycleTop - bottom)
                val redescent = !returned && v <= ascMax - minAmp
                if (!returned && !redescent) {
                    ascMax = maxOf(ascMax, v)
                    return null
                }
                val cycle = toCycle(tMs, byRedescent = redescent)
                val fireOk = lastFireAt?.let { tMs - it >= refractoryMs } ?: true
                if (fireOk) lastFireAt = tMs else refractoryDrops++
                // 다음 사이클 준비: 이미 다시 내려가는 중이면 DESC 로, 아니면 REST 로.
                // (복귀 지점을 넘었어도 a − h 아래면 DESC — 프로토타입과 같은 판정 순서)
                if (v <= ascMax - minAmp) {
                    val top = ascMax
                    rest = top
                    startDescent(tMs, top = top, v = v)
                } else {
                    phase = Phase.REST
                    rest = v
                }
                return if (fireOk) cycle else null
            }
        }
    }

    private fun startDescent(tMs: Long, top: Float, v: Float) {
        phase = Phase.DESC
        cycleTop = top
        bottom = v
        descentAt = tMs
    }

    private fun toCycle(tMs: Long, byRedescent: Boolean): RepCycle =
        if (polarity == RepPolarity.DOWN) RepCycle(tMs, descentAt, bottom, cycleTop, byRedescent)
        else RepCycle(tMs, descentAt, -cycleTop, -bottom, byRedescent)

    private fun toCandidate(): RepCandidate =
        if (polarity == RepPolarity.DOWN) RepCandidate(descentAt, bottom, cycleTop)
        else RepCandidate(descentAt, -cycleTop, -bottom)

    companion object {
        const val DEFAULT_RETURN_FRACTION = 0.25f
        const val DEFAULT_REFRACTORY_MS = 800L
        const val DEFAULT_MAX_GAP_MS = 1_500L
    }
}

/**
 * 세트 시작 확정과 진폭 일관성 (docs/REP_ENGINE_DESIGN.md §4.3).
 *
 * 준비 자세(`ReturnRepTracker` 의 앵커)로 시작 전 오염을 막던 것을 **사이클 두 개의 일관성**으로 바꾼다:
 * - 첫 사이클은 보류한다. 진폭 비가 [minRatio]~[maxRatio] 인 두 번째 사이클이 [firstPairWindowMs] 안에 오면
 *   둘을 함께 발표한다(HUD 0 → 2). 오지 않으면 버리고 [dropped] 에 남긴다.
 * - 이후 사이클은 직전 발표 사이클과 진폭 비만 맞으면 발표한다([laterWindowMs] = null, 기본).
 *   맞지 않는 사이클은 다시 보류되어 **바로 다음 사이클**과 짝이 맞으면 둘이 함께 발표되고(새 진폭 기준),
 *   아니면 버려진다 — 고립된 한 번의 큰 움직임은 반복이 아니다.
 *
 * 창 8초 · 이후 진폭만: 예전 제안(모든 사이클에 4초 창)은 반복당 4.5초 이상이면 한 회도 확정하지 못했다
 * (앱의 스쿼트·런지 페이스 안내가 반복당 4초다). 기본값은 결과를 보기 전에 적은 선택 규칙(설계 §12)을 MM-Fit 스트레스
 * 배터리(설계 §13, 후보 4s/4s·first8+amp·first10+amp·tempo)에 적용해 골랐다 — 기준 넷을 통과한 둘 중 세트 앞 헛카운트가
 * 적은 쪽이다. MM-Fit 은 개발 데이터라 이것은 후보 사이의 선택이지 성능 약속이 아니다.
 * 알려진 비용: 첫 두 발화 간격이 8초를 넘으면 첫 짝이 맺히지 않아 세트가 0회다(반복당 8초 이상, 설계 §4.3·§13.4),
 * 1회짜리 세트는 0회다. 300ms 샘플에서 반복당 8초는 발화 간격이 7.8~8.1초로 흔들려 창 경계에 걸린다 — 창은 포함(≤)이다.
 * 이 값들을 바꾸면 세트 로그의 `reps.config`(RepEngineLog)가 바뀐 값을 그대로 적는다.
 *
 * 세트 중 리셋([dropPending]): 일시정지·카메라 전환 때 보류 사이클은 버린다 — 리셋 앞의 사이클이 리셋 뒤 첫 사이클과 짝지어
 * 발표되면 사람이 멈췄던 경계를 넘어 +2 를 센다(설계 §4.7). 끊김(maxGap 초과)은 코어가 진행 중 사이클만 버리고 보류는 창이 정한다.
 *
 * 연구 프로토타입(`prototype_counter.py` 의 consistency)과 다른 점 하나: 프로토타입은 거부된 이후 사이클을 보류한 채
 * 다음 사이클이 직전 발표 사이클과 맞으면 보류를 남겨 두어, 더 뒤의 사이클과 짝지어 **발화 순서를 거슬러** 발표할 수 있었다.
 * 여기서는 보류를 바로 다음 사이클 하나로만 해소한다(짝 또는 버림) — 발표 순서가 항상 발화 순서다.
 */
class RepStartConfirmation(
    val firstPairWindowMs: Long = DEFAULT_FIRST_PAIR_WINDOW_MS,
    /** 이후 사이클의 시간 창. null = 진폭 비만 본다(기본). */
    val laterWindowMs: Long? = null,
    val minRatio: Float = 0.5f,
    val maxRatio: Float = 2.0f,
) {
    /** 발화했지만 아직 확정되지 않은 사이클(0 또는 1개). 세지 않는다. */
    var pending: RepCycle? = null
        private set

    /** 발표된(= 세는) 사이클, 발화 순서대로. */
    val published: List<RepCycle> get() = publishedList
    private val publishedList = ArrayList<RepCycle>()

    /** 확정되지 못하고 버려진 사이클 — 로그용(설계 §4.9). 세지 않는다. */
    val dropped: List<RepCycle> get() = droppedList
    private val droppedList = ArrayList<RepCycle>()

    fun reset() {
        pending = null
        publishedList.clear()
        droppedList.clear()
    }

    /** 이 확정 규칙의 구성값 — 세트 로그가 그대로 적는다(RepEngineLog). */
    val config: RepConfirmationConfig get() = RepConfirmationConfig(firstPairWindowMs, laterWindowMs, minRatio, maxRatio)

    /** 보류 사이클을 버린다(있으면 [dropped] 로) — 세트 중 리셋용. 발표된 수는 그대로다. */
    fun dropPending() {
        pending?.let { droppedList += it }
        pending = null
    }

    /** @return 이번 사이클로 새로 발표된 사이클들(0·1·2개, 발화 순서대로). */
    fun offer(cycle: RepCycle): List<RepCycle> {
        val held = pending
        if (held != null && cycle.tMs - held.tMs <= firstPairWindowMs && ratioOk(cycle, held)) {
            pending = null
            publishedList += held
            publishedList += cycle
            return listOf(held, cycle)
        }
        if (held != null) {
            droppedList += held
            pending = null
        }
        val last = publishedList.lastOrNull()
        if (last != null && (laterWindowMs == null || cycle.tMs - last.tMs <= laterWindowMs) && ratioOk(cycle, last)) {
            publishedList += cycle
            return listOf(cycle)
        }
        pending = cycle
        return emptyList()
    }

    private fun ratioOk(cycle: RepCycle, reference: RepCycle): Boolean {
        val ratio = cycle.amplitude / maxOf(reference.amplitude, 1e-6f)
        return ratio in minRatio..maxRatio
    }

    companion object {
        const val DEFAULT_FIRST_PAIR_WINDOW_MS = 8_000L
    }
}
