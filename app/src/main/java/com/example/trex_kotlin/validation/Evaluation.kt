package com.example.trex_kotlin.validation

import kotlin.math.abs

/** 정답은 영상 검토로만 입력한다. 빈 목록은 검토 전과 실제 0회를 구분한다. */
data class TruthRep(val startMs: Long, val endMs: Long, val side: String = "COMMON")
data class TimeSpan(val startMs: Long, val endMs: Long)
data class TruthForm(val startMs: Long, val endMs: Long, val item: String,
    val verdict: String, val side: String = "UNKNOWN", val phase: String = "전체", val note: String = "")
data class Truth(val reviewer: String, val startMs: Long, val endMs: Long,
    val repsReviewed: Boolean, val reps: List<TruthRep>, val forms: List<TruthForm>,
    val predictionExposed: Boolean = false, val holdsReviewed: Boolean = false, val holds: List<TimeSpan> = emptyList()) {
    fun validate(durationMs: Long) {
        require(reviewer.isNotBlank()) { "평가자 코드를 입력해 주세요." }
        require(startMs >= 0 && endMs > startMs && endMs <= durationMs) { "영상 안의 평가 시작·끝을 지정해 주세요." }
        reps.forEach { require(it.startMs >= startMs && it.endMs <= endMs && it.endMs > it.startMs) { "반복 구간이 평가 범위를 벗어났습니다." } }
        require(reps.map { it.endMs to it.side }.distinct().size == reps.size) { "동일한 반복을 중복 입력했습니다." }
        holds.forEach { require(it.startMs >= startMs && it.endMs <= endMs && it.endMs > it.startMs) { "유지 구간이 평가 범위를 벗어났습니다." } }
        require(holds.sortedBy { it.startMs }.zipWithNext().all { (a,b) -> a.endMs <= b.startMs }) { "유지 구간이 겹칩니다." }
        forms.forEach { require(it.startMs >= startMs && it.endMs <= endMs && it.endMs > it.startMs &&
            it.item.isNotBlank() && it.verdict in setOf("OK", "VIOLATION", "UNKNOWN")) { "자세 정답 구간을 확인해 주세요." } }
        forms.groupBy { it.item }.values.forEach { rows ->
            require(rows.sortedBy { it.startMs }.zipWithNext().all { (a,b) -> a.endMs <= b.startMs }) { "같은 항목의 정답 구간이 겹칩니다." }
        }
    }
}
data class PredRep(val timeMs: Long, val side: String = "COMMON")
data class PredForm(val timeMs: Long, val item: String, val verdict: String)
data class RepScore(val truth: Int, val predicted: Int, val matched: Int, val missed: Int,
    val extra: Int, val meanTimingErrorMs: Double?)
data class HoldScore(val truthMs: Long, val predictedMs: Long, val overlapMs: Long, val missedMs: Long, val extraMs: Long)
data class FormScore(val item: String, val normalMs: Long, val errorMs: Long, val falseAlarmMs: Long,
    val detectedErrorMs: Long, val judgedMs: Long, val unknownTruthMs: Long) {
    val coverage: Double? get() = ratio(judgedMs, normalMs + errorMs)
    val falseAlarmRate: Double? get() = ratio(falseAlarmMs, normalMs)
    val sensitivity: Double? get() = ratio(detectedErrorMs, errorMs)
    private fun ratio(a: Long, b: Long) = if (b == 0L) null else a.toDouble()/b
}

object Evaluation {
    fun hold(truth: Truth, predictions: List<TimeSpan>): HoldScore? {
        if(!truth.holdsReviewed) return null
        val p = predictions.map { TimeSpan(maxOf(it.startMs,truth.startMs),minOf(it.endMs,truth.endMs)) }.filter { it.endMs>it.startMs }.sortedBy { it.startMs }
        require(p.zipWithNext().all { (a,b) -> a.endMs<=b.startMs })
        val duration = truth.holds.sumOf { it.endMs-it.startMs }
        val predicted = p.sumOf { it.endMs-it.startMs }
        val overlap = truth.holds.sumOf { g -> p.sumOf { (minOf(g.endMs,it.endMs)-maxOf(g.startMs,it.startMs)).coerceAtLeast(0) } }
        return HoldScore(duration,predicted,overlap,duration-overlap,predicted-overlap)
    }
    /** 순서 보존 최대 매칭 후 총 시각 오차 최소화. 한 예측으로 두 정답을 맞힐 수 없다. */
    fun reps(truth: Truth, predictions: List<PredRep>, toleranceMs: Long = 500, matchSide: Boolean = false): RepScore? {
        require(toleranceMs >= 0)
        if (!truth.repsReviewed) return null
        val gt = truth.reps.sortedBy { it.endMs }
        val pred = predictions.filter { it.timeMs in truth.startMs..truth.endMs }.sortedBy { it.timeMs }
        data class Cell(val n: Int = 0, val cost: Long = 0)
        fun better(a: Cell, b: Cell) = if (a.n > b.n || a.n == b.n && a.cost <= b.cost) a else b
        var previous = Array(pred.size+1) { Cell() }
        gt.forEach { g ->
            val next = Array(pred.size+1) { Cell() }
            pred.forEachIndexed { j, p ->
                var best = better(previous[j+1], next[j])
                val delta = abs(g.endMs-p.timeMs)
                if (delta <= toleranceMs && (!matchSide || g.side == p.side)) {
                    val old = previous[j]
                    best = better(best, Cell(old.n+1, old.cost+delta))
                }
                next[j+1] = best
            }
            previous = next
        }
        val last = previous.last()
        return RepScore(gt.size,pred.size,last.n,gt.size-last.n,pred.size-last.n,
            if(last.n == 0) null else last.cost.toDouble()/last.n)
    }

    /** 고정 표본 간격을 가정하지 않고 정답 구간과 유효 예측 구간의 교집합을 집계한다. */
    fun forms(truth: Truth, predictions: List<PredForm>, maxCarryMs: Long = 250): List<FormScore> {
        require(maxCarryMs > 0)
        return truth.forms.groupBy { it.item }.map { (item, labels) ->
            val samples = predictions.filter { it.item == item }.sortedBy { it.timeMs }
            var normal = 0L; var error = 0L; var falseAlarm = 0L; var detected = 0L; var judged = 0L; var unknown = 0L
            labels.forEach { label ->
                val duration = label.endMs-label.startMs
                when(label.verdict) { "OK" -> normal += duration; "VIOLATION" -> error += duration; else -> unknown += duration }
                if (label.verdict != "UNKNOWN") samples.forEachIndexed { index, p ->
                    val until = minOf(p.timeMs+maxCarryMs, samples.getOrNull(index+1)?.timeMs ?: Long.MAX_VALUE)
                    val overlap = (minOf(until,label.endMs)-maxOf(p.timeMs,label.startMs)).coerceAtLeast(0)
                    if (p.verdict in setOf("OK","VIOLATION")) judged += overlap
                    if (p.verdict == "VIOLATION") {
                        if (label.verdict == "OK") falseAlarm += overlap else detected += overlap
                    }
                }
            }
            FormScore(item,normal,error,falseAlarm,detected,judged,unknown)
        }
    }
}
