package com.example.trex_kotlin.posture

import kotlin.math.abs

enum class MotionPhase(val label: String) {
    READY("동작 준비"), OUTBOUND("움직이는 중"), RETURNING("돌아오는 중"),
    REARM("다음 동작 준비"), UNOBSERVABLE("동작 측정 대기"), PAUSED("일시정지"),
}
data class MotionCompletion(val atMs: Long, val observedAtMs: Long, val side: MotionSide, val min: Float, val max: Float,
    val beganAtMs: Long = atMs)

/** 인과적 단일 측 단계 추적. 미래 보간·고정 1.2초 제한·다음 반복의 하강을 사용하지 않는다. */
internal class ChannelPhaseTracker(private val c: MotionChannel, private val point: CountPoint) {
    var phase = MotionPhase.UNOBSERVABLE; private set
    private var previousTime: Long? = null
    private var previousRaw: Float? = null
    private var filtered: Float? = null
    private var anchor: Float? = null
    private var direction = c.direction
    private var extreme = 0f
    private var extremeAt = 0L
    private var supportMin = 0f
    private var supportMax = 0f
    private var low = Float.POSITIVE_INFINITY
    private var high = Float.NEGATIVE_INFINITY
    private var movingSamples = 0
    private var plateauAt: Long? = null
    private var beganAt = 0L

    fun reset() {
        phase = MotionPhase.UNOBSERVABLE; previousTime = null; previousRaw = null; filtered = null
        anchor = null; direction = c.direction; movingSamples = 0; plateauAt = null
        low = Float.POSITIVE_INFINITY; high = Float.NEGATIVE_INFINITY
    }

    fun add(t: Long, features: Map<String, Float>, legacyValue: Float?): MotionCompletion? {
        val raw = features[c.primary]?.takeIf(Float::isFinite)
        val aux = features[c.support]?.takeIf(Float::isFinite)
        if (raw == null || aux == null) { reset(); return null }
        val dt = previousTime?.let { t - it }
        if (dt != null && (dt <= 0 || dt > 450)) { reset(); return null }
        // 한 표본의 좌표 붕괴를 극점으로 삼지 않는다. 검출용의 넓은 속도 경계다.
        if (dt != null && previousRaw?.let { abs(raw - it) > c.travel * 50f * dt / 1000f } == true) {
            reset(); return null
        }
        previousTime = t; previousRaw = raw
        val alpha = if (dt == null) 1f else dt.toFloat() / (dt + 25f)
        val x = filtered?.let { it + alpha * (raw - it) } ?: raw
        filtered = x
        if (anchor == null) {
            anchor = x; extreme = x; extremeAt = t; supportMin = aux; supportMax = aux
            phase = MotionPhase.READY
        }
        legacyValue?.takeIf(Float::isFinite)?.let { low = minOf(low, it); high = maxOf(high, it) }
        supportMin = minOf(supportMin, aux); supportMax = maxOf(supportMax, aux)
        if (c.reanchorReady && phase == MotionPhase.READY && direction != 0 && (x - anchor!!) * direction < -c.travel * .15f) {
            // 이미 선 채로 시작한 데드리프트 등: 출발 쪽으로 이동하는 동안 시작점을 갱신한다.
            anchor = x; extreme = x; supportMin = aux; supportMax = aux
            low = legacyValue ?: Float.POSITIVE_INFINITY; high = legacyValue ?: Float.NEGATIVE_INFINITY
        }
        val origin = anchor!!
        if (direction == 0 && abs(x - origin) >= c.travel) direction = if (x > origin) 1 else -1
        val away = (x - origin) * direction
        if (phase == MotionPhase.READY) {
            if (away < c.travel) return null
            phase = MotionPhase.OUTBOUND; beganAt = t; extreme = x; extremeAt = t; movingSamples = 1
        }
        movingSamples++
        val extent = (extreme - origin) * direction
        if (away > extent) { extreme = x; extremeAt = t; plateauAt = null }
        val reversal = (extreme - x) * direction >= c.travel * .12f
        val nearExtreme = abs(extreme - x) <= c.travel * .06f
        if (nearExtreme) { if (plateauAt == null) plateauAt = t } else plateauAt = null
        val supported = supportMax - supportMin >= c.supportTravel && movingSamples >= 3
        if (phase == MotionPhase.OUTBOUND && (reversal || plateauAt?.let { t - it >= 120 } == true)) {
            if (point == CountPoint.TURN && supported) {
                phase = MotionPhase.REARM
                return completion(t, extremeAt)
            }
            if (reversal) phase = MotionPhase.RETURNING
        }
        // 복귀 위치를 실제 관측하면 즉시 확정. 한 프레임에서 다음 주기로 넘어가도 중복하지 않는다.
        val returned = away <= c.travel * .18f ||
            ((raw - origin) * direction <= c.travel * .18f && away <= maxOf(c.travel * .5f, extent * .15f))
        if (phase in setOf(MotionPhase.RETURNING, MotionPhase.REARM) && returned) {
            val result = if (phase == MotionPhase.RETURNING && supported) completion(t, t) else null
            anchor = raw; filtered = raw; extreme = raw; extremeAt = t; supportMin = aux; supportMax = aux
            direction = c.direction; phase = MotionPhase.READY; movingSamples = 0; plateauAt = null
            low = legacyValue ?: Float.POSITIVE_INFINITY; high = legacyValue ?: Float.NEGATIVE_INFINITY
            return result
        }
        return null
    }

