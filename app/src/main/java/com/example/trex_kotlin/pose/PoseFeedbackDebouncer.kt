package com.example.trex_kotlin.pose

/**
 * 한 프레임의 흔들림을 음성 경고로 바로 내보내지 않도록 지속시간과 코드별 cooldown을 적용한다.
 */
class PoseFeedbackDebouncer(
    val persistenceMs: Long = 650L,
    val cooldownMs: Long = 5_000L,
) {
    init {
        require(persistenceMs >= 0L) { "persistenceMs cannot be negative" }
        require(cooldownMs >= 0L) { "cooldownMs cannot be negative" }
    }

    private var candidateCode: PoseFeedbackCode? = null
    private var candidateSinceMs = 0L
    private var lastTimestampMs: Long? = null
    private val lastEmittedAt = mutableMapOf<PoseFeedbackCode, Long>()

    /** 조건을 충족해 지금 한 번 안내해야 할 때만 [candidate]를 반환한다. */
    fun update(candidate: PoseFeedback?, timestampMs: Long): PoseFeedback? {
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        val previousTimestamp = lastTimestampMs
        if (previousTimestamp != null && timestampMs < previousTimestamp) reset()
        lastTimestampMs = timestampMs

        val code = candidate?.code
        if (candidate == null || candidate.severity == PoseFeedbackSeverity.INFO) {
            clearCandidate()
            return null
        }
        if (code != candidateCode) {
            candidateCode = code
            candidateSinceMs = timestampMs
            return if (persistenceMs == 0L) emitIfCooledDown(candidate, timestampMs) else null
        }
        if (timestampMs - candidateSinceMs < persistenceMs) return null
        return emitIfCooledDown(candidate, timestampMs)
    }

    fun reset() {
        clearCandidate()
        lastTimestampMs = null
        lastEmittedAt.clear()
    }

    private fun emitIfCooledDown(candidate: PoseFeedback, timestampMs: Long): PoseFeedback? {
        val lastEmitted = lastEmittedAt[candidate.code]
        if (lastEmitted != null && timestampMs - lastEmitted < cooldownMs) return null
        lastEmittedAt[candidate.code] = timestampMs
        return candidate
    }

    private fun clearCandidate() {
        candidateCode = null
        candidateSinceMs = 0L
    }
}
