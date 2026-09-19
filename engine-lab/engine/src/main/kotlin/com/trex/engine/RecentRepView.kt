package com.trex.engine

/**
 * 반복 관측의 현재 촬영 방향. 세트 전체 통계와 분리하여 몸을 돌리기 전 방향이 게이트에 남지 않게 한다.
 * 준비 화면에서도 add를 호출할 수 있지만, 반복 횟수기의 준비/동작 상태에는 영향을 주지 않는다.
 * null/UNKNOWN은 모두 방향 미확정이다. 후방과 측면을 정면으로 바꾸지 않고 프로필에 그대로 전달한다.
 */
class RecentRepView(
    private val frameCount: Int = ViewEstimator.MIN_FRAMES,
    private val maxAgeMs: Long = 3_000L,
    private val maxGapMs: Long = 1_500L,
) {
    private data class Sample(val timeMs: Long, val cos: Float, val sin: Float)
    private val samples = ArrayDeque<Sample>()
    private var lastTimeMs: Long? = null

    init {
        require(frameCount > 0)
        require(maxAgeMs > 0 && maxGapMs > 0)
    }

    @Synchronized fun reset() {
        samples.clear()
        lastTimeMs = null
    }

    /** 같은 단조 시계의 시각만 넣는다. 관측 손실/카메라 전환은 호출부에서도 reset한다. */
    @Synchronized fun add(timeMs: Long, features: Map<String, Float>): ViewEstimator.ViewClass? {
        if (timeMs < 0) {
            samples.clear()
            return null
        }
        val previous = lastTimeMs
        if (previous != null && timeMs <= previous) {
            samples.clear() // 오래된 프레임이 단조 시계의 최댓값을 되감게 하지 않는다.
            return null
        }
        if (previous != null && timeMs - previous > maxGapMs) samples.clear()
        lastTimeMs = timeMs
        val c = features[ViewEstimator.FEAT_COS]
        val s = features[ViewEstimator.FEAT_SIN]
        if (c == null || s == null || !c.isFinite() || !s.isFinite()) {
            samples.clear()
            return null
        }

        val current = ViewEstimator.fromMeans(c, s, 1).cls
        if (current == ViewEstimator.ViewClass.UNKNOWN) {
            samples.clear()
            return ViewEstimator.ViewClass.UNKNOWN
        }
        while (samples.isNotEmpty() && timeMs - samples.first().timeMs > maxAgeMs) samples.removeFirst()
        samples.addLast(Sample(timeMs, c, s))
        while (samples.size > frameCount) samples.removeFirst()
        if (samples.size < frameCount) return null

        val meanCos = samples.sumOf { it.cos.toDouble() } / samples.size
        val meanSin = samples.sumOf { it.sin.toDouble() } / samples.size
        val window = ViewEstimator.fromMeans(meanCos.toFloat(), meanSin.toFloat(), samples.size).cls
        // 오래된 창이 아직 정면이어도 현재 프레임이 옆/뒤로 돌아섰으면 통과시키지 않는다.
        return window.takeIf { it == current }
    }
}