    private fun completion(t: Long, observed: Long) = MotionCompletion(t, observed, c.side,
        low.takeIf(Float::isFinite) ?: Float.NaN, high.takeIf(Float::isFinite) ?: Float.NaN, beganAt)
}

/** 좌우 사건을 합치는 계층. 가림 시 임의 보충하지 않으며, 완료된 횟수는 사이클 초기화와 분리한다. */
class MotionRepCounter(val contract: MotionContract, val signal: RepSignal) {
    private val trackers = contract.channels.map { ChannelPhaseTracker(it, contract.countPoint) }
    private val pending = HashMap<Int, MotionCompletion>()
    private var selected: Int? = null
    var reps = 0; private set
    val repTimesMs = ArrayList<Long>()
    var lastCompletion: MotionCompletion? = null; private set
    var phase = MotionPhase.UNOBSERVABLE; private set
    val lastCycleMin get() = lastCompletion?.min ?: Float.NaN
    val lastCycleMax get() = lastCompletion?.max ?: Float.NaN
    val periodMs get() = RepMetrics.medianPeriodMs(repTimesMs)
    var unknownCycles = 0; private set

    @Synchronized fun resetCycle() {
        trackers.forEach { it.reset() }; pending.clear(); selected = null; phase = MotionPhase.UNOBSERVABLE
    }

    @Synchronized fun onFrame(t: Long, features: Map<String, Float>): List<MotionCompletion> {
        if (contract.hold) return emptyList()
        val usable = contract.channels.indices.filter { i ->
            val c = contract.channels[i]
            features[c.primary]?.isFinite() == true && features[c.support]?.isFinite() == true
        }
        if (usable.isEmpty() || contract.bilateral && usable.size != trackers.size) {
            if (phase in setOf(MotionPhase.OUTBOUND, MotionPhase.RETURNING)) unknownCycles++
            resetCycle(); return emptyList()
        }
        val active = if (contract.visibleSide) {
            if (selected != null && selected !in usable) { resetCycle(); return emptyList() }
            if (selected == null) selected = usable.maxBy { i ->
                val c=contract.channels[i]
                minOf(features[c.primary+"_quality"] ?: 0f,features[c.support+"_quality"] ?: 0f)
            }
            listOf(selected!!)
        } else contract.channels.indices.toList()
        val found = ArrayList<MotionCompletion>()
        for (i in active) {
            val event = trackers[i].add(t, features, features[signal.feature]) ?: continue
            if (contract.bilateral) pending[i] = event else found += event
            if (contract.singleLegCycle) break // 런지의 뒷다리 굽힘을 별도 1회로 중복하지 않는다.
        }
        pending.entries.removeAll { t - it.value.atMs > 500 }
        if (contract.bilateral && pending.size == trackers.size) {
            val xs = pending.values.toList()
            // 서로 다른 왕복의 왼쪽 완료/오른쪽 완료를 우연히 한 쌍으로 합치지 않는다.
            val syncMs = periodMs?.let { (it * .35f).toLong().coerceIn(120,350) } ?: 350L
            if (xs.maxOf { it.beganAtMs } - xs.minOf { it.beganAtMs } <= syncMs)
                found += MotionCompletion(t, xs.maxOf { it.observedAtMs }, MotionSide.BOTH,
                    xs.minOf { it.min }, xs.maxOf { it.max },xs.minOf { it.beganAtMs })
            else unknownCycles++
            pending.clear()
        }
        phase = active.map { trackers[it].phase }.firstOrNull { it in setOf(MotionPhase.OUTBOUND, MotionPhase.RETURNING) }
            ?: trackers[active.first()].phase
        if (contract.singleLegCycle && found.isNotEmpty()) resetCycle()
        for (event in found) { reps++; repTimesMs += event.atMs; lastCompletion = event }
        return found
    }

    companion object {
        fun forExercise(exercise: String, rules: List<PostureRule> = emptyList()): MotionRepCounter? {
            val contract = MotionContracts.forExercise(exercise)?.takeUnless { it.hold } ?: return null
            val old = RepSignals.byExercise[exercise] ?: RepSignal(contract.channels.first().primary, contract.channels.first().travel)
            val rule = rules.firstOrNull { it.exercise == exercise && it.kind == "rep" && it.status != RuleStatus.EXCLUDE }
            // 종목의 다른 피처 임계값을 새 주신호에 이식하지 않는다. 원래 피처 극값으로만 ROM을 참고한다.
            val signal = if (rule?.repConfig != null) old.copy(feature = rule.baseFeature,
                romDirection = rule.repConfig.direction, romThreshold = rule.repConfig.threshold, romValidated = false)
            else if (exercise in FloorTemporal.exercises || old.feature.startsWith("knee_out") ||
                (!contract.bilateral && !contract.visibleSide && !contract.singleLegCycle && old.feature.endsWith("_mean")))
                old.copy(romDirection = null, romThreshold = null, romValidated = false)
            else old
            return MotionRepCounter(contract, signal)
        }
    }
}
