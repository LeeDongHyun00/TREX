package com.trex.engine

/** 이전 복귀 FSM 회귀 입력을 새 독립 추적기의 공개 경로로 실행한다. */
class TestChannel(signal: RepSignal, maxGapMs: Long = 1500) {
    private val feature = signal.feature
    private val tracker = ExerciseRepTracker(RepMovementPattern.SIMULTANEOUS,commonSignal=signal,maxGapMs=maxGapMs)
    val reps get() = tracker.counts.total
    val repTimesMs get() = tracker.repTimesMs
    val periodMs get() = tracker.periodMs
    val lastCycleMin get() = tracker.events.lastOrNull()?.common?.cycleMin ?: 0f
    fun onFrame(t: Long, v: Float?) = tracker.onFrame(t,if(v == null) emptyMap() else mapOf(feature to v)).isNotEmpty()
    fun reset() = tracker.reset()
    fun resetCycle() = tracker.resetCycle()
    fun onObservationLost() = tracker.onObservationLost()
}
