package com.example.trex_kotlin.posture

/**
 * 같은 전체 피처에 한해서 기존 측정 붕괴 범위를 보존한다. 새 프로필의 최소 진폭은 그대로 쓴다.
 * ROM은 호출부가 같은 피처의 규칙에서 찾은 repConfig만 적용한다. 과거 신호의 ROM 임계값이나
 * 검증 상태를 복사하지 않으며, 측별 새 채널로 전체/평균 피처의 기준을 옮기지 않는다.
 */
fun ExerciseRepProfile.withLegacySignalConstraints(
    legacySignal: RepSignal?,
    repConfig: RepRuleConfig?,
): ExerciseRepProfile {
    val common = commonSignal ?: return this
    if (legacySignal == null || common.feature != legacySignal.feature) return this
    // 프로필이 이미 더 좁은 물리 범위를 명시한 경우에도 그 범위를 느슨하게 만들지 않는다.
    val constrained = common.copy(
        plausibleMin = listOfNotNull(common.plausibleMin, legacySignal.plausibleMin).maxOrNull(),
        plausibleMax = listOfNotNull(common.plausibleMax, legacySignal.plausibleMax).minOrNull(),
    )
    val configured = if (repConfig == null) constrained else constrained.copy(
        romDirection = repConfig.direction,
        romThreshold = repConfig.threshold,
        romValidated = false,
        romCue = null,
    )
    return copy(commonSignal = configured)
}
