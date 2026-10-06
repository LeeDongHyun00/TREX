package com.example.trex_kotlin.posture

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
) {
    companion object {
        const val ENGINE_RETURN = "return_v1"
        const val ENGINE_HYSTERESIS = "hysteresis_v1"

        /**
         * 카운터가 **실제로 쓰는** 구성을 읽어 적는다(`RepCounter.effective*`·코어·시작 확정의 값) — 복사한 상수가 아니라서
         * `forSession` 의 구성이나 코어 기본값이 바뀌어도 로그가 거짓이 되지 않는다. Gate A 재생 파리티가 이 블록에 기댄다.
         */
        fun of(counter: RepCounter): RepEngineLog {
            val s = counter.signal
            val confirm = counter.confirmationConfig
            return RepEngineLog(
                engine = if (counter.legTracker != null) LegCycleTracker.VERSION else if (counter.usesHysteresis) ENGINE_HYSTERESIS else ENGINE_RETURN,
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
                pairedFeatures = s.pairedFeatures,
                rejectFeatures = s.rejectFeatures,
                romRatio = s.romRatio, romAbsMin = s.romAbsMin, romRefMin = s.romRefMin,
                romAuxFeature = s.romAuxFeature, romAuxRatio = s.romAuxRatio, romAuxFloor = s.romAuxFloor,
            )
        }
    }
}
