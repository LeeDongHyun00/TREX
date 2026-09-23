package com.example.trex_kotlin.posture

/** 교대 운동에서 오른쪽 동작을 왼쪽의 '초반 정상'과 비교하지 않는다. 관측 기준은 측별로 고정한다. */
class SideMotionComparison(contract: MotionContract, metrics: List<ComparisonMetric>) {
    private val trackers = contract.channels.associate { ch ->
        val suffix=if(ch.side==MotionSide.LEFT) "_L" else "_R"
        val noise=if(ch.travel>1) 8f else .06f
        val selected=metrics.filter { !it.feature.endsWith("_L")&&!it.feature.endsWith("_R")||it.feature.endsWith(suffix) }
        val own=ComparisonMetric(ch.primary,"${label(ch.side)} 동작 범위",if(ch.travel>1) "°" else "정규화 비율",noise)
        ch.side to PostureComparisonTracker(contract.exercise,(listOf(own)+selected).distinctBy { it.feature },
            signal=RepSignal(ch.primary,ch.travel),variantId="${contract.exercise} · ${label(ch.side)}")
    }
    private var selected: MotionSide? = null
    private var revision=0L
    private var lastKey: Pair<MotionSide,Long>? = null
    @Synchronized fun add(t: Long, features: Map<String,Float>, events: List<MotionCompletion>, anchored: Boolean): ComparisonSnapshot {
        for((side,tracker) in trackers) tracker.add(t,features,events.lastOrNull { it.side==side }?.atMs,anchored)
        events.lastOrNull()?.side?.let { selected=it }
        val side=selected ?: return ComparisonSnapshot(message="좌우 동작을 각각 관측하고 있어요")
        val snapshot=trackers[side]?.snapshot ?: return ComparisonSnapshot()
        val key=side to snapshot.revision
        if(lastKey!=key) { lastKey=key; revision++ }
        return snapshot.copy(message="${label(side)} · ${snapshot.message}",revision=revision)
    }
    @Synchronized fun reset() { trackers.values.forEach { it.reset() }; selected=null; lastKey=null; revision++ }
    @Synchronized fun unavailable() { trackers.values.forEach { it.unavailable() } }
    @Synchronized fun report(end: Long, origin: Long) = trackers.values.flatMap { it.report(end,origin) }
    companion object {
        fun applies(c: MotionContract) = !c.bilateral&&!c.visibleSide&&c.channels.size>1
        private fun label(side: MotionSide) = if(side==MotionSide.LEFT) "왼쪽" else "오른쪽"
    }
}
