// tools/sync_ios_core.py 생성본 — Android 정본에서 수정한 뒤 동기화하세요.
package com.example.trex_kotlin.posture

/**
 * 세트 로그의 렙 카운터 구성 (spec §58 단계 0) — 같은 프레임을 오프라인에서 **같은 카운터로** 다시 돌릴 수 있게 남긴다.
 * 폰 검증(Gate A)에서 "그 세트를 어떤 카운터가 셌는가" 를 로그만으로 가를 수 있어야 새 코어를 켜고 끈 전후를 비교할 수 있다.
 *
 * @property engine [ENGINE_RETURN](복귀형 `ReturnRepTracker` — 지금 앱) | [ENGINE_HYSTERESIS](`RepHysteresis` + 시작 확정, 폰 검증 전 비활성).
 * @property polarity "down"|"up". null = 레거시 경로(방향 개념 없음).
 * @property returnFraction·firstPairWindowMs·laterWindowMs·minRatio·maxRatio 새 코어 전용(레거시는 null — 키도 없다).
 *   laterWindowMs 는 새 코어에서도 null 일 수 있다(= 이후 반복은 진폭 비만 본다, 설계 §4.3) — 그때는 JSON null 로 적는다.
 * @property romTier [RepRomTier.key] — 화면·음성이 ROM 을 어떤 확신으로 말했는가.
 */
data class RepEngineLog(
    val engine: String,
    val feature: String,
    val minAmp: Float,
    val refractoryMs: Long,
    val maxGapMs: Long,
    val completeOnReturn: Boolean,
    val polarity: String? = null,
    val returnFraction: Float? = null,
    val firstPairWindowMs: Long? = null,
    val laterWindowMs: Long? = null,
    val minRatio: Float? = null,
    val maxRatio: Float? = null,
    val romDirection: String? = null,
    val romThreshold: Float? = null,
    val romTier: String? = null,
    /** 반복 판별 게이트(spec §62) — `config.identity{feature,min_amp}`. 판별 신호가 없는 종목은 null 이고 키가 없다. */
    val identityFeature: String? = null,
    val identityMinAmp: Float? = null,
    /** 준비 단계에서 심은 서 있는 기준값(§89 후속 2) — `config.seed`. 없으면 키 없음. */
    val seed: Float? = null,
    /** 팔별 경로(spec §62c) — `config.paired{left,right}`·`config.reject{feature:max_swing}`·`config.rom_ratio/rom_abs_min/rom_ref_min`. 아니면 키 없음. */
    val pairedFeatures: Pair<String, String>? = null,
    val rejectFeatures: Map<String, Float> = emptyMap(),
    val romRatio: Float? = null,
    val romAbsMin: Float? = null,
    val romRefMin: Float? = null,
    val romAuxFeature: String? = null,
    val romAuxRatio: Float? = null,
    val romAuxFloor: Float? = null,
    /** 다리 사이클 추적기(§97)의 세트 첫 서 있는 기준 — 재생기가 같은 값을 심는다. JSON 키 `standing`. */
    val standing: Map<String, Float>? = null,
    /** 바닥 반복 계열(§99)의 세트 첫 누운 기준(`FloorCycleTracker.lying`) — 재생기가 같은 값을 심는다(`restoreLying`). JSON 키 `lying`. */
    val lying: Map<String, Float>? = null,
) {
    companion object {
        /** v2 = 방향 복귀 띠(spec §99). v1 로그는 옛 복귀 규칙으로 센 것이라 지금 재생과 파리티가 어긋날 수 있다. */
        const val ENGINE_RETURN = "return_v2"
        const val ENGINE_HYSTERESIS = "hysteresis_v1"

        /**
         * 카운터가 **실제로 쓰는** 구성을 읽어 적는다(`RepCounter.effective*`·코어·시작 확정의 값) — 복사한 상수가 아니라서
         * `forSession` 의 구성이나 코어 기본값이 바뀌어도 로그가 거짓이 되지 않는다. Gate A 재생 파리티가 이 블록에 기댄다.
         */
        fun of(counter: RepCounter): RepEngineLog {
            val s = counter.signal
            val confirm = counter.confirmationConfig
            return RepEngineLog(
                engine = when {
                    counter.legTracker != null -> LegCycleTracker.VERSION
                    counter.floorTracker != null -> FloorCycleTracker.VERSION   // §99 — 누운 기준(lying)·기각 상세는 SetLog 의 바닥 필드
                    counter.usesHysteresis -> ENGINE_HYSTERESIS
                    else -> ENGINE_RETURN
                },
                feature = s.feature,
                minAmp = s.minAmp,
                refractoryMs = counter.effectiveRefractoryMs,
                maxGapMs = counter.effectiveMaxGapMs,
                completeOnReturn = counter.effectiveCompleteOnReturn,
                polarity = s.polarity?.name?.lowercase(),
                returnFraction = counter.returnFraction,
                firstPairWindowMs = confirm?.firstPairWindowMs,
                laterWindowMs = confirm?.laterWindowMs,
                minRatio = confirm?.minRatio,
                maxRatio = confirm?.maxRatio,
                romDirection = s.romDirection,
                romThreshold = s.romThreshold,
                romTier = RepRomTier.of(s).key,
                identityFeature = s.identityFeature,
                identityMinAmp = s.identityFeature?.let { s.identityMinAmp },
                seed = counter.standingSeed,
                standing = counter.legTracker?.standing,
                lying = counter.floorTracker?.lying,
                pairedFeatures = s.pairedFeatures,
                rejectFeatures = s.rejectFeatures,
                romRatio = s.romRatio, romAbsMin = s.romAbsMin, romRefMin = s.romRefMin,
                romAuxFeature = s.romAuxFeature, romAuxRatio = s.romAuxRatio, romAuxFloor = s.romAuxFloor,
            )
        }
    }
}
